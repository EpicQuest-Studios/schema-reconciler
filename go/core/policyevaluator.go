package reconciler

import (
	"fmt"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// evaluation is the result of policy evaluation: a decision per change
// (parallel to plan.Changes), and the blocked object that demoted each
// demoted change.
type evaluation struct {
	decisions []Decision
	demotedBy map[int]model.ObjectId
}

// evaluatePolicy is the policy evaluation of spec 3.3, in the spec's exact
// order: fail check, recreate pairing, then prerequisite closure to a fixed
// point.
func evaluatePolicy(d spi.Dialect, policy ReconcilePolicy, plan *model.ReconcilePlan,
	actual, desired model.SchemaSnapshot) (*evaluation, error) {
	decisions := make([]Decision, len(plan.Changes))
	fail := false
	for i, change := range plan.Changes {
		decision := policy.DecisionFor(change.Safety)
		if change.IsManual() && decision == DecisionApply {
			decision = DecisionFail
		}
		decisions[i] = decision
		if decision == DecisionFail {
			fail = true
		}
	}
	unsupportedFail := false
	for _, diff := range plan.Differences {
		if diff.Type == model.DifferenceUnsupported &&
			policy.UnsupportedDecision(diff) == DecisionFail {
			unsupportedFail = true
			break
		}
	}
	if fail || unsupportedFail {
		return nil, NewPolicyViolationError(fmt.Sprintf(
			"namespace '%s': plan contains changes or unsupported differences mapped to FAIL",
			plan.TargetNamespace), plan)
	}

	byTarget := map[model.ObjectId][]int{}
	for i, c := range plan.Changes {
		byTarget[c.Target] = append(byTarget[c.Target], i)
	}
	blocked := model.NewObjectSet()
	for i, decision := range decisions {
		if decision != DecisionApply {
			blocked.Add(plan.Changes[i].Target)
		}
	}
	for _, diff := range plan.Differences {
		if diff.Type == model.DifferenceUnsupported && !equalInActual(diff, actual) {
			blocked.Add(diff.ID)
		}
	}

	demotedBy := map[int]model.ObjectId{}
	desiredDeps := prerequisitesOf(d, desired)
	for changed := true; changed; {
		changed = false
		for i, change := range plan.Changes {
			if decisions[i] != DecisionApply {
				continue
			}
			blocker, blocked_ := blockedPrerequisite(change.Target, desiredDeps, blocked)
			if !blocked_ {
				continue
			}
			halves, ok := byTarget[change.Target]
			if !ok {
				halves = []int{i}
			}
			for _, half := range halves {
				if decisions[half] == DecisionApply {
					decisions[half] = DecisionReport
					if _, has := demotedBy[half]; !has {
						demotedBy[half] = blocker
					}
				}
			}
			blocked.Add(change.Target)
			changed = true
		}
	}
	return &evaluation{decisions: decisions, demotedBy: demotedBy}, nil
}

func equalInActual(difference model.Difference, actual model.SchemaSnapshot) bool {
	object, ok := actual.Objects[difference.ID]
	return ok && difference.Expected != nil &&
		*difference.Expected == object.Definition
}

// blockedPrerequisite finds the first blocked object reachable from target
// over desired deps, if any.
func blockedPrerequisite(target model.ObjectId,
	desiredDeps map[model.ObjectId]model.ObjectSet,
	blocked model.ObjectSet) (model.ObjectId, bool) {
	var stack []model.ObjectId
	for id := range desiredDeps[target] {
		stack = append(stack, id)
	}
	seen := model.NewObjectSet()
	for len(stack) > 0 {
		id := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if seen.Has(id) || id == target {
			continue
		}
		seen.Add(id)
		if blocked.Has(id) {
			return id, true
		}
		for next := range desiredDeps[id] {
			stack = append(stack, next)
		}
	}
	return model.ObjectId{}, false
}
