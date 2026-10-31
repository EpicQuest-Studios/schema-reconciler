import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ObjectId, ObjectKind } from "@schema-reconciler/core";
import type { SqlConnection } from "@schema-reconciler/core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import * as PgSql from "../src/internal/PgSql.ts";
import { PgTestDataSource, available } from "./PgTestDatabase.ts";

/** Spec A.5: each canonical definition is the engine's `pg_get_*def` text,
 *  normalised (5.3). */

const dbUp = await available();
const dialect = new PostgreSqlDialect();

async function query(c: SqlConnection, sql: string): Promise<string> {
    const rows = await c.query<[string] | Record<string, unknown>>(sql);
    const first = rows[0] as Record<string, unknown>;
    return String(Object.values(first)[0]);
}

describe("CanonicalDefinitionTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("canonical definitions are the normalised engine definitions",
            async () => {
        const ds = new PgTestDataSource();
        const schema = "canon_" + process.hrtime.bigint().toString(16);
        const q = dialect.quoteIdentifier(schema);
        const c = await ds.connect();
        try {
            await c.setAutoCommit(false);
            try {
                await c.execute("CREATE SCHEMA " + q);
                await c.execute("SET LOCAL search_path = " + q);
                await c.execute("CREATE TABLE t (id bigint PRIMARY KEY,"
                        + " name text NOT NULL DEFAULT 'x', ref bigint,"
                        + " CONSTRAINT t_chk CHECK (id > 0))");
                await c.execute("CREATE INDEX t_name ON t (lower(name))"
                        + " WHERE id > 5");
                await c.execute("CREATE VIEW v AS SELECT id, name FROM t");
                await c.execute("CREATE FUNCTION f(a int) RETURNS int"
                        + " LANGUAGE sql AS $$ SELECT a + 1 $$");
                await c.execute("CREATE FUNCTION trg() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$");
                await c.execute("CREATE TRIGGER tr BEFORE INSERT ON t"
                        + " FOR EACH ROW EXECUTE FUNCTION trg()");
                await c.execute("CREATE TYPE mood AS ENUM ('b', 'a')");
                await c.execute("CREATE MATERIALIZED VIEW mv AS SELECT id"
                        + " FROM t");
                await c.execute("CREATE DOMAIN loctext AS text COLLATE \"C\""
                        + " NOT NULL DEFAULT 'x' CHECK (VALUE <> '')");
                await c.execute("ALTER TABLE t ENABLE ROW LEVEL SECURITY");
                await c.execute("CREATE POLICY p_sel ON t AS RESTRICTIVE"
                        + " FOR SELECT TO PUBLIC USING (id > 0)");
                await c.execute("COMMENT ON TABLE t IS 'kept'");

                const snap = await dialect.readSnapshot(c, schema);
                const ns = schema;

                const matview = snap.objects.get(
                        new ObjectId(ObjectKind.MATERIALIZED_VIEW, "mv"))!;
                assert.ok(matview.definition.canonical.startsWith(
                        PgSql.normalize("viewdef=" + await query(c,
                                "SELECT pg_get_viewdef('mv'::regclass, true)"),
                                ns)),
                        matview.definition.canonical);
                assert.ok(matview.definition.canonical
                        .includes(";populated=true"),
                        matview.definition.canonical);

                const mood = snap.objects.get(
                        new ObjectId(ObjectKind.TYPE, "mood"))!;
                assert.equal(mood.definition.canonical,
                        "typtype=e;labels='b','a'");

                const loctext = snap.objects.get(
                        new ObjectId(ObjectKind.DOMAIN, "loctext"))!;
                assert.equal(loctext.definition.canonical,
                        "basetype=text;notnull=true;collation=\"C\";default="
                        + await query(c, "SELECT pg_get_expr(typdefaultbin, 0)"
                                + " FROM pg_type WHERE typname = 'loctext'"
                                + " AND typnamespace = current_schema()"
                                + "::regnamespace")
                        + ";check.loctext_check=" + await query(c,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE contypid = 'loctext'::regtype"
                                + " AND contype = 'c'"));

                const pol = snap.objects.get(
                        new ObjectId(ObjectKind.POLICY, "p_sel", "t"))!;
                assert.equal(pol.definition.canonical,
                        "cmd=r;permissive=false;roles=PUBLIC;using="
                        + await query(c, "SELECT pg_get_expr(polqual, polrelid)"
                                + " FROM pg_policy WHERE polname = 'p_sel'"
                                + " AND polrelid = 't'::regclass"));

                const table = snap.objects.get(
                        new ObjectId(ObjectKind.TABLE, "t"))!;
                assert.ok(table.definition.canonical.includes(";rls=true"),
                        table.definition.canonical);

                const comment = snap.objects.get(
                        new ObjectId(ObjectKind.COMMENT, "t"))!;
                assert.equal(comment.definition.canonical, "kept");

                const index = snap.objects.get(
                        new ObjectId(ObjectKind.INDEX, "t_name", "t"))!;
                assert.equal(index.definition.canonical, PgSql.normalize(
                        await query(c,
                                "SELECT pg_get_indexdef('t_name'::regclass)"),
                        ns));

                const check = snap.objects.get(
                        new ObjectId(ObjectKind.CHECK, "t_chk", "t"))!;
                assert.equal(check.definition.canonical, PgSql.normalize(
                        await query(c,
                                "SELECT pg_get_constraintdef(oid)"
                                + " FROM pg_constraint"
                                + " WHERE conname = 't_chk'"
                                + " AND conrelid = 't'::regclass"), ns));

                const pk = snap.objects.get(
                        new ObjectId(ObjectKind.PRIMARY_KEY, "t_pkey", "t"))!;
                assert.equal(pk.definition.canonical, PgSql.normalize(
                        await query(c,
                                "SELECT pg_get_constraintdef(oid)"
                                + " FROM pg_constraint"
                                + " WHERE conname = 't_pkey'"), ns));

                const view = snap.objects.get(
                        new ObjectId(ObjectKind.VIEW, "v"))!;
                assert.ok(view.definition.canonical.startsWith(PgSql.normalize(
                        "viewdef=" + await query(c,
                                "SELECT pg_get_viewdef('v'::regclass, true)"),
                        ns)), view.definition.canonical);

                const routine = snap.objects.get(
                        new ObjectId(ObjectKind.ROUTINE, "f(integer)"))!;
                assert.equal(routine.definition.canonical, PgSql.normalize(
                        await query(c,
                                "SELECT pg_get_functiondef('f(int)'::regprocedure)"),
                        ns));

                const trigger = snap.objects.get(
                        new ObjectId(ObjectKind.TRIGGER, "tr", "t"))!;
                assert.equal(trigger.definition.canonical, PgSql.normalize(
                        await query(c,
                                "SELECT pg_get_triggerdef(oid, true)"
                                + " FROM pg_trigger WHERE tgname = 'tr'"), ns)
                        + ";enabled=O");

                const column = snap.objects.get(
                        new ObjectId(ObjectKind.COLUMN, "name", "t"))!;
                assert.equal(column.definition.canonical,
                        "type=" + await query(c,
                        "SELECT format_type(atttypid, atttypmod)"
                                + " FROM pg_attribute"
                                + " WHERE attrelid = 't'::regclass"
                                + " AND attname = 'name'")
                        + ";notnull=true;default=" + await query(c,
                        "SELECT pg_get_expr(adbin, adrelid) FROM pg_attrdef"
                                + " WHERE adrelid = 't'::regclass"
                                + " AND adnum = (SELECT attnum FROM pg_attribute"
                                + " WHERE attrelid = 't'::regclass"
                                + " AND attname = 'name')")
                        + ";storage=" + await query(c,
                        "SELECT attstorage FROM pg_attribute"
                                + " WHERE attrelid = 't'::regclass"
                                + " AND attname = 'name'")
                        + ";compression=" + await query(c,
                        "SELECT coalesce(attcompression::text, '')"
                                + " FROM pg_attribute"
                                + " WHERE attrelid = 't'::regclass"
                                + " AND attname = 'name'"));
            } finally {
                await c.rollback();
            }
        } finally {
            await c.close();
        }
    });

    it("policy roles are quoted individually", async () => {
        const ds = new PgTestDataSource();
        const schema = "canonr_" + process.hrtime.bigint().toString(16);
        const comma = "a,b_" + schema;
        const plain = "ac_" + schema;
        const c = await ds.connect();
        try {
            await c.setAutoCommit(false);
            try {
                await c.execute("CREATE ROLE " + dialect.quoteIdentifier(comma));
                await c.execute("CREATE ROLE " + dialect.quoteIdentifier(plain));
                await c.execute("CREATE SCHEMA "
                        + dialect.quoteIdentifier(schema));
                await c.execute("SET LOCAL search_path = "
                        + dialect.quoteIdentifier(schema));
                await c.execute("CREATE TABLE t (id bigint)");
                await c.execute("CREATE POLICY p_multi ON t FOR SELECT TO "
                        + dialect.quoteIdentifier(comma) + ","
                        + dialect.quoteIdentifier(plain) + " USING (true)");

                const snap = await dialect.readSnapshot(c, schema);
                const pol = snap.objects.get(
                        new ObjectId(ObjectKind.POLICY, "p_multi", "t"))!;
                assert.equal(pol.definition.canonical,
                        "cmd=r;permissive=true;roles="
                        + dialect.quoteIdentifier(comma) + ","
                        + dialect.quoteIdentifier(plain) + ";using=true");
                assert.equal(pol.attributes.get("createSql"),
                        "CREATE POLICY \"p_multi\" ON ${namespace}.\"t\""
                        + " FOR SELECT TO " + dialect.quoteIdentifier(comma)
                        + "," + dialect.quoteIdentifier(plain)
                        + " USING (true)");
            } finally {
                await c.rollback();
            }
        } finally {
            await c.close();
        }
    });
});
