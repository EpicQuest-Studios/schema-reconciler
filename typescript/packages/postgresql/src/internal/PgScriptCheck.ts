import { DesiredStateException } from "@schema-reconciler/core";
import * as PgSql from "./PgSql.ts";
import * as PgIdentifiers from "./PgIdentifiers.ts";

/**
 * Enforces the script constraints of spec 3.2 rules 1–3 for PostgreSQL:
 * no transaction control, no session-level locks, no namespace DDL, no
 * statements that cannot run inside a transaction block, no session-level
 * `SET`/`RESET`, no DDL for database-wide objects — servers,
 * roles, event triggers and the like have no namespace anchor, so they can
 * never appear in a snapshot and a script creating one would go undetected
 * (5.4) — and no object names qualified by a namespace other than the
 * scratch namespace itself: a qualified `other_schema.name` in
 * object-name position would create (or alter, or grant on) an object that
 * escapes the scratch namespace. References to other namespaces elsewhere
 * in a statement are allowed: a cross-schema `REFERENCES` clause is a
 * legitimate external dependency (5.8.3), not an escaping creation.
 * The scan runs on the substituted script — after `${namespace}` has
 * been replaced by the scratch name — so a legitimate
 * `CREATE SCHEMA ${namespace}`-as-text is still rejected: the core
 * owns namespace creation.
 */

const TRANSACTION_CONTROL = new Set([
        "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE",
        "END", "ABORT"]);

const NON_TRANSACTIONAL = new Set([
        "VACUUM", "CHECKPOINT"]);

/**
 * Session-level advisory lock functions (spec 3.2 rule 2). The
 * transaction-scoped `pg_advisory_xact_*` variants are allowed:
 * they are released by the scratch rollback. Unlock functions acquire
 * nothing, so they are not banned either.
 */
const SESSION_LOCKS = new Set([
        "PG_ADVISORY_LOCK", "PG_ADVISORY_LOCK_SHARED",
        "PG_TRY_ADVISORY_LOCK", "PG_TRY_ADVISORY_LOCK_SHARED"]);

/**
 * System functions whose effect is not transactional — it survives the
 * scratch rollback (replication slots and origins, statistics resets,
 * WAL and backup control, server file writes, log emission) or hits
 * other sessions outright (terminating or cancelling backends, config
 * reload). Transactional variants — `pg_logical_slot_peek_*`,
 * `lo_import`, `pg_notify` — stay legal.
 */
const NON_TXN_FUNCTIONS = new Set([
        "PG_TERMINATE_BACKEND", "PG_CANCEL_BACKEND", "PG_RELOAD_CONF",
        "PG_ROTATE_LOGFILE", "PG_LOG_BACKEND_MEMORY_CONTEXTS",
        "PG_LOG_STANDBY_SNAPSHOT", "PG_LOG_EMIT", "PG_LOGICAL_EMIT_MESSAGE",
        "PG_SWITCH_WAL", "PG_SWITCH_XLOG", "PG_CREATE_RESTORE_POINT",
        "PG_BACKUP_START", "PG_BACKUP_STOP", "PG_START_BACKUP", "PG_STOP_BACKUP",
        "PG_PROMOTE", "PG_CHECKPOINT",
        "PG_WAL_REPLAY_PAUSE", "PG_WAL_REPLAY_RESUME",
        "PG_SYNC_REPLICATION_SLOTS",
        "PG_FILE_WRITE", "PG_FILE_RENAME", "PG_FILE_UNLINK",
        "PG_FILE_MKDIR", "PG_FILE_SYNC", "LO_EXPORT", "BE_LO_EXPORT",
        "PG_STAT_RESET", "PG_STAT_RESET_SHARED", "PG_STAT_RESET_SLRU",
        "PG_STAT_RESET_SINGLE_TABLE_COUNTERS",
        "PG_STAT_RESET_SINGLE_FUNCTION_COUNTERS",
        "PG_STAT_RESET_REPLICATION_SLOT", "PG_STAT_RESET_SUBSCRIPTION_STATS",
        "PG_STAT_FORCE_NEXT_FLUSH",
        "PG_CREATE_LOGICAL_REPLICATION_SLOT",
        "PG_CREATE_PHYSICAL_REPLICATION_SLOT",
        "PG_TEMPORARY_REPLICATION_SLOT", "PG_DROP_REPLICATION_SLOT",
        "PG_REPLICATION_SLOT_ADVANCE", "PG_LOGICAL_SLOT_GET_CHANGES",
        "PG_LOGICAL_SLOT_GET_BINARY_CHANGES",
        "PG_COPY_LOGICAL_REPLICATION_SLOT",
        "PG_COPY_PHYSICAL_REPLICATION_SLOT",
        "PG_REPLICATION_ORIGIN_CREATE", "PG_REPLICATION_ORIGIN_DROP",
        "PG_REPLICATION_ORIGIN_ADVANCE", "PG_REPLICATION_ORIGIN_SESSION_SETUP",
        "PG_REPLICATION_ORIGIN_SESSION_RESET",
        "PG_REPLICATION_ORIGIN_XACT_SETUP", "PG_REPLICATION_ORIGIN_XACT_RESET"]);

const SET_CONFIG_CALL = /\bSET_CONFIG\b/g;

/**
 * Sequence-valued functions whose effect is NOT transactional: `nextval`
 * and `setval` survive the scratch rollback, so a
 * namespace-qualified literal argument is an escape. (currval/lastval
 * only read session state.) The name match stops at the word:
 * `callArguments` handles the quoting and comment gap before the
 * `(`, so `"nextval" ('o.s')` cannot evade the check.
 */
const SEQUENCE_CALL = /\b(NEXTVAL|SETVAL)\b/g;

/**
 * Statements operating on session state with no schema effect.
 * `PREPARE` covers both forms — prepared statements and two-phase
 * `PREPARE TRANSACTION`; `DISCARD` is the same for all variants.
 */
const SESSION_STATE = new Set([
        "DECLARE", "EXECUTE", "DEALLOCATE", "COPY", "FETCH", "MOVE", "CLOSE",
        "PREPARE", "DISCARD"]);

/** CREATE/DROP/ALTER head modifiers skipped before the object-kind word. */
const HEAD_MODIFIERS = new Set([
        "UNIQUE", "OR", "REPLACE", "IF", "NOT", "EXISTS", "TEMP",
        "TEMPORARY", "UNLOGGED", "GLOBAL", "LOCAL", "MATERIALIZED",
        "RECURSIVE", "FOREIGN", "TRUSTED", "UNTRUSTED", "PROCEDURAL",
        "CONSTRAINT"]);

/** Tokens between the object-kind word and the object name. */
const NAME_MODIFIERS = new Set([
        "IF", "NOT", "EXISTS", "ONLY", "CONCURRENTLY",
        // multi-word kinds: TEXT SEARCH ..., OPERATOR FAMILY/CLASS
        "SEARCH", "CONFIGURATION", "DICTIONARY", "PARSER", "TEMPLATE",
        "FAMILY", "CLASS"]);

/**
 * Objects whose name anchors a second object after `ON`. A RULE's
 * anchor follows `TO` on CREATE and `ON` on DROP/ALTER;
 * `checkOnTarget` handles both words.
 */
const ON_ANCHORED = new Set([
        "INDEX", "TRIGGER", "POLICY", "RULE"]);

