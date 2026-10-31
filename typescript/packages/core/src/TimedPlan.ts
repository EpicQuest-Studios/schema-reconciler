import type { ReconcilePlan } from "./model/ReconcilePlan.ts";
import type { RunTimings } from "./RunTimings.ts";

/**
 * A plan together with the phase timings of the run that computed it
 * (spec 3.5). {@link RunTimings#apply} and {@link RunTimings#verify} are
 * zero: planning applies nothing.
 */
export class TimedPlan {
    readonly plan: ReconcilePlan;
    readonly timings: RunTimings;

    constructor(plan: ReconcilePlan, timings: RunTimings) {
        if (plan == null) {
            throw new TypeError("plan is required");
        }
        if (timings == null) {
            throw new TypeError("timings is required");
        }
        this.plan = plan;
        this.timings = timings;
        Object.freeze(this);
    }
}
