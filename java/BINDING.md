# Schema Reconciler — Java binding

This document is the normative Java binding of the language-neutral [schema-reconciler specification](../SPEC.md). It defines how the specification's logical API, SPI, model and error taxonomy are represented in Java. Behavioural semantics are defined by the root specification and are not repeated here.

| | |
|---|---|
| Specification version | 0.11 |
| Implementation version | 0.11.0 |
| Java baseline | Java SE 25 or later |
| Build baseline | Maven 3.9+ (the Maven wrapper is included) |
| Database access | JDBC 4.3 (`javax.sql.DataSource`, `java.sql.Connection`) |

## Artifacts and modules

| Maven artifact | JPMS module | Contents |
|---|---|---|
| `studio.epicquest.schema:schema-reconciler-core` | `studio.epicquest.schema.reconciler` | API, model, SPI and reconciliation engine; Java SE only |
| `studio.epicquest.schema:schema-reconciler-postgresql` | `studio.epicquest.schema.reconciler.postgresql` | PostgreSQL 14+ reference dialect |
| `studio.epicquest.schema:schema-reconciler-tck` | `studio.epicquest.schema.reconciler.tck` (automatic module) | JUnit Jupiter conformance suite |

The core module requires `java.sql` transitively and exports:

| Package | Role | Stability |
|---|---|---|
| `studio.epicquest.schema.reconciler` | Application API | Stable under the specification versioning rules |
| `studio.epicquest.schema.reconciler.model` | Snapshot, difference and plan model | Stable under the specification versioning rules |
| `studio.epicquest.schema.reconciler.spi` | Dialect SPI | Stable for implementers; pre-1.0 additions use `default` methods where possible |
| `studio.epicquest.schema.reconciler.internal` | Engine implementation | Not exported; not API |

The core module declares `uses studio.epicquest.schema.reconciler.spi.Dialect`. The PostgreSQL module declares `provides ... Dialect with ... PostgreSqlDialect` and also registers the class in `META-INF/services/studio.epicquest.schema.reconciler.spi.Dialect`, so discovery works both under JPMS and on the class path.

## Type and API mapping

| Specification concept | Java representation |
|---|---|
| Data source | `javax.sql.DataSource` |
| Database session | `java.sql.Connection` |
| Database metadata | `java.sql.DatabaseMetaData` |
| Driver failure cause | `java.sql.SQLException`, wrapped in the phase-appropriate reconciler exception |
| `T or absent` | `java.util.Optional<T>` |
| Immutable structures | `record` classes with compact-constructor validation |
| Lists, sets and maps | `List`, `Set` and `Map`, defensively copied (`List.copyOf`, `Set.copyOf`, `Map.copyOf`) and exposed unmodifiable |
| Duration | `java.time.Duration` |
| Object filter / partition matcher | `java.util.function.Predicate` / `BiPredicate` |
| Namespace customization callback | `java.util.function.Consumer<SchemaReconciler.Builder>` |
| Execution model | Synchronous, blocking methods; completion means the operation has finished |
| Logging | `System.Logger` named `studio.epicquest.schema.reconciler`; no logging-framework dependency |

Public methods do not return `null`. Null arguments are rejected with `NullPointerException`; other argument-contract violations use `IllegalArgumentException`. Public signatures expose only Java SE types and types defined by this project.

`SchemaReconciler` is a `final` class constructed by `SchemaReconciler.builder()`. Its operation names are the Java spellings of the specification: `plan()`, `planTimed()`, `reconcile()`, `apply(ReconcilePlan)`, `applyJson(String)` and `dump()`. `MultiNamespaceReconciler.builder()` is the multi-namespace entry point. `DesiredState` is a record with factories `ofScript(String)`, `ofResource(ClassLoader, String)` and `ofPath(Path)`; resource and file content is read once as UTF-8.

The exact Java signatures and member-level contracts are carried by the source and generated Javadoc. The Javadoc is part of this binding specification by reference.

## JDBC transaction and driver model

The specification's abstract auto-commit session model maps directly to JDBC `Connection.getAutoCommit()`, `setAutoCommit(boolean)`, `commit()` and `rollback()`. The application supplies the JDBC driver and configured `DataSource`; neither is a core dependency. A separate scratch database is represented by a second `DataSource` passed to `scratchDataSource`.

