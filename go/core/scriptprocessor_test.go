package reconciler

// scriptprocessor_test.go — Go port of test/ScriptProcessor.test.ts:
// spec 3.2 — placeholder substitution, escaping and the literal-target scan.

import (
	"errors"
	"testing"
)

func testProcessor(t *testing.T, script, target, scratch string) *scriptProcessor {
	t.Helper()
	p, err := newScriptProcessor(script, target, scratch,
		newFakeDialect(newFakeDb()))
	if err != nil {
		t.Fatalf("newScriptProcessor: %v", err)
	}
	return p
}

func requireDesiredStateErr(t *testing.T, err error) {
	t.Helper()
	var dse *DesiredStateError
	if !errors.As(err, &dse) {
		t.Fatalf("err = %v, want DesiredStateError", err)
	}
}

func TestScriptSubstitutesQuotedScratchNamespace(t *testing.T) {
	p := testProcessor(t, "CREATE TABLE ${namespace}.t", "corex", "rcl_scratch_1")
	if p.Substituted != `CREATE TABLE "rcl_scratch_1".t` {
		t.Fatalf("substituted = %q", p.Substituted)
	}
	if !p.HasPlaceholder {
		t.Fatal("hasPlaceholder = false")
	}
}

func TestScriptUnqualifiedScriptHasNoPlaceholder(t *testing.T) {
	p := testProcessor(t, "CREATE TABLE t", "corex", "rcl_scratch_1")
	if p.HasPlaceholder {
		t.Fatal("hasPlaceholder = true")
	}
	if p.Substituted != "CREATE TABLE t" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
}

func TestScriptEscapedPlaceholderIsLiteralText(t *testing.T) {
	p := testProcessor(t, "SELECT '$${namespace}'", "corex", "rcl_scratch_1")
	if p.Substituted != "SELECT '${namespace}'" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
	if p.HasPlaceholder {
		t.Fatal("hasPlaceholder = true")
	}
}

func TestScriptQuotedPlaceholderIsRejected(t *testing.T) {
	_, err := newScriptProcessor(`CREATE TABLE "${namespace}".t`, "corex",
		"rcl_scratch_1", newFakeDialect(newFakeDb()))
	requireDesiredStateErr(t, err)
}

func TestScriptLiteralTargetReferenceIsRejected(t *testing.T) {
	d := newFakeDialect(newFakeDb())
	for _, script := range []string{
		"CREATE TABLE corex.t",
		"SELECT * FROM corex.t",
		`SELECT * FROM "corex".t`,
		"SELECT * FROM COREX.t",
	} {
		_, err := newScriptProcessor(script, "corex", "rcl_scratch_1", d)
		requireDesiredStateErr(t, err)
	}
}

func TestScriptLiteralTargetScanRespectsQuotedCase(t *testing.T) {
	// "COREX" quoted is a different namespace from corex — unquoted COREX
	// folds to corex, but quoting makes the name case-sensitive
	d := newFakeDialect(newFakeDb())
	p := testProcessor(t, `SELECT * FROM "COREX".t`, "corex", "rcl_scratch_1")
	if p.Substituted != `SELECT * FROM "COREX".t` {
		t.Fatalf("substituted = %q", p.Substituted)
	}
	// a mixed-case target is only reachable quoted — bare references fold
	// away and can never name it
	p = testProcessor(t, "SELECT * FROM app.t", "App", "rcl_scratch_1")
	if p.Substituted != "SELECT * FROM app.t" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
	_, err := newScriptProcessor(`SELECT * FROM "App".t`, "App", "s", d)
	requireDesiredStateErr(t, err)
}

func TestScriptSimilarNamesAreNotTheTarget(t *testing.T) {
	// identifier prefix/suffix: not the namespace token
	p := testProcessor(t, "SELECT * FROM corex2.t", "corex", "rcl_scratch_1")
	if p.Substituted != "SELECT * FROM corex2.t" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
	p = testProcessor(t, "SELECT * FROM x.corex", "corex", "rcl_scratch_1")
	if p.Substituted != "SELECT * FROM x.corex" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
	// target name without a dot following it is a column/table, not a schema
	p = testProcessor(t, "CREATE TABLE corex", "corex", "rcl_scratch_1")
	if p.Substituted != "CREATE TABLE corex" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
}

func TestScriptPlaceholderTargetNameDoesNotFalsePositive(t *testing.T) {
	// literal namespace named 'namespace': the placeholder must not trip the
	// scan
	p := testProcessor(t, "CREATE TABLE ${namespace}.t", "namespace", "rcl_scratch_1")
	if p.Substituted != `CREATE TABLE "rcl_scratch_1".t` {
		t.Fatalf("substituted = %q", p.Substituted)
	}
}

func TestScriptQualifiedChainDoesNotFalsePositive(t *testing.T) {
	// 'corex' preceded by '.' is a mid-qualifier, not a namespace reference
	// (3.2 rule 5)
	p := testProcessor(t, "SELECT a.corex.b", "corex", "rcl_scratch_1")
	if p.Substituted != "SELECT a.corex.b" {
		t.Fatalf("substituted = %q", p.Substituted)
	}
}
