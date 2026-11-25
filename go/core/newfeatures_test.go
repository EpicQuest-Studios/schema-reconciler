package reconciler

// newfeatures_test.go — Go port of test/NewFeatures.test.ts: unit coverage
// for the spec 5.5–5.6 and serialisation features on fakeDb — the prune
// policy, JSON plans, hazard metadata, concurrent indexes and rename hints.

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

const featNS = "featx"

func featFixture() (*fakeDb, *fakeDialect) {
	db := newFakeDb()
	return db, newFakeDialect(db)
}

func featReconciler(db *fakeDb, dialect *fakeDialect, script string,
	policy ...ReconcilePolicy) *Builder {
	b := NewBuilder().
		DataSource(db.dataSource()).
		Namespace(featNS).
		DesiredState(DesiredStateOfScript(script)).
		Dialect(dialect)
	if len(policy) > 0 {
		b.Policy(policy[0])
	}
	return b
}

func mustBuild(t *testing.T, b *Builder) *SchemaReconciler {
	t.Helper()
	r, err := b.Build()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// ---- prune (5.5) ------------------------------------------------------

func TestFeatPruneOffLeavesUnexpectedObjectsAlone(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE TABLE extra DEF e")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k")).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindTable, Name: "extra"}]; !ok {
		t.Fatal("extra must remain")
	}
}

func TestFeatPruneDropsAnUnexpectedTableDestructively(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE TABLE extra DEF e")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply))).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindTable, Name: "extra"}]; ok {
		t.Fatal("extra must be dropped")
	}
	var drop *model.PlannedChange
	for i, c := range report.Applied {
		if c.Action == model.ChangeDrop && c.Target.Name == "extra" {
			drop = &report.Applied[i]
		}
	}
	if drop == nil {
		t.Fatal("no drop applied for extra")
	}
	if drop.Safety != model.SafetyDestructive {
		t.Fatalf("safety = %s, want DESTRUCTIVE", drop.Safety)
	}
}

func TestFeatPruneDropsAnUnexpectedViewAsRebuild(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k;"+
		" CREATE VIEW stray REQUIRES TABLE:keep DEF v")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true))).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindView, Name: "stray"}]; ok {
		t.Fatal("stray must be dropped")
	}
	var drop *model.PlannedChange
	for i, c := range report.Applied {
		if c.Action == model.ChangeDrop && c.Target.Kind == model.KindView {
			drop = &report.Applied[i]
		}
	}
	if drop == nil {
		t.Fatal("no view drop applied")
	}
	if drop.Safety != model.SafetyRebuild {
		t.Fatalf("safety = %s, want REBUILD", drop.Safety)
	}
}

func TestFeatPruneSuppressesChildDropsCoveredByAnUnexpectedParent(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k;"+
		" CREATE TABLE stray DEF s; CREATE COLUMN stray.c DEF c")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply))).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	var drops []model.PlannedChange
	for _, c := range report.Plan.Changes {
		if c.Action == model.ChangeDrop {
			drops = append(drops, c)
		}
	}
	if len(drops) != 1 {
		t.Fatalf("the table's drop covers its column, got %d drops", len(drops))
	}
	if drops[0].Target.Kind != model.KindTable {
		t.Fatalf("drop kind = %s, want TABLE", drops[0].Target.Kind)
	}
}

func TestFeatPruneColumnOnExpectedTableRendersDropColumn(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE COLUMN keep.c DEF c")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply))).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindColumn, Name: "c", Parent: "keep"}]; ok {
		t.Fatal("column must be dropped")
	}
	var drop *model.PlannedChange
	for i, c := range report.Applied {
		if c.Action == model.ChangeDrop {
			drop = &report.Applied[i]
		}
	}
	if drop == nil {
		t.Fatal("no drop applied")
	}
	if drop.Target.Kind != model.KindColumn {
		t.Fatalf("drop kind = %s, want COLUMN", drop.Target.Kind)
	}
	if drop.Safety != model.SafetyDestructive {
		t.Fatalf("safety = %s, want DESTRUCTIVE", drop.Safety)
	}
}

