package studio.epicquest.schema.reconciler;

/** The lifecycle phase a {@link SchemaHook} runs in (spec 3.8). */
public enum HookPhase {
    /** Inside the main transaction, before the plan's transactional DDL. */
    PRE_APPLY,
    /** Inside the main transaction, after the plan's transactional DDL and before verification. */
    POST_APPLY,
    /** After the transaction commits and after deferred non-transactional changes. */
    POST_COMMIT
}
