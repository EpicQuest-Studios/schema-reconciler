package reconciler

// engine_test.go — Go port of test/ReconcileEngine.test.ts: end-to-end
// engine tests on fakeDb exercising the whole spec 5.1 sequence — lock,
// snapshot, scratch desired state, diff, plan, policy, apply, verify —
// without a real database.

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

const engineNS = "corex"

func engineFixture() (*fakeDb, *fakeDialect) {
	db := newFakeDb()
	return db, newFakeDialect(db)
}

func engineReconciler(db *fakeDb, dialect *fakeDialect, script string,
	policy ...ReconcilePolicy) *SchemaReconciler {
	b := NewBuilder().
		DataSource(db.dataSource()).
		Namespace(engineNS).
		DesiredState(DesiredStateOfScript(script)).
		Dialect(dialect)
	if len(policy) > 0 {
		b.Policy(policy[0])
	}
	r, err := b.Build()
	if err != nil {
		panic(err)
	}
	return r
}

func TestEngineEmptyTargetCreatesEverything(t *testing.T) {
	db, dialect := engineFixture()
	script := "CREATE TABLE users DEF users_v1;" +
		" CREATE COLUMN users.id DEF id_v1;" +
		" CREATE VIEW active REQUIRES TABLE:users DEF active_v1"

	report, err := engineReconciler(db, dialect, script).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	objects := db.objectsIn(engineNS)
	if _, ok := objects[model.ObjectId{Kind: model.KindTable, Name: "users"}]; !ok {
		t.Fatal("users table missing")
	}
	if _, ok := objects[model.ObjectId{Kind: model.KindView, Name: "active"}]; !ok {
		t.Fatal("active view missing")
	}
	// second run converges
	again, err := engineReconciler(db, dialect, script).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if again.Outcome != OutcomeUpToDate {
		t.Fatalf("second run outcome = %s, want UP_TO_DATE", again.Outcome)
	}
}

func TestEngineFilterHidesExcludedObjectsAndTheirChildren(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS,
		"CREATE TABLE keep DEF keep_v1; CREATE TABLE skip DEF skip_v1;"+
			" CREATE COLUMN skip.c DEF c_v1")
	script := "CREATE TABLE keep DEF keep_v1; CREATE TABLE skip DEF skip_v2;" +
		" CREATE COLUMN skip.c DEF c_v1"

	r, err := NewBuilder().
		DataSource(db.dataSource()).
		Namespace(engineNS).
		DesiredState(DesiredStateOfScript(script)).
		Dialect(dialect).
		Filter(func(id model.ObjectId) bool {
			return !(id.Kind == model.KindTable && id.Name == "skip")
		}).Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", report.Outcome)
	}
	if len(report.Plan.Changes) != 0 {
		t.Fatalf("the excluded subtree is invisible, got %d changes",
			len(report.Plan.Changes))
	}
	if len(report.Unexpected) != 0 || len(report.Unsupported) != 0 {
		t.Fatalf("unexpected=%d unsupported=%d, want both 0",
			len(report.Unexpected), len(report.Unsupported))
	}
	if _, ok := db.objectsIn(engineNS)[model.ObjectId{Kind: model.KindTable, Name: "skip"}]; !ok {
		t.Fatal("the excluded objects still exist, untouched")
	}
}

func TestEngineFilterSuppressesUnsupportedObjects(t *testing.T) {
	db, dialect := engineFixture()
	r, err := NewBuilder().
		DataSource(db.dataSource()).
		Namespace(engineNS).
		DesiredState(DesiredStateOfScript(
			"CREATE TABLE t DEF t_v1; CREATE OTHER skip:note DEF n")).
		Dialect(dialect).
		Filter(func(id model.ObjectId) bool { return id.Kind != model.KindOther }).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if len(report.Unsupported) != 0 {
		t.Fatalf("unsupported = %d, want 0", len(report.Unsupported))
	}
}

