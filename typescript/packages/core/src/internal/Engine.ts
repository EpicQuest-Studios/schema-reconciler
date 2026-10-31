import { ApplyException } from "../ApplyException.ts";
import { ConfigurationException } from "../ConfigurationException.ts";
import { Decision } from "../Decision.ts";
import type { DesiredState } from "../DesiredState.ts";
import { DesiredStateException } from "../DesiredStateException.ts";
import { Duration } from "../Duration.ts";
import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import { LockTimeoutException } from "../LockTimeoutException.ts";
import { PlanningException } from "../PlanningException.ts";
import type { ReconcileListener } from "../ReconcileListener.ts";
import { ReconcileOutcome } from "../ReconcileOutcome.ts";
import type { ReconcilePolicy } from "../ReconcilePolicy.ts";
import { ReconcileReport } from "../ReconcileReport.ts";
import { RunTimings } from "../RunTimings.ts";
import { SchemaReconcilerException, messageOf } from "../SchemaReconcilerException.ts";
import { StalePlanException } from "../StalePlanException.ts";
import { TimedPlan } from "../TimedPlan.ts";
import { VerificationException } from "../VerificationException.ts";
import type { Definition } from "../model/Definition.ts";
import { Difference } from "../model/Difference.ts";
import { DifferenceType } from "../model/DifferenceType.ts";
import type { ObjectId } from "../model/ObjectId.ts";
import { ObjectMap } from "../model/ObjectMap.ts";
import { PlannedChange } from "../model/PlannedChange.ts";
import type { ReconcilePlan } from "../model/ReconcilePlan.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import type { Dialect } from "../spi/Dialect.ts";
import type { SqlConnection, SqlDataSource } from "../spi/Sql.ts";
import { DialectResolver } from "./DialectResolver.ts";
import { Differ } from "./Differ.ts";
import { DumpRenderer } from "./DumpRenderer.ts";
import { ObjectFilter } from "./ObjectFilter.ts";
import { PlanBuilder, type Built } from "./PlanBuilder.ts";
import { PolicyEvaluator, type Evaluation } from "./PolicyEvaluator.ts";
import { ScriptProcessor } from "./ScriptProcessor.ts";

/** Metadata retained per issued plan for {@code apply()}. */
interface PlanMeta {
    readonly desired: SchemaSnapshot;
    readonly coveredByParent: ObjectMap<ObjectMap<Definition>>;
}

interface RunOutcome {
    readonly outcome: ReconcileOutcome;
    readonly applied: readonly PlannedChange[];
    readonly reported: readonly PlannedChange[];
}

function warn(message: string): void {
    console.warn(message);
}

/**
 * The shared step machine of spec 5.1 behind {@code plan()}, {@code reconcile()}
 * and {@code apply(plan)}.
 */
export class Engine {

    private readonly resolver: DialectResolver;
    /**
     * Identity-keyed, GC-managed plan metadata — Java needs a
     * {@code WeakReference} + {@code ReferenceQueue} pair for this;
     * {@link WeakMap} does exactly that in one structure.
     */
    private readonly planMeta = new WeakMap<ReconcilePlan, PlanMeta>();

    private readonly dataSource: SqlDataSource;
    private readonly scratchDataSource: SqlDataSource | undefined;
    private readonly namespace: string;
    private readonly desiredState: DesiredState | undefined;
    private readonly policy: ReconcilePolicy;
    private readonly lockTimeout: Duration;
    private readonly statementTimeout: Duration | undefined;
    private readonly listener: ReconcileListener;
    private readonly objectFilter: (id: ObjectId) => boolean;

    constructor(dataSource: SqlDataSource,
                scratchDataSource: SqlDataSource | undefined,
                namespace: string,
                desiredState: DesiredState | undefined,
                dialect: Dialect | undefined,
                policy: ReconcilePolicy,
                lockTimeout: Duration,
                statementTimeout: Duration | undefined,
                listener: ReconcileListener,
                objectFilter: (id: ObjectId) => boolean) {
        this.dataSource = dataSource;
        this.scratchDataSource = scratchDataSource;
        this.namespace = namespace;
        this.desiredState = desiredState;
        this.policy = policy;
        this.lockTimeout = lockTimeout;
        this.statementTimeout = statementTimeout;
        this.listener = listener;
        this.objectFilter = objectFilter;
        this.resolver = new DialectResolver(dialect);
    }

