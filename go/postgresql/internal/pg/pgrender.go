package pg

import (
	"regexp"
	"sort"
	"strconv"
	"strings"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

const nsToken = "${namespace}"

// Full-table lock of a DROP/recreate or a destructive ALTER.
var lockExclusive = model.ChangeHazard{LockLevel: model.LockAccessExclusive}

// `SHARE ROW EXCLUSIVE` — constraint/index adds that still allow writes.
var lockRowExclusive = model.ChangeHazard{LockLevel: model.LockShareRowExclusive}

// A FOREIGN KEY add scans existing rows under SHARE ROW EXCLUSIVE.
var validates = model.ChangeHazard{LockLevel: model.LockShareRowExclusive, ValidatesRows: true}

// PK/UNIQUE/CHECK/EXCLUSION adds validate existing rows under ACCESS EXCLUSIVE.
var validatesExclusive = model.ChangeHazard{LockLevel: model.LockAccessExclusive, ValidatesRows: true}

// `CREATE INDEX` (plain) — SHARE on the table: reads pass, writes block.
var indexBuild = model.ChangeHazard{LockLevel: model.LockShare}

// `CREATE/DROP INDEX CONCURRENTLY` — SHARE UPDATE EXCLUSIVE, off-transaction.
var indexConcurrent = model.ChangeHazard{LockLevel: model.LockShareUpdateExclusive}

// An `ALTER COLUMN TYPE` whose cast is not binary-compatible rewrites the table.
var rewrites = model.ChangeHazard{LockLevel: model.LockAccessExclusive, RewritesTable: true}

// Render renders one difference (spec 5.5/5.6): the dialect's Render.
// For an UNEXPECTED difference under a pruning policy the snapshot
// parameter is the ACTUAL snapshot (spec 5.5): the unexpected object's
// drop fragments live there — nothing desired exists to read.
func Render(difference model.Difference, snapshot model.SchemaSnapshot,
	targetNamespace string, options spi.RenderOptions) ([]model.PlannedChange, error) {
	qns, err := Quote(targetNamespace)
	if err != nil {
		return nil, err
	}
	id := difference.ID
	want, ok := snapshot.Objects[id]
	if !ok {
		return nil, core.NewPlanningErrorf("difference %s of type %s is not renderable",
			id, difference.Type)
	}
	switch difference.Type {
	case model.DifferenceMissing:
		return missing(id, want, snapshot, qns, options)
	case model.DifferenceDrifted:
		return drifted(id, want, difference, snapshot, qns, options)
	case model.DifferenceUnexpected:
		return unexpected(id, want, snapshot, qns, options)
	default:
		return nil, core.NewPlanningErrorf("difference %s of type %s is not renderable",
			id, difference.Type)
	}
}

// ---- MISSING ---------------------------------------------------------

func missing(id model.ObjectId, want model.SchemaObject,
	desired model.SchemaSnapshot, qns string,
	options spi.RenderOptions) ([]model.PlannedChange, error) {
	switch id.Kind {
	case model.KindTable:
		stmts, err := createTableStatements(id, desired, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, stmts, defOf(want), "missing "+id.String(), false,
			model.HazardNone)}, nil
	case model.KindForeignTable:
		stmts, err := createForeignTableStatements(id, desired, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, stmts, defOf(want), "missing "+id.String(), false,
			model.HazardNone)}, nil
	case model.KindColumn:
		return missingColumn(id, want, desired, qns)
	case model.KindIndex:
		ch, err := indexCreate(id, want, desired, qns, "missing "+id.String(), options,
			model.SafetyAdditive)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{ch}, nil
	case model.KindPrimaryKey, model.KindUnique, model.KindForeignKey,
		model.KindCheck, model.KindExclusion:
		stmts, err := createStatements(want, qns)
		if err != nil {
			return nil, err
		}
		hazard := validatesExclusive
		if id.Kind == model.KindForeignKey {
			hazard = validates
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, stmts, defOf(want), "missing "+id.String(),
			false, hazard)}, nil
	case model.KindView, model.KindTrigger, model.KindType,
		model.KindDomain, model.KindPolicy, model.KindExtension,
		model.KindStatistics, model.KindRule, model.KindCollation,
		model.KindConversion, model.KindPartition, model.KindPublication,
		model.KindCast, model.KindTransform:
		stmts, err := createStatements(want, qns)
		if err != nil {
			return nil, err
		}
		hazard := model.HazardNone
		if id.Kind == model.KindPartition {
			hazard = lockExclusive
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, stmts, defOf(want), "missing "+id.String(),
			false, hazard)}, nil
	case model.KindMaterializedView:
		stmts, err := matviewCreateStatements(want, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, stmts, defOf(want), "missing "+id.String(), false,
			model.HazardNone)}, nil
	case model.KindComment:
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyRebuild, []string{s}, defOf(want),
			"missing comment on "+id.String(), false, model.HazardNone)}, nil
	case model.KindSecurityLabel:
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyRebuild, []string{s}, defOf(want),
			"missing label on "+id.String(), false, model.HazardNone)}, nil
	case model.KindOwnership:
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyRebuild, []string{s}, defOf(want),
			"missing ownership on "+id.String(), false, model.HazardNone)}, nil
	case model.KindPrivileges, model.KindDefaultPrivileges:
		return privilegesChanges(id, want, nil, qns, "missing privileges on "+id.String())
	case model.KindSequence:
		var statements []string
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		statements = append(statements, s)
		if owned, ok := want.Attributes[AttrOwnedSQL]; ok {
			statements = append(statements, bind(owned, qns))
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, statements, defOf(want), "missing "+id.String(),
			false, model.HazardNone)}, nil
	case model.KindRoutine, model.KindAggregate,
		model.KindTextSearchTemplate, model.KindTextSearchParser,
		model.KindTextSearchDictionary, model.KindTextSearchConfiguration,
		model.KindOperator, model.KindOperatorClass, model.KindOperatorFamily:
		stmts, err := createStatements(want, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, stmts, defOf(want), "missing "+id.String(),
			false, model.HazardNone)}, nil
	default:
		return nil, nil
	}
}

