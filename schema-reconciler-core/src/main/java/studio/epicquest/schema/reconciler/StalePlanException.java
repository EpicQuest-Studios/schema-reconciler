package studio.epicquest.schema.reconciler;

import java.util.Optional;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/** The target changed between {@code plan()} and {@code apply()} (spec 7). */
public class StalePlanException extends SchemaReconcilerException {

    private final ReconcilePlan plan;

    public StalePlanException(String message) {
        super(message);
        this.plan = null;
    }

    public StalePlanException(String message, ReconcilePlan plan) {
        super(message);
        this.plan = plan;
    }

    public StalePlanException(String message, Throwable cause) {
        super(message, cause);
        this.plan = null;
    }

    /** The stale plan, when the failure is bound to one. */
    public Optional<ReconcilePlan> plan() {
        return Optional.ofNullable(plan);
    }
}
