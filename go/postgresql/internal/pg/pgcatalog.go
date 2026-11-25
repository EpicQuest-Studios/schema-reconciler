package pg

import (
	"context"
	"fmt"
	"sort"
	"strconv"
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql/internal/queries"
)

// The catalog snapshot reader of spec A.5. Reads with `search_path`
// bound to the namespace so engine-rendered definitions omit its
// qualifier; any remaining qualifier is tokenised by the 5.3
// normaliser.

// ReadSnapshot loads the namespace's objects (spec A.5).
func ReadSnapshot(ctx context.Context, conn spi.Conn, namespace string) (model.SchemaSnapshot, error) {
	qNs, err := Quote(namespace)
	if err != nil {
		return model.SchemaSnapshot{}, err
	}
	if _, err := conn.ExecContext(ctx, "SET LOCAL search_path = "+qNs); err != nil {
		return model.SchemaSnapshot{}, err
	}
	d := newData(namespace, qNs)
	if meta, err := conn.Metadata(ctx); err == nil {
		d.serverMajor = meta.DatabaseMajorVersion
	}
	loaders := []func(context.Context, spi.Conn) error{
		d.loadExtensionOwned, d.loadAutoTypeFamily, d.loadRelations,
		d.loadColumns, d.loadConstraints, d.loadIndexes, d.loadViews,
		d.loadSequences, d.loadRoutines, d.loadAggregates, d.loadTriggers,
		d.loadEnums, d.loadDomains, d.loadComposites, d.loadRanges,
		d.loadPolicies, d.loadStatistics, d.loadRules, d.loadCollations,
		d.loadConversions, d.loadTextSearch, d.loadOperators,
		d.loadSecLabels, d.loadOther, d.loadComments, d.loadDependencies,
		d.loadExternalDependents}
	for _, load := range loaders {
		if err := load(ctx, conn); err != nil {
			return model.SchemaSnapshot{}, err
		}
	}
	d.depInvalidPartitionedIndexesOnPartitions()
	d.computeDumpSql()
	return d.snapshot()
}

// row is one catalog row as a column-name → value map.
type row map[string]any

