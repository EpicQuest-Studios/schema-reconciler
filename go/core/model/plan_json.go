package model

import (
	"bytes"
	"encoding/json"
)

// planSchemaVersion is the only plan-JSON schema version this implementation
// reads and writes (spec 4.4).
const planSchemaVersion = 1

// planJSON is the fixed-member-order serialisation of spec 4.4 — the same
// order Java's toJson and TypeScript's JSON.stringify produce.
type planJSON struct {
	SchemaVersion      int              `json:"schemaVersion"`
	TargetNamespace    string           `json:"targetNamespace"`
	DialectID          string           `json:"dialectId"`
	ActualFingerprint  string           `json:"actualFingerprint"`
	DesiredFingerprint string           `json:"desiredFingerprint"`
	Differences        []differenceJSON `json:"differences"`
	Changes            []changeJSON     `json:"changes"`
}

// changeJSON is the plan-JSON shape of a PlannedChange.
type changeJSON struct {
	Order            int          `json:"order"`
	Target           ObjectId     `json:"target"`
	Action           ChangeAction `json:"action"`
	Safety           ChangeSafety `json:"safety"`
	Statements       []string     `json:"statements"`
	Expected         *Definition  `json:"expected"`
	Reason           string       `json:"reason"`
	NonTransactional bool         `json:"nonTransactional"`
	Hazard           ChangeHazard `json:"hazard"`
}

func changeToJSON(c PlannedChange) changeJSON {
	statements := c.Statements
	if statements == nil {
		statements = []string{}
	}
	return changeJSON{
		Order:            c.Order,
		Target:           c.Target,
		Action:           c.Action,
		Safety:           c.Safety,
		Statements:       statements,
		Expected:         c.Expected,
		Reason:           c.Reason,
		NonTransactional: c.NonTransactional,
		Hazard:           c.Hazard,
	}
}

// ToJSON is the machine-readable serialisation of spec 4.4 (schemaVersion 1):
// keys in a fixed order, changes in plan order, compact with no whitespace —
// byte-identical for identical input, and byte-identical to the Java
// toJson()/TypeScript toJson() output (JSON.stringify string escaping, no
// HTML escaping of <, > and &).
func (p ReconcilePlan) ToJSON() []byte {
	data, _ := p.MarshalJSON()
	return data
}

// MarshalJSON implements json.Marshaler with the ToJSON encoding.
func (p ReconcilePlan) MarshalJSON() ([]byte, error) {
	differences := p.Differences
	if differences == nil {
		differences = []Difference{}
	}
	changes := make([]changeJSON, len(p.Changes))
	for i, c := range p.Changes {
		changes[i] = changeToJSON(c)
	}
	plan := planJSON{
		SchemaVersion:      planSchemaVersion,
		TargetNamespace:    p.TargetNamespace,
		DialectID:          p.DialectID,
		ActualFingerprint:  p.ActualFingerprint,
		DesiredFingerprint: p.DesiredFingerprint,
		Differences:        make([]differenceJSON, len(differences)),
		Changes:            changes,
	}
	for i, d := range differences {
		plan.Differences[i] = differenceJSON{
			ID: d.ID, Type: d.Type, Expected: d.Expected, Actual: d.Actual,
		}
	}
	return marshalCompact(plan)
}

// UnmarshalJSON parses a plan produced by MarshalJSON/ToJSON; see
// ParseReconcilePlan for the validation rules.
func (p *ReconcilePlan) UnmarshalJSON(data []byte) error {
	parsed, err := parsePlan(data)
	if err != nil {
		return err
	}
	*p = parsed
	return nil
}

// ParseReconcilePlan rebuilds a plan from ToJSON output (the Java fromJson).
// It returns ErrInvalidArgument on malformed JSON, a schema version other
// than 1, or missing/invalid fields — with the same strictness the
// TypeScript requireXxx checks apply.
func ParseReconcilePlan(data []byte) (ReconcilePlan, error) {
	return parsePlan(data)
}

// ParseReconcilePlanJSON is ParseReconcilePlan for a JSON string, mirroring
// ReconcilePlan.fromJson(String).
func ParseReconcilePlanJSON(jsonText string) (ReconcilePlan, error) {
	return parsePlan([]byte(jsonText))
}

