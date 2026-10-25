package studio.epicquest.schema.reconciler;

/** Base class of all unchecked exceptions thrown by the reconciler. */
public class SchemaReconcilerException extends RuntimeException {

    public SchemaReconcilerException(String message) {
        super(message);
    }

    public SchemaReconcilerException(String message, Throwable cause) {
        super(message, cause);
    }
}
