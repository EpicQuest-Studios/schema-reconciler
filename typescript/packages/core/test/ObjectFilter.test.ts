import assert from "node:assert/strict";
import test from "node:test";
import { ObjectFilter } from "../src/internal/ObjectFilter.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { ObjectMap } from "../src/model/ObjectMap.ts";
import { SchemaObject } from "../src/model/SchemaObject.ts";
import { SchemaSnapshot } from "../src/model/SchemaSnapshot.ts";
import { FakeDb } from "./fake/FakeDb.ts";

/**
 * The {@code Builder.filter} cascade: a rejected object takes its children
 * and mirrored annotations with it; everything else survives untouched.
 */

const object = FakeDb.object;

function snapshot(...objects: SchemaObject[]): SchemaSnapshot {
    const map = new ObjectMap<SchemaObject>();
    for (const o of objects) {
        map.set(o.id, o);
    }
    return SchemaSnapshot.of(map);
}

function id(kind: ObjectKind, name: string, parent?: string): ObjectId {
    return new ObjectId(kind, name, parent);
}

function sameIds(expected: ObjectId[], actual: ObjectMap<SchemaObject>): boolean {
    return actual.size === expected.length && expected.every(x => actual.has(x));
}

test("includeEverythingReturnsTheSameSnapshot", () => {
    const snap = snapshot(object(id(ObjectKind.TABLE, "t"), "d"));
    assert.equal(ObjectFilter.apply(snap, () => true), snap);
});

test("excludedTableHidesChildrenAndMirroredAnnotations", () => {
    const snap = snapshot(
            object(id(ObjectKind.TABLE, "t"), "t1"),
            object(id(ObjectKind.COLUMN, "c", "t"), "c1"),
            object(id(ObjectKind.INDEX, "i", "t"), "i1"),
            // mirrored on the table and on its column
            object(id(ObjectKind.COMMENT, "t"), "cm"),
            object(id(ObjectKind.COMMENT, "c", "t"), "cm"),
            object(id(ObjectKind.PRIVILEGES, "t"), "p"),
            object(id(ObjectKind.SECURITY_LABEL, "selinux:t"), "l"),
            object(id(ObjectKind.OWNERSHIP, "t"), "o"),
            object(id(ObjectKind.TABLE, "u"), "u1"),
            object(id(ObjectKind.COLUMN, "c", "u"), "c1"),
            object(id(ObjectKind.COMMENT, "u"), "cm"));
    const filtered = ObjectFilter.apply(snap,
            x => !(x.kind === ObjectKind.TABLE && x.name === "t"));
    assert.ok(sameIds([
            id(ObjectKind.TABLE, "u"),
            id(ObjectKind.COLUMN, "c", "u"),
            id(ObjectKind.COMMENT, "u")], filtered.objects));
});

test("excludedColumnKeepsItsTableButHidesItsAnnotations", () => {
    const snap = snapshot(
            object(id(ObjectKind.TABLE, "t"), "t1"),
            object(id(ObjectKind.COLUMN, "c", "t"), "c1"),
            object(id(ObjectKind.COLUMN, "d", "t"), "d1"),
            object(id(ObjectKind.COMMENT, "c", "t"), "cm"),
            object(id(ObjectKind.COMMENT, "d", "t"), "cm"),
            object(id(ObjectKind.PRIVILEGES, "c", "t"), "p"),
            object(id(ObjectKind.COMMENT, "t"), "cm"),
            // unsupported annotation OTHERs embed the qualified target
            object(id(ObjectKind.OTHER, "comment:t.c"), "cm"),
            object(id(ObjectKind.OTHER, "seclabel:p.t.c"), "l"),
            object(id(ObjectKind.OTHER, "comment:t.d"), "cm"),
            object(id(ObjectKind.OTHER, "comment:t"), "cm"),
            // constraint:<parent>.<member> follows the relation only —
            // member c is not column t.c
            object(id(ObjectKind.OTHER, "constraint:t.c"), "o"));
    const filtered = ObjectFilter.apply(snap,
            x => x.kind !== ObjectKind.COLUMN || x.name !== "c");
    assert.ok(sameIds([
            id(ObjectKind.TABLE, "t"),
            id(ObjectKind.COLUMN, "d", "t"),
            id(ObjectKind.COMMENT, "d", "t"),
            id(ObjectKind.COMMENT, "t"),
            id(ObjectKind.OTHER, "comment:t.d"),
            id(ObjectKind.OTHER, "comment:t"),
            id(ObjectKind.OTHER, "constraint:t.c")], filtered.objects));
});

