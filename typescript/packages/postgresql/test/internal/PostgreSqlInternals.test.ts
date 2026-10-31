import assert from "node:assert/strict";
import test from "node:test";
import { ChangeAction, ChangeSafety, Definition, Difference,
        DifferenceType, ObjectId, ObjectKind, SchemaObject,
        SchemaSnapshot } from "@epicquest-studios/schema-reconciler-core";
import { ObjectMap, ObjectSet } from "@epicquest-studios/schema-reconciler-core";
import * as PgCatalog from "../../src/internal/PgCatalog.ts";
import * as PgIdentifiers from "../../src/internal/PgIdentifiers.ts";
import * as PgRender from "../../src/internal/PgRender.ts";
import * as PgSql from "../../src/internal/PgSql.ts";

function obj(id: ObjectId, def: string,
        attrs: ReadonlyMap<string, string>): SchemaObject {
    return new SchemaObject(id, new Definition(def), new ObjectSet(), attrs);
}

function snap(...objects: SchemaObject[]): SchemaSnapshot {
    const map = new ObjectMap<SchemaObject>();
    for (const o of objects) {
        map.set(o.id, o);
    }
    return SchemaSnapshot.of(map);
}

// ---- PgIdentifiers -----------------------------------------------------

test("quoteDoublesEmbeddedQuotes", () => {
    assert.equal(PgIdentifiers.quote("plain"), "\"plain\"");
    assert.equal(PgIdentifiers.quote("has\"quote"), "\"has\"\"quote\"");
    assert.equal(PgIdentifiers.quote(" space "), "\" space \"");
});

test("quoteRejectsIllegalIdentifiers", () => {
    assert.throws(() => PgIdentifiers.quote(""));
    assert.throws(() => PgIdentifiers.quote("a\0b"));
    assert.throws(() => PgIdentifiers.quote("x".repeat(64)));
    assert.throws(() => PgIdentifiers.quote(null as unknown as string));
});

test("scratchNameFormatAndLimit", () => {
    const name = PgIdentifiers.scratchName("app", 0x1A2B3C);
    assert.ok(name.startsWith("app__rcl_"), name);
    assert.equal(name.substring(0, name.indexOf("__rcl_")), "app");
    const longNs = "n".repeat(100);
    assert.ok(new TextEncoder().encode(
            PgIdentifiers.scratchName(longNs, 1)).length <= 63);
});

test("constantDefaultLexicalRule", () => {
    assert.ok(PgIdentifiers.isConstantDefault("42"));
    assert.ok(PgIdentifiers.isConstantDefault("-3.14"));
    assert.ok(PgIdentifiers.isConstantDefault("'text'"));
    assert.ok(PgIdentifiers.isConstantDefault("'it''s'"));
    assert.ok(PgIdentifiers.isConstantDefault("true"));
    assert.ok(PgIdentifiers.isConstantDefault("NULL"));
    assert.ok(PgIdentifiers.isConstantDefault("42::bigint"));
    assert.ok(PgIdentifiers.isConstantDefault("'x'::text"));

    assert.ok(!PgIdentifiers.isConstantDefault("now()"));
    assert.ok(!PgIdentifiers.isConstantDefault("nextval('s'::regclass)"));
    assert.ok(!PgIdentifiers.isConstantDefault("a + b"));
    assert.ok(!PgIdentifiers.isConstantDefault("gen_random_uuid()"));
    assert.ok(!PgIdentifiers.isConstantDefault("1::int::bigint"));
    assert.ok(!PgIdentifiers.isConstantDefault("'unterminated"));
    assert.ok(!PgIdentifiers.isConstantDefault(null));

    // anything past the cast's type name is an expression, not a plain cast
    assert.ok(!PgIdentifiers.isConstantDefault("1::int + 2"));
    assert.ok(!PgIdentifiers.isConstantDefault("1::int + nextval('s')"));
    assert.ok(!PgIdentifiers.isConstantDefault("'x'::text || 'y'"));
    assert.ok(!PgIdentifiers.isConstantDefault("1::int/*evil*/"));
    assert.ok(!PgIdentifiers.isConstantDefault("0::int; DROP TABLE t"));
    assert.ok(!PgIdentifiers.isConstantDefault("1::unknown-func()"));
    assert.ok(!PgIdentifiers.isConstantDefault("now()::timestamptz"));

    // legitimate single casts still qualify
    assert.ok(PgIdentifiers.isConstantDefault("42::numeric(10,2)"));
    assert.ok(PgIdentifiers.isConstantDefault("1::int[]"));
    assert.ok(PgIdentifiers.isConstantDefault("'x'::pg_catalog.text"));
    assert.ok(PgIdentifiers.isConstantDefault("7::\"Weird Type\""));
    assert.ok(PgIdentifiers.isConstantDefault("1 :: int"));
});

