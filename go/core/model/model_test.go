package model_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

func def(s string) *model.Definition {
	d := model.Definition(s)
	return &d
}

func TestObjectIdValidation(t *testing.T) {
	if _, err := model.NewObjectId(model.KindTable, "t", ""); err != nil {
		t.Fatalf("table without parent: %v", err)
	}
	if _, err := model.NewObjectId(model.KindTable, "t", "p"); err == nil {
		t.Fatal("table with parent must fail")
	}
	if _, err := model.NewObjectId(model.KindColumn, "c", ""); err == nil {
		t.Fatal("column without parent must fail")
	}
	if _, err := model.NewObjectId(model.KindColumn, "c", "t"); err != nil {
		t.Fatalf("column with parent: %v", err)
	}
	if _, err := model.NewObjectId(model.KindTable, "", ""); err == nil {
		t.Fatal("empty name must fail")
	}
	// Mirrored kinds take a parent optionally.
	if _, err := model.NewObjectId(model.KindComment, "c", ""); err != nil {
		t.Fatalf("comment without parent: %v", err)
	}
	if _, err := model.NewObjectId(model.KindComment, "c", "t"); err != nil {
		t.Fatalf("comment with parent: %v", err)
	}
	// Errors are argument-contract errors.
	if _, err := model.NewObjectId(model.KindTable, "", ""); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("want ErrInvalidArgument, got %v", err)
	}
}

func TestObjectIdOrdering(t *testing.T) {
	ids := []model.ObjectId{
		model.MustObjectId(model.KindComment, "z", ""),
		model.MustObjectId(model.KindIndex, "i", "plans"),
		model.MustObjectId(model.KindColumn, "id", "plans"),
		model.MustObjectId(model.KindTable, "plans", ""),
		model.MustObjectId(model.KindColumn, "b", "a"),
		model.MustObjectId(model.KindComment, "a", "t"),
	}
	model.SortObjectIDs(ids)
	want := []model.ObjectId{
		model.MustObjectId(model.KindTable, "plans", ""),
		model.MustObjectId(model.KindColumn, "b", "a"),      // kind, then parent
		model.MustObjectId(model.KindColumn, "id", "plans"), // then name
		model.MustObjectId(model.KindIndex, "i", "plans"),
		model.MustObjectId(model.KindComment, "z", ""), // parent "" sorts first
		model.MustObjectId(model.KindComment, "a", "t"),
	}
	for i := range want {
		if ids[i] != want[i] {
			t.Fatalf("position %d: got %v, want %v", i, ids[i], want[i])
		}
	}
}

func TestObjectIdString(t *testing.T) {
	if got := model.MustObjectId(model.KindTable, "t", "").String(); got != "TABLE t" {
		t.Fatalf("got %q", got)
	}
	if got := model.MustObjectId(model.KindColumn, "c", "t").String(); got != "COLUMN t.c" {
		t.Fatalf("got %q", got)
	}
}

// Golden value produced by the TypeScript implementation's
// SchemaSnapshot.fingerprintOf over the same two objects.
func TestFingerprint(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "plans", "")
	col := model.MustObjectId(model.KindColumn, "id", "plans")
	objects := map[model.ObjectId]model.SchemaObject{
		table: model.NewSchemaObject(table,
			model.Definition("CREATE TABLE ${namespace}.plans (id bigint NOT NULL, plan text CHECK (plan <> ''))"),
			nil, nil),
		col: model.NewSchemaObject(col,
			model.Definition("id bigint NOT NULL"),
			[]model.ObjectId{table}, nil),
	}
	snapshot := model.NewSchemaSnapshot(objects)
	const want = "126de1bafc149c3bde24fad0f0ba2bf57310f4fc97c74276590297097b3f4133"
	if snapshot.Fingerprint != want {
		t.Fatalf("fingerprint:\n got %s\nwant %s", snapshot.Fingerprint, want)
	}
	if _, err := model.NewSchemaSnapshotChecked(objects, snapshot.Fingerprint); err != nil {
		t.Fatalf("checked snapshot: %v", err)
	}
	if _, err := model.NewSchemaSnapshotChecked(objects, "beef"); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("want ErrInvalidArgument, got %v", err)
	}
}