// queryRows runs a named catalog query and materialises its rows —
// the Go analog of conn.query<Row>(PgQueries.get(name), params).
func queryRows(ctx context.Context, conn spi.Conn, name string, args ...any) ([]row, error) {
	sql, err := queries.Get(name)
	if err != nil {
		return nil, err
	}
	rows, err := conn.QueryContext(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	cols, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	var out []row
	for rows.Next() {
		vals := make([]any, len(cols))
		ptrs := make([]any, len(cols))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return nil, err
		}
		r := make(row, len(cols))
		for i, c := range cols {
			out := vals[i]
			if b, ok := out.([]byte); ok {
				out = string(b)
			}
			r[c] = out
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// int8/oid columns come back as strings from some drivers — parse
// explicitly.
func num(v any) int64 {
	switch t := v.(type) {
	case nil:
		return 0
	case int64:
		return t
	case int:
		return int64(t)
	case float64:
		return int64(t)
	case string:
		n, _ := strconv.ParseInt(t, 10, 64)
		return n
	case bool:
		if t {
			return 1
		}
		return 0
	default:
		return 0
	}
}

func numOrNull(v any) (int64, bool) {
	if v == nil {
		return 0, false
	}
	return num(v), true
}

func str(v any) string {
	if v == nil {
		return ""
	}
	return fmt.Sprint(v)
}

func strOrNull(v any) (string, bool) {
	if v == nil {
		return "", false
	}
	return str(v), true
}

func boolOf(v any) bool {
	return v == true || v == "t" || v == "true"
}

func identitySqlOptions(r row) string {
	if r["seqincrement"] == nil {
		return ""
	}
	return "INCREMENT BY " + str(r["seqincrement"]) +
		" MINVALUE " + str(r["seqmin"]) +
		" MAXVALUE " + str(r["seqmax"]) +
		" START WITH " + str(r["seqstart"]) +
		" CACHE " + str(r["seqcache"]) +
		map[bool]string{true: " CYCLE", false: " NO CYCLE"}[boolOf(r["seqcycle"])]
}

func identityCanonical(r row) string {
	if r["seqincrement"] == nil {
		return ""
	}
	return "type=" + str(r["idtype"]) +
		";increment=" + str(r["seqincrement"]) +
		";min=" + str(r["seqmin"]) +
		";max=" + str(r["seqmax"]) +
		";start=" + str(r["seqstart"]) +
		";cache=" + str(r["seqcache"]) +
		";cycle=" + strconv.FormatBool(boolOf(r["seqcycle"]))
}

func reloptions(text string, ok bool) []string {
	if !ok || len(text) < 3 {
		return nil
	}
	inner := text[1 : len(text)-1]
	seen := make(map[string]bool)
	var out []string
	for _, s := range strings.Split(inner, ",") {
		s = strings.TrimSpace(s)
		if !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	sort.Strings(out)
	return out
}

func nsParams(ns string, count int) []any {
	out := make([]any, count)
	for i := range out {
		out[i] = ns
	}
	return out
}

// textArray decodes a `text[]` value: `'{a,"b,c","d""e"}'` → elements
// (or a driver-parsed array).
func textArray(v any) []string {
	if v == nil {
		return nil
	}
	switch arr := v.(type) {
	case []string:
		return arr
	case []any:
		out := make([]string, len(arr))
		for i, x := range arr {
			out[i] = fmt.Sprint(x)
		}
		return out
	}
	s := fmt.Sprint(v)
	if !strings.HasPrefix(s, "{") || !strings.HasSuffix(s, "}") {
		return []string{s}
	}
	if s == "{}" {
		return nil
	}
	inner := s[1 : len(s)-1]
	var out []string
	for i := 0; i < len(inner); {
		if inner[i] == '"' {
			var el strings.Builder
			i++
			for i < len(inner) && inner[i] != '"' {
				if inner[i] == '\\' {
					i++
				}
				el.WriteByte(inner[i])
				i++
			}
			out = append(out, el.String())
			i++
		} else {
			comma := strings.IndexByte(inner[i:], ',')
			end := len(inner)
			if comma >= 0 {
				end = i + comma
			}
			out = append(out, inner[i:end])
			i = end
		}
		for i < len(inner) && inner[i] == ',' {
			i++
		}
	}
	return out
}

// commentId — the mirrored COMMENT id for a managed target, with the
// optional name suffix used to break collisions.
func commentId(target model.ObjectId, suffix string) model.ObjectId {
	return model.ObjectId{Kind: model.KindComment,
		Name: target.Name + suffix, Parent: target.Parent}
}

// seclabelId — the mirrored SECURITY_LABEL id for a managed target and
// provider, with the optional collision suffix.
func seclabelId(target model.ObjectId, provider, suffix string) model.ObjectId {
	return model.ObjectId{Kind: model.KindSecurityLabel,
		Name: provider + ":" + target.Name + suffix, Parent: target.Parent}
}

// ownershipId — the mirrored OWNERSHIP id for a managed target, with
// the optional collision suffix.
func ownershipId(target model.ObjectId, suffix string) model.ObjectId {
	return model.ObjectId{Kind: model.KindOwnership,
		Name: target.Name + suffix, Parent: target.Parent}
}

// privilegesId — the mirrored PRIVILEGES id for a managed target, with
// the optional collision suffix.
func privilegesId(target model.ObjectId, suffix string) model.ObjectId {
	return model.ObjectId{Kind: model.KindPrivileges,
		Name: target.Name + suffix, Parent: target.Parent}
}

// defaultAclClass — the GRANT object-class word of a pg_default_acl
// objtype — `r` tables, `S` sequences, `f` functions and
// procedures, `T` types. An unknown future objtype renders no
// statement and is skipped.
func defaultAclClass(objtype string) string {
	switch objtype {
	case "r":
		return "TABLES"
	case "S":
		return "SEQUENCES"
	case "f":
		return "FUNCTIONS"
	case "T":
		return "TYPES"
	default:
		return ""
	}
}

// managedAnchor — a managed namespace object an anchored catalog row
// can belong to — a missing or OTHER anchor leaves the row unsupported.
func managedAnchor(id *model.ObjectId) bool {
	return id != nil && id.Kind != model.KindOther
}

// otherAnchor — an in-namespace OTHER anchor — the row references an
// unsupported object, so it can never be created.
func otherAnchor(id *model.ObjectId) bool {
	return id != nil && id.Kind == model.KindOther
}

// privilegeDumpStatements — the GRANT list of a PRIVILEGES object: one
// statement per `grantee:PRIVILEGE[*]` token of the canonical — a
// bootstrap dump replays the grants the way the reconcile diff renders
// them. Implicit `<owner>` rights are not grants and are skipped.
func privilegeDumpStatements(o *objData) ([]string, error) {
	target, ok := o.attrs[AttrGrantTarget]
	if !ok {
		return nil, nil
	}
	colClause := ""
	if column, ok := o.attrs[AttrGrantColumn]; ok {
		colClause = " (" + column + ")"
	}
	pfx := ""
	if prefix, ok := o.attrs[AttrGrantPrefix]; ok {
		pfx = prefix + " "
	}
	c := ""
	if o.def != nil {
		c = string(*o.def)
	}
	c = strings.TrimPrefix(c, "privileges=")
	var grants []string
	if c != "" {
		for _, tok := range SplitOutsideLiterals(c, ',') {
			colon := strings.LastIndex(tok, ":")
			g := tok[:colon]
			if g == "<owner>" {
				continue
			}
			p := tok[colon+1:]
			option := strings.HasSuffix(p, "*")
			if option {
				p = p[:len(p)-1]
			}
			var gs string
			if g == "PUBLIC" || strings.HasPrefix(g, "\"") {
				gs = g
			} else {
				var err error
				gs, err = Quote(g)
				if err != nil {
					return nil, err
				}
			}
			suffix := ""
			if option {
				suffix = " WITH GRANT OPTION"
			}
			grants = append(grants, pfx+"GRANT "+p+colClause+" ON "+target+
				" TO "+gs+suffix)
		}
	}
	return grants, nil
}

func fragments(o *objData, keys ...string) []string {
	var out []string
	for _, key := range keys {
		if sql, ok := o.attrs[key]; ok {
			out = append(out, sql)
		}
	}
	return out
}

// canonicalField is a scalar `name=value` field of a canonical
// definition.
func canonicalField(canonical, name string) string {
	for _, part := range SplitOutsideLiterals(canonical, ';') {
		if strings.HasPrefix(part, name+"=") {
			return part[len(name)+1:]
		}
	}
	return ""
}

func nullToDash(v string, ok bool) string {
	if !ok || strings.TrimSpace(v) == "" || v == "0" || v == "-" {
		return "-"
	}
	return v
}

// aggOpt adds a `KEY = value` aggregate option when non-empty.
func aggOpt(options []string, key string, value string, ok bool) []string {
	if ok {
		return append(options, key+" = "+value)
	}
	return options
}

// aggModify — `FINALFUNC_MODIFY`/`MFINALFUNC_MODIFY` — `'r'`
// (read-only) is the default and stays implicit.
func aggModify(options []string, key string, value string, ok bool) []string {
	switch {
	case ok && value == "w":
		return append(options, key+" = READ_WRITE")
	case ok && value == "s":
		return append(options, key+" = SHAREABLE")
	default:
		return options
	}
}

// objData accumulates one object's definition and attributes.
type objData struct {
	def   *model.Definition
	attrs map[string]string
}

// catalogData accumulates rows while reading, then assembles them into
// SchemaObjects — the TypeScript `Data` class.
type catalogData struct {
	ns  string
	qNs string

	serverMajor int
	objects     map[model.ObjectId]*objData
	relByOid    map[int64]model.ObjectId
	procByOid   map[int64]model.ObjectId
	conByOid    map[int64]model.ObjectId
	// conByIndexOid — backing-index oid → the constraint it backs
	// (pk/unique constraint deps).
	conByIndexOid map[int64]model.ObjectId
	typeByOid     map[int64]model.ObjectId
	polByOid      map[int64]model.ObjectId
	tgByOid       map[int64]model.ObjectId
	stxByOid      map[int64]model.ObjectId
	ruleByOid     map[int64]model.ObjectId
	convByOid     map[int64]model.ObjectId
	collByOid     map[int64]model.ObjectId
	langByOid     map[int64]model.ObjectId
	tmplByOid     map[int64]model.ObjectId
	prsByOid      map[int64]model.ObjectId
	dictByOid     map[int64]model.ObjectId
	cfgByOid      map[int64]model.ObjectId
	oprByOid      map[int64]model.ObjectId
	opcByOid      map[int64]model.ObjectId
	opfByOid      map[int64]model.ObjectId
	// otherByOid — catalog-row oid → the OTHER object it loaded as, per
	// classid, so an unsupported object that depends on a managed one
	// still records the edge and blocks an unrecreatable drop (5.8.3).
	otherByOid map[string]map[int64]model.ObjectId
	deps       map[model.ObjectId]model.ObjectSet
	// extOwned — catalog oids owned by an extension (pg_depend deptype
	// 'e'). Member objects cannot be created or dropped standalone, so
	// they never enter the model; the extension itself is reported as
	// OTHER.
	extOwned map[int64]bool
	// autoProcs/autoCasts — a range type's generated members (its
	// multirange and the constructor routines and `range→multirange`
	// cast recorded with `deptype 'i'`) fold into the range like a
	// table's rowtype — they are never objects, their oids remap to the
	// `TYPE` id so dependents still order after the range.
	autoProcs map[int64]int64
	autoCasts map[int64]int64
	// foldedMembers — oids that must never resolve to an annotation/acl
	// target: a comment or grant on a folded member has no renderable
	// target (GRANT on a multirange is even rejected by the server).
	foldedMembers map[int64]bool
	seqOptions    map[model.ObjectId]string
	seqOwnedBy    map[model.ObjectId]string
}

func newData(ns, qNs string) *catalogData {
	return &catalogData{
		ns: ns, qNs: qNs,
		objects:       make(map[model.ObjectId]*objData),
		relByOid:      make(map[int64]model.ObjectId),
		procByOid:     make(map[int64]model.ObjectId),
		conByOid:      make(map[int64]model.ObjectId),
		conByIndexOid: make(map[int64]model.ObjectId),
		typeByOid:     make(map[int64]model.ObjectId),
		polByOid:      make(map[int64]model.ObjectId),
		tgByOid:       make(map[int64]model.ObjectId),
		stxByOid:      make(map[int64]model.ObjectId),
		ruleByOid:     make(map[int64]model.ObjectId),
		convByOid:     make(map[int64]model.ObjectId),
		collByOid:     make(map[int64]model.ObjectId),
		langByOid:     make(map[int64]model.ObjectId),
		tmplByOid:     make(map[int64]model.ObjectId),
		prsByOid:      make(map[int64]model.ObjectId),
		dictByOid:     make(map[int64]model.ObjectId),
		cfgByOid:      make(map[int64]model.ObjectId),
		oprByOid:      make(map[int64]model.ObjectId),
		opcByOid:      make(map[int64]model.ObjectId),
		opfByOid:      make(map[int64]model.ObjectId),
		otherByOid:    make(map[string]map[int64]model.ObjectId),
		deps:          make(map[model.ObjectId]model.ObjectSet),
		extOwned:      make(map[int64]bool),
		autoProcs:     make(map[int64]int64),
		autoCasts:     make(map[int64]int64),
		foldedMembers: make(map[int64]bool),
		seqOptions:    make(map[model.ObjectId]string),
		seqOwnedBy:    make(map[model.ObjectId]string),
	}
}

func (d *catalogData) obj(id model.ObjectId) *objData {
	o := d.objects[id]
	if o == nil {
		o = &objData{attrs: make(map[string]string)}
		d.objects[id] = o
	}
	return o
}

func (d *catalogData) define(id model.ObjectId, canonical string,
	attrs map[string]string) {
	o := d.obj(id)
	def := model.Definition(Normalize(canonical, d.ns))
	o.def = &def
	for k, v := range attrs {
		o.attrs[k] = Normalize(v, d.ns)
	}
}

func (d *catalogData) dep(from *model.ObjectId, to *model.ObjectId) {
	if from == nil || to == nil || *to == *from {
		return
	}
	set := d.deps[*from]
	if set == nil {
		set = model.ObjectSet{}
		d.deps[*from] = set
	}
	set.Add(*to)
}

func (d *catalogData) depID(from model.ObjectId, to *model.ObjectId) {
	d.dep(&from, to)
}

func (d *catalogData) otherOid(cls string, oid int64, id model.ObjectId) {
	m := d.otherByOid[cls]
	if m == nil {
		m = make(map[int64]model.ObjectId)
		d.otherByOid[cls] = m
	}
	m[oid] = id
}

// byOid resolves a catalog oid to the snapshot object it names, if it
// is in this namespace.
func (d *catalogData) byOid(oid int64, ok bool) *model.ObjectId {
	if !ok {
		return nil
	}
	for _, m := range []map[int64]model.ObjectId{
		d.relByOid, d.procByOid, d.typeByOid, d.langByOid, d.tmplByOid,
		d.prsByOid, d.dictByOid, d.cfgByOid, d.oprByOid, d.opcByOid,
		d.opfByOid} {
		if id, found := m[oid]; found {
			return &id
		}
	}
	return nil
}

func (d *catalogData) depOid(from *model.ObjectId, refOid int64, ok bool) {
	d.dep(from, d.byOid(refOid, ok))
}

// depTableForCoveredChild propagates a covered column's dependency up
// to its parent table so that a `CREATE TABLE` change still orders
// after the referenced object. Skipped when the referenced object
// already depends on the table — a serial-owned sequence, for
// example — because a mutual edge would read as a dependency cycle.
func (d *catalogData) depTableForCoveredChild(table model.ObjectId, ref *model.ObjectId) {
	if ref != nil {
		if s := d.deps[*ref]; s != nil && s.Has(table) {
			return
		}
		d.dep(&table, ref)
	}
}

// ---- loads ------------------------------------------------------

func (d *catalogData) extMember(oid int64) bool { return d.extOwned[oid] }

func (d *catalogData) loadExtensionOwned(ctx context.Context, conn spi.Conn) error {
	// relations, routines and types dominate the member count (a
	// PostGIS install owns thousands), so only those in this
	// namespace are read; other classes are few and read whole
	rows, err := queryRows(ctx, conn, "extension_owned", d.ns, d.ns, d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		d.extOwned[num(r["objid"])] = true
	}
	return nil
}

// loadAutoTypeFamily — a range type's generated family: the
// `typtype 'm'` multirange, the constructor routines and the
// `range→multirange` cast, all internally bound to the range
// (`pg_depend deptype 'i'`). They fold into the managed
// `TYPE` like a table's rowtype — never objects of their own;
// their oids remap to the range so dependents and annotations on
// the family still resolve. Runs before every other type and
// routine loader so the oid maps are populated early.
func (d *catalogData) loadAutoTypeFamily(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "range_types", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		id := model.ObjectId{Kind: model.KindType, Name: str(r["typname"])}
		d.typeByOid[oid] = id
		mr := num(r["rngmultitypid"])
		d.typeByOid[mr] = id
		d.foldedMembers[mr] = true
	}
	rows, err = queryRows(ctx, conn, "auto_type_procs", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		d.autoProcs[num(r["objid"])] = num(r["rng"])
		d.foldedMembers[num(r["objid"])] = true
	}
	rows, err = queryRows(ctx, conn, "auto_type_casts", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		d.autoCasts[num(r["objid"])] = num(r["rng"])
		d.foldedMembers[num(r["objid"])] = true
	}
	return nil
}

func (d *catalogData) loadRelations(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "relations", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["relname"])
		kind := str(r["relkind"])
		persistence := str(r["relpersistence"])
		options := reloptions(str(r["options"]), r["options"] != nil)
		parentkinds, _ := strOrNull(r["parentkinds"])
		inherited := len(parentkinds) > 0
		// a plain table whose parents are also plain tables is a
		// classic-inheritance child — not faithfully creatable
		// as a standalone TABLE, so it stays OTHER. A partition
		// child ('r' under a 'p' parent) is managed as PARTITION
		// parented on the partitioned table; the 'p' parent
		// itself is a TABLE whose canonical carries the key.
		var id *model.ObjectId
		switch kind {
		case "r", "p":
			switch {
			case !inherited:
				id = &model.ObjectId{Kind: model.KindTable, Name: name}
			case strings.Contains(parentkinds, "p"):
				id = &model.ObjectId{Kind: model.KindPartition,
					Name: name, Parent: str(r["pparent"])}
			default:
				id = &model.ObjectId{Kind: model.KindOther,
					Name: "inherited:" + name}
			}
		case "v":
			id = &model.ObjectId{Kind: model.KindView, Name: name}
		case "S":
			id = &model.ObjectId{Kind: model.KindSequence, Name: name}
		// managed: canonical definition lands in loadViews
		case "m":
			id = &model.ObjectId{Kind: model.KindMaterializedView, Name: name}
		case "f":
			id = &model.ObjectId{Kind: model.KindForeignTable, Name: name}
		// a documented category prefix per relkind (3.3):
		// tolerating one non-managed relation kind must
		// not exempt the others
		// a standalone composite's 'c' row is the type's
		// backing — the managed TYPE object claims it in
		// loadComposites
		case "c":
			id = nil
		case "t":
			id = &model.ObjectId{Kind: model.KindOther, Name: "toast:" + name}
		default:
			id = &model.ObjectId{Kind: model.KindOther, Name: "relation:" + name}
		}
		if id == nil {
			continue
		}
		d.relByOid[oid] = *id
		// the companion composite rowtype — a column typed as
		// the relation resolves the pg_type oid, so it maps to
		// the same object (OTHER rowtypes included: an
		// inherited child's rowtype is equally unsupported)
		if typeoid := num(r["typeoid"]); typeoid != 0 {
			if _, has := d.typeByOid[typeoid]; !has {
				d.typeByOid[typeoid] = *id
			}
			if id.Kind == model.KindOther {
				d.otherOid("pg_type", typeoid, *id)
			}
		}
		switch id.Kind {
		case model.KindTable:
			replident, _ := strOrNull(r["relreplident"])
			replindex, hasReplindex := strOrNull(r["replindex"])
			partkey, hasPartkey := strOrNull(r["partkey"])
			attrs := map[string]string{
				AttrTableOptions: strings.Join(options, ", "),
				AttrReplident:    replident,
			}
			if hasPartkey {
				attrs[AttrPartkey] = partkey
			}
			canonical := "relkind=" + kind + ";relpersistence=" + persistence +
				";options=" + strings.Join(options, ",") +
				";replident=" + replident
			if hasReplindex {
				canonical += ";replindex=" + replindex
			}
			canonical += ";rls=" + strconv.FormatBool(boolOf(r["relrowsecurity"])) +
				";forcerls=" + strconv.FormatBool(boolOf(r["relforcerowsecurity"]))
			if hasPartkey {
				canonical += ";partkey=" + partkey
			}
			d.define(*id, canonical, attrs)
		case model.KindPartition:
			bound, _ := strOrNull(r["partbound"])
			partkey, hasPartkey := strOrNull(r["partkey"])
			qName, err := d.tableRef(name)
			if err != nil {
				return err
			}
			qParent, err := d.tableRef(id.Parent)
			if err != nil {
				return err
			}
			canonical := "bound=" + bound
			create := "CREATE TABLE " + qName + " PARTITION OF " + qParent + " " + bound
			if hasPartkey {
				canonical += ";partkey=" + partkey
				create += " PARTITION BY " + partkey
			}
			d.define(*id, canonical, map[string]string{
				AttrCreateSQL: create,
				AttrDropSQL:   "DROP TABLE " + qName})
		case model.KindForeignTable:
			// the server name is a cluster object reference
			// (4.1): it lives inside the canonical so drift on
			// it reconciles the table, never the server
			server, _ := strOrNull(r["ftserver"])
			ftoptions := reloptions(str(r["ftoptions"]), r["ftoptions"] != nil)
			var optionSql []string
			for _, o := range ftoptions {
				eq := strings.Index(o, "=")
				optionSql = append(optionSql, o[:eq]+" "+sqlLiteral(o[eq+1:]))
			}
			qName, err := d.tableRef(name)
			if err != nil {
				return err
			}
			d.define(*id, "server="+server+
				";ftoptions="+strings.Join(ftoptions, ", ")+
				";options="+strings.Join(options, ","), map[string]string{
				AttrFtServer:  server,
				AttrFtOptions: strings.Join(optionSql, ", "),
				AttrDropSQL:   "DROP FOREIGN TABLE " + qName})
		case model.KindOther:
			canonical := "relkind=" + kind
			if inherited {
				canonical += ";inherited"
			}
			d.define(*id, canonical, nil)
		}
	}
	return nil
}

func (d *catalogData) loadColumns(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "columns", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["reloid"])) {
			continue
		}
		parent := str(r["parent"])
		name := str(r["attname"])
		typ := str(r["typ"])
		notnull := boolOf(r["notnull"])
		generated, _ := strOrNull(r["attgenerated"])
		identity, _ := strOrNull(r["attidentity"])
		defexpr, hasDefexpr := strOrNull(r["defexpr"])
		collation0, hasCollation := strOrNull(r["collation"])
		collation := ""
		if hasCollation {
			collation = Normalize(collation0, d.ns)
		}
		customCollation := num(r["attcollation"]) != 0 &&
			num(r["attcollation"]) != num(r["typcollation"])
		storage, _ := strOrNull(r["attstorage"])
		compression, _ := strOrNull(r["attcompression"])

		canonical := "type=" + typ + ";notnull=" + strconv.FormatBool(notnull)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		sql := qName + " " + typ
		// storage/compression precede COLLATE in the column-def
		// grammar; emitted only when they deviate from the
		// type default (like a custom collation)
		typstorage, _ := strOrNull(r["typstorage"])
		if storage != typstorage {
			word := "EXTENDED"
			switch storage {
			case "e":
				word = "EXTERNAL"
			case "m":
				word = "MAIN"
			case "p":
				word = "PLAIN"
			}
			sql += " STORAGE " + word
		}
		if len(compression) > 0 {
			method := ""
			switch compression {
			case "p":
				method = "pglz"
			case "l":
				method = "lz4"
			case "z":
				method = "zstd"
			}
			if method != "" {
				sql += " COMPRESSION " + method
			}
		}
		if customCollation && hasCollation {
			canonical += ";collation=" + collation
			sql += " COLLATE " + collation
		}
		attrs := map[string]string{
			AttrAttnum:  str(r["attnum"]),
			AttrNotnull: strconv.FormatBool(notnull),
		}
		if len(generated) > 0 {
			canonical += ";generated=" + generated + ":" + defexpr
			sql += " GENERATED ALWAYS AS (" + defexpr + ") "
			if generated == "v" {
				sql += "VIRTUAL"
			} else {
				sql += "STORED"
			}
		} else if len(identity) > 0 {
			sqlOptions := identitySqlOptions(r)
			canonical += ";identity=" + identity +
				";identityOptions=" + identityCanonical(r)
			sql += " GENERATED "
			if identity == "a" {
				sql += "ALWAYS"
			} else {
				sql += "BY DEFAULT"
			}
			sql += " AS IDENTITY"
			if len(sqlOptions) > 0 {
				sql += " (" + sqlOptions + ")"
			}
		} else if hasDefexpr {
			canonical += ";default=" + defexpr
			sql += " DEFAULT " + defexpr
			attrs[AttrDefault] = defexpr
		}
		if notnull {
			sql += " NOT NULL"
		}
		canonical += ";storage=" + storage + ";compression=" + compression
		attrs[AttrColumnSQL] = sql
		d.define(model.ObjectId{Kind: model.KindColumn, Name: name, Parent: parent},
			canonical, attrs)
	}
	return nil
}

