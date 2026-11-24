package model

import (
	"encoding/json"
	"slices"
	"strings"
	"unicode/utf16"
)

// parentedKinds are the kinds that must carry a parent: the owning table or
// view (spec 4.1).
var parentedKinds = map[ObjectKind]struct{}{
	KindColumn: {}, KindPrimaryKey: {}, KindUnique: {},
	KindForeignKey: {}, KindCheck: {}, KindIndex: {},
	KindTrigger: {}, KindPolicy: {}, KindExclusion: {},
	KindRule: {}, KindPartition: {},
}

// parentMirroredKinds mirror their target's parent: optional, never a bare
// parent of their own (spec 4.1).
var parentMirroredKinds = map[ObjectKind]struct{}{
	KindComment: {}, KindPublication: {}, KindOwnership: {},
	KindPrivileges: {}, KindSecurityLabel: {},
}

// ObjectId is the namespace-neutral identity of a schema object (spec 4.1).
// It never contains a namespace, so two snapshots of different namespaces are
// directly comparable. All fields are comparable, making ObjectId usable as a
// Go map key — the analog of the Java record's value equality.
//
// Name is the identifier as stored by the engine; for KindRoutine it includes
// the identity signature, for example "ts(text)".
//
// Parent is the owning object's name for child kinds (a column's table, a
// constraint's table). An empty string means "absent" — the specification
// forbids an empty present parent, so no valid id is ambiguous. For the
// mirrored kinds (KindComment, KindPublication, KindOwnership, KindPrivileges,
// KindSecurityLabel) it mirrors the target's parent.
type ObjectId struct {
	Kind   ObjectKind
	Name   string
	Parent string
}

// NewObjectId builds a validated id; parent == "" means "no parent". It
// returns ErrInvalidArgument when name is empty, when a parented kind lacks a
// parent, or when a kind that never carries a parent is given one (spec 4.1).
func NewObjectId(kind ObjectKind, name, parent string) (ObjectId, error) {
	id := ObjectId{Kind: kind, Name: name, Parent: parent}
	if err := id.Validate(); err != nil {
		return ObjectId{}, err
	}
	return id, nil
}

// MustObjectId is NewObjectId for static ids and tests; it panics on invalid
// input.
func MustObjectId(kind ObjectKind, name, parent string) ObjectId {
	id, err := NewObjectId(kind, name, parent)
	if err != nil {
		panic(err)
	}
	return id
}

// Validate enforces the invariants of spec 4.1.
func (id ObjectId) Validate() error {
	if int(id.Kind) < 0 || int(id.Kind) >= len(objectKindNames) {
		return invalidArgf("unknown ObjectKind %d", int(id.Kind))
	}
	if id.Name == "" {
		return invalidArgf("name must not be empty")
	}
	_, parented := parentedKinds[id.Kind]
	_, mirrored := parentMirroredKinds[id.Kind]
	hasParent := id.Parent != ""
	if parented && !hasParent || !parented && !mirrored && hasParent {
		return invalidArgf("parent is required for %s and must be empty for other kinds", id.Kind)
	}
	return nil
}

// String returns "KIND parent.name" or "KIND name" — the same rendering the
// Java record's toString produces and the spelling used in reasons and errors.
func (id ObjectId) String() string {
	if id.Parent != "" {
		return id.Kind.String() + " " + id.Parent + "." + id.Name
	}
	return id.Kind.String() + " " + id.Name
}

// Compare is the deterministic ordering of spec 5.2: kind order (declaration
// ordinal), then parent, then name, lexicographically. Use it with
// slices.SortFunc.
func (id ObjectId) Compare(other ObjectId) int {
	if id.Kind != other.Kind {
		return int(id.Kind) - int(other.Kind)
	}
	if c := compareString(id.Parent, other.Parent); c != 0 {
		return c
	}
	return compareString(id.Name, other.Name)
}

// objectIdJSON is the plan-JSON shape {"kind","name","parent"} of spec 4.4.
type objectIdJSON struct {
	Kind   string  `json:"kind"`
	Name   *string `json:"name"`
	Parent *string `json:"parent"`
}

// MarshalJSON writes {"kind":"TABLE","name":"x","parent":null} — the same
// member order the Java and TypeScript toJson emit.
func (id ObjectId) MarshalJSON() ([]byte, error) {
	var parent *string
	if id.Parent != "" {
		parent = &id.Parent
	}
	return json.Marshal(objectIdJSON{Kind: id.Kind.String(), Name: &id.Name, Parent: parent})
}

// UnmarshalJSON reads the MarshalJSON shape. A null or absent parent maps to
// ""; an empty-string parent is rejected, matching the Java record.
func (id *ObjectId) UnmarshalJSON(data []byte) error {
	var aux objectIdJSON
	if err := json.Unmarshal(data, &aux); err != nil {
		return invalidArgf("%s", err)
	}
	kind, err := ParseObjectKind(aux.Kind)
	if err != nil {
		return err
	}
	if aux.Name == nil {
		return invalidArgf("id.name is not a string")
	}
	parent := ""
	if aux.Parent != nil {
		if *aux.Parent == "" {
			return invalidArgf("parent must not be empty")
		}
		parent = *aux.Parent
	}
	parsed, err := NewObjectId(kind, *aux.Name, parent)
	if err != nil {
		return err
	}
	*id = parsed
	return nil
}

// compareString orders strings the way Java String.compareTo does — UTF-16
// code units — so ordering stays identical across implementations. For
// BMP-only strings that equals Go's byte order; only astral characters (which
// UTF-16 encodes as surrogate pairs below U+E000) can invert it.
func compareString(a, b string) int {
	if !hasAstral(a) && !hasAstral(b) {
		return strings.Compare(a, b)
	}
	au, bu := utf16.Encode([]rune(a)), utf16.Encode([]rune(b))
	for i := 0; i < len(au) && i < len(bu); i++ {
		if au[i] != bu[i] {
			if au[i] < bu[i] {
				return -1
			}
			return 1
		}
	}
	switch {
	case len(au) < len(bu):
		return -1
	case len(au) > len(bu):
		return 1
	}
	return 0
}

func hasAstral(s string) bool {
	for _, r := range s {
		if r > 0xFFFF {
			return true
		}
	}
	return false
}

// ObjectSet is a set of ObjectIds — the plain Go map replacing the dedicated
// ObjectSet/ObjectMap wrappers the JavaScript binding needs for value-keyed
// ids.
type ObjectSet map[ObjectId]struct{}

// NewObjectSet builds a set from the given ids, deduplicated.
func NewObjectSet(ids ...ObjectId) ObjectSet {
	s := make(ObjectSet, len(ids))
	for _, id := range ids {
		s[id] = struct{}{}
	}
	return s
}

// Add inserts id and returns the set for chaining.
func (s ObjectSet) Add(id ObjectId) ObjectSet {
	s[id] = struct{}{}
	return s
}

// Has reports whether id is in the set.
func (s ObjectSet) Has(id ObjectId) bool {
	_, ok := s[id]
	return ok
}

// Sorted returns the ids in the deterministic order of spec 5.2 — the safe
// way to iterate a set whose traversal must not depend on hash order.
func (s ObjectSet) Sorted() []ObjectId {
	ids := make([]ObjectId, 0, len(s))
	for id := range s {
		ids = append(ids, id)
	}
	SortObjectIDs(ids)
	return ids
}

// SortObjectIDs sorts ids in place by the deterministic order of spec 5.2.
func SortObjectIDs(ids []ObjectId) {
	slices.SortFunc(ids, ObjectId.Compare)
}
