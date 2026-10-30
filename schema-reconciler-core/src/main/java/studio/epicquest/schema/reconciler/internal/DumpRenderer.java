package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Renders an actual snapshot back into a namespace-neutral desired-state
 * script — the body of {@code SchemaReconciler.dump()} (spec 3.1).
 * Objects emit in {@code dependsOn} order; an object the dialect cannot
 * render becomes a comment line rather than being silently dropped.
 */
final class DumpRenderer {

    private DumpRenderer() {
    }

    static String render(Dialect dialect, String namespace, SchemaSnapshot snapshot) {
        final var out = new StringBuilder()
                .append("-- schema-reconciler dump\n")
                .append("-- dialect: ").append(dialect.id()).append('\n')
                .append("-- namespace: ").append(dialect.quoteIdentifier(namespace)).append('\n')
                .append("-- fingerprint: ").append(snapshot.fingerprint()).append('\n');
        for (final var object : order(dialect, snapshot)) {
            final var statements = dialect.dumpStatements(object);
            if (statements.isEmpty()) {
                out.append("-- ").append(folded(object, snapshot) ? "folded" : "unsupported")
                        .append(": ").append(object.id()).append('\n');
                continue;
            }
            for (final var statement : statements) {
                out.append(statement).append(";\n");
            }
        }
        return out.toString();
    }

    /**
     * Topological order over the dependency graph: every object after the
     * objects it requires, ties by {@link ObjectIds#ORDER} so the output is
     * deterministic. Edges naming objects outside the snapshot are ignored;
     * a dependency cycle emits its members in plain ORDER after the
     * acyclic prefix.
     */
    private static List<SchemaObject> order(Dialect dialect, SchemaSnapshot snapshot) {
        final var objects = snapshot.objects();
        final var prereqs = Dependencies.prerequisitesOf(dialect, snapshot);
        final var pending = new HashMap<ObjectId, Set<ObjectId>>();
        prereqs.forEach((id, reqs) -> {
            final var present = new HashSet<>(reqs);
            present.retainAll(objects.keySet());
            pending.put(id, present);
        });
        final var dependents = Dependencies.reverse(pending);
        final var ready = new PriorityQueue<ObjectId>(ObjectIds.ORDER);
        pending.forEach((id, reqs) -> {
            if (reqs.isEmpty()) {
                ready.add(id);
            }
        });
        final var ordered = new ArrayList<SchemaObject>(objects.size());
        while (!ready.isEmpty()) {
            final var id = ready.remove();
            ordered.add(objects.get(id));
            for (final var dependent : dependents.getOrDefault(id, Set.of())) {
                final var reqs = pending.get(dependent);
                if (reqs != null && reqs.remove(id) && reqs.isEmpty()) {
                    ready.add(dependent);
                }
            }
        }
        if (ordered.size() < objects.size()) {
            pending.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty())
                    .map(Map.Entry::getKey).sorted(ObjectIds.ORDER)
                    .map(objects::get).forEach(ordered::add);
        }
        return ordered;
    }

    /**
     * An object whose creation is folded into another object's fragment and
     * therefore renders no statement of its own (spec 3.1 {@code -- folded}):
     * a column of a managed table, or a sequence owned by a table column —
     * detected as a {@code dependsOn} edge to a snapshot TABLE.
     */
    private static boolean folded(SchemaObject object, SchemaSnapshot snapshot) {
        final var id = object.id();
        if (id.kind() == ObjectKind.COLUMN) {
            return id.parent()
                    .map(parent -> new ObjectId(ObjectKind.TABLE, parent))
                    .map(snapshot.objects()::containsKey)
                    .orElse(false);
        }
        if (id.kind() == ObjectKind.SEQUENCE) {
            return object.dependsOn().stream().anyMatch(dep ->
                    dep.kind() == ObjectKind.TABLE && snapshot.objects().containsKey(dep));
        }
        return false;
    }
}
