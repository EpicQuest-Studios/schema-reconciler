package studio.epicquest.schema.reconciler.postgresql.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import studio.epicquest.schema.reconciler.DesiredStateException;

/**
 * Enforces the script constraints of spec 3.2 rules 1–3 for PostgreSQL:
 * no transaction control, no session-level locks, no namespace DDL, no
 * statements that cannot run inside a transaction block, no session-level
 * {@code SET}/{@code RESET}, no DDL for database-wide objects — servers,
 * roles, event triggers and the like have no namespace anchor, so they can
 * never appear in a snapshot and a script creating one would go undetected
 * (5.4) — and no object names qualified by a namespace other than the
 * scratch namespace itself: a qualified {@code other_schema.name} in
 * object-name position would create (or alter, or grant on) an object that
 * escapes the scratch namespace. References to other namespaces elsewhere
 * in a statement are allowed: a cross-schema {@code REFERENCES} clause is a
 * legitimate external dependency (5.8.3), not an escaping creation.
 * The scan runs on the substituted script — after {@code ${namespace}} has
 * been replaced by the scratch name — so a legitimate
 * {@code CREATE SCHEMA ${namespace}}-as-text is still rejected: the core
 * owns namespace creation.
 */
public final class PgScriptCheck {

    private PgScriptCheck() {
    }

    private static final Set<String> TRANSACTION_CONTROL = Set.of(
            "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE",
            "END", "ABORT");

    private static final Set<String> NON_TRANSACTIONAL = Set.of(
            "VACUUM", "CHECKPOINT");

    /**
     * Session-level advisory lock functions (spec 3.2 rule 2). The
     * transaction-scoped {@code pg_advisory_xact_*} variants are allowed:
     * they are released by the scratch rollback. Unlock functions acquire
     * nothing, so they are not banned either.
     */
    private static final Set<String> SESSION_LOCKS = Set.of(
            "PG_ADVISORY_LOCK", "PG_ADVISORY_LOCK_SHARED",
            "PG_TRY_ADVISORY_LOCK", "PG_TRY_ADVISORY_LOCK_SHARED");

    /**
     * System functions whose effect is not transactional — it survives the
     * scratch rollback (replication slots and origins, statistics resets,
     * WAL and backup control, server file writes, log emission) or hits
     * other sessions outright (terminating or cancelling backends, config
     * reload). Transactional variants — {@code pg_logical_slot_peek_*},
     * {@code lo_import}, {@code pg_notify} — stay legal.
     */
    private static final Set<String> NON_TXN_FUNCTIONS = Set.of(
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
            "PG_REPLICATION_ORIGIN_XACT_SETUP", "PG_REPLICATION_ORIGIN_XACT_RESET");

    private static final Pattern SET_CONFIG_CALL = Pattern.compile("\\bSET_CONFIG\\b");

    /**
     * Sequence-valued functions whose effect is NOT transactional: {@code
     * nextval} and {@code setval} survive the scratch rollback, so a
     * namespace-qualified literal argument is an escape. (currval/lastval
     * only read session state.) The name match stops at the word: {@link
     * #callArguments} handles the quoting and comment gap before the {@code
     * (}, so {@code "nextval" ('o.s')} cannot evade the check.
     */
    private static final Pattern SEQUENCE_CALL = Pattern.compile(
            "\\b(NEXTVAL|SETVAL)\\b");

    /**
     * Statements operating on session state with no schema effect. {@code
     * PREPARE} covers both forms — prepared statements and two-phase {@code
     * PREPARE TRANSACTION}; {@code DISCARD} is the same for all variants.
     */
    private static final Set<String> SESSION_STATE = Set.of(
            "DECLARE", "EXECUTE", "DEALLOCATE", "COPY", "FETCH", "MOVE", "CLOSE",
            "PREPARE", "DISCARD");

    /** CREATE/DROP/ALTER head modifiers skipped before the object-kind word. */
    private static final Set<String> HEAD_MODIFIERS = Set.of(
            "UNIQUE", "OR", "REPLACE", "IF", "NOT", "EXISTS", "TEMP",
            "TEMPORARY", "UNLOGGED", "GLOBAL", "LOCAL", "MATERIALIZED",
            "RECURSIVE", "FOREIGN", "TRUSTED", "UNTRUSTED", "PROCEDURAL",
            "CONSTRAINT");

    /** Tokens between the object-kind word and the object name. */
    private static final Set<String> NAME_MODIFIERS = Set.of(
            "IF", "NOT", "EXISTS", "ONLY", "CONCURRENTLY",
            // multi-word kinds: TEXT SEARCH ..., OPERATOR FAMILY/CLASS
            "SEARCH", "CONFIGURATION", "DICTIONARY", "PARSER", "TEMPLATE",
            "FAMILY", "CLASS");

    /**
     * Objects whose name anchors a second object after {@code ON}. A RULE's
     * anchor follows {@code TO} on CREATE and {@code ON} on DROP/ALTER;
     * {@link #checkOnTarget} handles both words.
     */
    private static final Set<String> ON_ANCHORED = Set.of(
            "INDEX", "TRIGGER", "POLICY", "RULE");

    /** GRANT/REVOKE object kinds that take a namespace-scoped name. */
    private static final Set<String> GRANT_KINDS = Set.of(
            "TABLE", "SEQUENCE", "FUNCTION", "PROCEDURE", "ROUTINE",
            "DOMAIN", "TYPE", "STATISTICS");

