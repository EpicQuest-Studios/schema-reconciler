import type { ObjectId } from "../model/ObjectId.ts";
import { ObjectIds } from "../model/ObjectIds.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import { ObjectMap } from "../model/ObjectMap.ts";
import { ObjectSet } from "../model/ObjectSet.ts";
import type { SchemaObject } from "../model/SchemaObject.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import type { Dialect } from "../spi/Dialect.ts";
import { Dependencies } from "./Dependencies.ts";
import { MinHeap } from "./MinHeap.ts";

/**
 * Renders an actual snapshot back into a namespace-neutral desired-state
 * script — the body of {@code SchemaReconciler.dump()} (spec 3.1).
 * Objects emit in {@code dependsOn} order; an object the dialect cannot
 * render becomes a comment line rather than being silently dropped.
 */
export const DumpRenderer = {
    render(dialect: Dialect, namespace: string, snapshot: SchemaSnapshot): string {
        const out: string[] = [];
        out.push("-- schema-reconciler dump\n");
        out.push("-- dialect: " + dialect.id() + "\n");
        out.push("-- namespace: " + dialect.quoteIdentifier(namespace) + "\n");
        out.push("-- fingerprint: " + snapshot.fingerprint + "\n");
        for (const object of order(dialect, snapshot)) {
            const statements = dialect.dumpStatements?.(object) ?? [];
            if (statements.length === 0) {
                out.push("-- " + (folded(object, snapshot) ? "folded" : "unsupported")
                        + ": " + object.id + "\n");
                continue;
            }
            for (const statement of statements) {
                out.push(statement + ";\n");
            }
        }
        return out.join("");
    },
} as const;

/**
 * Topological order over the dependency graph: every object after the
 * objects it requires, ties by {@link ObjectIds#ORDER} so the output is
 * deterministic. Edges naming objects outside the snapshot are ignored;
 * a dependency cycle emits its members in plain ORDER after the
 * acyclic prefix.
 */
function order(dialect: Dialect, snapshot: SchemaSnapshot): SchemaObject[] {
    const objects = snapshot.objects;
    const prereqs = Dependencies.prerequisitesOf(dialect, snapshot);
    const pending = new ObjectMap<ObjectSet>();
    for (const [id, reqs] of prereqs) {
        const present = new ObjectSet(reqs);
        present.retainAll(keysOf(objects));
        pending.set(id, present);
    }
    const dependents = Dependencies.reverse(pending);
    const ready = new MinHeap<ObjectId>(ObjectIds.ORDER);
    for (const [id, reqs] of pending) {
        if (reqs.isEmpty) {
            ready.add(id);
        }
    }
    const ordered: SchemaObject[] = [];
    let id: ObjectId | undefined;
    while ((id = ready.poll()) !== undefined) {
        ordered.push(objects.get(id)!);
        for (const dependent of dependents.get(id) ?? []) {
            const reqs = pending.get(dependent);
            if (reqs !== undefined && reqs.delete(id) && reqs.isEmpty) {
                ready.add(dependent);
            }
        }
    }
    if (ordered.length < objects.size) {
        for (const [remaining] of [...pending.entries()]
                .filter(([, reqs]) => !reqs.isEmpty)
                .sort((a, b) => ObjectIds.ORDER(a[0], b[0]))) {
            ordered.push(objects.get(remaining)!);
        }
    }
    return ordered;
}

function keysOf(objects: ObjectMap<SchemaObject>): ObjectSet {
    return new ObjectSet(objects.keys());
}

/**
 * An object whose creation is folded into another object's fragment and
 * therefore renders no statement of its own (spec 3.1 {@code -- folded}):
 * a column of a managed relation (table, partition, foreign table), or a
 * sequence owned by a table column —
 * detected as a {@code dependsOn} edge to a snapshot TABLE.
 */
function folded(object: SchemaObject, snapshot: SchemaSnapshot): boolean {
    const id = object.id;
    if (id.kind === ObjectKind.COLUMN) {
        if (id.parent === undefined) {
            return false;
        }
        const parent = id.parent;
        for (const o of snapshot.objects.keys()) {
            if (o.name === parent && (o.kind === ObjectKind.TABLE
                    || o.kind === ObjectKind.PARTITION
                    || o.kind === ObjectKind.FOREIGN_TABLE)) {
                return true;
            }
        }
        return false;
    }
    if (id.kind === ObjectKind.SEQUENCE) {
        for (const dep of object.dependsOn) {
            if (dep.kind === ObjectKind.TABLE && snapshot.objects.has(dep)) {
                return true;
            }
        }
        return false;
    }
    return false;
}