// ---- PgSql -------------------------------------------------------------

test("statementsSplitOnSemicolonsNotInsideLiterals", () => {
    const statements = PgSql.statements(
            "CREATE TABLE t (a text DEFAULT ';');"
                    + " CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$"
                    + " BEGIN PERFORM 1; PERFORM 2; END $$;"
                    + " CREATE TABLE u (b int)");
    assert.equal(statements.length, 3);
    assert.equal(statements[1]!.tokens[0], "CREATE");
    assert.ok(statements[1]!.text.includes("PERFORM"));
});

test("dollarQuotedTagsRespected", () => {
    const statements = PgSql.statements(
            "SELECT $body$a;b$c$body$; SELECT 2");
    assert.equal(statements.length, 2);
});

test("commentsAreSkipped", () => {
    const statements = PgSql.statements(
            "-- a comment ; with semicolon\nCREATE TABLE t (a int) /* block ; */");
    assert.equal(statements.length, 1);
});

test("namespaceReferenceNormalisation", () => {
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM \"app\".t", "app"),
            "SELECT * FROM ${namespace}.t");
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM app.t", "app"),
            "SELECT * FROM ${namespace}.t");
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM APP.t", "app"),
            "SELECT * FROM ${namespace}.t");
    // no false positives on prefixes or different schemas
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM apple.t", "app"),
            "SELECT * FROM apple.t");
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM other.t", "app"),
            "SELECT * FROM other.t");
});

test("quotedNamespaceMatchIsCaseSensitive", () => {
    // "APP" is a different schema from app — quoting makes it case-sensitive
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM \"APP\".t", "app"),
            "SELECT * FROM \"APP\".t");
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM \"Mixed\".t", "MIXED"),
            "SELECT * FROM \"Mixed\".t");
    // the exact quoted spelling still matches
    assert.equal(PgSql.replaceNamespaceRefs("SELECT * FROM \"MIXED\".t", "MIXED"),
            "SELECT * FROM ${namespace}.t");
});

test("collapseWhitespaceLeavesLiteralsAlone", () => {
    assert.equal(PgSql.collapseWhitespace("  SELECT   'a  b'\n  FROM   t  "),
            "SELECT 'a  b' FROM t");
    assert.equal(
            PgSql.collapseWhitespace("CREATE FUNCTION f() RETURNS void AS $$ x  y $$"),
            "CREATE FUNCTION f() RETURNS void AS $$ x  y $$");
});

// ---- PgRender ------------------------------------------------------------

function missing(id: ObjectId): Difference {
    return new Difference(id, DifferenceType.MISSING,
            new Definition("d"), undefined);
}

function drifted(id: ObjectId): Difference {
    return new Difference(id, DifferenceType.DRIFTED,
            new Definition("want"), new Definition("have"));
}

function scopes(id: ObjectId): Set<string> {
    return new Set(PgRender.nameScopes(id));
}

