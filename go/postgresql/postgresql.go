// Package postgresql is the PostgreSQL 14+ dialect of spec Appendix A —
// the reference implementation — for the Go schema reconciler. Adapt a
// *sql.DB pool with spi.NewDB(db, postgresql.MetadataProbe); the dialect
// registers itself with core.RegisterDialect on import.
//
//go:generate ../scripts/sync-sql.sh
package postgresql

import (
	"context"
	"database/sql"
	"errors"
	"math/rand"
	"regexp"
	"strings"
	"sync"
	"time"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql/internal/pg"
)

const (
	savepoint      = "rcl_scratch"
	lockKeyPrefix  = "studio.epicquest.schema.reconciler:"
	dumpSQLDivider = "\u0000"
)

var createExtension = regexp.MustCompile(
	`^\s*CREATE\s+EXTENSION\s+(?:IF\s+NOT\s+EXISTS\s+)?("(?:[^"]|"")*"|[\w-]+)`)

// namespaceLocks is the session-scoped namespace lock key per pinned
// connection, so scratch cleanup can release script locks without releasing
// the run's lock.
var namespaceLocks sync.Map // spi.Conn -> int64

// Dialect is the PostgreSQL dialect of spec Appendix A — stateless,
// safe to share. It implements spi.Dialect plus the optional
// CoreVersioner, SessionNamespaceBinder, StatementTimeoutSetter,
// AccessChecker, RenameRenderer, StatementDumper, ChildrenCoverer and
// NameScoper interfaces.
type Dialect struct{}

var (
	_ spi.Dialect                = Dialect{}
	_ spi.CoreVersioner          = Dialect{}
	_ spi.SessionNamespaceBinder = Dialect{}
	_ spi.StatementTimeoutSetter = Dialect{}
	_ spi.AccessChecker          = Dialect{}
	_ spi.RenameRenderer         = Dialect{}
	_ spi.StatementDumper        = Dialect{}
	_ spi.ChildrenCoverer        = Dialect{}
	_ spi.NameScoper             = Dialect{}
)

// init registers the dialect so engine dialect selection finds it on a
// bare import of this package.
func init() {
	core.RegisterDialect(Dialect{})
}

// MetadataProbe is the spi.NewDB probe for a PostgreSQL pool: it reads
// version() — available on every PostgreSQL-compatible server — and
// reports product "PostgreSQL" with the server's major version. A
// compatible engine that is not PostgreSQL proper reports its own
// product name, and Supports refuses it exactly as JDBC's
// DatabaseMetaData product name would.
func MetadataProbe(ctx context.Context, conn spi.Executor) (spi.DatabaseMetadata, error) {
	var version string
	if err := conn.QueryRowContext(ctx, "SELECT version()").Scan(&version); err != nil {
		return spi.DatabaseMetadata{}, err
	}
	meta := spi.DatabaseMetadata{DatabaseProductName: version}
	if rest, ok := strings.CutPrefix(version, "PostgreSQL "); ok {
		meta.DatabaseProductName = "PostgreSQL"
		meta.DatabaseMajorVersion = leadingMajor(rest)
	}
	return meta, nil
}

func leadingMajor(version string) int {
	digits := 0
	for digits < len(version) && version[digits] >= '0' && version[digits] <= '9' {
		digits++
	}
	major := 0
	for i := 0; i < digits; i++ {
		major = major*10 + int(version[i]-'0')
	}
	return major
}

// ID is the stable identifier, "postgresql".
func (Dialect) ID() string { return "postgresql" }

// Supports matches PostgreSQL 14 and later (spec A.1).
func (Dialect) Supports(meta spi.DatabaseMetadata) bool {
	return meta.DatabaseProductName == "PostgreSQL" &&
		meta.DatabaseMajorVersion >= 14
}

// CoreMajorVersion is the core API major this dialect was built
// against (spec 10.3).
func (Dialect) CoreMajorVersion() int { return 1 }

// Capabilities — transactional DDL, session default namespace, every
// managed kind (all but OTHER).
func (Dialect) Capabilities() spi.DialectCapabilities {
	var kinds []model.ObjectKind
	for _, k := range model.AllObjectKinds() {
		if k != model.KindOther {
			kinds = append(kinds, k)
		}
	}
	return spi.NewDialectCapabilities(true, true, kinds...)
}

// ---- A.2 locking -----------------------------------------------------------

