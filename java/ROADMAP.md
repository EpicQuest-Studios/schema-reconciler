# Roadmap — remaining PostgreSQL coverage and productization

Tracks the remaining gaps after managed kinds (`TABLE`, `COLUMN`, `PRIMARY_KEY`, `UNIQUE`,
`FOREIGN_KEY`, `CHECK`, `INDEX`, `VIEW`, `SEQUENCE`, `ROUTINE`, `TRIGGER`, `MATERIALIZED_VIEW`,
`TYPE` (enum), `DOMAIN`, `POLICY`, `EXTENSION`, `COMMENT`) and `dump()` landed.

**Status** — Phase 1 done: `EXCLUSION`, `STATISTICS`, `RULE`, `COLLATION`, `CONVERSION`,
`SECURITY_LABEL` all promoted (TCK C-49…C-53; labels have no TCK — no provider module ships
with a stock server, covered by a catalog-fixture test). Phase 3 done: partitioned parents
(`relkind 'p'`) are managed `TABLE` (partition key in the canonical), children are managed
`PARTITION` (bound canonical; bound drift → `DESTRUCTIVE` recreate; TCK C-54); partition
indexes stay auto-cloned `OTHER`, classic-inheritance children stay `inherited:` `OTHER`.
Residual `OTHER`s kept deliberately: default-provider/bound-encoding collations,
`DEFAULT CONVERSION`, labels on unmanaged targets.
Phase 4 amendment landed (namespace objects may reference — never manage — cluster-level
roles/servers/publications/tablespaces; existence is a preflight concern, not a diff).
Phase 4a done: `FOREIGN_TABLE` (`relkind 'f'`; `srvname` + `ftoptions` + columns canonical;
`CREATE FOREIGN TABLE`; TCK C-55). Phase 4b done: publication bindings are managed
`PUBLICATION` objects named by the publication and parented on the bound relation —
`pg_publication_rel` (incl. `prqual`/`prattrs`, PG15+), `pg_publication_namespace` (PG15+),
`pg_publication_sequences` (PG19+; `puballsequences` flag expansions are not bindings);
`ALTER PUBLICATION` binding forms pass the script check, missing `ADDITIVE`, drifted
drop+add `REBUILD`; TCK C-56.
Phase 4c done: `OWNERSHIP` — managed object mirroring its target (COMMENT-style id), loaded
only when `relowner`/`proowner`/`typowner` differs from the connecting role (absent
`OWNER TO` = "don't care"); `ALTER <kind> <target> OWNER TO <role>` in-place `REBUILD`;
index/rowtype/array-type owner rows are followers and skipped; ownership of unmanaged
targets stays `ownership:` `OTHER`; TCK C-57.
Phase 4d done: `PRIVILEGES` — managed object mirroring its acl-bearing target
(rel/proc/type); canonical is the sorted `grantee:PRIVILEGE[*]` token list
(`<owner>`-normalised, grantor dropped — a held grant compares equal whoever
recorded it); `GRANT_TARGET` attr carries the `ON` phrase (`TABLE`/`FOREIGN TABLE`/
`SEQUENCE`/`TYPE`/`DOMAIN`/`FUNCTION|PROCEDURE(sig)`); the token diff renders
`GRANT`/`REVOKE` (`REVOKE GRANT OPTION FOR` when only the option drifts, `<owner>`
implicit rights never rendered) as in-place `REBUILD`; grants on unmanaged targets
stay `privileges:` `OTHER` (still content-compared); `dump()` emits the GRANT list;
grantee roles are cluster references — never created; TCK C-58.
Phase 4e done: `CAST`/`TRANSFORM` — managed when the namespace anchor is managed
(cast: source/target type or cast function; transform: the `FOR` type — the only
anchor a script can express). Canonicals composed from `castcontext`/`castmethod`/
`castfunc` resp. `trffromsql`/`trftosql` (`regprocedure` text); `DROP`+`CREATE`
`REBUILD`. Casts/transforms on unmanaged anchors stay `cast:`/`transform:` `OTHER`;
`LANGUAGE` is cluster-level (`CREATE LANGUAGE` rejected) and stays `OTHER` always —
same rule as publications. TCK C-59 (cast); no transform TCK — no stock procedural
language accepts transform functions.

