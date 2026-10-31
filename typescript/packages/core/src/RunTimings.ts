import { Duration } from "./Duration.ts";

/**
 * Per-phase wall-clock timings of one reconcile run (spec 3.5), for
 * observing lock-holding and apply duration on a realistic database.
 * {@link lockWait} is the time spent acquiring the namespace lock —
 * contention shows up there; {@link apply} is the window in which DDL
 * locks are held (they release at commit, after {@link verify}).
 */
export class RunTimings {
    readonly lockWait: Duration;
    readonly readActual: Duration;
    readonly loadDesired: Duration;
    readonly plan: Duration;
    readonly apply: Duration;
    readonly verify: Duration;

    constructor(lockWait: Duration, readActual: Duration, loadDesired: Duration,
                plan: Duration, apply: Duration, verify: Duration) {
        for (const [name, v] of Object.entries({ lockWait, readActual, loadDesired, plan, apply, verify })) {
            if (v == null) {
                throw new TypeError(name + " is required");
            }
        }
        this.lockWait = lockWait;
        this.readActual = readActual;
        this.loadDesired = loadDesired;
        this.plan = plan;
        this.apply = apply;
        this.verify = verify;
        Object.freeze(this);
    }

    /** Zero timings for reports built without measurement. */
    static unmeasured(): RunTimings {
        return new RunTimings(Duration.ZERO, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }
}
