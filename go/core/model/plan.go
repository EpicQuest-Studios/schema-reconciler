package model

import (
	"fmt"
	"strings"
)

// ReconcilePlan is an inspectable plan (spec 4.4). ToSQL serialises it as
// plain text — a deterministic header plus one comment block per change;
// ToJSON serialises it as machine-readable JSON (schemaVersion 1) for
// later cross-process apply. Both are byte-identical for identical input
// (spec 5.2).
type ReconcilePlan struct {
	TargetNamespace    string
	DialectID          string
	ActualFingerprint  string
	DesiredFingerprint string
	Differences        []Difference
	Changes            []PlannedChange
}

// IsEmpty reports whether the plan has no changes.
func (p ReconcilePlan) IsEmpty() bool {
	return len(p.Changes) == 0
}

// ToSQL renders the plan as reviewable SQL text (spec 4.4): the deterministic
// header comment naming targetNamespace, dialectId and both fingerprints plus
// the session context the statements assume, then each change in plan order
// preceded by a comment with its order, target, action, safety, hazard and
// reason; manual changes are marked MANUAL. No timestamps — two runs of the
// same input produce byte-identical output.
func (p ReconcilePlan) ToSQL() string {
	var out strings.Builder
	out.WriteString("-- schema reconciler plan\n")
	fmt.Fprintf(&out, "-- target namespace: %s\n", p.TargetNamespace)
	fmt.Fprintf(&out, "-- dialect: %s\n", p.DialectID)
	fmt.Fprintf(&out, "-- actual fingerprint: %s\n", p.ActualFingerprint)
	fmt.Fprintf(&out, "-- desired fingerprint: %s\n", p.DesiredFingerprint)
	fmt.Fprintf(&out, "-- session context: unqualified statements assume namespace \"%s\"\n",
		p.TargetNamespace)
	for _, change := range p.Changes {
		fmt.Fprintf(&out, "\n-- [%d] %s %s (%s)",
			change.Order, change.Action, change.Target, change.Safety)
		if change.IsManual() {
			out.WriteString(" MANUAL")
		}
		hazard := change.Hazard
		if change.NonTransactional || !hazard.IsTrivial() {
			var parts []string
			if hazard.LockLevel != LockNone {
				parts = append(parts, "lock "+hazard.LockLevel.String())
			}
			if hazard.RewritesTable {
				parts = append(parts, "rewrites table")
			}
			if hazard.ValidatesRows {
				parts = append(parts, "validates rows")
			}
			if change.NonTransactional {
				parts = append(parts, "non-transactional")
			}
			parts = append(parts, hazard.Notes...)
			out.WriteString("; hazard: " + strings.Join(parts, "; "))
		}
		out.WriteByte('\n')
		out.WriteString("-- " + change.Reason + "\n")
		for _, statement := range change.Statements {
			stmt := strings.TrimSpace(statement)
			out.WriteString(stmt)
			if !strings.HasSuffix(stmt, ";") {
				out.WriteByte(';')
			}
			out.WriteByte('\n')
		}
	}
	return out.String()
}