Each kind promotion follows the same recipe, commit-per-kind:

1. `PgCatalog` — canonical loader + `CREATE_SQL`/`DROP_SQL` fragments in `DUMP_SQL`/attributes.
2. `PgRender` — `missing`, `drifted`, `drop` handling; no `CASCADE`; recreate = drop+create pair.
3. `PgScriptCheck` — qualified-name rejection for the new statement forms.
4. `ObjectKind`/`ObjectId` — kind enum, id shape, parent rules, name scopes.
5. `SPEC.md` — §3/§4 behavior, A.5 managed/unsupported rows, A.6 fragments, Appendix C.
6. TCK — new `DialectConformanceTest` scenario (C-49+); `ProfileRulesTest` count bump.
7. `AppendixCoverageTest` — remove the construct from the `OTHER` list once managed.
8. `mvn -f java/pom.xml verify` green before commit.

## Phase 1 — high-frequency gaps (mechanical)

| # | Kind | Notes |
|---|------|-------|
| 1a | `EXCLUSION` constraint | ✅ `contype 'x'`; canonical `pg_get_constraintdef`; drift → drop+create `REBUILD` |
| 1b | `STATISTICS` ext. stats | ✅ `pg_get_statisticsobjdef` deparsed create; recreate on drift |
| 1c | `RULE` | ✅ non-`_RETURN` `pg_rewrite`; `pg_get_ruledef` + `ev_enabled`; drift → drop+create `REBUILD` |
| 1d | `COLLATION` | ✅ libc/ICU providers; `collversion` excluded (env metadata); default-provider/bound-encoding stays `OTHER` |
| 1e | `CONVERSION` | ✅ encodings + `regproc`; `DEFAULT CONVERSION` stays `OTHER` |
| 1f | `SECURITY LABEL` | ✅ target-mirroring id `provider:target`; `IS 'x'`/`IS NULL`; no TCK (needs provider module) |

## Phase 2 — type system completion

| # | Kind | Notes |
|---|------|-------|
| 2a | composite `TYPE` | ✅ `pg_type typtype 'c'` + `relkind 'c'` backing folded into `TYPE`; attrs canonical via `pg_attribute` attnum order; `DROP`+`CREATE` `REBUILD`; TCK C-60 |
| 2b | `RANGE`/`MULTIRANGE`/`base` types | ✅ `typtype 'r'` managed `TYPE` — `pg_range` canonical (subtype, non-default collation/opclass, canonical, subtype-diff), `CREATE TYPE ... AS RANGE`, `DROP`+`CREATE` `REBUILD`; generated family (`'m'` multirange, constructors, r→m cast) folds via `loadAutoTypeFamily`, never an object; base types stay `OTHER`; TCK C-61 |
| 2c | `AGGREGATE`/`WINDOW` routines | ✅ `prokind 'a'` managed `AGGREGATE` — canonical composed from the `pg_aggregate` option list (`pg_get_functiondef` refuses), `CREATE AGGREGATE`, `DROP`+`CREATE` `REBUILD`; ordered-set/hypothetical ride the same path; TCK C-62 (`'w'` window functions were later promoted to `ROUTINE` by 6b-3) |

## Phase 3 — partitions

- ✅ Parent `relkind 'p'`: managed `TABLE`; canonical `pg_get_partkeydef` + columns + reloptions; partkey drift → manual `DESTRUCTIVE`.
- ✅ Children: managed `PARTITION`; canonical bound `pg_get_expr(relpartbound)`; create `PARTITION OF parent FOR VALUES…`; bound drift → `DESTRUCTIVE` recreate; classic-inheritance children stay `inherited:` `OTHER`; TCK C-54.
- ✅ Partition indexes (`relkind 'I'`): covered (auto-cloned), never standalone.
- Repartitioning stays manual forever (data migration — spec non-goal).