func (d *catalogData) loadConstraints(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "constraints", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		contype := str(r["contype"])
		var kind model.ObjectKind
		switch contype {
		case "p":
			kind = model.KindPrimaryKey
		case "u":
			kind = model.KindUnique
		case "f":
			kind = model.KindForeignKey
		case "c":
			kind = model.KindCheck
		case "x":
			kind = model.KindExclusion
		default:
			kind = model.KindOther
		}
		name := str(r["conname"])
		parent := str(r["parent"])
		def := str(r["def"])
		var id model.ObjectId
		if kind == model.KindOther {
			id = model.ObjectId{Kind: kind, Name: "constraint:" + parent + "." + name}
		} else {
			id = model.ObjectId{Kind: kind, Name: name, Parent: parent}
		}
		d.conByOid[oid] = id
		conindid := num(r["conindid"])
		// conindid is the backing index for p/u/x only; on an
		// FK it is the referenced index on the other table.
		if conindid != 0 && kind != model.KindForeignKey && kind != model.KindCheck {
			d.conByIndexOid[conindid] = id
		}
		qParent, err := d.tableRef(parent)
		if err != nil {
			return err
		}
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		attrs := map[string]string{
			AttrCreateSQL: "ALTER TABLE " + qParent + " ADD CONSTRAINT " + qName + " " + def,
			AttrDropSQL:   "ALTER TABLE " + qParent + " DROP CONSTRAINT " + qName,
		}
		if boolOf(r["replident"]) {
			attrs[AttrPostSQL] = "ALTER TABLE " + qParent +
				" REPLICA IDENTITY USING INDEX " + qName
		}
		d.define(id, def, attrs)
		if refoid, ok := numOrNull(r["refoid"]); ok {
			d.depOid(&id, refoid, true)
		}
	}
	return nil
}

