package pg

import (
	"context"
	"sort"
	"strconv"
	"strings"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// loadTextSearch — full-text-search objects — four managed kinds:
// `TEXT_SEARCH_TEMPLATE`, `TEXT_SEARCH_PARSER`,
// `TEXT_SEARCH_DICTIONARY` and `TEXT_SEARCH_CONFIGURATION`.
// No `pg_get_*def` exists for them, so the canonical and the
// `CREATE TEXT SEARCH …` fragment are composed from the catalog
// row (the AGGREGATE pattern): function references as
// `regproc` text, the dictionary's template reference and
// `dictinitoption` verbatim, the configuration's parser
// reference plus the `pg_ts_config_map` token→dictionary chain
// folded into the configuration object (map rows are never objects —
// `ALTER ... ADD MAPPING` fragments ride in `POST_SQL` in
// `mapseqno` order). Token aliases resolve through
// `ts_token_type(cfgparser)`; an unresolvable alias falls back
// to `tok<N>`. Any canonical drift is a DROP+CREATE REBUILD —
// PostgreSQL has no in-place create for the function references.
func (d *catalogData) loadTextSearch(ctx context.Context, conn spi.Conn) error {
	// templates: the two support functions are the whole body
	rows, err := queryRows(ctx, conn, "ts_templates", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["tmplname"])
		init := str(r["init"])
		lexize := str(r["lexize"])
		id := model.ObjectId{Kind: model.KindTextSearchTemplate, Name: name}
		d.tmplByOid[oid] = id
		d.otherOid("pg_ts_template", oid, id)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		opts := "LEXIZE = " + lexize
		if init != "-" {
			opts = "INIT = " + init + ", " + opts
		}
		d.define(id, "ts_template=init:"+init+";lexize:"+lexize, map[string]string{
			AttrCreateSQL: "CREATE TEXT SEARCH TEMPLATE " + d.qNs + "." + qName + " (" + opts + ")",
			AttrDropSQL:   "DROP TEXT SEARCH TEMPLATE " + d.qNs + "." + qName})
		if init != "-" {
			if fn, ok := d.procByOid[num(r["init_oid"])]; ok {
				d.dep(&id, &fn)
			}
		}
		if fn, ok := d.procByOid[num(r["lexize_oid"])]; ok {
			d.dep(&id, &fn)
		}
	}
	// parsers: five support functions; prsheadline is optional ('-')
	rows, err = queryRows(ctx, conn, "ts_parsers", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["prsname"])
		headline := str(r["headline"])
		id := model.ObjectId{Kind: model.KindTextSearchParser, Name: name}
		d.prsByOid[oid] = id
		d.otherOid("pg_ts_parser", oid, id)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		opts := "START = " + str(r["start"]) +
			", GETTOKEN = " + str(r["token"]) +
			", END = " + str(r["end"]) +
			", LEXTYPES = " + str(r["lextype"])
		if headline != "-" {
			opts += ", HEADLINE = " + headline
		}
		d.define(id, "ts_parser=start:"+str(r["start"])+
			";token:"+str(r["token"])+
			";end:"+str(r["end"])+
			";headline:"+headline+
			";lextype:"+str(r["lextype"]), map[string]string{
			AttrCreateSQL: "CREATE TEXT SEARCH PARSER " + d.qNs + "." + qName + " (" + opts + ")",
			AttrDropSQL:   "DROP TEXT SEARCH PARSER " + d.qNs + "." + qName})
		for _, c := range []string{"start_oid", "token_oid",
			"end_oid", "headline_oid", "lextype_oid"} {
			if fn := num(r[c]); fn != 0 {
				if p, ok := d.procByOid[fn]; ok {
					d.dep(&id, &p)
				}
			}
		}
	}
	// dictionaries: template reference + free-form init option text
	rows, err = queryRows(ctx, conn, "ts_dictionaries", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["dictname"])
		tmplref := str(r["tmplref"])
		initopt, hasInitopt := strOrNull(r["dictinitoption"])
		id := model.ObjectId{Kind: model.KindTextSearchDictionary, Name: name}
		d.dictByOid[oid] = id
		d.otherOid("pg_ts_dict", oid, id)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		opts := "TEMPLATE = " + tmplref
		init := "-"
		if hasInitopt && strings.TrimSpace(initopt) != "" {
			opts += ", " + initopt
			init = initopt
		}
		d.define(id, "ts_dict=template:"+tmplref+";init:"+init, map[string]string{
			AttrCreateSQL: "CREATE TEXT SEARCH DICTIONARY " + d.qNs + "." + qName + " (" + opts + ")",
			AttrDropSQL:   "DROP TEXT SEARCH DICTIONARY " + d.qNs + "." + qName})
		if tmpl, ok := d.tmplByOid[num(r["dicttemplate"])]; ok {
			d.dep(&id, &tmpl)
		}
	}
	// configurations: parser reference plus the folded token→dict
	// map chain; each map row renders an ALTER ... ADD MAPPING in
	// mapseqno order inside POST_SQL
	rows, err = queryRows(ctx, conn, "ts_configurations", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["cfgname"])
		prsref := str(r["prsref"])
		id := model.ObjectId{Kind: model.KindTextSearchConfiguration, Name: name}
		d.cfgByOid[oid] = id
		d.otherOid("pg_ts_config", oid, id)
		qName, err := Quote(name)
		if err != nil {
			return err
		}
		qualified := d.qNs + "." + qName
		attrs := map[string]string{
			AttrCreateSQL: "CREATE TEXT SEARCH CONFIGURATION " + qualified + " (PARSER = " + prsref + ")",
			AttrDropSQL:   "DROP TEXT SEARCH CONFIGURATION " + qualified}
		maps, err := d.configMaps(ctx, conn, oid)
		if err != nil {
			return err
		}
		canon := "ts_config=parser:" + prsref
		if len(maps) > 0 {
			var entries, alters []string
			for _, m := range maps {
				entries = append(entries, m.alias+"@"+strconv.FormatInt(m.seq, 10)+"="+m.dictref)
				alters = append(alters, "ALTER TEXT SEARCH CONFIGURATION "+
					qualified+" ADD MAPPING FOR "+m.alias+" WITH "+m.dictref)
				if dict, ok := d.dictByOid[m.dictoid]; ok {
					d.dep(&id, &dict)
				}
			}
			canon += ";map=" + strings.Join(entries, ",")
			attrs[AttrPostSQL] = strings.Join(alters, "; ")
		}
		d.define(id, canon, attrs)
		if prs, ok := d.prsByOid[num(r["cfgparser"])]; ok {
			d.dep(&id, &prs)
		}
	}
	return nil
}

type configMap struct {
	alias   string
	seq     int64
	dictoid int64
	dictref string
}

// configMaps — the `pg_ts_config_map` rows of one configuration,
// ordered by `mapseqno`: token alias (resolved through
// `ts_token_type(cfgparser)`, `tok<N>` when unresolvable),
// sequence number and the dictionary reference as
// `nspname.dictname` — normalized to `${namespace}` by
// `define`'s caller path like every reference.
func (d *catalogData) configMaps(ctx context.Context, conn spi.Conn, cfgoid int64) ([]configMap, error) {
	rows, err := queryRows(ctx, conn, "ts_config_maps", cfgoid)
	if err != nil {
		return nil, err
	}
	var out []configMap
	for _, r := range rows {
		alias, ok := strOrNull(r["alias"])
		if !ok {
			alias = "tok" + strconv.FormatInt(num(r["maptokentype"]), 10)
		}
		out = append(out, configMap{alias: alias,
			seq: num(r["mapseqno"]), dictoid: num(r["mapdict"]),
			dictref: str(r["dictref"])})
	}
	return out, nil
}

