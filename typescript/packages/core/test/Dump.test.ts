import assert from "node:assert/strict";
import test from "node:test";
import { ConfigurationException } from "../src/ConfigurationException.ts";
import { DesiredState } from "../src/DesiredState.ts";
import { ReconcileOutcome } from "../src/ReconcileOutcome.ts";
import { SchemaReconciler } from "../src/SchemaReconciler.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { ReconcilePlan } from "../src/model/ReconcilePlan.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** Spec 3.1 {@code dump()}: the namespace-neutral bootstrap path. */

const NS = "dumpns";

function fixture(): FakeDb {
    const db = new FakeDb();
    db.executeDirect(NS,
            "CREATE VIEW v REQUIRES TABLE:t DEF vw;"
            + " CREATE TABLE t DEF tv;"
            + " CREATE COLUMN t.id DEF int");
    return db;
}

function dumper(db: FakeDb): SchemaReconciler {
    return SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .dialect(new FakeDialect(db))
            .build();
}

test("dumpNeedsNoDesiredState", async () => {
    const db = fixture();
    const dump = await dumper(db).dump();
    const lines = dump.split("\n");
    assert.equal(lines[0], "-- schema-reconciler dump");
    assert.equal(lines[1], "-- dialect: fake");
    assert.equal(lines[2], '-- namespace: "' + NS + '"');
    assert.ok(lines[3]!.startsWith("-- fingerprint: "));
    // the table precedes its dependent view (dependsOn order)
    assert.ok(dump.indexOf("CREATE TABLE t") < dump.indexOf("CREATE VIEW v"));
    assert.ok(dump.includes("CREATE TABLE t DEF tv;"));
    assert.ok(dump.includes("CREATE VIEW v REQUIRES TABLE:t DEF vw;"));
    assert.ok(dump.includes("CREATE COLUMN t.id DEF int;"));
});

test("planReconcileApplyStillRequireDesiredState", async () => {
    const db = fixture();
    const reconciler = dumper(db);
    for (const call of [
            () => reconciler.plan(), () => reconciler.planTimed(),
            () => reconciler.reconcile(),
            () => reconciler.apply(new ReconcilePlan(
                    NS, "fake", "a", "b", [], []))]) {
        let e: unknown;
        try {
            await call();
        } catch (x) {
            e = x;
        }
        assert.ok(e instanceof ConfigurationException, "expected ConfigurationException: " + e);
        assert.match((e as Error).message, /desiredState/);
    }
});

test("dumpRoundTripsAndIsDeterministic", async () => {
    const db = fixture();
    const dump = await dumper(db).dump();
    const report = await SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript(dump))
            .dialect(new FakeDialect(db))
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.equal(report.plan.changes.length, 0);
    assert.equal(await dumper(db).dump(), dump);
});

test("unrenderableObjectsEmitCommentStubs", async () => {
    const db = fixture();
    db.executeDirect(NS, "CREATE OTHER privileges:t DEF grants");
    db.putObject(NS, FakeDb.externalDependent(new ObjectId(ObjectKind.VIEW, "x")));
    const dump = await dumper(db).dump();
    assert.ok(dump.includes("-- unsupported: OTHER privileges:t"), dump);
    assert.ok(dump.includes("-- unsupported: VIEW x"), dump);
    // stubs never emit a statement that a replay could trip over
    assert.equal(dump.includes("CREATE OTHER"), false);
});
