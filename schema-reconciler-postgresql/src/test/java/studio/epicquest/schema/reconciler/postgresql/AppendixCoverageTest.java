package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
        {"CREATE TABLE t (id int, CONSTRAINT t_ex EXCLUDE (id WITH =))", "constraint:"},
        {"CREATE TYPE rng AS RANGE (subtype = int)", "type:"},
        {"CREATE TYPE comp AS (a int, b text)", "composite:"},
        {"CREATE TABLE pt (id int) PARTITION BY RANGE (id)", "partition:"},
        {"CREATE TABLE ptp (id int) PARTITION BY RANGE (id);"
                + " CREATE TABLE pch PARTITION OF ptp FOR VALUES FROM (0) TO (100)",
                "partition:pch"},
        {"CREATE TABLE pt2 (k int) PARTITION BY RANGE (k); CREATE INDEX pix ON pt2 (k)", "partindex:"},
        {"CREATE TABLE parent (id int); CREATE TABLE child () INHERITS (parent)", "inherited:"},
        {"CREATE AGGREGATE mysum (int) (sfunc = int4pl, stype = int, initcond = '0')", "routine:"},
        {"CREATE TABLE r (id int); CREATE RULE rl AS ON INSERT TO r DO ALSO NOTHING", "rule:"},
        {"CREATE TABLE s (a int, b int); CREATE STATISTICS st ON a, b FROM s", "statistics:"},
        {"CREATE COLLATION coll (provider = libc, locale = 'C')", "collation:"},
        {"CREATE CONVERSION conv FOR 'LATIN1' TO 'UTF8' FROM iso8859_1_to_utf8", "conversion:"},
        {"CREATE TEXT SEARCH DICTIONARY d (template = simple)", "fts-dict:"},
        {"CREATE TEXT SEARCH CONFIGURATION c (copy = simple)", "fts-config:"},
        {"CREATE OPERATOR === (leftarg = int, rightarg = int, function = int4eq)", "operator:"},
        {"CREATE OPERATOR FAMILY fam USING btree", "opfamily:"},
        {"CREATE COLLATION cc (provider = libc, locale = 'C'); COMMENT ON COLLATION cc IS 'x'",
                "comment:"},
        {"CREATE TABLE g (id int); GRANT SELECT ON g TO PUBLIC", "privileges:"},
        {"CREATE FOREIGN DATA WRAPPER fdw_cov; CREATE SERVER srv_cov FOREIGN DATA WRAPPER fdw_cov;"
                + " CREATE FOREIGN TABLE ft (a int) SERVER srv_cov", "foreign:"},
        {"CREATE TYPE ce AS ENUM ('a'); CREATE CAST (ce AS int4) WITH INOUT", "cast:"},
        {"CREATE ROLE role_cov_owner; CREATE TABLE ow (id int);"
                + " ALTER TABLE ow OWNER TO role_cov_owner", "ownership:"},
        {"CREATE TABLE pb (id int); CREATE PUBLICATION pub_cov FOR TABLE pb", "publication:"},
        {"CREATE TEXT SEARCH PARSER prs (START = prsd_start, GETTOKEN = prsd_nexttoken,"
                + " END = prsd_end, LEXTYPES = prsd_lextype)", "fts-parser:"},
        {"CREATE TEXT SEARCH TEMPLATE tmpl (INIT = dsimple_init, LEXIZE = dsimple_lexize)",
                "fts-template:"},
        // superuser-only: a stand-in transform function, and a catalog row in place of a
        // security-label provider module (none ships with a stock server)
        {"CREATE FUNCTION tfrom(internal) RETURNS internal LANGUAGE internal IMMUTABLE AS 'textin';"
                + " CREATE TRANSFORM FOR int4 LANGUAGE plpgsql (FROM SQL WITH FUNCTION tfrom(internal))",
                "transform:", "super"},
        {"CREATE TABLE lt (id int); INSERT INTO pg_seclabel (objoid, classoid, objsubid, provider, label)"
                + " VALUES ('lt'::regclass::oid, 'pg_class'::regclass::oid, 0, 'dummy', 'x')",
                "seclabel:", "super"},
        {"CREATE OPERATOR CLASS oc FOR TYPE int4 USING hash AS OPERATOR 1 =,"
                + " FUNCTION 1 hashint4(int4)", "opclass:"},
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
}
