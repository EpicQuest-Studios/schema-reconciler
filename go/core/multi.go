package reconciler

import (
	"context"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// MultiNamespaceReconciler reconciles several namespaces in one call
// (spec 3.7): each registered namespace gets its own SchemaReconciler built
// with the shared configuration, and Reconcile runs them in registration
// order so a namespace whose desired script references an earlier one — a
// cross-schema foreign key — sees it already reconciled. Instances are
// immutable and safe for concurrent use; concurrent runs against the same
// target namespaces are serialised by each namespace's lock (spec 2.3.6).
type MultiNamespaceReconciler struct {
	reconcilers map[string]*SchemaReconciler
	order       []string
}

// NewMultiNamespaceBuilder starts a MultiNamespaceReconciler builder
// (spec 3.7).
func NewMultiNamespaceBuilder() *MultiNamespaceBuilder {
	return &MultiNamespaceBuilder{
		policy:      DefaultPolicy(),
		lockTimeout: 60 * time.Second,
		listener:    ListenerFuncs{},
		filter:      func(model.ObjectId) bool { return true },
	}
}

// Namespaces returns the configured namespaces in registration order.
func (m *MultiNamespaceReconciler) Namespaces() []string {
	return append([]string(nil), m.order...)
}

// Plan runs one Plan per namespace, in configured order, keyed by namespace
// name; iterate Namespaces for the configured order. It stops at the first
// failure.
func (m *MultiNamespaceReconciler) Plan(ctx context.Context) (map[string]*model.ReconcilePlan, error) {
	out := make(map[string]*model.ReconcilePlan, len(m.order))
	for _, ns := range m.order {
		plan, err := m.reconcilers[ns].Plan(ctx)
		if err != nil {
			return nil, err
		}
		out[ns] = plan
	}
	return out, nil
}

// Reconcile runs one Reconcile per namespace in configured order — each
// holds its own namespace lock only for its own run. It fails fast: the
// first error aborts the run; namespaces already reconciled stay reconciled.
func (m *MultiNamespaceReconciler) Reconcile(ctx context.Context) (MultiReconcileReport, error) {
	start := time.Now()
	reports := make(map[string]ReconcileReport, len(m.order))
	outcome := OutcomeUpToDate
	for _, ns := range m.order {
		report, err := m.reconcilers[ns].Reconcile(ctx)
		if err != nil {
			return MultiReconcileReport{}, err
		}
		reports[ns] = report
		switch report.Outcome {
		case OutcomeReconciled:
			outcome = OutcomeReconciled
		case OutcomeReportedOnly:
			if outcome != OutcomeReconciled {
				outcome = OutcomeReportedOnly
			}
		}
	}
	return MultiReconcileReport{
		Outcome:    outcome,
		Reports:    reports,
		Namespaces: append([]string(nil), m.order...),
		Elapsed:    time.Since(start),
	}, nil
}

// Dump runs one Dump per namespace, in configured order, keyed by namespace
// name; iterate Namespaces for the configured order.
func (m *MultiNamespaceReconciler) Dump(ctx context.Context) (map[string]string, error) {
	out := make(map[string]string, len(m.order))
	for _, ns := range m.order {
		dump, err := m.reconcilers[ns].Dump(ctx)
		if err != nil {
			return nil, err
		}
		out[ns] = dump
	}
	return out, nil
}

type namespaceRegistration struct {
	name      string
	desired   *DesiredState
	customize func(*Builder)
}

// NamespaceOption adjusts one registered namespace — see WithDesiredState
// and WithCustomize.
type NamespaceOption func(*namespaceRegistration)

// WithDesiredState sets the namespace's desired-state script; may be omitted
// only when the reconciler is built for Dump alone (spec 3.1).
func WithDesiredState(desiredState DesiredState) NamespaceOption {
	return func(r *namespaceRegistration) { r.desired = &desiredState }
}

// WithCustomize adjusts this namespace's Builder after the shared defaults
// are applied — the analog of the Java builder's customize consumer.
func WithCustomize(customize func(*Builder)) NamespaceOption {
	return func(r *namespaceRegistration) { r.customize = customize }
}

// MultiNamespaceBuilder assembles a MultiNamespaceReconciler — the analog of
// MultiNamespaceReconciler.Builder (spec 3.7): shared defaults forwarded to
// each namespace's Builder.
type MultiNamespaceBuilder struct {
	dataSource        spi.DB
	scratchDataSource spi.DB
	dialect           spi.Dialect
	policy            ReconcilePolicy
	lockTimeout       time.Duration
	statementTimeout  *time.Duration
	listener          ReconcileListener
	filter            ObjectFilter
	concurrentIndexes bool
	namespaces        []namespaceRegistration
}

// DataSource sets the shared pool (required).
func (b *MultiNamespaceBuilder) DataSource(db spi.DB) *MultiNamespaceBuilder {
	b.dataSource = db
	return b
}

// ScratchDataSource routes scratch namespaces to a separate database
// (spec 6.3).
func (b *MultiNamespaceBuilder) ScratchDataSource(db spi.DB) *MultiNamespaceBuilder {
	b.scratchDataSource = db
	return b
}

// Dialect sets the shared explicit dialect (spec 3.4).
func (b *MultiNamespaceBuilder) Dialect(d spi.Dialect) *MultiNamespaceBuilder {
	b.dialect = d
	return b
}

// Policy sets the shared reconcile policy; the default is DefaultPolicy.
func (b *MultiNamespaceBuilder) Policy(policy ReconcilePolicy) *MultiNamespaceBuilder {
	b.policy = policy
	return b
}

// LockTimeout sets the shared namespace-lock bound; the default is 60 s.
func (b *MultiNamespaceBuilder) LockTimeout(timeout time.Duration) *MultiNamespaceBuilder {
	b.lockTimeout = timeout
	return b
}

// StatementTimeout bounds single statements during every run; unset (the
// default) leaves them unbounded, a non-positive value fails at Build.
func (b *MultiNamespaceBuilder) StatementTimeout(timeout time.Duration) *MultiNamespaceBuilder {
	b.statementTimeout = &timeout
	return b
}

// Listener sets the shared listener; the default is a no-op.
func (b *MultiNamespaceBuilder) Listener(listener ReconcileListener) *MultiNamespaceBuilder {
	if listener != nil {
		b.listener = listener
	}
	return b
}

// Filter limits which catalog objects participate in reconciliation; the
// default includes every object.
func (b *MultiNamespaceBuilder) Filter(include ObjectFilter) *MultiNamespaceBuilder {
	if include != nil {
		b.filter = include
	}
	return b
}

// ConcurrentIndexes enables concurrent index rendering where the dialect
// supports it; default false.
func (b *MultiNamespaceBuilder) ConcurrentIndexes(enabled bool) *MultiNamespaceBuilder {
	b.concurrentIndexes = enabled
	return b
}

// Namespace registers a target namespace, preserving execution order. Use
// WithDesiredState to give it a script — required for plan/reconcile/apply —
// and WithCustomize for per-namespace overrides.
func (b *MultiNamespaceBuilder) Namespace(name string, opts ...NamespaceOption) *MultiNamespaceBuilder {
	reg := namespaceRegistration{name: name}
	for _, opt := range opts {
		if opt != nil {
			opt(&reg)
		}
	}
	b.namespaces = append(b.namespaces, reg)
	return b
}

// Build validates the shared configuration and builds every registered
// namespace's SchemaReconciler up front — so a per-namespace configuration
// error fails at Build without opening a connection (spec 3.7).
func (b *MultiNamespaceBuilder) Build() (*MultiNamespaceReconciler, error) {
	if b.dataSource == nil {
		return nil, NewConfigurationError("dataSource is required")
	}
	if len(b.namespaces) == 0 {
		return nil, NewConfigurationError("at least one namespace is required")
	}
	reconcilers := make(map[string]*SchemaReconciler, len(b.namespaces))
	order := make([]string, 0, len(b.namespaces))
	for _, reg := range b.namespaces {
		if reg.name == "" {
			return nil, NewConfigurationError("namespace must not be empty")
		}
		if _, dup := reconcilers[reg.name]; dup {
			return nil, NewConfigurationErrorf("duplicate namespace %s", reg.name)
		}
		child := NewBuilder().
			DataSource(b.dataSource).
			Namespace(reg.name).
			Policy(b.policy).
			LockTimeout(b.lockTimeout).
			Listener(b.listener).
			Filter(b.filter).
			ConcurrentIndexes(b.concurrentIndexes)
		if b.scratchDataSource != nil {
			child.ScratchDataSource(b.scratchDataSource)
		}
		if b.dialect != nil {
			child.Dialect(b.dialect)
		}
		if b.statementTimeout != nil {
			child.StatementTimeout(*b.statementTimeout)
		}
		if reg.desired != nil {
			child.DesiredState(*reg.desired)
		}
		if reg.customize != nil {
			reg.customize(child)
		}
		r, err := child.Build()
		if err != nil {
			return nil, err
		}
		reconcilers[reg.name] = r
		order = append(order, reg.name)
	}
	return &MultiNamespaceReconciler{reconcilers: reconcilers, order: order}, nil
}