// AcquireLock takes the run's advisory transaction lock, bounding the
// wait with lock_timeout (spec A.2).
func (Dialect) AcquireLock(ctx context.Context, conn spi.Conn,
	namespace string, timeout time.Duration) error {
	if _, err := conn.ExecContext(ctx, "SET LOCAL lock_timeout = '"+
		itoaMillis(millisBounded(timeout))+"ms'"); err != nil {
		return err
	}
	_, err := conn.ExecContext(ctx,
		"SELECT pg_advisory_lock(hashtextextended("+
			"'"+lockKeyPrefix+"'"+
			" || current_database() || ':' || $1, 0))",
		namespace)
	if err != nil {
		if sqlState(err) == "55P03" {
			return core.NewLockTimeoutErrorf(
				"namespace '%s': lock not acquired within %s",
				namespace, timeout).WithCause(err)
		}
		return err
	}
	var lockKey int64
	if err := conn.QueryRowContext(ctx,
		"SELECT hashtextextended('"+lockKeyPrefix+"'"+
			" || current_database() || ':' || $1, 0)",
		namespace).Scan(&lockKey); err != nil {
		return err
	}
	namespaceLocks.Store(conn, lockKey)
	_, err = conn.ExecContext(ctx, "SET LOCAL lock_timeout = DEFAULT")
	return err
}

// ReleaseLock releases the run's session-scoped namespace lock.
func (Dialect) ReleaseLock(ctx context.Context, conn spi.Conn, namespace string) error {
	defer namespaceLocks.Delete(conn)
	_, err := conn.ExecContext(ctx,
		"SELECT pg_advisory_unlock(hashtextextended('"+lockKeyPrefix+"'"+
			" || current_database() || ':' || $1, 0))",
		namespace)
	return err
}

// BindSessionNamespace binds unqualified names on the run's session to
// the namespace (SET LOCAL search_path).
func (Dialect) BindSessionNamespace(ctx context.Context, conn spi.Conn,
	namespace string) error {
	q, err := pg.Quote(namespace)
	if err != nil {
		return err
	}
	_, err = conn.ExecContext(ctx, "SET LOCAL search_path = "+q)
	return err
}

// SetStatementTimeout bounds single-statement runtime for the rest of
// the run (called after the lock, so it never bounds the lock wait).
func (Dialect) SetStatementTimeout(ctx context.Context, conn spi.Conn,
	timeout time.Duration) error {
	_, err := conn.ExecContext(ctx, "SET LOCAL statement_timeout = '"+
		itoaMillis(millisBounded(timeout))+"ms'")
	return err
}

// ---- A.3 scratch namespace -------------------------------------------------

// CreateScratchNamespace creates the run's scratch schema inside the
// session's open transaction (A.3).
func (Dialect) CreateScratchNamespace(ctx context.Context, conn spi.Conn,
	targetNamespace string) (string, error) {
	scratch := pg.ScratchName(targetNamespace, rand.Uint64()&0xffffffffffff)
	// a separate scratch connection arrives here with no open
	// transaction; BEGIN is required before SAVEPOINT and is a
	// harmless warning when the transaction is already open
	if _, err := conn.ExecContext(ctx, "BEGIN"); err != nil {
		return "", err
	}
	if _, err := conn.ExecContext(ctx, "SAVEPOINT "+savepoint); err != nil {
		return "", err
	}
	q, err := pg.Quote(scratch)
	if err != nil {
		return "", err
	}
	if _, err := conn.ExecContext(ctx, "CREATE SCHEMA "+q); err != nil {
		return "", err
	}
	return scratch, nil
}

// DropScratchNamespace rolls the scratch schema back to the savepoint;
// when the desired script left the transaction the schema is dropped
// explicitly and the run fails (A.3).
func (Dialect) DropScratchNamespace(ctx context.Context, conn spi.Conn,
	scratchNamespace string) error {
	if _, err := conn.ExecContext(ctx, "ROLLBACK TO SAVEPOINT "+savepoint); err == nil {
		// the rollback undoes settings but not session advisory locks,
		// which a function the script invoked may have taken (the static
		// check cannot see through a call): release them before the
		// connection is reused
		err = releaseScriptLocks(ctx, conn)
		return err
	} else {
		// the savepoint is gone: the script committed (or aborted) the
		// transaction — remove the committed schema explicitly (A.3)
		rollbackQuietly(ctx, conn)
		_ = releaseScriptLocks(ctx, conn)
		q, qerr := pg.Quote(scratchNamespace)
		if qerr == nil {
			_, _ = conn.ExecContext(ctx,
				"DROP SCHEMA IF EXISTS "+q+" CASCADE")
		}
		_ = conn.Commit(ctx)
		return core.NewDesiredStateErrorf(
			"the desired script left the transaction: "+
				"the run's savepoint is gone (%s)", err.Error()).WithCause(err)
	}
}

