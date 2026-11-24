package studio.epicquest.schema.reconciler;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The result of {@link MultiNamespaceReconciler#reconcile} (spec 3.7):
 * one {@link ReconcileReport} per configured namespace, keyed by its
 * name in configured order, plus the aggregate {@link #outcome} —
 * {@code RECONCILED} when any namespace reconciled, else
 * {@code REPORTED_ONLY} when any reported, else {@code UP_TO_DATE}.
 */
public record MultiReconcileReport(ReconcileOutcome outcome,
                                   Map<String, ReconcileReport> reports,
                                   Duration elapsed) {

    public MultiReconcileReport {
        Objects.requireNonNull(outcome, "outcome");
        reports = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(reports, "reports")));
        Objects.requireNonNull(elapsed, "elapsed");
    }
}
