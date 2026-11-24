package postgresql_test

// Spec A.7: the captured deployment script
// java/fixtures/deployment/schema.sql is migrated in memory (literal
// svc. qualifier to ${namespace}., deployment-only statements removed),
// reconciled against a real namespace, and asserted to converge, plan
// deterministically, and dump byte-identically.

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"testing"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql"
)

var (
	deploymentFile = filepath.Join("..", "..", "java", "fixtures",
		"deployment", "schema.sql")
	svcQualifier = regexp.MustCompile(`svc\.`)
)

// migrateDeployment applies the A.7 transform: literal qualifier →
// placeholder, deployment-only statements removed (the library owns
// locking + namespace).
func migrateDeployment(t *testing.T) string {
	t.Helper()
	raw, err := os.ReadFile(deploymentFile)
	if err != nil {
		t.Fatal(err)
	}
	s := svcQualifier.ReplaceAllLiteralString(string(raw), "${namespace}.")
	s = advisoryLock.ReplaceAllString(s, "")
	return createSchema.ReplaceAllString(s, "")
}

func deploymentReconciler(t *testing.T, namespace,
	script string) *core.SchemaReconciler {
	t.Helper()
	r, err := core.NewBuilder().DataSource(dataSource()).
		Namespace(namespace).
		DesiredState(core.DesiredStateOfScript(script)).
		Dialect(postgresql.Dialect{}).Build()
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func deploymentSnapshot(t *testing.T, namespace string) model.SchemaSnapshot {
	t.Helper()
	ctx := context.Background()
	c, err := dataSource().Conn(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if err := c.SetAutoCommit(ctx, false); err != nil {
		t.Fatal(err)
	}
	snap, err := postgresql.Dialect{}.ReadSnapshot(ctx, c, namespace)
	if err != nil {
		c.Rollback(ctx)
		t.Fatal(err)
	}
	if err := c.Rollback(ctx); err != nil {
		t.Fatal(err)
	}
	return snap
}

func TestDeploymentFixture(t *testing.T) {
	requireDB(t)
	ctx := context.Background()
	d := postgresql.Dialect{}

	newNamespace := func(t *testing.T) string {
		namespace := "svc_e2e_" + nextNamespace()
		if err := execute(ctx, "CREATE SCHEMA "+
			quoteID(t, d, namespace)); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			execute(ctx, "DROP SCHEMA "+quoteID(t, d, namespace)+" CASCADE")
		})
		return namespace
	}

	t.Run("A.7 migrated deployment fixture reconciles and is idempotent",
		func(t *testing.T) {
			namespace := newNamespace(t)
			if err := ensureUuidv7(ctx); err != nil {
				t.Fatal(err)
			}
			script := migrateDeployment(t)

			first, err := deploymentReconciler(t, namespace,
				script).Reconcile(ctx)
			if err != nil {
				t.Fatalf("reconcile: %v", err)
			}
			if first.Outcome != core.OutcomeReconciled {
				t.Fatalf("outcome = %v", first.Outcome)
			}
			if len(first.Unsupported) != 0 {
				t.Errorf("unexpected unsupported diffs: %v",
					first.Unsupported)
			}

			snap := deploymentSnapshot(t, namespace)
			for _, id := range []model.ObjectId{
				{Kind: model.KindTable, Name: "plans"},
				{Kind: model.KindTable, Name: "workflow_runs"},
				{Kind: model.KindSequence, Name: "fencing_seq"},
			} {
				if _, ok := snap.Objects[id]; !ok {
					t.Errorf("missing %s", id)
				}
			}
			var triggers, views, routines int
			for id := range snap.Objects {
				switch id.Kind {
				case model.KindTrigger:
					triggers++
				case model.KindView:
					views++
				case model.KindRoutine:
					routines++
				}
			}
			if triggers == 0 {
				t.Error("expected a trigger")
			}
			if views < 10 {
				t.Errorf("expected the fixture views, found %d", views)
			}
			if routines == 0 {
				t.Error("expected a routine")
			}

			again, err := deploymentReconciler(t, namespace,
				script).Reconcile(ctx)
			if err != nil {
				t.Fatal(err)
			}
			if again.Outcome != core.OutcomeUpToDate {
				t.Errorf("re-run = %v", again.Outcome)
			}
		})

	t.Run("A.7 migrated fixture plans deterministically", func(t *testing.T) {
		namespace := newNamespace(t)
		if err := ensureUuidv7(ctx); err != nil {
			t.Fatal(err)
		}
		script := migrateDeployment(t)
		one, err := deploymentReconciler(t, namespace, script).Plan(ctx)
		if err != nil {
			t.Fatal(err)
		}
		two, err := deploymentReconciler(t, namespace, script).Plan(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if one.ToSQL() != two.ToSQL() {
			t.Error("plan is not deterministic")
		}
	})

	t.Run("3.1 dump() round-trips a reconciled namespace", func(t *testing.T) {
		namespace := newNamespace(t)
		if err := ensureUuidv7(ctx); err != nil {
			t.Fatal(err)
		}
		report, err := deploymentReconciler(t, namespace,
			migrateDeployment(t)).Reconcile(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if report.Outcome != core.OutcomeReconciled {
			t.Fatalf("outcome = %v", report.Outcome)
		}
		dumper, err := core.NewBuilder().DataSource(dataSource()).
			Namespace(namespace).Dialect(d).Build()
		if err != nil {
			t.Fatal(err)
		}
		dump, err := dumper.Dump(ctx)
		if err != nil {
			t.Fatal(err)
		}
		// the emitted script is a desired-state script: feeding it
		// back on the same namespace must be a no-op
		self, err := deploymentReconciler(t, namespace, dump).Reconcile(ctx)
		if err != nil {
			t.Fatal(err)
		}
		if self.Outcome != core.OutcomeUpToDate {
			t.Fatalf("dump replay outcome = %v", self.Outcome)
		}
		if len(self.Plan.Changes) != 0 {
			t.Errorf("dump replay reported changes: %v", self.Plan.Changes)
		}
		// and the second dump is byte-identical
		if again, err := dumper.Dump(ctx); err != nil {
			t.Fatal(err)
		} else if again != dump {
			t.Error("second dump is not byte-identical")
		}
	})
}
