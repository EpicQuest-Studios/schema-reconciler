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
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * The dialect conformance suite of spec 9.2 — scenarios C-01 to C-67. A dialect
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

    /**
     * A {@code CREATE EXTENSION} statement for an extension the dialect can
     * install inside the test namespace (C-45), or {@code null} to skip.
     * The statement must not already be installed database-wide — an
     * {@code IF NOT EXISTS} clause then no-ops and no object materializes.
     */
    protected String extensionDdl() {
        return null;
    }

    /**
     * DDL that creates a foreign server named {@code tck_srv} usable from any
     * namespace (C-55), or {@code null} to skip. The implementation may do its
     * own setup (install the FDW, clean a stale server) before returning the
     * statement; the statement itself is run on the target connection.
     */
    protected String foreignServerDdl() throws Exception {
        return null;
    }

    /** Cleanup for {@link #foreignServerDdl()} — drops server {@code tck_srv}; {@code null} skips. */
    protected String foreignServerTeardownDdl() throws Exception {
        return null;
    }

    /**
     * DDL that creates an empty publication named {@code tck_pub} (C-56), or
     * {@code null} to skip. The publication is a cluster-level object — the
     * desired script binds to it with {@code ALTER PUBLICATION}; it is never
     * created or dropped by a reconciler plan.
     */
    protected String publicationDdl() throws Exception {
        return null;
    }

    /** Cleanup for {@link #publicationDdl()} — drops publication {@code tck_pub}; {@code null} skips. */
    protected String publicationTeardownDdl() throws Exception {
        return null;
    }

    /**
     * DDL that creates the cluster roles {@code tck_owner} and
     * {@code tck_owner2} and grants the connecting role membership in both
     * (C-57 — {@code ALTER ... OWNER TO} needs it), or {@code null} to skip.
     * Roles are cluster-level objects (4.1): the desired script references
     * them; it never creates them.
     */
    protected String ownershipRoleDdl() throws Exception {
        return null;
    }

    /** Cleanup for {@link #ownershipRoleDdl()} — drops the {@code tck_owner*} roles; {@code null} skips. */
    protected String ownershipRoleTeardownDdl() throws Exception {
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
        try {
            assertNoScratchNamespacesLeft();
        } finally {
            if (namespace != null) {
                dropNamespace(namespace);
            }
        }
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
        for (final var executed : ds.statements()) {
            final var sql = executed.sql();
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

    private static ObjectId matview(String name) {
        return new ObjectId(ObjectKind.MATERIALIZED_VIEW, name);
    }

    private static ObjectId type(String name) {
        return new ObjectId(ObjectKind.TYPE, name);
    }

    private static ObjectId domain(String name) {
        return new ObjectId(ObjectKind.DOMAIN, name);
    }

    private static ObjectId policy(String parent, String name) {
        return new ObjectId(ObjectKind.POLICY, name, parent);
    }

    private static ObjectId exclusion(String parent, String name) {
        return new ObjectId(ObjectKind.EXCLUSION, name, parent);
    }

    private static ObjectId statistics(String name) {
        return new ObjectId(ObjectKind.STATISTICS, name);
    }

    private static ObjectId rule(String parent, String name) {
        return new ObjectId(ObjectKind.RULE, name, parent);
    }

    private static ObjectId collation(String name) {
        return new ObjectId(ObjectKind.COLLATION, name);
    }

    private static ObjectId conversion(String name) {
        return new ObjectId(ObjectKind.CONVERSION, name);
    }

    private static ObjectId partition(String parent, String name) {
        return new ObjectId(ObjectKind.PARTITION, name, parent);
    }

    private static ObjectId foreignTable(String name) {
        return new ObjectId(ObjectKind.FOREIGN_TABLE, name);
    }

    private static ObjectId publication(String pub, String rel) {
        return new ObjectId(ObjectKind.PUBLICATION, pub, rel);
    }

    private static ObjectId ownership(String target) {
        return new ObjectId(ObjectKind.OWNERSHIP, target);
    }

    private static ObjectId privileges(String target) {
        return new ObjectId(ObjectKind.PRIVILEGES, target);
    }

    private static ObjectId cast(String name) {
        return new ObjectId(ObjectKind.CAST, name);
    }

    /** The row count of a relation in the test namespace — proves a
     *  materialized view actually holds its stored result rows. */
    protected final long count(String relation) throws SQLException {
        try (Connection c = dataSource().getConnection();
                final var s = c.createStatement();
                final var rs = s.executeQuery(
                        "SELECT count(*) FROM " + qualified(relation))) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static ObjectId routine(String name, String args) {
        return new ObjectId(ObjectKind.ROUTINE, name + "(" + args + ")");
    }

    private static ObjectId trigger(String parent, String name) {
        return new ObjectId(ObjectKind.TRIGGER, name, parent);
    }

    // ---- C-01 … C-48 ---------------------------------------------------------

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

    @Test
    @DisplayName("C-37 materialized view missing: ADDITIVE create, populated")
    void c37MaterializedViewMissing() throws Exception {
        assumeKind(ObjectKind.MATERIALIZED_VIEW);
        reconciler("CREATE TABLE t (id bigint)").reconcile();
        execute("INSERT INTO " + qualified("t") + " VALUES (1), (2)");
        final var script = """
                CREATE TABLE t (id bigint);
                CREATE MATERIALIZED VIEW mv AS SELECT id FROM t
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(matview("mv")) && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the materialized view: " + report.applied());
        // the view must hold its stored rows, not just exist
        assertEquals(2L, count("mv"));
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
    }

    @Test
    @DisplayName("C-38 materialized view drifted: DESTRUCTIVE drop+create+refresh")
    void c38MaterializedViewDrifted() throws Exception {
        assumeKind(ObjectKind.MATERIALIZED_VIEW);
        reconciler("""
                CREATE TABLE t (id bigint);
                CREATE MATERIALIZED VIEW mv AS SELECT id FROM t
                """).reconcile();
        execute("INSERT INTO " + qualified("t") + " VALUES (1), (2)");
        execute("REFRESH MATERIALIZED VIEW " + qualified("mv"));
        // a stored-data recreate is DESTRUCTIVE: the default policy refuses it
        assertThrows(PolicyViolationException.class, () -> reconciler(
                "CREATE TABLE t (id bigint);"
                        + " CREATE MATERIALIZED VIEW mv AS SELECT id, id + 1 AS plus FROM t")
                .reconcile());
        final var report = reconciler("""
                CREATE TABLE t (id bigint);
                CREATE MATERIALIZED VIEW mv AS SELECT id, id + 1 AS plus FROM t
                """, ReconcilePolicy.defaults().withDestructive(Decision.APPLY)).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(matview("mv")) && c.safety() == ChangeSafety.DESTRUCTIVE),
                "expected DESTRUCTIVE changes for the materialized view: " + report.applied());
        assertEquals(2L, count("mv"));
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler("""
                CREATE TABLE t (id bigint);
                CREATE MATERIALIZED VIEW mv AS SELECT id, id + 1 AS plus FROM t
                """, ReconcilePolicy.defaults().withDestructive(Decision.APPLY)).reconcile().outcome());
    }

    @Test
    @DisplayName("C-39 enum missing: ADDITIVE create; an appended label is ALTER TYPE ADD VALUE")
    void c39EnumMissingThenAppend() throws Exception {
        assumeKind(ObjectKind.TYPE);
        final var script = "CREATE TYPE mood AS ENUM ('sad', 'ok')";
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(type("mood")) && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the enum: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a pure in-order append renders ALTER TYPE ... ADD VALUE
        final var widened = "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')";
        final var grown = reconciler(widened).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, grown.outcome());
        assertTrue(grown.applied().stream().anyMatch(c ->
                        c.target().equals(type("mood")) && c.safety() == ChangeSafety.ADDITIVE
                                && c.statements().stream()
                                        .anyMatch(s -> s.contains("ADD VALUE"))),
                "expected an ADDITIVE ADD VALUE change: " + grown.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(widened).reconcile().outcome());
    }

    @Test
    @DisplayName("C-40 enum removed or reordered labels stay a manual change")
    void c40EnumLabelRemoved() throws Exception {
        assumeKind(ObjectKind.TYPE);
        reconciler("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')").reconcile();
        // a removed label cannot be altered in place — fail closed
        assertThrows(PolicyViolationException.class, () -> reconciler(
                "CREATE TYPE mood AS ENUM ('sad', 'ok')").reconcile());
        // a reordered label set fails the same way
        assertThrows(PolicyViolationException.class, () -> reconciler(
                "CREATE TYPE mood AS ENUM ('ok', 'sad', 'happy')").reconcile());
        // the type is untouched: the original state still verifies UP_TO_DATE
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')")
                        .reconcile().outcome());
    }

    @Test
    @DisplayName("C-41 domain missing: ADDITIVE create, constraints and nullability kept")
    void c41DomainMissing() throws Exception {
        assumeKind(ObjectKind.DOMAIN);
        final var script = """
                CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1 CHECK (VALUE > 0);
                CREATE TABLE t (a posint)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(domain("posint")) && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the domain: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // the domain behaves like its definition says: defaults fill, CHECK rejects
        execute("INSERT INTO " + qualified("t") + " DEFAULT VALUES");
        assertEquals(1L, count("t"));
    }

    @Test
    @DisplayName("C-42 domain drift on safe fields: REBUILD ALTER DOMAIN converges")
    void c42DomainSafeDrift() throws Exception {
        assumeKind(ObjectKind.DOMAIN);
        reconciler("CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1 CHECK (VALUE > 0)")
                .reconcile();
        // drop the default, drop NOT NULL, drop the check — all safe in place
        final var report = reconciler("CREATE DOMAIN posint AS integer").reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(domain("posint")) && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream()
                                        .anyMatch(s -> s.contains("ALTER DOMAIN"))),
                "expected a REBUILD ALTER DOMAIN: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler("CREATE DOMAIN posint AS integer").reconcile().outcome());
    }

    @Test
    @DisplayName("C-43 domain base-type change stays a manual change")
    void c43DomainBaseTypeChange() throws Exception {
        assumeKind(ObjectKind.DOMAIN);
        reconciler("CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1 CHECK (VALUE > 0)")
                .reconcile();
        // the base type cannot alter in place — fail closed
        assertThrows(PolicyViolationException.class, () -> reconciler(
                "CREATE DOMAIN posint AS bigint NOT NULL DEFAULT 1 CHECK (VALUE > 0)")
                .reconcile());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler("CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1"
                        + " CHECK (VALUE > 0)").reconcile().outcome());
    }

    @Test
    @DisplayName("C-44 policy missing: ADDITIVE; a changed USING is a REBUILD drop+create")
    void c44PolicyMissingAndDrifted() throws Exception {
        assumeKind(ObjectKind.POLICY);
        final var script = """
                CREATE TABLE t (id bigint, owner text);
                ALTER TABLE t ENABLE ROW LEVEL SECURITY;
                CREATE POLICY p_sel ON t FOR SELECT USING (owner = current_user)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(policy("t", "p_sel"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the policy: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a changed USING clause recreates the policy in place
        final var widened = """
                CREATE TABLE t (id bigint, owner text);
                ALTER TABLE t ENABLE ROW LEVEL SECURITY;
                CREATE POLICY p_sel ON t FOR SELECT USING (id > 0)
                """;
        final var drifted = reconciler(widened).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(policy("t", "p_sel"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD recreate for the policy: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(widened).reconcile().outcome());
    }

    @Test
    @DisplayName("C-45 extension missing: ADDITIVE create anchored in the namespace")
    void c45ExtensionMissing() throws Exception {
        assumeKind(ObjectKind.EXTENSION);
        final var ddl = extensionDdl();
        assumeTrue(ddl != null, "no installable extension for this dialect");
        final var report = reconciler(ddl).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var extensions = report.applied().stream()
                .filter(c -> c.target().kind() == ObjectKind.EXTENSION).toList();
        assertEquals(1, extensions.size(),
                "expected exactly one extension change: " + report.applied());
        assertEquals(ChangeSafety.ADDITIVE, extensions.get(0).safety());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(ddl).reconcile().outcome());
    }

    @Test
    @DisplayName("C-46 comment missing/drifted: ALTER REBUILD")
    void c46CommentMissingAndDrifted() throws Exception {
        assumeKind(ObjectKind.COMMENT);
        final var script = """
                CREATE TABLE t (id bigint);
                COMMENT ON TABLE t IS 'kept table'
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.COMMENT
                                && c.action() == ChangeAction.ALTER
                                && c.safety() == ChangeSafety.REBUILD),
                "expected an ALTER/REBUILD comment change: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // a changed comment rewrites in place
        final var drifted = reconciler("""
                CREATE TABLE t (id bigint);
                COMMENT ON TABLE t IS 'renamed table'
                """).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.COMMENT
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD comment change: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler("""
                CREATE TABLE t (id bigint);
                COMMENT ON TABLE t IS 'renamed table'
                """).reconcile().outcome());
    }

    @Test
    @DisplayName("C-47 unexpected comment is reported, never dropped")
    void c47UnexpectedComment() throws Exception {
        assumeKind(ObjectKind.COMMENT);
        execute("CREATE TABLE " + qualified("t") + " (id bigint)");
        execute("COMMENT ON TABLE " + qualified("t") + " IS 'ambient'");
        final var report = reconciler("CREATE TABLE t (id bigint)").reconcile();
        assertEquals(ReconcileOutcome.UP_TO_DATE, report.outcome());
        assertTrue(report.unexpected().stream().anyMatch(d ->
                        d.id().kind() == ObjectKind.COMMENT),
                "expected the ambient comment reported: " + report.unexpected());
        // and it is still there afterwards
        try (var c = dataSource().getConnection();
                final var s = c.createStatement();
                final var rs = s.executeQuery(
                        "SELECT obj_description('" + qualified("t") + "'::regclass)")) {
            rs.next();
            assertEquals("ambient", rs.getString(1),
                    "the unexpected comment must survive reconcile");
        }
    }

    @Test
    @DisplayName("C-48 interlocked new kinds reconcile in dependency order")
    void c48InterlockedNewKinds() throws Exception {
        assumeKind(ObjectKind.DOMAIN);
        assumeKind(ObjectKind.MATERIALIZED_VIEW);
        assumeKind(ObjectKind.POLICY);
        assumeKind(ObjectKind.COMMENT);
        final var script = """
                CREATE DOMAIN posint AS integer CHECK (VALUE > 0);
                CREATE TABLE t (id posint, note text);
                ALTER TABLE t ENABLE ROW LEVEL SECURITY;
                CREATE POLICY p_sel ON t FOR SELECT USING (id > 0);
                COMMENT ON TABLE t IS 'governed table';
                CREATE MATERIALIZED VIEW mv AS SELECT id, note FROM t;
                CREATE INDEX mv_note ON mv (note)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        final var order = report.applied().stream()
                .map(c -> c.target().kind()).toList();
        final var domainAt = order.indexOf(ObjectKind.DOMAIN);
        final var tableAt = order.indexOf(ObjectKind.TABLE);
        final var policyAt = order.indexOf(ObjectKind.POLICY);
        final var matviewAt = order.indexOf(ObjectKind.MATERIALIZED_VIEW);
        final var indexAt = order.indexOf(ObjectKind.INDEX);
        final var commentAt = order.indexOf(ObjectKind.COMMENT);
        assertTrue(domainAt >= 0 && tableAt > domainAt,
                "domain must precede the table that uses it: " + order);
        assertTrue(policyAt > tableAt && matviewAt > tableAt
                && indexAt > matviewAt && commentAt > tableAt,
                "dependents must follow their prerequisite: " + order);
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
    }

    @Test
    @DisplayName("C-49 exclusion constraint missing: ADDITIVE; drifted: REBUILD drop+create")
    void c49ExclusionConstraint() throws Exception {
        assumeKind(ObjectKind.EXCLUSION);
        final var script = """
                CREATE TABLE t (id bigint, span int4range,
                    CONSTRAINT no_overlap EXCLUDE USING gist (span WITH &&))
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(exclusion("t", "no_overlap"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the exclusion constraint: "
                        + report.applied());
        // the backing index is part of the constraint, not a separate INDEX
        assertTrue(report.applied().stream()
                        .noneMatch(c -> c.target().kind() == ObjectKind.INDEX),
                "the exclusion's index must not surface as a separate change: "
                        + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a changed predicate drifts the constraint: drop + create in place
        final var widened = """
                CREATE TABLE t (id bigint, span int4range,
                    CONSTRAINT no_overlap EXCLUDE USING gist (span WITH &&) WHERE (id > 0))
                """;
        final var drifted = reconciler(widened).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(exclusion("t", "no_overlap"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD recreate for the exclusion: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(widened).reconcile().outcome());
    }

    @Test
    @DisplayName("C-50 statistics missing: ADDITIVE; drifted: REBUILD drop+create")
    void c50Statistics() throws Exception {
        assumeKind(ObjectKind.STATISTICS);
        final var script = """
                CREATE TABLE t (a int, b int);
                CREATE STATISTICS st ON a, b FROM t
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(statistics("st"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the statistics: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a changed statistic-kind list drifts the object: drop + create
        final var widened = """
                CREATE TABLE t (a int, b int);
                CREATE STATISTICS st (mcv) ON a, b FROM t
                """;
        final var drifted = reconciler(widened).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(statistics("st"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD recreate for the statistics: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(widened).reconcile().outcome());
    }

    @Test
    @DisplayName("C-51 rule missing: ADDITIVE; drifted: REBUILD drop+create")
    void c51Rule() throws Exception {
        assumeKind(ObjectKind.RULE);
        final var script = """
                CREATE TABLE t (id bigint);
                CREATE RULE rl AS ON INSERT TO t DO ALSO NOTHING
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(rule("t", "rl"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the rule: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a changed action drifts the rule: drop + create
        final var changed = """
                CREATE TABLE t (id bigint);
                CREATE RULE rl AS ON INSERT TO t DO INSTEAD NOTHING
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(rule("t", "rl"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD recreate for the rule: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-52 collation missing: ADDITIVE; drifted: REBUILD drop+create")
    void c52Collation() throws Exception {
        assumeKind(ObjectKind.COLLATION);
        final var script = """
                CREATE COLLATION coll (provider = libc, locale = 'C')
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(collation("coll"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the collation: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a changed locale drifts the collation: drop + create
        final var changed = """
                CREATE COLLATION coll (provider = libc, locale = 'POSIX')
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(collation("coll"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD recreate for the collation: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-53 conversion missing: ADDITIVE; drifted: REBUILD drop+create")
    void c53Conversion() throws Exception {
        assumeKind(ObjectKind.CONVERSION);
        final var script = """
                CREATE CONVERSION cv FOR 'LATIN1' TO 'UTF8' FROM iso8859_1_to_utf8
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(conversion("cv"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the conversion: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a changed encoding pair drifts the conversion: drop + create
        final var changed = """
                CREATE CONVERSION cv FOR 'UTF8' TO 'LATIN1' FROM utf8_to_iso8859_1
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(conversion("cv"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a REBUILD recreate for the conversion: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-54 partitioned parent and children create ADDITIVE;"
            + " bound drift: DESTRUCTIVE recreate")
    void c54Partitions() throws Exception {
        assumeKind(ObjectKind.PARTITION);
        final var script = """
                CREATE TABLE pt (k int) PARTITION BY RANGE (k);
                CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (100)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(table("pt"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the parent: " + report.applied());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(partition("pt", "pc"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the partition: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script).reconcile().outcome());
        // a moved bound re-homes rows: DESTRUCTIVE detach+attach recreate,
        // refused by the default policy
        final var changed = """
                CREATE TABLE pt (k int) PARTITION BY RANGE (k);
                CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (50)
                """;
        assertThrows(PolicyViolationException.class,
                () -> reconciler(changed).reconcile());
        final var drifted = reconciler(changed,
                ReconcilePolicy.defaults().withDestructive(Decision.APPLY))
                .reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(partition("pt", "pc"))
                                && c.safety() == ChangeSafety.DESTRUCTIVE),
                "expected a DESTRUCTIVE recreate for the partition: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-55 foreign table create: ADDITIVE; options drift: drop+create REBUILD")
    void c55ForeignTable() throws Exception {
        assumeKind(ObjectKind.FOREIGN_TABLE);
        final var server = foreignServerDdl();
        assumeTrue(server != null, "skipped: dialect provides no foreign server hook");
        execute(server);
        try {
            final var script = """
                    CREATE FOREIGN TABLE ft (a int) SERVER tck_srv OPTIONS (fetch_size '100')
                    """;
            final var report = reconciler(script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                            c.target().equals(foreignTable("ft"))
                                    && c.safety() == ChangeSafety.ADDITIVE),
                    "expected an ADDITIVE create for the foreign table: " + report.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(script).reconcile().outcome());
            // an options change carries no local data — plain drop+create
            final var changed = """
                    CREATE FOREIGN TABLE ft (a int) SERVER tck_srv OPTIONS (fetch_size '200')
                    """;
            final var drifted = reconciler(changed).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
            assertTrue(drifted.applied().stream().anyMatch(c ->
                            c.target().equals(foreignTable("ft"))
                                    && c.safety() == ChangeSafety.REBUILD),
                    "expected a REBUILD recreate for the foreign table: " + drifted.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(changed).reconcile().outcome());
        } finally {
            final var teardown = foreignServerTeardownDdl();
            if (teardown != null) {
                execute(teardown);
            }
        }
    }

    @Test
    @DisplayName("C-56 publication binding add: ADDITIVE; filter drift: drop+add REBUILD")
    void c56PublicationBinding() throws Exception {
        assumeKind(ObjectKind.PUBLICATION);
        final var pub = publicationDdl();
        assumeTrue(pub != null, "skipped: dialect provides no publication hook");
        execute(pub);
        try {
            final var script = """
                    CREATE TABLE t (a int);
                    ALTER PUBLICATION tck_pub ADD TABLE t
                    """;
            final var report = reconciler(script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                            c.target().equals(publication("tck_pub", "t"))
                                    && c.safety() == ChangeSafety.ADDITIVE),
                    "expected an ADDITIVE binding for the table: " + report.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(script).reconcile().outcome());
            // a row-filter change drops and re-adds the binding — REBUILD
            final var changed = """
                    CREATE TABLE t (a int);
                    ALTER PUBLICATION tck_pub ADD TABLE t WHERE (a > 0)
                    """;
            final var drifted = reconciler(changed).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
            assertTrue(drifted.applied().stream().anyMatch(c ->
                            c.target().equals(publication("tck_pub", "t"))
                                    && c.safety() == ChangeSafety.REBUILD),
                    "expected a REBUILD recreate for the binding: " + drifted.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(changed).reconcile().outcome());
        } finally {
            final var teardown = publicationTeardownDdl();
            if (teardown != null) {
                execute(teardown);
            }
        }
    }

    @Test
    @DisplayName("C-57 ownership assign: REBUILD; owner drift: in-place REBUILD")
    void c57Ownership() throws Exception {
        assumeKind(ObjectKind.OWNERSHIP);
        final var roles = ownershipRoleDdl();
        assumeTrue(roles != null, "skipped: dialect provides no ownership-role hook");
        execute(roles);
        try {
            final var script = """
                    CREATE TABLE t (a int);
                    ALTER TABLE t OWNER TO tck_owner
                    """;
            final var report = reconciler(script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                            c.target().equals(ownership("t"))
                                    && c.safety() == ChangeSafety.REBUILD),
                    "expected a REBUILD owner assignment for t: " + report.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(script).reconcile().outcome());
            // a different desired role re-assigns in place — REBUILD
            final var changed = """
                    CREATE TABLE t (a int);
                    ALTER TABLE t OWNER TO tck_owner2
                    """;
            final var drifted = reconciler(changed).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
            assertTrue(drifted.applied().stream().anyMatch(c ->
                            c.target().equals(ownership("t"))
                                    && c.safety() == ChangeSafety.REBUILD),
                    "expected a REBUILD owner change for t: " + drifted.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(changed).reconcile().outcome());
        } finally {
            final var teardown = ownershipRoleTeardownDdl();
            if (teardown != null) {
                execute(teardown);
            }
        }
    }

    @Test
    @DisplayName("C-58 privileges granted: REBUILD; grant drift: GRANT/REVOKE REBUILD")
    void c58Privileges() throws Exception {
        assumeKind(ObjectKind.PRIVILEGES);
        final var roles = ownershipRoleDdl();
        assumeTrue(roles != null, "skipped: dialect provides no grantee-role hook");
        execute(roles);
        try {
            final var script = """
                    CREATE TABLE t (a int);
                    GRANT SELECT ON t TO tck_owner
                    """;
            final var report = reconciler(script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                            c.target().equals(privileges("t"))
                                    && c.safety() == ChangeSafety.REBUILD
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith("GRANT SELECT"))),
                    "expected a REBUILD GRANT on t: " + report.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(script).reconcile().outcome());
            // a different desired grant converges the acl — revoke the old,
            // grant the new, in one REBUILD change
            final var changed = """
                    CREATE TABLE t (a int);
                    GRANT INSERT ON t TO tck_owner2
                    """;
            final var drifted = reconciler(changed).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
            assertTrue(drifted.applied().stream().anyMatch(c ->
                            c.target().equals(privileges("t"))
                                    && c.safety() == ChangeSafety.REBUILD
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith("REVOKE"))
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith("GRANT INSERT"))),
                    "expected a REBUILD GRANT+REVOKE on t: " + drifted.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(changed).reconcile().outcome());
        } finally {
            final var teardown = ownershipRoleTeardownDdl();
            if (teardown != null) {
                execute(teardown);
            }
        }
    }

    @Test
    @DisplayName("C-59 cast missing: ADDITIVE; cast context drift: drop+create REBUILD")
    void c59Cast() throws Exception {
        assumeKind(ObjectKind.CAST);
        final var script = """
                CREATE DOMAIN d AS integer CHECK (VALUE > 0);
                CREATE FUNCTION dtotext(d) RETURNS text
                    LANGUAGE sql IMMUTABLE RETURN $1::text;
                CREATE CAST (d AS text) WITH FUNCTION dtotext(d)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(cast("d->text"))
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE create for the cast: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // a context change drops and recreates the cast
        final var changed = """
                CREATE DOMAIN d AS integer CHECK (VALUE > 0);
                CREATE FUNCTION dtotext(d) RETURNS text
                    LANGUAGE sql IMMUTABLE RETURN $1::text;
                CREATE CAST (d AS text) WITH FUNCTION dtotext(d) AS IMPLICIT
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(cast("d->text"))
                                && c.safety() == ChangeSafety.REBUILD),
                "expected a drop+create REBUILD for the cast: " + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-60 composite type missing: ADDITIVE; attribute drift: drop+create REBUILD")
    void c60CompositeType() throws Exception {
        assumeKind(ObjectKind.TYPE);
        final var script = """
                CREATE TYPE cmp AS (a integer, b text)
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(type("cmp"))
                                && c.safety() == ChangeSafety.ADDITIVE
                                && c.statements().stream()
                                        .anyMatch(s -> s.startsWith("CREATE TYPE"))),
                "expected an ADDITIVE CREATE TYPE for the composite: "
                        + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // attribute drift drops and recreates the type — a composite still
        // referenced by a column blocks the drop like every other recreate
        final var changed = """
                CREATE TYPE cmp AS (a integer, b text, c numeric)
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(type("cmp"))
                                && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream()
                                        .anyMatch(s -> s.startsWith("DROP TYPE"))),
                "expected a drop+create REBUILD for the composite: "
                        + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-61 range type missing: ADDITIVE; pg_range drift: drop+create REBUILD")
    void c61RangeType() throws Exception {
        assumeKind(ObjectKind.TYPE);
        // the generated family — multirange, constructor routines and the
        // range→multirange cast — folds into the range: only the TYPE
        // object is created, and a routine calling the folded constructor
        // still orders after it
        final var script = """
                CREATE TYPE r AS RANGE (subtype = integer);
                CREATE FUNCTION mk(x r) RETURNS r_multirange
                    LANGUAGE sql IMMUTABLE AS 'SELECT r_multirange(x)'
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().equals(type("r"))
                                && c.safety() == ChangeSafety.ADDITIVE
                                && c.statements().stream()
                                        .anyMatch(s -> s.contains("AS RANGE"))),
                "expected an ADDITIVE CREATE TYPE ... AS RANGE: "
                        + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // the dependent routine would block DROP TYPE (no CASCADE) — the
        // drift phase recreates a bare range
        execute("DROP FUNCTION " + qualified("mk") + "(" + qualified("r") + ")");
        // a pg_range option change drops and recreates the type
        final var changed = """
                CREATE TYPE r AS RANGE (subtype = text, collation = "C")
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().equals(type("r"))
                                && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream()
                                        .anyMatch(s -> s.startsWith("DROP TYPE"))),
                "expected a drop+create REBUILD for the range: "
                        + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-62 aggregate missing: ADDITIVE; option drift: drop+create REBUILD")
    void c62Aggregate() throws Exception {
        assumeKind(ObjectKind.AGGREGATE);
        // pg_get_functiondef refuses aggregates — the canonical is the
        // pg_aggregate option list, so any option change recreates
        final var script = """
                CREATE FUNCTION tfn(state int, x int)
                    RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT state + x';
                CREATE AGGREGATE a1(int) (SFUNC = tfn, STYPE = int, INITCOND = '0')
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.AGGREGATE
                                && c.target().name().equals("a1(integer)")
                                && c.safety() == ChangeSafety.ADDITIVE
                                && c.statements().stream()
                                        .anyMatch(s -> s.startsWith("CREATE AGGREGATE"))),
                "expected an ADDITIVE CREATE AGGREGATE: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        final var changed = """
                CREATE FUNCTION tfn(state int, x int)
                    RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT state + x';
                CREATE AGGREGATE a1(int) (SFUNC = tfn, STYPE = int)
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.AGGREGATE
                                && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream()
                                        .anyMatch(s -> s.startsWith("DROP AGGREGATE"))),
                "expected a drop+create REBUILD for the aggregate: "
                        + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-63 column privilege granted: REBUILD; column grant drift:"
            + " GRANT/REVOKE REBUILD")
    void c63ColumnPrivileges() throws Exception {
        assumeKind(ObjectKind.PRIVILEGES);
        final var roles = ownershipRoleDdl();
        assumeTrue(roles != null, "skipped: dialect provides no grantee-role hook");
        execute(roles);
        final var columnGrant = new ObjectId(ObjectKind.PRIVILEGES, "a", "t");
        try {
            final var script = """
                    CREATE TABLE t (a int, b int);
                    GRANT SELECT (a) ON t TO tck_owner
                    """;
            final var report = reconciler(script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                            c.target().equals(columnGrant)
                                    && c.safety() == ChangeSafety.REBUILD
                                    && c.statements().stream()
                                            .anyMatch(s -> s.contains("SELECT"))),
                    "expected a REBUILD column GRANT on t.a: " + report.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(script).reconcile().outcome());
            // a different desired column grant converges the acl — revoke the
            // old, grant the new, in one REBUILD change
            final var changed = """
                    CREATE TABLE t (a int, b int);
                    GRANT UPDATE (a) ON t TO tck_owner
                    """;
            final var drifted = reconciler(changed).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
            assertTrue(drifted.applied().stream().anyMatch(c ->
                            c.target().equals(columnGrant)
                                    && c.safety() == ChangeSafety.REBUILD
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith("REVOKE"))
                                    && c.statements().stream()
                                            .anyMatch(s -> s.contains("UPDATE"))),
                    "expected a REBUILD column GRANT+REVOKE on t.a: "
                            + drifted.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(changed).reconcile().outcome());
        } finally {
            final var teardown = ownershipRoleTeardownDdl();
            if (teardown != null) {
                execute(teardown);
            }
        }
    }

    @Test
    @DisplayName("C-64 default privileges granted: REBUILD; drift:"
            + " GRANT/REVOKE REBUILD")
    void c64DefaultPrivileges() throws Exception {
        assumeKind(ObjectKind.DEFAULT_PRIVILEGES);
        final var roles = ownershipRoleDdl();
        assumeTrue(roles != null, "skipped: dialect provides no grantee-role hook");
        execute(roles);
        final var defPrivs = new ObjectId(ObjectKind.DEFAULT_PRIVILEGES,
                "TABLES for tck_owner");
        try {
            final var script = """
                    ALTER DEFAULT PRIVILEGES FOR ROLE tck_owner
                        IN SCHEMA ${namespace} GRANT SELECT ON TABLES TO tck_owner2
                    """;
            final var report = reconciler(script).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
            assertTrue(report.applied().stream().anyMatch(c ->
                            c.target().equals(defPrivs)
                                    && c.safety() == ChangeSafety.REBUILD
                                    && c.statements().stream()
                                            .anyMatch(s -> s.startsWith(
                                                    "ALTER DEFAULT PRIVILEGES"))),
                    "expected a REBUILD default-privileges GRANT: "
                            + report.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(script).reconcile().outcome());
            // a different desired grant converges the acl — revoke the old,
            // grant the new, in one REBUILD change
            final var changed = """
                    ALTER DEFAULT PRIVILEGES FOR ROLE tck_owner
                        IN SCHEMA ${namespace} GRANT UPDATE ON TABLES TO tck_owner2
                    """;
            final var drifted = reconciler(changed).reconcile();
            assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
            assertTrue(drifted.applied().stream().anyMatch(c ->
                            c.target().equals(defPrivs)
                                    && c.safety() == ChangeSafety.REBUILD
                                    && c.statements().stream()
                                            .anyMatch(s -> s.contains("REVOKE"))
                                    && c.statements().stream()
                                            .anyMatch(s -> s.contains("UPDATE"))),
                    "expected a REBUILD GRANT+REVOKE on default privileges: "
                            + drifted.applied());
            assertEquals(ReconcileOutcome.UP_TO_DATE,
                    reconciler(changed).reconcile().outcome());
        } finally {
            // the pg_default_acl rows drop with the namespace and the roles
            final var teardown = ownershipRoleTeardownDdl();
            if (teardown != null) {
                execute(teardown);
            }
        }
    }

    @Test
    @DisplayName("C-65 window function missing: ADDITIVE; body drift:"
            + " in-place CREATE OR REPLACE REBUILD")
    void c65WindowFunction() throws Exception {
        assumeKind(ObjectKind.ROUTINE);
        final var script = """
                CREATE FUNCTION wf(x int) RETURNS int
                    LANGUAGE sql WINDOW AS 'SELECT x'
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.ROUTINE
                                && c.target().name().equals("wf(integer)")
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE window function: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // a body drift keeps the return type — an in-place replace
        final var changed = """
                CREATE FUNCTION wf(x int) RETURNS int
                    LANGUAGE sql WINDOW AS 'SELECT x + 1'
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.ROUTINE
                                && c.target().name().equals("wf(integer)")
                                && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream()
                                        .anyMatch(s -> s.startsWith(
                                                "CREATE OR REPLACE FUNCTION"))),
                "expected an in-place replace for the window function: "
                        + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-66 text-search dictionary/config with mapping: ADDITIVE;"
            + " option drift: DROP+CREATE REBUILD")
    void c66TextSearchObjects() throws Exception {
        assumeKind(ObjectKind.TEXT_SEARCH_DICTIONARY);
        assumeKind(ObjectKind.TEXT_SEARCH_CONFIGURATION);
        final var script = """
                CREATE TEXT SEARCH DICTIONARY mydict
                    (template = pg_catalog.simple, stopwords = 'english');
                CREATE TEXT SEARCH CONFIGURATION mycfg (parser = pg_catalog.default);
                ALTER TEXT SEARCH CONFIGURATION mycfg
                    ADD MAPPING FOR asciiword WITH mydict
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.TEXT_SEARCH_DICTIONARY
                                && c.target().name().equals("mydict")
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE dictionary: " + report.applied());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.TEXT_SEARCH_CONFIGURATION
                                && c.target().name().equals("mycfg")
                                && c.safety() == ChangeSafety.ADDITIVE
                                && c.statements().stream().anyMatch(s ->
                                        s.contains("ADD MAPPING"))),
                "expected the mapping fragment on the configuration: "
                        + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // an option drift recreates — no OR REPLACE form exists
        final var changed = """
                CREATE TEXT SEARCH DICTIONARY mydict
                    (template = pg_catalog.simple, stopwords = 'german');
                CREATE TEXT SEARCH CONFIGURATION mycfg (parser = pg_catalog.default);
                ALTER TEXT SEARCH CONFIGURATION mycfg
                    ADD MAPPING FOR asciiword WITH mydict
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.TEXT_SEARCH_DICTIONARY
                                && c.target().name().equals("mydict")
                                && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream().anyMatch(s ->
                                        s.startsWith(
                                                "DROP TEXT SEARCH DICTIONARY"))),
                "expected a drop+create for the drifted dictionary: "
                        + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }

    @Test
    @DisplayName("C-67 operator, family and class missing: ADDITIVE;"
            + " class option drift: DROP+CREATE REBUILD")
    void c67OperatorObjects() throws Exception {
        assumeKind(ObjectKind.OPERATOR);
        assumeKind(ObjectKind.OPERATOR_CLASS);
        assumeKind(ObjectKind.OPERATOR_FAMILY);
        final var script = """
                CREATE OPERATOR ===
                    (leftarg = int, rightarg = int, function = int4eq,
                     restrict = eqsel, join = eqjoinsel, hashes, merges);
                CREATE OPERATOR FAMILY fam USING btree;
                CREATE OPERATOR CLASS oc FOR TYPE int USING btree FAMILY fam
                    AS OPERATOR 1 ===
                """;
        final var report = reconciler(script).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.OPERATOR
                                && c.target().name().equals("===(integer,integer)")
                                && c.safety() == ChangeSafety.ADDITIVE),
                "expected an ADDITIVE operator: " + report.applied());
        assertTrue(report.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.OPERATOR_CLASS
                                && c.safety() == ChangeSafety.ADDITIVE
                                && c.statements().stream().anyMatch(s ->
                                        s.contains("OPERATOR 1"))),
                "expected member specs on the class create: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script).reconcile().outcome());
        // an option drift recreates — no OR REPLACE form exists
        final var changed = """
                CREATE OPERATOR ===
                    (leftarg = int, rightarg = int, function = int4eq,
                     restrict = eqsel, join = eqjoinsel, hashes, merges);
                CREATE OPERATOR FAMILY fam USING btree;
                CREATE OPERATOR CLASS oc FOR TYPE int USING btree FAMILY fam
                    AS OPERATOR 1 ===, FUNCTION 1 btint4cmp(int,int)
                """;
        final var drifted = reconciler(changed).reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, drifted.outcome());
        assertTrue(drifted.applied().stream().anyMatch(c ->
                        c.target().kind() == ObjectKind.OPERATOR_CLASS
                                && c.safety() == ChangeSafety.REBUILD
                                && c.statements().stream().anyMatch(s ->
                                        s.startsWith("DROP OPERATOR CLASS"))),
                "expected a drop+create for the drifted class: "
                        + drifted.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(changed).reconcile().outcome());
    }
}