The whole PostgreSQL desired script is submitted through JDBC in one `Statement.execute` call in the reference dialect. PostgreSQL parses the multi-statement string server-side, so the library does not split dollar-quoted bodies.

## Dialect SPI and discovery

Dialect implementers implement `studio.epicquest.schema.reconciler.spi.Dialect`. Java `default` methods provide the specification's optional operations (`coreMajorVersion`, `bindSessionNamespace`, `statementTimeout`, `checkAccess`, options-aware `render`, `renderRename`, `dumpStatements`, `coveredChildren` and `nameScopes`). `RenderOptions` is a record containing `concurrentIndexes` and `renamedParents`.

Automatic selection uses `ServiceLoader.load(Dialect.class)`: when no dialect is configured explicitly, the single candidate whose `supports(DatabaseMetaData)` accepts the target connection is selected on first use. Zero or several matches, or a mismatch by an explicitly configured dialect, is a `ConfigurationException`.


## Java API and SPI declarations

The following declarations state the Java spelling of the logical structures and operations defined by the root specification. Imports are omitted; types are in the packages listed above. Ellipses denote implementation bodies, not optional behaviour.

### Application entry point

```java
package studio.epicquest.schema.reconciler;

public final class SchemaReconciler {

    public static Builder builder() { ... }

    /** Computes a plan without changing the target namespace. */
    public ReconcilePlan plan() { ... }
    /** Like plan(), also returning the phase timings of the planning run (3.5). */
    public TimedPlan planTimed() { ... }

    /** Plans and applies under the configured policy, holding one namespace lock. */
    public ReconcileReport reconcile() { ... }

    /** Applies a plan previously returned by plan(); fails if the target has changed since. */
    public ReconcileReport apply(ReconcilePlan plan) { ... }

    /** Deserialises a JSON plan produced by ReconcilePlan.toJson() and applies it (4.4). */
    public ReconcileReport applyJson(String serializedPlan) { ... }

    /** Reads the target namespace under the lock and returns an equivalent desired-state script. */
    public String dump() { ... }

    public static final class Builder {
        public Builder dataSource(DataSource dataSource) { ... }          // required
        public Builder namespace(String targetNamespace) { ... }          // required
        public Builder desiredState(DesiredState desiredState) { ... }    // required for plan/reconcile/apply/applyJson; optional for dump()
        public Builder dialect(Dialect dialect) { ... }                   // optional, see 3.4
        public Builder scratchDataSource(DataSource dataSource) { ... }   // optional, see 6.3
        public Builder policy(ReconcilePolicy policy) { ... }             // default ReconcilePolicy.defaults()
        public Builder lockTimeout(Duration timeout) { ... }              // default 60 s
        public Builder statementTimeout(Duration timeout) { ... }         // optional, unbounded by default
        public Builder concurrentIndexes(boolean enabled) { ... }         // optional, default false (6.4)
        public Builder rename(ObjectId from, ObjectId to) { ... }         // optional, repeatable explicit rename hint
        public Builder retire(ObjectId id) { ... }                        // optional, repeatable targeted retirement
        public Builder externalPartitions(String parentTable) { ... }     // optional, repeatable coexistence rule
        public Builder externalPartitions(String parentTable, String namePrefix) { ... }
        public Builder externalPartitions(BiPredicate<String, String> matcher) { ... }
        public Builder pruneFilter(Predicate<ObjectId> allowPrune) { ... } // optional, default allows every prune
        public Builder hook(SchemaHook hook) { ... }                      // optional, repeatable lifecycle hook
        public Builder hookStore(HookStore hookStore) { ... }             // optional run-once hook records
        public Builder listener(ReconcileListener listener) { ... }       // optional
        public Builder filter(Predicate<ObjectId> include) { ... }        // optional, default includes all objects
        public SchemaReconciler build() { ... }
    }
}
```

### Desired state

```java
public record DesiredState(String script) {
    public static DesiredState ofScript(String sql) { ... }
    public static DesiredState ofResource(ClassLoader loader, String resourceName) { ... }
    public static DesiredState ofPath(Path path) { ... }
}
```

### Policy

