import assert from "node:assert/strict";
import test from "node:test";
import { IllegalArgumentError } from "../src/IllegalArgumentError.ts";
import { Definition } from "../src/model/Definition.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { ObjectMap } from "../src/model/ObjectMap.ts";
import { SchemaObject } from "../src/model/SchemaObject.ts";
import { SchemaSnapshot } from "../src/model/SchemaSnapshot.ts";

/** Spec 5.7: the byte-exact fingerprint contract. */

function obj(kind: ObjectKind, name: string, def: string): SchemaObject {
    return new SchemaObject(new ObjectId(kind, name), new Definition(def), new Set(), new Map());
}

test("fingerprintIsOrderIndependent", () => {
    const a = new ObjectMap<SchemaObject>();
    a.set(new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"));
    a.set(new ObjectId(ObjectKind.VIEW, "v"), obj(ObjectKind.VIEW, "v", "d2"));
    const b = new ObjectMap<SchemaObject>();
    b.set(new ObjectId(ObjectKind.VIEW, "v"), obj(ObjectKind.VIEW, "v", "d2"));
    b.set(new ObjectId(ObjectKind.TABLE, "t"), obj(ObjectKind.TABLE, "t", "d1"));
    assert.equal(SchemaSnapshot.of(a).fingerprint, SchemaSnapshot.of(b).fingerprint);
});

test("fingerprintChangesWithDefinition", () => {
    const a = new ObjectMap([[new ObjectId(ObjectKind.TABLE, "t"),
            obj(ObjectKind.TABLE, "t", "d1")]]);
    const b = new ObjectMap([[new ObjectId(ObjectKind.TABLE, "t"),
            obj(ObjectKind.TABLE, "t", "d2")]]);
    assert.notEqual(SchemaSnapshot.of(a).fingerprint, SchemaSnapshot.of(b).fingerprint);
});

test("fingerprintIsLowercaseHexSha256", () => {
    const fp = SchemaSnapshot.of(new ObjectMap([[new ObjectId(ObjectKind.TABLE, "t"),
            obj(ObjectKind.TABLE, "t", "d1")]])).fingerprint;
    assert.equal(fp.length, 64);
    assert.equal(fp, fp.toLowerCase());
    assert.equal(fp, fp.replace(/[^0-9a-f]/g, ""));
});

test("emptySnapshotFingerprint", () => {
    // sha256 of the empty byte string — a fixed reference value
    assert.equal(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SchemaSnapshot.of(new ObjectMap()).fingerprint);
});

test("constructorRejectsWrongFingerprint", () => {
    const objects = new ObjectMap([[new ObjectId(ObjectKind.TABLE, "t"),
            obj(ObjectKind.TABLE, "t", "d1")]]);
    assert.throws(() => new SchemaSnapshot(objects, "0".repeat(64)), IllegalArgumentError);
});

test("fingerprintUsesLengthPrefixedFields", () => {
    // "ab"+"c" and "a"+"bc" in field position must not collide
    const a = new ObjectMap([[new ObjectId(ObjectKind.TABLE, "ab"),
            new SchemaObject(new ObjectId(ObjectKind.TABLE, "ab"),
                    new Definition("c"), new Set(), new Map())]]);
    const b = new ObjectMap([[new ObjectId(ObjectKind.TABLE, "a"),
            new SchemaObject(new ObjectId(ObjectKind.TABLE, "a"),
                    new Definition("bc"), new Set(), new Map())]]);
    assert.notEqual(SchemaSnapshot.of(a).fingerprint, SchemaSnapshot.of(b).fingerprint);
});
