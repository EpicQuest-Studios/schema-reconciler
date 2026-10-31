package studio.epicquest.schema.reconciler.model;

/**
 * What a {@link PlannedChange} does (spec 4.4). A {@code DROP} is only ever the
 * first half of a recreate and is always paired with a {@code CREATE} of the
 * same target.
 */
public enum ChangeAction {
    CREATE, ALTER, DROP
}
