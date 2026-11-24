package reconciler

import (
	"io"
	"os"
)

// DesiredState is the desired state declared as a SQL script (spec 3.2). The
// script text is read once, as UTF-8. Namespace references inside the script
// are written as the placeholder ${namespace} — model.NamespaceToken.
type DesiredState struct {
	script string
}

// Script returns the desired-state script text.
func (d DesiredState) Script() string { return d.script }

// DesiredStateOfScript wraps an in-memory script.
func DesiredStateOfScript(sql string) DesiredState {
	return DesiredState{script: sql}
}

// DesiredStateOfReader reads a script fully from r — for embedded files,
// buffers and other non-path sources.
func DesiredStateOfReader(r io.Reader) (DesiredState, error) {
	data, err := io.ReadAll(r)
	if err != nil {
		return DesiredState{}, NewDesiredStateError("could not read script").WithCause(err)
	}
	return DesiredState{script: string(data)}, nil
}

// DesiredStateOfPath reads a script file as UTF-8 — the analog of
// DesiredState.ofPath.
func DesiredStateOfPath(path string) (DesiredState, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return DesiredState{}, NewDesiredStateError("could not read script: " + path).WithCause(err)
	}
	return DesiredState{script: string(data)}, nil
}
