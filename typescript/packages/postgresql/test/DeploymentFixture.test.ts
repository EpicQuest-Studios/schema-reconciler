import assert from "node:assert/strict";
import { describe, it, beforeEach, afterEach } from "node:test";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DesiredState, ObjectId, ObjectKind, ReconcileOutcome,
        SchemaReconciler } from "@schema-reconciler/core";
import type { SchemaSnapshot } from "@schema-reconciler/core";
import { PostgreSqlDialect } from "../src/PostgreSqlDialect.ts";
import { PgTestDataSource, available, ensureUuidv7, execute }
        from "./PgTestDatabase.ts";

/**
 * Spec A.7: the captured deployment script `fixtures/deployment/schema.sql`
 * is migrated in memory (literal `svc.` qualifier to `${namespace}.`,
 * deployment-only statements removed), reconciled against a real
 * namespace, and asserted to converge, plan deterministically, and dump
 * byte-identically.
 */

const fixtureFile = path.resolve(path.dirname(fileURLToPath(import.meta.url)),
        "../../../../fixtures/deployment/schema.sql");

const dbUp = await available();
const ds = new PgTestDataSource();
const dialect = new PostgreSqlDialect();

let sequence = 0;
let namespace = "";

/** The A.7 transform: literal qualifier → placeholder, deployment
 *  statements removed. */
function migrateFixture(): string {
    const raw = readFileSync(fixtureFile, "utf8");
    // step 1: literal qualifier → placeholder
    // step 2: remove deployment-only statements (library owns locking +
    // namespace)
    return raw.replaceAll("svc.", "${namespace}.")
            .replaceAll(/^\s*SELECT\s+pg_advisory_lock[^;]*;\s*/gm, "")
            .replaceAll(/^\s*CREATE\s+SCHEMA[^;]*;\s*/gm, "");
}

function reconciler(script: string) {
    return SchemaReconciler.builder().dataSource(ds).namespace(namespace)
            .desiredState(DesiredState.ofScript(script)).dialect(dialect)
            .build();
}

async function snapshot(): Promise<SchemaSnapshot> {
    const c = await ds.connect();
    try {
        await c.setAutoCommit(false);
        try {
            return await dialect.readSnapshot(c, namespace);
        } finally {
            await c.rollback();
        }
    } finally {
        await c.close();
    }
}

describe("DeploymentFixtureTest", {
    skip: !dbUp ? "no PostgreSQL test database" : false,
}, () => {
    beforeEach(async () => {
        namespace = "svc_e2e_" + (++sequence);
        await execute(ds, "CREATE SCHEMA " + dialect.quoteIdentifier(namespace));
    });

    afterEach(async () => {
        await execute(ds, "DROP SCHEMA " + dialect.quoteIdentifier(namespace)
                + " CASCADE");
    });

    it("A.7 migrated deployment fixture reconciles and is idempotent",
            async () => {
        await ensureUuidv7(ds);
        const script = migrateFixture();

        const first = await reconciler(script).reconcile();
        assert.equal(first.outcome, ReconcileOutcome.RECONCILED);
        assert.equal(first.unsupported.length, 0,
                "unexpected unsupported diffs: "
                + JSON.stringify(first.unsupported));

        const objects = await snapshot();
        assert.ok(objects.objects.has(new ObjectId(ObjectKind.TABLE, "plans")));
        assert.ok(objects.objects.has(
                new ObjectId(ObjectKind.TABLE, "workflow_runs")));
        assert.ok(objects.objects.has(
                new ObjectId(ObjectKind.SEQUENCE, "fencing_seq")));
        assert.ok([...objects.objects.keys()].some(
                id => id.kind === ObjectKind.TRIGGER));
        const views = [...objects.objects.keys()].filter(
                id => id.kind === ObjectKind.VIEW).length;
        assert.ok(views >= 10, "expected the fixture views, found " + views);
        assert.ok([...objects.objects.keys()].some(
                id => id.kind === ObjectKind.ROUTINE));

        assert.equal((await reconciler(script).reconcile()).outcome,
                ReconcileOutcome.UP_TO_DATE);
    });

    it("A.7 migrated fixture plans deterministically", async () => {
        await ensureUuidv7(ds);
        const script = migrateFixture();
        const one = (await reconciler(script).plan()).toSql();
        const two = (await reconciler(script).plan()).toSql();
        assert.equal(one, two);
    });

    it("3.1 dump() round-trips a reconciled namespace", async () => {
        await ensureUuidv7(ds);
        assert.equal((await reconciler(migrateFixture()).reconcile()).outcome,
                ReconcileOutcome.RECONCILED);
        const dumper = SchemaReconciler.builder().dataSource(ds)
                .namespace(namespace).dialect(dialect).build();
        const dump = await dumper.dump();
        // the emitted script is a desired-state script: feeding it back on
        // the same namespace must be a no-op
        const self = await reconciler(dump).reconcile();
        assert.equal(self.outcome, ReconcileOutcome.UP_TO_DATE);
        assert.equal(self.plan.changes.length, 0);
        // and the second dump is byte-identical
        assert.equal(await dumper.dump(), dump);
    });
});
