package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import studio.epicquest.schema.reconciler.DesiredStateException;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.RenameHint;

/**
 * Parses {@code -- @reconciler rename <KIND> [<parent>.]<old_name> -> <new_name>}
 * comment directives out of a desired-state script (spec 3.2). Identifier
 * paths follow SQL quoting: a {@code "double quoted"} segment is verbatim
 * text ({@code ""} escapes a quote); a bare segment is used as written.
 * The last path segment is the object name, the rest (at most one) its
 * parent.
 */
public final class RenameHints {

    private RenameHints() {
    }

    private static final Pattern DIRECTIVE = Pattern.compile(
            "^\\s*--\\s*@reconciler\\s+rename\\s+([A-Za-z_]+)\\s+(\\S+)\\s*->\\s*(\\S+)\\s*$");

    public static List<RenameHint> parse(String script) {
        final var hints = new ArrayList<RenameHint>();
        for (final var line : script.split("\n")) {
            final var m = DIRECTIVE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            final ObjectKind kind;
            try {
                kind = ObjectKind.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new DesiredStateException(
                        "@reconciler rename: unknown object kind '" + m.group(1) + "'");
            }
            hints.add(new RenameHint(idPath(kind, m.group(2)),
                    toId(kind, m.group(2), m.group(3))));
        }
        return hints;
    }

    /** {@code <KIND> [parent.]name} — the parent comes from the {@code from} path when {@code to} carries none. */
    private static ObjectId toId(ObjectKind kind, String fromPath, String toPath) {
        final var to = idPath(kind, toPath);
        if (to.parent().isPresent()) {
            return to;
        }
        final var from = idPath(kind, fromPath);
        return new ObjectId(kind, to.name(), from.parent());
    }

    private static ObjectId idPath(ObjectKind kind, String text) {
        final var parts = splitPath(text);
        if (parts.isEmpty() || parts.size() > 2 || parts.stream().anyMatch(String::isEmpty)) {
            throw new DesiredStateException(
                    "@reconciler rename: bad identifier path '" + text + "'");
        }
        final var parent = parts.size() == 2 ? parts.get(0) : null;
        return parent == null
                ? new ObjectId(kind, parts.get(parts.size() - 1))
                : new ObjectId(kind, parts.get(parts.size() - 1), parent);
    }

    /** Splits a {@code .}-separated identifier path outside double quotes and unquotes each segment. */
    private static List<String> splitPath(String text) {
        final var parts = new ArrayList<String>();
        final var cur = new StringBuilder();
        var inQuote = false;
        for (var i = 0; i < text.length(); i++) {
            final var c = text.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == '.') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (inQuote) {
            throw new DesiredStateException(
                    "@reconciler rename: unterminated quote in '" + text + "'");
        }
        parts.add(cur.toString());
        return parts;
    }
}
