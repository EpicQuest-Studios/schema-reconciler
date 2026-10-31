# schema-reconciler — TypeScript

TypeScript port of [SPEC.md](../SPEC.md), following the normative
binding in **Appendix D**. See the [root README](../README.md) for what
schema reconciliation is and how the engine works.

## Packages

| Package | Contents |
|---|---|
| `@schema-reconciler/core` | API, SPI, core algorithm (Promise-based; see SPEC.md Appendix D) |
| `@schema-reconciler/postgresql` | PostgreSQL dialect |
| `@schema-reconciler/tck` | Dialect conformance suite (`node:test`) |

Zero runtime dependencies. Packages ship `.ts` sources and run on
Node's native type stripping — **Node ≥ 22.18** required.

## Getting started

```bash
npm install @schema-reconciler/postgresql   # brings @schema-reconciler/core transitively
```

Implementing a dialect for another database? Depend on
`@schema-reconciler/core`, and use `@schema-reconciler/tck` to run the
conformance suite against it.

## Usage

```ts
import { SchemaReconciler, DesiredState } from "@schema-reconciler/core";
import { PostgreSqlDialect } from "@schema-reconciler/postgresql";

const reconciler = SchemaReconciler.builder()
        .dataSource(dataSource)             // your SqlDataSource adapter
        .namespace("svc")
        .desiredState(DesiredState.ofPath("db/schema.sql"))
        .dialect(new PostgreSqlDialect())
        .build();

const report = await reconciler.reconcile();
console.log(`schema svc: ${report.applied.length} applied, `
        + `${report.reported.length} reported, `
        + `${report.unexpected.length} unexpected`);
```

Dry run for review:

```ts
import { writeFile } from "node:fs/promises";

const plan = await reconciler.plan();
await writeFile("schema-plan.sql", plan.toSql());
```

Bootstrap a desired-state script from an existing schema (no `desiredState` needed):

```ts
const dumper = SchemaReconciler.builder()
        .dataSource(dataSource)
        .namespace("svc")
        .build();
const script = await dumper.dump();   // namespace-neutral desired-state script
```

`dump()` reads the catalog under the namespace lock and renders it as a `${namespace}`-placeholder script in dependency order — feeding it back via `DesiredState.ofScript(script)` converges the same schema with an empty plan. Objects the dialect cannot express as DDL become `-- folded:` / `-- unsupported:` comment lines rather than being silently dropped.

The connecting role can be least-privilege:

```sql
GRANT USAGE, CREATE ON SCHEMA svc TO app_reconciler;
GRANT CREATE ON DATABASE appdb TO app_reconciler;  -- scratch replay only; see SPEC.md A.4
```

Environments that cannot grant `CREATE ON DATABASE` point `scratchDataSource` at a separate scratch database instead.

Objects managed by another tool can be left out of the reconcile entirely:

```ts
const scoped = SchemaReconciler.builder()
        .dataSource(dataSource)
        .namespace("svc")
        .desiredState(desired)
        .filter(id => !id.name.startsWith("staging_"))
        .build();
```

`filter` hides the rejected objects — plus their children, mirrored annotations and followers — from both snapshots, so an externally-managed object produces no difference, no change and no unexpected report, and plan staleness is scoped to what remains visible.

## Connecting a driver

The API is driver-agnostic: it works against the `SqlDataSource` /
`SqlConnection` abstraction from `@schema-reconciler/core`, with the
same transaction semantics JDBC exposes (`setAutoCommit` modeled as
explicit `BEGIN`/`COMMIT`). A complete `pg` adapter is a few lines —
see [packages/postgresql/README.md](packages/postgresql/README.md).

## Build and test

npm workspaces. From this directory:

```bash
npm run typecheck   # tsc --noEmit across all packages
npm test            # every workspace test (node:test)
```

Core tests run against an in-memory fake database (`FakeDb`) — no real DB needed. The PostgreSQL tests use the `pg` driver against a PostgreSQL 14+ database and skip when none answers. Point them at one via env vars:

```bash
RECONCILER_TEST_URL=postgresql://rcl:rcl@localhost:55999/rcl npm test
```

(`RECONCILER_TEST_USER`/`RECONCILER_TEST_PASSWORD` override credentials separately.) The fixtures in [`../java/fixtures/`](../java/fixtures/) — deployment and the obfuscated schema corpus — are shared with the Java implementation; corpus-01 needs `pgvector/pgvector:pg*` for the `vector`/`pg_trgm` extension and skips otherwise.

Each package carries its own README with package-level usage:
[core](packages/core/README.md) · [postgresql](packages/postgresql/README.md) · [tck](packages/tck/README.md).

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
