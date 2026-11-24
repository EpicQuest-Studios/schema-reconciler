import { ConfigurationException } from "./ConfigurationException.ts";
import type { DesiredState } from "./DesiredState.ts";
import { Duration } from "./Duration.ts";
import { MultiReconcileReport } from "./MultiReconcileReport.ts";
import type { ReconcileListener } from "./ReconcileListener.ts";
import { ReconcileOutcome } from "./ReconcileOutcome.ts";
import { ReconcilePolicy } from "./ReconcilePolicy.ts";
import type { ReconcileReport } from "./ReconcileReport.ts";
import { Builder as SchemaReconcilerBuilder, SchemaReconciler }
        from "./SchemaReconciler.ts";
import type { ObjectId } from "./model/ObjectId.ts";
import type { ReconcilePlan } from "./model/ReconcilePlan.ts";
import type { Dialect } from "./spi/Dialect.ts";
import type { SqlDataSource } from "./spi/Sql.ts";

interface Registration {
    readonly name: string;
    readonly desiredState: DesiredState | undefined;
    readonly customize: ((b: SchemaReconcilerBuilder) => void) | undefined;
}

/**
 * A convenience facade that reconciles several namespaces in one call
 * (spec 3.7): each registered namespace gets its own {@link
 * SchemaReconciler}, built with the shared configuration, and {@link
 * reconcile} runs them in registration order so a namespace whose
 * desired script references an earlier one — a cross-schema foreign
 * key — sees it already reconciled. Instances are immutable and
 * thread-safe; concurrent runs against the same target namespaces are
 * serialised by each namespace's lock (spec 2.3.6).
 */
export class MultiNamespaceReconciler {

    private readonly reconcilers: ReadonlyMap<string, SchemaReconciler>;

    /** @internal use {@link MultiNamespaceReconciler.builder} */
    constructor(reconcilers: ReadonlyMap<string, SchemaReconciler>) {
        this.reconcilers = reconcilers;
    }

    static builder(): MultiNamespaceBuilder {
        return new MultiNamespaceBuilder();
    }

    /**
     * One {@link SchemaReconciler#plan} per namespace, in configured
     * order, keyed by namespace name. Stops at the first failure.
     */
    async plan(): Promise<ReadonlyMap<string, ReconcilePlan>> {
        const out = new Map<string, ReconcilePlan>();
        for (const [ns, reconciler] of this.reconcilers) {
            out.set(ns, await reconciler.plan());
        }
        return out;
    }

    /**
     * One {@link SchemaReconciler#reconcile} per namespace in configured
     * order — each holds its own namespace lock only for its own run.
     * Fails fast: the first exception aborts the run; namespaces already
     * reconciled stay reconciled.
     */
    async reconcile(): Promise<MultiReconcileReport> {
        const start = performance.now();
        const reports = new Map<string, ReconcileReport>();
        let outcome: ReconcileOutcome = ReconcileOutcome.UP_TO_DATE;
        for (const [ns, reconciler] of this.reconcilers) {
            const report = await reconciler.reconcile();
            reports.set(ns, report);
            if (report.outcome === ReconcileOutcome.RECONCILED) {
                outcome = ReconcileOutcome.RECONCILED;
            } else if (report.outcome === ReconcileOutcome.REPORTED_ONLY
                    && outcome !== ReconcileOutcome.RECONCILED) {
                outcome = ReconcileOutcome.REPORTED_ONLY;
            }
        }
        return new MultiReconcileReport(outcome, reports,
                Duration.ofMillis(performance.now() - start));
    }

    /**
     * One {@link SchemaReconciler#dump} per namespace, in configured
     * order, keyed by namespace name.
     */
    async dump(): Promise<ReadonlyMap<string, string>> {
        const out = new Map<string, string>();
        for (const [ns, reconciler] of this.reconcilers) {
            out.set(ns, await reconciler.dump());
        }
        return out;
    }
}

