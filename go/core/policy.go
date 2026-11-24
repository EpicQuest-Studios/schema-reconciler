package reconciler

import (
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// ReconcilePolicy governs what a plan may do with each safety class of
// change, and with UNSUPPORTED differences (spec 3.3). The unsupported
// decision governs differences, not changes; DecisionApply is never
// permitted for it. UnsupportedCategories overrides Unsupported per
// difference category: for KindOther objects the category is the dialect's
// name prefix before ':' (for example "extension", "comment", "policy"; a
// name without a prefix is "relation"), for other kinds it is the lowercased
// ObjectKind name.
//
// Build policies from DefaultPolicy or ReportOnlyPolicy and adjust them with
// the WithXxx methods, or validate a fully specified policy with
// NewReconcilePolicy.
type ReconcilePolicy struct {
	additive              Decision
	rebuild               Decision
	destructive           Decision
	unsupported           Decision
	unsupportedCategories map[string]Decision
	prune                 bool
}

// NewReconcilePolicy validates a fully specified policy: Unsupported and
// every UnsupportedCategories value must not be DecisionApply
// (ErrInvalidArgument). unsupportedCategories may be nil.
func NewReconcilePolicy(additive, rebuild, destructive, unsupported Decision,
	unsupportedCategories map[string]Decision, prune bool) (ReconcilePolicy, error) {
	if unsupported == DecisionApply {
		return ReconcilePolicy{}, model.InvalidArgumentErrorf("unsupported must not be APPLY")
	}
	for _, v := range unsupportedCategories {
		if v == DecisionApply {
			return ReconcilePolicy{}, model.InvalidArgumentErrorf("unsupported categories must not be APPLY")
		}
	}
	categories := make(map[string]Decision, len(unsupportedCategories))
	for k, v := range unsupportedCategories {
		categories[k] = v
	}
	return ReconcilePolicy{
		additive:              additive,
		rebuild:               rebuild,
		destructive:           destructive,
		unsupported:           unsupported,
		unsupportedCategories: categories,
		prune:                 prune,
	}, nil
}

// DefaultPolicy is the spec's ReconcilePolicy.defaults(): APPLY additive,
// APPLY rebuild, FAIL destructive, FAIL unsupported, prune false.
func DefaultPolicy() ReconcilePolicy {
	return ReconcilePolicy{
		additive:              DecisionApply,
		rebuild:               DecisionApply,
		destructive:           DecisionFail,
		unsupported:           DecisionFail,
		unsupportedCategories: map[string]Decision{},
	}
}

// ReportOnlyPolicy is the spec's ReconcilePolicy.reportOnly(): REPORT for
// every class, prune false.
func ReportOnlyPolicy() ReconcilePolicy {
	return ReconcilePolicy{
		additive:              DecisionReport,
		rebuild:               DecisionReport,
		destructive:           DecisionReport,
		unsupported:           DecisionReport,
		unsupportedCategories: map[string]Decision{},
	}
}

// Additive is the decision for ADDITIVE changes.
func (p ReconcilePolicy) Additive() Decision { return p.additive }

// Rebuild is the decision for REBUILD changes.
func (p ReconcilePolicy) Rebuild() Decision { return p.rebuild }

// Destructive is the decision for DESTRUCTIVE changes.
func (p ReconcilePolicy) Destructive() Decision { return p.destructive }

// Unsupported is the fallback decision for UNSUPPORTED differences.
func (p ReconcilePolicy) Unsupported() Decision { return p.unsupported }

// UnsupportedCategories are the per-category overrides of Unsupported.
func (p ReconcilePolicy) UnsupportedCategories() map[string]Decision {
	out := make(map[string]Decision, len(p.unsupportedCategories))
	for k, v := range p.unsupportedCategories {
		out[k] = v
	}
	return out
}

// Prune is the opt-in DROP of UNEXPECTED objects (spec 5.5).
func (p ReconcilePolicy) Prune() bool { return p.prune }

func (p ReconcilePolicy) copy() ReconcilePolicy {
	p.unsupportedCategories = p.UnsupportedCategories()
	return p
}

// WithAdditive returns a copy with the additive decision replaced.
func (p ReconcilePolicy) WithAdditive(d Decision) ReconcilePolicy {
	q := p.copy()
	q.additive = d
	return q
}

// WithRebuild returns a copy with the rebuild decision replaced.
func (p ReconcilePolicy) WithRebuild(d Decision) ReconcilePolicy {
	q := p.copy()
	q.rebuild = d
	return q
}

// WithDestructive returns a copy with the destructive decision replaced.
func (p ReconcilePolicy) WithDestructive(d Decision) ReconcilePolicy {
	q := p.copy()
	q.destructive = d
	return q
}

// WithUnsupported returns a copy with the unsupported decision replaced;
// DecisionApply is rejected.
func (p ReconcilePolicy) WithUnsupported(d Decision) (ReconcilePolicy, error) {
	if d == DecisionApply {
		return ReconcilePolicy{}, model.InvalidArgumentErrorf("unsupported must not be APPLY")
	}
	q := p.copy()
	q.unsupported = d
	return q, nil
}

// WithUnsupportedCategory overrides the unsupported decision for one
// difference category; DecisionApply is rejected.
func (p ReconcilePolicy) WithUnsupportedCategory(category string, d Decision) (ReconcilePolicy, error) {
	if d == DecisionApply {
		return ReconcilePolicy{}, model.InvalidArgumentErrorf("unsupported categories must not be APPLY")
	}
	q := p.copy()
	q.unsupportedCategories[category] = d
	return q, nil
}

// WithPrune returns a copy with prune replaced — the opt-in deletion of
// UNEXPECTED objects (spec 5.5).
func (p ReconcilePolicy) WithPrune(prune bool) ReconcilePolicy {
	q := p.copy()
	q.prune = prune
	return q
}

// DecisionFor is the decision governing a change of the given safety class.
func (p ReconcilePolicy) DecisionFor(safety model.ChangeSafety) Decision {
	switch safety {
	case model.SafetyAdditive:
		return p.additive
	case model.SafetyRebuild:
		return p.rebuild
	default:
		return p.destructive
	}
}

// UnsupportedDecision is the decision governing an UNSUPPORTED difference,
// per the category override — spec 3.3's
// unsupportedCategories.getOrDefault(category, unsupported).
func (p ReconcilePolicy) UnsupportedDecision(d model.Difference) Decision {
	if d, ok := p.unsupportedCategories[categoryOf(d)]; ok {
		return d
	}
	return p.unsupported
}

// categoryOf is the policy category of a difference's object: the OTHER name
// prefix before ':', else the lowercased kind name (spec 3.3).
func categoryOf(d model.Difference) string {
	id := d.ID
	if id.Kind == model.KindOther {
		if colon := strings.Index(id.Name, ":"); colon >= 0 {
			return id.Name[:colon]
		}
		return "relation"
	}
	return strings.ToLower(id.Kind.String())
}
