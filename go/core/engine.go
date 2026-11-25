package reconciler

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"runtime"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// planMeta is the metadata retained per issued plan for Apply.
type planMeta struct {
	desired         model.SchemaSnapshot
	coveredByParent map[model.ObjectId]map[model.ObjectId]model.Definition
}

// planMetaMap is the WeakMap<ReconcilePlan, PlanMeta> analog: it remembers
// the desired snapshot and covered-children expectations of an issued plan
// so Apply does not re-derive them. Entries are keyed by plan content (the
// plan JSON is canonical, so equal plans share a key and interchangeable
// metadata) and released by a runtime cleanup when the caller's plan
// pointer becomes unreachable.
type planMetaMap struct {
	mu      sync.Mutex
	entries map[string]planMeta
}

func newPlanMetaMap() *planMetaMap {
	return &planMetaMap{entries: map[string]planMeta{}}
}

func planKey(plan *model.ReconcilePlan) string {
	sum := sha256.Sum256(plan.ToJSON())
	return hex.EncodeToString(sum[:])
}

type planMetaCleanup struct {
	m   *planMetaMap
	key string
}

func (m *planMetaMap) set(plan *model.ReconcilePlan, meta planMeta) {
	key := planKey(plan)
	m.mu.Lock()
	m.entries[key] = meta
	m.mu.Unlock()
	runtime.AddCleanup(plan, func(arg planMetaCleanup) {
		arg.m.mu.Lock()
		delete(arg.m.entries, arg.key)
		arg.m.mu.Unlock()
	}, planMetaCleanup{m: m, key: key})
}

func (m *planMetaMap) get(plan *model.ReconcilePlan) (planMeta, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	meta, ok := m.entries[planKey(plan)]
	return meta, ok
}

// engine is the shared step machine of spec 5.1 behind Plan, Reconcile and
// Apply.
type engine struct {
	resolver           *dialectResolver
	planMeta           *planMetaMap
	dataSource         spi.DB
	scratchDataSource  spi.DB
	namespace          string
	desiredState       *DesiredState
	policy             ReconcilePolicy
	lockTimeout        time.Duration
	statementTimeout   *time.Duration
	listener           ReconcileListener
	objectFilter       ObjectFilter
	concurrentIndexes  bool
	renameHints        []model.RenameHint
	retireTargets      []model.ObjectId
	externalPartitions []externalPartitionMatcher
	pruneFilter        ObjectFilter
	hooks              []SchemaHook
	hookStore          HookStore
}

func sortedHooks(hooks []SchemaHook) []SchemaHook {
	out := append([]SchemaHook(nil), hooks...)
	slices.SortFunc(out, func(a, b SchemaHook) int {
		if a.Phase != b.Phase {
			return int(a.Phase) - int(b.Phase)
		}
		if a.Order != b.Order {
			return a.Order - b.Order
		}
		return strings.Compare(a.ID, b.ID)
	})
	return out
}

func newEngine(cfg reconcilerConfig) *engine {
	listener := cfg.listener
	if listener == nil {
		listener = ListenerFuncs{}
	}
	filter := cfg.filter
	if filter == nil {
		filter = func(model.ObjectId) bool { return true }
	}
	if cfg.hookStore != nil {
		if managed := cfg.hookStore.ManagedTable(cfg.namespace); managed != nil {
			base := filter
			filter = func(id model.ObjectId) bool {
				return id != *managed && base(id)
			}
		}
	}
	return &engine{
		resolver:           newDialectResolver(cfg.dialect, nil),
		planMeta:           newPlanMetaMap(),
		dataSource:         cfg.dataSource,
		scratchDataSource:  cfg.scratchDataSource,
		namespace:          cfg.namespace,
		desiredState:       cfg.desiredState,
		policy:             cfg.policy,
		lockTimeout:        cfg.lockTimeout,
		statementTimeout:   cfg.statementTimeout,
		listener:           listener,
		objectFilter:       filter,
		concurrentIndexes:  cfg.concurrentIndexes,
		renameHints:        cfg.renameHints,
		retireTargets:      cfg.retireTargets,
		externalPartitions: cfg.externalPartitions,
		pruneFilter:        cfg.pruneFilter,
		hooks:              sortedHooks(cfg.hooks),
		hookStore:          cfg.hookStore,
	}
}

