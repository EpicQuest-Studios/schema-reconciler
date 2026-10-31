import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import { ChangeAction } from "./ChangeAction.ts";
import type { ChangeSafety } from "./ChangeSafety.ts";
import type { Definition } from "./Definition.ts";
import type { ObjectId } from "./ObjectId.ts";

/**
 * One planned unit of DDL (spec 4.4).
 *
 * @param order 1-based execution order; {@code 0} on changes returned by
 *        {@code Dialect.render}, before the core assigns the final order
 * @param expected definition the target must have after apply; undefined for
 *        {@code DROP} changes and changes against unexpected objects
 */
export class PlannedChange {
    readonly order: number;
    readonly target: ObjectId;
    readonly action: ChangeAction;
    readonly safety: ChangeSafety;
    readonly statements: readonly string[];
    readonly expected: Definition | undefined;
    readonly reason: string;

    constructor(order: number, target: ObjectId, action: ChangeAction,
                safety: ChangeSafety, statements: readonly string[],
                expected: Definition | undefined, reason: string) {
        if (order < 0) {
            throw new IllegalArgumentError("order must not be negative");
        }
        if (target == null) {
            throw new TypeError("target is required");
        }
        if (action == null) {
            throw new TypeError("action is required");
        }
        if (safety == null) {
            throw new TypeError("safety is required");
        }
        if (statements == null) {
            throw new TypeError("statements is required");
        }
        if (reason == null) {
            throw new TypeError("reason is required");
        }
        if (action === ChangeAction.DROP && expected !== undefined) {
            throw new IllegalArgumentError("a DROP change has no expected definition");
        }
        this.order = order;
        this.target = target;
        this.action = action;
        this.safety = safety;
        this.statements = Object.freeze([...statements]);
        this.expected = expected;
        this.reason = reason;
        Object.freeze(this);
    }

    /** A change the dialect can describe but not perform safely (spec 3.3). */
    get isManual(): boolean {
        return this.statements.length === 0;
    }
}
