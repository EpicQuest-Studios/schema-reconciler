import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { DesiredState, ReconcileOutcome, SchemaReconciler }
        from "@schema-reconciler/core";
import type { SqlConnection, SqlDataSource } from "@schema-reconciler/core";
import { dialectConformanceSuite } from "@schema-reconciler/tck";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestConnection, PgTestDataSource, available, execute }
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
