package studio.epicquest.schema.reconciler.postgresql.internal;

import java.util.List;
import java.util.Set;
import studio.epicquest.schema.reconciler.DesiredStateException;

/**
 * Enforces the script constraints of spec 3.2 rules 2–3 for PostgreSQL:
 * no transaction control, no session-level locks, no namespace DDL, no
 * statements that cannot run inside a transaction block, and no DDL for
 * database-wide objects — servers, roles, event triggers and the like have
 * no namespace anchor, so they can never appear in a snapshot and a script
 * creating one would go undetected (5.4). The scan runs on the substituted
 * script — after {@code ${namespace}} has been replaced — so a legitimate
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

    public static void check(String script) {
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
                final var object = objectWord(t);
                if (object.equals("SCHEMA")) {
                    throw rejected(statement,
                            first + " SCHEMA is not allowed; the core owns namespace creation");
                }
                if (object.equals("DATABASE") || object.equals("TABLESPACE")
                        || object.equals("SUBSCRIPTION") || object.equals("PUBLICATION")
                        || object.equals("SYSTEM")) {
                    throw rejected(statement,
                            first + " " + object + " cannot run inside a transaction block");
                }
                if (object.equals("SERVER") || object.equals("ROLE") || object.equals("USER")
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
            }
            if (first.equals("REASSIGN")) {
                throw rejected(statement,
                        "REASSIGN OWNED changes ownership database-wide and cannot be represented in a snapshot");
            }
            if ((first.equals("GRANT") || first.equals("REVOKE")) && !t.contains("ON")) {
                throw rejected(statement,
                        "role membership grants are database-wide and cannot be represented in a snapshot");
            }
            if (first.equals("CREATE") && t.contains("CONCURRENTLY")) {
                throw rejected(statement,
                        "CREATE INDEX CONCURRENTLY cannot run inside a transaction block");
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

    /** True when {@code a} is immediately followed by {@code b} in the token list. */
    private static boolean adjacent(List<String> tokens, String a, String b) {
        for (var i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).equals(a) && tokens.get(i + 1).equals(b)) {
                return true;
            }
        }
        return false;
    }

    /** The first token after CREATE/DROP/ALTER, skipping modifiers like UNIQUE, OR, REPLACE, IF, NOT, EXISTS, CONCURRENTLY. */
    private static String objectWord(List<String> tokens) {
        for (var i = 1; i < tokens.size(); i++) {
            final var w = tokens.get(i);
            if (w.equals("UNIQUE") || w.equals("OR") || w.equals("REPLACE") || w.equals("IF")
                    || w.equals("NOT") || w.equals("EXISTS") || w.equals("TEMP")
                    || w.equals("TEMPORARY") || w.equals("UNLOGGED") || w.equals("GLOBAL")
                    || w.equals("LOCAL") || w.equals("MATERIALIZED") || w.equals("RECURSIVE")
                    || w.equals("FOREIGN") || w.equals("TRUSTED") || w.equals("UNTRUSTED")
                    || w.equals("PROCEDURAL")) {
                continue;
            }
            return w;
        }
        return "";
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
