package postgresql_test

// End-to-end coverage of the PostgreSQL feature surface that needs a live
// server: invalid-index recovery, session-lock retention across the
// concurrent-index phase, and ACL canonicalization of quoted role names.

import (
	"context"
	"errors"
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

func featureTableExists(ctx context.Context, ns, name string) (bool, error) {
	c, err := scratchPool.Conn(ctx)
	if err != nil {
		return false, err
	}
	defer c.Close()
	var exists bool
	err = c.QueryRowContext(ctx, "SELECT to_regclass($1) IS NOT NULL",
		`"`+ns+`".`+name).Scan(&exists)
	return exists, err
}

func TestFeatureManagedPartitionedIndexesReconcileDumpAndRename(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	script := "CREATE FUNCTION usage_day(timestamptz) RETURNS date" +
		" LANGUAGE sql IMMUTABLE AS $$ SELECT $1::date $$;" +
		" CREATE TABLE usage_records (tenant_account_id bigint," +
		" created_at timestamptz NOT NULL," +
		" CONSTRAINT usage_records_pkey PRIMARY KEY (created_at, tenant_account_id)," +
		" CONSTRAINT usage_records_tenant_key UNIQUE (tenant_account_id, created_at))" +
		" PARTITION BY RANGE (created_at);" +
		" CREATE TABLE usage_records_p2026_11 PARTITION OF usage_records" +
		" FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');" +
		" CREATE INDEX usage_records_tenant_idx ON usage_records (tenant_account_id)" +
		" WHERE tenant_account_id IS NOT NULL;" +
		" CREATE UNIQUE INDEX usage_records_tenant_created_uq" +
		" ON usage_records (tenant_account_id, created_at);" +
		" CREATE INDEX usage_records_day_idx ON usage_records (usage_day(created_at))"
	first, err := featureReconciler(t, ns, script, true).Reconcile(ctx)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if first.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %s, want RECONCILED", first.Outcome)
	}
	indexCreates := 0
	for _, ch := range first.Applied {
		if ch.Target.Kind == model.KindIndex {
			indexCreates++
			if ch.NonTransactional {
				t.Fatalf("partitioned index create became non-transactional: %#v", ch)
			}
			for _, sqlText := range ch.Statements {
				if strings.Contains(sqlText, "CONCURRENTLY") {
					t.Fatalf("partitioned index rendered CONCURRENTLY: %s", sqlText)
				}
			}
		}
	}
	if indexCreates < 2 {
		t.Fatalf("managed partitioned indexes missing from applied changes: %#v", first.Applied)
	}
	again, err := featureReconciler(t, ns, script, false).Reconcile(ctx)
	if err != nil || again.Outcome != core.OutcomeUpToDate {
		t.Fatalf("second run outcome=%s err=%v", again.Outcome, err)
	}
	dumper, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		Dialect(postgresql.Dialect{}).Build()
	if err != nil {
		t.Fatal(err)
	}
	dump, err := dumper.Dump(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(dump, "CREATE INDEX usage_records_tenant_idx ON ${namespace}.usage_records") ||
		strings.Contains(dump, "ON ONLY") {
		t.Fatalf("recursive partitioned index dump wrong:\n%s", dump)
	}

	renamed := strings.ReplaceAll(script, "usage_records_tenant_idx", "usage_records_tenant_new_idx") +
		"\n-- @reconciler rename INDEX usage_records.usage_records_tenant_idx -> usage_records_tenant_new_idx"
	rename, err := featureReconciler(t, ns, renamed, false).Reconcile(ctx)
	if err != nil {
		t.Fatalf("rename reconcile: %v", err)
	}
	seenRename := false
	for _, ch := range rename.Applied {
		if ch.Action == model.ChangeAlter && ch.Target.Kind == model.KindIndex &&
			ch.Target.Name == "usage_records_tenant_new_idx" {
			seenRename = true
		}
	}
	if !seenRename {
		t.Fatalf("partitioned index rename missing: %#v", rename.Applied)
	}
	exists, err := featureTableExists(ctx, ns, "usage_records_tenant_new_idx")
	if err != nil || !exists {
		t.Fatalf("renamed index exists=%v err=%v", exists, err)
	}
	up, err := featureReconciler(t, ns, renamed, false).Reconcile(ctx)
	if err != nil || up.Outcome != core.OutcomeUpToDate {
		t.Fatalf("post-rename outcome=%s err=%v", up.Outcome, err)
	}
}

func TestFeatureInvalidOnOnlyPartitionedIndexRecoversToValid(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	base := "CREATE TABLE usage_records (tenant_account_id bigint," +
		" created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);" +
		" CREATE TABLE usage_records_p2026_11 PARTITION OF usage_records" +
		" FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');"
	invalid := base + " CREATE INDEX usage_records_tenant_idx ON ONLY usage_records (tenant_account_id)"
	if _, err := featureReconciler(t, ns, invalid, false).Reconcile(ctx); err != nil {
		t.Fatalf("invalid reconcile: %v", err)
	}
	valid, err := featureIndexValid(ctx, ns, "usage_records_tenant_idx")
	if err != nil {
		t.Fatal(err)
	}
	if valid {
		t.Fatal("ON ONLY index should remain intentionally invalid")
	}
	validScript := base + " CREATE INDEX usage_records_tenant_idx ON usage_records (tenant_account_id)"
	if _, err := featureReconciler(t, ns, validScript, false).Reconcile(ctx); err != nil {
		t.Fatalf("recovery reconcile: %v", err)
	}
	valid, err = featureIndexValid(ctx, ns, "usage_records_tenant_idx")
	if err != nil || !valid {
		t.Fatalf("recovered valid=%v err=%v", valid, err)
	}
	up, err := featureReconciler(t, ns, validScript, false).Reconcile(ctx)
	if err != nil || up.Outcome != core.OutcomeUpToDate {
		t.Fatalf("recovered second outcome=%s err=%v", up.Outcome, err)
	}
}

func TestFeatureExternalPartitionsCoexistThroughPlanApplyDumpAndPrune(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)
	base := "CREATE TABLE usage_records (tenant_account_id bigint," +
		" created_at timestamptz NOT NULL) PARTITION BY RANGE (created_at);" +
		" CREATE TABLE usage_records_default PARTITION OF usage_records DEFAULT"
	policy := core.DefaultPolicy().WithDestructive(core.DecisionApply).WithPrune(true)
	builder := func(script string) *core.SchemaReconciler {
		r, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
			DesiredState(core.DesiredStateOfScript(script)).Dialect(d).Policy(policy).
			ExternalPartitionsPrefix("usage_records", "usage_records_p").Build()
		if err != nil {
			t.Fatal(err)
		}
		return r
	}
	if _, err := builder(base).Reconcile(ctx); err != nil {
		t.Fatalf("base reconcile: %v", err)
	}
	if err := execute(ctx, "CREATE TABLE "+qNs+".usage_records_p2026_11 PARTITION OF "+qNs+
		".usage_records FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')"); err != nil {
		t.Fatal(err)
	}
	up, err := builder(base).Reconcile(ctx)
	if err != nil || up.Outcome != core.OutcomeUpToDate || len(up.Unexpected) != 0 {
		t.Fatalf("external partition outcome=%s unexpected=%#v err=%v", up.Outcome, up.Unexpected, err)
	}
	withIndex := base + "; CREATE INDEX usage_records_tenant_idx ON usage_records (tenant_account_id)"
	plan, err := builder(withIndex).Plan(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if err := execute(ctx, "CREATE TABLE "+qNs+".usage_records_p2026_12 PARTITION OF "+qNs+
		".usage_records FOR VALUES FROM ('2026-12-01') TO ('2027-01-01')"); err != nil {
		t.Fatal(err)
	}
	applied, err := builder(withIndex).Apply(ctx, plan)
	if err != nil || applied.Outcome != core.OutcomeReconciled {
		t.Fatalf("apply outcome=%s err=%v", applied.Outcome, err)
	}
	dumper, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		Dialect(d).ExternalPartitionsPrefix("usage_records", "usage_records_p").Build()
	if err != nil {
		t.Fatal(err)
	}
	dump, err := dumper.Dump(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(dump, "usage_records_p2026_") || !strings.Contains(dump, "usage_records_default") {
		t.Fatalf("external partition dump wrong:\n%s", dump)
	}
}

func TestFeatureRetireAndPruneFilterProtectMigrationTableTree(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)
	if err := execute(ctx, "CREATE TABLE "+qNs+".router_models"+
		" (id bigint PRIMARY KEY, slug text NOT NULL, tenant_id bigint NOT NULL);"+
		" ALTER TABLE "+qNs+".router_models ADD CONSTRAINT router_models_slug_key UNIQUE (slug);"+
		" CREATE TABLE "+qNs+".goose_db_version (id bigserial PRIMARY KEY, version bigint NOT NULL)"); err != nil {
		t.Fatal(err)
	}
	script := "CREATE TABLE router_models (id bigint PRIMARY KEY," +
		" slug text NOT NULL, tenant_id bigint NOT NULL," +
		" CONSTRAINT router_models_global_slug_uq UNIQUE (slug)," +
		" CONSTRAINT router_models_tenant_slug_uq UNIQUE (tenant_id, slug));" +
		"\n-- @reconciler retire UNIQUE router_models.router_models_slug_key"
	report, err := featureReconciler(t, ns, script, false).Reconcile(ctx)
	if err != nil {
		t.Fatalf("retire reconcile: %v", err)
	}
	seenRetire := false
	for _, ch := range report.Applied {
		if ch.Action == model.ChangeDrop && ch.Target.Kind == model.KindUnique &&
			ch.Target.Name == "router_models_slug_key" && strings.Contains(ch.Reason, "retired") {
			seenRetire = true
		}
	}
	if !seenRetire {
		t.Fatalf("retired constraint drop missing: %#v", report.Applied)
	}
	exists, err := featureTableExists(ctx, ns, "goose_db_version")
	if err != nil || !exists {
		t.Fatalf("goose table exists=%v err=%v", exists, err)
	}
	up, err := featureReconciler(t, ns, script, false).Reconcile(ctx)
	if err != nil || up.Outcome != core.OutcomeUpToDate {
		t.Fatalf("retire second outcome=%s err=%v", up.Outcome, err)
	}
	if err := execute(ctx, "ALTER TABLE "+qNs+".router_models"+
		" ADD CONSTRAINT router_models_obsolete_uq UNIQUE (id, slug)"); err != nil {
		t.Fatal(err)
	}
	policy := core.DefaultPolicy().WithDestructive(core.DecisionApply).WithPrune(true)
	pruneReconciler, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript(script)).Dialect(d).Policy(policy).
		PruneFilter(func(id model.ObjectId) bool { return id.Name != "goose_db_version" }).Build()
	if err != nil {
		t.Fatal(err)
	}
	prune, err := pruneReconciler.Reconcile(ctx)
	if err != nil {
		t.Fatalf("prune reconcile: %v", err)
	}
	if prune.Outcome != core.OutcomeReconciled {
		t.Fatalf("prune outcome=%s", prune.Outcome)
	}
	for _, name := range []string{"goose_db_version", "goose_db_version_id_seq"} {
		exists, err = featureTableExists(ctx, ns, name)
		if err != nil || !exists {
			t.Fatalf("protected %s exists=%v err=%v", name, exists, err)
		}
	}
	protectedVisible := false
	for _, diff := range prune.Unexpected {
		if diff.ID.Name == "goose_db_version" {
			protectedVisible = true
		}
	}
	if !protectedVisible {
		t.Fatal("protected objects must remain visible as unexpected")
	}
}

