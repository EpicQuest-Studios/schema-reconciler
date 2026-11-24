package model

// SchemaObject is one schema object in a snapshot (spec 4.2).
//
// Attributes carry dialect-specific details such as a column's type or
// nullability, for display and diagnostics — informational only, never
// compared. Comparison uses Definition only. The only attributes the core
// reads are the markers spi.ExternalDependentAttribute (5.8.3) and
// spi.ContentComparedAttribute (5.4).
type SchemaObject struct {
	ID         ObjectId
	Definition Definition
	DependsOn  ObjectSet
	Attributes map[string]string
}

// NewSchemaObject builds an object, copying dependsOn into a set and the
// attribute map.
func NewSchemaObject(id ObjectId, definition Definition,
	dependsOn []ObjectId, attributes map[string]string) SchemaObject {
	deps := NewObjectSet(dependsOn...)
	attrs := make(map[string]string, len(attributes))
	for k, v := range attributes {
		attrs[k] = v
	}
	return SchemaObject{ID: id, Definition: definition, DependsOn: deps, Attributes: attrs}
}
