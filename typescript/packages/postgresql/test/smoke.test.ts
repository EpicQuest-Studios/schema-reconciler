import { test } from "node:test";
import assert from "node:assert/strict";
import { ok } from "@schema-reconciler/core";

test("cross-package type-stripped import resolves", () => {
    assert.equal(ok, true);
});
