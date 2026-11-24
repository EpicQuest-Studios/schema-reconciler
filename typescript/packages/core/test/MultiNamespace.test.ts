import assert from "node:assert/strict";
import test from "node:test";
import { ApplyException } from "../src/ApplyException.ts";
import { ConfigurationException } from "../src/ConfigurationException.ts";
import { DesiredState } from "../src/DesiredState.ts";
import { MultiNamespaceReconciler } from "../src/MultiNamespaceReconciler.ts";
import { ReconcileOutcome } from "../src/ReconcileOutcome.ts";
import { ReconcilePolicy } from "../src/ReconcilePolicy.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** Spec 3.7: {@link MultiNamespaceReconciler} over several namespaces. */

const A = "multi_a";
const B = "multi_b";

function builder(db: FakeDb) {
    return MultiNamespaceReconciler.builder()
            .dataSource(db.dataSource())
            .dialect(new FakeDialect(db));
}

test("reconcileRunsNamespacesInOrderAndAggregatesOutcome", async () => {
    const db = new FakeDb();
    const multi = builder(db)
            .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"))
            .namespace(B, DesiredState.ofScript("CREATE TABLE t DEF tb"))
            .build();
    const report = await multi.reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    // insertion order and one report per namespace
    assert.deepEqual([...report.reports.keys()], [A, B]);
    assert.equal(report.reports.get(A)!.outcome, ReconcileOutcome.RECONCILED);
    assert.equal(report.reports.get(B)!.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(!report.elapsed.isNegative);
    assert.ok(db.objectsIn(A).has(new ObjectId(ObjectKind.TABLE, "t")));
    assert.ok(db.objectsIn(B).has(new ObjectId(ObjectKind.TABLE, "t")));

    // converged → aggregate UP_TO_DATE
    const second = await multi.reconcile();
    assert.equal(second.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.deepEqual([...second.reports.keys()], [A, B]);
});

test("aggregateIsReportedOnlyWhenNoNamespaceReconciled", async () => {
    const db = new FakeDb();
    const multi = builder(db)
            .policy(ReconcilePolicy.reportOnly())
            .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"))
            .namespace(B, DesiredState.ofScript("CREATE TABLE t DEF tb"))
            .build();
    const report = await multi.reconcile();
    assert.equal(report.outcome, ReconcileOutcome.REPORTED_ONLY);
    assert.equal(db.objectsIn(A).size, 0);
    assert.equal(db.objectsIn(B).size, 0);
});

test("reconciledBeatsReportedOnlyInTheAggregate", async () => {
    const db = new FakeDb();
    const multi = builder(db)
            .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"),
                    b => b.policy(ReconcilePolicy.reportOnly()))
            .namespace(B, DesiredState.ofScript("CREATE TABLE t DEF tb"))
            .build();
    const report = await multi.reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.equal(report.reports.get(A)!.outcome, ReconcileOutcome.REPORTED_ONLY);
    assert.equal(report.reports.get(B)!.outcome, ReconcileOutcome.RECONCILED);
});

test("planReturnsAnOrderedPlanPerNamespace", async () => {
    const db = new FakeDb();
    db.executeDirect(A, "CREATE TABLE t DEF ta");
    const plans = await builder(db)
            .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"))
            .namespace(B, DesiredState.ofScript("CREATE TABLE u DEF ub"))
            .build().plan();
    assert.deepEqual([...plans.keys()], [A, B]);
    assert.equal(plans.get(A)!.changes.length, 0);
    assert.equal(plans.get(B)!.changes.length, 1);
});

test("dumpNeedsNoDesiredState", async () => {
    const db = new FakeDb();
    db.executeDirect(A, "CREATE TABLE t DEF ta");
    db.executeDirect(B, "CREATE VIEW v REQUIRES TABLE:w DEF vb;"
            + " CREATE TABLE w DEF wb");
    const dumps = await builder(db).namespace(A).namespace(B).build().dump();
    assert.deepEqual([...dumps.keys()], [A, B]);
    assert.ok(dumps.get(A)!.includes("CREATE TABLE t DEF ta;"));
    assert.ok(dumps.get(B)!.includes("CREATE VIEW v REQUIRES TABLE:w DEF vb;"));
    assert.ok(dumps.get(B)!.includes("CREATE TABLE w DEF wb;"));
});

test("buildValidatesWithoutOpeningConnections", () => {
    const db = new FakeDb();
    // no dataSource
    assert.throws(() => MultiNamespaceReconciler.builder()
                    .dialect(new FakeDialect(db))
                    .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF x"))
                    .build(),
            ConfigurationException);
    // no namespace
    assert.throws(() => builder(db).build(), ConfigurationException);
    // duplicate names
    assert.throws(() => builder(db).namespace(A).namespace(A).build(),
            ConfigurationException);
    // empty name
    assert.throws(() => builder(db).namespace("").build(),
            ConfigurationException);
});

test("perNamespaceCustomizerAdjustsOnlyThatNamespace", async () => {
    const db = new FakeDb();
    const script = "CREATE TABLE t DEF ta";
    const multi = builder(db)
            .namespace(A, DesiredState.ofScript(script),
                    b => b.listener({
                        applied() {
                            throw new Error("listener fired");
                        },
                    }))
            .namespace(B, DesiredState.ofScript(script))
            .build();
    // A's listener aborts A's run — B never reconciles
    await assert.rejects(() => multi.reconcile(), ApplyException);
    assert.equal(db.objectsIn(B).size, 0);
});