/** GRANT/REVOKE object kinds that take a namespace-scoped name. */
const GRANT_KINDS = new Set([
        "TABLE", "SEQUENCE", "FUNCTION", "PROCEDURE", "ROUTINE",
        "DOMAIN", "TYPE", "STATISTICS"]);

/** GRANT/REVOKE targets with no namespace anchor. */
const GRANT_DATABASE_WIDE = new Set([
        "DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "LARGE",
        "FOREIGN", "LANGUAGE", "PARAMETER", "SERVER"]);

/** Tokens between `SELECT ... INTO` and the created table's name. */
const SELECT_INTO_MODIFIERS = new Set([
        "TEMP", "TEMPORARY", "UNLOGGED", "TABLE"]);

/**
 * COMMENT ON / SECURITY LABEL ON kinds whose target carries its argument
 * types in parentheses — `FUNCTION f(t)`, `OPERATOR op(t,t)`.
 * Those are type references, like a cross-schema `REFERENCES`: a
 * qualified `other.t` inside the parentheses is a legitimate
 * foreign type, so only the name before the opening parenthesis anchors
 * the target. A CAST's operands stay checked: the parenthesized type
 * pair is the cast's entire name and anchors it.
 */
const SIGNATURE_TARGET_KINDS = new Set([
        "FUNCTION", "PROCEDURE", "ROUTINE", "AGGREGATE", "OPERATOR"]);

/** The `${namespace}` placeholder text of spec 3.2. */
const TOKEN = "${namespace}";

/** CREATE/DROP/ALTER objects with no namespace anchor (spec 5.4). */
const DATABASE_WIDE_OBJECTS = new Set([
        "SERVER", "PUBLICATION", "ROLE", "USER", "GROUP", "EVENT",
        "ACCESS", "DEFAULT", "OWNED", "LANGUAGE", "LARGE"]);

/**
 * `ALTER ... OWNER TO` targets whose owner column feeds the
 * snapshot — the pg_class/pg_proc/pg_type ownership legs (A.5
 * OWNERSHIP). An index's owner follows its table silently, and
 * conversion, collation, text-search, operator, statistics and
 * extension owners are never loaded — either way an unlisted target
 * would apply without a trace.
 */
const OWNER_MODELED = new Set([
        "TABLE", "VIEW", "MATERIALIZED", "SEQUENCE", "FOREIGN",
        "FUNCTION", "PROCEDURE", "ROUTINE", "AGGREGATE", "TYPE",
        "DOMAIN"]);

/**
 * COMMENT ON / SECURITY LABEL ON kinds whose target can never appear in a
 * namespace snapshot. `FOREIGN` is handled separately:
 * `FOREIGN SERVER`/`FOREIGN DATA WRAPPER` are database-wide, but a
 * `FOREIGN TABLE` is namespace-scoped and takes the qualifier check.
 */
const COMMENT_DATABASE_WIDE = new Set([
        "DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "SERVER",
        "PUBLICATION", "SUBSCRIPTION", "EVENT", "LANGUAGE", "ACCESS",
        "LARGE", "PROCEDURAL", "EXTENSION"]);

export function check(script: string, scratchNamespace?: string): void {
    for (const statement of PgSql.statements(script)) {
        const t = statement.tokens;
        const first = t[0]!;
        if (TRANSACTION_CONTROL.has(first)) {
            throw rejected(statement, "transaction control is not allowed in the desired script");
        }
        if (NON_TRANSACTIONAL.has(first)) {
            throw rejected(statement,
                    first + " cannot run inside a transaction block");
        }
        if (first === "CLUSTER" && clusterNamesNoTable(t)) {
            throw rejected(statement,
                    "CLUSTER without a table cannot run inside a transaction block");
        }
        if (first === "CLUSTER" && t.includes("USING")) {
            throw rejected(statement,
                    "CLUSTER ... USING records the clustering index; that"
                            + " catalog state is outside the snapshot");
        }
        if (first === "CREATE" || first === "DROP" || first === "ALTER") {
            checkDdl(statement, first, scratchNamespace);
        }
        if (first === "REASSIGN") {
            throw rejected(statement,
                    "REASSIGN OWNED changes ownership database-wide and cannot be represented in a snapshot");
        }
        if (first === "GRANT" || first === "REVOKE") {
            if (!t.includes("ON")) {
                throw rejected(statement,
                        "role membership grants are database-wide and cannot be represented in a snapshot");
            }
            if (scratchNamespace !== undefined) {
                checkGrantTarget(statement, scratchNamespace);
            }
        }
        if (first === "COMMENT" || first === "SECURITY") {
            checkCommentTarget(statement, scratchNamespace);
        }
        if (first === "REINDEX") {
            checkReindex(statement);
        }
        if (scratchNamespace !== undefined) {
            // SELECT INTO / CREATE TABLE AS-style INTO at top level —
            // also reachable through CREATE VIEW v AS SELECT ... INTO;
            // INSERT INTO / MERGE INTO name a write target, which is a
            // confined data effect (3.2 rule 4), not an object creation
            const into = indexAtDepthZero(t, "INTO");
            if (into >= 0
                    && !(into > 0 && (t[into - 1] === "INSERT"
                            || t[into - 1] === "MERGE"))) {
                checkNameAt(statement,
                        pastModifiers(t, into + 1, SELECT_INTO_MODIFIERS),
                        scratchNamespace);
            }
            if (evaluatesNow(t, first)) {
                checkSequenceCalls(statement, collapseEscapes(statement.text),
                        scratchNamespace);
            }
            checkScratchLiteral(statement, scratchNamespace);
        }
        if (SESSION_STATE.has(first)) {
            throw rejected(statement,
                    first + " is session state and must not appear in the script");
        }
        if (first === "IMPORT" && scratchNamespace !== undefined) {
            const into = indexAtDepthZero(t, "INTO");
            if (into >= 0) {
                checkSchemaName(statement, into + 1, scratchNamespace);
            }
        }
        if (first === "SET" && t.length < 2) {
            throw rejected(statement, "SET names no setting");
        }
        if (first === "SET"
                && !(t[1] === "LOCAL" || t[1] === "TRANSACTION"
                        || t[1] === "CONSTRAINTS")) {
            throw rejected(statement,
                    "session-level SET leaks onto the connection; use SET LOCAL");
        }
        if (first === "RESET" || first === "LOAD") {
            throw rejected(statement,
                    first + " changes session state and must not appear in the script");
        }
        if (t.some(x => unquote(x) === "SET_CONFIG")) {
            checkSetConfig(statement, collapseEscapes(statement.text));
        }
        if (first === "DO") {
            checkDoBody(statement, scratchNamespace);
        }
        checkBannedCalls(statement);
        if (first === "REPACK"
                && (t.includes("CONCURRENTLY") || repackNamesNoTable(t))) {
            throw rejected(statement,
                    "REPACK without a table, or with CONCURRENTLY,"
                            + " cannot run inside a transaction block");
        }
        if (first === "WAIT") {
            throw rejected(statement,
                    "WAIT FOR LSN is a session-level replication operation and must not appear in the script");
        }
    }
}

