import assert from "node:assert/strict";
import test from "node:test";
import { Decision } from "../src/Decision.ts";
import { DesiredState } from "../src/DesiredState.ts";
import { IllegalArgumentError } from "../src/IllegalArgumentError.ts";
import { PlanningException } from "../src/PlanningException.ts";
import { ReconcileOutcome } from "../src/ReconcileOutcome.ts";
import { ReconcilePolicy } from "../src/ReconcilePolicy.ts";
import { SchemaReconciler } from "../src/SchemaReconciler.ts";
import { ChangeAction } from "../src/model/ChangeAction.ts";
import { ChangeHazard } from "../src/model/ChangeHazard.ts";
import { ChangeSafety } from "../src/model/ChangeSafety.ts";
import { LockLevel } from "../src/model/LockLevel.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { PlannedChange } from "../src/model/PlannedChange.ts";
import { ReconcilePlan } from "../src/model/ReconcilePlan.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/**
 * Unit coverage for the spec 5.5–5.6 and serialisation features on
 * {@link FakeDb}: the prune policy, JSON plans, hazard metadata,
 * concurrent indexes and rename hints.
 */
const NS = "featx";

function fixture() {
    const db = new FakeDb();
    const dialect = new FakeDialect(db);
    return { db, dialect };
}

async function rejected(promise: Promise<unknown>): Promise<unknown> {
    try {
        await promise;
    } catch (e) {
        return e;
    }
    assert.fail("expected rejection");
}

function reconciler(db: FakeDb, dialect: FakeDialect, script: string,
                    policy?: ReconcilePolicy) {
    const b = SchemaReconciler.builder()
            .dataSource(db.dataSource())
            .namespace(NS)
            .desiredState(DesiredState.ofScript(script))
            .dialect(dialect);
    if (policy !== undefined) {
        b.policy(policy);
    }
    return b;
}

// ---- prune (5.5) ------------------------------------------------------

test("pruneOffLeavesUnexpectedObjectsAlone", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE TABLE extra DEF e");
    const report = await reconciler(db, dialect, "CREATE TABLE keep DEF k")
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "extra")));
});

test("pruneDropsAnUnexpectedTableDestructively", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE TABLE extra DEF e");
    const report = await reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY)).build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(!db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "extra")));
    const drop = report.applied.find(c => c.action === ChangeAction.DROP
            && c.target.name === "extra");
    assert.ok(drop !== undefined);
    assert.equal(drop.safety, ChangeSafety.DESTRUCTIVE);
});

test("pruneDropsAnUnexpectedViewAsRebuild", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k;"
            + " CREATE VIEW stray REQUIRES TABLE:keep DEF v");
    const report = await reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)).build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(!db.objectsIn(NS).has(new ObjectId(ObjectKind.VIEW, "stray")));
    const drop = report.applied.find(c => c.action === ChangeAction.DROP
            && c.target.kind === ObjectKind.VIEW);
    assert.ok(drop !== undefined);
    assert.equal(drop.safety, ChangeSafety.REBUILD);
});

test("pruneSuppressesChildDropsCoveredByAnUnexpectedParent", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k;"
            + " CREATE TABLE stray DEF s; CREATE COLUMN stray.c DEF c");
    const report = await reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY)).build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    const drops = report.plan.changes.filter(c => c.action === ChangeAction.DROP);
    assert.equal(drops.length, 1, "the table's drop covers its column");
    assert.equal(drops[0]!.target.kind, ObjectKind.TABLE);
});

test("pruneColumnOnExpectedTableRendersDropColumn", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE COLUMN keep.c DEF c");
    const report = await reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY)).build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(!db.objectsIn(NS).has(new ObjectId(ObjectKind.COLUMN, "c", "keep")));
    const drop = report.applied.find(c => c.action === ChangeAction.DROP);
    assert.ok(drop !== undefined);
    assert.equal(drop.target.kind, ObjectKind.COLUMN);
    assert.equal(drop.safety, ChangeSafety.DESTRUCTIVE);
});


test("pruneDropsOrdinaryDependentsInReverseDependencyOrder", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k;"
            + " CREATE TABLE victim DEF v; CREATE VIEW dependent REQUIRES TABLE:victim DEF d");
    const report = await reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY)).build().reconcile();
    const viewAt = report.plan.changes.findIndex(c => c.action === ChangeAction.DROP
            && c.target.kind === ObjectKind.VIEW && c.target.name === "dependent");
    const tableAt = report.plan.changes.findIndex(c => c.action === ChangeAction.DROP
            && c.target.kind === ObjectKind.TABLE && c.target.name === "victim");
    assert.ok(viewAt >= 0 && tableAt >= 0,
            "dependent and victim must both have standalone drops");
    assert.ok(viewAt < tableAt, "dependent drop must precede prerequisite drop");
});

