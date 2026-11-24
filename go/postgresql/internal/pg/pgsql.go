package pg

import (
	"strings"
	"unicode"
)

// PostgreSQL-aware SQL text handling: the normalisation of spec 5.3 and
// the lexical statement scan the script constraints of spec 3.2 need.
// Both share one lexer that understands '...' strings (with '' escape),
// "..." quoted identifiers (with "" escape), $tag$ dollar-quoted bodies
// and line/block comments.
//
// Character classes follow java.lang.Character semantics, matching the
// Java/TypeScript implementations: letters are Unicode letters, and the
// scan advances per code unit — a non-BMP rune is never an identifier
// character (its UTF-16 surrogates are not letters).

// Normalize applies rule 5.3: namespace reference replacement +
// whitespace collapse.
func Normalize(text, namespace string) string {
	return collapseWhitespace(ReplaceNamespaceRefs(text, namespace))
}

// ReplaceNamespaceRefs implements rule 5.3(1): `<ns>.` and `"<ns>".`
// become `${namespace}.` at identifier boundaries. The quoted form is
// matched case-sensitively: `"APP"` is a different namespace from `app`
// in PostgreSQL. The unquoted form is matched case-insensitively when ns
// is fold-safe (it is its own lowercase fold): unquoted references fold
// to lowercase, so `COREX.t` also means `corex`.
func ReplaceNamespaceRefs(text, namespace string) string {
	rs := []rune(text)
	quoted := []rune(`"` + namespace + `".`)
	foldSafe := namespace == strings.ToLower(namespace)
	var out strings.Builder
	for i := 0; i < len(rs); {
		if (i == 0 || !isBoundaryChar(rs[i-1])) && matchQuoted(rs, i, quoted) {
			out.WriteString("${namespace}.")
			i += len(quoted)
			continue
		}
		if foldSafe && (i == 0 || !isBoundaryChar(rs[i-1])) &&
			matchNamespaceCI(rs, i, namespace) {
			out.WriteString("${namespace}.")
			i += len([]rune(namespace)) + 1
			continue
		}
		out.WriteRune(rs[i])
		i++
	}
	return out.String()
}

