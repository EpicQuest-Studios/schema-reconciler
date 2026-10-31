import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ChangeAction, ChangeSafety, DesiredState, SchemaReconciler }
        from "@epicquest-studios/schema-reconciler-core";
import type { PlannedChange } from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available, execute } from "./PgTestDatabase.ts";

/** Spec A.6: the rendering table, each row verified end to end. */

interface Row {
    readonly name: string;
    readonly target: string;
    readonly desired: string;
    readonly expected: (changes: readonly PlannedChange[]) => boolean;
}

let sequence = 0;

function has(changes: readonly PlannedChange[], action: ChangeAction,
        safety: ChangeSafety, ...fragments: string[]): boolean {
    return changes.some(c => c.action === action && c.safety === safety
            && fragments.every(f =>
                    c.statements.some(s => s.includes(f))));
}

function manual(changes: readonly PlannedChange[], safety: ChangeSafety):
        boolean {
    return changes.some(c => c.isManual && c.safety === safety);
}

const ROWS: readonly Row[] = [
    { name: "MISSING table: options, replica identity, one ADDITIVE change",
        target: "",
        desired: "CREATE TABLE t (id bigint NOT NULL, note text)"
                + " WITH (fillfactor = 70);"
                + " ALTER TABLE t REPLICA IDENTITY FULL",
        expected: ch => ch.length === 1 && has(ch, ChangeAction.CREATE,
                ChangeSafety.ADDITIVE, "CREATE TABLE", "WITH (fillfactor=70)",
                "REPLICA IDENTITY FULL") },
    { name: "MISSING nullable column",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE TABLE t (id bigint, extra text)",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "ADD COLUMN") },
    { name: "MISSING NOT NULL column with constant default",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE TABLE t (id bigint, flag int NOT NULL DEFAULT 0)",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "ADD COLUMN", "NOT NULL") },
    { name: "MISSING NOT NULL column, non-constant default: manual DESTRUCTIVE",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE TABLE t (id bigint, at timestamptz NOT NULL"
                + " DEFAULT now())",
        expected: ch => manual(ch, ChangeSafety.DESTRUCTIVE) },
    { name: "MISSING NOT NULL column without default: manual DESTRUCTIVE",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE TABLE t (id bigint, must int NOT NULL)",
        expected: ch => manual(ch, ChangeSafety.DESTRUCTIVE) },
    { name: "MISSING constraint",
        target: "CREATE TABLE ${ns}.t (id bigint, n int)",
        desired: "CREATE TABLE t (id bigint, n int,"
                + " CONSTRAINT ck CHECK (n > 0))",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "ADD CONSTRAINT") },
    { name: "DRIFTED constraint: DROP then CREATE, REBUILD",
        target: "CREATE TABLE ${ns}.t (id bigint, n int,"
                + " CONSTRAINT ck CHECK (n > 1))",
        desired: "CREATE TABLE t (id bigint, n int,"
                + " CONSTRAINT ck CHECK (n > 0))",
        expected: ch => has(ch, ChangeAction.DROP, ChangeSafety.REBUILD,
                "DROP CONSTRAINT")
                && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        "ADD CONSTRAINT") },
    { name: "MISSING index",
        target: "CREATE TABLE ${ns}.t (id bigint, n int)",
        desired: "CREATE TABLE t (id bigint, n int); CREATE INDEX ix ON t (n)",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "CREATE INDEX") },
    { name: "DRIFTED index: DROP INDEX then CREATE",
        target: "CREATE TABLE ${ns}.t (id bigint, n int);"
                + " CREATE INDEX ix ON ${ns}.t (id)",
        desired: "CREATE TABLE t (id bigint, n int); CREATE INDEX ix ON t (n)",
        expected: ch => has(ch, ChangeAction.DROP, ChangeSafety.REBUILD,
                "DROP INDEX")
                && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        "CREATE INDEX") },
    { name: "MISSING view",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE TABLE t (id bigint);"
                + " CREATE VIEW v AS SELECT id FROM t",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "CREATE VIEW") },
    { name: "DRIFTED view: DROP VIEW then CREATE",
        target: "CREATE TABLE ${ns}.t (id bigint, n int);"
                + " CREATE VIEW ${ns}.v AS SELECT id FROM ${ns}.t",
        desired: "CREATE TABLE t (id bigint, n int);"
                + " CREATE VIEW v AS SELECT n FROM t",
        expected: ch => has(ch, ChangeAction.DROP, ChangeSafety.REBUILD,
                "DROP VIEW")
                && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        "CREATE VIEW") },
    { name: "MISSING owned sequence not covered by a table create",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE SEQUENCE sq;"
                + " CREATE TABLE t (id bigint DEFAULT nextval('sq'));"
                + " ALTER SEQUENCE sq OWNED BY t.id",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "CREATE SEQUENCE", "OWNED BY") },
    { name: "DRIFTED sequence: ALTER SEQUENCE, DESTRUCTIVE",
        target: "CREATE SEQUENCE ${ns}.sq INCREMENT BY 1",
        desired: "CREATE SEQUENCE sq INCREMENT BY 5",
        expected: ch => has(ch, ChangeAction.ALTER, ChangeSafety.DESTRUCTIVE,
                "ALTER SEQUENCE") },
    { name: "MISSING routine",
        target: "",
        desired: "CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql"
                + " AS $$ SELECT a $$",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "FUNCTION") },
    { name: "DRIFTED routine, same return type: CREATE OR REPLACE as"
            + " ALTER/REBUILD",
        target: "CREATE FUNCTION ${ns}.f(a int) RETURNS int LANGUAGE sql"
                + " AS $$ SELECT a $$",
        desired: "CREATE FUNCTION f(a int) RETURNS int LANGUAGE sql"
                + " AS $$ SELECT a + 1 $$",
        expected: ch => has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD,
                "CREATE OR REPLACE") },
    { name: "DRIFTED routine, return type changed: DROP FUNCTION then CREATE",
        target: "CREATE FUNCTION ${ns}.f(a int) RETURNS int LANGUAGE sql"
                + " AS $$ SELECT a $$",
        desired: "CREATE FUNCTION f(a int) RETURNS bigint LANGUAGE sql"
                + " AS $$ SELECT a::bigint $$",
        expected: ch => has(ch, ChangeAction.DROP, ChangeSafety.REBUILD,
                "DROP FUNCTION")
                && has(ch, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        "FUNCTION") },
    { name: "MISSING trigger with a non-default enabled state",
        target: "CREATE TABLE ${ns}.t (id bigint);"
                + " CREATE FUNCTION ${ns}.trg() RETURNS trigger"
                + " LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$",
        desired: "CREATE TABLE t (id bigint);"
                + " CREATE FUNCTION trg() RETURNS trigger LANGUAGE plpgsql"
                + " AS $$ BEGIN RETURN NEW; END $$;"
                + " CREATE TRIGGER tr BEFORE INSERT ON t"
                + " FOR EACH ROW EXECUTE FUNCTION trg();"
                + " ALTER TABLE t DISABLE TRIGGER tr",
        expected: ch => has(ch, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                "CREATE TRIGGER", "DISABLE TRIGGER") },
    { name: "DRIFTED column: default and DROP NOT NULL in one REBUILD ALTER",
        target: "CREATE TABLE ${ns}.t (id bigint, c text NOT NULL)",
        desired: "CREATE TABLE t (id bigint, c text DEFAULT 'x')",
        expected: ch => has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD,
                "SET DEFAULT", "DROP NOT NULL") },
    { name: "DRIFTED column: widening TYPE",
        target: "CREATE TABLE ${ns}.t (id bigint, c int)",
        desired: "CREATE TABLE t (id bigint, c bigint)",
        expected: ch => has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD,
                "TYPE bigint") },
    { name: "DRIFTED column also needing SET NOT NULL: second DESTRUCTIVE"
            + " change",
        target: "CREATE TABLE ${ns}.t (id bigint, c text DEFAULT 'a')",
        desired: "CREATE TABLE t (id bigint, c text NOT NULL DEFAULT 'b')",
        expected: ch => has(ch, ChangeAction.ALTER, ChangeSafety.REBUILD,
                "SET DEFAULT")
                && has(ch, ChangeAction.ALTER, ChangeSafety.DESTRUCTIVE,
                        "SET NOT NULL") },
    { name: "DRIFTED column: generated change is manual DESTRUCTIVE",
        target: "CREATE TABLE ${ns}.t (id bigint, g int)",
        desired: "CREATE TABLE t (id bigint, g int"
                + " GENERATED ALWAYS AS (id + 1) STORED)",
        expected: ch => manual(ch, ChangeSafety.DESTRUCTIVE) },
    { name: "DRIFTED column: non-widening type is manual DESTRUCTIVE",
        target: "CREATE TABLE ${ns}.t (id bigint, c bigint)",
        desired: "CREATE TABLE t (id bigint, c int)",
        expected: ch => manual(ch, ChangeSafety.DESTRUCTIVE) },
    { name: "DRIFTED table: manual DESTRUCTIVE",
        target: "CREATE TABLE ${ns}.t (id bigint)",
        desired: "CREATE UNLOGGED TABLE t (id bigint)",
        expected: ch => manual(ch, ChangeSafety.DESTRUCTIVE) },
    { name: "table-shape conflict: unexpected column is manual DESTRUCTIVE",
        target: "CREATE TABLE ${ns}.t (id bigint, foreign_col text)",
        desired: "CREATE TABLE t (id bigint)",
        expected: ch => manual(ch, ChangeSafety.DESTRUCTIVE) },
];

describe("RenderingTableTest", { skip: !(await available())
        ? "no PostgreSQL test database" : false }, () => {
    const ds = new PgTestDataSource();

    it("every row of the rendering table holds", async () => {
        const failures: string[] = [];
        for (const row of ROWS) {
            const ns = "rnd_" + process.hrtime.bigint().toString(16)
                    + "_" + (++sequence);
            await execute(ds, "CREATE SCHEMA " + ns);
            try {
                if (row.target !== "") {
                    await execute(ds, row.target.replaceAll("${ns}", ns));
                }
                const plan = await SchemaReconciler.builder().dataSource(ds)
                        .namespace(ns)
                        .desiredState(DesiredState.ofScript(row.desired))
                        .dialect(new PostgreSqlDialect()).build().plan();
                if (!row.expected(plan.changes)) {
                    failures.push(row.name + " -> " + plan.changes.map(c =>
                            c.action + "/" + c.safety
                            + (c.isManual ? "/MANUAL" : "")
                            + " " + c.statements).join(" | "));
                }
            } finally {
                await execute(ds, "DROP SCHEMA " + ns + " CASCADE");
            }
        }
        assert.equal(failures.join("\n"), "");
    });
});