/**
 * A `DO` block executes during the replay, so its body gets the
 * non-transactional scans top-level statements get: `set_config`
 * and sequence calls matched on the whole body text (with `''`
 * escapes collapsed — spec §8 rule 5 scans literals deliberately, they
 * are what an `EXECUTE` hands to the executor), banned calls on
 * the tokenized inner statements, and the object-creation check for
 * table-creating `INTO` forms. `EXECUTE '...'` literals
 * inside the body recurse through the full statement check — dynamic
 * SQL can carry anything. Transactional effects inside (foreign-table
 * writes, CALLs) are confined per 3.2 rule 4 and stay legal.
 */
function checkDoBody(statement: PgSql.StatementTokens,
                     scratchNamespace: string | undefined): void {
    const text = collapseEscapes(statement.text);
    checkSetConfig(statement, text);
    if (scratchNamespace === undefined) {
        return;
    }
    checkSequenceCalls(statement, text, scratchNamespace);
    for (const body of PgSql.literalBodies(statement.text)) {
        for (const inner of PgSql.statements(collapseEscapes(body))) {
            const innerTokens = inner.tokens;
            // in PL/pgSQL an INTO not led by TEMP/TEMPORARY/UNLOGGED/TABLE is
            // variable assignment; the modifier-led forms run a table-creating
            // SELECT INTO whose target is checked
            for (let i = 0; i + 1 < innerTokens.length; i++) {
                if (innerTokens[i] === "INTO"
                        && SELECT_INTO_MODIFIERS.has(innerTokens[i + 1]!)) {
                    checkNameAt(inner, pastModifiers(innerTokens, i + 1,
                            SELECT_INTO_MODIFIERS), scratchNamespace);
                }
            }
            checkBannedCalls(inner);
            checkExecuteLiterals(inner, scratchNamespace);
        }
    }
}

/**
 * Session-leaking and non-transactional calls — a call always has
 * `(` after the name, so a bare identifier (a column or object
 * reference, a name inside a literal or comment) is not an invocation
 * and must not reject. Token-based, so comments and quoted literals are
 * already gone; inside `DO` bodies the same check runs on the
 * body's inner statements.
 */
function checkBannedCalls(statement: PgSql.StatementTokens): void {
    const t = statement.tokens;
    for (let i = 0; i + 1 < t.length; i++) {
        if (t[i + 1] !== "(") {
            continue;
        }
        const fn = unquote(t[i]!);
        if (SESSION_LOCKS.has(fn)) {
            throw rejected(statement,
                    "session-level advisory locks leak onto the pooled connection and are not allowed");
        }
        if (NON_TXN_FUNCTIONS.has(fn)) {
            throw rejected(statement,
                    "its effect is not transactional or hits other sessions; it must not"
                            + " appear in the script (3.2 rule 1)");
        }
    }
}

/**
 * The CREATE/DROP/ALTER rules: object-kind resolution, the always-rejected
 * kinds (namespace DDL, database-wide and non-transactional objects,
 * `CONCURRENTLY` forms), then the qualified-name checks for the head
 * object, `ON`/`TO` anchors, comma-separated object lists, and
 * ALTER sub-command operands.
 */
