# ADR-0002: PgCatalog and PgRender Boundaries

## Status
Accepted (2026-11-24).

## Context
The PostgreSQL dialect inspects `pg_catalog` to build canonical `SchemaSnapshot`s and renders `PlannedChange`s from `SchemaObject.attributes` (`CREATE_SQL`, `DROP_SQL`, `POST_SQL`, `DUMP_SQL`, etc.) using `${namespace}` tokens.

## Key Design Boundaries
1. **Extension members (`deptype = 'e'`)** are excluded from all loaders; only the `pg_extension` row itself is managed as `EXTENSION` (`extension:<name>`). `CREATE EXTENSION` replay relocates pre-installed relocatable extensions into scratch under the savepoint.
2. **Range auto-families** (multirange `typtype = 'm'`, constructor routines, range→multirange cast) fold into the managed range `TYPE` via `loadAutoTypeFamily`.
3. **Partitioned hierarchy & non-standalone rows:**
   - `loadConstraints` filters `conislocal = false`; `loadTriggers` filters `tgparentid <> 0`; `loadIndexes` filters `pg_inherits` children and invalid indexes (`indisvalid`).
   - A `relkind = 'p'` relation that is itself a `pg_inherits` child is a managed `PARTITION` carrying both its sub-partition key and bound.
   - `ADD COLUMN` / widening `TYPE` on a partitioned hierarchy routes `ALTER TABLE` to the topmost partitioned ancestor; `DEFAULT`/`NOT NULL`/`STORAGE`/`COMPRESSION` stay partition-local.
4. **Column drift splitting (`PgRender`):**
   - Safe alterations (`SET/DROP DEFAULT`, `DROP NOT NULL`, `SET STORAGE`, `SET COMPRESSION`, and allowlisted widenings: `smallint→integer→bigint`, `varchar` growth/`→text`, `char` growth, `numeric` `s'≥s ∧ p'-s'≥p-s`) go into one `REBUILD` `ALTER` change.
   - `SET NOT NULL` goes into a separate `DESTRUCTIVE` `ALTER` change on the same column.
   - Generated/identity/collation changes and non-widening types (including `char→varchar/text`) stay manual `DESTRUCTIVE`.
5. **Physical/planner properties** (`attstattarget`, `relam`, `reltablespace`, `indisclustered`) are outside the canonical by design (except index `TABLESPACE` emitted by `pg_get_indexdef`).
6. **`OTHER` detection & `CONTENT_COMPARED_ATTRIBUTE`:** Every namespace-scoped unmanaged object becomes `OTHER` with a stable category prefix (`partindex:`, `toast:`, `inherited:`, `relation`, `type:`, `collation:`, `conversion:`, `constraint:`, `comment:`, `seclabel:`, `privileges:`, `ownership:`, `cast:`, `transform:`, `language:`). Only `privileges:` is content-compared; all others are presence-only.
7. **ObjectFilter cascade:** `Builder.filter` removes rejected objects from both snapshots and cascades transitively to children, mirrored annotations (`COMMENT`, `SECURITY_LABEL`, `OWNERSHIP`, `PRIVILEGES`), target-naming `OTHER`s, and `OWNED BY` follower `SEQUENCE`s, recomputing the snapshot fingerprint over survivors.
8. **Dependency & external-dependent completeness:** `loadDependencies` and `loadExternalDependents` cover views, routines (incl. aggregates/windows), indexes, triggers, constraints, column defaults, column/table types & collations, domains, policies, partitions, text-search objects, operators/opclasses/opfamilies, and `OTHER` dependents so any unrecreatable dependent fails planning with `PlanningException`.