func (d *catalogData) loadIndexes(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "indexes", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["relname"])
		relkind := str(r["relkind"])
		parent := str(r["parent"])
		def := str(r["def"])
		valid := boolOf(r["valid"])
		id := model.ObjectId{Kind: model.KindIndex, Name: name, Parent: parent}
		d.relByOid[oid] = id
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		createSQL := def
		if relkind == "I" && valid {
			createSQL = StripIndexOnOnly(def)
		}
		attrs := map[string]string{
			AttrCreateSQL: createSQL,
			AttrDropSQL:   "DROP INDEX " + d.qNs + "." + qName,
		}
		if relkind == "I" {
			attrs[AttrPartitionedIndex] = "true"
		}
		if boolOf(r["replident"]) {
			qParent, err := d.tableRef(parent)
			if err != nil {
				return err
			}
			attrs[AttrPostSQL] = "ALTER TABLE " + qParent +
				" REPLICA IDENTITY USING INDEX " + qName
		}
		if !valid {
			def += ";valid=false"
		}
		d.define(id, def, attrs)
	}
	return nil
}

// depInvalidPartitionedIndexesOnPartitions ties intentionally invalid
// partitioned indexes to partitions of their parent. CREATE INDEX ... ON ONLY
// is order-sensitive: when partitions already exist it stays invalid, while
// creating it before those partitions can converge as valid once they attach.
func (d *catalogData) depInvalidPartitionedIndexesOnPartitions() {
	for id, object := range d.objects {
		if id.Kind != model.KindIndex || object.attrs[AttrPartitionedIndex] != "true" ||
			object.def.Canonical() == "" ||
			!strings.HasSuffix(object.def.Canonical(), ";valid=false") || id.Parent == "" {
			continue
		}
		for partition := range d.objects {
			if partition.Kind == model.KindPartition && partition.Parent == id.Parent {
				d.dep(&id, &partition)
			}
		}
	}
}

