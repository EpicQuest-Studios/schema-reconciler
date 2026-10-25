# schema-reconciler

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Declarative, data-safe schema reconciliation for relational databases in Java. Brings a database to the state declared in a SQL script: plans, applies and verifies changes, and never drops data by default.

Inspired by the Kubernetes model: a manifest is the single source of truth and a reconciler drives actual state toward it. Here the manifest is `schema.sql` and the state is the database catalog — reconciled on demand, at startup or deploy time.

## The problem

A long-lived service typically owns a relational schema that must evolve with the code. Common approaches leave a gap:

- **Ordered migrations** answer "which deltas ran so far", not "make the database look like *this*". The desired end state lives only implicitly, spread across many ordered files.
- **Hand-written reconciliation** (comparing catalog rows against application-side constants) duplicates the desired state: once in `schema.sql`, once in hand-maintained maps of expected objects. The two copies can diverge, and such checkers tend not to cover function bodies, trigger definitions or view text.
- **Client-side SQL parsing** (introspecting a DDL script without running it) is brittle for vendor SQL: dollar-quoted function bodies, expression and partial indexes and dialect-specific syntax are hard for a general parser.
- **Dump-and-diff** (comparing two live databases or `pg_dump` outputs) generates migration scripts, but does not by itself give an embedded API with an explicit data-safety policy.

What is missing is an embeddable library where the *desired state is declared once, as ordinary DDL*; the target database itself parses it; and a deterministic, dependency-ordered, policy-gated plan brings the live schema to that state — while never deleting data the caller did not opt into losing.

## The goal

This repository contains two things:

1. **A specification** ([SPEC.md](SPEC.md)) that defines a vendor-neutral reconciliation contract: a Java API for applications, a `Dialect` SPI implemented once per database product, the reconciliation algorithm shared by all dialects, a 36-scenario conformance suite (TCK) every dialect must pass, and a PostgreSQL profile. The specification exists so the same contract can be implemented for other relational databases — dialects are discovered via `ServiceLoader` and selected per data source.
2. **A reference implementation** of that specification: `schema-reconciler-core` (the engine, depending only on Java SE), `schema-reconciler-postgresql` (the PostgreSQL 14+ dialect), and `schema-reconciler-tck` (the conformance suite, reusable by third-party dialects).

## How it works

- **The engine is the parser.** The desired script executes inside the real database in a scratch namespace that is rolled back afterwards, so the library never parses vendor SQL itself.
- **Engine-canonical comparison.** Desired and actual objects are compared using definitions rendered by the same engine from its own catalog (`pg_get_*def` style), so a matching name is never mistaken for a matching definition.
- **Plan before apply.** Every run produces an inspectable, deterministic plan ordered by the real dependency graph; applying is a separate step governed by an explicit `ReconcilePolicy` (`FAIL` / `REPORT` / `APPLY` for destructive, manual and unsupported changes).
- **Never lose data by default.** Destructive changes are rejected unless opted in; unexpected objects are reported, never dropped; external dependents are detected before apply, not discovered as mid-apply errors.
- **Converge and verify.** After applying, the catalog is re-read and every applied object is proven to match its desired definition; re-running on a converged schema reports `UP_TO_DATE`.

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
    <version>0.1.6</version>
</dependency>
```

Gradle:

```groovy
repositories { maven { url = 'https://epicquest-studios.github.io/schema-reconciler/maven' } }
implementation 'studio.epicquest.schema:schema-reconciler-postgresql:0.1.6'
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

## Build

Requires Java 25 and Maven 3.9+. `mvn verify` builds all three JPMS modules and runs every test.

Core tests run against an in-memory fake database. The PostgreSQL dialect tests need a PostgreSQL 14+ database; without configuration they start a Testcontainers `postgres:18` container, or you can point them at an existing one:

```bash
RECONCILER_TEST_URL=jdbc:postgresql://localhost:55999/rcl \
RECONCILER_TEST_USER=rcl RECONCILER_TEST_PASSWORD=rcl \
mvn verify
```

See [SPEC.md](SPEC.md) for the full contract — including the PostgreSQL 14–19 feature-coverage matrix and the schema-corpus description in Appendix C. The obfuscated schema corpus lives in `schema-reconciler-postgresql/src/test/resources/corpus/`.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