// createTableStatements — CREATE TABLE folding in every desired column
// plus serial-owned sequences (A.6, 5.8.4).
func createTableStatements(table model.ObjectId, desired model.SchemaSnapshot,
	qns string) ([]string, error) {
	var columns []model.SchemaObject
	for _, o := range desired.Objects {
		if o.ID.Kind == model.KindColumn && o.ID.Parent == table.Name {
			columns = append(columns, o)
		}
	}
	sort.Slice(columns, func(i, j int) bool {
		return attnum(columns[i]) < attnum(columns[j])
	})
	var sequences []model.SchemaObject
	for _, o := range desired.Objects {
		if o.ID.Kind == model.KindSequence && o.DependsOn.Has(table) {
			sequences = append(sequences, o)
		}
	}
	sort.Slice(sequences, func(i, j int) bool {
		return sequences[i].ID.Compare(sequences[j].ID) < 0
	})

	var statements []string
	for _, s := range sequences {
		stmt, err := bindObj(s, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		statements = append(statements, stmt)
	}
	qName, err := Quote(table.Name)
	if err != nil {
		return nil, err
	}
	var b strings.Builder
	b.WriteString("CREATE ")
	if tablePersistence(desired, table) == "u" {
		b.WriteString("UNLOGGED ")
	}
	b.WriteString("TABLE " + qns + "." + qName + " (\n")
	for i, c := range columns {
		if i > 0 {
			b.WriteString(",\n")
		}
		b.WriteString("    " + c.Attributes[AttrColumnSQL])
	}
	b.WriteString("\n)")
	tableAttrs := desired.Objects[table].Attributes
	if partkey, ok := tableAttrs[AttrPartkey]; ok {
		b.WriteString(" PARTITION BY " + partkey)
	}
	if options := tableAttrs[AttrTableOptions]; options != "" {
		b.WriteString(" WITH (" + options + ")")
	}
	statements = append(statements, bind(b.String(), qns))
	// 'i' (USING INDEX) is applied after the index exists, see POST_SQL
	var replident string
	switch tableAttrs[AttrReplident] {
	case "f":
		replident = "FULL"
	case "n":
		replident = "NOTHING"
	}
	if replident != "" {
		statements = append(statements, bind("ALTER TABLE "+nsToken+"."+qName+
			" REPLICA IDENTITY "+replident, qns))
	}
	// CREATE TABLE cannot set row-level security; enabled flags follow
	tableDef := columnFields(string(desired.Objects[table].Definition))
	if tableDef["rls"] == "true" {
		statements = append(statements, bind("ALTER TABLE "+nsToken+"."+qName+
			" ENABLE ROW LEVEL SECURITY", qns))
	}
	if tableDef["forcerls"] == "true" {
		statements = append(statements, bind("ALTER TABLE "+nsToken+"."+qName+
			" FORCE ROW LEVEL SECURITY", qns))
	}
	for _, s := range sequences {
		if owned, ok := s.Attributes[AttrOwnedSQL]; ok {
			statements = append(statements, bind(owned, qns))
		}
	}
	return statements, nil
}

// createForeignTableStatements — CREATE FOREIGN TABLE folding in every
// desired column, then SERVER/OPTIONS (A.6, 5.8.4).
func createForeignTableStatements(table model.ObjectId,
	desired model.SchemaSnapshot, qns string) ([]string, error) {
	var columns []model.SchemaObject
	for _, o := range desired.Objects {
		if o.ID.Kind == model.KindColumn && o.ID.Parent == table.Name {
			columns = append(columns, o)
		}
	}
	sort.Slice(columns, func(i, j int) bool {
		return attnum(columns[i]) < attnum(columns[j])
	})
	attrs := desired.Objects[table].Attributes
	qName, err := Quote(table.Name)
	if err != nil {
		return nil, err
	}
	server, err := Quote(attrs[AttrFtServer])
	if err != nil {
		return nil, err
	}
	var b strings.Builder
	b.WriteString("CREATE FOREIGN TABLE " + qns + "." + qName + " (\n")
	for i, c := range columns {
		if i > 0 {
			b.WriteString(",\n")
		}
		b.WriteString("    " + c.Attributes[AttrColumnSQL])
	}
	b.WriteString("\n) SERVER " + server)
	if options := attrs[AttrFtOptions]; options != "" {
		b.WriteString(" OPTIONS (" + options + ")")
	}
	return []string{bind(b.String(), qns)}, nil
}

func attnum(o model.SchemaObject) int {
	n, _ := strconv.Atoi(o.Attributes[AttrAttnum])
	return n
}

// matviewCreateStatements — A.6: the materialized-view create —
// `WITH NO DATA` when the desired object is unpopulated, so an
// intentional empty view converges instead of being refreshed full.
func matviewCreateStatements(want model.SchemaObject, qns string) ([]string, error) {
	create, ok := want.Attributes[AttrCreateSQL]
	if !ok {
		return nil, core.NewPlanningErrorf("object %s has no %s fragment", want.ID, AttrCreateSQL)
	}
	if want.Attributes[AttrPopulated] == "false" {
		create = DropTerminator(create) + " WITH NO DATA"
	}
	return []string{bind(create, qns)}, nil
}

func tablePersistence(desired model.SchemaSnapshot, table model.ObjectId) string {
	t, ok := desired.Objects[table]
	if !ok {
		return "p"
	}
	def := string(t.Definition)
	idx := strings.Index(def, "relpersistence=")
	if idx < 0 || idx+16 > len(def) {
		return "p"
	}
	return def[idx+15 : idx+16]
}

// missingColumn — A.6: ADD COLUMN — additive for nullable or
// constant-default columns, manual otherwise.
func missingColumn(id model.ObjectId, want model.SchemaObject,
	desired model.SchemaSnapshot, qns string) ([]model.PlannedChange, error) {
	parent := id.Parent
	// a partition column is inherited — ADD COLUMN runs on the
	// partitioned root and propagates; IF NOT EXISTS absorbs the
	// parent's own identical change
	routed := partitionParent(parent, desired)
	if routed == "" {
		routed = parent
	}
	qParent, err := Quote(routed)
	if err != nil {
		return nil, err
	}
	alter := "ALTER TABLE " + qns + "." + qParent +
		" ADD COLUMN IF NOT EXISTS " + want.Attributes[AttrColumnSQL]
	if want.Attributes[AttrNotnull] != "true" {
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, []string{bind(alter, qns)},
			defOf(want), "missing nullable column "+id.Name,
			false, lockExclusive)}, nil
	}
	if defexpr, ok := want.Attributes[AttrDefault]; ok && IsConstantDefault(defexpr) {
		return []model.PlannedChange{pc(0, id, model.ChangeCreate,
			model.SafetyAdditive, []string{bind(alter, qns)},
			defOf(want), "missing NOT NULL column "+id.Name+" with a constant default",
			false, lockExclusive)}, nil
	}
	return []model.PlannedChange{pc(0, id, model.ChangeAlter,
		model.SafetyDestructive, nil, nil,
		"missing NOT NULL column "+id.Name+" requires a manual change",
		false, model.HazardNone)}, nil
}

// ---- DRIFTED ----------------------------------------------------------

// driftedMatview — A.6: a materialized view holds stored rows, so its
// recreate is `DESTRUCTIVE` (5.6): `DROP`, `CREATE` and, unless
// the desired object is unpopulated, a `REFRESH`.
func driftedMatview(id model.ObjectId, want model.SchemaObject,
	qns string) ([]model.PlannedChange, error) {
	statements, err := matviewCreateStatements(want, qns)
	if err != nil {
		return nil, err
	}
	if want.Attributes[AttrPopulated] != "false" {
		qName, err := Quote(id.Name)
		if err != nil {
			return nil, err
		}
		statements = append(statements,
			bind("REFRESH MATERIALIZED VIEW "+nsToken+"."+qName, qns))
	}
	reason := "drifted " + id.String() + " (stored rows are dropped)"
	drop, err := bindObj(want, AttrDropSQL, qns)
	if err != nil {
		return nil, err
	}
	return []model.PlannedChange{
		pc(0, id, model.ChangeDrop, model.SafetyDestructive,
			[]string{drop}, nil, reason, false, lockExclusive),
		pc(0, id, model.ChangeCreate, model.SafetyDestructive,
			statements, defOf(want), reason, false, lockExclusive)}, nil
}

// driftedEnum — A.6: an enum's labels append in place — a pure in-order
// suffix addition renders one `ALTER TYPE ... ADD VALUE` per new label
// (`ADDITIVE`); a removed or reordered label cannot be altered
// in place and stays a manual change (`DESTRUCTIVE`).
func driftedEnum(id model.ObjectId, want model.SchemaObject,
	difference model.Difference, qns string) ([]model.PlannedChange, error) {
	have := enumLabels(actualCanonical(difference))
	wantLabels := enumLabels(string(want.Definition))
	if len(wantLabels) > len(have) && arrayEquals(have, wantLabels[:len(have)]) {
		qName, err := Quote(id.Name)
		if err != nil {
			return nil, err
		}
		var statements []string
		for _, l := range wantLabels[len(have):] {
			statements = append(statements,
				bind("ALTER TYPE "+nsToken+"."+qName+" ADD VALUE "+sqlLiteral(l), qns))
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyAdditive, statements, defOf(want),
			"drifted "+id.String()+" (labels appended in order)",
			false, model.HazardNone)}, nil
	}
	return []model.PlannedChange{pc(0, id, model.ChangeAlter,
		model.SafetyDestructive, nil, nil,
		"drifted "+id.String()+" (removed or reordered labels) requires a manual change",
		false, model.HazardNone)}, nil
}

// sqlLiteral is a single-quoted SQL literal for an enum label.
func sqlLiteral(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "''") + "'"
}

