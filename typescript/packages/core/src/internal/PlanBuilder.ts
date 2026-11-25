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
import type { RenameHint } from "../model/RenameHint.ts";
import type { SchemaSnapshot } from "../model/SchemaSnapshot.ts";
import { Dialect } from "../spi/Dialect.ts";
import type { RenderOptions } from "../spi/RenderOptions.ts";
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


const PRUNE_CONTAINERS: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.TABLE, ObjectKind.PARTITION, ObjectKind.FOREIGN_TABLE,
    ObjectKind.VIEW, ObjectKind.MATERIALIZED_VIEW]);

const PRUNE_CONTAINED: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.COLUMN, ObjectKind.PRIMARY_KEY, ObjectKind.UNIQUE,
    ObjectKind.FOREIGN_KEY, ObjectKind.CHECK, ObjectKind.EXCLUSION,
    ObjectKind.INDEX, ObjectKind.TRIGGER, ObjectKind.POLICY,
    ObjectKind.RULE, ObjectKind.COMMENT, ObjectKind.SECURITY_LABEL,
    ObjectKind.PRIVILEGES, ObjectKind.OWNERSHIP]);

const PRUNE_MIRRORED: ReadonlySet<ObjectKind> = new Set([
    ObjectKind.COMMENT, ObjectKind.SECURITY_LABEL, ObjectKind.OWNERSHIP,
    ObjectKind.PRIVILEGES, ObjectKind.PUBLICATION]);

function containedInUnexpectedParent(id: ObjectId, prereqs: ObjectSet,
        unexpected: ObjectSet): boolean {
    if (!PRUNE_CONTAINED.has(id.kind) || id.parent === undefined) {
        return false;
    }
    for (const dep of prereqs) {
        if (unexpected.has(dep) && PRUNE_CONTAINERS.has(dep.kind)
                && dep.name === id.parent) {
            return true;
        }
    }
    return false;
}

function serialOwnedByUnexpectedTable(id: ObjectId, prereqs: ObjectSet,
        unexpected: ObjectSet): boolean {
    if (id.kind !== ObjectKind.SEQUENCE) {
        return false;
    }
    for (const dep of prereqs) {
        if (unexpected.has(dep) && dep.kind === ObjectKind.TABLE) {
            return true;
        }
    }
    return false;
}

function mirroredOnUnexpected(id: ObjectId, prereqs: ObjectSet,
        unexpected: ObjectSet): boolean {
    if (!PRUNE_MIRRORED.has(id.kind)) {
        return false;
    }
    for (const dep of prereqs) {
        if (unexpected.has(dep)) {
            return true;
        }
    }
    return false;
}

/**
 * Turns differences into an ordered {@link ReconcilePlan}: covered-child
 * suppression (5.8.4), dependent-rebuild expansion (5.8.2, 5.8.3), drop/create
 * pairing (4.4), name-collision detection (5.8.7) and two-phase ordering
 * (5.8.5). With {@code prune} on, UNEXPECTED objects produce standalone
 * DROPs (5.5); with rename hints, matching from/to pairs collapse to an
 * {@code ALTER ... RENAME} plus a drift fix when the definition moved.
 */
export class PlanBuilder {

    private readonly dialect: Dialect;
    private readonly targetNamespace: string;
    private readonly actual: SchemaSnapshot;
    private readonly desired: SchemaSnapshot;
    private readonly prune: boolean;
    private readonly renameHints: readonly RenameHint[];
    private readonly retireTargets: readonly ObjectId[];
    private readonly pruneFilter: (id: ObjectId) => boolean;
    private readonly options: RenderOptions;
    private renamedParents: ReadonlyMap<string, string> = new Map();

    constructor(dialect: Dialect, targetNamespace: string,
                actual: SchemaSnapshot, desired: SchemaSnapshot,
                prune = false, renameHints: readonly RenameHint[] = [],
                options: RenderOptions = {},
                retireTargets: readonly ObjectId[] = [],
                pruneFilter: (id: ObjectId) => boolean = () => true) {
        this.dialect = dialect;
        this.targetNamespace = targetNamespace;
        this.actual = actual;
        this.desired = desired;
        this.prune = prune;
        this.renameHints = renameHints;
        this.options = options;
        this.retireTargets = retireTargets;
        this.pruneFilter = pruneFilter;
    }