## Phase 4 — cluster-referenced objects (needs spec amendment first)

One-time amendment: namespace objects may *reference* but never *manage* cluster-level objects
(roles, servers, publications, tablespaces). Referenced-object existence is a preflight check,
not a diff.

| # | Kind | Notes |
|---|------|-------|
| 4a | `FOREIGN_TABLE` | ✅ canonical `srvname` + `ftoptions` + columns; `CREATE FOREIGN TABLE`; TCK C-55 |
| 4b | `PUBLICATION` bindings | ✅ managed `PUBLICATION` (pub name + bound parent); `ALTER PUBLICATION` add/drop; `ADDITIVE`/`REBUILD`; TCK C-56 |
| 4c | `OWNERSHIP` | ✅ mirrored id; `owner=<role>` canonical (filtered to non-session owners); `ALTER … OWNER TO` `REBUILD`; TCK C-57 |
| 4d | `PRIVILEGES` rendering | ✅ mirrored id; `grantee:PRIVILEGE[*]` token canonical; acl diff → `GRANT`/`REVOKE` `REBUILD` (`REVOKE GRANT OPTION FOR` for option-only drift); unmanaged targets stay `privileges:` `OTHER`; TCK C-58 |
| 4e | `CAST`/`TRANSFORM`/`LANGUAGE` | ✅ managed on managed anchors; composed canonicals; `DROP`+`CREATE` `REBUILD`; `LANGUAGE` stays `OTHER` (cluster-level); TCK C-59 |

## Phase 5 — productization

| Item | Shape |
|------|-------|
| CLI `schema-reconciler-cli` | **out of scope** — zero-dep; `reconcile`/`plan`/`apply`/`dump`/`verify`; exit codes 0/1/2/3/4; `--policy`, `--allow-destructive`, `--plan-file` |
| Spring Boot starter | **out of scope** — `spring.schema-reconciler.*` props → bean + ordered `ApplicationRunner`; fail-fast on `PolicyViolationException` |
| Embedded-PG scratch | **deferred** — breaks same-engine canonicalization; revisit only behind explicit decision |
| Second dialect | **deferred** — SPI ready; build when a real consumer exists |

## Sequencing

Phase 1 → Phase 3 → Phase 4a/4d (with amendment) → Phase 2. All coverage
phases are done; the library's managed set now spans relations, routines,
aggregates, types (enum/composite/range/domain), constraints, indexes,
partitions, foreign tables, policies, statistics, rules, collations,
conversions, security labels, comments, extensions, publication bindings,
ownership, privileges and casts/transforms. Phase 5 stays parked: the
deliverable is the library — no CLI, embedded database or Spring Boot —
and embedded PG/second dialect remain deferred.

## Phase 6 — remaining gaps (research pass vs pgschema + catalog audit)

Ordered by severity. Competitive reference: after 6b we manage everything
pgschema manages (including column-level grants, `ALTER DEFAULT PRIVILEGES
... IN SCHEMA`, text-search objects and operators/opclasses/opfamilies)
plus extensions, ownership, collations, conversions, rules,
statistics, security labels, casts, transforms, publication bindings,
exclusion constraints, foreign tables and ranges/multiranges, which they
don't. Their `CONCURRENTLY`/online-DDL mode is a deliberate trade-off we
reject (always-transactional apply).

### 6a — silent no-ops: statements the scratch check admits but the snapshot can't see (correctness) ✅

A desired script whose statement passes `PgScriptCheck` but mutates catalog
state the loader doesn't model reconciles to UP_TO_DATE while the target
never changes. Rule: every admitted statement must be snapshot-visible or
rejected — now enforced in `PgScriptCheck` (3.2 rule 4). Resolutions:

