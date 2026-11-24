package reconciler

// dump_test.go — Go port of test/Dump.test.ts: spec 3.1 dump(), the
// namespace-neutral bootstrap path.

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

const dumpNS = "dumpns"

func dumpFixture() *fakeDb {
	db := newFakeDb()
	db.executeDirect(dumpNS,
		"CREATE VIEW v REQUIRES TABLE:t DEF vw;"+
			" CREATE TABLE t DEF tv;"+
			" CREATE COLUMN t.id DEF int")
	return db
}

func dumpReconciler(t *testing.T, db *fakeDb) *SchemaReconciler {
	t.Helper()
	r, err := NewBuilder().DataSource(db.dataSource()).Namespace(dumpNS).
		Dialect(newFakeDialect(db)).Build()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestDumpNeedsNoDesiredState(t *testing.T) {
	db := dumpFixture()
	dump, err := dumpReconciler(t, db).Dump(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(dump, "\n")
	if lines[0] != "-- schema-reconciler dump" {
		t.Fatalf("line 0 = %q", lines[0])
	}
	if lines[1] != "-- dialect: fake" {
		t.Fatalf("line 1 = %q", lines[1])
	}
	if lines[2] != `-- namespace: "`+dumpNS+`"` {
		t.Fatalf("line 2 = %q", lines[2])
	}
	if !strings.HasPrefix(lines[3], "-- fingerprint: ") {
		t.Fatalf("line 3 = %q", lines[3])
	}
	// the table precedes its dependent view (dependsOn order)
	if strings.Index(dump, "CREATE TABLE t") >= strings.Index(dump, "CREATE VIEW v") {
		t.Fatal("the table must precede its dependent view")
	}
	for _, want := range []string{
		"CREATE TABLE t DEF tv;",
		"CREATE VIEW v REQUIRES TABLE:t DEF vw;",
		"CREATE COLUMN t.id DEF int;",
	} {
		if !strings.Contains(dump, want) {
			t.Fatalf("dump missing %q:\n%s", want, dump)
		}
	}
}

func TestDumpPlanReconcileApplyStillRequireDesiredState(t *testing.T) {
	db := dumpFixture()
	r := dumpReconciler(t, db)
	calls := []func() error{
		func() error { _, err := r.Plan(context.Background()); return err },
		func() error { _, err := r.PlanTimed(context.Background()); return err },
		func() error { _, err := r.Reconcile(context.Background()); return err },
		func() error {
			_, err := r.Apply(context.Background(), &model.ReconcilePlan{
				TargetNamespace: dumpNS, DialectID: "fake",
			})
			return err
		},
	}
	for i, call := range calls {
		err := call()
		var ce *ConfigurationError
		if !errors.As(err, &ce) {
			t.Fatalf("call %d: err = %v, want ConfigurationError", i, err)
		}
		if !strings.Contains(err.Error(), "desiredState") {
			t.Fatalf("call %d: err = %v, want 'desiredState' in message", i, err)
		}
	}
}

func TestDumpRoundTripsAndIsDeterministic(t *testing.T) {
	db := dumpFixture()
	dump, err := dumpReconciler(t, db).Dump(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	r, err := NewBuilder().DataSource(db.dataSource()).Namespace(dumpNS).
		DesiredState(DesiredStateOfScript(dump)).
		Dialect(newFakeDialect(db)).Build()
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
		t.Fatalf("changes = %d, want 0", len(report.Plan.Changes))
	}
	again, err := dumpReconciler(t, db).Dump(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if again != dump {
		t.Fatalf("dump is not deterministic:\n%s\n---\n%s", dump, again)
	}
}

func TestDumpUnrenderableObjectsEmitCommentStubs(t *testing.T) {
	db := dumpFixture()
	db.executeDirect(dumpNS, "CREATE OTHER privileges:t DEF grants")
	db.putObject(dumpNS, fakeExternalDependent(
		model.ObjectId{Kind: model.KindView, Name: "x"}))
	dump, err := dumpReconciler(t, db).Dump(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(dump, "-- unsupported: OTHER privileges:t") {
		t.Fatalf("dump missing OTHER stub:\n%s", dump)
	}
	if !strings.Contains(dump, "-- unsupported: VIEW x") {
		t.Fatalf("dump missing VIEW stub:\n%s", dump)
	}
	// stubs never emit a statement that a replay could trip over
	if strings.Contains(dump, "CREATE OTHER") {
		t.Fatal("dump must not contain a CREATE OTHER statement")
	}
}
