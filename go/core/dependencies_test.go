package reconciler

// dependencies_test.go — Go port of test/Dependencies.test.ts: dependency
// graph helpers — parent resolution, reversal, reachability.

import (
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

func depObject(id model.ObjectId, prereqs ...model.ObjectId) model.SchemaObject {
	return model.NewSchemaObject(id, model.Definition("def "+id.Name), prereqs, nil)
}

func depSnapshot(objects ...model.SchemaObject) model.SchemaSnapshot {
	m := make(map[model.ObjectId]model.SchemaObject, len(objects))
	for _, o := range objects {
		m[o.ID] = o
	}
	return model.NewSchemaSnapshot(m)
}

func depPrereqsOf(t *testing.T, snap model.SchemaSnapshot) map[model.ObjectId]model.ObjectSet {
	t.Helper()
	return prerequisitesOf(newFakeDialect(newFakeDb()), snap)
}

func sameIDSet(t *testing.T, expected []model.ObjectId, actual model.ObjectSet) {
	t.Helper()
	if actual == nil || len(actual) != len(expected) {
		t.Fatalf("set = %v, want %v", actual.Sorted(), expected)
	}
	for _, id := range expected {
		if !actual.Has(id) {
			t.Fatalf("set = %v, want %v", actual.Sorted(), expected)
		}
	}
}

func TestDepsManagedObjectWinsOverOtherOnResolvableNameCollision(t *testing.T) {
	// a range type and a materialized view share the name "t": the index
	// parented on "t" must resolve to the managed object, not the OTHER
	typ := model.ObjectId{Kind: model.KindOther, Name: "type:t"}
	mv := model.ObjectId{Kind: model.KindMaterializedView, Name: "t"}
	index := model.ObjectId{Kind: model.KindIndex, Name: "i", Parent: "t"}
	prereqs := depPrereqsOf(t, depSnapshot(depObject(typ), depObject(mv), depObject(index)))
	sameIDSet(t, []model.ObjectId{mv}, prereqs[index])
}

func TestDepsOtherFillsTheParentableGapWhenNoManagedObjectMatches(t *testing.T) {
	// an unsupported inheritance child's name is still the parent anchor —
	// children parented on it must resolve through the OTHER prefix
	child := model.ObjectId{Kind: model.KindOther, Name: "inherited:pc"}
	index := model.ObjectId{Kind: model.KindIndex, Name: "i", Parent: "pc"}
	prereqs := depPrereqsOf(t, depSnapshot(depObject(child), depObject(index)))
	sameIDSet(t, []model.ObjectId{child}, prereqs[index])
}

func TestDepsNonRelationOtherIsNeverAParent(t *testing.T) {
	// an exclusion constraint's stripped name is the synthetic
	// "<parent>.<name>" — its only relation name is the backing index, so it
	// must not shadow the relation literally named "t.ex"
	constraint := model.ObjectId{Kind: model.KindOther, Name: "constraint:t.ex"}
	relation := model.ObjectId{Kind: model.KindOther, Name: "inherited:t.ex"}
	index := model.ObjectId{Kind: model.KindIndex, Name: "i", Parent: "t.ex"}
	prereqs := depPrereqsOf(t, depSnapshot(
		depObject(constraint), depObject(relation), depObject(index)))
	sameIDSet(t, []model.ObjectId{relation}, prereqs[index])
	// and a routine's "name(sig)" stripped form is not parentable either
	routine := model.ObjectId{Kind: model.KindOther, Name: "routine:f(int)"}
	orphan := model.ObjectId{Kind: model.KindIndex, Name: "i", Parent: "f(int)"}
	prereqs2 := depPrereqsOf(t, depSnapshot(depObject(routine), depObject(orphan)))
	if got := prereqs2[orphan]; len(got) != 0 {
		t.Fatalf("prereqs = %v, want empty", got.Sorted())
	}
}

func TestDepsPartitionAndForeignTableAreParentable(t *testing.T) {
	// a partition and a foreign table carry COLUMN children like a table —
	// the implicit parent edge must resolve for them too
	pp := model.ObjectId{Kind: model.KindTable, Name: "pp"}
	part := model.ObjectId{Kind: model.KindPartition, Name: "pt", Parent: "pp"}
	col := model.ObjectId{Kind: model.KindColumn, Name: "c", Parent: "pt"}
	prereqs := depPrereqsOf(t, depSnapshot(depObject(pp), depObject(part), depObject(col)))
	sameIDSet(t, []model.ObjectId{part}, prereqs[col])
	sameIDSet(t, []model.ObjectId{pp}, prereqs[part])
	ft := model.ObjectId{Kind: model.KindForeignTable, Name: "ft"}
	fcol := model.ObjectId{Kind: model.KindColumn, Name: "c", Parent: "ft"}
	prereqs2 := depPrereqsOf(t, depSnapshot(depObject(ft), depObject(fcol)))
	sameIDSet(t, []model.ObjectId{ft}, prereqs2[fcol])
}

func TestDepsSelfEdgesAndNullParentsAreIgnored(t *testing.T) {
	tab := model.ObjectId{Kind: model.KindTable, Name: "t"}
	prereqs := depPrereqsOf(t, depSnapshot(depObject(tab, tab)))
	if got := prereqs[tab]; len(got) != 0 {
		t.Fatalf("prereqs = %v, want empty", got.Sorted())
	}
}

func TestDepsReverseAndReachableWalkDependents(t *testing.T) {
	a := model.ObjectId{Kind: model.KindTable, Name: "a"}
	b := model.ObjectId{Kind: model.KindView, Name: "b"}
	c := model.ObjectId{Kind: model.KindView, Name: "c"}
	prereqs := depPrereqsOf(t, depSnapshot(depObject(a), depObject(b, a), depObject(c, b)))
	dependents := reverseDeps(prereqs)
	sameIDSet(t, []model.ObjectId{b}, dependents[a])
	sameIDSet(t, []model.ObjectId{c}, dependents[b])
	sameIDSet(t, []model.ObjectId{b, c}, reachable(a, dependents))
}
