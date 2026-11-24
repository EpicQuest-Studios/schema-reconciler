package reconciler

// fake_db_test.go — Go port of test/fake/FakeDb.ts: an in-memory database
// exposing the spi.DB/spi.Conn contract so the whole engine runs in tests
// without a real database. The fake understands a minimal DDL language:
//
//	CREATE <KIND> <parent.>?<name> [REQUIRES <KIND>:<name>,...] [COMPARED] [DEF <canonical>]
//	DROP   <KIND> <parent.>?<name>
//	ALTER  <KIND> <parent.>?<name> DEF <canonical>
//	RENAME <KIND> <parent.>?<name> TO <newname>
//
// COMPARED sets the content-compared attribute (spec 5.4). A CREATE stores a
// SchemaObject whose canonical definition is the DEF token (empty when
// absent); ALTER replaces it. Transactions copy the committed state on first
// write and merge on commit.

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// executedStatement is a statement run through a connection.
type executedStatement struct {
	Namespace string
	SQL       string
}

// fakeDb is the in-memory database; it is also its own spi.DB factory.
type fakeDb struct {
	mu           sync.Mutex
	committed    map[string]map[model.ObjectId]model.SchemaObject
	locks        map[string]*fakeConn
	statementLog []executedStatement
	scratchSeq   int
}

func newFakeDb() *fakeDb {
	return &fakeDb{
		committed: make(map[string]map[model.ObjectId]model.SchemaObject),
		locks:     make(map[string]*fakeConn),
	}
}

// Conn implements spi.DB — the dataSource() analog.
func (db *fakeDb) Conn(ctx context.Context) (spi.Conn, error) {
	return db.newConnection(), nil
}

// dataSource exposes the fake as the spi.DB a reconciler is built with.
func (db *fakeDb) dataSource() spi.DB { return db }

// statements returns every statement ever executed through exec.
func (db *fakeDb) statements() []executedStatement {
	db.mu.Lock()
	defer db.mu.Unlock()
	return append([]executedStatement(nil), db.statementLog...)
}

// statementsOn returns statements executed against a namespace, excluding
// scratch work.
func (db *fakeDb) statementsOn(namespace string) []executedStatement {
	var out []executedStatement
	for _, s := range db.statements() {
		if s.Namespace == namespace {
			out = append(out, s)
		}
	}
	return out
}

// objectsIn reads the committed objects of a namespace, as the catalog sees
// them.
func (db *fakeDb) objectsIn(namespace string) map[model.ObjectId]model.SchemaObject {
	db.mu.Lock()
	defer db.mu.Unlock()
	return copyObjects(db.committed[namespace])
}

func copyObjects(src map[model.ObjectId]model.SchemaObject) map[model.ObjectId]model.SchemaObject {
	out := make(map[model.ObjectId]model.SchemaObject, len(src))
	for k, v := range src {
		out[k] = v
	}
	return out
}

// executeDirect executes DDL directly, bypassing the connection — fixture
// setup.
func (db *fakeDb) executeDirect(namespace, script string) {
	db.mu.Lock()
	defer db.mu.Unlock()
	objects := db.committed[namespace]
	if objects == nil {
		objects = make(map[model.ObjectId]model.SchemaObject)
		db.committed[namespace] = objects
	}
	for _, chunk := range strings.Split(script, ";") {
		statement := strings.TrimSpace(chunk)
		if statement != "" {
			if err := db.applyStatement(objects, statement); err != nil {
				panic(err)
			}
		}
	}
}

// lockHeld reports whether the namespace lock is currently held.
func (db *fakeDb) lockHeld(namespace string) bool {
	db.mu.Lock()
	defer db.mu.Unlock()
	_, held := db.locks[namespace]
	return held
}

// putObject inserts an object directly — fixture setup for objects the DDL
// cannot express.
func (db *fakeDb) putObject(namespace string, object model.SchemaObject) {
	db.mu.Lock()
	defer db.mu.Unlock()
	objects := db.committed[namespace]
	if objects == nil {
		objects = make(map[model.ObjectId]model.SchemaObject)
		db.committed[namespace] = objects
	}
	objects[object.ID] = object
}

