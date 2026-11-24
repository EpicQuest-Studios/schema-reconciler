package model

import (
	"crypto/sha256"
	"encoding/hex"
	"hash"
	"strconv"
)

// SchemaSnapshot is an immutable, namespace-neutral description of a set of
// schema objects (spec 4.2). Fingerprint is the spec-5.7 SHA-256 over the
// objects, computed by FingerprintOf — the same computation every dialect
// snapshot shares, so staleness checks work across processes and library
// versions.
type SchemaSnapshot struct {
	Objects     map[ObjectId]SchemaObject
	Fingerprint string
}

// NewSchemaSnapshot builds a snapshot and computes its fingerprint — the
// analog of the Java factory SchemaSnapshot.of.
func NewSchemaSnapshot(objects map[ObjectId]SchemaObject) SchemaSnapshot {
	return SchemaSnapshot{Objects: objects, Fingerprint: FingerprintOf(objects)}
}

// NewSchemaSnapshotChecked builds a snapshot carrying a previously computed
// fingerprint and verifies it against the objects, like the canonical Java
// record constructor: a mismatch is ErrInvalidArgument.
func NewSchemaSnapshotChecked(objects map[ObjectId]SchemaObject, fingerprint string) (SchemaSnapshot, error) {
	if fingerprint != FingerprintOf(objects) {
		return SchemaSnapshot{}, invalidArgf("fingerprint does not match objects")
	}
	return SchemaSnapshot{Objects: objects, Fingerprint: fingerprint}, nil
}

// Object looks up one object by id; the second return is presence.
func (s SchemaSnapshot) Object(id ObjectId) (SchemaObject, bool) {
	o, ok := s.Objects[id]
	return o, ok
}

// FingerprintOf computes the byte-exact fingerprint of spec 5.7: for each
// object in the deterministic ObjectId order, the kind's constant name, the
// parent ("" when absent), the name and the canonical definition are written
// as <utf8 length>:<utf8 bytes>; the lowercase hex SHA-256 of that sequence
// is the fingerprint.
func FingerprintOf(objects map[ObjectId]SchemaObject) string {
	ids := make([]ObjectId, 0, len(objects))
	for id := range objects {
		ids = append(ids, id)
	}
	SortObjectIDs(ids)
	digest := sha256.New()
	for _, id := range ids {
		putFingerprintField(digest, id.Kind.String())
		putFingerprintField(digest, id.Parent)
		putFingerprintField(digest, id.Name)
		putFingerprintField(digest, objects[id].Definition.Canonical())
	}
	return hex.EncodeToString(digest.Sum(nil))
}

func putFingerprintField(digest hash.Hash, field string) {
	digest.Write([]byte(strconv.Itoa(len(field))))
	digest.Write([]byte{':'})
	digest.Write([]byte(field))
}
