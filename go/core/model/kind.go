// Package model contains the snapshot and plan model of the schema
// reconciler (SPEC.md section 4) — the Go analog of
// studio.epicquest.schema.reconciler.model. All types are namespace-neutral
// value types; ObjectId is comparable and keys every object collection.
package model

import "strconv"

// ObjectKind is the kind of a schema object (spec 4.1). Declaration order is
// significant: the deterministic ordering of spec 5.2 compares kinds by this
// ordinal, so it must stay aligned with the Java enum.
type ObjectKind int

const (
	KindTable ObjectKind = iota
	KindColumn
	KindPrimaryKey
	KindUnique
	KindForeignKey
	KindCheck
	KindIndex
	KindView
	KindSequence
	KindRoutine
	KindTrigger
	KindOther
	KindMaterializedView
	KindType
	KindDomain
	KindPolicy
	KindExtension
	KindComment
	KindExclusion
	KindStatistics
	KindRule
	KindCollation
	KindConversion
	KindSecurityLabel
	KindPartition
	KindForeignTable
	KindPublication
	KindOwnership
	KindPrivileges
	KindCast
	KindTransform
	KindAggregate
	KindDefaultPrivileges
	KindTextSearchTemplate
	KindTextSearchParser
	KindTextSearchDictionary
	KindTextSearchConfiguration
	KindOperator
	KindOperatorClass
	KindOperatorFamily
)

var objectKindNames = [...]string{
	KindTable:                   "TABLE",
	KindColumn:                  "COLUMN",
	KindPrimaryKey:              "PRIMARY_KEY",
	KindUnique:                  "UNIQUE",
	KindForeignKey:              "FOREIGN_KEY",
	KindCheck:                   "CHECK",
	KindIndex:                   "INDEX",
	KindView:                    "VIEW",
	KindSequence:                "SEQUENCE",
	KindRoutine:                 "ROUTINE",
	KindTrigger:                 "TRIGGER",
	KindOther:                   "OTHER",
	KindMaterializedView:        "MATERIALIZED_VIEW",
	KindType:                    "TYPE",
	KindDomain:                  "DOMAIN",
	KindPolicy:                  "POLICY",
	KindExtension:               "EXTENSION",
	KindComment:                 "COMMENT",
	KindExclusion:               "EXCLUSION",
	KindStatistics:              "STATISTICS",
	KindRule:                    "RULE",
	KindCollation:               "COLLATION",
	KindConversion:              "CONVERSION",
	KindSecurityLabel:           "SECURITY_LABEL",
	KindPartition:               "PARTITION",
	KindForeignTable:            "FOREIGN_TABLE",
	KindPublication:             "PUBLICATION",
	KindOwnership:               "OWNERSHIP",
	KindPrivileges:              "PRIVILEGES",
	KindCast:                    "CAST",
	KindTransform:               "TRANSFORM",
	KindAggregate:               "AGGREGATE",
	KindDefaultPrivileges:       "DEFAULT_PRIVILEGES",
	KindTextSearchTemplate:      "TEXT_SEARCH_TEMPLATE",
	KindTextSearchParser:        "TEXT_SEARCH_PARSER",
	KindTextSearchDictionary:    "TEXT_SEARCH_DICTIONARY",
	KindTextSearchConfiguration: "TEXT_SEARCH_CONFIGURATION",
	KindOperator:                "OPERATOR",
	KindOperatorClass:           "OPERATOR_CLASS",
	KindOperatorFamily:          "OPERATOR_FAMILY",
}

var objectKindByName = func() map[string]ObjectKind {
	m := make(map[string]ObjectKind, len(objectKindNames))
	for k, name := range objectKindNames {
		m[name] = ObjectKind(k)
	}
	return m
}()

// String returns the constant name, for example "TABLE". This is the spelling
// used by plan JSON and by the snapshot fingerprint of spec 5.7.
func (k ObjectKind) String() string {
	if int(k) < 0 || int(k) >= len(objectKindNames) {
		return "ObjectKind(" + strconv.Itoa(int(k)) + ")"
	}
	return objectKindNames[k]
}

// ParseObjectKind maps a constant name back to its kind; the inverse of
// String and of the plan-JSON encoding.
func ParseObjectKind(name string) (ObjectKind, error) {
	if k, ok := objectKindByName[name]; ok {
		return k, nil
	}
	return 0, invalidArgf("unknown ObjectKind %q", name)
}

// MarshalText encodes the kind as its constant name.
func (k ObjectKind) MarshalText() ([]byte, error) {
	return []byte(k.String()), nil
}

// AllObjectKinds returns every kind in declaration order — the seed for
// dialect SupportedKinds sets (the PostgreSQL dialect supports all but
// KindOther).
func AllObjectKinds() []ObjectKind {
	out := make([]ObjectKind, len(objectKindNames))
	for i := range objectKindNames {
		out[i] = ObjectKind(i)
	}
	return out
}

// UnmarshalText decodes a constant name produced by MarshalText.
func (k *ObjectKind) UnmarshalText(text []byte) error {
	parsed, err := ParseObjectKind(string(text))
	if err != nil {
		return err
	}
	*k = parsed
	return nil
}
