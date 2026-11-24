package reconciler

// objectfilter_test.go — Go port of test/ObjectFilter.test.ts: the
// Builder.filter cascade — a rejected object takes its children and
// mirrored annotations with it; everything else survives untouched.

import (
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

func filterID(kind model.ObjectKind, name, parent string) model.ObjectId {
	return model.ObjectId{Kind: kind, Name: name, Parent: parent}
}

func sameIDs(t *testing.T, expected []model.ObjectId,
	actual map[model.ObjectId]model.SchemaObject) {
	t.Helper()
	if len(actual) != len(expected) {
		t.Fatalf("objects = %v, want %v", actual, expected)
	}
	for _, id := range expected {
		if _, ok := actual[id]; !ok {
			t.Fatalf("objects = %v, want %v", actual, expected)
		}
	}
}

func TestFilterIncludeEverythingReturnsTheSameSnapshot(t *testing.T) {
	snap := depSnapshot(depObject(filterID(model.KindTable, "t", "")))
	filtered := applyObjectFilter(snap, func(model.ObjectId) bool { return true })
	if filtered.Fingerprint != snap.Fingerprint {
		t.Fatal("an all-inclusive filter must return the same snapshot")
	}
}

func TestFilterExcludedTableHidesChildrenAndMirroredAnnotations(t *testing.T) {
	obj := func(id model.ObjectId, def string) model.SchemaObject {
		return depObject(id)
	}
	snap := depSnapshot(
		obj(filterID(model.KindTable, "t", ""), "t1"),
		obj(filterID(model.KindColumn, "c", "t"), "c1"),
		obj(filterID(model.KindIndex, "i", "t"), "i1"),
		// mirrored on the table and on its column
		obj(filterID(model.KindComment, "t", ""), "cm"),
		obj(filterID(model.KindComment, "c", "t"), "cm"),
		obj(filterID(model.KindPrivileges, "t", ""), "p"),
		obj(filterID(model.KindSecurityLabel, "selinux:t", ""), "l"),
		obj(filterID(model.KindOwnership, "t", ""), "o"),
		obj(filterID(model.KindTable, "u", ""), "u1"),
		obj(filterID(model.KindColumn, "c", "u"), "c1"),
		obj(filterID(model.KindComment, "u", ""), "cm"))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return !(x.Kind == model.KindTable && x.Name == "t")
	})
	sameIDs(t, []model.ObjectId{
		filterID(model.KindTable, "u", ""),
		filterID(model.KindColumn, "c", "u"),
		filterID(model.KindComment, "u", ""),
	}, filtered.Objects)
}

func TestFilterExcludedColumnKeepsItsTableButHidesItsAnnotations(t *testing.T) {
	obj := func(id model.ObjectId, def string) model.SchemaObject {
		return depObject(id)
	}
	snap := depSnapshot(
		obj(filterID(model.KindTable, "t", ""), "t1"),
		obj(filterID(model.KindColumn, "c", "t"), "c1"),
		obj(filterID(model.KindColumn, "d", "t"), "d1"),
		obj(filterID(model.KindComment, "c", "t"), "cm"),
		obj(filterID(model.KindComment, "d", "t"), "cm"),
		obj(filterID(model.KindPrivileges, "c", "t"), "p"),
		obj(filterID(model.KindComment, "t", ""), "cm"),
		// unsupported annotation OTHERs embed the qualified target
		obj(filterID(model.KindOther, "comment:t.c", ""), "cm"),
		obj(filterID(model.KindOther, "seclabel:p.t.c", ""), "l"),
		obj(filterID(model.KindOther, "comment:t.d", ""), "cm"),
		obj(filterID(model.KindOther, "comment:t", ""), "cm"),
		// constraint:<parent>.<member> follows the relation only —
		// member c is not column t.c
		obj(filterID(model.KindOther, "constraint:t.c", ""), "o"))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return x.Kind != model.KindColumn || x.Name != "c"
	})
	sameIDs(t, []model.ObjectId{
		filterID(model.KindTable, "t", ""),
		filterID(model.KindColumn, "d", "t"),
		filterID(model.KindComment, "d", "t"),
		filterID(model.KindComment, "t", ""),
		filterID(model.KindOther, "comment:t.d", ""),
		filterID(model.KindOther, "comment:t", ""),
		filterID(model.KindOther, "constraint:t.c", ""),
	}, filtered.Objects)
}

