# Schema Reconciler

Vendor-neutral, declarative, data-safe schema reconciler for Java (spec: `SPEC.md`).

## Modules

- `schema-reconciler-core` — API (`studio.epicquest.schema.reconciler`), model records, `Dialect` SPI, engine internals (`internal` package, not exported).
- `schema-reconciler-postgresql` — PostgreSQL 14+ reference dialect (`studio.epicquest.schema.reconciler.postgresql`, internals in `.internal`).
- `schema-reconciler-tck` — dialect conformance suite (`DialectConformanceTest`, C-01…C-36) for third-party dialects.

## Build

Java 25, Maven 3.9+. `mvn verify` builds all three JPMS modules and runs every test.

## Tests

- Core: pure unit tests over an in-memory fake JDBC database (`FakeDb`) + `FakeDialect` — no real DB needed.
- PostgreSQL: needs a PostgreSQL 14+ database. Point tests at one via env vars:
  `RECONCILER_TEST_URL=jdbc:postgresql://localhost:55999/rcl RECONCILER_TEST_USER=rcl RECONCILER_TEST_PASSWORD=rcl mvn verify`
  Without env vars, `PgTestDatabase` falls back to a Testcontainers `postgres:18` container (requires Docker reachable by Testcontainers; with Docker Desktop on macOS `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` may be needed, or run a dedicated container: `docker run -d --name reconciler-tck -e POSTGRES_USER=rcl -e POSTGRES_PASSWORD=rcl -e POSTGRES_DB=rcl -p 55999:5432 postgres:18`).
- `DeploymentFixtureTest` migrates `src/test/resources/deployment/schema.sql` per spec A.7 at test time and reconciles it end to end.
- `CorpusFixtureTest` reconciles the obfuscated schema corpus `src/test/resources/corpus/corpus-*.sql` (same A.7 transform). corpus-01 declares `vector`/`pg_trgm` — on a plain `postgres:18` image it skips; full coverage needs `pgvector/pgvector:pg18` (or pg14/pg15/pg16/pg17/pg19 equivalents). Fixtures use the v18 `uuidv7()` builtin — `PgTestDatabase.ensureUuidv7` installs a pg_catalog stand-in on pre-18 servers — and v15 syntax (NULLS NOT DISTINCT, security_invoker) is downgraded by a test-only transform on pre-15 servers. Any new `corpus-NN.sql` file is picked up automatically.
- `AppendixCoverageTest` creates each construct of spec Appendix C's "detected but unsupported" list and checks its `OTHER` prefix; `CanonicalDefinitionTest` compares canonical definitions with the engine's `pg_get_*def` text. Both roll back; the transform and security-label cases need a superuser and are skipped otherwise.
- The PostgreSQL module's Surefire runs on the class path (`useModulePath=false`); main jars remain JPMS-modular.

## Releases

Artifacts are published as a Maven repository on the `gh-pages` branch (GitHub Pages) — anonymous HTTPS access, no credentials for consumers.

- Publish: `mvn -DskipTests deploy -DaltDeploymentRepository=pages::default::file:///<checkout-of-gh-pages>/maven`, then commit + push `gh-pages`.
- Delete a version or artifact: `git rm` it on `gh-pages` and push — removal takes effect immediately.
- Consumer repo URL: `https://epicquest-studios.github.io/schema-reconciler/maven`.

## Conventions