// releaseScriptLocks releases advisory locks a desired script took,
// preserving this run's namespace lock when scratch shares the target
// connection.
func releaseScriptLocks(ctx context.Context, conn spi.Conn) error {
	protectedValue, protected := namespaceLocks.Load(conn)
	if !protected {
		_, err := conn.ExecContext(ctx, "SELECT pg_advisory_unlock_all()")
		return err
	}
	protectedKey := protectedValue.(int64)
	rows, err := conn.QueryContext(ctx,
		"SELECT ((classid::integer::bigint << 32) | (objid::integer::bigint & 4294967295)) AS key,"+
			" objsubid, classid::integer AS key1, objid::integer AS key2"+
			" FROM pg_locks WHERE locktype = 'advisory'"+
			" AND pid = pg_backend_pid()")
	if err != nil {
		return err
	}
	type heldLock struct {
		key        int64
		subid      int
		key1, key2 int
	}
	var held []heldLock
	for rows.Next() {
		var h heldLock
		if err := rows.Scan(&h.key, &h.subid, &h.key1, &h.key2); err != nil {
			_ = rows.Close()
			return err
		}
		held = append(held, h)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, h := range held {
		switch h.subid {
		case 1:
			if h.key == protectedKey {
				continue
			}
			if _, err := conn.ExecContext(ctx, "SELECT pg_advisory_unlock($1)", h.key); err != nil {
				return err
			}
		case 2:
			if _, err := conn.ExecContext(ctx, "SELECT pg_advisory_unlock($1, $2)", h.key1, h.key2); err != nil {
				return err
			}
		}
	}
	return nil
}

// ---- A.4 access + script execution -----------------------------------------

// CheckAccess verifies USAGE+CREATE on the namespace and, when
// createNamespace is set, CREATE on the database for the scratch
// namespace (spec A.4).
func (Dialect) CheckAccess(ctx context.Context, conn spi.Conn,
	namespace string, createNamespace bool) error {
	if namespace != "" {
		// to_regnamespace parses its argument as an identifier, so
		// quote it to keep mixed-case namespaces exact
		quoted, err := pg.Quote(namespace)
		if err != nil {
			return err
		}
		// a comma list means "any of", so each privilege is checked alone
		var exists sql.NullString
		var granted bool
		err = conn.QueryRowContext(ctx,
			"SELECT to_regnamespace($1) AS exists,"+
				" has_schema_privilege(current_user, to_regnamespace($2), 'USAGE')"+
				" AND has_schema_privilege(current_user, to_regnamespace($3), 'CREATE')"+
				" AS granted",
			quoted, quoted, quoted).Scan(&exists, &granted)
		noRows := errors.Is(err, sql.ErrNoRows)
		if err != nil && !noRows {
			return err
		}
		if !noRows && !exists.Valid {
			return core.NewConfigurationErrorf("namespace '%s'"+
				" does not exist on the target database", namespace)
		}
		if noRows || !granted {
			return core.NewConfigurationErrorf("namespace '%s'"+
				": the connecting user lacks USAGE or CREATE"+
				" on the target namespace", namespace)
		}
	}
	if createNamespace {
		var granted bool
		err := conn.QueryRowContext(ctx,
			"SELECT has_database_privilege(current_user, current_database(),"+
				" 'CREATE') AS granted").Scan(&granted)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if !granted {
			return core.NewConfigurationError("the connecting user lacks" +
				" CREATE on the database, needed for the scratch" +
				" namespace; configure scratchDataSource to" +
				" offload scratch creation")
		}
	}
	return nil
}

// ExecuteScript runs the frozen lexical guard, then the complete
// desired script inside the scratch transaction (spec A.4).
func (d Dialect) ExecuteScript(ctx context.Context, conn spi.Conn,
	script, scratchNamespace string) error {
	if err := pg.Check(script, scratchNamespace); err != nil {
		return err
	}
	if _, err := conn.ExecContext(ctx, script); err != nil {
		return err
	}
	return d.anchorScriptExtensions(ctx, conn, script, scratchNamespace)
}

// anchorScriptExtensions — an extension is a database-wide object
// anchored to a namespace; a `CREATE EXTENSION IF NOT EXISTS` in the
// desired script no-ops when the extension is already installed
// anywhere, so the scratch snapshot would never see it. Relocating a
// relocatable pre-installed extension into the scratch namespace lets
// the snapshot report the anchored EXTENSION object — the savepoint
// rollback undoes the relocation with everything else.
func (Dialect) anchorScriptExtensions(ctx context.Context, conn spi.Conn,
	script, scratchNamespace string) error {
	for _, stmt := range pg.Statements(script) {
		m := createExtension.FindStringSubmatch(stmt.Text)
		if m == nil {
			continue
		}
		raw := m[1]
		var name string
		if strings.HasPrefix(raw, "\"") {
			name = strings.ReplaceAll(raw[1:len(raw)-1], "\"\"", "\"")
		} else {
			name = strings.ToLower(raw)
		}
		var relocatable bool
		err := conn.QueryRowContext(ctx,
			"SELECT e.extrelocatable FROM pg_extension e"+
				" JOIN pg_namespace n ON n.oid = e.extnamespace"+
				" WHERE e.extname = $1 AND n.nspname <> $2",
			name, scratchNamespace).Scan(&relocatable)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return err
		}
		if relocatable {
			qName, err := pg.Quote(name)
			if err != nil {
				return err
			}
			qScratch, err := pg.Quote(scratchNamespace)
			if err != nil {
				return err
			}
			if _, err := conn.ExecContext(ctx, "ALTER EXTENSION "+
				qName+" SET SCHEMA "+qScratch); err != nil {
				return err
			}
		}
	}
	return nil
}