func TestEngineMatchingTargetRunsNoStatements(t *testing.T) {
	db, dialect := engineFixture()
	script := "CREATE TABLE users DEF users_v1; CREATE COLUMN users.id DEF id_v1"
	db.executeDirect(engineNS, script)

	report, err := engineReconciler(db, dialect, script).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", report.Outcome)
	}
	if len(report.Plan.Changes) != 0 {
		t.Fatalf("changes = %d, want 0", len(report.Plan.Changes))
	}
	if got := len(db.statementsOn(engineNS)); got != 0 {
		t.Fatalf("%d statements ran on the target, want 0", got)
	}
	if db.lockHeld(engineNS) {
		t.Fatal("lock must be released")
	}
}

func TestEngineMissingTableCoversItsColumns(t *testing.T) {
	db, dialect := engineFixture()
	report, err := engineReconciler(db, dialect,
		"CREATE TABLE users DEF users_v1;"+
			" CREATE COLUMN users.id DEF id_v1;"+
			" CREATE COLUMN users.name DEF name_v1").Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(report.Plan.Changes) != 1 {
		t.Fatalf("the table create covers its columns, got %d changes",
			len(report.Plan.Changes))
	}
	if report.Plan.Changes[0].Target != (model.ObjectId{Kind: model.KindTable, Name: "users"}) {
		t.Fatalf("change target = %s, want TABLE users", report.Plan.Changes[0].Target)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	objects := db.objectsIn(engineNS)
	for _, col := range []string{"id", "name"} {
		if _, ok := objects[model.ObjectId{Kind: model.KindColumn, Name: col, Parent: "users"}]; !ok {
			t.Fatalf("column users.%s missing", col)
		}
	}
}

func TestEngineDriftedViewIsRecreatedInPlace(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS,
		"CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_old")
	script := "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_new"

	report, err := engineReconciler(db, dialect, script).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	v := model.ObjectId{Kind: model.KindView, Name: "v"}
	var actions []model.ChangeAction
	for _, c := range report.Plan.Changes {
		if c.Target == v {
			actions = append(actions, c.Action)
		}
	}
	if len(actions) != 2 || actions[0] != model.ChangeDrop || actions[1] != model.ChangeCreate {
		t.Fatalf("view actions = %v, want [DROP CREATE]", actions)
	}
	if got := db.objectsIn(engineNS)[v].Definition.Canonical(); got != "v_new" {
		t.Fatalf("view definition = %q, want v_new", got)
	}
}

func TestEngineDependentRebuildsAroundDroppedTable(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS,
		"CREATE TABLE t DEF t_old; CREATE VIEW v REQUIRES TABLE:t DEF v_v1")
	script := "CREATE TABLE t DEF t_new; CREATE VIEW v REQUIRES TABLE:t DEF v_v1"
	policy := DefaultPolicy().WithDestructive(DecisionApply)

	report, err := engineReconciler(db, dialect, script, policy).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	// drop v (dependent) before t; create t before v
	var drops, creates []string
	for _, c := range report.Plan.Changes {
		switch c.Action {
		case model.ChangeDrop:
			drops = append(drops, c.Target.String())
		case model.ChangeCreate:
			creates = append(creates, c.Target.String())
		}
	}
	wantDrops := []string{"VIEW v", "TABLE t"}
	wantCreates := []string{"TABLE t", "VIEW v"}
	if strings.Join(drops, ",") != strings.Join(wantDrops, ",") {
		t.Fatalf("drops = %v, want %v", drops, wantDrops)
	}
	if strings.Join(creates, ",") != strings.Join(wantCreates, ",") {
		t.Fatalf("creates = %v, want %v", creates, wantCreates)
	}
	if got := db.objectsIn(engineNS)[model.ObjectId{Kind: model.KindView, Name: "v"}].
		Definition.Canonical(); got != "v_v1" {
		t.Fatalf("view definition = %q, want v_v1", got)
	}
}

