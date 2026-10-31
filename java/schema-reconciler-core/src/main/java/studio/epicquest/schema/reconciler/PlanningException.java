package studio.epicquest.schema.reconciler;

/**
 * A dependency cycle, external or unexpected dependent, name collision, or an
 * unrenderable difference (spec 7).
 */
public class PlanningException extends SchemaReconcilerException {

    public PlanningException(String message) {
        super(message);
    }

    public PlanningException(String message, Throwable cause) {
        super(message, cause);
    }
}
