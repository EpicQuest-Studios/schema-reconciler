package studio.epicquest.schema.reconciler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.fake.FakeDb;
import studio.epicquest.schema.reconciler.fake.FakeDialect;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/** Dependency-graph helpers: parent resolution, reversal, reachability. */
class DependenciesTest {

    private static final Dialect DIALECT = new FakeDialect(new FakeDb());

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

    private static Map<ObjectId, Set<ObjectId>> prereqsOf(SchemaSnapshot snapshot) {
        return Dependencies.prerequisitesOf(DIALECT, snapshot);
    }

    @Test
    void managedObjectWinsOverOtherOnResolvableNameCollision() {
        // a range type and a materialized view share the name "t": the index
        // parented on "t" must resolve to the managed object, not the OTHER
        final var type = new ObjectId(ObjectKind.OTHER, "type:t");
        final var mv = new ObjectId(ObjectKind.MATERIALIZED_VIEW, "t");
        final var index = new ObjectId(ObjectKind.INDEX, "i", "t");
        final var prereqs = prereqsOf(snapshot(object(type), object(mv), object(index)));
        assertEquals(Set.of(mv), prereqs.get(index));
    }

    @Test
    void otherFillsTheParentableGapWhenNoManagedObjectMatches() {
        // an unsupported inheritance child's name is still the parent anchor —
        // children parented on it must resolve through the OTHER prefix
        final var child = new ObjectId(ObjectKind.OTHER, "inherited:pc");
        final var index = new ObjectId(ObjectKind.INDEX, "i", "pc");
        final var prereqs = prereqsOf(snapshot(object(child), object(index)));
        assertEquals(Set.of(child), prereqs.get(index));
    }

    @Test
    void nonRelationOtherIsNeverAParent() {
        // an exclusion constraint's stripped name is the synthetic
        // "<parent>.<name>" — its only relation name is the backing index, so
        // it must not shadow the relation literally named "t.ex"
        final var constraint = new ObjectId(ObjectKind.OTHER, "constraint:t.ex");
        final var relation = new ObjectId(ObjectKind.OTHER, "inherited:t.ex");
        final var index = new ObjectId(ObjectKind.INDEX, "i", "t.ex");
        final var prereqs = prereqsOf(
                snapshot(object(constraint), object(relation), object(index)));
        assertEquals(Set.of(relation), prereqs.get(index));
        // and a routine's "name(sig)" stripped form is not parentable either
        final var routine = new ObjectId(ObjectKind.OTHER, "routine:f(int)");
        final var orphan = new ObjectId(ObjectKind.INDEX, "i", "f(int)");
        final var prereqs2 = prereqsOf(snapshot(object(routine), object(orphan)));
        assertTrue(prereqs2.get(orphan).isEmpty());
    }

    @Test
    void partitionAndForeignTableAreParentable() {
        // a partition and a foreign table carry COLUMN children like a
        // table — the implicit parent edge must resolve for them too
        final var pp = new ObjectId(ObjectKind.TABLE, "pp");
        final var part = new ObjectId(ObjectKind.PARTITION, "pt", "pp");
        final var col = new ObjectId(ObjectKind.COLUMN, "c", "pt");
        final var prereqs = prereqsOf(snapshot(object(pp), object(part), object(col)));
        assertEquals(Set.of(part), prereqs.get(col));
        assertEquals(Set.of(pp), prereqs.get(part));
        final var ft = new ObjectId(ObjectKind.FOREIGN_TABLE, "ft");
        final var fcol = new ObjectId(ObjectKind.COLUMN, "c", "ft");
        final var prereqs2 = prereqsOf(snapshot(object(ft), object(fcol)));
        assertEquals(Set.of(ft), prereqs2.get(fcol));
    }

    @Test
    void selfEdgesAndNullParentsAreIgnored() {
        final var t = new ObjectId(ObjectKind.TABLE, "t");
        final var prereqs = prereqsOf(snapshot(object(t, t)));
        assertTrue(prereqs.get(t).isEmpty());
    }

    @Test
    void reverseAndReachableWalkDependents() {
        final var a = new ObjectId(ObjectKind.TABLE, "a");
        final var b = new ObjectId(ObjectKind.VIEW, "b");
        final var c = new ObjectId(ObjectKind.VIEW, "c");
        final var prereqs = prereqsOf(snapshot(object(a), object(b, a), object(c, b)));
        final var dependents = Dependencies.reverse(prereqs);
        assertEquals(Set.of(b), dependents.get(a));
        assertEquals(Set.of(c), dependents.get(b));
        assertEquals(Set.of(b, c), Dependencies.reachable(a, dependents));
    }
}
