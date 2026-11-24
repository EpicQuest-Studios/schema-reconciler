package reconciler

import (
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// ReconcileReport is the result of Reconcile() or Apply() (spec 3.5).
type ReconcileReport struct {
	Outcome ReconcileOutcome
	Plan    *model.ReconcilePlan
	// Applied lists the changes executed and verified.
	Applied []model.PlannedChange
	// Reported lists the changes the policy mapped to or demoted to REPORT.
	Reported []model.PlannedChange
	// Unexpected lists the UNEXPECTED differences (spec 5.5).
	Unexpected []model.Difference
	// Unsupported lists the UNSUPPORTED differences reported under a REPORT
	// policy (spec 3.3).
	Unsupported []model.Difference
	// Elapsed is the run's total monotonic duration.
	Elapsed time.Duration
	// Timings carries the per-phase measurements of the run.
	Timings RunTimings
}

// MultiReconcileReport is the result of MultiNamespaceReconciler.Reconcile
// (spec 3.7): one ReconcileReport per configured namespace plus the
// aggregate Outcome — OutcomeReconciled when any namespace reconciled, else
// OutcomeReportedOnly when any reported, else OutcomeUpToDate.
type MultiReconcileReport struct {
	Outcome ReconcileOutcome
	// Reports is keyed by namespace name.
	Reports map[string]ReconcileReport
	// Namespaces preserves the configured namespace order of Reports — the
	// analog of the Java map's insertion ordering.
	Namespaces []string
	// Elapsed is the call's total monotonic run time.
	Elapsed time.Duration
}