// fakeObject builds a snapshot object for putObject fixture setup.
func fakeObject(id model.ObjectId, def string, requires ...model.ObjectId) model.SchemaObject {
	return model.NewSchemaObject(id, model.Definition(def), requires, nil)
}

// fakeExternalDependent builds an external-dependent marker object (5.8.3).
func fakeExternalDependent(id model.ObjectId, requires ...model.ObjectId) model.SchemaObject {
	return model.NewSchemaObject(id, model.Definition(""), requires,
		map[string]string{spi.ExternalDependentAttribute: "true"})
}

func (db *fakeDb) nextScratch() string {
	db.mu.Lock()
	defer db.mu.Unlock()
	db.scratchSeq++
	return fmt.Sprintf("rcl_scratch_%d", db.scratchSeq)
}

// tryLock takes the namespace lock for handle; false when another connection
// holds it. Single-lock semantics: a held lock is either ours or held until
// released — the timeout is not modelled.
func (db *fakeDb) tryLock(handle *fakeConn, namespace string, timeout time.Duration) bool {
	db.mu.Lock()
	defer db.mu.Unlock()
	holder, held := db.locks[namespace]
	if held && holder != handle {
		return false
	}
	db.locks[namespace] = handle
	handle.heldLocks[namespace] = struct{}{}
	return true
}

func (db *fakeDb) unlock(handle *fakeConn, namespace string) {
	db.mu.Lock()
	defer db.mu.Unlock()
	if db.locks[namespace] == handle {
		delete(db.locks, namespace)
		delete(handle.heldLocks, namespace)
	}
}

// applyStatement applies one DDL statement to a namespace map — the
// connection exec path.
func (db *fakeDb) applyStatement(objects map[model.ObjectId]model.SchemaObject, sqlText string) error {
	var kept []string
	for _, line := range strings.Split(sqlText, "\n") {
		if !strings.HasPrefix(strings.TrimSpace(line), "--") {
			kept = append(kept, line)
		}
	}
	tokens := strings.Fields(strings.Join(kept, "\n"))
	if len(tokens) == 0 {
		return nil
	}
	if len(tokens) < 3 {
		return fmt.Errorf("syntax error: %s", sqlText)
	}
	switch tokens[0] {
	case "CREATE":
		id, err := fakeParseObjectID(tokens[1], tokens[2])
		if err != nil {
			return err
		}
		if _, exists := objects[id]; exists {
			return fmt.Errorf("object already exists: %s", id)
		}
		parsed, err := fakeParseTail(tokens, 3)
		if err != nil {
			return err
		}
		var attrs map[string]string
		if parsed.compared {
			attrs = map[string]string{spi.ContentComparedAttribute: "true"}
		}
		objects[id] = model.NewSchemaObject(id, model.Definition(parsed.def),
			parsed.requires, attrs)
	case "DROP":
		id, err := fakeParseObjectID(tokens[1], tokens[2])
		if err != nil {
			return err
		}
		if _, exists := objects[id]; !exists {
			return fmt.Errorf("object does not exist: %s", id)
		}
		delete(objects, id)
	case "ALTER":
		id, err := fakeParseObjectID(tokens[1], tokens[2])
		if err != nil {
			return err
		}
		existing, exists := objects[id]
		if !exists {
			return fmt.Errorf("object does not exist: %s", id)
		}
		parsed, err := fakeParseTail(tokens, 3)
		if err != nil {
			return err
		}
		if !parsed.hasDef {
			return fmt.Errorf("ALTER requires DEF: %s", sqlText)
		}
		deps := existing.DependsOn
		if len(parsed.requires) > 0 {
			deps = model.NewObjectSet(parsed.requires...)
		}
		objects[id] = model.NewSchemaObject(id, model.Definition(parsed.def),
			deps.Sorted(), existing.Attributes)
	case "RENAME":
		if len(tokens) != 5 || tokens[3] != "TO" {
			return fmt.Errorf("syntax error: %s", sqlText)
		}
		id, err := fakeParseObjectID(tokens[1], tokens[2])
		if err != nil {
			return err
		}
		existing, exists := objects[id]
		if !exists {
			return fmt.Errorf("object does not exist: %s", id)
		}
		delete(objects, id)
		newID := model.ObjectId{Kind: id.Kind, Name: tokens[4], Parent: id.Parent}
		objects[newID] = model.NewSchemaObject(newID, existing.Definition,
			existing.DependsOn.Sorted(), existing.Attributes)
	default:
		return fmt.Errorf("syntax error: %s", sqlText)
	}
	return nil
}

