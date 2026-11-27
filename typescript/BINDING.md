# Schema Reconciler — TypeScript binding

This document is the normative TypeScript binding of the language-neutral [schema-reconciler specification](../SPEC.md). Everything the root specification requires applies unchanged; this binding transforms only the API *surface*, never the behaviour.

| | |
|---|---|
| Specification version | 0.11 |
| Implementation version | 0.11.0 |
| Runtime baseline | Node.js 22.18 or later |
| Module format | Compiled ESM plus TypeScript declarations |
| Test runner | `node:test` |

## Packages

| npm package | Contents |
|---|---|
| `@epicquest-studios/schema-reconciler-core` | API, SPI, model and reconciliation engine |
| `@epicquest-studios/schema-reconciler-postgresql` | PostgreSQL 14+ reference dialect |
| `@epicquest-studios/schema-reconciler-tck` | Dialect conformance suite for `node:test` |

The packages have zero runtime dependencies. Published packages ship compiled ESM (`dist/*.js`) plus declaration files, because Node does not type-strip `.ts` files inside `node_modules`. In this repository sources use erasable TypeScript syntax only (no `enum`, `namespace` or parameter properties), so tests can run directly under Node's native type stripping while `tsc --noEmit` performs typechecking.

## Asynchrony

Every API or SPI operation that performs I/O returns a `Promise` and is awaited at the same point at which a synchronous implementation would block. This applies to `SqlConnection` operations, dialect catalog/script methods, `plan()`, `planTimed()`, `reconcile()`, `apply()`, `applyJson()` and `dump()`. Phase order, error types and listener callbacks are unchanged.

`Dialect.supports`, `render`, `quoteIdentifier`, `capabilities`, `nameScopes`, `coveredChildren` and `dumpStatements` are synchronous, matching their non-I/O character. `ReconcileListener` callbacks may return `void` or `Promise<void>` and are always awaited before the run continues.

## Platform type mapping

| Specification concept | TypeScript representation |
|---|---|
| Data source | `SqlDataSource` (`connect(): Promise<SqlConnection>`) |
| Database session | `SqlConnection`; one dedicated session per connection — for example a `pg.PoolClient`, never a shared pool handle |
| Database metadata | `DatabaseMetadata { databaseProductName, databaseMajorVersion }` |
| Auto-commit transaction model | `getAutoCommit()`, `setAutoCommit(on)`, `commit()`, `rollback()`; `setAutoCommit(false)` maps to explicit `BEGIN`, and `commit`/`rollback` end the transaction |
| `T or absent` | `T | undefined` |
| Lists | `readonly T[]` |
| Sets and maps | `ReadonlySet<T>`/`ObjectSet` and `ReadonlyMap<K,V>`/`ObjectMap`; `ObjectMap` keys objects by value semantics where needed |
| Immutable structures | Classes with `readonly` fields, defensive copies and `Object.freeze`; collection contents are shallow-frozen at runtime and read-only at compile time |
| Enumerations | `const` object plus union type, in the same declaration order as the specification so ordinal semantics are preserved |
| Duration | `Duration` with `ofMillis`, `ofSeconds`, `ofNanos` and `ZERO`, stored as fractional milliseconds |
| Diagnostic logging | `console.warn` |

`ObjectSet`/`ObjectMap` exist because JavaScript objects do not natively key by value equality for structured object ids. Plan metadata retention uses a `WeakMap` keyed by the plan object, giving identity-keyed, garbage-collected retention.

## API deltas

- `DesiredState.ofResource` has no analog because Node has no classpath. `DesiredState.ofScript` and `DesiredState.ofPath` cover the required construction contract.
- Dialect discovery is the explicit `registerDialect`, `unregisterDialect` and `registeredDialects` registry plus the builder's `dialect` option. Selection semantics are unchanged: explicit dialect first (still verified), otherwise the single registered candidate whose `supports` accepts the connection metadata; zero or several matches fail as configuration errors.
- `ReconcileListener.NONE` is replaced by the builder's default empty listener object.
- `externalPartitions(parentTable, match?)` accepts a prefix string, `RegExp`, partition predicate or `(parentTable, partitionName)` predicate. `pruneFilter`, `retire` and `rename` use the same object-id semantics as the other bindings.
- Hooks are async-capable objects `{ id, phase, order?, runOnce?, checksum?, run(ctx) }`. `HookContext.connection` exposes the `HookSqlExecutor` surface (`execute`/`query`). `PgHookStore.inSchema(schema)` and `PgHookStore.of(schema, table?)` implement `HookStore` for PostgreSQL.
- `int8` catalog values may arrive as strings because that is the `pg` driver's precision-safe default; identity and sequence-option reads use exact-string semantics so rendered SQL preserves the full value.

## Error mapping

The specification's abstract `ReconcileError` is represented by `SchemaReconcilerException` (despite the historical class name, it is the TypeScript domain-error base and extends `Error`). It carries `timings`.

| Specification error | TypeScript class |
|---|---|
| `ConfigurationError` | `ConfigurationException` |
| `LockTimeoutError` | `LockTimeoutException` |
| `DesiredStateError` | `DesiredStateException` |
| `PlanningError` | `PlanningException` |
| `PolicyViolationError` | `PolicyViolationException` |
| `StalePlanError` | `StalePlanException` |
| `ApplyError` | `ApplyException` |
| `VerificationError` | `VerificationException` |

Argument-contract violations use `IllegalArgumentError`; null/wrong-type boundary failures use `TypeError`. Driver failures are wrapped as causes in the phase-appropriate domain error rather than leaking directly. `UnsafePlanError`, `UnsupportedConstructError` and `DialectError` remain the root specification's abstract categories/reasons, not additional concrete classes.

## PostgreSQL driver integration

The dialect is driver-agnostic and operates against `SqlDataSource`/`SqlConnection`. The application supplies the driver adapter. A complete `pg` adapter is documented in [packages/postgresql/README.md](packages/postgresql/README.md); its central requirements are dedicated sessions, correct transaction-state handling and faithful row/value decoding. `PostgreSqlDialect` is the reference dialect implementation.

## Conformance

`@epicquest-studios/schema-reconciler-tck` exposes `dialectConformanceSuite(spec)`, the same C-01 through C-67 scenario identifiers defined by the root specification, executed under `node:test`. Fixture files, PostgreSQL script-check rule lists, fuzz templates and expected behaviour are shared with the other implementations. Skips are allowed only for undeclared kinds/capabilities and must be reported.
