import { createHash } from "node:crypto";
import type { Hash } from "node:crypto";
import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import { ObjectIds } from "./ObjectIds.ts";
import { ObjectMap } from "./ObjectMap.ts";
import type { ObjectId } from "./ObjectId.ts";
import type { SchemaObject } from "./SchemaObject.ts";

/**
 * An immutable, namespace-neutral description of a set of schema objects (spec 4.2).
 *
 * {@link SchemaSnapshot.of} computes the fingerprint defined by spec 5.7, so
 * both snapshots a dialect produces share one implementation. The canonical
 * constructor verifies a supplied fingerprint against the same computation.
 */
export class SchemaSnapshot {
    readonly objects: ObjectMap<SchemaObject>;
    readonly fingerprint: string;

    constructor(objects: ObjectMap<SchemaObject>, fingerprint: string) {
        if (objects == null) {
            throw new TypeError("objects is required");
        }
        if (fingerprint == null) {
            throw new TypeError("fingerprint is required");
        }
        if (fingerprint !== SchemaSnapshot.fingerprintOf(objects)) {
            throw new IllegalArgumentError("fingerprint does not match objects");
        }
        this.objects = new ObjectMap(objects);
        this.fingerprint = fingerprint;
        Object.freeze(this);
    }

    static of(objects: ObjectMap<SchemaObject> | Iterable<[ObjectId, SchemaObject]>): SchemaSnapshot {
        const map = objects instanceof ObjectMap ? objects : new ObjectMap(objects);
        return new SchemaSnapshot(map, SchemaSnapshot.fingerprintOf(map));
    }

    object(id: ObjectId): SchemaObject | undefined {
        return this.objects.get(id);
    }

    /** The byte-exact fingerprint format of spec 5.7. */
    static fingerprintOf(objects: ObjectMap<SchemaObject>): string {
        const ids = [...objects.keys()];
        ids.sort(ObjectIds.ORDER);
        const digest = createHash("sha256");
        for (const id of ids) {
            put(digest, id.kind);
            put(digest, id.parent ?? "");
            put(digest, id.name);
            put(digest, objects.get(id)!.definition.canonical);
        }
        return digest.digest("hex");
    }
}

function put(digest: Hash, field: string): void {
    const bytes = Buffer.from(field, "utf8");
    digest.update(bytes.length.toString(), "ascii");
    digest.update(":");
    digest.update(bytes);
}
