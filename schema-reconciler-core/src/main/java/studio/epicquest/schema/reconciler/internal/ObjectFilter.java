package studio.epicquest.schema.reconciler.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.SchemaObject;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * The object filter of {@code Builder.filter}: removes every object the
 * predicate rejects, plus everything the rejected object covers — its
 * parented children, the mirrored annotation kinds ({@code COMMENT},
 * {@code SECURITY_LABEL}, {@code OWNERSHIP}, {@code PRIVILEGES}) placed on
 * it or on one of its children, and the annotation {@code OTHER}s
 * ({@code comment:}/{@code seclabel:}/{@code privileges:}/{@code
 * ownership:}) naming it, and follower sequences whose {@code OWNED BY}
 * dependency names it — and returns a snapshot over the survivors.
 * The filtered snapshot carries a fresh fingerprint, so plan staleness (5.7)
 * is scoped to the visible objects.
 */
final class ObjectFilter {

    private static final Set<ObjectKind> MIRRORED = Set.of(
            ObjectKind.COMMENT, ObjectKind.SECURITY_LABEL,
            ObjectKind.OWNERSHIP, ObjectKind.PRIVILEGES);

    /** OTHER relation prefixes whose rows can own catalog children. */
    private static final Set<String> RELATION_OTHERS = Set.of(
            "inherited", "partindex", "toast", "relation");

    /** OTHER prefixes that name a target — the unsupported shadow forms of
     *  the mirrored kinds ({@code comment:}/{@code seclabel:}/
     *  {@code privileges:}/{@code ownership:}), and {@code constraint:},
     *  whose {@code <parent>.<member>} name carries the relation it
     *  constrains. */
    private static final Set<String> ANNOTATION_OTHERS = Set.of(
            "comment:", "seclabel:", "privileges:", "ownership:",
            "constraint:");

    private ObjectFilter() {
    }

    static SchemaSnapshot apply(SchemaSnapshot snapshot, Predicate<ObjectId> include) {
        final var remaining = new LinkedHashMap<ObjectId, SchemaObject>();
        final var removed = new HashSet<ObjectId>();
        final var removedOwnerNames = new HashSet<String>();
        final var removedTargets = new HashMap<String, Set<String>>();
        final var removedTargetNames = new HashSet<String>();
        for (final var object : snapshot.objects().values()) {
            final var id = object.id();
            if (include.test(id)) {
                remaining.put(id, object);
            } else {
                removed.add(id);
                drop(id, removedOwnerNames, removedTargets, removedTargetNames);
            }
        }
        if (remaining.size() == snapshot.objects().size()) {
            return snapshot;
        }
        // children and mirrored annotations of a removed object go too —
        // transitively, since a removed child can itself own names (a
        // partitioned partition, an inherited: OTHER relation)
        var changed = true;
        while (changed) {
            changed = false;
            for (final var it = remaining.values().iterator(); it.hasNext();) {
                final var object = it.next();
                final var id = object.id();
                if (removedOwnerNames.contains(id.parent().orElse(""))
                        || mirrorsExcluded(id, removedTargets)
                        || annotationOnRemoved(id, removedTargetNames)
                        || followerOf(id, object, removed)) {
                    it.remove();
                    removed.add(id);
                    drop(id, removedOwnerNames, removedTargets,
                            removedTargetNames);
                    changed = true;
                }
            }
        }
        return remaining.size() == snapshot.objects().size()
                ? snapshot : SchemaSnapshot.of(remaining);
    }

    /** A serial-owned sequence follows the relation that owns it — the
     *  {@code OWNED BY} dependency edge is the same covered-child signal
     *  {@code Dialect.coveredChildren} uses. */
    private static boolean followerOf(ObjectId id, SchemaObject object,
                                      Set<ObjectId> removed) {
        if (id.kind() != ObjectKind.SEQUENCE) {
            return false;
        }
        for (final var dep : object.dependsOn()) {
            if (removed.contains(dep)) {
                return true;
            }
        }
        return false;
    }

