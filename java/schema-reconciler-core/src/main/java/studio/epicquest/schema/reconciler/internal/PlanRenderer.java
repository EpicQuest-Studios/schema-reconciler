package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayList;
import studio.epicquest.schema.reconciler.model.LockLevel;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;

/**
 * The deterministic {@code toSql()} rendering of spec 4.4: a header with the
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
            final var hazard = change.hazard();
            if (change.nonTransactional() || !hazard.isTrivial()) {
                final var parts = new ArrayList<String>();
                if (hazard.lockLevel() != LockLevel.NONE) {
                    parts.add("lock " + hazard.lockLevel());
                }
                if (hazard.rewritesTable()) {
                    parts.add("rewrites table");
                }
                if (hazard.validatesRows()) {
                    parts.add("validates rows");
                }
                if (change.nonTransactional()) {
                    parts.add("non-transactional");
                }
                parts.addAll(hazard.notes());
                out.append("; hazard: ").append(String.join("; ", parts));
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
