package postgresql_test

// End-to-end coverage of the PostgreSQL feature surface that needs a live
// server: invalid-index recovery, session-lock retention across the
// concurrent-index phase, and ACL canonicalization of quoted role names.

import (
	"context"
	"strings"
	"testing"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql"
)

func featureReconciler(t *testing.T, namespace, script string,
	concurrent bool) *core.SchemaReconciler {
	t.Helper()
	b := core.NewBuilder().DataSource(dataSource()).Namespace(namespace).
		DesiredState(core.DesiredStateOfScript(script)).
		Dialect(postgresql.Dialect{}).ConcurrentIndexes(concurrent)
	r, err := b.Build()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func createFeatureNamespace(t *testing.T) string {
	t.Helper()
	ns := "feat_" + nextNamespace()
	d := postgresql.Dialect{}
	if err := execute(context.Background(),
		"CREATE SCHEMA "+quoteID(t, d, ns)); err != nil {
		t.Fatal(err)
	}
	return ns
}

func dropFeatureNamespace(t *testing.T, ns string) {
	t.Helper()
	d := postgresql.Dialect{}
	if err := execute(context.Background(),
		"DROP SCHEMA "+quoteID(t, d, ns)+" CASCADE"); err != nil {
		t.Logf("drop %s: %v", ns, err)
	}
}

func featureIndexValid(ctx context.Context, ns, name string) (bool, error) {
	c, err := scratchPool.Conn(ctx)
	if err != nil {
		return false, err
	}
	defer c.Close()
	var valid bool
	err = c.QueryRowContext(ctx,
		"SELECT i.indisvalid FROM pg_index i"+
			" JOIN pg_class x ON x.oid = i.indexrelid"+
			" JOIN pg_namespace n ON n.oid = x.relnamespace"+
			" WHERE n.nspname = $1 AND x.relname = $2", ns, name).Scan(&valid)
	return valid, err
}

// namespaceLockAvailable probes the session-scoped namespace advisory lock
// from a second connection. A successful try is released before returning,
// so the probe itself leaves no lock behind.
func namespaceLockAvailable(ctx context.Context, ns string) (bool, error) {
	c, err := scratchPool.Conn(ctx)
	if err != nil {
		return false, err
	}
	defer c.Close()
	keySQL := "hashtextextended('studio.epicquest.schema.reconciler:'" +
		" || current_database() || ':' || $1, 0)"
	var available bool
	if err := c.QueryRowContext(ctx,
		"SELECT pg_try_advisory_lock("+keySQL+")", ns).Scan(&available); err != nil {
		return false, err
	}
	if available {
		if _, err := c.ExecContext(ctx,
			"SELECT pg_advisory_unlock("+keySQL+")", ns); err != nil {
			return false, err
		}
	}
	return available, nil
}

func TestFeatureInvalidIndexIsDetectedAsDriftedAndRebuiltConcurrently(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)

	for _, sqlText := range []string{
		"CREATE TABLE " + qNs + ".t (x int)",
		"INSERT INTO " + qNs + ".t VALUES (1), (1)",
	} {
		if err := execute(ctx, sqlText); err != nil {
			t.Fatalf("%s: %v", sqlText, err)
		}
	}
	if err := execute(ctx,
		"CREATE UNIQUE INDEX CONCURRENTLY idx ON "+qNs+".t (x)"); err == nil {
		t.Fatal("duplicate rows must make the concurrent unique build fail")
	}
	valid, err := featureIndexValid(ctx, ns, "idx")
	if err != nil {
		t.Fatal(err)
	}
	if valid {
		t.Fatal("the failed concurrent build must leave an invalid index")
	}
	// Make the unique build possible again; the invalid index itself does
	// not enforce anything.
	if err := execute(ctx, "DELETE FROM "+qNs+".t WHERE ctid IN"+
		" (SELECT ctid FROM "+qNs+".t LIMIT 1)"); err != nil {
		t.Fatal(err)
	}

	script := "CREATE TABLE ${namespace}.t (x int);" +
		" CREATE UNIQUE INDEX idx ON ${namespace}.t (x)"
	plan, err := featureReconciler(t, ns, script, true).Plan(ctx)
	if err != nil {
		t.Fatalf("plan: %v", err)
	}
	var diff *model.Difference
	for i := range plan.Differences {
		d := &plan.Differences[i]
		if d.ID.Kind == model.KindIndex && d.ID.Name == "idx" {
			diff = d
		}
	}
	if diff == nil {
		t.Fatalf("index difference missing: %#v", plan.Differences)
	}
	if diff.Type != model.DifferenceDrifted {
		t.Fatalf("difference type = %s, want DRIFTED", diff.Type)
	}

	report, err := featureReconciler(t, ns, script, true).Reconcile(ctx)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	var sawDrop, sawCreate bool
	for _, c := range report.Applied {
		if c.Target.Kind == model.KindIndex && c.NonTransactional {
			sawDrop = sawDrop || c.Action == model.ChangeDrop
			sawCreate = sawCreate || c.Action == model.ChangeCreate
		}
	}
	if !sawDrop || !sawCreate {
		t.Fatalf("missing non-transactional index drop/create in %#v", report.Applied)
	}
	valid, err = featureIndexValid(ctx, ns, "idx")
	if err != nil {
		t.Fatal(err)
	}
	if !valid {
		t.Fatal("rebuilt index is still invalid")
	}
	second, err := featureReconciler(t, ns, script, true).Reconcile(ctx)
	if err != nil {
		t.Fatalf("re-run: %v", err)
	}
	if second.Outcome != core.OutcomeUpToDate {
		t.Fatalf("re-run outcome = %s, want UP_TO_DATE", second.Outcome)
	}
}

