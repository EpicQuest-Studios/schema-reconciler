// Package tck is the dialect conformance suite of spec 9.2 — scenarios
// C-01…C-67, the Go analog of DialectConformanceTest. A dialect package
// supplies ConformanceHooks (a disposable database plus per-test
// namespaces) and calls RunConformanceSuite; skips are permitted only
// for kinds absent from SupportedKinds or undeclared capabilities.
package tck

import (
	"context"
	"errors"
	"sync"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// ExecutedSQL is one recorded statement.
type ExecutedSQL struct {
	SQL      string
	Prepared bool
}

// TrackingDB wraps a spi.DB and records every SQL string passed to
// ExecContext/QueryContext/QueryRowContext — the analog of
// TrackingDataSource, for conformance scenarios that must observe which
// statements ran where (for example C-02's "no statements against the
// target namespace").
type TrackingDB struct {
	Delegate spi.DB

	mu  sync.Mutex
	log []ExecutedSQL
}

func (d *TrackingDB) record(sql string, prepared bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.log = append(d.log, ExecutedSQL{SQL: sql, Prepared: prepared})
}

// Statements returns all recorded SQL in execution order.
func (d *TrackingDB) Statements() []ExecutedSQL {
	d.mu.Lock()
	defer d.mu.Unlock()
	return append([]ExecutedSQL(nil), d.log...)
}

// ExecutedSql returns the statements recorded through ExecContext (not
// queries) — the analog of TrackingDataSource.executedSql().
func (d *TrackingDB) ExecutedSql() []string {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out []string
	for _, s := range d.log {
		if !s.Prepared {
			out = append(out, s.SQL)
		}
	}
	return out
}

// Conn pins a session and wraps it in the tracking connection.
func (d *TrackingDB) Conn(ctx context.Context) (spi.Conn, error) {
	c, err := d.Delegate.Conn(ctx)
	if err != nil {
		return nil, err
	}
	return &trackingConn{target: c, record: d.record}, nil
}

type trackingConn struct {
	target spi.Conn
	record func(sql string, prepared bool)
}

func (c *trackingConn) ExecContext(ctx context.Context, query string, args ...any) (spi.Result, error) {
	c.record(query, false)
	return c.target.ExecContext(ctx, query, args...)
}

func (c *trackingConn) QueryContext(ctx context.Context, query string, args ...any) (spi.Rows, error) {
	c.record(query, true)
	return c.target.QueryContext(ctx, query, args...)
}

func (c *trackingConn) QueryRowContext(ctx context.Context, query string, args ...any) spi.Row {
	c.record(query, true)
	return c.target.QueryRowContext(ctx, query, args...)
}

func (c *trackingConn) Metadata(ctx context.Context) (spi.DatabaseMetadata, error) {
	return c.target.Metadata(ctx)
}

func (c *trackingConn) AutoCommit() bool { return c.target.AutoCommit() }

func (c *trackingConn) SetAutoCommit(ctx context.Context, on bool) error {
	return c.target.SetAutoCommit(ctx, on)
}

func (c *trackingConn) Commit(ctx context.Context) error {
	return c.target.Commit(ctx)
}

func (c *trackingConn) Rollback(ctx context.Context) error {
	return c.target.Rollback(ctx)
}

func (c *trackingConn) Close() error { return c.target.Close() }

// --- shared assertion helpers -----------------------------------------

// MustReconcile runs a reconcile that must succeed; the test fails
// with the error otherwise.
func MustReconcile(t tester, r *core.SchemaReconciler) core.ReconcileReport {
	t.Helper()
	report, err := r.Reconcile(context.Background())
	if err != nil {
		t.Fatalf("reconcile failed: %v", err)
	}
	return report
}

// Rejects runs run expecting it to fail matching sentinel; returns the
// error for message checks — the analog of assert.rejects returning
// the thrown error.
func Rejects(t tester, sentinel error, run func() error) error {
	t.Helper()
	err := run()
	if err == nil {
		t.Fatalf("expected error matching %v, got nil", sentinel)
	}
	if !errors.Is(err, sentinel) {
		t.Fatalf("expected error matching %v, got %v", sentinel, err)
	}
	return err
}

type tester interface {
	Helper()
	Fatalf(format string, args ...any)
	Errorf(format string, args ...any)
	Skip(args ...any)
}
