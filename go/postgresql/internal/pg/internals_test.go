package pg

import (
	"strings"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

func obj(id model.ObjectId, def string, attrs map[string]string) model.SchemaObject {
	return model.NewSchemaObject(id, model.Definition(def), nil, attrs)
}

func snap(objects ...model.SchemaObject) model.SchemaSnapshot {
	m := make(map[model.ObjectId]model.SchemaObject, len(objects))
	for _, o := range objects {
		m[o.ID] = o
	}
	return model.NewSchemaSnapshot(m)
}

func missingDiff(id model.ObjectId) model.Difference {
	d := model.Definition("d")
	return model.Difference{ID: id, Type: model.DifferenceMissing, Expected: &d}
}

func driftedDiff(id model.ObjectId) model.Difference {
	want, have := model.Definition("want"), model.Definition("have")
	return model.Difference{ID: id, Type: model.DifferenceDrifted,
		Expected: &want, Actual: &have}
}

// ---- PgIdentifiers -----------------------------------------------------

func TestQuoteDoublesEmbeddedQuotes(t *testing.T) {
	for _, tc := range [][2]string{
		{"plain", `"plain"`},
		{`has"quote`, `"has""quote"`},
		{" space ", `" space "`},
	} {
		got, err := Quote(tc[0])
		if err != nil {
			t.Fatalf("Quote(%q): %v", tc[0], err)
		}
		if got != tc[1] {
			t.Errorf("Quote(%q) = %q, want %q", tc[0], got, tc[1])
		}
	}
}

func TestQuoteRejectsIllegalIdentifiers(t *testing.T) {
	for _, s := range []string{"", "a\000b", strings.Repeat("x", 64)} {
		if _, err := Quote(s); err == nil {
			t.Errorf("Quote(%q) succeeded, want error", s)
		}
	}
}

func TestScratchNameFormatAndLimit(t *testing.T) {
	name := ScratchName("app", 0x1A2B3C)
	if !strings.HasPrefix(name, "app__rcl_") {
		t.Errorf("ScratchName = %q, want app__rcl_*", name)
	}
	if got := name[:strings.Index(name, "__rcl_")]; got != "app" {
		t.Errorf("prefix = %q", got)
	}
	longNs := strings.Repeat("n", 100)
	if got := len(ScratchName(longNs, 1)); got > 63 {
		t.Errorf("ScratchName of 100-char ns = %d bytes, want <= 63", got)
	}
}

func TestConstantDefaultLexicalRule(t *testing.T) {
	ok := []string{"42", "-3.14", "'text'", "'it''s'", "true", "NULL",
		"42::bigint", "'x'::text", "42::numeric(10,2)", "1::int[]",
		"'x'::pg_catalog.text", `7::"Weird Type"`, "1 :: int"}
	not := []string{"now()", "nextval('s'::regclass)", "a + b",
		"gen_random_uuid()", "1::int::bigint", "'unterminated",
		// anything past the cast's type name is an expression, not a
		// plain cast
		"1::int + 2", "1::int + nextval('s')", "'x'::text || 'y'",
		"1::int/*evil*/", "0::int; DROP TABLE t", "1::unknown-func()",
		"now()::timestamptz"}
	for _, s := range ok {
		if !IsConstantDefault(s) {
			t.Errorf("IsConstantDefault(%q) = false", s)
		}
	}
	for _, s := range not {
		if IsConstantDefault(s) {
			t.Errorf("IsConstantDefault(%q) = true", s)
		}
	}
}

// ---- PgSql --------------------------------------------------------------

func TestStatementsSplitOnSemicolonsNotInsideLiterals(t *testing.T) {
	statements := Statements(
		"CREATE TABLE t (a text DEFAULT ';');" +
			" CREATE FUNCTION f() RETURNS void LANGUAGE plpgsql AS $$" +
			" BEGIN PERFORM 1; PERFORM 2; END $$;" +
			" CREATE TABLE u (b int)")
	if len(statements) != 3 {
		t.Fatalf("len = %d, want 3", len(statements))
	}
	if statements[1].Tokens[0] != "CREATE" {
		t.Errorf("tokens[0] = %q", statements[1].Tokens[0])
	}
	if !strings.Contains(statements[1].Text, "PERFORM") {
		t.Errorf("text = %q", statements[1].Text)
	}
}

func TestDollarQuotedTagsRespected(t *testing.T) {
	if got := len(Statements("SELECT $body$a;b$c$body$; SELECT 2")); got != 2 {
		t.Errorf("len = %d, want 2", got)
	}
}

func TestCommentsAreSkipped(t *testing.T) {
	got := Statements(
		"-- a comment ; with semicolon\nCREATE TABLE t (a int) /* block ; */")
	if len(got) != 1 {
		t.Errorf("len = %d, want 1", len(got))
	}
}

func TestNamespaceReferenceNormalisation(t *testing.T) {
	for _, tc := range [][3]string{
		{`SELECT * FROM "app".t`, "app", "SELECT * FROM ${namespace}.t"},
		{"SELECT * FROM app.t", "app", "SELECT * FROM ${namespace}.t"},
		{"SELECT * FROM APP.t", "app", "SELECT * FROM ${namespace}.t"},
		// no false positives on prefixes or different schemas
		{"SELECT * FROM apple.t", "app", "SELECT * FROM apple.t"},
		{"SELECT * FROM other.t", "app", "SELECT * FROM other.t"},
	} {
		if got := ReplaceNamespaceRefs(tc[0], tc[1]); got != tc[2] {
			t.Errorf("replace(%q, %q) = %q, want %q", tc[0], tc[1], got, tc[2])
		}
	}
}

func TestQuotedNamespaceMatchIsCaseSensitive(t *testing.T) {
	// "APP" is a different schema from app — quoting makes it
	// case-sensitive
	for _, tc := range [][3]string{
		{`SELECT * FROM "APP".t`, "app", `SELECT * FROM "APP".t`},
		{`SELECT * FROM "Mixed".t`, "MIXED", `SELECT * FROM "Mixed".t`},
		// the exact quoted spelling still matches
		{`SELECT * FROM "MIXED".t`, "MIXED", "SELECT * FROM ${namespace}.t"},
	} {
		if got := ReplaceNamespaceRefs(tc[0], tc[1]); got != tc[2] {
			t.Errorf("replace(%q, %q) = %q, want %q", tc[0], tc[1], got, tc[2])
		}
	}
}

func TestCollapseWhitespaceLeavesLiteralsAlone(t *testing.T) {
	if got := collapseWhitespace("  SELECT   'a  b'\n  FROM   t  "); got != "SELECT 'a  b' FROM t" {
		t.Errorf("got %q", got)
	}
	if got := collapseWhitespace(
		"CREATE FUNCTION f() RETURNS void AS $$ x  y $$"); got != "CREATE FUNCTION f() RETURNS void AS $$ x  y $$" {
		t.Errorf("got %q", got)
	}
}

// ---- PgRender ------------------------------------------------------------

func scopes(id model.ObjectId) map[string]bool {
	out := map[string]bool{}
	for _, s := range NameScopes(id) {
		out[s] = true
	}
	return out
}

func eqScopes(t *testing.T, id model.ObjectId, want ...string) {
	t.Helper()
	got := scopes(id)
	if len(got) != len(want) {
		t.Fatalf("NameScopes(%v) = %v, want %v", id, got, want)
	}
	for _, s := range want {
		if !got[s] {
			t.Fatalf("NameScopes(%v) = %v, want %v", id, got, want)
		}
	}
}

func TestNameScopesFollowTheATable(t *testing.T) {
	eqScopes(t, model.MustObjectId(model.KindTable, "t", ""), "relation:t")
	eqScopes(t, model.MustObjectId(model.KindIndex, "i", "t"), "relation:i")
	eqScopes(t, model.MustObjectId(model.KindPrimaryKey, "pk", "t"),
		"relation:pk", "constraint:t.pk")
	eqScopes(t, model.MustObjectId(model.KindForeignKey, "fk", "t"),
		"constraint:t.fk")
	if n := len(scopes(model.MustObjectId(model.KindColumn, "c", "t"))); n != 0 {
		t.Errorf("column scopes = %d", n)
	}
	if n := len(scopes(model.MustObjectId(model.KindTrigger, "g", "t"))); n != 0 {
		t.Errorf("trigger scopes = %d", n)
	}
	eqScopes(t, model.MustObjectId(model.KindRoutine, "f(integer)", ""),
		"routine:f(integer)")
	// an exclusion constraint names both its pg_constraint row and its
	// backing pg_class index — a create must collide with either
	eqScopes(t, model.MustObjectId(model.KindExclusion, "ex", "t"),
		"constraint:t.ex", "relation:ex")
	// an unsupported aggregate/window occupies the routine signature space
	eqScopes(t, model.MustObjectId(model.KindOther, "routine:f(integer)", ""),
		"routine:f(integer)")
	eqScopes(t, model.MustObjectId(model.KindOther, "type:m", ""),
		"relation:m")
}

func TestCoveredChildrenForTable(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "t", "")
	seq := model.NewSchemaObject(model.MustObjectId(model.KindSequence, "t_id_seq", ""),
		model.Definition("seq"),
		[]model.ObjectId{table},
		map[string]string{AttrCreateSQL: `CREATE SEQUENCE ${namespace}."t_id_seq"`})
	desired := snap(
		obj(table, "t", map[string]string{}),
		obj(model.MustObjectId(model.KindColumn, "id", "t"), "c",
			map[string]string{AttrColumnSQL: `"id" bigint`, AttrAttnum: "1"}),
		seq)
	covered := map[model.ObjectId]bool{}
	for _, id := range CoveredChildren(missingDiff(table), desired) {
		covered[id] = true
	}
	if len(covered) != 2 ||
		!covered[model.MustObjectId(model.KindColumn, "id", "t")] ||
		!covered[model.MustObjectId(model.KindSequence, "t_id_seq", "")] {
		t.Errorf("covered = %v", covered)
	}
	if n := len(CoveredChildren(
		missingDiff(model.MustObjectId(model.KindView, "v", "")), desired)); n != 0 {
		t.Errorf("view covered = %d", n)
	}
}

