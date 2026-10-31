package studio.epicquest.schema.reconciler;

/**
 * The desired script could not be read, substituted or executed in the scratch
 * namespace (spec 7). Includes the engine's error message and position when
 * the driver provides them.
 */
public class DesiredStateException extends SchemaReconcilerException {

    public DesiredStateException(String message) {
        super(message);
    }

    public DesiredStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
