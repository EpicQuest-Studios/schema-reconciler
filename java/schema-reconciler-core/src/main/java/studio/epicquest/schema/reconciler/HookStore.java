package studio.epicquest.schema.reconciler;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import studio.epicquest.schema.reconciler.model.ObjectId;

/**
 * Durable execution records for checksum-protected {@code runOnce} hooks
 * (spec 3.8). Implementations are called on the run's own connection, so
 * records share the hook's transaction phase.
 */
public interface HookStore {

    /** The recorded checksum of {@code hookId}, empty when it never ran. */
    Optional<String> recordedChecksum(Connection conn, String namespace, String hookId)
            throws SQLException;

    /** Records a successful hook execution. */
    void record(Connection conn, String namespace, String hookId, HookPhase phase,
                String checksum) throws SQLException;

    /**
     * The reconciler-managed history table when it lives in {@code targetNamespace};
     * the engine filters it out of both snapshots.
     */
    default Optional<ObjectId> managedTable(String targetNamespace) {
        return Optional.empty();
    }
}
