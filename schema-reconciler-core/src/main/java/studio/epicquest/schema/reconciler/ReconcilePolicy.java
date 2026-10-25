package studio.epicquest.schema.reconciler;

import java.util.Objects;
import studio.epicquest.schema.reconciler.model.ChangeSafety;

/**
 * Policy governing what a plan may do with each safety class of change, and
 * with {@code UNSUPPORTED} differences (spec 3.3). {@code unsupported} governs
 * differences, not changes; {@code APPLY} is not permitted for it.
 */
public record ReconcilePolicy(
        Decision additive,
        Decision rebuild,
        Decision destructive,
        Decision unsupported) {

    public ReconcilePolicy {
        Objects.requireNonNull(additive, "additive");
        Objects.requireNonNull(rebuild, "rebuild");
        Objects.requireNonNull(destructive, "destructive");
        Objects.requireNonNull(unsupported, "unsupported");
        if (unsupported == Decision.APPLY) {
            throw new IllegalArgumentException("unsupported must not be APPLY");
        }
    }

    public static ReconcilePolicy defaults() {
        return new ReconcilePolicy(Decision.APPLY, Decision.APPLY, Decision.FAIL, Decision.FAIL);
    }

    public static ReconcilePolicy reportOnly() {
        return new ReconcilePolicy(Decision.REPORT, Decision.REPORT, Decision.REPORT, Decision.REPORT);
    }

    public ReconcilePolicy withAdditive(Decision decision) {
        return new ReconcilePolicy(decision, rebuild, destructive, unsupported);
    }

    public ReconcilePolicy withRebuild(Decision decision) {
        return new ReconcilePolicy(additive, decision, destructive, unsupported);
    }

    public ReconcilePolicy withDestructive(Decision decision) {
        return new ReconcilePolicy(additive, rebuild, decision, unsupported);
    }

    public ReconcilePolicy withUnsupported(Decision decision) {
        return new ReconcilePolicy(additive, rebuild, destructive, decision);
    }

    /** The decision governing a change of the given safety class. */
    public Decision decisionFor(ChangeSafety safety) {
        return switch (safety) {
            case ADDITIVE -> additive;
            case REBUILD -> rebuild;
            case DESTRUCTIVE -> destructive;
        };
    }
}
