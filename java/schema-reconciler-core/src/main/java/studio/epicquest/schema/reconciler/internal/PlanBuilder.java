package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import studio.epicquest.schema.reconciler.PlanningException;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.ChangeSafety;
import studio.epicquest.schema.reconciler.model.Definition;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.ObjectIds;
import studio.epicquest.schema.reconciler.model.ObjectKind;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;

/**
 * Turns differences into an ordered {@link ReconcilePlan}: covered-child
 * suppression (5.8.4), dependent-rebuild expansion (5.8.2, 5.8.3), drop/create
 * pairing (4.4), name-collision detection (5.8.7) and two-phase ordering
 * (5.8.5).
 */
final class PlanBuilder {

    /** Result of plan building: the plan plus covered-child expectations per parent. */
    record Built(ReconcilePlan plan, Map<ObjectId, Map<ObjectId, Definition>> coveredByParent) { }

    private static final Comparator<PlannedChange> TIE = Comparator
            .comparing((PlannedChange c) -> c.target().kind().ordinal())
            .thenComparing(c -> c.target(), ObjectIds.ORDER)
            .thenComparing(c -> c.action() == ChangeAction.ALTER ? 0 : c.action() == ChangeAction.CREATE ? 1 : 2);

    private final Dialect dialect;
    private final String targetNamespace;
    private final SchemaSnapshot actual;
    private final SchemaSnapshot desired;

    PlanBuilder(Dialect dialect, String targetNamespace, SchemaSnapshot actual, SchemaSnapshot desired) {
        this.dialect = dialect;
        this.targetNamespace = targetNamespace;
        this.actual = actual;
        this.desired = desired;
    }

    Built build(List<Difference> differences) {
        final var coveredByParent = coverage(differences);
        final var covered = coveredByParent.values().stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toSet());

        final var changes = new ArrayList<PlannedChange>();
        differences.stream()
                .filter(d -> (d.type() == DifferenceType.MISSING
                        || d.type() == DifferenceType.DRIFTED)
                        && !covered.contains(d.id()))
                .map(this::render)
                .forEach(changes::addAll);
        changes.addAll(tableShapeConflicts(differences));
        changes.removeIf(c -> covered.contains(c.target()));

        addDependentRebuilds(changes, coveredByParent);
        validatePairs(changes);
        checkNameCollisions(changes);
        final var ordered = orderChanges(changes);