// loadOperators — operator objects — three managed kinds: `OPERATOR`
// (name plus its `(leftarg,rightarg)` identity signature like a
// routine, `NONE` for an absent prefix operand),
// `OPERATOR_FAMILY` (name `opfname USING amname` — the
// access method is part of identity) and `OPERATOR_CLASS`
// (same name form; a class's `pg_amop`/`pg_amproc`
// members fold into its canonical like a configuration's map).
// No `pg_get_*def` exists, so canonical and `CREATE`
// fragments are composed from the catalog rows: operator options
// (procedure, argument/result types, commutator, negator,
// restrict/join selectivity, HASHES/MERGES), family options (the
// access method), class options (DEFAULT, FOR TYPE, FAMILY,
// STORAGE, ordered `OPERATOR n`/`FUNCTION n` member
// specs). Member operator references render as
// `regoperator` text (name plus operand types), member
// functions as `regprocedure`. Standalone family members
// are not modeled — `ALTER OPERATOR FAMILY … ADD|DROP`
// stays rejected in the desired-script check. Any drift is a
// DROP+CREATE REBUILD.
func (d *catalogData) loadOperators(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "operators", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		left := num(r["oprleft"])
		right := num(r["oprright"])
		leftt, rightt := "NONE", "NONE"
		if left != 0 {
			leftt = str(r["leftt"])
		}
		if right != 0 {
			rightt = str(r["rightt"])
		}
		name := str(r["oprname"]) + "(" + leftt + "," + rightt + ")"
		id := model.ObjectId{Kind: model.KindOperator, Name: name}
		d.oprByOid[oid] = id
		d.otherOid("pg_operator", oid, id)
		// operator names are symbol strings, not identifiers
		// — double-quoting them is invalid syntax
		qName := d.qNs + "." + str(r["oprname"])
		comref, hasCom := strOrNull(r["comref"])
		negref, hasNeg := strOrNull(r["negref"])
		canon := "operator=" + str(r["result"]) +
			";proc:" + str(r["proc"]) +
			";rest:" + str(r["rest"]) +
			";join:" + str(r["joinsel"]) +
			";hash:" + strconv.FormatBool(boolOf(r["oprcanhash"])) +
			";merge:" + strconv.FormatBool(boolOf(r["oprcanmerge"])) +
			";com:" + nullToDash(comref, hasCom) +
			";neg:" + nullToDash(negref, hasNeg)
		opts := "PROCEDURE = " + str(r["proc"])
		if left != 0 {
			opts += ", LEFTARG = " + leftt
		}
		if right != 0 {
			opts += ", RIGHTARG = " + rightt
		}
		opts += ", RESULT = " + str(r["result"])
		if num(r["oprcom"]) != 0 {
			opts += ", COMMUTATOR = OPERATOR(" + str(r["comref"]) + ")"
		}
		if num(r["oprnegate"]) != 0 {
			opts += ", NEGATOR = OPERATOR(" + str(r["negref"]) + ")"
		}
		if str(r["rest"]) != "-" {
			opts += ", RESTRICT = " + str(r["rest"])
		}
		if str(r["joinsel"]) != "-" {
			opts += ", JOIN = " + str(r["joinsel"])
		}
		if boolOf(r["oprcanhash"]) {
			opts += ", HASHES"
		}
		if boolOf(r["oprcanmerge"]) {
			opts += ", MERGES"
		}
		d.define(id, canon, map[string]string{
			AttrCreateSQL: "CREATE OPERATOR " + qName + " (" + opts + ")",
			AttrDropSQL: "DROP OPERATOR " + qName + " (" +
				leftt + ", " + rightt + ")"})
		depOids := []struct {
			m   map[int64]model.ObjectId
			key string
		}{
			{d.procByOid, "proc_oid"},
			{d.procByOid, "rest_oid"},
			{d.procByOid, "join_oid"},
			{d.oprByOid, "oprcom"},
			{d.oprByOid, "oprnegate"},
			{d.typeByOid, "result_oid"}}
		for _, e := range depOids {
			if t, ok := e.m[num(r[e.key])]; ok {
				d.dep(&id, &t)
			}
		}
		if t, ok := d.typeByOid[left]; ok {
			d.dep(&id, &t)
		}
		if t, ok := d.typeByOid[right]; ok {
			d.dep(&id, &t)
		}
	}
	// families: name + access method only — standalone members are
	// not modeled (ALTER FAMILY member mutations stay rejected)
	rows, err = queryRows(ctx, conn, "opfamilies", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["opfname"]) + " USING " + str(r["amname"])
		id := model.ObjectId{Kind: model.KindOperatorFamily, Name: name}
		d.opfByOid[oid] = id
		d.otherOid("pg_opfamily", oid, id)
		q, err := Quote(str(r["opfname"]))
		if err != nil {
			return err
		}
		qName := d.qNs + "." + q
		d.define(id, "opfamily="+str(r["amname"]), map[string]string{
			AttrCreateSQL: "CREATE OPERATOR FAMILY " + qName + " USING " + str(r["amname"]),
			AttrDropSQL:   "DROP OPERATOR FAMILY " + qName + " USING " + str(r["amname"])})
	}
	// classes: am/type/family/default/storage plus the folded
	// amop/amproc member list (members where the row's left type is
	// the class's indexed type — cross-type members included)
	rows, err = queryRows(ctx, conn, "opclasses", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		name := str(r["opcname"]) + " USING " + str(r["amname"])
		id := model.ObjectId{Kind: model.KindOperatorClass, Name: name}
		d.opcByOid[oid] = id
		d.otherOid("pg_opclass", oid, id)
		q, err := Quote(str(r["opcname"]))
		if err != nil {
			return err
		}
		qName := d.qNs + "." + q
		members, err := d.opclassMembers(ctx, conn,
			num(r["opcfamily"]), num(r["opcintype"]), id)
		if err != nil {
			return err
		}
		storage, hasStorage := strOrNull(r["storage"])
		canon := "opclass=am:" + str(r["amname"]) +
			";type:" + str(r["intype"]) +
			";family:" + str(r["famref"]) +
			";default:" + strconv.FormatBool(boolOf(r["opcdefault"])) +
			";storage:" + nullToDash(storage, hasStorage)
		body := ""
		if boolOf(r["opcdefault"]) {
			body = "DEFAULT "
		}
		body += "FOR TYPE " + str(r["intype"]) +
			" USING " + str(r["amname"]) +
			" FAMILY " + str(r["famref"])
		var specs []string
		if num(r["opckeytype"]) != 0 {
			specs = append(specs, "STORAGE "+str(r["storage"]))
		}
		for _, m := range members {
			canon += ";m" + strconv.Itoa(len(specs)) + ":" + m
			specs = append(specs, m)
		}
		if len(specs) > 0 {
			body += " AS " + strings.Join(specs, ", ")
		}
		d.define(id, canon, map[string]string{
			AttrCreateSQL: "CREATE OPERATOR CLASS " + qName + " " + body,
			AttrDropSQL:   "DROP OPERATOR CLASS " + qName + " USING " + str(r["amname"])})
		if t, ok := d.typeByOid[num(r["opcintype"])]; ok {
			d.dep(&id, &t)
		}
		if f, ok := d.opfByOid[num(r["opcfamily"])]; ok {
			d.dep(&id, &f)
		}
	}
	return nil
}

