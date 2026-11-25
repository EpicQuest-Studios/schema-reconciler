import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Decision, DesiredState, DifferenceType,
        IllegalArgumentError, LockLevel, MultiNamespaceReconciler,
        ObjectId, ObjectKind,
        PlanningException, ApplyException, PolicyViolationException,
        ReconcileOutcome, ReconcilePolicy,
        ReconcilePlan, SchemaReconciler,
        StalePlanException, ChangeAction, ChangeSafety }
        from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgHookStore } from "../src/PgHookStore.ts";
import { PgTestDataSource, available, execute } from "./PgTestDatabase.ts";

/**
 * End-to-end coverage of the spec 5.5/5.6 features on a live PostgreSQL:
 * the prune policy, JSON plans and applyJson, hazard metadata, concurrent
 * index builds and rename hints — plus 3.7 multi-namespace reconcile and
 * the namespace's own nspacl/nspowner objects (A.5).
 */

const dbUp = await available();
const ds = new PgTestDataSource();
const dialect = new PostgreSqlDialect();

const RUN_ID = process.hrtime.bigint().toString(16);
let sequence = 0;

async function namespace(): Promise<string> {
    const name = "feat_" + RUN_ID + "_" + (++sequence);
    await execute(ds, "CREATE SCHEMA " + dialect.quoteIdentifier(name));
    return name;
}

async function drop(ns: string): Promise<void> {
    try {
        await execute(ds, "DROP SCHEMA " + dialect.quoteIdentifier(ns)
                + " CASCADE");
    } catch { /* cleanup best effort */ }
}

function reconciler(ns: string, script: string,
        policy = ReconcilePolicy.defaults()) {
    return SchemaReconciler.builder().dataSource(ds).namespace(ns)
            .desiredState(DesiredState.ofScript(script)).dialect(dialect)
            .policy(policy);
}

async function rejects(p: Promise<unknown>,
        ctor: new (...a: never[]) => Error): Promise<Error> {
    try {
        await p;
    } catch (e) {
        assert.ok(e instanceof ctor, "expected " + ctor.name + ", got " + e);
        return e;
    }
    assert.fail("expected " + ctor.name);
}

async function tableExists(ns: string, name: string): Promise<boolean> {
    const c = await ds.connect();
    try {
        const rows = await c.query<{ ok: boolean }>(
                "SELECT to_regclass($1) IS NOT NULL AS ok",
                [dialect.quoteIdentifier(ns) + "."
                        + dialect.quoteIdentifier(name)]);
        return rows[0]!.ok;
    } finally {
        await c.close();
    }
}

async function columnExists(ns: string, table: string,
        column: string): Promise<boolean> {
    const c = await ds.connect();
    try {
        const rows = await c.query<{ ok: boolean }>(
                "SELECT EXISTS (SELECT 1 FROM pg_attribute a"
                        + " JOIN pg_class r ON r.oid = a.attrelid"
                        + " JOIN pg_namespace n ON n.oid = r.relnamespace"
                        + " WHERE n.nspname = $1 AND r.relname = $2"
                        + " AND a.attname = $3 AND NOT a.attisdropped) AS ok",
                [ns, table, column]);
        return rows[0]!.ok;
    } finally {
        await c.close();
    }
}

