package studio.epicquest.schema.reconciler;

import java.util.Objects;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/** The plan contains changes mapped to {@code FAIL}; carries the plan (spec 7). */
public class PolicyViolationException extends SchemaReconcilerException {

    private final ReconcilePlan plan;

    public PolicyViolationException(String message, ReconcilePlan plan) {
        super(message);
        this.plan = Objects.requireNonNull(plan, "plan");
    }

    public ReconcilePlan plan() {
        return plan;
    }
}
