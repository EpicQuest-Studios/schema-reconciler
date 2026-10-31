/**
 * Safety class of a {@link PlannedChange} (spec 5.6).
 */
export const ChangeSafety = {
    ADDITIVE: "ADDITIVE",
    REBUILD: "REBUILD",
    DESTRUCTIVE: "DESTRUCTIVE",
} as const;

export type ChangeSafety = (typeof ChangeSafety)[keyof typeof ChangeSafety];
