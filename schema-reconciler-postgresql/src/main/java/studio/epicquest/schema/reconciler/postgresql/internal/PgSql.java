package studio.epicquest.schema.reconciler.postgresql.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL-aware SQL text handling: the normalisation of spec 5.3 and the
 * lexical statement scan the script constraints of spec 3.2 need. Both share
 * one lexer that understands {@code '...'} strings (with {@code ''} escape),
 * {@code "..."} quoted identifiers (with {@code ""} escape), {@code $tag$}
 * dollar-quoted bodies and line/block comments.
 */
public final class PgSql {

    private PgSql() {
    }

    /** Normalisation rule 5.3: namespace reference replacement + whitespace collapse. */
    public static String normalize(String text, String namespace) {
        return collapseWhitespace(replaceNamespaceRefs(text, namespace));
    }

    /**
     * Rule 5.3(1): {@code <ns>.} and {@code "<ns>".} become {@code ${namespace}.}
     * at identifier boundaries. The quoted form is matched case-sensitively:
     * {@code "APP"} is a different namespace from {@code app} in PostgreSQL.
     * The unquoted form is matched case-insensitively when {@code ns} is
     * fold-safe (it is its own lowercase fold): unquoted references fold to
     * lowercase, so {@code COREX.t} also means {@code corex}.
     */
    static String replaceNamespaceRefs(String text, String namespace) {
        var alternatives = Pattern.quote("\"" + namespace + "\"") + "\\.";
        if (namespace.equals(namespace.toLowerCase(Locale.ROOT))) {
            alternatives += "|(?i:" + Pattern.quote(namespace) + ")\\.";
        }
        final var pattern = Pattern.compile(
                "(?<![A-Za-z0-9_$.])(?:" + alternatives + ")");
        return pattern.matcher(text)
                .replaceAll(Matcher.quoteReplacement("${namespace}."));
    }