// opclassMembers — the `pg_amop`/`pg_amproc` member specs of one
// operator class — rows whose left type matches the class's
// `opcintype`, ordered so `DROP` runs over a stable
// list: `OPERATOR` members first (strategy, operator
// reference, `FOR ORDER BY` family when set), then
// `FUNCTION` members (number, type pair, function
// reference). Dependency edges to member operators/functions and
// sort families record on `id`.
func (d *catalogData) opclassMembers(ctx context.Context, conn spi.Conn,
	family, intype int64, id model.ObjectId) ([]string, error) {
	var out []string
	rows, err := queryRows(ctx, conn, "opclass_amop_members", family, intype)
	if err != nil {
		return nil, err
	}
	for _, r := range rows {
		spec := "OPERATOR " + strconv.FormatInt(num(r["amopstrategy"]), 10) +
			" " + str(r["opref"])
		if str(r["amoppurpose"]) == "o" {
			spec += " FOR ORDER BY " + str(r["sortref"])
		}
		out = append(out, spec)
		if t, ok := d.oprByOid[num(r["amopopr_oid"])]; ok {
			d.dep(&id, &t)
		}
		if t, ok := d.opfByOid[num(r["amopsortfamily"])]; ok {
			d.dep(&id, &t)
		}
	}
	rows, err = queryRows(ctx, conn, "opclass_amproc_members", family, intype)
	if err != nil {
		return nil, err
	}
	for _, r := range rows {
		out = append(out, "FUNCTION "+strconv.FormatInt(num(r["amprocnum"]), 10)+
			" ("+str(r["lt"])+","+str(r["rt"])+") "+str(r["fn"]))
		if t, ok := d.procByOid[num(r["amproc_oid"])]; ok {
			d.dep(&id, &t)
		}
	}
	return out, nil
}

// loadSecLabels — security labels (`pg_seclabel`) on managed targets:
// the id mirrors the target like a `COMMENT`, prefixed with the
// provider — `SECURITY_LABEL selinux:t`. The canonical is the
// label text; re-issuing `SECURITY LABEL FOR ... ON ... IS`
// is both the create and the in-place drift fix, and
// `IS NULL` the drop. Labels on targets the dialect does not
// manage stay `OTHER` (provider-qualified so two providers
// cannot collapse into one entry).
func (d *catalogData) loadSecLabels(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "seclabels", nsParams(d.ns, 17)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["objoid"])) {
			continue
		}
		attname, _ := strOrNull(r["attname"])
		target := d.annotatedTarget(str(r["cls"]),
			num(r["objoid"]), num(r["objsubid"]), attname)
		provider := str(r["provider"])
		if target != nil && target.Kind != model.KindOther {
			if _, has := d.objects[*target]; !has {
				target = nil
			}
		}
		if target != nil && target.Kind != model.KindOther {
			lid := seclabelId(*target, provider, "")
			if _, has := d.objects[lid]; has {
				// mirrors the COMMENT collision escape (A.5)
				lid = seclabelId(*target, provider,
					" ("+strings.ToLower(target.Kind.String())+")")
			}
			label := str(r["label"])
			qProvider, err := Quote(provider)
			if err != nil {
				return err
			}
			annotationTarget, err := d.annotationTarget(*target)
			if err != nil {
				return err
			}
			d.define(lid, label, map[string]string{
				AttrCreateSQL: "SECURITY LABEL FOR " + qProvider + " ON " +
					annotationTarget + " IS " + sqlLiteral(label),
				AttrDropSQL: "SECURITY LABEL FOR " + qProvider + " ON " +
					annotationTarget + " IS NULL"})
			d.dep(&lid, target)
		} else {
			suffix := ""
			if r["attname"] != nil {
				suffix = "." + str(r["attname"])
			}
			d.define(model.ObjectId{Kind: model.KindOther,
				Name: "seclabel:" + provider + "." + str(r["target"]) + suffix},
				"security-label", nil)
		}
	}
	return nil
}

