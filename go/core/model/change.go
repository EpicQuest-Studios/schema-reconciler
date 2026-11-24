package model

import (
	"encoding/json"
	"strconv"
)

// ChangeAction is what a PlannedChange does (spec 4.4). A ChangeDrop is only
// ever the first half of a recreate and is always paired with a ChangeCreate
// of the same target, except for standalone drops under a pruning policy.
type ChangeAction int

const (
	ChangeCreate ChangeAction = iota
	ChangeAlter
	ChangeDrop
)

var changeActionNames = [...]string{
	ChangeCreate: "CREATE",
	ChangeAlter:  "ALTER",
	ChangeDrop:   "DROP",
}

func (a ChangeAction) String() string {
	if int(a) < 0 || int(a) >= len(changeActionNames) {
		return "ChangeAction(" + strconv.Itoa(int(a)) + ")"
	}
	return changeActionNames[a]
}

// ParseChangeAction maps a constant name back to its action.
func ParseChangeAction(name string) (ChangeAction, error) {
	for a, n := range changeActionNames {
		if n == name {
			return ChangeAction(a), nil
		}
	}
	return 0, invalidArgf("unknown ChangeAction %q", name)
}

func (a ChangeAction) MarshalText() ([]byte, error) { return []byte(a.String()), nil }

func (a *ChangeAction) UnmarshalText(text []byte) error {
	parsed, err := ParseChangeAction(string(text))
	if err != nil {
		return err
	}
	*a = parsed
	return nil
}

// ChangeSafety is the safety class of a PlannedChange (spec 5.6).
type ChangeSafety int

const (
	SafetyAdditive ChangeSafety = iota
	SafetyRebuild
	SafetyDestructive
)

var changeSafetyNames = [...]string{
	SafetyAdditive:    "ADDITIVE",
	SafetyRebuild:     "REBUILD",
	SafetyDestructive: "DESTRUCTIVE",
}

func (s ChangeSafety) String() string {
	if int(s) < 0 || int(s) >= len(changeSafetyNames) {
		return "ChangeSafety(" + strconv.Itoa(int(s)) + ")"
	}
	return changeSafetyNames[s]
}

// ParseChangeSafety maps a constant name back to its safety class.
func ParseChangeSafety(name string) (ChangeSafety, error) {
	for s, n := range changeSafetyNames {
		if n == name {
			return ChangeSafety(s), nil
		}
	}
	return 0, invalidArgf("unknown ChangeSafety %q", name)
}

func (s ChangeSafety) MarshalText() ([]byte, error) { return []byte(s.String()), nil }

func (s *ChangeSafety) UnmarshalText(text []byte) error {
	parsed, err := ParseChangeSafety(string(text))
	if err != nil {
		return err
	}
	*s = parsed
	return nil
}

// LockLevel is the strongest lock a change takes on its target (spec 4.4
// metadata). Advisory: a dialect reports what its rendered statements imply
// so an operator can gauge concurrency impact; it does not drive execution.
type LockLevel int

const (
	// LockNone — no table-level lock implication worth reporting.
	LockNone LockLevel = iota
	// LockShareUpdateExclusive — CREATE/DROP INDEX CONCURRENTLY.
	LockShareUpdateExclusive
	// LockShare — CREATE INDEX (plain); blocks writes, not reads.
	LockShare
	// LockShareRowExclusive — ALTER TABLE variants that block writers only.
	LockShareRowExclusive
	// LockAccessExclusive — DROP TABLE, TRUNCATE, most ALTER TABLE forms.
	LockAccessExclusive
)

var lockLevelNames = [...]string{
	LockNone:                 "NONE",
	LockShareUpdateExclusive: "SHARE_UPDATE_EXCLUSIVE",
	LockShare:                "SHARE",
	LockShareRowExclusive:    "SHARE_ROW_EXCLUSIVE",
	LockAccessExclusive:      "ACCESS_EXCLUSIVE",
}

func (l LockLevel) String() string {
	if int(l) < 0 || int(l) >= len(lockLevelNames) {
		return "LockLevel(" + strconv.Itoa(int(l)) + ")"
	}
	return lockLevelNames[l]
}

// ParseLockLevel maps a constant name back to its lock level.
func ParseLockLevel(name string) (LockLevel, error) {
	for l, n := range lockLevelNames {
		if n == name {
			return LockLevel(l), nil
		}
	}
	return 0, invalidArgf("unknown LockLevel %q", name)
}

func (l LockLevel) MarshalText() ([]byte, error) { return []byte(l.String()), nil }