function checkDdl(statement: PgSql.StatementTokens, first: string,
                  scratchNamespace: string | undefined): void {
    const t = statement.tokens;
    if (kindIndex(t) >= t.length) {
        throw rejected(statement, first + " names no object kind");
    }
    const object = objectWord(t);
    if (object === "SCHEMA") {
        throw rejected(statement,
                first + " SCHEMA is not allowed; the core owns namespace creation");
    }
    // CREATE/DROP DATABASE/TABLESPACE and ALTER SYSTEM cannot run inside a
    // transaction block at all; the remaining forms of those objects are
    // transaction-legal but are database-wide objects below
    if (object === "SYSTEM"
            || ((object === "DATABASE" || object === "TABLESPACE")
                    && first !== "ALTER")) {
        throw rejected(statement,
                first + " " + object + " cannot run inside a transaction block");
    }
    if (object === "DATABASE" || object === "SUBSCRIPTION"
            || object === "TABLESPACE") {
        throw rejected(statement,
                first + " " + object + " is a database-wide object"
                        + " that cannot be represented in a namespace snapshot");
    }
    if (object === "DEFAULT" && first === "ALTER") {
        // ALTER DEFAULT PRIVILEGES — only an IN SCHEMA-scoped form
        // mutates a namespace-visible row (pg_default_acl); a global
        // default ACL belongs to no namespace
        checkDefaultPrivileges(statement, scratchNamespace);
        return;
    }
    if (object === "PUBLICATION" && first === "ALTER") {
        // a binding mutation (ADD|SET|DROP TABLE/TABLES IN SCHEMA/SEQUENCE
        // — the only PUBLICATION form that touches a namespace) is
        // namespace-anchored through its bound object; publication
        // properties, ownership and renames stay database-wide
        checkPublicationBinding(statement, scratchNamespace);
        return;
    }
    if (DATABASE_WIDE_OBJECTS.has(object)
            || (object === "DATA" && t.includes("WRAPPER"))) {
        throw rejected(statement,
                first + " " + object
                        + " creates a database-wide object that cannot be represented in a namespace snapshot");
    }
    if (t.includes("CONCURRENTLY")) {
        // CREATE/DROP INDEX CONCURRENTLY and ALTER ... DETACH PARTITION
        // CONCURRENTLY — the only CONCURRENTLY forms of these statements
        throw rejected(statement,
                first + " " + object + " CONCURRENTLY cannot run inside a transaction block");
    }
    if (first === "ALTER" && adjacent(t, "SET", "SCHEMA")) {
        throw rejected(statement,
                "ALTER ... SET SCHEMA is not allowed; the core owns namespace creation");
    }
    if (first === "ALTER" && object === "INDEX" && t.includes("ALL")) {
        throw rejected(statement,
                "ALTER INDEX ALL IN TABLESPACE is database-wide"
                        + " and cannot be represented in a snapshot");
    }
    // Statements whose catalog effect is outside the snapshot model —
    // they would apply in the scratch replay yet leave no trace, so a
    // plan could claim success while the target never changed (6a).
    if (adjacent(t, "SET", "STATISTICS")) {
        throw rejected(statement,
                "SET STATISTICS tunes the planner and stays outside the snapshot");
    }
    if (first === "ALTER" && object === "COLLATION"
            && adjacent(t, "REFRESH", "VERSION")) {
        throw rejected(statement,
                "REFRESH VERSION reloads environment metadata, not schema state");
    }
    if (first === "ALTER") {
        let d = 0;
        for (let i = 1; i + 2 < t.length; i++) {
            const w = t[i]!;
            if (w === "(") {
                d++;
            } else if (w === ")") {
                d--;
            } else if (d === 0 && w === "DEPENDS" && t[i + 1] === "ON"
                    && t[i + 2] === "EXTENSION") {
                throw rejected(statement,
                        "DEPENDS ON EXTENSION mutates extension membership,"
                                + " which the model excludes");
            }
        }
    }
    if (first === "ALTER" && object === "INDEX" && t.includes("ATTACH")) {
        throw rejected(statement,
                "ALTER INDEX ... ATTACH PARTITION mutates an auto-managed"
                        + " partition index binding");
    }
    if (first === "ALTER" && (adjacent(t, "CLUSTER", "ON")
            || adjacent(t, "WITHOUT", "CLUSTER"))) {
        throw rejected(statement,
                "CLUSTER membership is catalog state the snapshot does not model");
    }
    if (first === "ALTER" && object === "TEXT") {
        checkFtsAlter(statement, scratchNamespace);
    }
    if (first === "ALTER" && object === "OPERATOR") {
        checkOperatorAlter(statement);
    }
    if (first === "ALTER" && adjacent(t, "OWNER", "TO")
            && !OWNER_MODELED.has(object)) {
        throw rejected(statement,
                "ownership of " + object + " objects is not modeled");
    }
    if (first === "ALTER" && object === "VIEW"
            && (adjacent(t, "SET", "DEFAULT") || adjacent(t, "DROP", "DEFAULT"))) {
        throw rejected(statement,
                "view column defaults are not modeled");
    }
    let depth = 0;
    for (let i = 1; i < t.length; i++) {
        const w = t[i]!;
        if (w === "(") {
            depth++;
            continue;
        }
        if (w === ")") {
            depth--;
            continue;
        }
        if (depth > 0) {
            continue;
        }
        // a top-level RESTART is sequence/identity runtime state, a
        // top-level TABLESPACE the storage clause — an unquoted object
        // named after either keyword rejects too, but the quoted form
        // stays legal (fail closed)
        if (w === "RESTART" && first === "ALTER") {
            throw rejected(statement,
                    "RESTART changes runtime sequence state, not schema state");
        }
        if (w === "TABLESPACE") {
            throw rejected(statement,
                    "TABLESPACE is a cluster-level object that cannot be"
                            + " represented in a snapshot");
        }
    }
    if (object === "TABLE") {
        // OF at top level is the typed-table clause — PARTITION OF is the
        // one legal OF form; trailing NOT OF detaches the type
        const of = indexAtDepthZero(t, "OF");
        if (of > 0 && t[of - 1] !== "PARTITION"
                && (of + 1 < t.length || t[of - 1] === "NOT")) {
            throw rejected(statement, "typed tables (OF) are not modeled");
        }
        if (first === "CREATE" && indexAtDepthZero(t, "USING") >= 0) {
            throw rejected(statement,
                    "CREATE TABLE ... USING names an access method that"
                            + " is not modeled");
        }
    }
    if (scratchNamespace === undefined) {
        return;
    }
    const name = nameIndex(t);
    checkNameAt(statement, name, scratchNamespace);
    if (ON_ANCHORED.has(object)) {
        // the anchor ON follows the object name — an object named like
        // the keyword or a modifier (… on ON t, … exists ON t), or an
        // UPDATE OF column, is itself an ON token or shifts the name
        // index, so every depth-0 ON is examined
        let d = 0;
        for (let i = 1; i < t.length; i++) {
            const w = t[i]!;
            if (w === "(") {
                d++;
            } else if (w === ")") {
                d--;
            } else if (d === 0 && w === "ON") {
                checkOnTarget(statement, i, scratchNamespace);
            }
        }
    }
    if (first === "CREATE" && object === "RULE") {
        // the target TO follows the rule name — a rule named after the
        // keyword (… AS ON INSERT TO t) must not shift the anchor
        checkOnTarget(statement, indexAtDepthZero(t, "TO", name + 1), scratchNamespace);
    }
    if (object === "CAST") {
        // (source AS target) is the cast's name — a qualified type
        // anchors the cast to that type's namespace
        const open = t.indexOf("(");
        if (open >= 0) {
            const close = pastParen(t, open);
            for (let i = open + 1; i + 1 < close - 1; i++) {
                if (t[i + 1] === ".") {
                    checkQualifierAt(statement, i, scratchNamespace);
                }
            }
        }
    }
    if (object === "TRANSFORM") {
        // the type after FOR anchors the transform's namespace
        const type = t.indexOf("FOR") + 1;
        if (type > 0) {
            checkQualifierAt(statement, type, scratchNamespace);
        }
    }
    if (first === "DROP") {
        checkNameList(statement, name, scratchNamespace);
    }
    if (first === "ALTER" && (object === "TABLE" || object === "INDEX")) {
        // ALTER sub-commands whose operand is an object that gets mutated —
        // a partition attaches into (or detaches from) the named table.
        // INHERIT/PARTITION OF name references instead: a foreign parent
        // is a legitimate external dependency (5.8.3); a bare OF (typed
        // tables) is rejected above as unmodeled.
        checkQualifiedAfter(statement, t, "ATTACH", "PARTITION", scratchNamespace);
        checkQualifiedAfter(statement, t, "DETACH", "PARTITION", scratchNamespace);
    }
    if (object === "EXTENSION") {
        if (first !== "CREATE") {
            throw rejected(statement, first + " EXTENSION mutates a database-wide"
                    + " object that cannot be represented in a namespace snapshot");
        }
        const schema = indexAtDepthZero(t, "SCHEMA", name + 1);
        if (schema >= 0) {
            checkSchemaName(statement, schema + 1, scratchNamespace);
        }
    }
}

/**
 * The `ALTER PUBLICATION` binding forms: `ADD|SET|DROP TABLE`,
 * `... TABLES IN SCHEMA` and `... SEQUENCE` mutate the
 * namespace-visible binding (A.5 PUBLICATION); anything else —
 * `SET (…)`, `OWNER TO`, `RENAME`, `SET SCHEMA` —
 * mutates the cluster-level publication and is rejected. Bound
 * table/sequence names take the qualifier check; schema names must be
 * the scratch namespace. Returns silently when `scratchNamespace`
 * is `undefined`: the form check alone decides admissibility.
 */
function checkPublicationBinding(statement: PgSql.StatementTokens,
                                 scratchNamespace: string | undefined): void {
    const t = statement.tokens;
    const name = nameIndex(t);
    let i = name + 1;
    if (i >= t.length || !new Set(["ADD", "SET", "DROP"]).has(t[i]!)) {
        throw rejected(statement, "ALTER PUBLICATION changes a database-wide"
                + " object; only its binding forms can appear in a snapshot");
    }
    if (++i >= t.length
            || !new Set(["TABLE", "TABLES", "SEQUENCE", "SEQUENCES"]).has(t[i]!)) {
        throw rejected(statement, "ALTER PUBLICATION changes a database-wide"
                + " object; only its binding forms can appear in a snapshot");
    }
    const schema = t[i] === "TABLES" || t[i] === "SEQUENCES";
    if (scratchNamespace === undefined) {
        return;
    }
    if (schema) {
        const s = indexAtDepthZero(t, "SCHEMA");
        checkSchemaList(statement, s + 1, scratchNamespace);
        return;
    }
    // table/sequence name list — the first name follows the verb word
    // (with an optional ONLY), the rest follow commas
    let first = i + 1;
    if (first < t.length && t[first] === "ONLY") {
        first++;
    }
    checkQualifierAt(statement, first, scratchNamespace);
    checkNameList(statement, i, scratchNamespace);
}

/**
 * `ALTER DEFAULT PRIVILEGES`: only the `IN SCHEMA`-scoped
 * GRANT/REVOKE form is namespace state (A.5 DEFAULT_PRIVILEGES). The
 * FOR ROLE clause is a cluster reference like a grantee; a global
 * default ACL (no `IN SCHEMA`) is database-wide and rejected.
 * Schema names must be the scratch namespace. Returns silently when
 * `scratchNamespace` is `undefined`: the form check alone
 * decides admissibility.
 */
