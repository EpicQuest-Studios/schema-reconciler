package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Catalog dependency-edge regressions: ordering of changes against newly
 * discovered pg_depend edges, and external dependents on routines and
 * sequences (5.8.1, 5.8.3, 3.3).
 */
class PostgreSqlDependenciesTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long RUN_ID = System.nanoTime();

    private final DataSource ds = PgTestDatabase.dataSource();
    private final Dialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    private String namespace() throws Exception {
        final var name = "dep_" + Long.toHexString(RUN_ID) + "_" + SEQUENCE.incrementAndGet();
        PgTestDatabase.execute(ds, "CREATE SCHEMA " + dialect.quoteIdentifier(name));
        return name;
    }

    private void drop(String namespace) {
        try {
            PgTestDatabase.execute(ds, "DROP SCHEMA " + dialect.quoteIdentifier(namespace)
                    + " CASCADE");
        } catch (Exception ignored) {
            // cleanup best effort
        }
    }

    private SchemaReconciler reconciler(String ns, String script) {
        return reconciler(ns, script, ReconcilePolicy.defaults());
    }

    private SchemaReconciler reconciler(String ns, String script, ReconcilePolicy policy) {
        return SchemaReconciler.builder()
                .dataSource(ds)
                .namespace(ns)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect)
                .policy(policy)
                .build();
    }

    @Test
    @DisplayName("column default on a non-owned missing sequence orders the sequence first")
    void columnDefaultOnMissingSequence() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (id int)");
            final var report = reconciler(ns, """
                    CREATE SEQUENCE ${namespace}.s;
                    CREATE TABLE ${namespace}.t (id int, x bigint
                        DEFAULT nextval('${namespace}.s'::regclass));
                    """).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(ns, """
                    CREATE SEQUENCE ${namespace}.s;
                    CREATE TABLE ${namespace}.t (id int, x bigint
                        DEFAULT nextval('${namespace}.s'::regclass));
                    """).reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("a missing table whose column defaults to a missing sequence orders the sequence first")
    void tableCoveringColumnWithSequenceDefault() throws Exception {
        final var ns = namespace();
        try {
            final var report = reconciler(ns, """
                    CREATE SEQUENCE ${namespace}.s;
                    CREATE TABLE ${namespace}.t (id bigint
                        DEFAULT nextval('${namespace}.s'::regclass));
                    """).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("a missing check constraint on a missing routine orders the routine first")
    void checkConstraintOnMissingRoutine() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (x int)");
            final var report = reconciler(ns, """
                    CREATE FUNCTION ${namespace}.ok(int) RETURNS boolean
                        LANGUAGE sql IMMUTABLE AS $$SELECT $1 > 0$$;
                    CREATE TABLE ${namespace}.t (x int CHECK (${namespace}.ok(x)));
                    """).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("a column default calling a missing routine orders the routine first")
    void columnDefaultOnMissingRoutine() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + dialect.quoteIdentifier(ns)
                    + ".t (id int)");
            final var report = reconciler(ns, """
                    CREATE FUNCTION ${namespace}.def() RETURNS int
                        LANGUAGE sql IMMUTABLE AS $$SELECT 7$$;
                    CREATE TABLE ${namespace}.t (id int, x int DEFAULT ${namespace}.def());
                    """).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("an FK referencing a bare unique index snapshots the index and orders it first")
    void fkOnBareUniqueIndex() throws Exception {
        final var ns = namespace();
        final var script = """
                CREATE TABLE ${namespace}.a (id int PRIMARY KEY, tenant int NOT NULL);
                CREATE UNIQUE INDEX a_tenant_id ON ${namespace}.a (tenant, id);
                CREATE TABLE ${namespace}.b (a_tenant int NOT NULL, a_id int NOT NULL,
                    CONSTRAINT b_fk FOREIGN KEY (a_tenant, a_id)
                        REFERENCES ${namespace}.a (tenant, id));
                """;
        try {
            // an FK's conindid points at the referenced index: the index must
            // still appear in the snapshot and order before the FK
            final var report = reconciler(ns, script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(ns, script).reconcile().outcome());
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("an external view dependent on a recreated routine fails planning (5.8.3)")
    void externalViewOnRecreatedRoutine() throws Exception {
        final var ns = namespace();
        final var view = "ext_v_" + Long.toHexString(RUN_ID) + "_" + SEQUENCE.incrementAndGet();
        try {
            PgTestDatabase.execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".f_ret(int) RETURNS int LANGUAGE sql IMMUTABLE AS $$SELECT $1$$");
            PgTestDatabase.execute(ds, "CREATE VIEW public." + view
                    + " AS SELECT " + dialect.quoteIdentifier(ns) + ".f_ret(1) AS x");
            // the desired state changes the return type: the routine must be
            // dropped and recreated, which the external view blocks
            final var r = reconciler(ns, """
                    CREATE FUNCTION ${namespace}.f_ret(int) RETURNS bigint
                        LANGUAGE sql IMMUTABLE AS $$SELECT $1::bigint$$;
                    """);
            final var e = assertThrows(PlanningException.class, r::plan);
            assertTrue(e.getMessage().contains("public." + view), e.getMessage());
        } finally {
            PgTestDatabase.execute(ds, "DROP VIEW IF EXISTS public." + view);
            drop(ns);
        }
    }

    @Test
    @DisplayName("an external check constraint on a recreated routine fails planning (5.8.3)")
    void externalCheckOnRecreatedRoutine() throws Exception {
        final var ns = namespace();
        final var table = "ext_t_" + Long.toHexString(RUN_ID) + "_" + SEQUENCE.incrementAndGet();
        try {
            PgTestDatabase.execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".ok(int) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$SELECT $1 > 0$$");
            PgTestDatabase.execute(ds, "CREATE TABLE public." + table
                    + " (x int CHECK (" + dialect.quoteIdentifier(ns) + ".ok(x)))");
            final var r = reconciler(ns, """
                    CREATE FUNCTION ${namespace}.ok(int) RETURNS int
                        LANGUAGE sql IMMUTABLE AS $$SELECT 1$$;
                    """);
            assertThrows(PlanningException.class, r::plan);
        } finally {
            PgTestDatabase.execute(ds, "DROP TABLE IF EXISTS public." + table);
            drop(ns);
        }
    }

    @Test
    @DisplayName("an external trigger dependent on a recreated routine fails planning (5.8.3)")
    void externalTriggerOnRecreatedRoutine() throws Exception {
        final var ns = namespace();
        final var table = "ext_t_" + Long.toHexString(RUN_ID) + "_" + SEQUENCE.incrementAndGet();
        final var trigger = "trg_" + Long.toHexString(RUN_ID) + "_" + SEQUENCE.incrementAndGet();
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE public." + table + " (x int)");
            PgTestDatabase.execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".trg() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END$$");
            PgTestDatabase.execute(ds, "CREATE TRIGGER " + trigger
                    + " BEFORE INSERT ON public." + table
                    + " FOR EACH ROW EXECUTE FUNCTION " + dialect.quoteIdentifier(ns) + ".trg()");
            // a different return type forces a DROP + CREATE, which the
            // external trigger must block at plan time
            final var r = reconciler(ns, """
                    CREATE FUNCTION ${namespace}.trg() RETURNS int
                        LANGUAGE sql AS $$SELECT 1$$;
                    """);
            assertThrows(PlanningException.class, r::plan);
        } finally {
            PgTestDatabase.execute(ds, "DROP TABLE IF EXISTS public." + table);
            drop(ns);
        }
    }

    @Test
    @DisplayName("an external dictionary dependent on our template blocks its drop (5.8.3)")
    void externalDictOnDroppedTemplate() throws Exception {
        final var ns = namespace();
        final var dict = "ext_d_" + Long.toHexString(RUN_ID) + "_" + SEQUENCE.incrementAndGet();
        try {
            PgTestDatabase.execute(ds, "CREATE TEXT SEARCH TEMPLATE "
                    + dialect.quoteIdentifier(ns) + ".tmpl (LEXIZE = dsimple_lexize)");
            PgTestDatabase.execute(ds, "CREATE TEXT SEARCH DICTIONARY public." + dict
                    + " (TEMPLATE = " + dialect.quoteIdentifier(ns) + ".tmpl)");
            // the desired state drifts the template's lexize: it must be
            // dropped and recreated, which the external dictionary blocks
            final var r = reconciler(ns, """
                    CREATE TEXT SEARCH TEMPLATE ${namespace}.tmpl
                        (LEXIZE = dsnowball_lexize);
                    """);
            final var e = assertThrows(PlanningException.class, r::plan);
            assertTrue(e.getMessage().contains("public." + dict), e.getMessage());
        } finally {
            PgTestDatabase.execute(ds, "DROP TEXT SEARCH DICTIONARY IF EXISTS public." + dict);
            drop(ns);
        }
    }

    @Test
    @DisplayName("an unsupported cast dependent on a recreated routine fails planning (5.8.3)")
    void otherDependentOnRecreatedRoutine() throws Exception {
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE TYPE " + dialect.quoteIdentifier(ns)
                    + ".mood AS ENUM ('a','b')");
            PgTestDatabase.execute(ds, "CREATE FUNCTION " + dialect.quoteIdentifier(ns)
                    + ".mk(int) RETURNS " + dialect.quoteIdentifier(ns)
                    + ".mood LANGUAGE sql IMMUTABLE AS $$SELECT 'a'::"
                    + dialect.quoteIdentifier(ns) + ".mood$$");
            PgTestDatabase.execute(ds, "CREATE CAST (int AS "
                    + dialect.quoteIdentifier(ns) + ".mood) WITH FUNCTION "
                    + dialect.quoteIdentifier(ns) + ".mk(int)");
            // a different return type forces a DROP + CREATE, which the
            // unrecreatable cast must block at plan time (P4)
            final var r = reconciler(ns, """
                    CREATE TYPE ${namespace}.mood AS ENUM ('a','b');
                    CREATE FUNCTION ${namespace}.mk(int) RETURNS int
                        LANGUAGE sql IMMUTABLE AS $$SELECT 7$$;
                    """);
            final var e = assertThrows(PlanningException.class, r::plan);
            assertTrue(e.getMessage().contains("integer->mood"), e.getMessage());
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("every construct a desired script creates is detected, applied or reported (5.4)")
    void unsupportedConstructsDetected() throws Exception {
        final var ns = namespace();
        try {
            final var policy = ReconcilePolicy.defaults()
                    .withUnsupported(Decision.REPORT);
            PgTestDatabase.execute(ds, "DROP ROLE IF EXISTS rcl_tmp_role");
            PgTestDatabase.execute(ds, "CREATE ROLE rcl_tmp_role");
            final var report = reconciler(ns, """
                    CREATE TABLE ${namespace}.t (a int, b int, ts tstzrange);
                    CREATE TABLE ${namespace}.i (x int) INHERITS (${namespace}.t);
                    CREATE TYPE ${namespace}.mood AS ENUM ('ok');
                    CREATE TYPE ${namespace}.span AS RANGE (subtype = int4);
                    CREATE TABLE ${namespace}.cap (a int);
                    CREATE TABLE ${namespace}.cac () INHERITS (${namespace}.cap);
                    CREATE CAST (${namespace}.cac AS ${namespace}.cap) WITH INOUT;
                    CREATE STATISTICS ${namespace}.st ON a, b FROM ${namespace}.t;
                    COMMENT ON TABLE ${namespace}.i IS 'noted';
                    CREATE RULE r1 AS ON INSERT TO ${namespace}.t DO INSTEAD NOTHING;
                    ALTER TABLE ${namespace}.t ADD CONSTRAINT ex
                        EXCLUDE USING gist (ts WITH &&);
                    ALTER TABLE ${namespace}.t OWNER TO rcl_tmp_role;
                    GRANT SELECT ON ${namespace}.i TO rcl_tmp_role;
                    """, policy).reconcile();
            final var names = report.unsupported().stream()
                    .map(d -> d.id().name()).toList();
            for (final var expected : new String[]{
                    "i",                     // inherited table
                    "cast:",                 // enums and ranges are managed
                    "comment:i", "privileges:i"}) {
                assertTrue(names.stream().anyMatch(n -> n.contains(expected)),
                        expected + " missing from " + names);
            }
            // the OWNERSHIP change actually assigned t to the role — shed
            // its objects before dropping it
            PgTestDatabase.execute(ds, "DROP OWNED BY rcl_tmp_role");
            PgTestDatabase.execute(ds, "DROP ROLE IF EXISTS rcl_tmp_role");
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("a sequence bound to a publication is detected on PostgreSQL 19 (5.4)")
    void sequencePublicationBindingDetected() throws Exception {
        int major;
        try (var conn = ds.getConnection()) {
            major = conn.getMetaData().getDatabaseMajorVersion();
        }
        assumeTrue(major >= 19, "pg_publication_sequences exists on PostgreSQL 19+");
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE SEQUENCE " + dialect.quoteIdentifier(ns) + ".sq");
            PgTestDatabase.execute(ds, "CREATE PUBLICATION dep_pub FOR SEQUENCE "
                    + dialect.quoteIdentifier(ns) + ".sq");
            try (var conn = ds.getConnection()) {
                conn.setAutoCommit(false);
                final var snapshot = dialect.readSnapshot(conn, ns);
                assertTrue(snapshot.objects().keySet().stream()
                                .anyMatch(id -> id.kind() == ObjectKind.PUBLICATION
                                        && id.name().equals("dep_pub")),
                        "sequence publication binding missing: "
                                + snapshot.objects().keySet());
            }
        } finally {
            PgTestDatabase.execute(ds, "DROP PUBLICATION IF EXISTS dep_pub");
            drop(ns);
        }
    }

    @Test
    @DisplayName("a FOR TABLES IN SCHEMA publication binding is detected on PostgreSQL 15+ (5.4)")
    void schemaPublicationBindingDetected() throws Exception {
        int major;
        try (var conn = ds.getConnection()) {
            major = conn.getMetaData().getDatabaseMajorVersion();
        }
        assumeTrue(major >= 15, "pg_publication_namespace exists on PostgreSQL 15+");
        final var ns = namespace();
        try {
            PgTestDatabase.execute(ds, "CREATE PUBLICATION dep_pub_ns FOR TABLES IN SCHEMA "
                    + dialect.quoteIdentifier(ns));
            try (var conn = ds.getConnection()) {
                conn.setAutoCommit(false);
                final var snapshot = dialect.readSnapshot(conn, ns);
                assertTrue(snapshot.objects().keySet().stream()
                                .anyMatch(id -> id.kind() == ObjectKind.PUBLICATION
                                        && id.name().equals("dep_pub_ns")),
                        "schema publication binding missing: "
                                + snapshot.objects().keySet());
            }
        } finally {
            PgTestDatabase.execute(ds, "DROP PUBLICATION IF EXISTS dep_pub_ns");
            drop(ns);
        }
    }

    @Test
    @DisplayName("partition-inherited constraints and cloned triggers are not standalone objects (5.4)")
    void partitionInheritedObjectsDoNotLoad() throws Exception {
        final var ns = namespace();
        final var q = dialect.quoteIdentifier(ns);
        try {
            PgTestDatabase.execute(ds, "CREATE TABLE " + q + ".pt (id int, v text)"
                    + " PARTITION BY RANGE (id);"
                    + " CREATE TABLE " + q + ".p1 PARTITION OF " + q + ".pt"
                    + " FOR VALUES FROM (0) TO (1000);"
                    + " ALTER TABLE " + q + ".pt ADD PRIMARY KEY (id);"
                    + " ALTER TABLE " + q + ".pt ADD CONSTRAINT chk CHECK (v IS NOT NULL);"
                    + " CREATE TRIGGER trg BEFORE INSERT ON " + q + ".pt FOR EACH ROW"
                    + " EXECUTE FUNCTION suppress_redundant_updates_trigger()");
            try (var conn = ds.getConnection()) {
                conn.setAutoCommit(false);
                final var snapshot = dialect.readSnapshot(conn, ns);
                // the parent objects load normally — the inherited copies on the
                // partition must not: they are not standalone objects (cannot be
                // created or dropped directly) and would only report UNEXPECTED
                assertTrue(snapshot.objects().keySet().stream()
                                .anyMatch(id -> "chk".equals(id.name())),
                        "parent check missing: " + snapshot.objects().keySet());
                assertTrue(snapshot.objects().keySet().stream()
                                .anyMatch(id -> "trg".equals(id.name())),
                        "parent trigger missing: " + snapshot.objects().keySet());
                final var onPartition = snapshot.objects().keySet().stream()
                        .filter(id -> id.parent().filter("p1"::equals).isPresent())
                        .filter(id -> id.kind() != ObjectKind.COLUMN)
                        .toList();
                assertTrue(onPartition.isEmpty(),
                        "inherited objects on the partition must not load: " + onPartition);
            }
        } finally {
            drop(ns);
        }
    }

    @Test
    @DisplayName("changes depending on an unsupported object demote to REPORT (3.3)")
    void dependentsOfUnsupportedTypesDemote() throws Exception {
        final var ns = namespace();
        try {
            final var policy = ReconcilePolicy.defaults()
                    .withUnsupported(Decision.REPORT);
            final var report = reconciler(ns, """
                    CREATE TABLE ${namespace}.cap (a int);
                    CREATE TABLE ${namespace}.cac () INHERITS (${namespace}.cap);
                    CREATE TABLE ${namespace}.t (x ${namespace}.cac);
                    """, policy).reconcile();
            // cap applies; the table typed on the unsupported inherited
            // child's rowtype demotes
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.reported().stream()
                    .anyMatch(c -> c.target().name().equals("t")),
                    "the table change must be demoted, not attempted: "
                            + report.reported());
            // the table must NOT have been created
            PgTestDatabase.execute(ds, "SELECT 1 FROM pg_class WHERE relname = 't'");
            try (var c = ds.getConnection(); var s = c.createStatement();
                    final var rs = s.executeQuery("SELECT count(*) FROM pg_class c "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = '" + ns + "' AND c.relname = 't'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "the demoted change must not run");
            }
        } finally {
            drop(ns);
        }
    }
}
