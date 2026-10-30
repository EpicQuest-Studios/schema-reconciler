package studio.epicquest.schema.reconciler.postgresql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import studio.epicquest.schema.reconciler.ConfigurationException;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.LockTimeoutException;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.postgresql.internal.PgCatalog;
import studio.epicquest.schema.reconciler.postgresql.internal.PgIdentifiers;
import studio.epicquest.schema.reconciler.postgresql.internal.PgRender;
import studio.epicquest.schema.reconciler.postgresql.internal.PgScriptCheck;
import studio.epicquest.schema.reconciler.postgresql.internal.PgSql;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.spi.DialectCapabilities;

/**
 * The PostgreSQL dialect of spec Appendix A — the reference implementation.
 * Supports PostgreSQL 14 and later.
 */
public final class PostgreSqlDialect implements Dialect {

    private static final String SAVEPOINT = "rcl_scratch";
    private static final String LOCK_KEY_PREFIX = "studio.epicquest.schema.reconciler:";

    /** Creates the dialect — stateless, safe to share. */
    public PostgreSqlDialect() {
    }

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
        try (Statement s = connection.createStatement();
             PreparedStatement lock = connection.prepareStatement(
                     "SELECT pg_advisory_xact_lock(hashtextextended("
                             + "'" + LOCK_KEY_PREFIX + "'"
                             + " || current_database() || ':' || ?, 0))")) {
            s.execute("SET LOCAL lock_timeout = '" + millisBounded(timeout) + "ms'");
            try {
                lock.setString(1, namespace);
                lock.execute();
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

    @Override
    public void statementTimeout(Connection connection, Duration timeout) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("SET LOCAL statement_timeout = '" + millisBounded(timeout) + "ms'");
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
                // the rollback undoes settings but not session advisory locks, which a
                // function the script invoked may have taken (the static check cannot
                // see through a call): release them before the connection is reused
                s.execute("SELECT pg_advisory_unlock_all()");
            } catch (SQLException e) {
                // the savepoint is gone: the script committed (or aborted) the
                // transaction — remove the committed schema explicitly (A.3)
                rollbackQuietly(connection);
                s.execute("SELECT pg_advisory_unlock_all()");
                s.execute("DROP SCHEMA IF EXISTS " + PgIdentifiers.quote(scratchNamespace)
                        + " CASCADE");
                connection.commit();
                throw new DesiredStateException("the desired script left the transaction: "
                        + "the run's savepoint is gone (" + e.getMessage() + ")", e);
            }
        }
    }

    // ---- A.4 script execution -----------------------------------------------

    @Override
    public void checkAccess(Connection connection, String namespace,
                            boolean createNamespace) throws SQLException {
        if (namespace != null) {
            try (PreparedStatement ps = connection.prepareStatement(
                    // a comma list means "any of", so each privilege is checked alone
                    "SELECT to_regnamespace(?),"
                            + " has_schema_privilege(current_user, to_regnamespace(?), 'USAGE')"
                            + " AND has_schema_privilege(current_user, to_regnamespace(?), 'CREATE')")) {
                // to_regnamespace parses its argument as an identifier, so
                // quote it to keep mixed-case namespaces exact
                final var quoted = PgIdentifiers.quote(namespace);
                ps.setString(1, quoted);
                ps.setString(2, quoted);
                ps.setString(3, quoted);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getObject(1) == null) {
                        throw new ConfigurationException("namespace '" + namespace
                                + "' does not exist on the target database");
                    }
                    if (!rs.getBoolean(2)) {
                        throw new ConfigurationException("namespace '" + namespace
                                + "': the connecting user lacks USAGE or CREATE"
                                + " on the target namespace");
                    }
                }
            }
        }
        if (createNamespace) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT has_database_privilege(current_user, current_database(),"
                            + " 'CREATE')")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        throw new ConfigurationException("the connecting user lacks"
                                + " CREATE on the database, needed for the scratch"
                                + " namespace; configure scratchDataSource to"
                                + " offload scratch creation");
                    }
                }
            }
        }
    }

    // ---- A.4 script execution -----------------------------------------------

    @Override
    public void executeScript(Connection connection, String script, String scratchNamespace)
            throws SQLException {
        PgScriptCheck.check(script, scratchNamespace);
        try (Statement s = connection.createStatement()) {
            s.execute(script);
        }
        anchorScriptExtensions(connection, script, scratchNamespace);
    }

    /**
     * An extension is a database-wide object anchored to a namespace; a
     * {@code CREATE EXTENSION IF NOT EXISTS} in the desired script no-ops
     * when the extension is already installed anywhere, so the scratch
     * snapshot would never see it. Relocating a relocatable pre-installed
     * extension into the scratch namespace lets the snapshot report the
     * anchored {@code EXTENSION} object — the savepoint rollback undoes the
     * relocation with everything else.
     */
    private static final Pattern CREATE_EXTENSION = Pattern
            .compile("(?i)^\\s*CREATE\\s+EXTENSION\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(\"(?:[^\"]|\"\")*\"|[\\w-]+)");

    private static void anchorScriptExtensions(Connection connection, String script,
                                               String scratchNamespace) throws SQLException {
        for (final var statement : PgSql.statements(script)) {
            final var m = CREATE_EXTENSION.matcher(statement.text());
            if (!m.find()) {
                continue;
            }
            final var raw = m.group(1);
            final var name = raw.startsWith("\"")
                    ? raw.substring(1, raw.length() - 1).replace("\"\"", "\"")
                    : raw.toLowerCase(Locale.ROOT);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT e.extrelocatable FROM pg_extension e"
                            + " JOIN pg_namespace n ON n.oid = e.extnamespace"
                            + " WHERE e.extname = ? AND n.nspname <> ?")) {
                ps.setString(1, name);
                ps.setString(2, scratchNamespace);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        try (Statement s = connection.createStatement()) {
                            s.execute("ALTER EXTENSION " + PgIdentifiers.quote(name)
                                    + " SET SCHEMA " + PgIdentifiers.quote(scratchNamespace));
                        }
                    }
                }
            }
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

    /**
     * 6.1: the object's complete create-fragment list in {@code ${namespace}}
     * token form. {@link PgCatalog#DUMP_SQL} joins the statements with a
     * U+0000 separator — impossible in PostgreSQL text — so the split is
     * lossless; absent means folded or unrenderable. A trailing semicolon
     * (pg_get_viewdef leaves one) is dropped: the dump terminator adds it.
     */
    @Override
    public List<String> dumpStatements(SchemaObject object) {
        final var dump = object.attributes().get(PgCatalog.DUMP_SQL);
        if (dump == null) {
            return List.of();
        }
        return java.util.Arrays.stream(dump.split("\0"))
                .map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s)
                .toList();
    }

    @Override
    public Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) {
        return PgRender.coveredChildren(created, desired);
    }

    @Override
    public Set<String> nameScopes(ObjectId id) {
        return PgRender.nameScopes(id);
    }

    /** A positive duration below one millisecond must still bound the wait. */
    private static long millisBounded(Duration timeout) {
        return Math.max(1L, timeout.toMillis());
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best effort — the connection is already on its way out
        }
    }
}
