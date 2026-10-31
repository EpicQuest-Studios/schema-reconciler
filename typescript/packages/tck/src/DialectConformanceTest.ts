import assert from "node:assert/strict";
import { describe, it, beforeEach, afterEach } from "node:test";
import type { TestContext } from "node:test";
import {
        ChangeAction, ChangeSafety, Decision, DesiredState,
        DesiredStateException, DifferenceType, Duration, IllegalArgumentError,
        LockTimeoutException, ObjectId, ObjectKind, PlanningException,
        PolicyViolationException, ReconcileOutcome, ReconcilePlan,
        ReconcilePolicy, SchemaReconciler, StalePlanException }
        from "@schema-reconciler/core";
import type { Dialect, PlannedChange, ReconcileReport, SchemaSnapshot,
        SqlDataSource } from "@schema-reconciler/core";
import { TrackingDataSource } from "./TrackingDataSource.ts";

/**
 * The dialect conformance suite of spec 9.2 — scenarios C-01 to C-67.
 * Where Java subclasses {@code DialectConformanceTest}, TypeScript calls
 * {@link dialectConformanceSuite} with the same hooks: a disposable
 * database plus per-test namespaces. Skips are permitted only for kinds
 * absent from {@code supportedKinds} or undeclared capabilities, and every
 * skip is reported by the runner.
 */

/** A statement creating an unsupported object and one supported object
 *  depending on it. */
export interface UnsupportedScript {
    readonly script: string;
    readonly dependentName: string;
}

type MaybePromise<T> = T | Promise<T>;

/** The hooks a dialect supplies — the Java abstract/optional methods. */
export interface ConformanceHooks {
    /** A data source on the disposable database under test. */
    dataSource(): SqlDataSource;
    /** The dialect under test. */
    dialect(): Dialect;
    /** Creates and returns a fresh, empty namespace for one test. */
    createNamespace(): Promise<string>;
    /** Removes the namespace and everything in it after the test. */
    dropNamespace(namespace: string): Promise<void>;
    /** A second data source on the same engine, for `scratchDataSource` (C-26). */
    scratchDataSource?(): SqlDataSource;
    /** Asserts that no scratch namespace survives on the target (C-15, C-22). */
    assertNoScratchNamespacesLeft?(): Promise<void>;
    /** Asserts that a pooled connection carries no lock/session state (C-21). */
    assertCleanSession?(): Promise<void>;
    /** Marks the statement that opens scratch work on the same connection. */
    isScratchBoundary?(sql: string): boolean;
    /** Marks the statement that closes scratch work. */
    isScratchEnd?(sql: string): boolean;
    /** A script creating an `UNSUPPORTED` object plus a supported dependent. */
    unsupportedScript?(): UnsupportedScript | undefined;
    /** The policy category of the object {@link unsupportedScript} creates. */
    unsupportedCategory?(): string | undefined;
    /** Whether the dialect renders safe in-place column alterations (C-33). */
    columnAlterSupported?(): boolean;
    /** DDL for an identity-style column (C-32). */
    identityColumnDdl?(): string | undefined;
    /** DDL for a serial-style column (C-32). */
    serialColumnDdl?(): string | undefined;
    /** A `CREATE EXTENSION` the dialect can install in the namespace (C-45). */
    extensionDdl?(): MaybePromise<string | undefined>;
    /** DDL creating foreign server `tck_srv` (C-55). */
    foreignServerDdl?(): MaybePromise<string | undefined>;
    /** Cleanup for {@link foreignServerDdl}. */
    foreignServerTeardownDdl?(): MaybePromise<string | undefined>;
    /** DDL creating empty publication `tck_pub` (C-56). */
    publicationDdl?(): MaybePromise<string | undefined>;
    /** Cleanup for {@link publicationDdl}. */
    publicationTeardownDdl?(): MaybePromise<string | undefined>;
    /** DDL creating roles `tck_owner`/`tck_owner2` (C-57, C-58, C-63, C-64). */
    ownershipRoleDdl?(): MaybePromise<string | undefined>;
    /** Cleanup for {@link ownershipRoleDdl}. */
    ownershipRoleTeardownDdl?(): MaybePromise<string | undefined>;
}

