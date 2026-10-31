import assert from "node:assert/strict";
import test from "node:test";
import { Differ } from "../src/internal/Differ.ts";
import { Definition } from "../src/model/Definition.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { ObjectMap } from "../src/model/ObjectMap.ts";
import { SchemaObject } from "../src/model/SchemaObject.ts";
import { SchemaSnapshot } from "../src/model/SchemaSnapshot.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** Spec 4.2: comparison uses the definition only, never the attributes. */

function snapshot(attribute: string): SchemaSnapshot {
    const id = new ObjectId(ObjectKind.TABLE, "t");
    return SchemaSnapshot.of(new ObjectMap([[id, new SchemaObject(id,
            new Definition("same"), new Set(), new Map([["note", attribute]]))]]));
}

test("differentAttributesWithEqualDefinitionsAreNotADifference", () => {
    const diff = Differ.diff(snapshot("a"), snapshot("b"), new FakeDialect(new FakeDb()));
    assert.equal(diff.length, 0);
});