test("pruneStillFailsOnExternalDependent", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE TABLE victim DEF v");
    db.putObject(NS, FakeDb.externalDependent(
            new ObjectId(ObjectKind.OTHER, "foreign.obj"),
            new ObjectId(ObjectKind.TABLE, "victim")));
    await assert.rejects(() => reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY)).build().reconcile(),
            PlanningException);
});

test("pruneStillFailsOnUnprunableOtherDependent", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE TABLE victim DEF v;"
            + " CREATE OTHER sticky REQUIRES TABLE:victim DEF o");
    await assert.rejects(() => reconciler(db, dialect, "CREATE TABLE keep DEF k",
            ReconcilePolicy.defaults().withPrune(true)
                    .withDestructive(Decision.APPLY)).build().reconcile(),
            PlanningException);
});

// ---- machine-readable plans (JSON) -------------------------------------

test("planJsonRoundTripsAndApplyJsonApplies", async () => {
    const { db, dialect } = fixture();
    const planned = await reconciler(db, dialect, "CREATE TABLE t DEF v1")
            .build().plan();
    const json = planned.toJson();
    const restored = ReconcilePlan.fromJson(json);
    assert.equal(restored.differences.length, planned.differences.length);
    assert.equal(restored.changes.length, planned.changes.length);
    assert.equal(restored.toJson(), json, "serialisation is stable");

    const report = await reconciler(db, dialect, "CREATE TABLE t DEF v1")
            .build().applyJson(json);
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "t")));
});

test("fromJsonRejectsInvalidDocuments", () => {
    for (const bad of [
            "", "{}", "[]", "null", "42",
            JSON.stringify({ schemaVersion: 2 }),
            JSON.stringify({ schemaVersion: 1, targetNamespace: "x" }),
            JSON.stringify({ schemaVersion: 1, targetNamespace: "x",
                    dialectId: "fake", actualFingerprint: "fp",
                    desiredFingerprint: "fp2", differences: {},
                    changes: [] }),
            JSON.stringify({ schemaVersion: 1, targetNamespace: "x",
                    dialectId: "fake", actualFingerprint: "fp",
                    desiredFingerprint: "fp2", differences: [],
                    changes: [{ order: "1" }] })]) {
        assert.throws(() => ReconcilePlan.fromJson(bad), IllegalArgumentError,
                "input: " + bad);
    }
});

test("applyJsonRejectsAStalePlan", async () => {
    const { db, dialect } = fixture();
    const planned = await reconciler(db, dialect, "CREATE TABLE t DEF v1")
            .build().plan();
    db.executeDirect(NS, "CREATE TABLE other DEF o");
    const e = await rejected(reconciler(db, dialect, "CREATE TABLE t DEF v1")
            .build().applyJson(planned.toJson()));
    assert.ok(e instanceof Error);
});

// ---- hazard metadata ---------------------------------------------------

test("hazardDefaultsAreTrivial", () => {
    const c = new PlannedChange(1, new ObjectId(ObjectKind.TABLE, "t"),
            ChangeAction.CREATE, ChangeSafety.ADDITIVE, [], undefined, "r");
    assert.equal(c.nonTransactional, false);
    assert.ok(c.hazard.isTrivial);
    assert.equal(ChangeHazard.NONE.lockLevel, LockLevel.NONE);
});

