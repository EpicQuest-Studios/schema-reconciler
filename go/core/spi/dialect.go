package spi

import (
	"context"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// Marker attribute keys a dialect sets on snapshot objects (spec 4.2, 5.4,
// 5.8.3).
const (
	// ExternalDependentAttribute marks a snapshot object that lives outside
	// the snapshot's own namespace: a dependent detected from catalog
	// dependency data (5.8.3). The core never diffs such objects — they
	// exist so that dependency expansion can name them in a PlanningError.
	ExternalDependentAttribute = "externalDependent"

	// ContentComparedAttribute marks an object of an unsupported kind whose
	// canonical definition is a faithful rendering of its content. When the
	// actual snapshot holds an object with the same id and an equal
	// definition, the core reports no UNSUPPORTED difference for it (5.4);
	// without the marker an unsupported object is always reported.
	ContentComparedAttribute = "contentCompared"
)

// Dialect is the Service Provider Interface implemented once per database
// product (spec 6.1). Every I/O method takes the run's Conn — the pinned
// session, the analog of java.sql.Connection — and returns errors instead of
// throwing; the core wraps driver errors, never leaks them. The transaction
// lifecycle is the core's alone: a dialect never calls Commit, Rollback or
// SetAutoCommit itself. Methods without a Go counterpart for Java's
// `default` keyword are optional interfaces below: implement them on the
// dialect to opt in.
type Dialect interface {
	// ID is the stable identifier, for example "postgresql".
	ID() string

	// Supports reports whether this dialect matches the engine metadata —
	// the analog of supports(DatabaseMetaData). Called on first use for
	// dialect selection; it performs no I/O.
	Supports(meta DatabaseMetadata) bool

	// Capabilities describes what the dialect supports.
	Capabilities() DialectCapabilities

	// AcquireLock takes mutual exclusion between all processes reconciling
	// the same target namespace in the same database, honouring the timeout
	// (spec 6.2). Translate the engine's lock-timeout error into
	// reconciler.LockTimeoutError; any other error is wrapped by the core.
	AcquireLock(ctx context.Context, conn Conn, namespace string, timeout time.Duration) error

	// ReleaseLock releases the namespace lock on every exit path; it must
	// not leak onto a pooled connection.
	ReleaseLock(ctx context.Context, conn Conn, namespace string) error

	// CreateScratchNamespace creates a scratch namespace unique per run
	// (spec 6.3: <target>__rcl_<12 hex chars>, truncated to the engine's
	// identifier limit) and returns its name.
	CreateScratchNamespace(ctx context.Context, conn Conn, targetNamespace string) (string, error)

	// DropScratchNamespace removes the scratch namespace on every exit
	// path. Non-transactional dialects drop it with cascade semantics.
	DropScratchNamespace(ctx context.Context, conn Conn, scratchNamespace string) error

	// ExecuteScript runs the complete desired script in the scratch
	// namespace; the dialect decides whether to batch or split it.
	// scratchNamespace is the name CreateScratchNamespace returned — the
	// script's qualified references must resolve to it (3.2).
	ExecuteScript(ctx context.Context, conn Conn, script, scratchNamespace string) error

	// ReadSnapshot reads every object in the namespace, normalised per 5.3.
	ReadSnapshot(ctx context.Context, conn Conn, namespace string) (model.SchemaSnapshot, error)

	// Render renders the statements and safety class for one difference,
	// bound to the target namespace. Changes are returned with Order 0; the
	// core assigns the final order. For an UNEXPECTED difference under a
	// pruning policy the core passes the *actual* snapshot so the dialect
	// can read the unexpected object's drop fragments; for MISSING and
	// DRIFTED it is the desired snapshot.
	Render(difference model.Difference, snapshot model.SchemaSnapshot,
		targetNamespace string, options RenderOptions) ([]model.PlannedChange, error)

	// QuoteIdentifier quotes an identifier for safe inclusion in SQL. It
	// returns an error when the identifier is not legal in the engine
	// (empty, over the engine's limit, or containing characters it cannot
	// represent); the core relies on this to validate the configured
	// namespace (spec 8.3) — the analog of the Java IllegalArgumentException.
	QuoteIdentifier(identifier string) (string, error)
}

// --- Optional capabilities -------------------------------------------------
// Go has no default methods; the dialect-side optional operations of spec
// 6.1 are declared as separate interfaces. The engine probes with the helper
// functions below, which supply the spec's default behaviour (no-op or
// empty) when the dialect does not implement the interface.

// CoreVersioner reports the major version of the core API the dialect was
// built against (spec 10.3); the default is 1.
type CoreVersioner interface {
	CoreMajorVersion() int
}

// SessionNamespaceBinder binds unqualified names on the run's session to a
// namespace (spec 6.1 bindSessionNamespace — e.g. SET LOCAL search_path).
// Called before executing the desired script and before applying changes;
// the binding must not outlive the run. Default: no-op.
type SessionNamespaceBinder interface {
	BindSessionNamespace(ctx context.Context, conn Conn, namespace string) error
}

// StatementTimeoutSetter bounds how long any single statement may run during
// a run (spec 6.1 — e.g. SET LOCAL statement_timeout). Called once after the
// namespace lock is acquired, so it never bounds the lock wait itself.
// Default: no-op.
type StatementTimeoutSetter interface {
	SetStatementTimeout(ctx context.Context, conn Conn, timeout time.Duration) error
}

// AccessChecker verifies that the run's user holds the privileges a
// reconcile run needs (spec 6.1 checkAccess). Called once per run before the
// lock on the target connection — createNamespace tells it whether that
// connection must also create the scratch namespace — and again on the
// scratch connection when a separate scratchDataSource is configured, then
// with namespace "" and createNamespace true. Default: no-op.
type AccessChecker interface {
	CheckAccess(ctx context.Context, conn Conn, namespace string, createNamespace bool) error
}

// RenameRenderer renders an explicit rename (spec 5.5): from exists in
// actual, to is its desired identity. Dialects without a rename statement
// leave this unimplemented; the core then raises a PlanningError, matching
// the Java default method.
type RenameRenderer interface {
	RenderRename(from, to model.ObjectId, actual, desired model.SchemaSnapshot,
		targetNamespace string) ([]model.PlannedChange, error)
}

// StatementDumper renders create-fragment statements for one snapshot
// object, written with the ${namespace} token (3.2) so the emitted script is
// namespace-neutral — the building block of dump() (3.1). An object that
// cannot be represented as a create fragment returns an empty list; the core
// emits it as a comment line instead of dropping it silently. Default: empty.
type StatementDumper interface {
	DumpStatements(object model.SchemaObject) []string
}

// ChildrenCoverer reports the objects, other than the difference's own
// object, that the dialect's Render output for a MISSING difference creates
// as a side effect (spec 5.8.4 — for example the columns of a new table).
// The core suppresses their own differences and changes. A dialect must not
// return an id its Render output does not create. Default: empty.
type ChildrenCoverer interface {
	CoveredChildren(created model.Difference, desired model.SchemaSnapshot) []model.ObjectId
}

// NameScoper reports the scopes in which the engine requires an object's
// name to be unique, as opaque keys (spec 5.8.7 — e.g. "relation:plans_pk").
// Two objects with different ObjectIds that share a key cannot coexist in
// one namespace. Default: empty — the name is unique only within its id.
type NameScoper interface {
	NameScopes(id model.ObjectId) []string
}

// --- Optional-capability helpers (the spec's default methods) --------------

// CoreMajorVersion returns the dialect's declared core major version, or 1.
func CoreMajorVersion(d Dialect) int {
	if v, ok := d.(CoreVersioner); ok {
		return v.CoreMajorVersion()
	}
	return 1
}

// BindSessionNamespace invokes the dialect's session-namespace binding, or
// no-ops — spec 6.1 default.
func BindSessionNamespace(ctx context.Context, d Dialect, conn Conn, namespace string) error {
	if b, ok := d.(SessionNamespaceBinder); ok {
		return b.BindSessionNamespace(ctx, conn, namespace)
	}
	return nil
}

// SetStatementTimeout invokes the dialect's statement-timeout bound, or
// no-ops — spec 6.1 default.
func SetStatementTimeout(ctx context.Context, d Dialect, conn Conn, timeout time.Duration) error {
	if s, ok := d.(StatementTimeoutSetter); ok {
		return s.SetStatementTimeout(ctx, conn, timeout)
	}
	return nil
}

// CheckAccess invokes the dialect's privilege check, or no-ops — spec 6.1
// default.
func CheckAccess(ctx context.Context, d Dialect, conn Conn, namespace string, createNamespace bool) error {
	if a, ok := d.(AccessChecker); ok {
		return a.CheckAccess(ctx, conn, namespace, createNamespace)
	}
	return nil
}

// DumpStatements returns the dialect's create-fragment statements for the
// object, or nil — spec 6.1 default.
func DumpStatements(d Dialect, object model.SchemaObject) []string {
	if s, ok := d.(StatementDumper); ok {
		return s.DumpStatements(object)
	}
	return nil
}

// CoveredChildren returns the children the dialect's render covers, or nil —
// spec 6.1 default.
func CoveredChildren(d Dialect, created model.Difference, desired model.SchemaSnapshot) []model.ObjectId {
	if c, ok := d.(ChildrenCoverer); ok {
		return c.CoveredChildren(created, desired)
	}
	return nil
}

// NameScopes returns the dialect's name-uniqueness scopes for the id, or nil
// — spec 6.1 default.
func NameScopes(d Dialect, id model.ObjectId) []string {
	if n, ok := d.(NameScoper); ok {
		return n.NameScopes(id)
	}
	return nil
}
