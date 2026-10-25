package studio.epicquest.schema.reconciler.postgresql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.LockTimeoutException;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.postgresql.internal.PgCatalog;
import studio.epicquest.schema.reconciler.postgresql.internal.PgIdentifiers;
import studio.epicquest.schema.reconciler.postgresql.internal.PgRender;
import studio.epicquest.schema.reconciler.postgresql.internal.PgScriptCheck;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.spi.DialectCapabilities;

/**
 * The PostgreSQL dialect of spec Appendix A — the reference implementation.
 * Supports PostgreSQL 14 and later.
 */
public final class PostgreSqlDialect implements Dialect {

    private static final String SAVEPOINT = "rcl_scratch";
    private static final String LOCK_KEY_PREFIX = "studio.epicquest.schema.reconciler:";

    @Override
    public String id() {
        return "postgresql";
    }

    @Override
    public boolean supports(DatabaseMetaData metaData) throws SQLException {
        return "PostgreSQL".equals(metaData.getDatabaseProductName())
                && metaData.getDatabaseMajorVersion() >= 14;
    }

    @Override
    public DialectCapabilities capabilities() {
        final var kinds = EnumSet.allOf(ObjectKind.class);
        kinds.remove(ObjectKind.OTHER);
        return new DialectCapabilities(true, true, kinds);
    }

    // ---- A.2 locking -------------------------------------------------------

    @Override
    public void acquireLock(Connection connection, String namespace, Duration timeout)
            throws SQLException {
        try (Statement s = connection.createStatement()) {
            // a positive duration below one millisecond must still bound the wait
            s.execute("SET LOCAL lock_timeout = '" + Math.max(1L, timeout.toMillis()) + "ms'");
            try {
                s.execute("SELECT pg_advisory_xact_lock(hashtextextended("
                        + "'" + LOCK_KEY_PREFIX + "' || current_database() || ':' || "
                        + "'" + escapeLiteral(namespace) + "', 0))");
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) {
                    throw new LockTimeoutException("namespace '" + namespace
                            + "': lock not acquired within " + timeout, e);
                }
                throw e;
            }
            s.execute("SET LOCAL lock_timeout = DEFAULT");
        }
    }

    @Override
    public void releaseLock(Connection connection, String namespace) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("SELECT pg_advisory_unlock_all()");
        }
    }

    @Override
    public void bindSessionNamespace(Connection connection, String namespace) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("SET LOCAL search_path = " + PgIdentifiers.quote(namespace));
        }
    }

    // ---- A.3 scratch namespace ----------------------------------------------

    @Override
    public String createScratchNamespace(Connection connection, String targetNamespace)
            throws SQLException {
        final var scratch = PgIdentifiers.scratchName(
                targetNamespace, ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
        try (Statement s = connection.createStatement()) {
            // a separate scratch connection arrives here with no open
            // transaction; BEGIN is required before SAVEPOINT and is a
            // harmless warning when the transaction is already open
            s.execute("BEGIN");
            s.execute("SAVEPOINT " + SAVEPOINT);
            s.execute("CREATE SCHEMA " + PgIdentifiers.quote(scratch));
        }
        return scratch;
    }

    @Override
    public void dropScratchNamespace(Connection connection, String scratchNamespace)
            throws SQLException {
        try (Statement s = connection.createStatement()) {
            try {
                s.execute("ROLLBACK TO SAVEPOINT " + SAVEPOINT);
            } catch (SQLException e) {
                // the savepoint is gone: the script committed (or aborted) the
                // transaction — remove the committed schema explicitly (A.3)
                rollbackQuietly(connection);
                s.execute("DROP SCHEMA " + PgIdentifiers.quote(scratchNamespace) + " CASCADE");
                connection.commit();
                throw new DesiredStateException("the desired script left the transaction: "
                        + "the run's savepoint is gone (" + e.getMessage() + ")", e);
            }
        }
    }

    // ---- A.4 script execution -----------------------------------------------

    @Override
    public void executeScript(Connection connection, String script) throws SQLException {
        PgScriptCheck.check(script);
        try (Statement s = connection.createStatement()) {
            s.execute(script);
        }
    }

    // ---- A.5 snapshot ---------------------------------------------------------

    @Override
    public SchemaSnapshot readSnapshot(Connection connection, String namespace) throws SQLException {
        return PgCatalog.read(connection, namespace);
    }

    // ---- A.6 rendering ---------------------------------------------------------

    @Override
    public List<PlannedChange> render(Difference difference, SchemaSnapshot desired,
                                      String targetNamespace) {
        return PgRender.render(difference, desired, targetNamespace);
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return PgIdentifiers.quote(identifier);
    }

    @Override
    public Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) {
        return PgRender.coveredChildren(created, desired);
    }

    @Override
    public Set<String> nameScopes(ObjectId id) {
        return PgRender.nameScopes(id);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort — the connection is already on its way out
        }
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