func (e *engine) sessionConfig() sessionConfig {
	return sessionConfig{
		dataSource:        e.dataSource,
		scratchDataSource: e.scratchDataSource,
		namespace:         e.namespace,
		lockTimeout:       e.lockTimeout,
		statementTimeout:  e.statementTimeout,
		objectFilter:      e.objectFilter,
		resolver:          e.resolver,
	}
}

// timed runs fn while accumulating its duration into slot — the Go shape
// of the TypeScript try/finally clock.since(t) pairs.
func timed[T any](clock *phaseClock, slot *time.Duration, fn func() (T, error)) (out T, err error) {
	start := clock.now()
	defer func() { *slot = clock.since(start) }()
	return fn()
}

// timedErr is timed for error-only phases.
func timedErr(clock *phaseClock, slot *time.Duration, fn func() error) error {
	_, err := timed(clock, slot, func() (struct{}, error) {
		return struct{}{}, fn()
	})
	return err
}

// Plan is spec 5.1 plan(): steps 1–5, returning the plan without evaluating
// policy or applying.
func (e *engine) Plan(ctx context.Context) (*model.ReconcilePlan, error) {
	if err := e.requireDesiredState("plan"); err != nil {
		return nil, err
	}
	timedPlan, err := e.PlanTimed(ctx)
	if err != nil {
		return nil, err
	}
	return timedPlan.Plan, nil
}

// PlanTimed is Plan with the phase timings of the planning run.
func (e *engine) PlanTimed(ctx context.Context) (TimedPlan, error) {
	if err := e.requireDesiredState("planTimed"); err != nil {
		return TimedPlan{}, err
	}
	clock := &phaseClock{}
	session, err := openSession(ctx, e.sessionConfig(), true)
	if err != nil {
		return TimedPlan{}, err
	}
	defer func() { session.close(ctx) }()
	built, err := func() (*builtPlan, error) {
		if err := timedErr(clock, &clock.lockWait, func() error {
			return session.lock(ctx)
		}); err != nil {
			return nil, err
		}
		actual, err := timed(clock, &clock.readActual, func() (model.SchemaSnapshot, error) {
			return session.readActual(ctx)
		})
		if err != nil {
			return nil, err
		}
		desired, err := timed(clock, &clock.loadDesired, func() (desiredAndHints, error) {
			return e.loadDesired(ctx, session)
		})
		if err != nil {
			return nil, err
		}
		built, err := timed(clock, &clock.plan, func() (*builtPlan, error) {
			return e.buildPlan(session, e.effectiveActual(actual, desired.snapshot), desired.snapshot, desired.hints, desired.retires)
		})
		if err != nil {
			return nil, err
		}
		// nothing is applied by plan(), so a listener failure is a
		// planning failure
		if err := e.listener.Planned(built.plan); err != nil {
			return nil, NewPlanningErrorf("namespace '%s': listener aborted planning",
				e.namespace).WithCause(err)
		}
		e.planMeta.set(built.plan, planMeta{
			desired:         desired.snapshot,
			coveredByParent: built.coveredByParent,
		})
		return built, nil
	}()
	if err != nil {
		return TimedPlan{}, attachTimings(err, clock.snapshot())
	}
	return TimedPlan{Plan: built.plan, Timings: clock.snapshot()}, nil
}