    /** GRANT/REVOKE targets with no namespace anchor. */
    private static final Set<String> GRANT_DATABASE_WIDE = Set.of(
            "DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "LARGE",
            "FOREIGN", "LANGUAGE", "PARAMETER", "SERVER");

    /** Tokens between {@code SELECT ... INTO} and the created table's name. */
    private static final Set<String> SELECT_INTO_MODIFIERS = Set.of(
            "TEMP", "TEMPORARY", "UNLOGGED", "TABLE");

    /**
     * COMMENT ON / SECURITY LABEL ON kinds whose target carries its argument
     * types in parentheses — {@code FUNCTION f(t)}, {@code OPERATOR op(t,t)}.
     * Those are type references, like a cross-schema {@code REFERENCES}: a
     * qualified {@code other.t} inside the parentheses is a legitimate
     * foreign type, so only the name before the opening parenthesis anchors
     * the target. A CAST's operands stay checked: the parenthesized type
     * pair is the cast's entire name and anchors it.
     */
    private static final Set<String> SIGNATURE_TARGET_KINDS = Set.of(
            "FUNCTION", "PROCEDURE", "ROUTINE", "AGGREGATE", "OPERATOR");

    /** CREATE/DROP/ALTER objects with no namespace anchor (spec 5.4). */
    private static final Set<String> DATABASE_WIDE_OBJECTS = Set.of(
            "SERVER", "PUBLICATION", "ROLE", "USER", "GROUP", "EVENT",
            "ACCESS", "DEFAULT", "OWNED", "LANGUAGE", "LARGE");

    /**
     * COMMENT ON / SECURITY LABEL ON kinds whose target can never appear in a
     * namespace snapshot. {@code FOREIGN} is handled separately: {@code
     * FOREIGN SERVER}/{@code FOREIGN DATA WRAPPER} are database-wide, but a
     * {@code FOREIGN TABLE} is namespace-scoped and takes the qualifier check.
     */
    private static final Set<String> COMMENT_DATABASE_WIDE = Set.of(
            "DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "SERVER",
            "PUBLICATION", "SUBSCRIPTION", "EVENT", "LANGUAGE", "ACCESS",
            "LARGE", "PROCEDURAL", "EXTENSION");

    public static void check(String script) {
        check(script, null);
    }

    /**
     * @param scratchNamespace the substituted scratch name, for the
     *                         qualified-name check; {@code null} skips that
     *                         check (used by tests exercising other rules)
     */
    public static void check(String script, String scratchNamespace) {
        for (final var statement : PgSql.statements(script)) {
            final var t = statement.tokens();
            final var first = t.get(0);
            if (TRANSACTION_CONTROL.contains(first)) {
                throw rejected(statement, "transaction control is not allowed in the desired script");
            }
            if (NON_TRANSACTIONAL.contains(first)) {
                throw rejected(statement,
                        first + " cannot run inside a transaction block");
            }
            if (first.equals("CLUSTER") && clusterNamesNoTable(t)) {
                throw rejected(statement,
                        "CLUSTER without a table cannot run inside a transaction block");
            }
            if (first.equals("CREATE") || first.equals("DROP") || first.equals("ALTER")) {
                checkDdl(statement, first, scratchNamespace);
            }
            if (first.equals("REASSIGN")) {
                throw rejected(statement,
                        "REASSIGN OWNED changes ownership database-wide and cannot be represented in a snapshot");
            }
            if (first.equals("GRANT") || first.equals("REVOKE")) {
                if (!t.contains("ON")) {
                    throw rejected(statement,
                            "role membership grants are database-wide and cannot be represented in a snapshot");
                }
                if (scratchNamespace != null) {
                    checkGrantTarget(statement, scratchNamespace);
                }
            }
            if (first.equals("COMMENT") || first.equals("SECURITY")) {
                checkCommentTarget(statement, scratchNamespace);
            }
            if (first.equals("REINDEX")) {
                checkReindex(statement);
            }
            if (scratchNamespace != null) {
                // SELECT INTO / CREATE TABLE AS-style INTO at top level —
                // also reachable through CREATE VIEW v AS SELECT ... INTO;
                // INSERT INTO / MERGE INTO name a write target, which is a
                // confined data effect (3.2 rule 4), not an object creation
                final var into = indexAtDepthZero(t, "INTO");
                if (into >= 0
                        && !(into > 0 && (t.get(into - 1).equals("INSERT")
                                || t.get(into - 1).equals("MERGE")))) {
                    checkNameAt(statement,
                            pastModifiers(t, into + 1, SELECT_INTO_MODIFIERS),
                            scratchNamespace);
                }
                if (evaluatesNow(t, first)) {
                    checkSequenceCalls(statement, collapseEscapes(statement.text()),
                            scratchNamespace);
                }
                checkScratchLiteral(statement, scratchNamespace);
            }
            if (SESSION_STATE.contains(first)) {
                throw rejected(statement,
                        first + " is session state and must not appear in the script");
            }
            if (first.equals("IMPORT") && scratchNamespace != null) {
                final var into = t.indexOf("INTO");
                if (into >= 0) {
                    checkSchemaName(statement, into + 1, scratchNamespace);
                }
            }
            if (first.equals("SET") && t.size() < 2) {
                throw rejected(statement, "SET names no setting");
            }
            if (first.equals("SET")
                    && !(t.get(1).equals("LOCAL") || t.get(1).equals("TRANSACTION")
                            || t.get(1).equals("CONSTRAINTS"))) {
                throw rejected(statement,
                        "session-level SET leaks onto the connection; use SET LOCAL");
            }
            if (first.equals("RESET") || first.equals("LOAD")) {
                throw rejected(statement,
                        first + " changes session state and must not appear in the script");
            }
            if (t.stream().map(PgScriptCheck::unquote).anyMatch("SET_CONFIG"::equals)) {
                checkSetConfig(statement, collapseEscapes(statement.text()));
            }
            if (first.equals("DO")) {
                checkDoBody(statement, scratchNamespace);
            }
            checkBannedCalls(statement);
            if (first.equals("REPACK")
                    && (t.contains("CONCURRENTLY") || repackNamesNoTable(t))) {
                throw rejected(statement,
                        "REPACK without a table, or with CONCURRENTLY,"
                                + " cannot run inside a transaction block");
            }
            if (first.equals("WAIT")) {
                throw rejected(statement,
                        "WAIT FOR LSN is a session-level replication operation and must not appear in the script");
            }
        }
    }

