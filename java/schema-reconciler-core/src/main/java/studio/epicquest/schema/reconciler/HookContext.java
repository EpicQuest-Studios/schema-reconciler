package studio.epicquest.schema.reconciler;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * The immutable context passed to a {@link SchemaHook} (spec 3.8).
 *
 * @param namespace the target namespace
 * @param dialectId the resolved dialect id
 * @param phase the lifecycle phase being run
 * @param plan the plan being applied
 * @param applied the changes successfully applied before this hook ran
 * @param connection the run's pinned connection, inside the transaction for
 *        {@code PRE_APPLY}/{@code POST_APPLY} and in auto-commit mode for
 *        {@code POST_COMMIT}
 */
public record HookContext(String namespace, String dialectId, HookPhase phase,
                          ReconcilePlan plan, List<PlannedChange> applied,
                          Connection connection) {

    public HookContext {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(dialectId, "dialectId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(plan, "plan");
        applied = List.copyOf(Objects.requireNonNull(applied, "applied"));
        Objects.requireNonNull(connection, "connection");
    }
}
