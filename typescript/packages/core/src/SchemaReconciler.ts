import { ConfigurationException } from "./ConfigurationException.ts";
import { IllegalArgumentError } from "./IllegalArgumentError.ts";
import type { DesiredState } from "./DesiredState.ts";
import { Duration } from "./Duration.ts";
import type { ReconcileListener } from "./ReconcileListener.ts";
import { ReconcilePolicy } from "./ReconcilePolicy.ts";
import type { ReconcileReport } from "./ReconcileReport.ts";
import type { TimedPlan } from "./TimedPlan.ts";
import { Engine } from "./internal/Engine.ts";
import type { ExternalPartitionMatcher } from "./internal/ExternalPartitions.ts";
import { registeredDialects } from "./internal/dialects.ts";
import type { ObjectId } from "./model/ObjectId.ts";
import { ObjectKind } from "./model/ObjectKind.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";
import { RenameHint } from "./model/RenameHint.ts";
import type { Dialect } from "./spi/Dialect.ts";
import type { HookStore } from "./HookStore.ts";
import type { SchemaHook } from "./SchemaHook.ts";
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
     * Applies a plan serialised by {@link ReconcilePlan.toJson} — the
     * machine-readable counterpart of {@link apply}. Fails on invalid
     * JSON or when the target changed since the plan was issued.
     */
    applyJson(serializedPlan: string): Promise<ReconcileReport> {
        if (serializedPlan == null) {
            throw new TypeError("serializedPlan is required");
        }
        return this.engine.applyJson(serializedPlan);
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
    private concurrentIndexes_ = false;
    private renameHints_: RenameHint[] = [];
    private retireTargets_: ObjectId[] = [];
    private externalPartitionMatchers_: ExternalPartitionMatcher[] = [];
    private pruneFilter_: (id: ObjectId) => boolean = () => true;
    private hooks_: SchemaHook[] = [];
    private hookStore_: HookStore | undefined;

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
     * Renders index creates/drops in their concurrent, non-transactional
     * form where the dialect supports it (PostgreSQL: `CREATE INDEX
     * CONCURRENTLY` outside a transaction, spec 5.6). Default `false`.
     */
    concurrentIndexes(enabled: boolean): this {
        this.concurrentIndexes_ = enabled;
        return this;
    }

    /**
     * Declares that {@code from} was renamed to {@code to} — the same
     * hint the `-- @reconciler rename` script directive gives. An active
     * hint turns the UNEXPECTED(from)/MISSING(to) pair into an
     * `ALTER ... RENAME` change instead of a drop+create (spec 5.5.1).
     */
    rename(from: ObjectId, to: ObjectId): this {
        this.renameHints_.push(new RenameHint(from, to));
        return this;
    }

    /** Marks `id` for targeted retirement even when the prune policy is off. */
    retire(id: ObjectId): this {
        if (id == null) {
            throw new TypeError("id is required");
        }
        if (id.kind === ObjectKind.OTHER) {
            throw new IllegalArgumentError("OTHER objects cannot be retired: " + id);
        }
        this.retireTargets_.push(id);
        return this;
    }

    /** Coexists with externally managed runtime partitions. */
    externalPartitions(parentTable: string,
            match?: string | RegExp | ((partitionName: string) => boolean)): this;
    externalPartitions(matcher: (parentTable: string, partitionName: string) => boolean): this;
    externalPartitions(parentOrMatcher: string | ((parentTable: string, partitionName: string) => boolean),
            match?: string | RegExp | ((partitionName: string) => boolean)): this {
        if (typeof parentOrMatcher === "function") {
            this.externalPartitionMatchers_.push(parentOrMatcher);
            return this;
        }
        if (parentOrMatcher == null || parentOrMatcher === "") {
            throw new IllegalArgumentError("parentTable must not be empty");
        }
        if (match === null) {
            throw new TypeError("match must not be null");
        }
        if (match === undefined) {
            this.externalPartitionMatchers_.push((parent, _partition) => parent === parentOrMatcher);
        } else if (typeof match === "string") {
            this.externalPartitionMatchers_.push((parent, partition) =>
                    parent === parentOrMatcher && partition.startsWith(match));
        } else if (match instanceof RegExp) {
            this.externalPartitionMatchers_.push((parent, partition) =>
                    parent === parentOrMatcher && match.test(partition));
        } else if (typeof match === "function") {
            this.externalPartitionMatchers_.push((parent, partition) =>
                    parent === parentOrMatcher && match(partition));
        } else {
            throw new TypeError("match must be a string, RegExp, or predicate");
        }
        return this;
    }

    /** Limits which unexpected objects prune may drop; visibility is unaffected. */
    pruneFilter(allowPrune: (id: ObjectId) => boolean): this {
        if (allowPrune == null) {
            throw new TypeError("allowPrune is required");
        }
        this.pruneFilter_ = allowPrune;
        return this;
    }

    /** Registers an ordered lifecycle hook (spec 3.8); repeatable. */
    hook(hook: SchemaHook): this {
        if (hook == null) {
            throw new TypeError("hook is required");
        }
        this.hooks_.push(hook);
        return this;
    }

    /** Registers the durable store checksum-protected run-once hooks use. */
    hookStore(store: HookStore): this {
        if (store == null) {
            throw new TypeError("store is required");
        }
        this.hookStore_ = store;
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
        validateHooks(this.hooks_, this.hookStore_);
        return new SchemaReconciler(new Engine(
                this.dataSource_, this.scratchDataSource_, this.targetNamespace_,
                this.desiredState_, this.dialect_, this.policy_, this.lockTimeout_,
                this.statementTimeout_, this.listener_, this.objectFilter_,
                this.concurrentIndexes_, this.renameHints_, this.retireTargets_,
                this.externalPartitionMatchers_, this.pruneFilter_, this.hooks_,
                this.hookStore_));
    }
}

function validateHooks(hooks: readonly SchemaHook[], hookStore: HookStore | undefined): void {
    const ids = new Set<string>();
    for (const hook of hooks) {
        if (hook.id == null || hook.id.trim() === "") {
            throw new ConfigurationException("hook id must not be blank");
        }
        if (hook.phase == null) {
            throw new ConfigurationException("hook '" + hook.id + "' has no phase");
        }
        if (typeof hook.run !== "function") {
            throw new ConfigurationException("hook '" + hook.id + "' has no run action");
        }
        if (ids.has(hook.id)) {
            throw new ConfigurationException("duplicate hook id '" + hook.id + "'");
        }
        ids.add(hook.id);
        if (hook.runOnce === true && hookStore === undefined) {
            throw new ConfigurationException("hook '" + hook.id
                    + "' is runOnce but no hookStore is configured");
        }
    }
}
