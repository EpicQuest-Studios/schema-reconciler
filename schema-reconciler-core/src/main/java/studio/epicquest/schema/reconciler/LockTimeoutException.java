package studio.epicquest.schema.reconciler;

/** The namespace lock was not acquired within {@code lockTimeout} (spec 7). */
public class LockTimeoutException extends SchemaReconcilerException {

    public LockTimeoutException(String message) {
        super(message);
    }

    public LockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