func (d *catalogData) loadViews(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "views", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		name := str(r["relname"])
		def := str(r["def"])
		cols, _ := strOrNull(r["cols"])
		options := reloptions(str(r["options"]), r["options"] != nil)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		if str(r["relkind"]) == "m" {
			populated := boolOf(r["relispopulated"])
			create := "CREATE "
			if str(r["relpersistence"]) == "u" {
				create += "UNLOGGED "
			}
			create += "MATERIALIZED VIEW " + qualified
			if len(options) > 0 {
				create += " WITH (" + strings.Join(options, ",") + ")"
			}
			create += " AS " + def
			d.define(model.ObjectId{Kind: model.KindMaterializedView, Name: name},
				"viewdef="+def+";cols="+cols+";options="+strings.Join(options, ",")+
					";relpersistence="+str(r["relpersistence"])+
					";populated="+strconv.FormatBool(populated),
				map[string]string{
					AttrCreateSQL: create,
					AttrDropSQL:   "DROP MATERIALIZED VIEW " + qualified,
					AttrPopulated: strconv.FormatBool(populated)})
		} else {
			id := model.ObjectId{Kind: model.KindView, Name: name}
			create := "CREATE VIEW " + qualified
			if len(options) > 0 {
				create += " WITH (" + strings.Join(options, ",") + ")"
			}
			create += " AS " + def
			d.define(id, "viewdef="+def+";cols="+cols+
				";options="+strings.Join(options, ","), map[string]string{
				AttrCreateSQL: create,
				AttrDropSQL:   "DROP VIEW " + qualified})
		}
	}
	return nil
}

func (d *catalogData) loadSequences(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "sequences", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		name := str(r["relname"])
		id := model.ObjectId{Kind: model.KindSequence, Name: name}
		stype := str(r["stype"])
		inc := str(r["seqincrement"])
		min := str(r["seqmin"])
		max := str(r["seqmax"])
		start := str(r["seqstart"])
		cache := str(r["seqcache"])
		cycle := boolOf(r["seqcycle"])
		options := "AS " + stype + " INCREMENT BY " + inc +
			" MINVALUE " + min + " MAXVALUE " + max +
			" START " + start + " CACHE " + cache
		if cycle {
			options += " CYCLE"
		} else {
			options += " NO CYCLE"
		}
		persistence := str(r["relpersistence"])
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qSeq := d.qNs + "." + qName
		create := "CREATE "
		if persistence == "u" {
			create += "UNLOGGED "
		}
		create += "SEQUENCE " + qSeq + " " + options
		d.seqOptions[id] = "type=" + stype + ";increment=" + inc +
			";min=" + min + ";max=" + max + ";start=" + start +
			";cache=" + cache + ";cycle=" + strconv.FormatBool(cycle) +
			";relpersistence=" + persistence
		// the canonical needs ownership, resolved by the
		// second query — attrs accumulate here, define() runs
		// (and normalizes them) once below
		o := d.obj(id)
		o.attrs[AttrOptionsSQL] = options
		o.attrs[AttrCreateSQL] = create
		o.attrs[AttrDropSQL] = "DROP SEQUENCE " + qSeq
	}
	// serial-style ownership (deptype 'a')
	rows, err = queryRows(ctx, conn, "sequence_owners", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		seq, ok := d.relByOid[num(r["seq_oid"])]
		if !ok {
			continue
		}
		tbl := str(r["tbl"])
		col := str(r["col"])
		sameNs := d.ns == str(r["tblns"])
		qTbl, err := Quote(tbl)
		if err != nil {
			return err
		}
		qCol, err := Quote(col)
		if err != nil {
			return err
		}
		qualifier := "${namespace}"
		if !sameNs {
			qualifier, err = Quote(str(r["tblns"]))
			if err != nil {
				return err
			}
		}
		ownedBy := qualifier + "." + qTbl + "." + qCol
		d.seqOwnedBy[seq] = ownedBy
		if sameNs {
			// the owner may be a PARTITION or an
			// inherited: OTHER, not always a TABLE
			owner, ok := d.relByOid[num(r["tbl_oid"])]
			if !ok {
				owner = model.ObjectId{Kind: model.KindTable, Name: tbl}
			}
			d.dep(&seq, &owner)
		}
	}
	// finish sequence canonicals now that ownership is known
	for id, canonical := range d.seqOptions {
		ownedBy, has := d.seqOwnedBy[id]
		def := canonical
		if has {
			def += ";owned_by=" + ownedBy
		}
		objAttrs := d.objects[id].attrs
		attrs := map[string]string{
			AttrOptionsSQL: objAttrs[AttrOptionsSQL],
			AttrCreateSQL:  objAttrs[AttrCreateSQL],
			AttrDropSQL:    objAttrs[AttrDropSQL],
		}
		if has {
			ref, err := d.seqRef(id)
			if err != nil {
				return err
			}
			attrs[AttrOwnedSQL] = "ALTER SEQUENCE " + ref + " OWNED BY " + ownedBy
		}
		d.define(id, def, attrs)
	}
	return nil
}

