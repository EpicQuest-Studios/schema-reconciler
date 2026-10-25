package studio.epicquest.schema.reconciler.postgresql;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Supplies the disposable database for PostgreSQL dialect tests. Set
 * {@code RECONCILER_TEST_URL}, {@code RECONCILER_TEST_USER} and
 * {@code RECONCILER_TEST_PASSWORD} to point at an existing PostgreSQL 14+
 * instance; otherwise a Testcontainers {@code postgres:18} container is
 * started for the test JVM.
 */
final class PgTestDatabase {

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String user;
    private static String password;

    private PgTestDatabase() {
    }

    static synchronized void start() {
        if (url != null) {
            return;
        }
        final var envUrl = System.getenv("RECONCILER_TEST_URL");
        if (envUrl != null) {
            url = envUrl;
            user = System.getenv("RECONCILER_TEST_USER");
            password = System.getenv("RECONCILER_TEST_PASSWORD");
        } else {
            container = new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("reconciler_tck")
                    .withUsername("rcl")
                    .withPassword("rcl");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }
    }

    static synchronized void stop() {
        if (container != null) {
            container.stop();
            container = null;
            url = null;
        }
    }

    /** A fresh DataSource (each call returns an independent pool-free source). */
    static DataSource dataSource() {
        start();
        return new Simple(url, user, password);
    }

    /** A DataSource that authenticates as another role (preflight tests). */
    static DataSource dataSource(String user, String password) {
        start();
        return new Simple(url, user, password);
    }

    /** Minimal DriverManager-backed DataSource — keeps the JDBC driver test-only. */
    private record Simple(String url, String user, String password) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return user == null
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            return DriverManager.getConnection(url, u, p);
        }

        @Override public PrintWriter getLogWriter() { return DriverManager.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter w) { DriverManager.setLogWriter(w); }
        @Override public void setLoginTimeout(int s) { DriverManager.setLoginTimeout(s); }
        @Override public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { throw new SQLException(); }
        @Override public boolean isWrapperFor(Class<?> i) { return false; }
    }

    /** A DataSource that remembers the last connection it issued. */
    static final class Remembering implements DataSource {
        private final DataSource delegate = PgTestDatabase.dataSource();
        volatile Connection last;

        @Override
        public Connection getConnection() throws SQLException {
            last = delegate.getConnection();
            return last;
        }

        @Override
        public Connection getConnection(String u, String p) throws SQLException {
            last = delegate.getConnection(u, p);
            return last;
        }

        @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter w) throws SQLException { delegate.setLogWriter(w); }
        @Override public void setLoginTimeout(int s) throws SQLException { delegate.setLoginTimeout(s); }
        @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> i) throws SQLException { return delegate.unwrap(i); }
        @Override public boolean isWrapperFor(Class<?> i) throws SQLException { return delegate.isWrapperFor(i); }
    }

    static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * Installs a {@code pg_catalog.uuidv7()} stand-in on pre-18 servers so
     * fixtures written against the v18 built-in still exercise the whole
     * pipeline. Test databases only — it returns a random uuid, not a
     * time-ordered one.
     */
    static int serverMajor(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getDatabaseMajorVersion();
        }
    }

    static void ensureUuidv7(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            if (c.getMetaData().getDatabaseMajorVersion() < 18) {
                execute(ds, "CREATE OR REPLACE FUNCTION pg_catalog.uuidv7()"
                        + " RETURNS uuid LANGUAGE sql"
                        + " AS $$ SELECT gen_random_uuid() $$");
            }
        }
    }

    /** For ad-hoc scripts run outside the DataSource API. */
    static Connection connect() throws SQLException {
        start();
        return user == null
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }
}
