package reconciler

import (
	"slices"
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// renderDump renders an actual snapshot back into a namespace-neutral
// desired-state script — the body of SchemaReconciler.Dump (spec 3.1).
// Objects emit in dependsOn order; an object the dialect cannot render
// becomes a comment line rather than being silently dropped.
func renderDump(d spi.Dialect, namespace string, snapshot model.SchemaSnapshot) (string, error) {
	quoted, err := d.QuoteIdentifier(namespace)
	if err != nil {
		return "", err
	}
	var out strings.Builder
	out.WriteString("-- schema-reconciler dump\n")
	out.WriteString("-- dialect: " + d.ID() + "\n")
	out.WriteString("-- namespace: " + quoted + "\n")
	out.WriteString("-- fingerprint: " + snapshot.Fingerprint + "\n")
	for _, object := range dumpOrder(d, snapshot) {
		statements := spi.DumpStatements(d, object)
		if len(statements) == 0 {
			folded := "unsupported"
			if dumpFolded(object, snapshot) {
				folded = "folded"
			}
			out.WriteString("-- " + folded + ": " + object.ID.String() + "\n")
			continue
		}
		for _, statement := range statements {
			out.WriteString(statement + ";\n")
		}
	}
	return out.String(), nil
}

// dumpOrder is a topological order over the dependency graph: every object
// after the objects it requires, ties by spec-5.2 order so the output is
// deterministic. Edges naming objects outside the snapshot are ignored; a
// dependency cycle emits its members in plain order after the acyclic
// prefix.
func dumpOrder(d spi.Dialect, snapshot model.SchemaSnapshot) []model.SchemaObject {
	objects := snapshot.Objects
	prereqs := prerequisitesOf(d, snapshot)
	pending := make(map[model.ObjectId]model.ObjectSet, len(prereqs))
	for id, reqs := range prereqs {
		present := model.NewObjectSet()
		for r := range reqs {
			if _, ok := objects[r]; ok {
				present.Add(r)
			}
		}
		pending[id] = present
	}
	dependents := reverseDeps(pending)
	ready := newMinHeap(func(a, b model.ObjectId) int { return a.Compare(b) })
	for id, reqs := range pending {
		if len(reqs) == 0 {
			ready.add(id)
		}
	}
	var ordered []model.SchemaObject
	for {
		id, ok := ready.poll()
		if !ok {
			break
		}
		ordered = append(ordered, objects[id])
		for dependent := range dependents[id] {
			if reqs, ok := pending[dependent]; ok {
				if _, had := reqs[id]; had {
					delete(reqs, id)
					if len(reqs) == 0 {
						ready.add(dependent)
					}
				}
			}
		}
	}
	if len(ordered) < len(objects) {
		var leftover []model.ObjectId
		for id, reqs := range pending {
			if len(reqs) > 0 {
				leftover = append(leftover, id)
			}
		}
		slices.SortFunc(leftover, model.ObjectId.Compare)
		for _, id := range leftover {
			ordered = append(ordered, objects[id])
		}
	}
	return ordered
}

// dumpFolded reports whether the object's creation is folded into another
// object's fragment and it therefore renders no statement of its own (spec
// 3.1 "-- folded"): a column of a managed relation (table, partition,
// foreign table), or a sequence owned by a table column — detected as a
// dependsOn edge to a snapshot TABLE.
func dumpFolded(object model.SchemaObject, snapshot model.SchemaSnapshot) bool {
	id := object.ID
	switch id.Kind {
	case model.KindColumn:
		if id.Parent == "" {
			return false
		}
		for o := range snapshot.Objects {
			if o.Name == id.Parent && (o.Kind == model.KindTable ||
				o.Kind == model.KindPartition || o.Kind == model.KindForeignTable) {
				return true
			}
		}
	case model.KindSequence:
		for dep := range object.DependsOn {
			if dep.Kind == model.KindTable {
				if _, ok := snapshot.Objects[dep]; ok {
					return true
				}
			}
		}
	}
	return false
}