function checkDefaultPrivileges(statement: PgSql.StatementTokens,
                                scratchNamespace: string | undefined): void {
    const t = statement.tokens;
    if (t.length < 3 || t[2] !== "PRIVILEGES") {
        throw rejected(statement, "ALTER DEFAULT changes a database-wide"
                + " object that cannot be represented in a snapshot");
    }
    const into = indexAtDepthZero(t, "IN");
    if (into < 0 || into + 1 >= t.length || t[into + 1] !== "SCHEMA") {
        throw rejected(statement, "ALTER DEFAULT PRIVILEGES without IN SCHEMA"
                + " is database-wide; only the IN SCHEMA form can appear"
                + " in a snapshot");
    }
    if (!t.includes("GRANT") && !t.includes("REVOKE")) {
        throw rejected(statement, "ALTER DEFAULT PRIVILEGES needs a"
                + " GRANT or REVOKE clause");
    }
    if (scratchNamespace === undefined) {
        return;
    }
    checkSchemaList(statement, into + 2, scratchNamespace);
}

/**
 * The REINDEX rules: `CONCURRENTLY` and the `DATABASE`/
 * `SCHEMA`/`SYSTEM` forms cannot run in a transaction block.
 * A qualified `TABLE`/`INDEX` name only selects which object
 * gets rebuilt — reindexing is transaction-safe, so the effect is
 * confined to the scratch connection (3.2 rule 4).
 */
function checkReindex(statement: PgSql.StatementTokens): void {
    const t = statement.tokens;
    if (t.includes("CONCURRENTLY")) {
        throw rejected(statement,
                "REINDEX CONCURRENTLY cannot run inside a transaction block");
    }
    const objectIndex = reindexObjectIndex(t);
    const object = objectIndex < 0 ? "" : t[objectIndex]!;
    if (object === "DATABASE" || object === "SCHEMA" || object === "SYSTEM") {
        throw rejected(statement,
                "REINDEX " + object + " cannot run inside a transaction block");
    }
    if (t.includes("TABLESPACE")) {
        throw rejected(statement,
                "REINDEX ... TABLESPACE moves storage, which is not modeled");
    }
}

/** Statement heads whose statement body is evaluated immediately. */
const EXECUTING_FIRST = new Set([
        "SELECT", "WITH", "VALUES", "TABLE", "INSERT", "UPDATE", "DELETE",
        "MERGE", "CALL"]);

/**
 * Whether `statement` evaluates expressions now — a query, DML, or
 * `CALL` runs its expressions during the desired-script replay, but
 * DDL only stores them (a `nextval` inside a `CREATE
 * FUNCTION` body is an opaque deferred body like `REFERENCES`,
 * per spec §8 rule 5). The exception: `CREATE TABLE AS` and
 * `CREATE MATERIALIZED VIEW` populate from their query at
 * creation, and `EXPLAIN ANALYZE` really executes its inner
 * statement.
 */
function evaluatesNow(t: readonly string[], first: string): boolean {
    if (EXECUTING_FIRST.has(first)) {
        return true;
    }
    if (first === "EXPLAIN") {
        return t.includes("ANALYZE");
    }
    if (first === "CREATE" && indexAtDepthZero(t, "AS") >= 0) {
        const object = objectWord(t);
        return object === "TABLE"
                || (object === "VIEW" && t.includes("MATERIALIZED"));
    }
    return false;
}

/**
 * The literal-argument check for `nextval`/`setval`: the
 * sequence name is a string like `'schema.seq'`, so the qualifier
 * has to be parsed out of the literal's content. Every literal in the
 * first argument is inspected — `CAST('o.s' AS regclass)`,
 * `to_regclass('o.s')`, `E'…'` and dollar-quoted forms all
 * wrap the same qualified name the bare literal carries.
 */
function checkSequenceCalls(statement: PgSql.StatementTokens, text: string,
                            scratchNamespace: string): void {
    const upper = text.toUpperCase();
    SEQUENCE_CALL.lastIndex = 0;
    for (let m = SEQUENCE_CALL.exec(upper); m !== null;
            m = SEQUENCE_CALL.exec(upper)) {
        const args = callArguments(text, m.index + m[0].length);
        if (args === null || args.length === 0) {
            continue;
        }
        for (const literal of PgSql.literalBodies(args[0]!)) {
            const qualifier = literalQualifier(literal);
            if (qualifier !== null && !isScratchOrPgTemp(qualifier, scratchNamespace)) {
                throw rejected(statement, "'" + qualifier + "…' names a sequence in another"
                        + " namespace; sequence values do not roll back (3.2 rule 1)");
            }
        }
    }
}

/**
 * A namespace literal that can never converge (rule-5 rationale): a
 * string whose whole content is the scratch name — `'"rcl_x"'` or
 * `'rcl_x'` — is the residue of a bare `${namespace}` token
 * inside a literal, baked freshly every run; and literal text carrying
 * the token without a `.` after it — `'${namespace}'`,
 * `'a${namespace}b'` — binds to the quoted target on apply but
 * normalisation sees no qualifier, so it drifts forever. A token
 * followed by `.` stays legal: `'${namespace}.seq'` binds
 * to `'"target".seq'` which normalisation maps back.
 */
function checkScratchLiteral(statement: PgSql.StatementTokens,
                             scratchNamespace: string): void {
    const quotedScratch = PgIdentifiers.quote(scratchNamespace);
    for (const body of PgSql.literalBodies(statement.text)) {
        if (body === scratchNamespace || body === quotedScratch) {
            throw rejected(statement, "'" + body
                    + "' is the scratch namespace substituted into a literal;"
                    + " a bare ${namespace} inside a literal bakes an unstable name");
        }
        for (let p = body.indexOf(TOKEN); p >= 0; p = body.indexOf(TOKEN, p + 1)) {
            if (p + TOKEN.length >= body.length
                    || body.charAt(p + TOKEN.length) !== ".") {
                throw rejected(statement, "'" + TOKEN + "' inside a literal has no"
                        + " target form it can converge to; use " + TOKEN + "."
                        + " for namespace references or a literal value");
            }
        }
    }
}

/**
 * The namespace qualifier of a literal like `schema.name`,
 * `"schema"."name"` or `schema."name"` — in token form
 * (quoted verbatim, unquoted upper-cased) so `isScratchOrPgTemp`
 * applies. `null` when the literal is an unqualified name.
 */
function literalQualifier(literal: string): string | null {
    if (literal.startsWith('"')) {
        let qualifier = "";
        let i = 1;
        while (i < literal.length) {
            const c = literal.charAt(i);
            if (c === '"') {
                if (i + 1 < literal.length && literal.charAt(i + 1) === '"') {
                    qualifier += '"';
                    i += 2;
                    continue;
                }
                return i + 1 < literal.length && literal.charAt(i + 1) === "."
                        ? '"' + qualifier + '"' : null;
            }
            qualifier += c;
            i++;
        }
        return null;
    }
    const dot = literal.indexOf(".");
    return dot < 0 ? null : literal.substring(0, dot).toUpperCase();
}

/**
 * `EXECUTE '…'` inside a body is dynamic SQL — every literal of
 * the EXECUTE statement (a `format()` template argument included)
 * recurses through the full check.
 */
function checkExecuteLiterals(statement: PgSql.StatementTokens,
                              scratchNamespace: string): void {
    if (statement.tokens.includes("EXECUTE")) {
        for (const sql of PgSql.literalBodies(statement.text)) {
            check(collapseEscapes(sql), scratchNamespace);
        }
    }
}

