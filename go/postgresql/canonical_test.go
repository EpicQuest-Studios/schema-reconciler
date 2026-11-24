package postgresql_test

// Spec A.5: each canonical definition is the engine's pg_get_*def
// text, normalised (5.3) — the Go analog of
// CanonicalDefinition.test.ts.

import (
	"context"
	"strings"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql/internal/pg"
)

func firstCol(t *testing.T, c spi.Conn, sql string) string {
	t.Helper()
	var s string
	if err := c.QueryRowContext(context.Background(), sql).Scan(&s); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
	return s
}

func TestCanonicalDefinition(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}

	t.Run("canonical definitions are the normalised engine definitions",
		func(t *testing.T) {
			schema := "canon_" + nextNamespace()
			q := quoteID(t, d, schema)
			c, err := dataSource().Conn(ctx)
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			if err := c.SetAutoCommit(ctx, false); err != nil {
				t.Fatal(err)
			}
			defer c.Rollback(ctx)
			for _, sql := range []string{
				"CREATE SCHEMA " + q,
				"SET LOCAL search_path = " + q,
				"CREATE TABLE t (id bigint PRIMARY KEY," +
					" name text NOT NULL DEFAULT 'x', ref bigint," +
					" CONSTRAINT t_chk CHECK (id > 0))",
				"CREATE INDEX t_name ON t (lower(name)) WHERE id > 5",
				"CREATE VIEW v AS SELECT id, name FROM t",
				"CREATE FUNCTION f(a int) RETURNS int" +
					" LANGUAGE sql AS $$ SELECT a + 1 $$",
				"CREATE FUNCTION trg() RETURNS trigger" +
					" LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$",
				"CREATE TRIGGER tr BEFORE INSERT ON t" +
					" FOR EACH ROW EXECUTE FUNCTION trg()",
				"CREATE TYPE mood AS ENUM ('b', 'a')",
				"CREATE MATERIALIZED VIEW mv AS SELECT id FROM t",
				`CREATE DOMAIN loctext AS text COLLATE "C"` +
					" NOT NULL DEFAULT 'x' CHECK (VALUE <> '')",
				"ALTER TABLE t ENABLE ROW LEVEL SECURITY",
				"CREATE POLICY p_sel ON t AS RESTRICTIVE" +
					" FOR SELECT TO PUBLIC USING (id > 0)",
				"COMMENT ON TABLE t IS 'kept'",
			} {
				if _, err := c.ExecContext(ctx, sql); err != nil {
					t.Fatalf("%s: %v", sql, err)
				}
			}

			snap, err := d.ReadSnapshot(ctx, c, schema)
			if err != nil {
				t.Fatal(err)
			}
			ns := schema
			get := func(id model.ObjectId) model.SchemaObject {
				obj, ok := snap.Objects[id]
				if !ok {
					t.Fatalf("missing %s", id)
				}
				return obj
			}

			matview := get(model.ObjectId{Kind: model.KindMaterializedView,
				Name: "mv"})
			if want := pg.Normalize("viewdef="+firstCol(t, c,
				"SELECT pg_get_viewdef('mv'::regclass, true)"), ns); !strings.HasPrefix(
				matview.Definition.Canonical(), want) {
				t.Errorf("matview canonical = %q, want prefix %q",
					matview.Definition.Canonical(), want)
			}
			if !strings.Contains(matview.Definition.Canonical(),
				";populated=true") {
				t.Errorf("matview canonical = %q",
					matview.Definition.Canonical())
			}

			if got := get(model.ObjectId{Kind: model.KindType,
				Name: "mood"}).Definition.Canonical(); got != "typtype=e;labels='b','a'" {
				t.Errorf("mood = %q", got)
			}

			loctext := get(model.ObjectId{Kind: model.KindDomain,
				Name: "loctext"})
			want := "basetype=text;notnull=true;collation=\"C\";default=" +
				firstCol(t, c, "SELECT pg_get_expr(typdefaultbin, 0)"+
					" FROM pg_type WHERE typname = 'loctext'"+
					" AND typnamespace = current_schema()::regnamespace") +
				";check.loctext_check=" + firstCol(t, c,
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint"+
					" WHERE contypid = 'loctext'::regtype"+
					" AND contype = 'c'")
			if got := loctext.Definition.Canonical(); got != want {
				t.Errorf("loctext = %q, want %q", got, want)
			}

			pol := get(model.ObjectId{Kind: model.KindPolicy,
				Name: "p_sel", Parent: "t"})
			want = "cmd=r;permissive=false;roles=PUBLIC;using=" +
				firstCol(t, c, "SELECT pg_get_expr(polqual, polrelid)"+
					" FROM pg_policy WHERE polname = 'p_sel'"+
					" AND polrelid = 't'::regclass")
			if got := pol.Definition.Canonical(); got != want {
				t.Errorf("pol = %q, want %q", got, want)
			}

			table := get(model.ObjectId{Kind: model.KindTable, Name: "t"})
			if !strings.Contains(table.Definition.Canonical(), ";rls=true") {
				t.Errorf("table canonical = %q", table.Definition.Canonical())
			}

			if got := get(model.ObjectId{Kind: model.KindComment,
				Name: "t"}).Definition.Canonical(); got != "kept" {
				t.Errorf("comment = %q", got)
			}

			index := get(model.ObjectId{Kind: model.KindIndex,
				Name: "t_name", Parent: "t"})
			if want := pg.Normalize(firstCol(t, c,
				"SELECT pg_get_indexdef('t_name'::regclass)"), ns); index.Definition.Canonical() != want {
				t.Errorf("index = %q, want %q",
					index.Definition.Canonical(), want)
			}

			check := get(model.ObjectId{Kind: model.KindCheck,
				Name: "t_chk", Parent: "t"})
			if want := pg.Normalize(firstCol(t, c,
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint"+
					" WHERE conname = 't_chk'"+
					" AND conrelid = 't'::regclass"), ns); check.Definition.Canonical() != want {
				t.Errorf("check = %q, want %q",
					check.Definition.Canonical(), want)
			}

			pk := get(model.ObjectId{Kind: model.KindPrimaryKey,
				Name: "t_pkey", Parent: "t"})
			if want := pg.Normalize(firstCol(t, c,
				"SELECT pg_get_constraintdef(oid) FROM pg_constraint"+
					" WHERE conname = 't_pkey'"), ns); pk.Definition.Canonical() != want {
				t.Errorf("pk = %q, want %q", pk.Definition.Canonical(), want)
			}

			view := get(model.ObjectId{Kind: model.KindView, Name: "v"})
			if want := pg.Normalize("viewdef="+firstCol(t, c,
				"SELECT pg_get_viewdef('v'::regclass, true)"), ns); !strings.HasPrefix(
				view.Definition.Canonical(), want) {
				t.Errorf("view = %q, want prefix %q",
					view.Definition.Canonical(), want)
			}

			routine := get(model.ObjectId{Kind: model.KindRoutine,
				Name: "f(integer)"})
			if want := pg.Normalize(firstCol(t, c,
				"SELECT pg_get_functiondef('f(int)'::regprocedure)"), ns); routine.Definition.Canonical() != want {
				t.Errorf("routine = %q, want %q",
					routine.Definition.Canonical(), want)
			}

			trigger := get(model.ObjectId{Kind: model.KindTrigger,
				Name: "tr", Parent: "t"})
			if want := pg.Normalize(firstCol(t, c,
				"SELECT pg_get_triggerdef(oid, true)"+
					" FROM pg_trigger WHERE tgname = 'tr'"), ns) +
				";enabled=O"; trigger.Definition.Canonical() != want {
				t.Errorf("trigger = %q, want %q",
					trigger.Definition.Canonical(), want)
			}

			column := get(model.ObjectId{Kind: model.KindColumn,
				Name: "name", Parent: "t"})
			want = "type=" + firstCol(t, c,
				"SELECT format_type(atttypid, atttypmod)"+
					" FROM pg_attribute"+
					" WHERE attrelid = 't'::regclass"+
					" AND attname = 'name'") +
				";notnull=true;default=" + firstCol(t, c,
				"SELECT pg_get_expr(adbin, adrelid) FROM pg_attrdef"+
					" WHERE adrelid = 't'::regclass"+
					" AND adnum = (SELECT attnum FROM pg_attribute"+
					" WHERE attrelid = 't'::regclass"+
					" AND attname = 'name')") +
				";storage=" + firstCol(t, c,
				"SELECT attstorage FROM pg_attribute"+
					" WHERE attrelid = 't'::regclass"+
					" AND attname = 'name'") +
				";compression=" + firstCol(t, c,
				"SELECT coalesce(attcompression::text, '')"+
					" FROM pg_attribute"+
					" WHERE attrelid = 't'::regclass"+
					" AND attname = 'name'")
			if got := column.Definition.Canonical(); got != want {
				t.Errorf("column = %q, want %q", got, want)
			}
		})

	t.Run("policy roles are quoted individually", func(t *testing.T) {
		schema := "canonr_" + nextNamespace()
		comma := "a,b_" + schema
		plain := "ac_" + schema
		qComma := quoteID(t, d, comma)
		qPlain := quoteID(t, d, plain)
		c, err := dataSource().Conn(ctx)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		if err := c.SetAutoCommit(ctx, false); err != nil {
			t.Fatal(err)
		}
		defer c.Rollback(ctx)
		for _, sql := range []string{
			"CREATE ROLE " + qComma,
			"CREATE ROLE " + qPlain,
			"CREATE SCHEMA " + quoteID(t, d, schema),
			"SET LOCAL search_path = " + quoteID(t, d, schema),
			"CREATE TABLE t (id bigint)",
			"CREATE POLICY p_multi ON t FOR SELECT TO " + qComma +
				"," + qPlain + " USING (true)",
		} {
			if _, err := c.ExecContext(ctx, sql); err != nil {
				t.Fatalf("%s: %v", sql, err)
			}
		}
		snap, err := d.ReadSnapshot(ctx, c, schema)
		if err != nil {
			t.Fatal(err)
		}
		pol, ok := snap.Objects[model.ObjectId{Kind: model.KindPolicy,
			Name: "p_multi", Parent: "t"}]
		if !ok {
			t.Fatal("missing p_multi")
		}
		if want := "cmd=r;permissive=true;roles=" + qComma + "," + qPlain +
			";using=true"; pol.Definition.Canonical() != want {
			t.Errorf("pol = %q, want %q", pol.Definition.Canonical(), want)
		}
		if want := "CREATE POLICY \"p_multi\" ON ${namespace}.\"t\"" +
			" FOR SELECT TO " + qComma + "," + qPlain +
			" USING (true)"; pol.Attributes[pg.AttrCreateSQL] != want {
			t.Errorf("createSql = %q, want %q",
				pol.Attributes[pg.AttrCreateSQL], want)
		}
	})
}
