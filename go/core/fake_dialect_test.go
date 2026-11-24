package reconciler

// fake_dialect_test.go — Go port of test/fake/FakeDialect.ts: a spi.Dialect
// over fakeDb exercising the whole engine in core unit tests. Mirroring the
// PostgreSQL profile: a missing table's create covers its columns and owned
// sequences; drifted views/routines/etc. are recreate pairs (REBUILD),
// drifted tables recreate DESTRUCTIVE, drifted sequences are a destructive
// ALTER, and drifted columns are manual destructive changes.

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// fakeRelationNamespaceOthers are the OTHER categories whose stripped names
// occupy the relation namespace (mirrors PgRender).
var fakeRelationNamespaceOthers = map[string]struct{}{
	"inherited": {}, "partindex": {}, "toast": {}, "relation": {}, "type": {},
}

type fakeDialect struct {
	db             *fakeDb
	supportedKinds map[model.ObjectKind]struct{}
}

func newFakeDialect(db *fakeDb, supportedKinds ...model.ObjectKind) *fakeDialect {
	kinds := supportedKinds
	if len(kinds) == 0 {
		kinds = model.AllObjectKinds()
	}
	set := make(map[model.ObjectKind]struct{}, len(kinds))
	for _, k := range kinds {
		set[k] = struct{}{}
	}
	return &fakeDialect{db: db, supportedKinds: set}
}

func (d *fakeDialect) ID() string { return "fake" }

func (d *fakeDialect) Supports(meta spi.DatabaseMetadata) bool {
	return meta.DatabaseProductName == "FakeDB"
}

func (d *fakeDialect) Capabilities() spi.DialectCapabilities {
	return spi.DialectCapabilities{
		TransactionalDDL:        true,
		SessionDefaultNamespace: true,
		SupportedKinds:          d.supportedKinds,
	}
}

func (d *fakeDialect) AcquireLock(ctx context.Context, conn spi.Conn,
	namespace string, timeout time.Duration) error {
	if !d.db.tryLock(fakeHandle(conn), namespace, timeout) {
		return NewLockTimeoutErrorf("namespace '%s': lock not acquired within %s",
			namespace, timeout)
	}
	return nil
}

func (d *fakeDialect) ReleaseLock(ctx context.Context, conn spi.Conn, namespace string) error {
	d.db.unlock(fakeHandle(conn), namespace)
	return nil
}

// BindSessionNamespace implements spi.SessionNamespaceBinder.
func (d *fakeDialect) BindSessionNamespace(ctx context.Context, conn spi.Conn,
	namespace string) error {
	fakeHandle(conn).namespace = namespace
	return nil
}

func (d *fakeDialect) CreateScratchNamespace(ctx context.Context, conn spi.Conn,
	targetNamespace string) (string, error) {
	scratch := d.db.nextScratch()
	d.db.mu.Lock()
	d.db.stateOf(fakeHandle(conn), scratch)
	d.db.mu.Unlock()
	return scratch, nil
}

func (d *fakeDialect) DropScratchNamespace(ctx context.Context, conn spi.Conn,
	scratchNamespace string) error {
	d.db.mu.Lock()
	clear(d.db.stateOf(fakeHandle(conn), scratchNamespace))
	d.db.mu.Unlock()
	return nil
}

func (d *fakeDialect) ExecuteScript(ctx context.Context, conn spi.Conn,
	script, scratchNamespace string) error {
	for _, chunk := range strings.Split(script, ";") {
		statement := strings.TrimSpace(chunk)
		if statement != "" {
			if _, err := conn.ExecContext(ctx, statement); err != nil {
				return err
			}
		}
	}
	return nil
}

func (d *fakeDialect) ReadSnapshot(ctx context.Context, conn spi.Conn,
	namespace string) (model.SchemaSnapshot, error) {
	d.db.mu.Lock()
	objects := copyObjects(d.db.stateOf(fakeHandle(conn), namespace))
	d.db.mu.Unlock()
	return model.NewSchemaSnapshot(objects), nil
}

func (d *fakeDialect) Render(difference model.Difference, snapshot model.SchemaSnapshot,
	targetNamespace string, options spi.RenderOptions) ([]model.PlannedChange, error) {
	id := difference.ID
	switch difference.Type {
	case model.DifferenceMissing:
		want, _ := snapshot.Object(id)
		statements := []string{fakeCreateStatement(want)}
		children := d.CoveredChildren(difference, snapshot)
		model.SortObjectIDs(children)
		for _, child := range children {
			obj, _ := snapshot.Object(child)
			statements = append(statements, fakeCreateStatement(obj))
		}
		concurrent := options.ConcurrentIndexes && id.Kind == model.KindIndex
		hazard := model.HazardNone
		if concurrent {
			hazard = model.ChangeHazard{LockLevel: model.LockShareUpdateExclusive}
		}
		expected := want.Definition
		change, err := model.NewPlannedChange(0, id, model.ChangeCreate,
			model.SafetyAdditive, statements, &expected, "missing "+id.String(),
			concurrent, hazard)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{change}, nil
	case model.DifferenceDrifted:
		switch id.Kind {
		case model.KindTable:
			return d.recreate(snapshot, id, model.SafetyDestructive, "drifted table")
		case model.KindSequence:
			want, _ := snapshot.Object(id)
			expected := want.Definition
			change, err := model.NewPlannedChange(0, id, model.ChangeAlter,
				model.SafetyDestructive, []string{fakeAlterStatement(want)},
				&expected, "drifted sequence", false, model.HazardNone)
			if err != nil {
				return nil, err
			}
			return []model.PlannedChange{change}, nil
		case model.KindColumn:
			change, err := model.NewPlannedChange(0, id, model.ChangeAlter,
				model.SafetyDestructive, nil, nil,
				"drifted column requires manual change", false, model.HazardNone)
			if err != nil {
				return nil, err
			}
			return []model.PlannedChange{change}, nil
		default:
			return d.recreate(snapshot, id, model.SafetyRebuild,
				"drifted "+id.Kind.String())
		}
	case model.DifferenceUnexpected:
		// pruning passes the ACTUAL snapshot (spec 5.5): the unexpected
		// object drops; data-bearing kinds are DESTRUCTIVE
		safety := model.SafetyRebuild
		if id.Kind == model.KindTable || id.Kind == model.KindColumn {
			safety = model.SafetyDestructive
		}
		change, err := model.NewPlannedChange(0, id, model.ChangeDrop, safety,
			[]string{fakeDropStatement(id)}, nil, "unexpected "+id.String(),
			false, model.HazardNone)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{change}, nil
	default:
		return nil, nil
	}
}

