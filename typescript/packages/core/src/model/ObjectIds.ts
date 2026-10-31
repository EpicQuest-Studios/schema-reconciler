import type { ObjectId } from "./ObjectId.ts";
import { ObjectKind } from "./ObjectKind.ts";

const ORDINAL: ReadonlyMap<ObjectKind, number> = new Map(
        Object.values(ObjectKind).map((k, i) => [k, i]));

/** UTF-16 code-unit order — the same comparison Java {@code String.compareTo} performs. */
function compare(a: string, b: string): number {
    return a < b ? -1 : a > b ? 1 : 0;
}

/** The kind's position in {@link ObjectKind} declaration order (Java {@code enum.ordinal()}). */
export function kindOrdinal(kind: ObjectKind): number {
    return ORDINAL.get(kind)!;
}

/**
 * The deterministic ordering of {@link ObjectId} required by spec 5.2:
 * kind order, then parent, then name, lexicographically.
 */
export const ObjectIds = {
    ORDER(a: ObjectId, b: ObjectId): number {
        return (ORDINAL.get(a.kind)! - ORDINAL.get(b.kind)!)
                || compare(a.parent ?? "", b.parent ?? "")
                || compare(a.name, b.name);
    },
} as const;
