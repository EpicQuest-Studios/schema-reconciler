package reconciler

import (
	"context"
	"strings"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// SchemaReconciler is the entry point of the reconciler (spec 3.1). Build
// one with NewBuilder. Instances are immutable and safe for concurrent use;
// concurrent runs against the same target namespace are serialised by the
// namespace lock (spec 2.3.6).
type SchemaReconciler struct {
	engine *engine
}

type reconcilerConfig struct {
	dataSource         spi.DB
	scratchDataSource  spi.DB
	namespace          string
	desiredState       *DesiredState
	dialect            spi.Dialect
	policy             ReconcilePolicy
	lockTimeout        time.Duration
	listener           ReconcileListener
	filter             ObjectFilter
	concurrentIndexes  bool
	renameHints        []model.RenameHint
	retireTargets      []model.ObjectId
	externalPartitions []externalPartitionMatcher
	pruneFilter        ObjectFilter
	hooks              []SchemaHook
	hookStore          HookStore

	// statementTimeout is a pointer because the option is itself optional:
	// nil means "unbounded"; once set it must be positive (spec 3.1).
	statementTimeout *time.Duration
}

// NewBuilder starts a SchemaReconciler builder (spec 3.1).
func NewBuilder() *Builder {
	return &Builder{cfg: reconcilerConfig{
		policy:      DefaultPolicy(),
		lockTimeout: 60 * time.Second,
		listener:    ListenerFuncs{},
		filter:      func(model.ObjectId) bool { return true },
	}}
}

// ObjectFilter limits which catalog objects participate in reconciliation,
// in both the desired and the actual snapshot (spec 3.1 filter): an object
// the predicate rejects produces no difference, no change and no
// unexpected-object report, and its children, mirrored annotations and
// follower objects are invisible with it. The default filter includes every
// object. It is how a caller coexists with objects another tool manages —
// not a safety valve.
type ObjectFilter func(id model.ObjectId) bool

// Builder assembles a SchemaReconciler — the analog of
// SchemaReconciler.Builder (spec 3.1). Setter failures the Java API would
// surface eagerly are collected at Build as errors instead.
type Builder struct {
	cfg     reconcilerConfig
	hintErr error
}

// DataSource sets the pool the reconciler pins run connections from
// (required). Adapt a *sql.DB with spi.NewDB.
func (b *Builder) DataSource(db spi.DB) *Builder {
	b.cfg.dataSource = db
	return b
}

// Namespace sets the target namespace (required; empty counts as missing).
func (b *Builder) Namespace(targetNamespace string) *Builder {
	b.cfg.namespace = targetNamespace
	return b
}

// DesiredState sets the desired-state script. Required for Plan, Reconcile
// and Apply; may be omitted when the reconciler is built for Dump alone —
// the other methods then fail with ConfigurationError (spec 3.1).
func (b *Builder) DesiredState(desiredState DesiredState) *Builder {
	b.cfg.desiredState = &desiredState
	return b
}

// Dialect sets the dialect explicitly; when unset, the engine selects among
// RegisteredDialects on first use (spec 3.4).
func (b *Builder) Dialect(d spi.Dialect) *Builder {
	b.cfg.dialect = d
	return b
}

// ScratchDataSource routes scratch-namespace creation and the desired-script
// replay to a separate database (spec 6.3).
func (b *Builder) ScratchDataSource(db spi.DB) *Builder {
	b.cfg.scratchDataSource = db
	return b
}

// Policy sets the reconcile policy; the default is DefaultPolicy.
func (b *Builder) Policy(policy ReconcilePolicy) *Builder {
	b.cfg.policy = policy
	return b
}

// LockTimeout sets the namespace-lock bound; the default is 60 s and it must
// be positive.
func (b *Builder) LockTimeout(timeout time.Duration) *Builder {
	b.cfg.lockTimeout = timeout
	return b
}

// StatementTimeout bounds how long any single statement may run during a
// run — the dialect applies it after the lock is acquired (spec 3.1), so a
// slow applied statement cannot hold its locks unboundedly. Unset (the
// default) leaves statements unbounded; setting a non-positive value fails
// at Build.
func (b *Builder) StatementTimeout(timeout time.Duration) *Builder {
	b.cfg.statementTimeout = &timeout
	return b
}

// ConcurrentIndexes renders index creates/drops in their concurrent,
// non-transactional form where the dialect supports it (PostgreSQL:
// CREATE INDEX CONCURRENTLY outside a transaction, spec 5.6). Default false.
func (b *Builder) ConcurrentIndexes(enabled bool) *Builder {
	b.cfg.concurrentIndexes = enabled
	return b
}

// Rename declares that from was renamed to to — the same hint the
// `-- @reconciler rename` script directive gives (spec 5.5).
func (b *Builder) Rename(from, to model.ObjectId) *Builder {
	hint, err := model.NewRenameHint(from, to)
	if err != nil {
		b.hintErr = err
		return b
	}
	b.cfg.renameHints = append(b.cfg.renameHints, hint)
	return b
}

// Retire marks id for targeted retirement even when the prune policy is off.
func (b *Builder) Retire(id model.ObjectId) *Builder {
	if err := id.Validate(); err != nil {
		b.hintErr = err
		return b
	}
	if id.Kind == model.KindOther {
		b.hintErr = model.InvalidArgumentErrorf("OTHER objects cannot be retired: %s", id)
		return b
	}
	b.cfg.retireTargets = append(b.cfg.retireTargets, id)
	return b
}

// ExternalPartitions coexists with every runtime partition directly parented on parentTable.
func (b *Builder) ExternalPartitions(parentTable string) *Builder {
	if parentTable == "" {
		b.hintErr = NewConfigurationError("parentTable must not be empty")
		return b
	}
	b.cfg.externalPartitions = append(b.cfg.externalPartitions,
		func(parent, partition string) bool { return parent == parentTable })
	return b
}

// ExternalPartitionsPrefix coexists with runtime partitions of parentTable whose names start with namePrefix.
func (b *Builder) ExternalPartitionsPrefix(parentTable, namePrefix string) *Builder {
	if parentTable == "" {
		b.hintErr = NewConfigurationError("parentTable must not be empty")
		return b
	}
	b.cfg.externalPartitions = append(b.cfg.externalPartitions,
		func(parent, partition string) bool {
			return parent == parentTable && strings.HasPrefix(partition, namePrefix)
		})
	return b
}

// ExternalPartitionsFunc coexists with runtime partitions accepted by match.
func (b *Builder) ExternalPartitionsFunc(match func(parentTable, partitionName string) bool) *Builder {
	if match == nil {
		b.hintErr = NewConfigurationError("external partition matcher must not be nil")
		return b
	}
	b.cfg.externalPartitions = append(b.cfg.externalPartitions, match)
	return b
}

// PruneFilter limits which unexpected objects prune may drop; visibility is unaffected.
func (b *Builder) PruneFilter(allowPrune ObjectFilter) *Builder {
	if allowPrune == nil {
		b.hintErr = NewConfigurationError("pruneFilter must not be nil")
		return b
	}
	b.cfg.pruneFilter = allowPrune
	return b
}

// Hook registers an ordered lifecycle hook (spec 3.8); repeatable.
func (b *Builder) Hook(hook SchemaHook) *Builder {
	b.cfg.hooks = append(b.cfg.hooks, hook)
	return b
}

// HookStore registers the durable store checksum-protected run-once hooks use.
func (b *Builder) HookStore(store HookStore) *Builder {
	if store == nil {
		b.hintErr = NewConfigurationError("hookStore must not be nil")
		return b
	}
	b.cfg.hookStore = store
	return b
}

// Listener sets the run's callback listener; the default is a no-op.
func (b *Builder) Listener(listener ReconcileListener) *Builder {
	if listener != nil {
		b.cfg.listener = listener
	}
	return b
}

// Filter limits which catalog objects participate in reconciliation;
// the default includes every object.
func (b *Builder) Filter(include ObjectFilter) *Builder {
	if include != nil {
		b.cfg.filter = include
	}
	return b
}

// Build validates the required properties and, when no dialect is set, that
// at least one dialect is registered — failing with ConfigurationError
// otherwise (spec 3.1). It opens no connection: dialect selection happens on
// first use (spec 3.4).
func (b *Builder) Build() (*SchemaReconciler, error) {
	if b.hintErr != nil {
		return nil, b.hintErr
	}
	if b.cfg.dataSource == nil {
		return nil, NewConfigurationError("dataSource is required")
	}
	if b.cfg.namespace == "" {
		return nil, NewConfigurationError("namespace is required")
	}
	if b.cfg.lockTimeout <= 0 {
		return nil, NewConfigurationError("lockTimeout must be positive")
	}
	if b.cfg.statementTimeout != nil && *b.cfg.statementTimeout <= 0 {
		return nil, NewConfigurationError("statementTimeout must be positive")
	}
	if b.cfg.dialect == nil && len(RegisteredDialects()) == 0 {
		return nil, NewConfigurationErrorf(
			"no Dialect provider registered for namespace %s", b.cfg.namespace)
	}
	if b.cfg.pruneFilter == nil {
		b.cfg.pruneFilter = func(model.ObjectId) bool { return true }
	}
	seenHooks := map[string]bool{}
	for _, hook := range b.cfg.hooks {
		if strings.TrimSpace(hook.ID) == "" {
			return nil, NewConfigurationError("hook id must not be blank")
		}
		if hook.Phase < HookPreApply || hook.Phase > HookPostCommit {
			return nil, NewConfigurationErrorf("hook '%s' has invalid phase %d", hook.ID, hook.Phase)
		}
		if hook.Run == nil {
			return nil, NewConfigurationErrorf("hook '%s' has no run action", hook.ID)
		}
		if seenHooks[hook.ID] {
			return nil, NewConfigurationErrorf("duplicate hook id '%s'", hook.ID)
		}
		seenHooks[hook.ID] = true
		if hook.RunOnce && b.cfg.hookStore == nil {
			return nil, NewConfigurationErrorf("hook '%s' is runOnce but no hookStore is configured", hook.ID)
		}
	}
	cfg := b.cfg
	cfg.renameHints = append([]model.RenameHint(nil), b.cfg.renameHints...)
	cfg.retireTargets = append([]model.ObjectId(nil), b.cfg.retireTargets...)
	cfg.externalPartitions = append([]externalPartitionMatcher(nil), b.cfg.externalPartitions...)
	cfg.hooks = append([]SchemaHook(nil), b.cfg.hooks...)
	return &SchemaReconciler{engine: newEngine(cfg)}, nil
}

// Plan computes a plan without changing the target namespace.
func (r *SchemaReconciler) Plan(ctx context.Context) (*model.ReconcilePlan, error) {
	return r.engine.Plan(ctx)
}

// PlanTimed is like Plan, also returning the phase timings of the planning
// run (spec 3.5).
func (r *SchemaReconciler) PlanTimed(ctx context.Context) (TimedPlan, error) {
	return r.engine.PlanTimed(ctx)
}

// Reconcile plans and applies under the configured policy, holding one
// namespace lock.
func (r *SchemaReconciler) Reconcile(ctx context.Context) (ReconcileReport, error) {
	return r.engine.Reconcile(ctx)
}

// Apply applies a plan previously returned by Plan; it fails if the target
// has changed since (spec 5.7). A nil plan is ErrInvalidArgument.
func (r *SchemaReconciler) Apply(ctx context.Context, plan *model.ReconcilePlan) (ReconcileReport, error) {
	if plan == nil {
		return ReconcileReport{}, model.InvalidArgumentErrorf("plan must not be nil")
	}
	return r.engine.Apply(ctx, plan)
}

// ApplyJSON applies a plan serialised by ReconcilePlan.ToJSON — the
// machine-readable counterpart of Apply. It fails on invalid JSON or when
// the target changed since the plan was issued.
func (r *SchemaReconciler) ApplyJSON(ctx context.Context, serializedPlan string) (ReconcileReport, error) {
	return r.engine.ApplyJSON(ctx, serializedPlan)
}

// Dump reads the target namespace under the namespace lock and renders it as
// a namespace-neutral desired-state script — the bootstrap path of spec 3.1.
// It requires no DesiredState; the target is never mutated.
func (r *SchemaReconciler) Dump(ctx context.Context) (string, error) {
	return r.engine.Dump(ctx)
}
