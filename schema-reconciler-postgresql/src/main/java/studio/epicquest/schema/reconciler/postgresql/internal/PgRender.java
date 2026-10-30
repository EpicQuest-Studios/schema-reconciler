package studio.epicquest.schema.reconciler.postgresql.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * The rendering rules of spec A.6. Pre-computed statement fragments live in
 * {@code SchemaObject.attributes} ({@link PgCatalog#CREATE_SQL} etc.) with
 * {@code ${namespace}} tokens; {@link #bind} substitutes the quoted target.
 */
public final class PgRender {

    private static final String TOKEN = "${namespace}";

    private PgRender() {
    }

    public static List<PlannedChange> render(Difference difference, SchemaSnapshot desired,
                                             String targetNamespace) {
        final var id = difference.id();
        final var want = desired.objects().get(id);
        return switch (difference.type()) {
            case MISSING -> missing(id, want, desired, targetNamespace);
            case DRIFTED -> drifted(id, want, difference, targetNamespace);
            default -> throw new PlanningException("difference " + difference.id()
                    + " of type " + difference.type() + " is not renderable");
        };
    }

    // ---- MISSING ---------------------------------------------------------

    private static List<PlannedChange> missing(ObjectId id, SchemaObject want,
                                               SchemaSnapshot desired, String ns) {
        return switch (id.kind()) {
            case TABLE -> List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, createTableStatements(id, desired, ns),
                    Optional.of(want.definition()), "missing " + id));
            case COLUMN -> missingColumn(id, want, desired, ns);
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, INDEX, VIEW, TRIGGER,
                    TYPE, DOMAIN, POLICY, EXTENSION ->
                    List.of(additive(id, want, ns, "missing " + id));
            case MATERIALIZED_VIEW -> List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, matviewCreateStatements(want, ns),
                    Optional.of(want.definition()), "missing " + id));
            case COMMENT -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "missing comment on " + id));
            case SEQUENCE -> {
                final var statements = new ArrayList<String>();
                statements.add(bind(want, PgCatalog.CREATE_SQL, ns));
                final var owned = want.attributes().get(PgCatalog.OWNED_SQL);
                if (owned != null) {
                    statements.add(bind(owned, ns));
                }
                yield List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                        ChangeSafety.ADDITIVE, statements, Optional.of(want.definition()),
                        "missing " + id));
            }
            case ROUTINE -> List.of(additive(id, want, ns, "missing " + id));
            default -> List.of();
        };
    }

    /** CREATE TABLE folding in every desired column plus serial-owned sequences (A.6, 5.8.4). */
    private static List<String> createTableStatements(ObjectId table, SchemaSnapshot desired,
                                                      String ns) {
        final var columns = desired.objects().values().stream()
                .filter(o -> o.id().kind() == ObjectKind.COLUMN
                        && o.id().parent().orElse("").equals(table.name()))
                .sorted(Comparator.comparingInt(c -> Integer.parseInt(
                        c.attributes().getOrDefault(PgCatalog.ATTNUM, "0"))))
                .toList();
        final var sequences = desired.objects().values().stream()
                .filter(o -> o.id().kind() == ObjectKind.SEQUENCE
                        && o.dependsOn().contains(table))
                .sorted((a, b) -> ObjectIds.ORDER.compare(a.id(), b.id()))
                .toList();

        final var statements = new ArrayList<String>();
        sequences.stream().map(s -> bind(s, PgCatalog.CREATE_SQL, ns))
                .forEach(statements::add);
        final var create = new StringBuilder("CREATE ")
                .append(tablePersistence(desired, table).equals("u") ? "UNLOGGED " : "")
                .append("TABLE ")
                .append(PgIdentifiers.quote(ns)).append('.')
                .append(PgIdentifiers.quote(table.name())).append(" (\n")
                .append(columns.stream()
                        .map(c -> "    " + c.attributes().get(PgCatalog.COLUMN_SQL))
                        .collect(Collectors.joining(",\n")))
                .append("\n)");
        final var tableAttrs = desired.objects().get(table).attributes();
        final var options = tableAttrs.getOrDefault(PgCatalog.TABLE_OPTIONS, "");
        if (!options.isEmpty()) {
            create.append(" WITH (").append(options).append(')');
        }
        statements.add(bind(create.toString(), ns));
        // 'i' (USING INDEX) is applied after the index exists, see PgCatalog POST_SQL
        final var replident = switch (tableAttrs.getOrDefault(PgCatalog.REPLIDENT, "d")) {
            case "f" -> "FULL";
            case "n" -> "NOTHING";
            default -> null;
        };
        if (replident != null) {
            statements.add(bind("ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(table.name())
                    + " REPLICA IDENTITY " + replident, ns));
        }
        // CREATE TABLE cannot set row-level security; enabled flags follow
        final var tableDef = columnFields(
                desired.objects().get(table).definition().canonical());
        if (Boolean.parseBoolean(tableDef.getOrDefault("rls", "false"))) {
            statements.add(bind("ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(table.name())
                    + " ENABLE ROW LEVEL SECURITY", ns));
        }
        if (Boolean.parseBoolean(tableDef.getOrDefault("forcerls", "false"))) {
            statements.add(bind("ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(table.name())
                    + " FORCE ROW LEVEL SECURITY", ns));
        }
        sequences.stream()
                .map(s -> s.attributes().get(PgCatalog.OWNED_SQL))
                .filter(Objects::nonNull)
                .map(sql -> bind(sql, ns))
                .forEach(statements::add);
        return statements;
    }

    /**
     * A.6: the materialized-view create — {@code WITH NO DATA} when the
     * desired object is unpopulated, so an intentional empty view converges
     * instead of being refreshed full.
     */
    private static List<String> matviewCreateStatements(SchemaObject want, String ns) {
        final var create = want.attributes().get(PgCatalog.CREATE_SQL);
        final var populated = Boolean.parseBoolean(
                want.attributes().getOrDefault(PgCatalog.POPULATED, "true"));
        return List.of(bind(populated ? create : create + " WITH NO DATA", ns));
    }

    private static String tablePersistence(SchemaSnapshot desired, ObjectId table) {
        final var t = desired.objects().get(table);
        if (t == null) {
            return "p";
        }
        final var def = t.definition().canonical();
        final var idx = def.indexOf("relpersistence=");
        return idx < 0 ? "p" : def.substring(idx + 15, idx + 16);
    }

    /** A.6: ADD COLUMN — additive for nullable or constant-default columns, manual otherwise. */
    private static List<PlannedChange> missingColumn(ObjectId id, SchemaObject want,
                                                     SchemaSnapshot desired, String ns) {
        final var parent = id.parent().orElse("");
        final var alter = "ALTER TABLE " + PgIdentifiers.quote(ns) + "."
                + PgIdentifiers.quote(parent) + " ADD COLUMN "
                + want.attributes().get(PgCatalog.COLUMN_SQL);
        final var notnull = Boolean.parseBoolean(want.attributes().get(PgCatalog.NOTNULL));
        if (!notnull) {
            return List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, List.of(bind(alter, ns)),
                    Optional.of(want.definition()), "missing nullable column " + id.name()));
        }
        final var defexpr = want.attributes().get(PgCatalog.DEFAULT);
        if (defexpr != null && PgIdentifiers.isConstantDefault(defexpr)) {
            return List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, List.of(bind(alter, ns)),
                    Optional.of(want.definition()),
                    "missing NOT NULL column " + id.name() + " with a constant default"));
        }
        return List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(),
                "missing NOT NULL column " + id.name() + " requires a manual change"));
    }

    // ---- DRIFTED ----------------------------------------------------------

    /**
     * A.6: a materialized view holds stored rows, so its recreate is
     * {@code DESTRUCTIVE} (5.6): {@code DROP}, {@code CREATE} and, unless
     * the desired object is unpopulated, a {@code REFRESH}.
     */
    private static List<PlannedChange> driftedMatview(ObjectId id, SchemaObject want, String ns) {
        final var statements = new ArrayList<>(matviewCreateStatements(want, ns));
        if (Boolean.parseBoolean(want.attributes().getOrDefault(PgCatalog.POPULATED, "true"))) {
            statements.add(bind("REFRESH MATERIALIZED VIEW " + TOKEN + "."
                    + PgIdentifiers.quote(id.name()), ns));
        }
        final var reason = "drifted " + id + " (stored rows are dropped)";
        return List.of(
                new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.DESTRUCTIVE,
                        List.of(bind(want, PgCatalog.DROP_SQL, ns)), Optional.empty(), reason),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.DESTRUCTIVE,
                        statements, Optional.of(want.definition()), reason));
    }

    /**
     * A.6: an enum's labels append in place — a pure in-order suffix
     * addition renders one {@code ALTER TYPE ... ADD VALUE} per new label
     * ({@code ADDITIVE}); a removed or reordered label cannot be altered
     * in place and stays a manual change ({@code DESTRUCTIVE}).
     */
    private static List<PlannedChange> driftedEnum(ObjectId id, SchemaObject want,
                                                   Difference difference, String ns) {
        final var have = enumLabels(difference.actual().map(d -> d.canonical()).orElse(""));
        final var wantLabels = enumLabels(want.definition().canonical());
        if (wantLabels.size() > have.size() && have.equals(wantLabels.subList(0, have.size()))) {
            final var qName = TOKEN + "." + PgIdentifiers.quote(id.name());
            final var statements = wantLabels.subList(have.size(), wantLabels.size()).stream()
                    .map(l -> "ALTER TYPE " + qName + " ADD VALUE " + literal(l))
                    .map(s -> bind(s, ns))
                    .toList();
            return List.of(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.ADDITIVE,
                    statements, Optional.of(want.definition()),
                    "drifted " + id + " (labels appended in order)"));
        }
        return List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(),
                "drifted " + id + " (removed or reordered labels) requires a manual change"));
    }

    /** A single-quoted SQL literal for an enum label. */
    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * A.6: a {@code TABLE} drift limited to the row-level-security flags —
     * {@code relrowsecurity} and {@code relforcerowsecurity} — renders an
     * in-place {@code ALTER TABLE ... ENABLE|DISABLE|FORCE|NO FORCE ROW
     * LEVEL SECURITY} ({@code REBUILD}); any other table definition change
     * stays a manual {@code DESTRUCTIVE} change as before.
     */
    private static List<PlannedChange> driftedTable(ObjectId id, SchemaObject want,
                                                    Difference difference, String ns) {
        final var have = difference.actual().map(d -> d.canonical()).orElse(null);
        if (have == null) {
            return manual(id, "drifted " + id + " requires a manual change");
        }
        final var wantFields = columnFields(want.definition().canonical());
        final var haveFields = columnFields(have);
        final var qTable = "ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(id.name());
        final var statements = new ArrayList<String>();
        final var wantRls = Boolean.parseBoolean(wantFields.getOrDefault("rls", "false"));
        final var haveRls = Boolean.parseBoolean(haveFields.getOrDefault("rls", "false"));
        if (wantRls != haveRls) {
            statements.add(qTable + (wantRls ? " ENABLE" : " DISABLE") + " ROW LEVEL SECURITY");
        }
        final var wantForce = Boolean.parseBoolean(wantFields.getOrDefault("forcerls", "false"));
        final var haveForce = Boolean.parseBoolean(haveFields.getOrDefault("forcerls", "false"));
        if (wantForce != haveForce) {
            statements.add(qTable + (wantForce ? " FORCE" : " NO FORCE") + " ROW LEVEL SECURITY");
        }
        final var rest = new LinkedHashMap<>(wantFields);
        rest.remove("rls");
        rest.remove("forcerls");
        final var haveRest = new LinkedHashMap<>(haveFields);
        haveRest.remove("rls");
        haveRest.remove("forcerls");
        if (!rest.equals(haveRest)) {
            return manual(id, "drifted " + id + " requires a manual change");
        }
        return List.of(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                statements.stream().map(s -> bind(s, ns)).toList(),
                Optional.of(want.definition()), "drifted " + id + " (row-level security flag)"));
    }

    private static List<PlannedChange> manual(ObjectId id, String reason) {
        return List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(), reason));
    }

    /**
     * A.6: domain drift decomposed like column drift — {@code SET}/{@code
     * DROP DEFAULT}, {@code DROP NOT NULL} and {@code DROP CONSTRAINT} are
     * safe in place ({@code REBUILD}); {@code SET NOT NULL} and {@code ADD
     * CONSTRAINT} validate existing values, so they render as a separate
     * {@code DESTRUCTIVE} {@code ALTER} change; a base-type or collation
     * change cannot alter in place and stays manual.
     */
    private static List<PlannedChange> driftedDomain(ObjectId id, SchemaObject want,
                                                     Difference difference, String ns) {
        final var have = difference.actual().map(d -> d.canonical()).orElse(null);
        if (have == null) {
            return manual(id, "drifted " + id + " requires a manual change");
        }
        final var wantFields = columnFields(want.definition().canonical());
        final var haveFields = columnFields(have);
        for (final var key : List.of("basetype", "collation")) {
            if (!Objects.equals(wantFields.get(key), haveFields.get(key))) {
                return manual(id, "drifted " + id + " (" + key + " changed)"
                        + " requires a manual change");
            }
        }
        final var alter = "ALTER DOMAIN " + TOKEN + "." + PgIdentifiers.quote(id.name()) + " ";
        final var safe = new ArrayList<String>();
        final var validating = new ArrayList<String>();
        final var merged = new LinkedHashMap<>(wantFields);
        final var wantDefault = wantFields.get("default");
        if (!Objects.equals(wantDefault, haveFields.get("default"))) {
            safe.add(alter + (wantDefault != null ? "SET DEFAULT " + wantDefault : "DROP DEFAULT"));
        }
        final var wantNotnull =
                Boolean.parseBoolean(wantFields.getOrDefault("notnull", "false"));
        final var haveNotnull =
                Boolean.parseBoolean(haveFields.getOrDefault("notnull", "false"));
        if (haveNotnull && !wantNotnull) {
            safe.add(alter + "DROP NOT NULL");
        } else if (wantNotnull && !haveNotnull) {
            validating.add(alter + "SET NOT NULL");
            merged.put("notnull", haveFields.get("notnull"));
        }
        final var wantChecks = domainChecks(wantFields);
        final var haveChecks = domainChecks(haveFields);
        for (final var name : haveChecks.keySet()) {
            if (!wantChecks.containsKey(name)) {
                safe.add(alter + "DROP CONSTRAINT " + PgIdentifiers.quote(name));
            }
        }
        for (final var entry : wantChecks.entrySet()) {
            final var name = entry.getKey();
            if (haveChecks.containsKey(name) && !haveChecks.get(name).equals(entry.getValue())) {
                safe.add(alter + "DROP CONSTRAINT " + PgIdentifiers.quote(name));
            }
            if (!Objects.equals(haveChecks.get(name), entry.getValue())) {
                validating.add(alter + "ADD CONSTRAINT " + PgIdentifiers.quote(name)
                        + " " + entry.getValue());
                merged.remove("check." + name);
            }
        }
        if (safe.isEmpty() && validating.isEmpty()) {
            return manual(id, "drifted " + id + " requires a manual change");
        }
        final var changes = new ArrayList<PlannedChange>();
        if (!safe.isEmpty()) {
            final var expected = validating.isEmpty()
                    ? want.definition()
                    : new Definition(merged.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(";")));
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                    safe.stream().map(s -> bind(s, ns)).toList(),
                    Optional.of(expected), "drifted " + id));
        }
        if (!validating.isEmpty()) {
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE,
                    validating.stream().map(s -> bind(s, ns)).toList(),
                    Optional.of(want.definition()),
                    "drifted " + id + " (validates existing values)"));
        }
        return changes;
    }

    /** The {@code check.<name>=<def>} entries of a domain canonical. */
    private static Map<String, String> domainChecks(Map<String, String> fields) {
        final var checks = new LinkedHashMap<String, String>();
        fields.forEach((k, v) -> {
            if (k.startsWith("check.")) {
                checks.put(k.substring(6), v);
            }
        });
        return checks;
    }

    private static List<PlannedChange> drifted(ObjectId id, SchemaObject want,
                                               Difference difference, String ns) {
        return switch (id.kind()) {
            case COLUMN -> driftedColumn(id, want, difference, ns);
            case TABLE -> driftedTable(id, want, difference, ns);
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, INDEX, VIEW, TRIGGER,
                    POLICY ->
                    recreate(id, want, ns, "drifted " + id);
            case MATERIALIZED_VIEW -> driftedMatview(id, want, ns);
            case TYPE -> driftedEnum(id, want, difference, ns);
            case DOMAIN -> driftedDomain(id, want, difference, ns);
            case COMMENT -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "drifted comment on " + id));
            case SEQUENCE -> {
                final var statements = new ArrayList<String>();
                final var options = want.attributes().get(PgCatalog.OPTIONS_SQL);
                statements.add(bind("ALTER SEQUENCE " + seqRef(id) + " " + options, ns));
                final var owned = want.attributes().get(PgCatalog.OWNED_SQL);
                if (owned != null) {
                    statements.add(bind(owned, ns));
                }
                yield List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                        ChangeSafety.DESTRUCTIVE, statements, Optional.of(want.definition()),
                        "drifted " + id));
            }
            case ROUTINE -> {
                final var wantReturn = want.attributes().get(PgCatalog.RETURN_TYPE);
                final var haveReturn = returnTypeOf(difference.actual()
                        .map(d -> d.canonical()).orElse(""));
                if (wantReturn != null && wantReturn.equals(haveReturn)) {
                    yield List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                            ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                            Optional.of(want.definition()), "drifted " + id));
                }
                yield recreate(id, want, ns, "drifted " + id + " (return type changed)");
            }
            default -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(),
                    "drifted " + id + " requires a manual change"));
        };
    }

    /**
     * A.6: decomposes column drift by what each alteration can do to existing
     * rows (5.6 rule 1). Reversible metadata alterations — a widening {@code
     * TYPE} from the allowlist, default changes and {@code DROP NOT NULL} —
     * become one {@code REBUILD} change. {@code SET NOT NULL} scans and fails
     * on violating rows, so it becomes a separate {@code DESTRUCTIVE} change
     * (rendered, gated by policy). Generated, identity and collation changes
     * and non-widening type changes stay manual (no statements).
     */
    private static List<PlannedChange> driftedColumn(ObjectId id, SchemaObject want,
                                                     Difference difference, String ns) {
        final var haveCanonical = difference.actual()
                .map(d -> d.canonical()).orElse(null);
        if (haveCanonical == null) {
            return manualColumn(id, "drifted " + id + " requires a manual change");
        }
        final var wantFields = columnFields(want.definition().canonical());
        final var haveFields = columnFields(haveCanonical);
        for (final var key : List.of("generated", "identity", "identityOptions", "collation")) {
            if (!Objects.equals(wantFields.get(key), haveFields.get(key))) {
                return manualColumn(id, "drifted " + id + " (" + key + " changed)");
            }
        }
        final var alter = "ALTER TABLE " + TOKEN + "."
                + PgIdentifiers.quote(id.parent().orElse(""))
                + " ALTER COLUMN " + PgIdentifiers.quote(id.name()) + " ";
        final var safe = new ArrayList<String>();
        final var wantType = wantFields.get("type");
        if (!Objects.equals(wantType, haveFields.get("type"))) {
            if (wantType == null || haveFields.get("type") == null
                    || !widens(haveFields.get("type"), wantType)) {
                return manualColumn(id, "drifted " + id + " (type change "
                        + haveFields.get("type") + " -> " + wantType
                        + " is not a widening)");
            }
            safe.add(alter + "TYPE " + wantType);
        }
        final var wantDefault = wantFields.get("default");
        if (!Objects.equals(wantDefault, haveFields.get("default"))) {
            safe.add(alter + (wantDefault != null
                    ? "SET DEFAULT " + wantDefault : "DROP DEFAULT"));
        }
        final var wantNotnull =
                Boolean.parseBoolean(wantFields.getOrDefault("notnull", "false"));
        final var haveNotnull =
                Boolean.parseBoolean(haveFields.getOrDefault("notnull", "false"));
        final var setNotNull = wantNotnull && !haveNotnull;
        if (haveNotnull && !wantNotnull) {
            safe.add(alter + "DROP NOT NULL");
        }
        if (safe.isEmpty() && !setNotNull) {
            return manualColumn(id, "drifted " + id + " requires a manual change");
        }
        final var changes = new ArrayList<PlannedChange>();
        if (!safe.isEmpty()) {
            // with SET NOT NULL deferred to its own change, the applied
            // statements only converge the column to want-minus-notnull; the
            // expected definition must match that state exactly
            final var expected = setNotNull
                    ? new Definition(canonicalWith(wantFields, "notnull",
                            haveFields.get("notnull")))
                    : want.definition();
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                    safe.stream().map(s -> bind(s, ns)).toList(),
                    Optional.of(expected), "drifted " + id));
        }
        if (setNotNull) {
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE,
                    List.of(bind(alter + "SET NOT NULL", ns)),
                    Optional.of(want.definition()),
                    "drifted " + id + " (SET NOT NULL validates existing rows)"));
        }
        return changes;
    }

    /** The canonical of {@code fields}, with {@code key} replaced by {@code value}. */
    private static String canonicalWith(Map<String, String> fields, String key, String value) {
        final var merged = new LinkedHashMap<>(fields);
        merged.put(key, value);
        return merged.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    private static List<PlannedChange> manualColumn(ObjectId id, String reason) {
        return List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(), reason));
    }

    /** The {@code key=value} fields of a canonical definition — the
     *  {@code ;} separator is honoured only outside quoted literals, so a
     *  {@code ';'} inside a default expression or a label value cannot split
     *  a field. */
    private static Map<String, String> columnFields(String canonical) {
        final var fields = new LinkedHashMap<String, String>();
        for (final var part : splitCanonical(canonical)) {
            final var eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return fields;
    }

    /**
     * {@code ;}-split that skips single-quoted literals (with {@code ''}
     * escapes): canonical field values can carry quoted punctuation inside
     * expressions and labels.
     */
    private static List<String> splitCanonical(String text) {
        final var parts = new ArrayList<String>();
        var start = 0;
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\'') {
                i = literalEnd(text, i);
            } else if (text.charAt(i) == ';') {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    /** The index of the closing quote of the literal starting at {@code open} (or end of text). */
    private static int literalEnd(String text, int open) {
        var i = open + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return text.length();
    }

    /**
     * The labels of the canonical {@code labels='a','b'} field — a
     * comma-split that honours {@code ''} escapes inside each quoted label.
     */
    private static List<String> enumLabels(String canonical) {
        final var labels = columnFields(canonical).get("labels");
        if (labels == null) {
            return List.of();
        }
        final var out = new ArrayList<String>();
        var start = 0;
        for (var i = 0; i <= labels.length(); i++) {
            if (i == labels.length() || labels.charAt(i) == ',') {
                final var tok = labels.substring(start, i);
                if (tok.length() >= 2 && tok.startsWith("'") && tok.endsWith("'")) {
                    out.add(tok.substring(1, tok.length() - 1).replace("''", "'"));
                }
                start = i + 1;
            } else if (labels.charAt(i) == '\'') {
                i = literalEnd(labels, i);
            }
        }
        return out;
    }

    private static final Map<String, Integer> INT_TYPES = Map.of(
            "smallint", 1, "integer", 2, "bigint", 3);
    private static final Pattern TYPMOD = Pattern.compile("\\((\\d+)(?:,(\\d+))?\\)");

    /**
     * Whether {@code have → want} is a widening: a total and lossless type
     * change — every stored value converts without error, truncation or
     * semantic change. The allowlist is the integer chain, {@code character
     * varying} growth or conversion to {@code text}, {@code character(n)}
     * growth, and {@code numeric} growth that keeps the integer digits and
     * does not shrink the scale. Several of these
     * rewrite the table (the integer chain, {@code character} growth,
     * scale-increasing {@code numeric}); that is a locking concern (OI-6),
     * not a data-safety one. Notably excluded: {@code character → character
     * varying/text} strips trailing spaces, and {@code → character} changes
     * padding semantics.
     */
    private static boolean widens(String have, String want) {
        final var hb = base(have);
        final var wb = base(want);
        final var hr = INT_TYPES.get(hb);
        final var wr = INT_TYPES.get(wb);
        if (hr != null && wr != null && typmod(have).length == 0 && typmod(want).length == 0) {
            return wr > hr;
        }
        if (hb.equals("character varying")
                && (wb.equals("character varying") || wb.equals("text"))) {
            final var hm = typmod(have);
            final var wm = typmod(want);
            return wb.equals("text") || wm.length == 0 || (hm.length == 1 && wm[0] >= hm[0]);
        }
        if (hb.equals("character") && wb.equals("character")) {
            final var hm = typmod(have);
            final var wm = typmod(want);
            return hm.length == 1 && wm.length == 1 && wm[0] >= hm[0];
        }
        if (hb.equals("numeric") && wb.equals("numeric")) {
            final var hm = typmod(have);
            final var wm = typmod(want);
            // integer digits (p - s) must not shrink, or existing values overflow
            return wm.length == 0
                    || (hm.length == 2 && wm.length == 2
                            && wm[1] >= hm[1] && wm[0] - wm[1] >= hm[0] - hm[1]);
        }
        return false;
    }

    private static String base(String type) {
        final var p = type.indexOf('(');
        return (p < 0 ? type : type.substring(0, p)).strip();
    }

    private static int[] typmod(String type) {
        final var m = TYPMOD.matcher(type);
        if (!m.find()) {
            return new int[0];
        }
        return m.group(2) != null
                ? new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) }
                : new int[] { Integer.parseInt(m.group(1)) };
    }

    private static PlannedChange additive(ObjectId id, SchemaObject want, String ns, String reason) {
        return new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                createStatements(want, ns), Optional.of(want.definition()), reason);
    }

    /** The CREATE statement plus its post-create fragment (trigger enablement, replica identity). */
    private static List<String> createStatements(SchemaObject want, String ns) {
        final var statements = new ArrayList<String>();
        statements.add(bind(want, PgCatalog.CREATE_SQL, ns));
        final var post = want.attributes().get(PgCatalog.POST_SQL);
        if (post != null) {
            statements.add(bind(post, ns));
        }
        return statements;
    }

    /** A DROP change plus a CREATE change — the two halves of a recreate (4.4). */
    private static List<PlannedChange> recreate(ObjectId id, SchemaObject want, String ns,
                                                String reason) {
        return List.of(
                new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                        List.of(bind(want, PgCatalog.DROP_SQL, ns)), Optional.empty(), reason),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        createStatements(want, ns), Optional.of(want.definition()), reason));
    }

    private static String seqRef(ObjectId id) {
        return TOKEN + "." + PgIdentifiers.quote(id.name());
    }

    private static String bind(SchemaObject object, String attrKey, String ns) {
        return bind(object.attributes().get(attrKey), ns);
    }

    private static String bind(String sql, String ns) {
        return sql.replace(TOKEN, PgIdentifiers.quote(ns));
    }

    /** Extracts the {@code RETURNS <type>} clause from a normalised function definition. */
    private static String returnTypeOf(String functionDef) {
        final var matcher = Pattern
                .compile("\\bRETURNS (.+?) LANGUAGE\\b").matcher(functionDef);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ---- SPI helpers -------------------------------------------------------

    public static Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) {
        if (created.id().kind() != ObjectKind.TABLE) {
            return Set.of();
        }
        final var table = created.id();
        return desired.objects().values().stream()
                .filter(o -> (o.id().kind() == ObjectKind.COLUMN
                        && o.id().parent().orElse("").equals(table.name()))
                        || (o.id().kind() == ObjectKind.SEQUENCE
                                && o.dependsOn().contains(table)))
                .map(SchemaObject::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * OTHER categories whose stripped names occupy the relation/type
     * namespace — the relkind-prefixed relations plus {@code type:} (a
     * standalone type collides with a table's rowtype). Must match the
     * prefixes {@code PgCatalog} assigns to those objects.
     */
    private static final Set<String> RELATION_NAMESPACE_OTHERS = Set.of(
            "inherited", "foreign", "partition", "partindex",
            "composite", "toast", "relation", "type");

    public static Set<String> nameScopes(ObjectId id) {
        return switch (id.kind()) {
            case OTHER -> {
                final var name = id.name();
                final var colon = name.indexOf(':');
                yield colon > 0
                        && RELATION_NAMESPACE_OTHERS.contains(name.substring(0, colon))
                        ? Set.of("relation:" + name.substring(colon + 1))
                        : Set.of("relation:" + name);
            }
            // types and domains share the type namespace, which collides
            // with the relation namespace through composite-type rowtypes
            case TABLE, VIEW, MATERIALIZED_VIEW, SEQUENCE, INDEX, TYPE, DOMAIN ->
                    Set.of("relation:" + id.name());
            case PRIMARY_KEY, UNIQUE -> Set.of(
                    "relation:" + id.name(),
                    "constraint:" + id.parent().orElse("") + "." + id.name());
            case FOREIGN_KEY, CHECK ->
                    Set.of("constraint:" + id.parent().orElse("") + "." + id.name());
            case COLUMN, ROUTINE, TRIGGER, POLICY, EXTENSION, COMMENT -> Set.of();
        };
    }
}
