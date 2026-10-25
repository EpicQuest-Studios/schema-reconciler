package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Computes the differences of spec 5.4 and 5.5 between the actual and desired
 * snapshots, sorted by {@link ObjectIds#ORDER}.
 */
final class Differ {

    private Differ() {
    }

    static List<Difference> diff(SchemaSnapshot actual, SchemaSnapshot desired, Dialect dialect) {
        final var out = new ArrayList<Difference>();
        for (final var want : sorted(desired)) {
            final var id = want.id();
            final var have = actual.objects().get(id);
            if (!dialect.capabilities().supportedKinds().contains(id.kind())) {
                out.add(new Difference(id, DifferenceType.UNSUPPORTED,
                        Optional.of(want.definition()), definitionOf(have)));
            } else if (have == null) {
                out.add(new Difference(id, DifferenceType.MISSING,
                        Optional.of(want.definition()), Optional.empty()));
            } else if (!have.definition().equals(want.definition())) {
                out.add(new Difference(id, DifferenceType.DRIFTED,
                        Optional.of(want.definition()), Optional.of(have.definition())));
            }
        }
        sorted(actual).stream()
                .filter(have -> !desired.objects().containsKey(have.id())
                        && !isExternalDependent(have))
                .map(have -> new Difference(have.id(), DifferenceType.UNEXPECTED,
                        Optional.empty(), Optional.of(have.definition())))
                .forEach(out::add);
        out.sort((a, b) -> ObjectIds.ORDER.compare(a.id(), b.id()));
        return out;
    }

    private static boolean isExternalDependent(SchemaObject object) {
        return "true".equals(object.attributes().get(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE));
    }

    private static Optional<studio.epicquest.schema.reconciler.model.Definition> definitionOf(SchemaObject o) {
        return o == null ? Optional.empty() : Optional.of(o.definition());
    }

    private static List<SchemaObject> sorted(SchemaSnapshot snapshot) {
        return snapshot.objects().values().stream()
                .sorted((a, b) -> ObjectIds.ORDER.compare(a.id(), b.id()))
                .toList();
    }
}
