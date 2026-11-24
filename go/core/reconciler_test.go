package reconciler_test

import (
	"context"
	"errors"
	"testing"
	"time"

	reconciler "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

func TestErrorHierarchy(t *testing.T) {
	planning := reconciler.NewPlanningErrorf("cycle at %s", "TABLE t")

	// errors.Is with the subtype sentinel and the base sentinel.
	if !errors.Is(planning, reconciler.ErrPlanning) {
		t.Fatal("ErrPlanning must match *PlanningError")
	}
	if !errors.Is(planning, reconciler.ErrSchemaReconciler) {
		t.Fatal("ErrSchemaReconciler must match *PlanningError")
	}
	if errors.Is(planning, reconciler.ErrApply) {
		t.Fatal("ErrApply must not match *PlanningError")
	}

	// errors.As extracts the concrete subtype.
	var perr *reconciler.PlanningError
	if !errors.As(planning, &perr) {
		t.Fatal("As must extract *PlanningError")
	}
	var cerr *reconciler.ConfigurationError
	if errors.As(planning, &cerr) {
		t.Fatal("As must not extract *ConfigurationError from *PlanningError")
	}

	// Wrapped causes stay reachable.
	driver := errors.New("sqlstate 55P03")
	lock := reconciler.NewLockTimeoutError("lock wait exceeded").WithCause(driver)
	if !errors.Is(lock, driver) {
		t.Fatal("cause must be reachable via Unwrap")
	}

	// Timings: zero until attached.
	if lock.Timings() != (reconciler.RunTimings{}) {
		t.Fatal("unmeasured timings must be zero")
	}
	with := reconciler.RunTimings{LockWait: 5}
	lock.WithTimings(with)
	if lock.Timings() != with {
		t.Fatal("timings not attached")
	}
}

func TestErrorPayloads(t *testing.T) {
	plan := &model.ReconcilePlan{TargetNamespace: "app", DialectID: "pg"}
	pv := reconciler.NewPolicyViolationError("destructive change blocked", plan)
	if pv.Plan.TargetNamespace != "app" {
		t.Fatal("plan not carried")
	}

	change := model.PlannedChange{Order: 1}
	apply := reconciler.NewApplyError("stmt failed", []model.PlannedChange{change}, &change)
	if len(apply.Applied) != 1 || apply.Failed == nil {
		t.Fatal("apply payload missing")
	}

	stale := reconciler.NewStalePlanError("target drifted", nil)
	if stale.Plan != nil {
		t.Fatal("unbound stale plan must be nil")
	}

	ver := reconciler.NewVerificationError("did not converge",
		[]model.Difference{{ID: model.MustObjectId(model.KindTable, "t", "")}})
	if len(ver.Remaining) != 1 {
		t.Fatal("verification payload missing")
	}
}

// stubDialect is a minimal Dialect for builder/registry tests.
type stubDialect struct{ id string }

func (d stubDialect) ID() string                         { return d.id }
func (d stubDialect) Supports(spi.DatabaseMetadata) bool { return true }
func (d stubDialect) Capabilities() spi.DialectCapabilities {
	return spi.NewDialectCapabilities(true, true)
}
func (d stubDialect) AcquireLock(context.Context, spi.Conn, string, time.Duration) error {
	return nil
}
func (d stubDialect) ReleaseLock(context.Context, spi.Conn, string) error { return nil }
func (d stubDialect) CreateScratchNamespace(context.Context, spi.Conn, string) (string, error) {
	return "s", nil
}
func (d stubDialect) DropScratchNamespace(context.Context, spi.Conn, string) error { return nil }
func (d stubDialect) ExecuteScript(context.Context, spi.Conn, string, string) error {
	return nil
}
func (d stubDialect) ReadSnapshot(context.Context, spi.Conn, string) (model.SchemaSnapshot, error) {
	return model.NewSchemaSnapshot(map[model.ObjectId]model.SchemaObject{}), nil
}
func (d stubDialect) Render(model.Difference, model.SchemaSnapshot, string, spi.RenderOptions) ([]model.PlannedChange, error) {
	return nil, nil
}
func (d stubDialect) QuoteIdentifier(s string) (string, error) { return `"` + s + `"`, nil }

func TestBuilderValidation(t *testing.T) {
	if _, err := reconciler.NewBuilder().Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("missing dataSource: %v", err)
	}
	db := spi.NewDB(nil, nil)
	if _, err := reconciler.NewBuilder().DataSource(db).Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("missing namespace: %v", err)
	}
	if _, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		LockTimeout(0).Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("zero lockTimeout: %v", err)
	}
	if _, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		StatementTimeout(-time.Second).Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("negative statementTimeout: %v", err)
	}
	if _, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		StatementTimeout(0).Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("zero statementTimeout: %v", err)
	}
	// No explicit dialect and none registered.
	if _, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("no dialect: %v", err)
	}
	// An explicit dialect builds.
	d := stubDialect{id: "stub"}
	if _, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		Dialect(d).DesiredState(reconciler.DesiredStateOfScript("select 1")).
		Build(); err != nil {
		t.Fatalf("explicit dialect build: %v", err)
	}
	// Registered dialect counts too.
	reconciler.RegisterDialect(d)
	t.Cleanup(func() { reconciler.UnregisterDialect(d) })
	if _, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		DesiredState(reconciler.DesiredStateOfScript("select 1")).Build(); err != nil {
		t.Fatalf("registered dialect build: %v", err)
	}
}

