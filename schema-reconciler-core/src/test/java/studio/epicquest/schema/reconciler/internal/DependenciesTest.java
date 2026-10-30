package studio.epicquest.schema.reconciler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/** Dependency-graph helpers: parent resolution, reversal, reachability. */
class DependenciesTest {

    private static SchemaObject object(ObjectId id, ObjectId... prereqs) {
        return new SchemaObject(id, new Definition("def " + id.name()),
                Set.of(prereqs), Map.of());
    }

    private static SchemaSnapshot snapshot(SchemaObject... objects) {
        final var map = new java.util.HashMap<ObjectId, SchemaObject>();
        for (final var o : objects) {
            map.put(o.id(), o);
        }
        return SchemaSnapshot.of(map);
    }

    @Test
    void managedObjectWinsOverOtherOnResolvableNameCollision() {
        // a range type and a materialized view share the name "t": the index
        // parented on "t" must resolve to the managed object, not the OTHER
        final var type = new ObjectId(ObjectKind.OTHER, "type:t");
        final var mv = new ObjectId(ObjectKind.MATERIALIZED_VIEW, "t");
        final var index = new ObjectId(ObjectKind.INDEX, "i", "t");
        final var prereqs = Dependencies.prerequisitesOf(
                snapshot(object(type), object(mv), object(index)));
        assertEquals(Set.of(mv), prereqs.get(index));
    }

    @Test
    void otherFillsTheParentableGapWhenNoManagedObjectMatches() {
        // an unsupported partition child's name is still the parent anchor —
        // children parented on it must resolve through the OTHER prefix
        final var child = new ObjectId(ObjectKind.OTHER, "partition:pc");
        final var index = new ObjectId(ObjectKind.INDEX, "i", "pc");
        final var prereqs = Dependencies.prerequisitesOf(
                snapshot(object(child), object(index)));
        assertEquals(Set.of(child), prereqs.get(index));
    }

    @Test
    void selfEdgesAndNullParentsAreIgnored() {
        final var t = new ObjectId(ObjectKind.TABLE, "t");
        final var prereqs = Dependencies.prerequisitesOf(snapshot(object(t, t)));
        assertTrue(prereqs.get(t).isEmpty());
    }

    @Test
    void reverseAndReachableWalkDependents() {
        final var a = new ObjectId(ObjectKind.TABLE, "a");
        final var b = new ObjectId(ObjectKind.VIEW, "b");
        final var c = new ObjectId(ObjectKind.VIEW, "c");
        final var prereqs = Dependencies.prerequisitesOf(
                snapshot(object(a), object(b, a), object(c, b)));
        final var dependents = Dependencies.reverse(prereqs);
        assertEquals(Set.of(b), dependents.get(a));
        assertEquals(Set.of(c), dependents.get(b));
        assertEquals(Set.of(b, c), Dependencies.reachable(a, dependents));
    }
}