func TestFeatPruneDropsOrdinaryDependentsInReverseDependencyOrder(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k;"+
		" CREATE TABLE victim DEF v; CREATE VIEW dependent REQUIRES TABLE:victim DEF d")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply))).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	viewAt, tableAt := -1, -1
	for i, c := range report.Plan.Changes {
		if c.Action == model.ChangeDrop && c.Target.Kind == model.KindView && c.Target.Name == "dependent" {
			viewAt = i
		}
		if c.Action == model.ChangeDrop && c.Target.Kind == model.KindTable && c.Target.Name == "victim" {
			tableAt = i
		}
	}
	if viewAt < 0 || tableAt < 0 {
		t.Fatalf("dependent and victim must both have standalone drops: %#v", report.Plan.Changes)
	}
	if viewAt > tableAt {
		t.Fatalf("dependent drop at %d must precede prerequisite drop at %d", viewAt, tableAt)
	}
}

func TestFeatPruneStillFailsOnExternalDependent(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE TABLE victim DEF v")
	db.putObject(featNS, fakeExternalDependent(
		model.ObjectId{Kind: model.KindOther, Name: "foreign.obj"},
		model.ObjectId{Kind: model.KindTable, Name: "victim"}))
	_, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply))).
		Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
}

func TestFeatPruneStillFailsOnUnprunableOtherDependent(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE TABLE victim DEF v;"+
		" CREATE OTHER sticky REQUIRES TABLE:victim DEF o")
	_, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply))).
		Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
}

// ---- machine-readable plans (JSON) -------------------------------------

func TestFeatPlanJsonRoundTripsAndApplyJsonApplies(t *testing.T) {
	db, dialect := featFixture()
	planned, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE t DEF v1")).
		Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	jsonPlan := planned.ToJSON()
	restored, err := model.ParseReconcilePlan(jsonPlan)
	if err != nil {
		t.Fatal(err)
	}
	if len(restored.Differences) != len(planned.Differences) {
		t.Fatal("differences count mismatch")
	}
	if len(restored.Changes) != len(planned.Changes) {
		t.Fatal("changes count mismatch")
	}
	if string(restored.ToJSON()) != string(jsonPlan) {
		t.Fatal("serialisation is not stable")
	}

	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE t DEF v1")).
		ApplyJSON(context.Background(), string(jsonPlan))
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindTable, Name: "t"}]; !ok {
		t.Fatal("t missing")
	}
}

func TestFeatFromJsonRejectsInvalidDocuments(t *testing.T) {
	for _, bad := range []string{
		"", "{}", "[]", "null", "42",
		`{"schemaVersion":2}`,
		`{"schemaVersion":1,"targetNamespace":"x"}`,
		`{"schemaVersion":1,"targetNamespace":"x","dialectId":"fake",` +
			`"actualFingerprint":"fp","desiredFingerprint":"fp2",` +
			`"differences":{},"changes":[]}`,
		`{"schemaVersion":1,"targetNamespace":"x","dialectId":"fake",` +
			`"actualFingerprint":"fp","desiredFingerprint":"fp2",` +
			`"differences":[],"changes":[{"order":"1"}]}`,
	} {
		if _, err := model.ParseReconcilePlanJSON(bad); !errors.Is(err, model.ErrInvalidArgument) {
			t.Fatalf("input %q: err = %v, want ErrInvalidArgument", bad, err)
		}
	}
}

func TestFeatApplyJsonRejectsAStalePlan(t *testing.T) {
	db, dialect := featFixture()
	planned, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE t DEF v1")).
		Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	db.executeDirect(featNS, "CREATE TABLE other DEF o")
	_, err = mustBuild(t, featReconciler(db, dialect, "CREATE TABLE t DEF v1")).
		ApplyJSON(context.Background(), string(planned.ToJSON()))
	if err == nil {
		t.Fatal("expected an error")
	}
}

// ---- hazard metadata ---------------------------------------------------

func TestFeatHazardDefaultsAreTrivial(t *testing.T) {
	c, err := model.NewPlannedChange(1, model.ObjectId{Kind: model.KindTable, Name: "t"},
		model.ChangeCreate, model.SafetyAdditive, nil, nil, "r", false, model.HazardNone)
	if err != nil {
		t.Fatal(err)
	}
	if c.NonTransactional {
		t.Fatal("nonTransactional must default false")
	}
	if !c.Hazard.IsTrivial() {
		t.Fatal("hazard must be trivial")
	}
	if model.HazardNone.LockLevel != model.LockNone {
		t.Fatal("HazardNone lock level must be NONE")
	}
}

