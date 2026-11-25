package studio.epicquest.schema.reconciler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import javax.sql.DataSource;
import studio.epicquest.schema.reconciler.internal.Engine;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.RenameHint;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Entry point of the reconciler (spec 3.1). Instances are immutable and
 * thread-safe; concurrent runs against the same target namespace are
 * serialised by the namespace lock (spec 2.3.6).
 */
public final class SchemaReconciler {

    private final Engine engine;

    private SchemaReconciler(Engine engine) {
        this.engine = engine;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Computes a plan without changing the target namespace. */
    public ReconcilePlan plan() {
        return engine.plan();
    }

    /** Like {@link #plan()}, also returning the phase timings of the planning run. */
    public TimedPlan planTimed() {
        return engine.planTimed();
    }

    /** Plans and applies under the configured policy, holding one namespace lock. */
    public ReconcileReport reconcile() {
        return engine.reconcile();
    }

    /** Applies a plan previously returned by {@link #plan()}; fails if the target has changed since. */
    public ReconcileReport apply(ReconcilePlan plan) {
        return engine.apply(Objects.requireNonNull(plan, "plan"));
    }

    /**
     * Applies a plan serialised by {@link ReconcilePlan#toJson()} — the
     * machine-readable counterpart of {@link #apply}. Fails on invalid
     * JSON or when the target changed since the plan was issued.
     */
    public ReconcileReport applyJson(String serializedPlan) {
        return engine.applyJson(Objects.requireNonNull(serializedPlan, "serializedPlan"));
    }

    /**
     * Reads the target namespace under the namespace lock and renders it as
     * a namespace-neutral desired-state script — the bootstrap path of spec
     * 3.1. Requires no {@code desiredState}; the target is never mutated.
     */
    public String dump() {
        return engine.dump();
    }

    public static final class Builder {

        private DataSource dataSource;
        private String targetNamespace;
        private DesiredState desiredState;
        private Dialect dialect;
        private DataSource scratchDataSource;
        private ReconcilePolicy policy = ReconcilePolicy.defaults();
        private Duration lockTimeout = Duration.ofSeconds(60);
        private Duration statementTimeout;
        private ReconcileListener listener = ReconcileListener.NONE;
        private Predicate<ObjectId> objectFilter = id -> true;
        private boolean concurrentIndexes;
        private final List<RenameHint> renameHints = new ArrayList<>();
        private final List<ObjectId> retireTargets = new ArrayList<>();
        private final List<BiPredicate<String, String>> externalPartitionMatchers = new ArrayList<>();
        private Predicate<ObjectId> pruneFilter = id -> true;
        private final List<SchemaHook> hooks = new ArrayList<>();
        private HookStore hookStore;

        private Builder() {
        }

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            return this;
        }

        public Builder namespace(String targetNamespace) {
            this.targetNamespace = Objects.requireNonNull(targetNamespace, "targetNamespace");
            return this;
        }

        /**
         * The desired-state script for {@code plan()}, {@code reconcile()}
         * and {@code apply()}. May be omitted when the reconciler is built
         * for {@link SchemaReconciler#dump()} alone; the other methods then
         * fail with {@link ConfigurationException} (spec 3.1).
         */
        public Builder desiredState(DesiredState desiredState) {
            this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
            return this;
        }

        public Builder dialect(Dialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect");
            return this;
        }

        public Builder scratchDataSource(DataSource scratchDataSource) {
            this.scratchDataSource = Objects.requireNonNull(scratchDataSource, "scratchDataSource");
            return this;
        }

        public Builder policy(ReconcilePolicy policy) {
            this.policy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder lockTimeout(Duration timeout) {
            this.lockTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Bounds how long any single statement may run during a run — the
         * dialect applies it after the lock is acquired (spec 3.1), so a slow
         * applied statement cannot hold its locks unboundedly.
         */
        public Builder statementTimeout(Duration timeout) {
            this.statementTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder listener(ReconcileListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
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
        public Builder filter(Predicate<ObjectId> include) {
            this.objectFilter = Objects.requireNonNull(include, "include");
            return this;
        }

        /**
         * Renders index creates/drops in their concurrent, non-transactional
         * form where the dialect supports it (PostgreSQL: {@code CREATE INDEX
         * CONCURRENTLY} outside a transaction, spec 5.6). Default {@code false}.
         */
        public Builder concurrentIndexes(boolean enabled) {
            this.concurrentIndexes = enabled;
            return this;
        }

        /**
         * Declares that {@code from} was renamed to {@code to} — the same
         * hint the {@code -- @reconciler rename} script directive gives. An
         * active hint turns the UNEXPECTED(from)/MISSING(to) pair into an
         * {@code ALTER ... RENAME} change instead of a drop+create (spec
         * 5.5.1).
         */
        public Builder rename(ObjectId from, ObjectId to) {
            this.renameHints.add(new RenameHint(from, to));
            return this;
        }

        /**
         * Marks {@code id} for targeted retirement: when it exists in the
         * actual snapshot and is absent from the desired state it is dropped
         * even when the prune policy is off (spec 5.5). {@code OTHER} objects
         * are unsupported shadows and cannot be retired.
         */
        public Builder retire(ObjectId id) {
            Objects.requireNonNull(id, "id");
            if (id.kind() == studio.epicquest.schema.reconciler.model.ObjectKind.OTHER) {
                throw new IllegalArgumentException("OTHER objects cannot be retired: " + id);
            }
            this.retireTargets.add(id);
            return this;
        }

        /** Coexists with every runtime partition directly parented on {@code parentTable}. */
        public Builder externalPartitions(String parentTable) {
            validateParentTable(parentTable);
            this.externalPartitionMatchers.add((parent, partition) -> parent.equals(parentTable));
            return this;
        }

        /** Coexists with runtime partitions of {@code parentTable} whose names start with {@code namePrefix}. */
        public Builder externalPartitions(String parentTable, String namePrefix) {
            validateParentTable(parentTable);
            Objects.requireNonNull(namePrefix, "namePrefix");
            this.externalPartitionMatchers.add((parent, partition) ->
                    parent.equals(parentTable) && partition.startsWith(namePrefix));
            return this;
        }

        /** Coexists with runtime partitions {@code matcher} accepts as {@code (parentTable, partitionName)}. */
        public Builder externalPartitions(BiPredicate<String, String> matcher) {
            this.externalPartitionMatchers.add(Objects.requireNonNull(matcher, "matcher"));
            return this;
        }

        private static void validateParentTable(String parentTable) {
            Objects.requireNonNull(parentTable, "parentTable");
            if (parentTable.isEmpty()) {
                throw new IllegalArgumentException("parentTable must not be empty");
            }
        }

        /**
         * Limits which unexpected objects {@code prune=true} may drop; visibility
         * in plans and reports is unaffected. Rejecting a container protects its
         * contained objects transitively unless they are explicitly retired.
         */
        public Builder pruneFilter(Predicate<ObjectId> allowPrune) {
            this.pruneFilter = Objects.requireNonNull(allowPrune, "allowPrune");
            return this;
        }

        /** Registers an ordered lifecycle hook (spec 3.8); repeatable. */
        public Builder hook(SchemaHook hook) {
            this.hooks.add(Objects.requireNonNull(hook, "hook"));
            return this;
        }

        /** Registers the durable store checksum-protected {@code runOnce} hooks use. */
        public Builder hookStore(HookStore hookStore) {
            this.hookStore = Objects.requireNonNull(hookStore, "hookStore");
            return this;
        }

        /**
         * Validates required properties and, when no dialect is set, that at
         * least one {@code Dialect} provider is discoverable. Opens no
         * connection: dialect selection happens on first use (spec 3.1, 3.4).
         */
        public SchemaReconciler build() {
            if (dataSource == null) {
                throw new ConfigurationException("dataSource is required");
            }
            if (targetNamespace == null || targetNamespace.isEmpty()) {
                throw new ConfigurationException("namespace is required");
            }
            if (lockTimeout.isNegative() || lockTimeout.isZero()) {
                throw new ConfigurationException("lockTimeout must be positive");
            }
            if (statementTimeout != null
                    && (statementTimeout.isNegative() || statementTimeout.isZero())) {
                throw new ConfigurationException("statementTimeout must be positive");
            }
            if (dialect == null && ServiceLoader.load(Dialect.class).findFirst().isEmpty()) {
                throw new ConfigurationException(
                        "no Dialect provider discoverable via ServiceLoader for namespace "
                        + targetNamespace);
            }
            validateHooks(hooks, hookStore);
            return new SchemaReconciler(new Engine(
                    dataSource, scratchDataSource, targetNamespace, desiredState,
                    dialect, policy, lockTimeout, statementTimeout, listener,
                    objectFilter, concurrentIndexes, List.copyOf(renameHints),
                    List.copyOf(retireTargets), List.copyOf(externalPartitionMatchers),
                    pruneFilter, List.copyOf(hooks), hookStore));
        }

        private static void validateHooks(List<SchemaHook> hooks, HookStore hookStore) {
            final var ids = new java.util.HashSet<String>();
            for (final var hook : hooks) {
                final var id = hook.id();
                if (id == null || id.isBlank()) {
                    throw new ConfigurationException("hook id must not be blank");
                }
                if (hook.phase() == null) {
                    throw new ConfigurationException("hook '" + id + "' has no phase");
                }
                if (!ids.add(id)) {
                    throw new ConfigurationException("duplicate hook id '" + id + "'");
                }
                if (hook.runOnce() && hookStore == null) {
                    throw new ConfigurationException("hook '" + id
                            + "' is runOnce but no hookStore is configured");
                }
            }
        }
    }
}
