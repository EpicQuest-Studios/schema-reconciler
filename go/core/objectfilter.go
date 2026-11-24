package reconciler

import (
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// mirroredKinds are the annotation kinds that shadow their target's
// parentage (spec 4.1).
var mirroredKinds = map[model.ObjectKind]struct{}{
	model.KindComment: {}, model.KindSecurityLabel: {},
	model.KindOwnership: {}, model.KindPrivileges: {},
}

// relationOthers are the OTHER prefixes whose rows can own catalog children.
var relationOthers = map[string]struct{}{
	"inherited": {}, "partindex": {}, "toast": {}, "relation": {},
}

// annotationOthers are the OTHER prefixes that name a target — the
// unsupported shadow forms of the mirrored kinds plus "constraint:", whose
// "<parent>.<member>" name carries the relation it constrains.
var annotationOthers = []string{
	"comment:", "seclabel:", "privileges:", "ownership:", "constraint:",
}

// applyObjectFilter implements the object filter of Builder.Filter: removes
// every object the predicate rejects, plus everything the rejected object
// covers — its parented children, the mirrored annotation kinds placed on
// it or on one of its children, the annotation OTHERs naming it, and
// follower sequences whose OWNED BY dependency names it — and returns a
// snapshot over the survivors. The filtered snapshot carries a fresh
// fingerprint, so plan staleness (5.7) is scoped to the visible objects.
func applyObjectFilter(snapshot model.SchemaSnapshot, include ObjectFilter) model.SchemaSnapshot {
	remaining := make(map[model.ObjectId]model.SchemaObject, len(snapshot.Objects))
	removed := model.NewObjectSet()
	removedOwnerNames := map[string]struct{}{}
	removedTargets := map[string]map[string]struct{}{}
	removedTargetNames := map[string]struct{}{}
	for _, object := range snapshot.Objects {
		id := object.ID
		if include(id) {
			remaining[id] = object
		} else {
			removed.Add(id)
			filterDrop(id, removedOwnerNames, removedTargets, removedTargetNames)
		}
	}
	if len(remaining) == len(snapshot.Objects) {
		return snapshot
	}
	// children and mirrored annotations of a removed object go too —
	// transitively, since a removed child can itself own names (a
	// partitioned partition, an inherited: OTHER relation)
	for changed := true; changed; {
		changed = false
		for id, object := range remaining {
			_, ownerRemoved := removedOwnerNames[id.Parent]
			if ownerRemoved || mirrorsExcluded(id, removedTargets) ||
				annotationOnRemoved(id, removedTargetNames) ||
				followerOf(id, object, removed) {
				delete(remaining, id)
				removed.Add(id)
				filterDrop(id, removedOwnerNames, removedTargets, removedTargetNames)
				changed = true
			}
		}
	}
	if len(remaining) == len(snapshot.Objects) {
		return snapshot
	}
	return model.NewSchemaSnapshot(remaining)
}

// followerOf reports whether id is a serial-owned sequence following a
// removed owning relation — the OWNED BY dependency edge is the same
// covered-child signal Dialect's CoveredChildren uses.
func followerOf(id model.ObjectId, object model.SchemaObject, removed model.ObjectSet) bool {
	if id.Kind != model.KindSequence {
		return false
	}
	for dep := range object.DependsOn {
		if removed.Has(dep) {
			return true
		}
	}
	return false
}

// filterDrop records id's owned names so children, mirrors and OTHER
// annotations are removed with it.
func filterDrop(id model.ObjectId, removedOwnerNames map[string]struct{},
	removedTargets map[string]map[string]struct{}, removedTargetNames map[string]struct{}) {
	if owner, ok := filterOwnerName(id); ok {
		removedOwnerNames[owner] = struct{}{}
	}
	targets := removedTargets[id.Parent]
	if targets == nil {
		targets = map[string]struct{}{}
		removedTargets[id.Parent] = targets
	}
	targets[id.Name] = struct{}{}
	if id.Parent != "" {
		removedTargetNames[id.Parent+"."+filterPgName(id)] = struct{}{}
	} else {
		removedTargetNames[filterPgName(id)] = struct{}{}
	}
}

// filterPgName is the catalog name an annotation embeds: an OTHER id's
// "<prefix>:<name>" reduces to "<name>".
func filterPgName(id model.ObjectId) string {
	if id.Kind == model.KindOther {
		if colon := strings.Index(id.Name, ":"); colon > 0 {
			return id.Name[colon+1:]
		}
	}
	return id.Name
}

// annotationOnRemoved reports whether id is an annotation-OTHER whose
// target was removed.
func annotationOnRemoved(id model.ObjectId, removedTargetNames map[string]struct{}) bool {
	if id.Kind != model.KindOther {
		return false
	}
	for _, prefix := range annotationOthers {
		if !strings.HasPrefix(id.Name, prefix) {
			continue
		}
		target := id.Name[len(prefix):]
		if prefix == "seclabel:" {
			// seclabel:<provider>.<target> — the provider is one segment
			dot := strings.Index(target, ".")
			if dot < 0 {
				continue
			}
			target = target[dot+1:]
		} else if prefix == "constraint:" {
			// constraint:<parent>.<member> — only the parent is a target;
			// stripping further would confuse a member name with a
			// column's qualified name
			dot := strings.LastIndex(target, ".")
			if dot <= 0 {
				return false
			}
			_, ok := removedTargetNames[target[:dot]]
			return ok
		}
		// trailing .attname segments do not belong to the target
		for len(target) > 0 {
			if _, ok := removedTargetNames[target]; ok {
				return true
			}
			dot := strings.LastIndex(target, ".")
			if dot < 0 {
				break
			}
			target = target[:dot]
		}
		return false
	}
	return false
}

// filterOwnerName is the name children use to parent on this object, when
// it can own any.
func filterOwnerName(id model.ObjectId) (string, bool) {
	switch id.Kind {
	case model.KindTable, model.KindView, model.KindMaterializedView,
		model.KindPartition, model.KindForeignTable:
		return id.Name, true
	case model.KindOther:
		colon := strings.Index(id.Name, ":")
		if colon > 0 {
			if _, ok := relationOthers[id.Name[:colon]]; ok {
				return id.Name[colon+1:], true
			}
		}
	}
	return "", false
}

// mirrorsExcluded reports whether id is a mirrored annotation on an
// excluded object.
func mirrorsExcluded(id model.ObjectId, excludedTargets map[string]map[string]struct{}) bool {
	if _, mirrored := mirroredKinds[id.Kind]; !mirrored {
		return false
	}
	names, ok := excludedTargets[id.Parent]
	if !ok {
		return false
	}
	_, found := names[filterTargetName(id)]
	return found
}

// filterTargetName is the annotated object's name inside a mirrored id: the
// " (KIND)" collision suffix and a security label's provider: prefix do not
// belong to it.
func filterTargetName(id model.ObjectId) string {
	name := id.Name
	if paren := strings.Index(name, " ("); paren >= 0 {
		name = name[:paren]
	}
	if id.Kind == model.KindSecurityLabel {
		// the provider is the segment before the first ':'
		if colon := strings.Index(name, ":"); colon >= 0 {
			name = name[colon+1:]
		}
	}
	return name
}
