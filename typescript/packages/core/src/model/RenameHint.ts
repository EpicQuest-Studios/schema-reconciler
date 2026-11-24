import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import type { ObjectId } from "./ObjectId.ts";

/**
 * An explicit rename hint (spec 3.2 directive
 * `-- @reconciler rename <KIND> [parent.]old -> new`): when `from` exists
 * in the actual snapshot, `to` does not, `to` is desired and `from` is
 * not, the planner emits `ALTER ... RENAME` instead of a drop+create.
 */
export class RenameHint {
    readonly from: ObjectId;
    readonly to: ObjectId;

    constructor(from: ObjectId, to: ObjectId) {
        if (from == null) {
            throw new TypeError("from is required");
        }
        if (to == null) {
            throw new TypeError("to is required");
        }
        if (from.kind !== to.kind) {
            throw new IllegalArgumentError(
                    "rename kinds differ: " + from + " -> " + to);
        }
        this.from = from;
        this.to = to;
        Object.freeze(this);
    }
}
