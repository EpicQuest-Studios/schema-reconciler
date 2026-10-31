import assert from "node:assert/strict";
import test from "node:test";
import { ApplyException } from "../src/ApplyException.ts";
import { Decision } from "../src/Decision.ts";
import { DesiredState } from "../src/DesiredState.ts";
import { DesiredStateException } from "../src/DesiredStateException.ts";
import { Duration } from "../src/Duration.ts";
import { LockTimeoutException } from "../src/LockTimeoutException.ts";
import { PlanningException } from "../src/PlanningException.ts";
import { PolicyViolationException } from "../src/PolicyViolationException.ts";
import { ReconcileOutcome } from "../src/ReconcileOutcome.ts";
import { ReconcilePolicy } from "../src/ReconcilePolicy.ts";
import { SchemaReconciler } from "../src/SchemaReconciler.ts";
import { SchemaReconcilerException } from "../src/SchemaReconcilerException.ts";
import { StalePlanException } from "../src/StalePlanException.ts";
import { IllegalArgumentError } from "../src/IllegalArgumentError.ts";
import { ChangeAction } from "../src/model/ChangeAction.ts";
import { DifferenceType } from "../src/model/DifferenceType.ts";
import { Definition } from "../src/model/Definition.ts";
import { Dialect } from "../src/spi/Dialect.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { SchemaObject } from "../src/model/SchemaObject.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/**
 * End-to-end engine tests on {@link FakeDb}: the whole spec 5.1 sequence —
 * lock, snapshot, scratch desired state, diff, plan, policy, apply, verify —
 * exercised without a real database.
 */
const NS = "corex";

/** A fresh {@link FakeDb}+{@link FakeDialect} per test — JUnit's per-test instance fields. */
function fixture() {
    const db = new FakeDb();
    const dialect = new FakeDialect(db);
    return { db, dialect };
}

/** Awaits a rejecting promise and returns its error (assert.rejects returns void). */
async function rejected(promise: Promise<unknown>): Promise<unknown> {
    try {
        await promise;
    } catch (e) {
        return e;
    }
    assert.fail("expected rejection");
}

function reconciler(db: FakeDb, dialect: FakeDialect, script: string,
                    policy?: ReturnType<typeof ReconcilePolicy.defaults>) {
    const b = SchemaReconciler.builder()
            .dataSource(db.dataSource())
            .namespace(NS)
            .desiredState(DesiredState.ofScript(script))
            .dialect(dialect);
    if (policy !== undefined) {
        b.policy(policy);
    }
    return b.build();
}

test("emptyTargetCreatesEverything", async () => {
    const { db, dialect } = fixture();
    const report = await reconciler(db, dialect, 
            "CREATE TABLE users DEF users_v1;"
            + " CREATE COLUMN users.id DEF id_v1;"
            + " CREATE VIEW active REQUIRES TABLE:users DEF active_v1").reconcile();

    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "users")));
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.VIEW, "active")));
    // second run converges
    const again = await reconciler(db, dialect, 
            "CREATE TABLE users DEF users_v1;"
            + " CREATE COLUMN users.id DEF id_v1;"
            + " CREATE VIEW active REQUIRES TABLE:users DEF active_v1").reconcile();
    assert.equal(again.outcome, ReconcileOutcome.UP_TO_DATE);
});

test("filterHidesExcludedObjectsAndTheirChildren", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS,
            "CREATE TABLE keep DEF keep_v1; CREATE TABLE skip DEF skip_v1;"
                    + " CREATE COLUMN skip.c DEF c_v1");
    const script =
            "CREATE TABLE keep DEF keep_v1; CREATE TABLE skip DEF skip_v2;"
                    + " CREATE COLUMN skip.c DEF c_v1";

    const report = await SchemaReconciler.builder()
            .dataSource(db.dataSource())
            .namespace(NS)
            .desiredState(DesiredState.ofScript(script))
            .dialect(dialect)
            .filter(id => !(id.kind === ObjectKind.TABLE && id.name === "skip"))
            .build().reconcile();

    assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.equal(report.plan.changes.length, 0,
            "the excluded subtree is invisible");
    assert.equal(report.unexpected.length, 0);
    assert.equal(report.unsupported.length, 0);
    // the excluded objects still exist, untouched
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "skip")));
});