    /**
     * A {@code DO} block executes during the replay, so its body gets the
     * non-transactional scans top-level statements get: {@code set_config}
     * and sequence calls matched on the whole body text (with {@code ''}
     * escapes collapsed — spec §8 rule 5 scans literals deliberately, they
     * are what an {@code EXECUTE} hands to the executor), banned calls on
     * the tokenized inner statements, and the object-creation check for
     * table-creating {@code INTO} forms. {@code EXECUTE '...'} literals
     * inside the body recurse through the full statement check — dynamic
     * SQL can carry anything. Transactional effects inside (foreign-table
     * writes, CALLs) are confined per 3.2 rule 4 and stay legal.
     */
    private static void checkDoBody(PgSql.StatementTokens statement, String scratchNamespace) {
        final var text = collapseEscapes(statement.text());
        checkSetConfig(statement, text);
        if (scratchNamespace == null) {
            return;
        }
        checkSequenceCalls(statement, text, scratchNamespace);
        for (final var body : PgSql.literalBodies(statement.text())) {
            for (final var inner : PgSql.statements(collapseEscapes(body))) {
                final var innerTokens = inner.tokens();
                // in PL/pgSQL an INTO not led by TEMP/TEMPORARY/UNLOGGED/TABLE is
                // variable assignment; the modifier-led forms run a table-creating
                // SELECT INTO whose target is checked
                for (var i = 0; i + 1 < innerTokens.size(); i++) {
                    if (innerTokens.get(i).equals("INTO")
                            && SELECT_INTO_MODIFIERS.contains(innerTokens.get(i + 1))) {
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
     * {@code (} after the name, so a bare identifier (a column or object
     * reference, a name inside a literal or comment) is not an invocation
     * and must not reject. Token-based, so comments and quoted literals are
     * already gone; inside {@code DO} bodies the same check runs on the
     * body's inner statements.
     */
    private static void checkBannedCalls(PgSql.StatementTokens statement) {
        final var t = statement.tokens();
        for (var i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i + 1).equals("(")) {
                continue;
            }
            final var fn = unquote(t.get(i));
            if (SESSION_LOCKS.contains(fn)) {
                throw rejected(statement,
                        "session-level advisory locks leak onto the pooled connection and are not allowed");
            }
            if (NON_TXN_FUNCTIONS.contains(fn)) {
                throw rejected(statement,
                        "its effect is not transactional or hits other sessions; it must not"
                                + " appear in the script (3.2 rule 1)");
            }
        }
    }

    /**
     * The CREATE/DROP/ALTER rules: object-kind resolution, the always-rejected
     * kinds (namespace DDL, database-wide and non-transactional objects,
     * {@code CONCURRENTLY} forms), then the qualified-name checks for the head
     * object, {@code ON}/{@code TO} anchors, comma-separated object lists, and
     * ALTER sub-command operands.
     */
    private static void checkDdl(PgSql.StatementTokens statement, String first,
                                 String scratchNamespace) {
        final var t = statement.tokens();
        if (kindIndex(t) >= t.size()) {
            throw rejected(statement, first + " names no object kind");
        }
        final var object = objectWord(t);
        if (object.equals("SCHEMA")) {
            throw rejected(statement,
                    first + " SCHEMA is not allowed; the core owns namespace creation");
        }
        // CREATE/DROP DATABASE/TABLESPACE and ALTER SYSTEM cannot run inside a
        // transaction block at all; the remaining forms of those objects are
        // transaction-legal but are database-wide objects below
        if (object.equals("SYSTEM")
                || ((object.equals("DATABASE") || object.equals("TABLESPACE"))
                        && !first.equals("ALTER"))) {
            throw rejected(statement,
                    first + " " + object + " cannot run inside a transaction block");
        }
        if (object.equals("DATABASE") || object.equals("SUBSCRIPTION")
                || object.equals("TABLESPACE")) {
            throw rejected(statement,
                    first + " " + object + " is a database-wide object"
                            + " that cannot be represented in a namespace snapshot");
        }
        if (DATABASE_WIDE_OBJECTS.contains(object)
                || (object.equals("DATA") && t.contains("WRAPPER"))) {
            throw rejected(statement,
                    first + " " + object
                            + " creates a database-wide object that cannot be represented in a namespace snapshot");
        }
        if (t.contains("CONCURRENTLY")) {
            // CREATE/DROP INDEX CONCURRENTLY and ALTER ... DETACH PARTITION
            // CONCURRENTLY — the only CONCURRENTLY forms of these statements
            throw rejected(statement,
                    first + " " + object + " CONCURRENTLY cannot run inside a transaction block");
        }
        if (first.equals("ALTER") && adjacent(t, "SET", "SCHEMA")) {
            throw rejected(statement,
                    "ALTER ... SET SCHEMA is not allowed; the core owns namespace creation");
        }
        if (first.equals("ALTER") && object.equals("INDEX") && t.contains("ALL")) {
            throw rejected(statement,
                    "ALTER INDEX ALL IN TABLESPACE is database-wide"
                            + " and cannot be represented in a snapshot");
        }
        if (scratchNamespace == null) {
            return;
        }
        final var name = nameIndex(t);
        checkNameAt(statement, name, scratchNamespace);
        if (ON_ANCHORED.contains(object)) {
            checkOnTarget(statement, t.indexOf("ON"), scratchNamespace);
        }
        if (first.equals("CREATE") && object.equals("RULE")) {
            checkOnTarget(statement, t.indexOf("TO"), scratchNamespace);
        }
        if (object.equals("CAST")) {
            // (source AS target) is the cast's name — a qualified type
            // anchors the cast to that type's namespace
            final var open = t.indexOf("(");
            if (open >= 0) {
                final var close = pastParen(t, open);
                for (var i = open + 1; i + 1 < close - 1; i++) {
                    if (t.get(i + 1).equals(".")) {
                        checkQualifierAt(statement, i, scratchNamespace);
                    }
                }
            }
        }
        if (object.equals("TRANSFORM")) {
            // the type after FOR anchors the transform's namespace
            final var type = t.indexOf("FOR") + 1;
            if (type > 0) {
                checkQualifierAt(statement, type, scratchNamespace);
            }
        }
        if (first.equals("DROP")) {
            checkNameList(statement, name, scratchNamespace);
        }
        if (first.equals("ALTER") && (object.equals("TABLE") || object.equals("INDEX"))) {
            // ALTER sub-commands whose operand is an object that gets mutated —
            // a partition attaches into (or detaches from) the named table.
            // INHERIT/OF/PARTITION OF name references instead: a foreign parent
            // or composite type is a legitimate external dependency (5.8.3).
            checkQualifiedAfter(statement, t, "ATTACH", "PARTITION", scratchNamespace);
            checkQualifiedAfter(statement, t, "DETACH", "PARTITION", scratchNamespace);
        }
        if (object.equals("EXTENSION")) {
            if (!first.equals("CREATE")) {
                throw rejected(statement, first + " EXTENSION mutates a database-wide"
                        + " object that cannot be represented in a namespace snapshot");
            }
            final var schema = t.indexOf("SCHEMA");
            if (schema >= 0) {
                checkSchemaName(statement, schema + 1, scratchNamespace);
            }
        }
    }

    /**
     * The REINDEX rules: {@code CONCURRENTLY} and the {@code DATABASE}/
     * {@code SCHEMA}/{@code SYSTEM} forms cannot run in a transaction block.
     * A qualified {@code TABLE}/{@code INDEX} name only selects which object
     * gets rebuilt — reindexing is transaction-safe, so the effect is
     * confined to the scratch connection (3.2 rule 4).
     */
    private static void checkReindex(PgSql.StatementTokens statement) {
        final var t = statement.tokens();
        if (t.contains("CONCURRENTLY")) {
            throw rejected(statement,
                    "REINDEX CONCURRENTLY cannot run inside a transaction block");
        }
        final var objectIndex = reindexObjectIndex(t);
        final var object = objectIndex < 0 ? "" : t.get(objectIndex);
        if (object.equals("DATABASE") || object.equals("SCHEMA") || object.equals("SYSTEM")) {
            throw rejected(statement,
                    "REINDEX " + object + " cannot run inside a transaction block");
        }
    }

    /** Statement heads whose statement body is evaluated immediately. */
    private static final Set<String> EXECUTING_FIRST = Set.of(
            "SELECT", "WITH", "VALUES", "TABLE", "INSERT", "UPDATE", "DELETE",
            "MERGE", "CALL");

    /**
     * Whether {@code statement} evaluates expressions now — a query, DML, or
     * {@code CALL} runs its expressions during the desired-script replay, but
     * DDL only stores them (a {@code nextval} inside a {@code CREATE
     * FUNCTION} body is an opaque deferred body like {@code REFERENCES},
     * per spec §8 rule 5). The exception: {@code CREATE TABLE AS} and
     * {@code CREATE MATERIALIZED VIEW} populate from their query at
     * creation, and {@code EXPLAIN ANALYZE} really executes its inner
     * statement.
     */
    private static boolean evaluatesNow(List<String> t, String first) {
        if (EXECUTING_FIRST.contains(first)) {
            return true;
        }
        if (first.equals("EXPLAIN")) {
            return t.contains("ANALYZE");
        }
        if (first.equals("CREATE") && indexAtDepthZero(t, "AS") >= 0) {
            final var object = objectWord(t);
            return object.equals("TABLE")
                    || (object.equals("VIEW") && t.contains("MATERIALIZED"));
        }
        return false;
    }

    /**
     * The literal-argument check for {@code nextval}/{@code setval}: the
     * sequence name is a string like {@code 'schema.seq'}, so the qualifier
     * has to be parsed out of the literal's content. Every literal in the
     * first argument is inspected — {@code CAST('o.s' AS regclass)},
     * {@code to_regclass('o.s')}, {@code E'…'} and dollar-quoted forms all
     * wrap the same qualified name the bare literal carries.
     */
    private static void checkSequenceCalls(PgSql.StatementTokens statement, String text,
                                           String scratchNamespace) {
        final var matcher = SEQUENCE_CALL.matcher(text.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            final var args = callArguments(text, matcher.end());
            if (args == null || args.isEmpty()) {
                continue;
            }
            for (final var literal : PgSql.literalBodies(args.get(0))) {
                final var qualifier = literalQualifier(literal);
                if (qualifier != null && !isScratchOrPgTemp(qualifier, scratchNamespace)) {
                    throw rejected(statement, "'" + qualifier + "…' names a sequence in another"
                            + " namespace; sequence values do not roll back (3.2 rule 1)");
                }
            }
        }
    }

    /**
     * A string literal that IS the scratch name — {@code '"rcl_x"'} or
     * {@code 'rcl_x'} — is the residue of a bare {@code ${namespace}} token
     * placed inside a literal (3.2 substitutes the token textually). The
     * baked name differs on every run, so the object could never converge;
     * reject like rule 5. A literal that carries the scratch name as a
     * qualifier ({@code 'rcl_x.seq'}) stays legal.
     */
    private static void checkScratchLiteral(PgSql.StatementTokens statement,
                                            String scratchNamespace) {
        final var quotedScratch = PgIdentifiers.quote(scratchNamespace);
        for (final var body : PgSql.literalBodies(statement.text())) {
            if (body.equals(scratchNamespace) || body.equals(quotedScratch)) {
                throw rejected(statement, "'" + body
                        + "' is the scratch namespace substituted into a literal;"
                        + " a bare ${namespace} inside a literal bakes an unstable name");
            }
        }
    }

    /**
     * The namespace qualifier of a literal like {@code schema.name},
     * {@code "schema"."name"} or {@code schema."name"} — in token form
     * (quoted verbatim, unquoted upper-cased) so {@link #isScratchOrPgTemp}
     * applies. {@code null} when the literal is an unqualified name.
     */
    private static String literalQualifier(String literal) {
        if (literal == null) {
            return null;
        }
        if (literal.startsWith("\"")) {
            final var qualifier = new StringBuilder(literal.length());
            var i = 1;
            while (i < literal.length()) {
                final var c = literal.charAt(i);
                if (c == '"') {
                    if (i + 1 < literal.length() && literal.charAt(i + 1) == '"') {
                        qualifier.append('"');
                        i += 2;
                        continue;
                    }
                    return i + 1 < literal.length() && literal.charAt(i + 1) == '.'
                            ? "\"" + qualifier + "\"" : null;
                }
                qualifier.append(c);
                i++;
            }
            return null;
        }
        final var dot = literal.indexOf('.');
        return dot < 0 ? null : literal.substring(0, dot).toUpperCase(Locale.ROOT);
    }

    /**
     * {@code EXECUTE '…'} inside a body is dynamic SQL — every literal of
     * the EXECUTE statement (a {@code format()} template argument included)
     * recurses through the full check.
     */
    private static void checkExecuteLiterals(PgSql.StatementTokens statement,
                                             String scratchNamespace) {
        if (statement.tokens().contains("EXECUTE")) {
            for (final var sql : PgSql.literalBodies(statement.text())) {
                check(collapseEscapes(sql), scratchNamespace);
            }
        }
    }

    /** The index after the run of {@code modifiers} starting at {@code from}. */
    private static int pastModifiers(List<String> t, int from, Set<String> modifiers) {
        var i = from;
        while (i < t.size() && modifiers.contains(t.get(i))) {
            i++;
        }
        return i;
    }

    /** The index right after the parenthesized group opening at {@code open}. */
    private static int pastParen(List<String> t, int open) {
        var depth = 0;
        for (var i = open; i < t.size(); i++) {
            if (t.get(i).equals("(")) {
                depth++;
            } else if (t.get(i).equals(")") && --depth == 0) {
                return i + 1;
            }
        }
        return t.size();
    }

    /**
     * {@code CLUSTER} names no table after its {@code (options)} or
     * {@code VERBOSE} lead-ins — the bare form re-clusters every table and
     * cannot run inside a transaction block; {@code CLUSTER t} is confined.
     */
    private static boolean clusterNamesNoTable(List<String> t) {
        var i = 1;
        if (i < t.size() && t.get(i).equals("(")) {
            i = pastParen(t, i);
        }
        if (i < t.size() && t.get(i).equals("VERBOSE")) {
            i++;
        }
        return i >= t.size();
    }

    /**
     * {@code REPACK} (PostgreSQL 19+) names no table after its
     * {@code (options)} list — bare {@code REPACK} and
     * {@code REPACK … USING INDEX} repack every maintained table and cannot
     * run inside a transaction block; {@code REPACK t} is confined.
     */
    private static boolean repackNamesNoTable(List<String> t) {
        var i = 1;
        if (i < t.size() && t.get(i).equals("(")) {
            i = pastParen(t, i);
        }
        return i >= t.size() || t.get(i).equals("USING");
    }

    /**
     * {@code ''} escapes collapsed to {@code '}: inside a {@code DO} literal
     * an embedded string arrives doubled, and every level of nesting doubles
     * it again — collapsing until stable reveals the innermost SQL text.
     */
    private static String collapseEscapes(String text) {
        var collapsed = text;
        while (collapsed.contains("''")) {
            collapsed = collapsed.replace("''", "'");
        }
        return collapsed;
    }

    /**
     * The upper-cased name a token refers to: an upper-cased keyword for
     * plain tokens, the identifier's content for a quoted token — so a
     * quoted {@code "pg_advisory_lock"} function name cannot evade the
     * session-lock check.
     */
    private static String unquote(String token) {
        return token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")
                ? token.substring(1, token.length() - 1).replace("\"\"", "\"")
                        .toUpperCase(Locale.ROOT)
                : token;
    }

    /**
     * Every {@code set_config(name, value, is_local)} call in {@code text}
     * (string literals included, so {@code EXECUTE '...'} is covered) must
     * pass the literal {@code true} as its third argument; any other form —
     * {@code false}, an expression, fewer arguments — is rejected.
     */
    private static void checkSetConfig(PgSql.StatementTokens statement, String text) {
        final var matcher = SET_CONFIG_CALL.matcher(text.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            // the word without a parenthesized call isn't a call — a column,
            // literal or comment mention must not reject
            if (!opensCall(text, matcher.end())) {
                continue;
            }
            final var args = callArguments(text, matcher.end());
            if (args == null || args.size() != 3 || !args.get(2).strip().equalsIgnoreCase("true")) {
                throw rejected(statement,
                        "set_config must pass the literal true as is_local: a session-level"
                                + " setting leaks onto the pooled connection");
            }
        }
    }

    /**
     * Whether a (possibly quoted) function name ending at {@code from} opens a
     * parenthesized call — mirrors {@link #callArguments}' lookahead.
     */
    private static boolean opensCall(String text, int from) {
        var i = skipCallGap(text, from);
        if (i < text.length() && text.charAt(i) == '"') {
            i = skipCallGap(text, i + 1);
        }
        return i < text.length() && text.charAt(i) == '(';
    }

    /**
     * Skips the gap between a function name and its opening parenthesis:
     * whitespace and comments — {@code --} to end of line and (nesting)
     * {@code /* ... *\/} — so {@code nextval --x\n ('s.t')} and {@code
     * setval /*c*\/ ('s.t', 1)} cannot evade the call checks.
     */
    private static int skipCallGap(String text, int from) {
        var i = from;
        for (;;) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i + 1 < text.length() && text.charAt(i) == '-' && text.charAt(i + 1) == '-') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (i + 1 < text.length()
                    && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
                var depth = 1;
                i += 2;
                while (i < text.length() && depth > 0) {
                    if (i + 1 < text.length()
                            && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (i + 1 < text.length()
                            && text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
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

    /** The top-level comma-separated arguments of the call whose name ends at {@code from}, or null. */
    private static List<String> callArguments(String text, int from) {
        var i = skipCallGap(text, from);
        // a quoted "set_config" ends at the closing quote before the '('
        if (i < text.length() && text.charAt(i) == '"') {
            i = skipCallGap(text, i + 1);
        }
        if (i >= text.length() || text.charAt(i) != '(') {
            return null;
        }
        final var args = new ArrayList<String>();
        var depth = 0;
        var start = i + 1;
        for (; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (c == '\'') {
                i++;
                while (i < text.length()
                        && !(text.charAt(i) == '\'' && (i + 1 >= text.length() || text.charAt(i + 1) != '\''))) {
                    i += text.charAt(i) == '\'' ? 2 : 1;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    args.add(text.substring(start, i));
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(text.substring(start, i));
                start = i + 1;
            }
        }
        return null;
    }

    /**
     * Rejects when the token at {@code index} begins a {@code qualifier.name}
     * pair whose qualifier is not the scratch namespace. {@code pg_temp} is
     * allowed: temporary objects are session-confined like the scratch
     * namespace itself.
     */
    private static void checkQualifierAt(PgSql.StatementTokens statement, int index,
                                         String scratchNamespace) {
        final var t = statement.tokens();
        if (index + 2 >= t.size() || !t.get(index + 1).equals(".")) {
            return;
        }
        final var qualifier = t.get(index);
        if (isScratchOrPgTemp(qualifier, scratchNamespace)) {
            return;
        }
        throw rejected(statement, "'" + qualifier + "." + t.get(index + 2)
                + "' names an object in another namespace; objects must be created"
                + " inside ${namespace} or unqualified (3.2 rule 1)");
    }

    /** Rejects a bare schema name that is not the scratch namespace. */
    private static void checkSchemaName(PgSql.StatementTokens statement, int index,
                                        String scratchNamespace) {
        final var t = statement.tokens();
        if (index >= t.size() || isScratchOrPgTemp(t.get(index), scratchNamespace)) {
            return;
        }
        throw rejected(statement, "'" + t.get(index)
                + "' names another namespace; schema-level grants are only allowed"
                + " on ${namespace} itself (3.2 rule 1)");
    }

    /**
     * True when {@code token} names the scratch namespace or the session's
     * {@code pg_temp}. A quoted token (its quotes are preserved) compares
     * case-sensitively — {@code "S".t} is a different schema from
     * {@code s.t}; an unquoted token compares case-insensitively, which is
     * sound because the scratch name is fold-safe — it is already its own
     * lowercase fold ({@link PgIdentifiers#scratchName}).
     */
    private static boolean isScratchOrPgTemp(String token, String scratchNamespace) {
        if (token.startsWith("\"")) {
            return token.equals(PgIdentifiers.quote(scratchNamespace))
                    || token.equals("\"pg_temp\"");
        }
        return token.equalsIgnoreCase(scratchNamespace) || token.equals("PG_TEMP");
    }

    /**
     * The qualified-name check for the operand after {@code keyword [second]} —
     * ALTER sub-commands (ATTACH/DETACH PARTITION) whose operand may name an
     * object in another namespace.
     */
    private static void checkQualifiedAfter(PgSql.StatementTokens statement, List<String> t,
                                            String keyword, String second,
                                            String scratchNamespace) {
        for (var i = 0; i + 1 < t.size(); i++) {
            if (t.get(i).equals(keyword) && t.get(i + 1).equals(second)) {
                checkQualifierAt(statement, i + 2, scratchNamespace);
            }
        }
    }

    /** The object-name index of a CREATE/DROP/ALTER statement: kind word + modifiers. */
    private static int nameIndex(List<String> t) {
        var i = kindIndex(t) + 1;
        while (i < t.size() && NAME_MODIFIERS.contains(t.get(i))) {
            i++;
        }
        return i;
    }

    /**
     * The qualified-name check at an object-name position found by skipping
     * modifiers. When the skipped word itself was the qualifier — {@code
     * CREATE TABLE exists.evil}, where {@code exists} is an unreserved
     * keyword the modifier scan treats as a modifier — the scan lands on the
     * dot, and the token before it is the qualifier to check.
     */
    private static void checkNameAt(PgSql.StatementTokens statement, int index,
                                    String scratchNamespace) {
        final var t = statement.tokens();
        if (index > 0 && index < t.size() && t.get(index).equals(".")) {
            checkQualifierAt(statement, index - 1, scratchNamespace);
            return;
        }
        checkQualifierAt(statement, index, scratchNamespace);
    }

    /** The index of the object-kind word, skipping CREATE/DROP/ALTER modifiers. */
    private static int kindIndex(List<String> t) {
        var i = 1;
        while (i < t.size() && HEAD_MODIFIERS.contains(t.get(i))) {
            i++;
        }
        return i;
    }

    /** The qualified-name check for the object after {@code ON} (index/table). */
    private static void checkOnTarget(PgSql.StatementTokens statement, int onIndex,
                                      String scratchNamespace) {
        if (onIndex < 0) {
            return;
        }
        var i = onIndex + 1;
        if (i < statement.tokens().size() && statement.tokens().get(i).equals("ONLY")) {
            i++;
        }
        checkNameAt(statement, i, scratchNamespace);
    }

    /**
     * The qualified-name check for a GRANT/REVOKE object: database-wide
     * targets are rejected outright, {@code ON SCHEMA} and {@code ON ALL ...
     * IN SCHEMA} take a namespace name, object kinds take a possibly
     * qualified object name.
     */
    private static void checkGrantTarget(PgSql.StatementTokens statement,
                                         String scratchNamespace) {
        final var t = statement.tokens();
        final var on = t.indexOf("ON");
        var i = on + 1;
        if (i >= t.size()) {
            return;
        }
        final var w = t.get(i);
        // ON FOREIGN TABLE grants are namespace-scoped; FOREIGN SERVER and
        // FOREIGN DATA WRAPPER are database-wide
        final var foreignTable = w.equals("FOREIGN") && i + 1 < t.size()
                && t.get(i + 1).equals("TABLE");
        if (GRANT_DATABASE_WIDE.contains(w) && !foreignTable) {
            throw rejected(statement, "ON " + w
                    + " is a database-wide grant and cannot be represented in a snapshot");
        }
        if (w.equals("ALL")) {
            final var schema = t.indexOf("SCHEMA");
            if (schema >= 0) {
                checkSchemaList(statement, schema + 1, scratchNamespace);
            }
            return;
        }
        if (w.equals("SCHEMA")) {
            checkSchemaList(statement, i + 1, scratchNamespace);
            return;
        }
        if (foreignTable || GRANT_KINDS.contains(w)) {
            i += foreignTable ? 2 : 1;
        }
        checkQualifierAt(statement, i, scratchNamespace);
        checkNameList(statement, i, scratchNamespace);
    }

    /**
     * The bare-schema-name check for a {@code ,}-separated list — {@code ON
     * SCHEMA a, b} and {@code ON ALL TABLES IN SCHEMA a, b} — bounded by the
     * grantee clause (TO in GRANT, FROM in REVOKE) so role lists behind it
     * are not mistaken for schema names.
     */
    private static void checkSchemaList(PgSql.StatementTokens statement, int index,
                                        String scratchNamespace) {
        final var t = statement.tokens();
        var end = t.size();
        for (var j = index; j < t.size(); j++) {
            if (t.get(j).equals("TO") || t.get(j).equals("FROM")) {
                end = j;
                break;
            }
        }
        checkSchemaName(statement, index, scratchNamespace);
        for (var j = index; j + 1 < end; j++) {
            if (t.get(j).equals(",")) {
                checkSchemaName(statement, j + 1, scratchNamespace);
            }
        }
    }

    /**
     * The qualified-name check for every name of a top-level {@code
     * ,}-separated list — a {@code DROP TABLE a, s.b} or {@code GRANT ON
     * TABLE a, s.b} carries several objects. Commas inside parentheses are
     * signature arguments, not list separators.
     */
    private static void checkNameList(PgSql.StatementTokens statement, int fromIndex,
                                      String scratchNamespace) {
        final var t = statement.tokens();
        var depth = 0;
        for (var i = fromIndex + 1; i < t.size(); i++) {
            final var w = t.get(i);
            if (w.equals("(")) {
                depth++;
            } else if (w.equals(")")) {
                depth--;
            } else if (depth == 0 && w.equals(",")) {
                checkQualifierAt(statement, i + 1, scratchNamespace);
            }
        }
    }

    /**
     * The qualified-name check for COMMENT ON / SECURITY LABEL ON. Database-wide
     * kinds are rejected outright and {@code ON SCHEMA} takes a namespace name;
     * the rest of the target is scanned between {@code ON} and {@code IS}: a
     * {@code q.n.m} triple always exposes namespace qualifier {@code q}, and a
     * two-part {@code q.n} is a qualifier for every kind except {@code COLUMN},
     * where it is a {@code table.column} pair.
     */
    private static void checkCommentTarget(PgSql.StatementTokens statement,
                                           String scratchNamespace) {
        final var t = statement.tokens();
        final var on = t.indexOf("ON");
        final var is = t.indexOf("IS");
        if (on < 0 || is < 0 || on + 1 >= t.size()) {
            return;
        }
        final var kind = t.get(on + 1);
        if (COMMENT_DATABASE_WIDE.contains(kind)
                || (kind.equals("FOREIGN") && on + 2 < t.size()
                        && (t.get(on + 2).equals("SERVER") || t.get(on + 2).equals("DATA")))) {
            throw rejected(statement, kind
                    + " has no namespace anchor; a comment or security label on it"
                    + " can never appear in a snapshot (3.2 rule 1)");
        }
        if (scratchNamespace == null) {
            return;
        }
        if (kind.equals("SCHEMA")) {
            checkSchemaName(statement, on + 2, scratchNamespace);
            return;
        }
        final var column = kind.equals("COLUMN");
        var end = is;
        if (SIGNATURE_TARGET_KINDS.contains(kind)) {
            for (var i = on + 1; i < is; i++) {
                if (t.get(i).equals("(")) {
                    end = i;
                    break;
                }
            }
        }
        for (var i = on + 1; i + 2 < end; i++) {
            if (!t.get(i + 1).equals(".")) {
                continue;
            }
            if (i + 4 < end && t.get(i + 3).equals(".")) {
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

    /** True when {@code a} is immediately followed by {@code b} in the token list. */
    private static boolean adjacent(List<String> tokens, String a, String b) {
        for (var i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).equals(a) && tokens.get(i + 1).equals(b)) {
                return true;
            }
        }
        return false;
    }

    /** The object-kind word after CREATE/DROP/ALTER and its modifiers, or empty. */
    private static String objectWord(List<String> tokens) {
        final var i = kindIndex(tokens);
        return i < tokens.size() ? tokens.get(i) : "";
    }

    /**
     * The object-kind index of a REINDEX statement — TABLE, INDEX, SCHEMA,
     * DATABASE or SYSTEM — skipping the {@code (...)} option list and the
     * CONCURRENTLY/VERBOSE modifiers of the legacy syntax.
     */
    private static int reindexObjectIndex(List<String> tokens) {
        var depth = 0;
        for (var i = 1; i < tokens.size(); i++) {
            final var w = tokens.get(i);
            if (w.equals("(")) {
                depth++;
            } else if (w.equals(")")) {
                depth--;
            } else if (depth == 0 && !w.equals("CONCURRENTLY") && !w.equals("VERBOSE")) {
                return i;
            }
        }
        return -1;
    }

    /** The index of {@code word} at parenthesis depth 0, or -1. */
    private static int indexAtDepthZero(List<String> tokens, String word) {
        var depth = 0;
        for (var i = 0; i < tokens.size(); i++) {
            final var w = tokens.get(i);
            if (w.equals("(")) {
                depth++;
            } else if (w.equals(")")) {
                depth--;
            } else if (depth == 0 && w.equals(word)) {
                return i;
            }
        }
        return -1;
    }

    private static DesiredStateException rejected(PgSql.StatementTokens statement, String reason) {
        return new DesiredStateException("the desired script was rejected at position "
                + statement.offset() + ": " + reason
                + " (\"" + statement.text().strip() + "\")");
    }
}
