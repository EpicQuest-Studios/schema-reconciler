package tck

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// UnsupportedScript is a script creating an unsupported object and one
// supported object depending on it.
type UnsupportedScript struct {
	Script        string
	DependentName string
}

// Hooks are the dialect-supplied hooks — the Java abstract/optional
// methods of DialectConformanceTest.
type Hooks struct {
	// DataSource is a data source on the disposable database under test.
	DataSource func() spi.DB
	// Dialect is the dialect under test.
	Dialect func() spi.Dialect
	// CreateNamespace creates and returns a fresh, empty namespace for
	// one test.
	CreateNamespace func(ctx context.Context) (string, error)
	// DropNamespace removes the namespace and everything in it after
	// the test.
	DropNamespace func(ctx context.Context, namespace string) error
	// ScratchDataSource is a second data source on the same engine, for
	// the ScratchDataSource builder option (C-26).
	ScratchDataSource func() spi.DB
	// AssertNoScratchNamespacesLeft asserts that no scratch namespace
	// survives on the target (C-15, C-22).
	AssertNoScratchNamespacesLeft func(ctx context.Context) error
	// AssertCleanSession asserts that a pooled connection carries no
	// lock/session state (C-21).
	AssertCleanSession func(ctx context.Context) error
	// IsScratchBoundary marks the statement that opens scratch work on
	// the same connection.
	IsScratchBoundary func(sql string) bool
	// IsScratchEnd marks the statement that closes scratch work.
	IsScratchEnd func(sql string) bool
	// UnsupportedScript is a script creating an UNSUPPORTED object
	// plus a supported dependent.
	UnsupportedScript func() *UnsupportedScript
	// UnsupportedCategory is the policy category of the object
	// UnsupportedScript creates.
	UnsupportedCategory func() string
	// ColumnAlterSupported is whether the dialect renders safe
	// in-place column alterations (C-33).
	ColumnAlterSupported func() bool
	// IdentityColumnDdl is DDL for an identity-style column (C-32).
	IdentityColumnDdl func() string
	// SerialColumnDdl is DDL for a serial-style column (C-32).
	SerialColumnDdl func() string
	// ExtensionDdl is a CREATE EXTENSION the dialect can install in
	// the namespace (C-45); ok=false skips.
	ExtensionDdl func(ctx context.Context) (ddl string, ok bool, err error)
	// ForeignServerDdl creates foreign server tck_srv (C-55).
	ForeignServerDdl func(ctx context.Context) (ddl string, ok bool, err error)
	// ForeignServerTeardownDdl cleans up ForeignServerDdl.
	ForeignServerTeardownDdl func(ctx context.Context) (ddl string, ok bool, err error)
	// PublicationDdl creates empty publication tck_pub (C-56).
	PublicationDdl func(ctx context.Context) (ddl string, ok bool, err error)
	// PublicationTeardownDdl cleans up PublicationDdl.
	PublicationTeardownDdl func(ctx context.Context) (ddl string, ok bool, err error)
	// OwnershipRoleDdl creates roles tck_owner/tck_owner2
	// (C-57, C-58, C-63, C-64).
	OwnershipRoleDdl func(ctx context.Context) (ddl string, ok bool, err error)
	// OwnershipRoleTeardownDdl cleans up OwnershipRoleDdl.
	OwnershipRoleTeardownDdl func(ctx context.Context) (ddl string, ok bool, err error)
}

