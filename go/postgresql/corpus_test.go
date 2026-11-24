package postgresql_test

// End-to-end coverage over the obfuscated real-world schema corpus in
// java/fixtures/corpus/corpus-*.sql — the Go analog of
// CorpusFixture.test.ts. Each file is a captured deployment script: the
// A.7 migration steps are applied in memory (literal schema qualifier
// to ${namespace}; deployment-only statements removed), then the script
// is reconciled against a real namespace and asserted to converge and
// stay idempotent.

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql"
)

var (
	roleRef   = regexp.MustCompile(`\bTO (w\d{3})\b`)
	extension = regexp.MustCompile(`CREATE EXTENSION IF NOT EXISTS (\w+)`)

	fixtureWord   = regexp.MustCompile(`\bfixture\b`)
	advisoryLock  = regexp.MustCompile(`(?m)^\s*SELECT\s+pg_advisory_lock[^;]*;\s*`)
	createSchema  = regexp.MustCompile(`(?m)^\s*CREATE\s+SCHEMA[^;]*;\s*`)
	createExt     = regexp.MustCompile(`(?m)^\s*CREATE\s+EXTENSION[^;]*;\s*`)
	nullsDistinct = regexp.MustCompile(`(?i)\s+NULLS\s+(?:NOT\s+)?DISTINCT`)
	secInvLead    = regexp.MustCompile(`(?i)security_invoker\s*=\s*\w+\s*,\s*`)
	secInvTrail   = regexp.MustCompile(`(?i),\s*security_invoker\s*=\s*\w+`)
	secInvWith    = regexp.MustCompile(`(?i)WITH\s*\(\s*security_invoker\s*=\s*\w+\s*\)`)
	emptyWith     = regexp.MustCompile(`(?i)WITH\s*\(\s*\)`)
)

var corpusDir = filepath.Join("..", "..", "java", "fixtures", "corpus")

// migrateCorpus applies the A.7 transform: literal qualifier to
// placeholder, deployment statements removed.
func migrateCorpus(raw string) string {
	s := fixtureWord.ReplaceAllLiteralString(raw, "${namespace}")
	s = advisoryLock.ReplaceAllString(s, "")
	s = createSchema.ReplaceAllString(s, "")
	return createExt.ReplaceAllString(s, "")
}

// preFifteenCompat downgrades v15-only syntax on pre-15 servers — same
// coverage trade-off as the uuidv7 stand-in.
func preFifteenCompat(script string) string {
	s := nullsDistinct.ReplaceAllString(script, "")
	s = secInvLead.ReplaceAllString(s, "")
	s = secInvTrail.ReplaceAllString(s, "")
	s = secInvWith.ReplaceAllString(s, "")
	return emptyWith.ReplaceAllString(s, "")
}

func quoteID(t *testing.T, d interface {
	QuoteIdentifier(string) (string, error)
}, id string) string {
	t.Helper()
	q, err := d.QuoteIdentifier(id)
	if err != nil {
		t.Fatal(err)
	}
	return q
}

// prepareEnvironment creates the roles referenced by GRANT/policy
// statements, and the extensions the script declares (relocatable ones
// go to pg_catalog so the scratch search_path still resolves them).
// Returns a skip reason or "".
func prepareEnvironment(t *testing.T, raw string) string {
	t.Helper()
	ctx := context.Background()
	d := postgresql.Dialect{}
	if strings.Contains(raw, "uuidv7") {
		if err := ensureUuidv7(ctx); err != nil {
			t.Fatalf("ensureUuidv7: %v", err)
		}
	}
	roles := map[string]bool{}
	for _, m := range roleRef.FindAllStringSubmatch(raw, -1) {
		roles[m[1]] = true
	}
	var sorted []string
	for r := range roles {
		sorted = append(sorted, r)
	}
	sort.Strings(sorted)
	for _, role := range sorted {
		if err := execute(ctx, "DROP ROLE IF EXISTS "+
			quoteID(t, d, role)); err != nil {
			t.Fatalf("drop role: %v", err)
		}
		if err := execute(ctx, "CREATE ROLE "+
			quoteID(t, d, role)); err != nil {
			t.Fatalf("create role: %v", err)
		}
	}
	for _, m := range extension.FindAllStringSubmatch(raw, -1) {
		ext := m[1]
		c, err := scratchPool.Conn(ctx)
		if err != nil {
			t.Fatal(err)
		}
		var n int
		if err := c.QueryRowContext(ctx,
			"SELECT count(*) FROM pg_available_extensions WHERE name = $1",
			ext).Scan(&n); err != nil {
			c.Close()
			t.Fatal(err)
		}
		c.Close()
		if n == 0 {
			return "extension " + ext + " not available on this server"
		}
		if err := execute(ctx, "CREATE EXTENSION IF NOT EXISTS "+
			quoteID(t, d, ext)+" SCHEMA pg_catalog"); err != nil {
			t.Fatalf("create extension %s: %v", ext, err)
		}
	}
	return ""
}

