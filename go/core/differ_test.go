package reconciler

// differ_test.go — Go port of test/DefinitionComparison.test.ts: spec 4.2 —
// comparison uses the definition only, never the attributes.

import (
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

func attributeSnapshot(attribute string) model.SchemaSnapshot {
	id := model.ObjectId{Kind: model.KindTable, Name: "t"}
	return model.NewSchemaSnapshot(map[model.ObjectId]model.SchemaObject{
		id: model.NewSchemaObject(id, model.Definition("same"), nil,
			map[string]string{"note": attribute}),
	})
}

func TestDifferDifferentAttributesWithEqualDefinitionsAreNotADifference(t *testing.T) {
	diff := differDiff(attributeSnapshot("a"), attributeSnapshot("b"),
		newFakeDialect(newFakeDb()))
	if len(diff) != 0 {
		t.Fatalf("diff = %v, want empty", diff)
	}
}
