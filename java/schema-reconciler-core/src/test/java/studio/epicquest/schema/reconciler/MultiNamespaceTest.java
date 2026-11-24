package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;

/** Spec 3.7: {@link MultiNamespaceReconciler} over several namespaces. */
class MultiNamespaceTest {

    private static final String A = "multi_a";
    private static final String B = "multi_b";

    private MultiNamespaceReconciler.Builder builder(FakeDb db) {
        return MultiNamespaceReconciler.builder()
                .dataSource(db.dataSource())
                .dialect(new FakeDialect(db));
    }

    @Test
    void reconcileRunsNamespacesInOrderAndAggregatesOutcome() {
        final var db = new FakeDb();
        final var multi = builder(db)
                .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"))
                .namespace(B, DesiredState.ofScript("CREATE TABLE t DEF tb"))
                .build();
        final var report = multi.reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        // insertion order and one report per namespace
        assertEquals(List.of(A, B), List.copyOf(report.reports().keySet()));
        assertEquals(ReconcileOutcome.RECONCILED,
                report.reports().get(A).outcome());
        assertEquals(ReconcileOutcome.RECONCILED,
                report.reports().get(B).outcome());
        assertFalse(report.elapsed().isNegative());
        assertTrue(db.objectsIn(A).containsKey(
                new ObjectId(ObjectKind.TABLE, "t")));
        assertTrue(db.objectsIn(B).containsKey(
                new ObjectId(ObjectKind.TABLE, "t")));

        // converged → aggregate UP_TO_DATE
        final var second = multi.reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, second.outcome());
        assertEquals(List.of(A, B), List.copyOf(second.reports().keySet()));
    }

    @Test
    void aggregateIsReportedOnlyWhenNoNamespaceReconciled() {
        final var db = new FakeDb();
        final var multi = builder(db)
                .policy(ReconcilePolicy.reportOnly())
                .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"))
                .namespace(B, DesiredState.ofScript("CREATE TABLE t DEF tb"))
                .build();
        final var report = multi.reconcile();
        assertEquals(ReconcileOutcome.REPORTED_ONLY, report.outcome());
        assertTrue(db.objectsIn(A).isEmpty());
        assertTrue(db.objectsIn(B).isEmpty());
    }

    @Test
    void reconciledBeatsReportedOnlyInTheAggregate() {
        final var db = new FakeDb();
        final var multi = builder(db)
                .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"),
                        b -> b.policy(ReconcilePolicy.reportOnly()))
                .namespace(B, DesiredState.ofScript("CREATE TABLE t DEF tb"))
                .build();
        final var report = multi.reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertEquals(ReconcileOutcome.REPORTED_ONLY,
                report.reports().get(A).outcome());
        assertEquals(ReconcileOutcome.RECONCILED,
                report.reports().get(B).outcome());
    }

    @Test
    void planReturnsAnOrderedPlanPerNamespace() {
        final var db = new FakeDb();
        db.executeDirect(A, "CREATE TABLE t DEF ta");
        final var plans = builder(db)
                .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF ta"))
                .namespace(B, DesiredState.ofScript("CREATE TABLE u DEF ub"))
                .build().plan();
        assertEquals(List.of(A, B), List.copyOf(plans.keySet()));
        assertTrue(plans.get(A).changes().isEmpty());
        assertEquals(1, plans.get(B).changes().size());
    }

    @Test
    void dumpNeedsNoDesiredState() {
        final var db = new FakeDb();
        db.executeDirect(A, "CREATE TABLE t DEF ta");
        db.executeDirect(B, "CREATE VIEW v REQUIRES TABLE:w DEF vb;"
                + " CREATE TABLE w DEF wb");
        final var dumps = builder(db).namespace(A).namespace(B).build().dump();
        assertEquals(List.of(A, B), List.copyOf(dumps.keySet()));
        assertTrue(dumps.get(A).contains("CREATE TABLE t DEF ta;"),
                dumps.get(A));
        assertTrue(dumps.get(B).contains("CREATE VIEW v REQUIRES TABLE:w DEF vb;"),
                dumps.get(B));
        assertTrue(dumps.get(B).contains("CREATE TABLE w DEF wb;"),
                dumps.get(B));
    }

    @Test
    void buildValidatesWithoutOpeningConnections() {
        final var db = new FakeDb();
        // no dataSource
        assertThrows(ConfigurationException.class,
                () -> MultiNamespaceReconciler.builder()
                        .dialect(new FakeDialect(db))
                        .namespace(A, DesiredState.ofScript("CREATE TABLE t DEF x"))
                        .build());
        // no namespace
        assertThrows(ConfigurationException.class, () -> builder(db).build());
        // duplicate names
        assertThrows(ConfigurationException.class,
                () -> builder(db).namespace(A).namespace(A).build());
        // empty name
        assertThrows(ConfigurationException.class,
                () -> builder(db).namespace("").build());
    }

    @Test
    void perNamespaceCustomizerAdjustsOnlyThatNamespace() {
        final var db = new FakeDb();
        final var script = "CREATE TABLE t DEF ta";
        final var multi = builder(db)
                .namespace(A, DesiredState.ofScript(script),
                        b -> b.listener(new ReconcileListener() {
                            @Override
                            public void applied(PlannedChange change,
                                                Duration elapsed) {
                                throw new IllegalStateException("listener fired");
                            }
                        }))
                .namespace(B, DesiredState.ofScript(script))
                .build();
        // A's listener aborts A's run — B never reconciles
        assertThrows(ApplyException.class, multi::reconcile);
        assertTrue(db.objectsIn(B).isEmpty());
    }
}
