package studio.epicquest.schema.reconciler;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-phase wall-clock timings of one reconcile run (spec 3.5), for
 * observing lock-holding and apply duration on a realistic database.
 * {@code lockWait} is the time spent acquiring the namespace lock —
 * contention shows up there; {@link #apply()} is the window in which DDL
 * locks are held (they release at commit, after {@link #verify()}).
 */
public record RunTimings(
        Duration lockWait,
        Duration readActual,
        Duration loadDesired,
        Duration plan,
        Duration apply,
        Duration verify) {

    public RunTimings {
        Objects.requireNonNull(lockWait, "lockWait");
        Objects.requireNonNull(readActual, "readActual");
        Objects.requireNonNull(loadDesired, "loadDesired");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(apply, "apply");
        Objects.requireNonNull(verify, "verify");
    }

    /** Zero timings for reports built without measurement. */
    public static RunTimings unmeasured() {
        return new RunTimings(Duration.ZERO, Duration.ZERO, Duration.ZERO,
                Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }
}