func TestFilterCollisionSuffixedMirroredIDsFollowTheirTarget(t *testing.T) {
	// a " (KIND)" collision suffix and a seclabel provider prefix are not
	// part of the annotated name
	obj := func(id model.ObjectId, def string) model.SchemaObject {
		return depObject(id)
	}
	snap := depSnapshot(
		obj(filterID(model.KindTable, "t", ""), "t1"),
		obj(filterID(model.KindComment, "t (INDEX)", "x"), "cm"),
		obj(filterID(model.KindSecurityLabel, "p:t (TABLE)", ""), "l"))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return x.Kind != model.KindTable
	})
	// "t (INDEX)" mirrors an INDEX named t on parent x — not TABLE t
	if _, ok := filtered.Objects[filterID(model.KindComment, "t (INDEX)", "x")]; !ok {
		t.Fatal("comment t (INDEX) must survive")
	}
	if _, ok := filtered.Objects[filterID(model.KindSecurityLabel, "p:t (TABLE)", "")]; ok {
		t.Fatal("seclabel p:t (TABLE) must be removed")
	}
}

func TestFilterExcludedOtherRelationHidesChildren(t *testing.T) {
	obj := func(id model.ObjectId, def string) model.SchemaObject {
		return depObject(id)
	}
	snap := depSnapshot(
		obj(filterID(model.KindOther, "inherited:x", ""), "d"),
		obj(filterID(model.KindColumn, "a", "x"), "c1"),
		obj(filterID(model.KindColumn, "a", "y"), "c1"))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return x.Kind != model.KindOther
	})
	sameIDs(t, []model.ObjectId{filterID(model.KindColumn, "a", "y")}, filtered.Objects)
}

func TestFilterExcludedOtherTargetHidesAnnotationOthers(t *testing.T) {
	// comment:/seclabel:/privileges:/ownership: OTHERs annotate unmanaged
	// targets — they follow the target out
	obj := func(id model.ObjectId, def string) model.SchemaObject {
		return depObject(id)
	}
	snap := depSnapshot(
		obj(filterID(model.KindOther, "inherited:t", ""), "d"),
		obj(filterID(model.KindOther, "comment:t", ""), "cm"),
		obj(filterID(model.KindOther, "comment:t.c", ""), "cm"),
		obj(filterID(model.KindOther, "seclabel:p.t", ""), "l"),
		obj(filterID(model.KindOther, "seclabel:p.t.c", ""), "l"),
		obj(filterID(model.KindOther, "privileges:t", ""), "p"),
		obj(filterID(model.KindOther, "privileges:t.c", ""), "p"),
		obj(filterID(model.KindOther, "ownership:t", ""), "o"),
		obj(filterID(model.KindOther, "constraint:t.pk", ""), "o"),
		obj(filterID(model.KindOther, "comment:u", ""), "cm"),
		obj(filterID(model.KindOther, "constraint:u.ck", ""), "o"),
		obj(filterID(model.KindOther, "cast:a->b", ""), "c"))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return !(x.Kind == model.KindOther && x.Name == "inherited:t")
	})
	sameIDs(t, []model.ObjectId{
		filterID(model.KindOther, "comment:u", ""),
		filterID(model.KindOther, "constraint:u.ck", ""),
		filterID(model.KindOther, "cast:a->b", ""),
	}, filtered.Objects)
}

func TestFilterSerialSequenceFollowsItsOwningRelation(t *testing.T) {
	// the OWNED BY dep edge makes the sequence a covered follower of its
	// table — excluding the table hides it, but not a free sequence
	tab := filterID(model.KindTable, "t", "")
	snap := depSnapshot(
		depObject(tab),
		depObject(filterID(model.KindSequence, "t_c_seq", ""), tab),
		depObject(filterID(model.KindSequence, "free", "")))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return x.Kind != model.KindTable
	})
	sameIDs(t, []model.ObjectId{filterID(model.KindSequence, "free", "")}, filtered.Objects)
}

func TestFilterCascadeIsTransitiveThroughNestedParents(t *testing.T) {
	// rt -> md -> lf multi-level partitioning: excluding md removes lf and
	// everything parented on lf
	obj := func(id model.ObjectId, def string) model.SchemaObject {
		return depObject(id)
	}
	snap := depSnapshot(
		obj(filterID(model.KindTable, "rt", ""), "d"),
		obj(filterID(model.KindPartition, "md", "rt"), "d"),
		obj(filterID(model.KindPartition, "lf", "md"), "d"),
		obj(filterID(model.KindColumn, "j", "lf"), "d"),
		obj(filterID(model.KindComment, "lf", "md"), "cm"),
		obj(filterID(model.KindPublication, "pub", "lf"), "d"),
		obj(filterID(model.KindColumn, "j", "rt"), "d"))
	filtered := applyObjectFilter(snap, func(x model.ObjectId) bool {
		return !(x.Kind == model.KindPartition && x.Name == "md")
	})
	sameIDs(t, []model.ObjectId{
		filterID(model.KindTable, "rt", ""),
		filterID(model.KindColumn, "j", "rt"),
	}, filtered.Objects)
}