// Reconcile is spec 5.1 reconcile(): the full sequence.
func (e *engine) Reconcile(ctx context.Context) (ReconcileReport, error) {
	if err := e.requireDesiredState("reconcile"); err != nil {
		return ReconcileReport{}, err
	}
	start := time.Now()
	clock := &phaseClock{}
	session, err := openSession(ctx, e.sessionConfig(), true)
	if err != nil {
		return ReconcileReport{}, err
	}
	defer func() { session.close(ctx) }()
	report, err := func() (ReconcileReport, error) {
		if err := timedErr(clock, &clock.lockWait, func() error {
			return session.lock(ctx)
		}); err != nil {
			return ReconcileReport{}, err
		}
		actual, err := timed(clock, &clock.readActual, func() (model.SchemaSnapshot, error) {
			return session.readActual(ctx)
		})
		if err != nil {
			return ReconcileReport{}, err
		}
		desired, err := timed(clock, &clock.loadDesired, func() (desiredAndHints, error) {
			return e.loadDesired(ctx, session)
		})
		if err != nil {
			return ReconcileReport{}, err
		}
		effectiveActual := e.effectiveActual(actual, desired.snapshot)
		built, err := timed(clock, &clock.plan, func() (*builtPlan, error) {
			return e.buildPlan(session, effectiveActual, desired.snapshot, desired.hints, desired.retires)
		})
		if err != nil {
			return ReconcileReport{}, err
		}
		if err := e.notifyPlanned(built.plan, nil); err != nil {
			return ReconcileReport{}, err
		}
		eval, err := evaluatePolicy(session.dialect, e.policy, built.plan, effectiveActual, desired.snapshot)
		if err != nil {
			return ReconcileReport{}, err
		}
		outcome, err := e.applyAndVerify(ctx, session, built.plan, eval,
			built.coveredByParent, clock, desired.snapshot)
		if err != nil {
			return ReconcileReport{}, err
		}
		if err := session.commit(ctx); err != nil {
			return ReconcileReport{}, err
		}
		unexpected := unexpectedDifferences(built.plan)
		unsupported := e.unsupportedDifferences(built.plan)
		e.logUnexpectedAndUnsupported(unexpected, unsupported)
		return ReconcileReport{
			Outcome:     outcome.outcome,
			Plan:        built.plan,
			Applied:     outcome.applied,
			Reported:    outcome.reported,
			Unexpected:  unexpected,
			Unsupported: unsupported,
			Elapsed:     time.Since(start),
			Timings:     clock.snapshot(),
		}, nil
	}()
	if err != nil {
		return ReconcileReport{}, attachTimings(err, clock.snapshot())
	}
	return report, nil
}

// Apply is spec 5.1 apply(): the same sequence, driven by a previously
// issued plan.
func (e *engine) Apply(ctx context.Context, plan *model.ReconcilePlan) (ReconcileReport, error) {
	if err := e.requireDesiredState("apply"); err != nil {
		return ReconcileReport{}, err
	}
	start := time.Now()
	clock := &phaseClock{}
	// reject a foreign plan before touching the database: a namespace or
	// dialect mismatch needs no connection to diagnose (5.7)
	fixed := e.resolver.peek()
	if e.namespace != plan.TargetNamespace ||
		(fixed != nil && fixed.ID() != plan.DialectID) {
		dialectSuffix := "'"
		if fixed != nil {
			dialectSuffix = "' on '" + fixed.ID() + "'"
		}
		return ReconcileReport{}, model.InvalidArgumentErrorf(
			"plan targets namespace '%s' on dialect '%s', this reconciler is bound to '%s%s",
			plan.TargetNamespace, plan.DialectID, e.namespace, dialectSuffix)
	}
	session, err := openSession(ctx, e.sessionConfig(), true)
	if err != nil {
		return ReconcileReport{}, err
	}
	defer func() { session.close(ctx) }()
	report, err := func() (ReconcileReport, error) {
		if err := timedErr(clock, &clock.lockWait, func() error {
			return session.lock(ctx)
		}); err != nil {
			return ReconcileReport{}, err
		}
		actual, err := timed(clock, &clock.readActual, func() (model.SchemaSnapshot, error) {
			return session.readActual(ctx)
		})
		if err != nil {
			return ReconcileReport{}, err
		}
		if len(e.externalPartitions) == 0 {
			if err := e.verifyPlan(plan, session.dialect, actual); err != nil {
				return ReconcileReport{}, err
			}
		}
		desired, err := timed(clock, &clock.loadDesired, func() (desiredAndHints, error) {
			return e.desiredFor(ctx, plan, session)
		})
		if err != nil {
			return ReconcileReport{}, err
		}
		effectiveActual := e.effectiveActual(actual, desired.snapshot)
		if len(e.externalPartitions) != 0 {
			if err := e.verifyPlan(plan, session.dialect, effectiveActual); err != nil {
				return ReconcileReport{}, err
			}
		}
		if err := e.notifyPlanned(plan, nil); err != nil {
			return ReconcileReport{}, err
		}
		eval, err := evaluatePolicy(session.dialect, e.policy, plan, effectiveActual, desired.snapshot)
		if err != nil {
			return ReconcileReport{}, err
		}
		var covered map[model.ObjectId]map[model.ObjectId]model.Definition
		if meta, ok := e.planMeta.get(plan); ok {
			covered = meta.coveredByParent
		} else {
			covered, err = coverageOf(session.dialect, e.namespace,
				plan.Differences, desired.snapshot, plan.Changes)
			if err != nil {
				return ReconcileReport{}, err
			}
		}
		outcome, err := e.applyAndVerify(ctx, session, plan, eval, covered,
			clock, desired.snapshot)
		if err != nil {
			return ReconcileReport{}, err
		}
		if err := session.commit(ctx); err != nil {
			return ReconcileReport{}, err
		}
		unexpected := unexpectedDifferences(plan)
		unsupported := e.unsupportedDifferences(plan)
		e.logUnexpectedAndUnsupported(unexpected, unsupported)
		return ReconcileReport{
			Outcome:     outcome.outcome,
			Plan:        plan,
			Applied:     outcome.applied,
			Reported:    outcome.reported,
			Unexpected:  unexpected,
			Unsupported: unsupported,
			Elapsed:     time.Since(start),
			Timings:     clock.snapshot(),
		}, nil
	}()
	if err != nil {
		return ReconcileReport{}, attachTimings(err, clock.snapshot())
	}
	return report, nil
}

