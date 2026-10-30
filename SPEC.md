# Schema Reconciler Specification

*Declarative, data-safe schema reconciliation for relational databases in Java*

| | |
|---|---|
| **Version** | 0.9, Draft |
| **Date** | 2026-10-25 |
| **Maven group** | `studio.epicquest.schema` |
| **Java baseline** | Java SE 25 |
| **Reference implementation** | PostgreSQL 14+ dialect (`schema-reconciler-postgresql`) |
| **Conformance suite** | `schema-reconciler-tck` |

This document is the working specification of the schema-reconciler project: the contract the core, the PostgreSQL dialect and every third-party dialect are held to, plus background appendices. It is a draft and may change until version 1.0.

## Document conventions

- Sections 1–11 and Appendix A define required behaviour. The preface, examples and rationale embedded in them, and Appendices B and C are background and establish no requirements.
- Requirements are stated in plain words: **must** is binding, **should** is a recommendation an implementation may deviate from for good reason, **may** marks a permitted option.
- Text in `monospace` is program text. Type and member signatures shown in Java blocks are the API surface; the prose beneath each block defines its contract.

## Contents

- [1. Introduction](#1-introduction)
  - [1.1 Purpose](#11-purpose)
  - [1.2 Motivation](#12-motivation)
  - [1.3 Design principles](#13-design-principles)
  - [1.4 Terminology](#14-terminology)
  - [1.5 Scope](#15-scope)
  - [1.6 Platform dependencies](#16-platform-dependencies)
- [2. Architecture](#2-architecture)
  - [2.1 Modules](#21-modules)
  - [2.2 Java packages and JPMS](#22-java-packages-and-jpms)
  - [2.3 General API rules](#23-general-api-rules)
- [3. API](#3-api)
  - [3.1 `SchemaReconciler`](#31-schemareconciler)
  - [3.2 `DesiredState`](#32-desiredstate)
  - [3.3 `ReconcilePolicy`](#33-reconcilepolicy)
  - [3.4 Dialect resolution](#34-dialect-resolution)
  - [3.5 Results](#35-results)
  - [3.6 `ReconcileListener`](#36-reconcilelistener)
- [4. Model](#4-model)
  - [4.1 Object identity](#41-object-identity)
  - [4.2 Schema objects and snapshots](#42-schema-objects-and-snapshots)
  - [4.3 Differences](#43-differences)
  - [4.4 Plan](#44-plan)
- [5. Reconciliation semantics](#5-reconciliation-semantics)
  - [5.1 Algorithm](#51-algorithm)
  - [5.2 Determinism](#52-determinism)
  - [5.3 Canonical definitions](#53-canonical-definitions)
  - [5.4 Missing, drifted and unsupported objects](#54-missing-drifted-and-unsupported-objects)
  - [5.5 Unexpected objects](#55-unexpected-objects)
  - [5.6 Safety classes](#56-safety-classes)
  - [5.7 Fingerprints and stale plans](#57-fingerprints-and-stale-plans)
  - [5.8 Dependencies and ordering](#58-dependencies-and-ordering)
- [6. Service Provider Interface](#6-service-provider-interface)
  - [6.1 `Dialect`](#61-dialect)
  - [6.2 Locking](#62-locking)
  - [6.3 Scratch namespaces](#63-scratch-namespaces)
  - [6.4 Non-transactional DDL](#64-non-transactional-ddl)
- [7. Exceptions](#7-exceptions)
- [8. Security](#8-security)
- [9. Conformance](#9-conformance)
  - [9.1 The conformance suite](#91-the-conformance-suite)
  - [9.2 Required scenarios](#92-required-scenarios)
  - [9.3 Implementation conformance](#93-implementation-conformance)
  - [9.4 Deliverables](#94-deliverables)
- [10. Versioning and compatibility](#10-versioning-and-compatibility)
- [11. Open issues](#11-open-issues)
- [Appendix A. PostgreSQL profile — reference implementation](#appendix-a-postgresql-profile--reference-implementation)
  - [A.1 Capabilities](#a1-capabilities)
  - [A.2 Locking](#a2-locking)
  - [A.3 Scratch namespace](#a3-scratch-namespace)
  - [A.4 Script execution](#a4-script-execution)
  - [A.5 Catalog sources](#a5-catalog-sources)
  - [A.6 Rendering](#a6-rendering)
  - [A.7 Migration path for an existing deployment script](#a7-migration-path-for-an-existing-deployment-script)
- [Appendix B. Example](#appendix-b-example)
- [Appendix C. PostgreSQL 14–19 feature coverage and test corpus](#appendix-c-postgresql-1419-feature-coverage-and-test-corpus)
  - [Reconciled constructs (managed kinds)](#reconciled-constructs-managed-kinds)
  - [Detected but unsupported (`OTHER` → `UNSUPPORTED` per 5.4)](#detected-but-unsupported-other--unsupported-per-54)
  - [Out of scope (cluster-level or statement-only, per 1.5/3.2)](#out-of-scope-cluster-level-or-statement-only-per-1532)
  - [Test corpus](#test-corpus)

## References

| Reference | Relevance |
|---|---|
| Java SE 25 (`java.base`, `java.sql`, `java.time`, `java.util`) | Runtime baseline (10.5) |
| JDBC 4.3 (`java.sql`, `javax.sql`) | `javax.sql.DataSource`, `java.sql.Connection`, `DatabaseMetaData`, `SQLException` |
| Java Platform Module System | `uses`/`provides` dialect discovery (2.2) |
| `java.lang.System.Logger` | Logging façade (2.3) |

## 1. Introduction

### 1.1 Purpose

This specification defines a Java library that brings a relational database schema to a *desired state* declared as a SQL script. It does so safely, repeatably, and without ever losing data unless a caller explicitly allows it. The model is the declarative one Kubernetes popularised: the script is the single source of truth, and a reconciler drives actual state toward it — here on demand rather than in a continuous control loop. The specification defines:

- a vendor-neutral **API** used by applications,
- a **Service Provider Interface (SPI)** implemented once per database product (a *dialect*),
- the **reconciliation algorithm** shared by all dialects,
- a **conformance suite** (TCK) that every dialect must pass,
- the **PostgreSQL profile**, which is the reference implementation (Appendix A).

### 1.2 Motivation

Hand-written schema checkers keep their expected state in code (maps of tables, constraints, indexes and views) duplicated from the schema script, and rarely verify function bodies, trigger definitions or view bodies. This specification generalises the scratch-and-compare approach so that:

1. the expected state is derived from the script itself, never written twice, and
2. the same contract can be implemented for other relational databases.

### 1.3 Design principles

| ID | Principle |
|---|---|
| P1 | **The engine is the parser.** The desired script is executed by the target database engine itself, in an isolated scratch namespace. The library never parses vendor SQL. |
| P2 | **Engine-canonical comparison.** Desired and actual objects are compared using definitions rendered by the same engine from its own catalog. A matching name is never proof of a matching definition. |
| P3 | **Never lose data by default.** Any change that can remove stored data is rejected unless the caller opts in explicitly. Unexpected objects are reported, never dropped. |
| P4 | **Fail loudly, never silently.** Unsupported constructs, unknown objects and failed verification are errors or explicit report entries, never ignored. |
| P5 | **Plan before apply.** Every run produces an inspectable plan. Applying is a separate, policy-governed step. |
| P6 | **Converge and verify.** After applying, the implementation re-reads the catalog and proves that the applied objects now match. |
| P7 | **No dependencies in the core.** The API, SPI and core depend only on the Java SE platform (`java.base`, `java.sql`). |

### 1.4 Terminology

| Term | Meaning |
|---|---|
| Namespace | The unit that groups schema objects: a schema in PostgreSQL and Oracle, a database in MySQL. |
| Target namespace | The namespace being reconciled. |
| Scratch namespace | A temporary namespace into which the desired script is loaded for introspection. |
| Desired state | The set of objects produced by executing the desired script in a scratch namespace. |
| Actual state | The set of objects found in the target namespace. |
| Snapshot | An immutable, namespace-neutral description of a set of schema objects. |
| Canonical definition | The engine-rendered definition of an object, after the normalisation rules in section 5.3. |
| Dialect | An SPI implementation for one database product. |
| Change | One planned unit of DDL, with an action and a safety class. |

### 1.5 Scope

In scope: tables, columns, primary-key, unique, foreign-key and check constraints, indexes (including partial and expression indexes where the dialect supports them), views, materialized views, sequences, routines (functions and procedures), triggers, enum types, domains, policies, extensions and comments.

Non-goals for version 1:

- versioned migrations or a migration history table,
- data migration or backfill,
- translating a script from one dialect to another,
- rename detection (a rename appears as one missing and one unexpected object),
- down-migrations or rollback scripts,
- granted privileges, ownership, security labels, partitioning, foreign tables, composite types, non-enum user types (ranges, multiranges, base types), aggregates and window functions, rules, statistics, collations, conversions, text-search objects, operators, casts, transforms, languages and publications. Dialects must report these as `UNSUPPORTED` if a desired script creates them (section 5.4).

### 1.6 Platform dependencies

- **JDBC 4.3**: all database access uses `javax.sql.DataSource` and `java.sql` types; this specification adds no new connection or statement model and requires no driver beyond what the application already supplies.
- **Java Platform Module System**: dialect discovery uses `ServiceLoader` under both JPMS (`uses`/`provides`) and the class path (`META-INF/services`), per 2.2.
- **Logging**: `java.lang.System.Logger` only; no logging framework is required or configured.
- No third-party schema-management tool is reused or depended on.

## 2. Architecture

### 2.1 Modules

| Artifact | Contents | Dependencies |
|---|---|---|
| `studio.epicquest.schema:schema-reconciler-core` | API, SPI, core algorithm | Java SE only |
| `studio.epicquest.schema:schema-reconciler-postgresql` | PostgreSQL dialect (reference implementation) | core, PostgreSQL JDBC driver (runtime, provided by the application) |
| `studio.epicquest.schema:schema-reconciler-tck` | Conformance suite | core, JUnit Jupiter |

### 2.2 Java packages and JPMS

| Package | Role | Stability |
|---|---|---|
| `studio.epicquest.schema.reconciler` | API for applications | Stable, semantic versioning |
| `studio.epicquest.schema.reconciler.model` | Snapshot and plan model | Stable, semantic versioning |
| `studio.epicquest.schema.reconciler.spi` | Dialect SPI | Stable for implementers; additions may be made in minor versions as `default` methods only |
| `studio.epicquest.schema.reconciler.internal` | Core algorithm | Not exported |

The core module must be a named JPMS module `studio.epicquest.schema.reconciler` that `exports` the first three packages and `uses studio.epicquest.schema.reconciler.spi.Dialect`. Each dialect module must `provides studio.epicquest.schema.reconciler.spi.Dialect with ...` and must also register itself in `META-INF/services` for class-path use.

### 2.3 General API rules

All public types must follow these rules:

1. Public methods must not return `null`. Absent values are `Optional<T>`; empty collections are returned as empty, unmodifiable collections.
2. Public methods must reject `null` arguments with `NullPointerException`.
3. Data carriers are `record`s that validate their components in the compact constructor and defensively copy collections (`List.copyOf`, `Map.copyOf`, `Set.copyOf`).
4. Domain failures are unchecked and extend `SchemaReconcilerException` (section 7). `SQLException`s are wrapped, never leaked. Argument validation uses `NullPointerException` and `IllegalArgumentException`.
5. Logging uses `System.Logger` (`System.getLogger("studio.epicquest.schema.reconciler")`), so the core has no logging dependency.
6. `SchemaReconciler` instances are immutable and thread-safe. Concurrent runs against the same target namespace are serialised by the namespace lock (section 6.2).
7. All text read or produced through the API — scripts, statements, definitions, `toSql()` output — is UTF-8.
8. Public signatures expose only Java SE types and types defined by this specification; no third-party types appear in the API surface.

## 3. API

The signatures below define the API surface; the Javadoc generated from the reference implementation is part of the specification by reference. All types live in the packages of 2.2.

### 3.1 `SchemaReconciler`

```java
package studio.epicquest.schema.reconciler;

public final class SchemaReconciler {

    public static Builder builder() { ... }

    /** Computes a plan without changing the target namespace. */
    public ReconcilePlan plan() { ... }
    /** Like plan(), also returning the phase timings of the planning run (3.5). */
    public TimedPlan planTimed() { ... }

    /** Plans and applies under the configured policy, holding one namespace lock. */
    public ReconcileReport reconcile() { ... }

    /** Applies a plan previously returned by plan(); fails if the target has changed since. */
    public ReconcileReport apply(ReconcilePlan plan) { ... }

    /** Reads the target namespace under the lock and returns an equivalent desired-state script. */
    public String dump() { ... }

    public static final class Builder {
        public Builder dataSource(DataSource dataSource) { ... }          // required
        public Builder namespace(String targetNamespace) { ... }          // required
        public Builder desiredState(DesiredState desiredState) { ... }    // required for plan/reconcile/apply; optional for dump()
        public Builder dialect(Dialect dialect) { ... }                   // optional, see 3.4
        public Builder scratchDataSource(DataSource dataSource) { ... }   // optional, see 6.3
        public Builder policy(ReconcilePolicy policy) { ... }             // default ReconcilePolicy.defaults()
        public Builder lockTimeout(Duration timeout) { ... }              // default 60 s
        public Builder statementTimeout(Duration timeout) { ... }         // optional, unbounded by default
        public Builder listener(ReconcileListener listener) { ... }       // optional
        public SchemaReconciler build() { ... }
    }
}
```

`build()` must fail with `ConfigurationException` if a required property is missing, if `lockTimeout` or `statementTimeout` is zero or negative, or — when no dialect is set — if no `Dialect` provider is discoverable via `ServiceLoader` at all. An empty namespace counts as missing. `desiredState` is required for `plan()`, `reconcile()` and `apply()` but may be omitted when the reconciler is built for `dump()` alone; those methods then fail with `ConfigurationException` naming the missing property. `build()` must not open a connection: the `supports(DatabaseMetaData)` check of 3.4 needs one, so final dialect selection is deferred to the first use of the reconciler.

`dump()` performs steps 1 to 3 of 5.1 — connect, lock, read the actual snapshot — and renders the snapshot as a desired-state script: a deterministic header comment naming the dialect id, the target namespace and the actual fingerprint (never a timestamp), then for each object, in `dependsOn` order, the create-fragment statements the dialect returns from `dumpStatements` (6.1), each terminated by `;`. Objects whose `dumpStatements` is empty are emitted as comment lines — `-- folded: <id>` for an object folded into its parent's create fragment (columns, serial-owned sequences), `-- unsupported: <id>` for `ObjectKind.OTHER` objects and anything else the dialect cannot render — so nothing is silently dropped. Feeding the output back as a desired-state script on an equal database produces an empty plan and a byte-identical second dump.

`plan()` and `planTimed()` perform the planning steps of 5.1 without changing the target; `planTimed()` additionally returns the run's `RunTimings` (3.5). A `ReconcileListener` exception during planning aborts as `PlanningException` (3.6).

### 3.2 `DesiredState`

```java
public record DesiredState(String script) {
    public static DesiredState ofScript(String sql) { ... }
    public static DesiredState ofResource(ClassLoader loader, String resourceName) { ... }
    public static DesiredState ofPath(Path path) { ... }
}
```

Namespace references inside the script must be written as the placeholder `${namespace}`, for example `CREATE TABLE ${namespace}.plans (...)`. The core replaces every occurrence with the scratch namespace identifier, quoted by the dialect, before executing the script. The replacement is already quoted, so the script must not quote the placeholder itself: `"${namespace}"` would be quoted twice. The core must reject a placeholder directly enclosed in the dialect's identifier quotes with `DesiredStateException`. To write the literal text `${namespace}`, for example inside a routine body that builds SQL, the script writes `$${namespace}`, which the core emits as `${namespace}` without substitution. Only the exact token `${namespace}` is recognised; any other `${...}` text is left unchanged. A script may instead leave every object name unqualified; the core then binds the scratch connection's session namespace before executing it (`bindSessionNamespace`, 6.1), which requires `DialectCapabilities.sessionDefaultNamespace`. If the script contains no `${namespace}` placeholder and the dialect does not support a session default namespace, `plan()` must fail with `DesiredStateException`.

The desired script is a trusted build artefact. It is executed with the privileges of the configured `DataSource`. Its content is constrained:

1. The script should create objects only inside `${namespace}` (or unqualified). A statement that creates an object in another namespace escapes the scratch namespace — it can neither be detected nor cleaned up — so its behaviour is undefined. A dialect may instead reject a statement it can determine would create an object with no namespace anchor at all — a role or a server, for example — since such an object could never appear in a snapshot (5.4).
2. The script must not create, alter or drop namespaces, must not acquire session-level locks (for example `pg_advisory_lock`), and must not change session state (for example `SET` without `LOCAL`, `RESET`, `LOAD`, or `set_config` with `is_local` other than the literal `true`): session-level effects can leak onto a pooled connection (C-21). A dialect should detect these statically, including inside anonymous code blocks, and must release any session-level lock a script took indirectly (for example inside a function it invoked) before the scratch connection is reused (A.2). A dialect may also reject a statement whose created, altered or dropped object name is qualified by a namespace other than the scratch namespace — the statement would escape the scratch namespace even though its text parses.
3. On transactional dialects, the script must not contain transaction control (`BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`), nor statements that cannot run inside a transaction block, for example `CREATE INDEX CONCURRENTLY` or `VACUUM` (OI-3). After executing the script, the dialect must check that the run's transaction and scratch savepoint are still intact. If they are not, it must remove the scratch namespace and fail with `DesiredStateException`.
4. Other non-DDL statements may appear; their effects are confined to the scratch connection.
5. The script must not refer to the target namespace by its literal name; it uses `${namespace}` or unqualified names instead. Before substitution, the core must search the script for the target namespace identifier — bare or enclosed in the dialect's identifier quotes, compared case-insensitively — immediately followed by `.` and not preceded by an identifier character (letter, digit, `_`, `$`) or by `.`. On a match, `plan()` must fail with `DesiredStateException` giving the position of the first match.

   Rationale: in the scratch namespace a literal reference stays literal. In the actual snapshot, normalisation rule 5.3(1) turns the same reference into `${namespace}.`. The object would therefore be `DRIFTED` on every run and never pass verification. The check is lexical, so a match inside a comment or string literal also fails; this follows P4, and the script author can rewrite the text. The same failure has a second form on the placeholder side: substitution replaces `${namespace}` textually, so a bare token inside a string literal — `DEFAULT '${namespace}'` — bakes the scratch name itself into the catalog, where it stays literal and drifts on every run. The PostgreSQL dialect therefore rejects a string literal whose whole content is the substituted scratch name, bare or in identifier quotes; the qualified form `'${namespace}.seq'` inside a literal stays legal because normalisation sees its `.` qualifier.

### 3.3 `ReconcilePolicy`

```java
public enum Decision { APPLY, REPORT, FAIL }

public record ReconcilePolicy(
        Decision additive,                          // default APPLY
        Decision rebuild,                           // default APPLY
        Decision destructive,                       // default FAIL
        Decision unsupported,                       // default FAIL; APPLY is not permitted
        Map<String, Decision> unsupportedCategories) { // per-category overrides; APPLY is not permitted

    public static ReconcilePolicy defaults() { ... }
    public static ReconcilePolicy reportOnly() { ... }  // REPORT for every class

    public ReconcilePolicy withDestructive(Decision decision) { ... }
    public ReconcilePolicy withUnsupportedCategory(String category, Decision decision) { ... }
    // with... for each component
}
```

The compact constructor must reject `unsupported == APPLY` and any `unsupportedCategories` value of `APPLY` with `IllegalArgumentException`. The safety classes are defined in section 5.6.

`unsupportedCategories` overrides `unsupported` per *difference category*. The category of an `UNSUPPORTED` difference is, for an `ObjectKind.OTHER` object, the dialect's documented name prefix before `:` (for example `privileges`, `comment`, `rule`, `composite`, `partition`; an `OTHER` name without a prefix is `relation`), and for any other kind the lowercased `ObjectKind` name (for example `table`, `routine`). A policy can therefore tolerate objects it deliberately leaves to other tooling — grants or rules, for example — while still failing on unsupported constructs that carry reconciliation risk, such as foreign tables or partitions. Categories are stable identifiers, never free-text matching: a category not present in the map falls back to `unsupported`, which defaults to `FAIL` — unknown or unclassified objects fail closed. The decision for a difference is `unsupportedCategories.getOrDefault(category, unsupported)`. The PostgreSQL prefix list is in A.5.

`additive`, `rebuild` and `destructive` govern *changes* by their `ChangeSafety`. `unsupported` governs *differences*: an `UNSUPPORTED` difference (5.4) never produces a `PlannedChange`, so it is evaluated against `plan.differences()`, not `plan.changes()`. Semantics of each decision:

- `APPLY`: the change is executed.
- `REPORT`: the change is not executed. It appears in `ReconcileReport.reported()` and is logged at `WARNING`. For `unsupported`, each `UNSUPPORTED` difference appears in `ReconcileReport.unsupported()` and is logged at `WARNING`.
- `FAIL`: if the plan contains at least one change of this class — or, for `unsupported`, at least one `UNSUPPORTED` difference — `reconcile()` and `apply(plan)` must throw `PolicyViolationException` **before executing any change**.

Unexpected objects (section 5.5) never produce changes, regardless of policy, except for the table-shape conflict defined there.

A change whose `statements` list is empty is a *manual change*: the dialect can describe it but not perform it safely. A manual change mapped to `APPLY` must be treated as `FAIL`.

**Evaluation** (step 7 of 5.1) proceeds in this order:

1. *Fail check.* Map every change to its class's decision, treating a manual change mapped to `APPLY` as `FAIL`. If any change maps to `FAIL`, or any `UNSUPPORTED` difference resolves to `FAIL` under `unsupported` and `unsupportedCategories`, throw `PolicyViolationException` carrying the plan.
2. *Recreate pairing.* The `DROP` and `CREATE` halves of one recreate (4.4) always receive the same decision.
3. *Prerequisite closure.* A change mapped to `APPLY` must be demoted to `REPORT` when its target depends — through `dependsOn` in the desired snapshot, transitively, or through `ObjectId.parent` — on a *blocked* object. An object is blocked when either:
   - it has a change in the plan that is not applied (mapped to `REPORT`, or demoted), or
   - it is the subject of an `UNSUPPORTED` difference and the actual snapshot has no object with an equal `ObjectId` and canonical definition.

   Demotion repeats until no further change is demoted. The `DROP` half of a demoted recreate is demoted with it. A demoted change appears in `reported()` and its `WARNING` log entry must name the blocking object. This keeps the applied part of a plan executable: the core never runs a change against a prerequisite it has not created or brought up to date.

### 3.4 Dialect resolution

Dialects are discovered with `ServiceLoader.load(Dialect.class)`. When the dialect is not set explicitly, the core must select the single candidate whose `supports(DatabaseMetaData)` returns `true` for a connection from the configured `DataSource`; zero or several matches must raise `ConfigurationException`, naming the product and the candidate dialects. Because `supports()` needs a connection and `build()` opens none, this selection happens on the first `plan()`, `reconcile()` or `apply()` call, which is where `ConfigurationException` for an unresolvable dialect is thrown. `SQLException`s from `supports()` are wrapped in `ConfigurationException`.

When the dialect is set explicitly, the core must still verify `supports()` on first use and raise `ConfigurationException` on a mismatch, so that wiring errors surface early.

### 3.5 Results

```java
public enum ReconcileOutcome { UP_TO_DATE, RECONCILED, REPORTED_ONLY }

public record ReconcileReport(
        ReconcileOutcome outcome,
        ReconcilePlan plan,
        List<PlannedChange> applied,
        List<PlannedChange> reported,
        List<Difference> unexpected,
        List<Difference> unsupported,   // UNSUPPORTED differences reported under policy REPORT (3.3)
        Duration elapsed,
        RunTimings timings) { }

public record RunTimings(
        Duration lockWait,      // time spent acquiring the namespace lock — contention shows here
        Duration readActual,    // catalog read of the target namespace
        Duration loadDesired,   // scratch namespace + script + snapshot + cleanup
        Duration plan,          // diff, render and plan assembly
        Duration apply,         // the window in which applied DDL holds its locks
        Duration verify) { }    // post-apply snapshot re-read and comparison
```

`timings` measures the run so adopters can size its locking impact on a realistic database (OI-6). `SchemaReconciler.planTimed()` (3.1) returns the same measurements for a planning run as a `TimedPlan(plan, timings)`; its `apply` and `verify` phases are zero. DDL locks release only at commit, so the lock-holding window is `apply` + `verify`. All phases are monotonic elapsed times measured with `System.nanoTime`, never wall-clock differences. On failure the run still surfaces its timings: the exception's `timings()` returns the completed phases plus the partial duration of the phase that failed (section 7). `statementTimeout` bounds each *statement*, not the run — a run of many short statements can exceed it.

- `UP_TO_DATE`: the plan had no changes. Unexpected objects and `UNSUPPORTED` differences reported under `REPORT` do not prevent `UP_TO_DATE`. They are listed in `unexpected` and `unsupported` respectively and never produce changes (5.4, 5.5).
- `RECONCILED`: at least one change was applied and verified.
- `REPORTED_ONLY`: changes exist, but the policy reported all of them and applied none.

### 3.6 `ReconcileListener`

```java
public interface ReconcileListener {
    default void planned(ReconcilePlan plan) { }
    default void applying(PlannedChange change) { }
    default void applied(PlannedChange change, Duration elapsed) { }
    default void reported(PlannedChange change) { }
}
```

Listeners are called on the reconciling thread. An exception thrown by a listener must abort the run as `ApplyException`, carrying the changes applied so far (possibly none) and, when thrown from `applying` or `applied`, the change being processed. This holds even when the exception comes from `planned`, before anything is applied. `plan()` applies nothing, so there a listener exception aborts as `PlanningException`. In transactional dialects the abort rolls everything back.

## 4. Model

All model types live in `studio.epicquest.schema.reconciler.model`.

### 4.1 Object identity

```java
public enum ObjectKind {
    TABLE, COLUMN, PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK,
    INDEX, VIEW, SEQUENCE, ROUTINE, TRIGGER, OTHER,
    MATERIALIZED_VIEW, TYPE, DOMAIN, POLICY, EXTENSION, COMMENT
}

/** Namespace-neutral identity. */
public record ObjectId(ObjectKind kind, String name, Optional<String> parent) { }
```

- `name` is the identifier as stored by the engine (case as the catalog reports it).
- `parent` is required for `COLUMN`, all constraint kinds, `INDEX`, `TRIGGER` and `POLICY` (the owning table or view). For `COMMENT` it mirrors the commented target's `parent`: a comment on a column, index, constraint, trigger or policy carries the parent's name, a comment on a top-level object carries none. It must be empty for other kinds. A present `parent` and `name` must be non-empty strings. The compact constructor enforces these rules with `IllegalArgumentException`.
- For `ROUTINE`, `name` must include the identity signature, because routines can be overloaded. The signature is the routine name followed by the parenthesised list of its input argument types (`IN`, `INOUT` and `VARIADIC` arguments; never `OUT`), separated by `, `, for example `extract_ts(text)` or `merge_plan(bigint, jsonb)`. Each type is written by the engine's canonical type formatter. Argument names are omitted, because they belong to the canonical definition: renaming a parameter makes the routine `DRIFTED`, not a missing routine plus an unexpected one. A type of the snapshot's own namespace is written unqualified when the dialect binds the search path to that namespace (A.5), as the PostgreSQL profile does; any qualifier that remains is replaced by the `${namespace}.` token of 5.3.
- `ObjectId` never contains a namespace. Two snapshots of different namespaces are directly comparable.

### 4.2 Schema objects and snapshots

```java
public record Definition(String canonical) { }   // see 5.3

public record SchemaObject(
        ObjectId id,
        Definition definition,
        Set<ObjectId> dependsOn,                  // objects this one requires
        Map<String, String> attributes) { }       // informational only, never compared

public record SchemaSnapshot(
        Map<ObjectId, SchemaObject> objects,
        String fingerprint) { }                   // see 5.7
```

`attributes` carry dialect-specific details such as a column's type or nullability, for display and diagnostics. Comparison must use `definition` only, so that the core stays vendor-neutral. The only attributes the core reads are the markers `Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE` (5.8.3) and `Dialect.CONTENT_COMPARED_ATTRIBUTE` (5.4); neither takes part in equality.

### 4.3 Differences

```java
public enum DifferenceType { MISSING, DRIFTED, UNEXPECTED, UNSUPPORTED }

public record Difference(
        ObjectId id,
        DifferenceType type,
        Optional<Definition> expected,
        Optional<Definition> actual) { }
```

### 4.4 Plan

```java
public enum ChangeAction { CREATE, ALTER, DROP }
public enum ChangeSafety { ADDITIVE, REBUILD, DESTRUCTIVE }

public record PlannedChange(
        int order,                          // 1-based execution order
        ObjectId target,
        ChangeAction action,
        ChangeSafety safety,
        List<String> statements,            // final SQL, already bound to the target namespace
        Optional<Definition> expected,      // definition the target must have after apply; empty for DROP changes and changes against unexpected objects
        String reason) { }                  // human-readable cause, e.g. "definition drift", "dependency of <id>"

public record ReconcilePlan(
        String targetNamespace,
        String dialectId,
        String actualFingerprint,
        String desiredFingerprint,
        List<Difference> differences,
        List<PlannedChange> changes) {

    public boolean isEmpty() { return changes.isEmpty(); }

    /** All statements in order, each preceded by a comment with its order,
        target, action, safety and reason; manual changes are marked MANUAL. */
    public String toSql() { ... }
}
```

Each `PlannedChange` is one unit of execution, placed by its own `order`. Actions:

- `CREATE`: creates an object that does not exist, including creating it again after its `DROP` half (below), and adding a column or constraint to an existing table.
- `ALTER`: modifies an existing object in place, for example `CREATE OR REPLACE` of a routine, and every manual change.
- `DROP`: removes an object. In version 1 a `DROP` is only ever the first half of a *recreate*: whenever an object must be dropped and created again, `render` must return two changes for the same `target` — a `DROP` change and a `CREATE` change carrying `expected` — with the same safety class and reason. A `DROP` without a matching `CREATE` for the same target is invalid, and the core must reject it with `PlanningException`. The core ties the two halves together: they are always both applied or both reported (3.3).

Splitting a recreate into two changes lets the drop and create halves be ordered separately (5.8.5): dependents are dropped before their prerequisites and created after them.

A plan must be serialisable as plain text for review via `toSql()`. The output must begin with a deterministic header comment naming `targetNamespace`, `dialectId` and both fingerprints plus the session context the statements assume (see `bindSessionNamespace`, 6.1), and must not contain timestamps, so that two runs of the same input produce byte-identical output (5.2).

## 5. Reconciliation semantics

### 5.1 Algorithm

`reconcile()` must behave as the following steps. `plan()` performs steps 1 to 6 and then releases the lock without changing the target; before releasing it must roll back (transactional dialects) or clean up (non-transactional dialects) so the returned connection carries no session state (C-21). `apply(plan)` performs steps 1 to 3, checks the fingerprint (5.7), then performs steps 7 to 10 with the given plan. `reconcile()` holds one lock throughout and may skip the fingerprint recheck.

1. **Connect.** Obtain one connection from the `DataSource`. Call `Dialect.checkAccess` to verify the connection's privileges on the target namespace — a missing namespace or insufficient privileges must fail the run before any lock is taken. If `capabilities().transactionalDdl()` is true, disable auto-commit. The core must restore the connection's original auto-commit setting on every exit path, before closing the connection and returning it to its pool (C-21).
2. **Lock.** Acquire the namespace lock (6.2) within `lockTimeout`, or fail with `LockTimeoutException`. If `statementTimeout` is configured, the dialect applies it now (for example `SET LOCAL statement_timeout`), so it bounds applied statements and catalog reads but never the lock wait itself.
3. **Read actual.** Read the snapshot of the target namespace.
4. **Load desired.** Create a scratch namespace (6.3), bind the session namespace to it (`bindSessionNamespace`, 6.1), execute the desired script in it, read its snapshot, then remove the scratch namespace. Removal must happen even if loading fails. When `scratchDataSource` supplies a separate connection and `statementTimeout` is configured, the dialect applies the bound on that connection as well before the script runs.
5. **Diff.** Compute differences (5.4, 5.5).
6. **Plan.** Ask the dialect to render changes for each difference, add dependent rebuilds (5.8), assign execution order and safety classes, and build the `ReconcilePlan`.
7. **Evaluate policy.** Evaluate the plan as defined in 3.3: fail check, recreate pairing, then prerequisite closure. On a `FAIL`, throw `PolicyViolationException` carrying the plan. Nothing is executed.
8. **Apply.** Bind the session namespace to the target namespace (6.1), then execute the changes that remain mapped to `APPLY`, in plan order. Collect the changes mapped to or demoted to `REPORT`, and the `UNSUPPORTED` differences reported under `REPORT`.
9. **Verify.** Re-read the actual snapshot. The target of every applied change whose `expected` is present must now have a definition equal to it (4.4); otherwise throw `VerificationException`, listing each object that did not converge. `DROP` changes are verified through the `CREATE` half of their recreate.
10. **Finish.** Commit (transactional dialects), release the lock and return the report.

On any exception in steps 3 to 9, transactional dialects must roll back the whole run. For non-transactional dialects see 6.4.

### 5.2 Determinism

Given the same desired script and the same actual state, two runs must produce plans with identical changes in identical order. Ordering must not depend on hash iteration order or catalog row order. `differences` must be sorted by `ObjectId` (kind order, then parent, then name, lexicographically) and `changes` by their final `order`.

### 5.3 Canonical definitions

A canonical definition is produced by the dialect from its catalog, never from the script text. Normalisation must:

1. replace every qualified reference to the snapshot's own namespace (quoted or unquoted, followed by `.`) with the token `${namespace}.`. A reference matches only at an identifier boundary: the namespace identifier must not be immediately preceded by an identifier character (letter, digit, `_`, `$`) or by `.`, so that namespace `app` never matches inside `myapp.` or `other.app.`,
2. collapse runs of whitespace outside string literals and quoted identifiers into a single space and trim the result,
3. leave everything else unchanged. In particular, case must not be folded globally, because that would change string literals and quoted identifiers.

Dialects may apply further rules that are documented in their profile and proven not to hide real differences (for example, removing the default `ASC` from index columns).

Two objects are equal if and only if their `ObjectId`s and canonical definitions are equal.

### 5.4 Missing, drifted and unsupported objects

- `MISSING`: present in desired, absent in actual.
- `DRIFTED`: present in both with different canonical definitions.
- `UNSUPPORTED`: present in desired with a kind the dialect does not support (`ObjectKind.OTHER`, or a kind absent from `capabilities().supportedKinds()`). It takes precedence over `MISSING` and `DRIFTED` for such an object, and the dialect is never asked to render it. One exception: a dialect may mark an unsupported-kind object whose canonical definition is a faithful rendering of its content (`Dialect.CONTENT_COMPARED_ATTRIBUTE`, 4.2); when the actual snapshot holds an object with the same `ObjectId` and an equal definition, no difference is reported for it. When the object is absent from the actual snapshot or its definition differs, the `UNSUPPORTED` difference stands, with the actual definition attached when there is one. Policy `unsupported` governs these as differences (3.3); `APPLY` is not permitted.

A dialect must detect every object in the scratch namespace, including kinds it does not manage, so that nothing created by the script is silently ignored.

### 5.5 Unexpected objects

`UNEXPECTED`: present in actual, absent in desired. Unexpected objects must be listed in the report and logged at `WARNING`. They must not produce changes in version 1. Removing them is an operator decision outside this library.

Exception: an unexpected `COLUMN` whose parent table is expected is a table-shape conflict. The core must add a `DESTRUCTIVE` change for the owning table with the reason `unexpected column <name>`, so that the default policy fails loudly instead of running against a foreign table shape.

The asymmetry applies to comments too: a `COMMENT` object exists only where a script's `COMMENT ON` produced one in the scratch snapshot, so removing the statement from the desired script never drops the comment — the actual comment is reported `UNEXPECTED` instead. Version 1 has no desired-state spelling of "no comment"; deleting one is an operator decision.

### 5.6 Safety classes

| Class | Definition | Examples |
|---|---|---|
| `ADDITIVE` | Creates something new. Existing objects and data are untouched. | Create missing table, sequence, index, view, routine, trigger; add missing constraint; add nullable column. |
| `REBUILD` | Drops and recreates an object that holds no data of its own, or rewrites pure metadata in place. | Recreate drifted index, view, constraint, routine or trigger; drop+create a policy; set or change a comment via `COMMENT ON`; domain alterations that cannot touch stored values (`SET`/`DROP DEFAULT`, `DROP NOT NULL`, `DROP CONSTRAINT`). |
| `DESTRUCTIVE` | Can remove or rewrite stored data, or can fail on existing data. | Drop or retype a column, change a table's column set, alter a sequence's options (this changes future values and can fail against the current value), drop+create a materialized view (its stored result rows are rewritten), a domain `SET NOT NULL` or `ADD CHECK` (validates existing values); manual changes for enum label removal or reorder and domain base-type changes. |

A `MISSING` table and the children its create covers (5.8.4) are created as one `ADDITIVE` change, whatever the nullability of its columns, because no stored data exists yet. So C-01 reconciles under the default policy. Rules 1 and 2 below govern tables present in the actual snapshot:

1. Any change to an existing `TABLE` must be classified `DESTRUCTIVE`. A change to an existing `COLUMN` must be classified `DESTRUCTIVE` unless the dialect renders it as in-place alterations that cannot lose data and cannot fail on any existing value — a default change, `DROP NOT NULL`, `SET STORAGE` or `SET COMPRESSION` (they re-point only future writes), or a widening from the dialect's explicit allowlist (A.6) — which it may classify `REBUILD`. `SET NOT NULL` always checks existing rows and can fail on them, so it is `DESTRUCTIVE`; the dialect must not bundle it with `REBUILD` operations on the same column. Widening safety is defined by the allowlist, never inferred from PostgreSQL accepting the cast; note that a lossless widening may still physically rewrite the table under the DDL lock (A.6). Adding a missing column to an existing table is a `CREATE` of that column, not a change to an existing one: it is `ADDITIVE` when the column is nullable, and is classified by rule 2 otherwise.
2. Adding a `NOT NULL` column without a default to an existing table must be classified `DESTRUCTIVE`, because it fails on a non-empty table. Adding a `NOT NULL` column with a default may be `ADDITIVE` where the engine fills it without rewriting existing rows (constant defaults on PostgreSQL 11+); the dialect must classify it by its engine's behaviour.
3. A dialect may raise a change to a stricter class, never to a looser one.
4. Adding a constraint that existing rows violate fails at apply time. This is an `ApplyException`, not a misclassification.

### 5.7 Fingerprints and stale plans

A snapshot fingerprint is the lowercase hex SHA-256 of the following byte sequence. For each object, in the deterministic `ObjectId` order of 5.2, four fields are written: the `ObjectKind` constant name, the parent (the empty string when absent), the name, and the canonical definition. Each field is encoded as its UTF-8 byte length in ASCII decimal, a colon (`:`), and then its UTF-8 bytes. Nothing separates objects. Because every field carries its length, no two different snapshots serialise to the same bytes.

With this format, a fingerprint depends only on the snapshot's content. Plans can therefore be checked across processes and library versions, as long as the dialect's normalisation rules have not changed (10.4).

`apply(plan)` must first reject the plan with `IllegalArgumentException` if `plan.targetNamespace()` or `plan.dialectId()` does not match this reconciler's configuration — the statements are already bound to a namespace, so a foreign plan would execute against the wrong namespace. It must then re-read the actual snapshot after acquiring the lock and throw `StalePlanException` if its fingerprint differs from `plan.actualFingerprint()`.

### 5.8 Dependencies and ordering

1. Dialects must populate `dependsOn` from catalog dependency information, for example view-on-table, view-on-routine, trigger-on-routine, foreign-key-on-referenced-key, and index-on-routine for expression indexes. Self-dependencies (a recursive routine, a self-referencing foreign key) must be recorded but must be ignored by ordering and cycle detection. Two tables with mutually referencing foreign keys do not form a cycle: the foreign keys depend on the tables, never the reverse.
2. When an object is recreated, every object that depends on it, directly or transitively, must also be recreated if the engine would otherwise drop it or reject the drop. The core obtains these changes by calling `Dialect.render` with a synthetic `DRIFTED` difference whose expected definition is the desired one; the synthetic difference drives rendering only and must not be added to `plan.differences()` unless the object is itself `MISSING` or `DRIFTED`. Such changes carry the reason `dependency of <id>`. Their `CREATE` or `ALTER` change carries the object's desired definition as `expected`, so verification (step 9) can check them.
3. If an object slated for drop or recreation has dependents that are not themselves in the desired or actual snapshot — objects in other namespaces, or `UNEXPECTED` objects inside the target namespace — the dialect must detect them from catalog dependency data and the core must fail with `PlanningException` naming each such dependent, before anything is applied. Discovering the conflict as a mid-apply error would violate P4.
4. When a `MISSING` parent is created by a change, differences on the children its create already covers must not produce separate changes. The dialect decides what its creates cover by implementing `Dialect.coveredChildren` (6.1). For each `MISSING` difference the core is about to render, it must call `coveredChildren` with that difference and the desired snapshot, drop every difference whose `ObjectId` is returned (and any change rendered for it), and still order and verify the covered objects as part of the parent's create: after apply, each covered object must be present in the actual snapshot with its desired definition (5.1 step 9). A dialect must not return an `ObjectId` that its `render` output for the parent does not create. The PostgreSQL profile folds columns and serial-owned sequences into the table's create, and renders constraints and indexes as separate changes (A.5, A.6).
5. Execution order is: all `DROP` changes in reverse dependency order of the *actual* snapshot, then all `CREATE` and `ALTER` changes in dependency order of the *desired* snapshot. A recreate contributes its `DROP` change to the first phase and its `CREATE` change to the second (4.4). Ties are broken by `ObjectKind` order, then by `ObjectId` as in 5.2, then by action (`ALTER` before `CREATE`). For example, recreating view `v` and its dependent view `w` executes `DROP w`, `DROP v`, `CREATE v`, `CREATE w`.
6. Dependency cycles must be reported as `PlanningException`.
7. **Name collisions.** For every `CREATE` change, the core must compare `Dialect.nameScopes` of its target with the scopes of every object in the actual snapshot that has a different `ObjectId` and no `DROP` change in the plan. A shared key means the create would fail on an existing name, typically held by an `UNEXPECTED` object. The core must then fail with `PlanningException` naming both objects, before anything is applied, rather than plan the create as `ADDITIVE` and fail mid-apply (P4).

## 6. Service Provider Interface

### 6.1 `Dialect`

```java
package studio.epicquest.schema.reconciler.spi;

public interface Dialect {

    /** Stable identifier, e.g. "postgresql". */
    String id();

    boolean supports(DatabaseMetaData metaData) throws SQLException;

    /** The major version of the core API this dialect was built against (10.3). */
    default int coreMajorVersion() { return 1; }

    DialectCapabilities capabilities();

    void acquireLock(Connection connection, String namespace, Duration timeout) throws SQLException;

    void releaseLock(Connection connection, String namespace) throws SQLException;

    /**
     * Binds unqualified names on this connection to the given namespace for the
     * rest of the run (for example SET LOCAL search_path). Called before executing the
     * desired script and before applying changes; a no-op for dialects that
     * fully qualify every rendered statement. The binding must not outlive the run:
     * transactional dialects should use a transaction-scoped setting; others must
     * restore the previous value on every exit path.
     */
    default void bindSessionNamespace(Connection connection, String namespace) throws SQLException { }

    /**
     * Bounds how long any single statement may run during this run (for
     * example SET LOCAL statement_timeout). Called once after the namespace
     * lock is acquired, so it never bounds the lock wait itself. A no-op for
     * dialects that cannot express the bound.
     */
    default void statementTimeout(Connection connection, Duration timeout) throws SQLException { }

    /**
     * Verifies that this connection's user holds the privileges a reconcile run
     * needs. Called once per run, before the lock, on the target connection —
     * with {@code createNamespace} telling it whether that connection must
     * also create the scratch namespace — and again on the scratch connection
     * when a separate {@code scratchDataSource} is configured, then with
     * {@code namespace} {@code null} and {@code createNamespace} {@code true}.
     * A missing namespace or insufficient privilege fails the run. A no-op
     * for dialects that cannot express the check.
     */
    default void checkAccess(Connection connection, String namespace,
                             boolean createNamespace) throws SQLException { }

    String createScratchNamespace(Connection connection, String targetNamespace) throws SQLException;

    void dropScratchNamespace(Connection connection, String scratchNamespace) throws SQLException;

    /**
     * Executes a complete script in the scratch namespace; the dialect decides
     * whether to batch or split it. scratchNamespace is the name
     * createScratchNamespace returned — qualified object references in the
     * script must resolve to it (3.2).
     */
    void executeScript(Connection connection, String script, String scratchNamespace) throws SQLException;

    /** Reads every object in the namespace, normalised per 5.3. */
    SchemaSnapshot readSnapshot(Connection connection, String namespace) throws SQLException;

    /** Renders the statements and safety class for one difference, bound to the target namespace. */
    List<PlannedChange> render(Difference difference, SchemaSnapshot desired, String targetNamespace);

    /**
     * Create-fragment statements for one snapshot object, written with the
     * {@code ${namespace}} token (3.2) so the emitted script is
     * namespace-neutral — the building block of {@code dump()} (3.1). An
     * object that cannot be represented as a create fragment returns an
     * empty list; the core emits it as a comment line instead of dropping
     * it silently (P4).
     */
    default List<String> dumpStatements(SchemaObject object) { return List.of(); }

    /**
     * Quotes an identifier for safe inclusion in SQL. Throws
     * IllegalArgumentException if the identifier is not legal in the engine
     * (for example empty, longer than the engine's limit, or containing
     * characters the engine cannot represent); the core relies on this to
     * validate the configured namespace (8.3).
     */
    String quoteIdentifier(String identifier);

    /**
     * The objects, other than the difference's own object, that this dialect's
     * render output for a MISSING difference creates as a side effect (for
     * example the columns of a new table). The core suppresses their own
     * differences and changes (5.8.4). Empty means the create covers nothing
     * else.
     */
    default Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) { return Set.of(); }

    /**
     * The scopes in which the engine requires this object's name to be unique,
     * as opaque keys (for example "relation:plans_pk"). Two objects with different
     * ObjectIds that share a key cannot coexist in one namespace (5.8.7).
     * Empty means the name is unique only within its ObjectId.
     */
    default Set<String> nameScopes(ObjectId id) { return Set.of(); }
}

public record DialectCapabilities(
        boolean transactionalDdl,
        boolean sessionDefaultNamespace,
        Set<ObjectKind> supportedKinds) { }
```

The core owns diffing, dependency expansion, ordering, policy, verification and transaction control. A dialect owns only vendor-specific SQL. `render` returns changes with `order` set to `0`; the core assigns the final order. If `capabilities().sessionDefaultNamespace()` is false, rendered statements must qualify every reference so they execute correctly without `bindSessionNamespace`.

### 6.2 Locking

`acquireLock` must give mutual exclusion between all processes reconciling the same target namespace in the same database, and must honour the timeout. Locks must be released on every exit path, and must not leak onto a pooled connection. Suitable mechanisms are advisory locks (PostgreSQL), `GET_LOCK` (MySQL) and `DBMS_LOCK` (Oracle). A dialect should translate the engine's lock-timeout error (for example SQLSTATE `55P03` on PostgreSQL) into `LockTimeoutException`; the core wraps any other `SQLException`.

### 6.3 Scratch namespaces

1. A scratch namespace name must be unique per run and must not collide with an existing namespace. The RECOMMENDED form is `<target>__rcl_<12 hex chars>`, truncated to the engine's identifier limit.
2. If `scratchDataSource` is configured, the scratch namespace must be created there instead of in the target database. `createScratchNamespace`, `executeScript` for the desired script, the scratch `readSnapshot` and `dropScratchNamespace` then run on connections from `scratchDataSource`; the actual snapshot and all applied changes run on the `dataSource` connection. A scratch database need not contain other namespaces: a desired script that references objects outside its own namespace (cross-schema foreign keys, external routines) will fail to load there with `DesiredStateException`; dialect profiles must document this limitation. This option is required for engines where creating a namespace needs privileges the application should not hold (for example `CREATE USER` in Oracle). Canonical definitions are rendered by the engine and can differ between versions. So on first use the core must check that the scratch database reports the same `DatabaseMetaData.getDatabaseProductName()` and the same major version as the target, and that the dialect's `supports()` accepts it. Otherwise it must fail with `ConfigurationException`.
3. The scratch namespace must be removed on every exit path. In transactional dialects, rolling back to a savepoint that precedes its creation satisfies this. On non-transactional dialects the dialect must drop it with cascade semantics, because the script may have created interdependent objects.
4. Loading the desired state must not modify the target namespace.

### 6.4 Non-transactional DDL

If `transactionalDdl` is false (for example MySQL and Oracle, where DDL commits implicitly):

1. Each `PlannedChange` must be idempotent with respect to its own target, so that re-running after a partial failure converges.
2. On failure, `ApplyException` must report the changes already applied and the change that failed.
3. Verification (step 9) still applies to the changes that completed.

## 7. Exceptions

```text
SchemaReconcilerException (extends RuntimeException)
├── ConfigurationException      invalid builder input, no or ambiguous dialect
├── LockTimeoutException        lock not acquired within lockTimeout
├── DesiredStateException       script could not be read, substituted or executed in scratch
├── PlanningException           dependency cycle, external or unexpected dependent, name collision, or unrenderable difference
├── PolicyViolationException    plan contains changes mapped to FAIL; carries the plan
├── StalePlanException          target changed between plan() and apply()
├── ApplyException              a statement failed; carries applied changes and the failed one
└── VerificationException       applied objects did not converge; carries remaining differences
```

Every message raised during a run must name the target namespace and, where applicable, the object id; a component that does not know the namespace (a dialect, the dialect resolver) may omit it, and the core then prefixes it. Messages from the builder and the `DesiredState` factories, which run before any namespace is in play, name the offending property or resource. `DesiredStateException` must include the engine's error message and position when the driver provides them. `SchemaReconcilerException` itself carries `timings()` — the `RunTimings` snapshot taken when the run failed, with the phases that completed and the partial duration of the phase that failed (`Duration.ZERO` where nothing ran yet, `RunTimings.unmeasured()` for exceptions raised outside a run).

## 8. Security

1. Object names read from the catalog must be quoted with `Dialect.quoteIdentifier` whenever they are placed into SQL.
2. The library must not build SQL from data supplied at run time other than the configured namespace and the trusted desired script.
3. The configured namespace must be validated as a legal identifier for the dialect before use: the core passes it to `Dialect.quoteIdentifier` and reports an `IllegalArgumentException` from it as `ConfigurationException`. Because a dialect is selected on first use (3.4), this validation happens then, before any SQL is sent.
4. Documentation should recommend running reconciliation with a role limited to the target namespace. The run verifies that role's privileges before locking (`Dialect.checkAccess`), so a misconfigured role fails fast rather than mid-apply. When no `scratchDataSource` is configured, that role additionally needs namespace-creation privilege on the target database (for example `CREATE ON DATABASE` in PostgreSQL); `scratchDataSource` exists for environments that cannot grant it.
5. The script checks of 3.2 and A.4 are guardrails, not a security sandbox. They scan top-level statements, and `DO` bodies only for `set_config` and session advisory locks: function and procedure bodies and other string literals are otherwise opaque to them, and dynamic SQL issued from inside (`EXECUTE`, `format()`) runs with the connection's privileges. Desired scripts must be treated as trusted artifacts — ship them with the service's own build, review them like migrations, and combine them with the least-privilege role of rule 4 rather than relying on lexical checks alone.

## 9. Conformance

### 9.1 The conformance suite

`studio.epicquest.schema:schema-reconciler-tck` provides an abstract JUnit Jupiter class `DialectConformanceTest`. A dialect passes when a subclass that supplies a disposable database passes every test. Dialects may skip tests only for object kinds absent from `supportedKinds()` or for capabilities they do not declare; skips must be reported.

### 9.2 Required scenarios

| ID | Scenario | Expected |
|---|---|---|
| C-01 | Empty target, valid script including `NOT NULL` columns without defaults | `RECONCILED` under the default policy; then a second run is `UP_TO_DATE` |
| C-02 | Target already matches | `UP_TO_DATE`; no statements executed against the target namespace |
| C-03 | Index dropped | `MISSING` index, `ADDITIVE`, restored |
| C-04 | Index with same name, different definition | `DRIFTED`, `REBUILD`, recreated to canonical form |
| C-05 | Partial or expression index drifted (where supported) | as C-04 |
| C-06 | Unique constraint dropped | `ADDITIVE`, restored |
| C-07 | Constraint with same name, different columns | `REBUILD`, recreated |
| C-08 | Foreign key dropped | `ADDITIVE`, restored |
| C-09 | View recreated with fewer columns | `REBUILD`; dependent views also recreated |
| C-10 | Routine body changed | `REBUILD`; dependent triggers and indexes handled per 5.8 |
| C-11 | Trigger dropped | `ADDITIVE`, restored |
| C-12 | Table with foreign column shape under expected name | `DESTRUCTIVE`; default policy throws `PolicyViolationException`; table and rows untouched |
| C-13 | Extra table in target | `UNEXPECTED` reported; table untouched |
| C-14 | Script creates an unsupported object kind | `UNSUPPORTED`; default policy fails |
| C-15 | Script has a syntax error | `DesiredStateException`; target untouched; no scratch namespace left behind |
| C-16 | Two concurrent `reconcile()` calls | serialised; both succeed; one is `UP_TO_DATE` |
| C-17 | `apply(plan)` after the target changed | `StalePlanException` |
| C-18 | `reportOnly()` policy with drift | `REPORTED_ONLY`; nothing executed |
| C-19 | Same input twice | identical plans (5.2) |
| C-20 | Lock not available within timeout | `LockTimeoutException` |
| C-21 | Pooled connection after any run | no lock and no session state left behind |
| C-22 | `plan()` with drift | target unchanged; no scratch namespace or session state left behind |
| C-23 | Self-referencing foreign key; mutually referencing foreign keys | reconciles; no `PlanningException` |
| C-24 | `apply(plan)` built for a different namespace or dialect | `IllegalArgumentException`; nothing executed |
| C-25 | An object outside the namespace, or an `UNEXPECTED` view inside it, depends on an object to be recreated | `PlanningException` naming the dependent; nothing executed; the unexpected view still exists |
| C-26 | `scratchDataSource` configured | scratch activity confined to that database; target sees only applied changes |
| C-27 | Policy `additive = APPLY`, `rebuild = REPORT`; a drifted view `v` and a missing view `w` that depends on `v` | both reported, `w` demoted with `v` named as the blocking object; nothing executed against either |
| C-28 | Script qualifies an object with the literal target namespace name | `DesiredStateException` with the position; target untouched |
| C-29 | Policy `unsupported = REPORT`; script creates an unsupported object and a supported object that depends on it | `UNSUPPORTED` difference in `report.unsupported()`; the dependent change demoted to `REPORT`; independent changes applied |
| C-30 | Drifted view `v` with dependent view `w` | plan order is `DROP w`, `DROP v`, `CREATE v`, `CREATE w`; both verified |
| C-31 | Missing index `i` on table `a`, while the target holds an unexpected index `i` on table `b` (where the dialect declares relation-scoped names) | `PlanningException` naming both; nothing executed |
| C-32 | Missing table with a serial-style or identity column | `RECONCILED`; no dependency cycle; the second run is `UP_TO_DATE` |
| C-33 | Drifted column on a table holding a `NULL` row: default change and `DROP NOT NULL`, then `SET NOT NULL` | default and `DROP NOT NULL` apply in place as `REBUILD` `ALTER COLUMN` changes; `SET NOT NULL` renders `DESTRUCTIVE` and fails with `PolicyViolationException` under the default policy |
| C-34 | Drifted column: unsafe type change | manual `DESTRUCTIVE` change; default policy throws `PolicyViolationException`; data untouched |
| C-35 | `unsupportedCategories` reports one category while `unsupported` stays `FAIL` | the tolerated `UNSUPPORTED` difference lands in `report.unsupported()`; a `FAIL`-category one still fails the plan |
| C-36 | `ReconcileReport.timings()` | every `RunTimings` phase is non-negative; `elapsed` covers their sum |
| C-37 | Materialized view missing | `MISSING` `MATERIALIZED_VIEW`, `ADDITIVE`, created with data; then `UP_TO_DATE` |
| C-38 | Materialized view drifted (viewdef changed) | `DESTRUCTIVE` `DROP`+`CREATE`+`REFRESH` under a `destructive=APPLY` policy; recreated to canonical form with data |
| C-39 | Enum type missing; label appended in order | `MISSING` `TYPE`, `ADDITIVE`; then appending one label renders an `ADDITIVE` `ALTER TYPE ... ADD VALUE` |
| C-40 | Enum with a removed or reordered label | manual `DESTRUCTIVE`; default policy throws `PolicyViolationException`; type untouched |
| C-41 | Domain missing | `MISSING` `DOMAIN`, `ADDITIVE`, created; then `UP_TO_DATE` |
| C-42 | Domain drift limited to safe alterations (default, drop not-null, drop check) | `REBUILD` in-place `ALTER DOMAIN`; converges |
| C-43 | Domain base-type change | manual `DESTRUCTIVE`; default policy throws `PolicyViolationException` |
| C-44 | Policy missing; policy drifted | `MISSING` `POLICY`, `ADDITIVE` created; a changed `USING` renders `REBUILD` `DROP`+`CREATE` |
| C-45 | Extension missing | `MISSING` `EXTENSION`, `ADDITIVE`, created in the target namespace; removed by the scratch rollback |
| C-46 | Comment missing; comment drifted | `COMMENT` `ALTER` changes (`REBUILD`) converge to the desired text |
| C-47 | Comment absent from desired, present in target | `UNEXPECTED` `COMMENT`, never dropped |
| C-48 | Desired script with interlocked new kinds (domain-typed column, materialized-view index, policy and comments on the table) | reconciles in dependency order; second run `UP_TO_DATE` |

### 9.3 Implementation conformance

This specification defines two conformance targets:

1. A **conforming core** implements every requirement of sections 2–8 and 10 for the packages listed in 2.2, and passes the core test suite shipped with the reference implementation.
2. A **conforming dialect** implements `studio.epicquest.schema.reconciler.spi.Dialect` for one database product, honours the declarations of its `DialectCapabilities`, and passes `DialectConformanceTest` (9.1) with only declared skips.

A product may be described as implementing this specification only when a core and at least one dialect pass their respective conformance suites. Implementations must not claim compatibility on the basis of a partial pass; skipped scenarios (9.1) are the only permitted deviations, and only for declared-unsupported kinds or capabilities. `OTHER` detection (5.4) is exempt from skipping: every conforming dialect must detect it.

### 9.4 Deliverables

A release of this project ships three deliverables: this document, the reference implementation (the PostgreSQL dialect on the shared core), and the conformance suite (`schema-reconciler-tck`).

## 10. Versioning and compatibility

1. Releases follow Semantic Versioning 2.0.0.
2. Within a major version, the API and model must stay source- and binary-compatible. New SPI methods must be `default` methods. Before 1.0 the SPI may still change signatures, so dialect implementations must be recompiled against each release.
3. A dialect declares the core major version it was built against via `Dialect.coreMajorVersion()` (6.1). The core must refuse, with `ConfigurationException`, a dialect reporting a different major version than its own.
4. Changing a dialect's normalisation rules is a breaking change for that dialect, because it can turn equal objects into drifted ones.
5. Implementations of this specification must run on Java SE 25 or later and must not rely on preview, incubator or deprecated-for-removal APIs.

## 11. Open issues

| ID | Issue | Proposed resolution |
|---|---|---|
| OI-1 | One script per dialect, or one portable script? | One script per dialect. Translation is a non-goal (1.5). |
| OI-2 | Should version 1 ever drop unexpected objects? | No. Revisit with an explicit `prune` policy in a later version. |
| OI-3 | Online index builds (`CREATE INDEX CONCURRENTLY` cannot run in a transaction) | Out of scope for version 1; the PostgreSQL profile rejects it (3.2 rule 3). |
| OI-4 | Namespace-token replacement (5.3 rule 1) could also match text inside string literals or routine bodies | Matches require an identifier boundary and scripts may not name the target literally (3.2). Matches inside literals remain possible and are symmetric between desired and actual, so they do not cause drift. |
| OI-5 | Sequence current values differ between scratch and target | Compare sequence options only, never the current value. |
| OI-6 | DDL applied in one transaction holds its locks (for example `ACCESS EXCLUSIVE` for recreated constraints and indexes) until commit | Locks release at commit (3.5). Applications should reconcile at startup, before taking traffic. |

## Appendix A. PostgreSQL profile — reference implementation

### A.1 Capabilities

| Capability | Value |
|---|---|
| `id()` | `postgresql` |
| `supports` | `DatabaseMetaData.getDatabaseProductName()` equals `PostgreSQL` and the major version is 14 or later |
| `transactionalDdl` | `true` |
| `sessionDefaultNamespace` | `true` (`search_path`) |
| `supportedKinds` | all kinds except `OTHER` |

### A.2 Locking

**Access preflight (`checkAccess`).** Before the lock, the dialect resolves the target namespace with `to_regnamespace` — a null result fails the run with `ConfigurationException`, because PostgreSQL silently ignores a `search_path` entry that names a missing schema and applied DDL would land in the wrong namespace — and checks `has_schema_privilege(current_user, <namespace>, 'USAGE')` and, separately, `'CREATE'` (a comma-separated list means *any of*, so the two privileges must not share one call), failing with `ConfigurationException` when the connecting role lacks either. The namespace is passed to `to_regnamespace` as a quoted identifier so mixed-case names resolve exactly. When no `scratchDataSource` is configured it also checks `has_database_privilege(current_user, 'CREATE')`, the privilege scratch-schema creation needs, and fails with `ConfigurationException` naming the missing privilege (5.1 step 1).

`SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))` with `:key = 'studio.epicquest.schema.reconciler:' || <database> || ':' || <namespace>`, under `SET LOCAL lock_timeout` set to the reconciler's `lockTimeout`. Once the lock is held, the dialect must run `SET LOCAL lock_timeout = DEFAULT`, so that the reconciler's timeout does not also govern lock waits of the applied DDL; those follow the session's own setting. The lock is released by commit or rollback. Session-level advisory locks are not undone by a rollback, so a lock taken by the script — directly, or inside a function it invoked — would outlive the scratch run. The dialect must call `pg_advisory_unlock_all()` immediately after the scratch rollback and again before returning the connection. Transaction-scoped locks, including the namespace lock, are unaffected by it.

When `statementTimeout` is configured, the dialect applies it immediately after the lock as `SET LOCAL statement_timeout = <millis>`; being transaction-scoped it needs no explicit restore and never bounds the lock wait itself. With a separate `scratchDataSource` the engine applies the same bound on the scratch connection too — one `SET LOCAL` cannot cross connections, so without this the desired script and scratch snapshot read would run unbounded. It bounds *every* statement of the run — catalog reads included — not the total run duration. Note that inspection itself can stall: catalog getters such as `pg_get_expr` open the relation they deparse with `AccessShareLock`, so reading a schema that holds an `ACCESS EXCLUSIVE`-locked table can block until `statement_timeout` cancels the query.

### A.3 Scratch namespace

Inside the run's transaction: `SAVEPOINT rcl_scratch`, `CREATE SCHEMA <scratch>`, execute the script, read the snapshot, then `ROLLBACK TO SAVEPOINT rcl_scratch`. The scratch schema never becomes visible to other sessions.

After the script, the dialect must confirm that the transaction is still open and that the savepoint exists (3.2 rule 3), for example by checking that `ROLLBACK TO SAVEPOINT rcl_scratch` succeeds. If the script committed, the dialect must run `DROP SCHEMA <scratch> CASCADE` and raise `DesiredStateException`.

With `scratchDataSource` (6.3.2), the same sequence runs on the scratch connection in its own transaction: `BEGIN`, `SAVEPOINT rcl_scratch`, `CREATE SCHEMA <scratch>`, execute the script, read the snapshot, `ROLLBACK TO SAVEPOINT rcl_scratch`, after which the engine rolls the scratch transaction back. The namespace lock is taken only on the target connection.

### A.4 Script execution

The whole script is sent in one `Statement.execute` call. The server parses it, so dollar-quoted bodies need no client-side splitting.

**Least-privilege role.** The connecting role for `plan()`/`reconcile()`/`apply()` needs exactly `USAGE` and `CREATE` on the target schema plus `CREATE` on the database that hosts the scratch namespace:

```sql
GRANT USAGE, CREATE ON SCHEMA svc TO app_reconciler;
GRANT CREATE ON DATABASE appdb TO app_reconciler;
```

Where `CREATE ON DATABASE` cannot be granted, a separate `scratchDataSource` (6.3.2) pointing at a scratch database replaces it; the target role then needs only the schema grants and the scratch role owns the scratch database. `dump()` creates no scratch namespace, so its session runs `checkAccess` with `createNamespace` `false`: a role holding only `USAGE` and `CREATE` on the schema can dump (catalog reads need no extra grants — the `pg_catalog` views used are world-readable).

### A.5 Catalog sources

| Kind | Source | Canonical definition |
|---|---|---|
| `TABLE` | `pg_class` (`relkind` `r`, excluding inheritance children) | `relkind`, `relpersistence`, `reloptions`, `relreplident`, and for replica identity `USING INDEX` the index name; columns are separate objects |
| `COLUMN` | `pg_attribute`, `pg_attrdef`, `pg_collation` | `format_type(atttypid, atttypmod)`, nullability, `pg_get_expr(adbin, adrelid)` as the default or, when `attgenerated` is set, as the generation expression; collation when it is not the type's default; identity kind and identity sequence options; `attstorage` storage strategy and `attcompression` compression method |
| `PRIMARY_KEY`, `UNIQUE`, `FOREIGN_KEY`, `CHECK` | `pg_constraint` (`contype` `p`, `u`, `f`, `c`), excluding inherited copies (`conislocal` `false` — partition-child and classic-inheritance constraints, which only the parent's object owns) | `pg_get_constraintdef(oid)` |
| `INDEX` | `pg_index` (`relkind` `i`), excluding constraint-backing indexes (`pg_constraint.conindid` on `p`, `u`, `x` constraints) and partition-child indexes | `pg_get_indexdef(indexrelid)` |
| `VIEW` | `pg_class` (`relkind` `v`) | `pg_get_viewdef(oid, true)` plus column names and types, plus `reloptions` (`check_option`, `security_barrier`, `security_invoker`) in sorted order |
| `SEQUENCE` | `pg_sequence`, `pg_class.relpersistence` | type, start, increment, min, max, cache, cycle, persistence (`LOGGED`/`UNLOGGED`); never the current value |
| `ROUTINE` | `pg_proc` (`prokind` `f`, `p`) | `pg_get_functiondef(oid)`; the identity signature (4.1) is `proname` plus `format_type` over each element of `proargtypes` |
| `TRIGGER` | `pg_trigger` (`NOT tgisinternal` and `tgparentid` `0` — partition clones are not standalone objects) | `pg_get_triggerdef(oid, true)` plus the enabled state (`tgenabled`) |
| `MATERIALIZED_VIEW` | `pg_class` (`relkind` `m`) | `pg_get_viewdef(oid, true)` plus column names and types, plus `reloptions`, `relpersistence` and `relispopulated` |
| `TYPE` | `pg_type` (`typtype` `e` — enums only) | `typtype` plus the labels of `pg_enum` ordered by `enumsortorder` |
| `DOMAIN` | `pg_type` (`typtype` `d`) | `format_type(typbasetype, typtypmod)`, `typnotnull`, `pg_get_expr(typdefaultbin, 0)`, collation when it differs from the base type's, and the domain's `CHECK` constraints (`pg_constraint` on `contypid`, `contype` `c`, `pg_get_constraintdef`, in constraint-name order) |
| `POLICY` | `pg_policy` | `polcmd`, `polpermissive`, the role list as sorted, individually quoted names (`polroles` oid `0` is `PUBLIC`), `pg_get_expr(polqual, polrelid)`, `pg_get_expr(polwithcheck, polrelid)` |
| `EXTENSION` | `pg_extension` | presence only — the literal `extension`; the version is never compared (`ALTER EXTENSION UPDATE` is out of scope), and `ObjectId.name` keeps the `extension:<name>` form so the id is stable across the promotion |
| `COMMENT` | `pg_description` joined to the managed object (`objoid`, `objsubid` `0` for objects, `> 0` for columns) | the comment text; a target with no row has no `COMMENT` object |
| dependencies | `pg_depend`, `pg_rewrite`, `pg_inherits`, `pg_trigger`, restricted to objects of the namespace being read | populate `dependsOn` |
| `OTHER` | see below | detected so that unsupported constructs are reported (5.4) |

`OTHER` detection covers every namespace-scoped catalog object the managed
kinds do not claim: non-managed relations (`relkind` `f`, `p`, `c`, `t`,
`I` and `r` tables with an inheritance parent), exclusion constraints
(`contype` `x`), non-enum user types (`pg_type` `r`, `m` and base types;
composite types carry `relkind` `c` and collide with the relation
namespace, so they stay `OTHER` rather than `TYPE`), aggregates and window
functions (`pg_proc` `a`, `w`), non-view rules (`pg_rewrite`), statistics
(`pg_statistic_ext`), collations (`pg_collation`), conversions
(`pg_conversion`), text-search objects (`pg_ts_config`, `pg_ts_dict`,
`pg_ts_parser`, `pg_ts_template`), operators, operator classes and families
(`pg_operator`, `pg_opclass`, `pg_opfamily`), comments on unmanaged or
otherwise unrepresentable targets (`pg_description`), security labels
(`pg_seclabel`), granted privileges (non-null `relacl`, `proacl`, `typacl`; compared by content: the ACL entries as sorted `grantor>grantee:PRIVILEGE[*]` tokens, with the owner written `<owner>` and `*` marking a grant option, so a target holding the same grants reports no difference), ownership changed away from the connecting role
(`relowner`, `proowner`, `typowner`) and publication bindings
(`pg_publication_rel` for tables, `pg_publication_namespace` for
`FOR TABLES IN SCHEMA` on PostgreSQL 15+, `pg_publication_sequences` for
sequences on PostgreSQL 19+). Three catalog object kinds have no namespace of their
own but are detected through a namespace anchor instead: `pg_cast` rows
whose source type, target type or cast function is in the namespace,
`pg_transform` rows anchored by their type or SQL functions, and
`pg_language` rows whose handler functions are in the namespace.

`OTHER` object ids carry a documented category prefix — the substring
before the first `:` of the `ObjectId` name, used by
`unsupportedCategories` (3.3): `foreign`, `partition`,
`partindex`, `composite`, `toast`, `inherited` and `relation` (any other
non-managed `relkind`), `constraint` (exclusion), `type` (non-enum),
`rule`, `statistics`, `collation`, `conversion`, `fts-config`,
`fts-dict`, `fts-parser`, `fts-template`, `operator`, `opclass`,
`opfamily`, `comment` (unmanaged target), `seclabel`, `privileges`,
`ownership`, `publication`, `cast`, `transform`, `language` and `routine`
(aggregate or window). Extension *member* objects (`pg_depend`
`deptype = 'e'`) are excluded from the model entirely: they cannot be
created or dropped standalone, so a member function or type must never
surface as a managed object or an `UNEXPECTED` one — only the
`pg_extension` row itself is reported, as an `EXTENSION` object named
`extension:<name>`.

A `COMMENT` object's `ObjectId` mirrors its target — same `name`, same
`parent` — so a comment on table `t` is `COMMENT t`, a comment on column
`t.c` is `COMMENT c` with parent `t`, and a comment on index `i` on `t`
is `COMMENT i` with parent `t`. When two commented targets share name and
parent (a function and its aggregate use the same signature name, a
constraint and its backing index share a name), the second id gains a
`(<target kind>)` suffix in name order so both comments surface. `COMMENT`
objects `dependsOn` their target. A `POLICY` object's `ObjectId` is the
policy name with `parent` the table, and it carries both the parent edge
and an explicit `dependsOn` entry for the table. A table's
`relrowsecurity` flag is part of the `TABLE` canonical definition, so
enabling row-level security with no policies still reconciles — a `TABLE`
drift limited to that flag renders an in-place `ALTER TABLE ... ENABLE/DISABLE
ROW LEVEL SECURITY` (`REBUILD`); any other `TABLE` definition change stays
manual `DESTRUCTIVE` as before.

Objects that have no namespace anchor at all — databases, tablespaces,
roles, event triggers, servers, foreign data wrappers, user mappings,
publications, subscriptions, access methods, default privileges and large
objects — are outside the snapshot model. The script constraints of 3.2
reject the non-transactional ones outright, and the PostgreSQL script
check additionally rejects DDL for every database-wide object it cannot
represent (`SERVER`, `PUBLICATION`, `FOREIGN DATA WRAPPER`, `USER MAPPING`,
`EVENT TRIGGER`, `ACCESS METHOD`, `LANGUAGE`, `ROLE`/`GROUP`/`USER`,
`DEFAULT PRIVILEGES`, `OWNED`, `REASSIGN OWNED`, role-membership
`GRANT`/`REVOKE`, and `ALTER ... SET SCHEMA`), so a script cannot silently
create an undetectable object. It also rejects any object name in
`CREATE`/`DROP`/`ALTER` position — including the `ON`-anchored names of
`CREATE INDEX`, `CREATE TRIGGER` and `CREATE POLICY`, and the object
targets of `GRANT`/`REVOKE`, `COMMENT` and `SECURITY LABEL` — that is
qualified by a schema other than the scratch namespace (`pg_temp`
excepted), because such a statement creates or changes an object outside
the sandbox (3.2 rule 1); a `GRANT ... ON SCHEMA` is legal only on the
scratch namespace itself. References to foreign schemas elsewhere in a
statement remain legal: a cross-schema `REFERENCES` clause is a declared
external dependency, not an escaping creation. Finally it rejects
session-state changes — `SET` without `LOCAL` or `TRANSACTION`,
`RESET`, `LOAD`, and any `set_config` call whose third argument is not the
literal `true` (its arguments are parsed, string literals included) — and
scans the bodies of `DO` blocks for `set_config` and session advisory
locks (3.2 rule 2). Lexically it follows PostgreSQL: backslash escapes
inside `E'...'` strings, and a `$` that continues an identifier does not
open a dollar-quoted body. Casts and transforms anchored only to
foreign objects — a `CAST (text AS int)` with a `pg_catalog` function —
remain outside the model. Appendix C tabulates coverage against the
PostgreSQL 14–19 SQL command sets.

The snapshot must be read with `search_path` set to the namespace being read, using `SET LOCAL` so that the setting ends with the transaction, or with the savepoint for the scratch snapshot. `pg_get_viewdef`, `pg_get_functiondef` and the expression getters then omit the qualifier for that namespace; getters that always qualify, such as `pg_get_indexdef` (`... ON <ns>.<table> ...`), are handled by normalisation rule 5.3(1).

Sequences that back columns are handled by how they are owned:

- **Identity columns.** The internal sequence (`pg_depend.deptype` `i`) is not reported as an object. Identity generation (`ALWAYS` or `BY DEFAULT`) and its sequence options are part of the `COLUMN` canonical definition.
- **Serial-style owned sequences** (`deptype` `a`, `OWNED BY`). These are reported as `SEQUENCE` objects under their catalog names, for example `plans_id_seq`. Their canonical definition includes `OWNED BY <table>.<column>`. Their `dependsOn` records the owning *table*, never the column. The column's `nextval(...)` default already depends on the sequence, so recording the ownership edge against the column would form a cycle (5.8.6). When the owning table is `MISSING`, its create covers its owned sequences (5.8.4) and runs `CREATE SEQUENCE`, `CREATE TABLE`, `ALTER SEQUENCE ... OWNED BY` as one change.

Name scopes (`nameScopes`, 5.8.7). Tables, views, materialized views, sequences and indexes share one relation namespace per schema; types and domains share the same type namespace, which collides with the relation namespace through composite-type rowtypes. Primary-key and unique constraints also create an index under their own name. Constraint names are unique per table:

| Kind | Keys |
|---|---|
| `TABLE`, `VIEW`, `MATERIALIZED_VIEW`, `SEQUENCE`, `INDEX`, `OTHER` relations | `relation:<name>` |
| `TYPE`, `DOMAIN`, `OTHER` types (`type:`) | `relation:<name>` (the shared type/relation namespace) |
| `ROUTINE`, `OTHER` routines (`routine:` aggregates, window functions) | `routine:<name>(<signature>)` |
| `PRIMARY_KEY`, `UNIQUE` | `relation:<name>`, `constraint:<parent>.<name>` |
| `FOREIGN_KEY`, `CHECK` | `constraint:<parent>.<name>` |
| `OTHER` constraints (`constraint:` exclusion constraints) | `constraint:<parent>.<name>`, `relation:<name>` (the backing index) |
| `COLUMN`, `TRIGGER`, `POLICY`, `EXTENSION`, `COMMENT` | none (the `ObjectId` is already the engine's uniqueness key) |

### A.6 Rendering

| Difference | Statements | Safety |
|---|---|---|
| `MISSING` table | `CREATE TABLE` assembled from all desired columns, including `NOT NULL` columns without defaults (no separate column changes, 5.8.4), with `UNLOGGED` and `WITH (<reloptions>)` as desired, followed by `ALTER TABLE ... REPLICA IDENTITY FULL|NOTHING` when set; constraints and indexes follow as separate changes | `ADDITIVE` |
| `MISSING` nullable column on an existing table | `ALTER TABLE ... ADD COLUMN` | `ADDITIVE` |
| `MISSING` `NOT NULL` column with a constant default (see below) | `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT` | `ADDITIVE` (metadata-only since PostgreSQL 11) |
| `MISSING` `NOT NULL` column on an existing table whose default is not constant | manual change (no statements) | `DESTRUCTIVE` |
| `MISSING` constraint | `ALTER TABLE ... ADD CONSTRAINT <name> <pg_get_constraintdef>`, followed by `ALTER TABLE ... REPLICA IDENTITY USING INDEX <name>` when its index is the table's replica identity | `ADDITIVE` |
| `DRIFTED` constraint | a `DROP` change `ALTER TABLE ... DROP CONSTRAINT`, then a `CREATE` change `ADD CONSTRAINT` | `REBUILD` |
| `MISSING` / `DRIFTED` index | `CREATE`: the desired `pg_get_indexdef`, followed by `ALTER TABLE ... REPLICA IDENTITY USING INDEX` when it is the table's replica identity; when drifted, preceded by a `DROP` change `DROP INDEX` | `ADDITIVE` / `REBUILD` |
| `MISSING` / `DRIFTED` view | `CREATE`: `CREATE VIEW ... AS <pg_get_viewdef>`; when drifted, preceded by a `DROP` change `DROP VIEW` | `ADDITIVE` / `REBUILD` |
| `MISSING` sequence not covered by a table create | `CREATE`: `CREATE [UNLOGGED] SEQUENCE` with the desired options, followed by `ALTER SEQUENCE ... OWNED BY` when it is owned | `ADDITIVE` |
| `DRIFTED` sequence | `ALTER`: `ALTER SEQUENCE` setting the desired options and `ALTER SEQUENCE ... SET LOGGED`/`SET UNLOGGED` when persistence differs; the current value is never changed (OI-5) | `DESTRUCTIVE` |
| `MISSING` routine | `CREATE`: `pg_get_functiondef` | `ADDITIVE` |
| `DRIFTED` routine | `ALTER`: `CREATE OR REPLACE` from `pg_get_functiondef`; if the return type changed, a `DROP` change `DROP FUNCTION`/`DROP PROCEDURE` plus a `CREATE` change instead | `REBUILD` |
| `MISSING` / `DRIFTED` trigger | `CREATE`: `pg_get_triggerdef`, followed by `ALTER TABLE ... ENABLE|DISABLE TRIGGER` for a non-default enabled state; when drifted, preceded by a `DROP` change `DROP TRIGGER` | `ADDITIVE` / `REBUILD` |
| `MISSING` materialized view | `CREATE MATERIALIZED VIEW` with the desired viewdef, `reloptions` and `relpersistence`, `WITH NO DATA` when the desired object is unpopulated | `ADDITIVE` |
| `DRIFTED` materialized view | `DROP MATERIALIZED VIEW` + `CREATE MATERIALIZED VIEW` + `REFRESH MATERIALIZED VIEW` (skipped when the desired object is unpopulated); dependent indexes follow via the dependency graph | `DESTRUCTIVE` — the stored result rows are rewritten (5.6) |
| `MISSING` enum type | `CREATE TYPE <name> AS ENUM (<labels>)` | `ADDITIVE` |
| `DRIFTED` enum whose desired labels append to the actual ones in order | one `ALTER TYPE ... ADD VALUE` per new label | `ADDITIVE` — pure in-order growth |
| `DRIFTED` enum with a removed or reordered label | manual change (no statements) | `DESTRUCTIVE` |
| `MISSING` domain | `CREATE DOMAIN` with base type, collation, `DEFAULT`, `NOT NULL` and `CHECK` constraints | `ADDITIVE` |
| `DRIFTED` domain | in-place `ALTER DOMAIN` statements decomposed from the canonical difference — `SET`/`DROP DEFAULT`, `DROP NOT NULL`, `DROP CONSTRAINT` go in one `ALTER` change; `SET NOT NULL` and `ADD CONSTRAINT` validate existing values, so each renders as a separate `ALTER` change; a base-type or collation change is a manual change (no statements) | `REBUILD` / `DESTRUCTIVE` |
| `MISSING` policy | `CREATE POLICY ... ON <table> FOR <cmd> TO <roles>` with `USING` / `WITH CHECK` as desired | `ADDITIVE` |
| `DRIFTED` policy | `DROP POLICY` + `CREATE POLICY` | `REBUILD` |
| `DRIFTED` table limited to `relrowsecurity` | `ALTER`: `ALTER TABLE ... ENABLE|DISABLE ROW LEVEL SECURITY` | `REBUILD` |
| `MISSING` extension | `CREATE EXTENSION IF NOT EXISTS <name> WITH SCHEMA <target>` | `ADDITIVE` |
| `DRIFTED` extension | unreachable — the canonical definition is presence-only (asserted manual change as a safeguard) | `DESTRUCTIVE` |
| `MISSING` / `DRIFTED` comment | `ALTER`: `COMMENT ON <kind> <target> IS '<literal>'` (`COMMENT ON MATERIALIZED VIEW` for a materialized-view target) | `REBUILD` — metadata only, no data risk |
| `DRIFTED` column | in-place `ALTER TABLE ... ALTER COLUMN` statements decomposed from the canonical difference — `SET`/`DROP DEFAULT`, `DROP NOT NULL`, `SET STORAGE`, `SET COMPRESSION`, and `TYPE` only for a widening (below); one `ALTER` change | `REBUILD` |
| `DRIFTED` column that also needs `SET NOT NULL` | the `REBUILD` change above carries only the safe statements; the `SET NOT NULL` renders as a second `ALTER` change on the same column | `DESTRUCTIVE` |
| `DRIFTED` column with a generated, identity or collation change, or a type change outside the widening list | manual change (no statements) | `DESTRUCTIVE` |
| any `DRIFTED` table, `MISSING` `NOT NULL` column without default on an existing table, table-shape conflict | manual change (no statements) | `DESTRUCTIVE` |

**Column type widenings.** A `DRIFTED` column's `TYPE` alteration is rendered only for conversions on this allowlist — conversions that preserve every stored value and cannot fail: the integer chain (`smallint` → `integer` → `bigint`), `character varying(n)` growing, becoming unbounded `character varying`, or becoming `text`, `character(n)` growing, and `numeric(p,s)` to `numeric(p′,s′)` with `s′ ≥ s` and `p′ − s′ ≥ p − s` (the integer digits never shrink), or to unbounded `numeric`. Everything else — `text` to `varchar(n)`, `bigint` to `integer`, `varchar` to `integer`, `char` to `varchar`/`text` (trailing spaces are stripped), type-family changes — stays a manual change (5.6 rule 1). The allowlist defines safety, not the planner: several entries (`integer` chain, `character`/`numeric` growth) still rewrite the table physically under the `ALTER` lock — safe for data, not free at runtime.

**Constant defaults.** Deciding whether an arbitrary default expression is volatile would require parsing SQL (P1), so the profile uses a conservative lexical rule, which 5.6 rule 3 permits. A default counts as *constant* only if its canonical text is a numeric literal, a boolean literal, `NULL`, or a quoted string literal, in each case optionally followed by one `::type` cast. Anything else, for example `now()`, `gen_random_uuid()`, an arithmetic expression or any function call, is treated as not constant. A dialect must classify by this rule and must not loosen it.

**Covered children (`coveredChildren`).** For a `MISSING` table: every `COLUMN` whose parent is that table, and every `SEQUENCE` that is serial-owned by one of those columns (A.5). For every other difference the dialect returns an empty set.

### A.7 Migration path for an existing deployment script

1. Replace the literal namespace qualifier (for example `svc.`) in the deployment script `db/schema.sql` with `${namespace}.`.
2. Remove the deployment-only statements that violate the script constraints of 3.2 — `CREATE SCHEMA IF NOT EXISTS svc;` and `SELECT pg_advisory_lock(...)` — because the library owns namespace binding and locking. (`pg_advisory_lock` would otherwise leak a session-level lock onto the pooled connection: it is not released by rollback.)
3. Replace the hand-written reconcile routine with a `SchemaReconciler.builder()` call configured for the target namespace, and delete the duplicated expected-state maps (of tables, constraints, indexes and views).
4. Keep the application's existing reconcile tests as a regression suite until the TCK covers the same scenarios.

## Appendix B. Example

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

## Appendix C. PostgreSQL 14–19 feature coverage and test corpus

Coverage of the PostgreSQL SQL command set by the reference dialect, measured against what a desired script can express (PostgreSQL documentation, part VI reference). PostgreSQL 14 lacks `MERGE`, a data statement the script check passes through like other DML (on 14 it fails at execution, as any version-specific user syntax does). The catalog queries use no columns that are missing on 14. PostgreSQL 19 adds `REPACK` and `WAIT FOR LSN`, both rejected as session/utility statements below, and `pg_publication_sequences`, which exposes sequence publication bindings.

### Reconciled constructs (managed kinds)

| SQL construct | Handling |
|---|---|
| `CREATE TABLE` / `ALTER TABLE` | `TABLE` objects (columns, generated columns, identity columns, `NOT NULL`, defaults, collations, `relpersistence`, `reloptions`, `relreplident`); inheritance children and partition children are `OTHER` instead |
| `PRIMARY KEY`, `UNIQUE`, `FOREIGN KEY`, `CHECK` | constraint kinds via `pg_constraint` |
| `CREATE INDEX` | `INDEX` objects (partial, expression, unique — including indexes that back `FOREIGN KEY` references); `CONCURRENTLY` is non-transactional and rejected (3.2) |
| `CREATE [OR REPLACE] VIEW` | `VIEW` objects incl. `reloptions` (`check_option`, `security_barrier`, `security_invoker`) |
| `CREATE MATERIALIZED VIEW` / `REFRESH MATERIALIZED VIEW` | `MATERIALIZED_VIEW` objects incl. `reloptions`, `relpersistence`, populated state; its indexes are managed `INDEX` objects |
| `CREATE TYPE ... AS ENUM` / `ALTER TYPE ... ADD VALUE` | `TYPE` objects; other `typtype` values stay `OTHER` |
| `CREATE DOMAIN` / `ALTER DOMAIN` | `DOMAIN` objects incl. base type, `NOT NULL`, `DEFAULT`, `CHECK` constraints |
| `CREATE POLICY` / `ALTER POLICY` / `DROP POLICY`, `ALTER TABLE ... ENABLE|DISABLE ROW LEVEL SECURITY` | `POLICY` objects; `relrowsecurity` is part of the `TABLE` canonical definition |
| `CREATE EXTENSION` | `EXTENSION` objects, presence-only; member objects stay excluded; `ALTER EXTENSION` is rejected (version upgrades out of scope) |
| `COMMENT ON` | `COMMENT` objects on managed targets (`TABLE`, `COLUMN`, `VIEW`, `MATERIALIZED_VIEW`, `SEQUENCE`, `INDEX`, constraint kinds, `ROUTINE`, `TRIGGER`, `TYPE`, `DOMAIN`, `POLICY`); on any other target it is detected as `OTHER` — see below. `COMMENT ON EXTENSION` stays rejected: an extension's shipped description is database-wide metadata anchored to the extension row, which already reports |
| `CREATE SEQUENCE` / `ALTER SEQUENCE` | `SEQUENCE` objects; serial-owned and identity sequences folded per A.5 |
| `CREATE FUNCTION` / `CREATE PROCEDURE` | `ROUTINE` objects (`prokind` `f`, `p`) |
| `CREATE TRIGGER` | `TRIGGER` objects incl. `tgenabled` state; constraint triggers are excluded by `tgisinternal` |
| `GRANT`/`REVOKE`, `ALTER ... OWNER TO`, `SECURITY LABEL` | attributes are detected — see below |
| `CREATE SCHEMA` | rejected: the core owns the namespace (3.2) |

### Detected but unsupported (`OTHER` → `UNSUPPORTED` per 5.4)

`EXCLUDE` constraints, `CREATE TYPE` for non-enum types (range/multirange/base/composite — composites stay `OTHER` because their `relkind` `c` rowtype collides with the relation namespace), `CREATE FOREIGN TABLE`, `IMPORT FOREIGN SCHEMA`, partitioned tables and indexes, classic-inheritance children, `CREATE AGGREGATE`, window functions, `CREATE RULE`, `CREATE STATISTICS`, `CREATE COLLATION`, `CREATE CONVERSION`, `CREATE TEXT SEARCH CONFIGURATION|DICTIONARY|PARSER|TEMPLATE`, `CREATE OPERATOR`, `CREATE OPERATOR CLASS`, `CREATE OPERATOR FAMILY`, `CREATE CAST` and `CREATE TRANSFORM` when anchored to a namespace object, `COMMENT ON` an unmanaged target, `SECURITY LABEL`, granted privileges, ownership reassignment, `FOR TABLE` publication bindings, `FOR TABLES IN SCHEMA` publication bindings (`pg_publication_namespace`, PostgreSQL 15+) and sequence publication bindings (`pg_publication_sequences`, PostgreSQL 19+). Publication bindings are observable on the target only: the script check rejects every `PUBLICATION` statement, so a script cannot create one.

### Out of scope (cluster-level or statement-only, per 1.5/3.2)

Objects with no namespace anchor — `CREATE DATABASE`*, `CREATE TABLESPACE`*, `CREATE SUBSCRIPTION`*, `CREATE PUBLICATION`, `CREATE EVENT TRIGGER`, `CREATE SERVER`, `CREATE FOREIGN DATA WRAPPER`, `CREATE USER MAPPING`, `CREATE ACCESS METHOD`, `CREATE LANGUAGE`, `CREATE ROLE|GROUP|USER`, default privileges, `REASSIGN OWNED`, `DROP OWNED`, `ALTER SYSTEM`*, `ALTER ... SET SCHEMA`, role-membership `GRANT`/`REVOKE` — and session/utility statements — transaction control*, `VACUUM`*, `CHECKPOINT`*, bare `CLUSTER`* (no table), `DISCARD`* (`DISCARD ALL` cannot run in a transaction; the other forms are session state), session-level advisory locks, `LOAD`*, bare and `CONCURRENTLY` `REPACK`*, `WAIT FOR LSN`. Large objects are created by function call, not by a statement, so the script check does not see them. Items marked `*` are rejected by the script check because they cannot run inside a transaction; the rest are rejected because their catalog rows have no namespace anchor and could never be detected (5.4), or — `WAIT FOR LSN` — because the statement manipulates session or replication state only. `LISTEN`/`NOTIFY`/`UNLISTEN`, `CLUSTER t`, `REPACK t`, `TRUNCATE`, `LOCK`, `ANALYZE`, `REFRESH MATERIALIZED VIEW` and foreign-table DML are transactional effects confined to the scratch connection and stay legal (3.2 rule 4). Casts and transforms anchored only to foreign objects have no anchor either and stay invisible; `DO`-block bodies are scanned only for `set_config` and session advisory locks (3.2 rule 2), so dynamic DDL issued from them is not scanned.

### Test corpus

| Fixture | Contents | Coverage |
|---|---|---|
| `schema-reconciler-postgresql/src/test/resources/deployment/schema.sql` | a real-world deployment script: 23 tables, 23 views, 46 index declarations, 7 functions, 6 triggers (with 6 preceding `DROP TRIGGER`), 1 sequence, 12 dollar-quoted bodies, partial and expression indexes, JSONB operators, 1 `CREATE SCHEMA` + 1 advisory-lock line for the A.7 transform to strip | `DeploymentFixtureTest` — end-to-end reconcile + idempotency |
| `schema-reconciler-postgresql/src/test/resources/corpus/corpus-01.sql` … `corpus-08.sql` | eight anonymized production schemas (all identifiers obfuscated, comments stripped, schema name normalised to `fixture`). corpus-01 exercises extensions (`vector`, `pg_trgm`), generated columns, composite foreign keys over bare unique indexes, triggers and routines; corpus-07 exercises RLS policies, `GRANT`s, `security_invoker` views and a `DO` block reading `pg_catalog`; all exercise `CREATE OR REPLACE` views, expression/partial indexes and dollar-quoted bodies | `CorpusFixtureTest` — auto-discovers every `corpus-*.sql`, reconciles + idempotent re-run; policy-bearing files run under `unsupported=REPORT` |

Dollar-quoted PL/pgSQL bodies in the corpus contain semicolons, so client-side statement splitting is invalid — reinforcing P1. The corpus files are a compatibility corpus, not a migration source of truth.