func samplePlan() model.ReconcilePlan {
	table := model.MustObjectId(model.KindTable, "plans", "")
	idx := model.MustObjectId(model.KindIndex, "plans_idx", "plans")
	domain := model.MustObjectId(model.KindDomain, "d", "")
	return model.ReconcilePlan{
		TargetNamespace:    "app",
		DialectID:          "postgresql",
		ActualFingerprint:  "aa11",
		DesiredFingerprint: "bb22",
		Differences: []model.Difference{
			{ID: table, Type: model.DifferenceMissing,
				Expected: def("CREATE TABLE ${namespace}.plans (id bigint)")},
			{ID: idx, Type: model.DifferenceDrifted,
				Expected: def("CREATE INDEX plans_idx ON ${namespace}.plans (id)"),
				Actual:   def("CREATE INDEX plans_idx ON app.plans (id)")},
		},
		Changes: []model.PlannedChange{
			{Order: 1, Target: table, Action: model.ChangeCreate, Safety: model.SafetyAdditive,
				Statements: []string{"CREATE TABLE \"app\".plans (id bigint NOT NULL)"},
				Expected:   def("CREATE TABLE ${namespace}.plans (id bigint)"), Reason: "missing"},
			{Order: 2, Target: idx, Action: model.ChangeDrop, Safety: model.SafetyRebuild,
				Statements: []string{"DROP INDEX \"app\".plans_idx"},
				Reason:     "definition drift",
				Hazard: model.ChangeHazard{
					LockLevel: model.LockAccessExclusive, Notes: []string{}}},
			{Order: 3, Target: idx, Action: model.ChangeCreate, Safety: model.SafetyRebuild,
				Statements: []string{"CREATE INDEX plans_idx ON \"app\".plans (id)"},
				Expected:   def("CREATE INDEX plans_idx ON ${namespace}.plans (id)"),
				Reason:     "definition drift",
				Hazard:     model.ChangeHazard{LockLevel: model.LockShare, Notes: []string{}}},
			{Order: 4, Target: domain, Action: model.ChangeAlter, Safety: model.SafetyDestructive,
				Statements: []string{}, Expected: def("DOMAIN x"), Reason: "manual change",
				NonTransactional: true,
				Hazard:           model.ChangeHazard{ValidatesRows: true, Notes: []string{"check values"}}},
		},
	}
}

// The exact ReconcilePlan.toJson() output of the TypeScript implementation
// for samplePlan — byte-identical output is the spec-4.4 contract.
const goldenPlanJSON = `{"schemaVersion":1,"targetNamespace":"app","dialectId":"postgresql","actualFingerprint":"aa11","desiredFingerprint":"bb22","differences":[{"id":{"kind":"TABLE","name":"plans","parent":null},"type":"MISSING","expected":"CREATE TABLE ${namespace}.plans (id bigint)","actual":null},{"id":{"kind":"INDEX","name":"plans_idx","parent":"plans"},"type":"DRIFTED","expected":"CREATE INDEX plans_idx ON ${namespace}.plans (id)","actual":"CREATE INDEX plans_idx ON app.plans (id)"}],"changes":[{"order":1,"target":{"kind":"TABLE","name":"plans","parent":null},"action":"CREATE","safety":"ADDITIVE","statements":["CREATE TABLE \"app\".plans (id bigint NOT NULL)"],"expected":"CREATE TABLE ${namespace}.plans (id bigint)","reason":"missing","nonTransactional":false,"hazard":{"lockLevel":"NONE","rewritesTable":false,"validatesRows":false,"notes":[]}},{"order":2,"target":{"kind":"INDEX","name":"plans_idx","parent":"plans"},"action":"DROP","safety":"REBUILD","statements":["DROP INDEX \"app\".plans_idx"],"expected":null,"reason":"definition drift","nonTransactional":false,"hazard":{"lockLevel":"ACCESS_EXCLUSIVE","rewritesTable":false,"validatesRows":false,"notes":[]}},{"order":3,"target":{"kind":"INDEX","name":"plans_idx","parent":"plans"},"action":"CREATE","safety":"REBUILD","statements":["CREATE INDEX plans_idx ON \"app\".plans (id)"],"expected":"CREATE INDEX plans_idx ON ${namespace}.plans (id)","reason":"definition drift","nonTransactional":false,"hazard":{"lockLevel":"SHARE","rewritesTable":false,"validatesRows":false,"notes":[]}},{"order":4,"target":{"kind":"DOMAIN","name":"d","parent":null},"action":"ALTER","safety":"DESTRUCTIVE","statements":[],"expected":"DOMAIN x","reason":"manual change","nonTransactional":true,"hazard":{"lockLevel":"NONE","rewritesTable":false,"validatesRows":true,"notes":["check values"]}}]}`

func TestPlanToJSONGolden(t *testing.T) {
	got := string(samplePlan().ToJSON())
	if got != goldenPlanJSON {
		t.Fatalf("toJson mismatch:\n got %s\nwant %s", got, goldenPlanJSON)
	}
}

// The exact ReconcilePlan.toSql() output of the TypeScript implementation.
const goldenPlanSQL = `-- schema reconciler plan
-- target namespace: app
-- dialect: postgresql
-- actual fingerprint: aa11
-- desired fingerprint: bb22
-- session context: unqualified statements assume namespace "app"

-- [1] CREATE TABLE plans (ADDITIVE)
-- missing
CREATE TABLE "app".plans (id bigint NOT NULL);

-- [2] DROP INDEX plans.plans_idx (REBUILD); hazard: lock ACCESS_EXCLUSIVE
-- definition drift
DROP INDEX "app".plans_idx;

-- [3] CREATE INDEX plans.plans_idx (REBUILD); hazard: lock SHARE
-- definition drift
CREATE INDEX plans_idx ON "app".plans (id);

-- [4] ALTER DOMAIN d (DESTRUCTIVE) MANUAL; hazard: validates rows; non-transactional; check values
-- manual change
`

