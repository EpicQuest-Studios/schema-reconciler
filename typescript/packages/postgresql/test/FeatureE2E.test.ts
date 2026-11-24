import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Decision, Definition, DesiredState, Difference, DifferenceType,
        IllegalArgumentError, LockLevel, MultiNamespaceReconciler,
        ObjectId, ObjectKind,
        PlanningException, ReconcileOutcome, ReconcilePolicy,
        ReconcilePlan, SchemaObject, SchemaReconciler, SchemaSnapshot,
        StalePlanException, ChangeAction, ChangeSafety }
        from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
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

    it("an index on a partitioned parent stays transactional", () => {
        // PostgreSQL catalogues an index on a partitioned table as an
        // unsupported partindex (relkind 'I'), so the partitioned-parent
        // fallback can only be exercised on a synthetic snapshot
        const tableId = new ObjectId(ObjectKind.TABLE, "p");
        const indexId = new ObjectId(ObjectKind.INDEX, "i", "p");
        const table = new SchemaObject(tableId, new Definition("t"), [],
                new Map([["partkey", "(id)"]]));
        const index = new SchemaObject(indexId, new Definition("i"), [],
                new Map([["createSql", "CREATE INDEX \"i\" ON"
                        + " ${namespace}.\"p\" (\"id\")"]]));
        const desired = SchemaSnapshot.of([[tableId, table],
                [indexId, index]]);
        const changes = dialect.render(new Difference(indexId,
                DifferenceType.MISSING, index.definition, undefined),
                desired, "ns", { concurrentIndexes: true });
        assert.equal(changes.length, 1);
        assert.equal(changes[0]!.nonTransactional, false);
        assert.ok(!changes[0]!.statements[0]!.includes("CONCURRENTLY"),
                String(changes[0]!.statements[0]));
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
});
