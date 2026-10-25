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

    private static final List<String> TRANSACTION_CONTROL = List.of(
            "BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE",
            "END", "ABORT", "PREPARE");

    private static final List<String> NON_TRANSACTIONAL = List.of(
            "VACUUM", "CHECKPOINT", "CLUSTER", "DISCARD");

    /**
     * Session-level advisory lock functions (spec 3.2 rule 3). The
     * transaction-scoped {@code pg_advisory_xact_*} variants are allowed:
     * they are released by the scratch rollback. Unlock functions acquire
     * nothing, so they are not banned either.
     */
    private static final Set<String> SESSION_LOCKS = Set.of(
            "PG_ADVISORY_LOCK", "PG_ADVISORY_LOCK_SHARED",
            "PG_TRY_ADVISORY_LOCK", "PG_TRY_ADVISORY_LOCK_SHARED");

    private static final Pattern SET_CONFIG_CALL = Pattern.compile("\\bSET_CONFIG\\b");
    private static final Pattern SESSION_LOCK_CALL = Pattern.compile(
            "\\bPG_(TRY_)?ADVISORY_LOCK(_SHARED)?\\b");

    /** CREATE/DROP/ALTER head modifiers skipped before the object-kind word. */
    private static final Set<String> HEAD_MODIFIERS = Set.of(
            "UNIQUE", "OR", "REPLACE", "IF", "NOT", "EXISTS", "TEMP",
            "TEMPORARY", "UNLOGGED", "GLOBAL", "LOCAL", "MATERIALIZED",
            "RECURSIVE", "FOREIGN", "TRUSTED", "UNTRUSTED", "PROCEDURAL",
            "CONSTRAINT");

    /** Tokens between the object-kind word and the object name. */
    private static final Set<String> NAME_MODIFIERS = Set.of(
            "IF", "NOT", "EXISTS", "ONLY", "CONCURRENTLY");

    /** Objects whose name anchors a second object after {@code ON}. */
    private static final Set<String> ON_ANCHORED = Set.of(
            "INDEX", "TRIGGER", "POLICY");

    /** GRANT/REVOKE object kinds that take a namespace-scoped name. */
    private static final Set<String> GRANT_KINDS = Set.of(
            "TABLE", "SEQUENCE", "FUNCTION", "PROCEDURE", "ROUTINE",
            "DOMAIN", "TYPE", "STATISTICS");

    /** GRANT/REVOKE targets with no namespace anchor. */
    private static final Set<String> GRANT_DATABASE_WIDE = Set.of(
            "DATABASE", "TABLESPACE", "ROLE", "USER", "GROUP", "LARGE",
            "FOREIGN", "LANGUAGE", "PARAMETER", "SERVER");

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
            if (first.equals("CREATE") || first.equals("DROP") || first.equals("ALTER")) {
                if (kindIndex(t) >= t.size()) {
                    throw rejected(statement, first + " names no object kind");
                }
                final var object = objectWord(t);
                if (object.equals("SCHEMA")) {
                    throw rejected(statement,
                            first + " SCHEMA is not allowed; the core owns namespace creation");
                }
                if (object.equals("DATABASE") || object.equals("TABLESPACE")
                        || object.equals("SUBSCRIPTION")
                        || object.equals("SYSTEM")) {
                    throw rejected(statement,
                            first + " " + object + " cannot run inside a transaction block");
                }
                if (object.equals("SERVER") || object.equals("PUBLICATION") || object.equals("ROLE") || object.equals("USER")
                        || object.equals("GROUP") || object.equals("EVENT")
                        || object.equals("ACCESS") || object.equals("DEFAULT")
                        || object.equals("OWNED") || object.equals("LANGUAGE")
                        || (object.equals("DATA") && t.contains("WRAPPER"))) {
                    throw rejected(statement,
                            first + " " + objectWord(t)
                                    + " creates a database-wide object that cannot be represented in a namespace snapshot");
                }
                if (first.equals("ALTER") && adjacent(t, "SET", "SCHEMA")) {
                    throw rejected(statement,
                            "ALTER ... SET SCHEMA is not allowed; the core owns namespace creation");
                }
                if (scratchNamespace != null) {
                    checkQualifierAt(statement, nameIndex(t), scratchNamespace);
                    final var kind = t.get(kindIndex(t));
                    if (ON_ANCHORED.contains(kind)) {
                        checkOnTarget(statement, t.indexOf("ON"), scratchNamespace);
                    }
                }
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
                if (scratchNamespace != null) {
                    checkCommentTarget(statement, scratchNamespace);
                }
            }
            if (first.equals("CREATE") && t.contains("CONCURRENTLY")) {
                throw rejected(statement,
                        "CREATE INDEX CONCURRENTLY cannot run inside a transaction block");
            }
            if (first.equals("DROP") && t.contains("CONCURRENTLY")) {
                throw rejected(statement,
                        "DROP INDEX CONCURRENTLY cannot run inside a transaction block");
            }
            if ((first.equals("REINDEX") || first.equals("REFRESH"))
                    && t.contains("CONCURRENTLY")) {
                throw rejected(statement,
                        first + " CONCURRENTLY cannot run inside a transaction block");
            }
            if (first.equals("REINDEX")) {
                final var reindexObject = reindexObjectWord(t);
                if (reindexObject.equals("DATABASE") || reindexObject.equals("SCHEMA")
                        || reindexObject.equals("SYSTEM")) {
                    throw rejected(statement,
                            "REINDEX " + reindexObject + " cannot run inside a transaction block");
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
            if (t.contains("SET_CONFIG")) {
                checkSetConfig(statement, statement.text());
            }
            if (first.equals("DO")) {
                for (final var body : PgSql.dollarBodies(statement.text())) {
                    checkDoBody(statement, body);
                }
            }
            if (t.stream().anyMatch(SESSION_LOCKS::contains)) {
                throw rejected(statement,
                        "session-level advisory locks leak onto the pooled connection and are not allowed");
            }
            if (first.equals("LISTEN") || first.equals("NOTIFY") || first.equals("UNLISTEN")) {
                throw rejected(statement, first + " is session state and must not appear in the script");
            }
            if (first.equals("REPACK")) {
                throw rejected(statement,
                        "REPACK rewrites table storage without a schema effect; its database-wide,"
                                + " partitioned, CONCURRENTLY and ANALYZE forms cannot run inside a transaction block");
            }
            if (first.equals("WAIT")) {
                throw rejected(statement,
                        "WAIT FOR LSN is a session-level replication operation and must not appear in the script");
            }
        }
    }

    /**
     * A {@code DO} block runs immediately but its body is opaque to the
     * statement scan, so the session-leaking calls are matched on its text,
     * string literals included ({@code EXECUTE '...'}).
     */
    private static void checkDoBody(PgSql.StatementTokens statement, String body) {
        final var upper = body.toUpperCase(Locale.ROOT);
        if (SESSION_LOCK_CALL.matcher(upper).find()) {
            throw rejected(statement,
                    "session-level advisory locks leak onto the pooled connection and are not allowed");
        }
        checkSetConfig(statement, body);
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
            final var args = callArguments(text, matcher.end());
            if (args == null || args.size() != 3 || !args.get(2).strip().equalsIgnoreCase("true")) {
                throw rejected(statement,
                        "set_config must pass the literal true as is_local: a session-level"
                                + " setting leaks onto the pooled connection");
            }
        }
    }

    /** The top-level comma-separated arguments of the call whose name ends at {@code from}, or null. */
    private static List<String> callArguments(String text, int from) {
        var i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
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
        if (qualifier.equalsIgnoreCase(scratchNamespace) || qualifier.equals("PG_TEMP")) {
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
        if (index >= t.size() || t.get(index).equalsIgnoreCase(scratchNamespace)) {
            return;
        }
        throw rejected(statement, "'" + t.get(index)
                + "' names another namespace; schema-level grants are only allowed"
                + " on ${namespace} itself (3.2 rule 1)");
    }

    /** The object-name index of a CREATE/DROP/ALTER statement: kind word + modifiers. */
    private static int nameIndex(List<String> t) {
        var i = kindIndex(t) + 1;
        while (i < t.size() && NAME_MODIFIERS.contains(t.get(i))) {
            i++;
        }
        return i;
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
        checkQualifierAt(statement, i, scratchNamespace);
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
        if (GRANT_DATABASE_WIDE.contains(w)) {
            throw rejected(statement, "ON " + w
                    + " is a database-wide grant and cannot be represented in a snapshot");
        }
        if (w.equals("ALL")) {
            final var schema = t.indexOf("SCHEMA");
            if (schema >= 0) {
                checkSchemaName(statement, schema + 1, scratchNamespace);
            }
            return;
        }
        if (w.equals("SCHEMA")) {
            checkSchemaName(statement, i + 1, scratchNamespace);
            return;
        }
        if (GRANT_KINDS.contains(w)) {
            i++;
        }
        checkQualifierAt(statement, i, scratchNamespace);
    }

    /**
     * The qualified-name check for COMMENT ON / SECURITY LABEL ON: scans
     * the target between {@code ON} and {@code IS}. A {@code q.n.m} triple
     * always exposes namespace qualifier {@code q}; a two-part {@code q.n}
     * is a qualifier for every kind except {@code COLUMN}, where it is a
     * {@code table.column} pair.
     */
    private static void checkCommentTarget(PgSql.StatementTokens statement,
                                           String scratchNamespace) {
        final var t = statement.tokens();
        final var on = t.indexOf("ON");
        final var is = t.indexOf("IS");
        if (on < 0 || is < 0) {
            return;
        }
        final var column = on + 1 < t.size() && t.get(on + 1).equals("COLUMN");
        for (var i = on + 1; i + 2 < is; i++) {
            if (!t.get(i + 1).equals(".")) {
                continue;
            }
            if (i + 4 < is && t.get(i + 3).equals(".")) {
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
     * The object-kind word of a REINDEX statement — TABLE, INDEX, SCHEMA,
     * DATABASE or SYSTEM — skipping the {@code (...)} option list and the
     * CONCURRENTLY/VERBOSE modifiers of the legacy syntax.
     */
    private static String reindexObjectWord(List<String> tokens) {
        var depth = 0;
        for (var i = 1; i < tokens.size(); i++) {
            final var w = tokens.get(i);
            if (w.equals("(")) {
                depth++;
            } else if (w.equals(")")) {
                depth--;
            } else if (depth == 0 && !w.equals("CONCURRENTLY") && !w.equals("VERBOSE")) {
                return w;
            }
        }
        return "";
    }

    private static DesiredStateException rejected(PgSql.StatementTokens statement, String reason) {
        return new DesiredStateException("the desired script was rejected at position "
                + statement.offset() + ": " + reason
                + " (\"" + statement.text().strip() + "\")");
    }
}
