package studio.epicquest.schema.reconciler;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * The result of {@code reconcile()} or {@code apply(plan)} (spec 3.5).
 */
public record ReconcileReport(
        ReconcileOutcome outcome,
        ReconcilePlan plan,
        List<PlannedChange> applied,
        List<PlannedChange> reported,
        List<Difference> unexpected,
        List<Difference> unsupported,
        Duration elapsed,
        RunTimings timings) {

    public ReconcileReport {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(plan, "plan");
        applied = List.copyOf(Objects.requireNonNull(applied, "applied"));
        reported = List.copyOf(Objects.requireNonNull(reported, "reported"));
        unexpected = List.copyOf(Objects.requireNonNull(unexpected, "unexpected"));
        unsupported = List.copyOf(Objects.requireNonNull(unsupported, "unsupported"));
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(timings, "timings");
    }

    public ReconcileReport(ReconcileOutcome outcome, ReconcilePlan plan,
                           List<PlannedChange> applied, List<PlannedChange> reported,
                           List<Difference> unexpected, List<Difference> unsupported,
                           Duration elapsed) {
        this(outcome, plan, applied, reported, unexpected, unsupported, elapsed,
                RunTimings.unmeasured());
    }
}