// isBoundaryChar is the negated lookbehind set [A-Za-z0-9_$.].
func isBoundaryChar(c rune) bool {
	return c == '_' || c == '$' || c == '.' ||
		(c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
}

func matchQuoted(rs []rune, i int, quoted []rune) bool {
	if i+len(quoted) > len(rs) {
		return false
	}
	for j, c := range quoted {
		if rs[i+j] != c {
			return false
		}
	}
	return true
}

// matchNamespaceCI matches `<ns>.` at rs[i] with the ASCII
// case-insensitive character classes caseInsensitive() builds.
func matchNamespaceCI(rs []rune, i int, namespace string) bool {
	ns := []rune(namespace)
	if i+len(ns)+1 > len(rs) || rs[i+len(ns)] != '.' {
		return false
	}
	for j, c := range ns {
		a := rs[i+j]
		if a == c || (isASCIILetter(c) && a == asciiUpper(c)) {
			continue
		}
		return false
	}
	return true
}

func isASCIILetter(c rune) bool { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') }
func asciiUpper(c rune) rune {
	if c >= 'a' && c <= 'z' {
		return c - 'a' + 'A'
	}
	return c
}

// SplitOutsideLiterals splits on separator occurrences outside string
// literals, quoted identifiers, dollar-quoted bodies and comments —
// canonical key=value fields can carry quoted punctuation inside
// expression, label and identifier values.
func SplitOutsideLiterals(text string, separator byte) []string {
	rs := []rune(text)
	sep := rune(separator)
	var parts []string
	start := 0
	for i := 0; i < len(rs); i++ {
		if end, _, ok := nextSpan(rs, i); ok {
			i = end - 1
		} else if rs[i] == sep {
			parts = append(parts, string(rs[start:i]))
			start = i + 1
		}
	}
	return append(parts, string(rs[start:]))
}

// DropTerminator removes one trailing ';', trimmed — deparsed
// definitions carry one.
func DropTerminator(text string) string {
	t := strings.TrimRightFunc(text, isWhitespace)
	if strings.HasSuffix(t, ";") {
		return strings.TrimRightFunc(t[:len(t)-1], isWhitespace)
	}
	return t
}

// collapseWhitespace implements rule 5.3(2): whitespace runs outside
// string literals, quoted identifiers and dollar-quoted bodies collapse
// to one space; the result is trimmed.
func collapseWhitespace(text string) string {
	rs := []rune(text)
	var out strings.Builder
	i := 0
	pendingSpace := false
	for i < len(rs) {
		if end, _, ok := nextSpan(rs, i); ok {
			if pendingSpace {
				out.WriteByte(' ')
				pendingSpace = false
			}
			out.WriteString(string(rs[i:end]))
			i = end
		} else if isWhitespace(rs[i]) {
			pendingSpace = out.Len() > 0
			i++
		} else {
			if pendingSpace {
				out.WriteByte(' ')
				pendingSpace = false
			}
			out.WriteRune(rs[i])
			i++
		}
	}
	return out.String()
}

// StatementTokens is a top-level statement with its significant tokens
// (outside literals).
type StatementTokens struct {
	Text   string
	Tokens []string
	Offset int
}

// Statements splits a script into top-level statements (separated by ';')
// and extracts each statement's tokens outside string literals,
// dollar-quoted bodies and comments. Tokens are upper-cased
// keywords/punctuation plus quoted identifiers verbatim with their
// quotes — the quotes distinguish a case-sensitive `"Schema".t`
// reference from a keyword, which the checks of spec 3.2 need. A
// `BEGIN ATOMIC … END` routine body (SQL-standard routine syntax) is
// opaque like a literal: its ';'s separate body statements, not script
// statements, and its tokens are not part of the outer statement.
func Statements(script string) []StatementTokens {
	rs := []rune(script)
	var out []StatementTokens
	stmtStart := 0
	i := 0
	for i < len(rs) {
		if end, _, ok := nextSpan(rs, i); ok {
			i = end
		} else if rs[i] == ';' {
			out = addStatement(out, rs, stmtStart, i)
			i++
			stmtStart = i
		} else if isIdentStart(rs[i]) {
			end := wordEnd(rs, i)
			if word(rs, i, end) == "BEGIN" {
				if next := nextWord(rs, end); next != nil && word(rs, next[0], next[1]) == "ATOMIC" {
					i = atomicBodyEnd(rs, next[1])
					continue
				}
			}
			i = end
		} else {
			i++
		}
	}
	return addStatement(out, rs, stmtStart, len(rs))
}

func addStatement(out []StatementTokens, rs []rune, from, to int) []StatementTokens {
	var tokens []string
	i := from
	for i < to {
		if end, kind, ok := nextSpan(rs, i); ok {
			if kind == spanIdentQuoted {
				tokens = append(tokens, string(rs[i:end]))
			}
			i = end
			continue
		}
		c := rs[i]
		if isWhitespace(c) {
			i++
		} else if isIdentStart(c) {
			j := i + 1
			for j < to && isIdentPart(rs[j]) {
				j++
			}
			if word(rs, i, j) == "BEGIN" {
				if next := nextWord(rs, j); next != nil && next[1] <= to &&
					word(rs, next[0], next[1]) == "ATOMIC" {
					i = atomicBodyEnd(rs, next[1])
					continue
				}
			}
			tokens = append(tokens, word(rs, i, j))
			i = j
		} else if !isIdentPart(c) {
			tokens = append(tokens, string(c))
			i++
		} else {
			i++
		}
	}
	if len(tokens) > 0 {
		out = append(out, StatementTokens{Text: string(rs[from:to]), Tokens: tokens, Offset: from})
	}
	return out
}

// LiteralBodies returns the text inside each string or dollar-quoted
// literal of text (delimiters excluded, escapes not decoded) — the
// bodies a DO or EXECUTE statement hands to the inner check.
func LiteralBodies(text string) []string {
	rs := []rune(text)
	var out []string
	i := 0
	for i < len(rs) {
		end, kind, ok := nextSpan(rs, i)
		if !ok {
			i++
			continue
		}
		if kind == spanString || kind == spanDollar {
			out = append(out, literalBody(rs, i, end, kind))
		}
		i = end
	}
	return out
}

// literalBody is the content (delimiters excluded) of a string or
// dollar-quoted span.
func literalBody(rs []rune, from, to, kind int) string {
	if kind == spanString {
		// an unterminated span ends at the text length with no closing
		// quote — only strip a real closing delimiter, never content
		closed := to-1 > from && rs[to-1] == '\''
		inner := to
		if closed {
			inner = to - 1
		}
		return string(rs[from+1 : inner])
	}
	tag := dollarTag(rs, from)
	innerEnd := to
	if to-len(tag) >= from+len(tag) && string(rs[to-len(tag):to]) == tag {
		innerEnd = to - len(tag)
	}
	return string(rs[from+len(tag) : innerEnd])
}

const (
	spanString      = 1
	spanIdentQuoted = 2
	spanDollar      = 3
	spanComment     = 4
)

// Block-closing keywords that may trail an `END` (`END IF`, `END LOOP`, …).
var endTrailers = map[string]bool{
	"IF": true, "LOOP": true, "CASE": true, "WHILE": true,
	"FOR": true, "REPEAT": true,
}

// wordEnd is the end of the identifier starting at pos.
func wordEnd(rs []rune, pos int) int {
	i := pos + 1
	for i < len(rs) && isIdentPart(rs[i]) {
		i++
	}
	return i
}

// word is the upper-cased word between from and to.
func word(rs []rune, from, to int) string {
	return strings.ToUpper(string(rs[from:to]))
}

// skipFiller skips whitespace and comments.
func skipFiller(rs []rune, i int) int {
	for i < len(rs) {
		if isWhitespace(rs[i]) {
			i++
			continue
		}
		if end, kind, ok := nextSpan(rs, i); ok && kind == spanComment {
			i = end
			continue
		}
		break
	}
	return i
}

// nextWord returns [start, end] of the next identifier after from, or nil.
func nextWord(rs []rune, from int) []int {
	i := skipFiller(rs, from)
	if i < len(rs) && isIdentStart(rs[i]) {
		return []int{i, wordEnd(rs, i)}
	}
	return nil
}

// skipParen is the index right after the parenthesized group opening at open.
func skipParen(rs []rune, open int) int {
	depth := 0
	for i := open; i < len(rs); i++ {
		if end, _, ok := nextSpan(rs, i); ok {
			i = end - 1
			continue
		}
		if rs[i] == '(' {
			depth++
		} else if rs[i] == ')' {
			depth--
			if depth == 0 {
				return i + 1
			}
		}
	}
	return len(rs)
}

// forIsLoop reports whether the FOR at from opens a loop —
// `FOR var [, var] IN …` — rather than starting a row-lock clause
// (`FOR UPDATE`), a cursor query (`OPEN c FOR SELECT …`) or a cursor
// declaration. The operand is a word, comma or parenthesized list and
// the loop form ends it with IN.
func forIsLoop(rs []rune, from int) bool {
	i := from
	for {
		i = skipFiller(rs, i)
		if i >= len(rs) {
			return false
		}
		c := rs[i]
		if isIdentStart(c) {
			if word(rs, i, wordEnd(rs, i)) == "IN" {
				return true
			}
			i = wordEnd(rs, i)
		} else if c == '(' {
			i = skipParen(rs, i)
		} else if c == ',' {
			i++
		} else {
			return false
		}
	}
}

// atomicBodyEnd is the end of the `BEGIN ATOMIC` routine body whose
// ATOMIC keyword ended at from. The body runs to its matching `END`;
// nested compound blocks and CASE expressions open and close inside it,
// and FOR/WHILE headers merge with the LOOP that follows them.
func atomicBodyEnd(rs []rune, from int) int {
	blocks := []string{"BEGIN"}
	i := from
	for i < len(rs) {
		if end, _, ok := nextSpan(rs, i); ok {
			i = end
			continue
		}
		c := rs[i]
		if !isIdentStart(c) {
			i++
			continue
		}
		end := wordEnd(rs, i)
		switch w := word(rs, i, end); w {
		case "BEGIN", "CASE", "IF", "WHILE", "REPEAT":
			blocks = append(blocks, w)
		case "FOR":
			if forIsLoop(rs, end) {
				blocks = append(blocks, "FOR")
			}
		case "LOOP":
			top := blocks[len(blocks)-1]
			if top != "FOR" && top != "WHILE" {
				blocks = append(blocks, "LOOP")
			}
		case "END":
			blocks = blocks[:len(blocks)-1]
			trailer := nextWord(rs, end)
			if len(blocks) == 0 {
				if trailer == nil {
					return end
				}
				return trailer[1]
			}
			if trailer != nil && endTrailers[word(rs, trailer[0], trailer[1])] {
				i = trailer[1]
				continue
			}
		}
		i = end
	}
	return len(rs)
}

// nextSpan returns [end, kind] if a literal/comment span starts at pos.
func nextSpan(rs []rune, pos int) (end, kind int, ok bool) {
	c := rs[pos]
	n := len(rs)
	if c == '\'' {
		// E'...' strings also escape with a backslash: \' does not end them
		escapes := pos > 0 && (rs[pos-1] == 'E' || rs[pos-1] == 'e') &&
			(pos < 2 || !isIdentPart(rs[pos-2]))
		i := pos + 1
		for i < n {
			if escapes && rs[i] == '\\' {
				i += 2
				continue
			}
			if rs[i] == '\'' {
				if i+1 < n && rs[i+1] == '\'' {
					i += 2
					continue
				}
				return i + 1, spanString, true
			}
			i++
		}
		return n, spanString, true
	}
	if c == '"' {
		i := pos + 1
		for i < n {
			if rs[i] == '"' {
				if i+1 < n && rs[i+1] == '"' {
					i += 2
					continue
				}
				return i + 1, spanIdentQuoted, true
			}
			i++
		}
		return n, spanIdentQuoted, true
	}
	// a '$' after an identifier character is part of that identifier
	if c == '$' && (pos == 0 || !isIdentPart(rs[pos-1])) {
		if tag := dollarTag(rs, pos); tag != "" {
			idx := strings.Index(string(rs[pos+len([]rune(tag)):]), tag)
			if idx < 0 {
				return n, spanDollar, true
			}
			// idx is a byte offset into the rune-slice string — recompute in runes
			rest := rs[pos+len([]rune(tag)):]
			off := len([]rune(string(rest[:idx])))
			return pos + len([]rune(tag)) + off + len([]rune(tag)), spanDollar, true
		}
	}
	if c == '-' && pos+1 < n && rs[pos+1] == '-' {
		for i := pos; i < n; i++ {
			if rs[i] == '\n' {
				return i, spanComment, true
			}
		}
		return n, spanComment, true
	}
	if c == '/' && pos+1 < n && rs[pos+1] == '*' {
		depth := 1
		i := pos + 2
		for i < n && depth > 0 {
			if i+1 < n && rs[i] == '/' && rs[i+1] == '*' {
				depth++
				i += 2
			} else if i+1 < n && rs[i] == '*' && rs[i+1] == '/' {
				depth--
				i += 2
			} else {
				i++
			}
		}
		return i, spanComment, true
	}
	return 0, 0, false
}

// dollarTag is the $tag$ opener at pos, or "".
func dollarTag(rs []rune, pos int) string {
	n := len(rs)
	if pos+1 < n && rs[pos+1] == '$' {
		return "$$"
	}
	i := pos + 1
	for i < n && rs[i] != '$' {
		c := rs[i]
		if i == pos+1 {
			if !isIdentStart(c) {
				return ""
			}
		} else if !isIdentPart(c) {
			return ""
		}
		i++
	}
	if i < n {
		return string(rs[pos : i+1])
	}
	return ""
}

// isWhitespace mirrors java.lang.Character.isWhitespace: ASCII
// whitespace + U+001C–U+001F.
func isWhitespace(c rune) bool {
	return c == ' ' || (c >= '\t' && c <= '\r') || (c >= '\u001c' && c <= '\u001f')
}

// isIdentStart mirrors `Character.isLetter(c) || c == '_'` per UTF-16
// code unit — a non-BMP rune is never a letter.
func isIdentStart(c rune) bool {
	return c == '_' || (c <= 0xFFFF && unicode.IsLetter(c))
}

// isIdentPart mirrors `Character.isLetterOrDigit(c) || c == '_' || c == '$'`.
func isIdentPart(c rune) bool {
	if c > 0xFFFF {
		return false
	}
	return c == '_' || c == '$' || unicode.IsLetter(c) || unicode.IsDigit(c)
}
