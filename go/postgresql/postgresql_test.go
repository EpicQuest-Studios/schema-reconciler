package postgresql

import (
	"context"
	"errors"
	"testing"
	"time"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

func TestIdAndSupports(t *testing.T) {
	d := Dialect{}
	if d.ID() != "postgresql" {
		t.Errorf("id = %q", d.ID())
	}
	cases := []struct {
		meta spi.DatabaseMetadata
		want bool
	}{
		{spi.DatabaseMetadata{DatabaseProductName: "PostgreSQL",
			DatabaseMajorVersion: 14}, true},
		{spi.DatabaseMetadata{DatabaseProductName: "PostgreSQL",
			DatabaseMajorVersion: 18}, true},
		{spi.DatabaseMetadata{DatabaseProductName: "PostgreSQL",
			DatabaseMajorVersion: 13}, false},
		{spi.DatabaseMetadata{DatabaseProductName: "MySQL",
			DatabaseMajorVersion: 14}, false},
		{spi.DatabaseMetadata{DatabaseProductName: "CockroachDB",
			DatabaseMajorVersion: 23}, false},
	}
	for _, tc := range cases {
		if got := d.Supports(tc.meta); got != tc.want {
			t.Errorf("Supports(%+v) = %v, want %v", tc.meta, got, tc.want)
		}
	}
}

func TestCapabilitiesCoverEveryManagedKind(t *testing.T) {
	caps := Dialect{}.Capabilities()
	if !caps.TransactionalDDL || !caps.SessionDefaultNamespace {
		t.Errorf("caps = %+v", caps)
	}
	if _, has := caps.SupportedKinds[model.KindOther]; has {
		t.Error("OTHER must not be a supported kind")
	}
	for _, k := range model.AllObjectKinds() {
		if k == model.KindOther {
			continue
		}
		if _, has := caps.SupportedKinds[k]; !has {
			t.Errorf("kind %s not supported", k)
		}
	}
}

func TestCoreMajorVersionIsOne(t *testing.T) {
	if got := (Dialect{}).CoreMajorVersion(); got != 1 {
		t.Errorf("CoreMajorVersion = %d", got)
	}
}

func TestRegistersItselfOnImport(t *testing.T) {
	found := false
	for _, d := range core.RegisteredDialects() {
		if d.ID() == "postgresql" {
			found = true
		}
	}
	if !found {
		t.Error("dialect not registered")
	}
}

// fakeRow answers a fixed value — the probe's one-column result.
type fakeRow struct{ value string }

func (r fakeRow) Scan(dest ...any) error {
	*(dest[0].(*string)) = r.value
	return nil
}

func TestMetadataProbeParsesServerVersion(t *testing.T) {
	conn := fakeExecutor{row: fakeRow{"PostgreSQL 16.4 (Debian 16.4-1.pgdg120+1) on x86_64-pc-linux-gnu, compiled by gcc"}}
	meta, err := MetadataProbe(context.Background(), conn)
	if err != nil {
		t.Fatal(err)
	}
	if meta.DatabaseProductName != "PostgreSQL" || meta.DatabaseMajorVersion != 16 {
		t.Errorf("meta = %+v", meta)
	}
	// a compatible-but-not-PostgreSQL engine reports its own product
	conn = fakeExecutor{row: fakeRow{"CockroachDB CCL v24.1.0 (x86_64-pc-linux-gnu, built 2024/05/20)"}}
	meta, err = MetadataProbe(context.Background(), conn)
	if err != nil {
		t.Fatal(err)
	}
	if meta.DatabaseProductName == "PostgreSQL" {
		t.Errorf("meta = %+v", meta)
	}
	if (Dialect{}).Supports(meta) {
		t.Error("Supports must refuse CockroachDB")
	}
}

type fakeExecutor struct{ row fakeRow }

func (fakeExecutor) ExecContext(context.Context, string, ...any) (spi.Result, error) {
	return nil, errors.New("no exec")
}
func (fakeExecutor) QueryContext(context.Context, string, ...any) (spi.Rows, error) {
	return nil, errors.New("no query")
}
func (e fakeExecutor) QueryRowContext(context.Context, string, ...any) spi.Row {
	return e.row
}

func TestMillisBoundedKeepsSubMillisecondTimeoutsPositive(t *testing.T) {
	if got := millisBounded(500 * time.Microsecond); got != 1 {
		t.Errorf("millisBounded(500µs) = %d", got)
	}
	if got := millisBounded(2500 * time.Millisecond); got != 2500 {
		t.Errorf("millisBounded(2500ms) = %d", got)
	}
}
