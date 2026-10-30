package studio.epicquest.schema.reconciler.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.LockTimeoutException;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.PolicyViolationException;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.ReconcileReport;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.StalePlanException;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * The dialect conformance suite of spec 9.2 — scenarios C-01 to C-36. A dialect
 * is run by subclassing this class and providing a disposable database plus
 * per-test namespaces via the hooks below. Skips are permitted only for kinds
 * absent from {@code supportedKinds()} or undeclared capabilities, and every
 * skip is reported by JUnit as an aborted test.
 */
public abstract class DialectConformanceTest {

    // ---- required hooks ---------------------------------------------------

    /** A DataSource on the disposable database under test. */
    protected abstract DataSource dataSource();

    /** The dialect under test. */
    protected abstract Dialect dialect();

    /** Creates and returns a fresh, empty namespace for one test. */
    protected abstract String createNamespace() throws Exception;

    /** Removes the namespace and everything in it after the test. */
    protected abstract void dropNamespace(String namespace) throws Exception;

    // ---- optional hooks ----------------------------------------------------

    /** A second DataSource on the same engine, for {@code scratchDataSource} (C-26). */
    protected DataSource scratchDataSource() {
        return dataSource();
    }

    /** Asserts that no scratch namespace survives on the target database (C-15, C-22). */
    protected void assertNoScratchNamespacesLeft() throws Exception {
    }

    /** Asserts that a pooled connection carries no lock or session state (C-21). */
    protected void assertCleanSession() throws Exception {
    }

    /**
     * Marks the statement that opens scratch work on the same connection
     * (for example {@code CREATE SCHEMA} or {@code SAVEPOINT}), used to exclude
     * scratch activity when counting target statements (C-02, C-18).
     */
    protected boolean isScratchBoundary(String sql) {
        return false;
    }

    /** Marks the statement that closes scratch work (for example {@code ROLLBACK TO SAVEPOINT}). */
    protected boolean isScratchEnd(String sql) {
        return false;
    }

    /**
     * A script fragment creating an object the dialect reports as
     * {@code UNSUPPORTED}, plus an expression a supported object can depend on.
     * {@code null} skips C-14 and C-29 — only for dialects without any
     * unsupported kind (they must still detect {@code OTHER}).
     */
    protected UnsupportedScript unsupportedScript() {
        return null;
    }

    /** A statement creating an unsupported object and one supported object depending on it. */
    public record UnsupportedScript(String script, String dependentName) { }

    /**
     * The policy category of the object {@link #unsupportedScript()} creates —
     * for OTHER ids the name prefix before {@code ':'}, or {@code "relation"}
     * when the name carries none (C-35). {@code null} skips the scenario.
     */
    protected String unsupportedCategory() {
        return null;
    }

    /**
     * Whether the dialect renders safe in-place column alterations (default,
     * {@code NOT NULL}, widening type changes) instead of manual changes
     * (C-33). {@code false} skips the scenario.
     */
    protected boolean columnAlterSupported() {
        return false;
    }

    /** DDL for an identity-style column (C-32), or {@code null} to skip. */
    protected String identityColumnDdl() {
        return null;
    }

    /** DDL for a serial-style column (C-32), or {@code null} to skip. */
    protected String serialColumnDdl() {
        return null;
    }

    // ---- lifecycle ----------------------------------------------------------

    private String namespace;

    @BeforeEach
    void openNamespace() throws Exception {
        namespace = createNamespace();
    }

    @AfterEach
    void closeNamespace() throws Exception {
        assertNoScratchNamespacesLeft();
        dropNamespace(namespace);
    }

    protected final String namespace() {
        return namespace;
    }

    // ---- helpers ----------------------------------------------------------

    protected final SchemaReconciler reconciler(String script) {
        return reconciler(script, ReconcilePolicy.defaults());
    }

