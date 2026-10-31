package studio.epicquest.schema.reconciler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static studio.epicquest.schema.reconciler.fake.FakeDb.object;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * The {@code Builder.filter} cascade: a rejected object takes its children
 * and mirrored annotations with it; everything else survives untouched.
 */
class ObjectFilterTest {

    private static SchemaSnapshot snapshot(SchemaObject... objects) {
        final var map = new LinkedHashMap<ObjectId, SchemaObject>();
        for (final var o : objects) {
            map.put(o.id(), o);
        }
        return SchemaSnapshot.of(map);
    }

    private static ObjectId id(ObjectKind kind, String name) {
        return new ObjectId(kind, name);
    }

    private static ObjectId id(ObjectKind kind, String name, String parent) {
        return new ObjectId(kind, name, parent);
    }

    @Test
    void includeEverythingReturnsTheSameSnapshot() {
        final var snapshot = snapshot(object(id(ObjectKind.TABLE, "t"), "d"));
        assertSame(snapshot, ObjectFilter.apply(snapshot, id -> true));
    }

    @Test
    void excludedTableHidesChildrenAndMirroredAnnotations() {
        final var snapshot = snapshot(
                object(id(ObjectKind.TABLE, "t"), "t1"),
                object(id(ObjectKind.COLUMN, "c", "t"), "c1"),
                object(id(ObjectKind.INDEX, "i", "t"), "i1"),
                // mirrored on the table and on its column
                object(id(ObjectKind.COMMENT, "t"), "cm"),
                object(id(ObjectKind.COMMENT, "c", "t"), "cm"),
                object(id(ObjectKind.PRIVILEGES, "t"), "p"),
                object(id(ObjectKind.SECURITY_LABEL, "selinux:t"), "l"),
                object(id(ObjectKind.OWNERSHIP, "t"), "o"),
                object(id(ObjectKind.TABLE, "u"), "u1"),
                object(id(ObjectKind.COLUMN, "c", "u"), "c1"),
                object(id(ObjectKind.COMMENT, "u"), "cm"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> !(x.kind() == ObjectKind.TABLE && x.name().equals("t")));
        assertEquals(
                java.util.Set.of(
                        id(ObjectKind.TABLE, "u"),
                        id(ObjectKind.COLUMN, "c", "u"),
                        id(ObjectKind.COMMENT, "u")),
                filtered.objects().keySet());
    }

    @Test
    void excludedColumnKeepsItsTableButHidesItsAnnotations() {
        final var snapshot = snapshot(
                object(id(ObjectKind.TABLE, "t"), "t1"),
                object(id(ObjectKind.COLUMN, "c", "t"), "c1"),
                object(id(ObjectKind.COLUMN, "d", "t"), "d1"),
                object(id(ObjectKind.COMMENT, "c", "t"), "cm"),
                object(id(ObjectKind.COMMENT, "d", "t"), "cm"),
                object(id(ObjectKind.PRIVILEGES, "c", "t"), "p"),
                object(id(ObjectKind.COMMENT, "t"), "cm"),
                // unsupported annotation OTHERs embed the qualified target
                object(id(ObjectKind.OTHER, "comment:t.c"), "cm"),
                object(id(ObjectKind.OTHER, "seclabel:p.t.c"), "l"),
                object(id(ObjectKind.OTHER, "comment:t.d"), "cm"),
                object(id(ObjectKind.OTHER, "comment:t"), "cm"),
                // constraint:<parent>.<member> follows the relation only —
                // member c is not column t.c
                object(id(ObjectKind.OTHER, "constraint:t.c"), "o"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> x.kind() != ObjectKind.COLUMN || !x.name().equals("c"));
        assertEquals(
                java.util.Set.of(
                        id(ObjectKind.TABLE, "t"),
                        id(ObjectKind.COLUMN, "d", "t"),
                        id(ObjectKind.COMMENT, "d", "t"),
                        id(ObjectKind.COMMENT, "t"),
                        id(ObjectKind.OTHER, "comment:t.d"),
                        id(ObjectKind.OTHER, "comment:t"),
                        id(ObjectKind.OTHER, "constraint:t.c")),
                filtered.objects().keySet());
    }

    @Test
    void collisionSuffixedMirroredIdsFollowTheirTarget() {
        // a " (KIND)" collision suffix and a seclabel provider prefix are not
        // part of the annotated name
        final var snapshot = snapshot(
                object(id(ObjectKind.TABLE, "t"), "t1"),
                object(id(ObjectKind.COMMENT, "t (INDEX)", "x"), "cm"),
                object(id(ObjectKind.SECURITY_LABEL, "p:t (TABLE)"), "l"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> x.kind() != ObjectKind.TABLE);
        // "t (INDEX)" mirrors an INDEX named t on parent x — not TABLE t
        assertTrue(filtered.objects().containsKey(
                id(ObjectKind.COMMENT, "t (INDEX)", "x")));
        assertTrue(!filtered.objects().containsKey(
                id(ObjectKind.SECURITY_LABEL, "p:t (TABLE)")));
    }

    @Test
    void excludedOtherRelationHidesChildren() {
        final var snapshot = snapshot(
                object(id(ObjectKind.OTHER, "inherited:x"), "d"),
                object(id(ObjectKind.COLUMN, "a", "x"), "c1"),
                object(id(ObjectKind.COLUMN, "a", "y"), "c1"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> x.kind() != ObjectKind.OTHER);
        assertEquals(java.util.Set.of(id(ObjectKind.COLUMN, "a", "y")),
                filtered.objects().keySet());
    }

    @Test
    void excludedOtherTargetHidesAnnotationOthers() {
        // comment:/seclabel:/privileges:/ownership: OTHERs annotate
        // unmanaged targets — they follow the target out
        final var snapshot = snapshot(
                object(id(ObjectKind.OTHER, "inherited:t"), "d"),
                object(id(ObjectKind.OTHER, "comment:t"), "cm"),
                object(id(ObjectKind.OTHER, "comment:t.c"), "cm"),
                object(id(ObjectKind.OTHER, "seclabel:p.t"), "l"),
                object(id(ObjectKind.OTHER, "seclabel:p.t.c"), "l"),
                object(id(ObjectKind.OTHER, "privileges:t"), "p"),
                object(id(ObjectKind.OTHER, "privileges:t.c"), "p"),
                object(id(ObjectKind.OTHER, "ownership:t"), "o"),
                object(id(ObjectKind.OTHER, "constraint:t.pk"), "o"),
                object(id(ObjectKind.OTHER, "comment:u"), "cm"),
                object(id(ObjectKind.OTHER, "constraint:u.ck"), "o"),
                object(id(ObjectKind.OTHER, "cast:a->b"), "c"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> !(x.kind() == ObjectKind.OTHER
                        && x.name().equals("inherited:t")));
        assertEquals(
                java.util.Set.of(
                        id(ObjectKind.OTHER, "comment:u"),
                        id(ObjectKind.OTHER, "constraint:u.ck"),
                        id(ObjectKind.OTHER, "cast:a->b")),
                filtered.objects().keySet());
    }

    @Test
    void serialSequenceFollowsItsOwningRelation() {
        // the OWNED BY dep edge makes the sequence a covered follower of
        // its table — excluding the table hides it, but not a free sequence
        final var t = id(ObjectKind.TABLE, "t");
        final var snapshot = snapshot(
                object(t, "d"),
                object(id(ObjectKind.SEQUENCE, "t_c_seq"), "d", t),
                object(id(ObjectKind.SEQUENCE, "free"), "d"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> x.kind() != ObjectKind.TABLE);
        assertEquals(
                java.util.Set.of(id(ObjectKind.SEQUENCE, "free")),
                filtered.objects().keySet());
    }

    @Test
    void cascadeIsTransitiveThroughNestedParents() {
        // rt -> md -> lf multi-level partitioning: excluding md removes lf
        // and everything parented on lf
        final var snapshot = snapshot(
                object(id(ObjectKind.TABLE, "rt"), "d"),
                object(id(ObjectKind.PARTITION, "md", "rt"), "d"),
                object(id(ObjectKind.PARTITION, "lf", "md"), "d"),
                object(id(ObjectKind.COLUMN, "j", "lf"), "d"),
                object(id(ObjectKind.COMMENT, "lf", "md"), "cm"),
                object(id(ObjectKind.PUBLICATION, "pub", "lf"), "d"),
                object(id(ObjectKind.COLUMN, "j", "rt"), "d"));
        final var filtered = ObjectFilter.apply(snapshot,
                x -> !(x.kind() == ObjectKind.PARTITION && x.name().equals("md")));
        assertEquals(
                java.util.Set.of(
                        id(ObjectKind.TABLE, "rt"),
                        id(ObjectKind.COLUMN, "j", "rt")),
                filtered.objects().keySet());
    }
}
