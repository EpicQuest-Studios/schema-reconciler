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
import studio.epicquest.schema.reconciler.model.ChangeHazard;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.LockLevel;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.RenderOptions;

/**
 * The rendering rules of spec A.6. Pre-computed statement fragments live in
 * {@code SchemaObject.attributes} ({@link PgCatalog#CREATE_SQL} etc.) with
 * {@code ${namespace}} tokens; {@link #bind} substitutes the quoted target.
 */
public final class PgRender {

    private static final String TOKEN = "${namespace}";

    /** Full-table lock of a DROP/recreate or a destructive ALTER. */
    private static final ChangeHazard LOCK_EXCLUSIVE =
            new ChangeHazard(LockLevel.ACCESS_EXCLUSIVE, false, false);
    /** {@code SHARE ROW EXCLUSIVE} — constraint/index adds that still allow writes. */
    private static final ChangeHazard LOCK_ROW_EXCLUSIVE =
            new ChangeHazard(LockLevel.SHARE_ROW_EXCLUSIVE, false, false);
    /** A CHECK/FOREIGN KEY add scans existing rows to validate them. */
    private static final ChangeHazard VALIDATES =
            new ChangeHazard(LockLevel.SHARE_ROW_EXCLUSIVE, false, true);
    /** {@code CREATE INDEX} (plain) — SHARE on the table: reads pass, writes block. */
    private static final ChangeHazard INDEX_BUILD =
            new ChangeHazard(LockLevel.SHARE, false, false);
    /** {@code CREATE/DROP INDEX CONCURRENTLY} — SHARE UPDATE EXCLUSIVE, off-transaction. */
    private static final ChangeHazard INDEX_CONCURRENT =
            new ChangeHazard(LockLevel.SHARE_UPDATE_EXCLUSIVE, false, false);
    /** An {@code ALTER COLUMN TYPE} whose cast is not binary-compatible rewrites the table. */
    private static final ChangeHazard REWRITES =
            new ChangeHazard(LockLevel.ACCESS_EXCLUSIVE, true, false);

    private PgRender() {
    }

    public static List<PlannedChange> render(Difference difference, SchemaSnapshot desired,
                                             String targetNamespace) {
        return render(difference, desired, targetNamespace, RenderOptions.DEFAULT);
    }

    public static List<PlannedChange> render(Difference difference, SchemaSnapshot snapshot,
                                             String targetNamespace, RenderOptions options) {
        final var id = difference.id();
        final var want = snapshot.objects().get(id);
        return switch (difference.type()) {
            case MISSING -> missing(id, want, snapshot, targetNamespace, options);
            case DRIFTED -> drifted(id, want, difference, snapshot, targetNamespace, options);
            // under a pruning policy the second parameter is the ACTUAL
            // snapshot (spec 5.5): the unexpected object's drop fragments
            // live there — nothing desired exists to read
            case UNEXPECTED -> unexpected(id, want, snapshot, targetNamespace, options);
            default -> throw new PlanningException("difference " + difference.id()
                    + " of type " + difference.type() + " is not renderable");
        };
    }

    // ---- MISSING ---------------------------------------------------------

