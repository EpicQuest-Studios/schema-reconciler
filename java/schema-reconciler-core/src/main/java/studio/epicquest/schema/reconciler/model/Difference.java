package studio.epicquest.schema.reconciler.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One detected difference between the desired and actual snapshots (spec 4.3).
 */
public record Difference(
        ObjectId id,
        DifferenceType type,
        Optional<Definition> expected,
        Optional<Definition> actual) {

    public Difference {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
    }
}