    /** Spec 5.1 plan(): steps 1–5, returning the plan without evaluating policy or applying. */
    async plan(): Promise<ReconcilePlan> {
        this.requireDesiredState("plan");
        return (await this.planTimed()).plan;
    }

    /** {@link plan} with the phase timings of the planning run. */
    async planTimed(): Promise<TimedPlan> {
        this.requireDesiredState("planTimed");
        const clock = new PhaseClock();
        const session = await this.open();
        try {
            let t = clock.now();
            try {
                await session.lock();
            } finally {
                clock.lockWait = clock.since(t);
            }
            t = clock.now();
            let actual: SchemaSnapshot;
            try {
                actual = await session.readActual();
            } finally {
                clock.readActual = clock.since(t);
            }
            t = clock.now();
            let desired: SchemaSnapshot;
            try {
                desired = await this.loadDesired(session);
            } finally {
                clock.loadDesired = clock.since(t);
            }
            t = clock.now();
            let built: Built;
            try {
                built = this.buildPlan(session, actual, desired);
            } finally {
                clock.plan = clock.since(t);
            }
            // nothing is applied by plan(), so a listener failure is a planning failure
            try {
                await this.listener.planned?.(built.plan);
            } catch (e) {
                throw new PlanningException(
                        "namespace '" + this.namespace + "': listener aborted planning", e);
            }
            this.planMeta.set(built.plan, { desired, coveredByParent: built.coveredByParent });
            return new TimedPlan(built.plan, clock.snapshot());
        } catch (e) {
            if (e instanceof SchemaReconcilerException) {
                throw e.withTimings(clock.snapshot());
            }
            throw e;
        } finally {
            await session.close();
        }
    }

    /** Spec 5.1 reconcile(): the full sequence. */
    async reconcile(): Promise<ReconcileReport> {
        this.requireDesiredState("reconcile");
        const start = performance.now();
        const clock = new PhaseClock();
        const session = await this.open();
        try {
            let t = clock.now();
            try {
                await session.lock();
            } finally {
                clock.lockWait = clock.since(t);
            }
            t = clock.now();
            let actual: SchemaSnapshot;
            try {
                actual = await session.readActual();
            } finally {
                clock.readActual = clock.since(t);
            }
            t = clock.now();
            let desired: SchemaSnapshot;
            try {
                desired = await this.loadDesired(session);
            } finally {
                clock.loadDesired = clock.since(t);
            }
            t = clock.now();
            let built: Built;
            try {
                built = this.buildPlan(session, actual, desired);
            } finally {
                clock.plan = clock.since(t);
            }
            await this.notifyPlanned(built.plan, []);
            const evaluation =
                    new PolicyEvaluator(session.dialect, this.policy)
                            .evaluate(built.plan, actual, desired);
            const outcome = await this.applyAndVerify(session, built.plan, evaluation, desired,
                    built.coveredByParent, clock);
            await session.commit();
            const unexpected = this.unexpected(built.plan);
            const unsupported = this.unsupported(built.plan);
            this.logUnexpectedAndUnsupported(unexpected, unsupported);
            return new ReconcileReport(outcome.outcome, built.plan, outcome.applied,
                    outcome.reported, unexpected, unsupported,
                    Duration.ofMillis(performance.now() - start), clock.snapshot());
        } catch (e) {
            if (e instanceof SchemaReconcilerException) {
                throw e.withTimings(clock.snapshot());
            }
            throw e;
        } finally {
            await session.close();
        }
    }

