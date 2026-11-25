package reconciler

import (
	"regexp"
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
)

// renameDirective marks a rename directive of spec 5.5.1: a whole line of
// the form `-- @reconciler rename <KIND> <from> -> <to>` (leading and
// trailing whitespace allowed).
var renameDirective = regexp.MustCompile(
	`^\s*--\s*@reconciler\s+rename\s+([A-Za-z_]+)\s+(\S+)\s*->\s*(\S+)\s*$`)
var retireDirective = regexp.MustCompile(
	`^\s*--\s*@reconciler\s+retire\s+([A-Za-z_]+)\s+(\S+)\s*$`)

// parseRenameDirectives collects the `-- @reconciler rename` directives of
// a desired-state script (spec 3.2, 5.5). Identifier paths follow SQL
// quoting: a "double quoted" segment is verbatim text ("" escapes a
// quote); a bare segment is used as written. The last path segment is the
// object name, the rest (at most one) its parent.
type scriptDirectives struct {
	renames []model.RenameHint
	retires []model.ObjectId
}

func parseRenameDirectives(script string) ([]model.RenameHint, error) {
	directives, err := parseScriptDirectives(script)
	return directives.renames, err
}

func parseScriptDirectives(script string) (scriptDirectives, error) {
	var hints []model.RenameHint
	var retires []model.ObjectId
	for _, line := range strings.Split(script, "\n") {
		m := renameDirective.FindStringSubmatch(line)
		if m == nil {
			r := retireDirective.FindStringSubmatch(line)
			if r != nil {
				kind, err := model.ParseObjectKind(strings.ToUpper(r[1]))
				if err != nil {
					return scriptDirectives{}, NewDesiredStateErrorf(
						"@reconciler retire: unknown object kind '%s'", r[1])
				}
				if kind == model.KindOther {
					return scriptDirectives{}, NewDesiredStateErrorf(
						"@reconciler retire: OTHER objects cannot be retired")
				}
				id, err := retireIDPath(kind, r[2])
				if err != nil {
					return scriptDirectives{}, err
				}
				retires = append(retires, id)
			}
			continue
		}
		kind, err := model.ParseObjectKind(strings.ToUpper(m[1]))
		if err != nil {
			return scriptDirectives{}, NewDesiredStateErrorf(
				"@reconciler rename: unknown object kind '%s'", m[1])
		}
		from, err := renameIDPath(kind, m[2])
		if err != nil {
			return scriptDirectives{}, err
		}
		// the parent comes from the from path when to carries none
		to, err := renameIDPathWithParent(kind, m[3], from.Parent)
		if err != nil {
			return scriptDirectives{}, err
		}
		hints = append(hints, model.RenameHint{From: from, To: to})
	}
	return scriptDirectives{renames: hints, retires: retires}, nil
}

// renameIDPath parses `<KIND> [parent.]name`; the parent comes from the
// from path when the to path carries none — handled by the caller.
func renameIDPath(kind model.ObjectKind, text string) (model.ObjectId, error) {
	return renameIDPathWithParent(kind, text, "")
}

func renameIDPathWithParent(kind model.ObjectKind, text, inheritedParent string) (model.ObjectId, error) {
	parts, err := splitRenamePath(text)
	if err != nil {
		return model.ObjectId{}, err
	}
	if len(parts) == 0 || len(parts) > 2 {
		return model.ObjectId{}, NewDesiredStateErrorf(
			"@reconciler rename: bad identifier path '%s'", text)
	}
	for _, p := range parts {
		if p == "" {
			return model.ObjectId{}, NewDesiredStateErrorf(
				"@reconciler rename: bad identifier path '%s'", text)
		}
	}
	id := model.ObjectId{Kind: kind, Name: parts[len(parts)-1]}
	if len(parts) == 2 {
		id.Parent = parts[0]
	} else {
		id.Parent = inheritedParent
	}
	if err := id.Validate(); err != nil {
		return model.ObjectId{}, NewDesiredStateErrorf(
			"@reconciler directive: bad identifier path '%s'", text).WithCause(err)
	}
	return id, nil
}

func retireIDPath(kind model.ObjectKind, text string) (model.ObjectId, error) {
	return renameIDPath(kind, text)
}

// splitRenamePath splits a `.`-separated identifier path on dots outside
// double quotes and unquotes each segment; "" inside quotes is an escaped
// quote.
func splitRenamePath(text string) ([]string, error) {
	var parts []string
	var cur strings.Builder
	inQuote := false
	for i := 0; i < len(text); i++ {
		c := text[i]
		if inQuote {
			if c == '"' {
				if i+1 < len(text) && text[i+1] == '"' {
					cur.WriteByte('"')
					i++
				} else {
					inQuote = false
				}
			} else {
				cur.WriteByte(c)
			}
		} else if c == '"' {
			inQuote = true
		} else if c == '.' {
			parts = append(parts, cur.String())
			cur.Reset()
		} else {
			cur.WriteByte(c)
		}
	}
	if inQuote {
		return nil, NewDesiredStateErrorf(
			"@reconciler rename: unterminated quote in '%s'", text)
	}
	parts = append(parts, cur.String())
	return parts, nil
}
