# schema-reconciler — TypeScript

TypeScript port of [SPEC.md](../SPEC.md), following the normative
binding in **Appendix D**. See the [root README](../README.md) for what
schema reconciliation is and how the engine works.

## Packages

| Package | Contents |
|---|---|
| `@epicquest-studios/schema-reconciler-core` | API, SPI, core algorithm (Promise-based; see SPEC.md Appendix D) |
| `@epicquest-studios/schema-reconciler-postgresql` | PostgreSQL dialect |
| `@epicquest-studios/schema-reconciler-tck` | Dialect conformance suite (`node:test`) |

Zero runtime dependencies. Packages publish compiled ESM (`dist/*.js`)
plus declaration files — plain JavaScript, loadable on any modern Node
(Node cannot type-strip `.ts` inside `node_modules`, so sources are
dev-only; **Node ≥ 22.18** is required for working in this repo).

## Getting started

Packages publish to a **static npm registry on GitHub Pages** — the
same mechanism as the Maven repository, so installs are anonymous HTTPS
with no token for anyone. Point your project at it:

```ini
# .npmrc (in the consuming project)
@epicquest-studios:registry=https://epicquest-studios.github.io/schema-reconciler/npm
```

```bash
npm install @epicquest-studios/schema-reconciler-postgresql   # brings -core transitively
```

Implementing a dialect for another database? Depend on
`@epicquest-studios/schema-reconciler-core`, and use
`@epicquest-studios/schema-reconciler-tck` to run the
conformance suite against it.

### Publishing

`npm publish` does not apply — the registry is static files. A script
packs the tarballs, merges each version into the packument JSON
(keeping prior versions installable), and writes the registry layout
into a checkout of the `gh-pages` branch:

```bash
npm run build
node scripts/publish-pages.ts --pages <checkout-of-gh-pages>
# then commit + push gh-pages — removal works the same as Maven:
# git rm the packument/tarball and push
```

The layout it writes: `npm/tarballs/*.tgz`, plus each packument twice —
`npm/@epicquest-studios/<name>` and `npm/@epicquest-studios%2f<name>` —
because whether `%2f` decodes to a path segment depends on the CDN.
It also creates `.nojekyll` so Pages serves files raw.

## Usage

```ts
import { SchemaReconciler, DesiredState } from "@epicquest-studios/schema-reconciler-core";
import { PostgreSqlDialect } from "@epicquest-studios/schema-reconciler-postgresql";

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
`SqlConnection` abstraction from `@epicquest-studios/schema-reconciler-core`, with the
same transaction semantics JDBC exposes (`setAutoCommit` modeled as
explicit `BEGIN`/`COMMIT`). A complete `pg` adapter is a few lines —
see [packages/postgresql/README.md](packages/postgresql/README.md).

## Build and test

npm workspaces. From this directory:

```bash
npm run build       # tsc emit to each package's dist/ (needed first —
                    # package imports resolve through it)
npm run typecheck   # build + tsc --noEmit across all packages
npm test            # build + every workspace test (node:test)
```

Package `exports` resolve to `dist/` — the compiled artifact consumers
install — so tests and typecheck build first automatically. Working on
`src/` runs under native type stripping; publishing ships `dist/` only.

Core tests run against an in-memory fake database (`FakeDb`) — no real DB needed. The PostgreSQL tests use the `pg` driver against a PostgreSQL 14+ database and skip when none answers. Point them at one via env vars:

```bash
RECONCILER_TEST_URL=postgresql://rcl:rcl@localhost:55999/rcl npm test
```

(`RECONCILER_TEST_USER`/`RECONCILER_TEST_PASSWORD` override credentials separately.) The fixtures in [`../java/fixtures/`](../java/fixtures/) — deployment and the obfuscated schema corpus — are shared with the Java implementation; corpus-01 needs `pgvector/pgvector:pg*` for the `vector`/`pg_trgm` extension and skips otherwise.

Each package carries its own README with package-level usage:
[core](packages/core/README.md) · [postgresql](packages/postgresql/README.md) · [tck](packages/tck/README.md).

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