// Dump is spec 3.1 dump(): steps 1 to 3 of 5.1 — connect, lock, read the
// actual snapshot — rendered as a namespace-neutral desired-state script.
// Requires no DesiredState; the target is never mutated.
func (e *engine) Dump(ctx context.Context) (string, error) {
	session, err := openSession(ctx, e.sessionConfig(), false)
	if err != nil {
		return "", err
	}
	defer session.close(ctx)
	if err := session.lock(ctx); err != nil {
		return "", err
	}
	actual, err := session.readActual(ctx)
	if err != nil {
		return "", err
	}
	return renderDump(session.dialect, e.namespace,
		suppressExternalPartitions(actual, nil, e.externalPartitions, false))
}

// ApplyJSON is spec 3.1 applyJson(): applies a plan serialised by ToJSON.
func (e *engine) ApplyJSON(ctx context.Context, serializedPlan string) (ReconcileReport, error) {
	plan, err := model.ParseReconcilePlanJSON(serializedPlan)
	if err != nil {
		return ReconcileReport{}, err
	}
	return e.Apply(ctx, &plan)
}

func (e *engine) requireDesiredState(method string) error {
	if e.desiredState == nil {
		return NewConfigurationErrorf("desiredState is required for %s()", method)
	}
	return nil
}

// runOutcome is the internal result of applyAndVerify.
type runOutcome struct {
	outcome  ReconcileOutcome
	applied  []model.PlannedChange
	reported []model.PlannedChange
}

func (e *engine) applyAndVerify(ctx context.Context, session *engineSession,
	plan *model.ReconcilePlan, eval *evaluation,
	coveredByParent map[model.ObjectId]map[model.ObjectId]model.Definition,
	clock *phaseClock, desired model.SchemaSnapshot) (runOutcome, error) {
	var applied, reported []model.PlannedChange
	// non-transactional APPLY changes (CREATE INDEX CONCURRENTLY and
	// friends) run in a second phase, after the transactional work
	// verifies and commits
	var deferred []model.PlannedChange
	if err := session.bindNamespace(ctx); err != nil {
		return runOutcome{}, err
	}
	anyApply := false
	for i := range plan.Changes {
		if eval.decisions[i] == DecisionApply {
			anyApply = true
			break
		}
	}
	hooksEligible := len(plan.Changes) == 0 || anyApply
	applyStart := clock.now()
	applyErr := func() error {
		if hooksEligible {
			if err := e.runHooks(ctx, session, HookPreApply, plan, &applied); err != nil {
				return err
			}
		}
		for i, change := range plan.Changes {
			if eval.decisions[i] == DecisionApply {
				if change.NonTransactional {
					deferred = append(deferred, change)
					continue
				}
				if err := e.executeChange(ctx, session, change, &applied); err != nil {
					return err
				}
			} else {
				if blocker, ok := eval.demotedBy[i]; ok {
					change.Reason += " (blocked by " + blocker.String() + ")"
				}
				reported = append(reported, change)
				warnf("namespace '%s': change %d (%s %s) not applied: %s",
					e.namespace, change.Order, change.Action, change.Target, change.Reason)
				if err := e.notifyReported(change, applied); err != nil {
					return err
				}
			}
		}
		if hooksEligible {
			if err := e.runHooks(ctx, session, HookPostApply, plan, &applied); err != nil {
				return err
			}
		}
		return nil
	}()
	clock.apply = clock.since(applyStart)
	if applyErr != nil {
		return runOutcome{}, applyErr
	}
	verifyStart := clock.now()
	verifyErr := e.verify(ctx, session, applied, coveredByParent, desired)
	clock.verify = clock.since(verifyStart)
	if verifyErr != nil {
		return runOutcome{}, verifyErr
	}
	postCommit := hooksEligible && slices.ContainsFunc(e.hooks, func(h SchemaHook) bool { return h.Phase == HookPostCommit })
	if len(deferred) > 0 || postCommit {
		if err := session.commit(ctx); err != nil {
			return runOutcome{}, err
		}
		// non-transactional statements and post-commit hooks run in auto-commit mode
		if err := session.autoCommit(ctx, true); err != nil {
			return runOutcome{}, err
		}
		phase1Count := len(applied)
		deferredErr := func() (err error) {
			defer func() {
				// restore the transactional session mode before the outer
				// commit/close handling; a failure replaces the in-flight
				// error exactly as a finally throw would
				if restoreErr := session.autoCommit(ctx, false); restoreErr != nil {
					err = restoreErr
				}
			}()
			for _, change := range deferred {
				if err := e.executeChange(ctx, session, change, &applied); err != nil {
					return err
				}
			}
			if len(deferred) > 0 {
				deferredVerifyStart := clock.now()
				verifyErr := e.verify(ctx, session, applied[phase1Count:], coveredByParent, desired)
				clock.verify += clock.since(deferredVerifyStart)
				if verifyErr != nil {
					return verifyErr
				}
			}
			if postCommit {
				if err := e.runHooks(ctx, session, HookPostCommit, plan, &applied); err != nil {
					return err
				}
			}
			return nil
		}()
		if deferredErr != nil {
			return runOutcome{}, deferredErr
		}
	}
	outcome := OutcomeReconciled
	if len(applied) == 0 {
		if len(plan.Changes) == 0 {
			outcome = OutcomeUpToDate
		} else {
			outcome = OutcomeReportedOnly
		}
	}
	return runOutcome{outcome: outcome, applied: applied, reported: reported}, nil
}