test("planSqlHeadersCarryNonTrivialHazards", async () => {
    const { db, dialect } = fixture();
    const planned = await reconciler(db, dialect,
            "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
            .concurrentIndexes(true).build().plan();
    const sql = planned.toSql();
    assert.ok(sql.includes("hazard: lock SHARE_UPDATE_EXCLUSIVE"),
            sql);
    assert.ok(sql.includes("non-transactional"), sql);
    // a hazard-aware change surfaces its lock in the header
    const hazard = new PlannedChange(1, new ObjectId(ObjectKind.TABLE, "x"),
            ChangeAction.ALTER, ChangeSafety.REBUILD, ["ALTER TABLE x"], undefined,
            "r", false, new ChangeHazard(LockLevel.ACCESS_EXCLUSIVE,
                    true, false, ["heavy"]));
    assert.ok(hazard.hazard.rewritesTable);
    assert.equal(hazard.hazard.lockLevel, LockLevel.ACCESS_EXCLUSIVE);
});

// ---- concurrent indexes (5.6) -------------------------------------------

test("concurrentIndexesRunsTheIndexCreateNonTransactionally", async () => {
    const { db, dialect } = fixture();
    const report = await reconciler(db, dialect,
            "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
            .concurrentIndexes(true).build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    const index = report.applied.find(c => c.target.kind === ObjectKind.INDEX);
    assert.ok(index !== undefined);
    assert.equal(index.nonTransactional, true);
    assert.equal(index.hazard.lockLevel, LockLevel.SHARE_UPDATE_EXCLUSIVE);
    // a second run stays converged
    const again = await reconciler(db, dialect,
            "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
            .concurrentIndexes(true).build().reconcile();
    assert.equal(again.outcome, ReconcileOutcome.UP_TO_DATE);
});

test("concurrentIndexesOffKeepsIndexCreatesTransactional", async () => {
    const { db, dialect } = fixture();
    const report = await reconciler(db, dialect,
            "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1").build().reconcile();
    const index = report.applied.find(c => c.target.kind === ObjectKind.INDEX);
    assert.ok(index !== undefined);
    assert.equal(index.nonTransactional, false);
});

// ---- rename hints (5.5.1) -------------------------------------------------

test("builderRenameTurnsDropCreateIntoARename", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE old DEF v1");
    const report = await reconciler(db, dialect, "CREATE TABLE new DEF v1")
            .rename(new ObjectId(ObjectKind.TABLE, "old"),
                    new ObjectId(ObjectKind.TABLE, "new"))
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "new")));
    assert.ok(!db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "old")));
    const rename = report.applied.find(c => c.target.name === "new");
    assert.ok(rename !== undefined);
    assert.equal(rename.action, ChangeAction.ALTER);
    assert.ok(rename.statements[0]!.startsWith("RENAME TABLE old TO new"));
    // no drop+create pair survived
    assert.ok(report.applied.every(c => c.action !== ChangeAction.DROP));
});

test("scriptRenameDirectiveIsPickedUp", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE old DEF v1");
    const script = "-- @reconciler rename TABLE old -> new\n"
            + "CREATE TABLE new DEF v1";
    const report = await reconciler(db, dialect, script).build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "new")));
    assert.ok(!db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "old")));
});

test("renameWithDriftAppliesTheFollowUpChange", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE keep DEF k;"
            + " CREATE VIEW old REQUIRES TABLE:keep DEF v1");
    const report = await reconciler(db, dialect,
            "CREATE TABLE keep DEF k; CREATE VIEW new REQUIRES TABLE:keep DEF v2")
            .rename(new ObjectId(ObjectKind.VIEW, "old"),
                    new ObjectId(ObjectKind.VIEW, "new"))
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    const renamed = db.objectsIn(NS).get(new ObjectId(ObjectKind.VIEW, "new"));
    assert.equal(renamed!.definition.canonical, "v2");
    // the rename ran before the recreate pair
    const rename = report.applied.find(c => c.action === ChangeAction.ALTER)!;
    assert.ok(rename.statements[0]!.startsWith("RENAME VIEW old TO new"));
});

test("anAlreadyAppliedRenameHintIsAnIdempotentNoOp", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE new DEF v1");
    const report = await reconciler(db, dialect, "CREATE TABLE new DEF v1")
            .rename(new ObjectId(ObjectKind.TABLE, "old"),
                    new ObjectId(ObjectKind.TABLE, "new"))
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
});

test("renameWithBothSidesPresentIsAPlanningError", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE old DEF v1; CREATE TABLE new DEF v2");
    const e = await rejected(reconciler(db, dialect, "CREATE TABLE new DEF v2")
            .rename(new ObjectId(ObjectKind.TABLE, "old"),
                    new ObjectId(ObjectKind.TABLE, "new"))
            .build().reconcile());
    assert.ok(e instanceof PlanningException, String(e));
});


test("planJsonStrictFields", () => {
    const valid = JSON.stringify({ schemaVersion: 1, targetNamespace: "x",
            dialectId: "fake", actualFingerprint: "fp", desiredFingerprint: "fp2",
            differences: [], changes: [{ order: 1,
                    target: { kind: "TABLE", name: "t", parent: null },
                    action: "CREATE", safety: "ADDITIVE", statements: [],
                    expected: "x", reason: "r", nonTransactional: false,
                    hazard: { lockLevel: "NONE", rewritesTable: false,
                            validatesRows: false, notes: [] } }] });
    for (const mutate of [
            (o: any) => { o.changes[0].expected = 42; },
            (o: any) => { delete o.changes[0].hazard; },
            (o: any) => { o.changes[0].order = 2; },
            (o: any) => { o.differences = [{ id: { kind: "TABLE", name: "t", parent: null },
                    type: "MISSING", expected: 42, actual: null }]; }]) {
        const doc = JSON.parse(valid);
        mutate(doc);
        assert.throws(() => ReconcilePlan.fromJson(JSON.stringify(doc)),
                IllegalArgumentError);
    }
});
