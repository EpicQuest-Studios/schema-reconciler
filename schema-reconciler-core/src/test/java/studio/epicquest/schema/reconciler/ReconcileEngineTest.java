package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * End-to-end engine tests on {@link FakeDb}: the whole spec 5.1 sequence —
 * lock, snapshot, scratch desired state, diff, plan, policy, apply, verify —
 * exercised without a real database.
 */
class ReconcileEngineTest {

    private static final String NS = "corex";

    private final FakeDb db = new FakeDb();
    private final FakeDialect dialect = new FakeDialect(db);

    private SchemaReconciler reconciler(String script) {
        return SchemaReconciler.builder()
                .dataSource(db.dataSource())
                .namespace(NS)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect)
                .build();
    }

    private SchemaReconciler reconciler(String script, ReconcilePolicy policy) {
        return SchemaReconciler.builder()
                .dataSource(db.dataSource())
                .namespace(NS)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect)
                .policy(policy)
                .build();
    }

    @Test
    void emptyTargetCreatesEverything() {
        final var report = reconciler("""
                CREATE TABLE users DEF users_v1;
                CREATE COLUMN users.id DEF id_v1;
                CREATE VIEW active REQUIRES TABLE:users DEF active_v1
                """).reconcile();

        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "users")));
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.VIEW, "active")));
        // second run converges
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler("""
                CREATE TABLE users DEF users_v1;
                CREATE COLUMN users.id DEF id_v1;
                CREATE VIEW active REQUIRES TABLE:users DEF active_v1
                """).reconcile().outcome());
    }

    @Test
    void matchingTargetRunsNoStatements() {
        final var script = "CREATE TABLE users DEF users_v1; CREATE COLUMN users.id DEF id_v1";
        db.executeDirect(NS, script);

        final var report = reconciler(script).reconcile();

        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
        assertTrue(report.plan().changes().isEmpty());
        assertTrue(db.statementsOn(NS).isEmpty(), "no statements should run on the target");
        assertFalse(db.lockHeld(NS), "lock must be released");
    }

    @Test
    void missingTableCoversItsColumns() {
        final var report = reconciler("""
                CREATE TABLE users DEF users_v1;
                CREATE COLUMN users.id DEF id_v1;
                CREATE COLUMN users.name DEF name_v1
                """).reconcile();

        assertEquals(1, report.plan().changes().size(), "the table create covers its columns");
        assertEquals(new ObjectId(ObjectKind.TABLE, "users"), report.plan().changes().get(0).target());
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.COLUMN, "id", "users")));
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.COLUMN, "name", "users")));
    }

    @Test
    void driftedViewIsRecreatedInPlace() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_old");
        final var script = "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_new";

        final var report = reconciler(script).reconcile();

        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var v = new ObjectId(ObjectKind.VIEW, "v");
        final var viewChanges = report.plan().changes().stream()
                .filter(c -> c.target().equals(v)).toList();
        assertEquals(List.of(ChangeAction.DROP, ChangeAction.CREATE),
                viewChanges.stream().map(PlannedChange::action).toList());
        assertEquals("v_new", db.objectsIn(NS).get(v).definition().canonical());
    }

    @Test
    void dependentRebuildsAroundDroppedTable() {
        db.executeDirect(NS,
                "CREATE TABLE t DEF t_old; CREATE VIEW v REQUIRES TABLE:t DEF v_v1");
        final var script = "CREATE TABLE t DEF t_new; CREATE VIEW v REQUIRES TABLE:t DEF v_v1";
        final var policy = ReconcilePolicy.defaults().withDestructive(Decision.APPLY);

        final var report = reconciler(script, policy).reconcile();

        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var changes = report.plan().changes();
        // drop v (dependent) before t; create t before v
        final var drops = changes.stream()
                .filter(c -> c.action() == ChangeAction.DROP).toList();
        final var creates = changes.stream()
                .filter(c -> c.action() == ChangeAction.CREATE).toList();
        assertEquals(List.of(new ObjectId(ObjectKind.VIEW, "v"), new ObjectId(ObjectKind.TABLE, "t")),
                drops.stream().map(PlannedChange::target).toList());
        assertEquals(List.of(new ObjectId(ObjectKind.TABLE, "t"), new ObjectId(ObjectKind.VIEW, "v")),
                creates.stream().map(PlannedChange::target).toList());
        assertEquals("v_v1", db.objectsIn(NS).get(new ObjectId(ObjectKind.VIEW, "v"))
                .definition().canonical());
    }

    @Test
    void unexpectedObjectsAreReportedNotDropped() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_v1; CREATE TABLE extra DEF e_v1");

        final var report = reconciler("CREATE TABLE t DEF t_v1").reconcile();

        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
        assertEquals(1, report.unexpected().size());
        assertEquals(new ObjectId(ObjectKind.TABLE, "extra"), report.unexpected().get(0).id());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "extra")));
        assertTrue(db.statementsOn(NS).stream().noneMatch(s -> s.sql().contains("extra")));
    }

    @Test
    void destructiveChangeFailsByDefaultPolicy() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_old");
        assertThrows(PolicyViolationException.class,
                () -> reconciler("CREATE TABLE t DEF t_new").reconcile());
    }

    @Test
    void reportOnlyChangesNothing() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_old");
        final var report = reconciler("CREATE TABLE t DEF t_new; CREATE TABLE u DEF u_v1",
                ReconcilePolicy.reportOnly()).reconcile();

        assertEquals(ReconcileOutcome.REPORTED_ONLY, report.outcome());
        assertTrue(report.applied().isEmpty());
        assertFalse(report.reported().isEmpty());
        assertTrue(db.statementsOn(NS).isEmpty());
        assertFalse(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "u")));
    }

    @Test
    void dependentsOfReportedChangesAreDemoted() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_old");
        final var script = """
                CREATE TABLE t DEF t_v1;
                CREATE VIEW v REQUIRES TABLE:t DEF v_new;
                CREATE VIEW w REQUIRES VIEW:v DEF w_v1
                """;
        final var policy = ReconcilePolicy.defaults().withRebuild(Decision.REPORT);

        final var report = reconciler(script, policy).reconcile();

        assertEquals(ReconcileOutcome.REPORTED_ONLY, report.outcome());
        final var w = new ObjectId(ObjectKind.VIEW, "w");
        assertTrue(report.reported().stream().anyMatch(c -> c.target().equals(w)),
                "w must be demoted to REPORT because v is not applied");
        assertFalse(db.objectsIn(NS).containsKey(w));
    }

    @Test
    void unsupportedKindIsSeparatePolicyDecision() {
        final var narrow = new FakeDialect(db,
                Set.of(ObjectKind.TABLE, ObjectKind.COLUMN, ObjectKind.INDEX, ObjectKind.VIEW,
                        ObjectKind.SEQUENCE, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
                        ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.TRIGGER));
        final var script = "CREATE ROUTINE f DEF f_v1";
        final var report = ReconcilePolicy.defaults().withUnsupported(Decision.REPORT);

        final var result = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript(script))
                .dialect(narrow).policy(report).build().reconcile();

        // UNSUPPORTED differences under REPORT do not prevent UP_TO_DATE (3.5)
        assertEquals(ReconcileOutcome.UP_TO_DATE, result.outcome());
        assertEquals(1, result.unsupported().size());
        assertEquals(DifferenceType.UNSUPPORTED, result.unsupported().get(0).type());
        assertTrue(db.statementsOn(NS).isEmpty());
        // and unsupported=FAIL rejects the plan
        assertThrows(PolicyViolationException.class,
                () -> SchemaReconciler.builder()
                        .dataSource(db.dataSource()).namespace(NS)
                        .desiredState(DesiredState.ofScript(script))
                        .dialect(narrow).policy(ReconcilePolicy.defaults()).build().reconcile());
    }

    @Test
    void unsupportedDependencyDemotesDependents() {
        final var narrow = new FakeDialect(db,
                Set.of(ObjectKind.TABLE, ObjectKind.VIEW));
        final var script = "CREATE ROUTINE f DEF f_v1; CREATE VIEW v REQUIRES ROUTINE:f DEF v_v1";
        final var policy = ReconcilePolicy.defaults().withUnsupported(Decision.REPORT);

        final var report = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript(script))
                .dialect(narrow).policy(policy).build().reconcile();

        final var v = new ObjectId(ObjectKind.VIEW, "v");
        assertTrue(report.reported().stream().anyMatch(c -> c.target().equals(v)),
                "view depending on an unsupported routine must be demoted");
        assertFalse(db.objectsIn(NS).containsKey(v));
    }

    @Test
    void externalDependentBlocksPlanning() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_old");
        db.putObject(NS, FakeDb.externalDependent(
                new ObjectId(ObjectKind.OTHER, "foreign.obj"),
                new ObjectId(ObjectKind.TABLE, "t")));

        assertThrows(PlanningException.class,
                () -> reconciler("CREATE TABLE t DEF t_new",
                        ReconcilePolicy.defaults().withDestructive(Decision.APPLY)).reconcile());
    }

    @Test
    void unexpectedDependentBlocksPlanning() {
        db.executeDirect(NS, "CREATE TABLE t DEF t_old; CREATE VIEW z REQUIRES TABLE:t DEF z_v1");

        assertThrows(PlanningException.class,
                () -> reconciler("CREATE TABLE t DEF t_new",
                        ReconcilePolicy.defaults().withDestructive(Decision.APPLY)).reconcile());
    }

    @Test
    void dependencyCycleIsPlanningError() {
        final var script = "CREATE VIEW a REQUIRES VIEW:b DEF a_v1; CREATE VIEW b REQUIRES VIEW:a DEF b_v1";
        final var e = assertThrows(PlanningException.class,
                () -> reconciler(script).reconcile());
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void selfDependencyIsIgnored() {
        final var report = reconciler(
                "CREATE TABLE t REQUIRES TABLE:t DEF t_v1").reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
    }

    @Test
    void nameCollisionIsPlanningError() {
        db.executeDirect(NS, "CREATE INDEX t.x DEF x_v1");
        final var script = "CREATE UNIQUE t.x DEF x_v1"; // UNIQUE claims relation:x like an index

        assertThrows(PlanningException.class, () -> reconciler(script).reconcile());
    }

    @Test
    void stalePlanIsRejected() {
        final var script = "CREATE TABLE t DEF t_v1";
        final var r = reconciler(script);
        final var plan = r.plan();

        db.executeDirect(NS, "CREATE TABLE moved DEF m_v1");

        assertThrows(StalePlanException.class, () -> r.apply(plan));
    }

    @Test
    void applyRejectsForeignNamespacePlan() {
        final var a = reconciler("CREATE TABLE t DEF t_v1");
        final var b = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace("other")
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
                .dialect(dialect).build();
        final var foreign = a.plan();
        assertThrows(IllegalArgumentException.class, () -> b.apply(foreign));
    }

    @Test
    void applyRunsIssuedPlan() {
        final var r = reconciler("CREATE TABLE t DEF t_v1");
        final var plan = r.plan();
        assertFalse(plan.changes().isEmpty());

        final var report = r.apply(plan);
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "t")));
    }

    @Test
    void badScriptFailsAndLeavesNoScratch() {
        final var e = assertThrows(DesiredStateException.class,
                () -> reconciler("CREATE TABLE t DEF t_v1; BOGUS").reconcile());
        assertFalse(db.lockHeld(NS));
        assertTrue(db.statementLog().stream()
                .noneMatch(s -> s.namespace().startsWith("rcl_scratch")
                        && db.objectsIn(s.namespace()).size() > 0));
    }

    @Test
    void literalTargetNamespaceInScriptIsRejected() {
        final var e = assertThrows(DesiredStateException.class,
                () -> reconciler("CREATE TABLE " + NS + ".t DEF t_v1").reconcile());
        assertTrue(e.getMessage().contains("literally"), e.getMessage());
        // but a different prefix is fine
        assertEquals(ReconcileOutcome.RECONCILED,
                reconciler("CREATE TABLE xcorex.t DEF t_v1").reconcile().outcome());
    }

    @Test
    void listenerAbortRollsBack() {
        final var r = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
                .dialect(dialect)
                .listener(new ReconcileListener() {
                    @Override
                    public void applying(PlannedChange change) {
                        throw new IllegalStateException("stop");
                    }
                })
                .build();

        final var e = assertThrows(ApplyException.class, r::reconcile);
        assertTrue(e.failed().isPresent());
        assertTrue(db.objectsIn(NS).isEmpty(), "transactional rollback removes applied objects");
    }

    @Test
    void failuresCarryRunTimings() {
        // an apply-time failure reports the phases it completed plus the
        // partial duration of the failed phase
        final var r = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
                .dialect(dialect)
                .listener(new ReconcileListener() {
                    @Override
                    public void applying(PlannedChange change) {
                        throw new IllegalStateException("stop");
                    }
                })
                .build();
        final var e = assertThrows(ApplyException.class, r::reconcile);
        assertTrue(e.timings().readActual().toNanos() > 0, e.timings().toString());
        assertTrue(e.timings().apply().toNanos() > 0,
                "partial apply phase must be measured: " + e.timings());
        assertEquals(Duration.ZERO, e.timings().verify(),
                "verify never started: " + e.timings());

        // a policy rejection still reports every completed phase
        db.executeDirect(NS, "CREATE TABLE t DEF t_old");
        final var policyFail = assertThrows(PolicyViolationException.class,
                () -> reconciler("CREATE TABLE t DEF t_new").reconcile());
        assertTrue(policyFail.timings().plan().toNanos() > 0,
                policyFail.timings().toString());
        assertEquals(Duration.ZERO, policyFail.timings().apply());
    }

    @Test
    void concurrentRunsSerialiseOnLock() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var slow = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
                .dialect(dialect)
                .listener(new ReconcileListener() {
                    @Override
                    public void planned(ReconcilePlan plan) {
                        entered.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .build();
        final var first = new Thread(() -> {
            try {
                slow.reconcile();
            } catch (RuntimeException ignored) {
            }
        });
        first.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        final var fast = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
                .dialect(dialect).lockTimeout(Duration.ofMillis(200)).build();

        assertThrows(LockTimeoutException.class, fast::reconcile);
        release.countDown();
        first.join(10_000);
        assertFalse(db.lockHeld(NS));
    }

    @Test
    void planSqlIsDeterministic() {
        final var script = "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_v1";
        final var one = reconciler(script).plan().toSql();
        final var two = reconciler(script).plan().toSql();
        assertEquals(one, two);
        assertTrue(one.contains("-- target namespace: " + NS));
        assertTrue(one.contains("actual fingerprint:"));
    }

    @Test
    void scratchDataSourcePathWorks() {
        final var report = SchemaReconciler.builder()
                .dataSource(db.dataSource())
                .scratchDataSource(db.dataSource())
                .namespace(NS)
                .desiredState(DesiredState.ofScript("CREATE TABLE t DEF t_v1"))
                .dialect(dialect)
                .build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
    }

    @Test
    void autoCommitIsRestored() {
        reconciler("CREATE TABLE t DEF t_v1").reconcile();
        // no exception means the pooled fake connection was closed cleanly; a
        // leaked tx would surface as missing committed objects
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "t")));
    }

    @Test
    void exceptionHierarchy() {
        assertInstanceOf(SchemaReconcilerException.class, new DesiredStateException("x"));
        assertInstanceOf(SchemaReconcilerException.class, new PlanningException("x"));
        assertInstanceOf(SchemaReconcilerException.class, new LockTimeoutException("x"));
    }
}