func TestFeatureSchemaHooksOrderBackfillRollbackAndRunOnce(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)
	if err := execute(ctx, "CREATE TABLE "+qNs+".accounts (id bigint PRIMARY KEY, slug text);"+
		" INSERT INTO "+qNs+".accounts (id, slug) VALUES (1, NULL)"); err != nil {
		t.Fatal(err)
	}
	store, err := postgresql.NewHookStore(ns, "")
	if err != nil {
		t.Fatal(err)
	}
	events := []string{}
	script := "CREATE TABLE accounts (id bigint PRIMARY KEY, slug text NOT NULL);" +
		" CREATE TABLE hook_audit (id int)"
	policy := core.DefaultPolicy().WithDestructive(core.DecisionApply)
	hooked, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript(script)).Dialect(d).Policy(policy).
		HookStore(store).
		Hook(core.SchemaHook{ID: "z-post", Phase: core.HookPostCommit, Order: 2,
			Run: func(ctx context.Context, hc core.HookContext) error {
				events = append(events, "z")
				return nil
			}}).
		Hook(core.SchemaHook{ID: "a-pre", Phase: core.HookPreApply, Order: 1,
			Run: func(ctx context.Context, hc core.HookContext) error {
				events = append(events, "a")
				_, err := hc.Conn.ExecContext(ctx, "UPDATE accounts SET slug = 'x' WHERE slug IS NULL")
				return err
			}}).
		Hook(core.SchemaHook{ID: "m-once", Phase: core.HookPostApply, Order: 0,
			RunOnce: true, Checksum: "v1",
			Run: func(ctx context.Context, hc core.HookContext) error {
				events = append(events, "m")
				_, err := hc.Conn.ExecContext(ctx, "INSERT INTO hook_audit (id) VALUES (1)")
				return err
			}}).Build()
	if err != nil {
		t.Fatal(err)
	}
	first, err := hooked.Reconcile(ctx)
	if err != nil || first.Outcome != core.OutcomeReconciled {
		t.Fatalf("hooked outcome=%s err=%v", first.Outcome, err)
	}
	if strings.Join(events, ",") != "a,m,z" {
		t.Fatalf("hook order = %v", events)
	}
	eventCount := len(events)
	up, err := hooked.Reconcile(ctx)
	if err != nil || up.Outcome != core.OutcomeUpToDate {
		t.Fatalf("second hooked outcome=%s err=%v", up.Outcome, err)
	}
	if len(events) != eventCount+2 || strings.Count(strings.Join(events, ","), "m") != 1 {
		t.Fatalf("run-once hook did not skip correctly: %v", events)
	}
	exists, err := featureTableExists(ctx, ns, "schema_reconciler_hooks")
	if err != nil || !exists {
		t.Fatalf("hook table exists=%v err=%v", exists, err)
	}
	changed, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript(script)).Dialect(d).HookStore(store).
		Hook(core.SchemaHook{ID: "m-once", Phase: core.HookPostApply, RunOnce: true,
			Checksum: "v2", Run: func(context.Context, core.HookContext) error { return nil }}).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = changed.Reconcile(ctx)
	var applyErr *core.ApplyError
	if !errors.As(err, &applyErr) {
		t.Fatalf("checksum mismatch error = %T %v", err, err)
	}
	failing, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE rollback_probe (id int)")).Dialect(d).
		Hook(core.SchemaHook{ID: "fail", Phase: core.HookPostApply,
			Run: func(ctx context.Context, hc core.HookContext) error {
				if _, err := hc.Conn.ExecContext(ctx, "INSERT INTO rollback_probe (id) VALUES (1)"); err != nil {
					return err
				}
				return errors.New("boom")
			}}).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = failing.Reconcile(ctx)
	if !errors.As(err, &applyErr) {
		t.Fatalf("hook failure error = %T %v", err, err)
	}
	exists, err = featureTableExists(ctx, ns, "rollback_probe")
	if err != nil {
		t.Fatal(err)
	}
	if exists {
		t.Fatal("POST_APPLY failure must roll back the DDL transaction")
	}
}

