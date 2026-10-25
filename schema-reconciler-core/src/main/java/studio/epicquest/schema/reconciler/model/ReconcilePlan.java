package studio.epicquest.schema.reconciler.model;

import java.util.List;
import java.util.Objects;
import studio.epicquest.schema.reconciler.internal.PlanRenderer;

/**
 * An inspectable plan (spec 4.4). {@link #toSql()} serialises it as plain text:
 * a deterministic header plus one comment block per change; the output is
 * byte-identical for identical input (spec 5.2).
 */
public record ReconcilePlan(
        String targetNamespace,
        String dialectId,
        String actualFingerprint,
        String desiredFingerprint,
        List<Difference> differences,
        List<PlannedChange> changes) {

    public ReconcilePlan {
        Objects.requireNonNull(targetNamespace, "targetNamespace");
        Objects.requireNonNull(dialectId, "dialectId");
        Objects.requireNonNull(actualFingerprint, "actualFingerprint");
        Objects.requireNonNull(desiredFingerprint, "desiredFingerprint");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public String toSql() {
        return PlanRenderer.render(this);
    }
}
