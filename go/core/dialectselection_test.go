package reconciler

// dialectselection_test.go — Go port of test/DialectSelection.test.ts:
// spec 3.4 (selection), 6.3.2 (same-engine scratch) and 10.3 (core major
// version).

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

const selectNS = "corex"

// stubDialect is a dialect with no behaviour: selection only ever asks it
// Supports.
type stubDialect struct {
	id        string
	supported bool
	coreMajor int
}

func (d stubDialect) ID() string { return d.id }

func (d stubDialect) Supports(meta spi.DatabaseMetadata) bool {
	return d.supported && meta.DatabaseProductName == "FakeDB"
}

func (d stubDialect) CoreMajorVersion() int { return d.coreMajor }

func (d stubDialect) Capabilities() spi.DialectCapabilities {
	return spi.DialectCapabilities{TransactionalDDL: true,
		SessionDefaultNamespace: true}
}

func (d stubDialect) AcquireLock(ctx context.Context, conn spi.Conn,
	namespace string, timeout time.Duration) error {
	return nil
}

func (d stubDialect) ReleaseLock(ctx context.Context, conn spi.Conn, namespace string) error {
	return nil
}

func (d stubDialect) CreateScratchNamespace(ctx context.Context, conn spi.Conn,
	namespace string) (string, error) {
	return "", errors.New("unsupported")
}

func (d stubDialect) DropScratchNamespace(ctx context.Context, conn spi.Conn,
	namespace string) error {
	return nil
}

func (d stubDialect) ExecuteScript(ctx context.Context, conn spi.Conn,
	script, namespace string) error {
	return nil
}

func (d stubDialect) ReadSnapshot(ctx context.Context, conn spi.Conn,
	namespace string) (model.SchemaSnapshot, error) {
	return model.SchemaSnapshot{}, errors.New("unsupported")
}

func (d stubDialect) Render(difference model.Difference, snapshot model.SchemaSnapshot,
	namespace string, options spi.RenderOptions) ([]model.PlannedChange, error) {
	return nil, errors.New("unsupported")
}

func (d stubDialect) QuoteIdentifier(identifier string) (string, error) {
	return identifier, nil
}

// withProviders registers dialects for discovery, unregistering them
// afterwards.
func withProviders(t *testing.T, body func() error, dialects ...spi.Dialect) error {
	t.Helper()
	for _, d := range dialects {
		RegisterDialect(d)
	}
	defer func() {
		for _, d := range dialects {
			UnregisterDialect(d)
		}
	}()
	return body()
}

func discoveringReconciler(t *testing.T, db *fakeDb) *SchemaReconciler {
	t.Helper()
	r, err := NewBuilder().DataSource(db.dataSource()).Namespace(selectNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF v")).Build()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestSelectSeveralMatchingDialectsAreAmbiguous(t *testing.T) {
	err := withProviders(t, func() error {
		_, err := discoveringReconciler(t, newFakeDb()).Plan(context.Background())
		return err
	}, stubDialect{"alpha", true, 1}, stubDialect{"beta", true, 1})
	var ce *ConfigurationError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	for _, want := range []string{"several dialects", "alpha, beta", "namespace '" + selectNS + "'"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("err = %v, want %q in message", err, want)
		}
	}
}

func TestSelectNoMatchingDialectNamesTheProductAndTheCandidates(t *testing.T) {
	err := withProviders(t, func() error {
		_, err := discoveringReconciler(t, newFakeDb()).Plan(context.Background())
		return err
	}, stubDialect{"unsupporting", false, 1})
	var ce *ConfigurationError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	for _, want := range []string{"no dialect supports", "FakeDB", "unsupporting"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("err = %v, want %q in message", err, want)
		}
	}
}

