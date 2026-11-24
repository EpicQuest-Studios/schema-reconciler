package model_test

// snapshot_test.go — Go port of test/SchemaSnapshot.test.ts: spec 5.7, the
// byte-exact fingerprint contract.

import (
	"errors"
	"regexp"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

func snapObject(kind model.ObjectKind, name, def string) model.SchemaObject {
	return model.NewSchemaObject(
		model.ObjectId{Kind: kind, Name: name}, model.Definition(def), nil, nil)
}

func TestSnapshotFingerprintIsOrderIndependent(t *testing.T) {
	a := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "t"}: snapObject(model.KindTable, "t", "d1"),
		{Kind: model.KindView, Name: "v"}:  snapObject(model.KindView, "v", "d2"),
	}
	b := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindView, Name: "v"}:  snapObject(model.KindView, "v", "d2"),
		{Kind: model.KindTable, Name: "t"}: snapObject(model.KindTable, "t", "d1"),
	}
	if model.NewSchemaSnapshot(a).Fingerprint != model.NewSchemaSnapshot(b).Fingerprint {
		t.Fatal("fingerprint must not depend on insertion order")
	}
}

func TestSnapshotFingerprintChangesWithDefinition(t *testing.T) {
	a := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "t"}: snapObject(model.KindTable, "t", "d1"),
	}
	b := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "t"}: snapObject(model.KindTable, "t", "d2"),
	}
	if model.NewSchemaSnapshot(a).Fingerprint == model.NewSchemaSnapshot(b).Fingerprint {
		t.Fatal("fingerprint must change with the definition")
	}
}

func TestSnapshotFingerprintIsLowercaseHexSha256(t *testing.T) {
	fp := model.NewSchemaSnapshot(map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "t"}: snapObject(model.KindTable, "t", "d1"),
	}).Fingerprint
	if len(fp) != 64 {
		t.Fatalf("fingerprint length = %d, want 64", len(fp))
	}
	if !regexp.MustCompile(`^[0-9a-f]+$`).MatchString(fp) {
		t.Fatalf("fingerprint %q is not lowercase hex", fp)
	}
}

func TestSnapshotEmptyFingerprint(t *testing.T) {
	// sha256 of the empty byte string — a fixed reference value
	const want = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if got := model.NewSchemaSnapshot(nil).Fingerprint; got != want {
		t.Fatalf("fingerprint = %s, want %s", got, want)
	}
}

func TestSnapshotConstructorRejectsWrongFingerprint(t *testing.T) {
	objects := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "t"}: snapObject(model.KindTable, "t", "d1"),
	}
	wrong := "0000000000000000000000000000000000000000000000000000000000000000"
	if _, err := model.NewSchemaSnapshotChecked(objects, wrong); !errors.Is(err, model.ErrInvalidArgument) {
		t.Fatalf("err = %v, want ErrInvalidArgument", err)
	}
}

func TestSnapshotFingerprintUsesLengthPrefixedFields(t *testing.T) {
	// "ab"+"c" and "a"+"bc" in field position must not collide
	a := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "ab"}: snapObject(model.KindTable, "ab", "c"),
	}
	b := map[model.ObjectId]model.SchemaObject{
		{Kind: model.KindTable, Name: "a"}: snapObject(model.KindTable, "a", "bc"),
	}
	if model.NewSchemaSnapshot(a).Fingerprint == model.NewSchemaSnapshot(b).Fingerprint {
		t.Fatal("field framing must keep 'ab,c' and 'a,bc' distinct")
	}
}
