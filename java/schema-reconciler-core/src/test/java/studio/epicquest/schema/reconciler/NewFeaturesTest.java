package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeHazard;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.LockLevel;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * Unit coverage for the spec 5.5–5.6 and serialisation features on
 * {@link FakeDb}: the prune policy, JSON plans, hazard metadata,
 * concurrent indexes and rename hints.
 */
class NewFeaturesTest {

    private static final String NS = "featx";

    private final FakeDb db = new FakeDb();
    private final FakeDialect dialect = new FakeDialect(db);

    private SchemaReconciler.Builder reconciler(String script) {
        return reconciler(script, null);
    }

    private SchemaReconciler.Builder reconciler(String script, ReconcilePolicy policy) {
        final var b = SchemaReconciler.builder()
                .dataSource(db.dataSource())
                .namespace(NS)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect);
        if (policy != null) {
            b.policy(policy);
        }
        return b;
    }

    // ---- prune (5.5) ------------------------------------------------------

    @Test
    void pruneOffLeavesUnexpectedObjectsAlone() {
        db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE TABLE extra DEF e");
        final var report = reconciler("CREATE TABLE keep DEF k")
                .build().reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "extra")));
    }

    @Test
    void pruneDropsAnUnexpectedTableDestructively() {
        db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE TABLE extra DEF e");
        final var report = reconciler("CREATE TABLE keep DEF k",
                ReconcilePolicy.defaults().withPrune(true)
                        .withDestructive(Decision.APPLY)).build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertFalse(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "extra")));
        final var drop = report.applied().stream()
                .filter(c -> c.action() == ChangeAction.DROP
                        && c.target().name().equals("extra"))
                .findFirst().orElseThrow();
        assertEquals(ChangeSafety.DESTRUCTIVE, drop.safety());
    }

    @Test
    void pruneDropsAnUnexpectedViewAsRebuild() {
        db.executeDirect(NS, "CREATE TABLE keep DEF k;"
                + " CREATE VIEW stray REQUIRES TABLE:keep DEF v");
        final var report = reconciler("CREATE TABLE keep DEF k",
                ReconcilePolicy.defaults().withPrune(true)).build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertFalse(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.VIEW, "stray")));
        final var drop = report.applied().stream()
                .filter(c -> c.action() == ChangeAction.DROP
                        && c.target().kind() == ObjectKind.VIEW)
                .findFirst().orElseThrow();
        assertEquals(ChangeSafety.REBUILD, drop.safety());
    }

    @Test
    void pruneSuppressesChildDropsCoveredByAnUnexpectedParent() {
        db.executeDirect(NS, "CREATE TABLE keep DEF k;"
                + " CREATE TABLE stray DEF s; CREATE COLUMN stray.c DEF c");
        final var report = reconciler("CREATE TABLE keep DEF k",
                ReconcilePolicy.defaults().withPrune(true)
                        .withDestructive(Decision.APPLY)).build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var drops = report.plan().changes().stream()
                .filter(c -> c.action() == ChangeAction.DROP).toList();
        assertEquals(1, drops.size(), "the table's drop covers its column");
        assertEquals(ObjectKind.TABLE, drops.get(0).target().kind());
    }

    @Test
    void pruneColumnOnExpectedTableRendersDropColumn() {
        db.executeDirect(NS, "CREATE TABLE keep DEF k; CREATE COLUMN keep.c DEF c");
        final var report = reconciler("CREATE TABLE keep DEF k",
                ReconcilePolicy.defaults().withPrune(true)
                        .withDestructive(Decision.APPLY)).build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertFalse(db.objectsIn(NS).containsKey(
                new ObjectId(ObjectKind.COLUMN, "c", "keep")));
        final var drop = report.applied().stream()
                .filter(c -> c.action() == ChangeAction.DROP)
                .findFirst().orElseThrow();
        assertEquals(ObjectKind.COLUMN, drop.target().kind());
        assertEquals(ChangeSafety.DESTRUCTIVE, drop.safety());
    }

    // ---- machine-readable plans (JSON) -------------------------------------

    @Test
    void planJsonRoundTripsAndApplyJsonApplies() {
        final var planned = reconciler("CREATE TABLE t DEF v1").build().plan();
        final var json = planned.toJson();
        final var restored = ReconcilePlan.fromJson(json);
        assertEquals(planned.differences().size(), restored.differences().size());
        assertEquals(planned.changes().size(), restored.changes().size());
        assertEquals(json, restored.toJson(), "serialisation is stable");

        final var report = reconciler("CREATE TABLE t DEF v1")
                .build().applyJson(json);
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "t")));
    }

    @Test
    void fromJsonRejectsInvalidDocuments() {
        for (final var bad : List.of(
                "", "{}", "[]", "null", "42",
                "{\"schemaVersion\":2}",
                "{\"schemaVersion\":1,\"targetNamespace\":\"x\"}",
                "{\"schemaVersion\":1,\"targetNamespace\":\"x\","
                        + "\"dialectId\":\"fake\",\"actualFingerprint\":\"fp\","
                        + "\"desiredFingerprint\":\"fp2\",\"differences\":{},"
                        + "\"changes\":[]}",
                "{\"schemaVersion\":1,\"targetNamespace\":\"x\","
                        + "\"dialectId\":\"fake\",\"actualFingerprint\":\"fp\","
                        + "\"desiredFingerprint\":\"fp2\",\"differences\":[],"
                        + "\"changes\":[{\"order\":\"1\"}]}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ReconcilePlan.fromJson(bad), "input: " + bad);
        }
    }

    @Test
    void applyJsonRejectsAStalePlan() {
        final var planned = reconciler("CREATE TABLE t DEF v1").build().plan();
        db.executeDirect(NS, "CREATE TABLE other DEF o");
        assertThrows(Exception.class,
                () -> reconciler("CREATE TABLE t DEF v1")
                        .build().applyJson(planned.toJson()));
    }

    // ---- hazard metadata ---------------------------------------------------

    @Test
    void hazardDefaultsAreTrivial() {
        final var c = new PlannedChange(1, new ObjectId(ObjectKind.TABLE, "t"),
                ChangeAction.CREATE, ChangeSafety.ADDITIVE, List.of(),
                Optional.empty(), "r");
        assertFalse(c.nonTransactional());
        assertTrue(c.hazard().isTrivial());
        assertEquals(LockLevel.NONE, ChangeHazard.NONE.lockLevel());
    }

    @Test
    void planSqlHeadersCarryNonTrivialHazards() {
        final var planned = reconciler(
                "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
                .concurrentIndexes(true).build().plan();
        final var sql = planned.toSql();
        assertTrue(sql.contains("hazard: lock SHARE_UPDATE_EXCLUSIVE"), sql);
        assertTrue(sql.contains("non-transactional"), sql);
        // a hazard-aware change surfaces its lock in the header
        final var hazard = new PlannedChange(1, new ObjectId(ObjectKind.TABLE, "x"),
                ChangeAction.ALTER, ChangeSafety.REBUILD, List.of("ALTER TABLE x"),
                Optional.empty(), "r", false,
                new ChangeHazard(LockLevel.ACCESS_EXCLUSIVE, true, false,
                        List.of("heavy")));
        assertTrue(hazard.hazard().rewritesTable());
        assertEquals(LockLevel.ACCESS_EXCLUSIVE, hazard.hazard().lockLevel());
    }

    // ---- concurrent indexes (5.6) -------------------------------------------

    @Test
    void concurrentIndexesRunsTheIndexCreateNonTransactionally() {
        final var report = reconciler(
                "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
                .concurrentIndexes(true).build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var index = report.applied().stream()
                .filter(c -> c.target().kind() == ObjectKind.INDEX)
                .findFirst().orElseThrow();
        assertTrue(index.nonTransactional());
        assertEquals(LockLevel.SHARE_UPDATE_EXCLUSIVE, index.hazard().lockLevel());
        // a second run stays converged
        final var again = reconciler(
                "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
                .concurrentIndexes(true).build().reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, again.outcome());
    }

    @Test
    void concurrentIndexesOffKeepsIndexCreatesTransactional() {
        final var report = reconciler(
                "CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")
                .build().reconcile();
        final var index = report.applied().stream()
                .filter(c -> c.target().kind() == ObjectKind.INDEX)
                .findFirst().orElseThrow();
        assertFalse(index.nonTransactional());
    }

    // ---- rename hints (5.5.1) -------------------------------------------------

    @Test
    void builderRenameTurnsDropCreateIntoARename() {
        db.executeDirect(NS, "CREATE TABLE old DEF v1");
        final var report = reconciler("CREATE TABLE new DEF v1")
                .rename(new ObjectId(ObjectKind.TABLE, "old"),
                        new ObjectId(ObjectKind.TABLE, "new"))
                .build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "new")));
        assertFalse(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "old")));
        final var rename = report.applied().stream()
                .filter(c -> c.target().name().equals("new"))
                .findFirst().orElseThrow();
        assertEquals(ChangeAction.ALTER, rename.action());
        assertTrue(rename.statements().get(0).startsWith("RENAME TABLE old TO new"));
        // no drop+create pair survived
        assertTrue(report.applied().stream()
                .allMatch(c -> c.action() != ChangeAction.DROP));
    }

    @Test
    void scriptRenameDirectiveIsPickedUp() {
        db.executeDirect(NS, "CREATE TABLE old DEF v1");
        final var script = "-- @reconciler rename TABLE old -> new\n"
                + "CREATE TABLE new DEF v1";
        final var report = reconciler(script).build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "new")));
        assertFalse(db.objectsIn(NS).containsKey(new ObjectId(ObjectKind.TABLE, "old")));
    }

    @Test
    void renameWithDriftAppliesTheFollowUpChange() {
        db.executeDirect(NS, "CREATE TABLE keep DEF k;"
                + " CREATE VIEW old REQUIRES TABLE:keep DEF v1");
        final var report = reconciler(
                "CREATE TABLE keep DEF k; CREATE VIEW new REQUIRES TABLE:keep DEF v2")
                .rename(new ObjectId(ObjectKind.VIEW, "old"),
                        new ObjectId(ObjectKind.VIEW, "new"))
                .build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var renamed = db.objectsIn(NS).get(new ObjectId(ObjectKind.VIEW, "new"));
        assertNotNull(renamed);
        assertEquals("v2", renamed.definition().canonical());
        // the rename ran before the recreate pair
        final var rename = report.applied().stream()
                .filter(c -> c.action() == ChangeAction.ALTER)
                .findFirst().orElseThrow();
        assertTrue(rename.statements().get(0).startsWith("RENAME VIEW old TO new"));
    }

    @Test
    void anAlreadyAppliedRenameHintIsAnIdempotentNoOp() {
        db.executeDirect(NS, "CREATE TABLE new DEF v1");
        final var report = reconciler("CREATE TABLE new DEF v1")
                .rename(new ObjectId(ObjectKind.TABLE, "old"),
                        new ObjectId(ObjectKind.TABLE, "new"))
                .build().reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
    }

    @Test
    void renameWithBothSidesPresentIsAPlanningError() {
        db.executeDirect(NS, "CREATE TABLE old DEF v1; CREATE TABLE new DEF v2");
        assertThrows(PlanningException.class,
                () -> reconciler("CREATE TABLE new DEF v2")
                        .rename(new ObjectId(ObjectKind.TABLE, "old"),
                                new ObjectId(ObjectKind.TABLE, "new"))
                        .build().reconcile());
    }
}
