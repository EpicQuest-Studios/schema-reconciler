package studio.epicquest.schema.reconciler.spi;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * Service Provider Interface implemented once per database product (spec 6.1).
 * The core owns diffing, dependency expansion, ordering, policy, verification
 * and transaction control; a dialect owns only vendor-specific SQL.
 */
public interface Dialect {

    /**
     * Attributes key marking a snapshot object that lives outside the
     * snapshot's own namespace: a dependent detected from catalog dependency
     * data (5.8.3). The core never diffs such objects — they exist so that
     * dependency expansion can name them in a {@code PlanningException}.
     */
    String EXTERNAL_DEPENDENT_ATTRIBUTE = "externalDependent";

    /**
     * Attributes key marking an object of an unsupported kind whose canonical
     * definition is a faithful rendering of its content. When the actual
     * snapshot holds an object with the same id and an equal definition, the
     * core reports no {@code UNSUPPORTED} difference for it (5.4); without
     * the marker an unsupported object is always reported.
     */
    String CONTENT_COMPARED_ATTRIBUTE = "contentCompared";

    /** Stable identifier, for example {@code "postgresql"}. */
    String id();

    boolean supports(DatabaseMetaData metaData) throws SQLException;

    /** The major version of the core API this dialect was built against (10.3). */
    default int coreMajorVersion() { return 1; }

    DialectCapabilities capabilities();

    void acquireLock(Connection connection, String namespace, Duration timeout) throws SQLException;

    void releaseLock(Connection connection, String namespace) throws SQLException;

    /**
     * Binds unqualified names on this connection to the given namespace for the
     * rest of the run (for example {@code SET LOCAL search_path}). Called before
     * executing the desired script and before applying changes; a no-op for
     * dialects that fully qualify every rendered statement. The binding MUST NOT
     * outlive the run: transactional dialects SHOULD use a transaction-scoped
     * setting; others MUST restore the previous value on every exit path.
     */
    default void bindSessionNamespace(Connection connection, String namespace) throws SQLException { }

    /**
     * Bounds how long any single statement may run during this run (for
     * example {@code SET LOCAL statement_timeout} on PostgreSQL). Called once
     * on the target connection after the namespace lock is acquired, so it
     * never bounds the lock wait itself, and again on the scratch connection
     * when a separate {@code scratchDataSource} is configured, so the desired
     * script and scratch snapshot read stay bounded there too. Adopters use
     * it to cap the time applied DDL — and the catalog reads around it — can
     * hold locks. A dialect that cannot express the bound leaves it a no-op.
     */
    default void statementTimeout(Connection connection, Duration timeout) throws SQLException { }

    /**
     * Verifies that this connection's user holds the privileges a reconcile run
     * needs (for example {@code USAGE, CREATE} on the target schema plus
     * {@code CREATE} on the database for the scratch namespace, on
     * PostgreSQL). Called once per run, before the lock, on the target
     * connection — with {@code createNamespace} telling it whether this
     * connection must also create the scratch namespace — and again on the
     * scratch connection when a separate {@code scratchDataSource} is
     * configured, then with {@code namespace} {@code null} and
     * {@code createNamespace} {@code true}. A dialect that cannot express the
     * check leaves it a no-op. Throw {@code SQLException} or
     * {@code ConfigurationException} when access is insufficient.
     */
    default void checkAccess(Connection connection, String namespace,
                             boolean createNamespace) throws SQLException { }

    String createScratchNamespace(Connection connection, String targetNamespace) throws SQLException;

    void dropScratchNamespace(Connection connection, String scratchNamespace) throws SQLException;

    /**
     * Executes a complete script in the scratch namespace; the dialect decides
     * whether to batch or split it. {@code scratchNamespace} is the name
     * {@link #createScratchNamespace} returned — the script's qualified
     * references must resolve to it (3.2 rule 1).
     */
    void executeScript(Connection connection, String script, String scratchNamespace)
            throws SQLException;

    /** Reads every object in the namespace, normalised per 5.3. */
    SchemaSnapshot readSnapshot(Connection connection, String namespace) throws SQLException;

    /**
     * Renders the statements and safety class for one difference, bound to the
     * target namespace. Changes are returned with {@code order} set to {@code 0};
     * the core assigns the final order.
     */
    List<PlannedChange> render(Difference difference, SchemaSnapshot desired, String targetNamespace);

    /**
     * Create-fragment statements for one snapshot object, written with the
     * {@code ${namespace}} token (3.2) so the emitted script is
     * namespace-neutral — the building block of {@code dump()} (3.1). An
     * object that cannot be represented as a create fragment returns an
     * empty list; the core emits it as a comment line instead of dropping
     * it silently.
     */
    default List<String> dumpStatements(SchemaObject object) { return List.of(); }

    /**
     * Quotes an identifier for safe inclusion in SQL. MUST throw
     * {@link IllegalArgumentException} if the identifier is not legal in the
     * engine (for example empty, longer than the engine's limit, or containing
     * characters the engine cannot represent); the core relies on this to
     * validate the configured namespace (8.3).
     */
    String quoteIdentifier(String identifier);

    /**
     * The objects, other than the difference's own object, that this dialect's
     * render output for a {@code MISSING} difference creates as a side effect
     * (for example the columns of a new table). The core suppresses their own
     * differences and changes (5.8.4). Empty means the create covers nothing
     * else. A dialect MUST NOT return an {@link ObjectId} that its
     * {@link #render} output for the parent does not create.
     */
    default Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) { return Set.of(); }

    /**
     * The scopes in which the engine requires this object's name to be unique,
     * as opaque keys (for example {@code "relation:plans_pk"}). Two objects with
     * different ObjectIds that share a key cannot coexist in one namespace
     * (5.8.7). Empty means the name is unique only within its {@link ObjectId}.
     */
    default Set<String> nameScopes(ObjectId id) { return Set.of(); }
}