// RunConformanceSuite registers the C-01…C-67 scenarios as subtests.
func RunConformanceSuite(t *testing.T, hooks Hooks) {
	scenarios := []struct {
		name string
		run  func(s *suite)
	}{
		{"C-01 empty target reconciles; second run is UP_TO_DATE", c01},
		{"C-02 matching target: UP_TO_DATE, no statements on the target", c02},
		{"C-03 dropped index is restored as ADDITIVE", c03},
		{"C-04 drifted index is rebuilt", c04},
		{"C-05 drifted expression index is rebuilt", c05},
		{"C-06 dropped unique constraint is restored", c06},
		{"C-07 drifted constraint is rebuilt", c07},
		{"C-08 dropped foreign key is restored", c08},
		{"C-09 drifted view recreated with its dependents", c09},
		{"C-10 changed routine body rebuilds; dependents survive", c10},
		{"C-11 dropped trigger is restored", c11},
		{"C-12 foreign table shape is DESTRUCTIVE; default policy fails", c12},
		{"C-13 unexpected object reported, untouched", c13},
		{"C-14 unsupported object kind: UNSUPPORTED diff, default policy fails", c14},
		{"C-15 syntax error: DesiredStateError, nothing left behind", c15},
		{"C-16 concurrent reconcile calls serialise", c16},
		{"C-17 apply on a changed target throws StalePlanError", c17},
		{"C-18 reportOnly applies nothing", c18},
		{"C-19 identical input produces identical plans", c19},
		{"C-20 lock timeout", c20},
		{"C-21 pooled connection is clean after a run", c21},
		{"C-22 plan changes nothing", c22},
		{"C-23 self and mutual foreign keys reconcile", c23},
		{"C-24 apply rejects a foreign plan", c24},
		{"C-25 unexpected dependent blocks planning", c25},
		{"C-26 scratchDataSource confines scratch work", c26},
		{"C-27 demoted change names its blocking object", c27},
		{"C-28 literal target namespace in the script is rejected", c28},
		{"C-29 unsupported REPORT demotes dependents", c29},
		{"C-30 drop/create order: DROP w, DROP v, CREATE v, CREATE w", c30},
		{"C-31 name-scope collision fails planning", c31},
		{"C-32 identity and serial columns reconcile without cycles", c32},
		{"C-33 column default and DROP NOT NULL alter; SET NOT NULL fails closed", c33},
		{"C-34 unsafe column type change stays destructive", c34},
		{"C-35 unsupported category override reports while the rest fails", c35},
		{"C-36 the report carries phase timings", c36},
		{"C-37 materialized view missing: ADDITIVE create, populated", c37},
		{"C-38 materialized view drifted: DESTRUCTIVE drop+create+refresh", c38},
		{"C-39 enum missing: ADDITIVE create; an appended label is ALTER TYPE ADD VALUE", c39},
		{"C-40 enum removed or reordered labels stay a manual change", c40},
		{"C-41 domain missing: ADDITIVE create, constraints and nullability kept", c41},
		{"C-42 domain drift on safe fields: REBUILD ALTER DOMAIN converges", c42},
		{"C-43 domain base-type change stays a manual change", c43},
		{"C-44 policy missing: ADDITIVE; a changed USING is a REBUILD drop+create", c44},
		{"C-45 extension missing: ADDITIVE create anchored in the namespace", c45},
		{"C-46 comment missing/drifted: ALTER REBUILD", c46},
		{"C-47 unexpected comment is reported, never dropped", c47},
		{"C-48 interlocked new kinds reconcile in dependency order", c48},
		{"C-49 exclusion constraint missing: ADDITIVE; drifted: REBUILD drop+create", c49},
		{"C-50 statistics missing: ADDITIVE; drifted: REBUILD drop+create", c50},
		{"C-51 rule missing: ADDITIVE; drifted: REBUILD drop+create", c51},
		{"C-52 collation missing: ADDITIVE; drifted: REBUILD drop+create", c52},
		{"C-53 conversion missing: ADDITIVE; drifted: REBUILD drop+create", c53},
		{"C-54 partitioned parent and children create ADDITIVE; bound drift: DESTRUCTIVE recreate", c54},
		{"C-55 foreign table create: ADDITIVE; options drift: drop+create REBUILD", c55},
		{"C-56 publication binding add: ADDITIVE; filter drift: drop+add REBUILD", c56},
		{"C-57 ownership assign: REBUILD; owner drift: in-place REBUILD", c57},
		{"C-58 privileges granted: REBUILD; grant drift: GRANT/REVOKE REBUILD", c58},
		{"C-59 cast missing: ADDITIVE; cast context drift: drop+create REBUILD", c59},
		{"C-60 composite type missing: ADDITIVE; attribute drift: drop+create REBUILD", c60},
		{"C-61 range type missing: ADDITIVE; pg_range drift: drop+create REBUILD", c61},
		{"C-62 aggregate missing: ADDITIVE; option drift: drop+create REBUILD", c62},
		{"C-63 column privilege granted: REBUILD; column grant drift: GRANT/REVOKE REBUILD", c63},
		{"C-64 default privileges granted: REBUILD; drift: GRANT/REVOKE REBUILD", c64},
		{"C-65 window function missing: ADDITIVE; body drift: in-place CREATE OR REPLACE REBUILD", c65},
		{"C-66 text-search dictionary/config with mapping: ADDITIVE; option drift: DROP+CREATE REBUILD", c66},
		{"C-67 operator, family and class missing: ADDITIVE; class option drift: DROP+CREATE REBUILD", c67},
	}
	for _, s := range scenarios {
		s := s
		t.Run(s.name, func(t *testing.T) { s.run(newSuite(t, hooks)) })
	}
}

// suite carries the per-test state (the beforeEach namespace) and the
// helpers the scenarios share.
type suite struct {
	t *testing.T
	h Hooks

	ctx       context.Context
	namespace string
}

func newSuite(t *testing.T, h Hooks) *suite {
	s := &suite{t: t, h: h, ctx: context.Background()}
	ns, err := h.CreateNamespace(s.ctx)
	if err != nil {
		t.Fatalf("createNamespace: %v", err)
	}
	s.namespace = ns
	t.Cleanup(func() {
		// afterEach: the scratch-namespace assertion runs first, then
		// the namespace drops either way
		if h.AssertNoScratchNamespacesLeft != nil {
			if err := h.AssertNoScratchNamespacesLeft(context.Background()); err != nil {
				t.Errorf("assertNoScratchNamespacesLeft: %v", err)
			}
		}
		if err := h.DropNamespace(context.Background(), ns); err != nil {
			t.Errorf("dropNamespace: %v", err)
		}
	})
	return s
}

// ---- helpers ---------------------------------------------------------