func (d *catalogData) loadRoutines(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "routines", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		if rng, ok := d.autoProcs[oid]; ok {
			// a range/multirange constructor folds into its
			// range type — dependents resolve its oid to the
			// TYPE object
			d.procByOid[oid] = d.typeByOid[rng]
			continue
		}
		proname := str(r["proname"])
		prokind := str(r["prokind"])
		sigargs := str(r["sigargs"])
		rettype := str(r["rettype"])
		def := str(r["def"])
		id := model.ObjectId{Kind: model.KindRoutine,
			Name: Normalize(proname+"("+sigargs+")", d.ns)}
		d.procByOid[oid] = id
		qName, err := Quote(proname)
		if err != nil {
			return err
		}
		dropWord := "FUNCTION"
		if prokind == "p" {
			dropWord = "PROCEDURE"
		}
		d.define(id, def, map[string]string{
			AttrCreateSQL:  def,
			AttrDropSQL:    "DROP " + dropWord + " " + d.qNs + "." + qName + "(" + sigargs + ")",
			attrSigArgs:    sigargs,
			AttrProkind:    prokind,
			AttrReturnType: rettype})
	}
	return nil
}

// loadAggregates — aggregates (`prokind 'a'`): `pg_get_functiondef`
// refuses them, so the canonical definition is composed from the
// `pg_aggregate` row — every option that can appear between
// the parentheses, in a fixed order, with `null`/default
// fields omitted. The same option list renders `CREATE
// AGGREGATE`; drift recreates (`DROP AGGREGATE` +
// `CREATE AGGREGATE`) because aggregates have no
// `CREATE OR REPLACE` form. Ordered-set and hypothetical-set
// aggregates ride the same path — `ORDER BY` inside the
// signature and the `HYPOTHETICAL` flag carry their
// difference.
func (d *catalogData) loadAggregates(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "aggregates", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		if _, folded := d.autoProcs[oid]; folded {
			continue
		}
		proname := str(r["proname"])
		idargs := str(r["idargs"])
		var options []string
		kind := str(r["aggkind"])
		if kind == "h" {
			options = append(options, "HYPOTHETICAL")
		}
		options = append(options, "SFUNC = "+str(r["sfunc"]))
		options = append(options, "STYPE = "+str(r["stype"]))
		options = aggOpt(options, "SSPACE", str(r["sspace"]), r["sspace"] != nil)
		options = aggOpt(options, "FINALFUNC", str(r["ffunc"]), r["ffunc"] != nil)
		if boolOf(r["aggfinalextra"]) {
			options = append(options, "FINALFUNC_EXTRA")
		}
		options = aggModify(options, "FINALFUNC_MODIFY", str(r["aggfinalmodify"]), r["aggfinalmodify"] != nil)
		options = aggOpt(options, "COMBINEFUNC", str(r["combine"]), r["combine"] != nil)
		options = aggOpt(options, "SERIALFUNC", str(r["serial"]), r["serial"] != nil)
		options = aggOpt(options, "DESERIALFUNC", str(r["deserial"]), r["deserial"] != nil)
		if r["agginitval"] != nil {
			options = append(options, "INITCOND = '"+
				strings.ReplaceAll(str(r["agginitval"]), "'", "''")+"'")
		}
		options = aggOpt(options, "MSFUNC", str(r["msfunc"]), r["msfunc"] != nil)
		options = aggOpt(options, "MINVFUNC", str(r["minv"]), r["minv"] != nil)
		options = aggOpt(options, "MSTYPE", str(r["mstype"]), r["mstype"] != nil)
		options = aggOpt(options, "MSSPACE", str(r["msspace"]), r["msspace"] != nil)
		options = aggOpt(options, "MFINALFUNC", str(r["mffunc"]), r["mffunc"] != nil)
		if boolOf(r["aggmfinalextra"]) {
			options = append(options, "MFINALFUNC_EXTRA")
		}
		options = aggModify(options, "MFINALFUNC_MODIFY", str(r["aggmfinalmodify"]), r["aggmfinalmodify"] != nil)
		if r["aggminitval"] != nil {
			options = append(options, "MINITCOND = '"+
				strings.ReplaceAll(str(r["aggminitval"]), "'", "''")+"'")
		}
		if r["sortop"] != nil {
			options = append(options, "SORTOP = OPERATOR("+str(r["sortop"])+")")
		}
		opts := strings.Join(options, ", ")
		id := model.ObjectId{Kind: model.KindAggregate,
			Name: Normalize(proname+"("+idargs+")", d.ns)}
		d.procByOid[oid] = id
		qName, err := Quote(proname)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		d.define(id, "prokind=a;kind="+kind+";"+opts, map[string]string{
			AttrCreateSQL: "CREATE AGGREGATE " + qualified +
				"(" + str(r["args"]) + ")" + " (" + opts + ")",
			AttrDropSQL: "DROP AGGREGATE " + qualified + "(" + idargs + ")",
			attrSigArgs: idargs,
			AttrProkind: "a"})
	}
	return nil
}

func (d *catalogData) loadTriggers(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "triggers", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["tgname"])
		parent := str(r["parent"])
		enabled := str(r["tgenabled"])
		def := str(r["def"])
		id := model.ObjectId{Kind: model.KindTrigger, Name: name, Parent: parent}
		d.tgByOid[oid] = id
		qParent, err := d.tableRef(parent)
		if err != nil {
			return err
		}
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		attrs := map[string]string{
			AttrCreateSQL: def,
			AttrDropSQL:   "DROP TRIGGER " + qName + " ON " + qParent,
		}
		if enabled != "O" {
			verb := "ENABLE ALWAYS"
			if enabled == "D" {
				verb = "DISABLE"
			} else if enabled == "R" {
				verb = "ENABLE REPLICA"
			}
			attrs[AttrPostSQL] = "ALTER TABLE " + qParent + " " + verb +
				" TRIGGER " + qName
		}
		d.define(id, def+";enabled="+enabled, attrs)
		d.depOid(&id, num(r["tgfoid"]), true)
	}
	return nil
}

// loadEnums — enum types: the canonical definition is the ordered label
// list; the `typeByOid` entry resolves column and table dependency
// edges to the managed `TYPE` object.
func (d *catalogData) loadEnums(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "enums", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["typname"])
		labels := textArray(r["labels"])
		var lit []string
		for _, l := range labels {
			lit = append(lit, sqlLiteral(l))
		}
		csv := strings.Join(lit, ",")
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		id := model.ObjectId{Kind: model.KindType, Name: name}
		create := "CREATE TYPE " + qualified + " AS ENUM"
		if csv != "" {
			create += " (" + csv + ")"
		}
		d.define(id, "typtype=e;labels="+csv, map[string]string{
			AttrCreateSQL: create,
			AttrDropSQL:   "DROP TYPE " + qualified})
		d.typeByOid[oid] = id
	}
	return nil
}

