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
import studio.epicquest.schema.reconciler.model.RenameHint;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;
import studio.epicquest.schema.reconciler.spi.Dialect;
import studio.epicquest.schema.reconciler.spi.RenderOptions;

/**
 * Turns differences into an ordered {@link ReconcilePlan}: covered-child
 * suppression (5.8.4), dependent-rebuild expansion (5.8.2, 5.8.3), drop/create
 * pairing (4.4), name-collision detection (5.8.7) and two-phase ordering
 * (5.8.5). With {@code prune} on, UNEXPECTED objects produce standalone
 * DROPs (5.5); with rename hints, matching from/to pairs collapse to an
 * {@code ALTER ... RENAME} plus a drift fix when the definition moved.
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
    private final boolean prune;
    private final List<RenameHint> renameHints;
    private final RenderOptions options;
    private Map<String, String> renamedParents = Map.of();

    PlanBuilder(Dialect dialect, String targetNamespace, SchemaSnapshot actual, SchemaSnapshot desired) {
        this(dialect, targetNamespace, actual, desired, false, List.of(),
                RenderOptions.DEFAULT);
    }

    PlanBuilder(Dialect dialect, String targetNamespace, SchemaSnapshot actual,
                SchemaSnapshot desired, boolean prune, List<RenameHint> renameHints,
                RenderOptions options) {
        this.dialect = dialect;
        this.targetNamespace = targetNamespace;
        this.actual = actual;
        this.desired = desired;
        this.prune = prune;
        this.renameHints = renameHints;
        this.options = options;
    }

    Built build(List<Difference> differences) {
        final var coveredByParent = coverage(differences);
        final var covered = coveredByParent.values().stream()
                .flatMap(m -> m.keySet().stream())
                .collect(Collectors.toCollection(HashSet::new));

        // active rename hints hide the UNEXPECTED(from)/MISSING(to) pair
        // (and the unexpected-column table-shape conflict) behind one
        // RENAME change plus an optional drift fix (5.5.1)
        final var renamed = activeRenames();
        for (final var hint : renamed) {
            covered.add(hint.from());
            covered.add(hint.to());
        }
        // prune: an UNEXPECTED object dropped because another UNEXPECTED
        // object it depends on is dropped needs no DROP of its own —
        // the parent's drop takes it down (indexes and constraints on a
        // dropped table, owned sequences, mirrored annotations)
        final var pruned = prune ? suppressedUnexpected(differences) : Set.<ObjectId>of();

        // a parent rename carries its same-named children along: their
        // UNEXPECTED(old.x)/MISSING(new.x) pairs are the same object with
        // a moved parent, not a drop+create; a def change still gets a
        // DRIFTED fix on the new id. Children dropped under prune with
        // no desired counterpart render through the NEW parent name —
        // the old one is gone once the rename ran (5.5.1)
        final var carried = carriedChildren(renamed);
        covered.addAll(carried.suppressed());
        this.renamedParents = carried.parentNames();

        final var changes = new ArrayList<PlannedChange>();
        for (final var drift : carried.drift()) {
            changes.addAll(render(drift));
        }
        for (final var d : differences) {
            if (covered.contains(d.id()) || pruned.contains(d.id())) {
                continue;
            }
            if (d.type() == DifferenceType.MISSING || d.type() == DifferenceType.DRIFTED) {
                changes.addAll(render(d));
            } else if (d.type() == DifferenceType.UNEXPECTED && prune
                    && d.id().kind() != ObjectKind.OTHER) {
                changes.addAll(render(d));
            }
        }
        if (!prune) {
            changes.addAll(tableShapeConflicts(differences, covered));
        }
        changes.removeIf(c -> covered.contains(c.target()));

        // rename changes arrive after the covered-children splice: they
        // legitimately target {@code to}, which {@code covered} holds to
        // suppress the MISSING difference — splicing them away with it
        // would silently drop the rename
        final var renameChanges = new ArrayList<PlannedChange>();
        for (final var hint : renamed) {
            final var rendered = renderRename(hint);
            // the ALTER ... RENAME must precede everything else that
            // touches {@code to} — a drift recreate's DROP targets the
            // new name, which only exists after the rename ran
            renameChanges.addAll(rendered.early());
            changes.addAll(rendered.late());
        }

        addDependentRebuilds(changes, coveredByParent, pruned);
        validatePairs(changes);
        checkNameCollisions(changes);
        final var ordered = orderChanges(changes, renameChanges);

        final var visible = differences.stream()
                .filter(d -> !covered.contains(d.id()))
                .toList();
        final var plan = new ReconcilePlan(targetNamespace, dialect.id(),
                actual.fingerprint(), desired.fingerprint(), visible, ordered);
        return new Built(plan, coveredByParent);
    }

    /**
     * The hints that are active in this diff: {@code from} exists in the
     * actual snapshot, {@code to} does not, {@code to} is desired and
     * {@code from} is not. Both sides present in actual is a conflict a
     * rename cannot resolve.
     */
    private List<RenameHint> activeRenames() {
        final var active = new ArrayList<RenameHint>();
        for (final var hint : renameHints) {
            final var haveFrom = actual.objects().containsKey(hint.from());
            final var haveTo = actual.objects().containsKey(hint.to());
            if (haveFrom && haveTo) {
                throw new PlanningException("namespace '" + targetNamespace
                        + "': rename " + hint.from() + " -> " + hint.to()
                        + " is ambiguous, both objects exist");
            }
            if (haveFrom && !haveTo && desired.objects().containsKey(hint.to())
                    && !desired.objects().containsKey(hint.from())) {
                active.add(hint);
            }
        }
        active.sort((a, b) -> ObjectIds.ORDER.compare(a.from(), b.from()));
        return active;
    }

    /**
     * One rename change, then a drift fix on {@code to} when the
     * definition moved. {@code early} runs before all drops (the rename
     * makes {@code to} exist); {@code late} joins the normal phases.
     */
    private RenamedChange renderRename(RenameHint hint) {
        final List<PlannedChange> early;
        try {
            early = dialect.renderRename(hint.from(), hint.to(),
                    actual, desired, targetNamespace);
        } catch (PlanningException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PlanningException("namespace '" + targetNamespace
                    + "': dialect failed to render rename " + hint.from()
                    + " -> " + hint.to(), e);
        }
        if (early == null || early.isEmpty()) {
            throw new PlanningException("namespace '" + targetNamespace
                    + "': dialect rendered no changes for rename " + hint.from()
                    + " -> " + hint.to());
        }
        final var late = new ArrayList<PlannedChange>();
        final var want = desired.objects().get(hint.to());
        final var have = actual.objects().get(hint.from());
        if (!want.definition().equals(have.definition())) {
            final var drift = new Difference(hint.to(), DifferenceType.DRIFTED,
                    Optional.of(want.definition()), Optional.of(have.definition()));
            late.addAll(render(drift));
        }
        return new RenamedChange(early, late);
    }

    private record RenamedChange(List<PlannedChange> early, List<PlannedChange> late) { }

    /**
     * Children of renamed parents: {@code suppressed} holds both sides of
     * each UNEXPECTED(old.x)/MISSING(new.x) pair that the rename carries,
     * {@code drift} the synthetic DRIFTED differences for carried
     * children whose definition also changed, and {@code parentNames}
     * the old-to-new parent name map for prune renders.
     */
    private CarriedChildren carriedChildren(List<RenameHint> renamed) {
        final var suppressed = new HashSet<ObjectId>();
        final var drift = new ArrayList<Difference>();
        final var parentNames = new LinkedHashMap<String, String>();
        for (final var hint : renamed) {
            if (hint.from().parent().isPresent()) {
                continue;
            }
            parentNames.put(hint.from().name(), hint.to().name());
            for (final var have : actual.objects().values()) {
                if (!have.id().parent().orElse("").equals(hint.from().name())) {
                    continue;
                }
                final var wantId = new ObjectId(have.id().kind(), have.id().name(),
                        hint.to().name());
                final var want = desired.objects().get(wantId);
                if (want == null) {
                    continue;
                }
                suppressed.add(have.id());
                suppressed.add(wantId);
                if (!want.definition().equals(have.definition())) {
                    drift.add(new Difference(wantId, DifferenceType.DRIFTED,
                            Optional.of(want.definition()),
                            Optional.of(have.definition())));
                }
            }
        }
        drift.sort((a, b) -> ObjectIds.ORDER.compare(a.id(), b.id()));
        return new CarriedChildren(suppressed, drift, parentNames);
    }

    private record CarriedChildren(Set<ObjectId> suppressed, List<Difference> drift,
                                   Map<String, String> parentNames) { }

    private static final Set<ObjectKind> PRUNE_CONTAINERS = Set.of(
            ObjectKind.TABLE, ObjectKind.PARTITION, ObjectKind.FOREIGN_TABLE,
            ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW);

    private static final Set<ObjectKind> PRUNE_CONTAINED = Set.of(
            ObjectKind.COLUMN, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
            ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.EXCLUSION,
            ObjectKind.INDEX, ObjectKind.TRIGGER, ObjectKind.POLICY,
            ObjectKind.RULE, ObjectKind.COMMENT, ObjectKind.SECURITY_LABEL,
            ObjectKind.PRIVILEGES, ObjectKind.OWNERSHIP);

    private static final Set<ObjectKind> PRUNE_MIRRORED = Set.of(
            ObjectKind.COMMENT, ObjectKind.SECURITY_LABEL, ObjectKind.OWNERSHIP,
            ObjectKind.PRIVILEGES, ObjectKind.PUBLICATION);

    /**
     * UNEXPECTED objects implicitly removed by another UNEXPECTED object's
     * DROP — no CASCADE is ever rendered, so ordinary dependents need their
     * own standalone DROP (5.5). Only containment, serial ownership and
     * mirrored annotations are implicit removals.
     */
    private Set<ObjectId> suppressedUnexpected(List<Difference> differences) {
        final var unexpected = differences.stream()
                .filter(d -> d.type() == DifferenceType.UNEXPECTED
                        && d.id().kind() != ObjectKind.OTHER)
                .map(Difference::id)
                .collect(Collectors.toSet());
        if (unexpected.isEmpty()) {
            return Set.of();
        }
        final var prereqs = Dependencies.prerequisitesOf(dialect, actual);
        final var suppressed = new HashSet<ObjectId>();
        for (final var id : unexpected) {
            final var deps = prereqs.getOrDefault(id, Set.of());
            if (containedInUnexpectedParent(id, deps, unexpected)
                    || serialOwnedByUnexpectedTable(id, deps, unexpected)
                    || mirroredOnUnexpected(id, deps, unexpected)) {
                suppressed.add(id);
            }
        }
        return suppressed;
    }

    private static boolean containedInUnexpectedParent(ObjectId id,
            Set<ObjectId> prereqs, Set<ObjectId> unexpected) {
        if (!PRUNE_CONTAINED.contains(id.kind()) || id.parent().isEmpty()) {
            return false;
        }
        final var parent = id.parent().orElseThrow();
        return prereqs.stream().anyMatch(dep -> unexpected.contains(dep)
                && PRUNE_CONTAINERS.contains(dep.kind())
                && dep.name().equals(parent));
    }

    private static boolean serialOwnedByUnexpectedTable(ObjectId id,
            Set<ObjectId> prereqs, Set<ObjectId> unexpected) {
        return id.kind() == ObjectKind.SEQUENCE
                && prereqs.stream().anyMatch(dep -> unexpected.contains(dep)
                        && dep.kind() == ObjectKind.TABLE);
    }

    private static boolean mirroredOnUnexpected(ObjectId id,
            Set<ObjectId> prereqs, Set<ObjectId> unexpected) {
        return PRUNE_MIRRORED.contains(id.kind())
                && prereqs.stream().anyMatch(unexpected::contains);
    }

    private List<PlannedChange> render(Difference difference) {
        final var opts = difference.type() == DifferenceType.UNEXPECTED
                && !renamedParents.isEmpty()
                ? new RenderOptions(options.concurrentIndexes(), renamedParents)
                : options;
        return renderWith(dialect, targetNamespace, difference,
                difference.type() == DifferenceType.UNEXPECTED ? actual : desired,
                opts);
    }

    private static List<PlannedChange> renderWith(Dialect dialect, String targetNamespace,
                                                  Difference difference,
                                                  SchemaSnapshot snapshot,
                                                  RenderOptions options) {
        List<PlannedChange> rendered;
        try {
            rendered = dialect.render(difference, snapshot, targetNamespace, options);
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
    private List<PlannedChange> tableShapeConflicts(List<Difference> differences,
                                                    Set<ObjectId> covered) {
        return differences.stream()
                .filter(d -> d.type() == DifferenceType.UNEXPECTED
                        && d.id().kind() == ObjectKind.COLUMN
                        && !covered.contains(d.id())
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
                                      Map<ObjectId, Map<ObjectId, Definition>> coveredByParent,
                                      Set<ObjectId> pruned) {
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
                    // an out-of-namespace dependent can never be pruned —
                    // dropping the prerequisite would break it silently
                    if ("true".equals(have.attributes()
                            .get(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE))) {
                        throw new PlanningException("namespace '" + targetNamespace + "': "
                                + dependent + " depends on " + dropped
                                + " but is not part of the desired state");
                    }
                    if (prune && (targets.contains(dependent) || pruned.contains(dependent))) {
                        // under prune this in-namespace dependent is itself
                        // being dropped (or implicitly removed); no rebuild
                        continue;
                    }
                    throw new PlanningException("namespace '" + targetNamespace + "': "
                            + dependent + " depends on " + dropped
                            + " but is not part of the desired state");
                }
                targets.add(dependent);
                final var synthetic = new Difference(dependent, DifferenceType.DRIFTED,
                        Optional.of(want.definition()), Optional.of(have.definition()));
                for (final var c : render(synthetic)) {
                    extra.add(new PlannedChange(0, c.target(), c.action(), c.safety(),
                            c.statements(), c.expected(), "dependency of " + dropped,
                            c.nonTransactional(), c.hazard()));
                    targets.add(c.target());
                }
            }
        }
        changes.addAll(extra);
    }

    /**
     * 4.4: a DROP is only ever half of a recreate, paired with a CREATE —
     * except a standalone DROP of an object the desired state does not
     * contain (a pruning drop of an UNEXPECTED object, 5.5).
     */
    private void validatePairs(List<PlannedChange> changes) {
        final var byTarget = new LinkedHashMap<ObjectId, List<PlannedChange>>();
        changes.forEach(c -> byTarget.computeIfAbsent(c.target(), k -> new ArrayList<>()).add(c));
        for (final var e : byTarget.entrySet()) {
            final var drops = e.getValue().stream().filter(c -> c.action() == ChangeAction.DROP).count();
            if (drops > 0) {
                final var creates = e.getValue().stream()
                        .filter(c -> c.action() == ChangeAction.CREATE).count();
                if (!desired.objects().containsKey(e.getKey())) {
                    // standalone DROP of an absent-from-desired object
                    // (prune, 5.5): valid only with no CREATE on the target
                    if (creates != 0) {
                        throw new PlanningException("namespace '" + targetNamespace + "': "
                                + e.getKey() + " is dropped but also created "
                                + "while absent from the desired state");
                    }
                    continue;
                }
                if (drops != creates) {
                    throw new PlanningException("namespace '" + targetNamespace + "': " + e.getKey()
                            + " has " + drops + " DROP change(s) but " + creates
                            + " CREATE change(s); a DROP is only ever half of a recreate");
                }
                // the halves must match — other actions on the same target
                // (a rename's ALTER followed by a drift recreate) carry
                // their own reason and safety
                final var pair = e.getValue().stream()
                        .filter(c -> c.action() != ChangeAction.ALTER).toList();
                final var safeties = pair.stream().map(PlannedChange::safety)
                        .collect(Collectors.toSet());
                final var reasons = pair.stream().map(PlannedChange::reason)
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
     * desired snapshot. {@code early} changes (rename ALTERs) run
     * first, in hint order — a drift fix on the new name cannot touch
     * it before the rename made it exist.
     */
    private List<PlannedChange> orderChanges(List<PlannedChange> changes,
                                             List<PlannedChange> early) {
        final var desiredDeps = Dependencies.prerequisitesOf(dialect, desired);
        Map<ObjectId, Set<ObjectId>> actualDependents =
                Dependencies.reverse(Dependencies.prerequisitesOf(dialect, actual));

        final var drops = changes.stream()
                .filter(c -> c.action() == ChangeAction.DROP).toList();
        final var creates = changes.stream()
                .filter(c -> c.action() != ChangeAction.DROP).toList();

        final var ordered = new ArrayList<PlannedChange>(early);
        ordered.addAll(topologicalOrder(drops, actualDependents));
        ordered.addAll(topologicalOrder(creates, desiredDeps));
        return IntStream.range(0, ordered.size())
                .mapToObj(i -> {
                    final var c = ordered.get(i);
                    return new PlannedChange(i + 1, c.target(), c.action(), c.safety(),
                            c.statements(), c.expected(), c.reason(),
                            c.nonTransactional(), c.hazard());
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