func (s *suite) dialect() spi.Dialect { return s.h.Dialect() }
func (s *suite) dataSource() spi.DB   { return s.h.DataSource() }

func (s *suite) scratch() spi.DB {
	if s.h.ScratchDataSource != nil {
		return s.h.ScratchDataSource()
	}
	return s.dataSource()
}

func (s *suite) noScratchLeft() {
	if s.h.AssertNoScratchNamespacesLeft != nil {
		if err := s.h.AssertNoScratchNamespacesLeft(s.ctx); err != nil {
			s.t.Error(err)
		}
	}
}

func (s *suite) cleanSession() {
	if s.h.AssertCleanSession != nil {
		if err := s.h.AssertCleanSession(s.ctx); err != nil {
			s.t.Error(err)
		}
	}
}

func (s *suite) scratchBoundary(sql string) bool {
	return s.h.IsScratchBoundary != nil && s.h.IsScratchBoundary(sql)
}

func (s *suite) scratchEnd(sql string) bool {
	return s.h.IsScratchEnd != nil && s.h.IsScratchEnd(sql)
}

func (s *suite) q(id string) string {
	q, err := s.dialect().QuoteIdentifier(id)
	if err != nil {
		s.t.Fatalf("quoteIdentifier(%q): %v", id, err)
	}
	return q
}

func (s *suite) qualified(name string) string {
	return s.q(s.namespace) + "." + s.q(name)
}

func (s *suite) reconciler(script string, policy core.ReconcilePolicy) *core.SchemaReconciler {
	return s.reconcilerOn(s.dataSource(), script, policy)
}

func (s *suite) reconcilerOn(ds spi.DB, script string,
	policy core.ReconcilePolicy) *core.SchemaReconciler {
	r, err := core.NewBuilder().DataSource(ds).Namespace(s.namespace).
		DesiredState(core.DesiredStateOfScript(script)).
		Dialect(s.dialect()).Policy(policy).Build()
	if err != nil {
		s.t.Fatalf("build: %v", err)
	}
	return r
}

func (s *suite) reconcilerBuilder() *core.Builder {
	return core.NewBuilder().DataSource(s.dataSource()).
		Namespace(s.namespace).Dialect(s.dialect())
}