// loadDomains — domains: the canonical definition is the base type
// (deparsed by `format_type`), the nullability flag, the deparsed
// default expression and the check constraints by name. The
// `typeByOid` entry resolves column and table dependency edges to
// the managed `DOMAIN` object; the base type and the checks'
// referenced routines and types join the dependency graph in
// `loadDependencies`.
func (d *catalogData) loadDomains(ctx context.Context, conn spi.Conn) error {
	checks := make(map[int64][]string)
	rows, err := queryRows(ctx, conn, "domain_checks", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		dom := num(r["dom"])
		checks[dom] = append(checks[dom], str(r["conname"])+"\u0000"+str(r["def"]))
	}
	rows, err = queryRows(ctx, conn, "domains", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["typname"])
		basetype := str(r["basetype"])
		notnull := boolOf(r["typnotnull"])
		dflt, hasDflt := strOrNull(r["dflt"])
		collation, hasColl := strOrNull(r["coll"])
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		id := model.ObjectId{Kind: model.KindDomain, Name: name}
		create := "CREATE DOMAIN " + qualified + " AS " + basetype
		canonical := "basetype=" + basetype + ";notnull=" + strconv.FormatBool(notnull)
		if hasColl {
			canonical += ";collation=" + collation
			create += " COLLATE " + collation
		}
		if hasDflt {
			canonical += ";default=" + dflt
			create += " DEFAULT " + dflt
		}
		if notnull {
			create += " NOT NULL"
		}
		for _, entry := range checks[oid] {
			tab := strings.Index(entry, "\u0000")
			cname := entry[:tab]
			def := entry[tab+1:]
			canonical += ";check." + cname + "=" + def
			qCname, err := Quote(cname)
			if err != nil {
				return err
			}
			create += " CONSTRAINT " + qCname + " " + def
		}
		d.define(id, canonical, map[string]string{
			AttrCreateSQL: create,
			AttrDropSQL:   "DROP DOMAIN " + qualified})
		d.typeByOid[oid] = id
	}
	return nil
}

// loadComposites — standalone composite types
// (`CREATE TYPE n AS (a int, …)`): the canonical definition is the
// ordered attribute list — quoted name, deparsed type and
// non-default collation — in attnum order, since field order is part
// of the type. The twin `relkind 'c'` row is the composite's
// backing relation, not an object of its own; `relByOid` maps it back
// to the type so dependency edges and annotations resolve to the
// managed `TYPE` object. A table's rowtype also carries
// `typtype 'c'`, but its `typrelid` is an `'r'` relation — the
// join keeps only standalone composites.
func (d *catalogData) loadComposites(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "composites", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		relid := num(r["typrelid"])
		if d.extMember(oid) || d.extMember(relid) {
			continue
		}
		name := str(r["typname"])
		attrs, _ := strOrNull(r["attrs"])
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		id := model.ObjectId{Kind: model.KindType, Name: name}
		d.define(id, "typtype=c;attrs="+attrs, map[string]string{
			AttrCreateSQL: "CREATE TYPE " + qualified + " AS (" + attrs + ")",
			AttrDropSQL:   "DROP TYPE " + qualified})
		d.typeByOid[oid] = id
		d.relByOid[relid] = id
	}
	return nil
}

// loadRanges — range types (`CREATE TYPE n AS RANGE (SUBTYPE = t, …)`):
// the canonical definition is the `pg_range` row — subtype,
// non-default collation, subtype operator class, canonical and
// subtype-diff functions. The generated family (multirange,
// constructor routines, the range→multirange cast) folds into the
// range in `loadAutoTypeFamily`; a multirange has no
// standalone create form, so it is never an object of its own.
// `typeByOid` is already populated there.
func (d *catalogData) loadRanges(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "ranges", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["typname"])
		subtype := str(r["subtype"])
		coll, hasColl := strOrNull(r["coll"])
		opc, hasOpc := strOrNull(r["opc"])
		canonicalFn, hasCanon := strOrNull(r["canonical"])
		diff, hasDiff := strOrNull(r["diff"])
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		canon := "typtype=r;subtype=" + subtype
		create := "CREATE TYPE " + qualified + " AS RANGE (SUBTYPE = " + subtype
		if hasOpc {
			canon += ";opclass=" + opc
			create += ", SUBTYPE_OPCLASS = " + opc
		}
		if hasColl {
			canon += ";collation=" + coll
			create += ", COLLATION = " + coll
		}
		if hasCanon {
			canon += ";canonical=" + canonicalFn
			create += ", CANONICAL = " + canonicalFn
		}
		if hasDiff {
			canon += ";subtype_diff=" + diff
			create += ", SUBTYPE_DIFF = " + diff
		}
		create += ")"
		id := model.ObjectId{Kind: model.KindType, Name: name}
		d.define(id, canon, map[string]string{
			AttrCreateSQL: create,
			AttrDropSQL:   "DROP TYPE " + qualified})
		d.typeByOid[oid] = id
	}
	return nil
}

// loadPolicies — row-level-security policies on our tables: a `POLICY`
// object parented on its table. The canonical definition is the
// command, permissive flag, sorted distinct role list (`{0}` renders as
// `PUBLIC`) and the deparsed `USING`/`WITH CHECK`
// expressions. The parent linkage and an explicit dependency edge tie
// the policy to its table; `loadDependencies` adds the
// expressions' referenced routines and types.
func (d *catalogData) loadPolicies(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "policies", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["polname"])
		table := str(r["relname"])
		var roles []string
		for _, role := range textArray(r["roles"]) {
			if role == "PUBLIC" {
				roles = append(roles, role)
			} else {
				q, err := Quote(role)
				if err != nil {
					return err
				}
				roles = append(roles, q)
			}
		}
		using, hasUsing := strOrNull(r["using"])
		wcheck, hasWcheck := strOrNull(r["wcheck"])
		qTable, err := Quote(table)
		if err != nil {
			return err
		}
		qTable = d.qNs + "." + qTable
		id := model.ObjectId{Kind: model.KindPolicy, Name: name, Parent: table}
		polcmd := str(r["polcmd"])
		permissive := boolOf(r["polpermissive"])
		canonical := "cmd=" + polcmd + ";permissive=" +
			strconv.FormatBool(permissive) + ";roles=" + strings.Join(roles, ",")
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		create := "CREATE POLICY " + qName + " ON " + qTable
		if !permissive {
			create += " AS RESTRICTIVE"
		}
		cmdWord := "ALL"
		switch polcmd {
		case "r":
			cmdWord = "SELECT"
		case "a":
			cmdWord = "INSERT"
		case "w":
			cmdWord = "UPDATE"
		case "d":
			cmdWord = "DELETE"
		}
		create += " FOR " + cmdWord + " TO "
		if len(roles) == 0 {
			create += "PUBLIC"
		} else {
			create += strings.Join(roles, ",")
		}
		if hasUsing {
			canonical += ";using=" + using
			create += " USING (" + using + ")"
		}
		if hasWcheck {
			canonical += ";check=" + wcheck
			create += " WITH CHECK (" + wcheck + ")"
		}
		d.define(id, canonical, map[string]string{
			AttrCreateSQL: create,
			AttrDropSQL:   "DROP POLICY " + qName + " ON " + qTable})
		d.polByOid[oid] = id
		if parent, ok := d.relByOid[num(r["polrelid"])]; ok {
			d.dep(&id, &parent)
		}
	}
	return nil
}