// RenderRename implements spi.RenameRenderer.
func (d *fakeDialect) RenderRename(from, to model.ObjectId, actual,
	desired model.SchemaSnapshot, targetNamespace string) ([]model.PlannedChange, error) {
	have, ok := actual.Object(from)
	if !ok {
		return nil, model.InvalidArgumentErrorf("rename source missing: %s", from)
	}
	expected := have.Definition
	change, err := model.NewPlannedChange(0, to, model.ChangeAlter,
		model.SafetyRebuild,
		[]string{"RENAME " + from.Kind.String() + " " + fakeQualified(from) +
			" TO " + to.Name},
		&expected, fmt.Sprintf("renamed %s to %s", from, to), false, model.HazardNone)
	if err != nil {
		return nil, err
	}
	return []model.PlannedChange{change}, nil
}

func (d *fakeDialect) recreate(desired model.SchemaSnapshot, id model.ObjectId,
	safety model.ChangeSafety, reason string) ([]model.PlannedChange, error) {
	want, _ := desired.Object(id)
	expected := want.Definition
	drop, err := model.NewPlannedChange(0, id, model.ChangeDrop, safety,
		[]string{fakeDropStatement(id)}, nil, reason, false, model.HazardNone)
	if err != nil {
		return nil, err
	}
	create, err := model.NewPlannedChange(0, id, model.ChangeCreate, safety,
		[]string{fakeCreateStatement(want)}, &expected, reason, false, model.HazardNone)
	if err != nil {
		return nil, err
	}
	return []model.PlannedChange{drop, create}, nil
}

func (d *fakeDialect) QuoteIdentifier(identifier string) (string, error) {
	if identifier == "" || len(identifier) > 63 || strings.ContainsRune(identifier, 0) {
		return "", model.InvalidArgumentErrorf("illegal identifier: %s", identifier)
	}
	return `"` + strings.ReplaceAll(identifier, `"`, `""`) + `"`, nil
}

// CoveredChildren implements spi.ChildrenCoverer.
func (d *fakeDialect) CoveredChildren(created model.Difference,
	desired model.SchemaSnapshot) []model.ObjectId {
	if created.ID.Kind != model.KindTable {
		return nil
	}
	table := created.ID
	var children []model.ObjectId
	for _, object := range desired.Objects {
		id := object.ID
		if id.Kind == model.KindColumn && id.Parent == table.Name {
			children = append(children, id)
		}
		if id.Kind == model.KindSequence && object.DependsOn.Has(table) {
			children = append(children, id)
		}
	}
	return children
}

// NameScopes implements spi.NameScoper.
func (d *fakeDialect) NameScopes(id model.ObjectId) []string {
	switch id.Kind {
	case model.KindOther:
		name := id.Name
		if colon := strings.Index(name, ":"); colon > 0 {
			category := name[:colon]
			stripped := name[colon+1:]
			if _, ok := fakeRelationNamespaceOthers[category]; ok {
				return []string{"relation:" + stripped}
			}
			if category == "constraint" {
				return []string{"constraint:" + stripped,
					"relation:" + stripped[strings.LastIndex(stripped, ".")+1:]}
			}
			if category == "routine" {
				return []string{"routine:" + stripped}
			}
		}
		return []string{"relation:" + name}
	case model.KindTable, model.KindView, model.KindSequence, model.KindIndex,
		model.KindMaterializedView, model.KindType, model.KindDomain,
		model.KindPartition, model.KindForeignTable:
		return []string{"relation:" + id.Name}
	case model.KindPrimaryKey, model.KindUnique, model.KindExclusion:
		return []string{"constraint:" + id.Parent + "." + id.Name,
			"relation:" + id.Name}
	case model.KindForeignKey, model.KindCheck:
		return []string{"constraint:" + id.Parent + "." + id.Name}
	case model.KindColumn:
		return []string{"column:" + id.Parent + "." + id.Name}
	case model.KindTrigger:
		return []string{"trigger:" + id.Parent + "." + id.Name}
	case model.KindRoutine, model.KindAggregate:
		return []string{"routine:" + id.Name}
	default:
		return nil
	}
}

// DumpStatements implements spi.StatementDumper. The fake language
// round-trips its own CREATE text, so every managed object dumps as a
// creatable statement; OTHER objects and external-dependent markers have no
// create fragment (spec 6.1).
func (d *fakeDialect) DumpStatements(object model.SchemaObject) []string {
	if object.ID.Kind == model.KindOther ||
		object.Attributes[spi.ExternalDependentAttribute] != "" {
		return nil
	}
	return []string{fakeCreateStatement(object)}
}

func fakeHandle(conn spi.Conn) *fakeConn {
	if c, ok := conn.(*fakeConn); ok {
		return c
	}
	panic(fmt.Sprintf("fakeDialect got a non-fake connection %T", conn))
}