func (s *suite) execute(sql string) {
	c, err := s.dataSource().Conn(s.ctx)
	if err != nil {
		s.t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.ExecContext(s.ctx, sql); err != nil {
		s.t.Fatalf("execute %q: %v", sql, err)
	}
}

func (s *suite) snapshot() model.SchemaSnapshot {
	c, err := s.dataSource().Conn(s.ctx)
	if err != nil {
		s.t.Fatal(err)
	}
	defer c.Close()
	if err := c.SetAutoCommit(s.ctx, false); err != nil {
		s.t.Fatal(err)
	}
	snap, err := s.dialect().ReadSnapshot(s.ctx, c, s.namespace)
	if err != nil {
		s.t.Fatal(err)
	}
	if err := c.Rollback(s.ctx); err != nil {
		s.t.Fatal(err)
	}
	if err := c.SetAutoCommit(s.ctx, true); err != nil {
		s.t.Fatal(err)
	}
	return snap
}

func (s *suite) present(id model.ObjectId) bool {
	_, ok := s.snapshot().Objects[id]
	return ok
}

func (s *suite) count(relation string) int64 {
	c, err := s.dataSource().Conn(s.ctx)
	if err != nil {
		s.t.Fatal(err)
	}
	defer c.Close()
	var n int64
	if err := c.QueryRowContext(s.ctx,
		"SELECT count(*) FROM "+s.qualified(relation)).Scan(&n); err != nil {
		s.t.Fatal(err)
	}
	return n
}

func (s *suite) assumeKind(kind model.ObjectKind) bool {
	if _, ok := s.dialect().Capabilities().SupportedKinds[kind]; !ok {
		s.t.Skipf("kind %s not in supportedKinds", kind)
		return false
	}
	return true
}

// targetDdl — DDL statements recorded against the target namespace
// (scratch phases excluded).
func (s *suite) targetDdl(ds *TrackingDB) []string {
	var out []string
	inScratch := false
	for _, executed := range ds.Statements() {
		sqlText := executed.SQL
		if s.scratchBoundary(sqlText) {
			inScratch = true
			continue
		}
		if s.scratchEnd(sqlText) {
			inScratch = false
			continue
		}
		if inScratch {
			continue
		}
		upper := strings.ToUpper(strings.TrimSpace(sqlText))
		for _, p := range []string{"CREATE ", "DROP ", "ALTER ", "INSERT ", "UPDATE ", "DELETE "} {
			if strings.HasPrefix(upper, p) {
				out = append(out, sqlText)
				break
			}
		}
	}
	return out
}

// mustReconcile — reconcile, fatal on error.
func (s *suite) mustReconcile(r *core.SchemaReconciler) core.ReconcileReport {
	s.t.Helper()
	report, err := r.Reconcile(s.ctx)
	if err != nil {
		s.t.Fatalf("reconcile: %v", err)
	}
	return report
}

func (s *suite) reconcile(script string) core.ReconcileReport {
	return s.mustReconcile(s.reconciler(script, core.DefaultPolicy()))
}

func (s *suite) reconcilePolicy(script string,
	policy core.ReconcilePolicy) core.ReconcileReport {
	return s.mustReconcile(s.reconciler(script, policy))
}

// rejects — run must fail with an error matching sentinel; returns the
// error for message checks.
func (s *suite) rejects(sentinel error, run func() error) error {
	s.t.Helper()
	err := run()
	if err == nil {
		s.t.Fatalf("expected %v, got nil", sentinel)
	}
	if !errors.Is(err, sentinel) {
		s.t.Fatalf("expected %v, got %v", sentinel, err)
	}
	return err
}

// anyChange asserts some planned change matches pred.
func (s *suite) anyChange(changes []model.PlannedChange,
	pred func(model.PlannedChange) bool, label string) {
	s.t.Helper()
	for _, c := range changes {
		if pred(c) {
			return
		}
	}
	var b strings.Builder
	for _, c := range changes {
		b.WriteString(" " + c.Target.String() + " " + c.Action.String() + "/" + c.Safety.String())
	}
	s.t.Fatalf("%s: changes:%s", label, b.String())
}

// ---- object-id constructors ------------------------------------------

func tableID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindTable, Name: name}
}
func viewID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindView, Name: name}
}
func indexID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindIndex, Name: name, Parent: parent}
}
func columnID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindColumn, Name: name, Parent: parent}
}
func matviewID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindMaterializedView, Name: name}
}
func typeID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindType, Name: name}
}
func domainID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindDomain, Name: name}
}
func policyID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindPolicy, Name: name, Parent: parent}
}
func exclusionID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindExclusion, Name: name, Parent: parent}
}
func statisticsID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindStatistics, Name: name}
}
func ruleID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindRule, Name: name, Parent: parent}
}
func collationID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindCollation, Name: name}
}
func conversionID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindConversion, Name: name}
}
func partitionID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindPartition, Name: name, Parent: parent}
}
func foreignTableID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindForeignTable, Name: name}
}
func publicationID(pub, rel string) model.ObjectId {
	return model.ObjectId{Kind: model.KindPublication, Name: pub, Parent: rel}
}
func ownershipID(target string) model.ObjectId {
	return model.ObjectId{Kind: model.KindOwnership, Name: target}
}
func privilegesID(target string) model.ObjectId {
	return model.ObjectId{Kind: model.KindPrivileges, Name: target}
}
func castID(name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindCast, Name: name}
}
func routineID(name, args string) model.ObjectId {
	return model.ObjectId{Kind: model.KindRoutine, Name: name + "(" + args + ")"}
}
func triggerID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindTrigger, Name: name, Parent: parent}
}
func uniqueID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindUnique, Name: name, Parent: parent}
}
func foreignKeyID(parent, name string) model.ObjectId {
	return model.ObjectId{Kind: model.KindForeignKey, Name: name, Parent: parent}
}

// optionalHook runs a nullable DDL hook: nil hook or ok=false skips;
// empty ddl is a no-op.
func (s *suite) optionalHook(name string,
	hook func(ctx context.Context) (string, bool, error)) (string, bool) {
	s.t.Helper()
	if hook == nil {
		s.t.Skipf("dialect provides no %s hook", name)
		return "", false
	}
	ddl, ok, err := hook(s.ctx)
	if err != nil {
		s.t.Fatalf("%s: %v", name, err)
	}
	if !ok {
		s.t.Skipf("no %s on this server", name)
		return "", false
	}
	return ddl, true
}

func (s *suite) teardown(name string,
	hook func(ctx context.Context) (string, bool, error)) {
	if hook == nil {
		return
	}
	ddl, ok, err := hook(s.ctx)
	if err == nil && ok && ddl != "" {
		s.execute(ddl)
	}
}

// ---- C-01 … C-33 -------------------------------------------------------

func c01(s *suite) {
	t := s.t
	script := `CREATE TABLE users (id bigint NOT NULL, name text NOT NULL);
CREATE TABLE plans (id bigint NOT NULL, owner bigint NOT NULL)`
	first := s.reconcile(script)
	if first.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", first.Outcome)
	}
	if !s.present(tableID("users")) {
		t.Error("users missing")
	}
	if !s.present(columnID("users", "id")) {
		t.Error("users.id missing")
	}
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("second outcome = %v", got)
	}
}

