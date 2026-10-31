package studio.epicquest.schema.reconciler.postgresql.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredStateException;

/**
 * Anchor-evasion fuzzing for {@link PgScriptCheck}. The qualified-name
 * checks scan token streams for keyword anchors ({@code ON}, {@code IS},
 * {@code TO}, {@code FOR}, {@code USING} …); an identifier that is itself
 * spelled like an anchor must not shift the anchor and smuggle a foreign
 * qualifier through. For every template below, each checked identifier
 * slot is filled with a foreign qualifier while every other slot cycles
 * through the whole {@link #WORDS} pool — the check must reject every
 * generated statement.
 *
 * <p>The property is one-directional: instances are not asserted to be
 * legal PostgreSQL. A nonsense statement the check passes is harmless —
 * it fails on the scratch connection and rolls back. A foreign qualifier
 * it misses is not.
 */
class ScriptCheckFuzzTest {

    private static final String SCRATCH = "scr";

    /**
     * Anchor keywords that double as identifiers somewhere, kind words,
     * plain identifiers and near-scratch names. Quoted forms are included:
     * quotes survive in tokens, so a quoted word can never equal a keyword.
     */
    private static final List<String> WORDS = List.of(
            // anchor and clause keywords
            "on", "is", "to", "for", "from", "of", "using", "as", "into",
            "values", "set", "where", "by", "in", "and", "or", "not", "null",
            "all", "any", "exists", "case", "when", "then", "else",
            // ddl words that appear inside checked statements
            "select", "insert", "update", "delete", "table", "column",
            "schema", "constraint", "trigger", "rule", "policy", "grant",
            "revoke", "owner", "rename", "attach", "detach", "partition",
            "foreign", "primary", "key", "unique", "check", "exclude",
            "default", "cascade", "restrict", "references", "sequence",
            "view", "index", "function", "procedure", "aggregate", "type",
            "domain", "cast", "convert", "role", "public", "session",
            "local", "temp", "operator", "collation", "conversion",
            "transform", "publication", "extension", "materialized",
            "text", "search", "configuration", "dictionary", "parser",
            "template", "mapping", "language", "server", "statistics",
            // plain identifiers and near-scratch names
            "x", "t", "s", "q", "n", "a", "b", "c", "i", "p", "f", "g",
            "r", "u", "v", "w", "scr", "sc", "scr2", "scr_x", "x_scr",
            // quoted forms — a keyword or near-scratch name in quotes
            "\"is\"", "\"on\"", "\"to\"", "\"t\"", "\"scr\"");

    /** Foreign-qualifier forms: bare, quoted and upper-case. */
    private static final List<String> EVIL = List.of("evil", "\"evil\"", "EVIL");

    /**
     * A statement template: {@code %s} once per identifier slot in order;
     * {@code checked} lists the slot indexes the qualified-name check is
     * responsible for. Other slots — grantees, rule names, access methods,
     * {@code PARTITION OF} parents, {@code REFERENCES} targets — are
     * filled from {@link #WORDS} only.
     */
    private record Tpl(String fmt, int... checked) {
    }

