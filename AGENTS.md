# Schema Reconciler

Vendor-neutral, declarative, data-safe schema reconciler for Java (spec: `SPEC.md`).

## Modules

- `schema-reconciler-core` — API (`studio.epicquest.schema.reconciler`), model records, `Dialect` SPI, engine internals (`internal` package, not exported).
- `schema-reconciler-postgresql` — PostgreSQL 14+ reference dialect (`studio.epicquest.schema.reconciler.postgresql`, internals in `.internal`).
- `schema-reconciler-tck` — dialect conformance suite (`DialectConformanceTest`, C-01…C-32) for third-party dialects.

## Build

Java 25, Maven 3.9+. `mvn verify` builds all three JPMS modules and runs every test.

## Tests

- Core: pure unit tests over an in-memory fake JDBC database (`FakeDb`) + `FakeDialect` — no real DB needed.
- PostgreSQL: needs a PostgreSQL 14+ database. Point tests at one via env vars:
  `RECONCILER_TEST_URL=jdbc:postgresql://localhost:55999/rcl RECONCILER_TEST_USER=rcl RECONCILER_TEST_PASSWORD=rcl mvn verify`
  Without env vars, `PgTestDatabase` falls back to a Testcontainers `postgres:18` container (requires Docker reachable by Testcontainers; on this machine `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` may be needed, or run a dedicated container: `docker run -d --name reconciler-tck -e POSTGRES_USER=rcl -e POSTGRES_PASSWORD=rcl -e POSTGRES_DB=rcl -p 55999:5432 postgres:18`).
- `DeploymentFixtureTest` migrates `src/test/resources/deployment/schema.sql` per spec A.7 at test time and reconciles it end to end.
- `CorpusFixtureTest` reconciles the obfuscated schema corpus `src/test/resources/corpus/corpus-*.sql` (same A.7 transform). corpus-01 declares `vector`/`pg_trgm` — on a plain `postgres:18` image it skips; full coverage needs `pgvector/pgvector:pg18` (or pg14/pg15/pg16/pg17/pg19 equivalents). Fixtures use the v18 `uuidv7()` builtin — `PgTestDatabase.ensureUuidv7` installs a pg_catalog stand-in on pre-18 servers — and v15 syntax (NULLS NOT DISTINCT, security_invoker) is downgraded by a test-only transform on pre-15 servers. Any new `corpus-NN.sql` file is picked up automatically.
- Surefire runs on the class path (`useModulePath=false`); main jars remain JPMS-modular.

## Releases

Artifacts are published as a Maven repository on the `gh-pages` branch (GitHub Pages) — anonymous HTTPS access, no credentials for consumers.

- Publish: `mvn -DskipTests deploy -DaltDeploymentRepository=pages::default::file:///<checkout-of-gh-pages>/maven`, then commit + push `gh-pages`.
- Delete a version or artifact: `git rm` it on `gh-pages` and push — removal takes effect immediately.
- Consumer repo URL: `https://epicquest-studios.github.io/schema-reconciler/maven`.

## Conventions

- No `CASCADE` in rendered drops; recreates are `DROP`+`CREATE` change pairs ordered two-phase over the dependency graph.
- `SchemaObject.attributes` carries dialect-private render fragments (`PgCatalog.CREATE_SQL` etc.) with `${namespace}` tokens, bound to the quoted target at render time.
- Canonical definitions normalize namespace refs to `${namespace}` (`PgSql.normalize`) so scratch and target snapshots compare equal.
- Policy order (spec 3.3): fail check (manual changes count APPLY as FAIL) → recreate pairing → prerequisite demotion closure; demoted changes record their blocker.
- `PgCatalog.loadDependencies` covers: view→rel/proc/type (pg_rewrite), routine→rel/proc/type, index→proc, trigger→proc, constraint→proc/type/class/constraint (FK `conindid` resolves the referenced unique index to its backing constraint or the index object — `conindid` is only backing for `contype` `p`/`u`/`x`), column default→rel/proc/type (pg_attrdef — propagated to the parent table for covered columns, skipping owned-seq back-edges), column/table→type/collation, and partition child→parent. `loadExternalDependents` mirrors these for other-namespace dependents (5.8.3).
- `PgCatalog.loadOther` detects every namespace-scoped object the managed kinds don't claim (spec A.5): non-managed relations and inheritance children, exclusion constraints, UDTs (enum/domain/range/multirange/base), aggregates/window functions, policies, extensions, rules, statistics, collations, conversions, FTS objects, operators/opclasses/opfamilies, comments, security labels, privileges (acl), non-session ownership, and publication bindings — all `OTHER` → `UNSUPPORTED`. `pg_description`/`pg_seclabel` use `classoid` (not `classid`).