// loadOther loads every catalog object a desired script can create in
// the namespace that has no managed `ObjectKind`, as OTHER, so the
// diff reports it UNSUPPORTED instead of ignoring it (5.4).
func (d *catalogData) loadOther(ctx context.Context, conn spi.Conn) error {
	// user-defined types that stay unsupported: base types (array
	// types carry typelem != 0 and are not standalone; enums,
	// composites and ranges are managed TYPE objects, domains
	// managed DOMAIN, and a range's multirange folds into it)
	rows, err := queryRows(ctx, conn, "other_base_types", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		name := str(r["typname"])
		id := model.ObjectId{Kind: model.KindOther, Name: "type:" + name}
		d.typeByOid[num(r["oid"])] = id
		d.otherOid("pg_type", num(r["oid"]), id)
		d.define(id, "typtype="+str(r["typtype"]), nil)
	}
	// extensions anchored to this namespace are managed EXTENSION
	// objects — presence-only canonical, so a version drift can
	// never surface as DRIFTED (A.5: ALTER EXTENSION UPDATE is out
	// of scope). The name keeps the extension: prefix so the id
	// stays stable across the promotion from OTHER.
	rows, err = queryRows(ctx, conn, "other_extensions", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		extname := str(r["extname"])
		q, err := Quote(extname)
		if err != nil {
			return err
		}
		d.define(model.ObjectId{Kind: model.KindExtension,
			Name: "extension:" + extname}, "extension",
			map[string]string{AttrCreateSQL: "CREATE EXTENSION IF NOT EXISTS " +
				q + " WITH SCHEMA ${namespace}"})
	}
	// policies on our tables load as managed POLICY objects in
	// loadPolicies — nothing to detect here
	// encoding conversions a desired script cannot express
	// (DEFAULT CONVERSION is a database-wide object word)
	rows, err = queryRows(ctx, conn, "other_default_conversions", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		id := model.ObjectId{Kind: model.KindOther,
			Name: "conversion:" + str(r["conname"])}
		d.otherOid("pg_conversion", num(r["oid"]), id)
		d.define(id, "conversion", nil)
	}
	// constraint rows the managed loader did not claim and which are
	// not followers of another object surface as OTHER — a contype
	// this build does not model cannot silently disappear. Trigger
	// constraints ('t') back a managed pg_trigger row and column
	// NOT NULL constraints ('n') fold into their COLUMN; non-local
	// rows are partition/inheritance copies of the parent.
	rows, err = queryRows(ctx, conn, "other_constraints", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		id := model.ObjectId{Kind: model.KindOther,
			Name: "constraint:" + str(r["parent"]) + "." + str(r["conname"])}
		d.conByOid[oid] = id
		d.otherOid("pg_constraint", oid, id)
		d.define(id, "constraint", nil)
	}
	// collations the managed loader cannot express (default provider,
	// bound encoding) stay OTHER
	rows, err = queryRows(ctx, conn, "other_collations", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		id := model.ObjectId{Kind: model.KindOther,
			Name: "collation:" + str(r["collname"])}
		d.collByOid[num(r["oid"])] = id
		d.define(id, "collation", nil)
	}
	// granted privileges: object acl columns are NULL until a
	// GRANT. On a managed target the acl becomes a managed
	// PRIVILEGES object mirroring the target like OWNERSHIP —
	// grantee roles are cluster-object references (4.1), whose
	// existence is a preflight concern. On an unmanaged target the
	// acl stays privileges: OTHER — still content-compared, so a
	// target holding the same grants reports nothing (5.4). The
	// definition is the sorted grantee:PRIVILEGE[*] token list with
	// the owner written <owner> and the grantor dropped.
	rows, err = queryRows(ctx, conn, "privileges", nsParams(d.ns, 4)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		acl, hasAcl := strOrNull(r["acl"])
		if !hasAcl {
			// an acl of '{}' unnests to no rows — no grants
			// held, so nothing to compare
			continue
		}
		attname := str(r["attname"])
		if str(r["cls"]) == "pg_attribute" {
			if err := d.columnPrivileges(oid, str(r["name"]), attname, acl); err != nil {
				return err
			}
			continue
		}
		clsName := str(r["cls"])
		var target *model.ObjectId
		switch clsName {
		case "pg_class":
			target = oidLookup(d.relByOid, oid)
		case "pg_proc":
			target = oidLookup(d.procByOid, oid)
		default:
			target = oidLookup(d.typeByOid, oid)
		}
		if target == nil || target.Kind == model.KindOther || d.foldedMembers[oid] {
			d.define(model.ObjectId{Kind: model.KindOther, Name: str(r["name"])},
				"privileges="+acl,
				map[string]string{spi.ContentComparedAttribute: "true"})
			continue
		}
		pid := privilegesId(*target, "")
		if _, has := d.objects[pid]; has {
			pid = privilegesId(*target, " ("+strings.ToLower(target.Kind.String())+")")
		}
		gt, err := d.grantTarget(*target)
		if err != nil {
			return err
		}
		d.define(pid, "privileges="+acl, map[string]string{AttrGrantTarget: gt})
		d.dep(&pid, target)
	}
	// schema-scoped default privileges (pg_default_acl) — future
	// grants the namespace applies to every new object of a class.
	// Global rows (defaclnamespace = 0) belong to no namespace and
	// stay invisible like every other cluster object (A.5).
	rows, err = queryRows(ctx, conn, "default_acl", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		acl, hasAcl := strOrNull(r["acl"])
		classWord := defaultAclClass(str(r["objtype"]))
		if !hasAcl || classWord == "" {
			continue
		}
		role := str(r["role"])
		qRole, err := Quote(role)
		if err != nil {
			return err
		}
		d.define(model.ObjectId{Kind: model.KindDefaultPrivileges,
			Name: classWord + " for " + role}, "privileges="+acl,
			map[string]string{
				AttrGrantTarget: classWord,
				AttrGrantPrefix: "ALTER DEFAULT PRIVILEGES FOR ROLE " +
					qRole + " IN SCHEMA ${namespace}"})
	}
	// ownership: the role that owns each object, filtered by the
	// session role — a script that assigns no owner leaves the
	// object session-owned, so an absent OWNER TO means "don't
	// care", not "claim". On a managed target the role is a
	// cluster-object reference (4.1): the ownership binding becomes
	// a managed OWNERSHIP object named and parented like the target
	// (mirroring COMMENT); on an unmanaged target it stays OTHER.
	// An index's relowner always follows its table's — it cannot
	// diverge, so index rows are skipped outright.
	rows, err = queryRows(ctx, conn, "ownership", nsParams(d.ns, 3)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		clsName := str(r["cls"])
		var target *model.ObjectId
		switch clsName {
		case "pg_class":
			target = oidLookup(d.relByOid, oid)
		case "pg_proc":
			target = oidLookup(d.procByOid, oid)
		default:
			target = oidLookup(d.typeByOid, oid)
		}
		if target != nil && target.Kind == model.KindIndex {
			continue
		}
		owner := str(r["owner"])
		if target == nil || target.Kind == model.KindOther || d.foldedMembers[oid] {
			d.define(model.ObjectId{Kind: model.KindOther, Name: str(r["name"])},
				"ownership", nil)
			continue
		}
		wid := ownershipId(*target, "")
		if _, has := d.objects[wid]; has {
			wid = ownershipId(*target, " ("+strings.ToLower(target.Kind.String())+")")
		}
		at, err := d.annotationTarget(*target)
		if err != nil {
			return err
		}
		qOwner, err := Quote(owner)
		if err != nil {
			return err
		}
		d.define(wid, "owner="+owner, map[string]string{
			AttrCreateSQL: "ALTER " + at + " OWNER TO " + qOwner})
		d.dep(&wid, target)
	}
	// the reconcile unit itself: the schema's own owner and acl.
	// nspowner follows the same session-role "don't care" rule as
	// every other owner leg; nspacl is NULL until a GRANT (the
	// default acl then), and an empty acl means nothing held. Both
	// mirror onto the neutral "<namespace>" name — a managed name
	// no table, view or type can collide with (4.1) — so the real
	// schema name never enters a canonical
	rows, err = queryRows(ctx, conn, "namespace_owner", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		owner := str(r["owner"])
		qOwner, err := Quote(owner)
		if err != nil {
			return err
		}
		d.define(model.ObjectId{Kind: model.KindOwnership, Name: "<namespace>"},
			"owner="+owner, map[string]string{
				AttrCreateSQL: "ALTER SCHEMA ${namespace} OWNER TO " + qOwner})
	}
	rows, err = queryRows(ctx, conn, "namespace_acl", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		acl, hasAcl := strOrNull(r["acl"])
		if !hasAcl {
			// an acl of '{}' unnests to no rows — nothing held
			continue
		}
		d.define(model.ObjectId{Kind: model.KindPrivileges, Name: "<namespace>"},
			"privileges="+acl, map[string]string{
				AttrGrantTarget: "SCHEMA ${namespace}"})
	}
	// publication bindings on namespace tables (the publication itself
	// is a cluster-level reference, 4.1; the FOR TABLE edge is the
	// namespace-visible part). The binding's parent is the bound
	// table, so it orders after it; filter/column lists ride in the
	// canonical (pg_publication_rel.prqual/.prattrs, PG15+)
	pubQuery := "publication_rel_pre15"
	if d.serverMajor >= 15 {
		pubQuery = "publication_rel"
	}
	rows, err = queryRows(ctx, conn, pubQuery, d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		if err := d.publicationBinding(r, "TABLE", "table"); err != nil {
			return err
		}
	}
	// FOR TABLES IN SCHEMA bindings — pg_publication_namespace anchors
	// the publication to the schema itself (PostgreSQL 15+)
	if d.serverMajor >= 15 {
		rows, err = queryRows(ctx, conn, "publication_namespace", d.ns)
		if err != nil {
			return err
		}
		for _, r := range rows {
			if err := d.publicationBinding(r, "TABLES IN SCHEMA", "schema"); err != nil {
				return err
			}
		}
	}
	// casts have no namespace; they are visible through a namespace
	// source/target type or cast function (a cast between two foreign
	// types has no namespace anchor and stays outside the model).
	// A cast anchored to a managed object becomes a managed CAST —
	// its identity is the (source AS target) pair and foreign types
	// or the cast function stay cluster/namespace references in the
	// canonical; a cast anchored only to unmanaged objects stays
	// cast: OTHER
	rows, err = queryRows(ctx, conn, "casts", nsParams(d.ns, 2)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		if rng, ok := d.autoCasts[oid]; ok {
			// the range→multirange cast folds into the range
			// type — its oid remaps so dependents resolve
			d.otherOid("pg_cast", oid, d.typeByOid[rng])
			continue
		}
		source := oidLookup(d.typeByOid, num(r["castsource"]))
		target := oidLookup(d.typeByOid, num(r["casttarget"]))
		fn := oidLookup(d.procByOid, num(r["castfunc"]))
		// managed only when every anchor visible in the
		// namespace resolves managed — a cast on an
		// unsupported rowtype cannot be created, so it
		// stays OTHER instead of failing at apply
		managed := (managedAnchor(source) || managedAnchor(target) ||
			managedAnchor(fn)) &&
			!(otherAnchor(source) || otherAnchor(target) || otherAnchor(fn))
		if !managed || r["create_sql"] == nil {
			// unanchored, or a castmethod the profile cannot
			// render (binary casts were removed in PG10)
			id := model.ObjectId{Kind: model.KindOther, Name: str(r["name"])}
			d.otherOid("pg_cast", oid, id)
			d.define(id, "cast", nil)
			continue
		}
		id := model.ObjectId{Kind: model.KindCast, Name: str(r["bare"])}
		d.otherOid("pg_cast", oid, id)
		if managedAnchor(source) {
			d.dep(&id, source)
		}
		if managedAnchor(target) {
			d.dep(&id, target)
		}
		if managedAnchor(fn) {
			d.dep(&id, fn)
		}
		d.define(id, str(r["canon"]), map[string]string{
			AttrCreateSQL: str(r["create_sql"]),
			AttrDropSQL:   str(r["drop_sql"])})
	}
	// transforms have no namespace; anchor on the type or the
	// from/to-SQL functions. A transform's only expressible anchor
	// is the FOR type (3.2) — a transform on a managed type becomes
	// a managed TRANSFORM, its identity the type/language pair and
	// the language itself a cluster reference; anything else stays
	// transform: OTHER
	rows, err = queryRows(ctx, conn, "transforms", nsParams(d.ns, 2)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		oid := num(r["oid"])
		if d.extMember(oid) {
			continue
		}
		typ := oidLookup(d.typeByOid, num(r["trftype"]))
		if !managedAnchor(typ) {
			id := model.ObjectId{Kind: model.KindOther, Name: str(r["name"])}
			d.otherOid("pg_transform", oid, id)
			d.define(id, "transform", nil)
			continue
		}
		id := model.ObjectId{Kind: model.KindTransform, Name: str(r["bare"])}
		d.otherOid("pg_transform", oid, id)
		d.dep(&id, typ)
		if t, ok := d.procByOid[num(r["trffromsql"])]; ok {
			d.dep(&id, &t)
		}
		if t, ok := d.procByOid[num(r["trftosql"])]; ok {
			d.dep(&id, &t)
		}
		d.define(id, str(r["canon"]), map[string]string{
			AttrCreateSQL: str(r["create_sql"]),
			AttrDropSQL:   str(r["drop_sql"])})
	}
	// languages are database-level; a language whose handlers live in
	// the namespace is part of the desired state
	rows, err = queryRows(ctx, conn, "languages", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["oid"])) {
			continue
		}
		id := model.ObjectId{Kind: model.KindOther, Name: str(r["name"])}
		d.langByOid[num(r["oid"])] = id
		d.otherOid("pg_language", num(r["oid"]), id)
		d.define(id, "language", nil)
	}
	// sequence publication bindings — the pg_publication_sequences
	// view exists from PostgreSQL 19 (pg_publication_rel covers
	// tables) and also expands FOR ALL SEQUENCES flags, which have no
	// per-sequence binding to reconcile — those pubs are filtered out
	if d.serverMajor >= 19 {
		rows, err = queryRows(ctx, conn, "publication_sequences", d.ns)
		if err != nil {
			return err
		}
		for _, r := range rows {
			if err := d.publicationBinding(r, "SEQUENCE", "sequence"); err != nil {
				return err
			}
		}
	}
	return nil
}

