import type { ObjectId } from "../model/ObjectId.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import { ObjectMap } from "../model/ObjectMap.ts";
import { ObjectSet } from "../model/ObjectSet.ts";
import type { SchemaObject } from "../model/SchemaObject.ts";
import { SchemaSnapshot } from "../model/SchemaSnapshot.ts";

const MIRRORED: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.COMMENT, ObjectKind.SECURITY_LABEL,
    ObjectKind.OWNERSHIP, ObjectKind.PRIVILEGES]);

/** OTHER relation prefixes whose rows can own catalog children. */
const RELATION_OTHERS: ReadonlySet<string> = new Set([
    "inherited", "partindex", "toast", "relation"]);

/** OTHER prefixes that name a target — the unsupported shadow forms of
 *  the mirrored kinds ({@code comment:}/{@code seclabel:}/
 *  {@code privileges:}/{@code ownership:}), and {@code constraint:},
 *  whose {@code <parent>.<member>} name carries the relation it
 *  constrains. */
const ANNOTATION_OTHERS: ReadonlySet<string> = new Set([
    "comment:", "seclabel:", "privileges:", "ownership:",
    "constraint:"]);

/**
 * The object filter of {@code Builder.filter}: removes every object the
 * predicate rejects, plus everything the rejected object covers — its
 * parented children, the mirrored annotation kinds ({@code COMMENT},
 * {@code SECURITY_LABEL}, {@code OWNERSHIP}, {@code PRIVILEGES}) placed on
 * it or on one of its children, and the annotation {@code OTHER}s
 * ({@code comment:}/{@code seclabel:}/{@code privileges:}/{@code
 * ownership:}) naming it, and follower sequences whose {@code OWNED BY}
 * dependency names it — and returns a snapshot over the survivors.
 * The filtered snapshot carries a fresh fingerprint, so plan staleness (5.7)
 * is scoped to the visible objects.
 */
export const ObjectFilter = {
    apply(snapshot: SchemaSnapshot, include: (id: ObjectId) => boolean): SchemaSnapshot {
        const remaining = new ObjectMap<SchemaObject>();
        const removed = new ObjectSet();
        const removedOwnerNames = new Set<string>();
        const removedTargets = new Map<string, Set<string>>();
        const removedTargetNames = new Set<string>();
        for (const object of snapshot.objects.values()) {
            const id = object.id;
            if (include(id)) {
                remaining.set(id, object);
            } else {
                removed.add(id);
                drop(id, removedOwnerNames, removedTargets, removedTargetNames);
            }
        }
        if (remaining.size === snapshot.objects.size) {
            return snapshot;
        }
        // children and mirrored annotations of a removed object go too —
        // transitively, since a removed child can itself own names (a
        // partitioned partition, an inherited: OTHER relation)
        let changed = true;
        while (changed) {
            changed = false;
            for (const [, object] of [...remaining.entries()]) {
                const id = object.id;
                if (removedOwnerNames.has(id.parent ?? "")
                        || mirrorsExcluded(id, removedTargets)
                        || annotationOnRemoved(id, removedTargetNames)
                        || followerOf(id, object, removed)) {
                    remaining.delete(id);
                    removed.add(id);
                    drop(id, removedOwnerNames, removedTargets,
                            removedTargetNames);
                    changed = true;
                }
            }
        }
        return remaining.size === snapshot.objects.size
                ? snapshot : SchemaSnapshot.of(remaining);
    },
} as const;

/** A serial-owned sequence follows the relation that owns it — the
 *  {@code OWNED BY} dependency edge is the same covered-child signal
 *  {@code Dialect.coveredChildren} uses. */
function followerOf(id: ObjectId, object: SchemaObject, removed: ObjectSet): boolean {
    if (id.kind !== ObjectKind.SEQUENCE) {
        return false;
    }
    for (const dep of object.dependsOn) {
        if (removed.has(dep)) {
            return true;
        }
    }
    return false;
}