func c02(s *suite) {
	t := s.t
	script := "CREATE TABLE users (id bigint NOT NULL, name text)"
	s.reconcile(script)
	tracking := &TrackingDB{Delegate: s.dataSource()}
	report := s.mustReconcile(s.reconcilerOn(tracking, script, core.DefaultPolicy()))
	if report.Outcome != core.OutcomeUpToDate {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if len(report.Plan.Changes) != 0 {
		t.Errorf("changes = %v", report.Plan.Changes)
	}
	if ddl := s.targetDdl(tracking); len(ddl) != 0 {
		t.Errorf("no DDL should run on the target: %v", ddl)
	}
}

func c03(s *suite) {
	if !s.assumeKind(model.KindIndex) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_name_idx ON users (name)`
	s.reconcile(script)
	s.execute("DROP INDEX " + s.qualified("users_name_idx"))
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if !s.present(indexID("users", "users_name_idx")) {
		t.Error("index missing")
	}
}

func c04(s *suite) {
	if !s.assumeKind(model.KindIndex) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_name_idx ON users (name)`
	s.reconcile(script)
	s.execute("DROP INDEX " + s.qualified("users_name_idx"))
	s.execute("CREATE INDEX users_name_idx ON " + s.qualified("users") + " (id)")
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	found := false
	for _, c := range report.Plan.Changes {
		if c.Action == model.ChangeDrop && c.Target.Kind == model.KindIndex {
			found = true
		}
	}
	if !found {
		t.Error("expected a DROP on the drifted index")
	}
}

func c05(s *suite) {
	if !s.assumeKind(model.KindIndex) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_expr_idx ON users ((lower(name)))`
	s.reconcile(script)
	s.execute("DROP INDEX " + s.qualified("users_expr_idx"))
	s.execute("CREATE INDEX users_expr_idx ON " + s.qualified("users") +
		" ((upper(name)))")
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if !s.present(indexID("users", "users_expr_idx")) {
		t.Error("index missing")
	}
}

func c06(s *suite) {
	if !s.assumeKind(model.KindUnique) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text,
    CONSTRAINT users_name_uk UNIQUE (name))`
	s.reconcile(script)
	s.execute("ALTER TABLE " + s.qualified("users") +
		" DROP CONSTRAINT users_name_uk")
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if !s.present(uniqueID("users", "users_name_uk")) {
		t.Error("constraint missing")
	}
}

func c07(s *suite) {
	if !s.assumeKind(model.KindUnique) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text,
    CONSTRAINT users_uk UNIQUE (name))`
	s.reconcile(script)
	s.execute("ALTER TABLE " + s.qualified("users") +
		" DROP CONSTRAINT users_uk")
	s.execute("ALTER TABLE " + s.qualified("users") +
		" ADD CONSTRAINT users_uk UNIQUE (id)")
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	found := false
	for _, c := range report.Plan.Changes {
		if c.Action == model.ChangeDrop && c.Target.Kind == model.KindUnique {
			found = true
		}
	}
	if !found {
		t.Error("expected a DROP on the drifted constraint")
	}
}

func c08(s *suite) {
	if !s.assumeKind(model.KindForeignKey) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint PRIMARY KEY);
CREATE TABLE plans (id bigint PRIMARY KEY, owner bigint,
    CONSTRAINT plans_owner_fk FOREIGN KEY (owner) REFERENCES users (id))`
	s.reconcile(script)
	s.execute("ALTER TABLE " + s.qualified("plans") +
		" DROP CONSTRAINT plans_owner_fk")
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if !s.present(foreignKeyID("plans", "plans_owner_fk")) {
		t.Error("fk missing")
	}
}

func c09(s *suite) {
	if !s.assumeKind(model.KindView) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users;
CREATE VIEW v_outer AS SELECT id FROM v_users`
	s.reconcile(script)
	// CREATE OR REPLACE VIEW cannot drop columns — drift by adding one
	s.execute("CREATE OR REPLACE VIEW " + s.qualified("v_users") +
		" AS SELECT id, name, 1 AS extra FROM " + s.qualified("users"))
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	var dropV, dropW bool
	for _, c := range report.Plan.Changes {
		if c.Action == model.ChangeDrop {
			if c.Target == viewID("v_users") {
				dropV = true
			}
			if c.Target == viewID("v_outer") {
				dropW = true
			}
		}
	}
	if !dropV || !dropW {
		t.Errorf("expected drops of v_users and v_outer")
	}
}

func c10(s *suite) {
	if !s.assumeKind(model.KindRoutine) || !s.assumeKind(model.KindTrigger) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE FUNCTION bump() RETURNS trigger LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END $$;
CREATE TRIGGER users_bump BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION bump()`
	s.reconcile(script)
	s.execute("CREATE OR REPLACE FUNCTION " + s.qualified("bump") +
		"() RETURNS trigger LANGUAGE plpgsql " +
		"AS $$ BEGIN NEW.name := coalesce(NEW.name, '');" +
		" RETURN NEW; END $$")
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if !s.present(routineID("bump", "")) {
		t.Error("bump() missing")
	}
	if !s.present(triggerID("users", "users_bump")) {
		t.Error("trigger missing")
	}
}

