package studio.epicquest.schema.reconciler.postgresql.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads the shared PostgreSQL catalog queries from
 * {@code sql/postgresql/*.sql} (spec A.5 — the same files the TypeScript
 * dialect reads from {@code dist/sql/postgresql}), copied onto the class
 * path by the build under
 * {@code studio/epicquest/schema/reconciler/postgresql/internal/sql/}.
 * Each file holds named sections:
 *
 * <pre>
 *   -- name: &lt;query_name&gt;
 *   SELECT ... WHERE n.nspname = ?
 * </pre>
 *
 * {@code ?} marks a positional parameter; JDBC binds {@code ?} natively, so
 * the bodies are used verbatim — unlike the TypeScript loader, which rewrites
 * them to {@code $1, $2, ...} for the {@code pg} driver. Parsed queries are
 * cached for the process.
 */
final class PgQueries {

    private static final List<String> FILES =
            List.of("catalog.sql", "annotations.sql", "dependencies.sql");

    private static final Pattern MARKER =
            Pattern.compile("^--\\s*name:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*$");

    private static final String DIR =
            "/studio/epicquest/schema/reconciler/postgresql/internal/sql/";

    private static volatile Map<String, String> queries;

    private PgQueries() {
    }

    /**
     * Splits one {@code .sql} file on its {@code -- name: <ident>} markers.
     * Lines before the first marker (the file header) must be blank or
     * comments.
     */
    static Map<String, String> parseFile(String text, String file) {
        final var sections = new LinkedHashMap<String, String>();
        String name = null;
        final var body = new StringBuilder();
        var lineNo = 0;
        for (final var line : text.split("\n", -1)) {
            lineNo++;
            final var marker = MARKER.matcher(line);
            if (marker.matches()) {
                flush(sections, name, body, file);
                name = marker.group(1);
                if (sections.containsKey(name)) {
                    throw new IllegalArgumentException(file
                            + ": duplicate query '" + name + "'");
                }
                body.setLength(0);
            } else if (name == null) {
                final var trimmed = line.strip();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    throw new IllegalArgumentException(file + ":" + lineNo
                            + ": SQL before the first '-- name:' marker");
                }
            } else {
                if (body.length() > 0) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        flush(sections, name, body, file);
        return sections;
    }

    private static void flush(Map<String, String> sections, String name,
            StringBuilder body, String file) {
        if (name != null) {
            final var sql = body.toString().strip();
            if (sql.isEmpty()) {
                throw new IllegalArgumentException(file + ": query '" + name
                        + "' has no body");
            }
            sections.put(name, sql);
        }
    }

    private static Map<String, String> load() {
        var all = queries;
        if (all == null) {
            synchronized (PgQueries.class) {
                all = queries;
                if (all == null) {
                    all = new LinkedHashMap<>();
                    for (final var file : FILES) {
                        final var path = DIR + file;
                        final String text;
                        try (InputStream in = PgQueries.class
                                .getResourceAsStream(path)) {
                            if (in == null) {
                                throw new IllegalStateException(path
                                        + " not found on the class path");
                            }
                            text = new String(in.readAllBytes(),
                                    StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        for (final var e : parseFile(text, file).entrySet()) {
                            if (all.put(e.getKey(), e.getValue()) != null) {
                                throw new IllegalArgumentException(
                                        "duplicate query '" + e.getKey() + "'");
                            }
                        }
                    }
                    queries = all;
                }
            }
        }
        return all;
    }

    /** The named catalog query, or throws. */
    static String get(String name) {
        final var sql = load().get(name);
        if (sql == null) {
            throw new IllegalArgumentException(
                    "unknown catalog query '" + name + "'");
        }
        return sql;
    }
}
