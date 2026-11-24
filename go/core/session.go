package reconciler

import (
	"context"
	"errors"
	"log"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// warnf is the WARNING-log analog of spec 3.3 and 5.5 — console.warn in the
// TypeScript binding, a Logger.warning in the Java one. It is a package
// variable so tests can capture warnings.
var warnf = func(format string, args ...any) {
	log.Printf("reconciler: "+format, args...)
}

// phaseClock accumulates per-phase durations for RunTimings on a monotonic
// clock.
type phaseClock struct {
	lockWait    time.Duration
	readActual  time.Duration
	loadDesired time.Duration
	plan        time.Duration
	apply       time.Duration
	verify      time.Duration
}

func (c *phaseClock) now() time.Time { return time.Now() }

func (c *phaseClock) since(start time.Time) time.Duration { return time.Since(start) }

func (c *phaseClock) snapshot() RunTimings {
	return RunTimings{
		LockWait:    c.lockWait,
		ReadActual:  c.readActual,
		LoadDesired: c.loadDesired,
		Plan:        c.plan,
		Apply:       c.apply,
		Verify:      c.verify,
	}
}

// sessionConfig is the engine configuration a session reads.
type sessionConfig struct {
	dataSource        spi.DB
	scratchDataSource spi.DB
	namespace         string
	lockTimeout       time.Duration
	statementTimeout  *time.Duration
	objectFilter      ObjectFilter
	resolver          *dialectResolver
}

// engineSession is one reconcile run's connection, dialect, lock and
// transaction lifecycle.
type engineSession struct {
	dialect           spi.Dialect
	conn              spi.Conn
	cfg               sessionConfig
	restoreAutoCommit bool
	closed            bool
}

// openSession pins a connection, resolves the dialect, validates the
// namespace, checks access and — on a transactional dialect — leaves
// autocommit. needsScratch reports whether the run will need the scratch
// namespace on this connection (false for Dump).
func openSession(ctx context.Context, cfg sessionConfig, needsScratch bool) (*engineSession, error) {
	namespace := cfg.namespace
	conn, err := cfg.dataSource.Conn(ctx)
	if err != nil {
		return nil, NewSchemaReconcilerError("namespace '%s': could not obtain a connection",
			namespace).WithCause(err)
	}
	var session *engineSession
	dialect, err := func() (d spi.Dialect, err error) {
		if d, err = cfg.resolver.resolve(ctx, conn); err != nil {
			return nil, err
		}
		if err := quoteOf(d, namespace); err != nil {
			return nil, err
		}
		if err := spi.CheckAccess(ctx, d, conn, namespace,
			needsScratch && cfg.scratchDataSource == nil); err != nil {
			return nil, err
		}
		return d, nil
	}()
	if err == nil {
		session = &engineSession{dialect: dialect, conn: conn, cfg: cfg}
		if dialect.Capabilities().TransactionalDDL && conn.AutoCommit() {
			err = conn.SetAutoCommit(ctx, false)
			session.restoreAutoCommit = err == nil
		}
	}
	if err != nil {
		_ = conn.Close() // best effort
		var configErr *ConfigurationError
		if errors.As(err, &configErr) && !namesNamespace(err.Error(), namespace) {
			return nil, NewConfigurationErrorf("namespace '%s': %s",
				namespace, err.Error()).WithCause(err)
		}
		var sre *SchemaReconcilerError
		if errors.As(err, &sre) {
			return nil, err
		}
		return nil, NewSchemaReconcilerError("namespace '%s': could not prepare the connection",
			namespace).WithCause(err)
	}
	return session, nil
}

// namesNamespace reports whether message already names this namespace —
// the "already prefixed" check of spec 7.
func namesNamespace(message, namespace string) bool {
	return containsNamespace(message, namespace)
}

// lock acquires the namespace lock, honouring lockTimeout, then applies the
// statement bound — after the lock so it never bounds the lock wait itself.
func (s *engineSession) lock(ctx context.Context) error {
	namespace := s.cfg.namespace
	if err := s.dialect.AcquireLock(ctx, s.conn, namespace, s.cfg.lockTimeout); err != nil {
		var lockTimeout *LockTimeoutError
		if errors.As(err, &lockTimeout) {
			return err
		}
		return NewSchemaReconcilerError("namespace '%s': could not acquire the reconcile lock",
			namespace).WithCause(err)
	}
	if s.cfg.statementTimeout != nil {
		if err := spi.SetStatementTimeout(ctx, s.dialect, s.conn, *s.cfg.statementTimeout); err != nil {
			return NewSchemaReconcilerError("namespace '%s': could not acquire the reconcile lock",
				namespace).WithCause(err)
		}
	}
	return nil
}

// readActual reads and filters the actual snapshot.
func (s *engineSession) readActual(ctx context.Context) (model.SchemaSnapshot, error) {
	snapshot, err := s.dialect.ReadSnapshot(ctx, s.conn, s.cfg.namespace)
	if err != nil {
		var sre *SchemaReconcilerError
		if errors.As(err, &sre) {
			return model.SchemaSnapshot{}, err
		}
		return model.SchemaSnapshot{}, NewSchemaReconcilerError(
			"namespace '%s': could not read the catalog snapshot",
			s.cfg.namespace).WithCause(err)
	}
	return applyObjectFilter(snapshot, s.cfg.objectFilter), nil
}

// bindNamespace binds unqualified names on the session when the dialect has
// a session default namespace.
func (s *engineSession) bindNamespace(ctx context.Context) error {
	if s.dialect.Capabilities().SessionDefaultNamespace {
		if err := spi.BindSessionNamespace(ctx, s.dialect, s.conn, s.cfg.namespace); err != nil {
			return NewSchemaReconcilerError(
				"namespace '%s': could not bind the session namespace",
				s.cfg.namespace).WithCause(err)
		}
	}
	return nil
}

// execute runs one applied statement, unwrapped — the caller classifies.
func (s *engineSession) execute(ctx context.Context, sql string) error {
	_, err := s.conn.ExecContext(ctx, sql)
	return err
}

// commit commits the session's transaction.
func (s *engineSession) commit(ctx context.Context) error {
	if err := s.conn.Commit(ctx); err != nil {
		return NewSchemaReconcilerError("namespace '%s': commit failed",
			s.cfg.namespace).WithCause(err)
	}
	return nil
}

// autoCommit switches the connection's auto-commit mode for the
// non-transactional phase.
func (s *engineSession) autoCommit(ctx context.Context, on bool) error {
	if s.conn.AutoCommit() == on {
		return nil
	}
	if err := s.conn.SetAutoCommit(ctx, on); err != nil {
		return NewSchemaReconcilerError("namespace '%s': could not switch auto-commit for the non-transactional phase",
			s.cfg.namespace).WithCause(err)
	}
	return nil
}

// close releases every session resource on every exit path: roll back
// first (it clears an aborted transaction and releases transaction-scoped
// locks, so explicit lock release runs on a clean session), then release
// the lock, restore autocommit and return the connection — all warn-only,
// since a throw here would replace an in-flight error.
func (s *engineSession) close(ctx context.Context) {
	if s.closed {
		return
	}
	s.closed = true
	namespace := s.cfg.namespace
	if err := s.conn.Rollback(ctx); err != nil {
		warnf("rollback failed: %s", err)
	}
	if err := s.dialect.ReleaseLock(ctx, s.conn, namespace); err != nil {
		warnf("lock release failed: %s", err)
	}
	if s.restoreAutoCommit {
		if err := s.conn.SetAutoCommit(ctx, true); err != nil {
			warnf("namespace '%s': could not restore auto-commit: %s", namespace, err)
		}
	}
	if err := s.conn.Close(); err != nil {
		warnf("namespace '%s': could not close the connection: %s", namespace, err)
	}
}
