// Package spi is the Service Provider Interface of the schema reconciler
// (SPEC.md section 6) — the Go analog of studio.epicquest.schema.reconciler.spi.
// The core owns diffing, dependency expansion, ordering, policy, verification
// and transaction control; a dialect owns only vendor-specific SQL.
package spi

import (
	"context"
	"database/sql"
	"errors"
	"sync"
)

// DatabaseMetadata is the product metadata dialect selection reads (spec 3.4,
// 6.1) — the analog of the two java.sql.DatabaseMetaData members the engine
// consults: product name and major version.
type DatabaseMetadata struct {
	DatabaseProductName  string
	DatabaseMajorVersion int
}

// Result is the database/sql driver's statement result — the analog of
// java.sql.Statement's update count.
type Result = sql.Result

// Rows is the row-iterating surface a dialect needs from a query result —
// *sql.Rows satisfies it directly, and test doubles provide their own.
type Rows interface {
	Columns() ([]string, error)
	Next() bool
	Scan(dest ...any) error
	Err() error
	Close() error
}

// Row is the single-row surface — *sql.Row satisfies it directly.
type Row interface {
	Scan(dest ...any) error
}

// Executor is the statement-executing surface embedded in Conn — the part of
// java.sql.Connection a dialect uses to run catalog queries and rendered
// statements. When the run's session is inside its transaction (AutoCommit
// false) statements join it automatically, exactly as on a JDBC connection.
type Executor interface {
	// ExecContext runs a statement that returns no rows — the analog of
	// Statement.execute. Multi-statement scripts are the dialect's
	// responsibility (ExecuteScript), not this method's.
	ExecContext(ctx context.Context, query string, args ...any) (Result, error)
	// QueryContext runs a statement and returns its rows.
	QueryContext(ctx context.Context, query string, args ...any) (Rows, error)
	// QueryRowContext runs a statement expected to return one row.
	QueryRowContext(ctx context.Context, query string, args ...any) Row
}

// Conn is one dedicated database session pinned for a reconcile run — the
// java.sql.Connection analog. The engine drives the transaction lifecycle
// through AutoCommit/SetAutoCommit/Commit/Rollback exactly as it does JDBC's:
//
//   - AutoCommit reports whether every statement commits implicitly.
//   - SetAutoCommit(false) leaves autocommit; the next statement joins the
//     run's transaction. SetAutoCommit(true) commits a pending transaction.
//   - Commit and Rollback end the current transaction; with AutoCommit
//     still off the next statement begins a new one (JDBC semantics).
//
// A dialect receives the Conn and never manages this lifecycle itself —
// the core owns transaction control (spec 6.1).
type Conn interface {
	Executor

	// Metadata returns the product name and major version of the connected
	// engine, for dialect selection and engine-equality checks.
	Metadata(ctx context.Context) (DatabaseMetadata, error)

	// AutoCommit reports whether the connection commits every statement
	// implicitly — getAutoCommit in JDBC terms.
	AutoCommit() bool

	// SetAutoCommit turns implicit commit off (the next statement begins
	// the unit of work) or back on (a pending transaction commits first).
	SetAutoCommit(ctx context.Context, on bool) error

	// Commit commits the run's transaction; with AutoCommit off the next
	// statement begins a fresh transaction. No-op in autocommit mode.
	Commit(ctx context.Context) error

	// Rollback aborts the run's transaction. No-op in autocommit mode.
	Rollback(ctx context.Context) error

	// Close returns the session to its pool (or closes it). The engine
	// calls it exactly once per run, on every exit path.
	Close() error
}

// DB is the Go analog of javax.sql.DataSource: a factory of pinned sessions.
// Adapt a *sql.DB pool with NewDB, or implement the interface for test fakes.
type DB interface {
	Conn(ctx context.Context) (Conn, error)
}

// MetadataProbe supplies DatabaseMetadata for a session — NewDB's analog of
// JDBC's driver-provided DatabaseMetaData. It is engine-specific (the
// PostgreSQL dialect package ships one issuing SHOW server_version); a nil
// probe makes Conn.Metadata return an error.
type MetadataProbe func(ctx context.Context, conn Executor) (DatabaseMetadata, error)

// NewDB adapts a *sql.DB pool to the spi.DB contract: Conn pins one session
// via db.Conn(ctx) and wires it the given metadata probe.
func NewDB(db *sql.DB, probe MetadataProbe) DB {
	return pooledDB{db: db, probe: probe}
}

// NewConn adapts an already-pinned *sql.Conn — for scratch sessions and test
// doubles that hold a single connection.
func NewConn(conn *sql.Conn, probe MetadataProbe) Conn {
	return &connAdapter{conn: conn, probe: probe, autoCommit: true}
}

type pooledDB struct {
	db    *sql.DB
	probe MetadataProbe
}

