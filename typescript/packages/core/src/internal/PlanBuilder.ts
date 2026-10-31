import { IllegalArgumentError } from "../IllegalArgumentError.ts";
import { PlanningException } from "../PlanningException.ts";
import { messageOf } from "../SchemaReconcilerException.ts";
import { ChangeAction } from "../model/ChangeAction.ts";
import { ChangeSafety } from "../model/ChangeSafety.ts";
import type { Definition } from "../model/Definition.ts";
import { Difference } from "../model/Difference.ts";
import { DifferenceType } from "../model/DifferenceType.ts";
import { ObjectId } from "../model/ObjectId.ts";
import { ObjectIds, kindOrdinal } from "../model/ObjectIds.ts";
import { ObjectKind } from "../model/ObjectKind.ts";
import { ObjectMap } from "../model/ObjectMap.ts";
import { ObjectSet } from "../model/ObjectSet.ts";
import { PlannedChange } from "../model/PlannedChange.ts";
import { ReconcilePlan } from "../model/ReconcilePlan.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import type { Dialect } from "../spi/Dialect.ts";
import { Dependencies } from "./Dependencies.ts";
import { MinHeap } from "./MinHeap.ts";

/** Result of plan building: the plan plus covered-child expectations per parent. */
export interface Built {
    readonly plan: ReconcilePlan;
    readonly coveredByParent: ObjectMap<ObjectMap<Definition>>;
}

function tieCompare(a: PlannedChange, b: PlannedChange): number {
    return (kindOrdinal(a.target.kind) - kindOrdinal(b.target.kind))
            || ObjectIds.ORDER(a.target, b.target)
            || actionRank(a.action) - actionRank(b.action);
}

function actionRank(action: ChangeAction): number {
    return action === ChangeAction.ALTER ? 0 : action === ChangeAction.CREATE ? 1 : 2;
}

/**
 * Turns differences into an ordered {@link ReconcilePlan}: covered-child
 * suppression (5.8.4), dependent-rebuild expansion (5.8.2, 5.8.3), drop/create
 * pairing (4.4), name-collision detection (5.8.7) and two-phase ordering
 * (5.8.5).
 */
export class PlanBuilder {

    private readonly dialect: Dialect;
    private readonly targetNamespace: string;
    private readonly actual: SchemaSnapshot;
    private readonly desired: SchemaSnapshot;

    constructor(dialect: Dialect, targetNamespace: string,
                actual: SchemaSnapshot, desired: SchemaSnapshot) {
        this.dialect = dialect;
        this.targetNamespace = targetNamespace;
        this.actual = actual;
        this.desired = desired;
    }

    build(differences: Difference[]): Built {
        const coveredByParent = this.coverage(differences);
        const covered = new ObjectSet();
        for (const m of coveredByParent.values()) {
            for (const child of m.keys()) {
                covered.add(child);
            }
        }

        const changes: PlannedChange[] = [];
        for (const d of differences) {
            if ((d.type === DifferenceType.MISSING || d.type === DifferenceType.DRIFTED)
                    && !covered.has(d.id)) {
                changes.push(...this.render(d));
            }
        }
        changes.push(...this.tableShapeConflicts(differences));
        for (let i = changes.length - 1; i >= 0; i--) {
            if (covered.has(changes[i]!.target)) {
                changes.splice(i, 1);
            }
        }

        this.addDependentRebuilds(changes, coveredByParent);
        this.validatePairs(changes);
        this.checkNameCollisions(changes);
        const ordered = this.orderChanges(changes);

        const visible = differences.filter(d => !covered.has(d.id));
        const plan = new ReconcilePlan(this.targetNamespace, this.dialect.id(),
                this.actual.fingerprint, this.desired.fingerprint, visible, ordered);
        return { plan, coveredByParent };
    }

    private render(difference: Difference): PlannedChange[] {
        return renderWith(this.dialect, this.targetNamespace, difference, this.desired);
    }