func TestBuilderRenameValidation(t *testing.T) {
	db := spi.NewDB(nil, nil)
	_, err := reconciler.NewBuilder().DataSource(db).Namespace("app").
		Dialect(stubDialect{id: "s"}).
		Rename(model.MustObjectId(model.KindTable, "a", ""),
			model.MustObjectId(model.KindView, "b", "")).
		Build()
	if !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("mismatched rename kinds must fail: %v", err)
	}
}

func TestReconcilePolicy(t *testing.T) {
	p := reconciler.DefaultPolicy()
	if p.DecisionFor(model.SafetyAdditive) != reconciler.DecisionApply ||
		p.DecisionFor(model.SafetyRebuild) != reconciler.DecisionApply ||
		p.DecisionFor(model.SafetyDestructive) != reconciler.DecisionFail {
		t.Fatal("default decisions wrong")
	}
	if p.Unsupported() != reconciler.DecisionFail || p.Prune() {
		t.Fatal("default unsupported/prune wrong")
	}

	r := reconciler.ReportOnlyPolicy()
	if r.DecisionFor(model.SafetyAdditive) != reconciler.DecisionReport {
		t.Fatal("reportOnly wrong")
	}

	// APPLY for unsupported is rejected.
	if _, err := reconciler.NewReconcilePolicy(reconciler.DecisionApply, reconciler.DecisionApply,
		reconciler.DecisionFail, reconciler.DecisionApply, nil, false); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("unsupported=APPLY must fail: %v", err)
	}
	if _, err := p.WithUnsupported(reconciler.DecisionApply); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("WithUnsupported APPLY must fail: %v", err)
	}
	if _, err := p.WithUnsupportedCategory("comment", reconciler.DecisionApply); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("category APPLY must fail: %v", err)
	}

	// Category overrides: OTHER prefix, else lowercased kind.
	p2, err := p.WithUnsupportedCategory("comment", reconciler.DecisionReport)
	if err != nil {
		t.Fatalf("with category: %v", err)
	}
	other := model.MustObjectId(model.KindOther, "comment:plans", "")
	if got := p2.UnsupportedDecision(model.Difference{ID: other}); got != reconciler.DecisionReport {
		t.Fatalf("category override: %v", got)
	}
	table := model.MustObjectId(model.KindTable, "t", "")
	if got := p2.UnsupportedDecision(model.Difference{ID: table}); got != reconciler.DecisionFail {
		t.Fatalf("uncategorised must fall back to unsupported: %v", got)
	}
	if got := p2.UnsupportedDecision(model.Difference{
		ID: model.MustObjectId(model.KindOther, "weird", "")}); got != reconciler.DecisionFail {
		t.Fatalf("OTHER without prefix is 'relation', falls back: %v", got)
	}
}

func TestMultiNamespaceBuilderValidation(t *testing.T) {
	db := spi.NewDB(nil, nil)
	if _, err := reconciler.NewMultiNamespaceBuilder().Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("missing dataSource: %v", err)
	}
	if _, err := reconciler.NewMultiNamespaceBuilder().DataSource(db).
		Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("no namespaces: %v", err)
	}
	if _, err := reconciler.NewMultiNamespaceBuilder().DataSource(db).
		Dialect(stubDialect{id: "s"}).
		Namespace("a").Namespace("a").
		Build(); !errors.Is(err, reconciler.ErrConfiguration) {
		t.Fatalf("duplicate namespace: %v", err)
	}
	m, err := reconciler.NewMultiNamespaceBuilder().DataSource(db).
		Dialect(stubDialect{id: "s"}).
		Namespace("b").Namespace("a", reconciler.WithDesiredState(reconciler.DesiredStateOfScript("select 1"))).
		Build()
	if err != nil {
		t.Fatalf("build: %v", err)
	}
	got := m.Namespaces()
	if len(got) != 2 || got[0] != "b" || got[1] != "a" {
		t.Fatalf("registration order not preserved: %v", got)
	}
}
