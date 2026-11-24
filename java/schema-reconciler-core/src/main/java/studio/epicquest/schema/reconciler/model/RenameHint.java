package studio.epicquest.schema.reconciler.model;

import java.util.Objects;

/**
 * An explicit rename hint (spec 3.2 directive
 * {@code -- @reconciler rename <KIND> [parent.]old -> new}): when
 * {@code from} exists in the actual snapshot, {@code to} does not,
 * {@code to} is desired and {@code from} is not, the planner emits
 * {@code ALTER ... RENAME} instead of a drop+create.
 */
public record RenameHint(ObjectId from, ObjectId to) {

    public RenameHint {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.kind() != to.kind()) {
            throw new IllegalArgumentException(
                    "rename kinds differ: " + from + " -> " + to);
        }
    }
}
