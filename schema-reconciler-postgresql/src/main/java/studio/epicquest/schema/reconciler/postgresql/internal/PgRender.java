package studio.epicquest.schema.reconciler.postgresql.internal;

import java.util.ArrayList;
import java.util.Comparator;
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
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK ->
                    List.of(additive(id, want, ns, "missing " + id));
            case INDEX -> List.of(additive(id, want, ns, "missing " + id));
            case VIEW -> List.of(additive(id, want, ns, "missing " + id));
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
            case TRIGGER -> {
                final var statements = new ArrayList<String>();
                statements.add(bind(want, PgCatalog.CREATE_SQL, ns));
                final var post = want.attributes().get(PgCatalog.POST_SQL);
                if (post != null) {
                    statements.add(bind(post, ns));
                }
                yield List.of(new PlannedChange(0, id, ChangeAction.CREATE,
                        ChangeSafety.ADDITIVE, statements, Optional.of(want.definition()),
                        "missing " + id));
            }
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
        statements.add(bind(create.toString(), ns));
        sequences.stream()
                .map(s -> s.attributes().get(PgCatalog.OWNED_SQL))
                .filter(Objects::nonNull)
                .map(sql -> bind(sql, ns))
                .forEach(statements::add);
        return statements;
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

    private static List<PlannedChange> drifted(ObjectId id, SchemaObject want,
                                               Difference difference, String ns) {
        return switch (id.kind()) {
            case PRIMARY_KEY, UNIQUE, FOREIGN_KEY, CHECK, INDEX, VIEW ->
                    recreate(id, want, ns, "drifted " + id);
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
            case TRIGGER -> {
                final var w = want;
                final var pair = new ArrayList<PlannedChange>(recreate(id, w, ns, "drifted " + id));
                final var post = w.attributes().get(PgCatalog.POST_SQL);
                if (post != null) {
                    final var create = pair.get(1);
                    final var statements = new ArrayList<String>(create.statements());
                    statements.add(bind(post, ns));
                    pair.set(1, new PlannedChange(0, create.target(), create.action(),
                            create.safety(), statements, create.expected(), create.reason()));
                }
                yield pair;
            }
            default -> List.of(new PlannedChange(0, id, ChangeAction.ALTER,
                    ChangeSafety.DESTRUCTIVE, List.of(), Optional.empty(),
                    "drifted " + id + " requires a manual change"));
        };
    }

    private static PlannedChange additive(ObjectId id, SchemaObject want, String ns, String reason) {
        return new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.ADDITIVE,
                List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                Optional.of(want.definition()), reason);
    }

    /** A DROP change plus a CREATE change — the two halves of a recreate (4.4). */
    private static List<PlannedChange> recreate(ObjectId id, SchemaObject want, String ns,
                                                String reason) {
        return List.of(
                new PlannedChange(0, id, ChangeAction.DROP, ChangeSafety.REBUILD,
                        List.of(bind(want, PgCatalog.DROP_SQL, ns)), Optional.empty(), reason),
                new PlannedChange(0, id, ChangeAction.CREATE, ChangeSafety.REBUILD,
                        List.of(bind(want, PgCatalog.CREATE_SQL, ns)),
                        Optional.of(want.definition()), reason));
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
    static String returnTypeOf(String functionDef) {
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

    public static Set<String> nameScopes(ObjectId id) {
        return switch (id.kind()) {
            case TABLE, VIEW, SEQUENCE, INDEX, OTHER -> Set.of("relation:" + id.name());
            case PRIMARY_KEY, UNIQUE -> Set.of(
                    "relation:" + id.name(),
                    "constraint:" + id.parent().orElse("") + "." + id.name());
            case FOREIGN_KEY, CHECK ->
                    Set.of("constraint:" + id.parent().orElse("") + "." + id.name());
            case COLUMN, ROUTINE, TRIGGER -> Set.of();
        };
    }
}
