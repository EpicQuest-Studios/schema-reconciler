import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it } from "node:test";
import { ApplyException, ConfigurationException, Decision, DesiredState,
        DesiredStateException, DifferenceType, Duration, ObjectId, ObjectKind,
        PlanningException, PolicyViolationException, ReconcileOutcome,
        ReconcilePolicy, SchemaReconciler, SchemaReconcilerException }
        from "@epicquest-studios/schema-reconciler-core";
import type { SqlConnection, SqlDataSource } from "@epicquest-studios/schema-reconciler-core";
import { dialectConformanceSuite } from "@epicquest-studios/schema-reconciler-tck";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestConnection, PgTestDataSource, available, execute, serverMajor }
        from "./PgTestDatabase.ts";

/** Runs the TCK against PostgreSQL. */

let sequence = 0;
const RUN_ID = process.hrtime.bigint().toString(16);
const dialect = new PostgreSqlDialect();
const scratch = new PgTestDataSource();

/** A data source whose connections survive `close()` so session state can
 *  be inspected. */
class Pool implements SqlDataSource {
    private readonly delegate = new PgTestDataSource();
    private readonly issued: { pc: PgTestConnection }[] = [];

    async connect(): Promise<SqlConnection> {
        const pc = (await this.delegate.connect()) as PgTestConnection;
        const entry = { pc };
        this.issued.push(entry);
        const pooled: SqlConnection = {
            metadata: () => pc.metadata(),
            execute: sql => pc.execute(sql),
            query: (sql, params) => pc.query(sql, params),
            getAutoCommit: () => pc.getAutoCommit(),
            setAutoCommit: on => pc.setAutoCommit(on),
            commit: () => pc.commit(),
            rollback: () => pc.rollback(),
            close: () => Promise.resolve(), // pooled-close: keep the session open
        };
        return pooled;
    }

    realConnections(): readonly PgTestConnection[] {
        return this.issued.map(e => e.pc);
    }

    async closeAll(): Promise<void> {
        for (const e of this.issued.splice(0)) {
            try {
                await e.pc.close();
            } catch { /* best effort */ }
        }
    }
}

const pool = new Pool();

const dbUp = await available();

