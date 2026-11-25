package reconciler

import (
	"regexp"
	"strings"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// scriptToken is the desired-script placeholder bound to the scratch
// namespace (spec 3.2); the doubled-dollar form is its escape.
const (
	scriptToken   = "${namespace}"
	scriptEscaped = "$" + scriptToken
)

// scriptProcessor is the result of preparing a desired script for
// execution in the scratch namespace (spec 3.2): the literal-target scan
// (rule 5), the quoted-placeholder rejection, the ${namespace}
// substitution with $${namespace} escaping, and the
// `-- @reconciler rename` directive harvest.
type scriptProcessor struct {
	// Substituted is the script with every placeholder replaced by the
	// quoted scratch namespace.
	Substituted string
	// HasPlaceholder reports whether the script contained at least one
	// ${namespace} placeholder.
	HasPlaceholder bool
	// RenameHints are the `-- @reconciler rename` directives found in the
	// script (spec 5.5.1).
	RenameHints []model.RenameHint
	// RetireTargets are the `-- @reconciler retire` directives found in the script.
	RetireTargets []model.ObjectId
}

// newScriptProcessor runs spec 3.2 over the raw script for
// targetNamespace, binding placeholders to scratchNamespace.
func newScriptProcessor(script, targetNamespace, scratchNamespace string,
	d spi.Dialect) (*scriptProcessor, error) {
	open, close_, err := identifierQuotes(d)
	if err != nil {
		return nil, err
	}
	if strings.Contains(script, open+scriptToken+close_) {
		return nil, NewDesiredStateErrorf(
			"namespace '%s': the placeholder %s must not be quoted; the replacement is already quoted",
			targetNamespace, scriptToken)
	}
	if err := checkLiteralTarget(script, targetNamespace, d); err != nil {
		return nil, err
	}
	quotedScratch, err := d.QuoteIdentifier(scratchNamespace)
	if err != nil {
		return nil, err
	}
	var out strings.Builder
	found := false
	for i := 0; i < len(script); {
		if strings.HasPrefix(script[i:], scriptEscaped) {
			out.WriteString("\x00")
			i += len(scriptEscaped)
		} else if strings.HasPrefix(script[i:], scriptToken) {
			out.WriteString(quotedScratch)
			found = true
			i += len(scriptToken)
		} else {
			out.WriteByte(script[i])
			i++
		}
	}
	directives, err := parseScriptDirectives(script)
	if err != nil {
		return nil, err
	}
	return &scriptProcessor{
		Substituted:    strings.ReplaceAll(out.String(), "\x00", scriptToken),
		HasPlaceholder: found,
		RenameHints:    directives.renames,
		RetireTargets: directives.retires,
	}, nil
}

// checkLiteralTarget is spec 3.2 rule 5: reject literal references to the
// target namespace — bare or in identifier quotes, immediately followed by
// `.`, not preceded by an identifier character or `.`. The comparison is
// the mirror image of namespace normalisation: the quoted form is exact
// (`"APP"` is not `app`), and the bare form is matched case-insensitively
// only when the target is fold-safe — unquoted references fold to
// lowercase, so they cannot name a mixed-case target anyway. The scan runs
// on text with the placeholders masked out, so `${namespace}.` can never
// trip it even if the target is literally named "namespace".
func checkLiteralTarget(script, targetNamespace string, d spi.Dialect) error {
	masked := maskScriptPlaceholders(script)
	quoted, err := d.QuoteIdentifier(targetNamespace)
	if err != nil {
		return err
	}
	hit := searchLiteralTarget(masked, quoted, false)
	if hit < 0 && targetNamespace == strings.ToLower(targetNamespace) {
		hit = searchLiteralTarget(masked, targetNamespace, true)
	}
	if hit >= 0 {
		return NewDesiredStateErrorf(
			"the script refers to the target namespace '%s' literally at position %d; use ${namespace} or unqualified names instead",
			targetNamespace, hit)
	}
	return nil
}

// searchLiteralTarget finds `(?<![A-Za-z0-9_$.])<name>\.` in text — Go's
// regexp engine has no lookbehind, so the match runs forward and the
// boundary is checked by hand; the earlier of the quoted/bare positions
// wins.
func searchLiteralTarget(text, name string, ignoreCase bool) int {
	pattern := regexp.QuoteMeta(name) + `\.`
	if ignoreCase {
		pattern = `(?i)` + pattern
	}
	re := regexp.MustCompile(pattern)
	loc := re.FindStringIndex(text)
	if loc == nil {
		return -1
	}
	if loc[0] > 0 {
		prev := text[loc[0]-1]
		if isWordOrDollarDot(prev) {
			// the first candidate was preceded by an identifier character:
			// keep scanning from just after it
			if next := searchLiteralTarget(text[loc[0]+1:], name, ignoreCase); next >= 0 {
				return loc[0] + 1 + next
			}
			return -1
		}
	}
	return loc[0]
}

func isWordOrDollarDot(c byte) bool {
	return c == '.' || c == '$' || c == '_' ||
		(c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
}

// maskScriptPlaceholders blanks every placeholder token to same-length
// spaces so the literal-target scan sees stable positions.
func maskScriptPlaceholders(script string) string {
	masked := script
	for i := 0; i < len(masked); {
		var length int
		if strings.HasPrefix(masked[i:], scriptEscaped) {
			length = len(scriptEscaped)
		} else if strings.HasPrefix(masked[i:], scriptToken) {
			length = len(scriptToken)
		} else {
			i++
			continue
		}
		masked = masked[:i] + strings.Repeat(" ", length) + masked[i+length:]
		i += length
	}
	return masked
}

// identifierQuotes returns the open and close characters of the dialect's
// identifier quoting, probed by rendering a sentinel identifier.
func identifierQuotes(d spi.Dialect) (string, string, error) {
	quoted, err := d.QuoteIdentifier("rcl_probe")
	if err != nil {
		return "", "", err
	}
	return quoted[:1], quoted[len(quoted)-1:], nil
}
