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
                id(ObjectKind.COLUMN, "s", "t3"), id(ObjectKind.OTHER, "collation:cl")),
        new Edge("partition child -> parent", TYPES
                + "CREATE TABLE pt (k int) PARTITION BY RANGE (k); "
                + "CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (10)",
                id(ObjectKind.OTHER, "inherited:pc"), id(ObjectKind.OTHER, "partition:pt")),
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
