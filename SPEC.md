# Schema Reconciler Specification

*Declarative, data-safe schema reconciliation for relational databases in Java*

| | |
|---|---|
| **Version** | 0.8, Draft |
| **Date** | 2026-10-25 |
| **Maven group** | `studio.epicquest.schema` |
| **Java baseline** | Java SE 25 |
| **Reference implementation** | PostgreSQL 14+ dialect (`schema-reconciler-postgresql`) |
| **Conformance suite** | `schema-reconciler-tck` |

This document is the working specification of the schema-reconciler project: the contract the core, the PostgreSQL dialect and every third-party dialect are held to, plus the background appendices that motivated it. It is a draft and may change until version 1.0.

## Document conventions

- Sections 1–11 and Appendix A define required behaviour. The preface, examples and rationale embedded in them, and Appendices B, C, D and E are background and establish no requirements.
- Requirements are stated in plain words: **must** is binding, **should** is a recommendation an implementation may deviate from for good reason, **may** marks a permitted option.
- Text in `monospace` is program text. Type and member signatures shown in Java blocks are the API surface; the prose beneath each block defines its contract.

## Contents

- [1. Introduction](#1-introduction)
  - [1.1 Purpose](#11-purpose)
  - [1.2 Motivation](#12-motivation)
  - [1.3 Design principles](#13-design-principles)
  - [1.4 Terminology](#14-terminology)
  - [1.5 Scope](#15-scope)
  - [1.6 Platform dependencies and related work](#16-platform-dependencies-and-related-work)
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
- [Appendix C. Related work](#appendix-c-related-work)
- [Appendix D. Library evaluation](#appendix-d-library-evaluation)
- [Appendix E. PostgreSQL 14–19 feature coverage and test corpus](#appendix-e-postgresql-1419-feature-coverage-and-test-corpus)
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

This design grew out of a long-running service that reconciles its PostgreSQL schema at startup with a hand-written checker. That checker keeps its expected state in hand-written Java maps (`TABLES`, `CONSTRAINTS`, `INDEXES`, `VIEWS`) duplicated from `schema.sql`, and it does not verify function bodies, trigger definitions or view bodies. The library-suitability analysis (Appendix D) found no third-party library that can serve as the core. This specification generalises that approach so that:

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

In scope: tables, columns, primary-key, unique, foreign-key and check constraints, indexes (including partial and expression indexes where the dialect supports them), views, sequences, routines (functions and procedures) and triggers.

Non-goals for version 1:

- versioned migrations or a migration history table,
- data migration or backfill,
- translating a script from one dialect to another,
- rename detection (a rename appears as one missing and one unexpected object),
- down-migrations or rollback scripts,
- privileges, ownership, comments, row-level security, partitioning, materialized views, user-defined types and extensions. Dialects must report these as `UNSUPPORTED` if a desired script creates them (section 5.4).

### 1.6 Platform dependencies and related work

- **JDBC 4.3**: all database access uses `javax.sql.DataSource` and `java.sql` types; this specification adds no new connection or statement model and requires no driver beyond what the application already supplies.
- **Java Platform Module System**: dialect discovery uses `ServiceLoader` under both JPMS (`uses`/`provides`) and the class path (`META-INF/services`), per 2.2.
- **Logging**: `java.lang.System.Logger` only; no logging framework is required or configured.
- Existing declarative schema-management tools are surveyed in Appendix C; none is reused or depended on.

## 2. Architecture

### 2.1 Modules

| Artifact | Contents | Dependencies |
|---|---|---|
| `studio.epicquest.schema:schema-reconciler-core` | API, SPI, core algorithm | Java SE only |
| `studio.epicquest.schema:schema-reconciler-postgresql` | PostgreSQL dialect (reference implementation) | core, PostgreSQL JDBC driver (runtime, provided by the application) |
| `studio.epicquest.schema:schema-reconciler-tck` | Conformance suite | core, JUnit Jupiter |
| `studio.epicquest.schema:schema-reconciler-spring` (optional) | Spring Boot auto-configuration | core, Spring Boot |

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

    /** Plans and applies under the configured policy, holding one namespace lock. */
    public ReconcileReport reconcile() { ... }

    /** Applies a plan previously returned by plan(); fails if the target has changed since. */
    public ReconcileReport apply(ReconcilePlan plan) { ... }

    public static final class Builder {
        public Builder dataSource(DataSource dataSource) { ... }          // required
        public Builder namespace(String targetNamespace) { ... }          // required
        public Builder desiredState(DesiredState desiredState) { ... }    // required
        public Builder dialect(Dialect dialect) { ... }                   // optional, see 3.4
        public Builder scratchDataSource(DataSource dataSource) { ... }   // optional, see 6.3
        public Builder policy(ReconcilePolicy policy) { ... }             // default ReconcilePolicy.defaults()
        public Builder lockTimeout(Duration timeout) { ... }              // default 60 s
        public Builder listener(ReconcileListener listener) { ... }       // optional
        public SchemaReconciler build() { ... }
    }
}
```

`build()` must fail with `ConfigurationException` if a required property is missing, if an explicitly configured dialect is invalid, or — when no dialect is set — if no `Dialect` provider is discoverable via `ServiceLoader` at all. `build()` must not open a connection: the `supports(DatabaseMetaData)` check of 3.4 needs one, so final dialect selection is deferred to the first use of the reconciler.

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
2. The script must not create, alter or drop namespaces, and must not acquire session-level locks (for example `pg_advisory_lock`): session-level effects are not undone by the scratch rollback and can leak onto a pooled connection (C-21).
3. On transactional dialects, the script must not contain transaction control (`BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`), nor statements that cannot run inside a transaction block, for example `CREATE INDEX CONCURRENTLY` or `VACUUM` (OI-3). After executing the script, the dialect must check that the run's transaction and scratch savepoint are still intact. If they are not, it must remove the scratch namespace and fail with `DesiredStateException`.
4. Other non-DDL statements may appear; their effects are confined to the scratch connection.
5. The script must not refer to the target namespace by its literal name; it uses `${namespace}` or unqualified names instead. Before substitution, the core must search the script for the target namespace identifier — bare or enclosed in the dialect's identifier quotes, compared case-insensitively — immediately followed by `.` and not preceded by an identifier character (letter, digit, `_`, `$`) or by `.`. On a match, `plan()` must fail with `DesiredStateException` giving the position of the first match.

   Rationale: in the scratch namespace a literal reference stays literal. In the actual snapshot, normalisation rule 5.3(1) turns the same reference into `${namespace}.`. The object would therefore be `DRIFTED` on every run and never pass verification. The check is lexical, so a match inside a comment or string literal also fails; this follows P4, and the script author can rewrite the text.

### 3.3 `ReconcilePolicy`

```java
public enum Decision { APPLY, REPORT, FAIL }

public record ReconcilePolicy(
        Decision additive,      // default APPLY
        Decision rebuild,       // default APPLY
        Decision destructive,   // default FAIL
        Decision unsupported) { // default FAIL; APPLY is not permitted

    public static ReconcilePolicy defaults() { ... }
    public static ReconcilePolicy reportOnly() { ... }  // REPORT for every class

    public ReconcilePolicy withDestructive(Decision decision) { ... }
    // with... for each component
}
```

The compact constructor must reject `unsupported == APPLY` with `IllegalArgumentException`. The safety classes are defined in section 5.6.

`additive`, `rebuild` and `destructive` govern *changes* by their `ChangeSafety`. `unsupported` governs *differences*: an `UNSUPPORTED` difference (5.4) never produces a `PlannedChange`, so it is evaluated against `plan.differences()`, not `plan.changes()`. Semantics of each decision:

- `APPLY`: the change is executed.
- `REPORT`: the change is not executed. It appears in `ReconcileReport.reported()` and is logged at `WARNING`. For `unsupported`, each `UNSUPPORTED` difference appears in `ReconcileReport.unsupported()` and is logged at `WARNING`.
- `FAIL`: if the plan contains at least one change of this class — or, for `unsupported`, at least one `UNSUPPORTED` difference — `reconcile()` and `apply(plan)` must throw `PolicyViolationException` **before executing any change**.

Unexpected objects (section 5.5) never produce changes, regardless of policy, except for the table-shape conflict defined there.

A change whose `statements` list is empty is a *manual change*: the dialect can describe it but not perform it safely. A manual change mapped to `APPLY` must be treated as `FAIL`.

**Evaluation** (step 7 of 5.1) proceeds in this order:

1. *Fail check.* Map every change to its class's decision, treating a manual change mapped to `APPLY` as `FAIL`. If any change maps to `FAIL`, or `unsupported` is `FAIL` and an `UNSUPPORTED` difference exists, throw `PolicyViolationException` carrying the plan.
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
        Duration elapsed) { }
```

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

Listeners are called on the reconciling thread. An exception thrown by a listener must abort the run as `ApplyException`, carrying the changes applied so far (possibly none) and, when thrown from `applying` or `applied`, the change being processed. This holds even when the exception comes from `planned`, before anything is applied. In transactional dialects the abort rolls everything back.

## 4. Model

All model types live in `studio.epicquest.schema.reconciler.model`.

### 4.1 Object identity

```java
public enum ObjectKind {
    TABLE, COLUMN, PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK,
    INDEX, VIEW, SEQUENCE, ROUTINE, TRIGGER, OTHER
}

/** Namespace-neutral identity. */
public record ObjectId(ObjectKind kind, String name, Optional<String> parent) { }
```

- `name` is the identifier as stored by the engine (case as the catalog reports it).
- `parent` is required for `COLUMN`, all constraint kinds, `INDEX` and `TRIGGER` (the owning table or view), and must be empty for other kinds. A present `parent` and `name` must be non-empty strings. The compact constructor enforces these rules with `IllegalArgumentException`.
- For `ROUTINE`, `name` must include the identity signature, because routines can be overloaded. The signature is the routine name followed by the parenthesised list of its input argument types (`IN`, `INOUT` and `VARIADIC` arguments; never `OUT`), separated by `, `, for example `extract_ts(text)` or `merge_plan(bigint, jsonb)`. Each type is written by the engine's canonical type formatter. Argument names are omitted, because they belong to the canonical definition: renaming a parameter makes the routine `DRIFTED`, not a missing routine plus an unexpected one. Types defined in the snapshot's own namespace use the `${namespace}.` token of 5.3.
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

`attributes` carry dialect-specific details such as a column's type or nullability, for display and diagnostics. Comparison must use `definition` only, so that the core stays vendor-neutral.

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

1. **Connect.** Obtain one connection from the `DataSource`. If `capabilities().transactionalDdl()` is true, disable auto-commit. The core must restore the connection's original auto-commit setting on every exit path, before closing the connection and returning it to its pool (C-21).
2. **Lock.** Acquire the namespace lock (6.2) within `lockTimeout`, or fail with `LockTimeoutException`.
3. **Read actual.** Read the snapshot of the target namespace.
4. **Load desired.** Create a scratch namespace (6.3), bind the session namespace to it (`bindSessionNamespace`, 6.1), execute the desired script in it, read its snapshot, then remove the scratch namespace. Removal must happen even if loading fails.
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
- `UNSUPPORTED`: present in desired with a kind the dialect does not support (`ObjectKind.OTHER`, or a kind absent from `capabilities().supportedKinds()`). It takes precedence over `MISSING` and `DRIFTED` for such an object, and the dialect is never asked to render it. Policy `unsupported` governs these as differences (3.3); `APPLY` is not permitted.

A dialect must detect every object in the scratch namespace, including kinds it does not manage, so that nothing created by the script is silently ignored.

### 5.5 Unexpected objects

`UNEXPECTED`: present in actual, absent in desired. Unexpected objects must be listed in the report and logged at `WARNING`. They must not produce changes in version 1. Removing them is an operator decision outside this library.

Exception: an unexpected `COLUMN` whose parent table is expected is a table-shape conflict. The core must add a `DESTRUCTIVE` change for the owning table with the reason `unexpected column <name>`, so that the default policy fails loudly instead of running against a foreign table shape.

### 5.6 Safety classes

| Class | Definition | Examples |
|---|---|---|
| `ADDITIVE` | Creates something new. Existing objects and data are untouched. | Create missing table, sequence, index, view, routine, trigger; add missing constraint; add nullable column. |
| `REBUILD` | Drops and recreates an object that holds no data of its own. | Recreate drifted index, view, constraint, routine or trigger. |
| `DESTRUCTIVE` | Can remove or rewrite stored data, or can fail on existing data. | Drop or retype a column, change a table's column set, alter a sequence's options (this changes future values and can fail against the current value). |

Rules:

Rules 1 and 2 govern tables present in the actual snapshot. A `MISSING` table and the children its create covers (5.8.4) are created as one `ADDITIVE` change, whatever the nullability of its columns, because no stored data exists yet. So C-01 reconciles under the default policy.

1. Any change to an existing `TABLE`, and any change to an existing `COLUMN`, must be classified `DESTRUCTIVE`. Adding a missing column to an existing table is a `CREATE` of that column, not a change to an existing one: it is `ADDITIVE` when the column is nullable, and is classified by rule 2 otherwise.
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

    String createScratchNamespace(Connection connection, String targetNamespace) throws SQLException;

    void dropScratchNamespace(Connection connection, String scratchNamespace) throws SQLException;

    /** Executes a complete script; the dialect decides whether to batch or split it. */
    void executeScript(Connection connection, String script) throws SQLException;

    /** Reads every object in the namespace, normalised per 5.3. */
    SchemaSnapshot readSnapshot(Connection connection, String namespace) throws SQLException;

    /** Renders the statements and safety class for one difference, bound to the target namespace. */
    List<PlannedChange> render(Difference difference, SchemaSnapshot desired, String targetNamespace);

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

Every message must name the target namespace and, where applicable, the object id. `DesiredStateException` must include the engine's error message and position when the driver provides them.

## 8. Security

1. Object names read from the catalog must be quoted with `Dialect.quoteIdentifier` whenever they are placed into SQL.
2. The library must not build SQL from data supplied at run time other than the configured namespace and the trusted desired script.
3. The configured namespace must be validated as a legal identifier for the dialect before use: the core passes it to `Dialect.quoteIdentifier` and reports an `IllegalArgumentException` from it as `ConfigurationException`. Because a dialect is selected on first use (3.4), this validation happens then, before any SQL is sent.
4. Documentation should recommend running reconciliation with a role limited to the target namespace. When no `scratchDataSource` is configured, that role additionally needs namespace-creation privilege on the target database (for example `CREATE ON DATABASE` in PostgreSQL); `scratchDataSource` exists for environments that cannot grant it.

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

C-03, C-04, C-07, C-08, C-09 and C-12 mirror regression scenarios exercised by the hand-written reconciler this specification generalises.

### 9.3 Implementation conformance

This specification defines two conformance targets:

1. A **conforming core** implements every requirement of sections 2–8 and 10 for the packages listed in 2.2, and passes the core test suite shipped with the reference implementation.
2. A **conforming dialect** implements `studio.epicquest.schema.reconciler.spi.Dialect` for one database product, honours the declarations of its `DialectCapabilities`, and passes `DialectConformanceTest` (9.1) with only declared skips.

A product may be described as implementing this specification only when a core and at least one dialect pass their respective conformance suites. Implementations must not claim compatibility on the basis of a partial pass; skipped scenarios (9.1) are the only permitted deviations, and only for declared-unsupported kinds or capabilities. `OTHER` detection (5.4) is exempt from skipping: every conforming dialect must detect it.

### 9.4 Deliverables

A release of this project ships three deliverables: this document, the reference implementation (the PostgreSQL dialect on the shared core), and the conformance suite (`schema-reconciler-tck`).

## 10. Versioning and compatibility

1. Releases follow Semantic Versioning 2.0.0.
2. Within a major version, the API and model must stay source- and binary-compatible. New SPI methods must be `default` methods.
3. A dialect declares the core major version it was built against via `Dialect.coreMajorVersion()` (6.1). The core must refuse, with `ConfigurationException`, a dialect reporting a different major version than its own.
4. Changing a dialect's normalisation rules is a breaking change for that dialect, because it can turn equal objects into drifted ones.
5. Implementations of this specification must run on Java SE 25 or later and must not rely on preview, incubator or deprecated-for-removal APIs.

## 11. Open issues

| ID | Issue | Proposed resolution |
|---|---|---|
| OI-1 | One script per dialect, or one portable script? | One script per dialect. Translation is a non-goal (1.5). |
| OI-2 | Should version 1 ever drop unexpected objects? | No. Revisit with an explicit `prune` policy in a later version. |
| OI-3 | Online index builds (`CREATE INDEX CONCURRENTLY` cannot run in a transaction) | Out of scope for version 1; document as a known limitation of the PostgreSQL profile. |
| OI-4 | Namespace-token replacement (5.3 rule 1) could also match text inside string literals or routine bodies | Partly resolved in 0.5: matches now require an identifier boundary, and scripts may not name the target literally (3.2). Matches inside literals remain possible and are symmetric between desired and actual, so they do not cause drift. Prototype on the bundled deployment fixture before freezing. |
| OI-5 | Sequence current values differ between scratch and target | Compare sequence options only, never the current value. |
| OI-6 | Rule 5.6(1) classifies every change to an existing column as `DESTRUCTIVE`, including ones that cannot lose data (changing a default, dropping `NOT NULL`) | Keep for version 1, and render those as manual changes. Consider allowing a dialect to classify specific column alterations as `REBUILD` in a later minor version. |
| OI-7 | DDL applied in one transaction holds its locks (for example `ACCESS EXCLUSIVE` for recreated constraints and indexes) until commit | Document in the PostgreSQL profile. Applications should reconcile at startup, before taking traffic. |

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

`SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))` with `:key = 'studio.epicquest.schema.reconciler:' || <database> || ':' || <namespace>`, under `SET LOCAL lock_timeout` set to the reconciler's `lockTimeout`. Once the lock is held, the dialect must run `SET LOCAL lock_timeout = DEFAULT`, so that the reconciler's timeout does not also govern lock waits of the applied DDL; those follow the session's own setting. The lock is released by commit or rollback. The dialect must also call `pg_advisory_unlock_all()` before returning the connection, in case the script took session-level locks.

### A.3 Scratch namespace

Inside the run's transaction: `SAVEPOINT rcl_scratch`, `CREATE SCHEMA <scratch>`, execute the script, read the snapshot, then `ROLLBACK TO SAVEPOINT rcl_scratch`. The scratch schema never becomes visible to other sessions.

After the script, the dialect must confirm that the transaction is still open and that the savepoint exists (3.2 rule 3), for example by checking that `ROLLBACK TO SAVEPOINT rcl_scratch` succeeds. If the script committed, the dialect must run `DROP SCHEMA <scratch> CASCADE` and raise `DesiredStateException`.

With `scratchDataSource` (6.3.2), the scratch connection uses a separate transaction and no savepoint: `BEGIN`, `CREATE SCHEMA <scratch>`, execute the script, read the snapshot, `ROLLBACK`. The namespace lock is taken only on the target connection.

### A.4 Script execution

The whole script is sent in one `Statement.execute` call. The server parses it, so dollar-quoted bodies need no client-side splitting. This matches the current reconciler and the jOOQ probe result in Appendix D.

### A.5 Catalog sources

| Kind | Source | Canonical definition |
|---|---|---|
| `TABLE` | `pg_class` (`relkind` `r`, excluding inheritance children) | `relkind`, `relpersistence`, `reloptions`, `relreplident`; columns are separate objects |
| `COLUMN` | `pg_attribute`, `pg_attrdef`, `pg_collation` | `format_type(atttypid, atttypmod)`, nullability, `pg_get_expr(adbin, adrelid)` as the default or, when `attgenerated` is set, as the generation expression; collation when it is not the type's default; identity kind and identity sequence options |
| `PRIMARY_KEY`, `UNIQUE`, `FOREIGN_KEY`, `CHECK` | `pg_constraint` (`contype` `p`, `u`, `f`, `c`) | `pg_get_constraintdef(oid)` |
| `INDEX` | `pg_index` (`relkind` `i`), excluding constraint-backing indexes (`pg_constraint.conindid` on `p`, `u`, `x` constraints) and partition-child indexes | `pg_get_indexdef(indexrelid)` |
| `VIEW` | `pg_class` (`relkind` `v`) | `pg_get_viewdef(oid, true)` plus column names and types, plus `reloptions` (`check_option`, `security_barrier`, `security_invoker`) in sorted order |
| `SEQUENCE` | `pg_sequence` | type, start, increment, min, max, cycle; never the current value |
| `ROUTINE` | `pg_proc` (`prokind` `f`, `p`) | `pg_get_functiondef(oid)`; the identity signature (4.1) is `proname` plus `format_type` over each element of `proargtypes` |
| `TRIGGER` | `pg_trigger` (`NOT tgisinternal`) | `pg_get_triggerdef(oid, true)` plus the enabled state (`tgenabled`) |
| dependencies | `pg_depend`, `pg_rewrite`, `pg_inherits`, `pg_trigger` | populate `dependsOn` |
| `OTHER` | see below | detected so that unsupported constructs are reported (5.4) |

`OTHER` detection covers every namespace-scoped catalog object the managed
kinds do not claim: non-managed relations (`relkind` `m`, `f`, `p`, `c`, `t`,
`I` and `r` tables with an inheritance parent), exclusion constraints
(`contype` `x`), user-defined types (`pg_type` `e`, `d`, `r`, `m` and base
types), aggregates and window functions (`pg_proc` `a`, `w`), policies
(`pg_policy`), extensions (`pg_extension`), non-view rules (`pg_rewrite`),
statistics (`pg_statistic_ext`), collations (`pg_collation`), conversions
(`pg_conversion`), text-search objects (`pg_ts_config`, `pg_ts_dict`,
`pg_ts_parser`, `pg_ts_template`), operators, operator classes and families
(`pg_operator`, `pg_opclass`, `pg_opfamily`), comments (`pg_description`),
security labels (`pg_seclabel`), granted privileges (non-null `relacl`,
`proacl`, `typacl`), ownership changed away from the connecting role
(`relowner`, `proowner`, `typowner`) and publication bindings
(`pg_publication_rel` for tables, `pg_publication_sequences` for sequences
on PostgreSQL 19+). Three catalog objects have no namespace of their
own but are detected through a namespace anchor instead: `pg_cast` rows
whose source type, target type or cast function is in the namespace,
`pg_transform` rows anchored by their type or SQL functions, and
`pg_language` rows whose handler functions are in the namespace.

Objects that have no namespace anchor at all — databases, tablespaces,
roles, event triggers, servers, foreign data wrappers, user mappings,
publications, subscriptions, access methods, default privileges and large
objects — are outside the snapshot model. The script constraints of 3.2
reject the non-transactional ones outright, and the PostgreSQL script
check additionally rejects DDL for every database-wide object it cannot
represent (`SERVER`, `FOREIGN DATA WRAPPER`, `USER MAPPING`,
`EVENT TRIGGER`, `ACCESS METHOD`, `LANGUAGE`, `ROLE`/`GROUP`/`USER`,
`DEFAULT PRIVILEGES`, `OWNED`, `REASSIGN OWNED`, role-membership
`GRANT`/`REVOKE`, and `ALTER ... SET SCHEMA`), so a script cannot silently
create an undetectable object. Casts and transforms anchored only to
foreign objects — a `CAST (text AS int)` with a `pg_catalog` function —
remain outside the model. Appendix E tabulates coverage against the
PostgreSQL 14–19 SQL command sets.

The snapshot must be read with `search_path` set to the namespace being read, using `SET LOCAL` so that the setting ends with the transaction, or with the savepoint for the scratch snapshot. `pg_get_*def` then omits the qualifier for that namespace. Normalisation rule 5.3(1) then handles explicit qualifiers that remain.

Sequences that back columns are handled by how they are owned:

- **Identity columns.** The internal sequence (`pg_depend.deptype` `i`) is not reported as an object. Identity generation (`ALWAYS` or `BY DEFAULT`) and its sequence options are part of the `COLUMN` canonical definition.
- **Serial-style owned sequences** (`deptype` `a`, `OWNED BY`). These are reported as `SEQUENCE` objects under their catalog names, for example `plans_id_seq`. Their canonical definition includes `OWNED BY <table>.<column>`. Their `dependsOn` records the owning *table*, never the column. The column's `nextval(...)` default already depends on the sequence, so recording the ownership edge against the column would form a cycle (5.8.6). When the owning table is `MISSING`, its create covers its owned sequences (5.8.4) and runs `CREATE SEQUENCE`, `CREATE TABLE`, `ALTER SEQUENCE ... OWNED BY` as one change.

Name scopes (`nameScopes`, 5.8.7). Tables, views, sequences and indexes share one relation namespace per schema. Primary-key and unique constraints also create an index under their own name. Constraint names are unique per table:

| Kind | Keys |
|---|---|
| `TABLE`, `VIEW`, `SEQUENCE`, `INDEX`, `OTHER` relations | `relation:<name>` |
| `PRIMARY_KEY`, `UNIQUE` | `relation:<name>`, `constraint:<parent>.<name>` |
| `FOREIGN_KEY`, `CHECK` | `constraint:<parent>.<name>` |
| `COLUMN`, `ROUTINE`, `TRIGGER` | none (the `ObjectId` is already the engine's uniqueness key) |

### A.6 Rendering

| Difference | Statements | Safety |
|---|---|---|
| `MISSING` table | `CREATE TABLE` assembled from all desired columns, including `NOT NULL` columns without defaults (no separate column changes, 5.8.4); constraints and indexes follow as separate changes | `ADDITIVE` |
| `MISSING` nullable column on an existing table | `ALTER TABLE ... ADD COLUMN` | `ADDITIVE` |
| `MISSING` `NOT NULL` column with a constant default (see below) | `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT` | `ADDITIVE` (metadata-only since PostgreSQL 11) |
| `MISSING` `NOT NULL` column on an existing table whose default is not constant | manual change (no statements) | `DESTRUCTIVE` |
| `MISSING` constraint | `ALTER TABLE ... ADD CONSTRAINT <name> <pg_get_constraintdef>` | `ADDITIVE` |
| `DRIFTED` constraint | a `DROP` change `ALTER TABLE ... DROP CONSTRAINT`, then a `CREATE` change `ADD CONSTRAINT` | `REBUILD` |
| `MISSING` / `DRIFTED` index | `CREATE`: the desired `pg_get_indexdef`; when drifted, preceded by a `DROP` change `DROP INDEX` | `ADDITIVE` / `REBUILD` |
| `MISSING` / `DRIFTED` view | `CREATE`: `CREATE VIEW ... AS <pg_get_viewdef>`; when drifted, preceded by a `DROP` change `DROP VIEW` | `ADDITIVE` / `REBUILD` |
| `MISSING` sequence not covered by a table create | `CREATE`: `CREATE SEQUENCE` with the desired options, followed by `ALTER SEQUENCE ... OWNED BY` when it is owned | `ADDITIVE` |
| `DRIFTED` sequence | `ALTER`: `ALTER SEQUENCE` setting the desired options; the current value is never changed (OI-5) | `DESTRUCTIVE` |
| `MISSING` routine | `CREATE`: `pg_get_functiondef` | `ADDITIVE` |
| `DRIFTED` routine | `ALTER`: `CREATE OR REPLACE` from `pg_get_functiondef`; if the return type changed, a `DROP` change `DROP FUNCTION`/`DROP PROCEDURE` plus a `CREATE` change instead | `REBUILD` |
| `MISSING` / `DRIFTED` trigger | `CREATE`: `pg_get_triggerdef`; when drifted, preceded by a `DROP` change `DROP TRIGGER` | `ADDITIVE` / `REBUILD` |
| any `DRIFTED` table or column, `MISSING` `NOT NULL` column without default on an existing table, table-shape conflict | manual change (no statements) in version 1 | `DESTRUCTIVE` |

**Constant defaults.** Deciding whether an arbitrary default expression is volatile would require parsing SQL (P1), so the profile uses a conservative lexical rule, which 5.6 rule 3 permits. A default counts as *constant* only if its canonical text is a numeric literal, a boolean literal, `NULL`, or a quoted string literal, in each case optionally followed by one `::type` cast. Anything else, for example `now()`, `gen_random_uuid()`, an arithmetic expression or any function call, is treated as not constant. A dialect must classify by this rule and must not loosen it.

**Covered children (`coveredChildren`).** For a `MISSING` table: every `COLUMN` whose parent is that table, and every `SEQUENCE` that is serial-owned by one of those columns (A.5). For every other difference the dialect returns an empty set.

### A.7 Migration path for an existing deployment script

1. Replace the literal namespace qualifier (for example `svc.`) in the deployment script `db/schema.sql` with `${namespace}.`.
2. Remove the deployment-only statements that violate the script constraints of 3.2 — `CREATE SCHEMA IF NOT EXISTS svc;` and `SELECT pg_advisory_lock(...)` — because the library now owns namespace binding and locking. (`pg_advisory_lock` would otherwise leak a session-level lock onto the pooled connection: it is not released by rollback.)
3. Replace the hand-written reconcile routine with a `SchemaReconciler.builder()` call configured for the target namespace, and delete the duplicated `TABLES`, `CONSTRAINTS`, `INDEXES` and `VIEWS` maps.
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

## Appendix C. Related work

Declarative, desired-state schema management is an established pattern; what is novel here is packaging it as a zero-dependency, embeddable Java library with an SPI and a conformance suite. The closest prior art:

| Tool | Relationship to this design |
|---|---|
| Skeema (MySQL, Go) | Mechanism twin: runs desired `CREATE`s in a temporary workspace schema on the same server (or Docker) *because* parsing SQL is brittle, then diffs catalog state — the same reasoning as P1 and the scratch namespace/`scratchDataSource` duality. Gates destructive operations behind `allow-unsafe`. CLI only. |
| Atlas (ariga, Go) | `schema apply` normalises the desired state through a dev database (`--dev-url`) and diffs it against the target. Functions, triggers and views require the paid Pro tier. CLI with a wrapper SDK; not JVM-embeddable. |
| stripe/pg-schema-diff (Go) | Applies desired DDL to a temporary PostgreSQL instance, diffs, and emits a plan with per-statement hazards gated by `--allow-hazards` (≈ `ChangeSafety`/`ReconcilePolicy`) and a `CurrentSchemaHash` checked before apply (≈ `actualFingerprint`/`StalePlanException`). PostgreSQL-only CLI. |
| SqlPackage `.dacpac` (SQL Server) | Declarative publish of a compiled model with `Script` (plan), `DeployReport` and `DriftReport`. The enterprise ancestor of plan/report/drift separation. |
| sqldef (psqldef/mysqldef) | Idempotent application of a schema file with `--dry-run`/`--check` and an `--enable-drop` destructive gate, but parses DDL with its own client-side parser — the brittleness P1 avoids. |
| migra / `results dbdiff` (Python) | Diffs two live PostgreSQL databases into a migration script; drops require `--unsafe`. Deprecated upstream; needs a live desired-state database. |
| apgdiff (Java) | Diffs two `pg_dump` outputs into upgrade DDL. Client-side parser, unmaintained, offline dumps only. |
| Apache DdlUtils (Java) | Historic alter-database-to-model library; unmaintained since the late 2000s, pre-dating modern DDL features. |
| Liquibase `diff`/`diffChangeLog`, jOOQ `Meta.diff` | Java diff/changelog generators; probed and rejected for this schema in Appendix D (fidelity and destructive output). |
| Hibernate `hbm2ddl`, Prisma `db push`, Drizzle push | ORM-scoped declarative updates; model-derived, not script-derived. |
| pgroll, Reshape | Zero-downtime *versioned* migration engines (expand/contract); a different problem than state reconciliation. |

Distinguishing properties of this design: the target engine is the only parser (P1), comparison is engine-canonical (P2), unexpected objects are never dropped (P3), the scratch namespace lives inside the run's transaction on the target database by default (savepoint rollback — no second server needed, unlike Skeema's temp schema or Atlas's dev database), applied changes are verified by re-reading the catalog (P6), and third-party dialects are checked by a conformance suite.

## Appendix D. Library evaluation

Before the engine was built, PostgreSQL-aware tooling was evaluated as a candidate reconciler core for a Java service. Requirements came from an existing hand-written reconciler that (a) sends the whole `schema.sql` to the server in one call — PostgreSQL itself is the parser, so dollar-quoted bodies need no client-side splitting — and (b) duplicates its expected state in hand-written maps (`TABLES`, `CONSTRAINTS`, `INDEXES`, `VIEWS`) that verify the live catalog but never check function bodies, triggers or view bodies.

| Candidate | Role | Verdict | Reason |
|---|---|---|---|
| Liquibase | introspection, changelog | Do not adopt | Migration-oriented; changelog fidelity for functions, triggers, view bodies and expression/partial indexes unproven; the design deliberately has no ordered changelog. |
| jOOQ | DSL, DDL parser, `Meta` diff | Reject | Probed with OSS 3.21.9 on PostgreSQL 18.6: parser rejects functions and triggers ("pro edition"), DDL interpretation fails on expression indexes, generated diff emits destructive `drop view` statements. |
| hand-written verifier | status quo | Baseline | Works, but duplicates expected state and under-verifies. |

**Conclusion.** No library was worth adopting. The closest fix needs none: apply `schema.sql` into a scratch schema, compare its catalog (`pg_get_indexdef`, `pg_get_constraintdef`, `pg_get_viewdef`, `pg_get_functiondef`, `pg_get_triggerdef`) with the live one, and the duplicated maps disappear — the working approach that became P1.

**jOOQ probe detail** (OSS 3.21.9, PostgreSQL 18.6, 23-table real-world fixture): whole-file parse fails at the first `CREATE FUNCTION`; per-statement parsing needs a dollar-quote-aware splitter (95/115 pass; functions, triggers and multi-clause `ALTER`s fail); `Meta` interpretation yields 12/23 tables and 8/46 indexes; live-catalog `Meta` works well but duplicates what `pg_get_*def` returns more exactly; `migrateTo` produced 149 queries starting with `drop view` — neither faithful nor non-destructive.

## Appendix E. PostgreSQL 14–19 feature coverage and test corpus

Coverage of the PostgreSQL SQL command set by the reference dialect, measured against what a desired script can express — PostgreSQL 18.6 documentation, part VI reference. The PostgreSQL 15, 16 and 17 command sets are identical; 14 lacks only MERGE (a data statement the script check passes through like other DML — on 14 it fails at execution, as any version-specific user syntax does). The catalog queries use no 14-plus-only columns, and the suite runs green on PostgreSQL 14.24, 15.19, 16.15 and 17.11 (the corpus exercises two v15 constructs — NULLS NOT DISTINCT indexes and security_invoker views — downgraded by a test-only transform on pre-15 servers, alongside the uuidv7() stand-in on pre-18). The PostgreSQL 19 (rc1) delta: the command set gains `REPACK` and `WAIT FOR LSN`, both rejected as session/utility statements below, and `pg_publication_sequences` exposes sequence publication bindings. The suite also runs green on `postgres:19rc1`.

### Reconciled constructs (managed kinds)

| SQL construct | Handling |
|---|---|
| `CREATE TABLE` / `ALTER TABLE` | `TABLE` objects (columns, generated columns, identity columns, `NOT NULL`, defaults, collations, `relpersistence`, `reloptions`, `relreplident`); inheritance children and partition children are `OTHER` instead |
| `PRIMARY KEY`, `UNIQUE`, `FOREIGN KEY`, `CHECK` | constraint kinds via `pg_constraint` |
| `CREATE INDEX` | `INDEX` objects (partial, expression, unique — including indexes that back `FOREIGN KEY` references); `CONCURRENTLY` is non-transactional and rejected (3.2) |
| `CREATE [OR REPLACE] VIEW` | `VIEW` objects incl. `reloptions` (`check_option`, `security_barrier`, `security_invoker`) |
| `CREATE SEQUENCE` / `ALTER SEQUENCE` | `SEQUENCE` objects; serial-owned and identity sequences folded per A.5 |
| `CREATE FUNCTION` / `CREATE PROCEDURE` | `ROUTINE` objects (`prokind` `f`, `p`) |
| `CREATE TRIGGER` | `TRIGGER` objects incl. `tgenabled` state; constraint triggers are excluded by `tgisinternal` |
| `COMMENT ON`, `GRANT`/`REVOKE`, `ALTER ... OWNER TO`, `SECURITY LABEL` | attributes are detected — see below |
| `CREATE SCHEMA` | rejected: the core owns the namespace (3.2) |

### Detected but unsupported (`OTHER` → `UNSUPPORTED` per 5.4)

`EXCLUDE` constraints, `CREATE TYPE` (enum/domain/range/multirange/base/composite), `CREATE DOMAIN`, `CREATE MATERIALIZED VIEW`, `CREATE FOREIGN TABLE`, `IMPORT FOREIGN SCHEMA`, partitioned tables and indexes, classic-inheritance children, `CREATE AGGREGATE`, window functions, `CREATE POLICY` (row-level security), `CREATE EXTENSION`, `CREATE RULE`, `CREATE STATISTICS`, `CREATE COLLATION`, `CREATE CONVERSION`, `CREATE TEXT SEARCH CONFIGURATION|DICTIONARY|PARSER|TEMPLATE`, `CREATE OPERATOR`, `CREATE OPERATOR CLASS`, `CREATE OPERATOR FAMILY`, `CREATE CAST` and `CREATE TRANSFORM` when anchored to a namespace object, `COMMENT ON` any catalog object, `SECURITY LABEL`, granted privileges, ownership reassignment, `FOR TABLE` publication bindings, and sequence publication bindings (`pg_publication_sequences`, PostgreSQL 19+).

### Out of scope (cluster-level or statement-only, per 1.5/3.2)

Objects with no namespace anchor — `CREATE DATABASE`*, `CREATE TABLESPACE`*, `CREATE SUBSCRIPTION`*, `CREATE PUBLICATION`*, `CREATE EVENT TRIGGER`, `CREATE SERVER`, `CREATE FOREIGN DATA WRAPPER`, `CREATE USER MAPPING`, `CREATE ACCESS METHOD`, `CREATE LANGUAGE`, `CREATE ROLE|GROUP|USER`, `CREATE LARGE OBJECT`, default privileges, `REASSIGN OWNED`, `DROP OWNED`, `ALTER SYSTEM`, `ALTER ... SET SCHEMA`, role-membership `GRANT`/`REVOKE` — and session/utility statements — transaction control*, `VACUUM`*, `CHECKPOINT`*, `CLUSTER`*, `DISCARD`*, `LISTEN`/`NOTIFY`/`UNLISTEN`, session-level advisory locks, `LOAD`*, `REPACK`*, `WAIT FOR LSN`. Items marked `*` are rejected by the script check because they cannot run inside a transaction; the rest are rejected because their catalog rows have no namespace anchor and could never be detected (5.4), or — `WAIT FOR LSN` — because the statement manipulates session or replication state only. Casts and transforms anchored only to foreign objects have no anchor either and stay invisible; `DO`-block bodies are string literals to the lexical check, so dynamic DDL issued from them is not scanned.

### Test corpus

| Fixture | Contents | Coverage |
|---|---|---|
| `schema-reconciler-postgresql/src/test/resources/deployment/schema.sql` | a real-world deployment script: 23 tables, 23 views, 46 index declarations, 7 functions, 6 triggers (with 6 preceding `DROP TRIGGER`), 1 sequence, 12 dollar-quoted bodies, partial and expression indexes, JSONB operators, 1 `CREATE SCHEMA` + 1 advisory-lock line for the A.7 transform to strip | `DeploymentFixtureTest` — end-to-end reconcile + idempotency |
| `schema-reconciler-postgresql/src/test/resources/corpus/corpus-01.sql` … `corpus-08.sql` | eight anonymized production schemas (all identifiers obfuscated, comments stripped, schema name normalised to `fixture`), 4–132 catalog objects each. corpus-01 exercises extensions (`vector`, `pg_trgm`), generated columns, composite foreign keys over bare unique indexes, triggers and routines; corpus-07 exercises RLS policies, `GRANT`s, `security_invoker` views and a `DO` block reading `pg_catalog`; all exercise `CREATE OR REPLACE` views, expression/partial indexes and dollar-quoted bodies | `CorpusFixtureTest` — auto-discovers every `corpus-*.sql`, reconciles + idempotent re-run; policy-bearing files run under `unsupported=REPORT` |

Dollar-quoted PL/pgSQL bodies in the corpus contain semicolons, so client-side statement splitting is invalid — reinforcing P1. The corpus files are a compatibility corpus, not a migration source of truth.
