import type { Definition } from "./Definition.ts";
import type { DifferenceType } from "./DifferenceType.ts";
import type { ObjectId } from "./ObjectId.ts";

/**
 * One detected difference between the desired and actual snapshots (spec 4.3).
 */
export class Difference {
    readonly id: ObjectId;
    readonly type: DifferenceType;
    readonly expected: Definition | undefined;
    readonly actual: Definition | undefined;

    constructor(id: ObjectId, type: DifferenceType,
                expected: Definition | undefined, actual: Definition | undefined) {
        if (id == null) {
            throw new TypeError("id is required");
        }
        if (type == null) {
            throw new TypeError("type is required");
        }
        this.id = id;
        this.type = type;
        this.expected = expected;
        this.actual = actual;
        Object.freeze(this);
    }
}