    protected final SchemaReconciler reconciler(String script, ReconcilePolicy policy) {
        return SchemaReconciler.builder()
                .dataSource(dataSource())
                .namespace(namespace)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect())
                .policy(policy)
                .build();
    }

    protected final SchemaReconciler reconcilerOn(DataSource ds, String script,
                                                  ReconcilePolicy policy) {
        return SchemaReconciler.builder()
                .dataSource(ds)
                .namespace(namespace)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect())
                .policy(policy)
                .build();
    }

    /** Executes fixture DDL directly, outside the reconciler. */
    protected final void execute(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection();
                final var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    protected final String q(String identifier) {
        return dialect().quoteIdentifier(identifier);
    }

    protected final String qualified(String name) {
        return q(namespace) + "." + q(name);
    }

    protected final SchemaSnapshot snapshot() throws SQLException {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                return dialect().readSnapshot(c, namespace);
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        }
    }

    protected final boolean present(ObjectId id) throws SQLException {
        return snapshot().objects().containsKey(id);
    }

    protected final void assumeKind(ObjectKind kind) {
        assumeTrue(dialect().capabilities().supportedKinds().contains(kind),
                () -> "skipped: kind " + kind + " not in supportedKinds");
    }

    /** Statements executed on the target namespace — DDL outside any scratch window. */
    protected final List<String> targetDdl(TrackingDataSource ds) {
        final var out = new ArrayList<String>();
        var scratch = false;
        for (final var sql : ds.executedSql()) {
            if (isScratchBoundary(sql)) {
                scratch = true;
                continue;
            }
            if (isScratchEnd(sql)) {
                scratch = false;
                continue;
            }
            if (scratch) {
                continue;
            }
            final var upper = sql.stripLeading().toUpperCase(Locale.ROOT);
            if (upper.startsWith("CREATE ") || upper.startsWith("DROP ")
                    || upper.startsWith("ALTER ") || upper.startsWith("INSERT ")
                    || upper.startsWith("UPDATE ") || upper.startsWith("DELETE ")) {
                out.add(sql);
            }
        }
        return out;
    }

    private static ObjectId table(String name) {
        return new ObjectId(ObjectKind.TABLE, name);
    }

    private static ObjectId view(String name) {
        return new ObjectId(ObjectKind.VIEW, name);
    }

    private static ObjectId index(String parent, String name) {
        return new ObjectId(ObjectKind.INDEX, name, parent);
    }

    private static ObjectId column(String parent, String name) {
        return new ObjectId(ObjectKind.COLUMN, name, parent);
    }

    private static ObjectId routine(String name, String args) {
        return new ObjectId(ObjectKind.ROUTINE, name + "(" + args + ")");
    }

    private static ObjectId trigger(String parent, String name) {
        return new ObjectId(ObjectKind.TRIGGER, name, parent);
    }

    // ---- C-01 … C-32 ---------------------------------------------------------

    @Test
    @DisplayName("C-01 empty target reconciles; second run is UP_TO_DATE")
    void c01EmptyTarget() throws Exception {
        final var script = """
                CREATE TABLE users (id bigint NOT NULL, name text NOT NULL);
                CREATE TABLE plans (id bigint NOT NULL, owner bigint NOT NULL)
                """;
        final var first = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, first.outcome());
        assertTrue(present(table("users")));
        assertTrue(present(new ObjectId(ObjectKind.COLUMN, "id", "users")));
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
    }

    @Test
    @DisplayName("C-02 matching target: UP_TO_DATE, no statements on the target")
    void c02AlreadyMatching() throws Exception {
        final var script = "CREATE TABLE users (id bigint NOT NULL, name text)";
        reconciler(script).reconcile();

        final var tracking = new TrackingDataSource(dataSource());
        final var report = reconcilerOn(tracking, script, ReconcilePolicy.defaults()).reconcile();

        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
        assertTrue(report.plan().changes().isEmpty());
        assertTrue(targetDdl(tracking).isEmpty(),
                "no DDL should run on the target: " + targetDdl(tracking));
    }

    @Test
    @DisplayName("C-03 dropped index is restored as ADDITIVE")
    void c03DroppedIndex() throws Exception {
        assumeKind(ObjectKind.INDEX);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE INDEX users_name_idx ON users (name)
                """;
        reconciler(script).reconcile();
        execute("DROP INDEX " + qualified("users_name_idx"));

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(present(index("users", "users_name_idx")));
    }

    @Test
    @DisplayName("C-04 drifted index is rebuilt")
    void c04DriftedIndex() throws Exception {
        assumeKind(ObjectKind.INDEX);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE INDEX users_name_idx ON users (name)
                """;
        reconciler(script).reconcile();
        execute("DROP INDEX " + qualified("users_name_idx"));
        execute("CREATE INDEX users_name_idx ON " + qualified("users") + " (id)");

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.plan().changes().stream()
                .anyMatch(c -> c.action() == ChangeAction.DROP
                        && c.target().kind() == ObjectKind.INDEX));
    }

    @Test
    @DisplayName("C-05 drifted expression index is rebuilt")
    void c05ExpressionIndex() throws Exception {
        assumeKind(ObjectKind.INDEX);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE INDEX users_expr_idx ON users ((lower(name)))
                """;
        reconciler(script).reconcile();
        execute("DROP INDEX " + qualified("users_expr_idx"));
        execute("CREATE INDEX users_expr_idx ON " + qualified("users") + " ((upper(name)))");

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(present(index("users", "users_expr_idx")));
    }

    @Test
    @DisplayName("C-06 dropped unique constraint is restored")
    void c06DroppedUnique() throws Exception {
        assumeKind(ObjectKind.UNIQUE);
        final var script = """
                CREATE TABLE users (id bigint, name text, CONSTRAINT users_name_uk UNIQUE (name))
                """;
        reconciler(script).reconcile();
        execute("ALTER TABLE " + qualified("users") + " DROP CONSTRAINT users_name_uk");

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(present(new ObjectId(ObjectKind.UNIQUE, "users_name_uk", "users")));
    }

    @Test
    @DisplayName("C-07 drifted constraint is rebuilt")
    void c07DriftedConstraint() throws Exception {
        assumeKind(ObjectKind.UNIQUE);
        final var script = """
                CREATE TABLE users (id bigint, name text, CONSTRAINT users_uk UNIQUE (name))
                """;
        reconciler(script).reconcile();
        execute("ALTER TABLE " + qualified("users") + " DROP CONSTRAINT users_uk");
        execute("ALTER TABLE " + qualified("users") + " ADD CONSTRAINT users_uk UNIQUE (id)");

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.plan().changes().stream()
                .anyMatch(c -> c.action() == ChangeAction.DROP
                        && c.target().kind() == ObjectKind.UNIQUE));
    }

    @Test
    @DisplayName("C-08 dropped foreign key is restored")
    void c08DroppedForeignKey() throws Exception {
        assumeKind(ObjectKind.FOREIGN_KEY);
        final var script = """
                CREATE TABLE users (id bigint PRIMARY KEY);
                CREATE TABLE plans (id bigint PRIMARY KEY, owner bigint,
                    CONSTRAINT plans_owner_fk FOREIGN KEY (owner) REFERENCES users (id))
                """;
        reconciler(script).reconcile();
        execute("ALTER TABLE " + qualified("plans") + " DROP CONSTRAINT plans_owner_fk");

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(present(new ObjectId(ObjectKind.FOREIGN_KEY, "plans_owner_fk", "plans")));
    }

    @Test
    @DisplayName("C-09 drifted view recreated with its dependents")
    void c09ViewFewerColumns() throws Exception {
        assumeKind(ObjectKind.VIEW);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id, name FROM users;
                CREATE VIEW v_outer AS SELECT id FROM v_users
                """;
        reconciler(script).reconcile();
        // CREATE OR REPLACE VIEW cannot drop columns — drift by adding one
        execute("CREATE OR REPLACE VIEW " + qualified("v_users")
                + " AS SELECT id, name, 1 AS extra FROM " + qualified("users"));

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var drops = report.plan().changes().stream()
                .filter(c -> c.action() == ChangeAction.DROP).toList();
        assertTrue(drops.stream().anyMatch(c -> c.target().equals(view("v_users"))));
        assertTrue(drops.stream().anyMatch(c -> c.target().equals(view("v_outer"))));
    }

    @Test
    @DisplayName("C-10 changed routine body rebuilds; dependents survive")
    void c10RoutineBodyChanged() throws Exception {
        assumeKind(ObjectKind.ROUTINE);
        assumeKind(ObjectKind.TRIGGER);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE FUNCTION bump() RETURNS trigger LANGUAGE plpgsql
                    AS $$ BEGIN RETURN NEW; END $$;
                CREATE TRIGGER users_bump BEFORE INSERT ON users
                    FOR EACH ROW EXECUTE FUNCTION bump()
                """;
        reconciler(script).reconcile();
        execute("CREATE OR REPLACE FUNCTION " + qualified("bump")
                + "() RETURNS trigger LANGUAGE plpgsql "
                + "AS $$ BEGIN NEW.name := coalesce(NEW.name, ''); RETURN NEW; END $$");

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(present(routine("bump", "")));
        assertTrue(present(trigger("users", "users_bump")));
    }

    @Test
    @DisplayName("C-11 dropped trigger is restored")
    void c11DroppedTrigger() throws Exception {
        assumeKind(ObjectKind.TRIGGER);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE FUNCTION bump() RETURNS trigger LANGUAGE plpgsql
                    AS $$ BEGIN RETURN NEW; END $$;
                CREATE TRIGGER users_bump BEFORE INSERT ON users
                    FOR EACH ROW EXECUTE FUNCTION bump()
                """;
        reconciler(script).reconcile();
        execute("DROP TRIGGER users_bump ON " + qualified("users"));

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(present(trigger("users", "users_bump")));
    }

    @Test
    @DisplayName("C-12 foreign table shape is DESTRUCTIVE; default policy fails")
    void c12ForeignTableShape() throws Exception {
        final var script = "CREATE TABLE users (id bigint NOT NULL)";
        reconciler(script).reconcile();
        execute("ALTER TABLE " + qualified("users") + " ADD COLUMN extra text");
        execute("INSERT INTO " + qualified("users") + " (id, extra) VALUES (1, 'x')");

        assertThrows(PolicyViolationException.class, () -> reconciler(script).reconcile());
        try (Connection c = dataSource().getConnection();
                final var s = c.createStatement();
                var rs = s.executeQuery("SELECT count(*) FROM " + qualified("users"))) {
            rs.next();
            assertEquals(1, rs.getInt(1), "the row must be untouched");
        }
        assertTrue(present(new ObjectId(ObjectKind.COLUMN, "extra", "users")));
    }

    @Test
    @DisplayName("C-13 unexpected object reported, untouched")
    void c13UnexpectedObject() throws Exception {
        final var script = "CREATE TABLE users (id bigint)";
        reconciler(script).reconcile();
        execute("CREATE TABLE " + qualified("stray") + " (id bigint)");
        execute("INSERT INTO " + qualified("stray") + " (id) VALUES (7)");

        final var report = reconciler(script).reconcile();
        assertTrue(report.unexpected().stream()
                .anyMatch(d -> d.id().equals(table("stray"))));
        try (Connection c = dataSource().getConnection();
                final var s = c.createStatement();
                final var rs = s.executeQuery("SELECT count(*) FROM " + qualified("stray"))) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("C-14 unsupported object kind: UNSUPPORTED diff, default policy fails")
    void c14UnsupportedObject() throws Exception {
        final var unsupported = unsupportedScript();
        assumeTrue(unsupported != null, "skipped: dialect declares every kind it can detect");
        final var script = unsupported.script();

        assertThrows(PolicyViolationException.class, () -> reconciler(script).reconcile());
        final var report = reconciler(script,
                ReconcilePolicy.defaults().withUnsupported(Decision.REPORT)).reconcile();
        assertTrue(report.unsupported().stream()
                .anyMatch(d -> d.type() == DifferenceType.UNSUPPORTED));
    }

    @Test
    @DisplayName("C-15 syntax error: DesiredStateException, nothing left behind")
    void c15SyntaxError() throws Exception {
        assertThrows(DesiredStateException.class,
                () -> reconciler("THIS IS NOT VALID SQL;").reconcile());
        assertNoScratchNamespacesLeft();
    }

    @Test
    @DisplayName("C-16 concurrent reconcile() calls serialise")
    void c16ConcurrentReconcile() throws Exception {
        final var script = "CREATE TABLE users (id bigint)";
        final var pool = Executors.newFixedThreadPool(2);
        try {
            final var ready = new CountDownLatch(2);
            Callable<ReconcileReport> run = () -> {
                ready.countDown();
                ready.await(10, TimeUnit.SECONDS);
                return reconciler(script).reconcile();
            };
            final var fa = pool.submit(run);
            final var fb = pool.submit(run);
            final var a = fa.get(120, TimeUnit.SECONDS);
            final var b = fb.get(120, TimeUnit.SECONDS);
            assertNotNull(a);
            assertNotNull(b);
            assertTrue(a.outcome() == ReconcileOutcome.UP_TO_DATE
                    || b.outcome() == ReconcileOutcome.UP_TO_DATE,
                    "one run must observe the other's work");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("C-17 apply() on a changed target throws StalePlanException")
    void c17StalePlan() throws Exception {
        final var script = "CREATE TABLE users (id bigint)";
        final var r = reconciler(script);
        final var plan = r.plan();
        execute("CREATE TABLE " + qualified("stray") + " (id bigint)");
        assertThrows(StalePlanException.class, () -> r.apply(plan));
    }

    @Test
    @DisplayName("C-18 reportOnly applies nothing")
    void c18ReportOnly() throws Exception {
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE INDEX users_name_idx ON users (name)
                """;
        reconciler("CREATE TABLE users (id bigint, name text)").reconcile();
        final var tracking = new TrackingDataSource(dataSource());

        final var report = reconcilerOn(tracking, script, ReconcilePolicy.reportOnly()).reconcile();
        assertEquals(ReconcileOutcome.REPORTED_ONLY, report.outcome());
        assertTrue(report.applied().isEmpty());
        assertFalse(report.reported().isEmpty());
        assertTrue(targetDdl(tracking).isEmpty());
    }

    @Test
    @DisplayName("C-19 identical input produces identical plans")
    void c19DeterministicPlans() throws Exception {
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id FROM users;
                CREATE INDEX users_name_idx ON users (name)
                """;
        final var one = reconciler(script).plan().toSql();
        final var two = reconciler(script).plan().toSql();
        assertEquals(one, two);
    }

    @Test
    @DisplayName("C-20 lock timeout")
    void c20LockTimeout() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            dialect().acquireLock(c, namespace, Duration.ofSeconds(10));
            try {
                final var r = SchemaReconciler.builder()
                        .dataSource(dataSource())
                        .namespace(namespace)
                        .desiredState(DesiredState.ofScript("CREATE TABLE t (id bigint)"))
                        .dialect(dialect())
                        .lockTimeout(Duration.ofMillis(300))
                        .build();
                assertThrows(LockTimeoutException.class, r::reconcile);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("C-21 pooled connection is clean after a run")
    void c21CleanSession() throws Exception {
        reconciler("CREATE TABLE t (id bigint)").reconcile();
        assertCleanSession();
    }

    @Test
    @DisplayName("C-22 plan() changes nothing")
    void c22PlanOnly() throws Exception {
        final var script = "CREATE TABLE users (id bigint, name text)";
        reconciler("CREATE TABLE users (id bigint)").reconcile();
        final var before = snapshot();

        final var plan = reconciler(script).plan();
        assertFalse(plan.changes().isEmpty());

        assertEquals(before.fingerprint(), snapshot().fingerprint(),
                "plan() must not modify the target");
        assertNoScratchNamespacesLeft();
        assertCleanSession();
    }

    @Test
    @DisplayName("C-23 self and mutual foreign keys reconcile")
    void c23SelfAndMutualForeignKeys() throws Exception {
        assumeKind(ObjectKind.FOREIGN_KEY);
        final var script = """
                CREATE TABLE selfref (id bigint PRIMARY KEY,
                    parent bigint REFERENCES selfref (id));
                CREATE TABLE a_mut (id bigint PRIMARY KEY, b_id bigint);
                CREATE TABLE b_mut (id bigint PRIMARY KEY, a_id bigint);
                ALTER TABLE a_mut ADD CONSTRAINT a_b_fk FOREIGN KEY (b_id) REFERENCES b_mut (id);
                ALTER TABLE b_mut ADD CONSTRAINT b_a_fk FOREIGN KEY (a_id) REFERENCES a_mut (id)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
    }

    @Test
    @DisplayName("C-24 apply() rejects a foreign plan")
    void c24ForeignPlan() throws Exception {
        final var a = reconciler("CREATE TABLE t (id bigint)");
        final var plan = a.plan();

        final var b = SchemaReconciler.builder()
                .dataSource(dataSource())
                .namespace(namespace + "_other")
                .desiredState(DesiredState.ofScript("CREATE TABLE t (id bigint)"))
                .dialect(dialect())
                .build();
        assertThrows(IllegalArgumentException.class, () -> b.apply(plan));

        final var wrongDialect = new ReconcilePlan(plan.targetNamespace(), "other-dialect",
                plan.actualFingerprint(), plan.desiredFingerprint(),
                plan.differences(), plan.changes());
        assertThrows(IllegalArgumentException.class, () -> a.apply(wrongDialect));
    }

    @Test
    @DisplayName("C-25 unexpected dependent blocks planning")
    void c25UnexpectedDependent() throws Exception {
        assumeKind(ObjectKind.VIEW);
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id, name FROM users
                """;
        reconciler("CREATE TABLE users (id bigint, name text);"
                + " CREATE VIEW v_users AS SELECT id FROM users").reconcile();
        // an unexpected view inside the namespace depending on v_users
        execute("CREATE VIEW " + qualified("stray_view")
                + " AS SELECT id FROM " + qualified("v_users"));

        final var e = assertThrows(PlanningException.class,
                () -> reconciler(script).reconcile());
        assertTrue(e.getMessage().contains("stray_view"), e.getMessage());
        assertTrue(present(view("stray_view")), "the unexpected view must survive");
    }

    @Test
    @DisplayName("C-26 scratchDataSource confines scratch work")
    void c26ScratchDataSource() throws Exception {
        final var script = "CREATE TABLE users (id bigint)";
        final var report = SchemaReconciler.builder()
                .dataSource(dataSource())
                .scratchDataSource(scratchDataSource())
                .namespace(namespace)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect())
                .build().reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertNoScratchNamespacesLeft();
    }

    @Test
    @DisplayName("C-27 demoted change names its blocking object")
    void c27PrerequisiteDemotion() throws Exception {
        assumeKind(ObjectKind.VIEW);
        reconciler("""
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id FROM users
                """).reconcile();
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id, name FROM users;
                CREATE VIEW w_users AS SELECT id FROM v_users
                """;
        final var policy = ReconcilePolicy.defaults()
                .withAdditive(Decision.APPLY).withRebuild(Decision.REPORT);

        final var report = reconciler(script, policy).reconcile();
        assertEquals(ReconcileOutcome.REPORTED_ONLY, report.outcome());
        assertTrue(report.reported().stream()
                .anyMatch(c -> c.target().equals(view("w_users"))
                        && c.reason().contains("v_users")),
                "w_users must be demoted naming v_users as blocker: " + report.reported());
        assertFalse(present(view("w_users")));
    }

    @Test
    @DisplayName("C-28 literal target namespace in the script is rejected")
    void c28LiteralTargetNamespace() throws Exception {
        final var script = "CREATE TABLE " + qualified("users") + " (id bigint)";
        final var e = assertThrows(DesiredStateException.class,
                () -> reconciler(script).reconcile());
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
        assertFalse(present(table("users")));
    }

    @Test
    @DisplayName("C-29 unsupported REPORT demotes dependents")
    void c29UnsupportedDemotion() throws Exception {
        final var unsupported = unsupportedScript();
        assumeTrue(unsupported != null, "skipped: dialect declares every kind it can detect");
        final var script = unsupported.script() + "; CREATE TABLE independent_t (id bigint)";

        final var report = reconciler(script,
                ReconcilePolicy.defaults().withUnsupported(Decision.REPORT)).reconcile();

        assertTrue(report.unsupported().stream()
                .anyMatch(d -> d.type() == DifferenceType.UNSUPPORTED));
        assertTrue(report.reported().stream()
                .anyMatch(c -> c.target().name().equals(unsupported.dependentName())
                        || c.reason().contains(unsupported.dependentName())),
                "the supported dependent must be demoted to REPORT");
        assertTrue(present(table("independent_t")),
                "independent changes are still applied");
    }

    @Test
    @DisplayName("C-30 drop/create order: DROP w, DROP v, CREATE v, CREATE w")
    void c30ExactOrdering() throws Exception {
        assumeKind(ObjectKind.VIEW);
        reconciler("""
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id FROM users;
                CREATE VIEW w_users AS SELECT id FROM v_users
                """).reconcile();
        final var script = """
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id, name FROM users;
                CREATE VIEW w_users AS SELECT id FROM v_users
                """;

        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var changes = report.plan().changes().stream()
                .filter(c -> c.target().kind() == ObjectKind.VIEW).toList();
        assertEquals(
                List.of("w_users DROP", "v_users DROP", "v_users CREATE", "w_users CREATE"),
                changes.stream()
                        .map(c -> c.target().name() + " " + c.action()).toList());
    }

    @Test
    @DisplayName("C-31 name-scope collision fails planning")
    void c31NameCollision() throws Exception {
        assumeKind(ObjectKind.INDEX);
        final var idx = index("a_tbl", "shared_idx");
        assumeTrue(dialect().nameScopes(idx).stream()
                        .anyMatch(s -> s.startsWith("relation:")),
                "skipped: dialect does not declare relation-scoped index names");
        reconciler("""
                CREATE TABLE b_tbl (id bigint, name text);
                CREATE INDEX shared_idx ON b_tbl (name)
                """).reconcile();
        // the desired index shares the name of the unexpected index on b_tbl
        final var collision = """
                CREATE TABLE a_tbl (id bigint, name text);
                CREATE INDEX shared_idx ON a_tbl (id)
                """;
        final var e = assertThrows(PlanningException.class,
                () -> reconciler(collision).reconcile());
        assertTrue(e.getMessage().contains("shared_idx"), e.getMessage());
        assertTrue(present(index("b_tbl", "shared_idx")));
    }

    @Test
    @DisplayName("C-32 identity and serial columns reconcile without cycles")
    void c32IdentityAndSerial() throws Exception {
        final var identity = identityColumnDdl();
        final var serial = serialColumnDdl();
        assumeTrue(identity != null || serial != null,
                "skipped: dialect has no identity/serial column form");
        final var cols = new StringBuilder();
        if (identity != null) {
            cols.append("ident ").append(identity);
        }
        if (serial != null) {
            if (cols.length() > 0) {
                cols.append(", ");
            }
            cols.append("ser ").append(serial);
        }
        final var script = "CREATE TABLE ids (" + cols + ")";
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
    }

    @Test
    @DisplayName("C-33 column default and DROP NOT NULL alter; SET NOT NULL fails closed")
    void c33ColumnDefaultAlter() throws Exception {
        assumeKind(ObjectKind.COLUMN);
        assumeTrue(columnAlterSupported(),
                "skipped: dialect renders column drift as manual changes");
        reconciler("CREATE TABLE t (id bigint, note text DEFAULT 'a', req text NOT NULL)")
                .reconcile();
        execute("INSERT INTO " + qualified("t") + " VALUES (1, NULL, 'x'), (2, 'y', 'z')");
        // both safe directions apply in place: default change and DROP NOT NULL
        final var report = reconciler(
                "CREATE TABLE t (id bigint, note text DEFAULT 'b', req text)").reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        for (final var col : List.of(column("t", "note"), column("t", "req"))) {
            assertTrue(report.applied().stream()
                    .anyMatch(c -> c.target().equals(col)
                            && c.action() == ChangeAction.ALTER),
                    "expected an applied ALTER for " + col + ": " + report.applied());
        }
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(
                "CREATE TABLE t (id bigint, note text DEFAULT 'b', req text)")
                .reconcile().outcome());
        // the reverse direction — SET NOT NULL — can fail on the NULL row held
        // by 'note': it renders DESTRUCTIVE and stays manual under the default
        // policy instead of being silently applied
        assertThrows(PolicyViolationException.class, () -> reconciler(
                "CREATE TABLE t (id bigint, note text NOT NULL, req text)").reconcile());
    }

    @Test
    @DisplayName("C-34 unsafe column type change stays destructive")
    void c34UnsafeColumnType() throws Exception {
        assumeKind(ObjectKind.COLUMN);
        reconciler("CREATE TABLE t (id bigint, payload text)").reconcile();
        assertThrows(PolicyViolationException.class, () -> reconciler(
                "CREATE TABLE t (id bigint, payload boolean)").reconcile());
        assertTrue(snapshot().objects().get(column("t", "payload"))
                        .definition().canonical().contains("type=text"),
                "the column type must be unchanged after the rejected plan");
    }

    @Test
    @DisplayName("C-35 unsupported category override reports while the rest fails")
    void c35UnsupportedCategory() throws Exception {
        final var unsupported = unsupportedScript();
        final var category = unsupportedCategory();
        assumeTrue(unsupported != null && category != null,
                "skipped: dialect declares every kind it can detect");
        final var script = unsupported.script()
                + "; CREATE TABLE independent_t (id bigint)";
        final var report = reconciler(script, ReconcilePolicy.defaults()
                .withUnsupportedCategory(category, Decision.REPORT)).reconcile();
        assertTrue(report.unsupported().stream()
                .anyMatch(d -> d.type() == DifferenceType.UNSUPPORTED));
        assertTrue(present(table("independent_t")),
                "independent changes are still applied");
        // a category that does not match still falls back to the global FAIL
        assertThrows(PolicyViolationException.class, () -> reconciler(script,
                ReconcilePolicy.defaults()
                        .withUnsupportedCategory("no-match", Decision.REPORT)).reconcile());
    }

    @Test
    @DisplayName("C-36 the report carries phase timings")
    void c36RunTimings() throws Exception {
        final var report = reconciler("CREATE TABLE t (id bigint)").reconcile();
        final var timings = report.timings();
        assertNotNull(timings);
        for (final var d : List.of(timings.lockWait(), timings.readActual(),
                timings.loadDesired(), timings.plan(), timings.apply(), timings.verify())) {
            assertNotNull(d);
            assertFalse(d.isNegative());
        }
        assertFalse(report.elapsed().isNegative());
    }
}
