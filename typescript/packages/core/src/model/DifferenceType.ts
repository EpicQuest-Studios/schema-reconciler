/**
 * Kind of difference between desired and actual state (spec 4.3).
 */
export const DifferenceType = {
    MISSING: "MISSING",
    DRIFTED: "DRIFTED",
    UNEXPECTED: "UNEXPECTED",
    UNSUPPORTED: "UNSUPPORTED",
} as const;

export type DifferenceType = (typeof DifferenceType)[keyof typeof DifferenceType];
