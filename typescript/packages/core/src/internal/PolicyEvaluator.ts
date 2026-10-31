import { Decision } from "../Decision.ts";
import { PolicyViolationException } from "../PolicyViolationException.ts";
import type { ReconcilePolicy } from "../ReconcilePolicy.ts";
import type { Difference } from "../model/Difference.ts";
import { DifferenceType } from "../model/DifferenceType.ts";
import type { ObjectId } from "../model/ObjectId.ts";
import type { PlannedChange } from "../model/PlannedChange.ts";
import type { ReconcilePlan } from "../model/ReconcilePlan.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import { ObjectMap } from "../model/ObjectMap.ts";
import { ObjectSet } from "../model/ObjectSet.ts";
import type { Dialect } from "../spi/Dialect.ts";
import { Dependencies } from "./Dependencies.ts";

/** Decisions per change, and which object blocked each demoted change. */
export interface Evaluation {
    readonly decisions: Map<PlannedChange, Decision>;
    readonly demotedBy: Map<PlannedChange, ObjectId>;
}

/**
 * Policy evaluation of spec 3.3, in the spec's exact order: fail check,
 * recreate pairing, then prerequisite closure to a fixed point.
 */
export class PolicyEvaluator {

    private readonly dialect: Dialect;
    private readonly policy: ReconcilePolicy;

    constructor(dialect: Dialect, policy: ReconcilePolicy) {
        this.dialect = dialect;
        this.policy = policy;
    }

    evaluate(plan: ReconcilePlan, actual: SchemaSnapshot, desired: SchemaSnapshot): Evaluation {
        const decisions = new Map<PlannedChange, Decision>();
        let fail = false;
        for (const change of plan.changes) {
            let decision = this.policy.decisionFor(change.safety);
            if (change.isManual && decision === Decision.APPLY) {
                decision = Decision.FAIL;
            }
            decisions.set(change, decision);
            if (decision === Decision.FAIL) {
                fail = true;
            }
        }
        const unsupported = plan.differences.some(d => d.type === DifferenceType.UNSUPPORTED
                && this.policy.unsupportedDecision(d) === Decision.FAIL);
        if (fail || unsupported) {
            throw new PolicyViolationException(
                    "namespace '" + plan.targetNamespace
                            + "': plan contains changes or unsupported differences mapped to FAIL",
                    plan);
        }

        const byTarget = new ObjectMap<PlannedChange[]>();
        for (const c of plan.changes) {
            let list = byTarget.get(c.target);
            if (list === undefined) {
                list = [];
                byTarget.set(c.target, list);
            }
            list.push(c);
        }

        const blocked = new ObjectSet();
        for (const [c, d] of decisions) {
            if (d !== Decision.APPLY) {
                blocked.add(c.target);
            }
        }
        for (const d of plan.differences) {
            if (d.type === DifferenceType.UNSUPPORTED && !equalInActual(d, actual)) {
                blocked.add(d.id);
            }
        }

        const demotedBy = new Map<PlannedChange, ObjectId>();
        const desiredDeps = Dependencies.prerequisitesOf(this.dialect, desired);
        let changed = true;
        while (changed) {
            changed = false;
            for (const change of plan.changes) {
                if (decisions.get(change) !== Decision.APPLY) {
                    continue;
                }
                const blocker = blockedPrerequisite(change.target, desiredDeps, blocked);
                if (blocker === undefined) {
                    continue;
                }
                for (const half of byTarget.get(change.target) ?? [change]) {
                    if (decisions.get(half) === Decision.APPLY) {
                        decisions.set(half, Decision.REPORT);
                        if (!demotedBy.has(half)) {
                            demotedBy.set(half, blocker);
                        }
                    }
                }
                blocked.add(change.target);
                changed = true;
            }
        }
        return { decisions, demotedBy };
    }
}

function equalInActual(difference: Difference, actual: SchemaSnapshot): boolean {
    const object = actual.object(difference.id);
    return object !== undefined && difference.expected !== undefined
            && difference.expected.equals(object.definition);
}

/** The first blocked object reachable from {@code target} over desired deps, if any. */
function blockedPrerequisite(
        target: ObjectId, desiredDeps: ObjectMap<ObjectSet>, blocked: ObjectSet)
        : ObjectId | undefined {
    const stack = [...(desiredDeps.get(target) ?? [])];
    const seen = new ObjectSet();
    while (stack.length > 0) {
        const id = stack.pop()!;
        if (seen.has(id) || id.equals(target)) {
            continue;
        }
        seen.add(id);
        if (blocked.has(id)) {
            return id;
        }
        stack.push(...(desiredDeps.get(id) ?? []));
    }
    return undefined;
}
