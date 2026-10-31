package studio.epicquest.schema.reconciler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.ObjectKind;

/**
 * Policy governing what a plan may do with each safety class of change, and
 * with {@code UNSUPPORTED} differences (spec 3.3). {@code unsupported} governs
 * differences, not changes; {@code APPLY} is not permitted for it.
 * {@code unsupportedCategories} overrides {@code unsupported} per difference
 * category: for {@link ObjectKind#OTHER} objects the category is the dialect's
 * name prefix before {@code ':'} (for example {@code "extension"},
 * {@code "comment"}, {@code "policy"}; a name without a prefix is
 * {@code "relation"}), for other kinds it is the lowercased
 * {@link ObjectKind} name.
 */
public record ReconcilePolicy(
        Decision additive,
        Decision rebuild,
        Decision destructive,
        Decision unsupported,
        Map<String, Decision> unsupportedCategories) {

    public ReconcilePolicy {
        Objects.requireNonNull(additive, "additive");
        Objects.requireNonNull(rebuild, "rebuild");
        Objects.requireNonNull(destructive, "destructive");
        Objects.requireNonNull(unsupported, "unsupported");
        if (unsupported == Decision.APPLY) {
            throw new IllegalArgumentException("unsupported must not be APPLY");
        }
        unsupportedCategories = Map.copyOf(
                Objects.requireNonNull(unsupportedCategories, "unsupportedCategories"));
        if (unsupportedCategories.containsValue(Decision.APPLY)) {
            throw new IllegalArgumentException("unsupported categories must not be APPLY");
        }
    }

    public ReconcilePolicy(Decision additive, Decision rebuild, Decision destructive,
                           Decision unsupported) {
        this(additive, rebuild, destructive, unsupported, Map.of());
    }

    public static ReconcilePolicy defaults() {
        return new ReconcilePolicy(Decision.APPLY, Decision.APPLY, Decision.FAIL, Decision.FAIL);
    }

    public static ReconcilePolicy reportOnly() {
        return new ReconcilePolicy(Decision.REPORT, Decision.REPORT, Decision.REPORT, Decision.REPORT);
    }

    public ReconcilePolicy withAdditive(Decision decision) {
        return new ReconcilePolicy(decision, rebuild, destructive, unsupported,
                unsupportedCategories);
    }

    public ReconcilePolicy withRebuild(Decision decision) {
        return new ReconcilePolicy(additive, decision, destructive, unsupported,
                unsupportedCategories);
    }

    public ReconcilePolicy withDestructive(Decision decision) {
        return new ReconcilePolicy(additive, rebuild, decision, unsupported,
                unsupportedCategories);
    }

    public ReconcilePolicy withUnsupported(Decision decision) {
        return new ReconcilePolicy(additive, rebuild, destructive, decision,
                unsupportedCategories);
    }

    /** Overrides the {@code unsupported} decision for one difference category. */
    public ReconcilePolicy withUnsupportedCategory(String category, Decision decision) {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(decision, "decision");
        final var categories = new HashMap<>(unsupportedCategories);
        categories.put(category, decision);
        return new ReconcilePolicy(additive, rebuild, destructive, unsupported,
                Map.copyOf(categories));
    }

    /** The decision governing a change of the given safety class. */
    public Decision decisionFor(ChangeSafety safety) {
        return switch (safety) {
            case ADDITIVE -> additive;
            case REBUILD -> rebuild;
            case DESTRUCTIVE -> destructive;
        };
    }

    /** The decision governing an {@code UNSUPPORTED} difference, per category override. */
    public Decision unsupportedDecision(Difference difference) {
        return unsupportedCategories.getOrDefault(
                categoryOf(difference), unsupported);
    }

    /** The policy category of a difference's object: OTHER name prefix, else kind name. */
    private static String categoryOf(Difference difference) {
        final var id = difference.id();
        if (id.kind() == ObjectKind.OTHER) {
            final var colon = id.name().indexOf(':');
            return colon < 0 ? "relation" : id.name().substring(0, colon);
        }
        return id.kind().name().toLowerCase(Locale.ROOT);
    }
}
