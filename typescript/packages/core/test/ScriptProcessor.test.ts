import assert from "node:assert/strict";
import test from "node:test";
import { DesiredStateException } from "../src/DesiredStateException.ts";
import { ScriptProcessor } from "../src/internal/ScriptProcessor.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** Spec 3.2: placeholder substitution, escaping and the literal-target scan. */

const dialect = new FakeDialect(new FakeDb());

function process(script: string): ScriptProcessor {
    return new ScriptProcessor(script, "corex", "rcl_scratch_1", dialect);
}

test("substitutesQuotedScratchNamespace", () => {
    const p = process("CREATE TABLE ${namespace}.t");
    assert.equal(p.substituted, 'CREATE TABLE "rcl_scratch_1".t');
    assert.equal(p.hasPlaceholder, true);
});

test("unqualifiedScriptHasNoPlaceholder", () => {
    const p = process("CREATE TABLE t");
    assert.equal(p.hasPlaceholder, false);
    assert.equal(p.substituted, "CREATE TABLE t");
});

test("escapedPlaceholderIsLiteralText", () => {
    const p = process("SELECT '$${namespace}'");
    assert.equal(p.substituted, "SELECT '${namespace}'");
    assert.equal(p.hasPlaceholder, false);
});

test("quotedPlaceholderIsRejected", () => {
    assert.throws(() => process('CREATE TABLE "${namespace}".t'), DesiredStateException);
});

test("literalTargetReferenceIsRejected", () => {
    assert.throws(() => process("CREATE TABLE corex.t"), DesiredStateException);
    assert.throws(() => process("SELECT * FROM corex.t"), DesiredStateException);
    assert.throws(() => process('SELECT * FROM "corex".t'), DesiredStateException);
    assert.throws(() => process("SELECT * FROM COREX.t"), DesiredStateException);
});

test("literalTargetScanRespectsQuotedCase", () => {
    // "COREX" quoted is a different namespace from corex — unquoted COREX
    // folds to corex, but quoting makes the name case-sensitive
    assert.equal('SELECT * FROM "COREX".t',
            process('SELECT * FROM "COREX".t').substituted);
    // a mixed-case target is only reachable quoted — bare references fold
    // away and can never name it
    const p = new ScriptProcessor(
            "SELECT * FROM app.t", "App", "rcl_scratch_1", dialect);
    assert.equal(p.substituted, "SELECT * FROM app.t");
    assert.throws(() => new ScriptProcessor('SELECT * FROM "App".t', "App", "s", dialect),
            DesiredStateException);
});

test("similarNamesAreNotTheTarget", () => {
    // identifier prefix/suffix: not the namespace token
    assert.equal("SELECT * FROM corex2.t", process("SELECT * FROM corex2.t").substituted);
    assert.equal("SELECT * FROM x.corex", process("SELECT * FROM x.corex").substituted);
    // target name without a dot following it is a column/table, not a schema ref
    assert.equal("CREATE TABLE corex", process("CREATE TABLE corex").substituted);
});

test("placeholderTargetNameDoesNotFalsePositive", () => {
    // literal namespace named 'namespace': the placeholder must not trip the scan
    const p = new ScriptProcessor(
            "CREATE TABLE ${namespace}.t", "namespace", "rcl_scratch_1", dialect);
    assert.equal(p.substituted, 'CREATE TABLE "rcl_scratch_1".t');
});

test("qualifiedChainDoesNotFalsePositive", () => {
    // 'corex' preceded by '.' is a mid-qualifier, not a namespace reference (3.2 rule 5)
    assert.equal("SELECT a.corex.b", process("SELECT a.corex.b").substituted);
});