    /** 5.8.4: for each MISSING difference, ask the dialect which children its create covers. */
    private coverage(differences: Difference[]): ObjectMap<ObjectMap<Definition>> {
        return PlanBuilder.coverageOf(this.dialect, this.targetNamespace, differences, this.desired);
    }

    /**
     * The covered-children map of 5.8.4 for a given difference list and desired
     * snapshot; used both at plan time and by {@code apply()} when the plan
     * was issued by another engine instance. A recreated object's CREATE half
     * covers the same children a MISSING create does.
     */
    static coverageOf(dialect: Dialect, targetNamespace: string,
                      differences: readonly Difference[], desired: SchemaSnapshot,
                      changes: readonly PlannedChange[] = [])
            : ObjectMap<ObjectMap<Definition>> {
        const covered = new ObjectMap<ObjectMap<Definition>>();
        for (const d of differences) {
            if (d.type !== DifferenceType.MISSING) {
                continue;
            }
            const children = dialect.coveredChildren?.(d, desired) ?? [];
            for (const child of children) {
                if (child.equals(d.id)) {
                    continue;
                }
                const object = desired.objects.get(child);
                if (object === undefined) {
                    throw new PlanningException("namespace '" + targetNamespace
                            + "': dialect claims " + d.id + " covers " + child
                            + ", which is not in the desired snapshot");
                }
                let m = covered.get(d.id);
                if (m === undefined) {
                    m = new ObjectMap();
                    covered.set(d.id, m);
                }
                m.set(child, object.definition);
            }
        }
        for (const c of changes) {
            if (c.action === ChangeAction.DROP && desired.objects.has(c.target)) {
                coverRecreatedChildren(dialect, c.target, desired, covered);
            }
        }
        return covered;
    }

    /** 5.5: an unexpected COLUMN on an expected table is a table-shape conflict. */
    private tableShapeConflicts(differences: Difference[]): PlannedChange[] {
        const out: PlannedChange[] = [];
        for (const d of differences) {
            if (d.type !== DifferenceType.UNEXPECTED
                    || d.id.kind !== ObjectKind.COLUMN
                    || d.id.parent === undefined) {
                continue;
            }
            const table = new ObjectId(ObjectKind.TABLE, d.id.parent);
            if (this.desired.objects.has(table)) {
                out.push(new PlannedChange(0, table,
                        ChangeAction.ALTER, ChangeSafety.DESTRUCTIVE, [],
                        undefined, "unexpected column " + d.id.name));
            }
        }
        return out;
    }

    /**
     * 5.8.2 and 5.8.3: every dependent of a dropped or recreated object is
     * recreated via a synthetic DRIFTED render; dependents outside the desired
     * and actual snapshots, or UNEXPECTED ones, fail planning.
     */
    private addDependentRebuilds(changes: PlannedChange[],
                                 coveredByParent: ObjectMap<ObjectMap<Definition>>): void {
        const actualDeps = Dependencies.prerequisitesOf(this.dialect, this.actual);
        const dependents = Dependencies.reverse(actualDeps);

        const targets = new ObjectSet(changes.map(c => c.target));
        const extra: PlannedChange[] = [];
        for (const change of [...changes]) {
            if (change.action !== ChangeAction.DROP) {
                continue;
            }
            const dropped = change.target;
            // a DROP is always half of a recreate: the object's CREATE
            // fragment re-materializes its covered children, so those
            // dependents need no rebuild of their own — they verify through
            // the parent's covered-children expectations
            if (this.desired.objects.has(dropped)) {
                coverRecreatedChildren(this.dialect, dropped, this.desired, coveredByParent);
                const covered = coveredByParent.get(dropped);
                if (covered !== undefined) {
                    for (const child of covered.keys()) {
                        targets.add(child);
                    }
                }
            }
            for (const dependent of Dependencies.reachable(dropped, dependents)) {
                if (dependent.equals(dropped) || targets.has(dependent)) {
                    continue;
                }
                const have = this.actual.objects.get(dependent);
                if (have === undefined) {
                    continue;
                }
                const want = this.desired.objects.get(dependent);
                if (want === undefined) {
                    throw new PlanningException("namespace '" + this.targetNamespace + "': "
                            + dependent + " depends on " + dropped
                            + " but is not part of the desired state");
                }
                targets.add(dependent);
                const synthetic = new Difference(dependent, DifferenceType.DRIFTED,
                        want.definition, have.definition);
                for (const c of this.render(synthetic)) {
                    extra.push(new PlannedChange(0, c.target, c.action, c.safety,
                            c.statements, c.expected, "dependency of " + dropped));
                    targets.add(c.target);
                }
            }
        }
        changes.push(...extra);
    }

