package reconciler

import (
	"context"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// HookPhase is the lifecycle phase a schema hook runs in (spec 3.8).
type HookPhase int

const (
	HookPreApply HookPhase = iota
	HookPostApply
	HookPostCommit
)

func (p HookPhase) String() string {
	switch p {
	case HookPreApply:
		return "PRE_APPLY"
	case HookPostApply:
		return "POST_APPLY"
	case HookPostCommit:
		return "POST_COMMIT"
	default:
		return "UNKNOWN"
	}
}

// HookContext is passed to a schema hook. Conn is the run's pinned executor,
// inside the transaction for PRE_APPLY/POST_APPLY and in auto-commit mode
// for POST_COMMIT.
type HookContext struct {
	Namespace string
	DialectID string
	Phase     HookPhase
	Plan      *model.ReconcilePlan
	Applied   []model.PlannedChange
	Conn      spi.Executor
}

// SchemaHook is an ordered lifecycle hook (spec 3.8). Hooks sort by phase,
// then Order, then ID.
type SchemaHook struct {
	ID       string
	Phase    HookPhase
	Order    int
	RunOnce  bool
	Checksum string
	Run      func(ctx context.Context, hc HookContext) error
}

// HookStore stores durable execution records for checksum-protected hooks.
type HookStore interface {
	RecordedChecksum(ctx context.Context, exec spi.Executor, namespace, hookID string) (string, bool, error)
	Record(ctx context.Context, exec spi.Executor, namespace, hookID string, phase HookPhase, checksum string) error
	ManagedTable(targetNamespace string) *model.ObjectId
}
