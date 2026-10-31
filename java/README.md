# schema-reconciler — Java

Java reference implementation of [SPEC.md](../SPEC.md). See the
[root README](../README.md) for what schema reconciliation is and how the
engine works.

## Modules

| Artifact | Contents |
|---|---|
| `studio.epicquest.schema:schema-reconciler-core` | API, SPI, core algorithm (Java SE only) |
| `studio.epicquest.schema:schema-reconciler-postgresql` | PostgreSQL dialect (reference implementation) |
| `studio.epicquest.schema:schema-reconciler-tck` | Dialect conformance suite |

## Getting started

Releases are served from a Maven repository hosted on this repo's GitHub Pages site:

```xml
<repository>
    <id>epicquest-github</id>
    <url>https://epicquest-studios.github.io/schema-reconciler/maven</url>
</repository>
```

Most applications need the PostgreSQL dialect, which brings the core transitively:

```xml
<dependency>
    <groupId>studio.epicquest.schema</groupId>
    <artifactId>schema-reconciler-postgresql</artifactId>
    <version>0.9.0</version>
</dependency>
```

Gradle:

```groovy
repositories { maven { url = 'https://epicquest-studios.github.io/schema-reconciler/maven' } }
implementation 'studio.epicquest.schema:schema-reconciler-postgresql:0.9.0'
```

Implementing a dialect for another database? Depend on `-core`, and use `-tck` (test scope) to run the conformance suite against it. No credentials are needed — the repository is served anonymously over HTTPS.

## Usage

```java
var reconciler = SchemaReconciler.builder()
        .dataSource(dataSource)
        .namespace("svc")
        .desiredState(DesiredState.ofResource(getClass().getClassLoader(), "db/schema.sql"))
        .build();

var report = reconciler.reconcile();
log.info("schema {}: {} applied, {} reported, {} unexpected",
        report.outcome(), report.applied().size(), report.reported().size(), report.unexpected().size());
```

Dry run for review:

```java
var plan = reconciler.plan();
Files.writeString(Path.of("schema-plan.sql"), plan.toSql());
```

Bootstrap a desired-state script from an existing schema (no `desiredState` needed):

```java
var dumper = SchemaReconciler.builder()
        .dataSource(dataSource)
        .namespace("svc")
        .build();
String script = dumper.dump();   // namespace-neutral desired-state script
```

`dump()` reads the catalog under the namespace lock and renders it as a `${namespace}`-placeholder script in dependency order — feeding it back via `DesiredState.ofScript(script)` converges the same schema with an empty plan. Objects the dialect cannot express as DDL become `-- folded:` / `-- unsupported:` comment lines rather than being silently dropped.

The connecting role can be least-privilege:

```sql
GRANT USAGE, CREATE ON SCHEMA svc TO app_reconciler;
GRANT CREATE ON DATABASE appdb TO app_reconciler;  -- scratch replay only; see SPEC.md A.4
```

Environments that cannot grant `CREATE ON DATABASE` point `scratchDataSource` at a separate scratch database instead.

Objects managed by another tool can be left out of the reconcile entirely:

```java
var scoped = SchemaReconciler.builder()
        .dataSource(dataSource)
        .namespace("svc")
        .desiredState(desired)
        .filter(id -> !id.name().startsWith("staging_"))
        .build();
```

`filter` hides the rejected objects — plus their children, mirrored annotations and followers — from both snapshots, so an externally-managed object produces no difference, no change and no unexpected report, and plan staleness is scoped to what remains visible.

## Build

Requires Java 25 and Maven 3.9+. From this directory:

```bash
mvn verify
```

(or `./mvnw verify` — the wrapper lives here too.)

Core and the PostgreSQL dialect are JPMS-modular; the TCK ships as an automatic module (`Automatic-Module-Name`).

## Tests

Core tests run against an in-memory fake JDBC database (`FakeDb`) — no real DB needed. The PostgreSQL dialect tests need a PostgreSQL 14+ database; without configuration they start a Testcontainers `postgres:18` container, or you can point them at an existing one:

```bash
RECONCILER_TEST_URL=jdbc:postgresql://localhost:55999/rcl \
RECONCILER_TEST_USER=rcl RECONCILER_TEST_PASSWORD=rcl \
mvn verify
```

With Docker Desktop on macOS, Testcontainers may need `DOCKER_HOST=unix://$HOME/.docker/run/docker.sock`; alternatively run a dedicated container:

```bash
docker run -d --name reconciler-tck -e POSTGRES_USER=rcl \
        -e POSTGRES_PASSWORD=rcl -e POSTGRES_DB=rcl \
        -p 55999:5432 postgres:18
```

- `DeploymentFixtureTest` migrates `fixtures/deployment/schema.sql` per spec A.7 at test time and reconciles it end to end.
- `CorpusFixtureTest` reconciles the obfuscated schema corpus `fixtures/corpus/corpus-*.sql` (same A.7 transform). corpus-01 declares `vector`/`pg_trgm` — on a plain `postgres:18` image it skips; full coverage needs `pgvector/pgvector:pg18` (or pg14–pg19 equivalents). Fixtures use the v18 `uuidv7()` builtin — `PgTestDatabase.ensureUuidv7` installs a pg_catalog stand-in on pre-18 servers — and v15 syntax (NULLS NOT DISTINCT, security_invoker) is downgraded by a test-only transform on pre-15 servers. Any new `corpus-NN.sql` file is picked up automatically.
- `AppendixCoverageTest` creates each construct of spec Appendix C's "detected but unsupported" list and checks its `OTHER` prefix; `CanonicalDefinitionTest` compares canonical definitions with the engine's `pg_get_*def` text. Both roll back; the transform and security-label cases need a superuser and are skipped otherwise.
- The PostgreSQL module's Surefire runs on the class path (`useModulePath=false`); main jars remain JPMS-modular.

## Releases

Artifacts are published as a Maven repository on the `gh-pages` branch (GitHub Pages) — anonymous HTTPS access, no credentials for consumers.

- Publish: `mvn -DskipTests deploy -DaltDeploymentRepository=pages::default::file:///<checkout-of-gh-pages>/maven`, then commit + push `gh-pages`.
- Delete a version or artifact: `git rm` it on `gh-pages` and push — removal takes effect immediately.
- Consumer repo URL: `https://epicquest-studios.github.io/schema-reconciler/maven`.

## License

Licensed under the [Apache License, Version 2.0](../LICENSE).