    build(differences: Difference[]): Built {
        const coveredByParent = this.coverage(differences);
        const covered = new ObjectSet();
        for (const m of coveredByParent.values()) {
            for (const child of m.keys()) {
                covered.add(child);
            }
        }

        // active rename hints hide the UNEXPECTED(from)/MISSING(to) pair
        // (and the unexpected-column table-shape conflict) behind one
        // RENAME change plus an optional drift fix (5.5.1)
        const renamed = this.activeRenames();
        for (const hint of renamed) {
            covered.add(hint.from);
            covered.add(hint.to);
        }
        // prune: an UNEXPECTED object dropped because another UNEXPECTED
        // object it depends on is dropped needs no DROP of its own —
        // the parent's drop takes it down (indexes and constraints on a
        // dropped table, owned sequences, mirrored annotations)
        const retired = this.activeRetireTargets();
        const retireSet = new ObjectSet();
        for (const id of retired) {
            retireSet.add(id);
        }
        const pruneApproved = this.pruneApproved(differences, retireSet);
        const pruned = this.suppressedUnexpected(differences, pruneApproved);

        // a parent rename carries its same-named children along: their
        // UNEXPECTED(old.x)/MISSING(new.x) pairs are the same object with
        // a moved parent, not a drop+create; a def change still gets a
        // DRIFTED fix on the new id. Children dropped under prune with
        // no desired counterpart render through the NEW parent name —
        // the old one is gone once the rename ran (5.5.1)
        const carried = this.carriedChildren(renamed);
        for (const id of carried.suppressed) {
            covered.add(id);
        }
        this.renamedParents = carried.parentNames;

        const changes: PlannedChange[] = [];
        for (const drift of carried.drift) {
            changes.push(...this.render(drift));
        }
        for (const target of retired) {
            if (!covered.has(target) && !pruned.has(target)) {
                const have = this.actual.objects.get(target)!;
                changes.push(...this.renderRetired(new Difference(target,
                        DifferenceType.UNEXPECTED, undefined, have.definition)));
            }
        }
        for (const d of differences) {
            if (covered.has(d.id) || pruned.has(d.id)) {
                continue;
            }
            if (d.type === DifferenceType.MISSING || d.type === DifferenceType.DRIFTED) {
                changes.push(...this.render(d));
            } else if (d.type === DifferenceType.UNEXPECTED
                    && pruneApproved.has(d.id) && !retireSet.has(d.id)) {
                changes.push(...this.render(d));
            }
        }
        changes.push(...this.tableShapeConflicts(differences, covered, pruneApproved));
        for (let i = changes.length - 1; i >= 0; i--) {
            if (covered.has(changes[i]!.target)) {
                changes.splice(i, 1);
            }
        }
        // rename changes arrive after the covered-children splice: they
        // legitimately target {@code to}, which {@code covered} holds to
        // suppress the MISSING difference — splicing them away with it
        // would silently drop the rename
        const renameChanges: PlannedChange[] = [];
        for (const hint of renamed) {
            const rendered = this.renderRename(hint);
            // the ALTER ... RENAME must precede everything else that
            // touches {@code to} — a drift recreate's DROP targets the
            // new name, which only exists after the rename ran
            renameChanges.push(...rendered.early);
            changes.push(...rendered.late);
        }

        this.addDependentRebuilds(changes, coveredByParent, pruned);
        this.validatePairs(changes);
        this.checkNameCollisions(changes);
        const ordered = this.orderChanges(changes, renameChanges);

        const visible = differences.filter(d => !covered.has(d.id));
        const plan = new ReconcilePlan(this.targetNamespace, this.dialect.id(),
                this.actual.fingerprint, this.desired.fingerprint, visible, ordered);
        return { plan, coveredByParent };
    }

    /**
     * The hints that are active in this diff: {@code from} exists in the
     * actual snapshot, {@code to} does not, {@code to} is desired and
     * {@code from} is not. Both sides present in actual is a conflict a
     * rename cannot resolve.
     */
    private activeRenames(): RenameHint[] {
        const active: RenameHint[] = [];
        for (const hint of this.renameHints) {
            const haveFrom = this.actual.objects.has(hint.from);
            const haveTo = this.actual.objects.has(hint.to);
            if (haveFrom && haveTo) {
                throw new PlanningException("namespace '" + this.targetNamespace
                        + "': rename " + hint.from + " -> " + hint.to
                        + " is ambiguous, both objects exist");
            }
            if (haveFrom && !haveTo && this.desired.objects.has(hint.to)
                    && !this.desired.objects.has(hint.from)) {
                active.push(hint);
            }
        }
        active.sort((a, b) => ObjectIds.ORDER(a.from, b.from));
        return active;
    }

