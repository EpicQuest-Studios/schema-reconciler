package reconciler

import (
	"errors"
	"slices"
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// builtPlan is the result of plan building: the plan plus covered-child
// expectations per parent.
type builtPlan struct {
	plan            *model.ReconcilePlan
	coveredByParent map[model.ObjectId]map[model.ObjectId]model.Definition
}

// tieCompare breaks topological-order ties per spec 5.8.5: kind ordinal,
// then object-id order, then action rank (ALTER before CREATE before DROP).
func tieCompare(a, b model.PlannedChange) int {
	if a.Target.Kind != b.Target.Kind {
		return int(a.Target.Kind) - int(b.Target.Kind)
	}
	if c := a.Target.Compare(b.Target); c != 0 {
		return c
	}
	return actionRank(a.Action) - actionRank(b.Action)
}

func actionRank(action model.ChangeAction) int {
	switch action {
	case model.ChangeAlter:
		return 0
	case model.ChangeCreate:
		return 1
	default:
		return 2
	}
}

// planBuilder turns differences into an ordered ReconcilePlan: covered-child
// suppression (5.8.4), dependent-rebuild expansion (5.8.2, 5.8.3),
// drop/create pairing (4.4), name-collision detection (5.8.7) and two-phase
// ordering (5.8.5). With prune on, UNEXPECTED objects produce standalone
// DROPs (5.5); with rename hints, matching from/to pairs collapse to an
// ALTER ... RENAME plus a drift fix when the definition moved.
type planBuilder struct {
	dialect         spi.Dialect
	targetNamespace string
	actual          model.SchemaSnapshot
	desired         model.SchemaSnapshot
	prune           bool
	renameHints     []model.RenameHint
	options         spi.RenderOptions
	renamedParents  map[string]string
}

func newPlanBuilder(d spi.Dialect, targetNamespace string,
	actual, desired model.SchemaSnapshot, prune bool,
	renameHints []model.RenameHint, options spi.RenderOptions) *planBuilder {
	return &planBuilder{
		dialect: d, targetNamespace: targetNamespace, actual: actual,
		desired: desired, prune: prune, renameHints: renameHints,
		options: options, renamedParents: map[string]string{},
	}
}

func (pb *planBuilder) build(differences []model.Difference) (*builtPlan, error) {
	coveredByParent, err := coverageOf(pb.dialect, pb.targetNamespace, differences, pb.desired, nil)
	if err != nil {
		return nil, err
	}
	covered := model.NewObjectSet()
	for _, m := range coveredByParent {
		for child := range m {
			covered.Add(child)
		}
	}

	// active rename hints hide the UNEXPECTED(from)/MISSING(to) pair (and
	// the unexpected-column table-shape conflict) behind one RENAME change
	// plus an optional drift fix (5.5.1)
	renamed, err := pb.activeRenames()
	if err != nil {
		return nil, err
	}
	for _, hint := range renamed {
		covered.Add(hint.From)
		covered.Add(hint.To)
	}
	// prune: an UNEXPECTED object dropped because another UNEXPECTED object
	// it depends on is dropped needs no DROP of its own — the parent's drop
	// takes it down (indexes and constraints on a dropped table, owned
	// sequences, mirrored annotations)
	var pruned model.ObjectSet
	if pb.prune {
		pruned = pb.suppressedUnexpected(differences)
	} else {
		pruned = model.NewObjectSet()
	}

	// a parent rename carries its same-named children along: their
	// UNEXPECTED(old.x)/MISSING(new.x) pairs are the same object with a
	// moved parent, not a drop+create; a def change still gets a DRIFTED
	// fix on the new id. Children dropped under prune with no desired
	// counterpart render through the NEW parent name — the old one is gone
	// once the rename ran (5.5.1)
	carried := pb.carriedChildren(renamed)
	for id := range carried.suppressed {
		covered.Add(id)
	}
	pb.renamedParents = carried.parentNames

	var changes []model.PlannedChange
	for _, drift := range carried.drift {
		rendered, err := pb.render(drift)
		if err != nil {
			return nil, err
		}
		changes = append(changes, rendered...)
	}
	for _, d := range differences {
		if covered.Has(d.ID) || pruned.Has(d.ID) {
			continue
		}
		switch {
		case d.Type == model.DifferenceMissing || d.Type == model.DifferenceDrifted:
			rendered, err := pb.render(d)
			if err != nil {
				return nil, err
			}
			changes = append(changes, rendered...)
		case d.Type == model.DifferenceUnexpected && pb.prune && d.ID.Kind != model.KindOther:
			rendered, err := pb.render(d)
			if err != nil {
				return nil, err
			}
			changes = append(changes, rendered...)
		}
	}
	if !pb.prune {
		changes = append(changes, pb.tableShapeConflicts(differences, covered)...)
	}
	kept := changes[:0]
	for _, c := range changes {
		if !covered.Has(c.Target) {
			kept = append(kept, c)
		}
	}
	changes = kept
	// rename changes arrive after the covered-children splice: they
	// legitimately target to, which covered holds to suppress the MISSING
	// difference — splicing them away with it would silently drop the rename
	var renameChanges []model.PlannedChange
	for _, hint := range renamed {
		early, late, err := pb.renderRename(hint)
		if err != nil {
			return nil, err
		}
		// the ALTER ... RENAME must precede everything else that touches
		// to — a drift recreate's DROP targets the new name, which only
		// exists after the rename ran
		renameChanges = append(renameChanges, early...)
		changes = append(changes, late...)
	}
	if err := pb.addDependentRebuilds(&changes, coveredByParent, pruned); err != nil {
		return nil, err
	}
	if err := pb.validatePairs(changes); err != nil {
		return nil, err
	}
	if err := pb.checkNameCollisions(changes); err != nil {
		return nil, err
	}
	ordered, err := pb.orderChanges(changes, renameChanges)
	if err != nil {
		return nil, err
	}

	var visible []model.Difference
	for _, d := range differences {
		if !covered.Has(d.ID) {
			visible = append(visible, d)
		}
	}
	return &builtPlan{
		plan: &model.ReconcilePlan{
			TargetNamespace:    pb.targetNamespace,
			DialectID:          pb.dialect.ID(),
			ActualFingerprint:  pb.actual.Fingerprint,
			DesiredFingerprint: pb.desired.Fingerprint,
			Differences:        visible,
			Changes:            ordered,
		},
		coveredByParent: coveredByParent,
	}, nil
}

// activeRenames are the hints active in this diff: from exists in the actual
// snapshot, to does not, to is desired and from is not. Both sides present
// in actual is a conflict a rename cannot resolve.
func (pb *planBuilder) activeRenames() ([]model.RenameHint, error) {
	var active []model.RenameHint
	for _, hint := range pb.renameHints {
		_, haveFrom := pb.actual.Objects[hint.From]
		_, haveTo := pb.actual.Objects[hint.To]
		if haveFrom && haveTo {
			return nil, NewPlanningErrorf("namespace '%s': rename %s -> %s is ambiguous, both objects exist",
				pb.targetNamespace, hint.From, hint.To)
		}
		_, toDesired := pb.desired.Objects[hint.To]
		_, fromDesired := pb.desired.Objects[hint.From]
		if haveFrom && !haveTo && toDesired && !fromDesired {
			active = append(active, hint)
		}
	}
	slices.SortFunc(active, func(a, b model.RenameHint) int {
		return a.From.Compare(b.From)
	})
	return active, nil
}

// renderRename produces one rename change, then a drift fix on to when the
// definition moved. early runs before all drops (the rename makes to
// exist); late joins the normal phases.
func (pb *planBuilder) renderRename(hint model.RenameHint) (early, late []model.PlannedChange, err error) {
	renderer, ok := pb.dialect.(spi.RenameRenderer)
	if !ok {
		return nil, nil, NewPlanningErrorf("namespace '%s': dialect '%s' does not support renames",
			pb.targetNamespace, pb.dialect.ID())
	}
	early, err = renderer.RenderRename(hint.From, hint.To, pb.actual, pb.desired, pb.targetNamespace)
	if err != nil {
		var planning *PlanningError
		if errors.As(err, &planning) {
			return nil, nil, err
		}
		return nil, nil, NewPlanningErrorf("namespace '%s': dialect failed to render rename %s -> %s",
			pb.targetNamespace, hint.From, hint.To).WithCause(err)
	}
	if len(early) == 0 {
		return nil, nil, NewPlanningErrorf("namespace '%s': dialect rendered no changes for rename %s -> %s",
			pb.targetNamespace, hint.From, hint.To)
	}
	want := pb.desired.Objects[hint.To]
	have := pb.actual.Objects[hint.From]
	if want.Definition != have.Definition {
		drift := model.Difference{
			ID: hint.To, Type: model.DifferenceDrifted,
			Expected: &want.Definition, Actual: &have.Definition,
		}
		late, err = pb.render(drift)
		if err != nil {
			return nil, nil, err
		}
	}
	return early, late, nil
}

// carriedChildren are the children of renamed parents: suppressed holds
// both sides of each UNEXPECTED(old.x)/MISSING(new.x) pair the rename
// carries, drift the synthetic DRIFTED differences for carried children
// whose definition also changed, and parentNames the old-to-new parent
// name map for prune renders.
type carriedChildrenResult struct {
	suppressed  model.ObjectSet
	drift       []model.Difference
	parentNames map[string]string
}

func (pb *planBuilder) carriedChildren(renamed []model.RenameHint) carriedChildrenResult {
	out := carriedChildrenResult{
		suppressed:  model.NewObjectSet(),
		parentNames: map[string]string{},
	}
	for _, hint := range renamed {
		if hint.From.Parent != "" {
			continue
		}
		out.parentNames[hint.From.Name] = hint.To.Name
		for _, have := range pb.actual.Objects {
			if have.ID.Parent != hint.From.Name {
				continue
			}
			wantID := model.ObjectId{Kind: have.ID.Kind, Name: have.ID.Name, Parent: hint.To.Name}
			want, ok := pb.desired.Objects[wantID]
			if !ok {
				continue
			}
			out.suppressed.Add(have.ID)
			out.suppressed.Add(wantID)
			if want.Definition != have.Definition {
				out.drift = append(out.drift, model.Difference{
					ID: wantID, Type: model.DifferenceDrifted,
					Expected: &want.Definition, Actual: &have.Definition,
				})
			}
		}
	}
	slices.SortFunc(out.drift, func(a, b model.Difference) int {
		return a.ID.Compare(b.ID)
	})
	return out
}

var pruneContainers = map[model.ObjectKind]struct{}{
	model.KindTable: {}, model.KindPartition: {}, model.KindForeignTable: {},
	model.KindView: {}, model.KindMaterializedView: {},
}

var pruneContained = map[model.ObjectKind]struct{}{
	model.KindColumn: {}, model.KindPrimaryKey: {}, model.KindUnique: {},
	model.KindForeignKey: {}, model.KindCheck: {}, model.KindExclusion: {},
	model.KindIndex: {}, model.KindTrigger: {}, model.KindPolicy: {},
	model.KindRule: {}, model.KindComment: {}, model.KindSecurityLabel: {},
	model.KindPrivileges: {}, model.KindOwnership: {},
}

var pruneMirrored = map[model.ObjectKind]struct{}{
	model.KindComment: {}, model.KindSecurityLabel: {}, model.KindOwnership: {},
	model.KindPrivileges: {}, model.KindPublication: {},
}

// suppressedUnexpected are the UNEXPECTED objects implicitly removed by
// another UNEXPECTED object's DROP. No CASCADE is ever rendered, so
// ordinary dependents need their own standalone DROP (5.5); only
// containment, serial ownership and mirrored annotations are implicit
// removals.
func (pb *planBuilder) suppressedUnexpected(differences []model.Difference) model.ObjectSet {
	unexpected := model.NewObjectSet()
	for _, d := range differences {
		if d.Type == model.DifferenceUnexpected && d.ID.Kind != model.KindOther {
			unexpected.Add(d.ID)
		}
	}
	suppressed := model.NewObjectSet()
	if len(unexpected) == 0 {
		return suppressed
	}
	prereqs := prerequisitesOf(pb.dialect, pb.actual)
	for id := range unexpected {
		deps := prereqs[id]
		if containedInUnexpectedParent(id, deps, unexpected) ||
			serialOwnedByUnexpectedTable(id, deps, unexpected) ||
			mirroredOnUnexpected(id, deps, unexpected) {
			suppressed.Add(id)
		}
	}
	return suppressed
}

func containedInUnexpectedParent(id model.ObjectId, prereqs, unexpected model.ObjectSet) bool {
	if _, ok := pruneContained[id.Kind]; !ok || id.Parent == "" {
		return false
	}
	for dep := range prereqs {
		if _, container := pruneContainers[dep.Kind]; unexpected.Has(dep) &&
			container && dep.Name == id.Parent {
			return true
		}
	}
	return false
}

func serialOwnedByUnexpectedTable(id model.ObjectId, prereqs, unexpected model.ObjectSet) bool {
	if id.Kind != model.KindSequence {
		return false
	}
	for dep := range prereqs {
		if unexpected.Has(dep) && dep.Kind == model.KindTable {
			return true
		}
	}
	return false
}

func mirroredOnUnexpected(id model.ObjectId, prereqs, unexpected model.ObjectSet) bool {
	if _, ok := pruneMirrored[id.Kind]; !ok {
		return false
	}
	for dep := range prereqs {
		if unexpected.Has(dep) {
			return true
		}
	}
	return false
}

func (pb *planBuilder) render(difference model.Difference) ([]model.PlannedChange, error) {
	options := pb.options
	if difference.Type == model.DifferenceUnexpected && len(pb.renamedParents) > 0 {
		options = spi.RenderOptions{
			ConcurrentIndexes: pb.options.ConcurrentIndexes,
			RenamedParents:    pb.renamedParents,
		}
	}
	snapshot := pb.desired
	if difference.Type == model.DifferenceUnexpected {
		snapshot = pb.actual
	}
	return renderWith(pb.dialect, pb.targetNamespace, difference, snapshot, options)
}

// tableShapeConflicts is spec 5.5: an unexpected COLUMN on an expected
// table is a table-shape conflict.
func (pb *planBuilder) tableShapeConflicts(differences []model.Difference,
	covered model.ObjectSet) []model.PlannedChange {
	var out []model.PlannedChange
	for _, d := range differences {
		if d.Type != model.DifferenceUnexpected || d.ID.Kind != model.KindColumn ||
			covered.Has(d.ID) || d.ID.Parent == "" {
			continue
		}
		table := model.ObjectId{Kind: model.KindTable, Name: d.ID.Parent}
		if _, ok := pb.desired.Objects[table]; ok {
			out = append(out, model.PlannedChange{
				Target: table, Action: model.ChangeAlter,
				Safety: model.SafetyDestructive,
				Reason: "unexpected column " + d.ID.Name,
			})
		}
	}
	return out
}

// addDependentRebuilds is spec 5.8.2 and 5.8.3: every dependent of a dropped
// or recreated object is recreated via a synthetic DRIFTED render;
// dependents outside the desired and actual snapshots, or UNEXPECTED ones,
// fail planning.
func (pb *planBuilder) addDependentRebuilds(changes *[]model.PlannedChange,
	coveredByParent map[model.ObjectId]map[model.ObjectId]model.Definition,
	pruned model.ObjectSet) error {
	actualDeps := prerequisitesOf(pb.dialect, pb.actual)
	dependents := reverseDeps(actualDeps)

	targets := model.NewObjectSet()
	for _, c := range *changes {
		targets.Add(c.Target)
	}
	var extra []model.PlannedChange
	for _, change := range *changes {
		if change.Action != model.ChangeDrop {
			continue
		}
		dropped := change.Target
		// a DROP that is half of a recreate: the object's CREATE fragment
		// re-materializes its covered children, so those dependents need no
		// rebuild of their own — they verify through the parent's
		// covered-children expectations
		if _, ok := pb.desired.Objects[dropped]; ok {
			coverRecreatedChildren(pb.dialect, dropped, pb.desired, coveredByParent)
			if covered, ok := coveredByParent[dropped]; ok {
				for child := range covered {
					targets.Add(child)
				}
			}
		}
		for dependent := range reachable(dropped, dependents) {
			if dependent == dropped || targets.Has(dependent) {
				continue
			}
			have, haveOk := pb.actual.Objects[dependent]
			if !haveOk {
				continue
			}
			want, wantOk := pb.desired.Objects[dependent]
			if !wantOk {
				// an out-of-namespace dependent can never be pruned —
				// dropping the prerequisite would break it silently
				if have.Attributes[spi.ExternalDependentAttribute] == "true" {
					return NewPlanningErrorf("namespace '%s': %s depends on %s but is not part of the desired state",
						pb.targetNamespace, dependent, dropped)
				}
				if pb.prune && (targets.Has(dependent) || pruned.Has(dependent)) {
					// under prune this in-namespace dependent is itself
					// being dropped (or implicitly removed); no rebuild
					continue
				}
				return NewPlanningErrorf("namespace '%s': %s depends on %s but is not part of the desired state",
					pb.targetNamespace, dependent, dropped)
			}
			targets.Add(dependent)
			synthetic := model.Difference{
				ID: dependent, Type: model.DifferenceDrifted,
				Expected: &want.Definition, Actual: &have.Definition,
			}
			rendered, err := pb.render(synthetic)
			if err != nil {
				return err
			}
			for _, c := range rendered {
				extra = append(extra, model.PlannedChange{
					Target: c.Target, Action: c.Action, Safety: c.Safety,
					Statements: c.Statements, Expected: c.Expected,
					Reason:           "dependency of " + dropped.String(),
					NonTransactional: c.NonTransactional, Hazard: c.Hazard,
				})
				targets.Add(c.Target)
			}
		}
	}
	*changes = append(*changes, extra...)
	return nil
}

// validatePairs is spec 4.4: a DROP is only ever half of a recreate, paired
// with a CREATE — except a standalone DROP of an object the desired state
// does not contain (a pruning drop of an UNEXPECTED object, 5.5).
func (pb *planBuilder) validatePairs(changes []model.PlannedChange) error {
	var order []model.ObjectId
	byTarget := map[model.ObjectId][]model.PlannedChange{}
	for _, c := range changes {
		if _, ok := byTarget[c.Target]; !ok {
			order = append(order, c.Target)
		}
		byTarget[c.Target] = append(byTarget[c.Target], c)
	}
	for _, target := range order {
		list := byTarget[target]
		var drops, creates int
		for _, c := range list {
			switch c.Action {
			case model.ChangeDrop:
				drops++
			case model.ChangeCreate:
				creates++
			}
		}
		if drops == 0 {
			continue
		}
		if _, ok := pb.desired.Objects[target]; !ok {
			// standalone DROP of an absent-from-desired object (prune,
			// 5.5): valid only with no CREATE on the target
			if creates != 0 {
				return NewPlanningErrorf("namespace '%s': %s is dropped but also created while absent from the desired state",
					pb.targetNamespace, target)
			}
			continue
		}
		if drops != creates {
			return NewPlanningErrorf("namespace '%s': %s has %d DROP change(s) but %d CREATE change(s); a DROP is only ever half of a recreate",
				pb.targetNamespace, target, drops, creates)
		}
		// the halves must match — other actions on the same target (a
		// rename's ALTER followed by a drift recreate) carry their own
		// reason and safety
		var pair []model.PlannedChange
		for _, c := range list {
			if c.Action != model.ChangeAlter {
				pair = append(pair, c)
			}
		}
		safeties := map[model.ChangeSafety]struct{}{}
		reasons := map[string]struct{}{}
		for _, c := range pair {
			safeties[c.Safety] = struct{}{}
			reasons[c.Reason] = struct{}{}
		}
		if len(safeties) != 1 {
			return NewPlanningErrorf("namespace '%s': the DROP and CREATE halves of %s have different safety classes",
				pb.targetNamespace, target)
		}
		if len(reasons) != 1 {
			return NewPlanningErrorf("namespace '%s': the DROP and CREATE halves of %s have different reasons",
				pb.targetNamespace, target)
		}
	}
	return nil
}

// checkNameCollisions is spec 5.8.7: a CREATE must not collide on a name
// with anything not being dropped.
func (pb *planBuilder) checkNameCollisions(changes []model.PlannedChange) error {
	dropped := model.NewObjectSet()
	for _, c := range changes {
		if c.Action == model.ChangeDrop {
			dropped.Add(c.Target)
		}
	}
	actualByScope := map[string][]model.ObjectId{}
	for _, have := range pb.actual.Objects {
		for _, scope := range spi.NameScopes(pb.dialect, have.ID) {
			actualByScope[scope] = append(actualByScope[scope], have.ID)
		}
	}
	createScopes := map[string]model.ObjectId{}
	for _, change := range changes {
		if change.Action != model.ChangeCreate {
			continue
		}
		scopes := spi.NameScopes(pb.dialect, change.Target)
		for _, scope := range scopes {
			if earlier, ok := createScopes[scope]; ok {
				if earlier != change.Target {
					return NewPlanningErrorf("namespace '%s': %s collides with %s in name scope '%s'",
						pb.targetNamespace, change.Target, earlier, scope)
				}
			} else {
				createScopes[scope] = change.Target
			}
		}
		for _, scope := range scopes {
			for _, have := range actualByScope[scope] {
				if have != change.Target && !dropped.Has(have) {
					return NewPlanningErrorf("namespace '%s': %s collides with %s in name scope '%s'",
						pb.targetNamespace, change.Target, have, scope)
				}
			}
		}
	}
	return nil
}

// orderChanges is spec 5.8.5: all DROP changes in reverse dependency order
// of the actual snapshot, then all CREATE and ALTER changes in dependency
// order of the desired snapshot. early changes (rename ALTERs) run first,
// in hint order — a drift fix on the new name cannot touch it before the
// rename made it exist.
func (pb *planBuilder) orderChanges(changes, early []model.PlannedChange) ([]model.PlannedChange, error) {
	desiredDeps := prerequisitesOf(pb.dialect, pb.desired)
	actualDependents := reverseDeps(prerequisitesOf(pb.dialect, pb.actual))

	var drops, creates []model.PlannedChange
	for _, c := range changes {
		if c.Action == model.ChangeDrop {
			drops = append(drops, c)
		} else {
			creates = append(creates, c)
		}
	}
	ordered := slices.Clone(early)
	dropOrdered, err := topologicalOrder(drops, actualDependents, pb.targetNamespace)
	if err != nil {
		return nil, err
	}
	ordered = append(ordered, dropOrdered...)
	createOrdered, err := topologicalOrder(creates, desiredDeps, pb.targetNamespace)
	if err != nil {
		return nil, err
	}
	ordered = append(ordered, createOrdered...)
	for i := range ordered {
		ordered[i].Order = i + 1
	}
	return ordered, nil
}

// topologicalOrder orders changes so each target comes after the targets in
// edges it can reach: for creates, its prerequisites (desired graph); for
// drops, its dependents (reversed actual graph). Ties per 5.8.5.
func topologicalOrder(changes []model.PlannedChange,
	edges map[model.ObjectId]model.ObjectSet, namespace string) ([]model.PlannedChange, error) {
	targets := model.NewObjectSet()
	for _, c := range changes {
		targets.Add(c.Target)
	}
	predecessors := make([]model.ObjectSet, len(changes))
	dependentsOfTarget := map[model.ObjectId][]int{}
	for i, c := range changes {
		prereqs := reachable(c.Target, edges)
		for r := range prereqs {
			if !targets.Has(r) {
				delete(prereqs, r)
			}
		}
		predecessors[i] = prereqs
		for prereq := range prereqs {
			dependentsOfTarget[prereq] = append(dependentsOfTarget[prereq], i)
		}
	}
	ready := newMinHeap(func(a, b int) int {
		return tieCompare(changes[a], changes[b])
	})
	for i := range predecessors {
		if len(predecessors[i]) == 0 {
			ready.add(i)
		}
	}
	var out []model.PlannedChange
	for {
		i, ok := ready.poll()
		if !ok {
			break
		}
		out = append(out, changes[i])
		for _, depIdx := range dependentsOfTarget[changes[i].Target] {
			if depIdx != i {
				if _, had := predecessors[depIdx][changes[i].Target]; had {
					delete(predecessors[depIdx], changes[i].Target)
					if len(predecessors[depIdx]) == 0 {
						ready.add(depIdx)
					}
				}
			}
		}
	}
	if len(out) < len(changes) {
		var cycle []string
		for i, c := range changes {
			if len(predecessors[i]) > 0 {
				cycle = append(cycle, c.Target.String())
			}
		}
		return nil, NewPlanningErrorf("namespace '%s': dependency cycle involving %s",
			namespace, joinComma(cycle))
	}
	return out, nil
}

func joinComma(parts []string) string {
	out := ""
	for i, p := range parts {
		if i > 0 {
			out += ", "
		}
		out += p
	}
	return out
}

// coverRecreatedChildren records the children a recreated object's CREATE
// fragment re-materializes into coveredByParent — a partition's columns
// come back with its CREATE, exactly as a MISSING create's covered children
// do.
func coverRecreatedChildren(d spi.Dialect, dropped model.ObjectId,
	desired model.SchemaSnapshot,
	coveredByParent map[model.ObjectId]map[model.ObjectId]model.Definition) {
	synthetic := model.Difference{ID: dropped, Type: model.DifferenceMissing}
	for _, child := range spi.CoveredChildren(d, synthetic, desired) {
		object, ok := desired.Objects[child]
		if !ok {
			continue
		}
		m := coveredByParent[dropped]
		if m == nil {
			m = map[model.ObjectId]model.Definition{}
			coveredByParent[dropped] = m
		}
		m[child] = object.Definition
	}
}

// coverageOf is the covered-children map of spec 5.8.4 for a difference
// list and desired snapshot; used at plan time and by Apply when the plan
// was issued by another engine instance. A recreated object's CREATE half
// covers the same children a MISSING create does.
func coverageOf(d spi.Dialect, targetNamespace string, differences []model.Difference,
	desired model.SchemaSnapshot, changes []model.PlannedChange,
) (map[model.ObjectId]map[model.ObjectId]model.Definition, error) {
	covered := map[model.ObjectId]map[model.ObjectId]model.Definition{}
	for _, diff := range differences {
		if diff.Type != model.DifferenceMissing {
			continue
		}
		for _, child := range spi.CoveredChildren(d, diff, desired) {
			if child == diff.ID {
				continue
			}
			object, ok := desired.Objects[child]
			if !ok {
				return nil, NewPlanningErrorf("namespace '%s': dialect claims %s covers %s, which is not in the desired snapshot",
					targetNamespace, diff.ID, child)
			}
			m := covered[diff.ID]
			if m == nil {
				m = map[model.ObjectId]model.Definition{}
				covered[diff.ID] = m
			}
			m[child] = object.Definition
		}
	}
	for _, c := range changes {
		if c.Action == model.ChangeDrop {
			if _, ok := desired.Objects[c.Target]; ok {
				coverRecreatedChildren(d, c.Target, desired, covered)
			}
		}
	}
	return covered, nil
}

// renderWith calls dialect.Render and classifies failures per spec 7: a
// PlanningError already naming this namespace passes through, one without
// it is prefixed, ErrInvalidArgument passes through, and anything else is
// wrapped as a render failure.
func renderWith(d spi.Dialect, targetNamespace string, difference model.Difference,
	snapshot model.SchemaSnapshot, options spi.RenderOptions) ([]model.PlannedChange, error) {
	rendered, err := d.Render(difference, snapshot, targetNamespace, options)
	if err != nil {
		var planning *PlanningError
		if errors.As(err, &planning) {
			if containsNamespace(err.Error(), targetNamespace) {
				return nil, err
			}
			return nil, NewPlanningErrorf("namespace '%s': %s",
				targetNamespace, planning.Error()).WithCause(err)
		}
		if errors.Is(err, ErrInvalidArgument) {
			return nil, err
		}
		return nil, NewPlanningErrorf("namespace '%s': dialect failed to render %s",
			targetNamespace, difference.ID).WithCause(err)
	}
	if len(rendered) == 0 {
		return nil, NewPlanningErrorf("namespace '%s': dialect rendered no changes for %s",
			targetNamespace, difference.ID)
	}
	return rendered, nil
}

func containsNamespace(message, namespace string) bool {
	return strings.Contains(message, "namespace '"+namespace+"'")
}