func TestFeatureExternalPartitionsRemainVisibleWhenParentIsPruned(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)
	if err := execute(ctx, "CREATE TABLE "+qNs+".usage_records (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at);"+
		"CREATE TABLE "+qNs+".usage_records_p2026_11 PARTITION OF "+qNs+".usage_records FOR VALUES FROM ('2026-11-01') TO ('2026-12-01')"); err != nil {
		t.Fatal(err)
	}
	r, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).DesiredState(core.DesiredStateOfScript("")).
		Dialect(d).Policy(core.DefaultPolicy().WithPrune(true).WithDestructive(core.DecisionApply)).
		ExternalPartitionsPrefix("usage_records", "usage_records_p").Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := r.Reconcile(ctx)
	if err != nil || report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome=%s err=%v", report.Outcome, err)
	}
	partitionDrop, tableDrop := false, false
	for _, ch := range report.Applied {
		if ch.Action == model.ChangeDrop && ch.Target.Kind == model.KindPartition && ch.Target.Name == "usage_records_p2026_11" {
			partitionDrop = true
		}
		if ch.Action == model.ChangeDrop && ch.Target.Kind == model.KindTable && ch.Target.Name == "usage_records" {
			tableDrop = true
		}
	}
	if !partitionDrop || !tableDrop {
		t.Fatalf("partition/table drops missing: %#v", report.Applied)
	}
}

