package studio.epicquest.schema.reconciler;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/** The general API rules of spec 2.3 and the message rule of spec 7. */
class ApiRulesTest {

    private static final String NS = "corex";

    @Test
    void publicEntryPointsRejectNull() {
        final var builder = SchemaReconciler.builder();
        assertThrows(NullPointerException.class, () -> builder.dataSource(null));
        assertThrows(NullPointerException.class, () -> builder.namespace(null));
        assertThrows(NullPointerException.class, () -> builder.desiredState(null));
        assertThrows(NullPointerException.class, () -> builder.dialect(null));
        assertThrows(NullPointerException.class, () -> builder.scratchDataSource(null));
        assertThrows(NullPointerException.class, () -> builder.policy(null));
        assertThrows(NullPointerException.class, () -> builder.lockTimeout(null));
        assertThrows(NullPointerException.class, () -> builder.statementTimeout(null));
        assertThrows(NullPointerException.class, () -> builder.listener(null));
        assertThrows(NullPointerException.class, () -> DesiredState.ofScript(null));
        assertThrows(NullPointerException.class, () -> DesiredState.ofPath(null));
        assertThrows(NullPointerException.class, () -> DesiredState.ofResource(null, "x"));
        assertThrows(NullPointerException.class, () -> new Definition(null));
        assertThrows(NullPointerException.class,
                () -> new ObjectId(ObjectKind.TABLE, null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new ReconcilePlan(
                null, "d", "a", "b", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> SchemaSnapshot.of(null));
    }

    @Test
    void recordsCopyTheirCollections() {
        final var changes = new java.util.ArrayList<studio.epicquest.schema.reconciler.model.PlannedChange>();
        final var plan = new ReconcilePlan("ns", "d", "a", "b", List.of(), changes);
        changes.add(null);
        assertTrue(plan.changes().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> plan.changes().add(null));
    }

    @Test
    void builderMessagesNameTheOffendingProperty() {
        final var db = new FakeDb();
        final var missing = assertThrows(ConfigurationException.class,
                () -> SchemaReconciler.builder().namespace(NS)
                        .desiredState(DesiredState.ofScript("")).build());
        assertTrue(missing.getMessage().contains("dataSource"), missing.getMessage());
        final var timeout = assertThrows(ConfigurationException.class,
                () -> SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                        .desiredState(DesiredState.ofScript("")).dialect(new FakeDialect(db))
                        .lockTimeout(Duration.ZERO).build());
        assertTrue(timeout.getMessage().contains("lockTimeout"), timeout.getMessage());
        final var stmt = assertThrows(ConfigurationException.class,
                () -> SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                        .desiredState(DesiredState.ofScript("")).dialect(new FakeDialect(db))
                        .statementTimeout(Duration.ofMillis(-1)).build());
        assertTrue(stmt.getMessage().contains("statementTimeout"), stmt.getMessage());
    }

    @Test
    void runMessagesRaisedBelowTheEngineNameTheNamespace() {
        final var db = new FakeDb();
        final var real = new FakeDialect(db);
        final Dialect unsupported = (Dialect) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Dialect.class},
                (proxy, method, args) -> method.getName().equals("supports")
                        ? Boolean.FALSE : method.invoke(real, args));
        final var reconciler = SchemaReconciler.builder().dataSource(db.dataSource())
                .namespace(NS).desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
                .dialect(unsupported).build();
        final var e = assertThrows(ConfigurationException.class, reconciler::plan);
        assertTrue(e.getMessage().contains("namespace '" + NS + "'"), e.getMessage());
    }
}