// loadStatistics — extended statistics objects (pg_statistic_ext): a
// `STATISTICS` object named by `stxname` — statistics names are unique
// per schema, not per table, so the id is unparented; an explicit
// `dependsOn` edge ties it to its table. The canonical
// definition is `pg_get_statisticsobjdef`, which carries the
// enabled statistic kinds, the column/expression list and the source
// table. `stxstattarget` is a per-column planner property, not
// declarable, so it stays outside the canonical (like attstattarget).
func (d *catalogData) loadStatistics(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "statistics", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["stxname"])
		def := str(r["def"])
		id := model.ObjectId{Kind: model.KindStatistics, Name: name}
		d.stxByOid[oid] = id
		// the unsupported-dependents union resolves edges from
		// expression statistics (function/type refs in
		// stxexprs) through this registration too
		d.otherOid("pg_statistic_ext", oid, id)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		d.define(id, def, map[string]string{
			AttrCreateSQL: def,
			AttrDropSQL:   "DROP STATISTICS " + d.qNs + "." + qName})
		if parent, ok := d.relByOid[num(r["stxrelid"])]; ok {
			d.dep(&id, &parent)
		}
	}
	return nil
}

// loadRules — non-view rewrite rules (pg_rewrite rows other than
// `_RETURN`, which is part of its view): a `RULE` object parented on
// its relation — rule names are unique per table. The canonical
// definition is `pg_get_ruledef` plus the enabled state
// (`ev_enabled`, like a trigger's `tgenabled`); the
// `pg_rewrite`-classid registration feeds the
// unsupported-dependents union, which records the rule's edges to
// its relation and every object the action references.
func (d *catalogData) loadRules(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "rules", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["rulename"])
		parent := str(r["parent"])
		enabled := str(r["ev_enabled"])
		def := str(r["def"])
		id := model.ObjectId{Kind: model.KindRule, Name: name, Parent: parent}
		d.ruleByOid[oid] = id
		d.otherOid("pg_rewrite", oid, id)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qParent, err := d.tableRef(parent)
		if err != nil {
			return err
		}
		attrs := map[string]string{
			AttrCreateSQL: def,
			AttrDropSQL:   "DROP RULE " + qName + " ON " + qParent,
		}
		if enabled != "O" {
			verb := "ENABLE ALWAYS"
			if enabled == "D" {
				verb = "DISABLE"
			} else if enabled == "R" {
				verb = "ENABLE REPLICA"
			}
			attrs[AttrPostSQL] = "ALTER TABLE " + qParent + " " + verb +
				" RULE " + qName
		}
		d.define(id, def+";enabled="+enabled, attrs)
		if rel, ok := d.relByOid[num(r["ev_class"])]; ok {
			d.dep(&id, &rel)
		}
	}
	return nil
}

// loadCollations — collations a desired script can create: libc or ICU
// provider and an encoding either universal (`-1`) or bound to this
// database's own encoding — that is what `CREATE COLLATION` records,
// and a collation bound to a different encoding cannot be recreated
// here (the database-default provider can't either — both stay
// `OTHER`). There is no `pg_get_collationdef`, so the
// canonical definition and the `CREATE COLLATION` fragment are
// composed from the provider fields; `collversion` is
// environment metadata (the libc/ICU library version at creation)
// and deliberately excluded so the same collation does not drift
// across hosts. `colllocale` is named `colliculocale`
// before PostgreSQL 17 — read via `to_json` so both spellings
// resolve.
func (d *catalogData) loadCollations(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "collations", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		name := str(r["collname"])
		icu := str(r["collprovider"]) == "i"
		deterministic := boolOf(r["collisdeterministic"])
		collate := str(r["collcollate"])
		ctype := str(r["collctype"])
		locale := str(r["colllocale"])
		rules, hasRules := strOrNull(r["collicurules"])
		var canonical, options string
		if icu {
			options = "provider = icu, locale = " + sqlLiteral(locale)
			canonical = "provider=icu;locale=" + locale
			if hasRules {
				options += ", rules = " + sqlLiteral(rules)
				canonical += ";rules=" + rules
			}
			if !deterministic {
				options += ", deterministic = false"
				canonical += ";deterministic=false"
			}
			canonical += ";encoding=" + strconv.FormatInt(num(r["collencoding"]), 10)
		} else {
			if collate == ctype {
				options = "locale = " + sqlLiteral(collate)
			} else {
				options = "lc_collate = " + sqlLiteral(collate) +
					", lc_ctype = " + sqlLiteral(ctype)
			}
			canonical = "provider=libc;collate=" + collate +
				";ctype=" + ctype +
				";encoding=" + strconv.FormatInt(num(r["collencoding"]), 10)
		}
		id := model.ObjectId{Kind: model.KindCollation, Name: name}
		d.collByOid[num(r["oid"])] = id
		qName, err := d.tableRef(name)
		if err != nil {
			return err
		}
		d.define(id, canonical, map[string]string{
			AttrCreateSQL: "CREATE COLLATION " + qName + " (" + options + ")",
			AttrDropSQL:   "DROP COLLATION " + qName})
	}
	return nil
}

// loadConversions — conversions between client encodings: there is no
// deparsed definition, so the canonical composes source/target
// encoding names and the conversion function (`regproc`,
// which is the reference form `CREATE CONVERSION ... FROM` accepts).
// `DEFAULT CONVERSION` rows stay `OTHER` — `DEFAULT`
// heads the script check's database-wide list, so a desired script
// can never express the flag. A conversion depends on its function
// when the function is managed.
func (d *catalogData) loadConversions(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "conversions", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		name := str(r["conname"])
		forenc := str(r["forenc"])
		toenc := str(r["toenc"])
		conproc := str(r["procname"])
		id := model.ObjectId{Kind: model.KindConversion, Name: name}
		d.convByOid[num(r["oid"])] = id
		qName, err := d.tableRef(name)
		if err != nil {
			return err
		}
		d.define(id, "from="+forenc+";to="+toenc+";proc="+conproc,
			map[string]string{
				AttrCreateSQL: "CREATE CONVERSION " + qName +
					" FOR " + sqlLiteral(forenc) +
					" TO " + sqlLiteral(toenc) +
					" FROM " + conproc,
				AttrDropSQL: "DROP CONVERSION " + qName})
		if proc, ok := d.procByOid[num(r["conproc"])]; ok {
			d.dep(&id, &proc)
		}
	}
	return nil
}

// tableRef is `"${namespace}".<quoted name>` — a table reference in
// unbound token form.
func (d *catalogData) tableRef(name string) (string, error) {
	q, err := Quote(name)
	if err != nil {
		return "", err
	}
	return "${namespace}." + q, nil
}

func (d *catalogData) seqRef(id model.ObjectId) (string, error) {
	q, err := Quote(id.Name)
	if err != nil {
		return "", err
	}
	return "${namespace}." + q, nil
}

// snapshot assembles the read objects into a SchemaSnapshot.
func (d *catalogData) snapshot() (model.SchemaSnapshot, error) {
	out := make(map[model.ObjectId]model.SchemaObject, len(d.objects))
	for id, o := range d.objects {
		def := model.Definition("")
		if o.def != nil {
			def = *o.def
		}
		out[id] = model.NewSchemaObject(id, def, d.deps[id].Sorted(), o.attrs)
	}
	return model.NewSchemaSnapshot(out), nil
}