    /** 4.4: a DROP is only ever the first half of a recreate, paired with a CREATE. */
    private validatePairs(changes: PlannedChange[]): void {
        const byTarget = new ObjectMap<PlannedChange[]>();
        for (const c of changes) {
            let list = byTarget.get(c.target);
            if (list === undefined) {
                list = [];
                byTarget.set(c.target, list);
            }
            list.push(c);
        }
        for (const [target, list] of byTarget) {
            const drops = list.filter(c => c.action === ChangeAction.DROP).length;
            if (drops > 0) {
                const creates = list.filter(c => c.action === ChangeAction.CREATE).length;
                if (drops !== creates) {
                    throw new PlanningException("namespace '" + this.targetNamespace + "': " + target
                            + " has " + drops + " DROP change(s) but " + creates
                            + " CREATE change(s); a DROP is only ever half of a recreate");
                }
                const safeties = new Set(list.map(c => c.safety));
                const reasons = new Set(list.map(c => c.reason));
                if (safeties.size !== 1) {
                    throw new PlanningException("namespace '" + this.targetNamespace
                            + "': the DROP and CREATE halves of " + target
                            + " have different safety classes");
                }
                if (reasons.size !== 1) {
                    throw new PlanningException("namespace '" + this.targetNamespace
                            + "': the DROP and CREATE halves of " + target
                            + " have different reasons");
                }
            }
        }
    }

