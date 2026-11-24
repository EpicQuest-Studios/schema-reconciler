package studio.epicquest.schema.reconciler.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One planned unit of DDL (spec 4.4).
 *
 * @param order 1-based execution order; {@code 0} on changes returned by
 *        {@code Dialect.render}, before the core assigns the final order
 * @param expected definition the target must have after apply; empty for
 *        {@code DROP} changes and changes against unexpected objects
 * @param nonTransactional the change cannot run inside a transaction —
 *        the engine applies it after the transactional phase commits
 * @param hazard lock/rewrite/validation metadata of the statements
 */
public record PlannedChange(
        int order,
        ObjectId target,
        ChangeAction action,
        ChangeSafety safety,
        List<String> statements,
        Optional<Definition> expected,
        String reason,
        boolean nonTransactional,
        ChangeHazard hazard) {

    public PlannedChange {
        if (order < 0) {
            throw new IllegalArgumentException("order must not be negative");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(safety, "safety");
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(hazard, "hazard");
        if (action == ChangeAction.DROP && expected.isPresent()) {
            throw new IllegalArgumentException("a DROP change has no expected definition");
        }
    }

    public PlannedChange(int order, ObjectId target, ChangeAction action,
            ChangeSafety safety, List<String> statements,
            Optional<Definition> expected, String reason) {
        this(order, target, action, safety, statements, expected, reason,
                false, ChangeHazard.NONE);
    }

    /** A change the dialect can describe but not perform safely (spec 3.3). */
    public boolean isManual() {
        return statements.isEmpty();
    }
}
