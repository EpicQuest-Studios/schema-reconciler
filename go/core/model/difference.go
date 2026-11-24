package model

import (
	"encoding/json"
	"strconv"
)

// DifferenceType is the kind of difference between desired and actual state
// (spec 4.3).
type DifferenceType int

const (
	DifferenceMissing DifferenceType = iota
	DifferenceDrifted
	DifferenceUnexpected
	DifferenceUnsupported
)

var differenceTypeNames = [...]string{
	DifferenceMissing:     "MISSING",
	DifferenceDrifted:     "DRIFTED",
	DifferenceUnexpected:  "UNEXPECTED",
	DifferenceUnsupported: "UNSUPPORTED",
}

func (t DifferenceType) String() string {
	if int(t) < 0 || int(t) >= len(differenceTypeNames) {
		return "DifferenceType(" + strconv.Itoa(int(t)) + ")"
	}
	return differenceTypeNames[t]
}

// ParseDifferenceType maps a constant name back to its type.
func ParseDifferenceType(name string) (DifferenceType, error) {
	for t, n := range differenceTypeNames {
		if n == name {
			return DifferenceType(t), nil
		}
	}
	return 0, invalidArgf("unknown DifferenceType %q", name)
}

func (t DifferenceType) MarshalText() ([]byte, error) { return []byte(t.String()), nil }

func (t *DifferenceType) UnmarshalText(text []byte) error {
	parsed, err := ParseDifferenceType(string(text))
	if err != nil {
		return err
	}
	*t = parsed
	return nil
}

// Difference is one detected difference between the desired and actual
// snapshots (spec 4.3). Expected and Actual are nil when absent — the
// Optional<Definition> of the Java record.
type Difference struct {
	ID       ObjectId
	Type     DifferenceType
	Expected *Definition
	Actual   *Definition
}

// differenceJSON is the plan-JSON shape of a Difference.
type differenceJSON struct {
	ID       ObjectId       `json:"id"`
	Type     DifferenceType `json:"type"`
	Expected *Definition    `json:"expected"`
	Actual   *Definition    `json:"actual"`
}

// MarshalJSON writes the spec-4.4 member order.
func (d Difference) MarshalJSON() ([]byte, error) {
	return json.Marshal(differenceJSON{
		ID:       d.ID,
		Type:     d.Type,
		Expected: d.Expected,
		Actual:   d.Actual,
	})
}

// UnmarshalJSON reads the MarshalJSON shape, enforcing the same field rules
// as ReconcilePlan.fromJson: id and a known type are required; expected and
// actual are definition strings or null.
func (d *Difference) UnmarshalJSON(data []byte) error {
	var aux struct {
		ID       *json.RawMessage `json:"id"`
		Type     string           `json:"type"`
		Expected *Definition      `json:"expected"`
		Actual   *Definition      `json:"actual"`
	}
	if err := json.Unmarshal(data, &aux); err != nil {
		return invalidArgf("%s", err)
	}
	if aux.ID == nil {
		return invalidArgf("difference.id is not an object")
	}
	var id ObjectId
	if err := id.UnmarshalJSON(*aux.ID); err != nil {
		return err
	}
	t, err := ParseDifferenceType(aux.Type)
	if err != nil {
		return err
	}
	d.ID = id
	d.Type = t
	d.Expected = aux.Expected
	d.Actual = aux.Actual
	return nil
}
