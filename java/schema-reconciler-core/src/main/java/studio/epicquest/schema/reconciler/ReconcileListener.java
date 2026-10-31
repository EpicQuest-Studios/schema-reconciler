package studio.epicquest.schema.reconciler;

import java.time.Duration;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * Callbacks on the reconciling thread (spec 3.6). A thrown exception aborts the
 * run as {@link ApplyException}; in transactional dialects everything rolls back.
 */
public interface ReconcileListener {

    ReconcileListener NONE = new ReconcileListener() { };

    default void planned(ReconcilePlan plan) { }

    default void applying(PlannedChange change) { }

    default void applied(PlannedChange change, Duration elapsed) { }

    default void reported(PlannedChange change) { }
}
