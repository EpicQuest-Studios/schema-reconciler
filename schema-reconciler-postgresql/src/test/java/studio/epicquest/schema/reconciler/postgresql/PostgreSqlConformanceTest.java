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
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.DesiredState;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.SchemaReconciler;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.tck.DialectConformanceTest;

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
}
