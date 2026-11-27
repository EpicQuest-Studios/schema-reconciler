# Schema Reconciler — Go binding

This document is the normative Go binding of the language-neutral [schema-reconciler specification](../SPEC.md). Behavioural semantics are defined by the root specification; this document defines their idiomatic Go representation.

| | |
|---|---|
| Specification version | 0.11 |
| Implementation version | v0.11.0 |
| Module | `github.com/EpicQuest-Studios/schema-reconciler/go` |
| Go baseline | Go 1.24 or later |
| Database access | `database/sql` through the SPI's `spi.DB`/`spi.Conn` abstraction |
| Test runner | the standard `testing` package |

## Packages

| Import path | Package name | Contents |
|---|---|---|
| `github.com/EpicQuest-Studios/schema-reconciler/go/core` | `reconciler` | Application API, engine, policy, reports, hooks, errors and dialect registry |
| `github.com/EpicQuest-Studios/schema-reconciler/go/core/model` | `model` | Object ids/kinds, definitions, differences, snapshots, plans and JSON serialization |
| `github.com/EpicQuest-Studios/schema-reconciler/go/core/spi` | `spi` | Dialect SPI, connection abstraction and `database/sql` adapters |
| `github.com/EpicQuest-Studios/schema-reconciler/go/postgresql` | `postgresql` | PostgreSQL 14+ reference dialect |
| `github.com/EpicQuest-Studios/schema-reconciler/go/tck` | `tck` | C-01 through C-67 conformance-suite runner |

The core and dialect have zero non-standard runtime dependencies. `github.com/jackc/pgx/v5` is a test-only dependency used by the live PostgreSQL tests; applications may use any `database/sql` driver that satisfies the session semantics.

## Execution and error model

Every operation that performs I/O accepts `context.Context` as its first argument and returns Go `(value, error)` or `error`. Cancellation and deadlines propagate through the context, but cancellation cannot reorder the phases required by the root specification. The same invocation ordering and completion semantics apply as in a synchronous binding.

Errors are values. The concrete error types embed `SchemaReconcilerError`, implement `Unwrap`, and support classification with `errors.Is`/`errors.As`. Sentinel values (`ErrConfiguration`, `ErrPlanning`, and so on) classify by error type; `ErrSchemaReconciler` matches every reconciler domain failure.

| Specification error | Go type / sentinel |
|---|---|
| `ReconcileError` | `SchemaReconcilerError` / `ErrSchemaReconciler` |
| `ConfigurationError` | `ConfigurationError` / `ErrConfiguration` |
| `LockTimeoutError` | `LockTimeoutError` / `ErrLockTimeout` |
| `DesiredStateError` | `DesiredStateError` / `ErrDesiredState` |
| `PlanningError` | `PlanningError` / `ErrPlanning` |
| `PolicyViolationError` | `PolicyViolationError` / `ErrPolicyViolation` |
| `StalePlanError` | `StalePlanError` / `ErrStalePlan` |
| `ApplyError` | `ApplyError` / `ErrApply` |
| `VerificationError` | `VerificationError` / `ErrVerification` |

The carried data is idiomatic Go: `PolicyViolationError.Plan`, `StalePlanError.Plan`, `ApplyError.Applied`/`Failed`, `VerificationError.Remaining` and `SchemaReconcilerError.Timings()`. `UnsafePlanError`, `UnsupportedConstructError` and `DialectError` remain the root specification's abstract categories/reasons rather than additional concrete Go types. Driver errors are wrapped causes in the phase-appropriate error.

## Type mapping

| Specification concept | Go representation |
|---|---|
| `T or absent` | A pointer, `(value, bool)` result, empty string when the specification makes empty invalid, or nil interface — as documented per API |
| Lists | Slices; constructors and builders defensively copy inputs where mutation could violate immutability |
| Sets | `model.ObjectSet` or map-based sets |
| Maps | Go maps; exposed copies where mutation would violate immutability |
| Duration | `time.Duration` |
| Object identity | `model.ObjectId`, a comparable struct suitable as a map key |
| Enumerations | Typed integer constants with `String`/parse support, declared in specification order |
| Immutable structures | Exported structs treated as immutable values; constructors validate invariants, shared slices are copied, and mutation after construction is outside the API contract |
| Invalid arguments | `model.InvalidArgumentError`/`ErrInvalidArgument` rather than a domain reconcile error |