func TestMissingTableRendersOneCreate(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "t", "")
	desired := snap(
		obj(table, "relkind=r;relpersistence=p", map[string]string{}),
		obj(model.MustObjectId(model.KindColumn, "id", "t"), "c",
			map[string]string{AttrColumnSQL: `"id" bigint NOT NULL`, AttrAttnum: "1"}),
		obj(model.MustObjectId(model.KindColumn, "nm", "t"), "c",
			map[string]string{AttrColumnSQL: `"nm" text`, AttrAttnum: "2"}))
	changes, err := Render(missingDiff(table), desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 1 {
		t.Fatalf("len = %d", len(changes))
	}
	change := changes[0]
	if change.Action != model.ChangeCreate || change.Safety != model.SafetyAdditive {
		t.Errorf("action=%v safety=%v", change.Action, change.Safety)
	}
	sql := change.Statements[0]
	if !strings.Contains(sql, `CREATE TABLE "core"."t"`) {
		t.Errorf("sql = %q", sql)
	}
	if !strings.Contains(sql, `"id" bigint NOT NULL`) {
		t.Errorf("sql = %q", sql)
	}
	if strings.Index(sql, `"id"`) > strings.Index(sql, `"nm"`) {
		t.Errorf("columns must render in attnum order: %s", sql)
	}
}