// driftedTable — A.6: a `TABLE` drift limited to the row-level-security
// flags — `relrowsecurity` and `relforcerowsecurity` — renders an
// in-place `ALTER TABLE ... ENABLE|DISABLE|FORCE|NO FORCE ROW
// LEVEL SECURITY` (`REBUILD`); any other table definition change
// stays a manual `DESTRUCTIVE` change as before.
func driftedTable(id model.ObjectId, want model.SchemaObject,
	difference model.Difference, qns string) ([]model.PlannedChange, error) {
	if difference.Actual == nil {
		return manual(id, "drifted "+id.String()+" requires a manual change"), nil
	}
	wantFields := columnFields(string(want.Definition))
	haveFields := columnFields(string(*difference.Actual))
	qName, err := Quote(id.Name)
	if err != nil {
		return nil, err
	}
	qTable := "ALTER TABLE " + nsToken + "." + qName
	var statements []string
	wantRls := wantFields["rls"] == "true"
	haveRls := haveFields["rls"] == "true"
	if wantRls != haveRls {
		verb := " DISABLE"
		if wantRls {
			verb = " ENABLE"
		}
		statements = append(statements, qTable+verb+" ROW LEVEL SECURITY")
	}
	wantForce := wantFields["forcerls"] == "true"
	haveForce := haveFields["forcerls"] == "true"
	if wantForce != haveForce {
		verb := " NO FORCE"
		if wantForce {
			verb = " FORCE"
		}
		statements = append(statements, qTable+verb+" ROW LEVEL SECURITY")
	}
	rest := mapsCopy(wantFields)
	delete(rest, "rls")
	delete(rest, "forcerls")
	haveRest := mapsCopy(haveFields)
	delete(haveRest, "rls")
	delete(haveRest, "forcerls")
	if !mapsEqual(rest, haveRest) {
		return manual(id, "drifted "+id.String()+" requires a manual change"), nil
	}
	bound := make([]string, len(statements))
	for i, s := range statements {
		bound[i] = bind(s, qns)
	}
	return []model.PlannedChange{pc(0, id, model.ChangeAlter, model.SafetyRebuild,
		bound, defOf(want), "drifted "+id.String()+" (row-level security flag)",
		false, lockExclusive)}, nil
}

func manual(id model.ObjectId, reason string) []model.PlannedChange {
	return []model.PlannedChange{pc(0, id, model.ChangeAlter,
		model.SafetyDestructive, nil, nil, reason, false, model.HazardNone)}
}

// driftedDomain — A.6: domain drift decomposed like column drift —
// `SET`/`DROP DEFAULT`, `DROP NOT NULL` and `DROP CONSTRAINT` are
// safe in place (`REBUILD`); `SET NOT NULL` and `ADD
// CONSTRAINT` validate existing values, so they render as a separate
// `DESTRUCTIVE` `ALTER` change; a base-type or collation
// change cannot alter in place and stays manual.
func driftedDomain(id model.ObjectId, want model.SchemaObject,
	difference model.Difference, qns string) ([]model.PlannedChange, error) {
	if difference.Actual == nil {
		return manual(id, "drifted "+id.String()+" requires a manual change"), nil
	}
	wantOrder, wantFields := orderedFields(string(want.Definition))
	haveFields := columnFields(string(*difference.Actual))
	for _, key := range []string{"basetype", "collation"} {
		if wantFields[key] != haveFields[key] {
			return manual(id, "drifted "+id.String()+" ("+key+" changed)"+
				" requires a manual change"), nil
		}
	}
	qName, err := Quote(id.Name)
	if err != nil {
		return nil, err
	}
	alter := "ALTER DOMAIN " + nsToken + "." + qName + " "
	var safe, validating []string
	merged := mapsCopy(wantFields)
	wantDefault, hasDefault := wantFields["default"]
	if wantDefault != haveFields["default"] {
		if hasDefault {
			safe = append(safe, alter+"SET DEFAULT "+wantDefault)
		} else {
			safe = append(safe, alter+"DROP DEFAULT")
		}
	}
	wantNotnull := wantFields["notnull"] == "true"
	haveNotnull := haveFields["notnull"] == "true"
	if haveNotnull && !wantNotnull {
		safe = append(safe, alter+"DROP NOT NULL")
	} else if wantNotnull && !haveNotnull {
		validating = append(validating, alter+"SET NOT NULL")
		merged["notnull"] = haveFields["notnull"]
	}
	wantChecks := domainChecks(wantFields)
	haveChecks := domainChecks(haveFields)
	for name := range haveChecks {
		if _, ok := wantChecks[name]; !ok {
			q, err := Quote(name)
			if err != nil {
				return nil, err
			}
			safe = append(safe, alter+"DROP CONSTRAINT "+q)
		}
	}
	for name, value := range wantChecks {
		if hv, ok := haveChecks[name]; ok && hv != value {
			q, err := Quote(name)
			if err != nil {
				return nil, err
			}
			safe = append(safe, alter+"DROP CONSTRAINT "+q)
		}
		if haveChecks[name] != value {
			q, err := Quote(name)
			if err != nil {
				return nil, err
			}
			validating = append(validating, alter+"ADD CONSTRAINT "+q+" "+value)
			delete(merged, "check."+name)
		}
	}
	if len(safe) == 0 && len(validating) == 0 {
		return manual(id, "drifted "+id.String()+" requires a manual change"), nil
	}
	var changes []model.PlannedChange
	if len(safe) > 0 {
		var expected *model.Definition
		if len(validating) == 0 {
			expected = defOf(want)
		} else {
			d := canonicalOrdered(wantOrder, merged)
			expected = &d
		}
		changes = append(changes, pc(0, id, model.ChangeAlter, model.SafetyRebuild,
			bindAll(safe, qns), expected, "drifted "+id.String(),
			false, lockExclusive))
	}
	if len(validating) > 0 {
		changes = append(changes, pc(0, id, model.ChangeAlter,
			model.SafetyDestructive, bindAll(validating, qns), defOf(want),
			"drifted "+id.String()+" (validates existing values)",
			false, model.ChangeHazard{LockLevel: model.LockAccessExclusive,
				ValidatesRows: true}))
	}
	return changes, nil
}

// domainChecks is the `check.<name>=<def>` entries of a domain canonical.
func domainChecks(fields map[string]string) map[string]string {
	checks := make(map[string]string)
	for k, v := range fields {
		if strings.HasPrefix(k, "check.") {
			checks[k[6:]] = v
		}
	}
	return checks
}