    /** 5.8.7: a CREATE must not collide on a name with anything not being dropped. */
    private checkNameCollisions(changes: PlannedChange[]): void {
        const dropped = new ObjectSet(changes
                .filter(c => c.action === ChangeAction.DROP)
                .map(c => c.target));

        const actualByScope = new Map<string, ObjectId[]>();
        for (const have of this.actual.objects.values()) {
            for (const scope of this.dialect.nameScopes?.(have.id) ?? []) {
                let list = actualByScope.get(scope);
                if (list === undefined) {
                    list = [];
                    actualByScope.set(scope, list);
                }
                list.push(have.id);
            }
        }
        const createScopes = new Map<string, ObjectId>();
        for (const change of changes) {
            if (change.action !== ChangeAction.CREATE) {
                continue;
            }
            const scopes = this.dialect.nameScopes?.(change.target) ?? [];
            for (const scope of scopes) {
                if (!createScopes.has(scope)) {
                    createScopes.set(scope, change.target);
                } else {
                    const earlier = createScopes.get(scope)!;
                    if (!earlier.equals(change.target)) {
                        throw new PlanningException("namespace '" + this.targetNamespace + "': "
                                + change.target + " collides with " + earlier
                                + " in name scope '" + scope + "'");
                    }
                }
            }
            for (const scope of scopes) {
                for (const have of actualByScope.get(scope) ?? []) {
                    if (!have.equals(change.target) && !dropped.has(have)) {
                        throw new PlanningException("namespace '" + this.targetNamespace + "': "
                                + change.target + " collides with " + have
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
    private orderChanges(changes: PlannedChange[]): PlannedChange[] {
        const desiredDeps = Dependencies.prerequisitesOf(this.dialect, this.desired);
        const actualDependents =
                Dependencies.reverse(Dependencies.prerequisitesOf(this.dialect, this.actual));

        const drops = changes.filter(c => c.action === ChangeAction.DROP);
        const creates = changes.filter(c => c.action !== ChangeAction.DROP);

        const ordered: PlannedChange[] = [];
        ordered.push(...this.topologicalOrder(drops, actualDependents));
        ordered.push(...this.topologicalOrder(creates, desiredDeps));
        return ordered.map((c, i) => new PlannedChange(i + 1, c.target, c.action, c.safety,
                c.statements, c.expected, c.reason));
    }

    /**
     * Orders changes so that each target comes after the targets in {@code edges}
     * it can reach: for creates, its prerequisites (desired graph); for drops,
     * its dependents (reversed actual graph). Ties per 5.8.5.
     */
    private topologicalOrder(changes: PlannedChange[], edges: ObjectMap<ObjectSet>)
            : PlannedChange[] {
        const targets = new ObjectSet(changes.map(c => c.target));

        const predecessors = new Map<PlannedChange, ObjectSet>();
        const dependentsOfTarget = new ObjectMap<PlannedChange[]>();
        for (const c of changes) {
            const prereqs = Dependencies.reachable(c.target, edges);
            prereqs.retainAll(targets);
            predecessors.set(c, prereqs);
            for (const prereq of prereqs) {
                let list = dependentsOfTarget.get(prereq);
                if (list === undefined) {
                    list = [];
                    dependentsOfTarget.set(prereq, list);
                }
                list.push(c);
            }
        }

        const ready = new MinHeap<PlannedChange>(tieCompare);
        for (const [c, prereqs] of predecessors) {
            if (prereqs.isEmpty) {
                ready.add(c);
            }
        }
        const out: PlannedChange[] = [];
        let c: PlannedChange | undefined;
        while ((c = ready.poll()) !== undefined) {
            out.push(c);
            for (const dependent of dependentsOfTarget.get(c.target) ?? []) {
                if (dependent !== c && predecessors.get(dependent)!.delete(c.target)
                        && predecessors.get(dependent)!.isEmpty) {
                    ready.add(dependent);
                }
            }
        }
        if (out.length < changes.length) {
            const cycle = [...predecessors.entries()]
                    .filter(([, reqs]) => !reqs.isEmpty)
                    .map(([change]) => change.target);
            throw new PlanningException(
                    "namespace '" + this.targetNamespace + "': dependency cycle involving "
                            + cycle.join(", "));
        }
        return out;
    }
}

/**
 * Records the children a recreated object's CREATE fragment re-materializes
 * into {@code coveredByParent} — a partition's columns come back with its
 * {@code CREATE}, exactly as a MISSING create's covered children do.
 */
function coverRecreatedChildren(dialect: Dialect, dropped: ObjectId,
        desired: SchemaSnapshot, coveredByParent: ObjectMap<ObjectMap<Definition>>): void {
    const synthetic = new Difference(dropped, DifferenceType.MISSING, undefined, undefined);
    for (const child of dialect.coveredChildren?.(synthetic, desired) ?? []) {
        const object = desired.objects.get(child);
        if (object !== undefined) {
            let m = coveredByParent.get(dropped);
            if (m === undefined) {
                m = new ObjectMap();
                coveredByParent.set(dropped, m);
            }
            m.set(child, object.definition);
        }
    }
}

function renderWith(dialect: Dialect, targetNamespace: string,
                    difference: Difference, desired: SchemaSnapshot): PlannedChange[] {
    let rendered: PlannedChange[];
    try {
        rendered = dialect.render(difference, desired, targetNamespace);
    } catch (e) {
        if (e instanceof PlanningException) {
            if (messageOf(e).includes("namespace '" + targetNamespace + "'")) {
                throw e;
            }
            throw new PlanningException("namespace '" + targetNamespace + "': " + messageOf(e), e);
        }
        if (e instanceof IllegalArgumentError) {
            throw e;
        }
        throw new PlanningException(
                "namespace '" + targetNamespace + "': dialect failed to render " + difference.id, e);
    }
    if (rendered == null || rendered.length === 0) {
        throw new PlanningException("namespace '" + targetNamespace
                + "': dialect rendered no changes for " + difference.id);
    }
    return rendered;
}