func TestMissingTableFoldsSerialSequence(t *testing.T) {
	table := model.MustObjectId(model.KindTable, "t", "")
	seqID := model.MustObjectId(model.KindSequence, "t_id_seq", "")
	seqObj := model.NewSchemaObject(seqID, model.Definition("seq"),
		[]model.ObjectId{table},
		map[string]string{
			AttrCreateSQL: `CREATE SEQUENCE ${namespace}."t_id_seq" AS bigint`,
			AttrOwnedSQL:  `ALTER SEQUENCE ${namespace}."t_id_seq" OWNED BY ${namespace}.t.id`})
	desired := snap(
		obj(table, "relkind=r;relpersistence=p", map[string]string{}),
		obj(model.MustObjectId(model.KindColumn, "id", "t"), "c",
			map[string]string{
				AttrColumnSQL: `"id" bigint DEFAULT nextval('${namespace}."t_id_seq"'::regclass) NOT NULL`,
				AttrAttnum:    "1"}),
		seqObj)
	changes, err := Render(missingDiff(table), desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	statements := changes[0].Statements
	if len(statements) != 3 {
		t.Fatalf("statements = %v", statements)
	}
	if !strings.HasPrefix(statements[0], `CREATE SEQUENCE "core"`) {
		t.Errorf("statements[0] = %q", statements[0])
	}
	if !strings.HasPrefix(statements[1], "CREATE TABLE") {
		t.Errorf("statements[1] = %q", statements[1])
	}
	if !strings.Contains(statements[2], "OWNED BY") {
		t.Errorf("statements[2] = %q", statements[2])
	}
}

func TestMissingColumnVariants(t *testing.T) {
	col := model.MustObjectId(model.KindColumn, "c", "t")
	// nullable → ADDITIVE
	nullable := snap(obj(col, "c", map[string]string{
		AttrColumnSQL: `"c" text`, AttrNotnull: "false"}))
	n, err := Render(missingDiff(col), nullable, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if n[0].Safety != model.SafetyAdditive || !strings.Contains(n[0].Statements[0], "ADD COLUMN") {
		t.Errorf("nullable: %v %v", n[0].Safety, n[0].Statements)
	}
	// NOT NULL + constant default → ADDITIVE
	constant := snap(obj(col, "c", map[string]string{
		AttrColumnSQL: `"c" bigint DEFAULT 0 NOT NULL`,
		AttrNotnull:   "true", AttrDefault: "0"}))
	c, err := Render(missingDiff(col), constant, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if c[0].Safety != model.SafetyAdditive {
		t.Errorf("constant: %v", c[0].Safety)
	}
	// NOT NULL + volatile default → manual DESTRUCTIVE
	volatile := snap(obj(col, "c", map[string]string{
		AttrColumnSQL: `"c" bigint DEFAULT nextval('s') NOT NULL`,
		AttrNotnull:   "true", AttrDefault: "nextval('s')"}))
	v, err := Render(missingDiff(col), volatile, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if v[0].Safety != model.SafetyDestructive || !v[0].IsManual() {
		t.Errorf("volatile: %v manual=%v", v[0].Safety, v[0].IsManual())
	}
	// NOT NULL + no default → manual DESTRUCTIVE
	noDefault := snap(obj(col, "c", map[string]string{
		AttrColumnSQL: `"c" bigint NOT NULL`, AttrNotnull: "true"}))
	m, err := Render(missingDiff(col), noDefault, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if m[0].Safety != model.SafetyDestructive || !m[0].IsManual() {
		t.Errorf("noDefault: %v manual=%v", m[0].Safety, m[0].IsManual())
	}
}

func TestDriftedViewIsDropCreatePair(t *testing.T) {
	view := model.MustObjectId(model.KindView, "v", "")
	desired := snap(obj(view, "viewdef", map[string]string{
		AttrCreateSQL: `CREATE VIEW ${namespace}."v" AS SELECT 1`,
		AttrDropSQL:   `DROP VIEW ${namespace}."v"`}))
	changes, err := Render(driftedDiff(view), desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 2 ||
		changes[0].Action != model.ChangeDrop ||
		changes[1].Action != model.ChangeCreate {
		t.Fatalf("changes = %v", changes)
	}
	if changes[0].Statements[0] != `DROP VIEW "core"."v"` {
		t.Errorf("drop = %q", changes[0].Statements[0])
	}
	if strings.Contains(changes[0].Statements[0], "CASCADE") {
		t.Error("no CASCADE anywhere (spec)")
	}
}

func TestDriftedTriggerWithPostSqlRendersWithoutCrashing(t *testing.T) {
	trg := model.MustObjectId(model.KindTrigger, "g", "t")
	desired := snap(obj(trg, "tgdef;enabled=D", map[string]string{
		AttrCreateSQL: `CREATE TRIGGER "g" BEFORE INSERT ON ${namespace}."t"` +
			` FOR EACH ROW EXECUTE FUNCTION ${namespace}."f"()`,
		AttrDropSQL: `DROP TRIGGER "g" ON ${namespace}."t"`,
		AttrPostSQL: `ALTER TABLE ${namespace}."t" DISABLE TRIGGER "g"`}))
	pair, err := Render(driftedDiff(trg), desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(pair) != 2 || pair[0].Action != model.ChangeDrop ||
		pair[1].Action != model.ChangeCreate {
		t.Fatalf("pair = %v", pair)
	}
	if !strings.Contains(pair[1].Statements[1], "DISABLE TRIGGER") {
		t.Errorf("statements = %v", pair[1].Statements)
	}
}

func TestDriftedSequencePersistenceGetsItsOwnAlterStatement(t *testing.T) {
	seq := model.MustObjectId(model.KindSequence, "s", "")
	want := "type=bigint;increment=1;min=1;max=9;start=1;cache=1" +
		";cycle=false;relpersistence=u"
	desired := snap(obj(seq, want, map[string]string{
		AttrOptionsSQL: "AS bigint INCREMENT BY 1 MINVALUE 1 MAXVALUE 9 START 1 CACHE 1 NO CYCLE",
		AttrCreateSQL:  `CREATE UNLOGGED SEQUENCE ${namespace}."s" AS bigint`,
		AttrDropSQL:    `DROP SEQUENCE ${namespace}."s"`}))
	have := model.Definition(strings.ReplaceAll(want, "relpersistence=u", "relpersistence=p"))
	w := model.Definition(want)
	d := model.Difference{ID: seq, Type: model.DifferenceDrifted, Expected: &w, Actual: &have}
	changes, err := Render(d, desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	statements := changes[0].Statements
	if !strings.HasPrefix(statements[0], `ALTER SEQUENCE "core"."s" AS bigint`) {
		t.Errorf("statements = %v", statements)
	}
	if last := statements[len(statements)-1]; last != `ALTER SEQUENCE "core"."s" SET UNLOGGED` {
		t.Errorf("last = %q", last)
	}
}

func TestDriftedRoutineSameReturnTypeIsAlter(t *testing.T) {
	fn := model.MustObjectId(model.KindRoutine, "f()", "")
	desired := snap(obj(fn, "def", map[string]string{
		AttrCreateSQL:  `CREATE OR REPLACE FUNCTION ${namespace}."f"() RETURNS void LANGUAGE sql AS 'SELECT 1'`,
		AttrReturnType: "void",
		AttrDropSQL:    `DROP FUNCTION ${namespace}."f"()`}))
	want, have := model.Definition("w"), model.Definition("RETURNS void LANGUAGE x")
	d := model.Difference{ID: fn, Type: model.DifferenceDrifted, Expected: &want, Actual: &have}
	changes, err := Render(d, desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 1 || changes[0].Action != model.ChangeAlter ||
		changes[0].Safety != model.SafetyRebuild {
		t.Fatalf("changes = %v", changes)
	}
	// return type changed → DROP+CREATE
	have2 := model.Definition("RETURNS integer LANGUAGE x")
	d2 := model.Difference{ID: fn, Type: model.DifferenceDrifted, Expected: &want, Actual: &have2}
	pair, err := Render(d2, desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(pair) != 2 || pair[0].Action != model.ChangeDrop ||
		pair[1].Action != model.ChangeCreate {
		t.Fatalf("pair = %v", pair)
	}
}

func TestDriftedSetofRoutineSameReturnTypeIsAlter(t *testing.T) {
	fn := model.MustObjectId(model.KindRoutine, "f()", "")
	desired := snap(obj(fn, "def", map[string]string{
		AttrCreateSQL:  `CREATE OR REPLACE FUNCTION ${namespace}."f"() RETURNS SETOF integer LANGUAGE sql AS 'SELECT 1'`,
		AttrReturnType: "SETOF integer",
		AttrDropSQL:    `DROP FUNCTION ${namespace}."f"()`}))
	want, have := model.Definition("w"), model.Definition("RETURNS SETOF integer LANGUAGE x")
	d := model.Difference{ID: fn, Type: model.DifferenceDrifted, Expected: &want, Actual: &have}
	changes, err := Render(d, desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 1 || changes[0].Action != model.ChangeAlter {
		t.Fatalf("changes = %v", changes)
	}
}

func TestDriftedColumnIsManualDestructive(t *testing.T) {
	col := model.MustObjectId(model.KindColumn, "c", "t")
	desired := snap(obj(col, "c", map[string]string{}))
	changes, err := Render(driftedDiff(col), desired, "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if changes[0].Action != model.ChangeAlter ||
		changes[0].Safety != model.SafetyDestructive || !changes[0].IsManual() {
		t.Errorf("change = %+v", changes[0])
	}
}

func driftedColDiff(want, have string) model.Difference {
	w, h := model.Definition(want), model.Definition(have)
	return model.Difference{
		ID:       model.MustObjectId(model.KindColumn, "c", "t"),
		Type:     model.DifferenceDrifted,
		Expected: &w, Actual: &h}
}

func columnSnap(canonical string) model.SchemaSnapshot {
	return snap(obj(model.MustObjectId(model.KindColumn, "c", "t"),
		canonical, map[string]string{}))
}

func TestDriftedColumnDefaultAltersInPlace(t *testing.T) {
	d := driftedColDiff("type=text;notnull=false;default='b'::text",
		"type=text;notnull=false;default='a'::text")
	changes, err := Render(d, columnSnap(
		"type=text;notnull=false;default='b'::text"), "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	change := changes[0]
	if change.Action != model.ChangeAlter || change.Safety != model.SafetyRebuild {
		t.Fatalf("change = %+v", change)
	}
	want := []string{`ALTER TABLE "core"."t" ALTER COLUMN "c" SET DEFAULT 'b'::text`}
	if !equalStrings(change.Statements, want) {
		t.Errorf("statements = %v", change.Statements)
	}
}

func TestDriftedColumnQuotedSemicolonsDoNotSplitCanonicalFields(t *testing.T) {
	// ';' inside a 'literal' or a "quoted identifier" is value content,
	// not a field separator — only the default differs
	want := `type="a;b";notnull=false;default='x;y'`
	d := driftedColDiff(want, `type="a;b";notnull=false;default='x;z'`)
	changes, err := Render(d, columnSnap(want), "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	change := changes[0]
	if change.Safety != model.SafetyRebuild {
		t.Fatalf("safety = %v", change.Safety)
	}
	expected := []string{`ALTER TABLE "core"."t" ALTER COLUMN "c" SET DEFAULT 'x;y'`}
	if !equalStrings(change.Statements, expected) {
		t.Errorf("statements = %v", change.Statements)
	}
}

func TestDriftedColumnNotNullSplitsByDataRisk(t *testing.T) {
	// DROP NOT NULL is a metadata change (REBUILD); SET NOT NULL validates
	// existing rows and can fail on them (rendered DESTRUCTIVE, 5.6 rule 1)
	d := driftedColDiff("type=text;notnull=true", "type=text;notnull=false")
	changes, err := Render(d, columnSnap("type=text;notnull=true"), "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 1 || changes[0].Safety != model.SafetyDestructive {
		t.Fatalf("changes = %v", changes)
	}
	if !equalStrings(changes[0].Statements,
		[]string{`ALTER TABLE "core"."t" ALTER COLUMN "c" SET NOT NULL`}) {
		t.Errorf("statements = %v", changes[0].Statements)
	}
	d = driftedColDiff("type=text;notnull=false;default='a'::text",
		"type=text;notnull=true")
	changes, err = Render(d, columnSnap(
		"type=text;notnull=false;default='a'::text"), "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		`ALTER TABLE "core"."t" ALTER COLUMN "c" SET DEFAULT 'a'::text`,
		`ALTER TABLE "core"."t" ALTER COLUMN "c" DROP NOT NULL`}
	if !equalStrings(changes[0].Statements, want) {
		t.Errorf("statements = %v", changes[0].Statements)
	}
	if changes[0].Safety != model.SafetyRebuild {
		t.Errorf("safety = %v", changes[0].Safety)
	}
}

func TestDriftedColumnSetNotNullDefersToDestructiveChange(t *testing.T) {
	// safe parts converge under the default policy; SET NOT NULL stays
	// gated — and the REBUILD half's expected omits the deferred notnull
	d := driftedColDiff("type=text;notnull=true;default='a'::text",
		"type=text;notnull=false")
	changes, err := Render(d, columnSnap(
		"type=text;notnull=true;default='a'::text"), "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(changes) != 2 {
		t.Fatalf("changes = %v", changes)
	}
	if changes[0].Safety != model.SafetyRebuild {
		t.Errorf("safety = %v", changes[0].Safety)
	}
	if !equalStrings(changes[0].Statements,
		[]string{`ALTER TABLE "core"."t" ALTER COLUMN "c" SET DEFAULT 'a'::text`}) {
		t.Errorf("statements = %v", changes[0].Statements)
	}
	if changes[0].Expected.Canonical() != "type=text;notnull=false;default='a'::text" {
		t.Errorf("expected = %q", changes[0].Expected.Canonical())
	}
	if changes[1].Safety != model.SafetyDestructive {
		t.Errorf("safety = %v", changes[1].Safety)
	}
	if !equalStrings(changes[1].Statements,
		[]string{`ALTER TABLE "core"."t" ALTER COLUMN "c" SET NOT NULL`}) {
		t.Errorf("statements = %v", changes[1].Statements)
	}
	if changes[1].Expected.Canonical() != "type=text;notnull=true;default='a'::text" {
		t.Errorf("expected = %q", changes[1].Expected.Canonical())
	}
}

func TestDriftedColumnStorageAndCompressionAlterInPlace(t *testing.T) {
	want := "type=text;notnull=false;storage=e;compression=p"
	d := driftedColDiff(want, "type=text;notnull=false;storage=x;compression=")
	changes, err := Render(d, columnSnap(want), "core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if changes[0].Safety != model.SafetyRebuild {
		t.Fatalf("safety = %v", changes[0].Safety)
	}
	expected := []string{
		`ALTER TABLE "core"."t" ALTER COLUMN "c" SET STORAGE EXTERNAL`,
		`ALTER TABLE "core"."t" ALTER COLUMN "c" SET COMPRESSION pglz`}
	if !equalStrings(changes[0].Statements, expected) {
		t.Errorf("statements = %v", changes[0].Statements)
	}
}

func TestDriftedColumnWideningTypeAltersInPlace(t *testing.T) {
	for _, pair := range [][2]string{
		{"character varying(20)", "character varying(10)"},
		{"text", "character varying(10)"},
		{"character varying", "character varying(10)"},
		{"character(8)", "character(5)"},
		{"bigint", "integer"},
		{"integer", "smallint"},
		{"numeric(12,3)", "numeric(10,2)"},
		{"numeric(12,4)", "numeric(10,2)"},
		{"numeric", "numeric(10,2)"},
	} {
		want := "type=" + pair[0] + ";notnull=false"
		have := "type=" + pair[1] + ";notnull=false"
		changes, err := Render(driftedColDiff(want, have),
			columnSnap(want), "core", spi.RenderOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if changes[0].Safety != model.SafetyRebuild {
			t.Errorf("%s <- %s: safety = %v", pair[0], pair[1], changes[0].Safety)
		}
		expected := []string{`ALTER TABLE "core"."t" ALTER COLUMN "c" TYPE ` + pair[0]}
		if !equalStrings(changes[0].Statements, expected) {
			t.Errorf("%s <- %s: statements = %v", pair[0], pair[1], changes[0].Statements)
		}
	}
}

func TestDriftedColumnUnsafeTypeStaysManual(t *testing.T) {
	for _, pair := range [][2]string{
		{"character varying(10)", "character varying(20)"}, // narrowing
		{"character varying(10)", "text"},                  // bounded
		{"integer", "bigint"},                              // narrowing
		{"boolean", "text"},                                // unrelated
		{"text", "character(5)"},                           // bpchar strips padding
		{"character varying(10)", "character(5)"},          // bpchar strips padding
		{"character(5)", "character varying(10)"},          // adds padding semantics
		{"numeric(10,4)", "numeric(10,2)"},                 // fewer integer digits
		{"numeric(10,2)", "numeric(10,4)"},                 // scale shrinks
		{"text", "integer"},
		{"timestamp with time zone", "timestamp without time zone"},
	} {
		want := "type=" + pair[0] + ";notnull=false"
		have := "type=" + pair[1] + ";notnull=false"
		changes, err := Render(driftedColDiff(want, have),
			columnSnap(want), "core", spi.RenderOptions{})
		if err != nil {
			t.Fatal(err)
		}
		if changes[0].Safety != model.SafetyDestructive {
			t.Errorf("%s <- %s: safety = %v", pair[0], pair[1], changes[0].Safety)
		}
		if !changes[0].IsManual() {
			t.Errorf("%s <- %s: not manual", pair[0], pair[1])
		}
	}
}

func TestDriftedColumnGeneratedOrIdentityStaysManual(t *testing.T) {
	changes, err := Render(driftedColDiff(
		"type=int;notnull=false;generated=s:(a + 1)",
		"type=int;notnull=false"),
		columnSnap("type=int;notnull=false;generated=s:(a + 1)"), "core",
		spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if changes[0].Safety != model.SafetyDestructive || !changes[0].IsManual() {
		t.Errorf("generated: %+v", changes[0])
	}
	changes, err = Render(driftedColDiff(
		"type=bigint;notnull=false;identity=a;identityOptions=x",
		"type=bigint;notnull=false"),
		columnSnap("type=bigint;notnull=false;identity=a;identityOptions=x"),
		"core", spi.RenderOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if changes[0].Safety != model.SafetyDestructive {
		t.Errorf("identity: %+v", changes[0])
	}
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