func drifted(id model.ObjectId, want model.SchemaObject,
	difference model.Difference, desired model.SchemaSnapshot,
	qns string, options spi.RenderOptions) ([]model.PlannedChange, error) {
	reason := "drifted " + id.String()
	switch id.Kind {
	case model.KindColumn:
		return driftedColumn(id, want, difference, desired, qns)
	case model.KindTable:
		return driftedTable(id, want, difference, qns)
	case model.KindIndex:
		return indexRecreate(id, want, desired, qns, reason, options)
	case model.KindPrimaryKey, model.KindUnique, model.KindForeignKey,
		model.KindCheck, model.KindExclusion:
		return constraintRecreate(id, want, qns, reason)
	case model.KindView, model.KindTrigger, model.KindPolicy,
		model.KindStatistics, model.KindRule, model.KindCollation,
		model.KindConversion, model.KindPublication, model.KindCast,
		model.KindTransform:
		return recreate(id, want, qns, reason, false, lockExclusive)
	// a bound change re-homes rows — DESTRUCTIVE, not REBUILD
	case model.KindPartition:
		drop, err := bindObj(want, AttrDropSQL, qns)
		if err != nil {
			return nil, err
		}
		create, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{
			pc(0, id, model.ChangeDrop, model.SafetyDestructive,
				[]string{drop}, nil, reason, false, lockExclusive),
			pc(0, id, model.ChangeCreate, model.SafetyDestructive,
				[]string{create}, defOf(want), reason, false, lockExclusive)}, nil
	// no local data — a server/options change is a plain drop+create
	case model.KindForeignTable:
		drop, err := bindObj(want, AttrDropSQL, qns)
		if err != nil {
			return nil, err
		}
		stmts, err := createForeignTableStatements(id, desired, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{
			pc(0, id, model.ChangeDrop, model.SafetyRebuild,
				[]string{drop}, nil, reason, false, lockExclusive),
			pc(0, id, model.ChangeCreate, model.SafetyRebuild,
				stmts, defOf(want), reason, false, lockExclusive)}, nil
	case model.KindMaterializedView:
		return driftedMatview(id, want, qns)
	// a composite's attribute drift and a range's pg_range drift
	// drop and recreate the type — no in-place recipe exists;
	// enums keep the label-aware path
	case model.KindType:
		if strings.HasPrefix(string(want.Definition), "typtype=e") {
			return driftedEnum(id, want, difference, qns)
		}
		return recreate(id, want, qns, reason, false, lockExclusive)
	case model.KindDomain:
		return driftedDomain(id, want, difference, qns)
	case model.KindComment:
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyRebuild, []string{s}, defOf(want),
			"drifted comment on "+id.String(), false, model.HazardNone)}, nil
	case model.KindSecurityLabel:
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyRebuild, []string{s}, defOf(want),
			"drifted label on "+id.String(), false, model.HazardNone)}, nil
	case model.KindOwnership:
		s, err := bindObj(want, AttrCreateSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyRebuild, []string{s}, defOf(want),
			"drifted ownership on "+id.String(), false, model.HazardNone)}, nil
	case model.KindPrivileges, model.KindDefaultPrivileges:
		return privilegesChanges(id, want,
			aclTokens(actualCanonical(difference)), qns,
			"drifted privileges on "+id.String())
	case model.KindSequence:
		var statements []string
		optionsSQL := want.Attributes[AttrOptionsSQL]
		ref, err := seqRef(id)
		if err != nil {
			return nil, err
		}
		statements = append(statements,
			bind("ALTER SEQUENCE "+ref+" "+optionsSQL, qns))
		if owned, ok := want.Attributes[AttrOwnedSQL]; ok {
			statements = append(statements, bind(owned, qns))
		}
		// persistence is not an ALTER SEQUENCE option — it has its own
		// SET LOGGED/UNLOGGED form (since PostgreSQL 10)
		wantPersist := orDefault(columnFields(string(want.Definition))["relpersistence"], "p")
		havePersist := orDefault(columnFields(actualCanonical(difference))["relpersistence"], "p")
		if wantPersist != havePersist {
			suffix := " SET LOGGED"
			if wantPersist == "u" {
				suffix = " SET UNLOGGED"
			}
			statements = append(statements, bind("ALTER SEQUENCE "+ref+suffix, qns))
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter,
			model.SafetyDestructive, statements, defOf(want),
			reason, false, lockExclusive)}, nil
	case model.KindRoutine:
		haveDef := actualCanonical(difference)
		wantProcedure := orDefault(want.Attributes[AttrProkind], "f") == "p"
		haveProcedure := strings.HasPrefix(haveDef, "CREATE OR REPLACE PROCEDURE")
		// a procedure has no RETURNS clause to compare; a function
		// alters in place only while its return type is unchanged
		inPlace := wantProcedure == haveProcedure
		if inPlace && !wantProcedure {
			inPlace = want.Attributes[AttrReturnType] == returnTypeOf(haveDef)
		}
		if inPlace {
			s, err := bindObj(want, AttrCreateSQL, qns)
			if err != nil {
				return nil, err
			}
			return []model.PlannedChange{pc(0, id, model.ChangeAlter,
				model.SafetyRebuild, []string{s}, defOf(want),
				reason, false, model.HazardNone)}, nil
		}
		return recreate(id, want, qns, reason+" (return type changed)", false, lockExclusive)
	// aggregates have no CREATE OR REPLACE form — every option
	// change recreates; text-search objects compose their canonical
	// from the catalog row — any field drift recreates (no OR REPLACE
	// form exists)
	case model.KindAggregate, model.KindTextSearchTemplate,
		model.KindTextSearchParser, model.KindTextSearchDictionary,
		model.KindTextSearchConfiguration, model.KindOperator,
		model.KindOperatorClass, model.KindOperatorFamily:
		return recreate(id, want, qns, reason, false, lockExclusive)
	default:
		return manual(id, reason+" requires a manual change"), nil
	}
}

// privilegesChanges — the GRANT/REVOKE statement list converging a
// PRIVILEGES object's acl token set onto the desired one
// (`grantee:PRIVILEGE[*]`, A.5). Implicit owner rights (`<owner>`
// grantees) are skipped — they are not grants and cannot be revoked.
// A grant-option-only difference renders `REVOKE GRANT OPTION FOR` so
// the base privilege stays.
func privilegesChanges(id model.ObjectId, want model.SchemaObject,
	actual []string, qns string, reason string) ([]model.PlannedChange, error) {
	target, err := bindObj(want, AttrGrantTarget, qns)
	if err != nil {
		return nil, err
	}
	// a column-level grant parenthesizes its column after the privilege
	// word: GRANT SELECT ("c") ON TABLE t — absent on object-level grants
	colClause := ""
	if column, ok := want.Attributes[AttrGrantColumn]; ok {
		colClause = " (" + bind(column, qns) + ")"
	}
	// a scoped default-privileges object prefixes every statement with
	// ALTER DEFAULT PRIVILEGES FOR ROLE "r" IN SCHEMA "ns"
	pfx := ""
	if prefix, ok := want.Attributes[AttrGrantPrefix]; ok {
		pfx = bind(prefix, qns) + " "
	}
	desired := aclTokens(string(want.Definition))
	var stmts []string
	for _, tok := range desired {
		g := grantee(tok)
		if g == "<owner>" || containsStr(actual, tok) {
			continue
		}
		// the base privilege is held if the actual side has it with or
		// without the grant option
		bare := tokenSansOption(tok)
		held := containsStr(actual, bare) || containsStr(actual, bare+"*")
		// missing entirely, or held without the grant option — a GRANT
		// upgrades a bare grant to grantable either way
		if !held || grantable(tok) {
			gs, err := granteeSQL(g)
			if err != nil {
				return nil, err
			}
			suffix := ""
			if grantable(tok) {
				suffix = " WITH GRANT OPTION"
			}
			stmts = append(stmts, pfx+"GRANT "+privilege(tok)+colClause+" ON "+target+
				" TO "+gs+suffix)
		}
	}
	for _, tok := range actual {
		g := grantee(tok)
		if g == "<owner>" || containsStr(desired, tok) {
			continue
		}
		// the privilege is wanted when the desired side holds it in
		// either form — bare or grantable
		bare := tokenSansOption(tok)
		wanted := containsStr(desired, bare) || containsStr(desired, bare+"*")
		gs, err := granteeSQL(g)
		if err != nil {
			return nil, err
		}
		if grantable(tok) && containsStr(desired, bare) {
			// same grant, keep the base privilege — drop only the option
			stmts = append(stmts, pfx+"REVOKE GRANT OPTION FOR "+privilege(tok)+colClause+
				" ON "+target+" FROM "+gs)
		} else if !wanted {
			stmts = append(stmts, pfx+"REVOKE "+privilege(tok)+colClause+" ON "+target+
				" FROM "+gs)
		}
		// else: held bare, wanted grantable — the GRANT above upgrades it
	}
	if len(stmts) == 0 {
		return nil, nil
	}
	return []model.PlannedChange{pc(0, id, model.ChangeAlter, model.SafetyRebuild,
		stmts, defOf(want), reason, false, model.HazardNone)}, nil
}

// aclTokens is the `grantee:PRIVILEGE[*]` token list of a
// `privileges=` canonical.
func aclTokens(canonical string) []string {
	s := strings.TrimPrefix(canonical, "privileges=")
	if s == "" {
		return nil
	}
	return SplitOutsideLiterals(s, ',')
}

// grantee is the grantee of a `grantee:PRIVILEGE[*]` token — the last
// colon splits it.
func grantee(token string) string {
	return token[:strings.LastIndex(token, ":")]
}

// privilege is the privilege word of a `grantee:PRIVILEGE[*]` token.
func privilege(token string) string {
	p := token[strings.LastIndex(token, ":")+1:]
	return strings.TrimSuffix(p, "*")
}

func grantable(token string) bool { return strings.HasSuffix(token, "*") }

// tokenSansOption is the same token without a grant-option marker.
func tokenSansOption(token string) string {
	return strings.TrimSuffix(token, "*")
}

