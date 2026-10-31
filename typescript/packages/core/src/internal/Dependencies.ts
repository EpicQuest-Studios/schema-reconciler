import type { ObjectId } from "../model/ObjectId.ts";
import { ObjectIds } from "../model/ObjectIds.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import { ObjectMap } from "../model/ObjectMap.ts";
import { ObjectSet } from "../model/ObjectSet.ts";
import type { SchemaObject } from "../model/SchemaObject.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import type { Dialect } from "../spi/Dialect.ts";

const PARENTABLE: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW,
    ObjectKind.SEQUENCE, ObjectKind.INDEX, ObjectKind.OTHER,
    ObjectKind.PARTITION, ObjectKind.FOREIGN_TABLE]);

/**
 * Dependency-graph helpers. A prerequisite edge is a {@code dependsOn} entry
 * or the implicit edge from a child {@link ObjectId} to its {@code parent};
 * self-dependencies are recorded but ignored (spec 5.8.1).
 */
export const Dependencies = {
    /** object -> the objects it requires (dependsOn plus parent, minus itself). */
    prerequisitesOf(dialect: Dialect, snapshot: SchemaSnapshot)
            : ObjectMap<ObjectSet> {
        const parentables = parentablesOf(dialect, snapshot.objects);
        const out = new ObjectMap<ObjectSet>();
        for (const object of snapshot.objects.values()) {
            const prereqs = new ObjectSet(object.dependsOn);
            if (object.id.parent !== undefined) {
                const parent = parentables.get(object.id.parent);
                if (parent !== undefined) {
                    prereqs.add(parent);
                }
            }
            prereqs.delete(object.id);
            out.set(object.id, prereqs);
        }
        return out;
    },

    /** Reverses an object->prerequisites map into object->dependents. */
    reverse(prerequisites: ObjectMap<ObjectSet>): ObjectMap<ObjectSet> {
        const dependents = new ObjectMap<ObjectSet>();
        for (const [id, prereqs] of prerequisites) {
            for (const prereq of prereqs) {
                let set = dependents.get(prereq);
                if (set === undefined) {
                    set = new ObjectSet();
                    dependents.set(prereq, set);
                }
                set.add(id);
            }
        }
        return dependents;
    },

    /** Everything reachable from {@code from} over an object->objects map. */
    reachable(from: ObjectId, edges: ObjectMap<ObjectSet>): ObjectSet {
        const seen = new ObjectSet();
        const stack = [...(edges.get(from) ?? [])];
        while (stack.length > 0) {
            const id = stack.pop()!;
            if (!seen.has(id)) {
                seen.add(id);
                stack.push(...(edges.get(id) ?? []));
            }
        }
        return seen;
    },
} as const;

function parentablesOf(dialect: Dialect, objects: ObjectMap<SchemaObject>)
        : Map<string, ObjectId> {
    // same-catalog names are unique, so two managed objects never collide
    // on a resolvable name — but an OTHER's cross-catalog name can, and
    // the managed object must win. Only an OTHER whose stripped name is a
    // relation name in the dialect's own scopes can be a parent: a
    // constraint's stripped name is the synthetic "<parent>.<name>" and
    // its relation scope is the backing index's name, never a parent.
    const map = new Map<string, ObjectId>();
    for (const o of sorted(objects)) {
        if (PARENTABLE.has(o.id.kind) && o.id.kind !== ObjectKind.OTHER) {
            putIfAbsent(map, resolvableName(o.id), o.id);
        }
    }
    for (const o of sorted(objects)) {
        if (o.id.kind === ObjectKind.OTHER
                && nameScopes(dialect, o.id).has("relation:" + resolvableName(o.id))) {
            putIfAbsent(map, resolvableName(o.id), o.id);
        }
    }
    return map;
}

function putIfAbsent(map: Map<string, ObjectId>, key: string, value: ObjectId): void {
    if (!map.has(key)) {
        map.set(key, value);
    }
}

function nameScopes(dialect: Dialect, id: ObjectId): Set<string> {
    return new Set(dialect.nameScopes?.(id) ?? []);
}

/** An OTHER id's name carries a documented category prefix (3.3); the
 *  parent linkage keys on the object name behind the prefix, so a
 *  {@code matview:mv} still blocks children parented on {@code mv}. */
function resolvableName(id: ObjectId): string {
    const name = id.name;
    if (id.kind === ObjectKind.OTHER) {
        const colon = name.indexOf(":");
        if (colon > 0) {
            return name.substring(colon + 1);
        }
    }
    return name;
}

function sorted(objects: ObjectMap<SchemaObject>): SchemaObject[] {
    return [...objects.values()].sort((a, b) => ObjectIds.ORDER(a.id, b.id));
}
