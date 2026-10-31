import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";
import type { Difference } from "./model/Difference.ts";

/**
 * Applied objects did not converge (spec 7). Carries the remaining differences.
 */
export class VerificationException extends SchemaReconcilerException {

    readonly remaining: readonly Difference[];

    constructor(message: string, remaining: readonly Difference[]) {
        super(message);
        if (remaining == null) {
            throw new TypeError("remaining is required");
        }
        this.remaining = Object.freeze([...remaining]);
    }
}
