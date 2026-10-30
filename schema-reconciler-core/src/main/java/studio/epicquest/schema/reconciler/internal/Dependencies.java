package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * Dependency-graph helpers. A prerequisite edge is a {@code dependsOn} entry
 * or the implicit edge from a child {@link ObjectId} to its {@code parent};
 * self-dependencies are recorded but ignored (spec 5.8.1).
 */
final class Dependencies {

    private static final Set<ObjectKind> PARENTABLE = Set.of(
            ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW,
            ObjectKind.SEQUENCE, ObjectKind.INDEX, ObjectKind.OTHER);

    private Dependencies() {
    }

    /** object -> the objects it requires (dependsOn plus parent, minus itself). */
    static Map<ObjectId, Set<ObjectId>> prerequisitesOf(SchemaSnapshot snapshot) {
        final var parentables = parentables(snapshot.objects());
        final var out = new HashMap<ObjectId, Set<ObjectId>>();
        for (final var object : snapshot.objects().values()) {
            final var prereqs = new LinkedHashSet<ObjectId>(object.dependsOn());
            object.id().parent().map(parentables::get).ifPresent(prereqs::add);
            prereqs.remove(object.id());
            out.put(object.id(), prereqs);
        }
        return out;
    }

    /** Reverses an object->prerequisites map into object->dependents. */
    static Map<ObjectId, Set<ObjectId>> reverse(Map<ObjectId, Set<ObjectId>> prerequisites) {
        final var dependents = new HashMap<ObjectId, Set<ObjectId>>();
        prerequisites.forEach((id, prereqs) -> prereqs.forEach(
                prereq -> dependents.computeIfAbsent(prereq, k -> new HashSet<>()).add(id)));
        return dependents;
    }

    /** Everything reachable from {@code from} over an object->objects map. */
    static Set<ObjectId> reachable(ObjectId from, Map<ObjectId, Set<ObjectId>> edges) {
        final var seen = new LinkedHashSet<ObjectId>();
        final var stack = new ArrayDeque<ObjectId>();
        stack.addAll(edges.getOrDefault(from, Set.of()));
        while (!stack.isEmpty()) {
            final var id = stack.pop();
            if (seen.add(id)) {
                stack.addAll(edges.getOrDefault(id, Set.of()));
            }
        }
        return seen;
    }

    private static Map<String, ObjectId> parentables(Map<ObjectId, SchemaObject> objects) {
        // same-catalog names are unique, so two managed objects never collide
        // on a resolvable name — but an OTHER's cross-catalog name can, and
        // the managed object must win
        final var map = new HashMap<String, ObjectId>();
        objects.values().stream()
                .sorted((a, b) -> ObjectIds.ORDER.compare(a.id(), b.id()))
                .filter(o -> PARENTABLE.contains(o.id().kind())
                        && o.id().kind() != ObjectKind.OTHER)
                .forEach(o -> map.putIfAbsent(resolvableName(o.id()), o.id()));
        objects.values().stream()
                .filter(o -> o.id().kind() == ObjectKind.OTHER)
                .forEach(o -> map.putIfAbsent(resolvableName(o.id()), o.id()));
        return map;
    }

    /** An OTHER id's name carries a documented category prefix (3.3); the
     *  parent linkage keys on the object name behind the prefix, so a
     *  {@code matview:mv} still blocks children parented on {@code mv}. */
    private static String resolvableName(ObjectId id) {
        final var name = id.name();
        if (id.kind() == ObjectKind.OTHER) {
            final var colon = name.indexOf(':');
            if (colon > 0) {
                return name.substring(colon + 1);
            }
        }
        return name;
    }
}
