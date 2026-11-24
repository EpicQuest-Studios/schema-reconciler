import type { Duration } from "./Duration.ts";
import type { ReconcileOutcome } from "./ReconcileOutcome.ts";
import type { ReconcileReport } from "./ReconcileReport.ts";

/**
 * The result of {@link MultiNamespaceReconciler#reconcile} (spec 3.7):
 * one {@link ReconcileReport} per configured namespace, keyed by its
 * name in configured order, plus the aggregate {@link #outcome} —
 * {@code RECONCILED} when any namespace reconciled, else
 * {@code REPORTED_ONLY} when any reported, else {@code UP_TO_DATE}.
 */
export class MultiReconcileReport {
    readonly outcome: ReconcileOutcome;
    readonly reports: ReadonlyMap<string, ReconcileReport>;
    readonly elapsed: Duration;

    constructor(outcome: ReconcileOutcome,
                reports: ReadonlyMap<string, ReconcileReport>,
                elapsed: Duration) {
        for (const [name, v] of Object.entries({ outcome, reports, elapsed })) {
            if (v == null) {
                throw new TypeError(name + " is required");
            }
        }
        this.outcome = outcome;
        this.reports = new Map(reports);
        this.elapsed = elapsed;
        Object.freeze(this.reports);
        Object.freeze(this);
    }
}
