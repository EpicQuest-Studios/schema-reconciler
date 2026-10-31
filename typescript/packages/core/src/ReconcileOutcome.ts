/**
 * Outcome of a reconcile run (spec 3.5).
 */
export const ReconcileOutcome = {
    UP_TO_DATE: "UP_TO_DATE",
    RECONCILED: "RECONCILED",
    REPORTED_ONLY: "REPORTED_ONLY",
} as const;

export type ReconcileOutcome = (typeof ReconcileOutcome)[keyof typeof ReconcileOutcome];