    private static final Tpl[] TEMPLATES = {
            // COMMENT ON / SECURITY LABEL: the ON/IS anchor family
            new Tpl("COMMENT ON TABLE %s IS 'x'", 0),
            new Tpl("COMMENT ON VIEW %s IS 'x'", 0),
            new Tpl("COMMENT ON INDEX %s IS 'x'", 0),
            new Tpl("COMMENT ON SEQUENCE %s IS 'x'", 0),
            new Tpl("COMMENT ON TYPE %s IS 'x'", 0),
            new Tpl("COMMENT ON DOMAIN %s IS 'x'", 0),
            new Tpl("COMMENT ON MATERIALIZED VIEW %s IS 'x'", 0),
            new Tpl("COMMENT ON FOREIGN TABLE %s IS 'x'", 0),
            new Tpl("COMMENT ON FUNCTION %s(int) IS 'x'", 0),
            new Tpl("COMMENT ON PROCEDURE %s() IS 'x'", 0),
            new Tpl("COMMENT ON AGGREGATE %s(int) IS 'x'", 0),
            new Tpl("COMMENT ON COLUMN %s.%s IS 'x'", 0),
            new Tpl("COMMENT ON COLUMN %s.%s.%s IS 'x'", 0),
            new Tpl("COMMENT ON CONSTRAINT %s ON %s IS 'x'", 0, 1),
            new Tpl("COMMENT ON TRIGGER %s ON %s IS 'x'", 1),
            new Tpl("COMMENT ON RULE %s ON %s IS 'x'", 1),
            new Tpl("COMMENT ON POLICY %s ON %s IS 'x'", 1),
            // signature targets: only the name before the parenthesis anchors
            new Tpl("COMMENT ON CAST (%s AS text) IS 'x'", 0),
            new Tpl("COMMENT ON OPERATOR %s (int, int) IS 'x'", 0),
            new Tpl("SECURITY LABEL FOR %s ON TABLE %s IS 'x'", 1),
            new Tpl("SECURITY LABEL ON TABLE %s IS 'x'", 0),
            new Tpl("SECURITY LABEL FOR %s ON COLUMN %s.%s IS 'x'", 1),
            new Tpl("SECURITY LABEL ON COLUMN %s.%s IS 'x'", 0),
            new Tpl("SECURITY LABEL ON FUNCTION %s(int) IS 'x'", 0),
            // GRANT/REVOKE: the ON anchor family and schema lists
            new Tpl("GRANT SELECT ON TABLE %s TO %s", 0),
            new Tpl("GRANT SELECT(%s), INSERT ON TABLE %s TO r", 1),
            new Tpl("GRANT SELECT ON SEQUENCE %s TO r", 0),
            new Tpl("GRANT SELECT ON FOREIGN TABLE %s TO r", 0),
            new Tpl("GRANT USAGE ON DOMAIN %s TO r", 0),
            new Tpl("GRANT EXECUTE ON FUNCTION %s(int) TO r", 0),
            new Tpl("GRANT EXECUTE ON PROCEDURE %s(int) TO r", 0),
            new Tpl("REVOKE SELECT ON TABLE %s FROM %s", 0),
            new Tpl("REVOKE INSERT(%s) ON TABLE %s FROM r", 1),
            new Tpl("GRANT SELECT ON ALL TABLES IN SCHEMA %s TO r", 0),
            new Tpl("GRANT SELECT ON ALL TABLES IN SCHEMA scr, %s TO r", 0),
            new Tpl("GRANT SELECT ON ALL SEQUENCES IN SCHEMA %s TO %s", 0),
            new Tpl("ALTER DEFAULT PRIVILEGES IN SCHEMA %s"
                    + " GRANT SELECT ON TABLES TO r", 0),
            new Tpl("ALTER DEFAULT PRIVILEGES IN SCHEMA scr, %s"
                    + " GRANT SELECT ON TABLES TO r", 0),
            // CREATE/DROP/ALTER name positions and name lists
            new Tpl("CREATE TABLE %s (a int)", 0),
            new Tpl("CREATE TABLE %s (%s int, %s text)", 0),
            new Tpl("CREATE TABLE %s PARTITION OF %s"
                    + " FOR VALUES FROM (0) TO (10)", 0),
            new Tpl("CREATE TEMP TABLE %s (a int)", 0),
            new Tpl("CREATE UNLOGGED TABLE %s (a int)", 0),
            new Tpl("DROP TABLE %s", 0),
            new Tpl("DROP TABLE %s, %s", 0, 1),
            new Tpl("DROP TABLE a, %s", 0),
            new Tpl("DROP INDEX %s", 0),
            new Tpl("DROP VIEW %s", 0),
            new Tpl("DROP SEQUENCE %s", 0),
            new Tpl("DROP DOMAIN %s", 0),
            new Tpl("DROP TYPE %s", 0),
            new Tpl("DROP FUNCTION %s(int)", 0),
            new Tpl("CREATE VIEW %s AS SELECT 1", 0),
            new Tpl("CREATE MATERIALIZED VIEW %s AS SELECT 1", 0),
            new Tpl("CREATE SEQUENCE %s", 0),
            new Tpl("CREATE DOMAIN %s AS int", 0),
            new Tpl("CREATE TYPE %s AS ENUM ('a')", 0),
            new Tpl("CREATE TYPE %s AS (a int)", 0),
            new Tpl("CREATE FUNCTION %s() RETURNS int AS 'select 1'", 0),
            new Tpl("CREATE PROCEDURE %s() AS 'select 1'", 0),
            new Tpl("CREATE AGGREGATE %s(int) (SFUNC = f)", 0),
            new Tpl("CREATE COLLATION %s (LOCALE = 'x')", 0),
            new Tpl("CREATE CONVERSION %s FOR 'a' TO 'b' FROM f", 0),
            new Tpl("CREATE OPERATOR %s (PROCEDURE = f)", 0),
            new Tpl("CREATE OPERATOR CLASS %s USING %s", 0),
            new Tpl("CREATE OPERATOR FAMILY %s USING %s", 0),
            new Tpl("CREATE TEXT SEARCH CONFIGURATION %s", 0),
            new Tpl("CREATE TEXT SEARCH DICTIONARY %s (TEMPLATE = t)", 0),
            new Tpl("CREATE STATISTICS %s ON %s, %s FROM %s", 0),
            new Tpl("CREATE FOREIGN TABLE %s (a int) SERVER %s", 0),
            // the WITH SCHEMA clause is checked; the extension name is not
            new Tpl("CREATE EXTENSION %s WITH SCHEMA %s", 1),
            // the INTO schema anchors; the remote schema is a foreign
            // reference and stays legal
            new Tpl("IMPORT FOREIGN SCHEMA %s FROM SERVER s INTO %s", 1),
            new Tpl("CREATE INDEX %s ON %s (c)", 1),
            new Tpl("CREATE INDEX %s ON %s USING %s (c)", 1),
            new Tpl("CREATE UNIQUE INDEX %s ON %s (c)", 1),
            new Tpl("CREATE TRIGGER %s BEFORE UPDATE ON %s"
                    + " FOR EACH ROW EXECUTE FUNCTION f()", 1),
            new Tpl("CREATE TRIGGER %s BEFORE UPDATE OF %s ON %s"
                    + " FOR EACH ROW EXECUTE FUNCTION f()", 2),
            new Tpl("CREATE TRIGGER %s BEFORE UPDATE OF %s, %s ON %s"
                    + " FOR EACH ROW EXECUTE FUNCTION f()", 3),
            new Tpl("CREATE CONSTRAINT TRIGGER %s AFTER INSERT ON %s"
                    + " FOR EACH ROW EXECUTE FUNCTION f()", 1),
            new Tpl("CREATE RULE %s AS ON UPDATE TO %s DO ALSO NOTHING", 1),
            new Tpl("CREATE RULE %s AS ON INSERT TO %s DO INSTEAD NOTHING", 1),
            new Tpl("CREATE RULE %s AS ON SELECT TO %s DO INSTEAD NOTHING", 1),
            new Tpl("CREATE POLICY %s ON %s", 1),
            new Tpl("DROP POLICY %s ON %s", 0, 1),
            new Tpl("DROP RULE %s ON %s", 0, 1),
            new Tpl("DROP TRIGGER %s ON %s", 0, 1),
            new Tpl("DROP INDEX %s", 0),
            new Tpl("ALTER POLICY %s ON %s RENAME TO p2", 0, 1),
            new Tpl("ALTER RULE %s ON %s RENAME TO r2", 0, 1),
            new Tpl("ALTER TRIGGER %s ON %s RENAME TO t2", 0, 1),
            new Tpl("ALTER TABLE %s ADD COLUMN c int", 0),
            new Tpl("ALTER TABLE %s DROP COLUMN %s", 0),
            new Tpl("ALTER TABLE %s RENAME COLUMN a TO %s", 0),
            new Tpl("ALTER TABLE %s ATTACH PARTITION %s"
                    + " FOR VALUES FROM (0) TO (10)", 0, 1),
            new Tpl("ALTER TABLE %s DETACH PARTITION %s", 0, 1),
            // modeled OWNER TO forms — the name position is still checked
            new Tpl("ALTER TABLE %s OWNER TO %s", 0),
            new Tpl("ALTER VIEW %s OWNER TO r", 0),
            new Tpl("ALTER SEQUENCE %s OWNER TO r", 0),
            new Tpl("ALTER DOMAIN %s OWNER TO r", 0),
            new Tpl("ALTER FUNCTION %s(int) OWNER TO r", 0),
            new Tpl("ALTER MATERIALIZED VIEW %s OWNER TO r", 0),
            new Tpl("ALTER INDEX %s RENAME TO %s", 0),
            new Tpl("ALTER SEQUENCE %s OWNED BY %s.c", 0),
            new Tpl("ALTER VIEW %s RENAME TO %s", 0),
            new Tpl("ALTER DOMAIN %s ADD CONSTRAINT c CHECK (true)", 0),
            new Tpl("ALTER TYPE %s ADD VALUE 'v'", 0),
            new Tpl("ALTER PUBLICATION %s ADD TABLE %s", 1),
            new Tpl("ALTER PUBLICATION %s ADD TABLES IN SCHEMA %s", 1),
            new Tpl("ALTER PUBLICATION %s DROP TABLE %s", 1),
            new Tpl("ALTER TEXT SEARCH CONFIGURATION %s RENAME TO %s", 0),
            new Tpl("ALTER TEXT SEARCH DICTIONARY %s RENAME TO %s", 0),
            new Tpl("ALTER OPERATOR FAMILY %s USING %s"
                    + " ADD OPERATOR 1 <(int,int)", 0),
            new Tpl("ALTER OPERATOR CLASS %s USING %s"
                    + " ADD OPERATOR 1 <(int,int)", 0),
            // kinds whose object anchor is a qualified type or signature
            new Tpl("CREATE CAST (%s AS text) WITHOUT FUNCTION", 0),
            new Tpl("DROP CAST (%s AS text)", 0),
            new Tpl("CREATE TRANSFORM FOR %s LANGUAGE plpgsql"
                    + " (FROM SQL WITH FUNCTION f(int))", 0),
            new Tpl("DROP TRANSFORM FOR %s LANGUAGE plpgsql", 0),
            // sequence functions: qualified literal in an evaluating call
            new Tpl("SELECT nextval('%s')", 0),
            new Tpl("SELECT setval('%s', 1)", 0),
    };