// ---- A.5 snapshot -----------------------------------------------------------

// ReadSnapshot reads the namespace's objects (spec A.5).
func (Dialect) ReadSnapshot(ctx context.Context, conn spi.Conn,
	namespace string) (model.SchemaSnapshot, error) {
	return pg.ReadSnapshot(ctx, conn, namespace)
}

// ---- A.6 rendering -----------------------------------------------------------

// Render renders one difference into planned changes (spec A.6).
func (Dialect) Render(difference model.Difference, snapshot model.SchemaSnapshot,
	targetNamespace string, options spi.RenderOptions) ([]model.PlannedChange, error) {
	return pg.Render(difference, snapshot, targetNamespace, options)
}

// RenderRename renders an explicit rename (spec 5.5).
func (Dialect) RenderRename(from, to model.ObjectId,
	actual, desired model.SchemaSnapshot,
	targetNamespace string) ([]model.PlannedChange, error) {
	return pg.RenderRename(from, to, actual, desired, targetNamespace)
}

// QuoteIdentifier quotes an identifier for inclusion in SQL (spec 8.3).
func (Dialect) QuoteIdentifier(identifier string) (string, error) {
	return pg.Quote(identifier)
}

// DumpStatements — 6.1: the object's complete create-fragment list in
// ${namespace} token form. DUMP_SQL joins the statements with a U+0000
// separator — impossible in PostgreSQL text — so the split is
// lossless; absent means folded or unrenderable. A trailing semicolon
// (pg_get_viewdef leaves one) is dropped: the dump terminator adds it.
func (Dialect) DumpStatements(object model.SchemaObject) []string {
	dump, ok := object.Attributes[pg.AttrDumpSQL]
	if !ok {
		return nil
	}
	parts := strings.Split(dump, dumpSQLDivider)
	for i := range parts {
		parts[i] = pg.DropTerminator(parts[i])
	}
	return parts
}

// CoveredChildren reports the objects a CREATE of this object covers
// (spec 5.8.4).
func (Dialect) CoveredChildren(created model.Difference,
	desired model.SchemaSnapshot) []model.ObjectId {
	return pg.CoveredChildren(created, desired)
}

// NameScopes reports the collision scopes an id's name occupies
// (spec 5.8.7).
func (Dialect) NameScopes(id model.ObjectId) []string {
	return pg.NameScopes(id)
}

// ---- helpers ------------------------------------------------------------

// millisBounded — a positive duration below one millisecond must still
// bound the wait.
func millisBounded(timeout time.Duration) int64 {
	return max(1, timeout.Milliseconds())
}

func itoaMillis(ms int64) string {
	const digits = "0123456789"
	if ms == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for ms > 0 {
		i--
		buf[i] = digits[ms%10]
		ms /= 10
	}
	return string(buf[i:])
}

// rollbackQuietly — ROLLBACK as a statement, which works whether the
// session's transaction was begun by a raw BEGIN or by the adapter.
func rollbackQuietly(ctx context.Context, conn spi.Conn) {
	// best effort — the connection is already on its way out
	_, _ = conn.ExecContext(ctx, "ROLLBACK")
}

// sqlState extracts a PostgreSQL error code from a driver error:
// pgx's *PgError exposes SQLState(); other drivers that put the code
// verbatim in the message still translate.
func sqlState(err error) string {
	type sqlStater interface{ SQLState() string }
	var se sqlStater
	if errors.As(err, &se) {
		return se.SQLState()
	}
	if strings.Contains(err.Error(), "55P03") {
		return "55P03"
	}
	return ""
}
