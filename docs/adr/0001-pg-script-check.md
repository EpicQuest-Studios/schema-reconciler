# ADR-0001: PgScriptCheck Scope and Security Model

## Status
Accepted (2026-11-24) — scope frozen per SPEC.md §3.2 and §8 rule 5.

## Context
`schema-reconciler` executes the desired SQL script inside a PostgreSQL `SAVEPOINT` in a scratch schema (`rcl_scratch`). Because PostgreSQL DDL statements can mutate cluster-wide state, leak session state onto pooled connections (e.g. session advisory locks, `SET` without `LOCAL`), or escape the scratch schema via qualified names, `PgScriptCheck` was introduced as a pre-execution lexical guard.

Over time, `PgScriptCheck` accumulated dozens of lexical edge cases (unreserved keywords as qualifiers, `ON` anchor disambiguation, `DO` block `INTO` vs `SELECT INTO`, `nextval`/`setval` literal scanning, `BEGIN ATOMIC ... END` routine bodies, etc.).

## Decision
1. **Freeze `PgScriptCheck` scope.** `PgScriptCheck` is a best-effort lexical guard for common mistakes and accidental leaks, **not** a security sandbox or full SQL parser.
2. **Untrusted scripts** must use `scratchDataSource` pointing at an isolated scratch database together with a least-privilege database role (`USAGE, CREATE ON SCHEMA` on the target).
3. **Runtime backstops remain mandatory:**
   - `dropScratchNamespace` verifies the `SAVEPOINT` is still intact (catching any `COMMIT`/`ROLLBACK` inside invoked routines) and runs `pg_advisory_unlock_all()` after rollback.
   - `checkAccess` verifies privileges before locking.

## Key Invariants Preserved
- Rejects transaction control (`BEGIN`/`COMMIT`/`ROLLBACK`/`SAVEPOINT`/`END`), non-transactional commands (`VACUUM`, `CHECKPOINT`, `CONCURRENTLY` in script, `CREATE/DROP DATABASE/TABLESPACE`, `ALTER SYSTEM`, bare `CLUSTER`/`REPACK`), and session-state commands (`SET` without `LOCAL`/`TRANSACTION`/`CONSTRAINTS`, `RESET`, `LOAD`, `DECLARE`/`PREPARE`/`DISCARD`/etc.).
- Rejects qualified object names outside the scratch namespace in DDL/DML-creation targets (`CREATE`/`DROP`/`ALTER`, `GRANT`/`REVOKE`, `COMMENT`, `SECURITY LABEL`, `SELECT ... INTO`).
- Rejects non-transactional system functions (`pg_advisory_lock*`, `pg_terminate_backend`, replication slot/origin/WAL/backup/stat-reset functions, `set_config` without literal `true`).
- Rejects statements whose catalog effect is outside the snapshot (`TABLESPACE`, `OF`/`NOT OF`, `USING am`, `CLUSTER ... USING`, `SET STATISTICS`, `RESTART`, unmodeled `OWNER TO`, `DEPENDS ON EXTENSION`, collation `REFRESH VERSION`, `ALTER VIEW ... SET|DROP DEFAULT`, `COMMENT`/`SECURITY LABEL ON SCHEMA`; `GRANT`/`REVOKE ... ON SCHEMA <scratch>` and `ALTER SCHEMA <scratch> OWNER TO <role>` are admitted and modeled as `<namespace>` `PRIVILEGES`/`OWNERSHIP`).