`ObjectId.Parent` uses the empty string for "absent" because the specification forbids an empty present parent. Lexicographic ordering follows the cross-language ordering rules of the specification, including UTF-16-compatible comparison where required for deterministic plans.

## Database abstraction and `database/sql`

`spi.DB` is the Go analog of the abstract data source: `Conn(ctx)` returns one pinned `spi.Conn`. `spi.Conn` exposes statement execution/query methods, metadata, auto-commit state, commit/rollback and close. `spi.DatabaseMetadata` carries `DatabaseProductName` and `DatabaseMajorVersion`.

`spi.NewDB(*sql.DB, probe)` adapts a standard `database/sql` pool; each run pins one `*sql.Conn`. `spi.NewConn(*sql.Conn, probe)` adapts an already pinned session. The adapter implements the specification's JDBC-like auto-commit semantics by lazily opening a `*sql.Tx` when auto-commit is disabled, routing statements through it, and ending it on commit/rollback. `postgresql.MetadataProbe` is the PostgreSQL metadata probe.

A separate scratch database is configured with `Builder.ScratchDataSource`. The application supplies the driver; the core never imports one.

## API spelling

`core.NewBuilder()` constructs the single-namespace builder. Setters use Go capitalization: `DataSource`, `Namespace`, `DesiredState`, `Dialect`, `ScratchDataSource`, `Policy`, `LockTimeout`, `StatementTimeout`, `ConcurrentIndexes`, `Rename`, `Retire`, `ExternalPartitions`, `ExternalPartitionsPrefix`, `ExternalPartitionsFunc`, `PruneFilter`, `Hook`, `HookStore`, `Listener` and `Filter`. `Build()` validates configuration and returns `(*SchemaReconciler, error)` without opening a connection.

`SchemaReconciler` operations are `Plan(ctx)`, `PlanTimed(ctx)`, `Reconcile(ctx)`, `Apply(ctx, plan)`, `ApplyJSON(ctx, serializedPlan)` and `Dump(ctx)`. `DesiredStateOfScript`, `DesiredStateOfReader` and `DesiredStateOfPath` construct desired state.

`core.NewMultiNamespaceBuilder()` constructs the multi-namespace API. Namespace registration uses `Namespace(name, WithDesiredState(...), WithCustomize(...))`; plans and dumps are returned as maps, with `Namespaces()` exposing deterministic configured order.

## Dialect SPI and registration

`spi.Dialect` contains the required operations. The specification's optional/default SPI operations are represented by small optional interfaces — `CoreVersioner`, `SessionNamespaceBinder`, `StatementTimeoutSetter`, `AccessChecker`, `RenameRenderer`, `StatementDumper`, `ChildrenCoverer` and `NameScoper` — with helper functions in `spi` applying the required fallback when an interface is absent. `spi.RenderOptions` carries `ConcurrentIndexes` and `RenamedParents`.

Go has no service-loader facility. Dialects are registered explicitly with `core.RegisterDialect` (the PostgreSQL dialect does so on package import) or supplied directly through `Builder.Dialect`. `core.RegisteredDialects` is the deterministic discovery candidate set; `core.UnregisterDialect` is available for tests. Selection semantics are unchanged: explicit first, otherwise the single candidate whose `Supports` accepts connection metadata.

## Lifecycle hooks and PostgreSQL support

`core.SchemaHook` is a struct with `ID`, `Phase`, `Order`, `RunOnce`, `Checksum` and `Run func(context.Context, HookContext) error`. `HookContext.Conn` is the run's `spi.Executor`; it is transactional for `PRE_APPLY`/`POST_APPLY` and in auto-commit mode for `POST_COMMIT`. `core.HookStore` returns `(checksum, found, error)` from `RecordedChecksum` and a nil/non-nil `*model.ObjectId` from `ManagedTable`.

The PostgreSQL dialect is `postgresql.Dialect`. Its hook store is constructed with `postgresql.NewHookStore(schema, table)`; an empty table selects `schema_reconciler_hooks`.

## Conformance

`tck.RunConformanceSuite(t, hooks)` registers scenarios C-01 through C-67 as subtests using the standard `testing` package. `tck.Hooks` supplies the data source, dialect, namespace lifecycle and optional capability hooks. Skips are allowed only for undeclared kinds/capabilities and must be visible as test skips.