```java
public enum Decision { APPLY, REPORT, FAIL }

public record ReconcilePolicy(
        Decision additive,                          // default APPLY
        Decision rebuild,                           // default APPLY
        Decision destructive,                       // default FAIL
        Decision unsupported,                       // default FAIL; APPLY is not permitted
        Map<String, Decision> unsupportedCategories,// per-category overrides; APPLY is not permitted
        boolean prune) {                            // default false; opt-in DROP for UNEXPECTED objects

    public static ReconcilePolicy defaults() { ... }
    public static ReconcilePolicy reportOnly() { ... }  // REPORT for every class, prune = false

    public ReconcilePolicy withDestructive(Decision decision) { ... }
    public ReconcilePolicy withUnsupportedCategory(String category, Decision decision) { ... }
    public ReconcilePolicy withPrune(boolean prune) { ... }
    // with... for each component
}
```

### Run results and timings

```java
public enum ReconcileOutcome { UP_TO_DATE, RECONCILED, REPORTED_ONLY }

public record ReconcileReport(
        ReconcileOutcome outcome,
        ReconcilePlan plan,
        List<PlannedChange> applied,
        List<PlannedChange> reported,
        List<Difference> unexpected,
        List<Difference> unsupported,   // UNSUPPORTED differences reported under policy REPORT (3.3)
        Duration elapsed,
        RunTimings timings) { }

public record RunTimings(
        Duration lockWait,      // time spent acquiring the namespace lock — contention shows here
        Duration readActual,    // catalog read of the target namespace
        Duration loadDesired,   // scratch namespace + script + snapshot + cleanup
        Duration plan,          // diff, render and plan assembly
        Duration apply,         // the window in which applied DDL holds its locks
        Duration verify) { }    // post-apply snapshot re-read and comparison
```

### Run listener

```java
public interface ReconcileListener {
    default void planned(ReconcilePlan plan) { }
    default void applying(PlannedChange change) { }
    default void applied(PlannedChange change, Duration elapsed) { }
    default void reported(PlannedChange change) { }
}
```

### Multi-namespace API

```java
public final class MultiNamespaceReconciler {

    public static Builder builder() { ... }

    /** One plan() per namespace, in configured order. */
    public Map<String, ReconcilePlan> plan() { ... }

    /** One reconcile() per namespace in configured order; fails fast on the first exception. */
    public MultiReconcileReport reconcile() { ... }

    /** One dump() per namespace, in configured order. */
    public Map<String, String> dump() { ... }

    public static final class Builder {
        // shared defaults forwarded to each namespace's SchemaReconciler.Builder
        public Builder dataSource(DataSource dataSource) { ... }          // required
        public Builder scratchDataSource(DataSource dataSource) { ... }   // optional, see 6.3
        public Builder dialect(Dialect dialect) { ... }                   // optional, see 3.4
        public Builder policy(ReconcilePolicy policy) { ... }             // default ReconcilePolicy.defaults()
        public Builder lockTimeout(Duration timeout) { ... }              // default 60 s
        public Builder statementTimeout(Duration timeout) { ... }         // optional, unbounded by default
        public Builder concurrentIndexes(boolean enabled) { ... }         // optional, default false (6.4)
        public Builder externalPartitions(String parentTable) { ... }     // optional, repeatable coexistence rule
        public Builder externalPartitions(String parentTable, String namePrefix) { ... }
        public Builder externalPartitions(BiPredicate<String, String> matcher) { ... }
        public Builder pruneFilter(Predicate<ObjectId> allowPrune) { ... } // optional, default allows every prune
        public Builder hookStore(HookStore hookStore) { ... }             // optional run-once hook records
        public Builder listener(ReconcileListener listener) { ... }       // optional
        public Builder filter(Predicate<ObjectId> include) { ... }        // optional, default includes all objects

        /** Registers a target namespace, preserving execution order. */
        public Builder namespace(String namespace) { ... }                                // dump()-only usage
        public Builder namespace(String namespace, DesiredState desiredState) { ... }
        public Builder namespace(String namespace, DesiredState desiredState,
                                 Consumer<SchemaReconciler.Builder> customize) { ... }
        public MultiNamespaceReconciler build() { ... }
    }
}

public record MultiReconcileReport(
        ReconcileOutcome outcome,
        Map<String, ReconcileReport> reports,   // insertion-ordered by configured namespace order
        Duration elapsed) { }
```

### Lifecycle hooks