    /** Spec 5.1 apply(): the same sequence, driven by a previously issued plan. */
    async apply(plan: ReconcilePlan): Promise<ReconcileReport> {
        this.requireDesiredState("apply");
        const start = performance.now();
        const clock = new PhaseClock();
        // reject a foreign plan before touching the database: a namespace or
        // dialect mismatch needs no connection to diagnose (5.7)
        const fixed = this.resolver.peek();
        if (this.namespace !== plan.targetNamespace
                || (fixed !== undefined && fixed.id() !== plan.dialectId)) {
            throw new IllegalArgumentError("plan targets namespace '"
                    + plan.targetNamespace + "' on dialect '" + plan.dialectId
                    + "', this reconciler is bound to '" + this.namespace
                    + (fixed !== undefined ? "' on '" + fixed.id() + "'" : "'"));
        }
        const session = await this.open();
        try {
            let t = clock.now();
            try {
                await session.lock();
            } finally {
                clock.lockWait = clock.since(t);
            }
            t = clock.now();
            let actual: SchemaSnapshot;
            try {
                actual = await session.readActual();
            } finally {
                clock.readActual = clock.since(t);
            }
            this.verifyPlan(plan, session.dialect, actual);
            t = clock.now();
            let desired: SchemaSnapshot;
            try {
                desired = await this.desiredFor(plan, session);
            } finally {
                clock.loadDesired = clock.since(t);
            }
            await this.notifyPlanned(plan, []);
            const evaluation =
                    new PolicyEvaluator(session.dialect, this.policy)
                            .evaluate(plan, actual, desired);
            const meta = this.planMeta.get(plan);
            const covered = meta !== undefined
                    ? meta.coveredByParent
                    : PlanBuilder.coverageOf(
                            session.dialect, this.namespace, plan.differences, desired,
                            plan.changes);
            const outcome = await this.applyAndVerify(session, plan, evaluation, desired,
                    covered, clock);
            await session.commit();
            const unexpected = this.unexpected(plan);
            const unsupported = this.unsupported(plan);
            this.logUnexpectedAndUnsupported(unexpected, unsupported);
            return new ReconcileReport(outcome.outcome, plan, outcome.applied,
                    outcome.reported, unexpected, unsupported,
                    Duration.ofMillis(performance.now() - start), clock.snapshot());
        } catch (e) {
            if (e instanceof SchemaReconcilerException) {
                throw e.withTimings(clock.snapshot());
            }
            throw e;
        } finally {
            await session.close();
        }
    }

    /**
     * Spec 3.1 {@code dump()}: steps 1 to 3 of 5.1 — connect, lock, read the
     * actual snapshot — rendered as a namespace-neutral desired-state script.
     * Requires no {@code desiredState}; the target is never mutated.
     */
    async dump(): Promise<string> {
        const session = await this.open(false);
        try {
            await session.lock();
            return DumpRenderer.render(session.dialect, this.namespace,
                    await session.readActual());
        } finally {
            await session.close();
        }
    }

    private requireDesiredState(method: string): void {
        if (this.desiredState === undefined) {
            throw new ConfigurationException(
                    "desiredState is required for " + method + "()");
        }
    }

    private async applyAndVerify(session: Session, plan: ReconcilePlan,
                                 evaluation: Evaluation,
                                 desired: SchemaSnapshot,
                                 coveredByParent: ObjectMap<ObjectMap<Definition>>,
                                 clock: PhaseClock): Promise<RunOutcome> {
        const applied: PlannedChange[] = [];
        const reported: PlannedChange[] = [];
        await session.bindNamespace();
        const applyStart = clock.now();
        try {
            for (let change of plan.changes) {
                if (evaluation.decisions.get(change) === Decision.APPLY) {
                    await this.notifyApplying(change, applied);
                    const t0 = performance.now();
                    for (const sql of change.statements) {
                        try {
                            await session.execute(sql);
                        } catch (e) {
                            throw new ApplyException("namespace '" + this.namespace
                                    + "': change " + change.order + " failed: " + messageOf(e),
                                    [...applied], change, e);
                        }
                    }
                    applied.push(change);
                    await this.notifyApplied(change,
                            Duration.ofMillis(performance.now() - t0), applied);
                } else {
                    const blocker = evaluation.demotedBy.get(change);
                    if (blocker !== undefined) {
                        change = new PlannedChange(change.order, change.target, change.action,
                                change.safety, change.statements, change.expected,
                                change.reason + " (blocked by " + blocker + ")");
                    }
                    reported.push(change);
                    warn("namespace '" + this.namespace + "': change " + change.order
                            + " (" + change.action + " " + change.target + ") not applied: "
                            + change.reason);
                    await this.notifyReported(change, applied);
                }
            }
        } finally {
            clock.apply = clock.since(applyStart);
        }
        const verifyStart = clock.now();
        try {
            await this.verify(session, applied, coveredByParent);
        } finally {
            clock.verify = clock.since(verifyStart);
        }
        const outcome = applied.length === 0
                ? (plan.changes.length === 0 ? ReconcileOutcome.UP_TO_DATE
                        : ReconcileOutcome.REPORTED_ONLY)
                : ReconcileOutcome.RECONCILED;
        return { outcome, applied, reported };
    }