    /**
     * Rule 5.3(2): whitespace runs outside string literals, quoted identifiers
     * and dollar-quoted bodies collapse to one space; the result is trimmed.
     */
    public static String collapseWhitespace(String text) {
        final var out = new StringBuilder(text.length());
        var i = 0;
        final var n = text.length();
        var pendingSpace = false;
        while (i < n) {
            final var span = nextSpan(text, i);
            if (span == null) {
                final var c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    pendingSpace = !out.isEmpty();
                    i++;
                } else {
                    if (pendingSpace) {
                        out.append(' ');
                        pendingSpace = false;
                    }
                    out.append(c);
                    i++;
                }
            } else {
                if (pendingSpace) {
                    out.append(' ');
                    pendingSpace = false;
                }
                out.append(text, i, span[1]);
                i = span[1];
            }
        }
        return out.toString();
    }

    /** A top-level statement with its significant tokens (outside literals). */
    public record StatementTokens(String text, List<String> tokens, int offset) { }

    /**
     * Splits a script into top-level statements (separated by {@code ;}) and
     * extracts each statement's tokens outside string literals, quoted
     * identifiers, dollar-quoted bodies and comments. Tokens are upper-cased
     * keywords/punctuation — enough for the statement checks of spec 3.2.
     */
    public static List<StatementTokens> statements(String script) {
        final var out = new ArrayList<StatementTokens>();
        var stmtStart = 0;
        var i = 0;
        final var n = script.length();
        while (i < n) {
            final var span = nextSpan(script, i);
            if (span != null) {
                i = span[1];
            } else if (script.charAt(i) == ';') {
                addStatement(out, script, stmtStart, i);
                i++;
                stmtStart = i;
            } else {
                i++;
            }
        }
        addStatement(out, script, stmtStart, n);
        return out;
    }

    private static void addStatement(List<StatementTokens> out, String script, int from, int to) {
        final var tokens = new ArrayList<String>();
        var i = from;
        while (i < to) {
            final var span = nextSpan(script, i);
            if (span != null) {
                if (span[2] == SPAN_IDENT_QUOTED) {
                    tokens.add(script.substring(i + 1, span[1] - 1).replace("\"\"", "\""));
                }
                i = span[1];
                continue;
            }
            final var c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (isIdentStart(c)) {
                var j = i + 1;
                while (j < to && isIdentPart(script.charAt(j))) {
                    j++;
                }
                tokens.add(script.substring(i, j).toUpperCase(Locale.ROOT));
                i = j;
            } else if (!isIdentPart(c)) {
                tokens.add(String.valueOf(c));
                i++;
            } else {
                i++;
            }
        }
        if (!tokens.isEmpty()) {
            out.add(new StatementTokens(script.substring(from, to), tokens, from));
        }
    }

    /** The text inside each dollar-quoted body of {@code text} (tags excluded). */
    public static List<String> dollarBodies(String text) {
        final var out = new ArrayList<String>();
        var i = 0;
        while (i < text.length()) {
            final var span = nextSpan(text, i);
            if (span == null) {
                i++;
                continue;
            }
            if (span[2] == SPAN_DOLLAR) {
                final var tag = dollarTag(text, i);
                final var innerEnd = text.startsWith(tag, span[1] - tag.length())
                        && span[1] - tag.length() >= i + tag.length()
                        ? span[1] - tag.length() : span[1];
                out.add(text.substring(i + tag.length(), innerEnd));
            }
            i = span[1];
        }
        return out;
    }

    private static final int SPAN_STRING = 1;
    private static final int SPAN_IDENT_QUOTED = 2;
    private static final int SPAN_DOLLAR = 3;
    private static final int SPAN_COMMENT = 4;

    /**
     * If a literal/comment span starts at {@code pos}, returns
     * {@code [start, end, kind]}; otherwise {@code null}.
     */
    private static int[] nextSpan(String text, int pos) {
        final var c = text.charAt(pos);
        final var n = text.length();
        if (c == '\'') {
            // E'...' strings also escape with a backslash: \' does not end them
            final var escapes = pos > 0 && (text.charAt(pos - 1) == 'E' || text.charAt(pos - 1) == 'e')
                    && (pos < 2 || !isIdentPart(text.charAt(pos - 2)));
            var i = pos + 1;
            while (i < n) {
                if (escapes && text.charAt(i) == '\\') {
                    i += 2;
                    continue;
                }
                if (text.charAt(i) == '\'') {
                    if (i + 1 < n && text.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    return new int[] {pos, i + 1, SPAN_STRING};
                }
                i++;
            }
            return new int[] {pos, n, SPAN_STRING};
        }
        if (c == '"') {
            var i = pos + 1;
            while (i < n) {
                if (text.charAt(i) == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        i += 2;
                        continue;
                    }
                    return new int[] {pos, i + 1, SPAN_IDENT_QUOTED};
                }
                i++;
            }
            return new int[] {pos, n, SPAN_IDENT_QUOTED};
        }
        // a '$' after an identifier character is part of that identifier
        if (c == '$' && (pos == 0 || !isIdentPart(text.charAt(pos - 1)))) {
            final var tag = dollarTag(text, pos);
            if (tag != null) {
                var end = text.indexOf(tag, pos + tag.length());
                return new int[] {pos, end < 0 ? n : end + tag.length(), SPAN_DOLLAR};
            }
        }
        if (c == '-' && pos + 1 < n && text.charAt(pos + 1) == '-') {
            final var end = text.indexOf('\n', pos);
            return new int[] {pos, end < 0 ? n : end, SPAN_COMMENT};
        }
        if (c == '/' && pos + 1 < n && text.charAt(pos + 1) == '*') {
            var depth = 1;
            var i = pos + 2;
            while (i < n && depth > 0) {
                if (i + 1 < n && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
                    depth++;
                    i += 2;
                } else if (i + 1 < n && text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
                    depth--;
                    i += 2;
                } else {
                    i++;
                }
            }
            return new int[] {pos, i, SPAN_COMMENT};
        }
        return null;
    }

    /** The {@code $tag$} opener at {@code pos}, or {@code null}. */
    private static String dollarTag(String text, int pos) {
        final var n = text.length();
        if (pos + 1 < n && text.charAt(pos + 1) == '$') {
            return "$$";
        }
        var i = pos + 1;
        while (i < n && text.charAt(i) != '$') {
            final var c = text.charAt(i);
            if (i == pos + 1 ? !isIdentStart(c) : !isIdentPart(c)) {
                return null;
            }
            i++;
        }
        return i < n ? text.substring(pos, i + 1) : null;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