func (e *engine) runHooks(ctx context.Context, session *engineSession,
	phase HookPhase, plan *model.ReconcilePlan, applied *[]model.PlannedChange) error {
	for _, hook := range e.hooks {
		if hook.Phase != phase {
			continue
		}
		execute := true
		if hook.RunOnce {
			recorded, found, err := e.hookStore.RecordedChecksum(ctx, session.conn,
				e.namespace, hook.ID)
			if err != nil {
				return NewApplyError("namespace '"+e.namespace+"': hook '"+hook.ID+
					"' checksum lookup failed in "+phase.String(), append([]model.PlannedChange(nil), (*applied)...), nil).WithCause(err)
			}
			if found && recorded == hook.Checksum {
				execute = false
			} else if found {
				return NewApplyError("namespace '"+e.namespace+"': hook '"+hook.ID+
					"' is recorded with a different checksum", append([]model.PlannedChange(nil), (*applied)...), nil)
			}
		}
		if execute {
			ctxCopy := append([]model.PlannedChange(nil), (*applied)...)
			if err := hook.Run(ctx, HookContext{Namespace: e.namespace,
				DialectID: session.dialect.ID(), Phase: phase, Plan: plan,
				Applied: ctxCopy, Conn: session.conn}); err != nil {
				return NewApplyError("namespace '"+e.namespace+"': hook '"+hook.ID+
					"' failed in "+phase.String(), append([]model.PlannedChange(nil), (*applied)...), nil).WithCause(err)
			}
			if hook.RunOnce {
				if err := e.hookStore.Record(ctx, session.conn, e.namespace,
					hook.ID, phase, hook.Checksum); err != nil {
					return NewApplyError("namespace '"+e.namespace+"': hook '"+hook.ID+
						"' record failed in "+phase.String(), append([]model.PlannedChange(nil), (*applied)...), nil).WithCause(err)
				}
			}
		}
	}
	return nil
}

func (e *engine) executeChange(ctx context.Context, session *engineSession,
	change model.PlannedChange, applied *[]model.PlannedChange) error {
	if err := e.notifyApplying(change, *applied); err != nil {
		return err
	}
	t0 := time.Now()
	for _, sql := range change.Statements {
		if err := session.execute(ctx, sql); err != nil {
			return NewApplyError(fmt.Sprintf(
				"namespace '%s': change %d failed: %s", e.namespace, change.Order, err),
				append([]model.PlannedChange(nil), (*applied)...), &change).WithCause(err)
		}
	}
	*applied = append(*applied, change)
	return e.notifyApplied(change, time.Since(t0), *applied)
}