func TestEngineUnexpectedObjectsAreReportedNotDropped(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS, "CREATE TABLE t DEF t_v1; CREATE TABLE extra DEF e_v1")

	report, err := engineReconciler(db, dialect, "CREATE TABLE t DEF t_v1").
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", report.Outcome)
	}
	if len(report.Unexpected) != 1 {
		t.Fatalf("unexpected = %d, want 1", len(report.Unexpected))
	}
	if report.Unexpected[0].ID != (model.ObjectId{Kind: model.KindTable, Name: "extra"}) {
		t.Fatalf("unexpected[0] = %s, want TABLE extra", report.Unexpected[0].ID)
	}
	if _, ok := db.objectsIn(engineNS)[model.ObjectId{Kind: model.KindTable, Name: "extra"}]; !ok {
		t.Fatal("extra object dropped")
	}
	for _, s := range db.statementsOn(engineNS) {
		if strings.Contains(s.SQL, "extra") {
			t.Fatalf("statement touched the unexpected object: %s", s.SQL)
		}
	}
}

func TestEngineDestructiveChangeFailsByDefaultPolicy(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS, "CREATE TABLE t DEF t_old")
	_, err := engineReconciler(db, dialect, "CREATE TABLE t DEF t_new").
		Reconcile(context.Background())
	var pve *PolicyViolationError
	if !errors.As(err, &pve) {
		t.Fatalf("err = %v, want PolicyViolationError", err)
	}
}

func TestEngineReportOnlyChangesNothing(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS, "CREATE TABLE t DEF t_old")
	report, err := engineReconciler(db, dialect,
		"CREATE TABLE t DEF t_new; CREATE TABLE u DEF u_v1",
		ReportOnlyPolicy()).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReportedOnly {
		t.Fatalf("outcome = %s, want REPORTED_ONLY", report.Outcome)
	}
	if len(report.Applied) != 0 {
		t.Fatalf("applied = %d, want 0", len(report.Applied))
	}
	if len(report.Reported) == 0 {
		t.Fatal("reported is empty")
	}
	if got := len(db.statementsOn(engineNS)); got != 0 {
		t.Fatalf("%d statements ran on target, want 0", got)
	}
	if _, ok := db.objectsIn(engineNS)[model.ObjectId{Kind: model.KindTable, Name: "u"}]; ok {
		t.Fatal("u must not exist")
	}
}

func TestEngineDependentsOfReportedChangesAreDemoted(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS,
		"CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_old")
	script := "CREATE TABLE t DEF t_v1;" +
		" CREATE VIEW v REQUIRES TABLE:t DEF v_new;" +
		" CREATE VIEW w REQUIRES VIEW:v DEF w_v1"
	policy := DefaultPolicy().WithRebuild(DecisionReport)

	report, err := engineReconciler(db, dialect, script, policy).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReportedOnly {
		t.Fatalf("outcome = %s, want REPORTED_ONLY", report.Outcome)
	}
	w := model.ObjectId{Kind: model.KindView, Name: "w"}
	found := false
	for _, c := range report.Reported {
		if c.Target == w {
			found = true
		}
	}
	if !found {
		t.Fatal("w must be demoted to REPORT because v is not applied")
	}
	if _, ok := db.objectsIn(engineNS)[w]; ok {
		t.Fatal("w must not exist")
	}
}

var engineNarrowKinds = []model.ObjectKind{
	model.KindTable, model.KindColumn, model.KindIndex, model.KindView,
	model.KindSequence, model.KindPrimaryKey, model.KindUnique,
	model.KindForeignKey, model.KindCheck, model.KindTrigger,
}

