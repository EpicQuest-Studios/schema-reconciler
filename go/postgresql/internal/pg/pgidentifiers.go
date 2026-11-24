package pg

import (
	"fmt"
	"regexp"
	"strings"
	"unicode/utf8"
)

// Identifier quoting and the constant-default classification of spec A.6.

var numeric = regexp.MustCompile(`^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?$`)

// A single `::type` cast: an optionally schema-qualified type name
// (quoted or unquoted identifier parts), optional precision and optional
// array markers — and nothing else. Anything past the type name, for
// example an operator, a call, a comment or a second statement, makes the
// whole expression not a plain cast (A.6).
var typeCast = regexp.MustCompile(
	`^::\s*(?:"(?:[^"]|"")+"|[A-Za-z_][A-Za-z0-9_$]*)` +
		`(?:\s*\.\s*(?:"(?:[^"]|"")+"|[A-Za-z_][A-Za-z0-9_$]*))?` +
		`(?:\s*\(\s*\d+(?:\s*,\s*\d+)*\s*\))?` +
		`(?:\s*\[\s*\])*\s*$`)

// Quote renders `"<identifier>"` with embedded quotes doubled (6.1, 8.3).
// Errors on identifiers that are empty, contain NUL, or exceed 63 UTF-8
// bytes — the Go analog of the IllegalArgumentError thrown by the Java
// and TypeScript implementations.
func Quote(identifier string) (string, error) {
	if identifier == "" || len(identifier) > 63 || strings.ContainsRune(identifier, 0) {
		return "", fmt.Errorf("postgresql: illegal identifier %q", identifier)
	}
	return `"` + strings.ReplaceAll(identifier, `"`, `""`) + `"`, nil
}

// IsConstantDefault implements the conservative lexical rule of spec A.6:
// a default is constant only if it is a numeric literal, a boolean
// literal, NULL, or a quoted string literal, each optionally followed by
// one `::type` cast. Anything else (functions, arithmetic, nextval) is
// not constant.
func IsConstantDefault(expression string) bool {
	s := strings.TrimSpace(expression)
	if idx := strings.Index(s, "::"); idx > 0 {
		cast := s[idx:]
		if !typeCast.MatchString(cast) {
			return false
		}
		s = strings.TrimSpace(s[:idx])
		if strings.Contains(s, "::") {
			return false
		}
	}
	if s == "" {
		return false
	}
	upper := strings.ToUpper(s)
	if upper == "NULL" || upper == "TRUE" || upper == "FALSE" {
		return true
	}
	if numeric.MatchString(s) {
		return true
	}
	if strings.HasPrefix(s, "'") && strings.HasSuffix(s, "'") && len(s) >= 2 {
		inner := s[1 : len(s)-1]
		return !strings.Contains(strings.ReplaceAll(inner, "''", ""), "'")
	}
	return false
}

// ScratchName renders a scratch namespace name (6.3.1):
// `<target>__rcl_<12 hex>`, truncated to 63 bytes. The prefix is
// lower-cased: unquoted references fold to lowercase, so only a fold-safe
// scratch name makes the script check's case-insensitive qualifier
// comparison sound.
func ScratchName(targetNamespace string, seed uint64) string {
	suffix := fmt.Sprintf("__rcl_%012x", seed)
	prefix := strings.ToLower(targetNamespace)
	max := 63 - len(suffix)
	if len(prefix) > max {
		// truncate on a UTF-8 boundary
		prefix = prefix[:max]
		for len(prefix) > 0 && !utf8.ValidString(prefix) {
			prefix = prefix[:len(prefix)-1]
		}
	}
	return prefix + suffix
}
