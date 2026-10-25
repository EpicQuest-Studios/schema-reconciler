package studio.epicquest.schema.reconciler.internal;

import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * The deterministic {@code toSql()} rendering of spec 4.6: a header with the
 * plan's context, then each change as an ordered comment followed by its
 * statements. No timestamps — byte-identical runs render byte-identical plans.
 */
public final class PlanRenderer {

    private PlanRenderer() {
    }

    public static String render(ReconcilePlan plan) {
        final var out = new StringBuilder();
        out.append("-- schema reconciler plan\n");
        out.append("-- target namespace: ").append(plan.targetNamespace()).append('\n');
        out.append("-- dialect: ").append(plan.dialectId()).append('\n');
        out.append("-- actual fingerprint: ").append(plan.actualFingerprint()).append('\n');
        out.append("-- desired fingerprint: ").append(plan.desiredFingerprint()).append('\n');
        out.append("-- session context: unqualified statements assume namespace \"")
                .append(plan.targetNamespace()).append("\"\n");
        for (final var change : plan.changes()) {
            out.append("\n-- [").append(change.order()).append("] ")
                    .append(change.action()).append(' ').append(change.target())
                    .append(" (").append(change.safety()).append(')');
            if (change.isManual()) {
                out.append(" MANUAL");
            }
            out.append('\n');
            out.append("-- ").append(change.reason()).append('\n');
            for (final var statement : change.statements()) {
                out.append(statement.strip());
                if (!statement.stripTrailing().endsWith(";")) {
                    out.append(';');
                }
                out.append('\n');
            }
        }
        return out.toString();
    }
}
