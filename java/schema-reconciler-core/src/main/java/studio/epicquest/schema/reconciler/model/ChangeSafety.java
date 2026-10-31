package studio.epicquest.schema.reconciler.model;

/**
 * Safety class of a {@link PlannedChange} (spec 5.6).
 */
public enum ChangeSafety {
    ADDITIVE, REBUILD, DESTRUCTIVE
}