    private async verify(session: Session, applied: readonly PlannedChange[],
                         coveredByParent: ObjectMap<ObjectMap<Definition>>): Promise<void> {
        const expectations = new ObjectMap<Definition>();
        for (const change of applied) {
            if (change.expected !== undefined) {
                expectations.set(change.target, change.expected);
            }
            const covered = coveredByParent.get(change.target);
            if (covered !== undefined) {
                for (const [id, def] of covered) {
                    expectations.set(id, def);
                }
            }
        }
        if (expectations.size === 0) {
            return;
        }
        const after = await session.readActual();
        const remaining: Difference[] = [];
        for (const [id, expected] of expectations) {
            const object = after.objects.get(id);
            if (object === undefined) {
                remaining.push(new Difference(id, DifferenceType.MISSING,
                        expected, undefined));
            } else if (!object.definition.equals(expected)) {
                remaining.push(new Difference(id, DifferenceType.DRIFTED,
                        expected, object.definition));
            }
        }
        if (remaining.length > 0) {
            throw new VerificationException("namespace '" + this.namespace + "': "
                    + remaining.length + " object(s) did not converge after apply: "
                    + remaining.map(d => d.id.toString()).join(", "),
                    [...remaining]);
        }
    }

    private verifyPlan(plan: ReconcilePlan, dialect: Dialect, actual: SchemaSnapshot): void {
        if (this.namespace !== plan.targetNamespace || dialect.id() !== plan.dialectId) {
            throw new IllegalArgumentError("plan targets namespace '"
                    + plan.targetNamespace + "' on dialect '" + plan.dialectId
                    + "', this reconciler is bound to '" + this.namespace
                    + "' on '" + dialect.id() + "'");
        }
        if (actual.fingerprint !== plan.actualFingerprint) {
            throw new StalePlanException("namespace '" + this.namespace
                    + "': the target changed since this plan was issued", plan);
        }
    }

    private async desiredFor(plan: ReconcilePlan, session: Session): Promise<SchemaSnapshot> {
        const meta = this.planMeta.get(plan);
        if (meta !== undefined) {
            return meta.desired;
        }
        return this.loadDesired(session);
    }

    private unexpected(plan: ReconcilePlan): Difference[] {
        return plan.differences.filter(d => d.type === DifferenceType.UNEXPECTED);
    }

    private unsupported(plan: ReconcilePlan): Difference[] {
        return plan.differences.filter(d => d.type === DifferenceType.UNSUPPORTED
                && this.policy.unsupportedDecision(d) === Decision.REPORT);
    }

    /**
     * The WARNING logs of spec 3.3 and 5.5: unexpected objects and, under a
     * REPORT unsupported policy, each UNSUPPORTED difference.
     */
    private logUnexpectedAndUnsupported(unexpected: readonly Difference[],
                                        unsupported: readonly Difference[]): void {
        for (const d of unexpected) {
            warn("namespace '" + this.namespace + "': unexpected object " + d.id
                    + " is not in the desired state and was left unchanged");
        }
        for (const d of unsupported) {
            warn("namespace '" + this.namespace + "': unsupported object " + d.id
                    + " requires manual handling");
        }
    }

    private buildPlan(session: Session, actual: SchemaSnapshot, desired: SchemaSnapshot): Built {
        const differences = Differ.diff(actual, desired, session.dialect);
        return new PlanBuilder(session.dialect, this.namespace, actual, desired).build(differences);
    }

