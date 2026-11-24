package model

// NamespaceToken is the ${namespace} placeholder of spec 3.2/5.3: desired
// scripts write it for the target namespace, canonical definitions normalise
// namespace-qualified references to it, and dumpStatements fragments emit it.
const NamespaceToken = "${namespace}"

// Definition is an engine-rendered canonical definition of a schema object
// (spec 4.2, 5.3). Comparison uses it only — it is the whole basis of
// equality. A plain string kind: the definition *is* its canonical text.
type Definition string

// Canonical returns the canonical text (the Java record's canonical()).
func (d Definition) Canonical() string { return string(d) }

func (d Definition) String() string { return string(d) }
