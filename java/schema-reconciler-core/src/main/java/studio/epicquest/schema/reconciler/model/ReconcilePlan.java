package studio.epicquest.schema.reconciler.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import studio.epicquest.schema.reconciler.internal.Json;
import studio.epicquest.schema.reconciler.internal.PlanRenderer;

/**
 * An inspectable plan (spec 4.4). {@link #toSql()} serialises it as plain text:
 * a deterministic header plus one comment block per change; the output is
 * byte-identical for identical input (spec 5.2). {@link #toJson()} serialises
 * it as machine-readable JSON for later {@code SchemaReconciler.applyJson}.
 */
public record ReconcilePlan(
        String targetNamespace,
        String dialectId,
        String actualFingerprint,
        String desiredFingerprint,
        List<Difference> differences,
        List<PlannedChange> changes) {

    public ReconcilePlan {
        Objects.requireNonNull(targetNamespace, "targetNamespace");
        Objects.requireNonNull(dialectId, "dialectId");
        Objects.requireNonNull(actualFingerprint, "actualFingerprint");
        Objects.requireNonNull(desiredFingerprint, "desiredFingerprint");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public String toSql() {
        return PlanRenderer.render(this);
    }

    /**
     * The machine-readable serialisation (schemaVersion 1): keys in a fixed
     * order, changes in plan order — byte-identical for identical input.
     */
    public String toJson() {
        final var differences = this.differences.stream()
                .map(ReconcilePlan::differenceJson).toList();
        final var changes = this.changes.stream()
                .map(ReconcilePlan::changeJson).toList();
        final var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", 1);
        root.put("targetNamespace", targetNamespace);
        root.put("dialectId", dialectId);
        root.put("actualFingerprint", actualFingerprint);
        root.put("desiredFingerprint", desiredFingerprint);
        root.put("differences", differences);
        root.put("changes", changes);
        return Json.write(root);
    }

    private static Map<String, Object> differenceJson(Difference d) {
        final var out = new LinkedHashMap<String, Object>();
        out.put("id", idJson(d.id()));
        out.put("type", d.type().name());
        out.put("expected", d.expected().map(Definition::canonical).orElse(null));
        out.put("actual", d.actual().map(Definition::canonical).orElse(null));
        return out;
    }

    private static Map<String, Object> changeJson(PlannedChange c) {
        final var hazard = new LinkedHashMap<String, Object>();
        hazard.put("lockLevel", c.hazard().lockLevel().name());
        hazard.put("rewritesTable", c.hazard().rewritesTable());
        hazard.put("validatesRows", c.hazard().validatesRows());
        hazard.put("notes", List.copyOf(c.hazard().notes()));
        final var out = new LinkedHashMap<String, Object>();
        out.put("order", c.order());
        out.put("target", idJson(c.target()));
        out.put("action", c.action().name());
        out.put("safety", c.safety().name());
        out.put("statements", List.copyOf(c.statements()));
        out.put("expected", c.expected().map(Definition::canonical).orElse(null));
        out.put("reason", c.reason());
        out.put("nonTransactional", c.nonTransactional());
        out.put("hazard", hazard);
        return out;
    }

    private static Map<String, Object> idJson(ObjectId id) {
        final var out = new LinkedHashMap<String, Object>();
        out.put("kind", id.kind().name());
        out.put("name", id.name());
        out.put("parent", id.parent().orElse(null));
        return out;
    }

    /**
     * Rebuilds a plan from {@link #toJson()} output.
     *
     * @throws IllegalArgumentException on malformed JSON, a wrong schema
     *         version, or missing/invalid fields
     * @throws NullPointerException when {@code json} is null
     */
    public static ReconcilePlan fromJson(String json) {
        Objects.requireNonNull(json, "json");
        final Object raw;
        try {
            raw = Json.read(json);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid plan JSON: " + e.getMessage());
        }
        final var o = requireObject(raw, "plan");
        final var version = o.get("schemaVersion");
        if (!(version instanceof Number n) || n.doubleValue() != 1) {
            throw new IllegalArgumentException("invalid plan JSON: schemaVersion "
                    + version);
        }
        final var differences = requireArray(o.get("differences"), "differences");
        final var parsedDifferences = new java.util.ArrayList<Difference>();
        for (var i = 0; i < differences.size(); i++) {
            final var dd = requireObject(differences.get(i), "differences[" + i + "]");
            parsedDifferences.add(new Difference(
                    requireId(dd.get("id"), "differences[" + i + "].id"),
                    requireEnum(dd.get("type"), DifferenceType.class,
                            "differences[" + i + "].type"),
                    optDef(dd.get("expected")), optDef(dd.get("actual"))));
        }
        final var changes = requireArray(o.get("changes"), "changes");
        final var parsedChanges = new java.util.ArrayList<PlannedChange>();
        for (var i = 0; i < changes.size(); i++) {
            final var cc = requireObject(changes.get(i), "changes[" + i + "]");
            final var hzRaw = cc.get("hazard");
            final var hz = requireObject(hzRaw == null ? Map.of() : hzRaw,
                    "changes[" + i + "].hazard");
            final var lockLevel = hz.get("lockLevel");
            final var notes = hz.get("notes");
            parsedChanges.add(new PlannedChange(
                    requireNumber(cc.get("order"), "changes[" + i + "].order").intValue(),
                    requireId(cc.get("target"), "changes[" + i + "].target"),
                    requireEnum(cc.get("action"), ChangeAction.class,
                            "changes[" + i + "].action"),
                    requireEnum(cc.get("safety"), ChangeSafety.class,
                            "changes[" + i + "].safety"),
                    requireStrings(cc.get("statements"), "changes[" + i + "].statements"),
                    optDef(cc.get("expected")),
                    requireString(cc.get("reason"), "changes[" + i + "].reason"),
                    Boolean.TRUE.equals(cc.get("nonTransactional")),
                    new ChangeHazard(
                            requireEnum(lockLevel == null ? "NONE" : lockLevel,
                                    LockLevel.class,
                                    "changes[" + i + "].hazard.lockLevel"),
                            Boolean.TRUE.equals(hz.get("rewritesTable")),
                            Boolean.TRUE.equals(hz.get("validatesRows")),
                            requireStrings(notes == null ? List.of() : notes,
                                    "changes[" + i + "].hazard.notes"))));
        }
        return new ReconcilePlan(
                requireString(o.get("targetNamespace"), "targetNamespace"),
                requireString(o.get("dialectId"), "dialectId"),
                requireString(o.get("actualFingerprint"), "actualFingerprint"),
                requireString(o.get("desiredFingerprint"), "desiredFingerprint"),
                parsedDifferences, parsedChanges);
    }

    private static Optional<Definition> optDef(Object v) {
        return v == null ? Optional.empty()
                : Optional.of(new Definition(String.valueOf(v)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object v, String field) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("invalid plan JSON: " + field
                    + " is not an object");
        }
        return (Map<String, Object>) v;
    }

    private static List<?> requireArray(Object v, String field) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException("invalid plan JSON: " + field
                    + " is not an array");
        }
        return (List<?>) v;
    }

    private static String requireString(Object v, String field) {
        if (!(v instanceof String)) {
            throw new IllegalArgumentException("invalid plan JSON: " + field
                    + " is not a string");
        }
        return (String) v;
    }

    private static Number requireNumber(Object v, String field) {
        if (!(v instanceof Number)) {
            throw new IllegalArgumentException("invalid plan JSON: " + field
                    + " is not a number");
        }
        return (Number) v;
    }

    private static List<String> requireStrings(Object v, String field) {
        if (!(v instanceof List<?> list)
                || list.stream().anyMatch(e -> !(e instanceof String))) {
            throw new IllegalArgumentException("invalid plan JSON: " + field
                    + " is not a string array");
        }
        return list.stream().map(e -> (String) e).toList();
    }

    private static <E extends Enum<E>> E requireEnum(Object v, Class<E> type,
            String field) {
        if (v instanceof String s) {
            try {
                return Enum.valueOf(type, s);
            } catch (IllegalArgumentException e) {
                // fall through to the uniform error below
            }
        }
        throw new IllegalArgumentException("invalid plan JSON: " + field
                + " has unknown value " + v);
    }

    private static ObjectId requireId(Object v, String field) {
        final var o = requireObject(v, field);
        final var kind = requireEnum(o.get("kind"), ObjectKind.class, field + ".kind");
        final var name = requireString(o.get("name"), field + ".name");
        final var parent = o.get("parent");
        if (parent != null && !(parent instanceof String)) {
            throw new IllegalArgumentException("invalid plan JSON: " + field
                    + ".parent is not a string");
        }
        return parent == null ? new ObjectId(kind, name)
                : new ObjectId(kind, name, (String) parent);
    }
}