| Statement | Invisible catalog state | Resolution |
|-----------|------------------------|------------|
| `GRANT SELECT (col) ON TABLE` | `pg_attribute.attacl` | 6b-1 manages it ✅ |
| `GRANT`/`REVOKE`/`COMMENT`/`SECURITY LABEL ... ON SCHEMA` | `nspacl`, description/seclabel on `pg_namespace` | rejected — the namespace is the reconcile unit |
| `SET TABLESPACE` / `CREATE ... TABLESPACE` / `REINDEX ... TABLESPACE` | `reltablespace` | rejected — decided against promotion (6b-6): cluster-level refs the dialect can't create or verify |
| `CREATE TABLE ... USING am` | `relam` | rejected — decided against promotion (6b-6), same cluster-ref boundary |
| `CREATE/ALTER TABLE ... OF`, `NOT OF` | `reloftype` | rejected — typed tables unmodeled (6b-6: conflicts with per-column canonicals) |
| `SET STATISTICS` (table/index column, statistics object) | `attstattarget`/`stxstattarget` | rejected — planner tuning, deliberately outside canonical |
| `CLUSTER ... USING`, `ALTER ... CLUSTER ON`/`SET WITHOUT CLUSTER` | `indisclustered` | rejected; bare `CLUSTER t` stays legal (rule 4) |
| `ALTER SEQUENCE`/identity `RESTART` | runtime `last_value` | rejected |
| `ALTER INDEX ... OWNER TO` | none — silently follows the table in PG itself | rejected (verified a true no-op) |
| `OWNER TO` on `COLLATION`/`CONVERSION`/`TEXT SEARCH`/`OPERATOR`/`STATISTICS`/`EXTENSION` | unmodeled owner columns | rejected |
| `ALTER INDEX ... ATTACH PARTITION` | partition-index binding | rejected |
| `ALTER TEXT SEARCH ...`/`ALTER OPERATOR ...` member mutations | `pg_ts_config_map`/`pg_amop`/`pg_amproc` | FTS `MAPPING`+dict options carved out (6b-4); class `ADD|DROP OPERATOR|FUNCTION` carved out (6b-5); standalone FAMILY members stay unmodeled/rejected |
| `DEPENDS ON EXTENSION` | `deptype 'e'` membership | rejected — membership is deliberately excluded |
| `ALTER COLLATION ... REFRESH VERSION` | `collversion` env metadata | rejected |
| `ALTER VIEW ... SET\|DROP DEFAULT` | view column defaults (not loaded) | rejected |
| `REFRESH MATERIALIZED VIEW`, `ANALYZE`, `TRUNCATE`, `REPACK`, `REINDEX`, `LOCK`, DML, `NOTIFY`/`LISTEN` | none — confined transactional effects | stay legal (3.2 rule 4) |

Also verified while auditing: `relispopulated` (`WITH [NO] DATA`) is in the
matview canonical, `tgenabled`/`relforcerowsecurity`/`attcompression` are
loaded, `GRANT ... ON ALL TABLES IN SCHEMA` expands to visible per-target
acls and stays legal, and corpus-07's `GRANT USAGE ON SCHEMA fixture` was
silently ignored in every run — the exact bug class, removed from the
fixture.

### 6b — coverage promotions (namespace-scoped, recreatable)

