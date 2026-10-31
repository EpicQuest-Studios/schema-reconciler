import assert from "node:assert/strict";
import test from "node:test";
import { ConfigurationException } from "../src/ConfigurationException.ts";
import { Decision } from "../src/Decision.ts";
import { DesiredState } from "../src/DesiredState.ts";
import { Duration } from "../src/Duration.ts";
import { IllegalArgumentError } from "../src/IllegalArgumentError.ts";
import { ReconcilePlan } from "../src/model/ReconcilePlan.ts";
import { ReconcilePolicy } from "../src/ReconcilePolicy.ts";
import { SchemaReconciler } from "../src/SchemaReconciler.ts";
import { Definition } from "../src/model/Definition.ts";
import { ObjectId } from "../src/model/ObjectId.ts";
import { ObjectKind } from "../src/model/ObjectKind.ts";
import { SchemaSnapshot } from "../src/model/SchemaSnapshot.ts";
import { FakeDb } from "./fake/FakeDb.ts";
import { FakeDialect } from "./fake/FakeDialect.ts";

/** The general API rules of spec 2.3 and the message rule of spec 7. */

const NS = "corex";

test("publicEntryPointsRejectNull", () => {
    const builder = SchemaReconciler.builder();
    assert.throws(() => builder.dataSource(null!), TypeError);
    assert.throws(() => builder.namespace(null!), TypeError);
    assert.throws(() => builder.desiredState(null!), TypeError);
    assert.throws(() => builder.dialect(null!), TypeError);
    assert.throws(() => builder.scratchDataSource(null!), TypeError);
    assert.throws(() => builder.policy(null!), TypeError);
    assert.throws(() => builder.lockTimeout(null!), TypeError);
    assert.throws(() => builder.statementTimeout(null!), TypeError);
    assert.throws(() => builder.listener(null!), TypeError);
    assert.throws(() => DesiredState.ofScript(null!), TypeError);
    assert.throws(() => DesiredState.ofPath(null!), TypeError);
    assert.throws(() => new Definition(null!), TypeError);
    assert.throws(() => new ObjectId(ObjectKind.TABLE, null!), TypeError);
    assert.throws(() => new ReconcilePlan(null!, "d", "a", "b", [], []), TypeError);
    assert.throws(() => SchemaSnapshot.of(null!), TypeError);
});

test("recordsCopyTheirCollections", () => {
    const changes: any[] = [];
    const plan = new ReconcilePlan("ns", "d", "a", "b", [], changes);
    changes.push(null);
    assert.equal(plan.changes.length, 0);
    assert.throws(() => (plan.changes as any).push(null), TypeError);
});

test("builderMessagesNameTheOffendingProperty", () => {
    const db = new FakeDb();
    try {
        SchemaReconciler.builder().namespace(NS)
                .desiredState(DesiredState.ofScript("")).build();
        assert.fail("expected ConfigurationException");
    } catch (e: any) {
        assert.ok(e instanceof ConfigurationException);
        assert.match(e.message, /dataSource/);
    }
    try {
        SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("")).dialect(new FakeDialect(db))
                .lockTimeout(Duration.ZERO).build();
        assert.fail("expected ConfigurationException");
    } catch (e: any) {
        assert.match(e.message, /lockTimeout/);
    }
    try {
        SchemaReconciler.builder().dataSource(db.dataSource()).namespace(NS)
                .desiredState(DesiredState.ofScript("")).dialect(new FakeDialect(db))
                .statementTimeout(Duration.ofMillis(-1)).build();
        assert.fail("expected ConfigurationException");
    } catch (e: any) {
        assert.match(e.message, /statementTimeout/);
    }
});

test("runMessagesRaisedBelowTheEngineNameTheNamespace", async () => {
    const db = new FakeDb();
    const unsupported = new class extends FakeDialect {
        override supports(): boolean {
            return false;
        }
    }(db);
    const reconciler = SchemaReconciler.builder().dataSource(db.dataSource())
            .namespace(NS).desiredState(DesiredState.ofScript("CREATE TABLE t DEF v"))
            .dialect(unsupported).build();
    let e: unknown;
    try {
        await reconciler.plan();
    } catch (x) {
        e = x;
    }
    assert.ok(e instanceof ConfigurationException);
    assert.match((e as Error).message, new RegExp("namespace '" + NS + "'"));
});

test("policyRejectsApplyForUnsupported", () => {
    assert.throws(() => ReconcilePolicy.defaults().withUnsupported(Decision.APPLY),
            IllegalArgumentError);
    assert.throws(() => ReconcilePolicy.defaults()
            .withUnsupportedCategory("extension", Decision.APPLY), IllegalArgumentError);
});

test("objectIdEnforcesParentAndNameRules", () => {
    assert.throws(() => new ObjectId(ObjectKind.COLUMN, "c"), IllegalArgumentError);
    assert.throws(() => new ObjectId(ObjectKind.TABLE, "t", "p"), IllegalArgumentError);
    assert.throws(() => new ObjectId(ObjectKind.TABLE, ""), IllegalArgumentError);
    assert.throws(() => new ObjectId(ObjectKind.COLUMN, "c", ""), IllegalArgumentError);
    new ObjectId(ObjectKind.INDEX, "i", "t");
    new ObjectId(ObjectKind.TRIGGER, "tr", "t");
    new ObjectId(ObjectKind.CHECK, "ck", "t");
});

test("buildOpensNoConnection", () => {
    const db = new FakeDb();
    const failing = {
        connect() {
            throw new Error("build() must not connect");
        },
    };
    SchemaReconciler.builder().dataSource(failing).namespace("corex")
            .desiredState(DesiredState.ofScript("")).dialect(new FakeDialect(db)).build();
});
