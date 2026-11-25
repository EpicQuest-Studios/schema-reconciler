package studio.epicquest.schema.reconciler.internal;

import java.util.List;
import java.util.function.BiPredicate;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/** Actual-only suppression of externally managed runtime partitions (spec 5.5). */
final class ExternalPartitions {

    private ExternalPartitions() {
    }

    static boolean active(List<BiPredicate<String, String>> rules) {
        return rules != null && !rules.isEmpty();
    }

    /** Hides matching actual partitions, cascading through {@link ObjectFilter}. */
    static SchemaSnapshot suppress(SchemaSnapshot actual, SchemaSnapshot desired,
                                   List<BiPredicate<String, String>> rules,
                                   boolean requireDesiredAncestor) {
        if (!active(rules)) {
            return actual;
        }
        return ObjectFilter.apply(actual, id -> {
            if (id.kind() != ObjectKind.PARTITION || id.parent().isEmpty()
                    || !matches(id, rules)) {
                return true;
            }
            if (!requireDesiredAncestor) {
                return false;
            }
            return desired.objects().containsKey(id)
                    || !hasDesiredPartitionedAncestor(id, actual, desired);
        });
    }

    private static boolean matches(ObjectId id, List<BiPredicate<String, String>> rules) {
        for (final var rule : rules) {
            if (rule.test(id.parent().orElseThrow(), id.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the partition's direct partitioned-parent chain reaches an
     * ancestor declared in desired. Walking through actual preserves a
     * sub-partitioned hierarchy even when intermediate partitions are hidden.
     */
    private static boolean hasDesiredPartitionedAncestor(ObjectId partition,
                                                         SchemaSnapshot actual,
                                                         SchemaSnapshot desired) {
        var parentName = partition.parent().orElse("");
        var directParent = true;
        final var seen = new java.util.HashSet<String>();
        while (seen.add(parentName) && !parentName.isEmpty()) {
            final var table = new ObjectId(ObjectKind.TABLE, parentName);
            if (desired.objects().containsKey(table)) {
                return true;
            }
            ObjectId actualParent = actual.objects().containsKey(table) ? table : null;
            if (actualParent == null) {
                for (final var object : actual.objects().values()) {
                    if (object.id().kind() == ObjectKind.PARTITION
                            && object.id().name().equals(parentName)) {
                        actualParent = object.id();
                        break;
                    }
                }
            }
            if (actualParent == null) {
                return false;
            }
            if ((directParent || actualParent.kind() == ObjectKind.TABLE)
                    && desired.objects().containsKey(actualParent)) {
                return true;
            }
            if (actualParent.kind() != ObjectKind.PARTITION || actualParent.parent().isEmpty()) {
                return false;
            }
            directParent = false;
            parentName = actualParent.parent().orElseThrow();
        }
        return false;
    }
}
