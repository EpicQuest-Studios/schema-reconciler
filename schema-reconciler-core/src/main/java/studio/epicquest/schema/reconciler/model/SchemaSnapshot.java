package studio.epicquest.schema.reconciler.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, namespace-neutral description of a set of schema objects (spec 4.2).
 *
 * <p>{@link #of(Map)} computes the fingerprint defined by spec 5.7, so both
 * snapshots a dialect produces share one implementation. The canonical
 * constructor verifies a supplied fingerprint against the same computation.
 */
public record SchemaSnapshot(
        Map<ObjectId, SchemaObject> objects,
        String fingerprint) {

    public SchemaSnapshot {
        objects = Map.copyOf(Objects.requireNonNull(objects, "objects"));
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!fingerprint.equals(fingerprintOf(objects))) {
            throw new IllegalArgumentException("fingerprint does not match objects");
        }
    }

    public static SchemaSnapshot of(Map<ObjectId, SchemaObject> objects) {
        return new SchemaSnapshot(objects, fingerprintOf(objects));
    }

    public Optional<SchemaObject> object(ObjectId id) {
        return Optional.ofNullable(objects.get(id));
    }

    /** The byte-exact fingerprint format of spec 5.7. */
    public static String fingerprintOf(Map<ObjectId, SchemaObject> objects) {
        final var ids = new ArrayList<ObjectId>(objects.keySet());
        ids.sort(ObjectIds.ORDER);
        final var digest = sha256();
        for (final var id : ids) {
            put(digest, id.kind().name());
            put(digest, id.parent().orElse(""));
            put(digest, id.name());
            put(digest, objects.get(id).definition().canonical());
        }
        final var hash = digest.digest();
        final var hex = new StringBuilder(hash.length * 2);
        for (final var b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static void put(MessageDigest digest, String field) {
        final var bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
