import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ObjectId, ObjectKind } from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available } from "./PgTestDatabase.ts";

/** Every dependency edge AGENTS.md lists for {@code PgCatalog.loadDependencies}
 *  is recorded in {@code dependsOn}. */

let sequence = 0;
const RUN_ID = process.hrtime.bigint().toString(16);
const dialect = new PostgreSqlDialect();
const scratch = new PgTestDataSource();
const dbUp = await available();

interface Edge {
    readonly label: string;
    readonly setup: string;
    readonly from: ObjectId;
    readonly to: ObjectId;
}

const id = (kind: ObjectKind, name: string, parent?: string) =>
    new ObjectId(kind, name, parent);
const edge = (label: string, setup: string, from: ObjectId, to: ObjectId): Edge =>
    ({ label, setup, from, to });

const TYPES = "CREATE TYPE mood AS ENUM ('a','b');"
        + " CREATE FUNCTION fn(int) RETURNS int LANGUAGE sql IMMUTABLE"
        + " AS $$ SELECT $1 $$;"
        + " CREATE TABLE t (id int PRIMARY KEY, m mood, note text);";

const EDGES: readonly Edge[] = [
    edge("view -> relation", TYPES + "CREATE VIEW v AS SELECT id FROM t",
            id(ObjectKind.VIEW, "v"), id(ObjectKind.TABLE, "t")),
    edge("view -> routine", TYPES + "CREATE VIEW v AS SELECT fn(id) AS x FROM t",
            id(ObjectKind.VIEW, "v"), id(ObjectKind.ROUTINE, "fn(integer)")),
    edge("view -> type", TYPES + "CREATE VIEW v AS SELECT 'a'::mood AS x",
            id(ObjectKind.VIEW, "v"), id(ObjectKind.TYPE, "mood")),
    edge("routine -> relation", TYPES
            + "CREATE FUNCTION rr() RETURNS bigint LANGUAGE sql"
            + " BEGIN ATOMIC SELECT count(*) FROM t; END",
            id(ObjectKind.ROUTINE, "rr()"), id(ObjectKind.TABLE, "t")),
    edge("routine -> routine", TYPES
            + "CREATE FUNCTION rp() RETURNS int LANGUAGE sql"
            + " BEGIN ATOMIC SELECT fn(1); END",
            id(ObjectKind.ROUTINE, "rp()"), id(ObjectKind.ROUTINE, "fn(integer)")),
    edge("routine -> type", TYPES
            + "CREATE FUNCTION rt(m mood) RETURNS int LANGUAGE sql"
            + " AS $$ SELECT 1 $$",
            id(ObjectKind.ROUTINE, "rt(mood)"), id(ObjectKind.TYPE, "mood")),
    edge("index -> routine", TYPES + "CREATE INDEX ix ON t (fn(id))",
            id(ObjectKind.INDEX, "ix", "t"), id(ObjectKind.ROUTINE, "fn(integer)")),
    edge("trigger -> routine", TYPES
            + "CREATE FUNCTION trg() RETURNS trigger LANGUAGE plpgsql"
            + " AS $$ BEGIN RETURN NEW; END $$;"
            + " CREATE TRIGGER tr BEFORE INSERT ON t FOR EACH ROW"
            + " EXECUTE FUNCTION trg()",
            id(ObjectKind.TRIGGER, "tr", "t"), id(ObjectKind.ROUTINE, "trg()")),
    edge("check constraint -> routine", TYPES
            + "ALTER TABLE t ADD CONSTRAINT ck CHECK (fn(id) > 0)",
            id(ObjectKind.CHECK, "ck", "t"), id(ObjectKind.ROUTINE, "fn(integer)")),
    edge("check constraint -> type", TYPES
            + "ALTER TABLE t ADD CONSTRAINT ck CHECK (m <> 'b'::mood)",
            id(ObjectKind.CHECK, "ck", "t"), id(ObjectKind.TYPE, "mood")),
    edge("foreign key -> class", TYPES
            + "CREATE TABLE t2 (tid int, CONSTRAINT fk FOREIGN KEY (tid)"
            + " REFERENCES t (id))",
            id(ObjectKind.FOREIGN_KEY, "fk", "t2"), id(ObjectKind.TABLE, "t")),
    edge("foreign key -> constraint", TYPES
            + "CREATE TABLE t2 (tid int, CONSTRAINT fk FOREIGN KEY (tid)"
            + " REFERENCES t (id))",
            id(ObjectKind.FOREIGN_KEY, "fk", "t2"),
            id(ObjectKind.PRIMARY_KEY, "t_pkey", "t")),
    edge("column default -> relation (sequence)", TYPES
            + "CREATE SEQUENCE sq;"
            + " CREATE TABLE t3 (n bigint DEFAULT nextval('sq'))",
            id(ObjectKind.COLUMN, "n", "t3"), id(ObjectKind.SEQUENCE, "sq")),
    edge("column default -> routine", TYPES
            + "CREATE TABLE t3 (n int DEFAULT fn(1))",
            id(ObjectKind.COLUMN, "n", "t3"), id(ObjectKind.ROUTINE, "fn(integer)")),
    edge("column default -> type", TYPES
            + "CREATE TABLE t3 (m2 mood DEFAULT 'a'::mood)",
            id(ObjectKind.COLUMN, "m2", "t3"), id(ObjectKind.TYPE, "mood")),
    edge("column -> type", TYPES,
            id(ObjectKind.COLUMN, "m", "t"), id(ObjectKind.TYPE, "mood")),
    edge("table -> type", TYPES,
            id(ObjectKind.TABLE, "t"), id(ObjectKind.TYPE, "mood")),
    edge("column -> collation", TYPES
            + "CREATE COLLATION cl (provider = libc, locale = 'C');"
            + " CREATE TABLE t3 (s text COLLATE cl)",
            id(ObjectKind.COLUMN, "s", "t3"), id(ObjectKind.COLLATION, "cl")),
    edge("partition child -> parent", TYPES
            + "CREATE TABLE pt (k int) PARTITION BY RANGE (k);"
            + " CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (10)",
            id(ObjectKind.PARTITION, "pc", "pt"), id(ObjectKind.TABLE, "pt")),
    edge("comment -> table", TYPES + "COMMENT ON TABLE t IS 'x'",
            id(ObjectKind.COMMENT, "t"), id(ObjectKind.TABLE, "t")),
    edge("comment -> column", TYPES + "COMMENT ON COLUMN t.note IS 'x'",
            id(ObjectKind.COMMENT, "note", "t"),
            id(ObjectKind.COLUMN, "note", "t")),
    edge("policy -> table", TYPES
            + "ALTER TABLE t ENABLE ROW LEVEL SECURITY;"
            + " CREATE POLICY p_sel ON t FOR SELECT USING (id > 0)",
            id(ObjectKind.POLICY, "p_sel", "t"), id(ObjectKind.TABLE, "t")),
    edge("domain -> base type", TYPES + "CREATE DOMAIN dm AS mood",
            id(ObjectKind.DOMAIN, "dm"), id(ObjectKind.TYPE, "mood")),
    edge("statistics -> table", TYPES + "CREATE STATISTICS st ON id, note FROM t",
            id(ObjectKind.STATISTICS, "st"), id(ObjectKind.TABLE, "t")),
    edge("rule -> table", TYPES + "CREATE RULE rl AS ON INSERT TO t DO NOTHING",
            id(ObjectKind.RULE, "rl", "t"), id(ObjectKind.TABLE, "t")),
    // a range is a managed TYPE whose generated family folds into it: the
    // multirange, its constructors and the range→multirange cast are never
    // objects — a routine calling the folded constructor resolves to the
    // range instead (5.8.3 ordering still holds)
    edge("range type -> subtype", TYPES + "CREATE TYPE r AS RANGE (subtype = mood)",
            id(ObjectKind.TYPE, "r"), id(ObjectKind.TYPE, "mood")),
    edge("routine -> folded range constructor", TYPES
            + "CREATE TYPE r AS RANGE (subtype = mood);"
            + "CREATE FUNCTION mk(x r) RETURNS r_multirange"
            + " LANGUAGE sql IMMUTABLE AS 'SELECT r_multirange(x)'",
            id(ObjectKind.ROUTINE, "mk(r)"), id(ObjectKind.TYPE, "r")),
    // text-search and operator objects are managed, so dependents must order
    // after them — the legs' refclassid filters cover pg_ts_* and pg_op*
    edge("view -> text search configuration", TYPES
            + "CREATE TEXT SEARCH CONFIGURATION c1"
            + " (parser = pg_catalog.default);"
            + " CREATE VIEW v AS SELECT to_tsvector('c1', note) AS s FROM t",
            id(ObjectKind.VIEW, "v"),
            id(ObjectKind.TEXT_SEARCH_CONFIGURATION, "c1")),
    edge("routine -> text search configuration", TYPES
            + "CREATE TEXT SEARCH CONFIGURATION c1"
            + " (parser = pg_catalog.default);"
            + " CREATE FUNCTION f() RETURNS tsvector LANGUAGE sql"
            + " BEGIN ATOMIC SELECT to_tsvector('c1','x'); END",
            id(ObjectKind.ROUTINE, "f()"),
            id(ObjectKind.TEXT_SEARCH_CONFIGURATION, "c1")),
    edge("column default -> text search configuration", TYPES
            + "CREATE TEXT SEARCH CONFIGURATION c1"
            + " (parser = pg_catalog.default);"
            + " CREATE TABLE t3 (v tsvector DEFAULT to_tsvector('c1','x'))",
            id(ObjectKind.COLUMN, "v", "t3"),
            id(ObjectKind.TEXT_SEARCH_CONFIGURATION, "c1")),
    edge("view -> operator", TYPES
            + "CREATE OPERATOR === (LEFTARG = int, RIGHTARG = int,"
            + " PROCEDURE = int4eq);"
            + " CREATE VIEW v AS SELECT id === id AS x FROM t",
            id(ObjectKind.VIEW, "v"),
            id(ObjectKind.OPERATOR, "===(integer,integer)")),
    edge("check constraint -> operator", TYPES
            + "CREATE OPERATOR === (LEFTARG = int, RIGHTARG = int,"
            + " PROCEDURE = int4eq);"
            + " ALTER TABLE t ADD CONSTRAINT ck CHECK (id === id)",
            id(ObjectKind.CHECK, "ck", "t"),
            id(ObjectKind.OPERATOR, "===(integer,integer)")),
];

describe("DependencyEdgesTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("every listed edge is recorded", async () => {
        const failures: string[] = [];
        for (const e of EDGES) {
            const schema = "dep_" + RUN_ID + "_" + (++sequence);
            const quoted = dialect.quoteIdentifier(schema);
            const c = await scratch.connect();
            try {
                await c.setAutoCommit(false);
                try {
                    await c.execute("CREATE SCHEMA " + quoted);
                    await c.execute("SET LOCAL search_path = " + quoted);
                    await c.execute(e.setup);
                    const snapshot = await dialect.readSnapshot(c, schema);
                    const from = snapshot.objects.get(e.from);
                    if (from === undefined) {
                        failures.push(e.label + ": source " + e.from
                                + " not in " + JSON.stringify(
                                        [...snapshot.objects.keys()]));
                    } else if (!from.dependsOn.has(e.to)) {
                        failures.push(e.label + ": " + e.from
                                + " dependsOn " + JSON.stringify(
                                        [...from.dependsOn.values()])
                                + ", missing " + e.to);
                    }
                } finally {
                    await c.rollback();
                }
            } finally {
                await c.close();
            }
        }
        assert.equal(failures.length, 0, failures.join("\n"));
    });
});