func (e *engine) verify(ctx context.Context, session *engineSession,
	applied []model.PlannedChange,
	coveredByParent map[model.ObjectId]map[model.ObjectId]model.Definition,
	desired model.SchemaSnapshot) error {
	expectations := map[model.ObjectId]model.Definition{}
	var expectationOrder []model.ObjectId
	setExpected := func(id model.ObjectId, def model.Definition) {
		if _, seen := expectations[id]; !seen {
			expectationOrder = append(expectationOrder, id)
		}
		expectations[id] = def
	}
	var droppedTargets []model.ObjectId
	for _, change := range applied {
		if change.Expected != nil {
			setExpected(change.Target, *change.Expected)
		}
		if change.Action == model.ChangeDrop {
			if _, ok := desired.Objects[change.Target]; !ok {
				// a standalone (pruning) DROP — the target must be gone
				droppedTargets = append(droppedTargets, change.Target)
			}
		}
		if covered, ok := coveredByParent[change.Target]; ok {
			for id, def := range covered {
				setExpected(id, def)
			}
		}
	}
	if len(expectations) == 0 && len(droppedTargets) == 0 {
		return nil
	}
	after, err := session.readActual(ctx)
	if err != nil {
		return err
	}
	var remaining []model.Difference
	for _, id := range expectationOrder {
		expected := expectations[id]
		exp := expected
		object, ok := after.Objects[id]
		if !ok {
			remaining = append(remaining, model.Difference{
				ID: id, Type: model.DifferenceMissing, Expected: &exp})
		} else if object.Definition != expected {
			actual := object.Definition
			remaining = append(remaining, model.Difference{
				ID: id, Type: model.DifferenceDrifted, Expected: &exp, Actual: &actual})
		}
	}
	for _, id := range droppedTargets {
		if object, ok := after.Objects[id]; ok {
			actual := object.Definition
			remaining = append(remaining, model.Difference{
				ID: id, Type: model.DifferenceUnexpected, Actual: &actual})
		}
	}
	if len(remaining) > 0 {
		return NewVerificationError(fmt.Sprintf(
			"namespace '%s': %d object(s) did not converge after apply: %s",
			e.namespace, len(remaining), joinDifferenceIDs(remaining)), remaining)
	}
	return nil
}

func joinDifferenceIDs(diffs []model.Difference) string {
	out := ""
	for i, d := range diffs {
		if i > 0 {
			out += ", "
		}
		out += d.ID.String()
	}
	return out
}

func (e *engine) verifyPlan(plan *model.ReconcilePlan, d spi.Dialect,
	actual model.SchemaSnapshot) error {
	if e.namespace != plan.TargetNamespace || d.ID() != plan.DialectID {
		return model.InvalidArgumentErrorf(
			"plan targets namespace '%s' on dialect '%s', this reconciler is bound to '%s' on '%s'",
			plan.TargetNamespace, plan.DialectID, e.namespace, d.ID())
	}
	if actual.Fingerprint != plan.ActualFingerprint {
		return NewStalePlanError(fmt.Sprintf(
			"namespace '%s': the target changed since this plan was issued",
			e.namespace), plan)
	}
	return nil
}

func (e *engine) desiredFor(ctx context.Context, plan *model.ReconcilePlan,
	session *engineSession) (desiredAndHints, error) {
	if meta, ok := e.planMeta.get(plan); ok {
		return desiredAndHints{snapshot: meta.desired}, nil
	}
	return e.loadDesired(ctx, session)
}

func unexpectedDifferences(plan *model.ReconcilePlan) []model.Difference {
	var out []model.Difference
	for _, d := range plan.Differences {
		if d.Type == model.DifferenceUnexpected {
			out = append(out, d)
		}
	}
	return out
}

func (e *engine) unsupportedDifferences(plan *model.ReconcilePlan) []model.Difference {
	var out []model.Difference
	for _, d := range plan.Differences {
		if d.Type == model.DifferenceUnsupported &&
			e.policy.UnsupportedDecision(d) == DecisionReport {
			out = append(out, d)
		}
	}
	return out
}

