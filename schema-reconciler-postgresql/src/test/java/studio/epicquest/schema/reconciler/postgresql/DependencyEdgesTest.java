package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;

/** Every dependency edge AGENTS.md lists for {@code PgCatalog.loadDependencies} is recorded in {@code dependsOn}. */
class DependencyEdgesTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final PostgreSqlDialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    private record Edge(String label, String setup, ObjectId from, ObjectId to) { }

    private static ObjectId id(ObjectKind kind, String name) {
        return new ObjectId(kind, name);
    }

    private static ObjectId id(ObjectKind kind, String name, String parent) {
        return new ObjectId(kind, name, parent);
    }

    private static final String TYPES = "CREATE TYPE mood AS ENUM ('a','b'); "
            + "CREATE FUNCTION fn(int) RETURNS int LANGUAGE sql IMMUTABLE AS $$ SELECT $1 $$; "
            + "CREATE TABLE t (id int PRIMARY KEY, m mood, note text); ";

    private static final Edge[] EDGES = {
        new Edge("view -> relation", TYPES + "CREATE VIEW v AS SELECT id FROM t",
                id(ObjectKind.VIEW, "v"), id(ObjectKind.TABLE, "t")),
        new Edge("view -> routine", TYPES + "CREATE VIEW v AS SELECT fn(id) AS x FROM t",
                id(ObjectKind.VIEW, "v"), id(ObjectKind.ROUTINE, "fn(integer)")),
        new Edge("view -> type", TYPES + "CREATE VIEW v AS SELECT 'a'::mood AS x",
                id(ObjectKind.VIEW, "v"), id(ObjectKind.TYPE, "mood")),
        new Edge("routine -> relation", TYPES
                + "CREATE FUNCTION rr() RETURNS bigint LANGUAGE sql BEGIN ATOMIC SELECT count(*) FROM t; END",
                id(ObjectKind.ROUTINE, "rr()"), id(ObjectKind.TABLE, "t")),
        new Edge("routine -> routine", TYPES
                + "CREATE FUNCTION rp() RETURNS int LANGUAGE sql BEGIN ATOMIC SELECT fn(1); END",
                id(ObjectKind.ROUTINE, "rp()"), id(ObjectKind.ROUTINE, "fn(integer)")),
        new Edge("routine -> type", TYPES
                + "CREATE FUNCTION rt(m mood) RETURNS int LANGUAGE sql AS $$ SELECT 1 $$",
                id(ObjectKind.ROUTINE, "rt(mood)"), id(ObjectKind.TYPE, "mood")),
        new Edge("index -> routine", TYPES + "CREATE INDEX ix ON t (fn(id))",
                id(ObjectKind.INDEX, "ix", "t"), id(ObjectKind.ROUTINE, "fn(integer)")),
        new Edge("trigger -> routine", TYPES
                + "CREATE FUNCTION trg() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$; "
                + "CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW EXECUTE FUNCTION trg()",
                id(ObjectKind.TRIGGER, "tr", "t"), id(ObjectKind.ROUTINE, "trg()")),
        new Edge("check constraint -> routine", TYPES + "ALTER TABLE t ADD CONSTRAINT ck CHECK (fn(id) > 0)",
                id(ObjectKind.CHECK, "ck", "t"), id(ObjectKind.ROUTINE, "fn(integer)")),
        new Edge("check constraint -> type", TYPES + "ALTER TABLE t ADD CONSTRAINT ck CHECK (m <> 'b'::mood)",
                id(ObjectKind.CHECK, "ck", "t"), id(ObjectKind.TYPE, "mood")),
        new Edge("foreign key -> class", TYPES + "CREATE TABLE t2 (tid int, CONSTRAINT fk FOREIGN KEY (tid) REFERENCES t (id))",
                id(ObjectKind.FOREIGN_KEY, "fk", "t2"), id(ObjectKind.TABLE, "t")),
        new Edge("foreign key -> constraint", TYPES + "CREATE TABLE t2 (tid int, CONSTRAINT fk FOREIGN KEY (tid) REFERENCES t (id))",
                id(ObjectKind.FOREIGN_KEY, "fk", "t2"), id(ObjectKind.PRIMARY_KEY, "t_pkey", "t")),
        new Edge("column default -> relation (sequence)", TYPES
                + "CREATE SEQUENCE sq; CREATE TABLE t3 (n bigint DEFAULT nextval('sq'))",
                id(ObjectKind.COLUMN, "n", "t3"), id(ObjectKind.SEQUENCE, "sq")),
        new Edge("column default -> routine", TYPES + "CREATE TABLE t3 (n int DEFAULT fn(1))",
                id(ObjectKind.COLUMN, "n", "t3"), id(ObjectKind.ROUTINE, "fn(integer)")),
        new Edge("column default -> type", TYPES + "CREATE TABLE t3 (m2 mood DEFAULT 'a'::mood)",
                id(ObjectKind.COLUMN, "m2", "t3"), id(ObjectKind.TYPE, "mood")),
        new Edge("column -> type", TYPES,
                id(ObjectKind.COLUMN, "m", "t"), id(ObjectKind.TYPE, "mood")),
        new Edge("table -> type", TYPES,
                id(ObjectKind.TABLE, "t"), id(ObjectKind.TYPE, "mood")),
        new Edge("column -> collation", TYPES
                + "CREATE COLLATION cl (provider = libc, locale = 'C'); "
                + "CREATE TABLE t3 (s text COLLATE cl)",
                id(ObjectKind.COLUMN, "s", "t3"), id(ObjectKind.COLLATION, "cl")),
        new Edge("partition child -> parent", TYPES
                + "CREATE TABLE pt (k int) PARTITION BY RANGE (k); "
                + "CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (10)",
                id(ObjectKind.PARTITION, "pc", "pt"), id(ObjectKind.TABLE, "pt")),
        new Edge("comment -> table", TYPES + "COMMENT ON TABLE t IS 'x'",
                id(ObjectKind.COMMENT, "t"), id(ObjectKind.TABLE, "t")),
        new Edge("comment -> column", TYPES + "COMMENT ON COLUMN t.note IS 'x'",
                id(ObjectKind.COMMENT, "note", "t"), id(ObjectKind.COLUMN, "note", "t")),
        new Edge("policy -> table", TYPES
                + "ALTER TABLE t ENABLE ROW LEVEL SECURITY; "
                + "CREATE POLICY p_sel ON t FOR SELECT USING (id > 0)",
                id(ObjectKind.POLICY, "p_sel", "t"), id(ObjectKind.TABLE, "t")),
        new Edge("domain -> base type", TYPES + "CREATE DOMAIN dm AS mood",
                id(ObjectKind.DOMAIN, "dm"), id(ObjectKind.TYPE, "mood")),
        new Edge("statistics -> table", TYPES
                + "CREATE STATISTICS st ON id, note FROM t",
                id(ObjectKind.STATISTICS, "st"), id(ObjectKind.TABLE, "t")),
        new Edge("rule -> table", TYPES
                + "CREATE RULE rl AS ON INSERT TO t DO NOTHING",
                id(ObjectKind.RULE, "rl", "t"), id(ObjectKind.TABLE, "t")),
        // a range is a managed TYPE whose generated family folds into it:
        // the multirange, its constructors and the range→multirange cast
        // are never objects — a routine calling the folded constructor
        // resolves to the range instead (5.8.3 ordering still holds)
        new Edge("range type -> subtype", TYPES
                + "CREATE TYPE r AS RANGE (subtype = mood)",
                id(ObjectKind.TYPE, "r"), id(ObjectKind.TYPE, "mood")),
        new Edge("routine -> folded range constructor", TYPES
                + "CREATE TYPE r AS RANGE (subtype = mood);"
                + "CREATE FUNCTION mk(x r) RETURNS r_multirange"
                + " LANGUAGE sql IMMUTABLE AS 'SELECT r_multirange(x)'",
                id(ObjectKind.ROUTINE, "mk(r)"), id(ObjectKind.TYPE, "r")),
        // text-search and operator objects are managed, so dependents must
        // order after them — the legs' refclassid filters cover pg_ts_* and
        // pg_op* alongside rel/proc/type
        new Edge("view -> text search configuration", TYPES
                + "CREATE TEXT SEARCH CONFIGURATION c1 (parser = pg_catalog.default); "
                + "CREATE VIEW v AS SELECT to_tsvector('c1', note) AS s FROM t",
                id(ObjectKind.VIEW, "v"),
                id(ObjectKind.TEXT_SEARCH_CONFIGURATION, "c1")),
        new Edge("routine -> text search configuration", TYPES
                + "CREATE TEXT SEARCH CONFIGURATION c1 (parser = pg_catalog.default); "
                + "CREATE FUNCTION f() RETURNS tsvector LANGUAGE sql"
                + " BEGIN ATOMIC SELECT to_tsvector('c1','x'); END",
                id(ObjectKind.ROUTINE, "f()"),
                id(ObjectKind.TEXT_SEARCH_CONFIGURATION, "c1")),
        new Edge("column default -> text search configuration", TYPES
                + "CREATE TEXT SEARCH CONFIGURATION c1 (parser = pg_catalog.default); "
                + "CREATE TABLE t3 (v tsvector DEFAULT to_tsvector('c1','x'))",
                id(ObjectKind.COLUMN, "v", "t3"),
                id(ObjectKind.TEXT_SEARCH_CONFIGURATION, "c1")),
        new Edge("view -> operator", TYPES
                + "CREATE OPERATOR === (LEFTARG = int, RIGHTARG = int, PROCEDURE = int4eq); "
                + "CREATE VIEW v AS SELECT id === id AS x FROM t",
                id(ObjectKind.VIEW, "v"),
                id(ObjectKind.OPERATOR, "===(integer,integer)")),
        new Edge("check constraint -> operator", TYPES
                + "CREATE OPERATOR === (LEFTARG = int, RIGHTARG = int, PROCEDURE = int4eq); "
                + "ALTER TABLE t ADD CONSTRAINT ck CHECK (id === id)",
                id(ObjectKind.CHECK, "ck", "t"),
                id(ObjectKind.OPERATOR, "===(integer,integer)")),
    };

    @Test
    void everyListedEdgeIsRecorded() throws Exception {
        final var ds = PgTestDatabase.dataSource();
        final var failures = new StringBuilder();
        for (final var edge : EDGES) {
            final var schema = "dep_" + Long.toHexString(System.nanoTime()) + "_"
                    + SEQUENCE.incrementAndGet();
            final var quoted = dialect.quoteIdentifier(schema);
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                c.setAutoCommit(false);
                try {
                    s.execute("CREATE SCHEMA " + quoted);
                    s.execute("SET LOCAL search_path = " + quoted);
                    s.execute(edge.setup());
                    final var snapshot = dialect.readSnapshot(c, schema);
                    final var from = snapshot.objects().get(edge.from());
                    if (from == null) {
                        failures.append(edge.label()).append(": source ").append(edge.from())
                                .append(" not in ").append(snapshot.objects().keySet()).append('\n');
                    } else if (!from.dependsOn().contains(edge.to())) {
                        failures.append(edge.label()).append(": ").append(edge.from())
                                .append(" dependsOn ").append(from.dependsOn())
                                .append(", missing ").append(edge.to()).append('\n');
                    }
                } finally {
                    c.rollback();
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }
}
