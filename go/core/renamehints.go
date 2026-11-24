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

// parseRenameDirectives collects the `-- @reconciler rename` directives of
// a desired-state script (spec 3.2, 5.5). Identifier paths follow SQL
// quoting: a "double quoted" segment is verbatim text ("" escapes a
// quote); a bare segment is used as written. The last path segment is the
// object name, the rest (at most one) its parent.
func parseRenameDirectives(script string) ([]model.RenameHint, error) {
	var hints []model.RenameHint
	for _, line := range strings.Split(script, "\n") {
		m := renameDirective.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		kind, err := model.ParseObjectKind(strings.ToUpper(m[1]))
		if err != nil {
			return nil, NewDesiredStateErrorf(
				"@reconciler rename: unknown object kind '%s'", m[1])
		}
		from, err := renameIDPath(kind, m[2])
		if err != nil {
			return nil, err
		}
		to, err := renameIDPath(kind, m[3])
		if err != nil {
			return nil, err
		}
		if to.Parent == "" {
			// the parent comes from the from path when to carries none
			to.Parent = from.Parent
		}
		hints = append(hints, model.RenameHint{From: from, To: to})
	}
	return hints, nil
}

// renameIDPath parses `<KIND> [parent.]name`; the parent comes from the
// from path when the to path carries none — handled by the caller.
func renameIDPath(kind model.ObjectKind, text string) (model.ObjectId, error) {
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
	}
	return id, nil
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
