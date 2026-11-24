package reconciler

import (
	"slices"
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// parentableKinds are the kinds whose name can anchor a child's parent edge.
var parentableKinds = map[model.ObjectKind]struct{}{
	model.KindTable: {}, model.KindView: {}, model.KindMaterializedView: {},
	model.KindSequence: {}, model.KindIndex: {}, model.KindOther: {},
	model.KindPartition: {}, model.KindForeignTable: {},
}

// prerequisitesOf computes, for every object in the snapshot, the objects it
// requires (spec 5.8.1): its dependsOn entries plus the implicit edge from a
// child id to its parent. Self-dependencies are recorded but ignored.
func prerequisitesOf(d spi.Dialect, snapshot model.SchemaSnapshot) map[model.ObjectId]model.ObjectSet {
	parentables := parentablesOf(d, snapshot.Objects)
	out := make(map[model.ObjectId]model.ObjectSet, len(snapshot.Objects))
	for _, object := range snapshot.Objects {
		prereqs := model.NewObjectSet()
		for dep := range object.DependsOn {
			prereqs.Add(dep)
		}
		if object.ID.Parent != "" {
			if parent, ok := parentables[object.ID.Parent]; ok {
				prereqs.Add(parent)
			}
		}
		delete(prereqs, object.ID)
		out[object.ID] = prereqs
	}
	return out
}

// reverseDeps reverses an object->prerequisites map into object->dependents.
func reverseDeps(prerequisites map[model.ObjectId]model.ObjectSet) map[model.ObjectId]model.ObjectSet {
	dependents := make(map[model.ObjectId]model.ObjectSet, len(prerequisites))
	for id, prereqs := range prerequisites {
		for prereq := range prereqs {
			set, ok := dependents[prereq]
			if !ok {
				set = model.NewObjectSet()
				dependents[prereq] = set
			}
			set.Add(id)
		}
	}
	return dependents
}

// reachable returns everything reachable from 'from' over an
// object->objects map, not including 'from' itself unless a cycle leads
// back to it.
func reachable(from model.ObjectId, edges map[model.ObjectId]model.ObjectSet) model.ObjectSet {
	seen := model.NewObjectSet()
	var stack []model.ObjectId
	for id := range edgesOf(edges, from) {
		stack = append(stack, id)
	}
	for len(stack) > 0 {
		id := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		if seen.Has(id) {
			continue
		}
		seen.Add(id)
		for next := range edgesOf(edges, id) {
			stack = append(stack, next)
		}
	}
	return seen
}

func edgesOf(edges map[model.ObjectId]model.ObjectSet, id model.ObjectId) model.ObjectSet {
	if s, ok := edges[id]; ok {
		return s
	}
	return model.ObjectSet{}
}

// parentablesOf maps resolvable names to the ids children parent on.
// Same-catalog names are unique, so two managed objects never collide on a
// resolvable name — but an OTHER's cross-catalog name can, and the managed
// object must win. Only an OTHER whose stripped name is a relation name in
// the dialect's own scopes can be a parent: a constraint's stripped name is
// the synthetic "<parent>.<name>" and its relation scope is the backing
// index's name, never a parent.
func parentablesOf(d spi.Dialect, objects map[model.ObjectId]model.SchemaObject) map[string]model.ObjectId {
	sorted := sortedObjects(objects)
	parentables := make(map[string]model.ObjectId, len(sorted))
	for _, o := range sorted {
		_, parentable := parentableKinds[o.ID.Kind]
		if parentable && o.ID.Kind != model.KindOther {
			name := resolvableName(o.ID)
			if _, exists := parentables[name]; !exists {
				parentables[name] = o.ID
			}
		}
	}
	for _, o := range sorted {
		if o.ID.Kind != model.KindOther {
			continue
		}
		name := resolvableName(o.ID)
		if _, exists := parentables[name]; exists {
			continue
		}
		scopes := spi.NameScopes(d, o.ID)
		for _, scope := range scopes {
			if scope == "relation:"+name {
				parentables[name] = o.ID
				break
			}
		}
	}
	return parentables
}

// resolvableName is the name a child parent links against: an OTHER id's
// name carries a documented category prefix (spec 3.3), so the linkage keys
// on the object name behind the prefix — a "matview:mv" still blocks
// children parented on "mv".
func resolvableName(id model.ObjectId) string {
	if id.Kind == model.KindOther {
		if colon := strings.Index(id.Name, ":"); colon > 0 {
			return id.Name[colon+1:]
		}
	}
	return id.Name
}

func sortedObjects(objects map[model.ObjectId]model.SchemaObject) []model.SchemaObject {
	out := make([]model.SchemaObject, 0, len(objects))
	for _, o := range objects {
		out = append(out, o)
	}
	slices.SortFunc(out, func(a, b model.SchemaObject) int {
		return a.ID.Compare(b.ID)
	})
	return out
}
