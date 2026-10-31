import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";

/** The plan contains changes mapped to {@code FAIL}; carries the plan (spec 7). */
export class PolicyViolationException extends SchemaReconcilerException {

    readonly plan: ReconcilePlan;

    constructor(message: string, plan: ReconcilePlan) {
        super(message);
        if (plan == null) {
            throw new TypeError("plan is required");
        }
        this.plan = plan;
    }
}