```java
public enum HookPhase { PRE_APPLY, POST_APPLY, POST_COMMIT }

public record HookContext(String namespace, String dialectId, HookPhase phase,
        ReconcilePlan plan, List<PlannedChange> applied, Connection connection) { }

public interface SchemaHook {
    String id();
    HookPhase phase();
    default int order() { return 0; }
    default boolean runOnce() { return false; }
    default String checksum() { return ""; }
    void run(HookContext context) throws Exception;

    static SchemaHook of(String id, HookPhase phase, HookAction action) { ... }
    static SchemaHook of(String id, HookPhase phase, int order, HookAction action) { ... }
    static SchemaHook runOnce(String id, HookPhase phase, String checksum, HookAction action) { ... }
    static SchemaHook runOnce(String id, HookPhase phase, int order, String checksum, HookAction action) { ... }
}

@FunctionalInterface
public interface HookAction {
    void run(HookContext context) throws Exception;
}

public interface HookStore {
    Optional<String> recordedChecksum(Connection conn, String namespace, String hookId) throws SQLException;
    void record(Connection conn, String namespace, String hookId, HookPhase phase, String checksum) throws SQLException;
    default Optional<ObjectId> managedTable(String targetNamespace) { return Optional.empty(); }
}
```

### Object identity

```java
public enum ObjectKind {
    TABLE, COLUMN, PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK,
    INDEX, VIEW, SEQUENCE, ROUTINE, TRIGGER, OTHER, EXCLUSION,
    MATERIALIZED_VIEW, TYPE, DOMAIN, POLICY, EXTENSION, COMMENT,
    STATISTICS, RULE, COLLATION, CONVERSION, SECURITY_LABEL,
    PARTITION, FOREIGN_TABLE, PUBLICATION, OWNERSHIP, PRIVILEGES,
    CAST, TRANSFORM, AGGREGATE, DEFAULT_PRIVILEGES,
    TEXT_SEARCH_TEMPLATE, TEXT_SEARCH_PARSER, TEXT_SEARCH_DICTIONARY,
    TEXT_SEARCH_CONFIGURATION, OPERATOR, OPERATOR_CLASS, OPERATOR_FAMILY
}

/** Namespace-neutral identity. */
public record ObjectId(ObjectKind kind, String name, Optional<String> parent) { }
```

### Schema objects and snapshots

```java
public record Definition(String canonical) { }   // see 5.3

public record SchemaObject(
        ObjectId id,
        Definition definition,
        Set<ObjectId> dependsOn,                  // objects this one requires
        Map<String, String> attributes) { }       // informational only, never compared

public record SchemaSnapshot(
        Map<ObjectId, SchemaObject> objects,
        String fingerprint) { }                   // see 5.7
```

### Differences

```java
public enum DifferenceType { MISSING, DRIFTED, UNEXPECTED, UNSUPPORTED }

public record Difference(
        ObjectId id,
        DifferenceType type,
        Optional<Definition> expected,
        Optional<Definition> actual) { }
```

### Plan model

```java
public enum ChangeAction { CREATE, ALTER, DROP }
public enum ChangeSafety { ADDITIVE, REBUILD, DESTRUCTIVE }
public enum LockLevel { NONE, SHARE_UPDATE_EXCLUSIVE, SHARE, SHARE_ROW_EXCLUSIVE, ACCESS_EXCLUSIVE }

public record ChangeHazard(
        LockLevel lockLevel,
        boolean rewritesTable,
        boolean validatesRows,
        List<String> notes) {
    public static final ChangeHazard NONE = new ChangeHazard(LockLevel.NONE, false, false, List.of());
}

public record PlannedChange(
        int order,                          // 1-based execution order
        ObjectId target,
        ChangeAction action,
        ChangeSafety safety,
        List<String> statements,            // final SQL, already bound to the target namespace
        Optional<Definition> expected,      // definition the target must have after apply; empty for DROP changes and changes against unexpected objects
        String reason,                      // human-readable cause, e.g. "definition drift", "dependency of <id>"
        boolean nonTransactional,           // true when change must run post-commit with autoCommit=true (e.g. CONCURRENTLY)
        ChangeHazard hazard) { }            // estimated lock/rewrite/validation hazard metadata

public record ReconcilePlan(
        String targetNamespace,
        String dialectId,
        String actualFingerprint,
        String desiredFingerprint,
        List<Difference> differences,
        List<PlannedChange> changes) {

    public boolean isEmpty() { return changes.isEmpty(); }

    /** All statements in order, each preceded by a comment with its order,
        target, action, safety, hazard and reason; manual changes are marked MANUAL. */
    public String toSql() { ... }

    /** Deterministic JSON representation (schemaVersion: 1) for machine review and cross-process apply. */
    public String toJson() { ... }

    /** Parses and validates a JSON plan produced by toJson(). */
    public static ReconcilePlan fromJson(String json) { ... }
}
```

