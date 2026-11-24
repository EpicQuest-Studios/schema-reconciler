package reconciler

import (
	"fmt"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// The error hierarchy of spec 7. SchemaReconcilerError is the base type; the
// concrete subtypes embed it and add carried data. Sentinel values below let
// callers classify with errors.Is, and every subtype is extractable with
// errors.As:
//
//	errors.Is(err, reconciler.ErrPlanning)        // any *PlanningError
//	errors.Is(err, reconciler.ErrSchemaReconciler) // any reconciler failure
//	var apply *reconciler.ApplyError; errors.As(err, &apply)
var (
	// ErrSchemaReconciler matches every failure raised by the reconciler —
	// the "catch SchemaReconcilerException" analog.
	ErrSchemaReconciler = &SchemaReconcilerError{}
	// ErrConfiguration matches *ConfigurationError.
	ErrConfiguration = &ConfigurationError{}
	// ErrLockTimeout matches *LockTimeoutError.
	ErrLockTimeout = &LockTimeoutError{}
	// ErrDesiredState matches *DesiredStateError.
	ErrDesiredState = &DesiredStateError{}
	// ErrPlanning matches *PlanningError.
	ErrPlanning = &PlanningError{}
	// ErrPolicyViolation matches *PolicyViolationError.
	ErrPolicyViolation = &PolicyViolationError{}
	// ErrStalePlan matches *StalePlanError.
	ErrStalePlan = &StalePlanError{}
	// ErrApply matches *ApplyError.
	ErrApply = &ApplyError{}
	// ErrVerification matches *VerificationError.
	ErrVerification = &VerificationError{}
)

// SchemaReconcilerError is the base of all errors raised by the reconciler
// (spec 7). It carries the message, the wrapped cause and the RunTimings
// snapshot taken when the run failed — zero (UnmeasuredTimings) for failures
// raised before or outside a run.
type SchemaReconcilerError struct {
	message string
	cause   error
	timings RunTimings
}

// NewSchemaReconcilerError builds a base error; use NewXxxError for the
// concrete subtypes.
func NewSchemaReconcilerError(format string, args ...any) *SchemaReconcilerError {
	return &SchemaReconcilerError{message: fmt.Sprintf(format, args...)}
}

func (e *SchemaReconcilerError) Error() string { return e.message }

// Unwrap returns the wrapped cause, for errors.Is/errors.As chains.
func (e *SchemaReconcilerError) Unwrap() error { return e.cause }

// Timings returns the phases the failed run completed before throwing; zero
// for phases it never reached. Always present — UnmeasuredTimings when the
// failure predates the run's instrumentation.
func (e *SchemaReconcilerError) Timings() RunTimings { return e.timings }

// WithCause attaches a wrapped driver error; returns the same error.
func (e *SchemaReconcilerError) WithCause(cause error) *SchemaReconcilerError {
	e.cause = cause
	return e
}

// WithTimings attaches run timings to this failure; returns the same error.
func (e *SchemaReconcilerError) WithTimings(timings RunTimings) *SchemaReconcilerError {
	e.timings = timings
	return e
}

// withTimings is the engine's uniform timings hook: promoted through the
// embedded base to every concrete subtype, it lets the run attach
// clock.snapshot() to any escaping reconciler error in one place.
func (e *SchemaReconcilerError) withTimings(timings RunTimings) {
	e.timings = timings
}

// timingAttacher is satisfied by every reconciler error type (via the
// embedded base) — the analog of TS's `e.withTimings(clock.snapshot())`.
type timingAttacher interface{ withTimings(RunTimings) }

// attachTimings sets the run's phase snapshot on a reconciler error and
// returns it; non-reconciler errors pass through unchanged.
func attachTimings(err error, timings RunTimings) error {
	if e, ok := err.(timingAttacher); ok {
		e.withTimings(timings)
	}
	return err
}

// Is lets errors.Is(err, ErrSchemaReconciler) match every reconciler failure.
func (e *SchemaReconcilerError) Is(target error) bool {
	_, ok := target.(*SchemaReconcilerError)
	return ok
}

// matchesSchemaError implements the subtype test shared by every concrete
// error: errors.Is matches the sentinel of the error's own type or the
// ErrSchemaReconciler base sentinel.
func matchesSchemaError(self, target error) bool {
	if _, ok := target.(*SchemaReconcilerError); ok {
		return true
	}
	// A sentinel of a subtype compares by type only.
	switch target.(type) {
	case *ConfigurationError:
		_, ok := self.(*ConfigurationError)
		return ok
	case *LockTimeoutError:
		_, ok := self.(*LockTimeoutError)
		return ok
	case *DesiredStateError:
		_, ok := self.(*DesiredStateError)
		return ok
	case *PlanningError:
		_, ok := self.(*PlanningError)
		return ok
	case *PolicyViolationError:
		_, ok := self.(*PolicyViolationError)
		return ok
	case *StalePlanError:
		_, ok := self.(*StalePlanError)
		return ok
	case *ApplyError:
		_, ok := self.(*ApplyError)
		return ok
	case *VerificationError:
		_, ok := self.(*VerificationError)
		return ok
	}
	return false
}

// ConfigurationError is raised for invalid builder input, a missing or
// ambiguous dialect, an illegal configured namespace, or an unreadable
// desired-state resource (spec 7).
type ConfigurationError struct{ SchemaReconcilerError }

// NewConfigurationError builds the error with a formatted message.
func NewConfigurationError(message string) *ConfigurationError {
	return &ConfigurationError{SchemaReconcilerError{message: message}}
}

func NewConfigurationErrorf(format string, args ...any) *ConfigurationError {
	return &ConfigurationError{SchemaReconcilerError{message: fmt.Sprintf(format, args...)}}
}

func (e *ConfigurationError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *ConfigurationError) WithCause(cause error) *ConfigurationError {
	e.cause = cause
	return e
}
func (e *ConfigurationError) WithTimings(t RunTimings) *ConfigurationError {
	e.timings = t
	return e
}

// LockTimeoutError is raised when the namespace lock was not acquired within
// the configured lock timeout (spec 7).
type LockTimeoutError struct{ SchemaReconcilerError }

// NewLockTimeoutError builds the error with a formatted message.
func NewLockTimeoutError(message string) *LockTimeoutError {
	return &LockTimeoutError{SchemaReconcilerError{message: message}}
}

func NewLockTimeoutErrorf(format string, args ...any) *LockTimeoutError {
	return &LockTimeoutError{SchemaReconcilerError{message: fmt.Sprintf(format, args...)}}
}

func (e *LockTimeoutError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *LockTimeoutError) WithCause(cause error) *LockTimeoutError {
	e.cause = cause
	return e
}
func (e *LockTimeoutError) WithTimings(t RunTimings) *LockTimeoutError {
	e.timings = t
	return e
}

// DesiredStateError is raised when the desired script could not be read,
// substituted or executed in the scratch namespace (spec 7). The message
// includes the engine's error message and position when the driver provides
// them.
type DesiredStateError struct{ SchemaReconcilerError }

// NewDesiredStateError builds the error with a formatted message.
func NewDesiredStateError(message string) *DesiredStateError {
	return &DesiredStateError{SchemaReconcilerError{message: message}}
}

func NewDesiredStateErrorf(format string, args ...any) *DesiredStateError {
	return &DesiredStateError{SchemaReconcilerError{message: fmt.Sprintf(format, args...)}}
}

func (e *DesiredStateError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *DesiredStateError) WithCause(cause error) *DesiredStateError {
	e.cause = cause
	return e
}
func (e *DesiredStateError) WithTimings(t RunTimings) *DesiredStateError {
	e.timings = t
	return e
}

// PlanningError is raised for a dependency cycle, an external or unexpected
// dependent, a name collision, or an unrenderable difference (spec 7).
type PlanningError struct{ SchemaReconcilerError }

// NewPlanningError builds the error with a formatted message.
func NewPlanningError(message string) *PlanningError {
	return &PlanningError{SchemaReconcilerError{message: message}}
}

func NewPlanningErrorf(format string, args ...any) *PlanningError {
	return &PlanningError{SchemaReconcilerError{message: fmt.Sprintf(format, args...)}}
}

func (e *PlanningError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *PlanningError) WithCause(cause error) *PlanningError {
	e.cause = cause
	return e
}
func (e *PlanningError) WithTimings(t RunTimings) *PlanningError {
	e.timings = t
	return e
}

// PolicyViolationError is raised when the plan contains changes mapped to
// FAIL, before any change is executed; it carries the plan (spec 7).
type PolicyViolationError struct {
	SchemaReconcilerError
	// Plan is the offending plan.
	Plan *model.ReconcilePlan
}

// NewPolicyViolationError builds the error carrying the rejected plan.
func NewPolicyViolationError(message string, plan *model.ReconcilePlan) *PolicyViolationError {
	return &PolicyViolationError{SchemaReconcilerError{message: message}, plan}
}

func (e *PolicyViolationError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *PolicyViolationError) WithCause(cause error) *PolicyViolationError {
	e.cause = cause
	return e
}
func (e *PolicyViolationError) WithTimings(t RunTimings) *PolicyViolationError {
	e.timings = t
	return e
}

// StalePlanError is raised when the target changed between Plan() and Apply
// (spec 7).
type StalePlanError struct {
	SchemaReconcilerError
	// Plan is the stale plan, when the failure is bound to one; nil otherwise.
	Plan *model.ReconcilePlan
}

// NewStalePlanError builds the error; plan may be nil.
func NewStalePlanError(message string, plan *model.ReconcilePlan) *StalePlanError {
	return &StalePlanError{SchemaReconcilerError{message: message}, plan}
}

func (e *StalePlanError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *StalePlanError) WithCause(cause error) *StalePlanError {
	e.cause = cause
	return e
}
func (e *StalePlanError) WithTimings(t RunTimings) *StalePlanError {
	e.timings = t
	return e
}

// ApplyError is raised when a statement failed, or a listener aborted the
// run (spec 7). It carries the changes applied so far (possibly none) and,
// when known, the change being processed. On a transactional-DDL dialect the
// whole run is rolled back, so the applied changes are not persisted.
type ApplyError struct {
	SchemaReconcilerError
	// Applied are the changes already applied when the failure happened.
	Applied []model.PlannedChange
	// Failed is the change being processed, if the failure is bound to one.
	Failed *model.PlannedChange
}

// NewApplyError builds the error carrying the applied changes and the failed
// one (nil when the failure is not bound to a change).
func NewApplyError(message string, applied []model.PlannedChange,
	failed *model.PlannedChange) *ApplyError {
	return &ApplyError{SchemaReconcilerError{message: message}, applied, failed}
}

func (e *ApplyError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *ApplyError) WithCause(cause error) *ApplyError {
	e.cause = cause
	return e
}
func (e *ApplyError) WithTimings(t RunTimings) *ApplyError {
	e.timings = t
	return e
}

// VerificationError is raised when applied objects did not converge; it
// carries the remaining differences (spec 7).
type VerificationError struct {
	SchemaReconcilerError
	// Remaining lists each object that did not converge.
	Remaining []model.Difference
}

// NewVerificationError builds the error carrying the remaining differences.
func NewVerificationError(message string, remaining []model.Difference) *VerificationError {
	return &VerificationError{SchemaReconcilerError{message: message}, remaining}
}

func (e *VerificationError) Is(target error) bool { return matchesSchemaError(e, target) }
func (e *VerificationError) WithCause(cause error) *VerificationError {
	e.cause = cause
	return e
}
func (e *VerificationError) WithTimings(t RunTimings) *VerificationError {
	e.timings = t
	return e
}