    /**
     * One rename change, then a drift fix on {@code to} when the
     * definition moved. {@code early} runs before all drops (the rename
     * makes {@code to} exist); {@code late} joins the normal phases.
     */
    private renderRename(hint: RenameHint)
            : { early: PlannedChange[]; late: PlannedChange[] } {
        if (this.dialect.renderRename === undefined) {
            throw new PlanningException("namespace '" + this.targetNamespace
                    + "': dialect '" + this.dialect.id() + "' does not support renames");
        }
        let early: PlannedChange[];
        try {
            early = this.dialect.renderRename(hint.from, hint.to,
                    this.actual, this.desired, this.targetNamespace);
        } catch (e) {
            if (e instanceof PlanningException) {
                throw e;
            }
            throw new PlanningException("namespace '" + this.targetNamespace
                    + "': dialect failed to render rename " + hint.from
                    + " -> " + hint.to, e);
        }
        if (early == null || early.length === 0) {
            throw new PlanningException("namespace '" + this.targetNamespace
                    + "': dialect rendered no changes for rename " + hint.from
                    + " -> " + hint.to);
        }
        const late: PlannedChange[] = [];
        const want = this.desired.objects.get(hint.to)!;
        const have = this.actual.objects.get(hint.from)!;
        if (!want.definition.equals(have.definition)) {
            const drift = new Difference(hint.to, DifferenceType.DRIFTED,
                    want.definition, have.definition);
            late.push(...this.render(drift));
        }
        return { early, late };
    }

    /**
     * Children of renamed parents: {@code suppressed} holds both sides of
     * each UNEXPECTED(old.x)/MISSING(new.x) pair that the rename carries,
     * {@code drift} the synthetic DRIFTED differences for carried
     * children whose definition also changed, and {@code parentNames}
     * the old-to-new parent name map for prune renders.
     */
    private carriedChildren(renamed: readonly RenameHint[])
            : { suppressed: ObjectSet; drift: Difference[];
                parentNames: Map<string, string> } {
        const suppressed = new ObjectSet();
        const drift: Difference[] = [];
        const parentNames = new Map<string, string>();
        for (const hint of renamed) {
            if (hint.from.parent !== undefined) {
                continue;
            }
            parentNames.set(hint.from.name, hint.to.name);
            for (const have of this.actual.objects.values()) {
                if (have.id.parent !== hint.from.name) {
                    continue;
                }
                const wantId = new ObjectId(have.id.kind, have.id.name,
                        hint.to.name);
                const want = this.desired.objects.get(wantId);
                if (want === undefined) {
                    continue;
                }
                suppressed.add(have.id);
                suppressed.add(wantId);
                if (!want.definition.equals(have.definition)) {
                    drift.push(new Difference(wantId, DifferenceType.DRIFTED,
                            want.definition, have.definition));
                }
            }
        }
        drift.sort((a, b) => ObjectIds.ORDER(a.id, b.id));
        return { suppressed, drift, parentNames };
    }

    /**
     * UNEXPECTED objects implicitly removed by another UNEXPECTED object's
     * DROP — no CASCADE is ever rendered, so ordinary dependents need their
     * own standalone DROP (5.5). Only containment, serial ownership and
     * mirrored annotations are implicit removals.
     */
    private suppressedUnexpected(differences: Difference[], approved: ObjectSet): ObjectSet {
        const unexpected = new ObjectSet();
        for (const d of differences) {
            if (d.type === DifferenceType.UNEXPECTED && d.id.kind !== ObjectKind.OTHER) {
                unexpected.add(d.id);
            }
        }
        const suppressed = new ObjectSet();
        if (unexpected.isEmpty || approved.isEmpty) {
            return suppressed;
        }
        const prereqs = Dependencies.prerequisitesOf(this.dialect, this.actual);
        for (const id of unexpected) {
            const deps = prereqs.get(id) ?? new ObjectSet();
            if (containedInUnexpectedParent(id, deps, approved)
                    || serialOwnedByUnexpectedTable(id, deps, approved)
                    || mirroredOnUnexpected(id, deps, approved)) {
                suppressed.add(id);
            }
        }
        return suppressed;
    }