    private static List<PlannedChange> missing(ObjectId id, SchemaObject want,
                                               SchemaSnapshot desired, String ns,
                                               RenderOptions options) {
        return switch (id.kind()) {
            case TABLE -> List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, createTableStatements(id, desired, ns),
                    Optional.of(want.definition()), "missing " + id));
            case FOREIGN_TABLE -> List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, createForeignTableStatements(id, desired, ns),
                    Optional.of(want.definition()), "missing " + id));
            case COLUMN -> missingColumn(id, want, desired, ns);
            case INDEX -> List.of(indexCreate(id, want, desired, ns, "missing " + id,
                    options));
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, EXCLUSION ->
                    List.of(additive(id, want, ns, "missing " + id, false,
                            id.kind() == ObjectKind.FOREIGN_KEY
                                    || id.kind() == ObjectKind.CHECK
                                    ? VALIDATES : LOCK_ROW_EXCLUSIVE));
            case VIEW, TRIGGER, TYPE, DOMAIN, POLICY, EXTENSION, STATISTICS, RULE,
                    COLLATION, CONVERSION, PARTITION, PUBLICATION, CAST,
                    TRANSFORM ->
                    List.of(additive(id, want, ns, "missing " + id, false,
                            id.kind() == ObjectKind.PARTITION
                                    ? LOCK_EXCLUSIVE : ChangeHazard.NONE));
            case MATERIALIZED_VIEW -> List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, matviewCreateStatements(want, ns),
                    Optional.of(want.definition()), "missing " + id));
            case COMMENT -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "missing comment on " + id));
            case SECURITY_LABEL -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "missing label on " + id));
            case OWNERSHIP -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "missing ownership on " + id));
            case PRIVILEGES, DEFAULT_PRIVILEGES -> privilegesChanges(id, want,
                    List.of(), ns, "missing privileges on " + id);
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
            case ROUTINE, AGGREGATE -> List.of(additive(id, want, ns, "missing " + id));
            case TEXT_SEARCH_TEMPLATE, TEXT_SEARCH_PARSER,
                    TEXT_SEARCH_DICTIONARY, TEXT_SEARCH_CONFIGURATION ->
                    List.of(additive(id, want, ns, "missing " + id));
            case OPERATOR, OPERATOR_CLASS, OPERATOR_FAMILY ->
                    List.of(additive(id, want, ns, "missing " + id));
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
        final var partkey = tableAttrs.get(PgCatalog.PARTKEY);
        if (partkey != null) {
            create.append(" PARTITION BY ").append(partkey);
        }
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

    /** CREATE FOREIGN TABLE folding in every desired column, then SERVER/OPTIONS (A.6, 5.8.4). */
    private static List<String> createForeignTableStatements(ObjectId table,
                                                           SchemaSnapshot desired, String ns) {
        final var columns = desired.objects().values().stream()
                .filter(o -> o.id().kind() == ObjectKind.COLUMN
                        && o.id().parent().orElse("").equals(table.name()))
                .sorted(Comparator.comparingInt(c -> Integer.parseInt(
                        c.attributes().getOrDefault(PgCatalog.ATTNUM, "0"))))
                .toList();
        final var attrs = desired.objects().get(table).attributes();
        final var create = new StringBuilder("CREATE FOREIGN TABLE ")
                .append(PgIdentifiers.quote(ns)).append('.')
                .append(PgIdentifiers.quote(table.name())).append(" (\n")
                .append(columns.stream()
                        .map(c -> "    " + c.attributes().get(PgCatalog.COLUMN_SQL))
                        .collect(Collectors.joining(",\n")))
                .append("\n) SERVER ")
                .append(PgIdentifiers.quote(attrs.get(PgCatalog.FT_SERVER)));
        final var options = attrs.getOrDefault(PgCatalog.FT_OPTIONS, "");
        if (!options.isEmpty()) {
            create.append(" OPTIONS (").append(options).append(')');
        }
        return List.of(bind(create.toString(), ns));
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
        return List.of(bind(populated ? create
                : PgSql.dropTerminator(create) + " WITH NO DATA", ns));
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
        // a partition column is inherited — ADD COLUMN runs on the
        // partitioned root and propagates; IF NOT EXISTS absorbs the
        // parent's own identical change
        final var routed = partitionParent(parent, desired);
        final var alter = "ALTER TABLE " + PgIdentifiers.quote(ns) + "."
                + PgIdentifiers.quote(routed != null ? routed : parent)
                + " ADD COLUMN IF NOT EXISTS "
                + want.attributes().get(PgCatalog.COLUMN_SQL);
        final var notnull = Boolean.parseBoolean(want.attributes().get(PgCatalog.NOTNULL));
        if (!notnull) {
            return List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, List.of(bind(alter, ns)),
                    Optional.of(want.definition()), "missing nullable column " + id.name(),
                    false, LOCK_EXCLUSIVE));
        }
        final var defexpr = want.attributes().get(PgCatalog.DEFAULT);
        if (defexpr != null && PgIdentifiers.isConstantDefault(defexpr)) {
            return List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, List.of(bind(alter, ns)),
                    Optional.of(want.definition()),
                    "missing NOT NULL column " + id.name() + " with a constant default",
                    false, LOCK_EXCLUSIVE));
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
                        List.of(bind(want, PgCatalog.DROP_SQL, ns)), Optional.empty(), reason,
                        false, LOCK_EXCLUSIVE),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.DESTRUCTIVE,
                        statements, Optional.of(want.definition()), reason,
                        false, LOCK_EXCLUSIVE));
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
                Optional.of(want.definition()), "drifted " + id + " (row-level security flag)",
                false, LOCK_EXCLUSIVE));
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
                    Optional.of(expected), "drifted " + id, false, LOCK_EXCLUSIVE));
        }
        if (!validating.isEmpty()) {
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE,
                    validating.stream().map(s -> bind(s, ns)).toList(),
                    Optional.of(want.definition()),
                    "drifted " + id + " (validates existing values)",
                    false, new ChangeHazard(LockLevel.ACCESS_EXCLUSIVE,
                            false, true)));
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
                                               Difference difference, SchemaSnapshot desired,
                                               String ns, RenderOptions options) {
        return switch (id.kind()) {
            case COLUMN -> driftedColumn(id, want, difference, desired, ns);
            case TABLE -> driftedTable(id, want, difference, ns);
            case INDEX -> indexRecreate(id, want, desired, ns, "drifted " + id,
                    options);
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, EXCLUSION ->
                    recreate(id, want, ns, "drifted " + id, false,
                            LOCK_ROW_EXCLUSIVE);
            case VIEW, TRIGGER, POLICY, STATISTICS, RULE, COLLATION, CONVERSION,
                    PUBLICATION, CAST, TRANSFORM ->
                    recreate(id, want, ns, "drifted " + id);
            // a bound change re-homes rows — DESTRUCTIVE, not REBUILD
            case PARTITION -> List.of(
                    new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.DESTRUCTIVE,
                            List.of(bind(want, PgCatalog.DROP_SQL, ns)),
                            Optional.empty(), "drifted " + id, false, LOCK_EXCLUSIVE),
                    new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.DESTRUCTIVE,
                            List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                            Optional.of(want.definition()), "drifted " + id,
                            false, LOCK_EXCLUSIVE));
            // no local data — a server/options change is a plain drop+create
            case FOREIGN_TABLE -> List.of(
                    new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                            List.of(bind(want, PgCatalog.DROP_SQL, ns)),
                            Optional.empty(), "drifted " + id, false, LOCK_EXCLUSIVE),
                    new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.REBUILD,
                            createForeignTableStatements(id, desired, ns),
                            Optional.of(want.definition()), "drifted " + id,
                            false, LOCK_EXCLUSIVE));
            case MATERIALIZED_VIEW -> driftedMatview(id, want, ns);
            // a composite's attribute drift and a range's pg_range drift
            // drop and recreate the type — no in-place recipe exists;
            // enums keep the label-aware path
            case TYPE -> want.definition().canonical().startsWith("typtype=e")
                    ? driftedEnum(id, want, difference, ns)
                    : recreate(id, want, ns, "drifted " + id);
            case DOMAIN -> driftedDomain(id, want, difference, ns);
            case COMMENT -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "drifted comment on " + id));
            case SECURITY_LABEL -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "drifted label on " + id));
            case OWNERSHIP -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                    Optional.of(want.definition()), "drifted ownership on " + id));
            case PRIVILEGES, DEFAULT_PRIVILEGES -> privilegesChanges(id, want,
                    difference.actual().map(d -> aclTokens(d.canonical()))
                            .orElse(List.of()),
                    ns, "drifted privileges on " + id);
            case SEQUENCE -> {
                final var statements = new ArrayList<String>();
                final var seqOptions = want.attributes().get(PgCatalog.OPTIONS_SQL);
                statements.add(bind("ALTER SEQUENCE " + seqRef(id) + " " + seqOptions, ns));
                final var owned = want.attributes().get(PgCatalog.OWNED_SQL);
                if (owned != null) {
                    statements.add(bind(owned, ns));
                }
                // persistence is not an ALTER SEQUENCE option — it has its own
                // SET LOGGED/UNLOGGED form (since PostgreSQL 10)
                final var wantPersist = columnFields(want.definition().canonical())
                        .getOrDefault("relpersistence", "p");
                final var havePersist = columnFields(difference.actual()
                        .map(d -> d.canonical()).orElse(""))
                        .getOrDefault("relpersistence", "p");
                if (!wantPersist.equals(havePersist)) {
                    statements.add(bind("ALTER SEQUENCE " + seqRef(id)
                            + (wantPersist.equals("u") ? " SET UNLOGGED" : " SET LOGGED"), ns));
                }
                yield List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                        ChangeSafety.DESTRUCTIVE, statements, Optional.of(want.definition()),
                        "drifted " + id, false, LOCK_EXCLUSIVE));
            }
            case ROUTINE -> {
                final var haveDef = difference.actual()
                        .map(d -> d.canonical()).orElse("");
                final var wantProcedure = "p".equals(
                        want.attributes().getOrDefault(PgCatalog.PROKIND, "f"));
                final var haveProcedure = haveDef.startsWith(
                        "CREATE OR REPLACE PROCEDURE");
                // a procedure has no RETURNS clause to compare; a function
                // alters in place only while its return type is unchanged
                final var inPlace = wantProcedure
                        ? haveProcedure
                        : !haveProcedure && Objects.equals(
                                want.attributes().get(PgCatalog.RETURN_TYPE),
                                returnTypeOf(haveDef));
                if (inPlace) {
                    yield List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                            ChangeSafety.REBUILD, List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                            Optional.of(want.definition()), "drifted " + id));
                }
                yield recreate(id, want, ns, "drifted " + id + " (return type changed)");
            }
            // aggregates have no CREATE OR REPLACE form — every option
            // change recreates
            case AGGREGATE -> recreate(id, want, ns, "drifted " + id);
            // text-search objects compose their canonical from the catalog
            // row — any field drift recreates (no OR REPLACE form exists)
            case TEXT_SEARCH_TEMPLATE, TEXT_SEARCH_PARSER,
                    TEXT_SEARCH_DICTIONARY, TEXT_SEARCH_CONFIGURATION ->
                    recreate(id, want, ns, "drifted " + id);
            case OPERATOR, OPERATOR_CLASS, OPERATOR_FAMILY ->
                    recreate(id, want, ns, "drifted " + id);
            default -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(),
                    "drifted " + id + " requires a manual change"));
        };
    }

    /**
     * The GRANT/REVOKE statement list converging a PRIVILEGES object's acl
     * token set onto the desired one ({@code grantee:PRIVILEGE[*]}, A.5).
     * Implicit owner rights ({@code <owner>} grantees) are skipped — they
     * are not grants and cannot be revoked. A grant-option-only difference
     * renders {@code REVOKE GRANT OPTION FOR} so the base privilege stays.
     */
    private static List<PlannedChange> privilegesChanges(ObjectId id, SchemaObject want,
            List<String> actual, String ns, String reason) {
        final var target = bind(want, PgCatalog.GRANT_TARGET, ns);
        // a column-level grant parenthesizes its column after the privilege
        // word: GRANT SELECT ("c") ON TABLE t — absent on object-level grants
        final var column = want.attributes().get(PgCatalog.GRANT_COLUMN);
        final var colClause = column == null ? "" : " (" + bind(column, ns) + ")";
        // a scoped default-privileges object prefixes every statement with
        // ALTER DEFAULT PRIVILEGES FOR ROLE "r" IN SCHEMA "ns"
        final var prefix = want.attributes().get(PgCatalog.GRANT_PREFIX);
        final var pfx = prefix == null ? "" : bind(prefix, ns) + " ";
        final var desired = aclTokens(want.definition().canonical());
        final var stmts = new ArrayList<String>();
        for (final var tok : desired) {
            final var g = grantee(tok);
            if (g.equals("<owner>") || actual.contains(tok)) {
                continue;
            }
            // the base privilege is held if the actual side has it with or
            // without the grant option
            final var bare = tokenSansOption(tok);
            final var held = actual.contains(bare) || actual.contains(bare + "*");
            // missing entirely, or held without the grant option — a GRANT
            // upgrades a bare grant to grantable either way
            if (!held || grantable(tok)) {
                stmts.add(pfx + "GRANT " + privilege(tok) + colClause + " ON " + target
                        + " TO " + granteeSql(g)
                        + (grantable(tok) ? " WITH GRANT OPTION" : ""));
            }
        }
        for (final var tok : actual) {
            final var g = grantee(tok);
            if (g.equals("<owner>") || desired.contains(tok)) {
                continue;
            }
            // the privilege is wanted when the desired side holds it in
            // either form — bare or grantable
            final var bare = tokenSansOption(tok);
            final var wanted = desired.contains(bare) || desired.contains(bare + "*");
            if (grantable(tok) && desired.contains(bare)) {
                // same grant, keep the base privilege — drop only the option
                stmts.add(pfx + "REVOKE GRANT OPTION FOR " + privilege(tok) + colClause
                        + " ON " + target + " FROM " + granteeSql(g));
            } else if (!wanted) {
                stmts.add(pfx + "REVOKE " + privilege(tok) + colClause + " ON " + target
                        + " FROM " + granteeSql(g));
            }
            // else: held bare, wanted grantable — the GRANT above upgrades it
        }
        if (stmts.isEmpty()) {
            return List.of();
        }
        return List.of(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                List.copyOf(stmts), Optional.of(want.definition()), reason));
    }

    /** The {@code grantee:PRIVILEGE[*]} token list of a {@code privileges=} canonical. */
    private static List<String> aclTokens(String canonical) {
        var s = canonical;
        if (s.startsWith("privileges=")) {
            s = s.substring("privileges=".length());
        }
        return s.isEmpty() ? List.of() : List.of(s.split(","));
    }

    /** The grantee of a {@code grantee:PRIVILEGE[*]} token — the last colon splits it. */
    private static String grantee(String token) {
        return token.substring(0, token.lastIndexOf(':'));
    }

    /** The privilege word of a {@code grantee:PRIVILEGE[*]} token. */
    private static String privilege(String token) {
        final var p = token.substring(token.lastIndexOf(':') + 1);
        return p.endsWith("*") ? p.substring(0, p.length() - 1) : p;
    }

    private static boolean grantable(String token) {
        return token.endsWith("*");
    }

    /** The same token without a grant-option marker. */
    private static String tokenSansOption(String token) {
        return token.endsWith("*") ? token.substring(0, token.length() - 1) : token;
    }

    /** {@code PUBLIC} stays a keyword; a role name is quoted. */
    private static String granteeSql(String grantee) {
        return grantee.equals("PUBLIC") ? grantee : PgIdentifiers.quote(grantee);
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
                                                     Difference difference,
                                                     SchemaSnapshot desired, String ns) {
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
            // an inherited column's TYPE runs on the partitioned root and
            // propagates — the parent's own identical change does it first
            final var routed = partitionParent(id.parent().orElse(""), desired);
            safe.add(routed == null ? alter + "TYPE " + wantType
                    : "ALTER TABLE " + TOKEN + "." + PgIdentifiers.quote(routed)
                            + " ALTER COLUMN " + PgIdentifiers.quote(id.name())
                            + " TYPE " + wantType);
        }
        final var wantDefault = wantFields.get("default");
        if (!Objects.equals(wantDefault, haveFields.get("default"))) {
            safe.add(alter + (wantDefault != null
                    ? "SET DEFAULT " + wantDefault : "DROP DEFAULT"));
        }
        // storage strategy and compression are declarable (column storage
        // parameters in CREATE TABLE) and re-point only future writes —
        // in-place safe alterations like SET DEFAULT
        final var wantStorage = wantFields.get("storage");
        if (wantStorage != null && !Objects.equals(wantStorage, haveFields.get("storage"))) {
            safe.add(alter + "SET STORAGE "
                    + STORAGE_WORDS.getOrDefault(wantStorage, "EXTENDED"));
        }
        final var wantCompression = wantFields.get("compression");
        if (wantCompression != null
                && !Objects.equals(wantCompression, haveFields.get("compression"))) {
            safe.add(alter + "SET COMPRESSION "
                    + COMPRESSION_WORDS.getOrDefault(wantCompression, "default"));
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
            // an ALTER COLUMN TYPE can rewrite the table even on the
            // widening allowlist (e.g. integer -> bigint)
            final var hazard = !Objects.equals(wantType, haveFields.get("type"))
                    ? REWRITES : LOCK_EXCLUSIVE;
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER, ChangeSafety.REBUILD,
                    safe.stream().map(s -> bind(s, ns)).toList(),
                    Optional.of(expected), "drifted " + id, false, hazard));
        }
        if (setNotNull) {
            changes.add(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE,
                    List.of(bind(alter + "SET NOT NULL", ns)),
                    Optional.of(want.definition()),
                    "drifted " + id + " (SET NOT NULL validates existing rows)",
                    false, new ChangeHazard(LockLevel.ACCESS_EXCLUSIVE,
                            false, true)));
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

    /**
     * The name of the topmost partitioned ancestor a column's parent inherits
     * from — {@code null} when the parent is not a managed partition. PG
     * rejects inherited column alterations on every partition, including
     * intermediate ones in a multi-level hierarchy, so the ALTER runs on the
     * partitioned root and propagates down to every level.
     */
    private static String partitionParent(String parent, SchemaSnapshot desired) {
        final var partitions = desired.objects().keySet().stream()
                .filter(o -> o.kind() == ObjectKind.PARTITION)
                .collect(Collectors.toMap(ObjectId::name, o -> o.parent().orElse("")));
        final var seen = new LinkedHashSet<String>();
        String current = parent;
        while (seen.add(current) && partitions.containsKey(current)) {
            current = partitions.get(current);
        }
        return current.equals(parent) ? null : current;
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
     * {@code ;}-split that skips quoted literals, quoted identifiers and
     * dollar-quoted bodies: canonical field values can carry quoted
     * punctuation inside expressions, labels and identifier names.
     */
    private static List<String> splitCanonical(String text) {
        return PgSql.splitOutsideLiterals(text, ';');
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
        for (final var tok : PgSql.splitOutsideLiterals(labels, ',')) {
            if (tok.length() >= 2 && tok.startsWith("'") && tok.endsWith("'")) {
                out.add(tok.substring(1, tok.length() - 1).replace("''", "'"));
            }
        }
        return out;
    }

    /** {@code attstorage} letter to the {@code SET STORAGE} keyword. */
    private static final Map<String, String> STORAGE_WORDS = Map.of(
            "p", "PLAIN", "e", "EXTERNAL", "m", "MAIN", "x", "EXTENDED");

    /** {@code attcompression} letter to the {@code SET COMPRESSION} method. */
    private static final Map<String, String> COMPRESSION_WORDS = Map.of(
            "p", "pglz", "l", "lz4", "", "default");

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

    private static PlannedChange additive(ObjectId id, SchemaObject want, String ns,
                                          String reason) {
        return additive(id, want, ns, reason, false, ChangeHazard.NONE);
    }

    private static PlannedChange additive(ObjectId id, SchemaObject want, String ns,
                                          String reason, boolean nonTransactional,
                                          ChangeHazard hazard) {
        return new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                createStatements(want, ns), Optional.of(want.definition()), reason,
                nonTransactional, hazard);
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
        return recreate(id, want, ns, reason, false, LOCK_EXCLUSIVE);
    }

    private static List<PlannedChange> recreate(ObjectId id, SchemaObject want, String ns,
                                                String reason, boolean nonTransactional,
                                                ChangeHazard hazard) {
        return List.of(
                new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                        List.of(bind(want, PgCatalog.DROP_SQL, ns)), Optional.empty(), reason,
                        nonTransactional, hazard),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        createStatements(want, ns), Optional.of(want.definition()), reason,
                        nonTransactional, hazard));
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

    // ---- UNEXPECTED (5.5 pruning) -------------------------------------------

    /**
     * Data-bearing kinds: their standalone drop is DESTRUCTIVE because the
     * rows are lost. Every other kind's standalone drop is a REBUILD —
     * removing it cannot destroy stored data.
     */
    private static final Set<ObjectKind> DESTRUCTIVE_PRUNE = Set.of(
            ObjectKind.TABLE, ObjectKind.COLUMN, ObjectKind.MATERIALIZED_VIEW,
            ObjectKind.PARTITION, ObjectKind.SEQUENCE);

    /**
     * A standalone DROP for an object absent from the desired state (5.5).
     * {@code have} is the ACTUAL object — its attributes hold the drop
     * fragments. An unexpected {@code COLUMN} on an expected table renders
     * {@code ALTER TABLE ... DROP COLUMN}; one on an unexpected table is
     * suppressed upstream (the parent's own drop covers it). Mirrored kinds
     * map to their null/revoke/owner-reset forms; COMMENT and OWNERSHIP
     * have no {@code DROP_SQL} so their statements derive from the create
     * fragment.
     */
    private static List<PlannedChange> unexpected(ObjectId id, SchemaObject have,
                                                  SchemaSnapshot actual, String ns,
                                                  RenderOptions options) {
        final var safety = DESTRUCTIVE_PRUNE.contains(id.kind())
                ? ChangeSafety.DESTRUCTIVE : ChangeSafety.REBUILD;
        final var reason = "unexpected " + id;
        switch (id.kind()) {
            case COLUMN -> {
                // a pruned column on a renamed parent drops through the new
                // name — the old one no longer exists once the rename ran
                final var parent = options.renamedParents()
                        .getOrDefault(id.parent().orElse(""), id.parent().orElse(""));
                final var routed = partitionParent(parent, actual);
                return List.of(new PlannedChange(0, id, ChangeAction.DROP, safety,
                        List.of(bind("ALTER TABLE " + TOKEN + "."
                                + PgIdentifiers.quote(routed != null ? routed : parent)
                                + " DROP COLUMN " + PgIdentifiers.quote(id.name()), ns)),
                        Optional.empty(), reason, false, LOCK_EXCLUSIVE));
            }
            case TABLE -> {
                // a plain table never carries DROP_SQL — its drift path is
                // the destructive manual one, so the prune drop is built here
                return List.of(new PlannedChange(0, id, ChangeAction.DROP, safety,
                        List.of(bind("DROP TABLE " + TOKEN + "."
                                + PgIdentifiers.quote(id.name()), ns)),
                        Optional.empty(), reason, false, LOCK_EXCLUSIVE));
            }
            case INDEX -> {
                return List.of(indexDrop(id, have, actual, ns, reason, options));
            }
            case COMMENT -> {
                // COMMENT ON <target> IS NULL — the create fragment's
                // literal is always the statement's tail
                final var create = have.attributes().getOrDefault(PgCatalog.CREATE_SQL, "");
                return List.of(new PlannedChange(0, id, ChangeAction.ALTER, safety,
                        List.of(bind(create.replaceAll(" IS '(?:[^']|'')*'$", " IS NULL"), ns)),
                        Optional.empty(), reason));
            }
            case SECURITY_LABEL -> {
                return List.of(new PlannedChange(0, id, ChangeAction.ALTER, safety,
                        List.of(bind(have, PgCatalog.DROP_SQL, ns)),
                        Optional.empty(), reason));
            }
            case OWNERSHIP -> {
                // ALTER <target> OWNER TO CURRENT_USER — surrender the
                // scripted owner to whoever runs the reconcile
                final var create = have.attributes().getOrDefault(PgCatalog.CREATE_SQL, "");
                return List.of(new PlannedChange(0, id, ChangeAction.ALTER, safety,
                        List.of(bind(create.replaceAll(" OWNER TO \"(?:[^\"]|\"\")*\"$",
                                " OWNER TO CURRENT_USER"), ns)),
                        Optional.empty(), reason));
            }
            case PRIVILEGES, DEFAULT_PRIVILEGES -> {
                final var target = bind(have, PgCatalog.GRANT_TARGET, ns);
                final var prefix = have.attributes().get(PgCatalog.GRANT_PREFIX);
                final var pfx = prefix == null ? "" : bind(prefix, ns) + " ";
                final var stmts = new ArrayList<String>();
                for (final var tok : aclTokens(have.definition().canonical())) {
                    final var g = grantee(tok);
                    if (g.equals("<owner>")) {
                        continue;
                    }
                    stmts.add(pfx + "REVOKE ALL PRIVILEGES ON " + target
                            + " FROM " + granteeSql(g));
                }
                if (stmts.isEmpty()) {
                    return List.of();
                }
                return List.of(new PlannedChange(0, id, ChangeAction.ALTER, safety,
                        stmts, Optional.empty(), reason));
            }
            default -> {
                final var drop = have.attributes().get(PgCatalog.DROP_SQL);
                if (drop == null) {
                    throw new PlanningException("unexpected " + id
                            + " has no drop statement");
                }
                return List.of(new PlannedChange(0, id, ChangeAction.DROP, safety,
                        List.of(bind(drop, ns)), Optional.empty(), reason,
                        false, safety == ChangeSafety.DESTRUCTIVE
                                ? LOCK_EXCLUSIVE : ChangeHazard.NONE));
            }
        }
    }

    // ---- concurrent indexes (5.6) -------------------------------------------

    /**
     * Whether the index's parent relation is a partitioned table —
     * PostgreSQL rejects {@code CREATE INDEX CONCURRENTLY} on one (the
     * partitioned index build is transactional anyway).
     */
    private static boolean parentPartitioned(ObjectId id, SchemaSnapshot snapshot) {
        for (final var o : snapshot.objects().values()) {
            if (o.id().name().equals(id.parent().orElse(null))
                    && o.id().parent().isEmpty()
                    && (o.id().kind() == ObjectKind.TABLE
                            || o.id().kind() == ObjectKind.PARTITION)
                    && o.attributes().get(PgCatalog.PARTKEY) != null) {
                return true;
            }
        }
        return false;
    }

    /** Whether concurrent index DDL applies to this index under the options. */
    private static boolean concurrentIndex(ObjectId id, SchemaSnapshot snapshot,
                                           RenderOptions options) {
        return options.concurrentIndexes() && !parentPartitioned(id, snapshot);
    }

    /**
     * {@code CREATE [UNIQUE] INDEX CONCURRENTLY} — the concurrently variant
     * of a missing index (5.6). The statement runs outside a transaction
     * ({@code nonTransactional}) under a SHARE UPDATE EXCLUSIVE lock; on a
     * partitioned parent it degrades to the plain transactional form.
     */
    private static PlannedChange indexCreate(ObjectId id, SchemaObject want,
                                             SchemaSnapshot snapshot, String ns, String reason,
                                             RenderOptions options) {
        if (!concurrentIndex(id, snapshot, options)) {
            return new PlannedChange(0, id, ChangeAction.CREATE,
                    ChangeSafety.ADDITIVE, createStatements(want, ns),
                    Optional.of(want.definition()), reason, false, INDEX_BUILD);
        }
        final var statements = createStatements(want, ns).stream()
                .map(s -> s.replaceFirst("^(CREATE\\s+(?:UNIQUE\\s+)?INDEX)\\s",
                        "$1 CONCURRENTLY "))
                .toList();
        return new PlannedChange(0, id, ChangeAction.CREATE,
                ChangeSafety.ADDITIVE, statements, Optional.of(want.definition()), reason,
                true, INDEX_CONCURRENT);
    }

    /**
     * {@code DROP INDEX CONCURRENTLY} — the concurrently variant of an
     * index drop, whether from a drifted recreate pair or a prune of an
     * unexpected index.
     */
    private static PlannedChange indexDrop(ObjectId id, SchemaObject have,
                                           SchemaSnapshot snapshot, String ns, String reason,
                                           RenderOptions options) {
        final var drop = bind(have, PgCatalog.DROP_SQL, ns);
        if (!concurrentIndex(id, snapshot, options)) {
            return new PlannedChange(0, id, ChangeAction.DROP,
                    ChangeSafety.REBUILD, List.of(drop), Optional.empty(), reason,
                    false, LOCK_EXCLUSIVE);
        }
        return new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                List.of(drop.replaceFirst("^DROP\\s+INDEX\\s", "DROP INDEX CONCURRENTLY ")),
                Optional.empty(), reason, true, INDEX_CONCURRENT);
    }

    /** The DROP+CREATE pair of a drifted index — concurrent when allowed. */
    private static List<PlannedChange> indexRecreate(ObjectId id, SchemaObject want,
                                                     SchemaSnapshot snapshot, String ns,
                                                     String reason, RenderOptions options) {
        if (!concurrentIndex(id, snapshot, options)) {
            return recreate(id, want, ns, reason, false, INDEX_BUILD);
        }
        return List.of(
                indexDrop(id, want, snapshot, ns, reason, options),
                indexCreate(id, want, snapshot, ns, reason, options));
    }

    // ---- RENAME (5.5.1) ------------------------------------------------------

    /**
     * An {@code ALTER ... RENAME} statement per kind; returns {@code null}
     * when the kind has no rename form — the caller falls back to
     * drop+create.
     */
    private static String renameSql(ObjectId from, ObjectId to) {
        final var qFrom = TOKEN + "." + PgIdentifiers.quote(from.name());
        final var qNew = PgIdentifiers.quote(to.name());
        final var qParent = from.parent().isEmpty() ? null
                : TOKEN + "." + PgIdentifiers.quote(from.parent().orElseThrow());
        switch (from.kind()) {
            case TABLE, PARTITION -> {
                return "ALTER TABLE " + qFrom + " RENAME TO " + qNew;
            }
            case FOREIGN_TABLE -> {
                return "ALTER FOREIGN TABLE " + qFrom + " RENAME TO " + qNew;
            }
            case VIEW -> {
                return "ALTER VIEW " + qFrom + " RENAME TO " + qNew;
            }
            case MATERIALIZED_VIEW -> {
                return "ALTER MATERIALIZED VIEW " + qFrom + " RENAME TO " + qNew;
            }
            case SEQUENCE -> {
                return "ALTER SEQUENCE " + qFrom + " RENAME TO " + qNew;
            }
            case INDEX -> {
                return "ALTER INDEX " + qFrom + " RENAME TO " + qNew;
            }
            case TYPE, DOMAIN -> {
                return "ALTER TYPE " + qFrom + " RENAME TO " + qNew;
            }
            case STATISTICS -> {
                return "ALTER STATISTICS " + qFrom + " RENAME TO " + qNew;
            }
            case COLLATION -> {
                return "ALTER COLLATION " + qFrom + " RENAME TO " + qNew;
            }
            case CONVERSION -> {
                return "ALTER CONVERSION " + qFrom + " RENAME TO " + qNew;
            }
            case COLUMN -> {
                return "ALTER TABLE " + qParent + " RENAME COLUMN "
                        + PgIdentifiers.quote(from.name()) + " TO " + qNew;
            }
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, EXCLUSION -> {
                return "ALTER TABLE " + qParent + " RENAME CONSTRAINT "
                        + PgIdentifiers.quote(from.name()) + " TO " + qNew;
            }
            case TRIGGER -> {
                return "ALTER TRIGGER " + PgIdentifiers.quote(from.name())
                        + " ON " + qParent + " RENAME TO " + qNew;
            }
            case POLICY -> {
                return "ALTER POLICY " + PgIdentifiers.quote(from.name())
                        + " ON " + qParent + " RENAME TO " + qNew;
            }
            case ROUTINE, AGGREGATE -> {
                // the signature rides the name — ALTER FUNCTION
                // <ns>.<name>(<sig>) RENAME TO <newname>
                final var sig = signatureOf(from.name());
                final var word = from.kind() == ObjectKind.AGGREGATE
                        ? "AGGREGATE" : "FUNCTION";
                return "ALTER " + word + " " + TOKEN + "."
                        + PgIdentifiers.quote(baseName(from.name())) + sig
                        + " RENAME TO " + PgIdentifiers.quote(baseName(to.name()));
            }
            default -> {
                return null;
            }
        }
    }

    /** The {@code (args)} tail of a routine id name, or {@code ""}. */
    private static String signatureOf(String name) {
        final var p = name.indexOf('(');
        return p < 0 ? "" : name.substring(p);
    }

    /** The name without a {@code (args)} signature tail. */
    private static String baseName(String name) {
        final var p = name.indexOf('(');
        return p < 0 ? name : name.substring(0, p);
    }

    /**
     * 5.5.1: an explicit rename hint renders {@code ALTER ... RENAME} as a
     * single ALTER change targeting the NEW name — a rename is a
     * metadata-only, data-safe operation (REBUILD). The change's expected
     * definition is the actual one (a rename does not alter the
     * definition); when the desired definition also changed, the plan adds
     * a following DRIFTED change on the new name.
     */
    public static List<PlannedChange> renderRename(ObjectId from, ObjectId to,
                                                   SchemaSnapshot actual,
                                                   SchemaSnapshot desired,
                                                   String targetNamespace) {
        final var sql = renameSql(from, to);
        if (sql == null) {
            throw new PlanningException("rename of " + from.kind() + " " + from
                    + " is not supported");
        }
        final var have = actual.objects().get(from);
        return List.of(new PlannedChange(0, to, ChangeAction.ALTER, ChangeSafety.REBUILD,
                List.of(bind(sql, targetNamespace)), Optional.of(have.definition()),
                "renamed " + from + " to " + to, false, LOCK_EXCLUSIVE));
    }

    // ---- SPI helpers -------------------------------------------------------

    public static Set<ObjectId> coveredChildren(Difference created, SchemaSnapshot desired) {
        if (created.id().kind() != ObjectKind.TABLE
                && created.id().kind() != ObjectKind.PARTITION
                && created.id().kind() != ObjectKind.FOREIGN_TABLE) {
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
            "inherited", "partindex",
            "toast", "relation", "type");

    public static Set<String> nameScopes(ObjectId id) {
        return switch (id.kind()) {
            case OTHER -> {
                final var name = id.name();
                final var colon = name.indexOf(':');
                if (colon > 0) {
                    final var category = name.substring(0, colon);
                    final var stripped = name.substring(colon + 1);
                    if (RELATION_NAMESPACE_OTHERS.contains(category)) {
                        yield Set.of("relation:" + stripped);
                    }
                    // unsupported routines share pg_proc with managed
                    // routines, keyed by name plus signature
                    if (category.equals("routine")) {
                        yield Set.of("routine:" + stripped);
                    }
                    // an unsupported constraint (a constraint trigger) is
                    // named "<parent>.<name>" like a managed one
                    if (category.equals("constraint")) {
                        yield Set.of("constraint:" + stripped);
                    }
                }
                yield Set.of("relation:" + name);
            }
            // types and domains share the type namespace, which collides
            // with the relation namespace through composite-type rowtypes
            case TABLE, VIEW, MATERIALIZED_VIEW, SEQUENCE, INDEX, TYPE, DOMAIN,
                    PARTITION, FOREIGN_TABLE ->
                    Set.of("relation:" + id.name());
            case ROUTINE, AGGREGATE -> Set.of("routine:" + id.name());
            case PRIMARY_KEY, UNIQUE, EXCLUSION -> Set.of(
                    "relation:" + id.name(),
                    "constraint:" + id.parent().orElse("") + "." + id.name());
            case FOREIGN_KEY, CHECK ->
                    Set.of("constraint:" + id.parent().orElse("") + "." + id.name());
            case COLUMN, TRIGGER, POLICY, EXTENSION, COMMENT, STATISTICS, RULE,
                    COLLATION, CONVERSION, SECURITY_LABEL, PUBLICATION,
                    OWNERSHIP, PRIVILEGES, DEFAULT_PRIVILEGES, CAST,
                    TRANSFORM, TEXT_SEARCH_TEMPLATE, TEXT_SEARCH_PARSER,
                    TEXT_SEARCH_DICTIONARY, TEXT_SEARCH_CONFIGURATION,
                    OPERATOR, OPERATOR_CLASS, OPERATOR_FAMILY -> Set.of();
        };
    }
}
