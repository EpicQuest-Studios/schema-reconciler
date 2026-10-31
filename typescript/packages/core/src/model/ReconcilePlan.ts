import { PlanRenderer } from "../internal/PlanRenderer.ts";
import type { Difference } from "./Difference.ts";
import type { PlannedChange } from "./PlannedChange.ts";

/**
 * An inspectable plan (spec 4.4). {@link toSql} serialises it as plain text:
 * a deterministic header plus one comment block per change; the output is
 * byte-identical for identical input (spec 5.2).
 */
export class ReconcilePlan {
    readonly targetNamespace: string;
    readonly dialectId: string;
    readonly actualFingerprint: string;
    readonly desiredFingerprint: string;
    readonly differences: readonly Difference[];
    readonly changes: readonly PlannedChange[];

    constructor(targetNamespace: string, dialectId: string,
                actualFingerprint: string, desiredFingerprint: string,
                differences: readonly Difference[], changes: readonly PlannedChange[]) {
        if (targetNamespace == null) {
            throw new TypeError("targetNamespace is required");
        }
        if (dialectId == null) {
            throw new TypeError("dialectId is required");
        }
        if (actualFingerprint == null) {
            throw new TypeError("actualFingerprint is required");
        }
        if (desiredFingerprint == null) {
            throw new TypeError("desiredFingerprint is required");
        }
        if (differences == null) {
            throw new TypeError("differences is required");
        }
        if (changes == null) {
            throw new TypeError("changes is required");
        }
        this.targetNamespace = targetNamespace;
        this.dialectId = dialectId;
        this.actualFingerprint = actualFingerprint;
        this.desiredFingerprint = desiredFingerprint;
        this.differences = Object.freeze([...differences]);
        this.changes = Object.freeze([...changes]);
        Object.freeze(this);
    }

    get isEmpty(): boolean {
        return this.changes.length === 0;
    }

    toSql(): string {
        return PlanRenderer.render(this);
    }
}
