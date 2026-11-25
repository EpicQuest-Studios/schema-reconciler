package studio.epicquest.schema.reconciler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import javax.sql.DataSource;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * A convenience facade that reconciles several namespaces in one call
 * (spec 3.7): each registered namespace gets its own {@link
 * SchemaReconciler}, built with the shared configuration, and {@link
 * #reconcile} runs them in registration order so a namespace whose
 * desired script references an earlier one — a cross-schema foreign
 * key — sees it already reconciled. Instances are immutable and
 * thread-safe; concurrent runs against the same target namespaces are
 * serialised by each namespace's lock (spec 2.3.6).
 */
public final class MultiNamespaceReconciler {

    private final Map<String, SchemaReconciler> reconcilers;

    private MultiNamespaceReconciler(Map<String, SchemaReconciler> reconcilers) {
        this.reconcilers = reconcilers;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * One {@link SchemaReconciler#plan} per namespace, in configured
     * order, keyed by namespace name. Stops at the first failure.
     */
    public Map<String, ReconcilePlan> plan() {
        final Map<String, ReconcilePlan> out = new LinkedHashMap<>();
        for (final var entry : reconcilers.entrySet()) {
            out.put(entry.getKey(), entry.getValue().plan());
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * One {@link SchemaReconciler#reconcile} per namespace in configured
     * order — each holds its own namespace lock only for its own run.
     * Fails fast: the first exception aborts the run; namespaces already
     * reconciled stay reconciled.
     */
    public MultiReconcileReport reconcile() {
        final var start = System.nanoTime();
        final Map<String, ReconcileReport> reports = new LinkedHashMap<>();
        var outcome = ReconcileOutcome.UP_TO_DATE;
        for (final var entry : reconcilers.entrySet()) {
            final var report = entry.getValue().reconcile();
            reports.put(entry.getKey(), report);
            if (report.outcome() == ReconcileOutcome.RECONCILED) {
                outcome = ReconcileOutcome.RECONCILED;
            } else if (report.outcome() == ReconcileOutcome.REPORTED_ONLY
                    && outcome != ReconcileOutcome.RECONCILED) {
                outcome = ReconcileOutcome.REPORTED_ONLY;
            }
        }
        return new MultiReconcileReport(outcome, reports,
                Duration.ofNanos(System.nanoTime() - start));
    }

    /**
     * One {@link SchemaReconciler#dump} per namespace, in configured
     * order, keyed by namespace name.
     */
    public Map<String, String> dump() {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final var entry : reconcilers.entrySet()) {
            out.put(entry.getKey(), entry.getValue().dump());
        }
        return Collections.unmodifiableMap(out);
    }

    public static final class Builder {

        private record Registration(String name, DesiredState desiredState,
                                    Consumer<SchemaReconciler.Builder> customize) {
        }

        private DataSource dataSource;
        private DataSource scratchDataSource;
        private Dialect dialect;
        private ReconcilePolicy policy = ReconcilePolicy.defaults();
        private Duration lockTimeout = Duration.ofSeconds(60);
        private Duration statementTimeout;
        private ReconcileListener listener = ReconcileListener.NONE;
        private Predicate<ObjectId> objectFilter = id -> true;
        private boolean concurrentIndexes;
        private final List<BiPredicate<String, String>> externalPartitionMatchers = new ArrayList<>();
        private Predicate<ObjectId> pruneFilter = id -> true;
        private HookStore hookStore;
        private final List<Registration> namespaces = new ArrayList<>();

        private Builder() {
        }

        public Builder dataSource(DataSource dataSource) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            return this;
        }

        public Builder scratchDataSource(DataSource scratchDataSource) {
            this.scratchDataSource =
                    Objects.requireNonNull(scratchDataSource, "scratchDataSource");
            return this;
        }

        public Builder dialect(Dialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect");
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

        public Builder statementTimeout(Duration timeout) {
            this.statementTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder listener(ReconcileListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder filter(Predicate<ObjectId> include) {
            this.objectFilter = Objects.requireNonNull(include, "include");
            return this;
        }

        public Builder concurrentIndexes(boolean enabled) {
            this.concurrentIndexes = enabled;
            return this;
        }

        /** Coexists with every runtime partition directly parented on {@code parentTable}. */
        public Builder externalPartitions(String parentTable) {
            if (parentTable == null) {
                throw new NullPointerException("parentTable");
            }
            if (parentTable.isEmpty()) {
                throw new IllegalArgumentException("parentTable must not be empty");
            }
            this.externalPartitionMatchers.add((parent, partition) -> parent.equals(parentTable));
            return this;
        }

        /** Coexists with runtime partitions of {@code parentTable} whose names start with {@code namePrefix}. */
        public Builder externalPartitions(String parentTable, String namePrefix) {
            if (parentTable == null) {
                throw new NullPointerException("parentTable");
            }
            if (parentTable.isEmpty()) {
                throw new IllegalArgumentException("parentTable must not be empty");
            }
            if (namePrefix == null) {
                throw new NullPointerException("namePrefix");
            }
            this.externalPartitionMatchers.add((parent, partition) ->
                    parent.equals(parentTable) && partition.startsWith(namePrefix));
            return this;
        }

        /** Coexists with runtime partitions accepted by {@code matcher}. */
        public Builder externalPartitions(BiPredicate<String, String> matcher) {
            if (matcher == null) {
                throw new NullPointerException("matcher");
            }
            this.externalPartitionMatchers.add(matcher);
            return this;
        }

        /** Limits which unexpected objects prune may drop in every namespace. */
        public Builder pruneFilter(Predicate<ObjectId> allowPrune) {
            if (allowPrune == null) {
                throw new NullPointerException("allowPrune");
            }
            this.pruneFilter = allowPrune;
            return this;
        }

        /** Registers the shared hook store forwarded to every namespace. */
        public Builder hookStore(HookStore hookStore) {
            if (hookStore == null) {
                throw new NullPointerException("hookStore");
            }
            this.hookStore = hookStore;
            return this;
        }

        /**
         * Registers a target namespace for {@link #dump} usage alone — no
         * {@code desiredState}, so {@code plan} and {@code reconcile}
         * still raise {@link ConfigurationException} for it (spec 3.1).
         */
        public Builder namespace(String namespace) {
            return namespace(namespace, null, null);
        }

        /** Registers a target namespace, preserving execution order. */
        public Builder namespace(String namespace, DesiredState desiredState) {
            return namespace(namespace, desiredState, null);
        }

        /**
         * Registers a target namespace; {@code customize} adjusts this
         * namespace's {@link SchemaReconciler.Builder} after the shared
         * defaults are applied.
         */
        public Builder namespace(String namespace, DesiredState desiredState,
                                 Consumer<SchemaReconciler.Builder> customize) {
            namespaces.add(new Registration(
                    Objects.requireNonNull(namespace, "namespace"),
                    desiredState, customize));
            return this;
        }

        /**
         * Builds every registered namespace's {@link SchemaReconciler} —
         * each {@link SchemaReconciler.Builder#build} validates that
         * namespace's configuration and opens no connection (spec 3.1,
         * 3.4).
         */
        public MultiNamespaceReconciler build() {
            if (dataSource == null) {
                throw new ConfigurationException("dataSource is required");
            }
            if (namespaces.isEmpty()) {
                throw new ConfigurationException(
                        "at least one namespace is required");
            }
            final Map<String, SchemaReconciler> reconcilers = new LinkedHashMap<>();
            for (final var reg : namespaces) {
                if (reg.name().isEmpty()) {
                    throw new ConfigurationException(
                            "namespace must not be empty");
                }
                if (reconcilers.containsKey(reg.name())) {
                    throw new ConfigurationException(
                            "duplicate namespace " + reg.name());
                }
                final var b = SchemaReconciler.builder()
                        .dataSource(dataSource)
                        .namespace(reg.name())
                        .policy(policy)
                        .lockTimeout(lockTimeout)
                        .listener(listener)
                        .filter(objectFilter)
                        .concurrentIndexes(concurrentIndexes)
                        .pruneFilter(pruneFilter);
                for (final var matcher : externalPartitionMatchers) {
                    b.externalPartitions(matcher);
                }
                if (hookStore != null) {
                    b.hookStore(hookStore);
                }
                if (scratchDataSource != null) {
                    b.scratchDataSource(scratchDataSource);
                }
                if (dialect != null) {
                    b.dialect(dialect);
                }
                if (statementTimeout != null) {
                    b.statementTimeout(statementTimeout);
                }
                if (reg.desiredState() != null) {
                    b.desiredState(reg.desiredState());
                }
                if (reg.customize() != null) {
                    reg.customize().accept(b);
                }
                reconcilers.put(reg.name(), b.build());
            }
            return new MultiNamespaceReconciler(reconcilers);
        }
    }
}