### Dialect SPI

```java
package studio.epicquest.schema.reconciler.spi;

public interface Dialect {

    /** Marker attributes read by the core (never part of equality). */
    String EXTERNAL_DEPENDENT_ATTRIBUTE = "externalDependent";
    String CONTENT_COMPARED_ATTRIBUTE = "contentCompared";

    /** Stable identifier, e.g. "postgresql". */
    String id();

    boolean supports(DatabaseMetaData metaData) throws SQLException;

    /** The major version of the core API this dialect was built against (10.3). */
    default int coreMajorVersion() { return 1; }

    DialectCapabilities capabilities();

    void acquireLock(Connection connection, String namespace, Duration timeout) throws SQLException;

    void releaseLock(Connection connection, String namespace) throws SQLException;

    /**
     * Binds unqualified names on this connection to the given namespace for the
     * rest of the run (for example SET LOCAL search_path). Called before executing the
     * desired script and before applying changes; a no-op for dialects that
     * fully qualify every rendered statement. The binding must not outlive the run:
     * transactional dialects should use a transaction-scoped setting; others must
     * restore the previous value on every exit path.
     */
    default void bindSessionNamespace(Connection connection, String namespace) throws SQLException { }

    /**
     * Bounds how long any single statement may run during this run (for
     * example SET LOCAL statement_timeout). Called once after the namespace
     * lock is acquired, so it never bounds the lock wait itself. A no-op for
     * dialects that cannot express the bound.
     */
    default void statementTimeout(Connection connection, Duration timeout) throws SQLException { }

    /**
     * Verifies that this connection's user holds the privileges a reconcile run
     * needs. Called once per run, before the lock, on the target connection —
     * with {@code createNamespace} telling it whether that connection must
     * also create the scratch namespace — and again on the scratch connection
     * when a separate {@code scratchDataSource} is configured, then with
     * {@code namespace} {@code null} and {@code createNamespace} {@code true}.
     * A missing namespace or insufficient privilege fails the run. A no-op
     * for dialects that cannot express the check.
     */
    default void checkAccess(Connection connection, String namespace,
                             boolean createNamespace) throws SQLException { }

    String createScratchNamespace(Connection connection, String targetNamespace) throws SQLException;

    void dropScratchNamespace(Connection connection, String scratchNamespace) throws SQLException;

    /**
     * Executes a complete script in the scratch namespace; the dialect decides
     * whether to batch or split it. scratchNamespace is the name
     * createScratchNamespace returned — qualified object references in the
     * script must resolve to it (3.2).
     */
    void executeScript(Connection connection, String script, String scratchNamespace) throws SQLException;

    /** Reads every object in the namespace, normalised per 5.3. */
    SchemaSnapshot readSnapshot(Connection connection, String namespace) throws SQLException;

    /** Renders the statements and safety class for one difference, bound to the target namespace.
     *  For UNEXPECTED differences (prune), the snapshot argument is the actual snapshot. */
    List<PlannedChange> render(Difference difference, SchemaSnapshot snapshot, String targetNamespace);

    /** Renders with explicit options (e.g. concurrentIndexes). Defaults to 3-arg render. */
    default List<PlannedChange> render(Difference difference, SchemaSnapshot snapshot,
                                       String targetNamespace, RenderOptions options) {
        return render(difference, snapshot, targetNamespace);
    }

    /** Renders an explicit rename (from -> to) as a REBUILD ALTER change. */
    default List<PlannedChange> renderRename(ObjectId from, ObjectId to,
                                             SchemaSnapshot actual, SchemaSnapshot desired,
                                             String targetNamespace) {
        throw new PlanningException("dialect '" + id() + "' does not support rename hints");
    }

    /**
     * Create-fragment statements for one snapshot object, written with the
     * {@code ${namespace}} token (3.2) so the emitted script is
     * namespace-neutral — the building block of {@code dump()} (3.1). An
     * object that cannot be represented as a create fragment returns an
     * empty list; the core emits it as a comment line instead of dropping
     * it silently (P4).
     */
    default List<String> dumpStatements(SchemaObject object) { return List.of(); }

    /**
     * Quotes an identifier for safe inclusion in SQL. Throws
     * IllegalArgumentException if the identifier is not legal in the engine
     * (for example empty, longer than the engine's limit, or containing
     * characters the engine cannot represent); the core relies on this to
     * validate the configured namespace (8.3).
     */
    String quoteIdentifier(String identifier);

    /**
     * The objects, other than the difference's own object, that this dialect's
     * render output for a MISSING difference creates as a side effect (for
     * example the columns of a new table). The core suppresses their own
     * differences and changes (5.8.4). Empty means the create covers nothing
     * else.
     */
    default Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) { return Set.of(); }

    /**
     * The scopes in which the engine requires this object's name to be unique,
     * as opaque keys (for example "relation:plans_pk"). Two objects with different
     * ObjectIds that share a key cannot coexist in one namespace (5.8.7).
     * Empty means the name is unique only within its ObjectId.
     */
    default Set<String> nameScopes(ObjectId id) { return Set.of(); }
}

public record DialectCapabilities(
        boolean transactionalDdl,
        boolean sessionDefaultNamespace,
        Set<ObjectKind> supportedKinds) { }
```