func TestFeatureSessionAdvisoryLockSurvivesConcurrentIndexCommit(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)

	var availableDuringConcurrentPhase, observed bool
	script := "CREATE TABLE ${namespace}.t (id int);" +
		" CREATE INDEX i ON ${namespace}.t (id)"
	b := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript(script)).
		Dialect(postgresql.Dialect{}).ConcurrentIndexes(true).
		Listener(core.ListenerFuncs{
			ApplyingFunc: func(change model.PlannedChange) error {
				if change.Target.Kind == model.KindIndex &&
					change.Action == model.ChangeCreate && change.NonTransactional {
					available, err := namespaceLockAvailable(ctx, ns)
					if err != nil {
						return err
					}
					availableDuringConcurrentPhase = available
					observed = true
				}
				return nil
			},
		})
	r, err := b.Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := r.Reconcile(ctx)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", report.Outcome)
	}
	if !observed {
		t.Fatal("the deferred index create was not observed")
	}
	if availableDuringConcurrentPhase {
		t.Fatal("the namespace lock must still be held after the intermediate commit")
	}
	available, err := namespaceLockAvailable(ctx, ns)
	if err != nil {
		t.Fatal(err)
	}
	if !available {
		t.Fatal("the namespace lock must be released when the run ends")
	}
}

func TestFeatureTableGrantToQuotedCommaContainingRoleRoundTrips(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	role := "rcl,comma_feat_" + nextNamespace()
	qRole := quoteID(t, d, role)

	c, err := scratchPool.Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var canCreateRole bool
	if err := c.QueryRowContext(ctx,
		"SELECT rolsuper OR rolcreaterole FROM pg_roles"+
			" WHERE rolname = current_user").Scan(&canCreateRole); err != nil {
		c.Close()
		t.Fatal(err)
	}
	c.Close()
	if !canCreateRole {
		t.Skip("connecting role cannot create the grantee role")
	}
	if err := execute(ctx, "DROP ROLE IF EXISTS "+qRole); err != nil {
		t.Fatal(err)
	}
	if err := execute(ctx, "CREATE ROLE "+qRole); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := execute(ctx, "DROP ROLE "+qRole); err != nil {
			t.Logf("drop role: %v", err)
		}
	}()

	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	script := "CREATE TABLE ${namespace}.acl_t (id int);" +
		" GRANT SELECT ON ${namespace}.acl_t TO " + qRole
	first, err := featureReconciler(t, ns, script, false).Reconcile(ctx)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if first.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", first.Outcome)
	}
	second, err := featureReconciler(t, ns, script, false).Reconcile(ctx)
	if err != nil {
		t.Fatalf("re-run: %v", err)
	}
	if second.Outcome != core.OutcomeUpToDate {
		t.Fatalf("re-run outcome = %s, want UP_TO_DATE", second.Outcome)
	}

	db := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		Dialect(postgresql.Dialect{})
	dumpReconciler, err := db.Build()
	if err != nil {
		t.Fatal(err)
	}
	dump, err := dumpReconciler.Dump(ctx)
	if err != nil {
		t.Fatalf("dump: %v", err)
	}
	if !strings.Contains(dump, "GRANT SELECT") || !strings.Contains(dump, qRole) {
		t.Fatalf("dump missing quoted grant:\n%s", dump)
	}
	roundTrip, err := featureReconciler(t, ns, dump, false).Reconcile(ctx)
	if err != nil {
		t.Fatalf("dump round-trip: %v", err)
	}
	if roundTrip.Outcome != core.OutcomeUpToDate {
		t.Fatalf("dump round-trip outcome = %s, want UP_TO_DATE", roundTrip.Outcome)
	}
}