    private async loadDesired(session: Session): Promise<SchemaSnapshot> {
        const separate = this.scratchDataSource !== undefined;
        let scratch: SqlConnection;
        try {
            scratch = separate ? await this.scratchDataSource.connect() : session.conn;
        } catch (e) {
            throw new SchemaReconcilerException(
                    "namespace '" + this.namespace + "': could not obtain scratch connection", e);
        }
        let restoreAutoCommit = false;
        try {
            if (separate) {
                await this.checkSameEngine(session.conn, scratch, session.dialect);
                try {
                    await session.dialect.checkAccess?.(scratch, undefined, true);
                } catch (e) {
                    if (e instanceof ConfigurationException && !this.names(e)) {
                        throw new ConfigurationException(this.named(messageOf(e)), e);
                    }
                    throw e;
                }
                if (session.dialect.capabilities().transactionalDdl) {
                    await scratch.setAutoCommit(false);
                    restoreAutoCommit = true;
                }
                if (this.statementTimeout !== undefined) {
                    // the separate connection never sees the target session's
                    // SET LOCAL — the bound must be applied here too or the
                    // desired script and scratch snapshot read run unbounded
                    await session.dialect.statementTimeout?.(scratch, this.statementTimeout);
                }
            }
            let scratchNamespace: string;
            try {
                scratchNamespace = await session.dialect.createScratchNamespace(scratch, this.namespace);
            } catch (e) {
                throw new DesiredStateException("namespace '" + this.namespace
                        + "': could not create the scratch namespace", e);
            }
            try {
                const processor =
                        new ScriptProcessor(this.desiredState!.script, this.namespace,
                                scratchNamespace, session.dialect);
                if (!processor.hasPlaceholder
                        && !session.dialect.capabilities().sessionDefaultNamespace) {
                    throw new DesiredStateException("namespace '" + this.namespace
                            + "': the script contains no ${namespace} placeholder and dialect '"
                            + session.dialect.id() + "' has no session default namespace");
                }
                if (session.dialect.capabilities().sessionDefaultNamespace) {
                    await session.dialect.bindSessionNamespace?.(scratch, scratchNamespace);
                }
                try {
                    await session.dialect.executeScript(scratch, processor.substituted,
                            scratchNamespace);
                } catch (e) {
                    if (e instanceof DesiredStateException && this.names(e)) {
                        throw e;
                    }
                    if (e instanceof DesiredStateException) {
                        throw new DesiredStateException(this.named(messageOf(e)), e);
                    }
                    throw new DesiredStateException("namespace '" + this.namespace
                            + "': the desired-state script failed: " + messageOf(e), e);
                }
                return ObjectFilter.apply(
                        await session.dialect.readSnapshot(scratch, scratchNamespace),
                        this.objectFilter);
            } finally {
                try {
                    await session.dialect.dropScratchNamespace(scratch, scratchNamespace);
                } catch (e) {
                    throw new SchemaReconcilerException("namespace '" + this.namespace
                            + "': could not drop scratch namespace '" + scratchNamespace + "'", e);
                }
            }
        } catch (e) {
            if (e instanceof SchemaReconcilerException) {
                throw e;
            }
            throw new SchemaReconcilerException(
                    "namespace '" + this.namespace + "': scratch processing failed", e);
        } finally {
            if (separate) {
                try {
                    await scratch.rollback();
                } catch {
                    // already closed or committed inside dropScratchNamespace
                }
                try {
                    if (restoreAutoCommit) {
                        await scratch.setAutoCommit(true);
                    }
                } catch (e) {
                    warn("could not restore auto-commit on the scratch connection: "
                            + messageOf(e));
                } finally {
                    try {
                        await scratch.close();
                    } catch (e) {
                        throw new SchemaReconcilerException(
                                "namespace '" + this.namespace
                                        + "': could not close the scratch connection", e);
                    }
                }
            }
        }
    }