func (l *LockLevel) UnmarshalText(text []byte) error {
	parsed, err := ParseLockLevel(string(text))
	if err != nil {
		return err
	}
	*l = parsed
	return nil
}

// ChangeHazard is the operational hazard metadata of a PlannedChange
// (spec 4.4): the table lock the statements imply, whether the table is
// rewritten (a full rewrite is I/O proportional to table size), whether
// existing rows are validated, and free-form notes. The zero value is
// HazardNone — a change whose statements carry no lock beyond statement
// scope.
type ChangeHazard struct {
	LockLevel     LockLevel
	RewritesTable bool
	ValidatesRows bool
	Notes         []string
}

// HazardNone is the default: no lock, no rewrite, no validation, no notes.
var HazardNone = ChangeHazard{}

// IsTrivial reports whether this hazard carries anything beyond HazardNone.
func (h ChangeHazard) IsTrivial() bool {
	return h.LockLevel == LockNone && !h.RewritesTable && !h.ValidatesRows && len(h.Notes) == 0
}

// MarshalJSON writes the spec-4.4 hazard object; a nil Notes becomes [].
func (h ChangeHazard) MarshalJSON() ([]byte, error) {
	notes := h.Notes
	if notes == nil {
		notes = []string{}
	}
	return json.Marshal(struct {
		LockLevel     LockLevel `json:"lockLevel"`
		RewritesTable bool      `json:"rewritesTable"`
		ValidatesRows bool      `json:"validatesRows"`
		Notes         []string  `json:"notes"`
	}{h.LockLevel, h.RewritesTable, h.ValidatesRows, notes})
}

// UnmarshalJSON reads the hazard object; absent members default per spec 4.4
// (lockLevel NONE, empty notes).
func (h *ChangeHazard) UnmarshalJSON(data []byte) error {
	var aux struct {
		LockLevel     string   `json:"lockLevel"`
		RewritesTable bool     `json:"rewritesTable"`
		ValidatesRows bool     `json:"validatesRows"`
		Notes         []string `json:"notes"`
	}
	if err := json.Unmarshal(data, &aux); err != nil {
		return invalidArgf("%s", err)
	}
	level := LockNone
	if aux.LockLevel != "" {
		parsed, err := ParseLockLevel(aux.LockLevel)
		if err != nil {
			return err
		}
		level = parsed
	}
	notes := aux.Notes
	if notes == nil {
		notes = []string{}
	}
	*h = ChangeHazard{LockLevel: level, RewritesTable: aux.RewritesTable,
		ValidatesRows: aux.ValidatesRows, Notes: notes}
	return nil
}

// PlannedChange is one planned unit of DDL (spec 4.4).
//
// Order is the 1-based execution order; it is 0 on changes returned by
// Dialect.Render, before the core assigns the final order. Expected is the
// definition the target must have after apply; it is nil for ChangeDrop
// changes and changes against unexpected objects. NonTransactional marks a
// change that cannot run inside a transaction — the engine applies it after
// the transactional phase commits.
type PlannedChange struct {
	Order            int
	Target           ObjectId
	Action           ChangeAction
	Safety           ChangeSafety
	Statements       []string
	Expected         *Definition
	Reason           string
	NonTransactional bool
	Hazard           ChangeHazard
}

// NewPlannedChange builds a validated change (spec 4.4): order must be
// non-negative and a ChangeDrop carries no expected definition.
func NewPlannedChange(order int, target ObjectId, action ChangeAction,
	safety ChangeSafety, statements []string, expected *Definition,
	reason string, nonTransactional bool, hazard ChangeHazard) (PlannedChange, error) {
	c := PlannedChange{
		Order: order, Target: target, Action: action, Safety: safety,
		Statements: statements, Expected: expected, Reason: reason,
		NonTransactional: nonTransactional, Hazard: hazard,
	}
	if err := c.Validate(); err != nil {
		return PlannedChange{}, err
	}
	return c, nil
}

// Validate enforces the invariants of spec 4.4.
func (c PlannedChange) Validate() error {
	if c.Order < 0 {
		return invalidArgf("order must not be negative")
	}
	if err := c.Target.Validate(); err != nil {
		return err
	}
	if c.Action == ChangeDrop && c.Expected != nil {
		return invalidArgf("a DROP change has no expected definition")
	}
	return nil
}

// IsManual reports a change the dialect can describe but not perform safely
// (spec 3.3): it has no statements.
func (c PlannedChange) IsManual() bool {
	return len(c.Statements) == 0
}