test("filterSuppressesUnsupportedObjects", async () => {
    const { db, dialect } = fixture();
    const report = await SchemaReconciler.builder()
            .dataSource(db.dataSource())
            .namespace(NS)
            .desiredState(DesiredState.ofScript(
                    "CREATE TABLE t DEF t_v1; CREATE OTHER skip:note DEF n"))
            .dialect(dialect)
            .filter(id => id.kind !== ObjectKind.OTHER)
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.equal(report.unsupported.length, 0);
});

test("matchingTargetRunsNoStatements", async () => {
    const { db, dialect } = fixture();
    const script = "CREATE TABLE users DEF users_v1; CREATE COLUMN users.id DEF id_v1";
    db.executeDirect(NS, script);

    const report = await reconciler(db, dialect, script).reconcile();

    assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.equal(report.plan.changes.length, 0);
    assert.equal(db.statementsOn(NS).length, 0, "no statements should run on the target");
    assert.equal(db.lockHeld(NS), false, "lock must be released");
});

test("missingTableCoversItsColumns", async () => {
    const { db, dialect } = fixture();
    const report = await reconciler(db, dialect, 
            "CREATE TABLE users DEF users_v1;"
            + " CREATE COLUMN users.id DEF id_v1;"
            + " CREATE COLUMN users.name DEF name_v1").reconcile();

    assert.equal(report.plan.changes.length, 1, "the table create covers its columns");
    assert.ok(report.plan.changes[0]!.target.equals(new ObjectId(ObjectKind.TABLE, "users")));
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.COLUMN, "id", "users")));
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.COLUMN, "name", "users")));
});

test("driftedViewIsRecreatedInPlace", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_old");
    const script = "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_new";

    const report = await reconciler(db, dialect, script).reconcile();

    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    const v = new ObjectId(ObjectKind.VIEW, "v");
    const viewChanges = report.plan.changes.filter(c => c.target.equals(v));
    assert.deepEqual(viewChanges.map(c => c.action),
            [ChangeAction.DROP, ChangeAction.CREATE]);
    assert.equal(db.objectsIn(NS).get(v)!.definition.canonical, "v_new");
});

test("dependentRebuildsAroundDroppedTable", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS,
            "CREATE TABLE t DEF t_old; CREATE VIEW v REQUIRES TABLE:t DEF v_v1");
    const script = "CREATE TABLE t DEF t_new; CREATE VIEW v REQUIRES TABLE:t DEF v_v1";
    const policy = ReconcilePolicy.defaults().withDestructive(Decision.APPLY);

    const report = await reconciler(db, dialect, script, policy).reconcile();

    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    const changes = report.plan.changes;
    // drop v (dependent) before t; create t before v
    const drops = changes.filter(c => c.action === ChangeAction.DROP);
    const creates = changes.filter(c => c.action === ChangeAction.CREATE);
    assert.deepEqual(drops.map(c => c.target.toString()),
            ["VIEW v", "TABLE t"]);
    assert.deepEqual(creates.map(c => c.target.toString()),
            ["TABLE t", "VIEW v"]);
    assert.equal(db.objectsIn(NS).get(new ObjectId(ObjectKind.VIEW, "v"))!
            .definition.canonical, "v_v1");
});

test("unexpectedObjectsAreReportedNotDropped", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_v1; CREATE TABLE extra DEF e_v1");

    const report = await reconciler(db, dialect, "CREATE TABLE t DEF t_v1").reconcile();

    assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.equal(report.unexpected.length, 1);
    assert.ok(report.unexpected[0]!.id.equals(new ObjectId(ObjectKind.TABLE, "extra")));
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "extra")));
    assert.ok(db.statementsOn(NS).every(s => !s.sql.includes("extra")));
});

test("destructiveChangeFailsByDefaultPolicy", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_old");
    await assert.rejects(() => reconciler(db, dialect, "CREATE TABLE t DEF t_new").reconcile(),
            PolicyViolationException);
});

test("reportOnlyChangesNothing", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_old");
    const report = await reconciler(db, dialect, "CREATE TABLE t DEF t_new; CREATE TABLE u DEF u_v1",
            ReconcilePolicy.reportOnly()).reconcile();

    assert.equal(report.outcome, ReconcileOutcome.REPORTED_ONLY);
    assert.equal(report.applied.length, 0);
    assert.ok(report.reported.length > 0);
    assert.equal(db.statementsOn(NS).length, 0);
    assert.equal(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "u")), false);
});

