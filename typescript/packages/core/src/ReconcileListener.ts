import type { Duration } from "./Duration.ts";
import type { PlannedChange } from "./model/PlannedChange.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";

/**
 * Callbacks on the reconciling run (spec 3.6). A thrown error aborts the
 * run as {@link ApplyException}; in transactional dialects everything rolls
 * back. Callbacks may return a Promise — the engine awaits it.
 */
export interface ReconcileListener {
    planned?(plan: ReconcilePlan): void | Promise<void>;
    applying?(change: PlannedChange): void | Promise<void>;
    applied?(change: PlannedChange, elapsed: Duration): void | Promise<void>;
    reported?(change: PlannedChange): void | Promise<void>;
}
