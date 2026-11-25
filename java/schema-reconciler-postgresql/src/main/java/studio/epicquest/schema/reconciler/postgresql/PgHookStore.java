package studio.epicquest.schema.reconciler.postgresql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import studio.epicquest.schema.reconciler.HookPhase;
import studio.epicquest.schema.reconciler.HookStore;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.postgresql.internal.PgIdentifiers;

/**
 * PostgreSQL's durable {@link HookStore}: one versioned history table keyed by
 * {@code (namespace, hook_id)}. The table is created lazily and idempotently;
 * lookups and records use parameterized SQL.
 */
public final class PgHookStore implements HookStore {

    /** The default history table name. */
    public static final String DEFAULT_TABLE = "schema_reconciler_hooks";

    private final String schema;
    private final String table;

    private PgHookStore(String schema, String table) {
        this.schema = requireName(schema, "schema");
        this.table = requireName(table, "table");
    }

    /** A store using {@link #DEFAULT_TABLE} in {@code schema}. */
    public static PgHookStore inSchema(String schema) {
        return new PgHookStore(schema, DEFAULT_TABLE);
    }

    /** A store using an explicit table in {@code schema}. */
    public static PgHookStore of(String schema, String table) {
        return new PgHookStore(schema, table);
    }

    private static String requireName(String value, String property) {
        Objects.requireNonNull(value, property);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(property + " must not be empty");
        }
        return value;
    }

    @Override
    public Optional<String> recordedChecksum(Connection conn, String namespace, String hookId)
            throws SQLException {
        ensureTable(conn);
        try (var ps = conn.prepareStatement("SELECT checksum FROM " + qualified()
                + " WHERE namespace = ? AND hook_id = ?")) {
            ps.setString(1, namespace);
            ps.setString(2, hookId);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    @Override
    public void record(Connection conn, String namespace, String hookId, HookPhase phase,
                       String checksum) throws SQLException {
        ensureTable(conn);
        try (var ps = conn.prepareStatement("INSERT INTO " + qualified()
                + " (namespace, hook_id, phase, checksum) VALUES (?, ?, ?, ?)"
                + " ON CONFLICT (namespace, hook_id) DO NOTHING")) {
            ps.setString(1, namespace);
            ps.setString(2, hookId);
            ps.setString(3, phase.name());
            ps.setString(4, checksum);
            ps.executeUpdate();
        }
    }

    @Override
    public Optional<ObjectId> managedTable(String targetNamespace) {
        return schema.equals(targetNamespace)
                ? Optional.of(new ObjectId(ObjectKind.TABLE, table))
                : Optional.empty();
    }

    private void ensureTable(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + qualified() + " ("
                    + "namespace text NOT NULL,"
                    + "hook_id text NOT NULL,"
                    + "phase text NOT NULL,"
                    + "checksum text NOT NULL,"
                    + "applied_at timestamptz NOT NULL DEFAULT now(),"
                    + "PRIMARY KEY (namespace, hook_id))");
        }
    }

    private String qualified() {
        return PgIdentifiers.quote(schema) + "." + PgIdentifiers.quote(table);
    }
}
