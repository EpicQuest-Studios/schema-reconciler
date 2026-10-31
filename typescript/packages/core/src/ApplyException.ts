import { SchemaReconcilerException } from "./SchemaReconcilerException.ts";
import type { PlannedChange } from "./model/PlannedChange.ts";

/**
 * A statement failed, or a listener aborted the run (spec 7). Carries the
 * changes applied so far (possibly none) and, when known, the change being
 * processed. On a transactional-DDL dialect the whole run is rolled back, so
 * the applied changes are not persisted.
 */
export class ApplyException extends SchemaReconcilerException {

    /** The changes already applied when the failure happened. */
    readonly applied: readonly PlannedChange[];

    /** The change being processed, if the failure is bound to one. */
    readonly failed: PlannedChange | undefined;

    constructor(message: string, applied: readonly PlannedChange[],
                failed?: PlannedChange, cause?: unknown) {
        super(message, cause);
        this.applied = Object.freeze([...applied]);
        this.failed = failed;
    }
}
