/**
 * Policy decision for one safety class (spec 3.3).
 */
export const Decision = {
    APPLY: "APPLY",
    REPORT: "REPORT",
    FAIL: "FAIL",
} as const;

export type Decision = (typeof Decision)[keyof typeof Decision];