// granteeSQL — `PUBLIC` stays a keyword; a role name is quoted.
func granteeSQL(g string) (string, error) {
	if g == "PUBLIC" || strings.HasPrefix(g, "\"") {
		return g, nil
	}
	return Quote(g)
}

// driftedColumn — A.6: decomposes column drift by what each alteration
// can do to existing rows (5.6 rule 1). Reversible metadata alterations
// — a widening `TYPE` from the allowlist, default changes and
// `DROP NOT NULL` — become one `REBUILD` change. `SET NOT NULL` scans
// and fails on violating rows, so it becomes a separate `DESTRUCTIVE`
// change (rendered, gated by policy). Generated, identity and collation
// changes and non-widening type changes stay manual (no statements).
func driftedColumn(id model.ObjectId, want model.SchemaObject,
	difference model.Difference, desired model.SchemaSnapshot,
	qns string) ([]model.PlannedChange, error) {
	if difference.Actual == nil {
		return manualColumn(id, "drifted "+id.String()+" requires a manual change"), nil
	}
	haveCanonical := string(*difference.Actual)
	wantOrder, wantFields := orderedFields(string(want.Definition))
	haveFields := columnFields(haveCanonical)
	for _, key := range []string{"generated", "identity", "identityOptions", "collation"} {
		if wantFields[key] != haveFields[key] {
			return manualColumn(id, "drifted "+id.String()+" ("+key+" changed)"), nil
		}
	}
	qParent, err := Quote(id.Parent)
	if err != nil {
		return nil, err
	}
	qName, err := Quote(id.Name)
	if err != nil {
		return nil, err
	}
	alter := "ALTER TABLE " + nsToken + "." + qParent +
		" ALTER COLUMN " + qName + " "
	var safe []string
	wantType, hasWantType := wantFields["type"]
	haveType, hasHaveType := haveFields["type"]
	if wantType != haveFields["type"] {
		if !hasWantType || !hasHaveType || !widens(haveType, wantType) {
			return manualColumn(id, "drifted "+id.String()+" (type change "+
				haveFields["type"]+" -> "+wantType+" is not a widening)"), nil
		}
		// an inherited column's TYPE runs on the partitioned root and
		// propagates — the parent's own identical change does it first
		routed := partitionParent(id.Parent, desired)
		if routed == "" {
			safe = append(safe, alter+"TYPE "+wantType)
		} else {
			qRouted, err := Quote(routed)
			if err != nil {
				return nil, err
			}
			safe = append(safe, "ALTER TABLE "+nsToken+"."+qRouted+
				" ALTER COLUMN "+qName+" TYPE "+wantType)
		}
	}
	wantDefault, hasWantDefault := wantFields["default"]
	if wantDefault != haveFields["default"] {
		if hasWantDefault {
			safe = append(safe, alter+"SET DEFAULT "+wantDefault)
		} else {
			safe = append(safe, alter+"DROP DEFAULT")
		}
	}
	// storage strategy and compression are declarable (column storage
	// parameters in CREATE TABLE) and re-point only future writes —
	// in-place safe alterations like SET DEFAULT
	if wantStorage, ok := wantFields["storage"]; ok &&
		wantStorage != haveFields["storage"] {
		word, ok := storageWords[wantStorage]
		if !ok {
			word = "EXTENDED"
		}
		safe = append(safe, alter+"SET STORAGE "+word)
	}
	if wantCompression, ok := wantFields["compression"]; ok &&
		wantCompression != haveFields["compression"] {
		word, ok := compressionWords[wantCompression]
		if !ok {
			word = "default"
		}
		safe = append(safe, alter+"SET COMPRESSION "+word)
	}
	wantNotnull := wantFields["notnull"] == "true"
	haveNotnull := haveFields["notnull"] == "true"
	setNotNull := wantNotnull && !haveNotnull
	if haveNotnull && !wantNotnull {
		safe = append(safe, alter+"DROP NOT NULL")
	}
	if len(safe) == 0 && !setNotNull {
		return manualColumn(id, "drifted "+id.String()+" requires a manual change"), nil
	}
	var changes []model.PlannedChange
	if len(safe) > 0 {
		// with SET NOT NULL deferred to its own change, the applied
		// statements only converge the column to want-minus-notnull; the
		// expected definition must match that state exactly
		var expected *model.Definition
		if setNotNull {
			d := canonicalWith(wantOrder, wantFields, "notnull",
				haveFields["notnull"])
			expected = &d
		} else {
			expected = defOf(want)
		}
		// an ALTER COLUMN TYPE can rewrite the table even on the
		// widening allowlist (e.g. integer -> bigint)
		hazard := lockExclusive
		if wantType != haveFields["type"] {
			hazard = rewrites
		}
		changes = append(changes, pc(0, id, model.ChangeAlter, model.SafetyRebuild,
			bindAll(safe, qns), expected, "drifted "+id.String(), false, hazard))
	}
	if setNotNull {
		changes = append(changes, pc(0, id, model.ChangeAlter,
			model.SafetyDestructive,
			[]string{bind(alter+"SET NOT NULL", qns)},
			defOf(want), "drifted "+id.String()+" (SET NOT NULL validates existing rows)",
			false, model.ChangeHazard{LockLevel: model.LockAccessExclusive,
				ValidatesRows: true}))
	}
	return changes, nil
}

// canonicalWith is the canonical of `fields`, with `key` replaced by
// `value`, emitted in `order` — the insertion-ordered analog of the
// TypeScript `new Map(fields).set(key, value)` (verification compares
// canonical text, so field order must follow the desired canonical).
func canonicalWith(order []string, fields map[string]string, key,
	value string) model.Definition {
	merged := mapsCopy(fields)
	merged[key] = value
	return canonicalOrdered(order, merged)
}

// orderedFields is columnFields plus the canonical's field order — the
// insertion order a TypeScript Map preserves.
func orderedFields(canonical string) ([]string, map[string]string) {
	var order []string
	fields := map[string]string{}
	for _, part := range SplitOutsideLiterals(canonical, ';') {
		if eq := strings.IndexByte(part, '='); eq > 0 {
			k := part[:eq]
			if _, seen := fields[k]; !seen {
				order = append(order, k)
			}
			fields[k] = part[eq+1:]
		}
	}
	return order, fields
}

// canonicalOrdered joins fields to `k=v;…` in `order`, skipping keys
// deleted from the set — matching JS `Map` iteration order for merged
// intermediate definitions.
func canonicalOrdered(order []string,
	fields map[string]string) model.Definition {
	var parts []string
	for _, k := range order {
		if v, ok := fields[k]; ok {
			parts = append(parts, k+"="+v)
		}
	}
	return model.Definition(strings.Join(parts, ";"))
}

// partitionParent — the name of the topmost partitioned ancestor a
// column's parent inherits from — "" when the parent is not a managed
// partition. PG rejects inherited column alterations on every partition,
// including intermediate ones in a multi-level hierarchy, so the ALTER
// runs on the partitioned root and propagates down to every level.
func partitionParent(parent string, desired model.SchemaSnapshot) string {
	partitions := make(map[string]string)
	for id := range desired.Objects {
		if id.Kind == model.KindPartition {
			partitions[id.Name] = id.Parent
		}
	}
	seen := make(map[string]bool)
	current := parent
	for !seen[current] {
		if next, ok := partitions[current]; ok {
			seen[current] = true
			current = next
		} else {
			break
		}
	}
	if current == parent {
		return ""
	}
	return current
}

func manualColumn(id model.ObjectId, reason string) []model.PlannedChange {
	return []model.PlannedChange{pc(0, id, model.ChangeAlter,
		model.SafetyDestructive, nil, nil, reason, false, model.HazardNone)}
}

// columnFields is the `key=value` fields of a canonical definition —
// the `;` separator is honoured only outside quoted literals, so a
// `';'` inside a default expression or a label value cannot split
// a field.
func columnFields(canonical string) map[string]string {
	fields := make(map[string]string)
	for _, part := range SplitOutsideLiterals(canonical, ';') {
		if eq := strings.Index(part, "="); eq > 0 {
			fields[part[:eq]] = part[eq+1:]
		}
	}
	return fields
}

