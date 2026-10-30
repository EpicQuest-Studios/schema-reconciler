package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/** Spec 3.1 {@code dump()}: the namespace-neutral bootstrap path. */
class DumpTest {

    private static final String NS = "dumpns";

    private FakeDb fixture() {
        final var db = new FakeDb();
        db.executeDirect(NS, """
                CREATE VIEW v REQUIRES TABLE:t DEF vw;
                CREATE TABLE t DEF tv;
                CREATE COLUMN t.id DEF int
                """);
        return db;
    }

    private SchemaReconciler dumper(FakeDb db) {
        return SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .dialect(new FakeDialect(db))
                .build();
    }

    @Test
    void dumpNeedsNoDesiredState() {
        final var db = fixture();
        final var dump = dumper(db).dump();
        final var lines = dump.lines().toList();
        assertEquals("-- schema-reconciler dump", lines.get(0));
        assertEquals("-- dialect: fake", lines.get(1));
        assertEquals("-- namespace: \"" + NS + "\"", lines.get(2));
        assertTrue(lines.get(3).startsWith("-- fingerprint: "), lines.get(3));
        // the table precedes its dependent view (dependsOn order)
        assertTrue(dump.indexOf("CREATE TABLE t") < dump.indexOf("CREATE VIEW v"), dump);
        assertTrue(dump.contains("CREATE TABLE t DEF tv;"), dump);
        assertTrue(dump.contains("CREATE VIEW v REQUIRES TABLE:t DEF vw;"), dump);
        assertTrue(dump.contains("CREATE COLUMN t.id DEF int;"), dump);
    }

    @Test
    void planReconcileApplyStillRequireDesiredState() {
        final var db = fixture();
        final var reconciler = dumper(db);
        for (final var call : List.<Runnable>of(
                reconciler::plan, reconciler::planTimed, reconciler::reconcile,
                () -> reconciler.apply(new ReconcilePlan(
                        NS, "fake", "a", "b", List.of(), List.of())))) {
            final var e = assertThrows(ConfigurationException.class, call::run);
            assertTrue(e.getMessage().contains("desiredState"), e.getMessage());
        }
    }

    @Test
    void dumpRoundTripsAndIsDeterministic() {
        final var db = fixture();
        final var dump = dumper(db).dump();
        final var report = SchemaReconciler.builder()
                .dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript(dump))
                .dialect(new FakeDialect(db))
                .build().reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
        assertTrue(report.plan().changes().isEmpty());
        assertEquals(dump, dumper(db).dump());
    }

    @Test
    void unrenderableObjectsEmitCommentStubs() {
        final var db = fixture();
        db.executeDirect(NS, "CREATE OTHER privileges:t DEF grants");
        db.putObject(NS, FakeDb.externalDependent(new ObjectId(ObjectKind.VIEW, "x")));
        final var dump = dumper(db).dump();
        assertTrue(dump.contains("-- unsupported: OTHER privileges:t"), dump);
        assertTrue(dump.contains("-- unsupported: VIEW x"), dump);
        // stubs never emit a statement that a replay could trip over
        assertFalse(dump.contains("CREATE OTHER"), dump);
    }
}
