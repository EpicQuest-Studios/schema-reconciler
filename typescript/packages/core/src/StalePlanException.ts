import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";

/** The target changed between {@code plan()} and {@code apply()} (spec 7). */
export class StalePlanException extends SchemaReconcilerException {

    /** The stale plan, when the failure is bound to one. */
    readonly plan: ReconcilePlan | undefined;

    constructor(message: string, plan?: ReconcilePlan, cause?: unknown) {
        super(message, cause);
        this.plan = plan;
    }
}