`RenderOptions` is a record declared in `studio.epicquest.schema.reconciler.spi` with `(boolean concurrentIndexes, Map<String, String> renamedParents)`; `RenderOptions.DEFAULT` is `(false, empty)`. `TimedPlan` is a record declared in the API package with `(ReconcilePlan plan, RunTimings timings)`. `RunTimings.unmeasured()` returns zero timings for reports constructed outside an instrumented run.

## Error mapping

The specification's abstract `ReconcileError` is represented by the unchecked base class `SchemaReconcilerException`, which extends `RuntimeException` and carries `RunTimings`.

| Specification error | Java class |
|---|---|
| `ConfigurationError` | `studio.epicquest.schema.reconciler.ConfigurationException` |
| `LockTimeoutError` | `LockTimeoutException` |
| `DesiredStateError` | `DesiredStateException` |
| `PlanningError` | `PlanningException` |
| `PolicyViolationError` | `PolicyViolationException` (carries the rejected `ReconcilePlan`) |
| `StalePlanError` | `StalePlanException` |
| `ApplyError` | `ApplyException` (carries applied changes and the failed change when known) |
| `VerificationError` | `VerificationException` (carries remaining differences) |

`UnsafePlanError`, `UnsupportedConstructError` and `DialectError` are the specification's abstract categories/reasons, not additional Java classes: unsafe-plan failures surface as `PolicyViolationException` or `StalePlanException`; unsupported constructs are differences and policy failures as specified; JDBC/driver failures are wrapped causes rather than leaking as `SQLException`.

## Lifecycle hooks and PostgreSQL support

`SchemaHook` is a Java interface with `id()`, `phase()`, default `order()`, `runOnce()` and `checksum()`, plus static factories `of(...)` and `runOnce(...)`. `HookAction` is the functional callback. `HookStore` uses JDBC `Connection` arguments and `Optional` results.

The PostgreSQL implementation is `studio.epicquest.schema.reconciler.postgresql.PostgreSqlDialect`. Its hook store is `studio.epicquest.schema.reconciler.postgresql.PgHookStore`, constructed with `PgHookStore.inSchema(schema)` or `PgHookStore.of(schema, table)` (`schema_reconciler_hooks` by default).

## Conformance suite

`studio.epicquest.schema:schema-reconciler-tck` provides the abstract JUnit Jupiter class `studio.epicquest.schema.reconciler.tck.DialectConformanceTest`. A dialect implementation supplies a disposable database and lifecycle hooks in a subclass; the scenarios are C-01 through C-67 of the root specification. `TrackingDataSource` supports session-leak assertions. Skips are allowed only for undeclared kinds/capabilities and must be reported.

## Compatibility

The Java binding follows the root specification's versioning rules. Within a major version the exported packages remain source- and binary-compatible; new SPI methods are added as `default` methods where the specification evolution rules permit. Before specification version 1.0, dialects should be recompiled and revalidated against each release.
