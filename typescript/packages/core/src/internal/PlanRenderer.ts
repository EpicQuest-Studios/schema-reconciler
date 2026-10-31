import type { ReconcilePlan } from "../model/ReconcilePlan.ts";

/**
 * The deterministic {@code toSql()} rendering of spec 4.4: a header with the
 * plan's context, then each change as an ordered comment followed by its
 * statements. No timestamps — byte-identical runs render byte-identical plans.
 */
export const PlanRenderer = {
    render(plan: ReconcilePlan): string {
        const out: string[] = [];
        out.push("-- schema reconciler plan\n");
        out.push("-- target namespace: " + plan.targetNamespace + "\n");
        out.push("-- dialect: " + plan.dialectId + "\n");
        out.push("-- actual fingerprint: " + plan.actualFingerprint + "\n");
        out.push("-- desired fingerprint: " + plan.desiredFingerprint + "\n");
        out.push("-- session context: unqualified statements assume namespace \""
                + plan.targetNamespace + "\"\n");
        for (const change of plan.changes) {
            out.push("\n-- [" + change.order + "] "
                    + change.action + " " + change.target
                    + " (" + change.safety + ")");
            if (change.isManual) {
                out.push(" MANUAL");
            }
            out.push("\n");
            out.push("-- " + change.reason + "\n");
            for (const statement of change.statements) {
                out.push(statement.trim());
                if (!statement.trimEnd().endsWith(";")) {
                    out.push(";");
                }
                out.push("\n");
            }
        }
        return out.join("");
    },
} as const;
