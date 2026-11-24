package reconciler

import (
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// ReconcileListener receives the run's callbacks (spec 3.6), invoked on the
// reconciling goroutine. A callback returning a non-nil error aborts the run
// as ApplyError carrying the changes applied so far — even from Planned,
// before anything is applied; Plan() aborts as PlanningError instead. A
// panic is treated the same way. In transactional dialects the abort rolls
// everything back.
//
// The interface mirrors Java's default-method listener: implementations must
// provide all four methods. ListenerFuncs adapts standalone functions, with
// nil fields acting as no-ops — the common "implement only what you need"
// form.
type ReconcileListener interface {
	// Planned is called once the plan is computed.
	Planned(plan *model.ReconcilePlan) error
	// Applying is called before a change's statements execute.
	Applying(change model.PlannedChange) error
	// Applied is called after a change executed, with its elapsed time.
	Applied(change model.PlannedChange, elapsed time.Duration) error
	// Reported is called for each change the policy mapped to REPORT.
	Reported(change model.PlannedChange) error
}

// ListenerFuncs adapts functions to ReconcileListener; a nil field is a
// no-op for that callback.
type ListenerFuncs struct {
	PlannedFunc  func(plan *model.ReconcilePlan) error
	ApplyingFunc func(change model.PlannedChange) error
	AppliedFunc  func(change model.PlannedChange, elapsed time.Duration) error
	ReportedFunc func(change model.PlannedChange) error
}

// Planned implements ReconcileListener.
func (l ListenerFuncs) Planned(plan *model.ReconcilePlan) error {
	if l.PlannedFunc != nil {
		return l.PlannedFunc(plan)
	}
	return nil
}

// Applying implements ReconcileListener.
func (l ListenerFuncs) Applying(change model.PlannedChange) error {
	if l.ApplyingFunc != nil {
		return l.ApplyingFunc(change)
	}
	return nil
}

// Applied implements ReconcileListener.
func (l ListenerFuncs) Applied(change model.PlannedChange, elapsed time.Duration) error {
	if l.AppliedFunc != nil {
		return l.AppliedFunc(change, elapsed)
	}
	return nil
}

// Reported implements ReconcileListener.
func (l ListenerFuncs) Reported(change model.PlannedChange) error {
	if l.ReportedFunc != nil {
		return l.ReportedFunc(change)
	}
	return nil
}
