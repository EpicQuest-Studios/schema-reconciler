package studio.epicquest.schema.reconciler.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import studio.epicquest.schema.reconciler.Decision;
import studio.epicquest.schema.reconciler.PolicyViolationException;
import studio.epicquest.schema.reconciler.ReconcilePolicy;
import studio.epicquest.schema.reconciler.model.ChangeAction;
import studio.epicquest.schema.reconciler.model.Difference;
import studio.epicquest.schema.reconciler.model.DifferenceType;
import studio.epicquest.schema.reconciler.model.ObjectId;
import studio.epicquest.schema.reconciler.model.PlannedChange;
import studio.epicquest.schema.reconciler.model.ReconcilePlan;
import studio.epicquest.schema.reconciler.model.SchemaSnapshot;

/**
 * Policy evaluation of spec 3.3, in the spec's exact order: fail check,
 * recreate pairing, then prerequisite closure to a fixed point.
 */
final class PolicyEvaluator {

    /** Decisions per change, and which object blocked each demoted change. */
    record Evaluation(
            Map<PlannedChange, Decision> decisions,
            Map<PlannedChange, ObjectId> demotedBy) { }

    private final ReconcilePolicy policy;

    PolicyEvaluator(ReconcilePolicy policy) {
        this.policy = policy;
    }

    Evaluation evaluate(ReconcilePlan plan, SchemaSnapshot actual, SchemaSnapshot desired) {
        final var decisions = new LinkedHashMap<PlannedChange, Decision>();
        var fail = false;
        for (final var change : plan.changes()) {
            var decision = policy.decisionFor(change.safety());
            if (change.isManual() && decision == Decision.APPLY) {
                decision = Decision.FAIL;
            }
            decisions.put(change, decision);
            if (decision == Decision.FAIL) {
                fail = true;
            }
        }
        final var unsupported = plan.differences().stream()
                .anyMatch(d -> d.type() == DifferenceType.UNSUPPORTED
                        && policy.unsupportedDecision(d) == Decision.FAIL);
        if (fail || unsupported) {
            throw new PolicyViolationException(
                    "namespace '" + plan.targetNamespace()
                            + "': plan contains changes or unsupported differences mapped to FAIL",
                    plan);
        }

        final var byTarget = new HashMap<ObjectId, List<PlannedChange>>();
        plan.changes().forEach(c -> byTarget.computeIfAbsent(c.target(), k -> new ArrayList<>()).add(c));

        final var blocked = new HashSet<ObjectId>();
        decisions.forEach((c, d) -> {
            if (d != Decision.APPLY) {
                blocked.add(c.target());
            }
        });
        plan.differences().stream()
                .filter(d -> d.type() == DifferenceType.UNSUPPORTED
                        && !equalInActual(d, actual))
                .map(Difference::id)
                .forEach(blocked::add);

        final var demotedBy = new LinkedHashMap<PlannedChange, ObjectId>();
        final var desiredDeps = Dependencies.prerequisitesOf(desired);
        var changed = true;
        while (changed) {
            changed = false;
            for (final var change : plan.changes()) {
                if (decisions.get(change) != Decision.APPLY) {
                    continue;
                }
                final var blocker = blockedPrerequisite(change.target(), desiredDeps, blocked);
                if (blocker == null) {
                    continue;
                }
                for (final var half : byTarget.getOrDefault(change.target(), List.of(change))) {
                    if (decisions.get(half) == Decision.APPLY) {
                        decisions.put(half, Decision.REPORT);
                        demotedBy.putIfAbsent(half, blocker);
                    }
                }
                blocked.add(change.target());
                changed = true;
            }
        }
        return new Evaluation(decisions, demotedBy);
    }

    private static boolean equalInActual(Difference difference, SchemaSnapshot actual) {
        return actual.object(difference.id())
                .flatMap(o -> difference.expected().map(e -> e.equals(o.definition())))
                .orElse(false);
    }

    /** The first blocked object reachable from {@code target} over desired deps, if any. */
    private static ObjectId blockedPrerequisite(
            ObjectId target, Map<ObjectId, Set<ObjectId>> desiredDeps, Set<ObjectId> blocked) {
        final var stack = new ArrayDeque<ObjectId>();
        final var seen = new HashSet<ObjectId>();
        stack.addAll(desiredDeps.getOrDefault(target, Set.of()));
        while (!stack.isEmpty()) {
            final var id = stack.pop();
            if (!seen.add(id) || id.equals(target)) {
                continue;
            }
            if (blocked.contains(id)) {
                return id;
            }
            stack.addAll(desiredDeps.getOrDefault(id, Set.of()));
        }
        return null;
    }
}