func TestEngineUnsupportedKindIsSeparatePolicyDecision(t *testing.T) {
	db, _ := engineFixture()
	narrow := newFakeDialect(db, engineNarrowKinds...)
	script := "CREATE ROUTINE f DEF f_v1"
	policy, err := DefaultPolicy().WithUnsupported(DecisionReport)
	if err != nil {
		t.Fatal(err)
	}

	r, err := NewBuilder().DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript(script)).
		Dialect(narrow).Policy(policy).Build()
	if err != nil {
		t.Fatal(err)
	}
	result, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	// UNSUPPORTED differences under REPORT do not prevent UP_TO_DATE (3.5)
	if result.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", result.Outcome)
	}
	if len(result.Unsupported) != 1 {
		t.Fatalf("unsupported = %d, want 1", len(result.Unsupported))
	}
	if result.Unsupported[0].Type != model.DifferenceUnsupported {
		t.Fatalf("type = %s, want UNSUPPORTED", result.Unsupported[0].Type)
	}
	if got := len(db.statementsOn(engineNS)); got != 0 {
		t.Fatalf("%d statements ran on target, want 0", got)
	}
	// and unsupported=FAIL rejects the plan
	r, err = NewBuilder().DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript(script)).
		Dialect(narrow).Policy(DefaultPolicy()).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = r.Reconcile(context.Background())
	var pve *PolicyViolationError
	if !errors.As(err, &pve) {
		t.Fatalf("err = %v, want PolicyViolationError", err)
	}
}

func TestEngineUnsupportedDependencyDemotesDependents(t *testing.T) {
	db, _ := engineFixture()
	narrow := newFakeDialect(db, model.KindTable, model.KindView)
	script := "CREATE ROUTINE f DEF f_v1; CREATE VIEW v REQUIRES ROUTINE:f DEF v_v1"
	policy, err := DefaultPolicy().WithUnsupported(DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	r, err := NewBuilder().DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript(script)).
		Dialect(narrow).Policy(policy).Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	v := model.ObjectId{Kind: model.KindView, Name: "v"}
	found := false
	for _, c := range report.Reported {
		if c.Target == v {
			found = true
		}
	}
	if !found {
		t.Fatal("view depending on an unsupported routine must be demoted")
	}
	if _, ok := db.objectsIn(engineNS)[v]; ok {
		t.Fatal("v must not exist")
	}
}

func TestEngineExternalDependentBlocksPlanning(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS, "CREATE TABLE t DEF t_old")
	db.putObject(engineNS, fakeExternalDependent(
		model.ObjectId{Kind: model.KindOther, Name: "foreign.obj"},
		model.ObjectId{Kind: model.KindTable, Name: "t"}))

	_, err := engineReconciler(db, dialect, "CREATE TABLE t DEF t_new",
		DefaultPolicy().WithDestructive(DecisionApply)).Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
}

func TestEngineUnexpectedDependentBlocksPlanning(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS,
		"CREATE TABLE t DEF t_old; CREATE VIEW z REQUIRES TABLE:t DEF z_v1")

	_, err := engineReconciler(db, dialect, "CREATE TABLE t DEF t_new",
		DefaultPolicy().WithDestructive(DecisionApply)).Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
}

func TestEngineDependencyCycleIsPlanningError(t *testing.T) {
	db, dialect := engineFixture()
	script := "CREATE VIEW a REQUIRES VIEW:b DEF a_v1; CREATE VIEW b REQUIRES VIEW:a DEF b_v1"
	_, err := engineReconciler(db, dialect, script).Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
	if !strings.Contains(err.Error(), "cycle") {
		t.Fatalf("err = %v, want 'cycle' in message", err)
	}
}

func TestEngineSelfDependencyIsIgnored(t *testing.T) {
	db, dialect := engineFixture()
	report, err := engineReconciler(db, dialect,
		"CREATE TABLE t REQUIRES TABLE:t DEF t_v1").Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
}

func TestEngineNameCollisionIsPlanningError(t *testing.T) {
	db, dialect := engineFixture()
	db.executeDirect(engineNS, "CREATE INDEX t.x DEF x_v1")
	script := "CREATE UNIQUE t.x DEF x_v1" // UNIQUE claims relation:x like an index

	_, err := engineReconciler(db, dialect, script).Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
}