    private async checkSameEngine(target: SqlConnection, scratch: SqlConnection,
                                  dialect: Dialect): Promise<void> {
        const tm = await target.metadata();
        const sm = await scratch.metadata();
        const sameEngine = tm.databaseProductName === sm.databaseProductName
                && tm.databaseMajorVersion === sm.databaseMajorVersion;
        if (!sameEngine || !(await dialect.supports(sm))) {
            throw new ConfigurationException("namespace '" + this.namespace
                    + "': the scratchDataSource is not the same engine"
                    + " and major version as the target dataSource");
        }
    }

    private names(e: Error): boolean {
        return messageOf(e).includes("namespace '" + this.namespace + "'");
    }

    /** Prefixes a message raised below the engine (resolver, dialect) with the target namespace (spec 7). */
    private named(message: string): string {
        return "namespace '" + this.namespace + "': " + message;
    }

    private open(needsScratch = true): Promise<Session> {
        return Session.open(this, needsScratch);
    }

    /**
     * Spec 3.6: a listener failure during {@code reconcile()} or {@code apply()},
     * from {@code planned} too, aborts the run as {@link ApplyException}.
     */
    private async notifyPlanned(plan: ReconcilePlan, applied: readonly PlannedChange[]) {
        try {
            await this.listener.planned?.(plan);
        } catch (e) {
            throw new ApplyException("namespace '" + this.namespace
                    + "': listener aborted the run", [...applied], undefined, e);
        }
    }

    private async notifyApplying(change: PlannedChange, applied: readonly PlannedChange[]) {
        try {
            await this.listener.applying?.(change);
        } catch (e) {
            throw new ApplyException("namespace '" + this.namespace
                    + "': listener aborted the run", [...applied], change, e);
        }
    }

    private async notifyApplied(change: PlannedChange, elapsed: Duration,
                                applied: readonly PlannedChange[]) {
        try {
            await this.listener.applied?.(change, elapsed);
        } catch (e) {
            throw new ApplyException("namespace '" + this.namespace
                    + "': listener aborted the run", [...applied], change, e);
        }
    }

    private async notifyReported(change: PlannedChange, applied: readonly PlannedChange[]) {
        try {
            await this.listener.reported?.(change);
        } catch (e) {
            throw new ApplyException("namespace '" + this.namespace
                    + "': listener aborted the run", [...applied], undefined, e);
        }
    }

    /** Engine configuration a {@link Session} reads. */
    get config(): SessionConfig {
        return {
            dataSource: this.dataSource,
            scratchDataSource: this.scratchDataSource,
            namespace: this.namespace,
            lockTimeout: this.lockTimeout,
            statementTimeout: this.statementTimeout,
            objectFilter: this.objectFilter,
            resolver: this.resolver,
        };
    }
}

interface SessionConfig {
    readonly dataSource: SqlDataSource;
    readonly scratchDataSource: SqlDataSource | undefined;
    readonly namespace: string;
    readonly lockTimeout: Duration;
    readonly statementTimeout: Duration | undefined;
    readonly objectFilter: (id: ObjectId) => boolean;
    readonly resolver: DialectResolver;
}

/** Accumulates per-phase durations for {@link RunTimings}; monotonic clock. */
class PhaseClock {
    lockWait = Duration.ZERO;
    readActual = Duration.ZERO;
    loadDesired = Duration.ZERO;
    plan = Duration.ZERO;
    apply = Duration.ZERO;
    verify = Duration.ZERO;

    now(): number {
        return performance.now();
    }

    since(startMillis: number): Duration {
        return Duration.ofMillis(performance.now() - startMillis);
    }

    snapshot(): RunTimings {
        return new RunTimings(this.lockWait, this.readActual, this.loadDesired,
                this.plan, this.apply, this.verify);
    }
}

/** One reconcile run's connection, dialect, lock and transaction lifecycle. */
class Session {

    readonly dialect: Dialect;
    readonly conn: SqlConnection;
    private readonly config: SessionConfig;
    private restoreAutoCommit = false;
    private closed = false;

    private constructor(dialect: Dialect, conn: SqlConnection, config: SessionConfig) {
        this.dialect = dialect;
        this.conn = conn;
        this.config = config;
    }

