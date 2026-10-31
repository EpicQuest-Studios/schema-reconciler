/**
 * What a {@link PlannedChange} does (spec 4.4). A {@code DROP} is only ever the
 * first half of a recreate and is always paired with a {@code CREATE} of the
 * same target.
 */
export const ChangeAction = {
    CREATE: "CREATE",
    ALTER: "ALTER",
    DROP: "DROP",
} as const;

export type ChangeAction = (typeof ChangeAction)[keyof typeof ChangeAction];