func TestSelectADialectBuiltForAnotherCoreMajorVersionIsRefused(t *testing.T) {
	err := withProviders(t, func() error {
		_, err := discoveringReconciler(t, newFakeDb()).Plan(context.Background())
		return err
	}, stubDialect{"future", true, 2})
	var ce *ConfigurationError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	if !strings.Contains(err.Error(), "core major version") {
		t.Fatalf("err = %v, want 'core major version' in message", err)
	}

	db := newFakeDb()
	r, buildErr := NewBuilder().DataSource(db.dataSource()).Namespace(selectNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF v")).
		Dialect(coreMajorOverride{fakeDialect: newFakeDialect(db), version: 2}).Build()
	if buildErr != nil {
		t.Fatal(buildErr)
	}
	_, err = r.Plan(context.Background())
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	if !strings.Contains(err.Error(), "core major version") {
		t.Fatalf("err = %v, want 'core major version' in message", err)
	}
}

// coreMajorOverride is the Go analog of TS's
// Object.create(real).coreMajorVersion = () => 2.
type coreMajorOverride struct {
	*fakeDialect
	version int
}

func (d coreMajorOverride) CoreMajorVersion() int { return d.version }

// disguisedDB is a spi.DB whose connections report a different product or
// major version.
type disguisedDB struct {
	real    spi.DB
	product string
	major   int
}

func (d disguisedDB) Conn(ctx context.Context) (spi.Conn, error) {
	conn, err := d.real.Conn(ctx)
	if err != nil {
		return nil, err
	}
	return disguisedConn{Conn: conn, meta: spi.DatabaseMetadata{
		DatabaseProductName:  d.product,
		DatabaseMajorVersion: d.major,
	}}, nil
}

type disguisedConn struct {
	spi.Conn
	meta spi.DatabaseMetadata
}

func (c disguisedConn) Metadata(ctx context.Context) (spi.DatabaseMetadata, error) {
	return c.meta, nil
}

func TestSelectScratchDataSourceMustBeTheSameEngineAndMajorVersion(t *testing.T) {
	for _, tc := range []struct {
		product string
		major   int
	}{
		{"FakeDB", 2},
		{"OtherDB", 1},
	} {
		db := newFakeDb()
		r, err := NewBuilder().DataSource(db.dataSource()).
			ScratchDataSource(disguisedDB{db.dataSource(), tc.product, tc.major}).
			Namespace(selectNS).
			DesiredState(DesiredStateOfScript("CREATE TABLE t DEF v")).
			Dialect(newFakeDialect(db)).Build()
		if err != nil {
			t.Fatal(err)
		}
		_, err = r.Plan(context.Background())
		var ce *ConfigurationError
		if !errors.As(err, &ce) {
			t.Fatalf("err = %v, want ConfigurationError", err)
		}
		for _, want := range []string{"not the same engine", "namespace '" + selectNS + "'"} {
			if !strings.Contains(err.Error(), want) {
				t.Fatalf("err = %v, want %q in message", err, want)
			}
		}
	}
}

// recordingDialect is the Go analog of the TS Object.create(real) +
// overridden methods: spec 6.1 — checkAccess runs per connection before the
// lock; executeScript gets the scratch name.
type recordingDialect struct {
	*fakeDialect
	calls        *[]string
	scratchNames *[]string
}

func (d recordingDialect) CheckAccess(ctx context.Context, conn spi.Conn,
	namespace string, createNamespace bool) error {
	*d.calls = append(*d.calls,
		"checkAccess("+namespace+","+boolString(createNamespace)+")")
	return nil
}

func (d recordingDialect) AcquireLock(ctx context.Context, conn spi.Conn,
	namespace string, timeout time.Duration) error {
	*d.calls = append(*d.calls, "acquireLock")
	return d.fakeDialect.AcquireLock(ctx, conn, namespace, timeout)
}

func (d recordingDialect) ExecuteScript(ctx context.Context, conn spi.Conn,
	script, namespace string) error {
	*d.calls = append(*d.calls, "executeScript("+namespace+")")
	return d.fakeDialect.ExecuteScript(ctx, conn, script, namespace)
}

func (d recordingDialect) CreateScratchNamespace(ctx context.Context, conn spi.Conn,
	namespace string) (string, error) {
	name, err := d.fakeDialect.CreateScratchNamespace(ctx, conn, namespace)
	*d.scratchNames = append(*d.scratchNames, name)
	return name, err
}

func boolString(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

// Spec 6.1: checkAccess runs per connection before the lock; executeScript
// gets the scratch name.
func TestSelectSpiContractCallsMatchTheJavadoc(t *testing.T) {
	db := newFakeDb()
	var calls, scratchNames []string
	recording := recordingDialect{newFakeDialect(db), &calls, &scratchNames}
	r, err := NewBuilder().DataSource(db.dataSource()).
		ScratchDataSource(db.dataSource()).
		Namespace(selectNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF v")).
		Dialect(recording).Build()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.Plan(context.Background()); err != nil {
		t.Fatal(err)
	}
	// target connection first (namespace named, scratch not created there),
	// then the scratch connection (no namespace, scratch creation needed),
	// all before the lock
	want := []string{
		"checkAccess(" + selectNS + ",false)",
		"acquireLock",
		"checkAccess(,true)",
		"executeScript(" + scratchNames[0] + ")",
	}
	if strings.Join(calls, "|") != strings.Join(want, "|") {
		t.Fatalf("calls = %v, want %v", calls, want)
	}
}