func TestFeatPlanSqlHeadersCarryNonTrivialHazards(t *testing.T) {
	db, dialect := featFixture()
	planned, err := mustBuild(t, featReconciler(db, dialect,
		"CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1").
		ConcurrentIndexes(true)).Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	sql := planned.ToSQL()
	if !strings.Contains(sql, "hazard: lock SHARE_UPDATE_EXCLUSIVE") {
		t.Fatalf("plan SQL missing hazard header:\n%s", sql)
	}
	if !strings.Contains(sql, "non-transactional") {
		t.Fatalf("plan SQL missing non-transactional marker:\n%s", sql)
	}
	// a hazard-aware change surfaces its lock in the header
	hazard, err := model.NewPlannedChange(1,
		model.ObjectId{Kind: model.KindTable, Name: "x"},
		model.ChangeAlter, model.SafetyRebuild, []string{"ALTER TABLE x"}, nil,
		"r", false, model.ChangeHazard{
			LockLevel:     model.LockAccessExclusive,
			RewritesTable: true,
			Notes:         []string{"heavy"},
		})
	if err != nil {
		t.Fatal(err)
	}
	if !hazard.Hazard.RewritesTable {
		t.Fatal("rewritesTable must be true")
	}
	if hazard.Hazard.LockLevel != model.LockAccessExclusive {
		t.Fatalf("lockLevel = %s, want ACCESS_EXCLUSIVE", hazard.Hazard.LockLevel)
	}
}

// ---- concurrent indexes (5.6) -------------------------------------------

func TestFeatConcurrentIndexesRunsTheIndexCreateNonTransactionally(t *testing.T) {
	db, dialect := featFixture()
	report, err := mustBuild(t, featReconciler(db, dialect,
		"CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1").
		ConcurrentIndexes(true)).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	var index *model.PlannedChange
	for i, c := range report.Applied {
		if c.Target.Kind == model.KindIndex {
			index = &report.Applied[i]
		}
	}
	if index == nil {
		t.Fatal("no index applied")
	}
	if !index.NonTransactional {
		t.Fatal("index create must be non-transactional")
	}
	if index.Hazard.LockLevel != model.LockShareUpdateExclusive {
		t.Fatalf("lockLevel = %s, want SHARE_UPDATE_EXCLUSIVE", index.Hazard.LockLevel)
	}
	// a second run stays converged
	again, err := mustBuild(t, featReconciler(db, dialect,
		"CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1").
		ConcurrentIndexes(true)).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if again.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", again.Outcome)
	}
}

func TestFeatConcurrentIndexesOffKeepsIndexCreatesTransactional(t *testing.T) {
	db, dialect := featFixture()
	report, err := mustBuild(t, featReconciler(db, dialect,
		"CREATE TABLE t DEF v1; CREATE INDEX t.i DEF v1")).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	var index *model.PlannedChange
	for i, c := range report.Applied {
		if c.Target.Kind == model.KindIndex {
			index = &report.Applied[i]
		}
	}
	if index == nil {
		t.Fatal("no index applied")
	}
	if index.NonTransactional {
		t.Fatal("index create must be transactional")
	}
}

// ---- rename hints (5.5.1) -------------------------------------------------

func TestFeatBuilderRenameTurnsDropCreateIntoARename(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE old DEF v1")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE new DEF v1").
		Rename(model.ObjectId{Kind: model.KindTable, Name: "old"},
			model.ObjectId{Kind: model.KindTable, Name: "new"})).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	objects := db.objectsIn(featNS)
	if _, ok := objects[model.ObjectId{Kind: model.KindTable, Name: "new"}]; !ok {
		t.Fatal("new must exist")
	}
	if _, ok := objects[model.ObjectId{Kind: model.KindTable, Name: "old"}]; ok {
		t.Fatal("old must be gone")
	}
	var rename *model.PlannedChange
	for i, c := range report.Applied {
		if c.Target.Name == "new" {
			rename = &report.Applied[i]
		}
	}
	if rename == nil {
		t.Fatal("no rename change applied")
	}
	if rename.Action != model.ChangeAlter {
		t.Fatalf("action = %s, want ALTER", rename.Action)
	}
	if !strings.HasPrefix(rename.Statements[0], "RENAME TABLE old TO new") {
		t.Fatalf("rename statement = %q", rename.Statements[0])
	}
	// no drop+create pair survived
	for _, c := range report.Applied {
		if c.Action == model.ChangeDrop {
			t.Fatalf("unexpected drop in applied: %s", c.Target)
		}
	}
}

