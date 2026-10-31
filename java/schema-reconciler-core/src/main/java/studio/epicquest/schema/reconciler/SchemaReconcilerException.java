package studio.epicquest.schema.reconciler;

/** Base class of all unchecked exceptions thrown by the reconciler. */
public class SchemaReconcilerException extends RuntimeException {

    private volatile RunTimings timings = RunTimings.unmeasured();

    public SchemaReconcilerException(String message) {
        super(message);
    }

    public SchemaReconcilerException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The phases the failed run completed before throwing; zero for phases it
     * never reached. Always present — {@link RunTimings#unmeasured()} when
     * the failure predates the run's instrumentation.
     */
    public RunTimings timings() {
        return timings;
    }

    /** Attaches run timings to this failure; returns the same exception. */
    public SchemaReconcilerException withTimings(RunTimings timings) {
        this.timings = timings;
        return this;
    }
}
