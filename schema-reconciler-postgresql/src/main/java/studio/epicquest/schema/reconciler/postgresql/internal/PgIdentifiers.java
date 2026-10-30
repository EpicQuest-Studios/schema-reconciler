package studio.epicquest.schema.reconciler.postgresql.internal;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Identifier quoting and the constant-default classification of spec A.6. */
public final class PgIdentifiers {

    private PgIdentifiers() {
    }

    private static final Pattern NUMERIC =
            Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    /**
     * A single {@code ::type} cast: an optionally schema-qualified type name
     * (quoted or unquoted identifier parts), optional precision and optional
     * array markers — and nothing else. Anything past the type name, for
     * example an operator, a call, a comment or a second statement, makes the
     * whole expression not a plain cast (A.6).
     */
    private static final Pattern TYPE_CAST = Pattern.compile(
            "::\\s*(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*)"
                    + "(?:\\s*\\.\\s*(?:\"(?:[^\"]|\"\")+\"|[A-Za-z_][A-Za-z0-9_$]*))?"
                    + "(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*\\))?"
                    + "(?:\\s*\\[\\s*\\])*\\s*");

    /**
     * {@code "<identifier>"} with embedded quotes doubled (6.1, 8.3). Rejects
     * identifiers that are empty, contain NUL, or exceed 63 UTF-8 bytes.
     */
    public static String quote(String identifier) {
        if (identifier == null || identifier.isEmpty()
                || identifier.getBytes(StandardCharsets.UTF_8).length > 63
                || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("illegal PostgreSQL identifier: " + identifier);
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * The conservative lexical rule of spec A.6: a default is constant only if
     * it is a numeric literal, a boolean literal, {@code NULL}, or a quoted
     * string literal, each optionally followed by one {@code ::type} cast.
     * Anything else (functions, arithmetic, {@code nextval}) is not constant.
     */
    public static boolean isConstantDefault(String expression) {
        if (expression == null) {
            return false;
        }
        var s = expression.strip();
        String cast = null;
        final var idx = s.indexOf("::");
        if (idx > 0) {
            cast = s.substring(idx);
            if (!TYPE_CAST.matcher(cast).matches()) {
                return false;
            }
            s = s.substring(0, idx).strip();
            if (s.indexOf("::") >= 0) {
                return false;
            }
        }
        if (s.isEmpty()) {
            return false;
        }
        final var upper = s.toUpperCase(Locale.ROOT);
        if (upper.equals("NULL") || upper.equals("TRUE") || upper.equals("FALSE")) {
            return true;
        }
        if (NUMERIC.matcher(s).matches()) {
            return true;
        }
        if (s.startsWith("'") && s.endsWith("'") && s.length() >= 2) {
            // a single-quoted literal with '' escapes only — no unescaped quotes inside
            final var inner = s.substring(1, s.length() - 1);
            return inner.replace("''", "").indexOf('\'') < 0;
        }
        return false;
    }

    /**
     * A scratch namespace name (6.3.1): {@code <target>__rcl_<12 hex>},
     * truncated to 63 bytes. The prefix is lower-cased: unquoted references
     * fold to lowercase, so only a fold-safe scratch name makes the script
     * check's case-insensitive qualifier comparison sound.
     */
    public static String scratchName(String targetNamespace, long seed) {
        final var suffix = "__rcl_" + String.format("%012x", seed);
        var prefix = targetNamespace.toLowerCase(Locale.ROOT);
        final var max = 63 - suffix.length();
        if (prefix.getBytes(StandardCharsets.UTF_8).length > max) {
            // truncate on a UTF-8 boundary
            final var bytes = prefix.getBytes(StandardCharsets.UTF_8);
            var len = max;
            while (len > 0 && (bytes[len] & 0xC0) == 0x80) {
                len--;
            }
            prefix = new String(bytes, 0, len, StandardCharsets.UTF_8);
        }
        return prefix + suffix;
    }
}
