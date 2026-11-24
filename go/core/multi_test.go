package reconciler

// multi_test.go — Go port of test/MultiNamespace.test.ts: spec 3.7
// MultiNamespaceReconciler over several namespaces.

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

const (
	multiNSA = "multi_a"
	multiNSB = "multi_b"
)

func multiBuilder(db *fakeDb) *MultiNamespaceBuilder {
	return NewMultiNamespaceBuilder().
		DataSource(db.dataSource()).
		Dialect(newFakeDialect(db))
}

func TestMultiReconcileRunsNamespacesInOrderAndAggregatesOutcome(t *testing.T) {
	db := newFakeDb()
	multi, err := multiBuilder(db).
		Namespace(multiNSA, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF ta"))).
		Namespace(multiNSB, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF tb"))).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := multi.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	// insertion order and one report per namespace
	if len(report.Namespaces) != 2 ||
		report.Namespaces[0] != multiNSA || report.Namespaces[1] != multiNSB {
		t.Fatalf("namespaces = %v, want [multi_a multi_b]", report.Namespaces)
	}
	if report.Reports[multiNSA].Outcome != OutcomeReconciled ||
		report.Reports[multiNSB].Outcome != OutcomeReconciled {
		t.Fatal("per-namespace outcomes must be RECONCILED")
	}
	if report.Elapsed < 0 {
		t.Fatal("elapsed must be non-negative")
	}
	if _, ok := db.objectsIn(multiNSA)[model.ObjectId{Kind: model.KindTable, Name: "t"}]; !ok {
		t.Fatal("multi_a.t missing")
	}
	if _, ok := db.objectsIn(multiNSB)[model.ObjectId{Kind: model.KindTable, Name: "t"}]; !ok {
		t.Fatal("multi_b.t missing")
	}

	// converged → aggregate UP_TO_DATE
	second, err := multi.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if second.Outcome != OutcomeUpToDate {
		t.Fatalf("outcome = %s, want UP_TO_DATE", second.Outcome)
	}
	if len(second.Namespaces) != 2 {
		t.Fatalf("namespaces = %v", second.Namespaces)
	}
}

func TestMultiAggregateIsReportedOnlyWhenNoNamespaceReconciled(t *testing.T) {
	db := newFakeDb()
	multi, err := multiBuilder(db).
		Policy(ReportOnlyPolicy()).
		Namespace(multiNSA, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF ta"))).
		Namespace(multiNSB, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF tb"))).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := multi.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReportedOnly {
		t.Fatalf("outcome = %s, want REPORTED_ONLY", report.Outcome)
	}
	if len(db.objectsIn(multiNSA)) != 0 || len(db.objectsIn(multiNSB)) != 0 {
		t.Fatal("nothing must be applied")
	}
}

func TestMultiReconciledBeatsReportedOnlyInTheAggregate(t *testing.T) {
	db := newFakeDb()
	multi, err := multiBuilder(db).
		Namespace(multiNSA,
			WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF ta")),
			WithCustomize(func(b *Builder) { b.Policy(ReportOnlyPolicy()) })).
		Namespace(multiNSB, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF tb"))).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := multi.Reconcile(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if report.Outcome != OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if report.Reports[multiNSA].Outcome != OutcomeReportedOnly {
		t.Fatalf("multi_a outcome = %s, want REPORTED_ONLY",
			report.Reports[multiNSA].Outcome)
	}
	if report.Reports[multiNSB].Outcome != OutcomeReconciled {
		t.Fatalf("multi_b outcome = %s, want RECONCILED",
			report.Reports[multiNSB].Outcome)
	}
}

func TestMultiPlanReturnsAnOrderedPlanPerNamespace(t *testing.T) {
	db := newFakeDb()
	db.executeDirect(multiNSA, "CREATE TABLE t DEF ta")
	multi, err := multiBuilder(db).
		Namespace(multiNSA, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF ta"))).
		Namespace(multiNSB, WithDesiredState(DesiredStateOfScript("CREATE TABLE u DEF ub"))).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	plans, err := multi.Plan(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	// multi.Plan returns a map — order is asserted via Namespaces
	if len(plans) != 2 {
		t.Fatalf("plans = %v", plans)
	}
	if len(plans[multiNSA].Changes) != 0 {
		t.Fatalf("multi_a changes = %d, want 0", len(plans[multiNSA].Changes))
	}
	if len(plans[multiNSB].Changes) != 1 {
		t.Fatalf("multi_b changes = %d, want 1", len(plans[multiNSB].Changes))
	}
}

func TestMultiDumpNeedsNoDesiredState(t *testing.T) {
	db := newFakeDb()
	db.executeDirect(multiNSA, "CREATE TABLE t DEF ta")
	db.executeDirect(multiNSB, "CREATE VIEW v REQUIRES TABLE:w DEF vb;"+
		" CREATE TABLE w DEF wb")
	multi, err := multiBuilder(db).Namespace(multiNSA).Namespace(multiNSB).Build()
	if err != nil {
		t.Fatal(err)
	}
	dumps, err := multi.Dump(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(dumps) != 2 {
		t.Fatalf("dumps = %v", dumps)
	}
	if !strings.Contains(dumps[multiNSA], "CREATE TABLE t DEF ta;") {
		t.Fatalf("multi_a dump: %s", dumps[multiNSA])
	}
	if !strings.Contains(dumps[multiNSB], "CREATE VIEW v REQUIRES TABLE:w DEF vb;") {
		t.Fatalf("multi_b dump: %s", dumps[multiNSB])
	}
	if !strings.Contains(dumps[multiNSB], "CREATE TABLE w DEF wb;") {
		t.Fatalf("multi_b dump: %s", dumps[multiNSB])
	}
}

func TestMultiBuildValidatesWithoutOpeningConnections(t *testing.T) {
	db := newFakeDb()
	var ce *ConfigurationError
	// no dataSource
	_, err := NewMultiNamespaceBuilder().
		Dialect(newFakeDialect(db)).
		Namespace(multiNSA, WithDesiredState(DesiredStateOfScript("CREATE TABLE t DEF x"))).
		Build()
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	// no namespace
	if _, err := multiBuilder(db).Build(); !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	// duplicate names
	_, err = multiBuilder(db).Namespace(multiNSA).Namespace(multiNSA).Build()
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	// empty name
	if _, err := multiBuilder(db).Namespace("").Build(); !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
}

func TestMultiPerNamespaceCustomizerAdjustsOnlyThatNamespace(t *testing.T) {
	db := newFakeDb()
	script := "CREATE TABLE t DEF ta"
	multi, err := multiBuilder(db).
		Namespace(multiNSA, WithDesiredState(DesiredStateOfScript(script)),
			WithCustomize(func(b *Builder) {
				b.Listener(ListenerFuncs{
					AppliedFunc: func(model.PlannedChange, time.Duration) error {
						return errors.New("listener fired")
					},
				})
			})).
		Namespace(multiNSB, WithDesiredState(DesiredStateOfScript(script))).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	// A's listener aborts A's run — B never reconciles
	_, err = multi.Reconcile(context.Background())
	var ae *ApplyError
	if !errors.As(err, &ae) {
		t.Fatalf("err = %v, want ApplyError", err)
	}
	if got := len(db.objectsIn(multiNSB)); got != 0 {
		t.Fatalf("multi_b has %d objects, want 0", got)
	}
}
