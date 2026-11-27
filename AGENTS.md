# Schema Reconciler

Vendor-neutral, language-neutral, declarative, data-safe schema reconciler (spec: `SPEC.md`) with Java, TypeScript and Go implementations sharing one contract.

## Layout

- `java/` — Maven aggregator (Java 25, Maven 3.9+): `schema-reconciler-core` (API `studio.epicquest.schema.reconciler`, model records, `Dialect` SPI), `schema-reconciler-postgresql` (PostgreSQL 14+ reference dialect), `schema-reconciler-tck` (`DialectConformanceTest`, C-01…C-67+, automatic module).
- `typescript/` — npm workspaces (Node ≥ 22.18) implementing `@epicquest-studios/schema-reconciler-{core,postgresql,tck}` under `typescript/BINDING.md`. `pg` is a test-only devDep — runtime deps are zero.
- `go/` — Go module `github.com/EpicQuest-Studios/schema-reconciler/go` (Go ≥ 1.24) implementing `core`, `core/model`, `core/spi`, `postgresql` and `tck` under `go/BINDING.md`. `pgx` is test-only — runtime deps are zero.
- `sql/postgresql/` — shared PostgreSQL catalog SQL queries loaded by Java (Maven resource), TypeScript (`dist/sql/postgresql`) and Go (`//go:embed` copy synced into `go/postgresql/internal/queries/sql/`).
- `java/fixtures/` — shared `corpus/corpus-*.sql` + `deployment/schema.sql` read by all three test suites.
- `SPEC.md` — single language-neutral normative contract; `java/BINDING.md`, `typescript/BINDING.md` and `go/BINDING.md` hold language-specific API mappings; `docs/adr/` holds architectural decision records.

## Build & Test (TypeScript)

```bash
cd typescript
npm run build        # tsc emit to dist/ + copy shared sql/postgresql
npm run typecheck    # build + tsc --noEmit
RECONCILER_TEST_URL=postgresql://rcl:rcl@localhost:55999/rcl npm test
```

- Core (`packages/core`): pure unit tests (`FakeDb`/`FakeDialect`), no DB required.
- PostgreSQL (`packages/postgresql`): needs PostgreSQL 14+ (defaults to `localhost:55999`, skips if unreachable). `node --test --test-name-pattern "C-32"` filters.

## Build & Test (Java)

```bash
RECONCILER_TEST_URL=jdbc:postgresql://localhost:55999/rcl RECONCILER_TEST_USER=rcl RECONCILER_TEST_PASSWORD=rcl mvn -f java/pom.xml verify
```

- Core: pure unit tests (`FakeDb`/`FakeDialect`).
- PostgreSQL: falls back to Testcontainers `postgres:18` when `RECONCILER_TEST_URL` is unset (or run `docker run -d --name reconciler-tck -e POSTGRES_USER=rcl -e POSTGRES_PASSWORD=rcl -e POSTGRES_DB=rcl -p 55999:5432 postgres:18`).
- `corpus-01` requires `vector`/`pg_trgm` (skips on stock `postgres:18`); `AppendixCoverageTest` / `CanonicalDefinitionTest` skip superuser-only cases when not superuser.

## Build & Test (Go)

```bash
cd go
go generate ./...   # sync shared PostgreSQL SQL for //go:embed
go vet ./...
go test ./...
```

- Core: pure unit tests (fake DB/dialect), no DB required.
- PostgreSQL: needs PostgreSQL 14+ (skips if unreachable). `RECONCILER_TEST_URL=postgres://rcl:rcl@localhost:55999/rcl?sslmode=disable go test ./...`.

## Releases

Published to `gh-pages` branch (`maven/`, `npm/` and `go/`):
- Java: `mvn -f java/pom.xml -DskipTests deploy -DaltDeploymentRepository=pages::default::file:///<gh-pages>/maven`
- TypeScript: `cd typescript && npm run build && node scripts/publish-pages.ts --pages <gh-pages>`
- Go: `cd go && scripts/publish-pages.sh --pages <gh-pages> --version v<x.y.z>`
- Versioning: the SPEC.md version sets the implementation version — `0.9.x` patch bumps for small spec changes, bump minor (`0.10.0`) for bigger spec changes (new API surface, newly modeled catalog state, new features).

## Key Invariants

- No `CASCADE` in rendered drops; recreates are `DROP`+`CREATE` pairs ordered two-phase over the dependency graph (standalone drops only when `prune` is enabled).
- `SchemaObject.attributes` carries dialect-private render fragments (`${namespace}` tokens), bound at render time.
- Canonical definitions normalize namespace refs to `${namespace}` (`PgSql.normalize`).
- `PgScriptCheck` is a **frozen, best-effort lexical guard** (not a security sandbox); untrusted scripts should use `scratchDataSource` + a least-privilege role.
- See `docs/adr/` for detailed catalog, rendering, and script-check boundaries.

## Verification Notes

- PostgreSQL catalog SQL is shared through `sql/postgresql/`; after changing it, synchronize `go/postgresql/internal/queries/sql/` (normally via `go generate`/`go/scripts/sync-sql.sh`) before running Go tests.