test("dependentsOfReportedChangesAreDemoted", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_old");
    const script =
            "CREATE TABLE t DEF t_v1;"
            + " CREATE VIEW v REQUIRES TABLE:t DEF v_new;"
            + " CREATE VIEW w REQUIRES VIEW:v DEF w_v1";
    const policy = ReconcilePolicy.defaults().withRebuild(Decision.REPORT);

    const report = await reconciler(db, dialect, script, policy).reconcile();

    assert.equal(report.outcome, ReconcileOutcome.REPORTED_ONLY);
    const w = new ObjectId(ObjectKind.VIEW, "w");
    assert.ok(report.reported.some(c => c.target.equals(w)),
            "w must be demoted to REPORT because v is not applied");
    assert.equal(db.objectsIn(NS).has(w), false);
});

const NARROW_KINDS = new Set([
    ObjectKind.TABLE, ObjectKind.COLUMN, ObjectKind.INDEX, ObjectKind.VIEW,
    ObjectKind.SEQUENCE, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
    ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.TRIGGER]);

test("unsupportedKindIsSeparatePolicyDecision", async () => {
    const { db, dialect } = fixture();
    const narrow = new FakeDialect(db, NARROW_KINDS);
    const script = "CREATE ROUTINE f DEF f_v1";
    const report = ReconcilePolicy.defaults().withUnsupported(Decision.REPORT);

    const result = await SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript(script))
            .dialect(narrow).policy(report).build().reconcile();

    // UNSUPPORTED differences under REPORT do not prevent UP_TO_DATE (3.5)
    assert.equal(result.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.equal(result.unsupported.length, 1);
    assert.equal(result.unsupported[0]!.type, DifferenceType.UNSUPPORTED);
    assert.equal(db.statementsOn(NS).length, 0);
    // and unsupported=FAIL rejects the plan
    await assert.rejects(() => SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript(script))
            .dialect(narrow).policy(ReconcilePolicy.defaults()).build().reconcile(),
            PolicyViolationException);
});

test("unsupportedDependencyDemotesDependents", async () => {
    const { db, dialect } = fixture();
    const narrow = new FakeDialect(db, new Set([ObjectKind.TABLE, ObjectKind.VIEW]));
    const script = "CREATE ROUTINE f DEF f_v1; CREATE VIEW v REQUIRES ROUTINE:f DEF v_v1";
    const policy = ReconcilePolicy.defaults().withUnsupported(Decision.REPORT);

    const report = await SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript(script))
            .dialect(narrow).policy(policy).build().reconcile();

    const v = new ObjectId(ObjectKind.VIEW, "v");
    assert.ok(report.reported.some(c => c.target.equals(v)),
            "view depending on an unsupported routine must be demoted");
    assert.equal(db.objectsIn(NS).has(v), false);
});

test("externalDependentBlocksPlanning", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_old");
    db.putObject(NS, FakeDb.externalDependent(
            new ObjectId(ObjectKind.OTHER, "foreign.obj"),
            new ObjectId(ObjectKind.TABLE, "t")));

    await assert.rejects(() => reconciler(db, dialect, "CREATE TABLE t DEF t_new",
            ReconcilePolicy.defaults().withDestructive(Decision.APPLY)).reconcile(),
            PlanningException);
});

test("unexpectedDependentBlocksPlanning", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE TABLE t DEF t_old; CREATE VIEW z REQUIRES TABLE:t DEF z_v1");

    await assert.rejects(() => reconciler(db, dialect, "CREATE TABLE t DEF t_new",
            ReconcilePolicy.defaults().withDestructive(Decision.APPLY)).reconcile(),
            PlanningException);
});

test("dependencyCycleIsPlanningError", async () => {
    const { db, dialect } = fixture();
    const script = "CREATE VIEW a REQUIRES VIEW:b DEF a_v1; CREATE VIEW b REQUIRES VIEW:a DEF b_v1";
    const e = await rejected(reconciler(db, dialect, script).reconcile());
    assert.ok(e instanceof PlanningException);
    assert.match(e.message, /cycle/);
});

test("selfDependencyIsIgnored", async () => {
    const { db, dialect } = fixture();
    const report = await reconciler(db, dialect, 
            "CREATE TABLE t REQUIRES TABLE:t DEF t_v1").reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
});

