import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { describe, it } from "node:test";
import { ConfigurationException, DesiredState, DesiredStateException,
        ReconcileOutcome, SchemaReconciler }
        from "@schema-reconciler/core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available, execute }
        from "./PgTestDatabase.ts";

/** Spec 2.2 (dialect wiring), 8.3 (namespace validation) and A.3 (savepoint
 *  recovery). The Java class's JPMS/ServiceLoader discovery test has no
 *  TypeScript analog — module packaging is an npm concern, not a runtime
 *  contract — so it is intentionally not ported. */

const dialect = new PostgreSqlDialect();
const scratch = new PgTestDataSource();
const dbUp = await available();

async function rejects<T extends Error>(run: () => Promise<unknown>): Promise<T> {
    try {
        await run();
    } catch (e) {
        return e as T;
    }
    assert.fail("expected a rejection");
}

describe("ProfileRulesTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    it("an illegal namespace is a ConfigurationException", async () => {
        const tooLong = "n".repeat(64);
        const reconciler = SchemaReconciler.builder()
                .dataSource(scratch).namespace(tooLong)
                .desiredState(DesiredState.ofScript("CREATE TABLE t (id int)"))
                .dialect(dialect).build();
        const e = await rejects<ConfigurationException>(
                () => reconciler.plan());
        assert.ok(e instanceof ConfigurationException);
        assert.ok(e.message.includes("not a legal identifier"), e.message);
    });

    it("a script that committed is cleaned up and reported", async () => {
        let scratchName: string;
        const c = await scratch.connect();
        try {
            await c.setAutoCommit(false);
            scratchName = await dialect.createScratchNamespace(c,
                    "profile_rules");
            await c.commit(); // what a script's COMMIT does: savepoint is gone
            const e = await rejects<DesiredStateException>(
                    () => dialect.dropScratchNamespace(c, scratchName));
            assert.ok(e instanceof DesiredStateException);
            assert.ok(e.message.includes("savepoint"), e.message);
        } finally {
            await c.close();
        }
        const check = await scratch.connect();
        try {
            const rows = await check.query<{ count: string | number }>(
                    "SELECT count(*) FROM pg_namespace WHERE nspname = '"
                    + scratchName! + "'");
            assert.equal(Number(rows[0]!.count), 0,
                    "the committed scratch schema must have been dropped");
        } finally {
            await check.close();
        }
    });

    it("TCK scenario ids match the SPEC.md table", async () => {
        const root = new URL("../../../../", import.meta.url).pathname;
        const spec = new Set<string>();
        for (const line of (await readFile(root + "SPEC.md", "utf8"))
                .split("\n")) {
            const m = /^\| (C-\d+) \|/.exec(line);
            if (m !== null) {
                spec.add(m[1]!);
            }
        }
        const tck = new Set<string>();
        const source = await readFile(root
                + "typescript/packages/tck/src/DialectConformanceTest.ts",
                "utf8");
        for (const m of source.matchAll(/it\("C-(\d+)/g)) {
            tck.add("C-" + m[1]);
        }
        assert.equal(spec.size, 67);
        assert.deepEqual(tck, spec,
                "TCK scenarios and SPEC.md rows must match 1:1");
    });

    it("hostile catalog names are quoted in every rendered statement",
            async () => {
        const ns = "quoting_" + process.hrtime.bigint().toString(16);
        await execute(scratch, "CREATE SCHEMA " + ns);
        try {
            const script = "CREATE TABLE \"we\"\"ird; DROP TABLE x --\" ("
                    + "\"id col\" bigint PRIMARY KEY,"
                    + " \"q\"\"t\" text DEFAULT 'a''b',"
                    + " CONSTRAINT \"ck;1\" CHECK (\"id col\" > 0));"
                    + " CREATE INDEX \"ix \"\"1\"\"\" ON"
                    + " \"we\"\"ird; DROP TABLE x --\" (\"q\"\"t\");"
                    + " CREATE VIEW \"v; w\" AS SELECT \"id col\" FROM"
                    + " \"we\"\"ird; DROP TABLE x --\";"
                    + " CREATE SEQUENCE \"s ;q\";"
                    + " CREATE FUNCTION \"f(\"\"x\"\")\"(a int) RETURNS int"
                    + " LANGUAGE sql AS $$ SELECT a $$";
            const reconciler = SchemaReconciler.builder().dataSource(scratch)
                    .namespace(ns).desiredState(DesiredState.ofScript(script))
                    .dialect(dialect).build();
            assert.equal((await reconciler.reconcile()).outcome,
                    ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler.reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // drift every kind and converge again: DROP / CREATE statements
            // are rendered too
            await execute(scratch, "DROP INDEX " + ns + ".\"ix \"\"1\"\"\"");
            await execute(scratch, "DROP VIEW " + ns + ".\"v; w\"");
            await execute(scratch, "ALTER TABLE " + ns
                    + ".\"we\"\"ird; DROP TABLE x --\""
                    + " DROP CONSTRAINT \"ck;1\"");
            assert.equal((await reconciler.reconcile()).outcome,
                    ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler.reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        } finally {
            await execute(scratch, "DROP SCHEMA " + ns + " CASCADE");
        }
    });
});
