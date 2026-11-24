package reconciler

import (
	"slices"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// differDiff computes the differences of spec 5.4 and 5.5 between the actual
// and desired snapshots, sorted by the deterministic order of spec 5.2.
func differDiff(actual, desired model.SchemaSnapshot, d spi.Dialect) []model.Difference {
	var out []model.Difference
	caps := d.Capabilities()
	for _, want := range sortedObjects(desired.Objects) {
		id := want.ID
		have, haveOk := actual.Objects[id]
		if !caps.SupportsKind(id.Kind) {
			if isContentCompared(want) && haveOk && have.Definition == want.Definition {
				continue
			}
			out = append(out, model.Difference{
				ID:       id,
				Type:     model.DifferenceUnsupported,
				Expected: &want.Definition,
				Actual:   definitionOf(have, haveOk),
			})
		} else if !haveOk {
			out = append(out, model.Difference{
				ID:       id,
				Type:     model.DifferenceMissing,
				Expected: &want.Definition,
			})
		} else if have.Definition != want.Definition {
			out = append(out, model.Difference{
				ID:       id,
				Type:     model.DifferenceDrifted,
				Expected: &want.Definition,
				Actual:   &have.Definition,
			})
		}
	}
	for _, have := range sortedObjects(actual.Objects) {
		if _, wanted := desired.Objects[have.ID]; !wanted && !isExternalDependent(have) {
			out = append(out, model.Difference{
				ID:     have.ID,
				Type:   model.DifferenceUnexpected,
				Actual: &have.Definition,
			})
		}
	}
	slices.SortFunc(out, func(a, b model.Difference) int {
		return a.ID.Compare(b.ID)
	})
	return out
}

// isContentCompared reports the marker of spec 5.4: an unsupported-kind
// object whose canonical definition faithfully renders its content.
func isContentCompared(object model.SchemaObject) bool {
	return object.Attributes[spi.ContentComparedAttribute] == "true"
}

// isExternalDependent reports the marker of spec 5.8.3: an object outside
// the snapshot's namespace recorded only to name dependents in errors.
func isExternalDependent(object model.SchemaObject) bool {
	return object.Attributes[spi.ExternalDependentAttribute] == "true"
}

func definitionOf(o model.SchemaObject, ok bool) *model.Definition {
	if !ok {
		return nil
	}
	def := o.Definition
	return &def
}