    static async open(engine: Engine, needsScratch: boolean): Promise<Session> {
        const config = engine.config;
        const namespace = config.namespace;
        let conn: SqlConnection;
        try {
            conn = await config.dataSource.connect();
        } catch (e) {
            throw new SchemaReconcilerException(
                    "namespace '" + namespace + "': could not obtain a connection", e);
        }
        try {
            const dialect = await config.resolver.resolve(conn);
            const session = new Session(dialect, conn, config);
            DialectResolver.quoteOf(dialect, namespace);
            await dialect.checkAccess?.(conn, namespace,
                    needsScratch && config.scratchDataSource === undefined);
            if (dialect.capabilities().transactionalDdl && conn.getAutoCommit()) {
                await conn.setAutoCommit(false);
                session.restoreAutoCommit = true;
            }
            return session;
        } catch (e) {
            try {
                await conn.close();
            } catch {
                // best effort
            }
            if (e instanceof ConfigurationException
                    && !messageOf(e).includes("namespace '" + namespace + "'")) {
                throw new ConfigurationException(
                        "namespace '" + namespace + "': " + messageOf(e), e);
            }
            if (e instanceof SchemaReconcilerException) {
                throw e;
            }
            throw new SchemaReconcilerException(
                    "namespace '" + namespace + "': could not prepare the connection", e);
        }
    }

    async lock(): Promise<void> {
        const namespace = this.config.namespace;
        try {
            await this.dialect.acquireLock(this.conn, namespace, this.config.lockTimeout);
            if (this.config.statementTimeout !== undefined) {
                await this.dialect.statementTimeout?.(this.conn, this.config.statementTimeout);
            }
        } catch (e) {
            if (e instanceof LockTimeoutException) {
                throw e;
            }
            throw new SchemaReconcilerException(
                    "namespace '" + namespace + "': could not acquire the reconcile lock", e);
        }
    }

    async readActual(): Promise<SchemaSnapshot> {
        try {
            return ObjectFilter.apply(
                    await this.dialect.readSnapshot(this.conn, this.config.namespace),
                    this.config.objectFilter);
        } catch (e) {
            if (e instanceof SchemaReconcilerException) {
                throw e;
            }
            throw new SchemaReconcilerException(
                    "namespace '" + this.config.namespace
                            + "': could not read the catalog snapshot", e);
        }
    }

    async bindNamespace(): Promise<void> {
        if (this.dialect.capabilities().sessionDefaultNamespace) {
            try {
                await this.dialect.bindSessionNamespace?.(this.conn, this.config.namespace);
            } catch (e) {
                throw new SchemaReconcilerException(
                        "namespace '" + this.config.namespace
                                + "': could not bind the session namespace", e);
            }
        }
    }

    async execute(sql: string): Promise<void> {
        await this.conn.execute(sql);
    }

    async commit(): Promise<void> {
        try {
            await this.conn.commit();
        } catch (e) {
            throw new SchemaReconcilerException(
                    "namespace '" + this.config.namespace + "': commit failed", e);
        }
    }

    async close(): Promise<void> {
        if (this.closed) {
            return;
        }
        this.closed = true;
        const namespace = this.config.namespace;
        try {
            // roll back first: it clears an aborted transaction and
            // releases transaction-scoped locks, so explicit lock
            // release afterwards runs on a clean session
            try {
                await this.conn.rollback();
            } catch (e) {
                warn("rollback failed: " + messageOf(e));
            }
            try {
                await this.dialect.releaseLock(this.conn, namespace);
            } catch (e) {
                warn("lock release failed: " + messageOf(e));
            }
            try {
                if (this.restoreAutoCommit) {
                    await this.conn.setAutoCommit(true);
                }
            } catch (e) {
                // close() runs while an exception may be propagating: a throw
                // here would replace it, so the failure is only logged
                warn("namespace '" + namespace + "': could not restore auto-commit: "
                        + messageOf(e));
            }
        } finally {
            try {
                await this.conn.close();
            } catch (e) {
                warn("namespace '" + namespace + "': could not close the connection: "
                        + messageOf(e));
            }
        }
    }
}