func TestFeatureRetireEdgeCases(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)
	if err := execute(ctx, "CREATE TABLE "+qNs+".t (id int, old text)"); err != nil {
		t.Fatal(err)
	}
	retireColumn, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE t (id int)")).Dialect(d).
		Policy(core.DefaultPolicy().WithDestructive(core.DecisionApply)).
		Retire(model.ObjectId{Kind: model.KindColumn, Name: "old", Parent: "t"}).Build()
	if err != nil {
		t.Fatal(err)
	}
	column, err := retireColumn.Reconcile(ctx)
	if err != nil || column.Outcome != core.OutcomeReconciled {
		t.Fatalf("column outcome=%s err=%v", column.Outcome, err)
	}
	seen := false
	for _, ch := range column.Applied {
		if ch.Target.Kind == model.KindColumn && ch.Safety == model.SafetyDestructive {
			for _, sql := range ch.Statements {
				if strings.Contains(sql, "DROP COLUMN") {
					seen = true
				}
			}
		}
	}
	if !seen {
		t.Fatalf("retired DROP COLUMN missing: %#v", column.Applied)
	}
	if err := execute(ctx, "CREATE TABLE "+qNs+".victim (id int)"); err != nil {
		t.Fatal(err)
	}
	victim := model.ObjectId{Kind: model.KindTable, Name: "victim"}
	failR, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE t (id int)")).Dialect(d).Retire(victim).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = failR.Reconcile(ctx)
	var pve *core.PolicyViolationError
	if !errors.As(err, &pve) {
		t.Fatalf("destructive FAIL err=%v", err)
	}
	if err := execute(ctx, "CREATE VIEW "+qNs+".dependent AS SELECT id FROM "+qNs+".victim"); err != nil {
		t.Fatal(err)
	}
	depR, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE t (id int)")).Dialect(d).
		Policy(core.DefaultPolicy().WithDestructive(core.DecisionApply)).Retire(victim).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = depR.Reconcile(ctx)
	var pe *core.PlanningError
	if !errors.As(err, &pe) {
		t.Fatalf("dependent retire err=%v", err)
	}
}

