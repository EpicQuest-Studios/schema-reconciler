package studio.epicquest.schema.reconciler.model;

import java.util.Comparator;

/**
 * The deterministic ordering of {@link ObjectId} required by spec 5.2:
 * kind order, then parent, then name, lexicographically.
 */
public final class ObjectIds {

    public static final Comparator<ObjectId> ORDER = Comparator
            .comparing((ObjectId id) -> id.kind().ordinal())
            .thenComparing(id -> id.parent().orElse(""))
            .thenComparing(ObjectId::name);

    private ObjectIds() {
    }
}
