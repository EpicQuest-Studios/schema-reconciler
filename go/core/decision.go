package reconciler

import (
	"strconv"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// ErrInvalidArgument is re-exported from the model package: argument-contract
// violations (the Java IllegalArgumentException analog) satisfy
// errors.Is(err, ErrInvalidArgument) throughout this module.
var ErrInvalidArgument = model.ErrInvalidArgument

// Decision is the policy decision for one safety class or unsupported
// difference category (spec 3.3).
type Decision int

const (
	// DecisionApply executes the change.
	DecisionApply Decision = iota
	// DecisionReport collects the change or difference in the report without
	// executing it (logged at WARNING).
	DecisionReport
	// DecisionFail aborts the run with a PolicyViolationError before
	// anything executes.
	DecisionFail
)

var decisionNames = [...]string{
	DecisionApply:  "APPLY",
	DecisionReport: "REPORT",
	DecisionFail:   "FAIL",
}

func (d Decision) String() string {
	if int(d) < 0 || int(d) >= len(decisionNames) {
		return "Decision(" + strconv.Itoa(int(d)) + ")"
	}
	return decisionNames[d]
}

// ParseDecision maps a constant name back to its decision.
func ParseDecision(name string) (Decision, error) {
	for d, n := range decisionNames {
		if n == name {
			return Decision(d), nil
		}
	}
	return 0, model.InvalidArgumentErrorf("unknown Decision %q", name)
}

func (d Decision) MarshalText() ([]byte, error) { return []byte(d.String()), nil }

func (d *Decision) UnmarshalText(text []byte) error {
	parsed, err := ParseDecision(string(text))
	if err != nil {
		return err
	}
	*d = parsed
	return nil
}