func TestFeatScriptRenameDirectiveIsPickedUp(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE old DEF v1")
	script := "-- @reconciler rename TABLE old -> new\n" +
		"CREATE TABLE new DEF v1"
	report, err := mustBuild(t, featReconciler(db, dialect, script)).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	objects := db.objectsIn(featNS)
	if _, ok := objects[model.ObjectId{Kind: model.KindTable, Name: "new"}]; !ok {
		t.Fatal("new must exist")
	}
	if _, ok := objects[model.ObjectId{Kind: model.KindTable, Name: "old"}]; ok {
		t.Fatal("old must be gone")
	}
}

func TestFeatRenameWithDriftAppliesTheFollowUpChange(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k;"+
		" CREATE VIEW old REQUIRES TABLE:keep DEF v1")
	report, err := mustBuild(t, featReconciler(db, dialect,
		"CREATE TABLE keep DEF k; CREATE VIEW new REQUIRES TABLE:keep DEF v2").
		Rename(model.ObjectId{Kind: model.KindView, Name: "old"},
			model.ObjectId{Kind: model.KindView, Name: "new"})).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	renamed, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindView, Name: "new"}]
	if !ok {
		t.Fatal("new view must exist")
	}
	if renamed.Definition.Canonical() != "v2" {
		t.Fatalf("definition = %q, want v2", renamed.Definition.Canonical())
	}
	// the rename ran before the recreate pair
	var rename *model.PlannedChange
	for i, c := range report.Applied {
		if c.Action == model.ChangeAlter && rename == nil {
			rename = &report.Applied[i]
		}
	}
	if rename == nil {
		t.Fatal("no rename applied")
	}
	if !strings.HasPrefix(rename.Statements[0], "RENAME VIEW old TO new") {
		t.Fatalf("rename statement = %q", rename.Statements[0])
	}
}

func TestFeatAnAlreadyAppliedRenameHintIsAnIdempotentNoOp(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE new DEF v1")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE new DEF v1").
		Rename(model.ObjectId{Kind: model.KindTable, Name: "old"},
			model.ObjectId{Kind: model.KindTable, Name: "new"})).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", report.Outcome)
	}
}

func TestFeatRenameWithBothSidesPresentIsAPlanningError(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE old DEF v1; CREATE TABLE new DEF v2")
	_, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE new DEF v2").
		Rename(model.ObjectId{Kind: model.KindTable, Name: "old"},
			model.ObjectId{Kind: model.KindTable, Name: "new"})).
		Reconcile(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("err = %v, want PlanningError", err)
	}
}

func TestFeatPlanJsonStrictFields(t *testing.T) {
	valid := `{"schemaVersion":1,"targetNamespace":"x","dialectId":"fake","actualFingerprint":"fp","desiredFingerprint":"fp2","differences":[],"changes":[{"order":1,"target":{"kind":"TABLE","name":"t","parent":null},"action":"CREATE","safety":"ADDITIVE","statements":[],"expected":"x","reason":"r","nonTransactional":false,"hazard":{"lockLevel":"NONE","rewritesTable":false,"validatesRows":false,"notes":[]}}]}`
	bad := []string{
		strings.Replace(valid, `"expected":"x"`, `"expected":42`, 1),
		strings.Replace(valid, `"hazard":{"lockLevel":"NONE","rewritesTable":false,"validatesRows":false,"notes":[]}`, ``, 1),
		strings.Replace(valid, `"order":1`, `"order":2`, 1),
	}
	for _, text := range bad {
		if _, err := model.ParseReconcilePlanJSON(text); !errors.Is(err, model.ErrInvalidArgument) {
			t.Fatalf("err = %v, want ErrInvalidArgument for %s", err, text)
		}
	}
	nonStringDifference := `{"schemaVersion":1,"targetNamespace":"x","dialectId":"fake","actualFingerprint":"fp","desiredFingerprint":"fp2","differences":[{"id":{"kind":"TABLE","name":"t","parent":null},"type":"MISSING","expected":42,"actual":null}],"changes":[]}`
	if _, err := model.ParseReconcilePlanJSON(nonStringDifference); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("err = %v, want ErrInvalidArgument", err)
	}
}

