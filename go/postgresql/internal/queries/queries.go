// Package queries loads the shared PostgreSQL catalog queries from
// sql/postgresql/*.sql (spec A.5 — the same files the Java dialect reads
// as resources and TypeScript loads at runtime). Each file holds named
// sections:
//
//	-- name: <query_name>
//	SELECT ... WHERE n.nspname = ?
//
// '?' marks a positional parameter; Get rewrites every '?' that lies
// outside single-quoted string literals, '--' line comments and
// dollar-quoted bodies to $1, $2, ... in textual order — the
// database/sql bind syntax of a PostgreSQL driver.
package queries

import (
	_ "embed"
	"fmt"
	"strings"
	"sync"
)

//go:generate sh ../../../scripts/sync-sql.sh

//go:embed sql/catalog.sql
var catalogSQL string

//go:embed sql/annotations.sql
var annotationsSQL string

//go:embed sql/dependencies.sql
var dependenciesSQL string

var files = map[string]string{
	"catalog.sql":      catalogSQL,
	"annotations.sql":  annotationsSQL,
	"dependencies.sql": dependenciesSQL,
}

var (
	once  sync.Once
	cache map[string]string
	err   error
)

// Get returns the named catalog query with $n bind markers, or an error.
func Get(name string) (string, error) {
	once.Do(func() {
		cache, err = load()
	})
	if err != nil {
		return "", err
	}
	sql, ok := cache[name]
	if !ok {
		return "", fmt.Errorf("postgresql: unknown catalog query '%s'", name)
	}
	return sql, nil
}

func load() (map[string]string, error) {
	all := make(map[string]string)
	for _, file := range []string{"catalog.sql", "annotations.sql", "dependencies.sql"} {
		sections, err := parseFile(files[file], file)
		if err != nil {
			return nil, err
		}
		for name, sql := range sections {
			if _, dup := all[name]; dup {
				return nil, fmt.Errorf("postgresql: duplicate query '%s'", name)
			}
			all[name] = bind(sql)
		}
	}
	return all, nil
}

// parseFile splits one .sql file on its `-- name: <ident>` markers. Lines
// before the first marker must be blank or comments.
func parseFile(text, file string) (map[string]string, error) {
	sections := make(map[string]string)
	var name string
	var body []string
	lineNo := 0
	flush := func() error {
		if name != "" {
			sql := strings.TrimSpace(strings.Join(body, "\n"))
			if sql == "" {
				return fmt.Errorf("%s: query '%s' has no body", file, name)
			}
			sections[name] = sql
		}
		return nil
	}
	for _, line := range strings.Split(text, "\n") {
		lineNo++
		trimmed := strings.TrimSpace(line)
		if marker, ok := strings.CutPrefix(trimmed, "-- name:"); ok {
			if err := flush(); err != nil {
				return nil, err
			}
			name = strings.TrimSpace(marker)
			if !validIdent(name) {
				return nil, fmt.Errorf("%s:%d: invalid query name %q", file, lineNo, name)
			}
			if _, dup := sections[name]; dup {
				return nil, fmt.Errorf("%s: duplicate query '%s'", file, name)
			}
			body = nil
		} else if name == "" {
			if trimmed != "" && !strings.HasPrefix(trimmed, "--") {
				return nil, fmt.Errorf("%s:%d: SQL before the first '-- name:' marker", file, lineNo)
			}
		} else {
			body = append(body, line)
		}
	}
	if err := flush(); err != nil {
		return nil, err
	}
	return sections, nil
}

func validIdent(s string) bool {
	for i, c := range s {
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' ||
			(i > 0 && c >= '0' && c <= '9')) {
			return false
		}
	}
	return len(s) > 0
}

// bind rewrites '?' markers to $1, $2, ... in textual order. A '?' inside
// a single-quoted literal ('..' with ” escape), a '--' line comment or a
// dollar-quoted body ($tag$..$tag$) is not a parameter.
func bind(sql string) string {
	var out strings.Builder
	n := 0
	i := 0
	for i < len(sql) {
		c := sql[i]
		switch {
		case c == '\'':
			end := literalEnd(sql, i)
			out.WriteString(sql[i:end])
			i = end
		case c == '-' && i+1 < len(sql) && sql[i+1] == '-':
			nl := strings.IndexByte(sql[i:], '\n')
			end := len(sql)
			if nl >= 0 {
				end = i + nl
			}
			out.WriteString(sql[i:end])
			i = end
		case c == '$':
			tag := dollarTag(sql[i:])
			if tag == "" {
				out.WriteByte(c)
				i++
			} else {
				close_ := strings.Index(sql[i+len(tag):], tag)
				end := len(sql)
				if close_ >= 0 {
					end = i + len(tag) + close_ + len(tag)
				}
				out.WriteString(sql[i:end])
				i = end
			}
		case c == '?':
			n++
			fmt.Fprintf(&out, "$%d", n)
			i++
		default:
			out.WriteByte(c)
			i++
		}
	}
	return out.String()
}

// dollarTag returns the $tag$ opener starting at sql[0], or "".
func dollarTag(s string) string {
	if len(s) < 2 || s[0] != '$' {
		return ""
	}
	for i := 1; i < len(s); i++ {
		c := s[i]
		if c == '$' {
			return s[:i+1]
		}
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
			return ""
		}
	}
	return ""
}

// literalEnd is the index just past the single-quoted literal starting at
// from (” escapes).
func literalEnd(sql string, from int) int {
	i := from + 1
	for i < len(sql) {
		if sql[i] == '\'' {
			if i+1 < len(sql) && sql[i+1] == '\'' {
				i += 2
			} else {
				return i + 1
			}
		} else {
			i++
		}
	}
	return len(sql)
}
