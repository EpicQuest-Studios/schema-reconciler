# schema-reconciler — Go

Go port of [SPEC.md](../SPEC.md). See the [root README](../README.md)
for what schema reconciliation is and how the engine works.

## Packages

| Package | Contents |
|---|---|
| `…/go/core` (`reconciler`) | API, core algorithm (`NewBuilder`, `SchemaReconciler`, policy, reports) |
| `…/go/core/model` | Object ids/kinds, definitions, differences, plans (spec §4) |
| `…/go/core/spi` | Dialect SPI + the `database/sql` adapter (`spi.NewDB`) |
| `…/go/postgresql` | PostgreSQL 14+ dialect |
| `…/go/tck` | Dialect conformance suite (`testing`, C-01…C-67) |

Zero runtime dependencies — the core and dialect build on `database/sql`
alone. `github.com/jackc/pgx/v5` is a **test-only** dependency used by the
live PostgreSQL suite.

## Getting started

```bash
go get github.com/EpicQuest-Studios/schema-reconciler/go@v0.11.0
```

Or through the **static GOPROXY on GitHub Pages** — the same mechanism
as the Maven and npm registries:

```bash
export GOPROXY=https://epicquest-studios.github.io/schema-reconciler/go
go get github.com/EpicQuest-Studios/schema-reconciler/go@v0.11.0
```

Implementing a dialect for another database? Depend on `…/go/core` +
`…/go/core/spi`, and use `…/go/tck` to run the conformance suite against it.

### Publishing

`go get` fetches module source — the proxy can be static files. A
script writes the `@v/` layout (`list`, `<version>.info`, `.mod`,
`.zip`, `@latest`) into a checkout of the `gh-pages` branch:

```bash
scripts/publish-pages.sh --pages <checkout-of-gh-pages> --version v0.11.0
# then commit + push gh-pages — removal works the same as Maven:
# git rm the version files, drop the entry from @v/list, and push
```

## Usage

```go
db, _ := sql.Open("pgx", "postgres://app:app@localhost/appdb")
ds := spi.NewDB(db, postgresql.MetadataProbe)

desired, _ := core.DesiredStateOfPath("db/schema.sql")
reconciler, _ := core.NewBuilder().
	DataSource(ds).
	Namespace("svc").
	DesiredState(desired).
	Dialect(postgresql.Dialect{}).
	Build()

report, _ := reconciler.Reconcile(context.Background())
fmt.Printf("schema svc: %d applied, %d reported, %d unexpected\n",
	len(report.Applied), len(report.Reported), len(report.Unexpected))
```

Dry run for review:

```go
plan, _ := reconciler.Plan(context.Background())
os.WriteFile("schema-plan.sql", []byte(plan.ToSQL()), 0o644)
```

Bootstrap a desired-state script from an existing schema (no `DesiredState` needed):

```go
dumper, _ := core.NewBuilder().
	DataSource(ds).
	Namespace("svc").
	Dialect(postgresql.Dialect{}).
	Build()
script, _ := dumper.Dump(context.Background()) // namespace-neutral script
```

`Dump` reads the catalog under the namespace lock and renders it as a
`${namespace}`-placeholder script in dependency order — feeding it back
via `core.DesiredStateOfScript(script)` converges the same schema with
an empty plan. Objects the dialect cannot express as DDL become
`-- folded:` / `-- unsupported:` comment lines rather than being
silently dropped.

The connecting role can be least-privilege:

```sql
GRANT USAGE, CREATE ON SCHEMA svc TO app_reconciler;
GRANT CREATE ON DATABASE appdb TO app_reconciler;  -- scratch replay only; see SPEC.md A.4
```

Environments that cannot grant `CREATE ON DATABASE` point
`ScratchDataSource` at a separate scratch database instead.

Objects managed by another tool can be left out of the reconcile entirely:

```go
scoped, _ := core.NewBuilder().
	DataSource(ds).
	Namespace("svc").
	DesiredState(desired).
	Dialect(postgresql.Dialect{}).
	Filter(func(id model.ObjectId) bool {
		return !strings.HasPrefix(id.Name, "staging_")
	}).
	Build()
```

`Filter` hides the rejected objects — plus their children, mirrored
annotations and followers — from both snapshots, so an
externally-managed object produces no difference, no change and no
unexpected report, and plan staleness is scoped to what remains visible.

## Connecting a driver

The API is driver-agnostic: `spi.DB`/`spi.Conn` abstract `database/sql`,
with the same session semantics JDBC exposes (`AutoCommit`/`SetAutoCommit`
modeled as explicit `BEGIN`/`COMMIT`). `spi.NewDB(*sql.DB, probe)` adapts
any `database/sql` driver that honours those semantics — the tests use
`github.com/jackc/pgx/v5/stdlib`.

## Build and test

From this directory:

```bash
go generate ./...   # sync sql/postgresql/*.sql for //go:embed
go vet ./...
go test ./...
```

Core tests run against an in-memory fake database — no real DB needed.
The PostgreSQL tests use the pgx driver against a PostgreSQL 14+
database and skip when none answers. Point them at one via env vars:

```bash
RECONCILER_TEST_URL=postgres://rcl:rcl@localhost:55999/rcl?sslmode=disable \
    go test ./...
```

(`RECONCILER_TEST_USER`/`RECONCILER_TEST_PASSWORD` override credentials
separately.) The fixtures in [`../java/fixtures/`](../java/fixtures/) —
deployment and the obfuscated schema corpus — are shared with the Java
and TypeScript implementations; corpus-01 needs `vector`/`pg_trgm` and
skips otherwise.

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
