package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.postgresql.internal.PgSql;

/** Spec A.5: each canonical definition is the engine's {@code pg_get_*def} text, normalised (5.3). */
class CanonicalDefinitionTest {

    private final PostgreSqlDialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    private static String query(java.sql.Statement s, String sql) throws Exception {
        try (var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void canonicalDefinitionsAreTheNormalisedEngineDefinitions() throws Exception {
        final var schema = "canon_" + Long.toHexString(System.nanoTime());
        final var q = dialect.quoteIdentifier(schema);
        try (var c = PgTestDatabase.dataSource().getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE SCHEMA " + q);
                s.execute("SET LOCAL search_path = " + q);
                s.execute("CREATE TABLE t (id bigint PRIMARY KEY, name text NOT NULL DEFAULT 'x',"
                        + " ref bigint, CONSTRAINT t_chk CHECK (id > 0))");
                s.execute("CREATE INDEX t_name ON t (lower(name)) WHERE id > 5");
                s.execute("CREATE VIEW v AS SELECT id, name FROM t");
                s.execute("CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql AS $$ SELECT a + 1 $$");
                s.execute("CREATE FUNCTION trg() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN RETURN NEW; END $$");
                s.execute("CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION trg()");
                s.execute("CREATE TYPE mood AS ENUM ('b', 'a')");
                s.execute("CREATE MATERIALIZED VIEW mv AS SELECT id FROM t");
                s.execute("CREATE DOMAIN loctext AS text COLLATE \"C\""
                        + " NOT NULL DEFAULT 'x' CHECK (VALUE <> '')");
                s.execute("ALTER TABLE t ENABLE ROW LEVEL SECURITY");
                s.execute("CREATE POLICY p_sel ON t AS RESTRICTIVE FOR SELECT"
                        + " TO PUBLIC USING (id > 0)");
                s.execute("COMMENT ON TABLE t IS 'kept'");

                final var snapshot = dialect.readSnapshot(c, schema);
                final var ns = schema;

                final var matview = snapshot.objects().get(
                        new ObjectId(ObjectKind.MATERIALIZED_VIEW, "mv"));
                assertTrue(matview.definition().canonical().startsWith(PgSql.normalize(
                        "viewdef=" + query(s,
                                "SELECT pg_get_viewdef('mv'::regclass, true)"), ns)),
                        matview.definition().canonical());
                assertTrue(matview.definition().canonical()
                        .contains(";populated=true"), matview.definition().canonical());

                final var mood = snapshot.objects().get(new ObjectId(ObjectKind.TYPE, "mood"));
                assertEquals("typtype=e;labels='b','a'", mood.definition().canonical());

                final var loctext = snapshot.objects().get(
                        new ObjectId(ObjectKind.DOMAIN, "loctext"));
                assertEquals("basetype=text;notnull=true;collation=\"C\";default="
                        + query(s, "SELECT pg_get_expr(typdefaultbin, 0)"
                                + " FROM pg_type WHERE typname = 'loctext'"
                                + " AND typnamespace = current_schema()::regnamespace")
                        + ";check.loctext_check=" + query(s,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE contypid = 'loctext'::regtype AND contype = 'c'"),
                        loctext.definition().canonical());

                final var policy = snapshot.objects().get(
                        new ObjectId(ObjectKind.POLICY, "p_sel", "t"));
                assertEquals("cmd=r;permissive=false;roles=PUBLIC;using="
                        + query(s, "SELECT pg_get_expr(polqual, polrelid)"
                                + " FROM pg_policy WHERE polname = 'p_sel'"
                                + " AND polrelid = 't'::regclass"),
                        policy.definition().canonical());

                final var table = snapshot.objects().get(new ObjectId(ObjectKind.TABLE, "t"));
                assertTrue(table.definition().canonical().contains(";rls=true"),
                        table.definition().canonical());

                final var comment = snapshot.objects().get(
                        new ObjectId(ObjectKind.COMMENT, "t"));
                assertEquals("kept", comment.definition().canonical());

                final var index = snapshot.objects().get(new ObjectId(ObjectKind.INDEX, "t_name", "t"));
                assertEquals(PgSql.normalize(query(s,
                        "SELECT pg_get_indexdef('t_name'::regclass)"), ns),
                        index.definition().canonical());

                final var check = snapshot.objects().get(new ObjectId(ObjectKind.CHECK, "t_chk", "t"));
                assertEquals(PgSql.normalize(query(s,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                                + " WHERE conname = 't_chk' AND conrelid = 't'::regclass"), ns),
                        check.definition().canonical());

                final var pk = snapshot.objects().get(new ObjectId(ObjectKind.PRIMARY_KEY, "t_pkey", "t"));
                assertEquals(PgSql.normalize(query(s,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 't_pkey'"), ns),
                        pk.definition().canonical());

                final var view = snapshot.objects().get(new ObjectId(ObjectKind.VIEW, "v"));
                assertTrue(view.definition().canonical().startsWith(PgSql.normalize("viewdef="
                        + query(s, "SELECT pg_get_viewdef('v'::regclass, true)"), ns)),
                        view.definition().canonical());

                final var routine = snapshot.objects().get(new ObjectId(ObjectKind.ROUTINE, "f(integer)"));
                assertEquals(PgSql.normalize(query(s,
                        "SELECT pg_get_functiondef('f(int)'::regprocedure)"), ns),
                        routine.definition().canonical());

                final var trigger = snapshot.objects().get(new ObjectId(ObjectKind.TRIGGER, "tr", "t"));
                assertEquals(PgSql.normalize(query(s,
                        "SELECT pg_get_triggerdef(oid, true) FROM pg_trigger WHERE tgname = 'tr'"), ns)
                        + ";enabled=O", trigger.definition().canonical());

                final var column = snapshot.objects().get(new ObjectId(ObjectKind.COLUMN, "name", "t"));
                assertEquals("type=" + query(s,
                        "SELECT format_type(atttypid, atttypmod) FROM pg_attribute"
                                + " WHERE attrelid = 't'::regclass AND attname = 'name'")
                        + ";notnull=true;default=" + query(s,
                        "SELECT pg_get_expr(adbin, adrelid) FROM pg_attrdef"
                                + " WHERE adrelid = 't'::regclass"
                                + " AND adnum = (SELECT attnum FROM pg_attribute"
                                + " WHERE attrelid = 't'::regclass AND attname = 'name')"),
                        column.definition().canonical());
            } finally {
                c.rollback();
            }
        }
    }
}