func oidLookup(m map[int64]model.ObjectId, oid int64) *model.ObjectId {
	if id, ok := m[oid]; ok {
		return &id
	}
	return nil
}

// publicationBinding — one managed `PUBLICATION` binding: name the
// publication (a cluster-object reference, 4.1), parent the bound
// relation, and render the `ALTER PUBLICATION` add/drop pair.
// `verb` is the statement's object word — `TABLE`,
// `TABLES IN SCHEMA` or `SEQUENCE`; `shape` is the
// canonical's binding discriminator.
func (d *catalogData) publicationBinding(r row, verb, shape string) error {
	pub := str(r["pubname"])
	rel, hasRel := strOrNull(r["relname"])
	qual, hasQual := strOrNull(r["qual"])
	cols, hasCols := strOrNull(r["cols"])
	id := model.ObjectId{Kind: model.KindPublication, Name: pub}
	if hasRel {
		id.Parent = rel
	}
	qpub, err := Quote(pub)
	if err != nil {
		return err
	}
	create := "ALTER PUBLICATION " + qpub + " ADD " + verb + " "
	if hasRel {
		ref, err := d.tableRef(rel)
		if err != nil {
			return err
		}
		create += ref
	} else {
		create += "${namespace}"
	}
	if hasCols {
		var qs []string
		for _, c := range strings.Split(cols, ",") {
			q, err := Quote(c)
			if err != nil {
				return err
			}
			qs = append(qs, q)
		}
		create += " (" + strings.Join(qs, ",") + ")"
	}
	if hasQual {
		create += " WHERE (" + qual + ")"
	}
	dropTarget := "${namespace}"
	if hasRel {
		dropTarget, err = d.tableRef(rel)
		if err != nil {
			return err
		}
	}
	drop := "ALTER PUBLICATION " + qpub + " DROP " + verb + " " + dropTarget
	canonical := "binding=" + shape
	if hasQual {
		canonical += ";filter=" + qual
	}
	if hasCols {
		canonical += ";columns=" + cols
	}
	d.define(id, canonical, map[string]string{
		AttrCreateSQL: create, AttrDropSQL: drop})
	return nil
}

// columnPrivileges — a column-level grant — `pg_attribute.attacl` on a
// relation in the namespace. On a managed relation it mirrors the
// column like a comment does (`PRIVILEGES <col>` parented on the
// relation) and renders through the same token diff, the column name
// parenthesized after the privilege word; on an unmanaged relation it
// stays `privileges:<rel>.<col>` OTHER, content-compared.
func (d *catalogData) columnPrivileges(relOid int64, name, attname,
	acl string) error {
	rel := oidLookup(d.relByOid, relOid)
	if rel == nil || rel.Kind == model.KindOther || d.foldedMembers[relOid] {
		d.define(model.ObjectId{Kind: model.KindOther, Name: name},
			"privileges="+acl,
			map[string]string{spi.ContentComparedAttribute: "true"})
		return nil
	}
	pid := model.ObjectId{Kind: model.KindPrivileges,
		Name: attname, Parent: rel.Name}
	gt, err := d.grantTarget(*rel)
	if err != nil {
		return err
	}
	qCol, err := Quote(attname)
	if err != nil {
		return err
	}
	d.define(pid, "privileges="+acl, map[string]string{
		AttrGrantTarget: gt,
		AttrGrantColumn: qCol})
	// order after the column when it is a modeled object (tables and
	// partitions carry COLUMN objects; views and materialized views
	// do not) — otherwise the relation itself suffices
	column := model.ObjectId{Kind: model.KindColumn, Name: attname, Parent: rel.Name}
	if _, has := d.objects[column]; has {
		d.dep(&pid, &column)
	} else {
		d.dep(&pid, rel)
	}
	return nil
}