    /** Retire targets present in actual, after contradiction validation. */
    private activeRetireTargets(): ObjectId[] {
        const active: ObjectId[] = [];
        const seen = new ObjectSet();
        for (const target of this.retireTargets) {
            if (this.desired.objects.has(target)) {
                throw new PlanningException("namespace '" + this.targetNamespace
                        + "': retire target " + target + " is present in the desired state");
            }
            for (const hint of this.renameHints) {
                if (target.equals(hint.from) || target.equals(hint.to)) {
                    throw new PlanningException("namespace '" + this.targetNamespace
                            + "': " + target + " is both a retire target and a rename endpoint");
                }
            }
            if (this.actual.objects.has(target) && !seen.has(target)) {
                seen.add(target);
                active.push(target);
            }
        }
        active.sort(ObjectIds.ORDER);
        return active;
    }

    /** The UNEXPECTED objects approved for a standalone drop. */
    private pruneApproved(differences: Difference[], retired: ObjectSet): ObjectSet {
        const unexpected = new ObjectSet();
        for (const d of differences) {
            if (d.type === DifferenceType.UNEXPECTED && d.id.kind !== ObjectKind.OTHER) {
                unexpected.add(d.id);
            }
        }
        const approved = new ObjectSet();
        for (const id of retired) {
            approved.add(id);
        }
        if (!this.prune) {
            return approved;
        }
        const protectedObjects = this.protectedUnexpected(unexpected);
        for (const id of unexpected) {
            if (this.pruneFilter(id) && !protectedObjects.has(id)) {
                approved.add(id);
            }
        }
        return approved;
    }

    /** Objects protected because an unexpected container failed pruneFilter. */
    private protectedUnexpected(unexpected: ObjectSet): ObjectSet {
        const prereqs = Dependencies.prerequisitesOf(this.dialect, this.actual);
        const protectedObjects = new ObjectSet();
        for (const id of unexpected) {
            if (PRUNE_CONTAINERS.has(id.kind) && !this.pruneFilter(id)) {
                protectedObjects.add(id);
            }
        }
        const containment = new Set(PRUNE_CONTAINED);
        containment.add(ObjectKind.PARTITION);
        let changed = true;
        while (changed) {
            changed = false;
            for (const id of unexpected) {
                if (protectedObjects.has(id)) {
                    continue;
                }
                const deps = prereqs.get(id) ?? new ObjectSet();
                const contained = id.parent !== undefined && containment.has(id.kind)
                        && [...deps].some(dep => protectedObjects.has(dep)
                                && dep.name === id.parent);
                const serial = id.kind === ObjectKind.SEQUENCE
                        && [...deps].some(dep => protectedObjects.has(dep));
                const mirrored = PRUNE_MIRRORED.has(id.kind)
                        && [...deps].some(dep => protectedObjects.has(dep));
                if (contained || serial || mirrored) {
                    protectedObjects.add(id);
                    changed = true;
                }
            }
        }
        return protectedObjects;
    }

    /** Renders a targeted retirement with a clear reason. */
    private renderRetired(difference: Difference): PlannedChange[] {
        return this.render(difference).map(c => new PlannedChange(0, c.target,
                c.action, c.safety, c.statements, c.expected,
                "retired " + difference.id, c.nonTransactional, c.hazard));
    }