// logUnexpectedAndUnsupported emits the WARNING logs of spec 3.3 and 5.5:
// unexpected objects and, under a REPORT unsupported policy, each
// UNSUPPORTED difference.
func (e *engine) logUnexpectedAndUnsupported(unexpected, unsupported []model.Difference) {
	for _, d := range unexpected {
		warnf("namespace '%s': unexpected object %s is not in the desired state and was left unchanged",
			e.namespace, d.ID)
	}
	for _, d := range unsupported {
		warnf("namespace '%s': unsupported object %s requires manual handling",
			e.namespace, d.ID)
	}
}

// desiredAndHints carries the scratch-derived snapshot plus the rename
// directives the script declared — per-run state, not engine state, so
// concurrent runs never exchange hints.
type desiredAndHints struct {
	snapshot model.SchemaSnapshot
	hints    []model.RenameHint
	retires  []model.ObjectId
}

func (e *engine) effectiveActual(actual, desired model.SchemaSnapshot) model.SchemaSnapshot {
	return suppressExternalPartitions(actual, &desired, e.externalPartitions, true)
}

func (e *engine) buildPlan(session *engineSession,
	actual, desired model.SchemaSnapshot,
	scriptHints []model.RenameHint, scriptRetires []model.ObjectId) (*builtPlan, error) {
	differences := differDiff(actual, desired, session.dialect)
	hints := append(append([]model.RenameHint{}, e.renameHints...), scriptHints...)
	retires := append(append([]model.ObjectId{}, e.retireTargets...), scriptRetires...)
	return newPlanBuilder(session.dialect, e.namespace, actual, desired,
		e.policy.prune, hints, retires, e.pruneFilter,
		spi.RenderOptions{ConcurrentIndexes: e.concurrentIndexes}).build(differences)
}

func (e *engine) loadDesired(ctx context.Context, session *engineSession) (desiredAndHints, error) {
	separate := e.scratchDataSource != nil
	var scratch spi.Conn
	if separate {
		conn, err := e.scratchDataSource.Conn(ctx)
		if err != nil {
			return desiredAndHints{}, NewSchemaReconcilerError(
				"namespace '%s': could not obtain scratch connection",
				e.namespace).WithCause(err)
		}
		scratch = conn
	} else {
		scratch = session.conn
	}
	restoreAutoCommit := false
	defer func() {
		if !separate {
			return
		}
		// scratch cleanup on every path: roll back, restore autocommit,
		// then close — warn-only, since an error may already be in flight
		_ = scratch.Rollback(ctx)
		if restoreAutoCommit {
			if err := scratch.SetAutoCommit(ctx, true); err != nil {
				warnf("could not restore auto-commit on the scratch connection: %s", err)
			}
		}
		if err := scratch.Close(); err != nil {
			warnf("namespace '%s': could not close the scratch connection: %s", e.namespace, err)
		}
	}()
	desired, err := func() (out desiredAndHints, err error) {
		if separate {
			if err := e.checkSameEngine(ctx, session.conn, scratch, session.dialect); err != nil {
				return desiredAndHints{}, err
			}
			if err := spi.CheckAccess(ctx, session.dialect, scratch, "", true); err != nil {
				var configErr *ConfigurationError
				if errors.As(err, &configErr) && !namesNamespace(err.Error(), e.namespace) {
					return desiredAndHints{}, NewConfigurationErrorf(
						"namespace '%s': %s", e.namespace, err.Error()).WithCause(err)
				}
				return desiredAndHints{}, err
			}
			if session.dialect.Capabilities().TransactionalDDL {
				if err := scratch.SetAutoCommit(ctx, false); err != nil {
					return desiredAndHints{}, err
				}
				restoreAutoCommit = true
			}
			if e.statementTimeout != nil {
				// the separate connection never sees the target session's
				// SET LOCAL — the bound must be applied here too or the
				// desired script and scratch snapshot read run unbounded
				if err := spi.SetStatementTimeout(ctx, session.dialect, scratch,
					*e.statementTimeout); err != nil {
					return desiredAndHints{}, err
				}
			}
		}
		scratchNamespace, err := session.dialect.CreateScratchNamespace(ctx, scratch, e.namespace)
		if err != nil {
			return desiredAndHints{}, NewDesiredStateErrorf(
				"namespace '%s': could not create the scratch namespace",
				e.namespace).WithCause(err)
		}
		defer func() {
			// like the TypeScript finally throw: a drop failure replaces
			// whatever result or error was in flight
			if dropErr := session.dialect.DropScratchNamespace(ctx, scratch, scratchNamespace); dropErr != nil {
				out = desiredAndHints{}
				err = NewSchemaReconcilerError(
					"namespace '%s': could not drop scratch namespace '%s'",
					e.namespace, scratchNamespace).WithCause(dropErr)
			}
		}()
		processor, err := newScriptProcessor(e.desiredState.Script(), e.namespace,
			scratchNamespace, session.dialect)
		if err != nil {
			return desiredAndHints{}, err
		}
		if !processor.HasPlaceholder &&
			!session.dialect.Capabilities().SessionDefaultNamespace {
			return desiredAndHints{}, NewDesiredStateErrorf(
				"namespace '%s': the script contains no ${namespace} placeholder and dialect '%s' has no session default namespace",
				e.namespace, session.dialect.ID())
		}
		if session.dialect.Capabilities().SessionDefaultNamespace {
			if err := spi.BindSessionNamespace(ctx, session.dialect, scratch, scratchNamespace); err != nil {
				return desiredAndHints{}, err
			}
		}
		if err := session.dialect.ExecuteScript(ctx, scratch, processor.Substituted,
			scratchNamespace); err != nil {
			var dse *DesiredStateError
			if errors.As(err, &dse) && namesNamespace(err.Error(), e.namespace) {
				return desiredAndHints{}, err
			}
			if errors.As(err, &dse) {
				return desiredAndHints{}, NewDesiredStateErrorf(
					"namespace '%s': %s", e.namespace, err.Error()).WithCause(err)
			}
			return desiredAndHints{}, NewDesiredStateErrorf(
				"namespace '%s': the desired-state script failed: %s",
				e.namespace, err.Error()).WithCause(err)
		}
		snapshot, err := session.dialect.ReadSnapshot(ctx, scratch, scratchNamespace)
		if err != nil {
			return desiredAndHints{}, err
		}
		return desiredAndHints{
			snapshot: applyObjectFilter(snapshot, e.objectFilter),
			hints:    processor.RenameHints,
			retires:  processor.RetireTargets,
		}, nil
	}()
	if err != nil {
		return desiredAndHints{}, err
	}
	return desired, nil
}