test("nameScopesFollowTheATable", () => {
    assert.deepEqual(scopes(new ObjectId(ObjectKind.TABLE, "t")),
            new Set(["relation:t"]));
    assert.deepEqual(scopes(new ObjectId(ObjectKind.INDEX, "i", "t")),
            new Set(["relation:i"]));
    assert.deepEqual(scopes(new ObjectId(ObjectKind.PRIMARY_KEY, "pk", "t")),
            new Set(["relation:pk", "constraint:t.pk"]));
    assert.deepEqual(scopes(new ObjectId(ObjectKind.FOREIGN_KEY, "fk", "t")),
            new Set(["constraint:t.fk"]));
    assert.equal(scopes(new ObjectId(ObjectKind.COLUMN, "c", "t")).size, 0);
    assert.equal(scopes(new ObjectId(ObjectKind.TRIGGER, "g", "t")).size, 0);
    assert.deepEqual(scopes(new ObjectId(ObjectKind.ROUTINE, "f(integer)")),
            new Set(["routine:f(integer)"]));
    // an exclusion constraint names both its pg_constraint row and its
    // backing pg_class index — a create must collide with either
    assert.deepEqual(scopes(new ObjectId(ObjectKind.EXCLUSION, "ex", "t")),
            new Set(["constraint:t.ex", "relation:ex"]));
    // an unsupported aggregate/window occupies the routine signature space
    assert.deepEqual(scopes(new ObjectId(ObjectKind.OTHER, "routine:f(integer)")),
            new Set(["routine:f(integer)"]));
    assert.deepEqual(scopes(new ObjectId(ObjectKind.OTHER, "type:m")),
            new Set(["relation:m"]));
});

test("coveredChildrenForTable", () => {
    const table = new ObjectId(ObjectKind.TABLE, "t");
    const seq = new SchemaObject(new ObjectId(ObjectKind.SEQUENCE, "t_id_seq"),
            new Definition("seq"), new ObjectSet([table]),
            new Map([[PgCatalog.CREATE_SQL, "CREATE SEQUENCE ${namespace}.\"t_id_seq\""]]));
    const desired = snap(
            obj(table, "t", new Map()),
            obj(new ObjectId(ObjectKind.COLUMN, "id", "t"), "c",
                    new Map([[PgCatalog.COLUMN_SQL, "\"id\" bigint"],
                            [PgCatalog.ATTNUM, "1"]])),
            seq);
    const covered = new Set(PgRender.coveredChildren(missing(table), desired));
    assert.deepEqual(covered, new Set([
            new ObjectId(ObjectKind.COLUMN, "id", "t"),
            new ObjectId(ObjectKind.SEQUENCE, "t_id_seq")]));
    assert.equal([...PgRender.coveredChildren(
            missing(new ObjectId(ObjectKind.VIEW, "v")), desired)].length, 0);
});

test("missingTableRendersOneCreate", () => {
    const table = new ObjectId(ObjectKind.TABLE, "t");
    const desired = snap(
            obj(table, "relkind=r;relpersistence=p", new Map()),
            obj(new ObjectId(ObjectKind.COLUMN, "id", "t"), "c",
                    new Map([[PgCatalog.COLUMN_SQL, "\"id\" bigint NOT NULL"],
                            [PgCatalog.ATTNUM, "1"]])),
            obj(new ObjectId(ObjectKind.COLUMN, "nm", "t"), "c",
                    new Map([[PgCatalog.COLUMN_SQL, "\"nm\" text"],
                            [PgCatalog.ATTNUM, "2"]])));
    const changes = PgRender.render(missing(table), desired, "core");
    assert.equal(changes.length, 1);
    const change = changes[0]!;
    assert.equal(change.action, ChangeAction.CREATE);
    assert.equal(change.safety, ChangeSafety.ADDITIVE);
    const sql = change.statements[0]!;
    assert.ok(sql.includes("CREATE TABLE \"core\".\"t\""), sql);
    assert.ok(sql.includes("\"id\" bigint NOT NULL"), sql);
    assert.ok(sql.indexOf("\"id\"") < sql.indexOf("\"nm\""),
            "columns must render in attnum order: " + sql);
});

