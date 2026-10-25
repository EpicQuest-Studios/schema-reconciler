package studio.epicquest.schema.reconciler.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Namespace-neutral identity of a schema object (spec 4.1).
 *
 * @param kind the object kind
 * @param name the identifier as stored by the engine; for {@link ObjectKind#ROUTINE}
 *        it includes the identity signature, for example {@code "ts(text)"}
 * @param parent the owning object name for child kinds, empty otherwise
 */
public record ObjectId(ObjectKind kind, String name, Optional<String> parent) {

    private static final Set<ObjectKind> PARENTED = Set.of(
            ObjectKind.COLUMN, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
            ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.INDEX, ObjectKind.TRIGGER);

    public ObjectId {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parent, "parent");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        if (PARENTED.contains(kind) != parent.isPresent()) {
            throw new IllegalArgumentException(
                    "parent is required for " + kind + " and must be empty for other kinds");
        }
        if (parent.isPresent() && parent.get().isEmpty()) {
            throw new IllegalArgumentException("parent must not be empty");
        }
    }

    public ObjectId(ObjectKind kind, String name) {
        this(kind, name, Optional.empty());
    }

    public ObjectId(ObjectKind kind, String name, String parent) {
        this(kind, name, Optional.of(parent));
    }

    @Override
    public String toString() {
        return parent.map(p -> kind + " " + p + "." + name).orElseGet(() -> kind + " " + name);
    }
}