dialectConformanceSuite({
    dataSource: () => pool,
    scratchDataSource: () => scratch,
    dialect: () => dialect,

    async createNamespace() {
        const name = "tck_" + RUN_ID + "_" + (++sequence);
        await execute(scratch, "CREATE SCHEMA " + dialect.quoteIdentifier(name));
        return name;
    },

    async dropNamespace(ns: string) {
        try {
            await execute(scratch, "DROP SCHEMA " + dialect.quoteIdentifier(ns)
                    + " CASCADE");
        } finally {
            await pool.closeAll();
        }
    },

    async assertNoScratchNamespacesLeft() {
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM pg_namespace"
                    + " WHERE nspname LIKE '%\\_\\_rcl\\_%' ESCAPE '\\'");
            assert.equal(Number(rows[0]!.count), 0, "scratch namespaces leaked");
        } finally {
            await c.close();
        }
    },

    async assertCleanSession() {
        for (const c of pool.realConnections()) {
            assert.ok(c.getAutoCommit(),
                    "connection left with autoCommit=false");
            const locks = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM pg_locks"
                    + " WHERE locktype = 'advisory'"
                    + " AND pid = pg_backend_pid()");
            assert.equal(Number(locks[0]!.count), 0, "advisory lock leaked");
            const lt = await c.query<{ lock_timeout: string }>(
                    "SHOW lock_timeout");
            assert.equal(lt[0]!.lock_timeout, "0", "lock_timeout left set");
            const st = await c.query<{ statement_timeout: string }>(
                    "SHOW statement_timeout");
            assert.equal(st[0]!.statement_timeout, "0",
                    "statement_timeout left set");
            const sp = await c.query<{ search_path: string }>(
                    "SHOW search_path");
            assert.ok(!sp[0]!.search_path.includes("rcl"),
                    "search_path left bound to a scratch namespace");
        }
    },

    isScratchBoundary: sql => sql.toUpperCase().includes("SAVEPOINT RCL_SCRATCH")
            || sql.toUpperCase().includes("__RCL_"),
    isScratchEnd: sql => sql.toUpperCase()
            .includes("ROLLBACK TO SAVEPOINT RCL_SCRATCH"),

    unsupportedScript: () => ({
        script: "CREATE TABLE unsupported_base (a int);"
                + " CREATE TABLE unsupported_i (b int)"
                + " INHERITS (unsupported_base);"
                + " CREATE VIEW dep_v AS SELECT * FROM unsupported_i",
        dependentName: "dep_v",
    }),

    unsupportedCategory: () => "inherited",
    columnAlterSupported: () => true,
    identityColumnDdl: () => "bigint GENERATED ALWAYS AS IDENTITY",
    serialColumnDdl: () => "bigserial",

    async extensionDdl() {
        // pick an extension available on this server but not yet installed —
        // CREATE EXTENSION ... IF NOT EXISTS no-ops on an installed one
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ name: string }>(
                    "SELECT a.name FROM pg_available_extensions a"
                    + " WHERE a.name IN ('btree_gist', 'citext', 'hstore',"
                    + " 'pg_trgm', 'unaccent', 'uuid-ossp')"
                    + " AND NOT EXISTS (SELECT 1 FROM pg_extension e"
                    + " WHERE e.extname = a.name) ORDER BY a.name LIMIT 1");
            return rows.length === 0 ? undefined
                    : "CREATE EXTENSION IF NOT EXISTS "
                    + dialect.quoteIdentifier(rows[0]!.name)
                    + " WITH SCHEMA ${namespace}";
        } finally {
            await c.close();
        }
    },

    async foreignServerDdl() {
        // no built-in FDW: postgres_fdw must be installable — superuser only
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ rolsuper: boolean }>(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = current_user");
            if (rows.length === 0 || !rows[0]!.rolsuper) {
                return undefined;
            }
            await c.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw");
            await c.execute("DROP SERVER IF EXISTS tck_srv CASCADE");
            return "CREATE SERVER tck_srv FOREIGN DATA WRAPPER postgres_fdw";
        } finally {
            await c.close();
        }
    },

    foreignServerTeardownDdl: () => "DROP SERVER IF EXISTS tck_srv CASCADE",

    async publicationDdl() {
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ ok: boolean }>(
                    "SELECT has_database_privilege(current_user,"
                    + " current_database(), 'CREATE') AS ok");
            if (rows.length === 0 || !rows[0]!.ok) {
                return undefined;
            }
            await c.execute("DROP PUBLICATION IF EXISTS tck_pub");
            return "CREATE PUBLICATION tck_pub";
        } finally {
            await c.close();
        }
    },

    publicationTeardownDdl: () => "DROP PUBLICATION IF EXISTS tck_pub",

    async ownershipRoleDdl() {
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ ok: boolean }>(
                    "SELECT rolsuper OR rolcreaterole AS ok FROM pg_roles"
                    + " WHERE rolname = current_user");
            if (rows.length === 0 || !rows[0]!.ok) {
                return undefined;
            }
            const u = await c.query<{ current_user: string }>(
                    "SELECT current_user");
            await c.execute("DROP ROLE IF EXISTS tck_owner");
            await c.execute("DROP ROLE IF EXISTS tck_owner2");
            await c.execute("CREATE ROLE tck_owner");
            await c.execute("CREATE ROLE tck_owner2");
            await c.execute("GRANT tck_owner, tck_owner2 TO "
                    + dialect.quoteIdentifier(u[0]!.current_user));
            return "SELECT 1";
        } finally {
            await c.close();
        }
    },

    // per role — a multi-role DROP OWNED BY trips on a default ACL of one
    // listed role granting to another
    ownershipRoleTeardownDdl: () =>
        "DROP OWNED BY tck_owner; DROP OWNED BY tck_owner2;"
        + " DROP ROLE tck_owner; DROP ROLE tck_owner2",
}, { skip: !dbUp ? "no PostgreSQL test database" : false });

// ---- PostgreSQL-specific additions -------------------------------------

describe("PostgreSqlConformanceTest extras", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("a scratch-created extension leaves no pg_extension row behind",
            async (t) => {
        const c = await scratch.connect();
        let ddl: string | undefined;
        try {
            const rows = await c.query<{ name: string }>(
                    "SELECT a.name FROM pg_available_extensions a"
                    + " WHERE a.name NOT IN (SELECT extname FROM pg_extension)"
                    + " ORDER BY a.name LIMIT 1");
            ddl = rows.length === 0 ? undefined
                    : "CREATE EXTENSION IF NOT EXISTS "
                    + dialect.quoteIdentifier(rows[0]!.name)
                    + " WITH SCHEMA ${namespace}";
        } finally {
            await c.close();
        }
        if (ddl === undefined) {
            t.skip("no free extension on this server");
            return;
        }
        const ns = "tck_" + RUN_ID + "_ext" + (++sequence);
        await execute(scratch, "CREATE SCHEMA " + dialect.quoteIdentifier(ns));
        try {
            const report = await SchemaReconciler.builder().dataSource(scratch)
                    .namespace(ns).desiredState(DesiredState.ofScript(ddl))
                    .dialect(dialect).build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const q = await scratch.connect();
            try {
                const rows = await q.query(
                        "SELECT e.extname, n.nspname FROM pg_extension e"
                        + " JOIN pg_namespace n ON n.oid = e.extnamespace"
                        + " WHERE n.nspname LIKE '%\\_\\_rcl\\_%' ESCAPE '\\'");
                assert.equal(rows.length, 0,
                        "extension anchored to a scratch namespace leaked");
            } finally {
                await q.close();
            }
        } finally {
            await execute(scratch, "DROP SCHEMA " + dialect.quoteIdentifier(ns)
                    + " CASCADE");
            await pool.closeAll();
        }
    });
});

// ---- PostgreSQL-specific conformance extras (mirroring the Java
//      PostgreSqlConformanceTest methods beyond the shared TCK) ------------

