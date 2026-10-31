import { ConfigurationException } from "./ConfigurationException.ts";
import type { DesiredState } from "./DesiredState.ts";
import { Duration } from "./Duration.ts";
import type { ReconcileListener } from "./ReconcileListener.ts";
import { ReconcilePolicy } from "./ReconcilePolicy.ts";
import type { ReconcileReport } from "./ReconcileReport.ts";
import type { TimedPlan } from "./TimedPlan.ts";
import { Engine } from "./internal/Engine.ts";
import { registeredDialects } from "./internal/dialects.ts";
import type { ObjectId } from "./model/ObjectId.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";
import type { Dialect } from "./spi/Dialect.ts";
import type { SqlDataSource } from "./spi/Sql.ts";

/**
 * Entry point of the reconciler (spec 3.1). Instances are immutable and
 * thread-safe; concurrent runs against the same target namespace are
 * serialised by the namespace lock (spec 2.3.6).
 */
export class SchemaReconciler {

    private readonly engine: Engine;

    /** @internal use {@link SchemaReconciler.builder} */
    constructor(engine: Engine) {
        this.engine = engine;
    }

    static builder(): Builder {
        return new Builder();
    }

    /** Computes a plan without changing the target namespace. */
    plan(): Promise<ReconcilePlan> {
        return this.engine.plan();
    }

    /** Like {@link plan}, also returning the phase timings of the planning run. */
    planTimed(): Promise<TimedPlan> {
        return this.engine.planTimed();
    }

    /** Plans and applies under the configured policy, holding one namespace lock. */
    reconcile(): Promise<ReconcileReport> {
        return this.engine.reconcile();
    }

    /** Applies a plan previously returned by {@link plan}; fails if the target has changed since. */
    apply(plan: ReconcilePlan): Promise<ReconcileReport> {
        if (plan == null) {
            throw new TypeError("plan is required");
        }
        return this.engine.apply(plan);
    }

    /**
     * Reads the target namespace under the namespace lock and renders it as
     * a namespace-neutral desired-state script — the bootstrap path of spec
     * 3.1. Requires no {@code desiredState}; the target is never mutated.
     */
    dump(): Promise<string> {
        return this.engine.dump();
    }
}

export class Builder {

    private dataSource_: SqlDataSource | undefined;
    private targetNamespace_: string | undefined;
    private desiredState_: DesiredState | undefined;
    private dialect_: Dialect | undefined;
    private scratchDataSource_: SqlDataSource | undefined;
    private policy_: ReconcilePolicy = ReconcilePolicy.defaults();
    private lockTimeout_: Duration = Duration.ofSeconds(60);
    private statementTimeout_: Duration | undefined;
    private listener_: ReconcileListener = {};
    private objectFilter_: (id: ObjectId) => boolean = () => true;

    dataSource(dataSource: SqlDataSource): this {
        if (dataSource == null) {
            throw new TypeError("dataSource is required");
        }
        this.dataSource_ = dataSource;
        return this;
    }

    namespace(targetNamespace: string): this {
        if (targetNamespace == null) {
            throw new TypeError("targetNamespace is required");
        }
        this.targetNamespace_ = targetNamespace;
        return this;
    }

    /**
     * The desired-state script for {@code plan()}, {@code reconcile()}
     * and {@code apply()}. May be omitted when the reconciler is built
     * for {@link SchemaReconciler#dump} alone; the other methods then
     * fail with {@link ConfigurationException} (spec 3.1).
     */
    desiredState(desiredState: DesiredState): this {
        if (desiredState == null) {
            throw new TypeError("desiredState is required");
        }
        this.desiredState_ = desiredState;
        return this;
    }

    dialect(dialect: Dialect): this {
        if (dialect == null) {
            throw new TypeError("dialect is required");
        }
        this.dialect_ = dialect;
        return this;
    }

    scratchDataSource(scratchDataSource: SqlDataSource): this {
        if (scratchDataSource == null) {
            throw new TypeError("scratchDataSource is required");
        }
        this.scratchDataSource_ = scratchDataSource;
        return this;
    }

    policy(policy: ReconcilePolicy): this {
        if (policy == null) {
            throw new TypeError("policy is required");
        }
        this.policy_ = policy;
        return this;
    }

    lockTimeout(timeout: Duration): this {
        if (timeout == null) {
            throw new TypeError("timeout is required");
        }
        this.lockTimeout_ = timeout;
        return this;
    }

    /**
     * Bounds how long any single statement may run during a run — the
     * dialect applies it after the lock is acquired (spec 3.1), so a slow
     * applied statement cannot hold its locks unboundedly.
     */
    statementTimeout(timeout: Duration): this {
        if (timeout == null) {
            throw new TypeError("timeout is required");
        }
        this.statementTimeout_ = timeout;
        return this;
    }

    listener(listener: ReconcileListener): this {
        if (listener == null) {
            throw new TypeError("listener is required");
        }
        this.listener_ = listener;
        return this;
    }

    /**
     * Limits which catalog objects participate in reconciliation, in both
     * the desired and the actual snapshot. An object the predicate
     * rejects is invisible to the diff — it produces no {@code MISSING},
     * {@code DRIFTED}, {@code UNEXPECTED} or {@code UNSUPPORTED}
     * difference and no change — and its children and mirrored
     * annotations (comments, security labels, ownership, privileges and
     * publication bindings) are invisible with it. Dependencies an
     * invisible object records are invisible too: excluding a parent can
     * therefore unblock a change a detected dependent would otherwise
     * hold back, and a plan's staleness check no longer sees the excluded
     * objects (spec 5.7). Use it to ignore objects another tool manages —
     * staging tables, externally attached partitions. The default filter
     * includes every object.
     */
    filter(include: (id: ObjectId) => boolean): this {
        if (include == null) {
            throw new TypeError("include is required");
        }
        this.objectFilter_ = include;
        return this;
    }

    /**
     * Validates required properties and, when no dialect is set, that at
     * least one dialect is registered ({@link registerDialect}). Opens no
     * connection: dialect selection happens on first use (spec 3.1, 3.4).
     */
    build(): SchemaReconciler {
        if (this.dataSource_ === undefined) {
            throw new ConfigurationException("dataSource is required");
        }
        if (this.targetNamespace_ === undefined || this.targetNamespace_ === "") {
            throw new ConfigurationException("namespace is required");
        }
        if (this.lockTimeout_.isNegative || this.lockTimeout_.isZero) {
            throw new ConfigurationException("lockTimeout must be positive");
        }
        if (this.statementTimeout_ !== undefined
                && (this.statementTimeout_.isNegative || this.statementTimeout_.isZero)) {
            throw new ConfigurationException("statementTimeout must be positive");
        }
        if (this.dialect_ === undefined && registeredDialects().length === 0) {
            throw new ConfigurationException(
                    "no Dialect provider registered for namespace "
                            + this.targetNamespace_);
        }
        return new SchemaReconciler(new Engine(
                this.dataSource_, this.scratchDataSource_, this.targetNamespace_,
                this.desiredState_, this.dialect_, this.policy_, this.lockTimeout_,
                this.statementTimeout_, this.listener_, this.objectFilter_));
    }
}