func TestEngineStalePlanIsRejected(t *testing.T) {
	db, dialect := engineFixture()
	script := "CREATE TABLE t DEF t_v1"
	r := engineReconciler(db, dialect, script)
	plan, err := r.Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}

	db.executeDirect(engineNS, "CREATE TABLE moved DEF m_v1")

	_, err = r.Apply(context.Background(), plan)
	var spe *StalePlanError
	if !errors.As(err, &spe) {
		t.Fatalf("err = %v, want StalePlanError", err)
	}
}

func TestEngineApplyRejectsForeignNamespacePlan(t *testing.T) {
	db, dialect := engineFixture()
	a := engineReconciler(db, dialect, "CREATE TABLE t DEF t_v1")
	b, err := NewBuilder().
		DataSource(db.dataSource()).Namespace("other").
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF t_v1")).
		Dialect(dialect).Build()
	if err != nil {
		t.Fatal(err)
	}
	foreign, err := a.Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	_, err = b.Apply(context.Background(), foreign)
	if !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("err = %v, want ErrInvalidArgument", err)
	}
}

func TestEngineApplyRunsIssuedPlan(t *testing.T) {
	db, dialect := engineFixture()
	r := engineReconciler(db, dialect, "CREATE TABLE t DEF t_v1")
	plan, err := r.Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Changes) == 0 {
		t.Fatal("plan has no changes")
	}
	report, err := r.Apply(context.Background(), plan)
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if _, ok := db.objectsIn(engineNS)[model.ObjectId{Kind: model.KindTable, Name: "t"}]; !ok {
		t.Fatal("t missing")
	}
}

func TestEngineBadScriptFailsAndLeavesNoScratch(t *testing.T) {
	db, dialect := engineFixture()
	_, err := engineReconciler(db, dialect,
		"CREATE TABLE t DEF t_v1; BOGUS").Reconcile(context.Background())
	var dse *DesiredStateError
	if !errors.As(err, &dse) {
		t.Fatalf("err = %v, want DesiredStateError", err)
	}
	if db.lockHeld(engineNS) {
		t.Fatal("lock must be released")
	}
	leaked := false
	for _, s := range db.statements() {
		if strings.HasPrefix(s.Namespace, "rcl_scratch") &&
			len(db.objectsIn(s.Namespace)) > 0 {
			leaked = true
		}
	}
	if leaked {
		t.Fatal("scratch namespace leaked objects")
	}
}

func TestEngineLiteralTargetNamespaceInScriptIsRejected(t *testing.T) {
	db, dialect := engineFixture()
	_, err := engineReconciler(db, dialect,
		"CREATE TABLE "+engineNS+".t DEF t_v1").Reconcile(context.Background())
	var dse *DesiredStateError
	if !errors.As(err, &dse) {
		t.Fatalf("err = %v, want DesiredStateError", err)
	}
	if !strings.Contains(err.Error(), "literally") {
		t.Fatalf("err = %v, want 'literally' in message", err)
	}
	// but a different prefix is fine
	ok, err := engineReconciler(db, dialect,
		"CREATE TABLE xcorex.t DEF t_v1").Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if ok.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", ok.Outcome)
	}
}

func TestEngineListenerAbortRollsBack(t *testing.T) {
	db, dialect := engineFixture()
	r, err := NewBuilder().
		DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF t_v1")).
		Dialect(dialect).
		Listener(ListenerFuncs{
			ApplyingFunc: func(change model.PlannedChange) error {
				return errors.New("stop")
			},
		}).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = r.Reconcile(context.Background())
	var ae *ApplyError
	if !errors.As(err, &ae) {
		t.Fatalf("err = %v, want ApplyError", err)
	}
	if ae.Failed == nil {
		t.Fatal("Failed change not recorded")
	}
	if got := len(db.objectsIn(engineNS)); got != 0 {
		t.Fatalf("transactional rollback removes applied objects, got %d", got)
	}
}

