package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * End-to-end coverage over the migrated deployment fixture (spec A.7):
 * the legacy {@code schema.sql} is transformed in memory exactly as the
 * migration steps prescribe, then reconciled against a real namespace.
 */
class DeploymentFixtureTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final DataSource dataSource = PgTestDatabase.dataSource();
    private final Dialect dialect = new PostgreSqlDialect();
    private String namespace;

    @BeforeEach
    void createNs() throws Exception {
        namespace = "svc_e2e_" + SEQUENCE.incrementAndGet();
        execute("CREATE SCHEMA " + dialect.quoteIdentifier(namespace));
    }

    @AfterEach
    void dropNs() throws Exception {
        execute("DROP SCHEMA " + dialect.quoteIdentifier(namespace) + " CASCADE");
    }

    @AfterAll
    static void stop() {
        PgTestDatabase.stop();
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                final var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /** The A.7 transform: literal qualifier to placeholder, deployment statements removed. */
    private static String migrateFixture() {
        String raw;
        try (var in = DeploymentFixtureTest.class
                .getResourceAsStream("/deployment/schema.sql")) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // step 1: literal qualifier → placeholder
        var migrated = raw.replace("svc.", "${namespace}.");
        // step 2: remove deployment-only statements (library owns locking + namespace)
        migrated = migrated.replaceAll("(?m)^\\s*SELECT\\s+pg_advisory_lock[^;]*;\\s*", "");
        migrated = migrated.replaceAll("(?m)^\\s*CREATE\\s+SCHEMA[^;]*;\\s*", "");
        return migrated;
    }

    private SchemaReconciler reconciler(String script) {
        return SchemaReconciler.builder()
                .dataSource(dataSource)
                .namespace(namespace)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect)
                .build();
    }

    private SchemaSnapshot snapshot() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                return dialect.readSnapshot(c, namespace);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("A.7 migrated deployment fixture reconciles and is idempotent")
    void migratedFixtureReconciles() throws Exception {
        PgTestDatabase.ensureUuidv7(dataSource);
        final var script = migrateFixture();

        final var first = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, first.outcome());
        assertTrue(first.unsupported().isEmpty(),
                "unexpected unsupported diffs: " + first.unsupported());

        final var objects = snapshot();
        assertTrue(objects.objects().containsKey(new ObjectId(ObjectKind.TABLE, "plans")));
        assertTrue(objects.objects().containsKey(new ObjectId(ObjectKind.TABLE, "workflow_runs")));
        assertTrue(objects.objects().containsKey(new ObjectId(ObjectKind.SEQUENCE, "fencing_seq")));
        assertTrue(objects.objects().keySet().stream()
                .anyMatch(id -> id.kind() == ObjectKind.TRIGGER));
        final var views = objects.objects().keySet().stream()
                .filter(id -> id.kind() == ObjectKind.VIEW).count();
        assertTrue(views >= 10, "expected the fixture views, found " + views);
        assertTrue(objects.objects().keySet().stream()
                .anyMatch(id -> id.kind() == ObjectKind.ROUTINE));

        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
    }

    @Test
    @DisplayName("A.7 migrated fixture plans deterministically")
    void migratedFixtureDeterministicPlan() throws Exception {
        PgTestDatabase.ensureUuidv7(dataSource);
        final var script = migrateFixture();
        final var one = reconciler(script).plan().toSql();
        final var two = reconciler(script).plan().toSql();
        assertEquals(one, two);
    }

    @Test
    @DisplayName("3.1 dump() round-trips a reconciled namespace")
    void dumpRoundTrips() throws Exception {
        PgTestDatabase.ensureUuidv7(dataSource);
        assertEquals(ReconcileOutcome.RECONCILED,
                reconciler(migrateFixture()).reconcile().outcome());
        final var dumper = SchemaReconciler.builder()
                .dataSource(dataSource).namespace(namespace)
                .dialect(dialect).build();
        final var dump = dumper.dump();
        // the emitted script is a desired-state script: feeding it back on
        // the same namespace must be a no-op
        final var self = reconciler(dump).reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, self.outcome());
        assertTrue(self.plan().changes().isEmpty());
        // and the second dump is byte-identical
        assertEquals(dump, dumper.dump());
    }
}
