package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON reader/writer for the plan
 * serialisation of spec 4.4 — objects are {@link LinkedHashMap}
 * (insertion-ordered, matching the fixed key order {@code toJson}
 * emits), arrays {@link List}, numbers {@link Long} or {@link Double},
 * plus {@link String}, {@link Boolean} and {@code null}. The writer is
 * compact (no whitespace), matching {@code JSON.stringify}; the reader
 * accepts the full JSON grammar and rejects trailing garbage.
 */
public final class Json {

    private Json() {
    }

    // ---- writer ------------------------------------------------------------

    public static String write(Object value) {
        final var out = new StringBuilder();
        writeValue(out, value);
        return out.toString();
    }

    private static void writeValue(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(out, s);
            case Boolean b -> out.append(b);
            case Integer n -> out.append(n.intValue());
            case Long n -> out.append(n.longValue());
            case Double n -> out.append(n.doubleValue());
            case Map<?, ?> m -> {
                out.append('{');
                var first = true;
                for (final var e : m.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, (String) e.getKey());
                    out.append(':');
                    writeValue(out, e.getValue());
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                var first = true;
                for (final var e : list) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeValue(out, e);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException(
                    "cannot serialise " + value.getClass());
        }
    }

    /** {@code JSON.stringify} string escaping: control characters, quote and backslash. */
    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (var i = 0; i < s.length(); i++) {
            final var c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- reader ------------------------------------------------------------

    /** Parses one JSON text; throws {@link IllegalArgumentException} on malformed input. */
    public static Object read(String text) {
        final var p = new Parser(text);
        p.skipWhitespace();
        final var value = p.value();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw p.error("unexpected trailing characters");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        IllegalArgumentException error(String detail) {
            return new IllegalArgumentException(
                    "invalid JSON at position " + pos + ": " + detail);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                final var c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object value() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) {
                throw error("unexpected token");
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> object() {
            final var out = new LinkedHashMap<String, Object>();
            pos++;
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != '"') {
                    throw error("expected object key");
                }
                final var key = string();
                skipWhitespace();
                if (atEnd() || text.charAt(pos) != ':') {
                    throw error("expected ':'");
                }
                pos++;
                skipWhitespace();
                out.put(key, value());
                skipWhitespace();
                if (atEnd()) {
                    throw error("unexpected end of input");
                }
                if (text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (text.charAt(pos) == '}') {
                    pos++;
                    return out;
                }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            final var out = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                out.add(value());
                skipWhitespace();
                if (atEnd()) {
                    throw error("unexpected end of input");
                }
                if (text.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (text.charAt(pos) == ']') {
                    pos++;
                    return out;
                }
                throw error("expected ',' or ']'");
            }
        }

        private String string() {
            final var out = new StringBuilder();
            pos++;
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                final var c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("unterminated escape");
                    }
                    final var e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw error("truncated \\u escape");
                            }
                            try {
                                out.append((char) Integer.parseInt(
                                        text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException nfe) {
                                throw error("bad \\u escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character");
                } else {
                    out.append(c);
                }
            }
        }

        private Object number() {
            final var start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            var isDouble = false;
            while (!atEnd()) {
                final var c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isDouble = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start) {
                throw error("unexpected token");
            }
            final var raw = text.substring(start, pos);
            try {
                return isDouble ? (Object) Double.parseDouble(raw)
                        : (Object) Long.parseLong(raw);
            } catch (NumberFormatException e) {
                throw error("bad number '" + raw + "'");
            }
        }
    }
}