- No `CASCADE` in rendered drops; recreates are `DROP`+`CREATE` change pairs ordered two-phase over the dependency graph.
- `SchemaObject.attributes` carries dialect-private render fragments (`PgCatalog.CREATE_SQL` etc.) with `${namespace}` tokens, bound to the quoted target at render time.
- Canonical definitions normalize namespace refs to `${namespace}` (`PgSql.normalize`) so scratch and target snapshots compare equal.
- Policy order (spec 3.3): fail check (manual changes count APPLY as FAIL; each UNSUPPORTED difference resolves via `unsupportedCategories` → `unsupported`) → recreate pairing → prerequisite demotion closure; demoted changes record their blocker. Category key for `OTHER` ids is the name prefix before `:` (`extension:`, `policy:`, `matview:`…, spec A.5), else the lowercase kind name; non-managed relations carry relkind prefixes (`matview:`, `foreign:`, `partition:`, `inherited:`…) so tolerating one cannot exempt another.
- `apply(plan)` rejects a foreign-namespace/dialect plan before opening a connection; `verifyPlan` rechecks dialect id and fingerprint after resolve.
- `Dialect.checkAccess` runs once per session before the lock (PG: `to_regnamespace` must resolve, `has_schema_privilege` for `USAGE` and `CREATE` checked separately (a comma list means "any of"), plus `has_database_privilege CREATE` for scratch setup when no `scratchDataSource`); `Builder.statementTimeout` is applied right after the lock as `SET LOCAL statement_timeout`, and again on the scratch connection when `scratchDataSource` is configured — bounds each statement (catalog reads included), never the lock wait or total run. Catalog getters (`pg_get_expr`) open the deparsed relation with `AccessShareLock` — inspection itself can block on a locked table.
- `PgCatalog` excludes extension-member objects (`pg_depend` `deptype='e'`) from every object loader (the `pg_extension` row itself and the PostgreSQL 19 `pg_publication_sequences` view, which exposes no oid, are the two queries without the filter) — members can't be created/dropped standalone; only the `pg_extension` row reports, as `extension:<name>` OTHER.
- `PgScriptCheck` also rejects: qualified object names outside the scratch schema in CREATE/DROP/ALTER name position (incl. `ON` targets of INDEX/TRIGGER/POLICY and GRANT/REVOKE/COMMENT/SECURITY LABEL targets; `pg_temp` allowed; `ON SCHEMA` grants only on scratch), and session `SET`/`RESET`/`LOAD` (`SET LOCAL`/`TRANSACTION`/`CONSTRAINTS` allowed). Foreign-schema names elsewhere (e.g. cross-schema `REFERENCES`) stay legal. `DO` bodies are scanned for session advisory locks and `set_config`. Every `set_config` call must pass the literal `true` third argument (parsed, not token-matched). A lock taken indirectly through a function the script invokes is released by `dropScratchNamespace` (`pg_advisory_unlock_all` after the savepoint rollback, which already undoes session settings).
- `PgRender` creates a table with `UNLOGGED`, `WITH (<reloptions>)` and `REPLICA IDENTITY FULL|NOTHING` (`PgCatalog.TABLE_OPTIONS`/`REPLIDENT`); `REPLICA IDENTITY USING INDEX` is the `POST_SQL` of the index or constraint, appended on every create/recreate.
- `PgRender` splits a drifted column by data risk: safe statements (`SET/DROP DEFAULT`, `DROP NOT NULL`, `TYPE` only for the widening allowlist: int chain, `varchar` growth/→`text`, `char` growth, `numeric` s′≥s∧p′−s′≥p−s) go in one `ALTER`-classified `REBUILD` change; `SET NOT NULL` gets its own `DESTRUCTIVE` change on the same column (fails on existing NULLs); generated/identity/collation and non-widening types stay manual `DESTRUCTIVE`. `char→varchar`/`text` is excluded — the cast strips trailing spaces.
- `SchemaReconciler.planTimed()` returns a `TimedPlan` (plan + `RunTimings`; `apply`/`verify` zero). `ReconcileReport.timings()` carries `RunTimings` (lockWait, readActual, loadDesired, plan, apply, verify) measured by `System.nanoTime`; failed runs surface timings on `SchemaReconcilerException.timings()` — completed phases plus the failed phase's partial duration, `ZERO` where nothing ran. The SPI `executeScript` takes the scratch namespace name for the qualified-name check.
- `PgCatalog.loadDependencies` covers: view→rel/proc/type (pg_rewrite), routine→rel/proc/type, index→proc, trigger→proc, constraint→proc/type/class/constraint (FK `conindid` resolves the referenced unique index to its backing constraint or the index object — `conindid` is only backing for `contype` `p`/`u`/`x`), column default→rel/proc/type (pg_attrdef — propagated to the parent table for covered columns, skipping owned-seq back-edges), column/table→type/collation, and partition child→parent. `loadExternalDependents` mirrors these for other-namespace dependents (5.8.3).
- `PgCatalog.loadOther` detects every namespace-scoped object the managed kinds don't claim (spec A.5): non-managed relations and inheritance children, exclusion constraints, UDTs (enum/domain/range/multirange/base), aggregates/window functions, policies, extensions, rules, statistics, collations, conversions, FTS objects, operators/opclasses/opfamilies, comments, security labels, privileges (acl), non-session ownership, publication bindings, and casts, transforms and languages anchored to a namespace object — all `OTHER` → `UNSUPPORTED`, except that `privileges:*` carries the sorted, owner-normalised ACL plus `Dialect.CONTENT_COMPARED_ATTRIBUTE`, and `Differ` emits no difference when the target holds an equal object (spec 5.4); every other `OTHER` category is presence-only and is reported on every run. `pg_description`/`pg_seclabel` use `classoid` (not `classid`).