// ---- 0.11 targeted retirement, scoped prune and hooks -------------------

func TestFeatRetireDropsWithoutPruneAndRejectsContradictions(t *testing.T) {
	db, dialect := featFixture()
	old := model.ObjectId{Kind: model.KindTable, Name: "old"}
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE TABLE old DEF o")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithDestructive(DecisionApply)).Retire(old)).Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[old]; ok {
		t.Fatal("retired table must be dropped")
	}
	seen := false
	for _, ch := range report.Applied {
		if ch.Action == model.ChangeDrop && ch.Target == old && strings.Contains(ch.Reason, "retired") {
			seen = true
		}
	}
	if !seen {
		t.Fatalf("retired drop missing: %#v", report.Applied)
	}
	contradictory := featReconciler(db, dialect, "CREATE TABLE old DEF o").Retire(old)
	r, err := contradictory.Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = r.Plan(context.Background())
	var pe *PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("contradictory retire err = %v, want PlanningError", err)
	}
	bad := featReconciler(db, dialect, "CREATE TABLE keep DEF k").Retire(
		model.ObjectId{Kind: model.KindOther, Name: "x"})
	if _, err := bad.Build(); err == nil {
		t.Fatal("retire of OTHER must fail at Build")
	}
}

func TestFeatPruneFilterProtectsContainerTreeButKeepsItVisible(t *testing.T) {
	db, dialect := featFixture()
	db.executeDirect(featNS, "CREATE TABLE keep DEF k; CREATE TABLE stray DEF s;"+
		" CREATE COLUMN stray.c DEF c")
	report, err := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE keep DEF k",
		DefaultPolicy().WithPrune(true).WithDestructive(DecisionApply)).
		PruneFilter(func(id model.ObjectId) bool { return id.Name != "stray" })).
		Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s", report.Outcome)
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindTable, Name: "stray"}]; !ok {
		t.Fatal("protected table must remain")
	}
	if _, ok := db.objectsIn(featNS)[model.ObjectId{Kind: model.KindColumn, Name: "c", Parent: "stray"}]; !ok {
		t.Fatal("protected column must remain")
	}
	tableVisible, columnVisible := false, false
	for _, d := range report.Unexpected {
		if d.ID.Name == "stray" {
			tableVisible = true
		}
		if d.ID.Kind == model.KindColumn && d.ID.Parent == "stray" {
			columnVisible = true
		}
	}
	if !tableVisible || !columnVisible {
		t.Fatalf("protected objects must stay visible: %#v", report.Unexpected)
	}
	for _, ch := range report.Plan.Changes {
		if ch.Action == model.ChangeDrop {
			t.Fatalf("protected tree produced a drop: %#v", report.Plan.Changes)
		}
	}
}

func TestFeatHooksRunInDeterministicOrderButNeverDuringPlanning(t *testing.T) {
	db, dialect := featFixture()
	events := []string{}
	hook := func(id string, phase HookPhase, order int) SchemaHook {
		return SchemaHook{ID: id, Phase: phase, Order: order,
			Run: func(context.Context, HookContext) error {
				events = append(events, id)
				return nil
			}}
	}
	r := mustBuild(t, featReconciler(db, dialect, "CREATE TABLE t DEF v").
		Hook(hook("b", HookPreApply, 2)).
		Hook(hook("c", HookPostApply, 0)).
		Hook(hook("a", HookPreApply, 1)))
	report, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s", report.Outcome)
	}
	if strings.Join(events, ",") != "a,b,c" {
		t.Fatalf("hook order = %v", events)
	}
	count := len(events)
	if _, err := r.Plan(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(events) != count {
		t.Fatal("plan() must not run hooks")
	}
	withoutStore := featReconciler(db, dialect, "CREATE TABLE x DEF v").Hook(
		SchemaHook{ID: "x", Phase: HookPreApply, RunOnce: true,
			Run: func(context.Context, HookContext) error { return nil }})
	if _, err := withoutStore.Build(); err == nil {
		t.Fatal("runOnce without HookStore must fail at Build")
	}
	duplicate := featReconciler(db, dialect, "CREATE TABLE x DEF v").
		Hook(hook("x", HookPreApply, 0)).
		Hook(hook("x", HookPostApply, 0))
	if _, err := duplicate.Build(); err == nil {
		t.Fatal("duplicate hook ids must fail at Build")
	}
}
