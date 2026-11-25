package studio.epicquest.schema.reconciler;

/** The action a {@link SchemaHook} performs. */
@FunctionalInterface
public interface HookAction {
    void run(HookContext context) throws Exception;
}
