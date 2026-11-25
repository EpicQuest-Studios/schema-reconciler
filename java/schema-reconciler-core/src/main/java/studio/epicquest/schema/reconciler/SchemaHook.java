package studio.epicquest.schema.reconciler;

import java.util.Objects;

/**
 * An ordered lifecycle hook of spec 3.8. Hooks sort deterministically by
 * phase, then {@link #order()}, then {@link #id()}.
 */
public interface SchemaHook {

    String id();

    HookPhase phase();

    default int order() {
        return 0;
    }

    default boolean runOnce() {
        return false;
    }

    default String checksum() {
        return "";
    }

    void run(HookContext context) throws Exception;

    /** A hook with order zero that runs on every apply. */
    static SchemaHook of(String id, HookPhase phase, HookAction action) {
        return create(id, phase, 0, false, "", action);
    }

    /** A hook with an explicit ordering key that runs on every apply. */
    static SchemaHook of(String id, HookPhase phase, int order, HookAction action) {
        return create(id, phase, order, false, "", action);
    }

    /** A checksum-protected hook that runs at most once through a {@link HookStore}. */
    static SchemaHook runOnce(String id, HookPhase phase, String checksum, HookAction action) {
        return create(id, phase, 0, true, checksum, action);
    }

    /** A checksum-protected hook with an explicit ordering key. */
    static SchemaHook runOnce(String id, HookPhase phase, int order, String checksum,
                              HookAction action) {
        return create(id, phase, order, true, checksum, action);
    }

    private static SchemaHook create(String id, HookPhase phase, int order,
                                     boolean runOnce, String checksum, HookAction action) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(action, "action");
        return new SchemaHook() {
            @Override public String id() { return id; }
            @Override public HookPhase phase() { return phase; }
            @Override public int order() { return order; }
            @Override public boolean runOnce() { return runOnce; }
            @Override public String checksum() { return checksum; }
            @Override public void run(HookContext context) throws Exception { action.run(context); }
        };
    }
}