func TestFeatureHooksReportedOnlyAndPostCommitFailure(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	events := []string{}
	hook := func(phase core.HookPhase) core.SchemaHook {
		return core.SchemaHook{ID: phase.String(), Phase: phase, Run: func(context.Context, core.HookContext) error {
			events = append(events, phase.String())
			return nil
		}}
	}
	reported, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE later (id int)")).Dialect(d).
		Policy(core.ReportOnlyPolicy()).Hook(hook(core.HookPreApply)).Hook(hook(core.HookPostApply)).
		Hook(hook(core.HookPostCommit)).Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := reported.Reconcile(ctx)
	if err != nil || report.Outcome != core.OutcomeReportedOnly || len(events) != 0 {
		t.Fatalf("reported outcome=%s events=%v err=%v", report.Outcome, events, err)
	}
	failing, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE later (id int)")).Dialect(d).
		Hook(core.SchemaHook{ID: "boom", Phase: core.HookPostCommit,
			Run: func(context.Context, core.HookContext) error { return errors.New("boom") }}).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = failing.Reconcile(ctx)
	var ae *core.ApplyError
	if !errors.As(err, &ae) {
		t.Fatalf("post-commit err=%v", err)
	}
	carried := false
	for _, ch := range ae.Applied {
		if ch.Target.Name == "later" {
			carried = true
		}
	}
	if !carried {
		t.Fatalf("applied changes not carried: %#v", ae.Applied)
	}
}