// loadComments — descriptions on namespace objects (pg_description). A
// description on a managed object becomes a managed `COMMENT` object
// whose `ObjectId` mirrors the target (its name, and its parent
// for child objects); a description on anything else stays the OTHER
// `comment:<name>` object, undeclarable and reported on every
// run. pg_extension is not in NS_OBJECTS — an extension's shipped
// description is database-wide metadata (COMMENT ON EXTENSION is
// rejected), and an anchored object here would poison desired state
// for the managed EXTENSION object; the extension row itself reports.
// Comments sharing the mirrored id (a column and a policy with the
// same name on one table, say) merge into one COMMENT object, as on
// both sides of the diff, so they still converge — documented in A.5.
func (d *catalogData) loadComments(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "comments", nsParams(d.ns, 17)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		if d.extMember(num(r["objoid"])) {
			continue
		}
		attname, hasAttname := strOrNull(r["attname"])
		target := d.annotatedTarget(str(r["cls"]),
			num(r["objoid"]), num(r["objsubid"]), attname)
		if target != nil && target.Kind != model.KindOther {
			if _, has := d.objects[*target]; !has {
				target = nil
			}
		}
		if target != nil && target.Kind != model.KindOther {
			cid := commentId(*target, "")
			if _, has := d.objects[cid]; has {
				// two targets share the mirrored id (a column
				// and a same-named policy, say): the later one
				// gains a (kind) suffix so both surface (A.5)
				cid = commentId(*target,
					" ("+strings.ToLower(target.Kind.String())+")")
			}
			text := str(r["description"])
			cs, err := d.commentSql(*target, text)
			if err != nil {
				return err
			}
			d.define(cid, text, map[string]string{AttrCreateSQL: cs})
			d.dep(&cid, target)
		} else {
			suffix := ""
			if hasAttname {
				suffix = "." + attname
			}
			d.define(model.ObjectId{Kind: model.KindOther,
				Name: "comment:" + str(r["target"]) + suffix},
				"comment", nil)
		}
	}
	return nil
}

// grantTarget — the `<KEYWORD> ${namespace}.<name>` target phrase of a
// `GRANT|REVOKE ... ON` statement for a managed target — the
// relation kinds grant through `TABLE` except foreign tables,
// which use their own keyword; routines reuse the annotation phrase
// (`FUNCTION|PROCEDURE name(sigargs)`).
func (d *catalogData) grantTarget(target model.ObjectId) (string, error) {
	n := "${namespace}"
	name, err := Quote(target.Name)
	if err != nil {
		return "", err
	}
	switch target.Kind {
	case model.KindTable, model.KindPartition, model.KindView,
		model.KindMaterializedView:
		return "TABLE " + n + "." + name, nil
	case model.KindForeignTable:
		return "FOREIGN TABLE " + n + "." + name, nil
	case model.KindSequence:
		return "SEQUENCE " + n + "." + name, nil
	case model.KindType:
		return "TYPE " + n + "." + name, nil
	case model.KindDomain:
		return "DOMAIN " + n + "." + name, nil
	case model.KindRoutine:
		return d.routineAnnotationTarget(target, "")
	case model.KindAggregate:
		return d.routineAnnotationTarget(target, "FUNCTION")
	default:
		return "", core.NewPlanningErrorf("no grant form for %s", target.Kind)
	}
}

// annotatedTarget — the managed object an annotation row (comment or
// security label) targets, or nil when the annotated catalog entry
// maps to no managed object.
func (d *catalogData) annotatedTarget(cls string, objoid, objsubid int64,
	attname string) *model.ObjectId {
	switch cls {
	case "pg_class":
		if objsubid == 0 {
			return oidLookup(d.relByOid, objoid)
		}
		// column annotations resolve where columns are managed
		// objects — tables, partitions and foreign tables all
		// carry them parented on the relation
		rel := oidLookup(d.relByOid, objoid)
		if attname != "" && rel != nil &&
			(rel.Kind == model.KindTable || rel.Kind == model.KindPartition ||
				rel.Kind == model.KindForeignTable) {
			return &model.ObjectId{Kind: model.KindColumn,
				Name: attname, Parent: rel.Name}
		}
		return nil
	case "pg_proc":
		// a folded constructor has no annotation target of its
		// own — a comment or label on it stays OTHER
		if d.foldedMembers[objoid] {
			return nil
		}
		return oidLookup(d.procByOid, objoid)
	case "pg_constraint":
		return oidLookup(d.conByOid, objoid)
	case "pg_policy":
		return oidLookup(d.polByOid, objoid)
	case "pg_statistic_ext":
		return oidLookup(d.stxByOid, objoid)
	case "pg_rewrite":
		return oidLookup(d.ruleByOid, objoid)
	case "pg_collation":
		return oidLookup(d.collByOid, objoid)
	case "pg_conversion":
		return oidLookup(d.convByOid, objoid)
	case "pg_type":
		// a folded multirange likewise stays OTHER
		if d.foldedMembers[objoid] {
			return nil
		}
		return oidLookup(d.typeByOid, objoid)
	case "pg_trigger":
		return oidLookup(d.tgByOid, objoid)
	case "pg_ts_template":
		return oidLookup(d.tmplByOid, objoid)
	case "pg_ts_parser":
		return oidLookup(d.prsByOid, objoid)
	case "pg_ts_dict":
		return oidLookup(d.dictByOid, objoid)
	case "pg_ts_config":
		return oidLookup(d.cfgByOid, objoid)
	case "pg_operator":
		return oidLookup(d.oprByOid, objoid)
	case "pg_opclass":
		return oidLookup(d.opcByOid, objoid)
	case "pg_opfamily":
		return oidLookup(d.opfByOid, objoid)
	default:
		return nil
	}
}

// commentSql is the `COMMENT ON <target> IS 'text'` statement for a
// managed target.
func (d *catalogData) commentSql(target model.ObjectId, text string) (string, error) {
	at, err := d.annotationTarget(target)
	if err != nil {
		return "", err
	}
	return "COMMENT ON " + at + " IS " + sqlLiteral(text), nil
}

// annotationTarget — the `<KEYWORD> <name>` target phrase shared by
// `COMMENT ON` and `SECURITY LABEL ... ON`.
func (d *catalogData) annotationTarget(target model.ObjectId) (string, error) {
	n := "${namespace}"
	name, err := Quote(target.Name)
	if err != nil {
		return "", err
	}
	qParent := ""
	if target.Parent != "" {
		qp, err := Quote(target.Parent)
		if err != nil {
			return "", err
		}
		qParent = n + "." + qp
	}
	switch target.Kind {
	case model.KindTable, model.KindPartition:
		return "TABLE " + n + "." + name, nil
	case model.KindForeignTable:
		return "FOREIGN TABLE " + n + "." + name, nil
	case model.KindView:
		return "VIEW " + n + "." + name, nil
	case model.KindMaterializedView:
		return "MATERIALIZED VIEW " + n + "." + name, nil
	case model.KindSequence:
		return "SEQUENCE " + n + "." + name, nil
	case model.KindIndex:
		return "INDEX " + n + "." + name, nil
	case model.KindColumn:
		return "COLUMN " + qParent + "." + name, nil
	case model.KindPrimaryKey, model.KindUnique, model.KindForeignKey,
		model.KindCheck, model.KindExclusion:
		return "CONSTRAINT " + name + " ON " + qParent, nil
	case model.KindTrigger:
		return "TRIGGER " + name + " ON " + qParent, nil
	case model.KindPolicy:
		return "POLICY " + name + " ON " + qParent, nil
	case model.KindStatistics:
		return "STATISTICS " + n + "." + name, nil
	case model.KindRule:
		return "RULE " + name + " ON " + qParent, nil
	case model.KindCollation:
		return "COLLATION " + n + "." + name, nil
	case model.KindConversion:
		return "CONVERSION " + n + "." + name, nil
	case model.KindType:
		return "TYPE " + n + "." + name, nil
	case model.KindDomain:
		return "DOMAIN " + n + "." + name, nil
	case model.KindRoutine:
		return d.routineAnnotationTarget(target, "")
	case model.KindAggregate:
		return d.routineAnnotationTarget(target, "AGGREGATE")
	case model.KindTextSearchTemplate:
		return "TEXT SEARCH TEMPLATE " + n + "." + name, nil
	case model.KindTextSearchParser:
		return "TEXT SEARCH PARSER " + n + "." + name, nil
	case model.KindTextSearchDictionary:
		return "TEXT SEARCH DICTIONARY " + n + "." + name, nil
	case model.KindTextSearchConfiguration:
		return "TEXT SEARCH CONFIGURATION " + n + "." + name, nil
	// an operator name is a symbol plus signature — never an
	// identifier to quote: OPERATOR ns.=(integer,integer)
	case model.KindOperator:
		return "OPERATOR " + n + "." + target.Name, nil
	case model.KindOperatorClass, model.KindOperatorFamily:
		// the id name is "<name> USING <am>" — only the name
		// part is schema-qualified and quoted
		i := strings.Index(target.Name, " USING ")
		qBase, err := Quote(target.Name[:i])
		if err != nil {
			return "", err
		}
		word := "OPERATOR FAMILY "
		if target.Kind == model.KindOperatorClass {
			word = "OPERATOR CLASS "
		}
		return word + n + "." + qBase + target.Name[i:], nil
	default:
		return "", core.NewPlanningErrorf("no annotation form for %s", target.Kind)
	}
}

