import assert from "node:assert/strict";
import test from "node:test";
import { Dependencies } from "../src/internal/Dependencies.ts";
import { Definition } from "../src/model/Definition.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { ObjectMap } from "../src/model/ObjectMap.ts";
import { ObjectSet } from "../src/model/ObjectSet.ts";
import { SchemaObject } from "../src/model/SchemaObject.ts";
import { SchemaSnapshot } from "../src/model/SchemaSnapshot.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** Dependency-graph helpers: parent resolution, reversal, reachability. */

const DIALECT = new FakeDialect(new FakeDb());

function object(id: ObjectId, ...prereqs: ObjectId[]): SchemaObject {
    return new SchemaObject(id, new Definition("def " + id.name),
            new ObjectSet(prereqs), new Map());
}

function snapshot(...objects: SchemaObject[]): SchemaSnapshot {
    const map = new ObjectMap<SchemaObject>();
    for (const o of objects) {
        map.set(o.id, o);
    }
    return SchemaSnapshot.of(map);
}

function prereqsOf(snap: SchemaSnapshot): ObjectMap<ObjectSet> {
    return Dependencies.prerequisitesOf(DIALECT, snap);
}

function sameSet(expected: ObjectId[], actual: ObjectSet | undefined): boolean {
    return actual !== undefined && actual.size === expected.length
            && expected.every(id => actual.has(id));
}

test("managedObjectWinsOverOtherOnResolvableNameCollision", () => {
    // a range type and a materialized view share the name "t": the index
    // parented on "t" must resolve to the managed object, not the OTHER
    const type = new ObjectId(ObjectKind.OTHER, "type:t");
    const mv = new ObjectId(ObjectKind.MATERIALIZED_VIEW, "t");
    const index = new ObjectId(ObjectKind.INDEX, "i", "t");
    const prereqs = prereqsOf(snapshot(object(type), object(mv), object(index)));
    assert.ok(sameSet([mv], prereqs.get(index)));
});

test("otherFillsTheParentableGapWhenNoManagedObjectMatches", () => {
    // an unsupported inheritance child's name is still the parent anchor —
    // children parented on it must resolve through the OTHER prefix
    const child = new ObjectId(ObjectKind.OTHER, "inherited:pc");
    const index = new ObjectId(ObjectKind.INDEX, "i", "pc");
    const prereqs = prereqsOf(snapshot(object(child), object(index)));
    assert.ok(sameSet([child], prereqs.get(index)));
});

test("nonRelationOtherIsNeverAParent", () => {
    // an exclusion constraint's stripped name is the synthetic
    // "<parent>.<name>" — its only relation name is the backing index, so
    // it must not shadow the relation literally named "t.ex"
    const constraint = new ObjectId(ObjectKind.OTHER, "constraint:t.ex");
    const relation = new ObjectId(ObjectKind.OTHER, "inherited:t.ex");
    const index = new ObjectId(ObjectKind.INDEX, "i", "t.ex");
    const prereqs = prereqsOf(
            snapshot(object(constraint), object(relation), object(index)));
    assert.ok(sameSet([relation], prereqs.get(index)));
    // and a routine's "name(sig)" stripped form is not parentable either
    const routine = new ObjectId(ObjectKind.OTHER, "routine:f(int)");
    const orphan = new ObjectId(ObjectKind.INDEX, "i", "f(int)");
    const prereqs2 = prereqsOf(snapshot(object(routine), object(orphan)));
    assert.equal(prereqs2.get(orphan)!.size, 0);
});

test("partitionAndForeignTableAreParentable", () => {
    // a partition and a foreign table carry COLUMN children like a
    // table — the implicit parent edge must resolve for them too
    const pp = new ObjectId(ObjectKind.TABLE, "pp");
    const part = new ObjectId(ObjectKind.PARTITION, "pt", "pp");
    const col = new ObjectId(ObjectKind.COLUMN, "c", "pt");
    const prereqs = prereqsOf(snapshot(object(pp), object(part), object(col)));
    assert.ok(sameSet([part], prereqs.get(col)));
    assert.ok(sameSet([pp], prereqs.get(part)));
    const ft = new ObjectId(ObjectKind.FOREIGN_TABLE, "ft");
    const fcol = new ObjectId(ObjectKind.COLUMN, "c", "ft");
    const prereqs2 = prereqsOf(snapshot(object(ft), object(fcol)));
    assert.ok(sameSet([ft], prereqs2.get(fcol)));
});

test("selfEdgesAndNullParentsAreIgnored", () => {
    const t = new ObjectId(ObjectKind.TABLE, "t");
    const prereqs = prereqsOf(snapshot(object(t, t)));
    assert.equal(prereqs.get(t)!.size, 0);
});

test("reverseAndReachableWalkDependents", () => {
    const a = new ObjectId(ObjectKind.TABLE, "a");
    const b = new ObjectId(ObjectKind.VIEW, "b");
    const c = new ObjectId(ObjectKind.VIEW, "c");
    const prereqs = prereqsOf(snapshot(object(a), object(b, a), object(c, b)));
    const dependents = Dependencies.reverse(prereqs);
    assert.ok(sameSet([b], dependents.get(a)));
    assert.ok(sameSet([c], dependents.get(b)));
    assert.ok(sameSet([b, c], Dependencies.reachable(a, dependents)));
});
