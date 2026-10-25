package studio.epicquest.schema.reconciler.model;

/**
 * Kind of difference between desired and actual state (spec 4.3).
 */
public enum DifferenceType {
    MISSING, DRIFTED, UNEXPECTED, UNSUPPORTED
}