test("missingTableFoldsSerialSequence", () => {
    const table = new ObjectId(ObjectKind.TABLE, "t");
    const seq = new ObjectId(ObjectKind.SEQUENCE, "t_id_seq");
    const seqObj = new SchemaObject(seq, new Definition("seq"),
            new ObjectSet([table]),
            new Map([[PgCatalog.CREATE_SQL,
                    "CREATE SEQUENCE ${namespace}.\"t_id_seq\" AS bigint"],
                    [PgCatalog.OWNED_SQL,
                    "ALTER SEQUENCE ${namespace}.\"t_id_seq\" OWNED BY ${namespace}.t.id"]]));
    const desired = snap(
            obj(table, "relkind=r;relpersistence=p", new Map()),
            obj(new ObjectId(ObjectKind.COLUMN, "id", "t"), "c",
                    new Map([[PgCatalog.COLUMN_SQL,
                            "\"id\" bigint DEFAULT nextval('${namespace}.\"t_id_seq\"'::regclass) NOT NULL"],
                            [PgCatalog.ATTNUM, "1"]])),
            seqObj);
    const changes = PgRender.render(missing(table), desired, "core");
    const statements = changes[0]!.statements;
    assert.equal(statements.length, 3, statements.toString());
    assert.ok(statements[0]!.startsWith("CREATE SEQUENCE \"core\""));
    assert.ok(statements[1]!.startsWith("CREATE TABLE"));
    assert.ok(statements[2]!.includes("OWNED BY"));
});

test("missingColumnVariants", () => {
    const col = new ObjectId(ObjectKind.COLUMN, "c", "t");
    // nullable → ADDITIVE
    const nullable = snap(obj(col, "c",
            new Map([[PgCatalog.COLUMN_SQL, "\"c\" text"],
                    [PgCatalog.NOTNULL, "false"]])));
    const n = PgRender.render(missing(col), nullable, "core")[0]!;
    assert.equal(n.safety, ChangeSafety.ADDITIVE);
    assert.ok(n.statements[0]!.includes("ADD COLUMN"), n.statements.toString());

    // NOT NULL + constant default → ADDITIVE
    const constant = snap(obj(col, "c",
            new Map([[PgCatalog.COLUMN_SQL, "\"c\" bigint DEFAULT 0 NOT NULL"],
                    [PgCatalog.NOTNULL, "true"], [PgCatalog.DEFAULT, "0"]])));
    assert.equal(PgRender.render(missing(col), constant, "core")[0]!.safety,
            ChangeSafety.ADDITIVE);

    // NOT NULL + volatile default → manual DESTRUCTIVE
    const volatile_ = snap(obj(col, "c",
            new Map([[PgCatalog.COLUMN_SQL, "\"c\" bigint DEFAULT nextval('s') NOT NULL"],
                    [PgCatalog.NOTNULL, "true"],
                    [PgCatalog.DEFAULT, "nextval('s')"]])));
    const v = PgRender.render(missing(col), volatile_, "core")[0]!;
    assert.equal(v.safety, ChangeSafety.DESTRUCTIVE);
    assert.ok(v.isManual);

    // NOT NULL + no default → manual DESTRUCTIVE
    const noDefault = snap(obj(col, "c",
            new Map([[PgCatalog.COLUMN_SQL, "\"c\" bigint NOT NULL"],
                    [PgCatalog.NOTNULL, "true"]])));
    const m = PgRender.render(missing(col), noDefault, "core")[0]!;
    assert.equal(m.safety, ChangeSafety.DESTRUCTIVE);
    assert.ok(m.isManual);
});

test("driftedViewIsDropCreatePair", () => {
    const view = new ObjectId(ObjectKind.VIEW, "v");
    const desired = snap(obj(view, "viewdef",
            new Map([[PgCatalog.CREATE_SQL, "CREATE VIEW ${namespace}.\"v\" AS SELECT 1"],
                    [PgCatalog.DROP_SQL, "DROP VIEW ${namespace}.\"v\""]])));
    const changes = PgRender.render(drifted(view), desired, "core");
    assert.equal(changes.length, 2);
    assert.equal(changes[0]!.action, ChangeAction.DROP);
    assert.equal(changes[1]!.action, ChangeAction.CREATE);
    assert.equal(changes[0]!.statements[0], "DROP VIEW \"core\".\"v\"");
    assert.ok(!changes[0]!.statements[0]!.includes("CASCADE"),
            "no CASCADE anywhere (spec)");
});

