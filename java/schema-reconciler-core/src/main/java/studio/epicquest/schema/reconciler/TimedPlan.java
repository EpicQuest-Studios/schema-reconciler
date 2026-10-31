package studio.epicquest.schema.reconciler;

import java.util.Objects;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * A plan together with the phase timings of the run that computed it
 * (spec 3.5). {@link RunTimings#apply()} and {@link RunTimings#verify()} are
 * zero: planning applies nothing.
 */
public record TimedPlan(ReconcilePlan plan, RunTimings timings) {

    public TimedPlan {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(timings, "timings");
    }
}
