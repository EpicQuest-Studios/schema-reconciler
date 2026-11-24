package reconciler

import (
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// RunTimings is the per-phase monotonic elapsed times of one reconcile run
// (spec 3.5), for observing lock-holding and apply duration on a realistic
// database. LockWait is the time spent acquiring the namespace lock —
// contention shows there; Apply is the window in which DDL locks are held
// (they release at commit, after Verify). The zero value is the unmeasured
// timings of a failure raised before instrumentation — the analog of
// RunTimings.unmeasured().
type RunTimings struct {
	LockWait    time.Duration
	ReadActual  time.Duration
	LoadDesired time.Duration
	Plan        time.Duration
	Apply       time.Duration
	Verify      time.Duration
}

// UnmeasuredTimings returns the zero timings used for reports built without
// measurement.
func UnmeasuredTimings() RunTimings {
	return RunTimings{}
}

// TimedPlan is a plan together with the phase timings of the planning run
// (spec 3.5). Its Apply and Verify phases are zero: planning applies nothing.
type TimedPlan struct {
	Plan    *model.ReconcilePlan
	Timings RunTimings
}
