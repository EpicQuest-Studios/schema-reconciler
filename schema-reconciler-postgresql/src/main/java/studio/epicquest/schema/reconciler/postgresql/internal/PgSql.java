package studio.epicquest.schema.reconciler.postgresql.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
     * extracts each statement's tokens outside string literals, dollar-quoted
     * bodies and comments. Tokens are upper-cased keywords/punctuation plus
     * quoted identifiers verbatim with their quotes — the quotes distinguish
     * a case-sensitive {@code "Schema".t} reference from a keyword, which the
     * checks of spec 3.2 need. A {@code BEGIN ATOMIC … END} routine body
     * (SQL-standard routine syntax) is opaque like a literal: its {@code ;}s
     * separate body statements, not script statements, and its tokens are
     * not part of the outer statement.
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
            } else if (isIdentStart(script.charAt(i))) {
                final var end = wordEnd(script, i);
                if (word(script, i, end).equals("BEGIN")) {
                    final var next = nextWord(script, end);
                    if (next != null && word(script, next[0], next[1]).equals("ATOMIC")) {
                        i = atomicBodyEnd(script, next[1]);
                        continue;
                    }
                }
                i = end;
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
                    tokens.add(script.substring(i, span[1]));
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
                if (script.substring(i, j).equalsIgnoreCase("BEGIN")) {
                    final var next = nextWord(script, j);
                    if (next != null && next[1] <= to
                            && script.substring(next[0], next[1]).equalsIgnoreCase("ATOMIC")) {
                        i = atomicBodyEnd(script, next[1]);
                        continue;
                    }
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

    /**
     * The text inside each string or dollar-quoted literal of {@code text}
     * (delimiters excluded, escapes not decoded) — the bodies a {@code DO}
     * or {@code EXECUTE} statement hands to the inner check.
     */
    public static List<String> literalBodies(String text) {
        final var out = new ArrayList<String>();
        var i = 0;
        while (i < text.length()) {
            final var span = nextSpan(text, i);
            if (span == null) {
                i++;
                continue;
            }
            if (span[2] == SPAN_STRING || span[2] == SPAN_DOLLAR) {
                out.add(literalBody(text, span));
            }
            i = span[1];
        }
        return out;
    }

    /** The content (delimiters excluded) of a string or dollar-quoted span. */
    private static String literalBody(String text, int[] span) {
        if (span[2] == SPAN_STRING) {
            // an unterminated span ends at the text length with no closing
            // quote — only strip a real closing delimiter, never content
            final var closed = span[1] - 1 > span[0] && text.charAt(span[1] - 1) == '\'';
            return text.substring(span[0] + 1, closed ? span[1] - 1 : span[1]);
        }
        final var tag = dollarTag(text, span[0]);
        final var innerEnd = text.startsWith(tag, span[1] - tag.length())
                && span[1] - tag.length() >= span[0] + tag.length()
                ? span[1] - tag.length() : span[1];
        return text.substring(span[0] + tag.length(), innerEnd);
    }

    private static final int SPAN_STRING = 1;
    private static final int SPAN_IDENT_QUOTED = 2;
    private static final int SPAN_DOLLAR = 3;
    private static final int SPAN_COMMENT = 4;

    /** Block-closing keywords that may trail an {@code END} ({@code END IF}, {@code END LOOP}, …). */
    private static final Set<String> END_TRAILERS = Set.of(
            "IF", "LOOP", "CASE", "WHILE", "FOR", "REPEAT");

    /** The end of the identifier starting at {@code pos}. */
    private static int wordEnd(String text, int pos) {
        var i = pos + 1;
        while (i < text.length() && isIdentPart(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The upper-cased word between {@code from} and {@code to}. */
    private static String word(String text, int from, int to) {
        return text.substring(from, to).toUpperCase(Locale.ROOT);
    }

    /** Whitespace and comments. */
    private static int skipFiller(String text, int i) {
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            final var span = nextSpan(text, i);
            if (span != null && span[2] == SPAN_COMMENT) {
                i = span[1];
                continue;
            }
            break;
        }
        return i;
    }

    /** {@code {start, end}} of the next identifier after {@code from}, or {@code null}. */
    private static int[] nextWord(String text, int from) {
        final var i = skipFiller(text, from);
        return i < text.length() && isIdentStart(text.charAt(i))
                ? new int[] {i, wordEnd(text, i)} : null;
    }

    /** The index right after the parenthesized group opening at {@code open}. */
    private static int skipParen(String text, int open) {
        var depth = 0;
        for (var i = open; i < text.length(); i++) {
            final var span = nextSpan(text, i);
            if (span != null) {
                i = span[1] - 1;
                continue;
            }
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return text.length();
    }

    /**
     * Whether the {@code FOR} at {@code from} opens a loop — {@code FOR var
     * [, var] IN …} — rather than starting a row-lock clause ({@code FOR
     * UPDATE}), a cursor query ({@code OPEN c FOR SELECT …}) or a cursor
     * declaration. The operand is a word, comma or parenthesized list and
     * the loop form ends it with {@code IN}.
     */
    private static boolean forIsLoop(String text, int from) {
        var i = from;
        for (;;) {
            i = skipFiller(text, i);
            if (i >= text.length()) {
                return false;
            }
            final var c = text.charAt(i);
            if (isIdentStart(c)) {
                if (word(text, i, wordEnd(text, i)).equals("IN")) {
                    return true;
                }
                i = wordEnd(text, i);
            } else if (c == '(') {
                i = skipParen(text, i);
            } else if (c == ',') {
                i++;
            } else {
                return false;
            }
        }
    }

    /**
     * The end of the {@code BEGIN ATOMIC} routine body whose {@code ATOMIC}
     * keyword ended at {@code from}. The body runs to its matching {@code
     * END}; nested compound blocks and {@code CASE} expressions open and
     * close inside it, and {@code FOR}/{@code WHILE} headers merge with the
     * {@code LOOP} that follows them.
     */
    private static int atomicBodyEnd(String text, int from) {
        final var blocks = new ArrayList<String>(2);
        blocks.add("BEGIN");
        var i = from;
        final var n = text.length();
        while (i < n) {
            final var span = nextSpan(text, i);
            if (span != null) {
                i = span[1];
                continue;
            }
            final var c = text.charAt(i);
            if (!isIdentStart(c)) {
                i++;
                continue;
            }
            final var end = wordEnd(text, i);
            final var w = word(text, i, end);
            switch (w) {
                case "BEGIN", "CASE", "IF", "WHILE", "REPEAT" -> blocks.add(w);
                case "FOR" -> {
                    if (forIsLoop(text, end)) {
                        blocks.add("FOR");
                    }
                }
                case "LOOP" -> {
                    final var top = blocks.get(blocks.size() - 1);
                    if (!top.equals("FOR") && !top.equals("WHILE")) {
                        blocks.add("LOOP");
                    }
                }
                case "END" -> {
                    blocks.remove(blocks.size() - 1);
                    final var trailer = nextWord(text, end);
                    if (blocks.isEmpty()) {
                        return trailer == null ? end : trailer[1];
                    }
                    if (trailer != null
                            && END_TRAILERS.contains(word(text, trailer[0], trailer[1]))) {
                        i = trailer[1];
                        continue;
                    }
                }
                default -> { }
            }
            i = end;
        }
        return n;
    }

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