test("driftedTriggerWithPostSqlRendersWithoutCrashing", () => {
    const trg = new ObjectId(ObjectKind.TRIGGER, "g", "t");
    const desired = snap(obj(trg, "tgdef;enabled=D",
            new Map([[PgCatalog.CREATE_SQL,
                    "CREATE TRIGGER \"g\" BEFORE INSERT ON ${namespace}.\"t\""
                            + " FOR EACH ROW EXECUTE FUNCTION ${namespace}.\"f\"()"],
                    [PgCatalog.DROP_SQL, "DROP TRIGGER \"g\" ON ${namespace}.\"t\""],
                    [PgCatalog.POST_SQL, "ALTER TABLE ${namespace}.\"t\" DISABLE TRIGGER \"g\""]])));
    const pair = PgRender.render(drifted(trg), desired, "core");
    assert.deepEqual(pair.map(c => c.action),
            [ChangeAction.DROP, ChangeAction.CREATE]);
    assert.ok(pair[1]!.statements[1]!.includes("DISABLE TRIGGER"),
            pair[1]!.statements.toString());
});

test("driftedSequencePersistenceGetsItsOwnAlterStatement", () => {
    const seq = new ObjectId(ObjectKind.SEQUENCE, "s");
    const want = "type=bigint;increment=1;min=1;max=9;start=1;cache=1"
            + ";cycle=false;relpersistence=u";
    const desired = snap(obj(seq, want,
            new Map([[PgCatalog.OPTIONS_SQL,
                    "AS bigint INCREMENT BY 1 MINVALUE 1 MAXVALUE 9 START 1 CACHE 1 NO CYCLE"],
                    [PgCatalog.CREATE_SQL,
                    "CREATE UNLOGGED SEQUENCE ${namespace}.\"s\" AS bigint"],
                    [PgCatalog.DROP_SQL, "DROP SEQUENCE ${namespace}.\"s\""]])));
    const d = new Difference(seq, DifferenceType.DRIFTED,
            new Definition(want),
            new Definition(want.replace("relpersistence=u", "relpersistence=p")));
    const statements = PgRender.render(d, desired, "core")[0]!.statements;
    assert.ok(statements[0]!.startsWith("ALTER SEQUENCE \"core\".\"s\" AS bigint"),
            statements.toString());
    assert.equal(statements[statements.length - 1],
            "ALTER SEQUENCE \"core\".\"s\" SET UNLOGGED");
});

test("driftedRoutineSameReturnTypeIsAlter", () => {
    const fn = new ObjectId(ObjectKind.ROUTINE, "f()");
    const desired = snap(obj(fn, "def",
            new Map([[PgCatalog.CREATE_SQL,
                    "CREATE OR REPLACE FUNCTION ${namespace}.\"f\"() RETURNS void LANGUAGE sql AS 'SELECT 1'"],
                    [PgCatalog.RETURN_TYPE, "void"],
                    [PgCatalog.DROP_SQL, "DROP FUNCTION ${namespace}.\"f\"()"]])));
    const d = new Difference(fn, DifferenceType.DRIFTED,
            new Definition("w"),
            new Definition("RETURNS void LANGUAGE x"));
    const changes = PgRender.render(d, desired, "core");
    assert.equal(changes.length, 1);
    assert.equal(changes[0]!.action, ChangeAction.ALTER);
    assert.equal(changes[0]!.safety, ChangeSafety.REBUILD);

    // return type changed → DROP+CREATE
    const d2 = new Difference(fn, DifferenceType.DRIFTED,
            new Definition("w"),
            new Definition("RETURNS integer LANGUAGE x"));
    const pair = PgRender.render(d2, desired, "core");
    assert.deepEqual(pair.map(c => c.action),
            [ChangeAction.DROP, ChangeAction.CREATE]);
});

