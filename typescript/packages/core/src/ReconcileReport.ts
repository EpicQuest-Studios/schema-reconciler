import { RunTimings } from "./RunTimings.ts";
import type { Difference } from "./model/Difference.ts";
import type { Duration } from "./Duration.ts";
import type { PlannedChange } from "./model/PlannedChange.ts";
import type { ReconcileOutcome } from "./ReconcileOutcome.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";

/**
 * The result of {@code reconcile()} or {@code apply(plan)} (spec 3.5).
 */
export class ReconcileReport {
    readonly outcome: ReconcileOutcome;
    readonly plan: ReconcilePlan;
    readonly applied: readonly PlannedChange[];
    readonly reported: readonly PlannedChange[];
    readonly unexpected: readonly Difference[];
    readonly unsupported: readonly Difference[];
    readonly elapsed: Duration;
    readonly timings: RunTimings;

    constructor(outcome: ReconcileOutcome, plan: ReconcilePlan,
                applied: readonly PlannedChange[], reported: readonly PlannedChange[],
                unexpected: readonly Difference[], unsupported: readonly Difference[],
                elapsed: Duration, timings?: RunTimings) {
        for (const [name, v] of Object.entries({ outcome, plan, elapsed })) {
            if (v == null) {
                throw new TypeError(name + " is required");
            }
        }
        for (const [name, v] of Object.entries({ applied, reported, unexpected, unsupported })) {
            if (v == null) {
                throw new TypeError(name + " is required");
            }
        }
        this.outcome = outcome;
        this.plan = plan;
        this.applied = Object.freeze([...applied]);
        this.reported = Object.freeze([...reported]);
        this.unexpected = Object.freeze([...unexpected]);
        this.unsupported = Object.freeze([...unsupported]);
        this.elapsed = elapsed;
        this.timings = timings ?? RunTimings.unmeasured();
        Object.freeze(this);
    }
}