// enumLabels is the labels of the canonical `labels='a','b'` field — a
// comma-split that honours `”` escapes inside each quoted label.
func enumLabels(canonical string) []string {
	labels, ok := columnFields(canonical)["labels"]
	if !ok {
		return nil
	}
	var out []string
	for _, tok := range SplitOutsideLiterals(labels, ',') {
		if len(tok) >= 2 && strings.HasPrefix(tok, "'") && strings.HasSuffix(tok, "'") {
			out = append(out, strings.ReplaceAll(tok[1:len(tok)-1], "''", "'"))
		}
	}
	return out
}

// `attstorage` letter to the `SET STORAGE` keyword.
var storageWords = map[string]string{
	"p": "PLAIN", "e": "EXTERNAL", "m": "MAIN", "x": "EXTENDED"}

// `attcompression` letter to the `SET COMPRESSION` method.
var compressionWords = map[string]string{
	"p": "pglz", "l": "lz4", "z": "zstd", "": "default"}

var intTypes = map[string]int{"smallint": 1, "integer": 2, "bigint": 3}

var typmodRe = regexp.MustCompile(`\((\d+)(?:,(\d+))?\)`)

// widens reports whether `have → want` is a widening: a total and
// lossless type change — every stored value converts without error,
// truncation or semantic change. The allowlist is the integer chain,
// `character varying` growth or conversion to `text`,
// `character(n)` growth, and `numeric` growth that keeps the integer
// digits and does not shrink the scale. Several of these
// rewrite the table (the integer chain, `character` growth,
// scale-increasing `numeric`); that is a locking concern (OI-6),
// not a data-safety one. Notably excluded: `character → character
// varying/text` strips trailing spaces, and `→ character` changes
// padding semantics.
func widens(have, want string) bool {
	hb := baseType(have)
	wb := baseType(want)
	hr, hrOk := intTypes[hb]
	wr, wrOk := intTypes[wb]
	if hrOk && wrOk && len(typmod(have)) == 0 && len(typmod(want)) == 0 {
		return wr > hr
	}
	if hb == "character varying" &&
		(wb == "character varying" || wb == "text") {
		hm := typmod(have)
		wm := typmod(want)
		return wb == "text" || len(wm) == 0 ||
			(len(hm) == 1 && wm[0] >= hm[0])
	}
	if hb == "character" && wb == "character" {
		hm := typmod(have)
		wm := typmod(want)
		return len(hm) == 1 && len(wm) == 1 && wm[0] >= hm[0]
	}
	if hb == "numeric" && wb == "numeric" {
		hm := typmod(have)
		wm := typmod(want)
		// integer digits (p - s) must not shrink, or existing values overflow
		return len(wm) == 0 ||
			(len(hm) == 2 && len(wm) == 2 &&
				wm[1] >= hm[1] && wm[0]-wm[1] >= hm[0]-hm[1])
	}
	return false
}

func baseType(typ string) string {
	if p := strings.Index(typ, "("); p >= 0 {
		return strings.TrimSpace(typ[:p])
	}
	return strings.TrimSpace(typ)
}

func typmod(typ string) []int {
	m := typmodRe.FindStringSubmatch(typ)
	if m == nil {
		return nil
	}
	a, _ := strconv.Atoi(m[1])
	if m[2] != "" {
		b, _ := strconv.Atoi(m[2])
		return []int{a, b}
	}
	return []int{a}
}

func pc(order int, id model.ObjectId, action model.ChangeAction,
	safety model.ChangeSafety, statements []string, expected *model.Definition,
	reason string, nonTransactional bool, hazard model.ChangeHazard) model.PlannedChange {
	return model.PlannedChange{
		Order: order, Target: id, Action: action, Safety: safety,
		Statements: statements, Expected: expected, Reason: reason,
		NonTransactional: nonTransactional, Hazard: hazard}
}

func defOf(want model.SchemaObject) *model.Definition {
	d := want.Definition
	return &d
}

func actualCanonical(d model.Difference) string {
	if d.Actual == nil {
		return ""
	}
	return string(*d.Actual)
}

// createStatements is the CREATE statement plus its post-create fragment
// (trigger enablement, replica identity).
func createStatements(want model.SchemaObject, qns string) ([]string, error) {
	create, err := bindObj(want, AttrCreateSQL, qns)
	if err != nil {
		return nil, err
	}
	statements := []string{create}
	if post, ok := want.Attributes[AttrPostSQL]; ok {
		statements = append(statements, bind(post, qns))
	}
	return statements, nil
}

// recreate is a DROP change plus a CREATE change — the two halves of a
// recreate (4.4).

// constraintRecreate: DROP CONSTRAINT is ACCESS EXCLUSIVE; the CREATE half
// carries the constraint kind's own lock/validation hazard.
func constraintRecreate(id model.ObjectId, want model.SchemaObject, qns,
	reason string) ([]model.PlannedChange, error) {
	drop, err := bindObj(want, AttrDropSQL, qns)
	if err != nil {
		return nil, err
	}
	stmts, err := createStatements(want, qns)
	if err != nil {
		return nil, err
	}
	createHazard := validatesExclusive
	if id.Kind == model.KindForeignKey {
		createHazard = validates
	}
	return []model.PlannedChange{
		pc(0, id, model.ChangeDrop, model.SafetyRebuild,
			[]string{drop}, nil, reason, false, lockExclusive),
		pc(0, id, model.ChangeCreate, model.SafetyRebuild,
			stmts, defOf(want), reason, false, createHazard)}, nil
}

func recreate(id model.ObjectId, want model.SchemaObject, qns string,
	reason string, nonTransactional bool,
	hazard model.ChangeHazard) ([]model.PlannedChange, error) {
	drop, err := bindObj(want, AttrDropSQL, qns)
	if err != nil {
		return nil, err
	}
	stmts, err := createStatements(want, qns)
	if err != nil {
		return nil, err
	}
	return []model.PlannedChange{
		pc(0, id, model.ChangeDrop, model.SafetyRebuild,
			[]string{drop}, nil, reason, nonTransactional, hazard),
		pc(0, id, model.ChangeCreate, model.SafetyRebuild,
			stmts, defOf(want), reason, nonTransactional, hazard)}, nil
}

func seqRef(id model.ObjectId) (string, error) {
	q, err := Quote(id.Name)
	if err != nil {
		return "", err
	}
	return nsToken + "." + q, nil
}

func bindObj(object model.SchemaObject, attrKey, qns string) (string, error) {
	sql, ok := object.Attributes[attrKey]
	if !ok {
		return "", core.NewPlanningErrorf("object %s has no %s fragment", object.ID, attrKey)
	}
	return bind(sql, qns), nil
}

// bind replaces every ${namespace} token with the quoted namespace.
func bind(sql, qns string) string {
	return strings.ReplaceAll(sql, nsToken, qns)
}

func bindAll(stmts []string, qns string) []string {
	out := make([]string, len(stmts))
	for i, s := range stmts {
		out[i] = bind(s, qns)
	}
	return out
}

// returnTypeOf extracts the `RETURNS <type>` clause from a normalised
// function definition.
var returnsRe = regexp.MustCompile(`\bRETURNS (.+?) LANGUAGE\b`)

func returnTypeOf(functionDef string) string {
	m := returnsRe.FindStringSubmatch(functionDef)
	if m == nil {
		return ""
	}
	return m[1]
}

// ---- UNEXPECTED (5.5 pruning) -------------------------------------------

// destructivePrune — data-bearing kinds: their standalone drop is
// DESTRUCTIVE because the rows are lost. Every other kind's standalone
// drop is a REBUILD — removing it cannot destroy stored data.
var destructivePrune = map[model.ObjectKind]bool{
	model.KindTable: true, model.KindColumn: true,
	model.KindMaterializedView: true, model.KindPartition: true,
	model.KindSequence: true}