func parsePlan(data []byte) (ReconcilePlan, error) {
	var aux struct {
		SchemaVersion      json.Number        `json:"schemaVersion"`
		TargetNamespace    *string            `json:"targetNamespace"`
		DialectID          *string            `json:"dialectId"`
		ActualFingerprint  *string            `json:"actualFingerprint"`
		DesiredFingerprint *string            `json:"desiredFingerprint"`
		Differences        *[]json.RawMessage `json:"differences"`
		Changes            *[]json.RawMessage `json:"changes"`
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	if err := dec.Decode(&aux); err != nil {
		return ReconcilePlan{}, invalidArgf("invalid plan JSON: %s", err)
	}
	if dec.More() {
		return ReconcilePlan{}, invalidArgf("invalid plan JSON: trailing data")
	}
	if aux.SchemaVersion == "" {
		return ReconcilePlan{}, invalidArgf("invalid plan JSON: schemaVersion is required")
	}
	if v, err := aux.SchemaVersion.Float64(); err != nil || v != planSchemaVersion {
		return ReconcilePlan{}, invalidArgf("invalid plan JSON: schemaVersion %s", aux.SchemaVersion)
	}
	targetNamespace, err := requireJSONString(aux.TargetNamespace, "targetNamespace")
	if err != nil {
		return ReconcilePlan{}, err
	}
	dialectID, err := requireJSONString(aux.DialectID, "dialectId")
	if err != nil {
		return ReconcilePlan{}, err
	}
	actualFingerprint, err := requireJSONString(aux.ActualFingerprint, "actualFingerprint")
	if err != nil {
		return ReconcilePlan{}, err
	}
	desiredFingerprint, err := requireJSONString(aux.DesiredFingerprint, "desiredFingerprint")
	if err != nil {
		return ReconcilePlan{}, err
	}
	if aux.Differences == nil {
		return ReconcilePlan{}, invalidArgf("invalid plan JSON: differences is not an array")
	}
	if aux.Changes == nil {
		return ReconcilePlan{}, invalidArgf("invalid plan JSON: changes is not an array")
	}
	differences := make([]Difference, 0, len(*aux.Differences))
	for i, raw := range *aux.Differences {
		var d Difference
		if err := d.UnmarshalJSON(raw); err != nil {
			return ReconcilePlan{}, invalidArgf("invalid plan JSON: differences[%d]: %s", i, err)
		}
		differences = append(differences, d)
	}
	changes := make([]PlannedChange, 0, len(*aux.Changes))
	for i, raw := range *aux.Changes {
		var c PlannedChange
		if err := c.UnmarshalJSON(raw); err != nil {
			return ReconcilePlan{}, invalidArgf("invalid plan JSON: changes[%d]: %s", i, err)
		}
		if c.Order != i+1 {
			return ReconcilePlan{}, invalidArgf(
				"invalid plan JSON: changes[%d].order must be %d", i, i+1)
		}
		changes = append(changes, c)
	}
	return ReconcilePlan{
		TargetNamespace:    targetNamespace,
		DialectID:          dialectID,
		ActualFingerprint:  actualFingerprint,
		DesiredFingerprint: desiredFingerprint,
		Differences:        differences,
		Changes:            changes,
	}, nil
}

func requireJSONString(v *string, field string) (string, error) {
	if v == nil {
		return "", invalidArgf("invalid plan JSON: %s is not a string", field)
	}
	return *v, nil
}

// MarshalJSON writes the spec-4.4 member order for one change.
func (c PlannedChange) MarshalJSON() ([]byte, error) {
	return json.Marshal(changeToJSON(c))
}

// UnmarshalJSON reads the change object with the field rules of
// ReconcilePlan.fromJson: order, target, action, safety, statements and
// reason are required; expected is a definition string or null; hazard and
// its members default.
func (c *PlannedChange) UnmarshalJSON(data []byte) error {
	var aux struct {
		Order            json.Number      `json:"order"`
		Target           *json.RawMessage `json:"target"`
		Action           string           `json:"action"`
		Safety           string           `json:"safety"`
		Statements       *[]string        `json:"statements"`
		Expected         *Definition      `json:"expected"`
		Reason           *string          `json:"reason"`
		NonTransactional bool             `json:"nonTransactional"`
		Hazard           *json.RawMessage `json:"hazard"`
	}
	if err := json.Unmarshal(data, &aux); err != nil {
		return invalidArgf("%s", err)
	}
	order, err := aux.Order.Float64()
	if aux.Order == "" || err != nil {
		return invalidArgf("order is not a number")
	}
	if aux.Target == nil {
		return invalidArgf("target is not an object")
	}
	var target ObjectId
	if err := target.UnmarshalJSON(*aux.Target); err != nil {
		return err
	}
	action, err := ParseChangeAction(aux.Action)
	if err != nil {
		return err
	}
	safety, err := ParseChangeSafety(aux.Safety)
	if err != nil {
		return err
	}
	if aux.Statements == nil {
		return invalidArgf("statements is not an array")
	}
	reason, err := requireJSONString(aux.Reason, "reason")
	if err != nil {
		return err
	}
	if aux.Hazard == nil {
		return invalidArgf("hazard is not an object")
	}
	hazard := HazardNone
	if err := hazard.UnmarshalJSON(*aux.Hazard); err != nil {
		return err
	}
	parsed, err := NewPlannedChange(int(order), target, action, safety,
		*aux.Statements, aux.Expected, reason, aux.NonTransactional, hazard)
	if err != nil {
		return err
	}
	*c = parsed
	return nil
}

// marshalCompact encodes like JSON.stringify: compact, no HTML escaping of
// <, >, &, and no \u2028/\u2029 escaping — byte-identical to the Java and
// TypeScript writers for the same value.
func marshalCompact(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	out := bytes.TrimSuffix(buf.Bytes(), []byte{'\n'})
	return unescapeLineSeparators(out), nil
}

// unescapeLineSeparators rewrites the two escape sequences encoding/json
// emits unconditionally, \u2028 and \u2029, back to the raw characters — the
// spelling JSON.stringify produces. Escape sequences are consumed pairwise,
// so a literal "\\u2028" in the input (encoded as \\u2028) is preserved.
func unescapeLineSeparators(in []byte) []byte {
	if !bytes.ContainsAny(in, "\\") {
		return in
	}
	out := make([]byte, 0, len(in)+8)
	for i := 0; i < len(in); {
		if in[i] == '\\' && i+5 < len(in) && in[i+1] == 'u' &&
			in[i+2] == '2' && in[i+3] == '0' && in[i+4] == '2' &&
			(in[i+5] == '8' || in[i+5] == '9') {
			out = append(out, 0xE2, 0x80, byte(0xA8+(in[i+5]-'8')))
			i += 6
			continue
		}
		if in[i] == '\\' && i+1 < len(in) {
			out = append(out, in[i], in[i+1])
			i += 2
			continue
		}
		out = append(out, in[i])
		i++
	}
	return out
}