// routineAnnotationTarget — `<word> <name>(<sigargs>)` —
// `FUNCTION` for a grant on an aggregate, `AGGREGATE` for its
// annotation/owner forms; no `kind` reads the routine's own `PROKIND`.
func (d *catalogData) routineAnnotationTarget(target model.ObjectId, kind string) (string, error) {
	o := d.objects[target]
	if kind == "" {
		kind = "FUNCTION"
		if o != nil && o.attrs[AttrProkind] == "p" {
			kind = "PROCEDURE"
		}
	}
	// the id name was built as proname + "(" + sigargs + ")" — strip
	// that exact suffix so a quoted proname containing '(' survives
	sigargs := ""
	if o != nil {
		sigargs = o.attrs[attrSigArgs]
	}
	suffix := "(" + sigargs + ")"
	fname := target.Name
	if strings.HasSuffix(fname, suffix) {
		fname = fname[:len(fname)-len(suffix)]
	}
	q, err := Quote(fname)
	if err != nil {
		return "", err
	}
	return kind + " ${namespace}." + q + "(" + sigargs + ")", nil
}

func (d *catalogData) loadDependencies(ctx context.Context, conn spi.Conn) error {
	// view dependencies via the _RETURN rewrite rule
	rows, err := queryRows(ctx, conn, "dep_view_class", d.ns, d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		viewer := oidLookup(d.relByOid, num(r["viewer"]))
		d.depOid(viewer, num(r["dep"]), true)
	}
	// view-on-routine and view-on-type dependencies
	rows, err = queryRows(ctx, conn, "dep_view_other", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		viewer := oidLookup(d.relByOid, num(r["viewer"]))
		d.depOid(viewer, num(r["dep"]), true)
	}
	// routine dependencies on relations, routines and types
	// (SQL-language bodies and argument/return types)
	rows, err = queryRows(ctx, conn, "dep_routine", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		caller := oidLookup(d.procByOid, num(r["caller"]))
		if caller != nil {
			d.depOid(caller, num(r["dep"]), true)
		}
	}
	// index-on-routine dependencies (expression indexes)
	rows, err = queryRows(ctx, conn, "dep_index_proc", d.ns, d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		idx := oidLookup(d.relByOid, num(r["idx"]))
		proc := oidLookup(d.procByOid, num(r["dep"]))
		d.dep(idx, proc)
	}
	// column defaults on our tables -> sequences, routines and types
	// (the edge lands on the column and on its parent table, so that a
	// covered column inside a CREATE TABLE still orders after the ref)
	rows, err = queryRows(ctx, conn, "dep_attrdef", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		table := oidLookup(d.relByOid, num(r["tbl"]))
		if table == nil {
			continue
		}
		column := model.ObjectId{Kind: model.KindColumn,
			Name: str(r["col"]), Parent: table.Name}
		ref := d.byOid(num(r["dep"]), true)
		d.dep(&column, ref)
		d.depTableForCoveredChild(*table, ref)
	}
	// constraints on our tables -> routines, types, the unique
	// constraint backing an FK (a pg_class ref to its index), and
	// other constraints
	rows, err = queryRows(ctx, conn, "dep_constraint", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		from := oidLookup(d.conByOid, num(r["con"]))
		ref := num(r["dep"])
		var to *model.ObjectId
		switch str(r["refcls"]) {
		case "pg_constraint":
			to = oidLookup(d.conByOid, ref)
		case "pg_class":
			to = oidLookup(d.conByIndexOid, ref)
			if to == nil {
				to = oidLookup(d.relByOid, ref)
			}
		default:
			to = d.byOid(ref, true)
		}
		d.dep(from, to)
	}
	// domains and ranges -> their base/subtype, canonical and
	// subtype-diff routines; domain check constraints -> the
	// routines, types and relations their expressions use
	rows, err = queryRows(ctx, conn, "dep_type", d.ns, d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		dom := oidLookup(d.typeByOid, num(r["dom"]))
		d.depOid(dom, num(r["dep"]), true)
	}
	// policies -> the routines, types and relations their
	// USING / WITH CHECK expressions reference
	rows, err = queryRows(ctx, conn, "dep_policy", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		pol := oidLookup(d.polByOid, num(r["pol"]))
		d.depOid(pol, num(r["dep"]), true)
	}
	// column types and collations on our tables -> our domains, enums
	// and collations (objsubid is the column's attnum; a zero objsubid
	// is an object-level edge)
	rows, err = queryRows(ctx, conn, "dep_column_type", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		table := oidLookup(d.relByOid, num(r["tbl"]))
		if table == nil {
			continue
		}
		depOidV := num(r["dep"])
		ref := d.byOid(depOidV, true) // resolved twice: collByOid is the fallback
		if ref == nil {
			ref = oidLookup(d.collByOid, depOidV)
		}
		if col, ok := strOrNull(r["col"]); ok {
			column := model.ObjectId{Kind: model.KindColumn, Name: col, Parent: table.Name}
			d.dep(&column, ref)
		}
		d.depTableForCoveredChild(*table, ref)
	}
	// partition children (tables and indexes) -> their parents
	rows, err = queryRows(ctx, conn, "dep_partition", d.ns)
	if err != nil {
		return err
	}
	for _, r := range rows {
		child := oidLookup(d.relByOid, num(r["child"]))
		d.depOid(child, num(r["dep"]), true)
	}
	// unsupported objects -> the managed objects they depend on.
	// An OTHER object can never be recreated, so a dependent one must
	// block a drop or recreate of what it uses (5.8.3): a rule or an
	// exclusion constraint dies with its table, a cast or a range on
	// our type blocks dropping it, a language blocks its handlers.
	rows, err = queryRows(ctx, conn, "dep_other")
	if err != nil {
		return err
	}
	for _, r := range rows {
		m := d.otherByOid[str(r["cls"])]
		var from *model.ObjectId
		if m != nil {
			from = oidLookup(m, num(r["objid"]))
		}
		if from == nil {
			continue
		}
		ref := num(r["dep"])
		var to *model.ObjectId
		switch str(r["refcls"]) {
		case "pg_constraint":
			to = oidLookup(d.conByOid, ref)
		case "pg_collation":
			to = oidLookup(d.collByOid, ref)
		case "pg_ts_config":
			to = oidLookup(d.cfgByOid, ref)
		case "pg_ts_dict":
			to = oidLookup(d.dictByOid, ref)
		case "pg_ts_parser":
			to = oidLookup(d.prsByOid, ref)
		case "pg_ts_template":
			to = oidLookup(d.tmplByOid, ref)
		default:
			to = d.byOid(ref, true)
		}
		d.dep(from, to)
	}
	return nil
}