test("driftedSetofRoutineSameReturnTypeIsAlter", () => {
    const fn = new ObjectId(ObjectKind.ROUTINE, "f()");
    const desired = snap(obj(fn, "def",
            new Map([[PgCatalog.CREATE_SQL,
                    "CREATE OR REPLACE FUNCTION ${namespace}.\"f\"() RETURNS SETOF integer LANGUAGE sql AS 'SELECT 1'"],
                    [PgCatalog.RETURN_TYPE, "SETOF integer"],
                    [PgCatalog.DROP_SQL, "DROP FUNCTION ${namespace}.\"f\"()"]])));
    const d = new Difference(fn, DifferenceType.DRIFTED,
            new Definition("w"),
            new Definition("RETURNS SETOF integer LANGUAGE x"));
    const changes = PgRender.render(d, desired, "core");
    assert.equal(changes.length, 1);
    assert.equal(changes[0]!.action, ChangeAction.ALTER);
});

test("driftedColumnIsManualDestructive", () => {
    const col = new ObjectId(ObjectKind.COLUMN, "c", "t");
    const desired = snap(obj(col, "c", new Map()));
    const change = PgRender.render(drifted(col), desired, "core")[0]!;
    assert.equal(change.action, ChangeAction.ALTER);
    assert.equal(change.safety, ChangeSafety.DESTRUCTIVE);
    assert.ok(change.isManual);
});

function driftedColumn(want: string, have: string): Difference {
    return new Difference(new ObjectId(ObjectKind.COLUMN, "c", "t"),
            DifferenceType.DRIFTED,
            new Definition(want), new Definition(have));
}

function columnSnap(canonical: string): SchemaSnapshot {
    return snap(obj(new ObjectId(ObjectKind.COLUMN, "c", "t"), canonical,
            new Map()));
}

test("driftedColumnDefaultAltersInPlace", () => {
    const d = driftedColumn("type=text;notnull=false;default='b'::text",
            "type=text;notnull=false;default='a'::text");
    const change = PgRender.render(d, columnSnap(
            "type=text;notnull=false;default='b'::text"), "core")[0]!;
    assert.equal(change.action, ChangeAction.ALTER);
    assert.equal(change.safety, ChangeSafety.REBUILD);
    assert.deepEqual([...change.statements],
            ["ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\""
                    + " SET DEFAULT 'b'::text"]);
});

test("driftedColumnQuotedSemicolonsDoNotSplitCanonicalFields", () => {
    // ';' inside a 'literal' or a "quoted identifier" is value content,
    // not a field separator — only the default differs
    const want = "type=\"a;b\";notnull=false;default='x;y'";
    const d = driftedColumn(want,
            "type=\"a;b\";notnull=false;default='x;z'");
    const change = PgRender.render(d, columnSnap(want), "core")[0]!;
    assert.equal(change.safety, ChangeSafety.REBUILD);
    assert.deepEqual([...change.statements],
            ["ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\""
                    + " SET DEFAULT 'x;y'"]);
});

