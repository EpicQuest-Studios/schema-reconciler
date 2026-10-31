import type { Definition } from "./Definition.ts";
import type { ObjectId } from "./ObjectId.ts";
import { ObjectSet } from "./ObjectSet.ts";

/**
 * One schema object in a snapshot (spec 4.2).
 *
 * @param id identity
 * @param definition the canonical definition; comparison uses this only
 * @param dependsOn objects this one requires
 * @param attributes dialect-specific details, informational only, never compared
 */
export class SchemaObject {
    readonly id: ObjectId;
    readonly definition: Definition;
    readonly dependsOn: ObjectSet;
    readonly attributes: ReadonlyMap<string, string>;

    constructor(id: ObjectId, definition: Definition,
                dependsOn: Iterable<ObjectId>, attributes: ReadonlyMap<string, string>) {
        if (id == null) {
            throw new TypeError("id is required");
        }
        if (definition == null) {
            throw new TypeError("definition is required");
        }
        if (dependsOn == null) {
            throw new TypeError("dependsOn is required");
        }
        if (attributes == null) {
            throw new TypeError("attributes is required");
        }
        this.id = id;
        this.definition = definition;
        this.dependsOn = new ObjectSet(dependsOn);
        this.attributes = new Map(attributes);
        Object.freeze(this);
    }
}