func TestEngineFailuresCarryRunTimings(t *testing.T) {
	db, dialect := engineFixture()
	// an apply-time failure reports the phases it completed plus the partial
	// duration of the failed phase
	r, err := NewBuilder().
		DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF t_v1")).
		Dialect(dialect).
		Listener(ListenerFuncs{
			ApplyingFunc: func(change model.PlannedChange) error {
				return errors.New("stop")
			},
		}).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = r.Reconcile(context.Background())
	var ae *ApplyError
	if !errors.As(err, &ae) {
		t.Fatalf("err = %v, want ApplyError", err)
	}
	timings := ae.Timings()
	if timings.ReadActual < 0 {
		t.Fatal("readActual measured")
	}
	if timings.Apply < 0 {
		t.Fatal("partial apply phase must be measured")
	}
	if timings.Verify != 0 {
		t.Fatal("verify never started")
	}

	// a policy rejection still reports every completed phase
	db.executeDirect(engineNS, "CREATE TABLE t DEF t_old")
	_, err = engineReconciler(db, dialect, "CREATE TABLE t DEF t_new").
		Reconcile(context.Background())
	var pve *PolicyViolationError
	if !errors.As(err, &pve) {
		t.Fatalf("err = %v, want PolicyViolationError", err)
	}
	if pve.Timings().Plan < 0 {
		t.Fatal("plan phase measured")
	}
	if pve.Timings().Apply != 0 {
		t.Fatal("apply never started")
	}
}

func TestEngineConcurrentRunsSerialiseOnLock(t *testing.T) {
	db, dialect := engineFixture()
	// the slow run parks inside its planned listener while holding the lock;
	// the fast run's acquireLock then fails within its lock timeout
	release := make(chan struct{})
	entered := make(chan struct{})
	slow, err := NewBuilder().
		DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF t_v1")).
		Dialect(dialect).
		Listener(ListenerFuncs{
			PlannedFunc: func(plan *model.ReconcilePlan) error {
				close(entered)
				<-release
				return nil
			},
		}).Build()
	if err != nil {
		t.Fatal(err)
	}
	slowDone := make(chan error, 1)
	go func() {
		_, err := slow.Reconcile(context.Background())
		slowDone <- err
	}()
	<-entered

	fast, err := NewBuilder().
		DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF t_v1")).
		Dialect(dialect).LockTimeout(200 * time.Millisecond).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = fast.Reconcile(context.Background())
	var lte *LockTimeoutError
	if !errors.As(err, &lte) {
		t.Fatalf("err = %v, want LockTimeoutError", err)
	}
	close(release)
	if err := <-slowDone; err != nil {
		t.Fatalf("slow run failed: %v", err)
	}
	if db.lockHeld(engineNS) {
		t.Fatal("lock must be released")
	}
}

func TestEnginePlanSqlIsDeterministic(t *testing.T) {
	db, dialect := engineFixture()
	script := "CREATE TABLE t DEF t_v1; CREATE VIEW v REQUIRES TABLE:t DEF v_v1"
	one, err := engineReconciler(db, dialect, script).Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	two, err := engineReconciler(db, dialect, script).Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if one.ToSQL() != two.ToSQL() {
		t.Fatal("plan SQL differs between runs")
	}
	if !strings.Contains(one.ToSQL(), "-- target namespace: "+engineNS) {
		t.Fatal("plan SQL missing target namespace header")
	}
	if !strings.Contains(one.ToSQL(), "actual fingerprint:") {
		t.Fatal("plan SQL missing actual fingerprint")
	}
}