/** Registers the C-01…C-67 scenarios under one {@link describe} block. */
export function dialectConformanceSuite(hooks: ConformanceHooks,
        options?: { skip?: boolean | string }): void {
    let namespace = "";

    const dialect = () => hooks.dialect();
    const dataSource = () => hooks.dataSource();
    const scratch = () => hooks.scratchDataSource?.() ?? dataSource();
    const noScratchLeft = () => hooks.assertNoScratchNamespacesLeft?.();
    const cleanSession = () => hooks.assertCleanSession?.();
    const scratchBoundary = (s: string) => hooks.isScratchBoundary?.(s) ?? false;
    const scratchEnd = (s: string) => hooks.isScratchEnd?.(s) ?? false;

    const q = (id: string) => dialect().quoteIdentifier(id);
    const qualified = (name: string) => q(namespace) + "." + q(name);

    const reconciler = (script: string,
            policy: ReconcilePolicy = ReconcilePolicy.defaults()) =>
        reconcilerOn(dataSource(), script, policy);

    const reconcilerOn = (ds: SqlDataSource, script: string,
            policy: ReconcilePolicy) =>
        SchemaReconciler.builder().dataSource(ds).namespace(namespace)
                .desiredState(DesiredState.ofScript(script))
                .dialect(dialect()).policy(policy).build();

    const reconcilerBuilder = () =>
        SchemaReconciler.builder().dataSource(dataSource())
                .namespace(namespace).dialect(dialect());

    const execute = async (sql: string) => {
        const c = await dataSource().connect();
        try {
            await c.execute(sql);
        } finally {
            await c.close();
        }
    };

    const snapshot = async (): Promise<SchemaSnapshot> => {
        const c = await dataSource().connect();
        try {
            await c.setAutoCommit(false);
            try {
                return await dialect().readSnapshot(c, namespace);
            } finally {
                await c.rollback();
                await c.setAutoCommit(true);
            }
        } finally {
            await c.close();
        }
    };

    const present = async (id: ObjectId) => (await snapshot()).objects.has(id);

    const count = async (relation: string): Promise<number> => {
        const c = await dataSource().connect();
        try {
            const rows = await c.query<{ count: string | number }>(
                    "SELECT count(*) FROM " + qualified(relation));
            return Number(rows[0]?.count ?? -1);
        } finally {
            await c.close();
        }
    };

    const assumeKind = (t: TestContext, kind: ObjectKind) => {
        if (!dialect().capabilities().supportedKinds.has(kind)) {
            t.skip("kind " + kind + " not in supportedKinds");
            return false;
        }
        return true;
    };

    const targetDdl = (ds: TrackingDataSource): string[] => {
        const out: string[] = [];
        let inScratch = false;
        for (const executed of ds.statements()) {
            const sql = executed.sql;
            if (scratchBoundary(sql)) {
                inScratch = true;
                continue;
            }
            if (scratchEnd(sql)) {
                inScratch = false;
                continue;
            }
            if (inScratch) {
                continue;
            }
            const upper = sql.trimStart().toUpperCase();
            if (upper.startsWith("CREATE ") || upper.startsWith("DROP ")
                    || upper.startsWith("ALTER ") || upper.startsWith("INSERT ")
                    || upper.startsWith("UPDATE ") || upper.startsWith("DELETE ")) {
                out.push(sql);
            }
        }
        return out;
    };

    const table = (name: string) => new ObjectId(ObjectKind.TABLE, name);
    const view = (name: string) => new ObjectId(ObjectKind.VIEW, name);
    const index = (p: string, n: string) => new ObjectId(ObjectKind.INDEX, n, p);
    const column = (p: string, n: string) => new ObjectId(ObjectKind.COLUMN, n, p);
    const matview = (n: string) => new ObjectId(ObjectKind.MATERIALIZED_VIEW, n);
    const type = (n: string) => new ObjectId(ObjectKind.TYPE, n);
    const domain = (n: string) => new ObjectId(ObjectKind.DOMAIN, n);
    const policy = (p: string, n: string) => new ObjectId(ObjectKind.POLICY, n, p);
    const exclusion = (p: string, n: string) =>
        new ObjectId(ObjectKind.EXCLUSION, n, p);
    const statistics = (n: string) => new ObjectId(ObjectKind.STATISTICS, n);
    const rule = (p: string, n: string) => new ObjectId(ObjectKind.RULE, n, p);
    const collation = (n: string) => new ObjectId(ObjectKind.COLLATION, n);
    const conversion = (n: string) => new ObjectId(ObjectKind.CONVERSION, n);
    const partition = (p: string, n: string) =>
        new ObjectId(ObjectKind.PARTITION, n, p);
    const foreignTable = (n: string) => new ObjectId(ObjectKind.FOREIGN_TABLE, n);
    const publication = (pub: string, rel: string) =>
        new ObjectId(ObjectKind.PUBLICATION, pub, rel);
    const ownership = (t: string) => new ObjectId(ObjectKind.OWNERSHIP, t);
    const privileges = (t: string) => new ObjectId(ObjectKind.PRIVILEGES, t);
    const cast = (n: string) => new ObjectId(ObjectKind.CAST, n);
    const routine = (n: string, args: string) =>
        new ObjectId(ObjectKind.ROUTINE, n + "(" + args + ")");
    const trigger = (p: string, n: string) =>
        new ObjectId(ObjectKind.TRIGGER, n, p);

    /** assert.rejects that returns the thrown error for message checks. */
    const rejects = async <E extends Error>(p: Promise<unknown>,
            ctor: new (...args: never[]) => E): Promise<E> => {
        try {
            await p;
        } catch (e) {
            assert.ok(e instanceof ctor,
                    "expected " + ctor.name + ", got " + e);
            return e;
        }
        assert.fail("expected " + ctor.name);
    };

    const anyChange = (changes: readonly PlannedChange[],
            pred: (c: PlannedChange) => boolean, label: string) =>
        assert.ok(changes.some(pred), label + ": "
                + JSON.stringify(changes.map(c => c.target + " "
                        + c.action + "/" + c.safety)));

    describe("DialectConformanceTest (C-01…C-67)", {
        skip: options?.skip ?? false,
    }, () => {
        beforeEach(async () => {
            namespace = await hooks.createNamespace();
        });

        afterEach(async () => {
            try {
                await noScratchLeft();
            } finally {
                if (namespace !== "") {
                    await hooks.dropNamespace(namespace);
                }
            }
        });

        it("C-01 empty target reconciles; second run is UP_TO_DATE", async () => {
            const script = `CREATE TABLE users (id bigint NOT NULL, name text NOT NULL);
CREATE TABLE plans (id bigint NOT NULL, owner bigint NOT NULL)`;
            const first = await reconciler(script).reconcile();
            assert.equal(first.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(table("users")));
            assert.ok(await present(new ObjectId(ObjectKind.COLUMN, "id", "users")));
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-02 matching target: UP_TO_DATE, no statements on the target",
                async () => {
            const script = "CREATE TABLE users (id bigint NOT NULL, name text)";
            await reconciler(script).reconcile();
            const tracking = new TrackingDataSource(dataSource());
            const report = await reconcilerOn(tracking, script,
                    ReconcilePolicy.defaults()).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
            assert.equal(report.plan.changes.length, 0);
            assert.equal(targetDdl(tracking).length, 0,
                    "no DDL should run on the target: " + targetDdl(tracking));
        });

        it("C-03 dropped index is restored as ADDITIVE", async (t) => {
            if (!assumeKind(t, ObjectKind.INDEX)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_name_idx ON users (name)`;
            await reconciler(script).reconcile();
            await execute("DROP INDEX " + qualified("users_name_idx"));
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(index("users", "users_name_idx")));
        });

        it("C-04 drifted index is rebuilt", async (t) => {
            if (!assumeKind(t, ObjectKind.INDEX)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_name_idx ON users (name)`;
            await reconciler(script).reconcile();
            await execute("DROP INDEX " + qualified("users_name_idx"));
            await execute("CREATE INDEX users_name_idx ON " + qualified("users")
                    + " (id)");
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.plan.changes.some(c => c.action === ChangeAction.DROP
                    && c.target.kind === ObjectKind.INDEX));
        });

        it("C-05 drifted expression index is rebuilt", async (t) => {
            if (!assumeKind(t, ObjectKind.INDEX)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_expr_idx ON users ((lower(name)))`;
            await reconciler(script).reconcile();
            await execute("DROP INDEX " + qualified("users_expr_idx"));
            await execute("CREATE INDEX users_expr_idx ON " + qualified("users")
                    + " ((upper(name)))");
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(index("users", "users_expr_idx")));
        });

        it("C-06 dropped unique constraint is restored", async (t) => {
            if (!assumeKind(t, ObjectKind.UNIQUE)) { return; }
            const script = `CREATE TABLE users (id bigint, name text,
    CONSTRAINT users_name_uk UNIQUE (name))`;
            await reconciler(script).reconcile();
            await execute("ALTER TABLE " + qualified("users")
                    + " DROP CONSTRAINT users_name_uk");
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(
                    new ObjectId(ObjectKind.UNIQUE, "users_name_uk", "users")));
        });

        it("C-07 drifted constraint is rebuilt", async (t) => {
            if (!assumeKind(t, ObjectKind.UNIQUE)) { return; }
            const script = `CREATE TABLE users (id bigint, name text,
    CONSTRAINT users_uk UNIQUE (name))`;
            await reconciler(script).reconcile();
            await execute("ALTER TABLE " + qualified("users")
                    + " DROP CONSTRAINT users_uk");
            await execute("ALTER TABLE " + qualified("users")
                    + " ADD CONSTRAINT users_uk UNIQUE (id)");
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(report.plan.changes.some(c => c.action === ChangeAction.DROP
                    && c.target.kind === ObjectKind.UNIQUE));
        });

        it("C-08 dropped foreign key is restored", async (t) => {
            if (!assumeKind(t, ObjectKind.FOREIGN_KEY)) { return; }
            const script = `CREATE TABLE users (id bigint PRIMARY KEY);
CREATE TABLE plans (id bigint PRIMARY KEY, owner bigint,
    CONSTRAINT plans_owner_fk FOREIGN KEY (owner) REFERENCES users (id))`;
            await reconciler(script).reconcile();
            await execute("ALTER TABLE " + qualified("plans")
                    + " DROP CONSTRAINT plans_owner_fk");
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(new ObjectId(ObjectKind.FOREIGN_KEY,
                    "plans_owner_fk", "plans")));
        });

        it("C-09 drifted view recreated with its dependents", async (t) => {
            if (!assumeKind(t, ObjectKind.VIEW)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users;
CREATE VIEW v_outer AS SELECT id FROM v_users`;
            await reconciler(script).reconcile();
            // CREATE OR REPLACE VIEW cannot drop columns — drift by adding one
            await execute("CREATE OR REPLACE VIEW " + qualified("v_users")
                    + " AS SELECT id, name, 1 AS extra FROM " + qualified("users"));
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const drops = report.plan.changes.filter(
                    c => c.action === ChangeAction.DROP);
            assert.ok(drops.some(c => c.target.equals(view("v_users"))));
            assert.ok(drops.some(c => c.target.equals(view("v_outer"))));
        });

        it("C-10 changed routine body rebuilds; dependents survive",
                async (t) => {
            if (!assumeKind(t, ObjectKind.ROUTINE)
                    || !assumeKind(t, ObjectKind.TRIGGER)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE FUNCTION bump() RETURNS trigger LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END $$;
CREATE TRIGGER users_bump BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION bump()`;
            await reconciler(script).reconcile();
            await execute("CREATE OR REPLACE FUNCTION " + qualified("bump")
                    + "() RETURNS trigger LANGUAGE plpgsql "
                    + "AS $$ BEGIN NEW.name := coalesce(NEW.name, '');"
                    + " RETURN NEW; END $$");
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(routine("bump", "")));
            assert.ok(await present(trigger("users", "users_bump")));
        });

        it("C-11 dropped trigger is restored", async (t) => {
            if (!assumeKind(t, ObjectKind.TRIGGER)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE FUNCTION bump() RETURNS trigger LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END $$;
CREATE TRIGGER users_bump BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION bump()`;
            await reconciler(script).reconcile();
            await execute("DROP TRIGGER users_bump ON " + qualified("users"));
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.ok(await present(trigger("users", "users_bump")));
        });

        it("C-12 foreign table shape is DESTRUCTIVE; default policy fails",
                async () => {
            const script = "CREATE TABLE users (id bigint NOT NULL)";
            await reconciler(script).reconcile();
            await execute("ALTER TABLE " + qualified("users")
                    + " ADD COLUMN extra text");
            await execute("INSERT INTO " + qualified("users")
                    + " (id, extra) VALUES (1, 'x')");
            await assert.rejects(reconciler(script).reconcile(),
                    PolicyViolationException);
            const c = await dataSource().connect();
            try {
                const rows = await c.query<{ count: string | number }>(
                        "SELECT count(*) FROM " + qualified("users"));
                assert.equal(Number(rows[0]!.count), 1,
                        "the row must be untouched");
            } finally {
                await c.close();
            }
            assert.ok(await present(new ObjectId(ObjectKind.COLUMN, "extra",
                    "users")));
        });

        it("C-13 unexpected object reported, untouched", async () => {
            const script = "CREATE TABLE users (id bigint)";
            await reconciler(script).reconcile();
            await execute("CREATE TABLE " + qualified("stray") + " (id bigint)");
            await execute("INSERT INTO " + qualified("stray")
                    + " (id) VALUES (7)");
            const report = await reconciler(script).reconcile();
            assert.ok(report.unexpected.some(d => d.id.equals(table("stray"))));
            const c = await dataSource().connect();
            try {
                const rows = await c.query<{ count: string | number }>(
                        "SELECT count(*) FROM " + qualified("stray"));
                assert.equal(Number(rows[0]!.count), 1);
            } finally {
                await c.close();
            }
        });

        it("C-14 unsupported object kind: UNSUPPORTED diff, default policy"
                + " fails", async (t) => {
            const unsupported = hooks.unsupportedScript?.();
            if (unsupported === undefined) {
                t.skip("dialect declares every kind it can detect");
                return;
            }
            await assert.rejects(reconciler(unsupported.script).reconcile(),
                    PolicyViolationException);
            const report = await reconciler(unsupported.script,
                    ReconcilePolicy.defaults().withUnsupported(Decision.REPORT))
                    .reconcile();
            assert.ok(report.unsupported.some(
                    d => d.type === DifferenceType.UNSUPPORTED));
        });

        it("C-15 syntax error: DesiredStateException, nothing left behind",
                async () => {
            await assert.rejects(
                    reconciler("THIS IS NOT VALID SQL;").reconcile(),
                    DesiredStateException);
            await noScratchLeft();
        });

        it("C-16 concurrent reconcile() calls serialise", async () => {
            const script = "CREATE TABLE users (id bigint)";
            const [a, b] = await Promise.all([reconciler(script).reconcile(),
                    reconciler(script).reconcile()]);
            assert.ok(a.outcome === ReconcileOutcome.UP_TO_DATE
                    || b.outcome === ReconcileOutcome.UP_TO_DATE,
                    "one run must observe the other's work");
        });

        it("C-17 apply() on a changed target throws StalePlanException",
                async () => {
            const script = "CREATE TABLE users (id bigint)";
            const r = reconciler(script);
            const plan = await r.plan();
            await execute("CREATE TABLE " + qualified("stray") + " (id bigint)");
            await assert.rejects(r.apply(plan), StalePlanException);
        });

        it("C-18 reportOnly applies nothing", async () => {
            const script = `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_name_idx ON users (name)`;
            await reconciler("CREATE TABLE users (id bigint, name text)")
                    .reconcile();
            const tracking = new TrackingDataSource(dataSource());
            const report = await reconcilerOn(tracking, script,
                    ReconcilePolicy.reportOnly()).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.REPORTED_ONLY);
            assert.equal(report.applied.length, 0);
            assert.notEqual(report.reported.length, 0);
            assert.equal(targetDdl(tracking).length, 0);
        });

        it("C-19 identical input produces identical plans", async () => {
            const script = `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users;
CREATE INDEX users_name_idx ON users (name)`;
            const one = (await reconciler(script).plan()).toSql();
            const two = (await reconciler(script).plan()).toSql();
            assert.equal(one, two);
        });

        it("C-20 lock timeout", async () => {
            const c = await dataSource().connect();
            try {
                await c.setAutoCommit(false);
                await dialect().acquireLock(c, namespace,
                        Duration.ofSeconds(10));
                try {
                    const r = reconcilerBuilder()
                            .desiredState(DesiredState.ofScript(
                                    "CREATE TABLE t (id bigint)"))
                            .lockTimeout(Duration.ofMillis(300)).build();
                    await assert.rejects(r.reconcile(), LockTimeoutException);
                } finally {
                    await c.rollback();
                }
            } finally {
                await c.close();
            }
        });

        it("C-21 pooled connection is clean after a run", async () => {
            await reconciler("CREATE TABLE t (id bigint)").reconcile();
            await cleanSession();
        });

        it("C-22 plan() changes nothing", async () => {
            const script = "CREATE TABLE users (id bigint, name text)";
            await reconciler("CREATE TABLE users (id bigint)").reconcile();
            const before = await snapshot();
            const plan = await reconciler(script).plan();
            assert.notEqual(plan.changes.length, 0);
            assert.equal((await snapshot()).fingerprint, before.fingerprint,
                    "plan() must not modify the target");
            await noScratchLeft();
            await cleanSession();
        });

        it("C-23 self and mutual foreign keys reconcile", async (t) => {
            if (!assumeKind(t, ObjectKind.FOREIGN_KEY)) { return; }
            const script = `CREATE TABLE selfref (id bigint PRIMARY KEY,
    parent bigint REFERENCES selfref (id));
CREATE TABLE a_mut (id bigint PRIMARY KEY, b_id bigint);
CREATE TABLE b_mut (id bigint PRIMARY KEY, a_id bigint);
ALTER TABLE a_mut ADD CONSTRAINT a_b_fk FOREIGN KEY (b_id) REFERENCES b_mut (id);
ALTER TABLE b_mut ADD CONSTRAINT b_a_fk FOREIGN KEY (a_id) REFERENCES a_mut (id)`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
        });

        it("C-24 apply() rejects a foreign plan", async () => {
            const a = reconciler("CREATE TABLE t (id bigint)");
            const plan = await a.plan();
            const b = reconcilerBuilder().namespace(namespace + "_other")
                    .desiredState(DesiredState.ofScript(
                            "CREATE TABLE t (id bigint)")).build();
            await assert.rejects(b.apply(plan), IllegalArgumentError);
            const wrongDialect = new ReconcilePlan(plan.targetNamespace,
                    "other-dialect", plan.actualFingerprint,
                    plan.desiredFingerprint, plan.differences, plan.changes);
            await assert.rejects(a.apply(wrongDialect), IllegalArgumentError);
        });

        it("C-25 unexpected dependent blocks planning", async (t) => {
            if (!assumeKind(t, ObjectKind.VIEW)) { return; }
            const script = `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users`;
            await reconciler(`CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users`).reconcile();
            // an unexpected view inside the namespace depending on v_users
            await execute("CREATE VIEW " + qualified("stray_view")
                    + " AS SELECT id FROM " + qualified("v_users"));
            const e = await rejects(reconciler(script).reconcile(),
                    PlanningException);
            assert.ok(e.message.includes("stray_view"), e.message);
            assert.ok(await present(view("stray_view")),
                    "the unexpected view must survive");
        });

        it("C-26 scratchDataSource confines scratch work", async () => {
            const report = await reconcilerBuilder()
                    .scratchDataSource(scratch())
                    .desiredState(DesiredState.ofScript(
                            "CREATE TABLE users (id bigint)"))
                    .build().reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            await noScratchLeft();
        });

        it("C-27 demoted change names its blocking object", async (t) => {
            if (!assumeKind(t, ObjectKind.VIEW)) { return; }
            await reconciler(`CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users`).reconcile();
            const script = `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users;
CREATE VIEW w_users AS SELECT id FROM v_users`;
            const p = ReconcilePolicy.defaults().withAdditive(Decision.APPLY)
                    .withRebuild(Decision.REPORT);
            const report = await reconciler(script, p).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.REPORTED_ONLY);
            assert.ok(report.reported.some(c => c.target.equals(view("w_users"))
                    && c.reason.includes("v_users")),
                    "w_users must be demoted naming v_users as blocker: "
                    + JSON.stringify(report.reported));
            assert.ok(!(await present(view("w_users"))));
        });

        it("C-28 literal target namespace in the script is rejected",
                async () => {
            const script = "CREATE TABLE " + qualified("users") + " (id bigint)";
            await assert.rejects(reconciler(script).reconcile(),
                    DesiredStateException);
            assert.ok(!(await present(table("users"))));
        });

        it("C-29 unsupported REPORT demotes dependents", async (t) => {
            const unsupported = hooks.unsupportedScript?.();
            if (unsupported === undefined) {
                t.skip("dialect declares every kind it can detect");
                return;
            }
            const script = unsupported.script
                    + "; CREATE TABLE independent_t (id bigint)";
            const report = await reconciler(script, ReconcilePolicy.defaults()
                    .withUnsupported(Decision.REPORT)).reconcile();
            assert.ok(report.unsupported.some(
                    d => d.type === DifferenceType.UNSUPPORTED));
            assert.ok(report.reported.some(c =>
                    c.target.name === unsupported.dependentName
                    || c.reason.includes(unsupported.dependentName)),
                    "the supported dependent must be demoted to REPORT");
            assert.ok(await present(table("independent_t")),
                    "independent changes are still applied");
        });

        it("C-30 drop/create order: DROP w, DROP v, CREATE v, CREATE w",
                async (t) => {
            if (!assumeKind(t, ObjectKind.VIEW)) { return; }
            await reconciler(`CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users;
CREATE VIEW w_users AS SELECT id FROM v_users`).reconcile();
            const script = `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users;
CREATE VIEW w_users AS SELECT id FROM v_users`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const changes = report.plan.changes.filter(
                    c => c.target.kind === ObjectKind.VIEW);
            assert.deepEqual(changes.map(
                    c => c.target.name + " " + c.action),
                    ["w_users DROP", "v_users DROP", "v_users CREATE",
                            "w_users CREATE"]);
        });

        it("C-31 name-scope collision fails planning", async (t) => {
            if (!assumeKind(t, ObjectKind.INDEX)) { return; }
            const idx = index("a_tbl", "shared_idx");
            const scopes = [...(dialect().nameScopes?.(idx) ?? [])];
            if (!scopes.some(s => s.startsWith("relation:"))) {
                t.skip("dialect does not declare relation-scoped index names");
                return;
            }
            await reconciler(`CREATE TABLE b_tbl (id bigint, name text);
CREATE INDEX shared_idx ON b_tbl (name)`).reconcile();
            // the desired index shares the name of the unexpected index on b_tbl
            const collision = `CREATE TABLE a_tbl (id bigint, name text);
CREATE INDEX shared_idx ON a_tbl (id)`;
            const e = await rejects(reconciler(collision).reconcile(),
                    PlanningException);
            assert.ok(e.message.includes("shared_idx"), e.message);
            assert.ok(await present(index("b_tbl", "shared_idx")));
        });

        it("C-32 identity and serial columns reconcile without cycles",
                async (t) => {
            const identity = hooks.identityColumnDdl?.();
            const serial = hooks.serialColumnDdl?.();
            if (identity === undefined && serial === undefined) {
                t.skip("dialect has no identity/serial column form");
                return;
            }
            const cols: string[] = [];
            if (identity !== undefined) { cols.push("ident " + identity); }
            if (serial !== undefined) { cols.push("ser " + serial); }
            const script = "CREATE TABLE ids (" + cols.join(", ") + ")";
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-33 column default and DROP NOT NULL alter; SET NOT NULL fails"
                + " closed", async (t) => {
            if (!assumeKind(t, ObjectKind.COLUMN)) { return; }
            if (!(hooks.columnAlterSupported?.() ?? false)) {
                t.skip("dialect renders column drift as manual changes");
                return;
            }
            await reconciler("CREATE TABLE t (id bigint,"
                    + " note text DEFAULT 'a', req text NOT NULL)").reconcile();
            await execute("INSERT INTO " + qualified("t")
                    + " VALUES (1, NULL, 'x'), (2, 'y', 'z')");
            // both safe directions apply in place
            const report = await reconciler("CREATE TABLE t (id bigint,"
                    + " note text DEFAULT 'b', req text)").reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            for (const col of [column("t", "note"), column("t", "req")]) {
                assert.ok(report.applied.some(c => c.target.equals(col)
                        && c.action === ChangeAction.ALTER),
                        "expected an applied ALTER for " + col + ": "
                        + JSON.stringify(report.applied));
            }
            assert.equal((await reconciler("CREATE TABLE t (id bigint,"
                    + " note text DEFAULT 'b', req text)").reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // SET NOT NULL fails on the NULL row — DESTRUCTIVE stays manual
            // under the default policy
            await assert.rejects(reconciler("CREATE TABLE t (id bigint,"
                    + " note text NOT NULL, req text)").reconcile(),
                    PolicyViolationException);
        });

        it("C-34 unsafe column type change stays destructive", async (t) => {
            if (!assumeKind(t, ObjectKind.COLUMN)) { return; }
            await reconciler("CREATE TABLE t (id bigint, payload text)")
                    .reconcile();
            await assert.rejects(reconciler("CREATE TABLE t (id bigint,"
                    + " payload boolean)").reconcile(), PolicyViolationException);
            assert.ok((await snapshot()).objects.get(column("t", "payload"))!
                    .definition.canonical.includes("type=text"),
                    "the column type must be unchanged after the rejected plan");
        });

        it("C-35 unsupported category override reports while the rest fails",
                async (t) => {
            const unsupported = hooks.unsupportedScript?.();
            const category = hooks.unsupportedCategory?.();
            if (unsupported === undefined || category === undefined) {
                t.skip("dialect declares every kind it can detect");
                return;
            }
            const script = unsupported.script
                    + "; CREATE TABLE independent_t (id bigint)";
            const report = await reconciler(script, ReconcilePolicy.defaults()
                    .withUnsupportedCategory(category, Decision.REPORT))
                    .reconcile();
            assert.ok(report.unsupported.some(
                    d => d.type === DifferenceType.UNSUPPORTED));
            assert.ok(await present(table("independent_t")),
                    "independent changes are still applied");
            await assert.rejects(reconciler(script, ReconcilePolicy.defaults()
                    .withUnsupportedCategory("no-match", Decision.REPORT))
                    .reconcile(), PolicyViolationException);
        });

        it("C-36 the report carries phase timings", async () => {
            const report = await reconciler("CREATE TABLE t (id bigint)")
                    .reconcile();
            const timings = report.timings;
            assert.ok(timings !== undefined);
            for (const d of [timings.lockWait, timings.readActual,
                    timings.loadDesired, timings.plan, timings.apply,
                    timings.verify]) {
                assert.ok(d !== undefined && !d.isNegative);
            }
            assert.ok(!report.elapsed.isNegative);
        });

        it("C-37 materialized view missing: ADDITIVE create, populated",
                async (t) => {
            if (!assumeKind(t, ObjectKind.MATERIALIZED_VIEW)) { return; }
            await reconciler("CREATE TABLE t (id bigint)").reconcile();
            await execute("INSERT INTO " + qualified("t")
                    + " VALUES (1), (2)");
            const script = `CREATE TABLE t (id bigint);
CREATE MATERIALIZED VIEW mv AS SELECT id FROM t`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(matview("mv"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the materialized view");
            // the view must hold its stored rows, not just exist
            assert.equal(await count("mv"), 2);
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-38 materialized view drifted: DESTRUCTIVE drop+create+refresh",
                async (t) => {
            if (!assumeKind(t, ObjectKind.MATERIALIZED_VIEW)) { return; }
            await reconciler(`CREATE TABLE t (id bigint);
CREATE MATERIALIZED VIEW mv AS SELECT id FROM t`).reconcile();
            await execute("INSERT INTO " + qualified("t") + " VALUES (1), (2)");
            await execute("REFRESH MATERIALIZED VIEW " + qualified("mv"));
            // a stored-data recreate is DESTRUCTIVE: the default policy
            // refuses it
            const widened = `CREATE TABLE t (id bigint);
CREATE MATERIALIZED VIEW mv AS SELECT id, id + 1 AS plus FROM t`;
            await assert.rejects(reconciler(widened).reconcile(),
                    PolicyViolationException);
            const report = await reconciler(widened, ReconcilePolicy.defaults()
                    .withDestructive(Decision.APPLY)).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(matview("mv"))
                    && c.safety === ChangeSafety.DESTRUCTIVE,
                    "expected DESTRUCTIVE changes for the materialized view");
            assert.equal(await count("mv"), 2);
            assert.equal((await reconciler(widened, ReconcilePolicy.defaults()
                    .withDestructive(Decision.APPLY)).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-39 enum missing: ADDITIVE create; an appended label is ALTER"
                + " TYPE ADD VALUE", async (t) => {
            if (!assumeKind(t, ObjectKind.TYPE)) { return; }
            const script = "CREATE TYPE mood AS ENUM ('sad', 'ok')";
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(type("mood"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the enum");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // a pure in-order append renders ALTER TYPE ... ADD VALUE
            const widened = "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')";
            const grown = await reconciler(widened).reconcile();
            assert.equal(grown.outcome, ReconcileOutcome.RECONCILED);
            anyChange(grown.applied, c => c.target.equals(type("mood"))
                    && c.safety === ChangeSafety.ADDITIVE
                    && c.statements.some(s => s.includes("ADD VALUE")),
                    "expected an ADDITIVE ADD VALUE change");
            assert.equal((await reconciler(widened).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-40 enum removed or reordered labels stay a manual change",
                async (t) => {
            if (!assumeKind(t, ObjectKind.TYPE)) { return; }
            await reconciler("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')")
                    .reconcile();
            // a removed label cannot be altered in place — fail closed
            await assert.rejects(reconciler(
                    "CREATE TYPE mood AS ENUM ('sad', 'ok')").reconcile(),
                    PolicyViolationException);
            // a reordered label set fails the same way
            await assert.rejects(reconciler(
                    "CREATE TYPE mood AS ENUM ('ok', 'sad', 'happy')")
                    .reconcile(), PolicyViolationException);
            // the type is untouched: the original still verifies UP_TO_DATE
            assert.equal((await reconciler(
                    "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')")
                    .reconcile()).outcome, ReconcileOutcome.UP_TO_DATE);
        });

        it("C-41 domain missing: ADDITIVE create, constraints and nullability"
                + " kept", async (t) => {
            if (!assumeKind(t, ObjectKind.DOMAIN)) { return; }
            const script = `CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1 CHECK (VALUE > 0);
CREATE TABLE t (a posint)`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(domain("posint"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the domain");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // the domain behaves like its definition says: defaults fill,
            // CHECK rejects
            await execute("INSERT INTO " + qualified("t") + " DEFAULT VALUES");
            assert.equal(await count("t"), 1);
        });

        it("C-42 domain drift on safe fields: REBUILD ALTER DOMAIN converges",
                async (t) => {
            if (!assumeKind(t, ObjectKind.DOMAIN)) { return; }
            await reconciler("CREATE DOMAIN posint AS integer NOT NULL"
                    + " DEFAULT 1 CHECK (VALUE > 0)").reconcile();
            // drop the default, drop NOT NULL, drop the check — all safe
            const report = await reconciler(
                    "CREATE DOMAIN posint AS integer").reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(domain("posint"))
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.includes("ALTER DOMAIN")),
                    "expected a REBUILD ALTER DOMAIN");
            assert.equal((await reconciler("CREATE DOMAIN posint AS integer")
                    .reconcile()).outcome, ReconcileOutcome.UP_TO_DATE);
        });

        it("C-43 domain base-type change stays a manual change", async (t) => {
            if (!assumeKind(t, ObjectKind.DOMAIN)) { return; }
            const full = "CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1"
                    + " CHECK (VALUE > 0)";
            await reconciler(full).reconcile();
            // the base type cannot alter in place — fail closed
            await assert.rejects(reconciler("CREATE DOMAIN posint AS bigint"
                    + " NOT NULL DEFAULT 1 CHECK (VALUE > 0)").reconcile(),
                    PolicyViolationException);
            assert.equal((await reconciler(full).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-44 policy missing: ADDITIVE; a changed USING is a REBUILD"
                + " drop+create", async (t) => {
            if (!assumeKind(t, ObjectKind.POLICY)) { return; }
            const script = `CREATE TABLE t (id bigint, owner text);
ALTER TABLE t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON t FOR SELECT USING (owner = current_user)`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(policy("t", "p_sel"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the policy");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // a changed USING clause recreates the policy in place
            const widened = `CREATE TABLE t (id bigint, owner text);
ALTER TABLE t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON t FOR SELECT USING (id > 0)`;
            const drifted = await reconciler(widened).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(
                    policy("t", "p_sel")) && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD recreate for the policy");
            assert.equal((await reconciler(widened).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-45 extension missing: ADDITIVE create anchored in the"
                + " namespace", async (t) => {
            if (!assumeKind(t, ObjectKind.EXTENSION)) { return; }
            const ddl = await hooks.extensionDdl?.();
            if (ddl === undefined || ddl === null) {
                t.skip("no installable extension for this dialect");
                return;
            }
            const report = await reconciler(ddl).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const extensions = report.applied.filter(
                    c => c.target.kind === ObjectKind.EXTENSION);
            assert.equal(extensions.length, 1,
                    "expected exactly one extension change: "
                    + JSON.stringify(report.applied));
            assert.equal(extensions[0]!.safety, ChangeSafety.ADDITIVE);
            assert.equal((await reconciler(ddl).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-46 comment missing/drifted: ALTER REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.COMMENT)) { return; }
            const script = `CREATE TABLE t (id bigint);
COMMENT ON TABLE t IS 'kept table'`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.kind === ObjectKind.COMMENT
                    && c.action === ChangeAction.ALTER
                    && c.safety === ChangeSafety.REBUILD,
                    "expected an ALTER/REBUILD comment change");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // a changed comment rewrites in place
            const renamed = `CREATE TABLE t (id bigint);
COMMENT ON TABLE t IS 'renamed table'`;
            const drifted = await reconciler(renamed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.kind === ObjectKind.COMMENT
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD comment change");
            assert.equal((await reconciler(renamed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-47 unexpected comment is reported, never dropped", async (t) => {
            if (!assumeKind(t, ObjectKind.COMMENT)) { return; }
            await execute("CREATE TABLE " + qualified("t") + " (id bigint)");
            await execute("COMMENT ON TABLE " + qualified("t")
                    + " IS 'ambient'");
            const report = await reconciler("CREATE TABLE t (id bigint)")
                    .reconcile();
            assert.equal(report.outcome, ReconcileOutcome.UP_TO_DATE);
            assert.ok(report.unexpected.some(
                    d => d.id.kind === ObjectKind.COMMENT),
                    "expected the ambient comment reported: "
                    + JSON.stringify(report.unexpected));
            // and it is still there afterwards
            const c = await dataSource().connect();
            try {
                const rows = await c.query<{ obj_description: string }>(
                        "SELECT obj_description('" + qualified("t")
                        + "'::regclass)");
                assert.equal(rows[0]!.obj_description, "ambient",
                        "the unexpected comment must survive reconcile");
            } finally {
                await c.close();
            }
        });

        it("C-48 interlocked new kinds reconcile in dependency order",
                async (t) => {
            if (!assumeKind(t, ObjectKind.DOMAIN)
                    || !assumeKind(t, ObjectKind.MATERIALIZED_VIEW)
                    || !assumeKind(t, ObjectKind.POLICY)
                    || !assumeKind(t, ObjectKind.COMMENT)) { return; }
            const script = `CREATE DOMAIN posint AS integer CHECK (VALUE > 0);
CREATE TABLE t (id posint, note text);
ALTER TABLE t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON t FOR SELECT USING (id > 0);
COMMENT ON TABLE t IS 'governed table';
CREATE MATERIALIZED VIEW mv AS SELECT id, note FROM t;
CREATE INDEX mv_note ON mv (note)`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            const order = report.applied.map(c => c.target.kind);
            const domainAt = order.indexOf(ObjectKind.DOMAIN);
            const tableAt = order.indexOf(ObjectKind.TABLE);
            const policyAt = order.indexOf(ObjectKind.POLICY);
            const matviewAt = order.indexOf(ObjectKind.MATERIALIZED_VIEW);
            const indexAt = order.indexOf(ObjectKind.INDEX);
            const commentAt = order.indexOf(ObjectKind.COMMENT);
            assert.ok(domainAt >= 0 && tableAt > domainAt,
                    "domain must precede the table that uses it: " + order);
            assert.ok(policyAt > tableAt && matviewAt > tableAt
                    && indexAt > matviewAt && commentAt > tableAt,
                    "dependents must follow their prerequisite: " + order);
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-49 exclusion constraint missing: ADDITIVE; drifted: REBUILD"
                + " drop+create", async (t) => {
            if (!assumeKind(t, ObjectKind.EXCLUSION)) { return; }
            const script = `CREATE TABLE t (id bigint, span int4range,
    CONSTRAINT no_overlap EXCLUDE USING gist (span WITH &&))`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(
                    exclusion("t", "no_overlap"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the exclusion constraint");
            // the backing index is part of the constraint, not a separate
            // INDEX
            assert.ok(!report.applied.some(
                    c => c.target.kind === ObjectKind.INDEX),
                    "the exclusion's index must not surface as a separate"
                    + " change: " + JSON.stringify(report.applied));
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // a changed predicate drifts the constraint: drop + create
            const widened = `CREATE TABLE t (id bigint, span int4range,
    CONSTRAINT no_overlap EXCLUDE USING gist (span WITH &&) WHERE (id > 0))`;
            const drifted = await reconciler(widened).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(
                    exclusion("t", "no_overlap"))
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD recreate for the exclusion");
            assert.equal((await reconciler(widened).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-50 statistics missing: ADDITIVE; drifted: REBUILD drop+create",
                async (t) => {
            if (!assumeKind(t, ObjectKind.STATISTICS)) { return; }
            const script = `CREATE TABLE t (a int, b int);
CREATE STATISTICS st ON a, b FROM t`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(statistics("st"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the statistics");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const widened = `CREATE TABLE t (a int, b int);
CREATE STATISTICS st (mcv) ON a, b FROM t`;
            const drifted = await reconciler(widened).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(statistics("st"))
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD recreate for the statistics");
            assert.equal((await reconciler(widened).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-51 rule missing: ADDITIVE; drifted: REBUILD drop+create",
                async (t) => {
            if (!assumeKind(t, ObjectKind.RULE)) { return; }
            const script = `CREATE TABLE t (id bigint);
CREATE RULE rl AS ON INSERT TO t DO ALSO NOTHING`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(rule("t", "rl"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the rule");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const changed = `CREATE TABLE t (id bigint);
CREATE RULE rl AS ON INSERT TO t DO INSTEAD NOTHING`;
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(rule("t", "rl"))
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD recreate for the rule");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-52 collation missing: ADDITIVE; drifted: REBUILD drop+create",
                async (t) => {
            if (!assumeKind(t, ObjectKind.COLLATION)) { return; }
            const script = "CREATE COLLATION coll (provider = libc, locale = 'C')";
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(collation("coll"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the collation");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const changed = "CREATE COLLATION coll (provider = libc,"
                    + " locale = 'POSIX')";
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(collation("coll"))
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD recreate for the collation");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-53 conversion missing: ADDITIVE; drifted: REBUILD drop+create",
                async (t) => {
            if (!assumeKind(t, ObjectKind.CONVERSION)) { return; }
            const script = "CREATE CONVERSION cv FOR 'LATIN1' TO 'UTF8'"
                    + " FROM iso8859_1_to_utf8";
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(conversion("cv"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the conversion");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const changed = "CREATE CONVERSION cv FOR 'UTF8' TO 'LATIN1'"
                    + " FROM utf8_to_iso8859_1";
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(conversion("cv"))
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a REBUILD recreate for the conversion");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-54 partitioned parent and children create ADDITIVE; bound"
                + " drift: DESTRUCTIVE recreate", async (t) => {
            if (!assumeKind(t, ObjectKind.PARTITION)) { return; }
            const script = `CREATE TABLE pt (k int) PARTITION BY RANGE (k);
CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (100)`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(table("pt"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the parent");
            anyChange(report.applied, c => c.target.equals(
                    partition("pt", "pc"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the partition");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // a moved bound re-homes rows: DESTRUCTIVE detach+attach
            // recreate, refused by the default policy
            const changed = `CREATE TABLE pt (k int) PARTITION BY RANGE (k);
CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (50)`;
            await assert.rejects(reconciler(changed).reconcile(),
                    PolicyViolationException);
            const drifted = await reconciler(changed, ReconcilePolicy.defaults()
                    .withDestructive(Decision.APPLY)).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(
                    partition("pt", "pc"))
                    && c.safety === ChangeSafety.DESTRUCTIVE,
                    "expected a DESTRUCTIVE recreate for the partition");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-55 foreign table create: ADDITIVE; options drift: drop+create"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.FOREIGN_TABLE)) { return; }
            const server = await hooks.foreignServerDdl?.();
            if (server === undefined || server === null) {
                t.skip("dialect provides no foreign server hook");
                return;
            }
            await execute(server);
            try {
                const script = "CREATE FOREIGN TABLE ft (a int) SERVER tck_srv"
                        + " OPTIONS (fetch_size '100')";
                const report = await reconciler(script).reconcile();
                assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
                anyChange(report.applied, c => c.target.equals(
                        foreignTable("ft"))
                        && c.safety === ChangeSafety.ADDITIVE,
                        "expected an ADDITIVE create for the foreign table");
                assert.equal((await reconciler(script).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
                // an options change carries no local data — plain drop+create
                const changed = "CREATE FOREIGN TABLE ft (a int) SERVER"
                        + " tck_srv OPTIONS (fetch_size '200')";
                const drifted = await reconciler(changed).reconcile();
                assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
                anyChange(drifted.applied, c => c.target.equals(
                        foreignTable("ft"))
                        && c.safety === ChangeSafety.REBUILD,
                        "expected a REBUILD recreate for the foreign table");
                assert.equal((await reconciler(changed).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
            } finally {
                const teardown = await hooks.foreignServerTeardownDdl?.();
                if (teardown !== undefined && teardown !== null) {
                    await execute(teardown);
                }
            }
        });

        it("C-56 publication binding add: ADDITIVE; filter drift: drop+add"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.PUBLICATION)) { return; }
            const pub = await hooks.publicationDdl?.();
            if (pub === undefined || pub === null) {
                t.skip("dialect provides no publication hook");
                return;
            }
            await execute(pub);
            try {
                const script = `CREATE TABLE t (a int);
ALTER PUBLICATION tck_pub ADD TABLE t`;
                const report = await reconciler(script).reconcile();
                assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
                anyChange(report.applied, c => c.target.equals(
                        publication("tck_pub", "t"))
                        && c.safety === ChangeSafety.ADDITIVE,
                        "expected an ADDITIVE binding for the table");
                assert.equal((await reconciler(script).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
                // a row-filter change drops and re-adds the binding
                const changed = `CREATE TABLE t (a int);
ALTER PUBLICATION tck_pub ADD TABLE t WHERE (a > 0)`;
                const drifted = await reconciler(changed).reconcile();
                assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
                anyChange(drifted.applied, c => c.target.equals(
                        publication("tck_pub", "t"))
                        && c.safety === ChangeSafety.REBUILD,
                        "expected a REBUILD recreate for the binding");
                assert.equal((await reconciler(changed).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
            } finally {
                const teardown = await hooks.publicationTeardownDdl?.();
                if (teardown !== undefined && teardown !== null) {
                    await execute(teardown);
                }
            }
        });

        it("C-57 ownership assign: REBUILD; owner drift: in-place REBUILD",
                async (t) => {
            if (!assumeKind(t, ObjectKind.OWNERSHIP)) { return; }
            const roles = await hooks.ownershipRoleDdl?.();
            if (roles === undefined || roles === null) {
                t.skip("dialect provides no ownership-role hook");
                return;
            }
            await execute(roles);
            try {
                const script = `CREATE TABLE t (a int);
ALTER TABLE t OWNER TO tck_owner`;
                const report = await reconciler(script).reconcile();
                assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
                anyChange(report.applied, c => c.target.equals(ownership("t"))
                        && c.safety === ChangeSafety.REBUILD,
                        "expected a REBUILD owner assignment for t");
                assert.equal((await reconciler(script).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
                // a different desired role re-assigns in place — REBUILD
                const changed = `CREATE TABLE t (a int);
ALTER TABLE t OWNER TO tck_owner2`;
                const drifted = await reconciler(changed).reconcile();
                assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
                anyChange(drifted.applied, c => c.target.equals(ownership("t"))
                        && c.safety === ChangeSafety.REBUILD,
                        "expected a REBUILD owner change for t");
                assert.equal((await reconciler(changed).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
            } finally {
                const teardown = await hooks.ownershipRoleTeardownDdl?.();
                if (teardown !== undefined && teardown !== null) {
                    await execute(teardown);
                }
            }
        });

        it("C-58 privileges granted: REBUILD; grant drift: GRANT/REVOKE"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.PRIVILEGES)) { return; }
            const roles = await hooks.ownershipRoleDdl?.();
            if (roles === undefined || roles === null) {
                t.skip("dialect provides no grantee-role hook");
                return;
            }
            await execute(roles);
            try {
                const script = `CREATE TABLE t (a int);
GRANT SELECT ON t TO tck_owner`;
                const report = await reconciler(script).reconcile();
                assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
                anyChange(report.applied, c => c.target.equals(privileges("t"))
                        && c.safety === ChangeSafety.REBUILD
                        && c.statements.some(s => s.startsWith("GRANT SELECT")),
                        "expected a REBUILD GRANT on t");
                assert.equal((await reconciler(script).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
                // a different desired grant converges the acl — revoke the
                // old, grant the new, in one REBUILD change
                const changed = `CREATE TABLE t (a int);
GRANT INSERT ON t TO tck_owner2`;
                const drifted = await reconciler(changed).reconcile();
                assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
                anyChange(drifted.applied, c => c.target.equals(privileges("t"))
                        && c.safety === ChangeSafety.REBUILD
                        && c.statements.some(s => s.startsWith("REVOKE"))
                        && c.statements.some(s => s.startsWith("GRANT INSERT")),
                        "expected a REBUILD GRANT+REVOKE on t");
                assert.equal((await reconciler(changed).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
            } finally {
                const teardown = await hooks.ownershipRoleTeardownDdl?.();
                if (teardown !== undefined && teardown !== null) {
                    await execute(teardown);
                }
            }
        });

        it("C-59 cast missing: ADDITIVE; cast context drift: drop+create"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.CAST)) { return; }
            const script = `CREATE DOMAIN d AS integer CHECK (VALUE > 0);
CREATE FUNCTION dtotext(d) RETURNS text
    LANGUAGE sql IMMUTABLE RETURN $1::text;
CREATE CAST (d AS text) WITH FUNCTION dtotext(d)`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(cast("d->text"))
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE create for the cast");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // a context change drops and recreates the cast
            const changed = `CREATE DOMAIN d AS integer CHECK (VALUE > 0);
CREATE FUNCTION dtotext(d) RETURNS text
    LANGUAGE sql IMMUTABLE RETURN $1::text;
CREATE CAST (d AS text) WITH FUNCTION dtotext(d) AS IMPLICIT`;
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(cast("d->text"))
                    && c.safety === ChangeSafety.REBUILD,
                    "expected a drop+create REBUILD for the cast");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-60 composite type missing: ADDITIVE; attribute drift:"
                + " drop+create REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.TYPE)) { return; }
            const script = "CREATE TYPE cmp AS (a integer, b text)";
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(type("cmp"))
                    && c.safety === ChangeSafety.ADDITIVE
                    && c.statements.some(s => s.startsWith("CREATE TYPE")),
                    "expected an ADDITIVE CREATE TYPE for the composite");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // attribute drift drops and recreates the type
            const changed = "CREATE TYPE cmp AS (a integer, b text, c numeric)";
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(type("cmp"))
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.startsWith("DROP TYPE")),
                    "expected a drop+create REBUILD for the composite");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-61 range type missing: ADDITIVE; pg_range drift: drop+create"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.TYPE)) { return; }
            // the generated family — multirange, constructor routines and the
            // range→multirange cast — folds into the range: only the TYPE
            // object is created, and a routine calling the folded constructor
            // still orders after it
            const script = `CREATE TYPE r AS RANGE (subtype = integer);
CREATE FUNCTION mk(x r) RETURNS r_multirange
    LANGUAGE sql IMMUTABLE AS 'SELECT r_multirange(x)'`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.equals(type("r"))
                    && c.safety === ChangeSafety.ADDITIVE
                    && c.statements.some(s => s.includes("AS RANGE")),
                    "expected an ADDITIVE CREATE TYPE ... AS RANGE");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // the dependent routine would block DROP TYPE — the drift phase
            // recreates a bare range
            await execute("DROP FUNCTION " + qualified("mk") + "("
                    + qualified("r") + ")");
            const changed = 'CREATE TYPE r AS RANGE (subtype = text,'
                    + ' collation = "C")';
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.equals(type("r"))
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.startsWith("DROP TYPE")),
                    "expected a drop+create REBUILD for the range");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-62 aggregate missing: ADDITIVE; option drift: drop+create"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.AGGREGATE)) { return; }
            const script = `CREATE FUNCTION tfn(state int, x int)
    RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT state + x';
CREATE AGGREGATE a1(int) (SFUNC = tfn, STYPE = int, INITCOND = '0')`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.kind === ObjectKind.AGGREGATE
                    && c.target.name === "a1(integer)"
                    && c.safety === ChangeSafety.ADDITIVE
                    && c.statements.some(s => s.startsWith("CREATE AGGREGATE")),
                    "expected an ADDITIVE CREATE AGGREGATE");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const changed = `CREATE FUNCTION tfn(state int, x int)
    RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT state + x';
CREATE AGGREGATE a1(int) (SFUNC = tfn, STYPE = int)`;
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.kind === ObjectKind.AGGREGATE
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.startsWith("DROP AGGREGATE")),
                    "expected a drop+create REBUILD for the aggregate");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-63 column privilege granted: REBUILD; column grant drift:"
                + " GRANT/REVOKE REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.PRIVILEGES)) { return; }
            const roles = await hooks.ownershipRoleDdl?.();
            if (roles === undefined || roles === null) {
                t.skip("dialect provides no grantee-role hook");
                return;
            }
            await execute(roles);
            const columnGrant = new ObjectId(ObjectKind.PRIVILEGES, "a", "t");
            try {
                const script = `CREATE TABLE t (a int, b int);
GRANT SELECT (a) ON t TO tck_owner`;
                const report = await reconciler(script).reconcile();
                assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
                anyChange(report.applied, c => c.target.equals(columnGrant)
                        && c.safety === ChangeSafety.REBUILD
                        && c.statements.some(s => s.includes("SELECT")),
                        "expected a REBUILD column GRANT on t.a");
                assert.equal((await reconciler(script).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
                const changed = `CREATE TABLE t (a int, b int);
GRANT UPDATE (a) ON t TO tck_owner`;
                const drifted = await reconciler(changed).reconcile();
                assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
                anyChange(drifted.applied, c => c.target.equals(columnGrant)
                        && c.safety === ChangeSafety.REBUILD
                        && c.statements.some(s => s.startsWith("REVOKE"))
                        && c.statements.some(s => s.includes("UPDATE")),
                        "expected a REBUILD column GRANT+REVOKE on t.a");
                assert.equal((await reconciler(changed).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
            } finally {
                const teardown = await hooks.ownershipRoleTeardownDdl?.();
                if (teardown !== undefined && teardown !== null) {
                    await execute(teardown);
                }
            }
        });

        it("C-64 default privileges granted: REBUILD; drift: GRANT/REVOKE"
                + " REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.DEFAULT_PRIVILEGES)) { return; }
            const roles = await hooks.ownershipRoleDdl?.();
            if (roles === undefined || roles === null) {
                t.skip("dialect provides no grantee-role hook");
                return;
            }
            await execute(roles);
            const defPrivs = new ObjectId(ObjectKind.DEFAULT_PRIVILEGES,
                    "TABLES for tck_owner");
            try {
                const script = `ALTER DEFAULT PRIVILEGES FOR ROLE tck_owner
    IN SCHEMA \${namespace} GRANT SELECT ON TABLES TO tck_owner2`;
                const report = await reconciler(script).reconcile();
                assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
                anyChange(report.applied, c => c.target.equals(defPrivs)
                        && c.safety === ChangeSafety.REBUILD
                        && c.statements.some(s => s.startsWith(
                                "ALTER DEFAULT PRIVILEGES")),
                        "expected a REBUILD default-privileges GRANT");
                assert.equal((await reconciler(script).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
                const changed = `ALTER DEFAULT PRIVILEGES FOR ROLE tck_owner
    IN SCHEMA \${namespace} GRANT UPDATE ON TABLES TO tck_owner2`;
                const drifted = await reconciler(changed).reconcile();
                assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
                anyChange(drifted.applied, c => c.target.equals(defPrivs)
                        && c.safety === ChangeSafety.REBUILD
                        && c.statements.some(s => s.includes("REVOKE"))
                        && c.statements.some(s => s.includes("UPDATE")),
                        "expected a REBUILD GRANT+REVOKE on default"
                        + " privileges");
                assert.equal((await reconciler(changed).reconcile()).outcome,
                        ReconcileOutcome.UP_TO_DATE);
            } finally {
                const teardown = await hooks.ownershipRoleTeardownDdl?.();
                if (teardown !== undefined && teardown !== null) {
                    await execute(teardown);
                }
            }
        });

        it("C-65 window function missing: ADDITIVE; body drift: in-place"
                + " CREATE OR REPLACE REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.ROUTINE)) { return; }
            const script = `CREATE FUNCTION wf(x int) RETURNS int
    LANGUAGE sql WINDOW AS 'SELECT x'`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.kind === ObjectKind.ROUTINE
                    && c.target.name === "wf(integer)"
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE window function");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            const changed = `CREATE FUNCTION wf(x int) RETURNS int
    LANGUAGE sql WINDOW AS 'SELECT x + 1'`;
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.kind === ObjectKind.ROUTINE
                    && c.target.name === "wf(integer)"
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.startsWith(
                            "CREATE OR REPLACE FUNCTION")),
                    "expected an in-place replace for the window function");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-66 text-search dictionary/config with mapping: ADDITIVE;"
                + " option drift: DROP+CREATE REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.TEXT_SEARCH_DICTIONARY)
                    || !assumeKind(t, ObjectKind.TEXT_SEARCH_CONFIGURATION)) {
                return;
            }
            const script = `CREATE TEXT SEARCH DICTIONARY mydict
    (template = pg_catalog.simple, stopwords = 'english');
CREATE TEXT SEARCH CONFIGURATION mycfg (parser = pg_catalog.default);
ALTER TEXT SEARCH CONFIGURATION mycfg
    ADD MAPPING FOR asciiword WITH mydict`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.kind
                    === ObjectKind.TEXT_SEARCH_DICTIONARY
                    && c.target.name === "mydict"
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE dictionary");
            anyChange(report.applied, c => c.target.kind
                    === ObjectKind.TEXT_SEARCH_CONFIGURATION
                    && c.target.name === "mycfg"
                    && c.safety === ChangeSafety.ADDITIVE
                    && c.statements.some(s => s.includes("ADD MAPPING")),
                    "expected the mapping fragment on the configuration");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // an option drift recreates — no OR REPLACE form exists
            const changed = `CREATE TEXT SEARCH DICTIONARY mydict
    (template = pg_catalog.simple, stopwords = 'german');
CREATE TEXT SEARCH CONFIGURATION mycfg (parser = pg_catalog.default);
ALTER TEXT SEARCH CONFIGURATION mycfg
    ADD MAPPING FOR asciiword WITH mydict`;
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.kind
                    === ObjectKind.TEXT_SEARCH_DICTIONARY
                    && c.target.name === "mydict"
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.startsWith(
                            "DROP TEXT SEARCH DICTIONARY")),
                    "expected a drop+create for the drifted dictionary");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });

        it("C-67 operator, family and class missing: ADDITIVE; class option"
                + " drift: DROP+CREATE REBUILD", async (t) => {
            if (!assumeKind(t, ObjectKind.OPERATOR)
                    || !assumeKind(t, ObjectKind.OPERATOR_CLASS)
                    || !assumeKind(t, ObjectKind.OPERATOR_FAMILY)) { return; }
            const script = `CREATE OPERATOR ===
    (leftarg = int, rightarg = int, function = int4eq,
     restrict = eqsel, join = eqjoinsel, hashes, merges);
CREATE OPERATOR FAMILY fam USING btree;
CREATE OPERATOR CLASS oc FOR TYPE int USING btree FAMILY fam
    AS OPERATOR 1 ===`;
            const report = await reconciler(script).reconcile();
            assert.equal(report.outcome, ReconcileOutcome.RECONCILED);
            anyChange(report.applied, c => c.target.kind === ObjectKind.OPERATOR
                    && c.target.name === "===(integer,integer)"
                    && c.safety === ChangeSafety.ADDITIVE,
                    "expected an ADDITIVE operator");
            anyChange(report.applied, c => c.target.kind
                    === ObjectKind.OPERATOR_CLASS
                    && c.safety === ChangeSafety.ADDITIVE
                    && c.statements.some(s => s.includes("OPERATOR 1")),
                    "expected member specs on the class create");
            assert.equal((await reconciler(script).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
            // an option drift recreates — no OR REPLACE form exists
            const changed = `CREATE OPERATOR ===
    (leftarg = int, rightarg = int, function = int4eq,
     restrict = eqsel, join = eqjoinsel, hashes, merges);
CREATE OPERATOR FAMILY fam USING btree;
CREATE OPERATOR CLASS oc FOR TYPE int USING btree FAMILY fam
    AS OPERATOR 1 ===, FUNCTION 1 btint4cmp(int,int)`;
            const drifted = await reconciler(changed).reconcile();
            assert.equal(drifted.outcome, ReconcileOutcome.RECONCILED);
            anyChange(drifted.applied, c => c.target.kind
                    === ObjectKind.OPERATOR_CLASS
                    && c.safety === ChangeSafety.REBUILD
                    && c.statements.some(s => s.startsWith(
                            "DROP OPERATOR CLASS")),
                    "expected a drop+create for the drifted class");
            assert.equal((await reconciler(changed).reconcile()).outcome,
                    ReconcileOutcome.UP_TO_DATE);
        });
    });
}