func corpusReconciler(t *testing.T, namespace, script string,
	unsupportedReport bool) *core.SchemaReconciler {
	t.Helper()
	b := core.NewBuilder().DataSource(dataSource()).Namespace(namespace).
		DesiredState(core.DesiredStateOfScript(script)).
		Dialect(postgresql.Dialect{})
	if unsupportedReport {
		p, err := core.DefaultPolicy().WithUnsupported(core.DecisionReport)
		if err != nil {
			t.Fatal(err)
		}
		b = b.Policy(p)
	}
	r, err := b.Build()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func migratedCorpus(t *testing.T, file string) string {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(corpusDir, file))
	if err != nil {
		t.Fatal(err)
	}
	script := migrateCorpus(string(raw))
	if major, err := serverMajor(context.Background()); err != nil {
		t.Fatal(err)
	} else if major < 15 {
		script = preFifteenCompat(script)
	}
	return script
}

func TestCorpusFixture(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}

	entries, err := filepath.Glob(filepath.Join(corpusDir, "corpus-*.sql"))
	if err != nil || len(entries) == 0 {
		t.Fatalf("no corpus files under %s", corpusDir)
	}
	sort.Strings(entries)

	for _, path := range entries {
		file := filepath.Base(path)
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		rawStr := string(raw)

		t.Run(file+" reconciles and is idempotent", func(t *testing.T) {
			namespace := "corpus_" + nextNamespace()
			if err := execute(ctx, "CREATE SCHEMA "+
				quoteID(t, d, namespace)); err != nil {
				t.Fatal(err)
			}
			defer execute(ctx, "DROP SCHEMA "+quoteID(t, d, namespace)+
				" CASCADE")

			if missing := prepareEnvironment(t, rawStr); missing != "" {
				t.Skip(missing)
			}
			script := migratedCorpus(t, file)
			// a corpus file whose GRANTs land on unmanaged targets
			// still has privileges:* acl rows — unsupported by design
			// (5.4): those files run under unsupported=REPORT and the
			// unsupported set is stable across runs.
			expectUnsupported := strings.Contains(rawStr, "GRANT")

			first, err := corpusReconciler(t, namespace, script,
				expectUnsupported).Reconcile(ctx)
			if err != nil {
				t.Fatalf("reconcile: %v", err)
			}
			if first.Outcome != core.OutcomeReconciled {
				t.Fatalf("outcome = %v, reported: %v unsupported: %v",
					first.Outcome, first.Reported, first.Unsupported)
			}

			second, err := corpusReconciler(t, namespace, script,
				expectUnsupported).Reconcile(ctx)
			if err != nil {
				t.Fatalf("re-run: %v", err)
			}
			if second.Outcome != core.OutcomeUpToDate {
				t.Fatalf("re-run = %v", second.Outcome)
			}
			if expectUnsupported {
				names := func(ds []model.Difference) []string {
					var out []string
					for _, d := range ds {
						out = append(out, d.ID.Name)
					}
					sort.Strings(out)
					return out
				}
				a, b := names(first.Unsupported), names(second.Unsupported)
				if strings.Join(a, "|") != strings.Join(b, "|") {
					t.Errorf("unsupported objects must report identically"+
						" on every run: %v vs %v", a, b)
				}
			} else if len(second.Unsupported) != 0 {
				t.Errorf("unsupported diffs appeared on the idempotent"+
					" re-run: %v", second.Unsupported)
			}
		})

		t.Run(file+" dump round-trips", func(t *testing.T) {
			namespace := "corpus_" + nextNamespace()
			if err := execute(ctx, "CREATE SCHEMA "+
				quoteID(t, d, namespace)); err != nil {
				t.Fatal(err)
			}
			defer execute(ctx, "DROP SCHEMA "+quoteID(t, d, namespace)+
				" CASCADE")

			if missing := prepareEnvironment(t, rawStr); missing != "" {
				t.Skip(missing)
			}
			script := migratedCorpus(t, file)
			expectUnsupported := strings.Contains(rawStr, "GRANT")
			if report, err := corpusReconciler(t, namespace, script,
				expectUnsupported).Reconcile(ctx); err != nil {
				t.Fatalf("reconcile: %v", err)
			} else if report.Outcome != core.OutcomeReconciled {
				t.Fatalf("outcome = %v", report.Outcome)
			}

			dumper, err := core.NewBuilder().DataSource(dataSource()).
				Namespace(namespace).Dialect(d).Build()
			if err != nil {
				t.Fatal(err)
			}
			dump, err := dumper.Dump(ctx)
			if err != nil {
				t.Fatalf("dump: %v", err)
			}
			// the emitted script is a desired-state script — feeding
			// it back on the same namespace must be a no-op
			self, err := corpusReconciler(t, namespace, dump,
				expectUnsupported).Reconcile(ctx)
			if err != nil {
				t.Fatalf("dump replay: %v", err)
			}
			if self.Outcome != core.OutcomeUpToDate {
				t.Fatalf("dump replay reported changes: %v",
					self.Plan.Changes)
			}
			if again, err := dumper.Dump(ctx); err != nil {
				t.Fatal(err)
			} else if again != dump {
				t.Error("second dump is not byte-identical")
			}
		})
	}
}
