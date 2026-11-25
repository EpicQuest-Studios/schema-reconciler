import { ObjectId } from "../model/ObjectId.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import { ObjectFilter } from "./ObjectFilter.ts";

export type ExternalPartitionMatcher = (parentTable: string, partitionName: string) => boolean;

/** Hides matching actual partitions, cascading through the object filter. */
export function suppressExternalPartitions(actual: SchemaSnapshot,
        desired: SchemaSnapshot | undefined,
        rules: readonly ExternalPartitionMatcher[],
        requireDesiredAncestor: boolean): SchemaSnapshot {
    if (rules.length === 0) {
        return actual;
    }
    return ObjectFilter.apply(actual, id => {
        if (id.kind !== ObjectKind.PARTITION || id.parent === undefined
                || !rules.some(rule => rule(id.parent!, id.name))) {
            return true;
        }
        if (!requireDesiredAncestor || desired === undefined) {
            return false;
        }
        return desired.objects.has(id) || !hasDesiredPartitionedAncestor(id, actual, desired);
    });
}

function hasDesiredPartitionedAncestor(partition: ObjectId,
        actual: SchemaSnapshot, desired: SchemaSnapshot): boolean {
    let parentName = partition.parent ?? "";
    let directParent = true;
    const seen = new Set<string>();
    while (!seen.has(parentName) && parentName !== "") {
        seen.add(parentName);
        const table = new ObjectId(ObjectKind.TABLE, parentName);
        if (desired.objects.has(table)) {
            return true;
        }
        let actualParent: ObjectId | undefined = actual.objects.has(table) ? table : undefined;
        if (actualParent === undefined) {
            for (const object of actual.objects.values()) {
                if (object.id.kind === ObjectKind.PARTITION && object.id.name === parentName) {
                    actualParent = object.id;
                    break;
                }
            }
        }
        if (actualParent === undefined) {
            return false;
        }
        if ((directParent || actualParent.kind === ObjectKind.TABLE)
                && desired.objects.has(actualParent)) {
            return true;
        }
        if (actualParent.kind !== ObjectKind.PARTITION || actualParent.parent === undefined) {
            return false;
        }
        directParent = false;
        parentName = actualParent.parent;
    }
    return false;
}
