import assert from "node:assert/strict";
import test from "node:test";
import { DesiredStateException } from "@schema-reconciler/core";
import * as PgScriptCheck from "../../src/internal/PgScriptCheck.ts";

/**
 * Anchor-evasion fuzzing for {@link PgScriptCheck}. The qualified-name
 * checks scan token streams for keyword anchors (`ON`, `IS`,
 * `TO`, `FOR`, `USING` …); an identifier that is itself
 * spelled like an anchor must not shift the anchor and smuggle a foreign
 * qualifier through. For every template below, each checked identifier
 * slot is filled with a foreign qualifier while every other slot cycles
 * through the whole {@link WORDS} pool — the check must reject every
 * generated statement.
 *
 * The property is one-directional: instances are not asserted to be
 * legal PostgreSQL. A nonsense statement the check passes is harmless —
 * it fails on the scratch connection and rolls back. A foreign qualifier
 * it misses is not.
 */

const SCRATCH = "scr";

/**
 * Anchor keywords that double as identifiers somewhere, kind words,
 * plain identifiers and near-scratch names. Quoted forms are included:
 * quotes survive in tokens, so a quoted word can never equal a keyword.
 */
const WORDS = [
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
        '"is"', '"on"', '"to"', '"t"', '"scr"'];

/** Foreign-qualifier forms: bare, quoted and upper-case. */
const EVIL = ["evil", '"evil"', "EVIL"];

/**
 * A statement template: `%s` once per identifier slot in order;
 * `checked` lists the slot indexes the qualified-name check is
 * responsible for. Other slots — grantees, rule names, access methods,
 * `PARTITION OF` parents, `REFERENCES` targets — are
 * filled from {@link WORDS} only.
 */
interface Tpl {
    fmt: string;
    checked: number[];
}

function tpl(fmt: string, ...checked: number[]): Tpl {
    return { fmt, checked };
}