/** Wraps a data source and records every statement text it executes. */
class TrackingDataSource implements SqlDataSource {
    readonly executedSql: string[] = [];
    private readonly delegate: SqlDataSource;

    constructor(delegate: SqlDataSource) {
        this.delegate = delegate;
    }

    async connect(): Promise<SqlConnection> {
        const c = await this.delegate.connect();
        const sql = this.executedSql;
        return {
            metadata: () => c.metadata(),
            execute: (s: string) => {
                sql.push(s);
                return c.execute(s);
            },
            query: (s: string, params?: readonly unknown[]) => {
                sql.push(s);
                return c.query(s, params);
            },
            getAutoCommit: () => c.getAutoCommit(),
            setAutoCommit: on => c.setAutoCommit(on),
            commit: () => c.commit(),
            rollback: () => c.rollback(),
            close: () => c.close(),
        };
    }
}

async function rejects<T extends Error>(run: () => Promise<unknown>): Promise<T> {
    try {
        await run();
    } catch (e) {
        return e as T;
    }
    assert.fail("expected a rejection");
}

describe("PostgreSqlConformanceTest PostgreSQL extras", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    let ns = "";

    const q = (name: string) => dialect.quoteIdentifier(name);
    const qualified = (name: string) => q(ns) + "." + q(name);
    const table = (name: string) => new ObjectId(ObjectKind.TABLE, name);
    const column = (t: string, name: string) =>
        new ObjectId(ObjectKind.COLUMN, name, t);
    const reconciler = (script: string, policy?: ReconcilePolicy) =>
        SchemaReconciler.builder().dataSource(pool).namespace(ns)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect)
                .policy(policy ?? ReconcilePolicy.defaults())
                .build();
    const reconcilerOn = (ds: SqlDataSource, script: string,
            policy: ReconcilePolicy) =>
        SchemaReconciler.builder().dataSource(ds).namespace(ns)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect).policy(policy).build();

    async function assertCleanSession(): Promise<void> {
        for (const c of pool.realConnections()) {
            assert.ok(c.getAutoCommit(),
                    "connection left with autoCommit=false");
            const locks = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM pg_locks"
                    + " WHERE locktype = 'advisory'"
                    + " AND pid = pg_backend_pid()");
            assert.equal(Number(locks[0]!.count), 0, "advisory lock leaked");
            const st = await c.query<{ statement_timeout: string }>(
                    "SHOW statement_timeout");
            assert.equal(st[0]!.statement_timeout, "0",
                    "statement_timeout left set");
        }
    }

    async function assertNoScratchNamespacesLeft(): Promise<void> {
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM pg_namespace"
                    + " WHERE nspname LIKE '%\\_\\_rcl\\_%' ESCAPE '\\'");
            assert.equal(Number(rows[0]!.count), 0, "scratch namespaces leaked");
        } finally {
            await c.close();
        }
    }

    beforeEach(async () => {
        ns = "tck_" + RUN_ID + "_" + (++sequence);
        await execute(scratch, "CREATE SCHEMA " + q(ns));
    });

    afterEach(async () => {
        try {
            await execute(scratch, "DROP SCHEMA " + q(ns) + " CASCADE");
        } finally {
            await pool.closeAll();
        }
    });

    it("statementTimeout issues SET LOCAL after the lock", async () => {
        const tracking = new TrackingDataSource(scratch);
        await SchemaReconciler.builder().dataSource(tracking).namespace(ns)
                .desiredState(DesiredState.ofScript(
                        "CREATE TABLE t (id bigint)"))
                .dialect(dialect)
                .statementTimeout(Duration.ofSeconds(5))
                .build()
                .reconcile();
        assert.ok(tracking.executedSql
                        .some(s => s.startsWith("SET LOCAL statement_timeout")),
                JSON.stringify(tracking.executedSql));
    });

    it("plan() is repeatable and apply() converges", async () => {
        const script = "CREATE TABLE users (id bigint, name text)";
        const r = SchemaReconciler.builder().dataSource(pool).namespace(ns)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect).build();
        const plan = await r.plan();
        const report = await r.apply(plan);
        assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    });

    it("safe column drift converges on populated data", async () => {
        await reconciler("CREATE TABLE t (id bigint, name varchar(10),"
                + " note text DEFAULT 'a')").reconcile();
        await execute(scratch, "INSERT INTO " + qualified("t")
                + " VALUES (1, 'abcdefghij', 'x'), (2, 'k', 'y')");
        const report = await reconciler("CREATE TABLE t (id bigint,"
                + " name varchar(20), note text DEFAULT 'b')").reconcile();
        assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ name: string, note: string }>(
                    "SELECT name, note FROM " + qualified("t") + " ORDER BY id");
            assert.equal(rows[0]!.name, "abcdefghij");
            assert.equal(rows[0]!.note, "x");
        } finally {
            await c.close();
        }
        assert.equal((await reconciler("CREATE TABLE t (id bigint,"
                + " name varchar(20), note text DEFAULT 'b')").reconcile())
                .outcome, ReconcileOutcome.UP_TO_DATE);
    });

    it("SET NOT NULL on violating data fails by policy, rolls back when"
            + " forced", async () => {
        await reconciler("CREATE TABLE t (id bigint, note text)").reconcile();
        await execute(scratch, "INSERT INTO " + qualified("t")
                + " VALUES (1, NULL), (2, 'x')");
        const want = "CREATE TABLE t (id bigint, note text NOT NULL)";
        await assert.rejects(() => reconciler(want).reconcile(),
                PolicyViolationException);

        const forced = ReconcilePolicy.defaults()
                .withDestructive(Decision.APPLY);
        const e = await rejects<ApplyException>(
                () => reconciler(want, forced).reconcile());
        assert.ok(e.timings.apply.toMillis() > 0,
                "a mid-apply failure still reports partial apply time");
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM " + qualified("t")
                    + " WHERE note IS NULL");
            assert.equal(Number(rows[0]!.count), 1);
            const snapshot = await dialect.readSnapshot(c, ns);
            assert.ok(snapshot.objects.get(column("t", "note"))!
                    .definition.canonical.includes("notnull=false"),
                    "rollback must leave the column nullable");
        } finally {
            await c.close();
        }
        await assertCleanSession();
    });

    it("partition column drift propagates through the parent's ALTER",
            async () => {
        await reconciler("CREATE TABLE pp (k int, j int) PARTITION BY RANGE (k);"
                + " CREATE TABLE pc PARTITION OF pp FOR VALUES FROM (0) TO (100)")
                .reconcile();
        const want = "CREATE TABLE pp (k int, j bigint) PARTITION BY RANGE (k);"
                + " CREATE TABLE pc PARTITION OF pp FOR VALUES FROM (0) TO (100)";
        const report = await reconciler(want).reconcile();
        assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        assert.ok(report.applied.every(c => c.statements
                        .every(s => !s.includes(" pc") && !s.includes("\"pc\""))),
                "no statement may target the partition");
        assert.equal((await reconciler(want).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("multi-level partition column drift runs on the partitioned root",
            async () => {
        await reconciler("CREATE TABLE rt (k int, s int, j int)"
                + " PARTITION BY RANGE (k);"
                + " CREATE TABLE md PARTITION OF rt FOR VALUES FROM (0) TO (500)"
                + " PARTITION BY RANGE (s);"
                + " CREATE TABLE lf PARTITION OF md FOR VALUES FROM (0) TO (250)")
                .reconcile();
        const want = "CREATE TABLE rt (k int, s int, j bigint)"
                + " PARTITION BY RANGE (k);"
                + " CREATE TABLE md PARTITION OF rt FOR VALUES FROM (0) TO (500)"
                + " PARTITION BY RANGE (s);"
                + " CREATE TABLE lf PARTITION OF md FOR VALUES FROM (0) TO (250)";
        const report = await reconciler(want).reconcile();
        assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        assert.ok(report.applied.every(c => c.statements.every(s =>
                        !s.includes(" md") && !s.includes(" lf")
                        && !s.includes("\"md\"") && !s.includes("\"lf\""))),
                "no statement may target a partition");
        assert.equal((await reconciler(want).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("a detached partitioned table fails closed instead of silently"
            + " converging", async () => {
        await execute(scratch, "CREATE TABLE " + qualified("rt")
                + " (k int, s int) PARTITION BY RANGE (k)");
        await execute(scratch, "CREATE TABLE " + qualified("md")
                + " (k int, s int) PARTITION BY RANGE (s)");
        await assert.rejects(() => reconciler(
                "CREATE TABLE rt (k int, s int) PARTITION BY RANGE (k);"
                + " CREATE TABLE md PARTITION OF rt FOR VALUES FROM (0) TO (500)"
                + " PARTITION BY RANGE (s)").reconcile(), PlanningException);
    });

    it("Builder.filter hides an object subtree from the diff", async () => {
        await execute(scratch, "CREATE TABLE " + qualified("keep") + " (id int)");
        await execute(scratch, "CREATE TABLE " + qualified("skip")
                + " (id int, extra text)");
        await execute(scratch, "COMMENT ON TABLE " + qualified("skip")
                + " IS 'external'");
        const script = "CREATE TABLE keep (id int);"
                + " CREATE TABLE skip (id int);"
                + " COMMENT ON TABLE skip IS 'external'";
        const report = await SchemaReconciler.builder().dataSource(pool)
                .namespace(ns).desiredState(DesiredState.ofScript(script))
                .dialect(dialect)
                .filter(id => !(id.kind === ObjectKind.TABLE
                        && id.name === "skip"))
                .build().reconcile();
        assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
        assert.equal(report.plan.changes.length, 0,
                "excluded subtree is invisible");
        assert.equal(report.unexpected.length, 0);
        assert.equal(report.unsupported.length, 0);
    });

    it("PG18 virtual generated columns and WITHOUT OVERLAPS reconcile",
            async (t) => {
        if (await serverMajor(scratch) < 18) {
            t.skip("virtual generated columns need PostgreSQL 18");
            return;
        }
        await execute(scratch, "CREATE EXTENSION IF NOT EXISTS btree_gist"
                + " WITH SCHEMA pg_catalog");
        await execute(scratch, "ALTER EXTENSION btree_gist"
                + " SET SCHEMA pg_catalog");
        await execute(scratch, "CREATE TABLE " + qualified("gen") + " (a int,"
                + " b int GENERATED ALWAYS AS (a * 2) VIRTUAL,"
                + " c int GENERATED ALWAYS AS (a * 3) STORED)");
        await execute(scratch, "CREATE TABLE " + qualified("bk")
                + " (id int, per daterange,"
                + " PRIMARY KEY (id, per WITHOUT OVERLAPS))");
        const report = await reconciler(
                "CREATE TABLE gen (a int,"
                + " b int GENERATED ALWAYS AS (a * 2) VIRTUAL,"
                + " c int GENERATED ALWAYS AS (a * 3) STORED);"
                + " CREATE TABLE bk (id int, per daterange,"
                + " PRIMARY KEY (id, per WITHOUT OVERLAPS))").reconcile();
        assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE,
                JSON.stringify(report.plan.changes) + " / "
                + JSON.stringify(report.unsupported) + " / "
                + JSON.stringify(report.unexpected));
    });

    it("aggregate option functions round-trip and recreate", async () => {
        await execute(scratch, "CREATE FUNCTION " + qualified("asfn")
                + "(a int, b int) RETURNS int LANGUAGE sql IMMUTABLE"
                + " AS 'SELECT a + b'");
        await execute(scratch, "CREATE FUNCTION " + qualified("affn")
                + "(a int) RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT a * 2'");
        await execute(scratch, "CREATE AGGREGATE " + qualified("a1") + "(int)"
                + " (SFUNC = " + qualified("asfn") + ", STYPE = int,"
                + "  COMBINEFUNC = " + qualified("asfn")
                + ",  FINALFUNC = " + qualified("affn")
                + ", SORTOP = OPERATOR(<))");
        const script = "CREATE FUNCTION asfn(a int, b int)"
                + " RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT a + b';"
                + " CREATE FUNCTION affn(a int)"
                + " RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT a * 2';"
                + " CREATE AGGREGATE a1(int) (SFUNC = asfn, STYPE = int,"
                + " COMBINEFUNC = asfn, FINALFUNC = affn,"
                + " SORTOP = OPERATOR(<))";
        assert.equal((await reconciler(script).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
        await execute(scratch, "DROP AGGREGATE " + qualified("a1") + "(int)");
        await execute(scratch, "CREATE AGGREGATE " + qualified("a1") + "(int)"
                + " (SFUNC = " + qualified("asfn") + ", STYPE = int,"
                + "  COMBINEFUNC = " + qualified("asfn")
                + ",  FINALFUNC = " + qualified("affn")
                + ", SORTOP = OPERATOR(<), INITCOND = '1')");
        const drifted = await reconciler(script).reconcile();
        assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED,
                JSON.stringify(drifted.plan.changes));
        assert.equal((await reconciler(script).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("environmental objects report as UNEXPECTED without blocking",
            async () => {
        await execute(scratch, "CREATE TABLE " + qualified("t") + " (id bigint)");
        await execute(scratch, "CREATE EXTENSION IF NOT EXISTS pg_trgm"
                + " WITH SCHEMA pg_catalog");
        await execute(scratch, "ALTER EXTENSION pg_trgm SET SCHEMA " + q(ns));
        await execute(scratch, "CREATE POLICY p_sel ON " + qualified("t")
                + " FOR SELECT USING (true)");
        await execute(scratch, "COMMENT ON TABLE " + qualified("t")
                + " IS 'managed'");
        await execute(scratch, "CREATE MATERIALIZED VIEW " + qualified("mv")
                + " AS SELECT id FROM " + qualified("t"));
        const report = await reconciler(
                "CREATE TABLE t (id bigint, note text)").reconcile();
        assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        assert.equal(report.unexpected.length, 4,
                JSON.stringify(report.unexpected.map(d => d.id.name)));
        assert.ok(report.unexpected
                .every(d => d.type === DifferenceType.UNEXPECTED));
        const names = report.unexpected.map(d => d.id.name).sort();
        assert.deepEqual(names, ["extension:pg_trgm", "mv", "p_sel", "t"]);
        assert.equal((await reconciler(
                "CREATE TABLE t (id bigint, note text)").reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("category override tolerates partindex but not a comment", async () => {
        const script = "CREATE TABLE t (id bigint, n int);"
                + " CREATE TABLE pp (k int) PARTITION BY RANGE (k);"
                + " CREATE TABLE pt PARTITION OF pp FOR VALUES FROM (1) TO (10);"
                + " CREATE INDEX pi ON pp (k);"
                + " COMMENT ON INDEX pi IS 'x'";
        const tolerant = ReconcilePolicy.defaults()
                .withUnsupportedCategory("partindex", Decision.REPORT);
        await assert.rejects(() => reconciler(script, tolerant).reconcile(),
                PolicyViolationException);
        const both = tolerant.withUnsupportedCategory("comment",
                Decision.REPORT);
        const report = await reconciler(script, both).reconcile();
        assert.equal(report.unsupported.length, 2,
                JSON.stringify(report.unsupported));
        assert.ok(report.unsupported
                .every(d => d.type === DifferenceType.UNSUPPORTED));
        assert.ok(report.applied.some(c => c.target.equals(table("t"))),
                "the managed change still applies");
        assert.equal((await reconciler(script, both).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("checkAccess rejects a role missing target or scratch privileges",
            async () => {
        const role = "rcl_low_" + RUN_ID + "_" + (++sequence);
        await execute(scratch, "CREATE ROLE " + role + " LOGIN PASSWORD 'rcl'");
        const ds = new PgTestDataSource(undefined, role, "rcl");
        const policy = ReconcilePolicy.defaults();
        try {
            await assert.rejects(
                    () => reconcilerOn(ds, "CREATE TABLE t (id bigint)", policy)
                            .reconcile(), ConfigurationException);
            await execute(scratch, "GRANT USAGE, CREATE ON SCHEMA " + q(ns)
                    + " TO " + role);
            const c = await scratch.connect();
            let db: string;
            try {
                const rows = await c.query<{ current_database: string }>(
                        "SELECT current_database()");
                db = rows[0]!.current_database;
            } finally {
                await c.close();
            }
            await execute(scratch, "REVOKE CREATE ON DATABASE " + db
                    + " FROM PUBLIC");
            try {
                const e = await rejects<ConfigurationException>(
                        () => reconcilerOn(ds, "CREATE TABLE t (id bigint)",
                                policy).reconcile());
                assert.ok(e.message.includes("scratch"),
                        "expected the scratch-creation privilege in the message: "
                                + e.message);
            } finally {
                await execute(scratch, "GRANT CREATE ON DATABASE " + db
                        + " TO PUBLIC");
            }
        } finally {
            await execute(scratch, "REVOKE ALL ON SCHEMA " + q(ns)
                    + " FROM " + role);
            await execute(scratch, "DROP ROLE " + role);
        }
    });

    it("statementTimeout fires on a blocked apply; rollback keeps the pool"
            + " clean", async () => {
        await reconciler("CREATE TABLE t (id bigint, note text)").reconcile();
        const blocker = await scratch.connect();
        await blocker.setAutoCommit(false);
        await blocker.execute("LOCK TABLE " + qualified("t")
                + " IN ACCESS EXCLUSIVE MODE");
        try {
            const e = await rejects<ApplyException>(() =>
                    SchemaReconciler.builder().dataSource(pool).namespace(ns)
                            .desiredState(DesiredState.ofScript(
                                    "CREATE TABLE t (id bigint,"
                                    + " note text DEFAULT 'b')"))
                            .dialect(dialect)
                            .statementTimeout(Duration.ofSeconds(5))
                            .build()
                            .reconcile());
            assert.ok(e.timings.lockWait.toMillis() > 0);
            assert.ok(e.timings.apply.toMillis() > 0);
        } finally {
            await blocker.rollback();
            await blocker.close();
        }
        await assertCleanSession();
        assert.equal((await reconciler("CREATE TABLE t (id bigint,"
                + " note text DEFAULT 'b')").reconcile()).outcome,
                ReconcileOutcome.RECONCILED);
    });

    it("statementTimeout fires during the verification re-scan", async () => {
        await execute(scratch, "CREATE TABLE " + qualified("other")
                + " (id int DEFAULT 1)");
        await execute(scratch, "CREATE TABLE " + qualified("t") + " (id bigint)");
        const blocker = await scratch.connect();
        await blocker.setAutoCommit(false);
        let locked = false;
        try {
            const e = await rejects<SchemaReconcilerException>(() =>
                    SchemaReconciler.builder().dataSource(pool).namespace(ns)
                            .desiredState(DesiredState.ofScript(
                                    "CREATE TABLE t (id bigint, extra text)"))
                            .dialect(dialect)
                            .statementTimeout(Duration.ofSeconds(5))
                            .listener({
                                async applied() {
                                    if (!locked) {
                                        locked = true;
                                        await blocker.execute("LOCK TABLE "
                                                + qualified("other")
                                                + " IN ACCESS EXCLUSIVE MODE");
                                    }
                                },
                            })
                            .build()
                            .reconcile());
            assert.ok(locked, "apply phase never ran");
            assert.ok(e.message.includes("catalog snapshot"), e.message);
            assert.ok(e.timings.apply.toMillis() > 0);
            assert.ok(e.timings.verify.toMillis() >= 4000,
                    "verify timing: " + e.timings.verify);
        } finally {
            await blocker.rollback();
            await blocker.close();
        }
        await assertCleanSession();
    });

    it("statementTimeout bounds the separate scratch connection too",
            async () => {
        const e = await rejects<DesiredStateException>(() =>
                SchemaReconciler.builder().dataSource(pool)
                        .scratchDataSource(scratch)
                        .namespace(ns)
                        .desiredState(DesiredState.ofScript(
                                "DO $$ BEGIN PERFORM pg_sleep(10); END $$"))
                        .dialect(dialect)
                        .statementTimeout(Duration.ofSeconds(1))
                        .build()
                        .reconcile());
        assert.ok(e.timings.loadDesired.toMillis() >= 900,
                "the blocked scratch phase must still report its partial time");
        await assertNoScratchNamespacesLeft();
        await assertCleanSession();
    });

    it("tolerated partindex reports; the comment still fails", async () => {
        const script = "CREATE TABLE t (id bigint, n int);"
                + " CREATE TABLE pp (k int) PARTITION BY RANGE (k);"
                + " CREATE TABLE pt PARTITION OF pp FOR VALUES FROM (1) TO (10);"
                + " CREATE INDEX pi ON pp (k);"
                + " COMMENT ON INDEX pi IS 'x'";
        const tolerant = ReconcilePolicy.defaults()
                .withUnsupportedCategory("partindex", Decision.REPORT);
        await assert.rejects(() => reconciler(script, tolerant).reconcile(),
                PolicyViolationException);
        const both = tolerant.withUnsupportedCategory("comment",
                Decision.REPORT);
        const report = await reconciler(script, both).reconcile();
        const names = report.unsupported.map(d => d.id.name).sort();
        assert.ok(names.some(n => n.startsWith("comment:")),
                JSON.stringify(names));
        assert.ok(names.some(n => n.startsWith("partindex:")),
                "expected a partindex entry: " + JSON.stringify(names));
        assert.ok(report.unsupported
                .every(d => d.type === DifferenceType.UNSUPPORTED));
        assert.ok(report.applied.some(c => c.target.equals(table("t"))),
                "the managed change still applies");
        assert.equal((await reconciler(script, both).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("a created table keeps its storage options and replica identity",
            async () => {
        const script = "CREATE TABLE t (id bigint NOT NULL, note text)"
                + " WITH (fillfactor = 70);"
                + " CREATE UNIQUE INDEX t_id ON t (id);"
                + " ALTER TABLE t REPLICA IDENTITY USING INDEX t_id";
        const report = await reconciler(script).reconcile();
        assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        assert.equal((await reconciler(script).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("an unlogged sequence keeps its persistence and drifts in place",
            async () => {
        assert.equal((await reconciler("CREATE UNLOGGED SEQUENCE s").reconcile())
                .outcome, ReconcileOutcome.RECONCILED);
        assert.equal((await reconciler("CREATE UNLOGGED SEQUENCE s").reconcile())
                .outcome, ReconcileOutcome.UP_TO_DATE);
        const apply = ReconcilePolicy.defaults()
                .withDestructive(Decision.APPLY);
        assert.equal((await reconciler("CREATE SEQUENCE s", apply).reconcile())
                .outcome, ReconcileOutcome.RECONCILED);
        assert.equal((await reconciler("CREATE SEQUENCE s").reconcile())
                .outcome, ReconcileOutcome.UP_TO_DATE);
        const c = await scratch.connect();
        try {
            const rows = await c.query<{ relpersistence: string }>(
                    "SELECT relpersistence FROM pg_class"
                    + " WHERE relname = 's' AND relkind = 'S'"
                    + " AND relnamespace = '" + ns.replaceAll("'", "''")
                    + "'::regnamespace");
            assert.equal(rows[0]!.relpersistence, "p");
        } finally {
            await c.close();
        }
    });

    it("column storage and compression parameters converge and drift back",
            async () => {
        const declared = "CREATE TABLE t (id int, body text"
                + " STORAGE EXTERNAL COMPRESSION pglz)";
        assert.equal((await reconciler(declared).reconcile()).outcome,
                ReconcileOutcome.RECONCILED);
        assert.equal((await reconciler(declared).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
        assert.equal((await reconciler(
                "CREATE TABLE t (id int, body text)").reconcile()).outcome,
                ReconcileOutcome.RECONCILED);
        assert.equal((await reconciler(
                "CREATE TABLE t (id int, body text)").reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
        const c = await scratch.connect();
        try {
            const rows = await c.query<{
                attstorage: string, attcompression: string,
            }>("SELECT attstorage, attcompression FROM pg_attribute"
                    + " WHERE attrelid = '" + ns.replaceAll("'", "''")
                    + ".t'::regclass AND attname = 'body'");
            assert.equal(rows[0]!.attstorage, "x");
            assert.equal(rows[0]!.attcompression, "");
        } finally {
            await c.close();
        }
    });

    it("an exclusion constraint's backing index is not a separate index",
            async () => {
        await execute(scratch, "CREATE TABLE " + qualified("t")
                + " (id int, CONSTRAINT t_ex EXCLUDE (id WITH =))");
        const c = await scratch.connect();
        try {
            const snapshot = await dialect.readSnapshot(c, ns);
            const ids = [...snapshot.objects.keys()];
            assert.ok(ids.every(id => id.kind !== ObjectKind.INDEX),
                    "exclusion backing index must not surface");
            assert.ok(ids.some(id => id.kind === ObjectKind.EXCLUSION
                    && id.name === "t_ex"),
                    "the exclusion constraint must be a managed object");
        } finally {
            await c.close();
        }
    });

    it("a namespace token inside a literal is rejected as never-convergent",
            async () => {
        for (const script of [
            "CREATE TABLE t (c text DEFAULT '${namespace}')",
            "CREATE TABLE t (c text DEFAULT '$${namespace}')",
            "COMMENT ON TABLE t IS '${namespace}'",
        ]) {
            await assert.rejects(() => reconciler(script).plan(),
                    DesiredStateException, script);
        }
    });

    it("malformed scripts are rejected as desired-state errors", async () => {
        for (const script of ["SET", "CREATE", "ALTER", "DROP IF EXISTS"]) {
            await assert.rejects(() => reconciler(script).plan(),
                    DesiredStateException, script);
        }
    });

    it("an escape string cannot hide transaction control from the script"
            + " check", async () => {
        await assert.rejects(() => reconciler(
                "SELECT E'x\\''; COMMIT; SELECT ''").plan(),
                DesiredStateException);
    });

    it("a role with USAGE but not CREATE on the namespace is rejected up"
            + " front", async () => {
        const role = "rcl_ro_" + RUN_ID;
        await execute(scratch, "DROP ROLE IF EXISTS " + role);
        await execute(scratch, "CREATE ROLE " + role + " NOLOGIN");
        try {
            await execute(scratch, "GRANT USAGE ON SCHEMA " + q(ns)
                    + " TO " + role);
            const c = await scratch.connect();
            try {
                await c.execute("SET ROLE " + role);
                await assert.rejects(
                        () => dialect.checkAccess!(c, ns, false),
                        ConfigurationException);
            } finally {
                await c.close();
            }
        } finally {
            await execute(scratch, "DROP OWNED BY " + role);
            await execute(scratch, "DROP ROLE " + role);
        }
    });

    it("session-leaking calls inside DO blocks and set_config(false) are"
            + " rejected", async () => {
        for (const script of [
            "DO $$ BEGIN PERFORM pg_advisory_lock(1); END $$",
            "DO $$ BEGIN EXECUTE 'SELECT pg_try_advisory_lock(2)'; END $$",
            "DO $$ BEGIN PERFORM set_config('search_path', 'x', false); END $$",
            "SELECT set_config('search_path', 'x', false)",
        ]) {
            await assert.rejects(() => reconciler(script).plan(),
                    DesiredStateException, script);
        }
        await reconciler("SELECT set_config('app.x', '1', true)").plan();
    });

    it("a listener failure in plan() is a PlanningException", async () => {
        const r = SchemaReconciler.builder().dataSource(pool).namespace(ns)
                .desiredState(DesiredState.ofScript("CREATE TABLE t (id bigint)"))
                .dialect(dialect)
                .listener({
                    planned() {
                        throw new Error("no");
                    },
                })
                .build();
        await assert.rejects(() => r.plan(), PlanningException);
        await assert.rejects(() => r.reconcile(), ApplyException);
    });

    it("set_config is accepted only with a literal true third argument",
            async () => {
        for (const script of [
            "SELECT set_config('a','b', false), true",
            "SELECT set_config('a','b', false) = 'x' AND true",
            "SELECT set_config('a','b')",
            "SELECT set_config('a','b', 1 > 0)",
            "SELECT set_config('a', 'b,true', false)",
        ]) {
            await assert.rejects(() => reconciler(script).plan(),
                    DesiredStateException, script);
        }
        await reconciler("SELECT set_config('app.a', 'b,false)', TRUE)").plan();
    });

    it("a session lock taken by a function the script calls is released after"
            + " the script", async () => {
        const c = await scratch.connect();
        try {
            const scratchName = await dialect.createScratchNamespace(c, ns);
            try {
                await dialect.executeScript(c,
                        "SET LOCAL search_path = " + q(scratchName)
                        + "; CREATE FUNCTION leak() RETURNS boolean LANGUAGE sql"
                        + " AS $$ SELECT pg_try_advisory_lock(424242) $$;"
                        + " SELECT leak()", scratchName);
            } finally {
                await dialect.dropScratchNamespace(c, scratchName);
            }
            const rows = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"
                    + " AND objid = 424242 AND pid = pg_backend_pid()");
            assert.equal(Number(rows[0]!.count), 0);
            await c.execute("ROLLBACK");
        } finally {
            await c.close();
        }
    });

    it("planTimed() reports planning phase timings and no apply time",
            async () => {
        const timed = await SchemaReconciler.builder().dataSource(pool)
                .namespace(ns)
                .desiredState(DesiredState.ofScript("CREATE TABLE t (id bigint)"))
                .dialect(dialect).build().planTimed();
        assert.ok(timed.timings.readActual.toMillis() > 0);
        assert.ok(timed.timings.loadDesired.toMillis() > 0);
        assert.equal(timed.timings.apply, Duration.ZERO);
        assert.equal(timed.timings.verify, Duration.ZERO);
        assert.equal(timed.plan.changes.length, 1);
    });
});
