package reconciler

// apirules_test.go — Go port of test/ApiRules.test.ts: the general API
// rules of spec 2.3 and the message rule of spec 7. (The TypeScript
// null-rejection rules have no Go analog — Go builders validate nil values
// eagerly or at Build.)

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

const apiNS = "corex"

func TestApiBuilderMessagesNameTheOffendingProperty(t *testing.T) {
	db := newFakeDb()
	var ce *ConfigurationError
	_, err := NewBuilder().Namespace(apiNS).
		DesiredState(DesiredStateOfScript("")).Build()
	if !errors.As(err, &ce) || !strings.Contains(err.Error(), "dataSource") {
		t.Fatalf("err = %v, want ConfigurationError naming dataSource", err)
	}
	_, err = NewBuilder().DataSource(db.dataSource()).Namespace(apiNS).
		DesiredState(DesiredStateOfScript("")).Dialect(newFakeDialect(db)).
		LockTimeout(0).Build()
	if !errors.As(err, &ce) || !strings.Contains(err.Error(), "lockTimeout") {
		t.Fatalf("err = %v, want ConfigurationError naming lockTimeout", err)
	}
	_, err = NewBuilder().DataSource(db.dataSource()).Namespace(apiNS).
		DesiredState(DesiredStateOfScript("")).Dialect(newFakeDialect(db)).
		StatementTimeout(-time.Millisecond).Build()
	if !errors.As(err, &ce) || !strings.Contains(err.Error(), "statementTimeout") {
		t.Fatalf("err = %v, want ConfigurationError naming statementTimeout", err)
	}
}

func TestApiRunMessagesRaisedBelowTheEngineNameTheNamespace(t *testing.T) {
	db := newFakeDb()
	r, err := NewBuilder().DataSource(db.dataSource()).
		Namespace(apiNS).
		DesiredState(DesiredStateOfScript("CREATE TABLE t DEF v")).
		Dialect(unsupportingDialect{newFakeDialect(db)}).Build()
	if err != nil {
		t.Fatal(err)
	}
	_, err = r.Plan(context.Background())
	var ce *ConfigurationError
	if !errors.As(err, &ce) {
		t.Fatalf("err = %v, want ConfigurationError", err)
	}
	if !strings.Contains(err.Error(), "namespace '"+apiNS+"'") {
		t.Fatalf("err = %v, want namespace named", err)
	}
}

// unsupportingDialect reports Supports=false for every engine.
type unsupportingDialect struct{ *fakeDialect }

func (d unsupportingDialect) Supports(spi.DatabaseMetadata) bool { return false }

func TestApiPolicyRejectsApplyForUnsupported(t *testing.T) {
	if _, err := DefaultPolicy().WithUnsupported(DecisionApply); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("err = %v, want ErrInvalidArgument", err)
	}
	if _, err := DefaultPolicy().WithUnsupportedCategory("extension", DecisionApply); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("err = %v, want ErrInvalidArgument", err)
	}
}

func TestApiObjectIdEnforcesParentAndNameRules(t *testing.T) {
	for _, tc := range []struct {
		kind   model.ObjectKind
		name   string
		parent string
	}{
		{model.KindColumn, "c", ""},
		{model.KindTable, "t", "p"},
		{model.KindTable, "", ""},
		{model.KindColumn, "c", ""},
	} {
		if _, err := model.NewObjectId(tc.kind, tc.name, tc.parent); !errors.Is(err, model.ErrInvalidArgument) {
			t.Fatalf("%v: err = %v, want ErrInvalidArgument", tc, err)
		}
	}
	for _, tc := range []struct {
		kind   model.ObjectKind
		name   string
		parent string
	}{
		{model.KindIndex, "i", "t"},
		{model.KindTrigger, "tr", "t"},
		{model.KindCheck, "ck", "t"},
	} {
		if _, err := model.NewObjectId(tc.kind, tc.name, tc.parent); err != nil {
			t.Fatalf("%v: err = %v", tc, err)
		}
	}
}

func TestApiBuildOpensNoConnection(t *testing.T) {
	db := newFakeDb()
	failing := failingDB{}
	if _, err := NewBuilder().DataSource(failing).Namespace(apiNS).
		DesiredState(DesiredStateOfScript("")).Dialect(newFakeDialect(db)).Build(); err != nil {
		t.Fatalf("Build must not connect: %v", err)
	}
}

// failingDB fails if a connection is ever opened.
type failingDB struct{}

func (failingDB) Conn(ctx context.Context) (spi.Conn, error) {
	return nil, errors.New("build() must not connect")
}