type fakeParsedTail struct {
	requires []model.ObjectId
	def      string
	hasDef   bool
	compared bool
}

func fakeParseTail(tokens []string, from int) (fakeParsedTail, error) {
	var p fakeParsedTail
	for i := from; i < len(tokens); i++ {
		switch {
		case tokens[i] == "COMPARED":
			p.compared = true
		case tokens[i] == "REQUIRES" && i+1 < len(tokens):
			for _, ref := range strings.Split(tokens[i+1], ",") {
				id, err := fakeParseReference(ref)
				if err != nil {
					return p, err
				}
				p.requires = append(p.requires, id)
			}
			i++
		case tokens[i] == "DEF" && i+1 < len(tokens):
			p.def = strings.Join(tokens[i+1:], " ")
			p.hasDef = true
			i = len(tokens)
		}
	}
	return p, nil
}

// fakeParseReference parses KIND:parent.name or KIND:name (last dot splits
// the parent).
func fakeParseReference(text string) (model.ObjectId, error) {
	colon := strings.Index(text, ":")
	if colon < 0 {
		return model.ObjectId{}, fmt.Errorf("missing kind prefix: %s", text)
	}
	return fakeParseObjectID(text[:colon], text[colon+1:])
}

func fakeParseObjectID(kindToken, nameToken string) (model.ObjectId, error) {
	kind, err := model.ParseObjectKind(kindToken)
	if err != nil {
		return model.ObjectId{}, err
	}
	dot := strings.LastIndex(nameToken, ".")
	parented := map[model.ObjectKind]struct{}{
		model.KindColumn: {}, model.KindPrimaryKey: {},
		model.KindUnique: {}, model.KindForeignKey: {},
		model.KindCheck: {}, model.KindIndex: {},
		model.KindTrigger: {}, model.KindPolicy: {},
		model.KindComment: {},
	}
	if _, is := parented[kind]; is {
		if dot < 0 {
			return model.ObjectId{}, model.InvalidArgumentErrorf(
				"%s requires parent.name: %s", kind, nameToken)
		}
		return model.ObjectId{Kind: kind, Name: nameToken[dot+1:],
			Parent: nameToken[:dot]}, nil
	}
	return model.ObjectId{Kind: kind, Name: nameToken}, nil
}

// fakeCreateStatement renders a CREATE in the fake language.
func fakeCreateStatement(object model.SchemaObject) string {
	var sb strings.Builder
	sb.WriteString("CREATE " + object.ID.Kind.String() + " " + fakeQualified(object.ID))
	if len(object.DependsOn) > 0 {
		deps := object.DependsOn.Sorted()
		refs := make([]string, len(deps))
		for i, d := range deps {
			refs[i] = fakeReference(d)
		}
		sb.WriteString(" REQUIRES " + strings.Join(refs, ","))
	}
	if object.Attributes[spi.ContentComparedAttribute] == "true" {
		sb.WriteString(" COMPARED")
	}
	if object.Definition.Canonical() != "" {
		sb.WriteString(" DEF " + object.Definition.Canonical())
	}
	return sb.String()
}

func fakeDropStatement(id model.ObjectId) string {
	return "DROP " + id.Kind.String() + " " + fakeQualified(id)
}

func fakeAlterStatement(object model.SchemaObject) string {
	return "ALTER " + object.ID.Kind.String() + " " + fakeQualified(object.ID) +
		" DEF " + object.Definition.Canonical()
}

func fakeQualified(id model.ObjectId) string {
	if id.Parent != "" {
		return id.Parent + "." + id.Name
	}
	return id.Name
}

func fakeReference(id model.ObjectId) string {
	return id.Kind.String() + ":" + fakeQualified(id)
}

func (db *fakeDb) newConnection() *fakeConn {
	return &fakeConn{db: db, autoCommit: true, namespace: "main",
		heldLocks: make(map[string]struct{})}
}