func TestPlanToSQLGolden(t *testing.T) {
	got := samplePlan().ToSQL()
	if got != goldenPlanSQL {
		t.Fatalf("toSql mismatch:\n got\n%s\nwant\n%s", got, goldenPlanSQL)
	}
}

func TestPlanJSONRoundTrip(t *testing.T) {
	parsed, err := model.ParseReconcilePlan(samplePlan().ToJSON())
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if string(parsed.ToJSON()) != goldenPlanJSON {
		t.Fatal("round trip changed the serialisation")
	}
	if parsed.Changes[3].Hazard.Notes[0] != "check values" {
		t.Fatal("hazard notes lost")
	}
	if parsed.Changes[1].Expected != nil {
		t.Fatal("DROP change must have no expected")
	}
}

func TestPlanJSONValidation(t *testing.T) {
	for _, bad := range []string{
		`{`,
		`[]`,
		`{"schemaVersion":2}`,
		goldenPlanJSON + ` {}`,
		`{"schemaVersion":1,"targetNamespace":"app","dialectId":"pg","actualFingerprint":"a","desiredFingerprint":"b","differences":[{"id":{"kind":"NOPE","name":"x"},"type":"MISSING"}],"changes":[]}`,
		`{"schemaVersion":1,"targetNamespace":"app","dialectId":"pg","actualFingerprint":"a","desiredFingerprint":"b","differences":[],"changes":[{"order":1,"target":{"kind":"TABLE","name":"t"},"action":"DROP","safety":"REBUILD","statements":[],"expected":"x","reason":"r"}]}`,
	} {
		if _, err := model.ParseReconcilePlanJSON(bad); !errors.Is(err, model.ErrInvalidArgument) {
			t.Fatalf("want ErrInvalidArgument for %s, got %v", bad, err)
		}
	}
}

// JSON.stringify parity: line separators serialise raw, like JSON.stringify
// and the Java writer — never as \u2028 escapes.
func TestPlanJSONLineSeparators(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "t", "")
	plan := model.ReconcilePlan{
		TargetNamespace: "app", DialectID: "pg",
		ActualFingerprint: "a", DesiredFingerprint: "b",
		Differences: []model.Difference{},
		Changes: []model.PlannedChange{
			{Order: 1, Target: table, Action: model.ChangeCreate, Safety: model.SafetyAdditive,
				Statements: []string{"SELECT 'a\u2028b'"}, Reason: "r"},
		},
	}
	got := string(plan.ToJSON())
	if !strings.Contains(got, "SELECT 'a\u2028b'") {
		t.Fatalf("line separator must serialise raw: %q", got)
	}
	if strings.Contains(got, `\u2028`) {
		t.Fatalf("line separator must not be \\u-escaped: %q", got)
	}
	// Round trip restores the same text.
	parsed, err := model.ParseReconcilePlan(plan.ToJSON())
	if err != nil || parsed.Changes[0].Statements[0] != "SELECT 'a\u2028b'" {
		t.Fatalf("line-separator round trip: %v", err)
	}
	// A literal backslash-u2028 text must round-trip as text, not decode.
	plan2 := plan
	plan2.Changes[0].Statements[0] = `SELECT '\u2028'`
	got2 := string(plan2.ToJSON())
	if !strings.Contains(got2, `\\u2028`) {
		t.Fatalf("literal backslash-u2028 must stay escaped: %q", got2)
	}
}

// JSON.stringify parity: <, > and & in statements must not be \u-escaped.
func TestPlanJSONEscaping(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "t", "")
	plan := model.ReconcilePlan{
		TargetNamespace: "app", DialectID: "pg",
		ActualFingerprint: "a", DesiredFingerprint: "b",
		Differences: []model.Difference{},
		Changes: []model.PlannedChange{
			{Order: 1, Target: table, Action: model.ChangeCreate, Safety: model.SafetyAdditive,
				Statements: []string{"CHECK (x > 0 && y < 5)"}, Reason: "r"},
		},
	}
	got := string(plan.ToJSON())
	if !strings.Contains(got, `CHECK (x > 0 && y < 5)`) {
		t.Fatalf("angle brackets/ampersands must not be escaped: %s", got)
	}
}

func TestPlannedChangeInvariants(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "t", "")
	if _, err := model.NewPlannedChange(-1, table, model.ChangeCreate, model.SafetyAdditive,
		nil, nil, "r", false, model.HazardNone); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("negative order: %v", err)
	}
	if _, err := model.NewPlannedChange(0, table, model.ChangeDrop, model.SafetyRebuild,
		nil, def("x"), "r", false, model.HazardNone); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("DROP with expected: %v", err)
	}
}

func TestRenameHint(t *testing.T) {
	from := model.MustObjectId(model.KindTable, "a", "")
	to := model.MustObjectId(model.KindTable, "b", "")
	if _, err := model.NewRenameHint(from, to); err != nil {
		t.Fatalf("same-kind rename: %v", err)
	}
	if _, err := model.NewRenameHint(from, model.MustObjectId(model.KindView, "b", "")); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("cross-kind rename must fail: %v", err)
	}
}