// unexpected — a standalone DROP for an object absent from the desired
// state (5.5). have is the ACTUAL object — its attributes hold the
// drop fragments. An unexpected COLUMN on an expected table renders
// `ALTER TABLE ... DROP COLUMN`; one on an unexpected table is
// suppressed upstream (the parent's own drop covers it). Mirrored kinds
// map to their null/revoke/owner-reset forms; COMMENT and OWNERSHIP
// have no DROP_SQL so their statements derive from the create
// fragment.
func unexpected(id model.ObjectId, have model.SchemaObject,
	actual model.SchemaSnapshot, qns string,
	options spi.RenderOptions) ([]model.PlannedChange, error) {
	safety := model.SafetyRebuild
	if destructivePrune[id.Kind] {
		safety = model.SafetyDestructive
	}
	reason := "unexpected " + id.String()
	switch id.Kind {
	case model.KindColumn:
		// a pruned column on a renamed parent drops through the new
		// name — the old one no longer exists once the rename ran
		parent := id.Parent
		if renamed, ok := options.RenamedParents[parent]; ok {
			parent = renamed
		}
		routed := partitionParent(parent, actual)
		if routed == "" {
			routed = parent
		}
		qParent, err := Quote(routed)
		if err != nil {
			return nil, err
		}
		qName, err := Quote(id.Name)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeDrop, safety,
			[]string{bind("ALTER TABLE "+nsToken+"."+qParent+
				" DROP COLUMN "+qName, qns)},
			nil, reason, false, lockExclusive)}, nil
	case model.KindTable:
		// a plain table never carries DROP_SQL — its drift path is
		// the destructive manual one, so the prune drop is built here
		qName, err := Quote(id.Name)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeDrop, safety,
			[]string{bind("DROP TABLE "+nsToken+"."+qName, qns)},
			nil, reason, false, lockExclusive)}, nil
	case model.KindIndex:
		ch, err := indexDrop(id, have, actual, qns, reason, options)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{ch}, nil
	case model.KindComment:
		// COMMENT ON <target> IS NULL — the create fragment's
		// literal is always the statement's tail
		create := have.Attributes[AttrCreateSQL]
		return []model.PlannedChange{pc(0, id, model.ChangeAlter, safety,
			[]string{bind(commentNullRe.ReplaceAllString(create, " IS NULL"), qns)},
			nil, reason, false, model.HazardNone)}, nil
	case model.KindSecurityLabel:
		s, err := bindObj(have, AttrDropSQL, qns)
		if err != nil {
			return nil, err
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter, safety,
			[]string{s}, nil, reason, false, model.HazardNone)}, nil
	case model.KindOwnership:
		// ALTER <target> OWNER TO CURRENT_USER — surrender the
		// scripted owner to whoever runs the reconcile
		create := have.Attributes[AttrCreateSQL]
		return []model.PlannedChange{pc(0, id, model.ChangeAlter, safety,
			[]string{bind(ownerResetRe.ReplaceAllString(create,
				" OWNER TO CURRENT_USER"), qns)},
			nil, reason, false, model.HazardNone)}, nil
	case model.KindPrivileges, model.KindDefaultPrivileges:
		target, err := bindObj(have, AttrGrantTarget, qns)
		if err != nil {
			return nil, err
		}
		pfx := ""
		if prefix, ok := have.Attributes[AttrGrantPrefix]; ok {
			pfx = bind(prefix, qns) + " "
		}
		var stmts []string
		for _, tok := range aclTokens(string(have.Definition)) {
			g := grantee(tok)
			if g == "<owner>" {
				continue
			}
			gs, err := granteeSQL(g)
			if err != nil {
				return nil, err
			}
			stmts = append(stmts, pfx+"REVOKE ALL PRIVILEGES ON "+target+
				" FROM "+gs)
		}
		if len(stmts) == 0 {
			return nil, nil
		}
		return []model.PlannedChange{pc(0, id, model.ChangeAlter, safety,
			stmts, nil, reason, false, model.HazardNone)}, nil
	default:
		drop, ok := have.Attributes[AttrDropSQL]
		if !ok {
			return nil, core.NewPlanningErrorf("unexpected %s has no drop statement", id)
		}
		hazard := model.HazardNone
		if safety == model.SafetyDestructive || id.Kind == model.KindPrimaryKey ||
			id.Kind == model.KindUnique || id.Kind == model.KindForeignKey ||
			id.Kind == model.KindCheck || id.Kind == model.KindExclusion {
			hazard = lockExclusive
		}
		return []model.PlannedChange{pc(0, id, model.ChangeDrop, safety,
			[]string{bind(drop, qns)}, nil, reason, false, hazard)}, nil
	}
}

var commentNullRe = regexp.MustCompile(` IS '(?:[^']|'')*'$`)
var ownerResetRe = regexp.MustCompile(` OWNER TO "(?:[^"]|"")*"$`)

// ---- concurrent indexes (5.6) -------------------------------------------

// parentPartitioned — whether the index's parent relation is a
// partitioned table — PostgreSQL rejects `CREATE INDEX CONCURRENTLY` on
// one (the partitioned index build is transactional anyway).
func parentPartitioned(id model.ObjectId, snapshot model.SchemaSnapshot) bool {
	for _, o := range snapshot.Objects {
		if o.ID.Name == id.Parent && o.ID.Parent == "" &&
			(o.ID.Kind == model.KindTable || o.ID.Kind == model.KindPartition) {
			if _, ok := o.Attributes[AttrPartkey]; ok {
				return true
			}
		}
	}
	return false
}

// concurrentIndex — whether concurrent index DDL applies to this index
// under the options.
func concurrentIndex(id model.ObjectId, snapshot model.SchemaSnapshot,
	options spi.RenderOptions) bool {
	return options.ConcurrentIndexes && !parentPartitioned(id, snapshot)
}

var concurrentlyRe = regexp.MustCompile(`^(CREATE\s+(?:UNIQUE\s+)?INDEX)\s`)
var dropIndexRe = regexp.MustCompile(`^DROP\s+INDEX\s`)

// indexCreate — `CREATE [UNIQUE] INDEX CONCURRENTLY` — the concurrently
// variant of a missing index (5.6). The statement runs outside a
// transaction (nonTransactional) under a SHARE UPDATE EXCLUSIVE lock;
// on a partitioned parent it degrades to the plain transactional form.
func indexCreate(id model.ObjectId, want model.SchemaObject,
	snapshot model.SchemaSnapshot, qns string, reason string,
	options spi.RenderOptions, safety model.ChangeSafety) (model.PlannedChange, error) {
	stmts, err := createStatements(want, qns)
	if err != nil {
		return model.PlannedChange{}, err
	}
	if !concurrentIndex(id, snapshot, options) {
		return pc(0, id, model.ChangeCreate, safety, stmts,
			defOf(want), reason, false, indexBuild), nil
	}
	for i, s := range stmts {
		stmts[i] = concurrentlyRe.ReplaceAllString(s, "${1} CONCURRENTLY ")
	}
	return pc(0, id, model.ChangeCreate, safety, stmts,
		defOf(want), reason, true, indexConcurrent), nil
}

// indexDrop — `DROP INDEX CONCURRENTLY` — the concurrently variant of
// an index drop, whether from a drifted recreate pair or a prune of an
// unexpected index.
func indexDrop(id model.ObjectId, have model.SchemaObject,
	snapshot model.SchemaSnapshot, qns string, reason string,
	options spi.RenderOptions) (model.PlannedChange, error) {
	drop, err := bindObj(have, AttrDropSQL, qns)
	if err != nil {
		return model.PlannedChange{}, err
	}
	if !concurrentIndex(id, snapshot, options) {
		return pc(0, id, model.ChangeDrop, model.SafetyRebuild,
			[]string{drop}, nil, reason, false, lockExclusive), nil
	}
	return pc(0, id, model.ChangeDrop, model.SafetyRebuild,
		[]string{dropIndexRe.ReplaceAllString(drop, "DROP INDEX CONCURRENTLY ")},
		nil, reason, true, indexConcurrent), nil
}

// indexRecreate is the DROP+CREATE pair of a drifted index — concurrent
// when allowed.
func indexRecreate(id model.ObjectId, want model.SchemaObject,
	snapshot model.SchemaSnapshot, qns string, reason string,
	options spi.RenderOptions) ([]model.PlannedChange, error) {
	if !concurrentIndex(id, snapshot, options) {
		return recreate(id, want, qns, reason, false, indexBuild)
	}
	d, err := indexDrop(id, want, snapshot, qns, reason, options)
	if err != nil {
		return nil, err
	}
	c, err := indexCreate(id, want, snapshot, qns, reason, options,
		model.SafetyRebuild)
	if err != nil {
		return nil, err
	}
	return []model.PlannedChange{d, c}, nil
}