test("nameCollisionIsPlanningError", async () => {
    const { db, dialect } = fixture();
    db.executeDirect(NS, "CREATE INDEX t.x DEF x_v1");
    const script = "CREATE UNIQUE t.x DEF x_v1"; // UNIQUE claims relation:x like an index

    await assert.rejects(() => reconciler(db, dialect, script).reconcile(), PlanningException);
});

test("stalePlanIsRejected", async () => {
    const { db, dialect } = fixture();
    const script = "CREATE TABLE t DEF t_v1";
    const r = reconciler(db, dialect, script);
    const plan = await r.plan();

    db.executeDirect(NS, "CREATE TABLE moved DEF m_v1");

    await assert.rejects(() => r.apply(plan), StalePlanException);
});

test("applyRejectsForeignNamespacePlan", async () => {
    const { db, dialect } = fixture();
    const a = reconciler(db, dialect, "CREATE TABLE t DEF t_v1");
    const b = SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace("other")
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
            .dialect(dialect).build();
    const foreign = await a.plan();
    await assert.rejects(() => b.apply(foreign), IllegalArgumentError);
});

test("applyRunsIssuedPlan", async () => {
    const { db, dialect } = fixture();
    const r = reconciler(db, dialect, "CREATE TABLE t DEF t_v1");
    const plan = await r.plan();
    assert.ok(plan.changes.length > 0);

    const report = await r.apply(plan);
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "t")));
});

test("badScriptFailsAndLeavesNoScratch", async () => {
    const { db, dialect } = fixture();
    const e = await assert.rejects(() => reconciler(db, dialect, "CREATE TABLE t DEF t_v1; BOGUS").reconcile(),
            DesiredStateException);
    assert.equal(db.lockHeld(NS), false);
    const leaked = db.statementLog()
            .filter(s => s.namespace.startsWith("rcl_scratch"))
            .some(s => db.objectsIn(s.namespace).size > 0);
    assert.equal(leaked, false);
});

test("literalTargetNamespaceInScriptIsRejected", async () => {
    const { db, dialect } = fixture();
    const e = await rejected(reconciler(db, dialect, "CREATE TABLE " + NS + ".t DEF t_v1").reconcile());
    assert.ok(e instanceof DesiredStateException);
    assert.match(e.message, /literally/);
    // but a different prefix is fine
    const ok = await reconciler(db, dialect, "CREATE TABLE xcorex.t DEF t_v1").reconcile();
    assert.equal(ok.outcome, ReconcileOutcome.RECONCILED);
});

test("listenerAbortRollsBack", async () => {
    const { db, dialect } = fixture();
    const r = SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
            .dialect(dialect)
            .listener({
                applying() {
                    throw new Error("stop");
                },
            })
            .build();

    const e = await rejected(r.reconcile());
    assert.ok(e instanceof ApplyException);
    assert.ok(e.failed !== undefined);
    assert.equal(db.objectsIn(NS).size, 0, "transactional rollback removes applied objects");
});

test("failuresCarryRunTimings", async () => {
    const { db, dialect } = fixture();
    // an apply-time failure reports the phases it completed plus the
    // partial duration of the failed phase
    const r = SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
            .dialect(dialect)
            .listener({
                applying() {
                    throw new Error("stop");
                },
            })
            .build();
    const e = await rejected(r.reconcile());
    assert.ok(e instanceof ApplyException);
    assert.ok(e.timings.readActual.millis >= 0, "readActual measured");
    assert.ok(e.timings.apply.millis >= 0, "partial apply phase must be measured");
    assert.equal(e.timings.verify, Duration.ZERO, "verify never started");

    // a policy rejection still reports every completed phase
    db.executeDirect(NS, "CREATE TABLE t DEF t_old");
    const policyFail = await rejected(reconciler(db, dialect, "CREATE TABLE t DEF t_new").reconcile());
    assert.ok(policyFail instanceof PolicyViolationException);
    assert.ok(policyFail.timings.plan.millis >= 0, "plan phase measured");
    assert.equal(policyFail.timings.apply, Duration.ZERO);
});

