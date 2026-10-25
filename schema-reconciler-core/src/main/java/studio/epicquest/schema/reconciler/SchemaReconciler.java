package studio.epicquest.schema.reconciler;

import java.time.Duration;
import java.util.Objects;
import java.util.ServiceLoader;
import javax.sql.DataSource;
import studio.epicquest.schema.reconciler.internal.Engine;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
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

    /** Plans and applies under the configured policy, holding one namespace lock. */
    public ReconcileReport reconcile() {
        return engine.reconcile();
    }

    /** Applies a plan previously returned by {@link #plan()}; fails if the target has changed since. */
    public ReconcileReport apply(ReconcilePlan plan) {
        return engine.apply(Objects.requireNonNull(plan, "plan"));
    }

    public static final class Builder {

        private DataSource dataSource;
        private String targetNamespace;
        private DesiredState desiredState;
        private Dialect dialect;
        private DataSource scratchDataSource;
        private ReconcilePolicy policy = ReconcilePolicy.defaults();
        private Duration lockTimeout = Duration.ofSeconds(60);
        private ReconcileListener listener = ReconcileListener.NONE;

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

        public Builder desiredState(DesiredState desiredState) {
            this.desiredState = Objects.requireNonNull(desiredState, "desiredState");
            return this;
        }

        public Builder dialect(Dialect dialect) {
            this.dialect = Objects.requireNonNull(dialect, "dialect");
            return this;
        }

        public Builder scratchDataSource(DataSource dataSource) {
            this.scratchDataSource = Objects.requireNonNull(dataSource, "dataSource");
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

        public Builder listener(ReconcileListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
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
            if (desiredState == null) {
                throw new ConfigurationException("desiredState is required");
            }
            if (lockTimeout.isNegative() || lockTimeout.isZero()) {
                throw new ConfigurationException("lockTimeout must be positive");
            }
            if (dialect == null && ServiceLoader.load(Dialect.class).findFirst().isEmpty()) {
                throw new ConfigurationException(
                        "no Dialect provider discoverable via ServiceLoader for namespace "
                        + targetNamespace);
            }
            return new SchemaReconciler(new Engine(
                    dataSource, scratchDataSource, targetNamespace, desiredState,
                    dialect, policy, lockTimeout, listener));
        }
    }
}