// ---- RENAME (5.5.1) ------------------------------------------------------

// renameSql — an `ALTER ... RENAME` statement per kind; returns ""
// when the kind has no rename form — the caller falls back to
// drop+create.
func renameSql(from, to model.ObjectId) (string, error) {
	qFromName, err := Quote(from.Name)
	if err != nil {
		return "", err
	}
	qFrom := nsToken + "." + qFromName
	qNew, err := Quote(to.Name)
	if err != nil {
		return "", err
	}
	qParent := ""
	if from.Parent != "" {
		qp, err := Quote(from.Parent)
		if err != nil {
			return "", err
		}
		qParent = nsToken + "." + qp
	}
	switch from.Kind {
	case model.KindTable, model.KindPartition:
		return "ALTER TABLE " + qFrom + " RENAME TO " + qNew, nil
	case model.KindForeignTable:
		return "ALTER FOREIGN TABLE " + qFrom + " RENAME TO " + qNew, nil
	case model.KindView:
		return "ALTER VIEW " + qFrom + " RENAME TO " + qNew, nil
	case model.KindMaterializedView:
		return "ALTER MATERIALIZED VIEW " + qFrom + " RENAME TO " + qNew, nil
	case model.KindSequence:
		return "ALTER SEQUENCE " + qFrom + " RENAME TO " + qNew, nil
	case model.KindIndex:
		return "ALTER INDEX " + qFrom + " RENAME TO " + qNew, nil
	case model.KindType, model.KindDomain:
		return "ALTER TYPE " + qFrom + " RENAME TO " + qNew, nil
	case model.KindStatistics:
		return "ALTER STATISTICS " + qFrom + " RENAME TO " + qNew, nil
	case model.KindCollation:
		return "ALTER COLLATION " + qFrom + " RENAME TO " + qNew, nil
	case model.KindConversion:
		return "ALTER CONVERSION " + qFrom + " RENAME TO " + qNew, nil
	case model.KindColumn:
		return "ALTER TABLE " + qParent + " RENAME COLUMN " +
			qFromName + " TO " + qNew, nil
	case model.KindPrimaryKey, model.KindUnique, model.KindForeignKey,
		model.KindCheck, model.KindExclusion:
		return "ALTER TABLE " + qParent + " RENAME CONSTRAINT " +
			qFromName + " TO " + qNew, nil
	case model.KindTrigger:
		return "ALTER TRIGGER " + qFromName + " ON " + qParent +
			" RENAME TO " + qNew, nil
	case model.KindPolicy:
		return "ALTER POLICY " + qFromName + " ON " + qParent +
			" RENAME TO " + qNew, nil
	case model.KindRoutine, model.KindAggregate:
		// the signature rides the name — ALTER FUNCTION
		// <ns>.<name>(<sig>) RENAME TO <newname>
		sig := signatureOf(from.Name)
		word := "FUNCTION"
		if from.Kind == model.KindAggregate {
			word = "AGGREGATE"
		}
		qBase, err := Quote(baseName(from.Name))
		if err != nil {
			return "", err
		}
		qNewBase, err := Quote(baseName(to.Name))
		if err != nil {
			return "", err
		}
		return "ALTER " + word + " " + nsToken + "." + qBase + sig +
			" RENAME TO " + qNewBase, nil
	default:
		return "", nil
	}
}

// signatureOf is the `(args)` tail of a routine id name, or "".
func signatureOf(name string) string {
	if p := strings.Index(name, "("); p >= 0 {
		return name[p:]
	}
	return ""
}

// baseName is the name without a `(args)` signature tail.
func baseName(name string) string {
	if p := strings.Index(name, "("); p >= 0 {
		return name[:p]
	}
	return name
}

// RenderRename — 5.5.1: an explicit rename hint renders
// `ALTER ... RENAME` as a single ALTER change targeting the NEW
// name — a rename is a metadata-only, data-safe operation (REBUILD).
// The change's expected definition is the actual one (a rename does not
// alter the definition); when the desired definition also changed, the
// plan adds a following DRIFTED change on the new name.
func RenderRename(from, to model.ObjectId,
	actual, desired model.SchemaSnapshot,
	targetNamespace string) ([]model.PlannedChange, error) {
	sql, err := renameSql(from, to)
	if err != nil {
		return nil, err
	}
	if sql == "" {
		return nil, core.NewPlanningErrorf("rename of %s %s is not supported",
			from.Kind, from)
	}
	qns, err := Quote(targetNamespace)
	if err != nil {
		return nil, err
	}
	have := actual.Objects[from]
	d := have.Definition
	return []model.PlannedChange{pc(0, to, model.ChangeAlter, model.SafetyRebuild,
		[]string{bind(sql, qns)}, &d,
		"renamed "+from.String()+" to "+to.String(),
		false, lockExclusive)}, nil
}

// ---- SPI helpers -------------------------------------------------------

// CoveredChildren — child objects whose creation a CREATE of their
// parent covers (columns fold into CREATE TABLE, owned sequences into
// the serial path).
func CoveredChildren(created model.Difference,
	desired model.SchemaSnapshot) []model.ObjectId {
	if created.ID.Kind != model.KindTable &&
		created.ID.Kind != model.KindPartition &&
		created.ID.Kind != model.KindForeignTable {
		return nil
	}
	table := created.ID
	var out []model.ObjectId
	for _, o := range desired.Objects {
		if (o.ID.Kind == model.KindColumn && o.ID.Parent == table.Name) ||
			(o.ID.Kind == model.KindSequence && o.DependsOn.Has(table)) {
			out = append(out, o.ID)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Compare(out[j]) < 0 })
	return out
}

// relationNamespaceOthers — OTHER categories whose stripped names occupy
// the relation/type namespace — the relkind-prefixed relations plus
// `type:` (a standalone type collides with a table's rowtype). Must
// match the prefixes the catalog assigns to those objects.
var relationNamespaceOthers = map[string]bool{
	"inherited": true, "partindex": true, "toast": true,
	"relation": true, "type": true}

// NameScopes is the collision scopes an id's name occupies (spec 5.4.2).
func NameScopes(id model.ObjectId) []string {
	switch id.Kind {
	case model.KindOther:
		name := id.Name
		if colon := strings.Index(name, ":"); colon > 0 {
			category := name[:colon]
			stripped := name[colon+1:]
			if relationNamespaceOthers[category] {
				return []string{"relation:" + stripped}
			}
			// unsupported routines share pg_proc with managed
			// routines, keyed by name plus signature
			if category == "routine" {
				return []string{"routine:" + stripped}
			}
			// an unsupported constraint (a constraint trigger) is
			// named "<parent>.<name>" like a managed one
			if category == "constraint" {
				return []string{"constraint:" + stripped}
			}
		}
		return []string{"relation:" + name}
	// types and domains share the type namespace, which collides
	// with the relation namespace through composite-type rowtypes
	case model.KindTable, model.KindView, model.KindMaterializedView,
		model.KindSequence, model.KindIndex, model.KindType,
		model.KindDomain, model.KindPartition, model.KindForeignTable:
		return []string{"relation:" + id.Name}
	case model.KindRoutine, model.KindAggregate:
		return []string{"routine:" + id.Name}
	case model.KindPrimaryKey, model.KindUnique, model.KindExclusion:
		return []string{"relation:" + id.Name,
			"constraint:" + id.Parent + "." + id.Name}
	case model.KindForeignKey, model.KindCheck:
		return []string{"constraint:" + id.Parent + "." + id.Name}
	default:
		return nil
	}
}

func mapsEqual(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		bv, ok := b[k]
		if !ok || bv != v {
			return false
		}
	}
	return true
}

func mapsCopy(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

func arrayEquals(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i, v := range a {
		if v != b[i] {
			return false
		}
	}
	return true
}

func containsStr(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}