/** Records {@code id}'s owned names so children, mirrors and OTHER
 *  annotations are removed. */
function drop(id: ObjectId, removedOwnerNames: Set<string>,
              removedTargets: Map<string, Set<string>>,
              removedTargetNames: Set<string>): void {
    const owner = ownerName(id);
    if (owner !== undefined) {
        removedOwnerNames.add(owner);
    }
    let targets = removedTargets.get(id.parent ?? "");
    if (targets === undefined) {
        targets = new Set();
        removedTargets.set(id.parent ?? "", targets);
    }
    targets.add(id.name);
    removedTargetNames.add(
            id.parent !== undefined ? id.parent + "." + pgName(id) : pgName(id));
}

/** The catalog name an annotation embeds: an OTHER id's
 *  {@code <prefix>:<name>} reduces to {@code <name>}. */
function pgName(id: ObjectId): string {
    const name = id.name;
    if (id.kind === ObjectKind.OTHER) {
        const colon = name.indexOf(":");
        return colon > 0 ? name.substring(colon + 1) : name;
    }
    return name;
}

/** Whether {@code id} is an annotation-OTHER whose target was removed. */
function annotationOnRemoved(id: ObjectId, removedTargetNames: Set<string>): boolean {
    if (id.kind !== ObjectKind.OTHER) {
        return false;
    }
    const name = id.name;
    for (const prefix of ANNOTATION_OTHERS) {
        if (!name.startsWith(prefix)) {
            continue;
        }
        let target = name.substring(prefix.length);
        if (prefix === "seclabel:") {
            // seclabel:<provider>.<target> — the provider is one segment
            const dot = target.indexOf(".");
            if (dot < 0) {
                continue;
            }
            target = target.substring(dot + 1);
        } else if (prefix === "constraint:") {
            // constraint:<parent>.<member> — only the parent is a target;
            // stripping further would confuse a member name with a
            // column's qualified name
            const dot = target.lastIndexOf(".");
            return dot > 0 && removedTargetNames.has(target.substring(0, dot));
        }
        // trailing .attname segments do not belong to the target
        while (target.length > 0) {
            if (removedTargetNames.has(target)) {
                return true;
            }
            const dot = target.lastIndexOf(".");
            if (dot < 0) {
                break;
            }
            target = target.substring(0, dot);
        }
        return false;
    }
    return false;
}

/** The name children use to parent on this object, when it can own any. */
function ownerName(id: ObjectId): string | undefined {
    switch (id.kind) {
        case ObjectKind.TABLE:
        case ObjectKind.VIEW:
        case ObjectKind.MATERIALIZED_VIEW:
        case ObjectKind.PARTITION:
        case ObjectKind.FOREIGN_TABLE:
            return id.name;
        case ObjectKind.OTHER: {
            const name = id.name;
            const colon = name.indexOf(":");
            return colon > 0 && RELATION_OTHERS.has(name.substring(0, colon))
                    ? name.substring(colon + 1) : undefined;
        }
        default:
            return undefined;
    }
}

/** Whether {@code id} is a mirrored annotation on an excluded object. */
function mirrorsExcluded(id: ObjectId,
                         excludedTargets: Map<string, Set<string>>): boolean {
    if (!MIRRORED.has(id.kind)) {
        return false;
    }
    const names = excludedTargets.get(id.parent ?? "");
    return names !== undefined && names.has(targetName(id));
}

/**
 * The annotated object's name inside a mirrored id: the {@code " (KIND)"}
 * collision suffix and a security label's {@code provider:} prefix do not
 * belong to it.
 */
function targetName(id: ObjectId): string {
    let name = id.name;
    const paren = name.indexOf(" (");
    if (paren >= 0) {
        name = name.substring(0, paren);
    }
    if (id.kind === ObjectKind.SECURITY_LABEL) {
        // the provider is the segment before the first ':'
        const colon = name.indexOf(":");
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
    }
    return name;
}