test("concurrentRunsSerialiseOnLock", async () => {
    const { db, dialect } = fixture();
    // the slow run parks inside its planned listener while holding the lock;
    // the fast run's acquireLock then fails within its lock timeout
    let releasePlanned!: () => void;
    const gate = new Promise<void>(resolve => { releasePlanned = resolve; });
    let entered!: () => void;
    const enteredGate = new Promise<void>(resolve => { entered = resolve; });
    const slow = SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
            .dialect(dialect)
            .listener({
                async planned() {
                    entered();
                    await gate;
                },
            })
            .build();
    const first = slow.reconcile().catch(() => undefined);
    await enteredGate;

    const fast = SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
            .dialect(dialect).lockTimeout(Duration.ofMillis(200)).build();

    await assert.rejects(() => fast.reconcile(), LockTimeoutException);
    releasePlanned();
    await first;
    assert.equal(db.lockHeld(NS), false);
});

test("planSqlIsDeterministic", async () => {
    const { db, dialect } = fixture();
    const script = "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_v1";
    const one = (await reconciler(db, dialect, script).plan()).toSql();
    const two = (await reconciler(db, dialect, script).plan()).toSql();
    assert.equal(one, two);
    assert.ok(one.includes("-- target namespace: " + NS));
    assert.ok(one.includes("actual fingerprint:"));
});

test("scratchDataSourcePathWorks", async () => {
    const { db, dialect } = fixture();
    const report = await SchemaReconciler.builder()
            .dataSource(db.dataSource())
            .scratchDataSource(db.dataSource())
            .namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
            .dialect(dialect)
            .build().reconcile();
    assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
});

test("autoCommitIsRestored", async () => {
    const { db, dialect } = fixture();
    await reconciler(db, dialect, "CREATE TABLE t DEF t_v1").reconcile();
    // no exception means the pooled fake connection was closed cleanly; a
    // leaked tx would surface as missing committed objects
    assert.ok(db.objectsIn(NS).has(new ObjectId(ObjectKind.TABLE, "t")));
});

test("exceptionHierarchy", () => {
    assert.ok(new DesiredStateException("x") instanceof SchemaReconcilerException);
    assert.ok(new PlanningException("x") instanceof SchemaReconcilerException);
    assert.ok(new LockTimeoutException("x") instanceof SchemaReconcilerException);
});

/** Spec 5.4: a content-compared object of an unsupported kind that the target already holds is no difference. */
test("contentComparedUnsupportedObjectEqualInTargetIsNoDifference", async () => {
    const { db, dialect } = fixture();
    const narrow = new FakeDialect(db, NARROW_KINDS);
    const id = new ObjectId(ObjectKind.ROUTINE, "f");
    const compared = new Map([[Dialect.CONTENT_COMPARED_ATTRIBUTE, "true"]]);
    const report = ReconcilePolicy.defaults().withUnsupported(Decision.REPORT);
    const run = (script: string, policy: ReturnType<typeof ReconcilePolicy.defaults>) =>
        SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript(script))
                .dialect(narrow).policy(policy).build().reconcile();

    // (a) equal in the target: nothing to report, and the default FAIL policy does not fire
    db.putObject(NS, new SchemaObject(id, new Definition("f_v1"), new Set(), compared));
    const equal = await run("CREATE ROUTINE f COMPARED DEF f_v1", ReconcilePolicy.defaults());
    assert.equal(equal.outcome, ReconcileOutcome.UP_TO_DATE);
    assert.equal(equal.unsupported.length, 0);

    // (c) the content differs: still reported, with the target's definition attached
    const differs = await run("CREATE ROUTINE f COMPARED DEF f_v2", report);
    assert.equal(differs.unsupported.length, 1);
    assert.ok(differs.unsupported[0]!.actual !== undefined);

    // (d) without the marker the same equal object is still reported
    const unmarked = await run("CREATE ROUTINE f DEF f_v1", report);
    assert.equal(unmarked.unsupported.length, 1);
});

test("contentComparedUnsupportedObjectMissingInTargetIsStillReported", async () => {
    const { db, dialect } = fixture();
    const narrow = new FakeDialect(db, NARROW_KINDS);
    const result = await SchemaReconciler.builder()
            .dataSource(db.dataSource()).namespace(NS)
            .desiredState(DesiredState.ofScript("CREATE ROUTINE f COMPARED DEF f_v1"))
            .dialect(narrow).policy(ReconcilePolicy.defaults().withUnsupported(Decision.REPORT))
            .build().reconcile();
    assert.equal(result.unsupported.length, 1);
    assert.equal(result.unsupported[0]!.actual, undefined);
});