    @Test
    void aForeignQualifierInEveryCheckedSlotIsAlwaysRejected() {
        final var failures = new ArrayList<String>();
        for (final var tpl : TEMPLATES) {
            for (var position = 0; position < tpl.checked().length; position++) {
                for (var it = 0; it < WORDS.size() * EVIL.size(); it++) {
                    final var sql = fill(tpl, position, it);
                    try {
                        PgScriptCheck.check(sql, SCRATCH);
                        failures.add(sql);
                    } catch (DesiredStateException expected) {
                        // rejected — the qualifier did not escape
                    }
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("foreign qualifiers passed the check: "
                    + failures.subList(0, Math.min(200, failures.size()))
                    + (failures.size() > 200
                            ? " … (" + failures.size() + " total)" : ""));
        }
    }

    /** Renders {@code tpl} with checked-slot index {@code position} as a
     *  foreign qualifier and every other slot cycled through
     *  {@link #WORDS}. */
    private static String fill(Tpl tpl, int position, int it) {
        final var slots = tpl.fmt().split("%s", -1).length - 1;
        final var args = new Object[slots];
        for (var i = 0; i < slots; i++) {
            args[i] = WORDS.get((it + i * 17) % WORDS.size());
        }
        args[tpl.checked()[position]] = EVIL.get(it % EVIL.size())
                + "." + WORDS.get((it * 2 + 1) % WORDS.size());
        return tpl.fmt().formatted(args);
    }
}
