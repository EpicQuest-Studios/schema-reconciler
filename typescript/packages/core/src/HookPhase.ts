/** The lifecycle phase a schema hook runs in (spec 3.8). */
export type HookPhase = "PRE_APPLY" | "POST_APPLY" | "POST_COMMIT";

/** Deterministic phase order used to sort hooks. */
export function hookPhaseOrdinal(phase: HookPhase): number {
    return phase === "PRE_APPLY" ? 0 : phase === "POST_APPLY" ? 1 : 2;
}