func (d *catalogData) loadExternalDependents(ctx context.Context, conn spi.Conn) error {
	rows, err := queryRows(ctx, conn, "external_dependents", nsParams(d.ns, 28)...)
	if err != nil {
		return err
	}
	for _, r := range rows {
		target := d.byOid(num(r["tgt"]), true)
		if target == nil {
			continue
		}
		phantom := model.ObjectId{Kind: model.KindOther,
			Name: str(r["depns"]) + "." + str(r["depname"])}
		o := d.obj(phantom)
		if o.def == nil {
			def := model.Definition("")
			o.def = &def
			o.attrs[spi.ExternalDependentAttribute] = "true"
		}
		d.dep(&phantom, target)
	}
	return nil
}

// computeDumpSql — spec 3.1 dump(): fills `DUMP_SQL` — each object's
// complete `${namespace}`-token create fragment list, joined by
// U+0000 (impossible in PostgreSQL text). A table's fragment folds its
// serial-owned sequences and columns the way
// `createTableStatements` does; a sequence owned by a
// table column of this snapshot and every object without a create
// fragment get none — they surface as comment lines in the dump.
func (d *catalogData) computeDumpSql() error {
	for id, o := range d.objects {
		var statements []string
		switch id.Kind {
		case model.KindTable:
			var err error
			statements, err = d.tableDumpStatements(id, o)
			if err != nil {
				return err
			}
		case model.KindForeignTable:
			var err error
			statements, err = d.foreignTableDumpStatements(id, o)
			if err != nil {
				return err
			}
		case model.KindMaterializedView:
			if create, ok := o.attrs[AttrCreateSQL]; ok {
				if o.attrs[AttrPopulated] != "false" {
					statements = []string{create}
				} else {
					// CREATE_SQL embeds pg_get_viewdef's
					// trailing ';' — WITH NO DATA must
					// follow the query, not the ';'
					statements = []string{DropTerminator(create) + " WITH NO DATA"}
				}
			}
		case model.KindSequence:
			if !d.foldedSequence(id) {
				statements = fragments(o, AttrCreateSQL, AttrOwnedSQL)
			}
		case model.KindPrivileges:
			var err error
			statements, err = privilegeDumpStatements(o)
			if err != nil {
				return err
			}
		default:
			statements = fragments(o, AttrCreateSQL, AttrPostSQL, AttrOwnedSQL)
		}
		if len(statements) > 0 {
			o.attrs[AttrDumpSQL] = strings.Join(statements, "\u0000")
		}
	}
	return nil
}

// foreignTableDumpStatements — the CREATE FOREIGN TABLE fragment of
// `createForeignTableStatements` in unbound token form: columns
// folded, then `SERVER`/`OPTIONS`.
func (d *catalogData) foreignTableDumpStatements(id model.ObjectId, table *objData) ([]string, error) {
	var columns []*objData
	for k, v := range d.objects {
		if k.Kind == model.KindColumn && k.Parent == id.Name {
			columns = append(columns, v)
		}
	}
	sort.Slice(columns, func(i, j int) bool {
		return attnumStr(columns[i]) < attnumStr(columns[j])
	})
	ref, err := d.tableRef(id.Name)
	if err != nil {
		return nil, err
	}
	server, err := Quote(table.attrs[AttrFtServer])
	if err != nil {
		return nil, err
	}
	var b strings.Builder
	b.WriteString("CREATE FOREIGN TABLE " + ref + " (\n")
	for i, c := range columns {
		if i > 0 {
			b.WriteString(",\n")
		}
		b.WriteString("    " + c.attrs[AttrColumnSQL])
	}
	b.WriteString("\n) SERVER " + server)
	if options := table.attrs[AttrFtOptions]; options != "" {
		b.WriteString(" OPTIONS (" + options + ")")
	}
	return []string{b.String()}, nil
}

func attnumStr(o *objData) int {
	n, _ := strconv.Atoi(o.attrs[AttrAttnum])
	return n
}

// foldedSequence — a sequence folded into its owner table's create
// fragment (A.5).
func (d *catalogData) foldedSequence(id model.ObjectId) bool {
	for dep := range d.deps[id] {
		if dep.Kind == model.KindTable {
			if _, has := d.objects[dep]; has {
				return true
			}
		}
	}
	return false
}

// tableDumpStatements — the folded CREATE TABLE fragment list of
// `createTableStatements` in unbound token form: owned sequences
// first, the CREATE TABLE with every column, then
// replica-identity/row-security and OWNED BY alters.
func (d *catalogData) tableDumpStatements(id model.ObjectId, table *objData) ([]string, error) {
	var foldedSeqs []model.ObjectId
	for k := range d.objects {
		if k.Kind == model.KindSequence {
			if s := d.deps[k]; s != nil && s.Has(id) {
				foldedSeqs = append(foldedSeqs, k)
			}
		}
	}
	sort.Slice(foldedSeqs, func(i, j int) bool {
		return foldedSeqs[i].Compare(foldedSeqs[j]) < 0
	})
	var columns []*objData
	for k, v := range d.objects {
		if k.Kind == model.KindColumn && k.Parent == id.Name {
			columns = append(columns, v)
		}
	}
	sort.Slice(columns, func(i, j int) bool {
		return attnumStr(columns[i]) < attnumStr(columns[j])
	})
	var statements []string
	for _, k := range foldedSeqs {
		statements = append(statements, d.objects[k].attrs[AttrCreateSQL])
	}
	canonical := ""
	if table.def != nil {
		canonical = string(*table.def)
	}
	ref, err := d.tableRef(id.Name)
	if err != nil {
		return nil, err
	}
	var b strings.Builder
	b.WriteString("CREATE ")
	if canonicalField(canonical, "relpersistence") == "u" {
		b.WriteString("UNLOGGED ")
	}
	b.WriteString("TABLE " + ref + " (\n")
	for i, c := range columns {
		if i > 0 {
			b.WriteString(",\n")
		}
		b.WriteString("    " + c.attrs[AttrColumnSQL])
	}
	b.WriteString("\n)")
	if options := table.attrs[AttrTableOptions]; options != "" {
		b.WriteString(" WITH (" + options + ")")
	}
	statements = append(statements, b.String())
	var replident string
	switch table.attrs[AttrReplident] {
	case "f":
		replident = "FULL"
	case "n":
		replident = "NOTHING"
	}
	if replident != "" {
		statements = append(statements, "ALTER TABLE "+ref+
			" REPLICA IDENTITY "+replident)
	}
	if canonicalField(canonical, "rls") == "true" {
		statements = append(statements, "ALTER TABLE "+ref+
			" ENABLE ROW LEVEL SECURITY")
	}
	if canonicalField(canonical, "forcerls") == "true" {
		statements = append(statements, "ALTER TABLE "+ref+
			" FORCE ROW LEVEL SECURITY")
	}
	for _, k := range foldedSeqs {
		if owned, ok := d.objects[k].attrs[AttrOwnedSQL]; ok {
			statements = append(statements, owned)
		}
	}
	return statements, nil
}