func TestEngineScratchDataSourcePathWorks(t *testing.T) {
	db, dialect := engineFixture()
	r, err := NewBuilder().
		DataSource(db.dataSource()).
		ScratchDataSource(db.dataSource()).
		Namespace(engineNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF t_v1")).
		Dialect(dialect).Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
}

func TestEngineAutoCommitIsRestored(t *testing.T) {
	db, dialect := engineFixture()
	if _, err := engineReconciler(db, dialect, "CREATE TABLE t DEF t_v1").
		Reconcile(context.Background()); err != nil {
		t.Fatal(err)
	}
	// no error means the pooled fake connection was closed cleanly; a leaked
	// tx would surface as missing committed objects
	if _, ok := db.objectsIn(engineNS)[model.ObjectId{Kind: model.KindTable, Name: "t"}]; !ok {
		t.Fatal("t missing")
	}
}

func TestEngineExceptionHierarchy(t *testing.T) {
	for _, err := range []error{
		NewDesiredStateError("x"), NewPlanningError("x"), NewLockTimeoutError("x"),
	} {
		if !errors.Is(err, ErrSchemaReconciler) {
			t.Fatalf("%v is not a SchemaReconcilerError", err)
		}
	}
}

// Spec 5.4: a content-compared object of an unsupported kind that the target
// already holds is no difference.
func TestEngineContentComparedUnsupportedObjectEqualInTargetIsNoDifference(t *testing.T) {
	db, _ := engineFixture()
	narrow := newFakeDialect(db, engineNarrowKinds...)
	id := model.ObjectId{Kind: model.KindRoutine, Name: "f"}
	compared := map[string]string{spi.ContentComparedAttribute: "true"}
	reportPolicy, err := DefaultPolicy().WithUnsupported(DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	run := func(script string, policy ReconcilePolicy) (ReconcileReport, error) {
		r, err := NewBuilder().
			DataSource(db.dataSource()).Namespace(engineNS).
			DesiredState(DesiredStateOfScript(script)).
			Dialect(narrow).Policy(policy).Build()
		if err != nil {
			return ReconcileReport{}, err
		}
		return r.Reconcile(context.Background())
	}

	// (a) equal in the target: nothing to report, and the default FAIL
	// policy does not fire
	db.putObject(engineNS, model.NewSchemaObject(id, model.Definition("f_v1"),
		nil, compared))
	equal, err := run("CREATE ROUTINE f COMPARED DEF f_v1", DefaultPolicy())
	if err != nil {
		t.Fatal(err)
	}
	if equal.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", equal.Outcome)
	}
	if len(equal.Unsupported) != 0 {
		t.Fatalf("unsupported = %d, want 0", len(equal.Unsupported))
	}

	// (c) the content differs: still reported, with the target's definition
	differs, err := run("CREATE ROUTINE f COMPARED DEF f_v2", reportPolicy)
	if err != nil {
		t.Fatal(err)
	}
	if len(differs.Unsupported) != 1 {
		t.Fatalf("unsupported = %d, want 1", len(differs.Unsupported))
	}
	if differs.Unsupported[0].Actual == nil {
		t.Fatal("expected the target's definition attached")
	}

	// (d) without the marker the same equal object is still reported
	unmarked, err := run("CREATE ROUTINE f DEF f_v1", reportPolicy)
	if err != nil {
		t.Fatal(err)
	}
	if len(unmarked.Unsupported) != 1 {
		t.Fatalf("unsupported = %d, want 1", len(unmarked.Unsupported))
	}
}

func TestEngineContentComparedUnsupportedObjectMissingInTargetIsStillReported(t *testing.T) {
	db, _ := engineFixture()
	narrow := newFakeDialect(db, engineNarrowKinds...)
	policy, err := DefaultPolicy().WithUnsupported(DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	r, err := NewBuilder().
		DataSource(db.dataSource()).Namespace(engineNS).
		DesiredState(DesiredStateOfScript("CREATE ROUTINE f COMPARED DEF f_v1")).
		Dialect(narrow).Policy(policy).Build()
	if err != nil {
		t.Fatal(err)
	}
	result, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Unsupported) != 1 {
		t.Fatalf("unsupported = %d, want 1", len(result.Unsupported))
	}
	if result.Unsupported[0].Actual != nil {
		t.Fatal("missing object must not carry an actual definition")
	}
}
