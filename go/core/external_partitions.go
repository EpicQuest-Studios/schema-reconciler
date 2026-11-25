package reconciler

import "github.com/EpicQuest-Studios/schema-reconciler/go/core/model"

type externalPartitionMatcher func(parentTable, partitionName string) bool

// suppressExternalPartitions hides matching actual partitions, cascading
// through the object filter exactly like a user exclusion.
func suppressExternalPartitions(actual model.SchemaSnapshot, desired *model.SchemaSnapshot,
	rules []externalPartitionMatcher, requireDesiredAncestor bool) model.SchemaSnapshot {
	if len(rules) == 0 {
		return actual
	}
	return applyObjectFilter(actual, func(id model.ObjectId) bool {
		if id.Kind != model.KindPartition || id.Parent == "" || !externalPartitionMatches(id, rules) {
			return true
		}
		if !requireDesiredAncestor || desired == nil {
			return false
		}
		if desired.Objects != nil {
			if _, ok := desired.Objects[id]; ok {
				return true
			}
		}
		return !hasDesiredPartitionedAncestor(id, actual, *desired)
	})
}

func externalPartitionMatches(id model.ObjectId, rules []externalPartitionMatcher) bool {
	for _, rule := range rules {
		if rule(id.Parent, id.Name) {
			return true
		}
	}
	return false
}

func hasDesiredPartitionedAncestor(partition model.ObjectId,
	actual, desired model.SchemaSnapshot) bool {
	parentName := partition.Parent
	directParent := true
	seen := map[string]bool{}
	for parentName != "" && !seen[parentName] {
		seen[parentName] = true
		table := model.ObjectId{Kind: model.KindTable, Name: parentName}
		if _, ok := desired.Objects[table]; ok {
			return true
		}
		actualParent := table
		if _, ok := actual.Objects[table]; !ok {
			found := false
			for _, object := range actual.Objects {
				if object.ID.Kind == model.KindPartition && object.ID.Name == parentName {
					actualParent = object.ID
					found = true
					break
				}
			}
			if !found {
				return false
			}
		}
		if _, ok := desired.Objects[actualParent]; ok && (directParent || actualParent.Kind == model.KindTable) {
			return true
		}
		if actualParent.Kind != model.KindPartition || actualParent.Parent == "" {
			return false
		}
		directParent = false
		parentName = actualParent.Parent
	}
	return false
}
