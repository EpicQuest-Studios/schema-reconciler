package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;

/**
 * Every construct that spec Appendix C lists as "detected but unsupported"
 * and that a test database can create must surface in the snapshot as an
 * {@code OTHER} object with the documented category prefix (A.5).
 */
class AppendixCoverageTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final PostgreSqlDialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    /** {setup SQL (run in the schema, search_path bound), expected OTHER name prefix}.
     *  A third element {@code "super"} marks cases that need a superuser. */
    private static final String[][] CASES = {
        {"CREATE TABLE pt2 (k int) PARTITION BY RANGE (k); CREATE INDEX pix ON pt2 (k)", "partindex:"},
        {"CREATE TABLE parent (id int); CREATE TABLE child () INHERITS (parent)", "inherited:"},
        {"CREATE DEFAULT CONVERSION conv FOR 'LATIN1' TO 'UTF8' FROM iso8859_1_to_utf8",
                "conversion:"},
        {"CREATE TABLE cmtp (id int); CREATE TABLE cmtc () INHERITS (cmtp);"
                + " COMMENT ON TABLE cmtc IS 'x'", "comment:"},
        {"CREATE TABLE gp (id int); CREATE TABLE gc () INHERITS (gp);"
                + " GRANT SELECT ON gc TO PUBLIC", "privileges:"},
        // a cast needs an in-namespace anchor that is not managed — the
        // rowtypes of an inheritance pair are the last unmanaged anchor:
        // an inout cast between them stays cast: OTHER
        {"CREATE TABLE cast_p (a int); CREATE TABLE cast_c () INHERITS (cast_p);"
                + " CREATE CAST (cast_c AS cast_p) WITH INOUT", "cast:"},
        {"CREATE ROLE role_cov_owner; CREATE TABLE ow_p (id int);"
                + " CREATE TABLE ow_c () INHERITS (ow_p);"
                + " ALTER TABLE ow_c OWNER TO role_cov_owner", "ownership:"},
        // superuser-only: a stand-in transform function, and a catalog row in place of a
        // security-label provider module (none ships with a stock server)
        {"CREATE FUNCTION tfrom(internal) RETURNS internal LANGUAGE internal IMMUTABLE AS 'textin';"
                + " CREATE TRANSFORM FOR int4 LANGUAGE plpgsql (FROM SQL WITH FUNCTION tfrom(internal))",
                "transform:", "super"},
        {"CREATE TABLE lp (id int); CREATE TABLE lc () INHERITS (lp);"
                + " INSERT INTO pg_seclabel (objoid, classoid, objsubid, provider, label)"
                + " VALUES ((SELECT oid FROM pg_class WHERE relname = 'lc'),"
                + " 'pg_class'::regclass::oid, 0, 'dummy', 'x')",
                "seclabel:", "super"},
    };

    private static boolean superuser(java.sql.Statement s) throws SQLException {
        try (var rs = s.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    @Test
    void detectedButUnsupportedConstructsSurfaceAsOther() throws Exception {
        final var ds = PgTestDatabase.dataSource();
        for (final var testCase : CASES) {
            final var schema = "cov_" + Long.toHexString(System.nanoTime()) + "_"
                    + SEQUENCE.incrementAndGet();
            final var quoted = dialect.quoteIdentifier(schema);
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                c.setAutoCommit(false);
                if (testCase.length > 2 && !superuser(s)) {
                    continue;
                }
                try {
                    s.execute("CREATE SCHEMA " + quoted);
                    s.execute("SET LOCAL search_path = " + quoted);
                    s.execute(testCase[0]);
                    final var snapshot = dialect.readSnapshot(c, schema);
                    assertTrue(snapshot.objects().keySet().stream().anyMatch(id ->
                                    id.kind() == ObjectKind.OTHER
                                            && id.name().startsWith(testCase[1])),
                            testCase[0] + " -> expected an OTHER object prefixed "
                                    + testCase[1] + " in " + snapshot.objects().keySet());
                } catch (SQLException e) {
                    throw new AssertionError(testCase[0] + " failed: " + e.getMessage(), e);
                } finally {
                    c.rollback();
                }
            }
        }
    }

    @Test
    void labelOnManagedTargetIsManaged() throws Exception {
        // superuser-only: a catalog row stands in for a security-label
        // provider module — none ships with a stock server
        final var ds = PgTestDatabase.dataSource();
        final var schema = "cov_" + Long.toHexString(System.nanoTime())
                + "_" + SEQUENCE.incrementAndGet();
        final var quoted = dialect.quoteIdentifier(schema);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            org.junit.jupiter.api.Assumptions.assumeTrue(superuser(s));
            try {
                s.execute("CREATE SCHEMA " + quoted);
                s.execute("SET LOCAL search_path = " + quoted);
                s.execute("CREATE TABLE lt (id int)");
                s.execute("INSERT INTO pg_seclabel (objoid, classoid, objsubid, provider, label)"
                        + " VALUES ('lt'::regclass::oid, 'pg_class'::regclass::oid, 0,"
                        + " 'dummy', 'x')");
                final var snapshot = dialect.readSnapshot(c, schema);
                final var label = snapshot.objects().entrySet().stream()
                        .filter(e -> e.getKey().kind() == ObjectKind.SECURITY_LABEL)
                        .findFirst().orElseThrow(() -> new AssertionError(
                                "no SECURITY_LABEL in " + snapshot.objects().keySet()));
                assertTrue(label.getKey().name().equals("dummy:lt"),
                        "expected dummy:lt, got " + label.getKey().name());
                assertTrue(label.getValue().definition().canonical().equals("x"),
                        "expected canonical label text 'x'");
                assertTrue(label.getValue().dependsOn().stream()
                                .anyMatch(d -> d.kind() == ObjectKind.TABLE
                                        && d.name().equals("lt")),
                        "expected a dependency on TABLE lt");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void labelOnColumnIsManagedAndParented() throws Exception {
        // superuser-only catalog row like above — a column label mirrors
        // the column: parented SECURITY_LABEL (spec 4.1)
        final var ds = PgTestDatabase.dataSource();
        final var schema = "cov_" + Long.toHexString(System.nanoTime())
                + "_" + SEQUENCE.incrementAndGet();
        final var quoted = dialect.quoteIdentifier(schema);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            org.junit.jupiter.api.Assumptions.assumeTrue(superuser(s));
            try {
                s.execute("CREATE SCHEMA " + quoted);
                s.execute("SET LOCAL search_path = " + quoted);
                s.execute("CREATE TABLE lt (id int)");
                s.execute("INSERT INTO pg_seclabel (objoid, classoid, objsubid, provider, label)"
                        + " VALUES ('lt'::regclass::oid, 'pg_class'::regclass::oid, 1,"
                        + " 'dummy', 'y')");
                final var snapshot = dialect.readSnapshot(c, schema);
                final var label = snapshot.objects().keySet().stream()
                        .filter(id -> id.kind() == ObjectKind.SECURITY_LABEL)
                        .findFirst().orElseThrow(() -> new AssertionError(
                                "no SECURITY_LABEL in " + snapshot.objects().keySet()));
                assertTrue(label.name().equals("dummy:id") && label.parent().orElse("").equals("lt"),
                        "expected parented dummy:id, got " + label);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void commentOnPartitionAndForeignTableColumnIsManaged() throws Exception {
        // partition and foreign-table columns are managed COLUMN objects —
        // their comments mirror like a table column's (A.5)
        final var ds = PgTestDatabase.dataSource();
        final var schema = "cov_" + Long.toHexString(System.nanoTime())
                + "_" + SEQUENCE.incrementAndGet();
        final var quoted = dialect.quoteIdentifier(schema);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("CREATE SCHEMA " + quoted);
                s.execute("SET LOCAL search_path = " + quoted);
                s.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw");
                s.execute("CREATE TABLE pp (id int) PARTITION BY RANGE (id);"
                        + " CREATE TABLE pc PARTITION OF pp FOR VALUES FROM (0) TO (100);"
                        + " COMMENT ON COLUMN pc.id IS 'pc-col';"
                        + " CREATE SERVER fsrv FOREIGN DATA WRAPPER postgres_fdw;"
                        + " CREATE FOREIGN TABLE ft (id int) SERVER fsrv;"
                        + " COMMENT ON COLUMN ft.id IS 'ft-col'");
                final var snapshot = dialect.readSnapshot(c, schema);
                final var comments = snapshot.objects().keySet().stream()
                        .filter(id -> id.kind() == ObjectKind.COMMENT)
                        .map(ObjectId::toString).toList();
                assertTrue(comments.stream().anyMatch(c2 -> c2.equals("COMMENT pc.id")),
                        "expected COMMENT pc.id in " + comments);
                assertTrue(comments.stream().anyMatch(c2 -> c2.equals("COMMENT ft.id")),
                        "expected COMMENT ft.id in " + comments);
            } finally {
                c.rollback();
            }
        }
    }
}