    /** Records {@code id}'s owned names so children, mirrors and OTHER
     *  annotations are removed. */
    private static void drop(ObjectId id, Set<String> removedOwnerNames,
                             Map<String, Set<String>> removedTargets,
                             Set<String> removedTargetNames) {
        ownerName(id).ifPresent(removedOwnerNames::add);
        removedTargets.computeIfAbsent(id.parent().orElse(""),
                k -> new HashSet<>()).add(id.name());
        removedTargetNames.add(id.parent()
                .map(p -> p + "." + pgName(id)).orElse(pgName(id)));
    }

    /** The catalog name an annotation embeds: an OTHER id's
     *  {@code <prefix>:<name>} reduces to {@code <name>}. */
    private static String pgName(ObjectId id) {
        final var name = id.name();
        if (id.kind() == ObjectKind.OTHER) {
            final var colon = name.indexOf(':');
            return colon > 0 ? name.substring(colon + 1) : name;
        }
        return name;
    }

    /** Whether {@code id} is an annotation-OTHER whose target was removed. */
    private static boolean annotationOnRemoved(ObjectId id,
                                               Set<String> removedTargetNames) {
        if (id.kind() != ObjectKind.OTHER) {
            return false;
        }
        var name = id.name();
        for (final var prefix : ANNOTATION_OTHERS) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            var target = name.substring(prefix.length());
            if (prefix.equals("seclabel:")) {
                // seclabel:<provider>.<target> — the provider is one segment
                final var dot = target.indexOf('.');
                if (dot < 0) {
                    continue;
                }
                target = target.substring(dot + 1);
            } else if (prefix.equals("constraint:")) {
                // constraint:<parent>.<member> — only the parent is a target;
                // stripping further would confuse a member name with a
                // column's qualified name
                final var dot = target.lastIndexOf('.');
                return dot > 0 && removedTargetNames.contains(
                        target.substring(0, dot));
            }
            // trailing .attname segments do not belong to the target
            while (!target.isEmpty()) {
                if (removedTargetNames.contains(target)) {
                    return true;
                }
                final var dot = target.lastIndexOf('.');
                if (dot < 0) {
                    break;
                }
                target = target.substring(0, dot);
            }
            return false;
        }
        return false;
    }

    /** The name children use to parent on this object, when it can own any. */
    private static Optional<String> ownerName(ObjectId id) {
        return switch (id.kind()) {
            case TABLE, VIEW, MATERIALIZED_VIEW, PARTITION, FOREIGN_TABLE ->
                    Optional.of(id.name());
            case OTHER -> {
                final var name = id.name();
                final var colon = name.indexOf(':');
                yield colon > 0 && RELATION_OTHERS.contains(name.substring(0, colon))
                        ? Optional.of(name.substring(colon + 1))
                        : Optional.<String>empty();
            }
            default -> Optional.empty();
        };
    }

    /** Whether {@code id} is a mirrored annotation on an excluded object. */
    private static boolean mirrorsExcluded(ObjectId id,
                                           Map<String, Set<String>> excludedTargets) {
        if (!MIRRORED.contains(id.kind())) {
            return false;
        }
        final var names = excludedTargets.get(id.parent().orElse(""));
        return names != null && names.contains(targetName(id));
    }

    /**
     * The annotated object's name inside a mirrored id: the {@code " (KIND)"}
     * collision suffix and a security label's {@code provider:} prefix do not
     * belong to it.
     */
    private static String targetName(ObjectId id) {
        var name = id.name();
        final var paren = name.indexOf(" (");
        if (paren >= 0) {
            name = name.substring(0, paren);
        }
        if (id.kind() == ObjectKind.SECURITY_LABEL) {
            // the provider is the segment before the first ':'
            final var colon = name.indexOf(':');
            if (colon >= 0) {
                name = name.substring(colon + 1);
            }
        }
        return name;
    }
}