    private render(difference: Difference): PlannedChange[] {
        const options = difference.type === DifferenceType.UNEXPECTED
                && this.renamedParents.size > 0
                ? { ...this.options, renamedParents: this.renamedParents }
                : this.options;
        return renderWith(this.dialect, this.targetNamespace, difference,
                difference.type === DifferenceType.UNEXPECTED ? this.actual : this.desired,
                options);
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
    private tableShapeConflicts(differences: Difference[],
                                covered: ObjectSet,
                                pruneApproved: ObjectSet): PlannedChange[] {
        const out: PlannedChange[] = [];
        for (const d of differences) {
            if (d.type !== DifferenceType.UNEXPECTED
                    || d.id.kind !== ObjectKind.COLUMN
                    || covered.has(d.id)
                    || pruneApproved.has(d.id)
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
                                 coveredByParent: ObjectMap<ObjectMap<Definition>>,
                                 pruned: ObjectSet): void {
        const actualDeps = Dependencies.prerequisitesOf(this.dialect, this.actual);
        const dependents = Dependencies.reverse(actualDeps);

        const targets = new ObjectSet(changes.map(c => c.target));
        const extra: PlannedChange[] = [];
        for (const change of [...changes]) {
            if (change.action !== ChangeAction.DROP) {
                continue;
            }
            const dropped = change.target;
            // a DROP that is half of a recreate: the object's CREATE
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
                    // an out-of-namespace dependent can never be pruned —
                    // dropping the prerequisite would break it silently
                    if (have.attributes.get(Dialect.EXTERNAL_DEPENDENT_ATTRIBUTE) === "true") {
                        throw new PlanningException("namespace '" + this.targetNamespace + "': "
                                + dependent + " depends on " + dropped
                                + " but is not part of the desired state");
                    }
                    if (targets.has(dependent) || pruned.has(dependent)) {
                        // under prune this in-namespace dependent is itself
                        // being dropped (or implicitly removed); no rebuild
                        continue;
                    }
                    throw new PlanningException("namespace '" + this.targetNamespace + "': "
                            + dependent + " depends on " + dropped
                            + " but is not part of the desired state");
                }
                targets.add(dependent);
                const synthetic = new Difference(dependent, DifferenceType.DRIFTED,
                        want.definition, have.definition);
                for (const c of this.render(synthetic)) {
                    extra.push(new PlannedChange(0, c.target, c.action, c.safety,
                            c.statements, c.expected, "dependency of " + dropped,
                            c.nonTransactional, c.hazard));
                    targets.add(c.target);
                }
            }
        }
        changes.push(...extra);
    }

    /**
     * 4.4: a DROP is only ever half of a recreate, paired with a CREATE —
     * except a standalone DROP of an object the desired state does not
     * contain (a pruning drop of an UNEXPECTED object, 5.5).
     */
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
                if (!this.desired.objects.has(target)) {
                    // standalone DROP of an absent-from-desired object
                    // (prune, 5.5): valid only with no CREATE on the target
                    if (creates !== 0) {
                        throw new PlanningException("namespace '" + this.targetNamespace + "': "
                                + target + " is dropped but also created "
                                + "while absent from the desired state");
                    }
                    continue;
                }
                if (drops !== creates) {
                    throw new PlanningException("namespace '" + this.targetNamespace + "': " + target
                            + " has " + drops + " DROP change(s) but " + creates
                            + " CREATE change(s); a DROP is only ever half of a recreate");
                }
                // the halves must match — other actions on the same target
                // (a rename's ALTER followed by a drift recreate) carry
                // their own reason and safety
                const pair = list.filter(c => c.action !== ChangeAction.ALTER);
                const safeties = new Set(pair.map(c => c.safety));
                const reasons = new Set(pair.map(c => c.reason));
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
     * desired snapshot. {@code early} changes (rename ALTERs) run
     * first, in hint order — a drift fix on the new name cannot touch
     * it before the rename made it exist.
     */
    private orderChanges(changes: PlannedChange[],
                         early: PlannedChange[] = []): PlannedChange[] {
        const desiredDeps = Dependencies.prerequisitesOf(this.dialect, this.desired);
        const actualDependents =
                Dependencies.reverse(Dependencies.prerequisitesOf(this.dialect, this.actual));

        const drops = changes.filter(c => c.action === ChangeAction.DROP);
        const creates = changes.filter(c => c.action !== ChangeAction.DROP);

        const ordered: PlannedChange[] = [...early];
        ordered.push(...this.topologicalOrder(drops, actualDependents));
        ordered.push(...this.topologicalOrder(creates, desiredDeps));
        return ordered.map((c, i) => new PlannedChange(i + 1, c.target, c.action, c.safety,
                c.statements, c.expected, c.reason, c.nonTransactional, c.hazard));
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
                    difference: Difference, snapshot: SchemaSnapshot,
                    options: RenderOptions): PlannedChange[] {
    let rendered: PlannedChange[];
    try {
        rendered = dialect.render(difference, snapshot, targetNamespace, options);
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
