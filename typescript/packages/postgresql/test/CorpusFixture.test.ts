import assert from "node:assert/strict";
import { describe, it, beforeEach, afterEach } from "node:test";
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Decision, DesiredState, ReconcileOutcome, ReconcilePolicy,
        SchemaReconciler } from "@schema-reconciler/core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available, ensureUuidv7, execute, serverMajor }
        from "./PgTestDatabase.ts";

/**
 * End-to-end coverage over the obfuscated real-world schema corpus in
 * `java/fixtures/corpus/corpus-*.sql`. Each file is a captured deployment
 * script: the A.7 migration steps are applied in memory (literal schema
 * qualifier to `${namespace}`; deployment-only statements removed), then
 * the script is reconciled against a real namespace and asserted to
 * converge and stay idempotent.
 */

const ROLE_REF = /\bTO (w\d{3})\b/g;
const EXTENSION = /CREATE EXTENSION IF NOT EXISTS (\w+)/g;

const corpusDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)),
        "../../../../java/fixtures/corpus");
const corpusFiles = readdirSync(corpusDir)
        .filter(f => /^corpus-\d+\.sql$/.test(f)).sort();

const dbUp = await available();
const ds = new PgTestDataSource();
const dialect = new PostgreSqlDialect();

let sequence = 0;
let namespace = "";

/** The A.7 transform: literal qualifier to placeholder, deployment
 *  statements removed. */
function migrate(raw: string): string {
    return raw.replaceAll(/\bfixture\b/g, "${namespace}")
            .replaceAll(/^\s*SELECT\s+pg_advisory_lock[^;]*;\s*/gm, "")
            .replaceAll(/^\s*CREATE\s+SCHEMA[^;]*;\s*/gm, "")
            .replaceAll(/^\s*CREATE\s+EXTENSION[^;]*;\s*/gm, "");
}

/** v15-only syntax downgraded on pre-15 servers — same coverage trade-off
 *  as the uuidv7 stand-in. */
function preFifteenCompat(script: string): string {
    return script.replaceAll(/\s+NULLS\s+(?:NOT\s+)?DISTINCT/gi, "")
            .replaceAll(/security_invoker\s*=\s*\w+\s*,\s*/gi, "")
            .replaceAll(/,\s*security_invoker\s*=\s*\w+/gi, "")
            .replaceAll(/WITH\s*\(\s*security_invoker\s*=\s*\w+\s*\)/gi, "")
            .replaceAll(/WITH\s*\(\s*\)/gi, "");
}

async function extensionAvailable(name: string): Promise<boolean> {
    const c = await ds.connect();
    try {
        const rows = await c.query(
                "SELECT 1 FROM pg_available_extensions WHERE name = '"
                + name.replaceAll("'", "''") + "'");
        return rows.length > 0;
    } finally {
        await c.close();
    }
}

/** Creates the roles referenced by GRANT/policy statements, and the
 *  extensions the script declares (relocatable ones go to pg_catalog so
 *  the scratch search_path still resolves them). */
async function prepareEnvironment(raw: string): Promise<string | undefined> {
    if (raw.includes("uuidv7")) {
        await ensureUuidv7(ds);
    }
    const roles = new Set<string>();
    for (const m of raw.matchAll(ROLE_REF)) {
        roles.add(m[1]!);
    }
    for (const role of [...roles].sort()) {
        await execute(ds, "DROP ROLE IF EXISTS " + dialect.quoteIdentifier(role));
        await execute(ds, "CREATE ROLE " + dialect.quoteIdentifier(role));
    }
    for (const m of raw.matchAll(EXTENSION)) {
        const ext = m[1]!;
        if (!(await extensionAvailable(ext))) {
            return "extension " + ext + " not available on this server";
        }
        await execute(ds, "CREATE EXTENSION IF NOT EXISTS "
                + dialect.quoteIdentifier(ext) + " SCHEMA pg_catalog");
    }
    return undefined;
}

function reconciler(script: string, unsupportedReport: boolean) {
    const builder = SchemaReconciler.builder().dataSource(ds)
            .namespace(namespace).desiredState(DesiredState.ofScript(script))
            .dialect(dialect);
    if (unsupportedReport) {
        builder.policy(ReconcilePolicy.defaults()
                .withUnsupported(Decision.REPORT));
    }
    return builder.build();
}

async function migratedScript(file: string): Promise<string> {
    const raw = readFileSync(path.join(corpusDir, file), "utf8");
    let script = migrate(raw);
    if (await serverMajor(ds) < 15) {
        script = preFifteenCompat(script);
    }
    return script;
}

describe("CorpusFixtureTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    beforeEach(async () => {
        namespace = "corpus_" + (++sequence);
        await execute(ds, "CREATE SCHEMA " + dialect.quoteIdentifier(namespace));
    });

    afterEach(async () => {
        await execute(ds, "DROP SCHEMA " + dialect.quoteIdentifier(namespace)
                + " CASCADE");
    });

    for (const file of corpusFiles) {
        it(file + ": corpus schema reconciles and is idempotent", async (t) => {
            const raw = readFileSync(path.join(corpusDir, file), "utf8");
            const missing = await prepareEnvironment(raw);
            if (missing !== undefined) {
                t.skip(missing);
                return;
            }
            const script = await migratedScript(file);
            // a corpus file whose GRANTs land on unmanaged targets still has
            // privileges:* acl rows — unsupported by design (5.4): those
            // files run under unsupported=REPORT and the unsupported set is
            // stable across runs.
            const expectUnsupported = raw.includes("GRANT");

            const first = await reconciler(script, expectUnsupported)
                    .reconcile();
            assert.equal(first.outcome, ReconcileOutcome.RECONCILED,
                    "reported: " + JSON.stringify(first.reported)
                    + " unsupported: " + JSON.stringify(first.unsupported));

            const second = await reconciler(script, expectUnsupported)
                    .reconcile();
            assert.equal(second.outcome, ReconcileOutcome.UP_TO_DATE);
            if (expectUnsupported) {
                assert.deepEqual(
                        second.unsupported.map(d => d.id.name).sort(),
                        first.unsupported.map(d => d.id.name).sort(),
                        "unsupported objects must report identically on"
                        + " every run");
            } else {
                assert.equal(second.unsupported.length, 0,
                        "unsupported diffs appeared on the idempotent re-run: "
                        + JSON.stringify(second.unsupported));
            }
        });

        it(file + ": 3.1 dump() round-trips the reconciled corpus schema",
                async (t) => {
            const raw = readFileSync(path.join(corpusDir, file), "utf8");
            const missing = await prepareEnvironment(raw);
            if (missing !== undefined) {
                t.skip(missing);
                return;
            }
            const script = await migratedScript(file);
            const expectUnsupported = raw.includes("GRANT");
            assert.equal((await reconciler(script, expectUnsupported)
                    .reconcile()).outcome, ReconcileOutcome.RECONCILED);

            const dumper = SchemaReconciler.builder().dataSource(ds)
                    .namespace(namespace).dialect(dialect).build();
            const dump = await dumper.dump();
            // the emitted script is a desired-state script — feeding it back
            // on the same namespace must be a no-op
            const self = await reconciler(dump, expectUnsupported).reconcile();
            assert.equal(self.outcome, ReconcileOutcome.UP_TO_DATE,
                    "dump replay reported changes: "
                    + JSON.stringify(self.plan.changes));
            assert.equal(await dumper.dump(), dump,
                    "second dump is not byte-identical");
        });
    }
});
