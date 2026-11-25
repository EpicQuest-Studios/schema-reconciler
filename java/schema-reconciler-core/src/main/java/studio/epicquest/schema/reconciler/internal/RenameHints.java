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
 * and {@code -- @reconciler retire <KIND> [<parent>.]<name>} comment directives
 * out of a desired-state script (spec 3.2). Identifier paths follow SQL quoting:
 * a {@code "double quoted"} segment is verbatim text ({@code ""} escapes a
 * quote); a bare segment is used as written. The last path segment is the
 * object name, the rest (at most one) its parent.
 */
public final class RenameHints {

    private RenameHints() {
    }

    private static final Pattern DIRECTIVE = Pattern.compile(
            "^\\s*--\\s*@reconciler\\s+rename\\s+([A-Za-z_]+)\\s+(\\S+)\\s*->\\s*(\\S+)\\s*$");
    private static final Pattern RETIRE = Pattern.compile(
            "^\\s*--\\s*@reconciler\\s+retire\\s+([A-Za-z_]+)\\s+(\\S+)\\s*$");

    /** The directives a script declares. */
    public record Directives(List<RenameHint> renameHints, List<ObjectId> retireTargets) {
        public Directives {
            renameHints = List.copyOf(renameHints);
            retireTargets = List.copyOf(retireTargets);
        }
    }

    public static List<RenameHint> parse(String script) {
        return parseDirectives(script).renameHints();
    }

    /** Parses both {@code rename} and {@code retire} directives. */
    public static Directives parseDirectives(String script) {
        final var hints = new ArrayList<RenameHint>();
        final var retire = new ArrayList<ObjectId>();
        for (final var line : script.split("\n")) {
            final var m = DIRECTIVE.matcher(line);
            if (m.matches()) {
                final var kind = kind("rename", m.group(1));
                final var from = idPath("rename", kind, m.group(2));
                hints.add(new RenameHint(from, toId(kind, from, m.group(3))));
                continue;
            }
            final var r = RETIRE.matcher(line);
            if (r.matches()) {
                final var kind = kind("retire", r.group(1));
                if (kind == ObjectKind.OTHER) {
                    throw new DesiredStateException(
                            "@reconciler retire: OTHER objects cannot be retired");
                }
                retire.add(idPath("retire", kind, r.group(2)));
            }
        }
        return new Directives(hints, retire);
    }

    private static ObjectKind kind(String directive, String text) {
        try {
            return ObjectKind.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DesiredStateException(
                    "@reconciler " + directive + ": unknown object kind '" + text + "'");
        }
    }

    /** {@code <KIND> [parent.]name} — the parent comes from the {@code from} path when {@code to} carries none. */
    private static ObjectId toId(ObjectKind kind, ObjectId from, String toPath) {
        final var parts = splitPath("rename", toPath);
        if (parts.isEmpty() || parts.size() > 2 || parts.stream().anyMatch(String::isEmpty)) {
            throw new DesiredStateException(
                    "@reconciler rename: bad identifier path '" + toPath + "'");
        }
        final var parent = parts.size() == 2 ? parts.get(0)
                : from.parent().orElse(null);
        try {
            return parent == null
                    ? new ObjectId(kind, parts.get(parts.size() - 1))
                    : new ObjectId(kind, parts.get(parts.size() - 1), parent);
        } catch (IllegalArgumentException e) {
            throw new DesiredStateException(
                    "@reconciler rename: bad identifier path '" + toPath
                            + "': " + e.getMessage(), e);
        }
    }

    private static ObjectId idPath(String directive, ObjectKind kind, String text) {
        final var parts = splitPath(directive, text);
        if (parts.isEmpty() || parts.size() > 2 || parts.stream().anyMatch(String::isEmpty)) {
            throw new DesiredStateException(
                    "@reconciler " + directive + ": bad identifier path '" + text + "'");
        }
        final var parent = parts.size() == 2 ? parts.get(0) : null;
        try {
            return parent == null
                    ? new ObjectId(kind, parts.get(parts.size() - 1))
                    : new ObjectId(kind, parts.get(parts.size() - 1), parent);
        } catch (IllegalArgumentException e) {
            throw new DesiredStateException(
                    "@reconciler " + directive + ": bad identifier path '" + text
                            + "': " + e.getMessage(), e);
        }
    }

    /** Splits a {@code .}-separated identifier path outside double quotes and unquotes each segment. */
    private static List<String> splitPath(String directive, String text) {
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
                    "@reconciler " + directive + ": unterminated quote in '" + text + "'");
        }
        parts.add(cur.toString());
        return parts;
    }
}