export class MultiNamespaceBuilder {

    private dataSource_: SqlDataSource | undefined;
    private scratchDataSource_: SqlDataSource | undefined;
    private dialect_: Dialect | undefined;
    private policy_: ReconcilePolicy = ReconcilePolicy.defaults();
    private lockTimeout_: Duration = Duration.ofSeconds(60);
    private statementTimeout_: Duration | undefined;
    private listener_: ReconcileListener = {};
    private objectFilter_: (id: ObjectId) => boolean = () => true;
    private concurrentIndexes_ = false;
    private readonly namespaces_: Registration[] = [];

    dataSource(dataSource: SqlDataSource): this {
        if (dataSource == null) {
            throw new TypeError("dataSource is required");
        }
        this.dataSource_ = dataSource;
        return this;
    }

    scratchDataSource(scratchDataSource: SqlDataSource): this {
        if (scratchDataSource == null) {
            throw new TypeError("scratchDataSource is required");
        }
        this.scratchDataSource_ = scratchDataSource;
        return this;
    }

    dialect(dialect: Dialect): this {
        if (dialect == null) {
            throw new TypeError("dialect is required");
        }
        this.dialect_ = dialect;
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

    filter(include: (id: ObjectId) => boolean): this {
        if (include == null) {
            throw new TypeError("include is required");
        }
        this.objectFilter_ = include;
        return this;
    }

    concurrentIndexes(enabled: boolean): this {
        this.concurrentIndexes_ = enabled;
        return this;
    }

    /**
     * Registers a target namespace, preserving execution order. The
     * {@code desiredState} may be omitted only when the reconciler is
     * built for {@link MultiNamespaceReconciler#dump} alone (spec 3.1);
     * {@code customize} adjusts this namespace's {@link
     * SchemaReconcilerBuilder} after the shared defaults are applied.
     */
    namespace(namespace: string, desiredState?: DesiredState,
              customize?: (b: SchemaReconcilerBuilder) => void): this {
        if (namespace == null) {
            throw new TypeError("namespace is required");
        }
        this.namespaces_.push({ name: namespace, desiredState, customize });
        return this;
    }

    /**
     * Builds every registered namespace's {@link SchemaReconciler} —
     * each {@link SchemaReconcilerBuilder#build} validates that
     * namespace's configuration and opens no connection (spec 3.1, 3.4).
     */
    build(): MultiNamespaceReconciler {
        if (this.dataSource_ === undefined) {
            throw new ConfigurationException("dataSource is required");
        }
        if (this.namespaces_.length === 0) {
            throw new ConfigurationException(
                    "at least one namespace is required");
        }
        const reconcilers = new Map<string, SchemaReconciler>();
        for (const reg of this.namespaces_) {
            if (reg.name === "") {
                throw new ConfigurationException(
                        "namespace must not be empty");
            }
            if (reconcilers.has(reg.name)) {
                throw new ConfigurationException(
                        "duplicate namespace " + reg.name);
            }
            const b = SchemaReconciler.builder()
                    .dataSource(this.dataSource_)
                    .namespace(reg.name)
                    .policy(this.policy_)
                    .lockTimeout(this.lockTimeout_)
                    .listener(this.listener_)
                    .filter(this.objectFilter_)
                    .concurrentIndexes(this.concurrentIndexes_);
            if (this.scratchDataSource_ !== undefined) {
                b.scratchDataSource(this.scratchDataSource_);
            }
            if (this.dialect_ !== undefined) {
                b.dialect(this.dialect_);
            }
            if (this.statementTimeout_ !== undefined) {
                b.statementTimeout(this.statementTimeout_);
            }
            if (reg.desiredState !== undefined) {
                b.desiredState(reg.desiredState);
            }
            reg.customize?.(b);
            reconcilers.set(reg.name, b.build());
        }
        return new MultiNamespaceReconciler(reconcilers);
    }
}