/** The index after the run of `modifiers` starting at `from`. */
function pastModifiers(t: readonly string[], from: number,
                       modifiers: ReadonlySet<string>): number {
    let i = from;
    while (i < t.length && modifiers.has(t[i]!)) {
        i++;
    }
    return i;
}

/** The index right after the parenthesized group opening at `open`. */
function pastParen(t: readonly string[], open: number): number {
    let depth = 0;
    for (let i = open; i < t.length; i++) {
        if (t[i] === "(") {
            depth++;
        } else if (t[i] === ")" && --depth === 0) {
            return i + 1;
        }
    }
    return t.length;
}

/**
 * `CLUSTER` names no table after its `(options)` or
 * `VERBOSE` lead-ins — the bare form re-clusters every table and
 * cannot run inside a transaction block; `CLUSTER t` is confined.
 */
function clusterNamesNoTable(t: readonly string[]): boolean {
    let i = 1;
    if (i < t.length && t[i] === "(") {
        i = pastParen(t, i);
    }
    if (i < t.length && t[i] === "VERBOSE") {
        i++;
    }
    return i >= t.length;
}

/**
 * `REPACK` (PostgreSQL 19+) names no table after its
 * `(options)` list — bare `REPACK` and
 * `REPACK … USING INDEX` repack every maintained table and cannot
 * run inside a transaction block; `REPACK t` is confined.
 */
function repackNamesNoTable(t: readonly string[]): boolean {
    let i = 1;
    if (i < t.length && t[i] === "(") {
        i = pastParen(t, i);
    }
    return i >= t.length || t[i] === "USING";
}

/**
 * `''` escapes collapsed to `'`: inside a `DO` literal
 * an embedded string arrives doubled, and every level of nesting doubles
 * it again — collapsing until stable reveals the innermost SQL text.
 */
function collapseEscapes(text: string): string {
    let collapsed = text;
    while (collapsed.includes("''")) {
        collapsed = collapsed.replaceAll("''", "'");
    }
    return collapsed;
}

/**
 * The upper-cased name a token refers to: an upper-cased keyword for
 * plain tokens, the identifier's content for a quoted token — so a
 * quoted `"pg_advisory_lock"` function name cannot evade the
 * session-lock check.
 */
function unquote(token: string): string {
    return token.length >= 2 && token.startsWith('"') && token.endsWith('"')
            ? token.substring(1, token.length - 1).replaceAll('""', '"')
                    .toUpperCase()
            : token;
}

/**
 * Every `set_config(name, value, is_local)` call in `text`
 * (string literals included, so `EXECUTE '...'` is covered) must
 * pass the literal `true` as its third argument; any other form —
 * `false`, an expression, fewer arguments — is rejected.
 */
function checkSetConfig(statement: PgSql.StatementTokens, text: string): void {
    const upper = text.toUpperCase();
    SET_CONFIG_CALL.lastIndex = 0;
    for (let m = SET_CONFIG_CALL.exec(upper); m !== null;
            m = SET_CONFIG_CALL.exec(upper)) {
        // the word without a parenthesized call isn't a call — a column,
        // literal or comment mention must not reject
        if (!opensCall(text, m.index + m[0].length)) {
            continue;
        }
        const args = callArguments(text, m.index + m[0].length);
        if (args === null || args.length !== 3
                || args[2]!.trim().toUpperCase() !== "TRUE") {
            throw rejected(statement,
                    "set_config must pass the literal true as is_local: a session-level"
                            + " setting leaks onto the pooled connection");
        }
    }
}

/**
 * Whether a (possibly quoted) function name ending at `from` opens a
 * parenthesized call — mirrors `callArguments`' lookahead.
 */
function opensCall(text: string, from: number): boolean {
    let i = skipCallGap(text, from);
    if (i < text.length && text.charAt(i) === '"') {
        i = skipCallGap(text, i + 1);
    }
    return i < text.length && text.charAt(i) === "(";
}

/**
 * Skips the gap between a function name and its opening parenthesis:
 * whitespace and comments — `--` to end of line and (nesting)
 * `/* ... *\/` — so `nextval --x\n ('s.t')` and
 * `setval /*c*\/ ('s.t', 1)` cannot evade the call checks.
 */
function skipCallGap(text: string, from: number): number {
    let i = from;
    for (;;) {
        while (i < text.length && isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i + 1 < text.length && text.charAt(i) === "-" && text.charAt(i + 1) === "-") {
            while (i < text.length && text.charAt(i) !== "\n") {
                i++;
            }
        } else if (i + 1 < text.length
                && text.charAt(i) === "/" && text.charAt(i + 1) === "*") {
            let depth = 1;
            i += 2;
            while (i < text.length && depth > 0) {
                if (i + 1 < text.length
                        && text.charAt(i) === "/" && text.charAt(i + 1) === "*") {
                    depth++;
                    i += 2;
                } else if (i + 1 < text.length
                        && text.charAt(i) === "*" && text.charAt(i + 1) === "/") {
                    depth--;
                    i += 2;
                } else {
                    i++;
                }
            }
        } else {
            return i;
        }
    }
}

/** The top-level comma-separated arguments of the call whose name ends at `from`, or null. */
function callArguments(text: string, from: number): string[] | null {
    let i = skipCallGap(text, from);
    // a quoted "set_config" ends at the closing quote before the '('
    if (i < text.length && text.charAt(i) === '"') {
        i = skipCallGap(text, i + 1);
    }
    if (i >= text.length || text.charAt(i) !== "(") {
        return null;
    }
    const args: string[] = [];
    let depth = 0;
    let start = i + 1;
    for (; i < text.length; i++) {
        const c = text.charAt(i);
        if (c === "'") {
            i++;
            while (i < text.length
                    && !(text.charAt(i) === "'"
                            && (i + 1 >= text.length || text.charAt(i + 1) !== "'"))) {
                i += text.charAt(i) === "'" ? 2 : 1;
            }
        } else if (c === "(") {
            depth++;
        } else if (c === ")") {
            depth--;
            if (depth === 0) {
                args.push(text.substring(start, i));
                return args;
            }
        } else if (c === "," && depth === 1) {
            args.push(text.substring(start, i));
            start = i + 1;
        }
    }
    return null;
}

/**
 * Rejects when the token at `index` begins a `qualifier.name`
 * pair whose qualifier is not the scratch namespace. `pg_temp` is
 * allowed: temporary objects are session-confined like the scratch
 * namespace itself.
 */
function checkQualifierAt(statement: PgSql.StatementTokens, index: number,
                          scratchNamespace: string): void {
    const t = statement.tokens;
    if (index + 2 >= t.length || t[index + 1] !== ".") {
        return;
    }
    const qualifier = t[index]!;
    if (isScratchOrPgTemp(qualifier, scratchNamespace)) {
        return;
    }
    throw rejected(statement, "'" + qualifier + "." + t[index + 2]
            + "' names an object in another namespace; objects must be created"
            + " inside ${namespace} or unqualified (3.2 rule 1)");
}

