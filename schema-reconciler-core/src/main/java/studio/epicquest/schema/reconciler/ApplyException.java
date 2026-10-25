package studio.epicquest.schema.reconciler;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import studio.epicquest.schema.reconciler.model.PlannedChange;

/**
 * A statement failed, or a listener aborted the run (spec 7). Carries the
 * changes applied so far (possibly none) and, when known, the change being
 * processed.
 */
public class ApplyException extends SchemaReconcilerException {

    private final List<PlannedChange> applied;
    private final PlannedChange failed;

    public ApplyException(String message, List<PlannedChange> applied, PlannedChange failed) {
        super(message);
        this.applied = List.copyOf(applied);
        this.failed = failed;
    }

    public ApplyException(String message, Throwable cause, List<PlannedChange> applied, PlannedChange failed) {
        super(message, cause);
        this.applied = List.copyOf(Objects.requireNonNull(applied, "applied"));
        this.failed = failed;
    }

    /** The changes already applied when the failure happened. */
    public List<PlannedChange> applied() {
        return applied;
    }

    /** The change being processed, if the failure is bound to one. */
    public Optional<PlannedChange> failed() {
        return Optional.ofNullable(failed);
    }
}
