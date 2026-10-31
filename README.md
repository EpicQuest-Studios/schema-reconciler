# schema-reconciler

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Declarative, data-safe schema reconciliation for relational databases. Brings a database to the state declared in a SQL script: plans, applies and verifies changes, and never drops data by default.

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

1. **A specification** ([SPEC.md](SPEC.md)) that defines a vendor-neutral reconciliation contract: an API for applications, a `Dialect` SPI implemented once per database product, the reconciliation algorithm shared by all dialects, a 67-scenario conformance suite (TCK) every dialect must pass, and a PostgreSQL profile. The specification exists so the same contract can be implemented for other relational databases and other languages.
2. **Two implementations** of that specification: the Java reference implementation ([java/](java/)) and an independent TypeScript port ([typescript/](typescript/)) following the Appendix D binding.

## How it works

- **The engine is the parser.** The desired script executes inside the real database in a scratch namespace that is rolled back afterwards, so the library never parses vendor SQL itself.
- **Engine-canonical comparison.** Desired and actual objects are compared using definitions rendered by the same engine from its own catalog (`pg_get_*def` style), so a matching name is never mistaken for a matching definition.
- **Plan before apply.** Every run produces an inspectable, deterministic plan ordered by the real dependency graph; applying is a separate step governed by an explicit `ReconcilePolicy` (`FAIL` / `REPORT` / `APPLY` for destructive, manual and unsupported changes).
- **Never lose data by default.** Destructive changes are rejected unless opted in; unexpected objects are reported, never dropped; external dependents are detected before apply, not discovered as mid-apply errors.
- **Converge and verify.** After applying, the catalog is re-read and every applied object is proven to match its desired definition; re-running on a converged schema reports `UP_TO_DATE`.

## Repository layout

| Path | Contents |
|---|---|
| [SPEC.md](SPEC.md) | The vendor-neutral contract (normative; Appendix D is the TypeScript binding) |
| [java/](java/README.md) | Java reference implementation — core, PostgreSQL dialect, TCK |
| [typescript/](typescript/README.md) | TypeScript port — same three packages on `node:test` |
| [java/fixtures/](java/fixtures/) | Shared test fixtures (deployment + obfuscated schema corpus) |

Java specifics — artifacts, Maven coordinates, JDBC usage, JPMS — are in [java/README.md](java/README.md). TypeScript specifics — npm packages, the `SqlDataSource` adapter, Promise-based API — are in [typescript/README.md](typescript/README.md).

See [SPEC.md](SPEC.md) for the full contract — including the PostgreSQL 14–19 feature-coverage matrix and the schema-corpus description in Appendix C.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