/** Rejects a bare schema name that is not the scratch namespace. */
function checkSchemaName(statement: PgSql.StatementTokens, index: number,
                         scratchNamespace: string): void {
    const t = statement.tokens;
    if (index >= t.length || isScratchOrPgTemp(t[index]!, scratchNamespace)) {
        return;
    }
    throw rejected(statement, "'" + t[index]
            + "' names another namespace; schema-level grants are only allowed"
            + " on ${namespace} itself (3.2 rule 1)");
}

/**
 * True when `token` names the scratch namespace or the session's
 * `pg_temp`. A quoted token (its quotes are preserved) compares
 * case-sensitively — `"S".t` is a different schema from
 * `s.t`; an unquoted token compares case-insensitively, which is
 * sound because the scratch name is fold-safe — it is already its own
 * lowercase fold (`PgIdentifiers.scratchName`).
 */
function isScratchOrPgTemp(token: string, scratchNamespace: string): boolean {
    if (token.startsWith('"')) {
        return token === PgIdentifiers.quote(scratchNamespace)
                || token === '"pg_temp"';
    }
    return token.toUpperCase() === scratchNamespace.toUpperCase() || token === "PG_TEMP";
}

/**
 * The qualified-name check for the operand after `keyword [second]` —
 * ALTER sub-commands (ATTACH/DETACH PARTITION) whose operand may name an
 * object in another namespace.
 */
function checkQualifiedAfter(statement: PgSql.StatementTokens, t: readonly string[],
                             keyword: string, second: string,
                             scratchNamespace: string): void {
    for (let i = 0; i + 1 < t.length; i++) {
        if (t[i] === keyword && t[i + 1] === second) {
            checkQualifierAt(statement, i + 2, scratchNamespace);
        }
    }
}

/** The object-name index of a CREATE/DROP/ALTER statement: kind word + modifiers. */
function nameIndex(t: readonly string[]): number {
    let i = kindIndex(t) + 1;
    while (i < t.length && NAME_MODIFIERS.has(t[i]!)) {
        i++;
    }
    return i;
}

/**
 * The qualified-name check at an object-name position found by skipping
 * modifiers. When the skipped word itself was the qualifier —
 * `CREATE TABLE exists.evil`, where `exists` is an unreserved
 * keyword the modifier scan treats as a modifier — the scan lands on the
 * dot, and the token before it is the qualifier to check.
 */
function checkNameAt(statement: PgSql.StatementTokens, index: number,
                     scratchNamespace: string): void {
    const t = statement.tokens;
    if (index > 0 && index < t.length && t[index] === ".") {
        checkQualifierAt(statement, index - 1, scratchNamespace);
        return;
    }
    checkQualifierAt(statement, index, scratchNamespace);
}

/** The index of the object-kind word, skipping CREATE/DROP/ALTER modifiers. */
function kindIndex(t: readonly string[]): number {
    let i = 1;
    while (i < t.length && HEAD_MODIFIERS.has(t[i]!)) {
        i++;
    }
    return i;
}

/**
 * The `ALTER TEXT SEARCH` carve-out. Managed text-search objects
 * make two member-mutation forms snapshot state: a configuration's
 * `MAPPING` actions (`pg_ts_config_map` rows fold into the
 * configuration object) and a dictionary's option redefinition
 * (`(opt = value, …)`, the `dictinitoption` field). Every
 * other form — `RENAME`, `OWNER TO`, `SET SCHEMA` and
 * every parser/template body — touches state the snapshot does not
 * model and rejects. Dictionary references inside `WITH` and
 * `REPLACE` name lists still pass the qualifier check.
 */
function checkFtsAlter(statement: PgSql.StatementTokens,
                       scratchNamespace: string | undefined): void {
    const t = statement.tokens;
    const name = nameIndex(t);
    const kind = name > 3 ? t[name - 1]! : "";
    let rest = name + 1;
    if (rest + 1 < t.length && t[rest] === ".") {
        rest += 2;
    }
    switch (kind) {
        case "CONFIGURATION": {
            if (rest + 1 < t.length && (t[rest] === "ADD"
                    || t[rest] === "ALTER" || t[rest] === "DROP")
                    && t[rest + 1] === "MAPPING") {
                let depth = 0;
                for (let i = rest + 2; i < t.length; i++) {
                    const w = t[i]!;
                    if (w === "(") {
                        depth++;
                    } else if (w === ")") {
                        depth--;
                    } else if (depth === 0 && scratchNamespace !== undefined
                            && (w === "WITH" || w === "REPLACE")) {
                        checkQualifierAt(statement, i + 1, scratchNamespace);
                        checkNameList(statement, i + 1, scratchNamespace);
                    }
                }
                return;
            }
            throw rejected(statement, "ALTER TEXT SEARCH " + kind
                    + " mutates state the snapshot does not model");
        }
        case "DICTIONARY": {
            if (rest < t.length && t[rest] === "(") {
                return;
            }
            throw rejected(statement, "ALTER TEXT SEARCH DICTIONARY"
                    + " mutates state the snapshot does not model");
        }
        default:
            throw rejected(statement, "ALTER TEXT SEARCH mutates"
                    + " members the snapshot does not model");
    }
}

/**
 * The `ALTER OPERATOR` carve-out. A class's member list is
 * snapshot state (the `pg_amop`/`pg_amproc` rows fold into the
 * `OPERATOR_CLASS` canonical), so `ALTER OPERATOR
 * CLASS n USING am ADD|DROP OPERATOR|FUNCTION …` is legal.
 * `ALTER OPERATOR FAMILY … ADD|DROP` member mutations are not —
 * standalone family members are outside the snapshot model — and
 * every other form (`RENAME`, `OWNER TO`,
 * `SET SCHEMA`, plain-operator alters) rejects.
 */
function checkOperatorAlter(statement: PgSql.StatementTokens): void {
    const t = statement.tokens;
    const name = nameIndex(t);
    const kind = name > 1 ? t[name - 1]! : "";
    let rest = name + 1;
    if (rest + 1 < t.length && t[rest] === ".") {
        rest += 2;
    }
    if (kind === "CLASS" && rest + 3 < t.length
            && t[rest] === "USING"
            && (t[rest + 2] === "ADD" || t[rest + 2] === "DROP")
            && (t[rest + 3] === "OPERATOR"
                    || t[rest + 3] === "FUNCTION")) {
        return;
    }
    throw rejected(statement, "ALTER OPERATOR " + (kind === "" ? "" : kind + " ")
            + "mutates state the snapshot does not model");
}

/** The qualified-name check for the object after `ON` (index/table). */
function checkOnTarget(statement: PgSql.StatementTokens, onIndex: number,
                       scratchNamespace: string): void {
    if (onIndex < 0) {
        return;
    }
    let i = onIndex + 1;
    if (i < statement.tokens.length && statement.tokens[i] === "ONLY") {
        i++;
    }
    checkNameAt(statement, i, scratchNamespace);
}

/**
 * The qualified-name check for a GRANT/REVOKE object: database-wide
 * targets are rejected outright, `ON SCHEMA` and `ON ALL ...
 * IN SCHEMA` take a namespace name, object kinds take a possibly
 * qualified object name.
 */