test("driftedColumnNotNullSplitsByDataRisk", () => {
    // DROP NOT NULL is a metadata change (REBUILD); SET NOT NULL validates
    // existing rows and can fail on them (rendered DESTRUCTIVE, 5.6 rule 1)
    let d = driftedColumn("type=text;notnull=true",
            "type=text;notnull=false");
    let changes = PgRender.render(d, columnSnap("type=text;notnull=true"), "core");
    assert.equal(changes.length, 1);
    assert.equal(changes[0]!.safety, ChangeSafety.DESTRUCTIVE);
    assert.deepEqual([...changes[0]!.statements],
            ["ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET NOT NULL"]);
    d = driftedColumn("type=text;notnull=false;default='a'::text",
            "type=text;notnull=true");
    const change = PgRender.render(d, columnSnap(
            "type=text;notnull=false;default='a'::text"), "core")[0]!;
    assert.deepEqual([...change.statements], [
            "ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET DEFAULT 'a'::text",
            "ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" DROP NOT NULL"]);
    assert.equal(change.safety, ChangeSafety.REBUILD);
});

test("driftedColumnSetNotNullDefersToDestructiveChange", () => {
    // safe parts converge under the default policy; SET NOT NULL stays
    // gated — and the REBUILD half's expected omits the deferred notnull
    const d = driftedColumn("type=text;notnull=true;default='a'::text",
            "type=text;notnull=false");
    const changes = PgRender.render(d, columnSnap(
            "type=text;notnull=true;default='a'::text"), "core");
    assert.equal(changes.length, 2);
    assert.equal(changes[0]!.safety, ChangeSafety.REBUILD);
    assert.deepEqual([...changes[0]!.statements],
            ["ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\""
                    + " SET DEFAULT 'a'::text"]);
    assert.equal(changes[0]!.expected!.canonical,
            "type=text;notnull=false;default='a'::text");
    assert.equal(changes[1]!.safety, ChangeSafety.DESTRUCTIVE);
    assert.deepEqual([...changes[1]!.statements],
            ["ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET NOT NULL"]);
    assert.equal(changes[1]!.expected!.canonical,
            "type=text;notnull=true;default='a'::text");
});

test("driftedColumnStorageAndCompressionAlterInPlace", () => {
    const want = "type=text;notnull=false;storage=e;compression=p";
    const d = driftedColumn(want, "type=text;notnull=false;storage=x;compression=");
    const change = PgRender.render(d, columnSnap(want), "core")[0]!;
    assert.equal(change.safety, ChangeSafety.REBUILD);
    assert.deepEqual([...change.statements], [
            "ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET STORAGE EXTERNAL",
            "ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" SET COMPRESSION pglz"]);
});

test("driftedColumnWideningTypeAltersInPlace", () => {
    for (const pair of [
            ["character varying(20)", "character varying(10)"],
            ["text", "character varying(10)"],
            ["character varying", "character varying(10)"],
            ["character(8)", "character(5)"],
            ["bigint", "integer"],
            ["integer", "smallint"],
            ["numeric(12,3)", "numeric(10,2)"],
            ["numeric(12,4)", "numeric(10,2)"],
            ["numeric", "numeric(10,2)"]]) {
        const want = "type=" + pair[0] + ";notnull=false";
        const have = "type=" + pair[1] + ";notnull=false";
        const change = PgRender.render(driftedColumn(want, have),
                columnSnap(want), "core")[0]!;
        assert.equal(change.safety, ChangeSafety.REBUILD,
                pair[0] + " <- " + pair[1]);
        assert.deepEqual([...change.statements],
                ["ALTER TABLE \"core\".\"t\" ALTER COLUMN \"c\" TYPE "
                        + pair[0]]);
    }
});

test("driftedColumnUnsafeTypeStaysManual", () => {
    for (const pair of [
            ["character varying(10)", "character varying(20)"],   // narrowing
            ["character varying(10)", "text"],                    // bounded
            ["integer", "bigint"],                                // narrowing
            ["boolean", "text"],                                  // unrelated
            ["text", "character(5)"],                             // bpchar strips padding
            ["character varying(10)", "character(5)"],            // bpchar strips padding
            ["character(5)", "character varying(10)"],            // adds padding semantics
            ["numeric(10,4)", "numeric(10,2)"],                   // fewer integer digits
            ["numeric(10,2)", "numeric(10,4)"],                   // scale shrinks
            ["text", "integer"],
            ["timestamp with time zone", "timestamp without time zone"]]) {
        const want = "type=" + pair[0] + ";notnull=false";
        const have = "type=" + pair[1] + ";notnull=false";
        const change = PgRender.render(driftedColumn(want, have),
                columnSnap(want), "core")[0]!;
        assert.equal(change.safety, ChangeSafety.DESTRUCTIVE,
                pair[0] + " <- " + pair[1]);
        assert.ok(change.isManual);
    }
});

test("driftedColumnGeneratedOrIdentityStaysManual", () => {
    let change = PgRender.render(driftedColumn(
            "type=int;notnull=false;generated=s:(a + 1)",
            "type=int;notnull=false"),
            columnSnap("type=int;notnull=false;generated=s:(a + 1)"), "core")[0]!;
    assert.equal(change.safety, ChangeSafety.DESTRUCTIVE);
    assert.ok(change.isManual);
    change = PgRender.render(driftedColumn(
            "type=bigint;notnull=false;identity=a;identityOptions=x",
            "type=bigint;notnull=false"),
            columnSnap("type=bigint;notnull=false;identity=a;identityOptions=x"),
            "core")[0]!;
    assert.equal(change.safety, ChangeSafety.DESTRUCTIVE);
});