const TEMPLATES: Tpl[] = [
        // COMMENT ON / SECURITY LABEL: the ON/IS anchor family
        tpl("COMMENT ON TABLE %s IS 'x'", 0),
        tpl("COMMENT ON VIEW %s IS 'x'", 0),
        tpl("COMMENT ON INDEX %s IS 'x'", 0),
        tpl("COMMENT ON SEQUENCE %s IS 'x'", 0),
        tpl("COMMENT ON TYPE %s IS 'x'", 0),
        tpl("COMMENT ON DOMAIN %s IS 'x'", 0),
        tpl("COMMENT ON MATERIALIZED VIEW %s IS 'x'", 0),
        tpl("COMMENT ON FOREIGN TABLE %s IS 'x'", 0),
        tpl("COMMENT ON FUNCTION %s(int) IS 'x'", 0),
        tpl("COMMENT ON PROCEDURE %s() IS 'x'", 0),
        tpl("COMMENT ON AGGREGATE %s(int) IS 'x'", 0),
        tpl("COMMENT ON COLUMN %s.%s IS 'x'", 0),
        tpl("COMMENT ON COLUMN %s.%s.%s IS 'x'", 0),
        tpl("COMMENT ON CONSTRAINT %s ON %s IS 'x'", 0, 1),
        tpl("COMMENT ON TRIGGER %s ON %s IS 'x'", 1),
        tpl("COMMENT ON RULE %s ON %s IS 'x'", 1),
        tpl("COMMENT ON POLICY %s ON %s IS 'x'", 1),
        // signature targets: only the name before the parenthesis anchors
        tpl("COMMENT ON CAST (%s AS text) IS 'x'", 0),
        tpl("COMMENT ON OPERATOR %s (int, int) IS 'x'", 0),
        tpl("SECURITY LABEL FOR %s ON TABLE %s IS 'x'", 1),
        tpl("SECURITY LABEL ON TABLE %s IS 'x'", 0),
        tpl("SECURITY LABEL FOR %s ON COLUMN %s.%s IS 'x'", 1),
        tpl("SECURITY LABEL ON COLUMN %s.%s IS 'x'", 0),
        tpl("SECURITY LABEL ON FUNCTION %s(int) IS 'x'", 0),
        // GRANT/REVOKE: the ON anchor family and schema lists
        tpl("GRANT SELECT ON TABLE %s TO %s", 0),
        tpl("GRANT SELECT(%s), INSERT ON TABLE %s TO r", 1),
        tpl("GRANT SELECT ON SEQUENCE %s TO r", 0),
        tpl("GRANT SELECT ON FOREIGN TABLE %s TO r", 0),
        tpl("GRANT USAGE ON DOMAIN %s TO r", 0),
        tpl("GRANT EXECUTE ON FUNCTION %s(int) TO r", 0),
        tpl("GRANT EXECUTE ON PROCEDURE %s(int) TO r", 0),
        tpl("REVOKE SELECT ON TABLE %s FROM %s", 0),
        tpl("REVOKE INSERT(%s) ON TABLE %s FROM r", 1),
        tpl("GRANT SELECT ON ALL TABLES IN SCHEMA %s TO r", 0),
        tpl("GRANT SELECT ON ALL TABLES IN SCHEMA scr, %s TO r", 0),
        tpl("GRANT SELECT ON ALL SEQUENCES IN SCHEMA %s TO %s", 0),
        tpl("ALTER DEFAULT PRIVILEGES IN SCHEMA %s"
                + " GRANT SELECT ON TABLES TO r", 0),
        tpl("ALTER DEFAULT PRIVILEGES IN SCHEMA scr, %s"
                + " GRANT SELECT ON TABLES TO r", 0),
        // CREATE/DROP/ALTER name positions and name lists
        tpl("CREATE TABLE %s (a int)", 0),
        tpl("CREATE TABLE %s (%s int, %s text)", 0),
        tpl("CREATE TABLE %s PARTITION OF %s"
                + " FOR VALUES FROM (0) TO (10)", 0),
        tpl("CREATE TEMP TABLE %s (a int)", 0),
        tpl("CREATE UNLOGGED TABLE %s (a int)", 0),
        tpl("DROP TABLE %s", 0),
        tpl("DROP TABLE %s, %s", 0, 1),
        tpl("DROP TABLE a, %s", 0),
        tpl("DROP INDEX %s", 0),
        tpl("DROP VIEW %s", 0),
        tpl("DROP SEQUENCE %s", 0),
        tpl("DROP DOMAIN %s", 0),
        tpl("DROP TYPE %s", 0),
        tpl("DROP FUNCTION %s(int)", 0),
        tpl("CREATE VIEW %s AS SELECT 1", 0),
        tpl("CREATE MATERIALIZED VIEW %s AS SELECT 1", 0),
        tpl("CREATE SEQUENCE %s", 0),
        tpl("CREATE DOMAIN %s AS int", 0),
        tpl("CREATE TYPE %s AS ENUM ('a')", 0),
        tpl("CREATE TYPE %s AS (a int)", 0),
        tpl("CREATE FUNCTION %s() RETURNS int AS 'select 1'", 0),
        tpl("CREATE PROCEDURE %s() AS 'select 1'", 0),
        tpl("CREATE AGGREGATE %s(int) (SFUNC = f)", 0),
        tpl("CREATE COLLATION %s (LOCALE = 'x')", 0),
        tpl("CREATE CONVERSION %s FOR 'a' TO 'b' FROM f", 0),
        tpl("CREATE OPERATOR %s (PROCEDURE = f)", 0),
        tpl("CREATE OPERATOR CLASS %s USING %s", 0),
        tpl("CREATE OPERATOR FAMILY %s USING %s", 0),
        tpl("CREATE TEXT SEARCH CONFIGURATION %s", 0),
        tpl("CREATE TEXT SEARCH DICTIONARY %s (TEMPLATE = t)", 0),
        tpl("CREATE STATISTICS %s ON %s, %s FROM %s", 0),
        tpl("CREATE FOREIGN TABLE %s (a int) SERVER %s", 0),
        // the WITH SCHEMA clause is checked; the extension name is not
        tpl("CREATE EXTENSION %s WITH SCHEMA %s", 1),
        // the INTO schema anchors; the remote schema is a foreign
        // reference and stays legal
        tpl("IMPORT FOREIGN SCHEMA %s FROM SERVER s INTO %s", 1),
        tpl("CREATE INDEX %s ON %s (c)", 1),
        tpl("CREATE INDEX %s ON %s USING %s (c)", 1),
        tpl("CREATE UNIQUE INDEX %s ON %s (c)", 1),
        tpl("CREATE TRIGGER %s BEFORE UPDATE ON %s"
                + " FOR EACH ROW EXECUTE FUNCTION f()", 1),
        tpl("CREATE TRIGGER %s BEFORE UPDATE OF %s ON %s"
                + " FOR EACH ROW EXECUTE FUNCTION f()", 2),
        tpl("CREATE TRIGGER %s BEFORE UPDATE OF %s, %s ON %s"
                + " FOR EACH ROW EXECUTE FUNCTION f()", 3),
        tpl("CREATE CONSTRAINT TRIGGER %s AFTER INSERT ON %s"
                + " FOR EACH ROW EXECUTE FUNCTION f()", 1),
        tpl("CREATE RULE %s AS ON UPDATE TO %s DO ALSO NOTHING", 1),
        tpl("CREATE RULE %s AS ON INSERT TO %s DO INSTEAD NOTHING", 1),
        tpl("CREATE RULE %s AS ON SELECT TO %s DO INSTEAD NOTHING", 1),
        tpl("CREATE POLICY %s ON %s", 1),
        tpl("DROP POLICY %s ON %s", 0, 1),
        tpl("DROP RULE %s ON %s", 0, 1),
        tpl("DROP TRIGGER %s ON %s", 0, 1),
        tpl("DROP INDEX %s", 0),
        tpl("ALTER POLICY %s ON %s RENAME TO p2", 0, 1),
        tpl("ALTER RULE %s ON %s RENAME TO r2", 0, 1),
        tpl("ALTER TRIGGER %s ON %s RENAME TO t2", 0, 1),
        tpl("ALTER TABLE %s ADD COLUMN c int", 0),
        tpl("ALTER TABLE %s DROP COLUMN %s", 0),
        tpl("ALTER TABLE %s RENAME COLUMN a TO %s", 0),
        tpl("ALTER TABLE %s ATTACH PARTITION %s"
                + " FOR VALUES FROM (0) TO (10)", 0, 1),
        tpl("ALTER TABLE %s DETACH PARTITION %s", 0, 1),
        // modeled OWNER TO forms — the name position is still checked
        tpl("ALTER TABLE %s OWNER TO %s", 0),
        tpl("ALTER VIEW %s OWNER TO r", 0),
        tpl("ALTER SEQUENCE %s OWNER TO r", 0),
        tpl("ALTER DOMAIN %s OWNER TO r", 0),
        tpl("ALTER FUNCTION %s(int) OWNER TO r", 0),
        tpl("ALTER MATERIALIZED VIEW %s OWNER TO r", 0),
        tpl("ALTER INDEX %s RENAME TO %s", 0),
        tpl("ALTER SEQUENCE %s OWNED BY %s.c", 0),
        tpl("ALTER VIEW %s RENAME TO %s", 0),
        tpl("ALTER DOMAIN %s ADD CONSTRAINT c CHECK (true)", 0),
        tpl("ALTER TYPE %s ADD VALUE 'v'", 0),
        tpl("ALTER PUBLICATION %s ADD TABLE %s", 1),
        tpl("ALTER PUBLICATION %s ADD TABLES IN SCHEMA %s", 1),
        tpl("ALTER PUBLICATION %s DROP TABLE %s", 1),
        tpl("ALTER TEXT SEARCH CONFIGURATION %s RENAME TO %s", 0),
        tpl("ALTER TEXT SEARCH DICTIONARY %s RENAME TO %s", 0),
        tpl("ALTER OPERATOR FAMILY %s USING %s"
                + " ADD OPERATOR 1 <(int,int)", 0),
        tpl("ALTER OPERATOR CLASS %s USING %s"
                + " ADD OPERATOR 1 <(int,int)", 0),
        // kinds whose object anchor is a qualified type or signature
        tpl("CREATE CAST (%s AS text) WITHOUT FUNCTION", 0),
        tpl("DROP CAST (%s AS text)", 0),
        tpl("CREATE TRANSFORM FOR %s LANGUAGE plpgsql"
                + " (FROM SQL WITH FUNCTION f(int))", 0),
        tpl("DROP TRANSFORM FOR %s LANGUAGE plpgsql", 0),
        // sequence functions: qualified literal in an evaluating call
        tpl("SELECT nextval('%s')", 0),
        tpl("SELECT setval('%s', 1)", 0),
];