function checkGrantTarget(statement: PgSql.StatementTokens,
                          scratchNamespace: string): void {
    const t = statement.tokens;
    const on = indexAtDepthZero(t, "ON");
    let i = on + 1;
    if (i >= t.length) {
        return;
    }
    const w = t[i]!;
    // ON FOREIGN TABLE grants are namespace-scoped; FOREIGN SERVER and
    // FOREIGN DATA WRAPPER are database-wide
    const foreignTable = w === "FOREIGN" && i + 1 < t.length
            && t[i + 1] === "TABLE";
    if (GRANT_DATABASE_WIDE.has(w) && !foreignTable) {
        throw rejected(statement, "ON " + w
                + " is a database-wide grant and cannot be represented in a snapshot");
    }
    if (w === "ALL") {
        const schema = indexAtDepthZero(t, "SCHEMA");
        if (schema >= 0) {
            checkSchemaList(statement, schema + 1, scratchNamespace);
        }
        return;
    }
    if (w === "SCHEMA") {
        throw rejected(statement,
                "ON SCHEMA grants mutate the namespace object itself,"
                        + " which the snapshot does not model");
    }
    if (foreignTable || GRANT_KINDS.has(w)) {
        i += foreignTable ? 2 : 1;
    }
    checkQualifierAt(statement, i, scratchNamespace);
    checkNameList(statement, i, scratchNamespace);
}

/**
 * The bare-schema-name check for a `,`-separated list — `ON
 * SCHEMA a, b` and `ON ALL TABLES IN SCHEMA a, b` — bounded by the
 * grantee clause (TO in GRANT, FROM in REVOKE) so role lists behind it
 * are not mistaken for schema names.
 */
function checkSchemaList(statement: PgSql.StatementTokens, index: number,
                         scratchNamespace: string): void {
    const t = statement.tokens;
    let end = t.length;
    for (let j = index; j < t.length; j++) {
        if (t[j] === "TO" || t[j] === "FROM") {
            end = j;
            break;
        }
    }
    checkSchemaName(statement, index, scratchNamespace);
    for (let j = index; j + 1 < end; j++) {
        if (t[j] === ",") {
            checkSchemaName(statement, j + 1, scratchNamespace);
        }
    }
}

/**
 * The qualified-name check for every name of a top-level
 * `,`-separated list — a `DROP TABLE a, s.b` or `GRANT ON
 * TABLE a, s.b` carries several objects. Commas inside parentheses are
 * signature arguments, not list separators.
 */
function checkNameList(statement: PgSql.StatementTokens, fromIndex: number,
                       scratchNamespace: string): void {
    const t = statement.tokens;
    let depth = 0;
    // the scan includes fromIndex itself: a modifier-word name (…
    // exists, s.t) makes the name index land on the first list comma,
    // and the name after it still has to be checked
    for (let i = fromIndex; i < t.length; i++) {
        const w = t[i]!;
        if (w === "(") {
            depth++;
        } else if (w === ")") {
            depth--;
        } else if (depth === 0 && w === ",") {
            checkQualifierAt(statement, i + 1, scratchNamespace);
        }
    }
}

/**
 * The qualified-name check for COMMENT ON / SECURITY LABEL ON. Database-wide
 * kinds are rejected outright and `ON SCHEMA` takes a namespace name;
 * the rest of the target is scanned between `ON` and `IS`: a
 * `q.n.m` triple always exposes namespace qualifier `q`, and a
 * two-part `q.n` is a qualifier for every kind except `COLUMN`,
 * where it is a `table.column` pair.
 */
function checkCommentTarget(statement: PgSql.StatementTokens,
                            scratchNamespace: string | undefined): void {
    const t = statement.tokens;
    // the target ON is fixed per leading form: COMMENT takes it at the
    // head; SECURITY LABEL takes it after FOR <provider> — a provider
    // named after a keyword must not shift the anchor, and an object
    // named for must not fake the provider form (FOR sits at index 2)
    const on = t[0] === "SECURITY" && t.length > 2
            && t[2] === "FOR"
            ? 4
            : indexAtDepthZero(t, "ON");
    // the keyword IS is the last "IS" token — an earlier one can be a
    // keyword-named identifier (a column named "is"), which would
    // truncate the target scan and let a foreign qualifier through
    const is = t.lastIndexOf("IS");
    if (on < 0 || is < 0 || on + 1 >= t.length) {
        return;
    }
    const kind = t[on + 1]!;
    if (COMMENT_DATABASE_WIDE.has(kind)
            || (kind === "FOREIGN" && on + 2 < t.length
                    && (t[on + 2] === "SERVER" || t[on + 2] === "DATA"))) {
        throw rejected(statement, kind
                + " has no namespace anchor; a comment or security label on it"
                + " can never appear in a snapshot (3.2 rule 1)");
    }
    if (kind === "SCHEMA") {
        throw rejected(statement,
                "SCHEMA is the reconcile unit itself; a comment or"
                        + " security label on it can never appear in a"
                        + " namespace snapshot");
    }
    if (scratchNamespace === undefined) {
        return;
    }
    const column = kind === "COLUMN";
    let end = is;
    if (SIGNATURE_TARGET_KINDS.has(kind)) {
        for (let i = on + 1; i < is; i++) {
            if (t[i] === "(") {
                end = i;
                break;
            }
        }
    }
    for (let i = on + 1; i + 2 < end; i++) {
        if (t[i + 1] !== ".") {
            continue;
        }
        if (i + 4 < end && t[i + 3] === ".") {
            checkQualifierAt(statement, i, scratchNamespace);
            return;
        }
        if (!column) {
            checkQualifierAt(statement, i, scratchNamespace);
            return;
        }
        i++;
    }
}

/** True when `a` is immediately followed by `b` in the token list. */
function adjacent(tokens: readonly string[], a: string, b: string): boolean {
    for (let i = 0; i + 1 < tokens.length; i++) {
        if (tokens[i] === a && tokens[i + 1] === b) {
            return true;
        }
    }
    return false;
}

/** The object-kind word after CREATE/DROP/ALTER and its modifiers, or empty. */
function objectWord(tokens: readonly string[]): string {
    const i = kindIndex(tokens);
    return i < tokens.length ? tokens[i]! : "";
}

/**
 * The object-kind index of a REINDEX statement — TABLE, INDEX, SCHEMA,
 * DATABASE or SYSTEM — skipping the `(...)` option list and the
 * CONCURRENTLY/VERBOSE modifiers of the legacy syntax.
 */
function reindexObjectIndex(tokens: readonly string[]): number {
    let depth = 0;
    for (let i = 1; i < tokens.length; i++) {
        const w = tokens[i]!;
        if (w === "(") {
            depth++;
        } else if (w === ")") {
            depth--;
        } else if (depth === 0 && w !== "CONCURRENTLY" && w !== "VERBOSE") {
            return i;
        }
    }
    return -1;
}

/** The index of `word` at parenthesis depth 0, or -1. */
function indexAtDepthZero(tokens: readonly string[], word: string, from = 0): number {
    let depth = 0;
    for (let i = from; i < tokens.length; i++) {
        const w = tokens[i]!;
        if (w === "(") {
            depth++;
        } else if (w === ")") {
            depth--;
        } else if (depth === 0 && w === word) {
            return i;
        }
    }
    return -1;
}

function isWhitespace(c: string): boolean {
    return c === " " || (c >= "\t" && c <= "\r") || (c >= "\u001c" && c <= "\u001f");
}

function rejected(statement: PgSql.StatementTokens, reason: string): DesiredStateException {
    return new DesiredStateException("the desired script was rejected at position "
            + statement.offset + ": " + reason
            + " (\"" + statement.text.trim() + "\")");
}
