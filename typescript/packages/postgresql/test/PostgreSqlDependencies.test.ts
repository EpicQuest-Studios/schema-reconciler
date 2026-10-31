import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { Decision, DesiredState, ObjectKind, PlanningException,
        ReconcileOutcome, ReconcilePolicy, SchemaReconciler }
        from "@schema-reconciler/core";
import type { ReconcilePolicy as Policy, ReconcileReport }
        from "@schema-reconciler/core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available, execute, serverMajor }
        from "./PgTestDatabase.ts";

/**
 * Catalog dependency-edge regressions: ordering of changes against newly
 * discovered pg_depend edges, and external dependents on routines and
 * sequences (5.8.1, 5.8.3, 3.3).
 */

const dbUp = await available();
const ds = new PgTestDataSource();
const dialect = new PostgreSqlDialect();

const RUN_ID = process.hrtime.bigint().toString(16);
let sequence = 0;

async function namespace(): Promise<string> {
    const name = "dep_" + RUN_ID + "_" + (++sequence);
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
        policy: Policy = ReconcilePolicy.defaults()) {
    return SchemaReconciler.builder().dataSource(ds).namespace(ns)
            .desiredState(DesiredState.ofScript(script)).dialect(dialect)
            .policy(policy).build();
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

describe("PostgreSqlDependenciesTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("column default on a non-owned missing sequence orders the sequence"
            + " first", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (id int)");
            const script = `CREATE SEQUENCE \${namespace}.s;
CREATE TABLE \${namespace}.t (id int, x bigint
    DEFAULT nextval('\${namespace}.s'::regclass));`;
            const report = await reconciler(ns, script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler(ns, script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("a missing table whose column defaults to a missing sequence orders"
            + " the sequence first", async () => {
        const ns = await namespace();
        try {
            const report = await reconciler(ns,
                    `CREATE SEQUENCE \${namespace}.s;
CREATE TABLE \${namespace}.t (id bigint
    DEFAULT nextval('\${namespace}.s'::regclass));`).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        } finally {
            await drop(ns);
        }
    });

    it("a missing check constraint on a missing routine orders the routine"
            + " first", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (x int)");
            const report = await reconciler(ns,
                    `CREATE FUNCTION \${namespace}.ok(int) RETURNS boolean
    LANGUAGE sql IMMUTABLE AS $$SELECT $1 > 0$$;
CREATE TABLE \${namespace}.t (x int CHECK (\${namespace}.ok(x)));`).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        } finally {
            await drop(ns);
        }
    });

    it("a column default calling a missing routine orders the routine"
            + " first", async () => {
        const ns = await namespace();
        try {
            await execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (id int)");
            const report = await reconciler(ns,
                    `CREATE FUNCTION \${namespace}.def() RETURNS int
    LANGUAGE sql IMMUTABLE AS $$SELECT 7$$;
CREATE TABLE \${namespace}.t (id int, x int DEFAULT \${namespace}.def());`)
                    .reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        } finally {
            await drop(ns);
        }
    });

    it("an FK referencing a bare unique index snapshots the index and orders"
            + " it first", async () => {
        const ns = await namespace();
        const script = `CREATE TABLE \${namespace}.a (id int PRIMARY KEY, tenant int NOT NULL);
CREATE UNIQUE INDEX a_tenant_id ON \${namespace}.a (tenant, id);
CREATE TABLE \${namespace}.b (a_tenant int NOT NULL, a_id int NOT NULL,
    CONSTRAINT b_fk FOREIGN KEY (a_tenant, a_id)
        REFERENCES \${namespace}.a (tenant, id));`;
        try {
            // an FK's conindid points at the referenced index: the index
            // must still appear in the snapshot and order before the FK
            const report = await reconciler(ns, script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler(ns, script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await drop(ns);
        }
    });

    it("an external view dependent on a recreated routine fails planning"
            + " (5.8.3)", async () => {
        const ns = await namespace();
        const view = "ext_v_" + (++sequence).toString(16);
        try {
            await execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".f_ret(int) RETURNS int LANGUAGE sql IMMUTABLE"
                    + " AS $$SELECT $1$$");
            await execute(ds, "CREATE VIEW public." + view
                    + " AS SELECT " + dialect.quoteIdentifier(ns)
                    + ".f_ret(1) AS x");
            // the desired state changes the return type: the routine must
            // be dropped and recreated, which the external view blocks
            const e = await rejects(reconciler(ns,
                    `CREATE FUNCTION \${namespace}.f_ret(int) RETURNS bigint
    LANGUAGE sql IMMUTABLE AS $$SELECT $1::bigint$$;`).plan(),
                    PlanningException);
            assert.ok(e.message.includes("public." + view), e.message);
        } finally {
            await execute(ds, "DROP VIEW IF EXISTS public." + view);
            await drop(ns);
        }
    });

    it("an external check constraint on a recreated routine fails planning"
            + " (5.8.3)", async () => {
        const ns = await namespace();
        const table = "ext_t_" + (++sequence).toString(16);
        try {
            await execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".ok(int) RETURNS boolean LANGUAGE sql IMMUTABLE"
                    + " AS $$SELECT $1 > 0$$");
            await execute(ds, "CREATE TABLE public." + table
                    + " (x int CHECK (" + dialect.quoteIdentifier(ns)
                    + ".ok(x)))");
            await rejects(reconciler(ns,
                    `CREATE FUNCTION \${namespace}.ok(int) RETURNS int
    LANGUAGE sql IMMUTABLE AS $$SELECT 1$$;`).plan(), PlanningException);
        } finally {
            await execute(ds, "DROP TABLE IF EXISTS public." + table);
            await drop(ns);
        }
    });

    it("an external trigger dependent on a recreated routine fails planning"
            + " (5.8.3)", async () => {
        const ns = await namespace();
        const table = "ext_t_" + (++sequence).toString(16);
        const trigger = "trg_" + (++sequence).toString(16);
        try {
            await execute(ds, "CREATE TABLE public." + table + " (x int)");
            await execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".trg() RETURNS trigger LANGUAGE plpgsql"
                    + " AS $$BEGIN RETURN NEW; END$$");
            await execute(ds, "CREATE TRIGGER " + trigger
                    + " BEFORE INSERT ON public." + table
                    + " FOR EACH ROW EXECUTE FUNCTION "
                    + dialect.quoteIdentifier(ns) + ".trg()");
            // a different return type forces a DROP + CREATE, which the
            // external trigger must block at plan time
            await rejects(reconciler(ns,
                    `CREATE FUNCTION \${namespace}.trg() RETURNS int
    LANGUAGE sql AS $$SELECT 1$$;`).plan(), PlanningException);
        } finally {
            await execute(ds, "DROP TABLE IF EXISTS public." + table);
            await drop(ns);
        }
    });

    it("an external dictionary dependent on our template blocks its drop"
            + " (5.8.3)", async () => {
        const ns = await namespace();
        const dict = "ext_d_" + (++sequence).toString(16);
        try {
            await execute(ds, "CREATE TEXT SEARCH TEMPLATE "
                    + dialect.quoteIdentifier(ns)
                    + ".tmpl (LEXIZE = dsimple_lexize)");
            await execute(ds, "CREATE TEXT SEARCH DICTIONARY public." + dict
                    + " (TEMPLATE = " + dialect.quoteIdentifier(ns) + ".tmpl)");
            // the desired state drifts the template's lexize: it must be
            // dropped and recreated, which the external dictionary blocks
            const e = await rejects(reconciler(ns,
                    `CREATE TEXT SEARCH TEMPLATE \${namespace}.tmpl
    (LEXIZE = dsnowball_lexize);`).plan(), PlanningException);
            assert.ok(e.message.includes("public." + dict), e.message);
        } finally {
            await execute(ds, "DROP TEXT SEARCH DICTIONARY IF EXISTS public."
                    + dict);
            await drop(ns);
        }
    });

    it("an unsupported cast dependent on a recreated routine fails planning"
            + " (5.8.3)", async () => {
        const ns = await namespace();
        const q = dialect.quoteIdentifier(ns);
        try {
            await execute(ds, "CREATE TYPE " + q
                    + ".mood AS ENUM ('a','b')");
            await execute(ds, "CREATE FUNCTION " + q + ".mk(int) RETURNS " + q
                    + ".mood LANGUAGE sql IMMUTABLE AS $$SELECT 'a'::" + q
                    + ".mood$$");
            await execute(ds, "CREATE CAST (int AS " + q
                    + ".mood) WITH FUNCTION " + q + ".mk(int)");
            // a different return type forces a DROP + CREATE, which the
            // unrecreatable cast must block at plan time
            const e = await rejects(reconciler(ns,
                    `CREATE TYPE \${namespace}.mood AS ENUM ('a','b');
CREATE FUNCTION \${namespace}.mk(int) RETURNS int
    LANGUAGE sql IMMUTABLE AS $$SELECT 7$$;`).plan(), PlanningException);
            assert.ok(e.message.includes("integer->mood"), e.message);
        } finally {
            await drop(ns);
        }
    });

    it("every construct a desired script creates is detected, applied or"
            + " reported (5.4)", async () => {
        const ns = await namespace();
        try {
            const policy = ReconcilePolicy.defaults()
                    .withUnsupported(Decision.REPORT);
            await execute(ds, "DROP ROLE IF EXISTS rcl_tmp_role");
            await execute(ds, "CREATE ROLE rcl_tmp_role");
            const report: ReconcileReport = await reconciler(ns,
                    `CREATE TABLE \${namespace}.t (a int, b int, ts tstzrange);
CREATE TABLE \${namespace}.i (x int) INHERITS (\${namespace}.t);
CREATE TYPE \${namespace}.mood AS ENUM ('ok');
CREATE TYPE \${namespace}.span AS RANGE (subtype = int4);
CREATE TABLE \${namespace}.cap (a int);
CREATE TABLE \${namespace}.cac () INHERITS (\${namespace}.cap);
CREATE CAST (\${namespace}.cac AS \${namespace}.cap) WITH INOUT;
CREATE STATISTICS \${namespace}.st ON a, b FROM \${namespace}.t;
COMMENT ON TABLE \${namespace}.i IS 'noted';
CREATE RULE r1 AS ON INSERT TO \${namespace}.t DO INSTEAD NOTHING;
ALTER TABLE \${namespace}.t ADD CONSTRAINT ex
    EXCLUDE USING gist (ts WITH &&);
ALTER TABLE \${namespace}.t OWNER TO rcl_tmp_role;
GRANT SELECT ON \${namespace}.i TO rcl_tmp_role;`, policy).reconcile();
            const names = report.unsupported.map(d => d.id.name);
            for (const expected of [
                    "i",            // inherited table
                    "cast:",        // enums and ranges are managed
                    "comment:i", "privileges:i"]) {
                assert.ok(names.some(n => n.includes(expected)),
                        expected + " missing from " + JSON.stringify(names));
            }
            // the OWNERSHIP change actually assigned t to the role — shed
            // its objects before dropping it
            await execute(ds, "DROP OWNED BY rcl_tmp_role");
            await execute(ds, "DROP ROLE IF EXISTS rcl_tmp_role");
        } finally {
            await drop(ns);
        }
    });

    it("a sequence bound to a publication is detected on PostgreSQL 19"
            + " (5.4)", async (t) => {
        if (await serverMajor(ds) < 19) {
            t.skip("pg_publication_sequences exists on PostgreSQL 19+");
            return;
        }
        const ns = await namespace();
        try {
            await execute(ds, "CREATE SEQUENCE " + dialect.quoteIdentifier(ns)
                    + ".sq");
            await execute(ds, "CREATE PUBLICATION dep_pub FOR SEQUENCE "
                    + dialect.quoteIdentifier(ns) + ".sq");
            const conn = await ds.connect();
            try {
                await conn.setAutoCommit(false);
                const snap = await dialect.readSnapshot(conn, ns);
                assert.ok([...snap.objects.keys()].some(id =>
                        id.kind === ObjectKind.PUBLICATION
                        && id.name === "dep_pub"),
                        "sequence publication binding missing: "
                        + JSON.stringify([...snap.objects.keys()]));
            } finally {
                await conn.rollback();
                await conn.close();
            }
        } finally {
            await execute(ds, "DROP PUBLICATION IF EXISTS dep_pub");
            await drop(ns);
        }
    });

    it("a FOR TABLES IN SCHEMA publication binding is detected on"
            + " PostgreSQL 15+ (5.4)", async (t) => {
        if (await serverMajor(ds) < 15) {
            t.skip("pg_publication_namespace exists on PostgreSQL 15+");
            return;
        }
        const ns = await namespace();
        try {
            await execute(ds,
                    "CREATE PUBLICATION dep_pub_ns FOR TABLES IN SCHEMA "
                    + dialect.quoteIdentifier(ns));
            const conn = await ds.connect();
            try {
                await conn.setAutoCommit(false);
                const snap = await dialect.readSnapshot(conn, ns);
                assert.ok([...snap.objects.keys()].some(id =>
                        id.kind === ObjectKind.PUBLICATION
                        && id.name === "dep_pub_ns"),
                        "schema publication binding missing: "
                        + JSON.stringify([...snap.objects.keys()]));
            } finally {
                await conn.rollback();
                await conn.close();
            }
        } finally {
            await execute(ds, "DROP PUBLICATION IF EXISTS dep_pub_ns");
            await drop(ns);
        }
    });

    it("partition-inherited constraints and cloned triggers are not"
            + " standalone objects (5.4)", async () => {
        const ns = await namespace();
        const q = dialect.quoteIdentifier(ns);
        try {
            await execute(ds, "CREATE TABLE " + q + ".pt (id int, v text)"
                    + " PARTITION BY RANGE (id);"
                    + " CREATE TABLE " + q + ".p1 PARTITION OF " + q + ".pt"
                    + " FOR VALUES FROM (0) TO (1000);"
                    + " ALTER TABLE " + q + ".pt ADD PRIMARY KEY (id);"
                    + " ALTER TABLE " + q + ".pt ADD CONSTRAINT chk"
                    + " CHECK (v IS NOT NULL);"
                    + " CREATE TRIGGER trg BEFORE INSERT ON " + q + ".pt"
                    + " FOR EACH ROW EXECUTE FUNCTION"
                    + " suppress_redundant_updates_trigger()");
            const conn = await ds.connect();
            try {
                await conn.setAutoCommit(false);
                const snap = await dialect.readSnapshot(conn, ns);
                // the parent objects load normally — the inherited copies
                // on the partition must not: they are not standalone
                // objects and would only report UNEXPECTED
                const keys = [...snap.objects.keys()];
                assert.ok(keys.some(id => id.name === "chk"),
                        "parent check missing: " + JSON.stringify(keys));
                assert.ok(keys.some(id => id.name === "trg"),
                        "parent trigger missing: " + JSON.stringify(keys));
                const onPartition = keys.filter(id => id.parent === "p1"
                        && id.kind !== ObjectKind.COLUMN);
                assert.equal(onPartition.length, 0,
                        "inherited objects on the partition must not load: "
                        + JSON.stringify(onPartition));
            } finally {
                await conn.rollback();
                await conn.close();
            }
        } finally {
            await drop(ns);
        }
    });

    it("changes depending on an unsupported object demote to REPORT (3.3)",
            async () => {
        const ns = await namespace();
        try {
            const policy = ReconcilePolicy.defaults()
                    .withUnsupported(Decision.REPORT);
            const report = await reconciler(ns,
                    `CREATE TABLE \${namespace}.cap (a int);
CREATE TABLE \${namespace}.cac () INHERITS (\${namespace}.cap);
CREATE TABLE \${namespace}.t (x \${namespace}.cac);`,
                    policy).reconcile();
            // cap applies; the table typed on the unsupported inherited
            // child's rowtype demotes
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.reported.some(c => c.target.name === "t"),
                    "the table change must be demoted, not attempted: "
                    + JSON.stringify(report.reported));
            // the table must NOT have been created
            const c = await ds.connect();
            try {
                const rows = await c.query<{ count: string | number }>(
                        "SELECT count(*) FROM pg_class c"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = '" + ns + "'"
                        + " AND c.relname = 't'");
                assert.equal(Number(rows[0]!.count), 0,
                        "the demoted change must not run");
            } finally {
                await c.close();
            }
        } finally {
            await drop(ns);
        }
    });
});
