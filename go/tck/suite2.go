package tck

import (
	"strings"
	"time"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// ---- C-34 … C-67 -------------------------------------------------------

func c34(s *suite) {
	if !s.assumeKind(model.KindColumn) {
		return
	}
	t := s.t
	s.reconcile("CREATE TABLE t (id bigint, payload text)")
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler("CREATE TABLE t (id bigint,"+
			" payload boolean)", core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	obj := s.snapshot().Objects[columnID("t", "payload")]
	if !strings.Contains(obj.Definition.Canonical(), "type=text") {
		t.Error("the column type must be unchanged after the rejected plan")
	}
}

func c35(s *suite) {
	t := s.t
	var unsupported *UnsupportedScript
	var category string
	if s.h.UnsupportedScript != nil {
		unsupported = s.h.UnsupportedScript()
	}
	if s.h.UnsupportedCategory != nil {
		category = s.h.UnsupportedCategory()
	}
	if unsupported == nil || category == "" {
		t.Skip("dialect declares every kind it can detect")
		return
	}
	script := unsupported.Script + "; CREATE TABLE independent_t (id bigint)"
	policy, err := core.DefaultPolicy().WithUnsupportedCategory(category,
		core.DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	report := s.reconcilePolicy(script, policy)
	found := false
	for _, d := range report.Unsupported {
		if d.Type == model.DifferenceUnsupported {
			found = true
		}
	}
	if !found {
		t.Error("expected UNSUPPORTED differences")
	}
	if !s.present(tableID("independent_t")) {
		t.Error("independent changes are still applied")
	}
	policy, err = core.DefaultPolicy().WithUnsupportedCategory("no-match",
		core.DecisionReport)
	if err != nil {
		t.Fatal(err)
	}
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(script, policy).Reconcile(s.ctx)
		return err
	})
}

func c36(s *suite) {
	t := s.t
	report := s.reconcile("CREATE TABLE t (id bigint)")
	timings := report.Timings
	for name, v := range map[string]time.Duration{
		"lockWait": timings.LockWait, "readActual": timings.ReadActual,
		"loadDesired": timings.LoadDesired, "plan": timings.Plan,
		"apply": timings.Apply, "verify": timings.Verify,
	} {
		if v < 0 {
			t.Errorf("%s is negative", name)
		}
	}
	if report.Elapsed < 0 {
		t.Error("elapsed is negative")
	}
}

func c37(s *suite) {
	if !s.assumeKind(model.KindMaterializedView) {
		return
	}
	t := s.t
	s.reconcile("CREATE TABLE t (id bigint)")
	s.execute("INSERT INTO " + s.qualified("t") + " VALUES (1), (2)")
	script := `CREATE TABLE t (id bigint);
CREATE MATERIALIZED VIEW mv AS SELECT id FROM t`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == matviewID("mv") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the materialized view")
	// the view must hold its stored rows, not just exist
	if got := s.count("mv"); got != 2 {
		t.Errorf("mv rows = %d", got)
	}
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c38(s *suite) {
	if !s.assumeKind(model.KindMaterializedView) {
		return
	}
	t := s.t
	s.reconcile(`CREATE TABLE t (id bigint);
CREATE MATERIALIZED VIEW mv AS SELECT id FROM t`)
	s.execute("INSERT INTO " + s.qualified("t") + " VALUES (1), (2)")
	s.execute("REFRESH MATERIALIZED VIEW " + s.qualified("mv"))
	// a stored-data recreate is DESTRUCTIVE: the default policy refuses it
	widened := `CREATE TABLE t (id bigint);
CREATE MATERIALIZED VIEW mv AS SELECT id, id + 1 AS plus FROM t`
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(widened, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	report := s.reconcilePolicy(widened,
		core.DefaultPolicy().WithDestructive(core.DecisionApply))
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == matviewID("mv") && c.Safety == model.SafetyDestructive
	}, "expected DESTRUCTIVE changes for the materialized view")
	if got := s.count("mv"); got != 2 {
		t.Errorf("mv rows = %d", got)
	}
	if got := s.reconcilePolicy(widened,
		core.DefaultPolicy().WithDestructive(core.DecisionApply)).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c39(s *suite) {
	if !s.assumeKind(model.KindType) {
		return
	}
	t := s.t
	script := "CREATE TYPE mood AS ENUM ('sad', 'ok')"
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == typeID("mood") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the enum")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a pure in-order append renders ALTER TYPE ... ADD VALUE
	widened := "CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')"
	grown := s.reconcile(widened)
	if grown.Outcome != core.OutcomeReconciled {
		t.Fatalf("grown = %v", grown.Outcome)
	}
	s.anyChange(grown.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == typeID("mood") && c.Safety == model.SafetyAdditive) {
			return false
		}
		for _, st := range c.Statements {
			if strings.Contains(st, "ADD VALUE") {
				return true
			}
		}
		return false
	}, "expected an ADDITIVE ADD VALUE change")
	if got := s.reconcile(widened).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c40(s *suite) {
	if !s.assumeKind(model.KindType) {
		return
	}
	s.reconcile("CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')")
	// a removed label cannot be altered in place — fail closed
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(
			"CREATE TYPE mood AS ENUM ('sad', 'ok')",
			core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	// a reordered label set fails the same way
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(
			"CREATE TYPE mood AS ENUM ('ok', 'sad', 'happy')",
			core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	// the type is untouched: the original still verifies UP_TO_DATE
	if got := s.reconcile(
		"CREATE TYPE mood AS ENUM ('sad', 'ok', 'happy')").Outcome; got != core.OutcomeUpToDate {
		s.t.Errorf("original = %v", got)
	}
}

func c41(s *suite) {
	if !s.assumeKind(model.KindDomain) {
		return
	}
	t := s.t
	script := `CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1 CHECK (VALUE > 0);
CREATE TABLE t (a posint)`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == domainID("posint") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the domain")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// the domain behaves like its definition says: defaults fill,
	// CHECK rejects
	s.execute("INSERT INTO " + s.qualified("t") + " DEFAULT VALUES")
	if got := s.count("t"); got != 1 {
		t.Errorf("rows = %d", got)
	}
}

func c42(s *suite) {
	if !s.assumeKind(model.KindDomain) {
		return
	}
	t := s.t
	s.reconcile("CREATE DOMAIN posint AS integer NOT NULL" +
		" DEFAULT 1 CHECK (VALUE > 0)")
	// drop the default, drop NOT NULL, drop the check — all safe
	report := s.reconcile("CREATE DOMAIN posint AS integer")
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == domainID("posint") && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.Contains(st, "ALTER DOMAIN") {
				return true
			}
		}
		return false
	}, "expected a REBUILD ALTER DOMAIN")
	if got := s.reconcile("CREATE DOMAIN posint AS integer").Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c43(s *suite) {
	if !s.assumeKind(model.KindDomain) {
		return
	}
	full := "CREATE DOMAIN posint AS integer NOT NULL DEFAULT 1" +
		" CHECK (VALUE > 0)"
	s.reconcile(full)
	// the base type cannot alter in place — fail closed
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler("CREATE DOMAIN posint AS bigint"+
			" NOT NULL DEFAULT 1 CHECK (VALUE > 0)",
			core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	if got := s.reconcile(full).Outcome; got != core.OutcomeUpToDate {
		s.t.Errorf("original = %v", got)
	}
}

func c44(s *suite) {
	if !s.assumeKind(model.KindPolicy) {
		return
	}
	t := s.t
	script := `CREATE TABLE t (id bigint, owner text);
ALTER TABLE t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON t FOR SELECT USING (owner = current_user)`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == policyID("t", "p_sel") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the policy")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a changed USING clause recreates the policy in place
	widened := `CREATE TABLE t (id bigint, owner text);
ALTER TABLE t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON t FOR SELECT USING (id > 0)`
	drifted := s.reconcile(widened)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == policyID("t", "p_sel") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the policy")
	if got := s.reconcile(widened).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c45(s *suite) {
	if !s.assumeKind(model.KindExtension) {
		return
	}
	t := s.t
	ddl, ok := s.optionalHook("extensionDdl", s.h.ExtensionDdl)
	if !ok {
		return
	}
	report := s.reconcile(ddl)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	var extensions []model.PlannedChange
	for _, c := range report.Applied {
		if c.Target.Kind == model.KindExtension {
			extensions = append(extensions, c)
		}
	}
	if len(extensions) != 1 {
		t.Fatalf("expected exactly one extension change: %v", report.Applied)
	}
	if extensions[0].Safety != model.SafetyAdditive {
		t.Errorf("safety = %v", extensions[0].Safety)
	}
	if got := s.reconcile(ddl).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c46(s *suite) {
	if !s.assumeKind(model.KindComment) {
		return
	}
	t := s.t
	script := `CREATE TABLE t (id bigint);
COMMENT ON TABLE t IS 'kept table'`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target.Kind == model.KindComment &&
			c.Action == model.ChangeAlter && c.Safety == model.SafetyRebuild
	}, "expected an ALTER/REBUILD comment change")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a changed comment rewrites in place
	renamed := `CREATE TABLE t (id bigint);
COMMENT ON TABLE t IS 'renamed table'`
	drifted := s.reconcile(renamed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target.Kind == model.KindComment &&
			c.Safety == model.SafetyRebuild
	}, "expected a REBUILD comment change")
	if got := s.reconcile(renamed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c47(s *suite) {
	if !s.assumeKind(model.KindComment) {
		return
	}
	t := s.t
	s.execute("CREATE TABLE " + s.qualified("t") + " (id bigint)")
	s.execute("COMMENT ON TABLE " + s.qualified("t") + " IS 'ambient'")
	report := s.reconcile("CREATE TABLE t (id bigint)")
	if report.Outcome != core.OutcomeUpToDate {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	found := false
	for _, d := range report.Unexpected {
		if d.ID.Kind == model.KindComment {
			found = true
		}
	}
	if !found {
		t.Errorf("expected the ambient comment reported: %v", report.Unexpected)
	}
	// and it is still there afterwards
	c, err := s.dataSource().Conn(s.ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	var desc string
	if err := c.QueryRowContext(s.ctx, "SELECT obj_description('"+
		s.qualified("t")+"'::regclass)").Scan(&desc); err != nil {
		t.Fatal(err)
	}
	if desc != "ambient" {
		t.Error("the unexpected comment must survive reconcile")
	}
}

func c48(s *suite) {
	if !s.assumeKind(model.KindDomain) ||
		!s.assumeKind(model.KindMaterializedView) ||
		!s.assumeKind(model.KindPolicy) ||
		!s.assumeKind(model.KindComment) {
		return
	}
	t := s.t
	script := `CREATE DOMAIN posint AS integer CHECK (VALUE > 0);
CREATE TABLE t (id posint, note text);
ALTER TABLE t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON t FOR SELECT USING (id > 0);
COMMENT ON TABLE t IS 'governed table';
CREATE MATERIALIZED VIEW mv AS SELECT id, note FROM t;
CREATE INDEX mv_note ON mv (note)`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	var order []model.ObjectKind
	for _, c := range report.Applied {
		order = append(order, c.Target.Kind)
	}
	at := func(k model.ObjectKind) int {
		for i, o := range order {
			if o == k {
				return i
			}
		}
		return -1
	}
	domainAt := at(model.KindDomain)
	tableAt := at(model.KindTable)
	if domainAt < 0 || tableAt <= domainAt {
		t.Errorf("domain must precede the table that uses it: %v", order)
	}
	if !(at(model.KindPolicy) > tableAt &&
		at(model.KindMaterializedView) > tableAt &&
		at(model.KindIndex) > at(model.KindMaterializedView) &&
		at(model.KindComment) > tableAt) {
		t.Errorf("dependents must follow their prerequisite: %v", order)
	}
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c49(s *suite) {
	if !s.assumeKind(model.KindExclusion) {
		return
	}
	t := s.t
	script := `CREATE TABLE t (id bigint, span int4range,
    CONSTRAINT no_overlap EXCLUDE USING gist (span WITH &&))`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == exclusionID("t", "no_overlap") &&
			c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the exclusion constraint")
	// the backing index is part of the constraint, not a separate INDEX
	for _, c := range report.Applied {
		if c.Target.Kind == model.KindIndex {
			t.Errorf("the exclusion's index must not surface as a separate change: %v",
				report.Applied)
		}
	}
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a changed predicate drifts the constraint: drop + create
	widened := `CREATE TABLE t (id bigint, span int4range,
    CONSTRAINT no_overlap EXCLUDE USING gist (span WITH &&) WHERE (id > 0))`
	drifted := s.reconcile(widened)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == exclusionID("t", "no_overlap") &&
			c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the exclusion")
	if got := s.reconcile(widened).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c50(s *suite) {
	if !s.assumeKind(model.KindStatistics) {
		return
	}
	t := s.t
	script := `CREATE TABLE t (a int, b int);
CREATE STATISTICS st ON a, b FROM t`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == statisticsID("st") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the statistics")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	widened := `CREATE TABLE t (a int, b int);
CREATE STATISTICS st (mcv) ON a, b FROM t`
	drifted := s.reconcile(widened)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == statisticsID("st") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the statistics")
	if got := s.reconcile(widened).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c51(s *suite) {
	if !s.assumeKind(model.KindRule) {
		return
	}
	t := s.t
	script := `CREATE TABLE t (id bigint);
CREATE RULE rl AS ON INSERT TO t DO ALSO NOTHING`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == ruleID("t", "rl") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the rule")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := `CREATE TABLE t (id bigint);
CREATE RULE rl AS ON INSERT TO t DO INSTEAD NOTHING`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == ruleID("t", "rl") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the rule")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c52(s *suite) {
	if !s.assumeKind(model.KindCollation) {
		return
	}
	t := s.t
	script := "CREATE COLLATION coll (provider = libc, locale = 'C')"
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == collationID("coll") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the collation")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := "CREATE COLLATION coll (provider = libc, locale = 'POSIX')"
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == collationID("coll") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the collation")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c53(s *suite) {
	if !s.assumeKind(model.KindConversion) {
		return
	}
	t := s.t
	script := "CREATE CONVERSION cv FOR 'LATIN1' TO 'UTF8'" +
		" FROM iso8859_1_to_utf8"
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == conversionID("cv") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the conversion")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := "CREATE CONVERSION cv FOR 'UTF8' TO 'LATIN1'" +
		" FROM utf8_to_iso8859_1"
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == conversionID("cv") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the conversion")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c54(s *suite) {
	if !s.assumeKind(model.KindPartition) {
		return
	}
	t := s.t
	script := `CREATE TABLE pt (k int) PARTITION BY RANGE (k);
CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (100)`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == tableID("pt") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the parent")
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == partitionID("pt", "pc") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the partition")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a moved bound re-homes rows: DESTRUCTIVE detach+attach
	// recreate, refused by the default policy
	changed := `CREATE TABLE pt (k int) PARTITION BY RANGE (k);
CREATE TABLE pc PARTITION OF pt FOR VALUES FROM (0) TO (50)`
	s.rejects(core.ErrPolicyViolation, func() error {
		_, err := s.reconciler(changed, core.DefaultPolicy()).Reconcile(s.ctx)
		return err
	})
	drifted := s.reconcilePolicy(changed,
		core.DefaultPolicy().WithDestructive(core.DecisionApply))
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == partitionID("pt", "pc") &&
			c.Safety == model.SafetyDestructive
	}, "expected a DESTRUCTIVE recreate for the partition")
	if got := s.reconcilePolicy(changed,
		core.DefaultPolicy().WithDestructive(core.DecisionApply)).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c55(s *suite) {
	if !s.assumeKind(model.KindForeignTable) {
		return
	}
	t := s.t
	server, ok := s.optionalHook("foreignServerDdl", s.h.ForeignServerDdl)
	if !ok {
		return
	}
	defer s.teardown("foreignServerTeardownDdl", s.h.ForeignServerTeardownDdl)
	s.execute(server)
	script := "CREATE FOREIGN TABLE ft (a int) SERVER tck_srv" +
		" OPTIONS (fetch_size '100')"
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == foreignTableID("ft") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the foreign table")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// an options change carries no local data — plain drop+create
	changed := "CREATE FOREIGN TABLE ft (a int) SERVER" +
		" tck_srv OPTIONS (fetch_size '200')"
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == foreignTableID("ft") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the foreign table")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c56(s *suite) {
	if !s.assumeKind(model.KindPublication) {
		return
	}
	t := s.t
	pub, ok := s.optionalHook("publicationDdl", s.h.PublicationDdl)
	if !ok {
		return
	}
	defer s.teardown("publicationTeardownDdl", s.h.PublicationTeardownDdl)
	s.execute(pub)
	script := `CREATE TABLE t (a int);
ALTER PUBLICATION tck_pub ADD TABLE t`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == publicationID("tck_pub", "t") &&
			c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE binding for the table")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a row-filter change drops and re-adds the binding
	changed := `CREATE TABLE t (a int);
ALTER PUBLICATION tck_pub ADD TABLE t WHERE (a > 0)`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == publicationID("tck_pub", "t") &&
			c.Safety == model.SafetyRebuild
	}, "expected a REBUILD recreate for the binding")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c57(s *suite) {
	if !s.assumeKind(model.KindOwnership) {
		return
	}
	t := s.t
	roles, ok := s.optionalHook("ownershipRoleDdl", s.h.OwnershipRoleDdl)
	if !ok {
		return
	}
	defer s.teardown("ownershipRoleTeardownDdl", s.h.OwnershipRoleTeardownDdl)
	s.execute(roles)
	script := `CREATE TABLE t (a int);
ALTER TABLE t OWNER TO tck_owner`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == ownershipID("t") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD owner assignment for t")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a different desired role re-assigns in place — REBUILD
	changed := `CREATE TABLE t (a int);
ALTER TABLE t OWNER TO tck_owner2`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == ownershipID("t") && c.Safety == model.SafetyRebuild
	}, "expected a REBUILD owner change for t")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c58(s *suite) {
	if !s.assumeKind(model.KindPrivileges) {
		return
	}
	t := s.t
	roles, ok := s.optionalHook("ownershipRoleDdl", s.h.OwnershipRoleDdl)
	if !ok {
		return
	}
	defer s.teardown("ownershipRoleTeardownDdl", s.h.OwnershipRoleTeardownDdl)
	s.execute(roles)
	script := `CREATE TABLE t (a int);
GRANT SELECT ON t TO tck_owner`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == privilegesID("t") && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "GRANT SELECT") {
				return true
			}
		}
		return false
	}, "expected a REBUILD GRANT on t")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a different desired grant converges the acl — revoke the
	// old, grant the new, in one REBUILD change
	changed := `CREATE TABLE t (a int);
GRANT INSERT ON t TO tck_owner2`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == privilegesID("t") && c.Safety == model.SafetyRebuild) {
			return false
		}
		var rev, grant bool
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "REVOKE") {
				rev = true
			}
			if strings.HasPrefix(st, "GRANT INSERT") {
				grant = true
			}
		}
		return rev && grant
	}, "expected a REBUILD GRANT+REVOKE on t")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c59(s *suite) {
	if !s.assumeKind(model.KindCast) {
		return
	}
	t := s.t
	script := `CREATE DOMAIN d AS integer CHECK (VALUE > 0);
CREATE FUNCTION dtotext(d) RETURNS text
    LANGUAGE sql IMMUTABLE RETURN $1::text;
CREATE CAST (d AS text) WITH FUNCTION dtotext(d)`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target == castID("d->text") && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE create for the cast")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// a context change drops and recreates the cast
	changed := `CREATE DOMAIN d AS integer CHECK (VALUE > 0);
CREATE FUNCTION dtotext(d) RETURNS text
    LANGUAGE sql IMMUTABLE RETURN $1::text;
CREATE CAST (d AS text) WITH FUNCTION dtotext(d) AS IMPLICIT`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		return c.Target == castID("d->text") && c.Safety == model.SafetyRebuild
	}, "expected a drop+create REBUILD for the cast")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c60(s *suite) {
	if !s.assumeKind(model.KindType) {
		return
	}
	t := s.t
	script := "CREATE TYPE cmp AS (a integer, b text)"
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == typeID("cmp") && c.Safety == model.SafetyAdditive) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "CREATE TYPE") {
				return true
			}
		}
		return false
	}, "expected an ADDITIVE CREATE TYPE for the composite")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// attribute drift drops and recreates the type
	changed := "CREATE TYPE cmp AS (a integer, b text, c numeric)"
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == typeID("cmp") && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "DROP TYPE") {
				return true
			}
		}
		return false
	}, "expected a drop+create REBUILD for the composite")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c61(s *suite) {
	if !s.assumeKind(model.KindType) {
		return
	}
	t := s.t
	// the generated family — multirange, constructor routines and the
	// range→multirange cast — folds into the range: only the TYPE
	// object is created, and a routine calling the folded constructor
	// still orders after it
	script := `CREATE TYPE r AS RANGE (subtype = integer);
CREATE FUNCTION mk(x r) RETURNS r_multirange
    LANGUAGE sql IMMUTABLE AS 'SELECT r_multirange(x)'`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == typeID("r") && c.Safety == model.SafetyAdditive) {
			return false
		}
		for _, st := range c.Statements {
			if strings.Contains(st, "AS RANGE") {
				return true
			}
		}
		return false
	}, "expected an ADDITIVE CREATE TYPE ... AS RANGE")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// the dependent routine would block DROP TYPE — the drift phase
	// recreates a bare range
	s.execute("DROP FUNCTION " + s.qualified("mk") + "(" +
		s.qualified("r") + ")")
	changed := `CREATE TYPE r AS RANGE (subtype = text, collation = "C")`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == typeID("r") && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "DROP TYPE") {
				return true
			}
		}
		return false
	}, "expected a drop+create REBUILD for the range")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c62(s *suite) {
	if !s.assumeKind(model.KindAggregate) {
		return
	}
	t := s.t
	script := `CREATE FUNCTION tfn(state int, x int)
    RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT state + x';
CREATE AGGREGATE a1(int) (SFUNC = tfn, STYPE = int, INITCOND = '0')`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindAggregate &&
			c.Target.Name == "a1(integer)" && c.Safety == model.SafetyAdditive) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "CREATE AGGREGATE") {
				return true
			}
		}
		return false
	}, "expected an ADDITIVE CREATE AGGREGATE")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := `CREATE FUNCTION tfn(state int, x int)
    RETURNS int LANGUAGE sql IMMUTABLE AS 'SELECT state + x';
CREATE AGGREGATE a1(int) (SFUNC = tfn, STYPE = int)`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindAggregate &&
			c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "DROP AGGREGATE") {
				return true
			}
		}
		return false
	}, "expected a drop+create REBUILD for the aggregate")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c63(s *suite) {
	if !s.assumeKind(model.KindPrivileges) {
		return
	}
	t := s.t
	roles, ok := s.optionalHook("ownershipRoleDdl", s.h.OwnershipRoleDdl)
	if !ok {
		return
	}
	defer s.teardown("ownershipRoleTeardownDdl", s.h.OwnershipRoleTeardownDdl)
	s.execute(roles)
	columnGrant := model.ObjectId{Kind: model.KindPrivileges,
		Name: "a", Parent: "t"}
	script := `CREATE TABLE t (a int, b int);
GRANT SELECT (a) ON t TO tck_owner`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == columnGrant && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.Contains(st, "SELECT") {
				return true
			}
		}
		return false
	}, "expected a REBUILD column GRANT on t.a")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := `CREATE TABLE t (a int, b int);
GRANT UPDATE (a) ON t TO tck_owner`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == columnGrant && c.Safety == model.SafetyRebuild) {
			return false
		}
		var rev, upd bool
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "REVOKE") {
				rev = true
			}
			if strings.Contains(st, "UPDATE") {
				upd = true
			}
		}
		return rev && upd
	}, "expected a REBUILD column GRANT+REVOKE on t.a")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c64(s *suite) {
	if !s.assumeKind(model.KindDefaultPrivileges) {
		return
	}
	t := s.t
	roles, ok := s.optionalHook("ownershipRoleDdl", s.h.OwnershipRoleDdl)
	if !ok {
		return
	}
	defer s.teardown("ownershipRoleTeardownDdl", s.h.OwnershipRoleTeardownDdl)
	s.execute(roles)
	defPrivs := model.ObjectId{Kind: model.KindDefaultPrivileges,
		Name: "TABLES for tck_owner"}
	script := `ALTER DEFAULT PRIVILEGES FOR ROLE tck_owner
    IN SCHEMA ${namespace} GRANT SELECT ON TABLES TO tck_owner2`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == defPrivs && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "ALTER DEFAULT PRIVILEGES") {
				return true
			}
		}
		return false
	}, "expected a REBUILD default-privileges GRANT")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := `ALTER DEFAULT PRIVILEGES FOR ROLE tck_owner
    IN SCHEMA ${namespace} GRANT UPDATE ON TABLES TO tck_owner2`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target == defPrivs && c.Safety == model.SafetyRebuild) {
			return false
		}
		var rev, upd bool
		for _, st := range c.Statements {
			if strings.Contains(st, "REVOKE") {
				rev = true
			}
			if strings.Contains(st, "UPDATE") {
				upd = true
			}
		}
		return rev && upd
	}, "expected a REBUILD GRANT+REVOKE on default privileges")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c65(s *suite) {
	if !s.assumeKind(model.KindRoutine) {
		return
	}
	t := s.t
	script := `CREATE FUNCTION wf(x int) RETURNS int
    LANGUAGE sql WINDOW AS 'SELECT x'`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target.Kind == model.KindRoutine &&
			c.Target.Name == "wf(integer)" && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE window function")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	changed := `CREATE FUNCTION wf(x int) RETURNS int
    LANGUAGE sql WINDOW AS 'SELECT x + 1'`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindRoutine &&
			c.Target.Name == "wf(integer)" && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "CREATE OR REPLACE FUNCTION") {
				return true
			}
		}
		return false
	}, "expected an in-place replace for the window function")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c66(s *suite) {
	if !s.assumeKind(model.KindTextSearchDictionary) ||
		!s.assumeKind(model.KindTextSearchConfiguration) {
		return
	}
	t := s.t
	script := `CREATE TEXT SEARCH DICTIONARY mydict
    (template = pg_catalog.simple, stopwords = 'english');
CREATE TEXT SEARCH CONFIGURATION mycfg (parser = pg_catalog.default);
ALTER TEXT SEARCH CONFIGURATION mycfg
    ADD MAPPING FOR asciiword WITH mydict`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target.Kind == model.KindTextSearchDictionary &&
			c.Target.Name == "mydict" && c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE dictionary")
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindTextSearchConfiguration &&
			c.Target.Name == "mycfg" && c.Safety == model.SafetyAdditive) {
			return false
		}
		for _, st := range c.Statements {
			if strings.Contains(st, "ADD MAPPING") {
				return true
			}
		}
		return false
	}, "expected the mapping fragment on the configuration")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// an option drift recreates — no OR REPLACE form exists
	changed := `CREATE TEXT SEARCH DICTIONARY mydict
    (template = pg_catalog.simple, stopwords = 'german');
CREATE TEXT SEARCH CONFIGURATION mycfg (parser = pg_catalog.default);
ALTER TEXT SEARCH CONFIGURATION mycfg
    ADD MAPPING FOR asciiword WITH mydict`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindTextSearchDictionary &&
			c.Target.Name == "mydict" && c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "DROP TEXT SEARCH DICTIONARY") {
				return true
			}
		}
		return false
	}, "expected a drop+create for the drifted dictionary")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}

func c67(s *suite) {
	if !s.assumeKind(model.KindOperator) ||
		!s.assumeKind(model.KindOperatorClass) ||
		!s.assumeKind(model.KindOperatorFamily) {
		return
	}
	t := s.t
	script := `CREATE OPERATOR ===
    (leftarg = int, rightarg = int, function = int4eq,
     restrict = eqsel, join = eqjoinsel, hashes, merges);
CREATE OPERATOR FAMILY fam USING btree;
CREATE OPERATOR CLASS oc FOR TYPE int USING btree FAMILY fam
    AS OPERATOR 1 ===`
	report := s.reconcile(script)
	if report.Outcome != core.OutcomeReconciled {
		t.Fatalf("outcome = %v", report.Outcome)
	}
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		return c.Target.Kind == model.KindOperator &&
			c.Target.Name == "===(integer,integer)" &&
			c.Safety == model.SafetyAdditive
	}, "expected an ADDITIVE operator")
	s.anyChange(report.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindOperatorClass &&
			c.Safety == model.SafetyAdditive) {
			return false
		}
		for _, st := range c.Statements {
			if strings.Contains(st, "OPERATOR 1") {
				return true
			}
		}
		return false
	}, "expected member specs on the class create")
	if got := s.reconcile(script).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
	// an option drift recreates — no OR REPLACE form exists
	changed := `CREATE OPERATOR ===
    (leftarg = int, rightarg = int, function = int4eq,
     restrict = eqsel, join = eqjoinsel, hashes, merges);
CREATE OPERATOR FAMILY fam USING btree;
CREATE OPERATOR CLASS oc FOR TYPE int USING btree FAMILY fam
    AS OPERATOR 1 ===, FUNCTION 1 btint4cmp(int,int)`
	drifted := s.reconcile(changed)
	if drifted.Outcome != core.OutcomeReconciled {
		t.Fatalf("drifted = %v", drifted.Outcome)
	}
	s.anyChange(drifted.Applied, func(c model.PlannedChange) bool {
		if !(c.Target.Kind == model.KindOperatorClass &&
			c.Safety == model.SafetyRebuild) {
			return false
		}
		for _, st := range c.Statements {
			if strings.HasPrefix(st, "DROP OPERATOR CLASS") {
				return true
			}
		}
		return false
	}, "expected a drop+create for the drifted class")
	if got := s.reconcile(changed).Outcome; got != core.OutcomeUpToDate {
		t.Errorf("re-run = %v", got)
	}
}