func (d pooledDB) Conn(ctx context.Context) (Conn, error) {
	conn, err := d.db.Conn(ctx)
	if err != nil {
		return nil, err
	}
	return &connAdapter{conn: conn, probe: d.probe, autoCommit: true}, nil
}

// connAdapter presents one pinned *sql.Conn as a spi.Conn: it carries the
// JDBC transaction model onto database/sql by holding the run's *sql.Tx
// itself — statements route through the transaction while AutoCommit is
// false, and Commit/Rollback release it so the next statement begins a new
// one (the JDBC post-commit behaviour).
type connAdapter struct {
	conn  *sql.Conn
	probe MetadataProbe

	mu         sync.Mutex
	meta       *DatabaseMetadata
	autoCommit bool
	tx         *sql.Tx
}

// executorShim adapts the database/sql method sets (*sql.Conn, *sql.Tx)
// to the Executor contract — they return *sql.Rows/*sql.Row, which are
// assigned to the spi.Rows/spi.Row interfaces here.
type executorShim struct {
	execContext     func(ctx context.Context, query string, args ...any) (sql.Result, error)
	queryContext    func(ctx context.Context, query string, args ...any) (*sql.Rows, error)
	queryRowContext func(ctx context.Context, query string, args ...any) *sql.Row
}

func (s executorShim) ExecContext(ctx context.Context, query string, args ...any) (Result, error) {
	return s.execContext(ctx, query, args...)
}

func (s executorShim) QueryContext(ctx context.Context, query string, args ...any) (Rows, error) {
	rows, err := s.queryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	return rows, nil
}

func (s executorShim) QueryRowContext(ctx context.Context, query string, args ...any) Row {
	return s.queryRowContext(ctx, query, args...)
}

// executor returns the handle statements run on: the open transaction when
// the session is inside one, else the bare connection. When AutoCommit is
// off and no transaction exists yet it is begun lazily — mirroring JDBC,
// where setAutoCommit(false) defers the unit of work to the next statement.
func (a *connAdapter) executor(ctx context.Context) (Executor, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.autoCommit {
		return executorShim{a.conn.ExecContext, a.conn.QueryContext, a.conn.QueryRowContext}, nil
	}
	if a.tx == nil {
		tx, err := a.conn.BeginTx(ctx, nil)
		if err != nil {
			return nil, err
		}
		a.tx = tx
	}
	return executorShim{a.tx.ExecContext, a.tx.QueryContext, a.tx.QueryRowContext}, nil
}

func (a *connAdapter) ExecContext(ctx context.Context, query string, args ...any) (Result, error) {
	exec, err := a.executor(ctx)
	if err != nil {
		return nil, err
	}
	return exec.ExecContext(ctx, query, args...)
}

func (a *connAdapter) QueryContext(ctx context.Context, query string, args ...any) (Rows, error) {
	exec, err := a.executor(ctx)
	if err != nil {
		return nil, err
	}
	return exec.QueryContext(ctx, query, args...)
}

func (a *connAdapter) QueryRowContext(ctx context.Context, query string, args ...any) Row {
	exec, err := a.executor(ctx)
	if err != nil {
		return rowError{err}
	}
	return exec.QueryRowContext(ctx, query, args...)
}

func (a *connAdapter) AutoCommit() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.autoCommit
}

func (a *connAdapter) SetAutoCommit(ctx context.Context, on bool) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.autoCommit == on {
		return nil
	}
	a.autoCommit = on
	if on && a.tx != nil {
		// JDBC semantics: switching autocommit on commits a pending unit.
		tx := a.tx
		a.tx = nil
		return tx.Commit()
	}
	return nil
}

func (a *connAdapter) Commit(ctx context.Context) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.tx == nil {
		return nil
	}
	tx := a.tx
	a.tx = nil
	return tx.Commit()
}

func (a *connAdapter) Rollback(ctx context.Context) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.tx == nil {
		return nil
	}
	tx := a.tx
	a.tx = nil
	return tx.Rollback()
}

func (a *connAdapter) Metadata(ctx context.Context) (DatabaseMetadata, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.meta != nil {
		return *a.meta, nil
	}
	if a.probe == nil {
		return DatabaseMetadata{}, errors.New("spi: no metadata probe configured for this connection")
	}
	meta, err := a.probe(ctx, executorShim{a.conn.ExecContext, a.conn.QueryContext, a.conn.QueryRowContext})
	if err != nil {
		return DatabaseMetadata{}, err
	}
	a.meta = &meta
	return meta, nil
}

// Close returns the session to its pool. An unfinished transaction is
// rolled back by the driver, but the engine always rolls back explicitly
// first — this is only the pool hand-off.
func (a *connAdapter) Close() error {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.conn.Close()
}

// rowError surfaces a deferred BeginTx failure through the Row contract.
type rowError struct{ err error }

func (r rowError) Scan(dest ...any) error { return r.err }
