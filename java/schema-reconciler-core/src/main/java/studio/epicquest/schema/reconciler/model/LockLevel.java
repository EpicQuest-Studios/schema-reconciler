package studio.epicquest.schema.reconciler.model;

/**
 * The PostgreSQL lock mode a {@link PlannedChange}'s statements take on
 * the table they touch (spec 4.4). Ordered weakest to strongest;
 * {@link #NONE} means no lock beyond statement scope.
 */
public enum LockLevel {
    NONE,
    SHARE_UPDATE_EXCLUSIVE,
    SHARE,
    SHARE_ROW_EXCLUSIVE,
    ACCESS_EXCLUSIVE
}