        final var visible = differences.stream()
                .filter(d -> !covered.contains(d.id()))
                .toList();
        final var plan = new ReconcilePlan(targetNamespace, dialect.id(),
                actual.fingerprint(), desired.fingerprint(), visible, ordered);
        return new Built(plan, coveredByParent);
    }

    private List<PlannedChange> render(Difference difference) {
        List<PlannedChange> rendered;
        try {
            rendered = dialect.render(difference, desired, targetNamespace);
        } catch (PlanningException e) {
            if (e.getMessage() != null && e.getMessage().contains("namespace '" + targetNamespace + "'")) {
                throw e;
            }
            throw new PlanningException("namespace '" + targetNamespace + "': " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlanningException(
                    "namespace '" + targetNamespace + "': dialect failed to render " + difference.id(), e);
        }
        if (rendered == null || rendered.isEmpty()) {
            throw new PlanningException("namespace '" + targetNamespace
                    + "': dialect rendered no changes for " + difference.id());
        }
        return rendered;
    }

    /** 5.8.4: for each MISSING difference, ask the dialect which children its create covers. */
    private Map<ObjectId, Map<ObjectId, Definition>> coverage(List<Difference> differences) {
        return coverageOf(dialect, targetNamespace, differences, desired);
    }

    /**
     * The covered-children map of 5.8.4 for a given difference list, change
     * list and desired snapshot; used both at plan time and by {@code apply()}
     * when the plan was issued by another engine instance. A recreated
     * object's CREATE half covers the same children a MISSING create does.
     */
    static Map<ObjectId, Map<ObjectId, Definition>> coverageOf(Dialect dialect,
            String targetNamespace, List<Difference> differences,
            SchemaSnapshot desired) {
        return coverageOf(dialect, targetNamespace, differences, List.of(), desired);
    }

    static Map<ObjectId, Map<ObjectId, Definition>> coverageOf(Dialect dialect,
            String targetNamespace, List<Difference> differences,
            List<PlannedChange> changes, SchemaSnapshot desired) {
        final var covered = new LinkedHashMap<ObjectId, Map<ObjectId, Definition>>();
        for (final var d : differences) {
            if (d.type() != DifferenceType.MISSING) {
                continue;
            }
            final var children = dialect.coveredChildren(d, desired);
            for (final var child : children) {
                if (child.equals(d.id())) {
                    continue;
                }
                final var object = desired.objects().get(child);
                if (object == null) {
                    throw new PlanningException("namespace '" + targetNamespace
                            + "': dialect claims " + d.id() + " covers " + child
                            + ", which is not in the desired snapshot");
                }
                covered.computeIfAbsent(d.id(), k -> new LinkedHashMap<>())
                        .put(child, object.definition());
            }
        }
        for (final var c : changes) {
            if (c.action() == ChangeAction.DROP
                    && desired.objects().containsKey(c.target())) {
                coverRecreatedChildren(dialect, c.target(), desired, covered);
            }
        }
        return covered;
    }

    /**
     * Records the children a recreated object's CREATE fragment re-materializes
     * into {@code coveredByParent} — a partition's columns come back with its
     * {@code CREATE}, exactly as a MISSING create's covered children do.
     */
    private static void coverRecreatedChildren(Dialect dialect, ObjectId dropped,
            SchemaSnapshot desired, Map<ObjectId, Map<ObjectId, Definition>> coveredByParent) {
        for (final var child : dialect.coveredChildren(
                new Difference(dropped, DifferenceType.MISSING,
                        Optional.empty(), Optional.empty()), desired)) {
            final var object = desired.objects().get(child);
            if (object != null) {
                coveredByParent.computeIfAbsent(dropped, k -> new LinkedHashMap<>())
                        .put(child, object.definition());
            }
        }
    }

    /** 5.5: an unexpected COLUMN on an expected table is a table-shape conflict. */
    private List<PlannedChange> tableShapeConflicts(List<Difference> differences) {
        return differences.stream()
                .filter(d -> d.type() == DifferenceType.UNEXPECTED
                        && d.id().kind() == ObjectKind.COLUMN
                        && d.id().parent().isPresent())
                .filter(d -> desired.objects().containsKey(
                        new ObjectId(ObjectKind.TABLE, d.id().parent().get())))
                .map(d -> new PlannedChange(0,
                        new ObjectId(ObjectKind.TABLE, d.id().parent().get()),
                        ChangeAction.ALTER, ChangeSafety.DESTRUCTIVE, List.of(),
                        Optional.empty(), "unexpected column " + d.id().name()))
                .toList();
    }

    /**
     * 5.8.2 and 5.8.3: every dependent of a dropped or recreated object is
     * recreated via a synthetic DRIFTED render; dependents outside the desired
     * and actual snapshots, or UNEXPECTED ones, fail planning.
     */
    private void addDependentRebuilds(List<PlannedChange> changes,
                                      Map<ObjectId, Map<ObjectId, Definition>> coveredByParent) {
        final var actualDeps = Dependencies.prerequisitesOf(dialect, actual);
        final var dependents = Dependencies.reverse(actualDeps);

        final var targets = changes.stream().map(PlannedChange::target)
                .collect(Collectors.toCollection(HashSet::new));
        final var extra = new ArrayList<PlannedChange>();
        for (final var change : List.copyOf(changes)) {
            if (change.action() != ChangeAction.DROP) {
                continue;
            }
            final var dropped = change.target();
            // a DROP is always half of a recreate: the object's CREATE
            // fragment re-materializes its covered children, so those
            // dependents need no rebuild of their own — they verify through
            // the parent's covered-children expectations
            if (desired.objects().containsKey(dropped)) {
                coverRecreatedChildren(dialect, dropped, desired, coveredByParent);
                final var covered = coveredByParent.get(dropped);
                if (covered != null) {
                    targets.addAll(covered.keySet());
                }
            }
            for (final var dependent : Dependencies.reachable(dropped, dependents)) {
                if (dependent.equals(dropped) || targets.contains(dependent)) {
                    continue;
                }
                final var have = actual.objects().get(dependent);
                if (have == null) {
                    continue;
                }
                final var want = desired.objects().get(dependent);
                if (want == null) {
                    throw new PlanningException("namespace '" + targetNamespace + "': "
                            + dependent + " depends on " + dropped
                            + " but is not part of the desired state");
                }
                targets.add(dependent);
                final var synthetic = new Difference(dependent, DifferenceType.DRIFTED,
                        Optional.of(want.definition()), Optional.of(have.definition()));
                for (final var c : render(synthetic)) {
                    extra.add(new PlannedChange(0, c.target(), c.action(), c.safety(),
                            c.statements(), c.expected(), "dependency of " + dropped));
                    targets.add(c.target());
                }
            }
        }
        changes.addAll(extra);
    }

    /** 4.4: a DROP is only ever the first half of a recreate, paired with a CREATE. */
    private void validatePairs(List<PlannedChange> changes) {
        final var byTarget = new LinkedHashMap<ObjectId, List<PlannedChange>>();
        changes.forEach(c -> byTarget.computeIfAbsent(c.target(), k -> new ArrayList<>()).add(c));
        for (final var e : byTarget.entrySet()) {
            final var drops = e.getValue().stream().filter(c -> c.action() == ChangeAction.DROP).count();
            if (drops > 0) {
                final var creates = e.getValue().stream()
                        .filter(c -> c.action() == ChangeAction.CREATE).count();
                if (drops != creates) {
                    throw new PlanningException("namespace '" + targetNamespace + "': " + e.getKey()
                            + " has " + drops + " DROP change(s) but " + creates
                            + " CREATE change(s); a DROP is only ever half of a recreate");
                }
                final var safeties = e.getValue().stream().map(PlannedChange::safety)
                        .collect(Collectors.toSet());
                final var reasons = e.getValue().stream().map(PlannedChange::reason)
                        .collect(Collectors.toSet());
                if (safeties.size() != 1) {
                    throw new PlanningException("namespace '" + targetNamespace + "': the DROP and "
                            + "CREATE halves of " + e.getKey() + " have different safety classes");
                }
                if (reasons.size() != 1) {
                    throw new PlanningException("namespace '" + targetNamespace + "': the DROP and "
                            + "CREATE halves of " + e.getKey() + " have different reasons");
                }
            }
        }
    }

    /** 5.8.7: a CREATE must not collide on a name with anything not being dropped. */
    private void checkNameCollisions(List<PlannedChange> changes) {
        final var dropped = changes.stream()
                .filter(c -> c.action() == ChangeAction.DROP)
                .map(PlannedChange::target)
                .collect(Collectors.toSet());

        final var actualByScope = new HashMap<String, List<ObjectId>>();
        for (final var have : actual.objects().values()) {
            for (final var scope : dialect.nameScopes(have.id())) {
                actualByScope.computeIfAbsent(scope, k -> new ArrayList<>()).add(have.id());
            }
        }
        final var createScopes = new HashMap<String, ObjectId>();
        for (final var change : changes) {
            if (change.action() != ChangeAction.CREATE) {
                continue;
            }
            final var scopes = dialect.nameScopes(change.target());
            for (final var scope : scopes) {
                final var earlier = createScopes.putIfAbsent(scope, change.target());
                if (earlier != null && !earlier.equals(change.target())) {
                    throw new PlanningException("namespace '" + targetNamespace + "': "
                            + change.target() + " collides with " + earlier
                            + " in name scope '" + scope + "'");
                }
            }
            for (final var scope : scopes) {
                for (final var have : actualByScope.getOrDefault(scope, List.of())) {
                    if (!have.equals(change.target()) && !dropped.contains(have)) {
                        throw new PlanningException("namespace '" + targetNamespace + "': "
                                + change.target() + " collides with " + have
                                + " in name scope '" + scope + "'");
                    }
                }
            }
        }
    }

    /**
     * 5.8.5: all DROP changes in reverse dependency order of the actual
     * snapshot, then all CREATE and ALTER changes in dependency order of the
     * desired snapshot.
     */
    private List<PlannedChange> orderChanges(List<PlannedChange> changes) {
        final var desiredDeps = Dependencies.prerequisitesOf(dialect, desired);
        Map<ObjectId, Set<ObjectId>> actualDependents =
                Dependencies.reverse(Dependencies.prerequisitesOf(dialect, actual));

        final var drops = changes.stream()
                .filter(c -> c.action() == ChangeAction.DROP).toList();
        final var creates = changes.stream()
                .filter(c -> c.action() != ChangeAction.DROP).toList();

        final var ordered = new ArrayList<PlannedChange>();
        ordered.addAll(topologicalOrder(drops, actualDependents));
        ordered.addAll(topologicalOrder(creates, desiredDeps));
        return IntStream.range(0, ordered.size())
                .mapToObj(i -> {
                    final var c = ordered.get(i);
                    return new PlannedChange(i + 1, c.target(), c.action(), c.safety(),
                            c.statements(), c.expected(), c.reason());
                })
                .toList();
    }

    /**
     * Orders changes so that each target comes after the targets in {@code edges}
     * it can reach: for creates, its prerequisites (desired graph); for drops,
     * its dependents (reversed actual graph). Ties per 5.8.5.
     */
    private List<PlannedChange> topologicalOrder(
            List<PlannedChange> changes, Map<ObjectId, Set<ObjectId>> edges) {
        final var targets = changes.stream().map(PlannedChange::target)
                .collect(Collectors.toSet());

        final var predecessors = new LinkedHashMap<PlannedChange, Set<ObjectId>>();
        final var dependentsOfTarget = new HashMap<ObjectId, List<PlannedChange>>();
        for (final var c : changes) {
            final var prereqs = new HashSet<ObjectId>(Dependencies.reachable(c.target(), edges));
            prereqs.retainAll(targets);
            predecessors.put(c, prereqs);
            for (final var prereq : prereqs) {
                dependentsOfTarget.computeIfAbsent(prereq, k -> new ArrayList<>()).add(c);
            }
        }

        final var ready = new PriorityQueue<PlannedChange>(TIE);
        predecessors.forEach((c, prereqs) -> {
            if (prereqs.isEmpty()) {
                ready.add(c);
            }
        });
        final var out = new ArrayList<PlannedChange>();
        while (!ready.isEmpty()) {
            final var c = ready.poll();
            out.add(c);
            for (final var dependent : dependentsOfTarget.getOrDefault(c.target(), List.of())) {
                if (dependent != c && predecessors.get(dependent).remove(c.target())
                        && predecessors.get(dependent).isEmpty()) {
                    ready.add(dependent);
                }
            }
        }
        if (out.size() < changes.size()) {
            final var cycle = predecessors.entrySet().stream()
                    .filter(e -> !e.getValue().isEmpty()).map(e -> e.getKey().target()).toList();
            throw new PlanningException(
                    "namespace '" + targetNamespace + "': dependency cycle involving " + cycle);
        }
        return out;
    }
}