// stateOf returns the committed-or-overlay namespace state a connection
// sees, creating the namespace when absent. Callers hold db.mu.
func (db *fakeDb) stateOf(conn *fakeConn, ns string) map[model.ObjectId]model.SchemaObject {
	if !conn.isAutoCommit() && conn.overlay == nil {
		conn.overlay = make(map[string]map[model.ObjectId]model.SchemaObject)
	}
	if conn.overlay != nil {
		objects := conn.overlay[ns]
		if objects == nil {
			objects = copyObjects(db.committed[ns])
			conn.overlay[ns] = objects
		}
		return objects
	}
	objects := db.committed[ns]
	if objects == nil {
		objects = make(map[model.ObjectId]model.SchemaObject)
		db.committed[ns] = objects
	}
	return objects
}

func (db *fakeDb) commitOverlay(conn *fakeConn) {
	db.mu.Lock()
	defer db.mu.Unlock()
	for ns, objects := range conn.overlay {
		db.committed[ns] = objects
	}
	conn.overlay = nil
}

// fakeConn is the connection state and spi.Conn behaviour.
type fakeConn struct {
	db        *fakeDb
	namespace string

	mu         sync.Mutex
	autoCommit bool
	closed     bool
	overlay    map[string]map[model.ObjectId]model.SchemaObject
	heldLocks  map[string]struct{}
}

func (c *fakeConn) Metadata(ctx context.Context) (spi.DatabaseMetadata, error) {
	return spi.DatabaseMetadata{DatabaseProductName: "FakeDB", DatabaseMajorVersion: 1}, nil
}

func (c *fakeConn) isAutoCommit() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.autoCommit
}

func (c *fakeConn) AutoCommit() bool { return c.isAutoCommit() }

func (c *fakeConn) SetAutoCommit(ctx context.Context, on bool) error {
	c.mu.Lock()
	c.autoCommit = on
	c.mu.Unlock()
	if on {
		c.db.commitOverlay(c)
	}
	return nil
}

func (c *fakeConn) Commit(ctx context.Context) error {
	c.db.commitOverlay(c)
	return nil
}

func (c *fakeConn) Rollback(ctx context.Context) error {
	c.db.mu.Lock()
	defer c.db.mu.Unlock()
	c.overlay = nil
	return nil
}

func (c *fakeConn) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.overlay = nil
	c.mu.Unlock()
	for ns := range c.heldLocks {
		c.db.unlock(c, ns)
	}
	c.heldLocks = make(map[string]struct{})
	return nil
}

func (c *fakeConn) ExecContext(ctx context.Context, query string, args ...any) (spi.Result, error) {
	trimmed := strings.TrimSpace(query)
	if trimmed == "" {
		return fakeResult{}, nil
	}
	c.db.mu.Lock()
	defer c.db.mu.Unlock()
	c.db.statementLog = append(c.db.statementLog,
		executedStatement{Namespace: c.namespace, SQL: trimmed})
	if err := c.db.applyStatement(c.db.stateOf(c, c.namespace), trimmed); err != nil {
		return nil, err
	}
	return fakeResult{}, nil
}

func (c *fakeConn) QueryContext(ctx context.Context, query string, args ...any) (spi.Rows, error) {
	return &fakeRows{}, nil
}

func (c *fakeConn) QueryRowContext(ctx context.Context, query string, args ...any) spi.Row {
	return fakeRow{}
}

// fakeResult is an empty sql.Result.
type fakeResult struct{}

func (fakeResult) LastInsertId() (int64, error) { return 0, nil }
func (fakeResult) RowsAffected() (int64, error) { return 0, nil }

// fakeRows is an empty result set.
type fakeRows struct{}

func (*fakeRows) Columns() ([]string, error) { return nil, nil }
func (*fakeRows) Next() bool                 { return false }
func (*fakeRows) Scan(dest ...any) error     { return errors.New("no rows") }
func (*fakeRows) Err() error                 { return nil }
func (*fakeRows) Close() error               { return nil }

// fakeRow is an empty single-row result.
type fakeRow struct{}

func (fakeRow) Scan(dest ...any) error { return sql.ErrNoRows }

// Compile-time checks that the fakes satisfy the SPI.
var (
	_ spi.DB   = (*fakeDb)(nil)
	_ spi.Conn = (*fakeConn)(nil)
)