| # | Candidate | Notes |
|---|-----------|-------|
| 1 ✅ | column-level `PRIVILEGES` | `attacl` per column → `PRIVILEGES <col>` parented on the relation; `GRANT_COLUMN` attr parenthesizes the column after the privilege word (`GRANT SELECT ("c") ON TABLE t`); dep on the COLUMN object when modeled, else the relation; unmanaged relations stay `privileges:` OTHER; TCK C-63 |
| 2 ✅ | default privileges | `pg_default_acl` rows with `defaclnamespace` = target ns → `DEFAULT_PRIVILEGES <CLASS> for <role>`; `GRANT_TARGET` carries the class word, `GRANT_PREFIX` the `ALTER DEFAULT PRIVILEGES FOR ROLE "r" IN SCHEMA ${ns}` clause; FOR-ROLE/grantees are cluster refs; global rows invisible; script-check carve-out mirrors publication bindings (IN SCHEMA required, scratch name); TCK C-64 |
| 3 ✅ | window functions | `prokind 'w'` folded into `loadRoutines` (`pg_get_functiondef` emits `WINDOW`); `routine:` OTHER category removed; TCK C-65 |
| 4 ✅ | FTS objects | `TEXT_SEARCH_TEMPLATE`/`PARSER`/`DICTIONARY`/`CONFIGURATION` — canonical + `CREATE` composed from the catalog rows (no `pg_get_*def`); `pg_ts_config_map` folds into the configuration as `ALTER … ADD MAPPING` `POST_SQL` (aliases via `ts_token_type`); deps on function/template/parser/dict refs; `ALTER TEXT SEARCH` carve-out for `MAPPING` + dict-option forms; TCK C-66 |
| 5 ✅ | operators/opclasses/opfamilies | `OPERATOR` (`name(lt,rt)` signature, full option list), `OPERATOR_FAMILY`/`OPERATOR_CLASS` (`name USING am`); class `pg_amop`/`pg_amproc` members fold into the canonical (left type = `opcintype`) and render inside `CREATE OPERATOR CLASS … AS`; standalone family members unmodeled (ALTER FAMILY stays rejected); TCK C-67 |
| 6 ✅ decided | `relam`/`reltablespace`/`reloftype` | stays outside the canonical: all three are references to cluster-level objects (access methods, tablespaces, composite types-as-relations) the dialect can neither create nor verify — the same documented boundary as `attstattarget`/`indisclustered`. Their script forms (`USING`, `TABLESPACE`, `OF`/`NOT OF`) stay rejected per 6a so nothing applies untraced; typed tables additionally conflict with per-column canonicals |

Deliberately staying `OTHER`: `partindex`, `toast`, `inherited`
(data-risk), base `type:` (needs C), unrecreatable `collation:`,
`DEFAULT CONVERSION`, `language:` (cluster), unmanaged-target mirrors
(`comment:`/`seclabel:`/`privileges:`/`ownership:`/`cast:`/`transform:`).

### 6d — dependency-graph completion ✅

Follow-up audit of `pg_depend` coverage after the 6b promotions: every
`loadDependencies` leg's `refclassid` filter now names `pg_ts_*` and
`pg_operator`/`pg_opclass`/`pg_opfamily`, so managed dependents (views,
routines, column defaults, constraints, columns) record edges to
text-search/operator objects; `loadExternalDependents` gained the
parallel legs for cross-namespace dependents on them (external views and
column defaults, `pg_ts_dict`→template, `pg_ts_config`→dict/parser —
mappings record as config deps — and `pg_opclass`/`pg_amop`/`pg_amproc`/
`pg_operator`/`pg_opfamily` member refs). Verified by
`DependencyEdgesTest` (five new edges) and an external-dictionary blocking
test in `PostgreSqlDependenciesTest` (`b0d5de5`, `a65a999`).

### 6c — engine/API candidates (non-catalog) — deferred candidates

Reviewed and left as future enhancement candidates rather than gaps —
none affect correctness or coverage completeness:

- Machine-readable plan export — `ReconcilePlan.toSql()` is human text;
  a serialized plan + `apply(serialized)` would enable cross-process
  plan/apply split (fingerprint already guarantees freshness).
- ~~Object filtering~~ — **done**: `Builder.filter(Predicate<ObjectId>)`
  excludes objects from both snapshots with subtree cascade (children,
  mirrored annotations, annotation OTHERs) and rescoped staleness.
- Multi-namespace convenience — one namespace per reconcile today; a
  batch facade or multi-ns desired-script support for multi-schema apps.
- `nspacl`/`nspowner` — the target namespace's own grants/owner as a
  managed object (the only schema-level catalog row currently ignored);
  the script check already rejects `ON SCHEMA` grants/comments/labels so
  nothing applies untraced.

Do NOT build (reaffirmed non-goals): repartitioning, enum ordering
beyond add/rename, extension version upgrades (`ALTER EXTENSION UPDATE`),
collation `REFRESH VERSION`, runtime commands (`RESTART`, `ANALYZE`,
`REINDEX` output state), role/cluster object management, cross-dialect
translation, embedded PG, second dialect.