func TestFeatureDedicatedHookStoreAndSubPartitionIndex(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	internal := "_reconciler_internal_" + nextNamespace()
	if err := execute(ctx, "CREATE SCHEMA "+quoteID(t, d, internal)); err != nil {
		t.Fatal(err)
	}
	defer dropFeatureNamespace(t, internal)
	store, err := postgresql.NewHookStore(internal, "")
	if err != nil {
		t.Fatal(err)
	}
	runs := 0
	r, err := core.NewBuilder().DataSource(dataSource()).Namespace(ns).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE hooked (id int)")).Dialect(d).
		HookStore(store).Hook(core.SchemaHook{ID: "once", Phase: core.HookPostCommit,
		RunOnce: true, Checksum: "v1", Run: func(context.Context, core.HookContext) error {
			runs++
			return nil
		}}).Build()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = r.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err = r.Reconcile(ctx); err != nil {
		t.Fatal(err)
	}
	if runs != 1 {
		t.Fatalf("runOnce runs=%d", runs)
	}
	sub := "CREATE TABLE measurement (city_id int, logdate date) PARTITION BY RANGE (logdate);" +
		"CREATE TABLE measurement_2026 PARTITION OF measurement FOR VALUES FROM ('2026-01-01') TO ('2027-01-01') PARTITION BY LIST (city_id);" +
		"CREATE TABLE measurement_2026_x PARTITION OF measurement_2026 FOR VALUES IN (1);" +
		"CREATE INDEX measurement_city_idx ON measurement_2026 (city_id)"
	applied, err := featureReconciler(t, ns, sub, true).Reconcile(ctx)
	if err != nil {
		t.Fatal(err)
	}
	seenIndex := false
	for _, ch := range applied.Applied {
		if ch.Target.Kind == model.KindIndex && ch.Target.Name == "measurement_city_idx" {
			seenIndex = true
			if ch.NonTransactional {
				t.Fatal("sub-partitioned partition index must stay transactional")
			}
		}
	}
	if !seenIndex {
		t.Fatal("sub-partitioned partition index missing")
	}
}

func TestFeatureMultiNamespaceForwards011Options(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}
	ns := createFeatureNamespace(t)
	defer dropFeatureNamespace(t, ns)
	qNs := quoteID(t, d, ns)
	if err := execute(ctx, "CREATE TABLE "+qNs+".usage_records (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at);"+
		"CREATE TABLE "+qNs+".usage_records_p2026_11 PARTITION OF "+qNs+".usage_records FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');"+
		"CREATE TABLE "+qNs+".goose_db_version (id int)"); err != nil {
		t.Fatal(err)
	}
	store, err := postgresql.NewHookStore(ns, "")
	if err != nil {
		t.Fatal(err)
	}
	runs := 0
	multi, err := core.NewMultiNamespaceBuilder().DataSource(dataSource()).Dialect(d).
		Policy(core.DefaultPolicy().WithPrune(true).WithDestructive(core.DecisionApply)).
		ExternalPartitionsPrefix("usage_records", "usage_records_p").
		PruneFilter(func(id model.ObjectId) bool { return id.Name != "goose_db_version" }).
		HookStore(store).
		Namespace(ns,
			core.WithDesiredState(core.DesiredStateOfScript("CREATE TABLE usage_records (id bigint, created_at timestamptz) PARTITION BY RANGE (created_at)")),
			core.WithCustomize(func(b *core.Builder) {
				b.Hook(core.SchemaHook{ID: "once", Phase: core.HookPostCommit, RunOnce: true,
					Checksum: "v1", Run: func(context.Context, core.HookContext) error {
						runs++
						return nil
					}})
			})).Build()
	if err != nil {
		t.Fatal(err)
	}
	report, err := multi.Reconcile(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if report.Reports[ns].Outcome != core.OutcomeUpToDate {
		t.Fatalf("outcome=%s", report.Reports[ns].Outcome)
	}
	exists, err := featureTableExists(ctx, ns, "goose_db_version")
	if err != nil || !exists || runs != 1 {
		t.Fatalf("goose exists=%v runs=%d err=%v", exists, runs, err)
	}
	exists, err = featureTableExists(ctx, ns, "schema_reconciler_hooks")
	if err != nil || !exists {
		t.Fatalf("hook table exists=%v err=%v", exists, err)
	}
}