func (e *engine) checkSameEngine(ctx context.Context, target, scratch spi.Conn,
	d spi.Dialect) error {
	tm, err := target.Metadata(ctx)
	if err != nil {
		return err
	}
	sm, err := scratch.Metadata(ctx)
	if err != nil {
		return err
	}
	sameEngine := tm.DatabaseProductName == sm.DatabaseProductName &&
		tm.DatabaseMajorVersion == sm.DatabaseMajorVersion
	if !sameEngine || !d.Supports(sm) {
		return NewConfigurationErrorf("namespace '%s': the scratchDataSource is not the same engine and major version as the target dataSource",
			e.namespace)
	}
	return nil
}

// notifyPlanned is spec 3.6: a listener failure during Reconcile or Apply,
// from Planned too, aborts the run as ApplyError.
func (e *engine) notifyPlanned(plan *model.ReconcilePlan, applied []model.PlannedChange) error {
	if err := e.listener.Planned(plan); err != nil {
		return NewApplyError(e.named("listener aborted the run"),
			append([]model.PlannedChange(nil), applied...), nil).WithCause(err)
	}
	return nil
}

func (e *engine) notifyApplying(change model.PlannedChange, applied []model.PlannedChange) error {
	if err := e.listener.Applying(change); err != nil {
		return NewApplyError(e.named("listener aborted the run"),
			append([]model.PlannedChange(nil), applied...), &change).WithCause(err)
	}
	return nil
}

func (e *engine) notifyApplied(change model.PlannedChange, elapsed time.Duration,
	applied []model.PlannedChange) error {
	if err := e.listener.Applied(change, elapsed); err != nil {
		return NewApplyError(e.named("listener aborted the run"),
			append([]model.PlannedChange(nil), applied...), &change).WithCause(err)
	}
	return nil
}

func (e *engine) notifyReported(change model.PlannedChange, applied []model.PlannedChange) error {
	if err := e.listener.Reported(change); err != nil {
		return NewApplyError(e.named("listener aborted the run"),
			append([]model.PlannedChange(nil), applied...), nil).WithCause(err)
	}
	return nil
}

// named prefixes a message raised below the engine (resolver, dialect)
// with the target namespace (spec 7).
func (e *engine) named(message string) string {
	return "namespace '" + e.namespace + "': " + message
}