test("aForeignQualifierInEveryCheckedSlotIsAlwaysRejected", () => {
    const failures: string[] = [];
    for (const t of TEMPLATES) {
        for (let position = 0; position < t.checked.length; position++) {
            for (let it = 0; it < WORDS.length * EVIL.length; it++) {
                const sql = fill(t, position, it);
                try {
                    PgScriptCheck.check(sql, SCRATCH);
                    failures.push(sql);
                } catch (e) {
                    assert.ok(e instanceof DesiredStateException, sql + ": " + e);
                }
            }
        }
    }
    assert.deepEqual([], failures.slice(0, 200),
            failures.length + " foreign qualifiers passed the check");
});

/** Renders `tpl` with checked-slot index `position` as a
 *  foreign qualifier and every other slot cycled through
 *  {@link WORDS}. */
function fill(t: Tpl, position: number, it: number): string {
    const slots = t.fmt.split("%s").length - 1;
    const args: string[] = [];
    for (let i = 0; i < slots; i++) {
        args.push(WORDS[(it + i * 17) % WORDS.length]!);
    }
    args[t.checked[position]!] = EVIL[it % EVIL.length]!
            + "." + WORDS[(it * 2 + 1) % WORDS.length]!;
    let i = 0;
    return t.fmt.replaceAll("%s", () => args[i++]!);
}
