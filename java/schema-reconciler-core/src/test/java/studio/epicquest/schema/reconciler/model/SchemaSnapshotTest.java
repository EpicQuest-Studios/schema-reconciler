package studio.epicquest.schema.reconciler.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spec 5.7: the byte-exact fingerprint contract. */
class SchemaSnapshotTest {

    private static SchemaObject obj(ObjectKind kind, String name, String def) {
        return new SchemaObject(new ObjectId(kind, name), new Definition(def), Set.of(), Map.of());
    }

    @Test
    void fingerprintIsOrderIndependent() {
        final var a = new LinkedHashMap<ObjectId, SchemaObject>();
        a.put(new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"));
        a.put(new ObjectId(ObjectKind.VIEW, "v"), obj(ObjectKind.VIEW, "v", "d2"));
        final var b = new LinkedHashMap<ObjectId, SchemaObject>();
        b.put(new ObjectId(ObjectKind.VIEW, "v"), obj(ObjectKind.VIEW, "v", "d2"));
        b.put(new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"));
        assertEquals(SchemaSnapshot.of(a).fingerprint(), SchemaSnapshot.of(b).fingerprint());
    }

    @Test
    void fingerprintChangesWithDefinition() {
        var a = Map.of(new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"));
        var b = Map.of(new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d2"));
        assertNotEquals(SchemaSnapshot.of(a).fingerprint(), SchemaSnapshot.of(b).fingerprint());
    }

    @Test
    void fingerprintIsLowercaseHexSha256() {
        final var fp = SchemaSnapshot.of(Map.of(
                new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"))).fingerprint();
        assertEquals(64, fp.length());
        assertEquals(fp, fp.toLowerCase());
        assertEquals(fp, fp.replaceAll("[^0-9a-f]", ""));
    }

    @Test
    void emptySnapshotFingerprint() {
        // sha256 of the empty byte string — a fixed reference value
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                SchemaSnapshot.of(Map.of()).fingerprint());
    }

    @Test
    void constructorRejectsWrongFingerprint() {
        final var objects = Map.of(
                new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"));
        assertThrows(IllegalArgumentException.class, () -> new SchemaSnapshot(objects, "0".repeat(64)));
    }

    @Test
    void fingerprintUsesLengthPrefixedFields() {
        // "ab"+"c" and "a"+"bc" in field position must not collide
        final var a = Map.of(
                new ObjectId(ObjectKind.TABLE, "ab"),
                new SchemaObject(new ObjectId(ObjectKind.TABLE, "ab"),
                        new Definition("c"), Set.of(), Map.of()));
        final var b = Map.of(
                new ObjectId(ObjectKind.TABLE, "a"),
                new SchemaObject(new ObjectId(ObjectKind.TABLE, "a"),
                        new Definition("bc"), Set.of(), Map.of()));
        assertNotEquals(SchemaSnapshot.of(a).fingerprint(), SchemaSnapshot.of(b).fingerprint());
    }
}