test("collisionSuffixedMirroredIdsFollowTheirTarget", () => {
    // a " (KIND)" collision suffix and a seclabel provider prefix are not
    // part of the annotated name
    const snap = snapshot(
            object(id(ObjectKind.TABLE, "t"), "t1"),
            object(id(ObjectKind.COMMENT, "t (INDEX)", "x"), "cm"),
            object(id(ObjectKind.SECURITY_LABEL, "p:t (TABLE)"), "l"));
    const filtered = ObjectFilter.apply(snap,
            x => x.kind !== ObjectKind.TABLE);
    // "t (INDEX)" mirrors an INDEX named t on parent x — not TABLE t
    assert.ok(filtered.objects.has(id(ObjectKind.COMMENT, "t (INDEX)", "x")));
    assert.ok(!filtered.objects.has(id(ObjectKind.SECURITY_LABEL, "p:t (TABLE)")));
});

test("excludedOtherRelationHidesChildren", () => {
    const snap = snapshot(
            object(id(ObjectKind.OTHER, "inherited:x"), "d"),
            object(id(ObjectKind.COLUMN, "a", "x"), "c1"),
            object(id(ObjectKind.COLUMN, "a", "y"), "c1"));
    const filtered = ObjectFilter.apply(snap,
            x => x.kind !== ObjectKind.OTHER);
    assert.ok(sameIds([id(ObjectKind.COLUMN, "a", "y")], filtered.objects));
});

test("excludedOtherTargetHidesAnnotationOthers", () => {
    // comment:/seclabel:/privileges:/ownership: OTHERs annotate
    // unmanaged targets — they follow the target out
    const snap = snapshot(
            object(id(ObjectKind.OTHER, "inherited:t"), "d"),
            object(id(ObjectKind.OTHER, "comment:t"), "cm"),
            object(id(ObjectKind.OTHER, "comment:t.c"), "cm"),
            object(id(ObjectKind.OTHER, "seclabel:p.t"), "l"),
            object(id(ObjectKind.OTHER, "seclabel:p.t.c"), "l"),
            object(id(ObjectKind.OTHER, "privileges:t"), "p"),
            object(id(ObjectKind.OTHER, "privileges:t.c"), "p"),
            object(id(ObjectKind.OTHER, "ownership:t"), "o"),
            object(id(ObjectKind.OTHER, "constraint:t.pk"), "o"),
            object(id(ObjectKind.OTHER, "comment:u"), "cm"),
            object(id(ObjectKind.OTHER, "constraint:u.ck"), "o"),
            object(id(ObjectKind.OTHER, "cast:a->b"), "c"));
    const filtered = ObjectFilter.apply(snap,
            x => !(x.kind === ObjectKind.OTHER && x.name === "inherited:t"));
    assert.ok(sameIds([
            id(ObjectKind.OTHER, "comment:u"),
            id(ObjectKind.OTHER, "constraint:u.ck"),
            id(ObjectKind.OTHER, "cast:a->b")], filtered.objects));
});

test("serialSequenceFollowsItsOwningRelation", () => {
    // the OWNED BY dep edge makes the sequence a covered follower of
    // its table — excluding the table hides it, but not a free sequence
    const t = id(ObjectKind.TABLE, "t");
    const snap = snapshot(
            object(t, "d"),
            object(id(ObjectKind.SEQUENCE, "t_c_seq"), "d", t),
            object(id(ObjectKind.SEQUENCE, "free"), "d"));
    const filtered = ObjectFilter.apply(snap,
            x => x.kind !== ObjectKind.TABLE);
    assert.ok(sameIds([id(ObjectKind.SEQUENCE, "free")], filtered.objects));
});

test("cascadeIsTransitiveThroughNestedParents", () => {
    // rt -> md -> lf multi-level partitioning: excluding md removes lf
    // and everything parented on lf
    const snap = snapshot(
            object(id(ObjectKind.TABLE, "rt"), "d"),
            object(id(ObjectKind.PARTITION, "md", "rt"), "d"),
            object(id(ObjectKind.PARTITION, "lf", "md"), "d"),
            object(id(ObjectKind.COLUMN, "j", "lf"), "d"),
            object(id(ObjectKind.COMMENT, "lf", "md"), "cm"),
            object(id(ObjectKind.PUBLICATION, "pub", "lf"), "d"),
            object(id(ObjectKind.COLUMN, "j", "rt"), "d"));
    const filtered = ObjectFilter.apply(snap,
            x => !(x.kind === ObjectKind.PARTITION && x.name === "md"));
    assert.ok(sameIds([
            id(ObjectKind.TABLE, "rt"),
            id(ObjectKind.COLUMN, "j", "rt")], filtered.objects));
});