func c11(s *suite) {
	if !s.assumeKind(model.KindTrigger) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE FUNCTION bump() RETURNS trigger LANGUAGE plpgsql
    AS $$ BEGIN RETURN NEW; END $$;
CREATE TRIGGER users_bump BEFORE INSERT ON users
    FOR EACH ROW EXECUTE FUNCTION bump()`
	s.reconcile(script)
	s.execute("DROP TRIGGER users_bump ON " + s.qualified("users"))
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if !s.present(triggerID("users", "users_bump")) {
		t.Error("trigger missing")
	}
}

func c12(s *suite) {
	t := s.t
	script := "CREATE TABLE users (id bigint NOT NULL)"
	s.reconcile(script)
	s.execute("ALTER TABLE " + s.qualified("users") +
		" ADD COLUMN extra text")
	s.execute("INSERT INTO " + s.qualified("users") +
		" (id, extra) VALUES (1, 'x')")
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(script, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	if got := s.count("users"); got != 1 {
		t.Errorf("the row must be untouched, count = %d", got)
	}
	if !s.present(columnID("users", "extra")) {
		t.Error("extra column must survive")
	}
}

func c13(s *suite) {
	t := s.t
	script := "CREATE TABLE users (id bigint)"
	s.reconcile(script)
	s.execute("CREATE TABLE " + s.qualified("stray") + " (id bigint)")
	s.execute("INSERT INTO " + s.qualified("stray") + " (id) VALUES (7)")
	report := s.reconcile(script)
	found := false
	for _, d := range report.Unexpected {
		if d.ID == tableID("stray") {
			found = true
		}
	}
	if !found {
		t.Error("expected stray table in unexpected")
	}
	if got := s.count("stray"); got != 1 {
		t.Errorf("stray rows = %d", got)
	}
}

func c14(s *suite) {
	t := s.t
	var unsupported *UnsupportedScript
	if s.h.UnsupportedScript != nil {
		unsupported = s.h.UnsupportedScript()
	}
	if unsupported == nil {
		t.Skip("dialect declares every kind it can detect")
		return
	}
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(unsupported.Script, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	policy, err := core.DefaultPolicy().WithUnsupported(core.DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	report := s.reconcilePolicy(unsupported.Script, policy)
	found := false
	for _, d := range report.Unsupported {
		if d.Type == model.DifferenceUnsupported {
			found = true
		}
	}
	if !found {
		t.Error("expected UNSUPPORTED differences")
	}
}

func c15(s *suite) {
	s.rejects(core.ErrDesiredState, func() error {
		_, err := s.reconciler("THIS IS NOT VALID SQL;",
			core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	s.noScratchLeft()
}

func c16(s *suite) {
	t := s.t
	script := "CREATE TABLE users (id bigint)"
	var wg sync.WaitGroup
	reports := make([]core.ReconcileReport, 2)
	errs := make([]error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			reports[i], errs[i] = s.reconciler(script,
				core.DefaultPolicy()).Reconcile(s.ctx)
		}(i)
	}
	wg.Wait()
	for _, err := range errs {
		if err != nil {
			t.Fatalf("reconcile: %v", err)
		}
	}
	if reports[0].Outcome != core.OutcomeUpToDate &&
		reports[1].Outcome != core.OutcomeUpToDate {
		t.Error("one run must observe the other's work")
	}
}

func c17(s *suite) {
	script := "CREATE TABLE users (id bigint)"
	r := s.reconciler(script, core.DefaultPolicy())
	plan, err := r.Plan(s.ctx)
	if err != nil {
		s.t.Fatal(err)
	}
	s.execute("CREATE TABLE " + s.qualified("stray") + " (id bigint)")
	s.rejects(core.ErrStalePlan, func() error {
		_, err := r.Apply(s.ctx, plan)
		return err
	})
}

func c18(s *suite) {
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE INDEX users_name_idx ON users (name)`
	s.reconcile("CREATE TABLE users (id bigint, name text)")
	tracking := &TrackingDB{Delegate: s.dataSource()}
	report := s.mustReconcile(s.reconcilerOn(tracking, script,
		core.ReportOnlyPolicy()))
	if report.Outcome != core.OutcomeReportedOnly {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if len(report.Applied) != 0 {
		t.Errorf("applied = %v", report.Applied)
	}
	if len(report.Reported) == 0 {
		t.Error("reported is empty")
	}
	if ddl := s.targetDdl(tracking); len(ddl) != 0 {
		t.Errorf("no DDL should run on the target: %v", ddl)
	}
}

func c19(s *suite) {
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users;
CREATE INDEX users_name_idx ON users (name)`
	one, err := s.reconciler(script, core.DefaultPolicy()).Plan(s.ctx)
	if err != nil {
		t.Fatal(err)
	}
	two, err := s.reconciler(script, core.DefaultPolicy()).Plan(s.ctx)
	if err != nil {
		t.Fatal(err)
	}
	if one.ToSQL() != two.ToSQL() {
		t.Errorf("plans differ:\n%s\n---\n%s", one.ToSQL(), two.ToSQL())
	}
}

func c20(s *suite) {
	c, err := s.dataSource().Conn(s.ctx)
	if err != nil {
		s.t.Fatal(err)
	}
	defer c.Close()
	if err := c.SetAutoCommit(s.ctx, false); err != nil {
		s.t.Fatal(err)
	}
	if err := s.dialect().AcquireLock(s.ctx, c, s.namespace,
		10*time.Second); err != nil {
		s.t.Fatal(err)
	}
	r, err := s.reconcilerBuilder().DesiredState(core.DesiredStateOfScript(
		"CREATE TABLE t (id bigint)")).
		LockTimeout(300 * time.Millisecond).Build()
	if err != nil {
		s.t.Fatal(err)
	}
	s.rejects(core.ErrLockTimeout, func() error {
		_, err := r.Reconcile(s.ctx)
		return err
	})
	if err := c.Rollback(s.ctx); err != nil {
		s.t.Fatal(err)
	}
}

func c21(s *suite) {
	s.reconcile("CREATE TABLE t (id bigint)")
	s.cleanSession()
}

func c22(s *suite) {
	t := s.t
	script := "CREATE TABLE users (id bigint, name text)"
	s.reconcile("CREATE TABLE users (id bigint)")
	before := s.snapshot()
	plan, err := s.reconciler(script, core.DefaultPolicy()).Plan(s.ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Changes) == 0 {
		t.Fatal("expected changes")
	}
	if after := s.snapshot(); after.Fingerprint != before.Fingerprint {
		t.Error("plan() must not modify the target")
	}
	s.noScratchLeft()
	s.cleanSession()
}

func c23(s *suite) {
	if !s.assumeKind(model.KindForeignKey) {
		return
	}
	t := s.t
	script := `CREATE TABLE selfref (id bigint PRIMARY KEY,
    parent bigint REFERENCES selfref (id));
CREATE TABLE a_mut (id bigint PRIMARY KEY, b_id bigint);
CREATE TABLE b_mut (id bigint PRIMARY KEY, a_id bigint);
ALTER TABLE a_mut ADD CONSTRAINT a_b_fk FOREIGN KEY (b_id) REFERENCES b_mut (id);
ALTER TABLE b_mut ADD CONSTRAINT b_a_fk FOREIGN KEY (a_id) REFERENCES a_mut (id)`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
}

func c24(s *suite) {
	a := s.reconciler("CREATE TABLE t (id bigint)", core.DefaultPolicy())
	plan, err := a.Plan(s.ctx)
	if err != nil {
		s.t.Fatal(err)
	}
	b, err := s.reconcilerBuilder().Namespace(s.namespace + "_other").
		DesiredState(core.DesiredStateOfScript("CREATE TABLE t (id bigint)")).Build()
	if err != nil {
		s.t.Fatal(err)
	}
	s.rejects(model.ErrInvalidArgument, func() error {
		_, err := b.Apply(s.ctx, plan)
		return err
	})
	wrongDialect := &model.ReconcilePlan{
		TargetNamespace:    plan.TargetNamespace,
		DialectID:          "other-dialect",
		ActualFingerprint:  plan.ActualFingerprint,
		DesiredFingerprint: plan.DesiredFingerprint,
		Differences:        plan.Differences,
		Changes:            plan.Changes,
	}
	s.rejects(model.ErrInvalidArgument, func() error {
		_, err := a.Apply(s.ctx, wrongDialect)
		return err
	})
}

func c25(s *suite) {
	if !s.assumeKind(model.KindView) {
		return
	}
	t := s.t
	script := `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users`
	s.reconcile(`CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users`)
	// an unexpected view inside the namespace depending on v_users
	s.execute("CREATE VIEW " + s.qualified("stray_view") +
		" AS SELECT id FROM " + s.qualified("v_users"))
	e := s.rejects(core.ErrPlanning, func() error {
		_, err := s.reconciler(script, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	if !strings.Contains(e.Error(), "stray_view") {
		t.Errorf("error = %v", e)
	}
	if !s.present(viewID("stray_view")) {
		t.Error("the unexpected view must survive")
	}
}

func c26(s *suite) {
	t := s.t
	r, err := s.reconcilerBuilder().ScratchDataSource(s.scratch()).
		DesiredState(core.DesiredStateOfScript("CREATE TABLE users (id bigint)")).
		Build()
	if err != nil {
		t.Fatal(err)
	}
	report := s.mustReconcile(r)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.noScratchLeft()
}

func c27(s *suite) {
	if !s.assumeKind(model.KindView) {
		return
	}
	t := s.t
	s.reconcile(`CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users`)
	script := `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users;
CREATE VIEW w_users AS SELECT id FROM v_users`
	p := core.DefaultPolicy().WithAdditive(core.DecisionApply).
		WithRebuild(core.DecisionReport)
	report := s.reconcilePolicy(script, p)
	if report.Outcome != core.OutcomeReportedOnly {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	found := false
	for _, c := range report.Reported {
		if c.Target == viewID("w_users") && strings.Contains(c.Reason, "v_users") {
			found = true
		}
	}
	if !found {
		t.Errorf("w_users must be demoted naming v_users as blocker: %v",
			report.Reported)
	}
	if s.present(viewID("w_users")) {
		t.Error("w_users must not be created")
	}
}

func c28(s *suite) {
	t := s.t
	script := "CREATE TABLE " + s.qualified("users") + " (id bigint)"
	s.rejects(core.ErrDesiredState, func() error {
		_, err := s.reconciler(script, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	if s.present(tableID("users")) {
		t.Error("users must not exist")
	}
}

func c29(s *suite) {
	t := s.t
	var unsupported *UnsupportedScript
	if s.h.UnsupportedScript != nil {
		unsupported = s.h.UnsupportedScript()
	}
	if unsupported == nil {
		t.Skip("dialect declares every kind it can detect")
		return
	}
	script := unsupported.Script + "; CREATE TABLE independent_t (id bigint)"
	policy, err := core.DefaultPolicy().WithUnsupported(core.DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	report := s.reconcilePolicy(script, policy)
	hasUnsupported := false
	for _, d := range report.Unsupported {
		if d.Type == model.DifferenceUnsupported {
			hasUnsupported = true
		}
	}
	if !hasUnsupported {
		t.Error("expected UNSUPPORTED differences")
	}
	demoted := false
	for _, c := range report.Reported {
		if c.Target.Name == unsupported.DependentName ||
			strings.Contains(c.Reason, unsupported.DependentName) {
			demoted = true
		}
	}
	if !demoted {
		t.Error("the supported dependent must be demoted to REPORT")
	}
	if !s.present(tableID("independent_t")) {
		t.Error("independent changes are still applied")
	}
}

func c30(s *suite) {
	if !s.assumeKind(model.KindView) {
		return
	}
	t := s.t
	s.reconcile(`CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id FROM users;
CREATE VIEW w_users AS SELECT id FROM v_users`)
	script := `CREATE TABLE users (id bigint, name text);
CREATE VIEW v_users AS SELECT id, name FROM users;
CREATE VIEW w_users AS SELECT id FROM v_users`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	var got []string
	for _, c := range report.Plan.Changes {
		if c.Target.Kind == model.KindView {
			got = append(got, c.Target.Name+" "+c.Action.String())
		}
	}
	want := []string{"w_users DROP", "v_users DROP",
		"v_users CREATE", "w_users CREATE"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Errorf("order = %v, want %v", got, want)
	}
}

func c31(s *suite) {
	if !s.assumeKind(model.KindIndex) {
		return
	}
	t := s.t
	idx := indexID("a_tbl", "shared_idx")
	var hasRelationScope bool
	if ns, ok := s.dialect().(spi.NameScoper); ok {
		for _, sc := range ns.NameScopes(idx) {
			if strings.HasPrefix(sc, "relation:") {
				hasRelationScope = true
			}
		}
	}
	if !hasRelationScope {
		t.Skip("dialect does not declare relation-scoped index names")
		return
	}
	s.reconcile(`CREATE TABLE b_tbl (id bigint, name text);
CREATE INDEX shared_idx ON b_tbl (name)`)
	// the desired index shares the name of the unexpected index on b_tbl
	collision := `CREATE TABLE a_tbl (id bigint, name text);
CREATE INDEX shared_idx ON a_tbl (id)`
	e := s.rejects(core.ErrPlanning, func() error {
		_, err := s.reconciler(collision, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	if !strings.Contains(e.Error(), "shared_idx") {
		t.Errorf("error = %v", e)
	}
	if !s.present(indexID("b_tbl", "shared_idx")) {
		t.Error("the unexpected index must survive")
	}
}

func c32(s *suite) {
	t := s.t
	var identity, serial string
	if s.h.IdentityColumnDdl != nil {
		identity = s.h.IdentityColumnDdl()
	}
	if s.h.SerialColumnDdl != nil {
		serial = s.h.SerialColumnDdl()
	}
	if identity == "" && serial == "" {
		t.Skip("dialect has no identity/serial column form")
		return
	}
	var cols []string
	if identity != "" {
		cols = append(cols, "ident "+identity)
	}
	if serial != "" {
		cols = append(cols, "ser "+serial)
	}
	script := "CREATE TABLE ids (" + strings.Join(cols, ", ") + ")"
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("second = %v", got)
	}
}

func c33(s *suite) {
	if !s.assumeKind(model.KindColumn) {
		return
	}
	t := s.t
	if s.h.ColumnAlterSupported == nil || !s.h.ColumnAlterSupported() {
		t.Skip("dialect renders column drift as manual changes")
		return
	}
	s.reconcile("CREATE TABLE t (id bigint," +
		" note text DEFAULT 'a', req text NOT NULL)")
	s.execute("INSERT INTO " + s.qualified("t") +
		" VALUES (1, NULL, 'x'), (2, 'y', 'z')")
	// both safe directions apply in place
	report := s.reconcile("CREATE TABLE t (id bigint," +
		" note text DEFAULT 'b', req text)")
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	for _, col := range []model.ObjectId{columnID("t", "note"), columnID("t", "req")} {
		found := false
		for _, c := range report.Applied {
			if c.Target == col && c.Action == model.ChangeAlter {
				found = true
			}
		}
		if !found {
			t.Errorf("expected an applied ALTER for %s: %v", col, report.Applied)
		}
	}
	if got := s.reconcile("CREATE TABLE t (id bigint," +
		" note text DEFAULT 'b', req text)").Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// SET NOT NULL fails on the NULL row — DESTRUCTIVE stays manual
	// under the default policy
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler("CREATE TABLE t (id bigint,"+
			" note text NOT NULL, req text)", core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
}