describe("FeatureE2ETest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {

    // ---- 5.5 prune --------------------------------------------------------

    it("prune drops an unexpected table", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".keep (id int); CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".stray (id int)");
            const script = "CREATE TABLE ${namespace}.keep (id int)";
            const policy = ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY);
            const report = await reconciler(ns, script, policy)
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(!(await tableExists(ns, "stray")));
            assert.ok(report.applied.some(c => c.action === ChangeAction.DROP
                    && c.target.kind === ObjectKind.TABLE
                    && c.target.name === "stray"
                    && c.safety === ChangeSafety.DESTRUCTIVE));
        } finally {
            await drop(ns);
        }
    });

    it("prune renders DROP COLUMN for an unexpected column on an"
            + " expected table", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (a int, b text)");
            const script = "CREATE TABLE ${namespace}.t (a int)";
            const policy = ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY);
            const report = await reconciler(ns, script, policy)
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(!(await columnExists(ns, "t", "b")));
            const drop = report.applied.find(c => c.action === ChangeAction.DROP
                    && c.target.kind === ObjectKind.COLUMN);
            assert.ok(drop !== undefined);
            assert.ok(drop.statements[0]!.includes("DROP COLUMN"),
                    String(drop.statements[0]));
        } finally {
            await drop(ns);
        }
    });

    it("without prune an unexpected table is only reported", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".stray (id int)");
            const script = "CREATE TABLE ${namespace}.keep (id int)";
            const report = await reconciler(ns, script).build().reconcile();
            assert.ok(await tableExists(ns, "stray"));
            assert.ok(report.unexpected.some(d => d.id.name === "stray"));
        } finally {
            await drop(ns);
        }
    });

    // ---- machine-readable plans ---------------------------------------------

    it("toJson/fromJson round-trips and applyJson applies", async () => {
        const ns = await namespace();
        try {
            const script = "CREATE TABLE ${namespace}.t (id int)";
            const plan = await reconciler(ns, script).build().plan();
            const json = plan.toJson();
            const restored = ReconcilePlan.fromJson(json);
            assert.equal(restored.toJson(), json);
            const report = await reconciler(ns, script).build()
                    .applyJson(json);
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await tableExists(ns, "t"));
        } finally {
            await drop(ns);
        }
    });

    it("applyJson rejects a stale plan", async () => {
        const ns = await namespace();
        try {
            const script = "CREATE TABLE ${namespace}.t (id int)";
            const plan = await reconciler(ns, script).build().plan();
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".later (id int)");
            await rejects(reconciler(ns, script).build()
                    .applyJson(plan.toJson()), StalePlanException as never);
        } finally {
            await drop(ns);
        }
    });

    it("fromJson rejects malformed input", () => {
        assert.throws(() => ReconcilePlan.fromJson("not json"),
                IllegalArgumentError);
        assert.throws(() => ReconcilePlan.fromJson("{}"),
                IllegalArgumentError);
    });

    // ---- 5.6 concurrent indexes ----------------------------------------------

    it("concurrentIndexes emits CREATE INDEX CONCURRENTLY"
            + " non-transactionally", async () => {
        const ns = await namespace();
        try {
            const script = "CREATE TABLE ${namespace}.t (id int);"
                    + " CREATE INDEX i ON ${namespace}.t (id)";
            const report = await reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const index = report.applied.find(
                    c => c.target.kind === ObjectKind.INDEX);
            assert.ok(index !== undefined);
            assert.equal(index.nonTransactional, true);
            assert.equal(index.hazard.lockLevel,
                    LockLevel.SHARE_UPDATE_EXCLUSIVE);
            assert.ok(index.statements[0]!.includes("INDEX CONCURRENTLY"),
                    String(index.statements[0]));
            assert.equal((await reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("an index on a partitioned parent stays transactional", async () => {
        const ns = await namespace();
        try {
            const script = "CREATE TABLE p (id int) PARTITION BY RANGE (id);"
                    + " CREATE TABLE p1 PARTITION OF p FOR VALUES FROM (1) TO (10);"
                    + " CREATE INDEX i ON p (id)";
            const report = await reconciler(ns, script).concurrentIndexes(true)
                    .build().reconcile();
            const index = report.applied.find(c => c.target.kind === ObjectKind.INDEX);
            assert.ok(index !== undefined);
            assert.equal(index.nonTransactional, false);
            assert.ok(!index.statements[0]!.includes("CONCURRENTLY"));
            assert.equal((await reconciler(ns, script).concurrentIndexes(true)
                    .build().reconcile()).outcome, ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("concurrentIndexes off keeps plain CREATE INDEX", async () => {
        const ns = await namespace();
        try {
            const script = "CREATE TABLE ${namespace}.t (id int);"
                    + " CREATE INDEX i ON ${namespace}.t (id)";
            const report = await reconciler(ns, script).build().reconcile();
            const index = report.applied.find(
                    c => c.target.kind === ObjectKind.INDEX);
            assert.ok(index !== undefined);
            assert.equal(index.nonTransactional, false);
            assert.ok(!index.statements[0]!.includes("CONCURRENTLY"));
        } finally {
            await drop(ns);
        }
    });


    it("an invalid index is detected as drifted and rebuilt concurrently",
            async () => {
        const ns = await namespace();
        try {
            const qNs = dialect.quoteIdentifier(ns);
            await execute(ds, "CREATE TABLE " + qNs + ".t (x int);"
                    + " INSERT INTO " + qNs + ".t VALUES (1), (1)");
            await assert.rejects(() => execute(ds,
                    "CREATE UNIQUE INDEX CONCURRENTLY idx ON " + qNs + ".t (x)"));
            const invalid = await ds.connect();
            try {
                const rows = await invalid.query<{ valid: boolean }>(
                        "SELECT i.indisvalid AS valid FROM pg_index i"
                                + " JOIN pg_class c ON c.oid = i.indexrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = $1 AND c.relname = 'idx'",
                        [ns]);
                assert.equal(rows[0]?.valid, false,
                        "the failed concurrent build must leave an invalid index");
            } finally {
                await invalid.close();
            }
            // make the unique build possible again; the invalid index itself
            // does not enforce anything
            await execute(ds, "DELETE FROM " + qNs + ".t WHERE ctid IN"
                    + " (SELECT ctid FROM " + qNs + ".t LIMIT 1)");

            const script = "CREATE TABLE ${namespace}.t (x int);"
                    + " CREATE UNIQUE INDEX idx ON ${namespace}.t (x)";
            const plan = await reconciler(ns, script)
                    .concurrentIndexes(true).build().plan();
            const difference = plan.differences.find(d =>
                    d.id.kind === ObjectKind.INDEX && d.id.name === "idx");
            assert.ok(difference !== undefined, "index difference missing");
            assert.equal(difference.type, DifferenceType.DRIFTED);

            const report = await reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.applied.some(c => c.target.kind === ObjectKind.INDEX
                    && c.action === ChangeAction.DROP && c.nonTransactional));
            assert.ok(report.applied.some(c => c.target.kind === ObjectKind.INDEX
                    && c.action === ChangeAction.CREATE && c.nonTransactional));
            const valid = await ds.connect();
            try {
                const rows = await valid.query<{ valid: boolean }>(
                        "SELECT i.indisvalid AS valid FROM pg_index i"
                                + " JOIN pg_class c ON c.oid = i.indexrelid"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = $1 AND c.relname = 'idx'",
                        [ns]);
                assert.equal(rows[0]?.valid, true);
            } finally {
                await valid.close();
            }
            assert.equal((await reconciler(ns, script)
                    .concurrentIndexes(true).build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("the session advisory lock survives the concurrent-index commit",
            async () => {
        const ns = await namespace();
        const lockAvailable = async (): Promise<boolean> => {
            const c = await ds.connect();
            try {
                const keySql = "hashtextextended("
                        + "'studio.epicquest.schema.reconciler:'"
                        + " || current_database() || ':' || $1, 0)";
                const tried = await c.query<{ ok: boolean }>(
                        "SELECT pg_try_advisory_lock(" + keySql + ") AS ok",
                        [ns]);
                const available = tried[0]!.ok;
                if (available) {
                    await c.query("SELECT pg_advisory_unlock(" + keySql + ")",
                            [ns]);
                }
                return available;
            } finally {
                await c.close();
            }
        };
        try {
            let availableDuringConcurrentPhase: boolean | undefined;
            const script = "CREATE TABLE ${namespace}.t (id int);"
                    + " CREATE INDEX i ON ${namespace}.t (id)";
            const report = await reconciler(ns, script)
                    .concurrentIndexes(true)
                    .listener({
                        applying: async change => {
                            if (change.target.kind === ObjectKind.INDEX
                                    && change.action === ChangeAction.CREATE
                                    && change.nonTransactional) {
                                availableDuringConcurrentPhase =
                                        await lockAvailable();
                            }
                        },
                    })
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.equal(availableDuringConcurrentPhase, false,
                    "the namespace lock must still be held after the"
                    + " intermediate commit");
            assert.equal(await lockAvailable(), true,
                    "the namespace lock must be released when the run ends");
        } finally {
            await drop(ns);
        }
    });

    // ---- 5.5.1 rename hints ---------------------------------------------------

    it("builder rename turns a drop+create into ALTER ... RENAME",
            async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int)");
            const script = "CREATE TABLE ${namespace}.new (id int)";
            const report = await reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await tableExists(ns, "new"));
            assert.ok(!(await tableExists(ns, "old")));
            assert.ok(report.applied.some(c => c.action === ChangeAction.ALTER
                    && c.statements[0]!.includes("RENAME TO")));
            assert.equal((await reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("a table rename carries its index along", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int); CREATE INDEX i ON "
                    + dialect.quoteIdentifier(ns) + ".old (id)");
            const script = "CREATE TABLE ${namespace}.new (id int);"
                    + " CREATE INDEX i ON ${namespace}.new (id)";
            const report = await reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            // the carried index needed no create of its own — a
            // CREATE INDEX i would collide with the renamed-away index
            assert.ok(!report.applied.some(c => c.action === ChangeAction.CREATE
                    && c.target.kind === ObjectKind.INDEX));
        } finally {
            await drop(ns);
        }
    });

    it("the script rename directive is picked up", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int)");
            const script = "-- @reconciler rename TABLE old -> new\n"
                    + "CREATE TABLE ${namespace}.new (id int)";
            const report = await reconciler(ns, script).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await tableExists(ns, "new"));
        } finally {
            await drop(ns);
        }
    });

    it("a rename with drift converges through the follow-up change",
            async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE VIEW " + dialect.quoteIdentifier(ns)
                    + ".old AS SELECT 1 AS one");
            const script = "CREATE VIEW ${namespace}.new AS SELECT 2 AS two";
            const report = await reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.VIEW, "old"),
                            new ObjectId(ObjectKind.VIEW, "new"))
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.VIEW, "old"),
                            new ObjectId(ObjectKind.VIEW, "new"))
                    .build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("both sides present is a PlanningException", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".old (id int); CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".new (id int, x int)");
            const script = "CREATE TABLE ${namespace}.new (id int, x int)";
            await rejects(reconciler(ns, script)
                    .rename(new ObjectId(ObjectKind.TABLE, "old"),
                            new ObjectId(ObjectKind.TABLE, "new"))
                    .build().reconcile(), PlanningException as never);
        } finally {
            await drop(ns);
        }
    });

    // ---- 3.7 multi-namespace -------------------------------------------------

    it("MultiNamespaceReconciler reconciles two schemas in order with a"
            + " cross-schema foreign key", async () => {
        const nsB = await namespace();
        const nsA = await namespace();
        try {
            const scriptB = "CREATE TABLE ${namespace}.customers"
                    + " (id int PRIMARY KEY)";
            // nsA.orders references nsB.customers — a foreign-schema
            // REFERENCES is a legitimate external dependency (5.8.3)
            const scriptA = "CREATE TABLE ${namespace}.orders"
                    + " (id int PRIMARY KEY, cid int REFERENCES "
                    + dialect.quoteIdentifier(nsB) + ".customers(id))";
            const multi = MultiNamespaceReconciler.builder()
                    .dataSource(ds).dialect(dialect)
                    .namespace(nsB, DesiredState.ofScript(scriptB))
                    .namespace(nsA, DesiredState.ofScript(scriptA))
                    .build();
            const report = await multi.reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.deepEqual([...report.reports.keys()], [nsB, nsA]);
            assert.equal(report.reports.get(nsB)!.outcome,
                    ReconcileOutcome.RECONCILED);
            assert.equal(report.reports.get(nsA)!.outcome,
                    ReconcileOutcome.RECONCILED);
            assert.equal((await multi.reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);

            const plans = await multi.plan();
            assert.deepEqual([...plans.keys()], [nsB, nsA]);
            assert.equal(plans.get(nsB)!.changes.length, 0);
            assert.equal(plans.get(nsA)!.changes.length, 0);

            const dumps = await multi.dump();
            assert.deepEqual([...dumps.keys()], [nsB, nsA]);
            assert.ok(dumps.get(nsB)!.includes(
                    "CREATE TABLE ${namespace}.\"customers\""),
                    dumps.get(nsB)!);
            assert.ok(dumps.get(nsA)!.includes(
                    "CREATE TABLE ${namespace}.\"orders\""),
                    dumps.get(nsA)!);
            // the foreign-schema reference stays literal — only the
            // reconcile unit's own namespace is neutralised
            assert.ok(dumps.get(nsA)!.includes(nsB + "."), dumps.get(nsA)!);
        } finally {
            await drop(nsA);
            await drop(nsB);
        }
    });

    // ---- A.5: the namespace's own acl and owner -------------------------------

    it("GRANT ... ON SCHEMA ${namespace} round-trips through nspacl",
            async () => {
        const ns = await namespace();
        try {
            // the default acl already grants PUBLIC USAGE, so the script
            // also declares CREATE to land a real acl row
            const script = "GRANT USAGE, CREATE ON SCHEMA ${namespace}"
                    + " TO PUBLIC";
            const report = await reconciler(ns, script).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler(ns, script).build().reconcile())
                    .outcome, ReconcileOutcome.UP_TO_DATE);

            const dump = await SchemaReconciler.builder().dataSource(ds)
                    .namespace(ns).dialect(dialect).build().dump();
            assert.ok(dump.includes(
                    "GRANT CREATE ON SCHEMA ${namespace} TO PUBLIC;"),
                    dump);
            assert.ok(dump.includes(
                    "GRANT USAGE ON SCHEMA ${namespace} TO PUBLIC;"),
                    dump);
            assert.equal((await reconciler(ns, dump).build().reconcile())
                    .outcome, ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("a schema-level grant to a named role loads as the namespace"
            + " PRIVILEGES object", async (t) => {
        const role = "feat_grant_" + RUN_ID;
        const c = await ds.connect();
        let ok = false;
        try {
            const rows = await c.query<{ ok: boolean }>(
                    "SELECT rolsuper OR rolcreaterole AS ok FROM pg_roles"
                    + " WHERE rolname = current_user");
            ok = rows.length > 0 && rows[0]!.ok;
            if (ok) {
                await c.execute("DROP ROLE IF EXISTS "
                        + dialect.quoteIdentifier(role));
                await c.execute("CREATE ROLE "
                        + dialect.quoteIdentifier(role));
            }
        } finally {
            await c.close();
        }
        if (!ok) {
            t.skip("connecting role cannot create a grantee role");
            return;
        }
        const ns = await namespace();
        try {
            const script = "GRANT USAGE ON SCHEMA ${namespace} TO "
                    + dialect.quoteIdentifier(role);
            const report = await reconciler(ns, script).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.applied.some(ch =>
                    ch.target.kind === ObjectKind.PRIVILEGES
                            && ch.target.name === "<namespace>"));
            assert.equal((await reconciler(ns, script).build().reconcile())
                    .outcome, ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
            await execute(ds, "DROP ROLE " + dialect.quoteIdentifier(role));
        }
    });

    it("ALTER SCHEMA ${namespace} OWNER TO round-trips through nspowner",
            async (t) => {
        const role = "feat_owner_" + RUN_ID;
        const c = await ds.connect();
        try {
            const rows = await c.query<{ ok: boolean }>(
                    "SELECT rolsuper OR rolcreaterole AS ok FROM pg_roles"
                    + " WHERE rolname = current_user");
            if (rows.length === 0 || !rows[0]!.ok) {
                t.skip("connecting role cannot create a target owner role");
                return;
            }
            const u = await c.query<{ current_user: string }>(
                    "SELECT current_user");
            await c.execute("DROP ROLE IF EXISTS "
                    + dialect.quoteIdentifier(role));
            await c.execute("CREATE ROLE " + dialect.quoteIdentifier(role));
            // ALTER ... OWNER TO needs membership in the target role
            await c.execute("GRANT " + dialect.quoteIdentifier(role)
                    + " TO " + dialect.quoteIdentifier(u[0]!.current_user));
        } finally {
            await c.close();
        }
        const ns = await namespace();
        try {
            const script = "ALTER SCHEMA ${namespace} OWNER TO "
                    + dialect.quoteIdentifier(role);
            const report = await reconciler(ns, script).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.applied.some(ch =>
                    ch.target.kind === ObjectKind.OWNERSHIP
                            && ch.target.name === "<namespace>"
                            && ch.statements[0]!.includes("OWNER TO")));
            assert.equal((await reconciler(ns, script).build().reconcile())
                    .outcome, ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
            await execute(ds, "DROP OWNED BY " + dialect.quoteIdentifier(role)
                    + "; DROP ROLE " + dialect.quoteIdentifier(role));
        }
    });

    it("a table grant to a quoted comma-containing role round-trips",
            async (t) => {
        const role = "rcl,comma_feat_" + RUN_ID;
        const c = await ds.connect();
        try {
            const rows = await c.query<{ ok: boolean }>(
                    "SELECT rolsuper OR rolcreaterole AS ok FROM pg_roles"
                    + " WHERE rolname = current_user");
            if (rows.length === 0 || !rows[0]!.ok) {
                t.skip("connecting role cannot create the grantee role");
                return;
            }
            await c.execute("DROP ROLE IF EXISTS "
                    + dialect.quoteIdentifier(role));
            await c.execute("CREATE ROLE "
                    + dialect.quoteIdentifier(role));
        } finally {
            await c.close();
        }
        const ns = await namespace();
        try {
            const script = "CREATE TABLE ${namespace}.acl_t (id int);"
                    + " GRANT SELECT ON ${namespace}.acl_t TO "
                    + dialect.quoteIdentifier(role);
            assert.equal((await reconciler(ns, script).build().reconcile())
                    .outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler(ns, script).build().reconcile())
                    .outcome, ReconcileOutcome.UP_TO_DATE);

            const dump = await SchemaReconciler.builder().dataSource(ds)
                    .namespace(ns).dialect(dialect).build().dump();
            assert.ok(dump.includes("GRANT SELECT"), dump);
            assert.ok(dump.includes(dialect.quoteIdentifier(role)), dump);
            assert.equal((await reconciler(ns, dump).build().reconcile())
                    .outcome, ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
            await execute(ds, "DROP ROLE " + dialect.quoteIdentifier(role));
        }
    });

    it("managed partitioned indexes reconcile, stay transactional, dump and rename", async () => {
        const ns = await namespace();
        try {
            const script = "CREATE FUNCTION usage_day(timestamptz) RETURNS date"
                    + " LANGUAGE sql IMMUTABLE AS $$ SELECT $1::date $$;"
                    + " CREATE TABLE usage_records (tenant_account_id bigint,"
                    + " created_at timestamptz NOT NULL,"
                    + " CONSTRAINT usage_records_pkey PRIMARY KEY (created_at, tenant_account_id),"
                    + " CONSTRAINT usage_records_tenant_key UNIQUE (tenant_account_id, created_at))"
                    + " PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE usage_records_p2026_11 PARTITION OF usage_records"
                    + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');"
                    + " CREATE INDEX usage_records_tenant_idx ON usage_records (tenant_account_id)"
                    + " WHERE tenant_account_id IS NOT NULL;"
                    + " CREATE UNIQUE INDEX usage_records_tenant_created_uq"
                    + " ON usage_records (tenant_account_id, created_at);"
                    + " CREATE INDEX usage_records_day_idx ON usage_records (usage_day(created_at));"
                    + " COMMENT ON INDEX usage_records_tenant_idx IS 'tenant index'";
            const first = await reconciler(ns, script).concurrentIndexes(true)
                    .build().reconcile();
            assert.equal(first.outcome, ReconcileOutcome.RECONCILED);
            const indexCreates = first.applied.filter(c =>
                    c.target.kind === ObjectKind.INDEX);
            assert.ok(indexCreates.length >= 2, JSON.stringify(first.applied));
            assert.ok(indexCreates.every(c => !c.nonTransactional
                    && !c.statements.some(sql => sql.includes("CONCURRENTLY"))));
            assert.equal((await reconciler(ns, script).build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const dump = await SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .dialect(dialect).build().dump();
            assert.ok(dump.includes("CREATE INDEX usage_records_tenant_idx ON ${namespace}.usage_records"), dump);
            assert.ok(!dump.includes("ON ONLY"), dump);

            const renamed = script.replaceAll("usage_records_tenant_idx",
                    "usage_records_tenant_new_idx")
                    + "\n-- @reconciler rename INDEX usage_records.usage_records_tenant_idx -> usage_records_tenant_new_idx";
            const rename = await reconciler(ns, renamed).build().reconcile();
            assert.equal(rename.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(rename.applied.some(c => c.action === ChangeAction.ALTER
                    && c.target.kind === ObjectKind.INDEX
                            && c.target.name === "usage_records_tenant_new_idx"));
            assert.equal(await tableExists(ns, "usage_records_tenant_new_idx"), true);
            assert.equal((await reconciler(ns, renamed).build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("an invalid ON ONLY partitioned index recovers to valid", async () => {
        const ns = await namespace();
        try {
            const base = "CREATE TABLE usage_records (tenant_account_id bigint,"
                    + " created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE usage_records_p2026_11 PARTITION OF usage_records"
                    + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');";
            const invalid = base + " CREATE INDEX usage_records_tenant_idx"
                    + " ON ONLY usage_records (tenant_account_id)";
            assert.equal((await reconciler(ns, invalid).build().reconcile()).outcome,
                    ReconcileOutcome.RECONCILED);
            const valid = base + " CREATE INDEX usage_records_tenant_idx"
                    + " ON usage_records (tenant_account_id)";
            const recovered = await reconciler(ns, valid).build().reconcile();
            assert.equal(recovered.outcome, ReconcileOutcome.RECONCILED);
            const c = await ds.connect();
            try {
                const rows = await c.query<{ valid: boolean }>(
                        "SELECT i.indisvalid AS valid FROM pg_index i"
                        + " JOIN pg_class x ON x.oid = i.indexrelid"
                        + " JOIN pg_namespace n ON n.oid = x.relnamespace"
                        + " WHERE n.nspname = $1 AND x.relname = 'usage_records_tenant_idx'", [ns]);
                assert.equal(rows[0]!.valid, true);
            } finally {
                await c.close();
            }
            assert.equal((await reconciler(ns, valid).build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("external partitions coexist through reconcile, plan/apply, dump and prune", async () => {
        const ns = await namespace();
        try {
            const base = "CREATE TABLE usage_records (tenant_account_id bigint,"
                    + " created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE usage_records_default PARTITION OF usage_records DEFAULT";
            const build = (script: string, prune = false) => reconciler(ns, script,
                    ReconcilePolicy.defaults().withDestructive(Decision.APPLY).withPrune(prune))
                    .externalPartitions("usage_records", "usage_records_p");
            assert.equal((await build(base).build().reconcile()).outcome,
                    ReconcileOutcome.RECONCILED);
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".usage_records_p2026_11 PARTITION OF " + dialect.quoteIdentifier(ns)
                    + ".usage_records FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')");
            const upToDate = await build(base, true).build().reconcile();
            assert.equal(upToDate.outcome, ReconcileOutcome.UP_TO_DATE);
            assert.equal(upToDate.unexpected.length, 0, JSON.stringify(upToDate.unexpected));

            const withIndex = base + "; CREATE INDEX usage_records_tenant_idx"
                    + " ON usage_records (tenant_account_id)";
            const plan = await build(withIndex).build().plan();
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".usage_records_p2026_12 PARTITION OF " + dialect.quoteIdentifier(ns)
                    + ".usage_records FOR VALUES FROM ('2026-12-01') TO ('2027-01-01')");
            const applied = await build(withIndex).build().apply(plan);
            assert.equal(applied.outcome, ReconcileOutcome.RECONCILED);
            const dump = await SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .dialect(dialect).externalPartitions("usage_records", "usage_records_p")
                    .build().dump();
            assert.ok(!dump.includes("usage_records_p2026_"), dump);
            assert.ok(dump.includes("usage_records_default"), dump);
        } finally {
            await drop(ns);
        }
    });

    it("retire drops a targeted constraint and pruneFilter protects a migration table tree", async () => {
        const ns = await namespace();
        try {
            const q = (name: string) => dialect.quoteIdentifier(ns) + "." + dialect.quoteIdentifier(name);
            await execute(ds, "CREATE TABLE " + q("router_models")
                    + " (id bigint PRIMARY KEY, slug text NOT NULL, tenant_id bigint NOT NULL);"
                    + " ALTER TABLE " + q("router_models")
                    + " ADD CONSTRAINT router_models_slug_key UNIQUE (slug);"
                    + " CREATE TABLE " + q("goose_db_version")
                    + " (id bigserial PRIMARY KEY, version bigint NOT NULL)");
            const script = "CREATE TABLE router_models (id bigint PRIMARY KEY,"
                    + " slug text NOT NULL, tenant_id bigint NOT NULL,"
                    + " CONSTRAINT router_models_global_slug_uq UNIQUE (slug),"
                    + " CONSTRAINT router_models_tenant_slug_uq UNIQUE (tenant_id, slug));"
                    + "\n-- @reconciler retire UNIQUE router_models.router_models_slug_key";
            const report = await reconciler(ns, script).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.applied.some(c => c.action === ChangeAction.DROP
                    && c.target.kind === ObjectKind.UNIQUE
                    && c.target.name === "router_models_slug_key"
                    && c.reason.includes("retired")), JSON.stringify(report.applied));
            assert.equal(await tableExists(ns, "goose_db_version"), true);
            assert.equal((await reconciler(ns, script).build().reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);

            await execute(ds, "ALTER TABLE " + q("router_models")
                    + " ADD CONSTRAINT router_models_obsolete_uq UNIQUE (id, slug)");
            const prunePolicy = ReconcilePolicy.defaults()
                    .withDestructive(Decision.APPLY).withPrune(true);
            const prune = await reconciler(ns, script, prunePolicy)
                    .pruneFilter(id => id.name !== "goose_db_version")
                    .build().reconcile();
            assert.equal(prune.outcome, ReconcileOutcome.RECONCILED);
            assert.equal(await tableExists(ns, "goose_db_version"), true);
            assert.equal(await tableExists(ns, "goose_db_version_id_seq"), true);
            assert.equal(prune.unexpected.some(d => d.id.name === "goose_db_version"), true,
                    "protected objects remain visible as unexpected");
        } finally {
            await drop(ns);
        }
    });

    it("schema hooks order, backfill atomically, roll back, and run once", async () => {
        const ns = await namespace();
        try {
            const q = (name: string) => dialect.quoteIdentifier(ns) + "." + dialect.quoteIdentifier(name);
            await execute(ds, "CREATE TABLE " + q("accounts")
                    + " (id bigint PRIMARY KEY, slug text);"
                    + " INSERT INTO " + q("accounts") + " (id, slug) VALUES (1, NULL)");
            const events: string[] = [];
            const store = PgHookStore.inSchema(ns);
            const script = "CREATE TABLE accounts (id bigint PRIMARY KEY, slug text NOT NULL);"
                    + " CREATE TABLE hook_audit (id int)";
            const hookRun = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript(script)).dialect(dialect)
                    .policy(ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                    .hookStore(store)
                    .hook({ id: "z-post", phase: "POST_COMMIT", order: 2,
                            run: async ctx => { events.push("z:" + ctx.applied.length); } })
                    .hook({ id: "a-pre", phase: "PRE_APPLY", order: 1,
                            run: async ctx => { events.push("a");
                                await ctx.connection.execute("UPDATE accounts SET slug = 'x' WHERE slug IS NULL"); } })
                    .hook({ id: "m-once", phase: "POST_APPLY", order: 0, runOnce: true,
                            checksum: "v1",
                            run: async ctx => { events.push("m");
                                await ctx.connection.execute("INSERT INTO hook_audit (id) VALUES (1)"); } })
                    .build();
            const first = await hookRun.reconcile();
            assert.equal(first.outcome, ReconcileOutcome.RECONCILED);
            assert.deepEqual(events.map(e => e[0]), ["a", "m", "z"]);
            const eventCount = events.length;
            assert.equal((await hookRun.reconcile()).outcome, ReconcileOutcome.UP_TO_DATE);
            assert.equal(events.length, eventCount + 2,
                    "run-once hook skips but always-run hooks still run on an up-to-date apply");
            assert.equal(events.filter(e => e.startsWith("m")).length, 1);
            assert.equal(await tableExists(ns, "schema_reconciler_hooks"), true);
            const changed = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript(script)).dialect(dialect)
                    .hookStore(store)
                    .hook({ id: "m-once", phase: "POST_APPLY", runOnce: true,
                            checksum: "v2", run: () => {} })
                    .build();
            await assert.rejects(() => changed.reconcile(), ApplyException);

            const failing = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript(
                            "CREATE TABLE rollback_probe (id int)"))
                    .dialect(dialect)
                    .hook({ id: "fail", phase: "POST_APPLY", run: async ctx => {
                        await ctx.connection.execute("INSERT INTO rollback_probe (id) VALUES (1)");
                        throw new Error("boom");
                    } })
                    .build();
            await assert.rejects(() => failing.reconcile(), ApplyException);
            assert.equal(await tableExists(ns, "rollback_probe"), false,
                    "POST_APPLY failure rolls back the DDL transaction");
        } finally {
            await drop(ns);
        }
    });
    it("external partitions remain visible when their parent is pruned", async () => {
        const ns = await namespace();
        try {
            const q = (n: string) => dialect.quoteIdentifier(ns) + "." + dialect.quoteIdentifier(n);
            await execute(ds, "CREATE TABLE " + q("usage_records") + " (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE " + q("usage_records_p2026_11") + " PARTITION OF " + q("usage_records")
                    + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')");
            const report = await reconciler(ns, "", ReconcilePolicy.defaults()
                    .withPrune(true).withDestructive(Decision.APPLY))
                    .externalPartitions("usage_records", "usage_records_p").build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const drops = report.applied.filter(c => c.action === ChangeAction.DROP);
            assert.ok(drops.some(c => c.target.kind === ObjectKind.PARTITION
                    && c.target.name === "usage_records_p2026_11"), JSON.stringify(drops));
            assert.ok(drops.some(c => c.target.kind === ObjectKind.TABLE
                    && c.target.name === "usage_records"));
        } finally { await drop(ns); }
    });

    it("retire edge cases cover columns, destructive policy and dependents", async () => {
        const ns = await namespace();
        try {
            const q = (n: string) => dialect.quoteIdentifier(ns) + "." + dialect.quoteIdentifier(n);
            await execute(ds, "CREATE TABLE " + q("t") + " (id int, old text)");
            const column = await reconciler(ns, "CREATE TABLE t (id int)",
                    ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                    .retire(new ObjectId(ObjectKind.COLUMN, "old", "t")).build().reconcile();
            assert.equal(column.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(column.applied.some(c => c.target.kind === ObjectKind.COLUMN
                    && c.safety === ChangeSafety.DESTRUCTIVE
                    && c.statements.some(sql => sql.includes("DROP COLUMN"))));
            assert.equal(await columnExists(ns, "t", "old"), false);

            await execute(ds, "CREATE TABLE " + q("victim") + " (id int)");
            await rejects(reconciler(ns, "CREATE TABLE t (id int)")
                    .retire(new ObjectId(ObjectKind.TABLE, "victim")).build().reconcile(),
                    PolicyViolationException);

            await execute(ds, "CREATE VIEW " + q("dependent") + " AS SELECT id FROM " + q("victim"));
            await rejects(reconciler(ns, "CREATE TABLE t (id int)",
                    ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                    .retire(new ObjectId(ObjectKind.TABLE, "victim")).build().reconcile(),
                    PlanningException);
        } finally { await drop(ns); }
    });

    it("hooks skip reported-only plans and post-commit failures carry applied changes", async () => {
        const ns = await namespace();
        try {
            const events: string[] = [];
            const hook = (phase: "PRE_APPLY" | "POST_APPLY" | "POST_COMMIT") => ({
                    id: phase, phase, run: () => { events.push(phase); } });
            const reported = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript("CREATE TABLE later (id int)"))
                    .dialect(dialect).policy(ReconcilePolicy.reportOnly())
                    .hook(hook("PRE_APPLY")).hook(hook("POST_APPLY")).hook(hook("POST_COMMIT"))
                    .build();
            assert.equal((await reported.reconcile()).outcome, ReconcileOutcome.REPORTED_ONLY);
            assert.deepEqual(events, []);

            const failing = SchemaReconciler.builder().dataSource(ds).namespace(ns)
                    .desiredState(DesiredState.ofScript("CREATE TABLE later (id int)"))
                    .dialect(dialect)
                    .hook({ id: "boom", phase: "POST_COMMIT", run: () => { throw new Error("boom"); } })
                    .build();
            const error = await rejects(failing.reconcile(), ApplyException) as ApplyException;
            assert.ok(error.applied.some(c => c.target.name === "later"),
                    "post-commit context/error carries prior applied changes");
        } finally { await drop(ns); }
    });

    it("PgHookStore works in a dedicated schema and sub-partition indexes stay transactional", async () => {
        const ns = await namespace();
        const internal = "_reconciler_internal_" + RUN_ID + "_" + (++sequence);
        await execute(ds, "CREATE SCHEMA " + dialect.quoteIdentifier(internal));
        try {
            let runs = 0;
            const store = PgHookStore.inSchema(internal);
            const r = reconciler(ns, "CREATE TABLE hooked (id int)")
                    .hookStore(store)
                    .hook({ id: "once", phase: "POST_COMMIT", runOnce: true, checksum: "v1",
                            run: () => { runs++; } }).build();
            assert.equal((await r.reconcile()).outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await r.reconcile()).outcome, ReconcileOutcome.UP_TO_DATE);
            assert.equal(runs, 1);

            const sub = "CREATE TABLE measurement (city_id int, logdate date) PARTITION BY RANGE (logdate);"
                    + " CREATE TABLE measurement_2026 PARTITION OF measurement"
                    + " FOR VALUES FROM ('2026-01-01') TO ('2027-01-01') PARTITION BY LIST (city_id);"
                    + " CREATE TABLE measurement_2026_x PARTITION OF measurement_2026 FOR VALUES IN (1);"
                    + " CREATE INDEX measurement_city_idx ON measurement_2026 (city_id)";
            const applied = await reconciler(ns, sub).concurrentIndexes(true).build().reconcile();
            const idx = applied.applied.filter(c => c.target.kind === ObjectKind.INDEX
                    && c.target.name === "measurement_city_idx");
            assert.ok(idx.length > 0 && idx.every(c => !c.nonTransactional));
        } finally { await drop(ns); await drop(internal); }
    });

    it("MultiNamespaceReconciler forwards 0.11 options", async () => {
        const ns = await namespace();
        try {
            const q = (n: string) => dialect.quoteIdentifier(ns) + "." + dialect.quoteIdentifier(n);
            await execute(ds, "CREATE TABLE " + q("usage_records") + " (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at);"
                    + " CREATE TABLE " + q("usage_records_p2026_11") + " PARTITION OF " + q("usage_records")
                    + " FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');"
                    + " CREATE TABLE " + q("goose_db_version") + " (id int)");
            let runs = 0;
            const multi = MultiNamespaceReconciler.builder().dataSource(ds).dialect(dialect)
                    .policy(ReconcilePolicy.defaults().withPrune(true).withDestructive(Decision.APPLY))
                    .externalPartitions("usage_records", "usage_records_p")
                    .pruneFilter(id => id.name !== "goose_db_version")
                    .hookStore(PgHookStore.inSchema(ns))
                    .namespace(ns, DesiredState.ofScript(
                            "CREATE TABLE usage_records (id bigint, created_at timestamptz)"
                            + " PARTITION BY RANGE (created_at)"),
                            b => { b.hook({ id: "once", phase: "POST_COMMIT", runOnce: true,
                                    checksum: "v1", run: () => { runs++; } }); })
                    .build();
            const report = await multi.reconcile();
            assert.equal(report.reports.get(ns)!.outcome, ReconcileOutcome.UP_TO_DATE);
            assert.equal(await tableExists(ns, "goose_db_version"), true);
            assert.equal(runs, 1);
            assert.equal(await tableExists(ns, "schema_reconciler_hooks"), true);
        } finally { await drop(ns); }
    });

});
