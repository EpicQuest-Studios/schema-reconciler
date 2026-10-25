package studio.epicquest.schema.reconciler.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import studio.epicquest.schema.reconciler.ApplyException;
import studio.epicquest.schema.reconciler.ConfigurationException;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.PolicyViolationException;
import studio.epicquest.schema.reconciler.ReconcileOutcome;
import studio.epicquest.schema.reconciler.ReconcileListener;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.SchemaReconcilerException;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.tck.DialectConformanceTest;
import studio.epicquest.schema.reconciler.tck.TrackingDataSource;

/** Runs the TCK against PostgreSQL 18. */
class PostgreSqlConformanceTest extends DialectConformanceTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final long RUN_ID = System.nanoTime();

    /** A DataSource whose connections survive {@code close()} so session state can be inspected. */
    private static final class Pool implements DataSource {
        private final DataSource delegate = PgTestDatabase.dataSource();
        final List<Connection> issued = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Connection getConnection() throws SQLException {
            final var real = delegate.getConnection();
            issued.add(real);
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close")) {
                            return null; // pooled-close: keep the session open
                        }
                        return method.invoke(real, args);
                    });
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            throw new UnsupportedOperationException();
        }

        List<Connection> realConnections() {
            synchronized (issued) {
                return List.copyOf(issued);
            }
        }

        void closeAll() {
            for (final var c : realConnections()) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
            issued.clear();
        }

        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter w) throws SQLException { delegate.setLogWriter(w); }
        @Override public void setLoginTimeout(int s) throws SQLException { delegate.setLoginTimeout(s); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    private final Pool pool = new Pool();
    private final DataSource scratch = PgTestDatabase.dataSource();
    private final Dialect dialect = new PostgreSqlDialect();

    @AfterAll
    static void stopDatabase() {
        PgTestDatabase.stop();
    }

    @Override
    protected DataSource dataSource() {
        return pool;
    }

    @Override
    protected DataSource scratchDataSource() {
        return scratch;
    }

    @Override
    protected Dialect dialect() {
        return dialect;
    }

    @Override
    protected String createNamespace() throws Exception {
        final var name = "tck_" + Long.toHexString(RUN_ID) + "_"
                + SEQUENCE.incrementAndGet();
        PgTestDatabase.execute(scratch, "CREATE SCHEMA " + dialect.quoteIdentifier(name));
        return name;
    }

    @Override
    protected void dropNamespace(String namespace) throws Exception {
        try {
            PgTestDatabase.execute(scratch, "DROP SCHEMA "
                    + dialect.quoteIdentifier(namespace) + " CASCADE");
        } finally {
            pool.closeAll();
        }
    }

    @Override
    protected void assertNoScratchNamespacesLeft() throws Exception {
        try (Connection c = scratch.getConnection();
                final var s = c.createStatement();
                final var rs = s.executeQuery("SELECT count(*) FROM pg_namespace"
                        + " WHERE nspname LIKE '%\\_\\_rcl\\_%' ESCAPE '\\'")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "scratch namespaces leaked");
        }
    }

    @Override
    protected void assertCleanSession() throws Exception {
        for (final var c : pool.realConnections()) {
            assertTrue(c.getAutoCommit(), "connection left with autoCommit=false");
            try (Statement s = c.createStatement()) {
                try (var rs = s.executeQuery("SELECT count(*) FROM pg_locks"
                        + " WHERE locktype = 'advisory' AND pid = pg_backend_pid()")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1), "advisory lock leaked");
                }
                try (var rs = s.executeQuery("SHOW lock_timeout")) {
                    rs.next();
                    assertEquals("0", rs.getString(1), "lock_timeout left set");
                }
                try (var rs = s.executeQuery("SHOW statement_timeout")) {
                    rs.next();
                    assertEquals("0", rs.getString(1), "statement_timeout left set");
                }
                try (var rs = s.executeQuery("SHOW search_path")) {
                    rs.next();
                    assertTrue(!rs.getString(1).contains("rcl"),
                            "search_path left bound to a scratch namespace");
                }
            }
        }
    }

    @Override
    protected boolean isScratchBoundary(String sql) {
        return sql.toUpperCase(Locale.ROOT).contains("SAVEPOINT RCL_SCRATCH")
                || sql.toUpperCase(Locale.ROOT).contains("__RCL_");
    }

    @Override
    protected boolean isScratchEnd(String sql) {
        return sql.toUpperCase(Locale.ROOT).contains("ROLLBACK TO SAVEPOINT RCL_SCRATCH");
    }

    @Override
    protected UnsupportedScript unsupportedScript() {
        return new UnsupportedScript(
                "CREATE MATERIALIZED VIEW m_unsupported AS SELECT 1 AS id;"
                        + " CREATE VIEW dep_view AS SELECT id FROM m_unsupported",
                "dep_view");
    }

    @Override
    protected String unsupportedCategory() {
        return "matview"; // relkind 'm' → OTHER id "matview:<name>" (A.5)
    }

    @Override
    protected boolean columnAlterSupported() {
        return true;
    }

    @Override
    protected String identityColumnDdl() {
        return "bigint GENERATED ALWAYS AS IDENTITY";
    }

    @Override
    protected String serialColumnDdl() {
        return "bigserial";
    }

    // ---- PostgreSQL-specific additions -------------------------------------

    @Test
    @DisplayName("C-25b dependent outside the namespace blocks planning")
    void externalDependentBlocksPlanning() throws Exception {
        reconciler("""
                CREATE TABLE users (id bigint, name text);
                CREATE VIEW v_users AS SELECT id FROM users
                """).reconcile();
        final var extSchema = namespace() + "_ext";
        PgTestDatabase.execute(scratch, "CREATE SCHEMA " + extSchema);
        try {
            PgTestDatabase.execute(scratch, "CREATE VIEW " + extSchema + ".ext_view"
                    + " AS SELECT id FROM " + namespace() + ".v_users");

            final var e = assertThrows(PlanningException.class, () ->
                    reconciler("""
                            CREATE TABLE users (id bigint, name text);
                            CREATE VIEW v_users AS SELECT id, name FROM users
                            """).reconcile());
            assertTrue(e.getMessage().contains("ext_view"), e.getMessage());
        } finally {
            PgTestDatabase.execute(scratch, "DROP SCHEMA " + extSchema + " CASCADE");
        }
    }

    @Test
    @DisplayName("statementTimeout issues SET LOCAL after the lock")
    void statementTimeoutApplied() throws Exception {
        final var tracking = new TrackingDataSource(scratch);
        SchemaReconciler.builder()
                .dataSource(tracking)
                .namespace(namespace())
                .desiredState(DesiredState.ofScript("CREATE TABLE t (id bigint)"))
                .dialect(dialect())
                .statementTimeout(Duration.ofSeconds(5))
                .build()
                .reconcile();
        assertTrue(tracking.executedSql().stream()
                        .anyMatch(s -> s.startsWith("SET LOCAL statement_timeout")),
                tracking.executedSql().toString());
    }

    @Test
    @DisplayName("plan() is repeatable and apply() converges")
    void applyOfFreshPlan() throws Exception {
        final var script = "CREATE TABLE users (id bigint, name text)";
        final var r = SchemaReconciler.builder()
                .dataSource(dataSource())
                .namespace(namespace())
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect())
                .build();
        final var plan = r.plan();
        final var report = r.apply(plan);
        assertEquals(studio.epicquest.schema.reconciler.ReconcileOutcome.RECONCILED,
                report.outcome());
    }

    // ---- adoption-review validation ---------------------------------------

    private static ObjectId table(String name) {
        return new ObjectId(ObjectKind.TABLE, name);
    }

    private static ObjectId column(String table, String name) {
        return new ObjectId(ObjectKind.COLUMN, name, table);
    }

    @Test
    @DisplayName("safe column drift converges on populated data")
    void safeColumnDriftOnPopulatedTable() throws Exception {
        reconciler("CREATE TABLE t (id bigint, name varchar(10), note text DEFAULT 'a')")
                .reconcile();
        execute("INSERT INTO " + qualified("t")
                + " VALUES (1, 'abcdefghij', 'x'), (2, 'k', 'y')");
        final var report = reconciler(
                "CREATE TABLE t (id bigint, name varchar(20), note text DEFAULT 'b')")
                .reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        try (var rs = PgTestDatabase.connect().createStatement()
                .executeQuery("SELECT name, note FROM " + qualified("t") + " ORDER BY id")) {
            rs.next();
            assertEquals("abcdefghij", rs.getString(1));
            assertEquals("x", rs.getString(2));
        }
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(
                "CREATE TABLE t (id bigint, name varchar(20), note text DEFAULT 'b')")
                .reconcile().outcome());
    }

    @Test
    @DisplayName("SET NOT NULL on violating data fails by policy, rolls back when forced")
    void setNotNullOnViolatingData() throws Exception {
        reconciler("CREATE TABLE t (id bigint, note text)").reconcile();
        execute("INSERT INTO " + qualified("t") + " VALUES (1, NULL), (2, 'x')");
        final var want = "CREATE TABLE t (id bigint, note text NOT NULL)";
        assertThrows(PolicyViolationException.class, () -> reconciler(want).reconcile());

        final var forced = ReconcilePolicy.defaults()
                .withDestructive(Decision.APPLY);
        final var e = assertThrows(ApplyException.class,
                () -> reconciler(want, forced).reconcile());
        assertTrue(e.timings().apply().toNanos() > 0,
                "a mid-apply failure still reports partial apply time: " + e.timings());
        // rolled back: the NULLs are still there and the column stays nullable
        try (var rs = PgTestDatabase.connect().createStatement().executeQuery(
                "SELECT count(*) FROM " + qualified("t") + " WHERE note IS NULL")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
        assertTrue(snapshot().objects().get(column("t", "note"))
                        .definition().canonical().contains("notnull=false"),
                "rollback must leave the column nullable");
        assertCleanSession();
    }

    @Test
    @DisplayName("environmental objects report as UNEXPECTED without blocking")
    void environmentalObjectsReportUnexpected() throws Exception {
        // extension, policy, comment and matview live in the environment the
        // way Komisar finds them; the desired script covers managed objects
        execute("CREATE TABLE " + qualified("t") + " (id bigint)");
        execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA " + q(namespace()));
        execute("CREATE POLICY p_sel ON " + qualified("t") + " FOR SELECT USING (true)");
        execute("COMMENT ON TABLE " + qualified("t") + " IS 'managed'");
        execute("CREATE MATERIALIZED VIEW " + qualified("mv") + " AS SELECT id FROM "
                + qualified("t"));
        final var report = reconciler("CREATE TABLE t (id bigint, note text)").reconcile();
        assertEquals(ReconcileOutcome.RECONCILED, report.outcome());
        // exactly five environment objects reported — pg_trgm carries its own
        // description; extension members never surface as managed extras
        assertEquals(5, report.unexpected().size(), report.unexpected().toString());
        assertTrue(report.unexpected().stream().allMatch(
                d -> d.type() == DifferenceType.UNEXPECTED));
        final var names = report.unexpected().stream()
                .map(d -> d.id().name()).sorted().toList();
        assertEquals(List.of("comment:pg_trgm", "comment:t", "extension:pg_trgm",
                "matview:mv", "policy:t.p_sel"), names);
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler("CREATE TABLE t (id bigint, note text)").reconcile().outcome());
    }

    @Test
    @DisplayName("category override tolerates policy/comment but not the matview")
    void categoryOverrideSplitsUnsupportedKinds() throws Exception {
        final var script = """
                CREATE TABLE t (id bigint);
                CREATE POLICY p_sel ON t FOR SELECT USING (true);
                COMMENT ON TABLE t IS 'managed';
                CREATE MATERIALIZED VIEW mv AS SELECT id FROM t
                """;
        final var tolerant = ReconcilePolicy.defaults()
                .withUnsupportedCategory("policy", Decision.REPORT)
                .withUnsupportedCategory("comment", Decision.REPORT);
        // the matview category is not tolerated → still fails closed
        assertThrows(PolicyViolationException.class,
                () -> reconciler(script, tolerant).reconcile());
        final var report = reconciler(script, tolerant
                .withUnsupportedCategory("matview", Decision.REPORT)).reconcile();
        // tolerated objects are reported as intentionally ignored, not reconciled
        assertEquals(3, report.unsupported().size(), report.unsupported().toString());
        assertTrue(report.unsupported().stream().allMatch(
                d -> d.type() == DifferenceType.UNSUPPORTED));
        assertTrue(report.applied().stream()
                .anyMatch(c -> c.target().equals(table("t"))),
                "the managed change still applies: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE,
                reconciler(script, tolerant
                        .withUnsupportedCategory("matview", Decision.REPORT))
                        .reconcile().outcome());
    }

    @Test
    @DisplayName("checkAccess rejects a role missing target or scratch privileges")
    void checkAccessPreflight() throws Exception {
        final var role = "rcl_low_" + Long.toHexString(RUN_ID)
                + SEQUENCE.incrementAndGet();
        PgTestDatabase.execute(scratch, "CREATE ROLE " + role + " LOGIN PASSWORD 'rcl'");
        final var ds = PgTestDatabase.dataSource(role, "rcl");
        final var policy = ReconcilePolicy.defaults();
        try {
            // no privileges on the target schema at all
            assertThrows(ConfigurationException.class, () ->
                    reconcilerOn(ds, "CREATE TABLE t (id bigint)", policy).reconcile());
            // target granted but database CREATE (scratch setup) revoked
            PgTestDatabase.execute(scratch, "GRANT USAGE, CREATE ON SCHEMA "
                    + dialect.quoteIdentifier(namespace()) + " TO " + role);
            final var db = PgTestDatabase.connect().getCatalog();
            PgTestDatabase.execute(scratch,
                    "REVOKE CREATE ON DATABASE " + db + " FROM PUBLIC");
            try {
                final var e = assertThrows(ConfigurationException.class, () ->
                        reconcilerOn(ds, "CREATE TABLE t (id bigint)", policy).reconcile());
                assertTrue(e.getMessage().contains("scratch"),
                        "expected the scratch-creation privilege in the message: "
                                + e.getMessage());
            } finally {
                PgTestDatabase.execute(scratch,
                        "GRANT CREATE ON DATABASE " + db + " TO PUBLIC");
            }
        } finally {
            PgTestDatabase.execute(scratch, "REVOKE ALL ON SCHEMA "
                    + dialect.quoteIdentifier(namespace()) + " FROM " + role);
            PgTestDatabase.execute(scratch, "DROP ROLE " + role);
        }
    }

    @Test
    @DisplayName("statementTimeout fires on a blocked apply; rollback keeps the pool clean")
    void statementTimeoutFired() throws Exception {
        // no default on the table: pg_get_expr is STRICT and never opens the
        // locked relation, so catalog reads pass and only the apply's ALTER
        // blocks — a table with a default would already stall readActual
        reconciler("CREATE TABLE t (id bigint, note text)").reconcile();
        // an unrelated session holds an ACCESS EXCLUSIVE lock: the apply's
        // ALTER waits until statement_timeout cancels it
        final var blocker = PgTestDatabase.connect();
        blocker.setAutoCommit(false);
        blocker.createStatement().execute("LOCK TABLE " + q(namespace())
                + "." + q("t") + " IN ACCESS EXCLUSIVE MODE");
        try {
            final var e = assertThrows(ApplyException.class, () -> SchemaReconciler.builder()
                    .dataSource(dataSource())
                    .namespace(namespace())
                    .desiredState(DesiredState.ofScript(
                            "CREATE TABLE t (id bigint, note text DEFAULT 'b')"))
                    .dialect(dialect())
                    .statementTimeout(Duration.ofSeconds(5))
                    .build()
                    .reconcile());
            // phases before the failure are still measured
            assertTrue(e.timings().lockWait().toNanos() > 0, e.timings().toString());
            assertTrue(e.timings().apply().toNanos() > 0, e.timings().toString());
        } finally {
            blocker.rollback();
            blocker.close();
        }
        assertCleanSession();
        // after the blocker released, the same reconcile converges cleanly
        assertEquals(ReconcileOutcome.RECONCILED, reconciler(
                "CREATE TABLE t (id bigint, note text DEFAULT 'b')").reconcile().outcome());
    }

    @Test
    @DisplayName("statementTimeout fires during the verification re-scan")
    void statementTimeoutInVerify() throws Exception {
        // 'other' carries a DEFAULT, so the verify re-scan's pg_get_expr deparse
        // takes AccessShareLock on it; the applied listener runs inside the run
        // transaction — after apply, before verify — so the blocking lock lands
        // deterministically between the two phases
        execute("CREATE TABLE " + q(namespace()) + ".other (id int DEFAULT 1)");
        execute("CREATE TABLE " + q(namespace()) + ".t (id bigint)");
        final var blocker = PgTestDatabase.connect();
        blocker.setAutoCommit(false);
        final var locked = new AtomicBoolean();
        try {
            final var e = assertThrows(SchemaReconcilerException.class,
                    () -> SchemaReconciler.builder()
                            .dataSource(dataSource())
                            .namespace(namespace())
                            .desiredState(DesiredState.ofScript(
                                    "CREATE TABLE t (id bigint, extra text)"))
                            .dialect(dialect())
                            .statementTimeout(Duration.ofSeconds(5))
                            .listener(new ReconcileListener() {
                                @Override
                                public void applied(PlannedChange change, Duration elapsed) {
                                    if (locked.compareAndSet(false, true)) {
                                        try {
                                            blocker.createStatement().execute("LOCK TABLE "
                                                    + q(namespace()) + "." + q("other")
                                                    + " IN ACCESS EXCLUSIVE MODE");
                                        } catch (SQLException ex) {
                                            throw new IllegalStateException(ex);
                                        }
                                    }
                                }
                            })
                            .build()
                            .reconcile());
            assertTrue(locked.get(), "apply phase never ran");
            assertTrue(e.getMessage().contains("catalog snapshot"), e.getMessage());
            assertTrue(e.timings().apply().toNanos() > 0, e.timings().toString());
            assertTrue(e.timings().verify().compareTo(Duration.ofSeconds(4)) >= 0,
                    e.timings().toString());
        } finally {
            blocker.rollback();
            blocker.close();
        }
        assertCleanSession();
    }

    @Test
    @DisplayName("statementTimeout bounds the separate scratch connection too")
    void statementTimeoutOnScratchConnection() throws Exception {
        // a separate scratchDataSource gets its own connection — the target
        // session's SET LOCAL cannot reach it, so the engine applies the bound
        // there as well; the sleeping desired script proves it fired
        final var e = assertThrows(DesiredStateException.class,
                () -> SchemaReconciler.builder()
                        .dataSource(dataSource())
                        .scratchDataSource(scratchDataSource())
                        .namespace(namespace())
                        .desiredState(DesiredState.ofScript(
                                "DO $$ BEGIN PERFORM pg_sleep(10); END $$"))
                        .dialect(dialect())
                        .statementTimeout(Duration.ofSeconds(1))
                        .build()
                        .reconcile());
        assertTrue(e.timings().loadDesired().compareTo(Duration.ofMillis(900)) >= 0,
                "the blocked scratch phase must still report its partial time: "
                        + e.timings());
        assertNoScratchNamespacesLeft();
        assertCleanSession();
    }

    @Test
    @DisplayName("tolerated extension and grant report; the matview still fails")
    void toleratedExtensionAndGrantVsMatview() throws Exception {
        // environmental kinds (extension, privileges and the extension's own
        // comment) may be tolerated per category — a materialized view must
        // still fail closed under the same policy
        final var script = """
                CREATE TABLE t (id bigint);
                CREATE EXTENSION pg_trgm;
                GRANT SELECT ON t TO PUBLIC;
                CREATE MATERIALIZED VIEW mv AS SELECT id FROM t
                """;
        final var tolerant = ReconcilePolicy.defaults()
                .withUnsupportedCategory("extension", Decision.REPORT)
                .withUnsupportedCategory("privileges", Decision.REPORT)
                .withUnsupportedCategory("comment", Decision.REPORT);
        assertThrows(PolicyViolationException.class,
                () -> reconciler(script, tolerant).reconcile());
        final var report = reconciler(script, tolerant
                .withUnsupportedCategory("matview", Decision.REPORT)).reconcile();
        final var names = report.unsupported().stream()
                .map(d -> d.id().name()).sorted().toList();
        assertTrue(names.contains("extension:pg_trgm"), names.toString());
        assertTrue(names.contains("matview:mv"), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.startsWith("privileges:")),
                "expected a privileges entry: " + names);
        // tolerated objects are intentionally ignored, never applied
        assertTrue(report.unsupported().stream().allMatch(
                d -> d.type() == DifferenceType.UNSUPPORTED));
        assertTrue(report.applied().stream()
                .anyMatch(c -> c.target().equals(table("t"))),
                "the managed change still applies: " + report.applied());
        assertEquals(ReconcileOutcome.UP_TO_DATE, reconciler(script, tolerant
                .withUnsupportedCategory("matview", Decision.REPORT))
                .reconcile().outcome());
    }
}
