package reconciler

import (
	"strconv"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// ReconcileOutcome is the outcome of a reconcile run (spec 3.5).
type ReconcileOutcome int

const (
	// OutcomeUpToDate — the plan had no changes.
	OutcomeUpToDate ReconcileOutcome = iota
	// OutcomeReconciled — at least one change was applied and verified.
	OutcomeReconciled
	// OutcomeReportedOnly — changes exist, but the policy reported all of
	// them and applied none.
	OutcomeReportedOnly
)

var outcomeNames = [...]string{
	OutcomeUpToDate:     "UP_TO_DATE",
	OutcomeReconciled:   "RECONCILED",
	OutcomeReportedOnly: "REPORTED_ONLY",
}

func (o ReconcileOutcome) String() string {
	if int(o) < 0 || int(o) >= len(outcomeNames) {
		return "ReconcileOutcome(" + strconv.Itoa(int(o)) + ")"
	}
	return outcomeNames[o]
}

// ParseReconcileOutcome maps a constant name back to its outcome.
func ParseReconcileOutcome(name string) (ReconcileOutcome, error) {
	for o, n := range outcomeNames {
		if n == name {
			return ReconcileOutcome(o), nil
		}
	}
	return 0, model.InvalidArgumentErrorf("unknown ReconcileOutcome %q", name)
}

func (o ReconcileOutcome) MarshalText() ([]byte, error) { return []byte(o.String()), nil }

func (o *ReconcileOutcome) UnmarshalText(text []byte) error {
	parsed, err := ParseReconcileOutcome(string(text))
	if err != nil {
		return err
	}
	*o = parsed
	return nil
}
