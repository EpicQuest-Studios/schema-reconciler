package spi

// RenderOptions carries the optional render-time flags passed to
// Dialect.Render (spec 6.1). The zero value is the default — the analog of
// RenderOptions.DEFAULT.
type RenderOptions struct {
	// ConcurrentIndexes renders index creates and drops in their
	// CONCURRENTLY forms where the dialect supports them (PostgreSQL:
	// CREATE INDEX CONCURRENTLY — non-transactional, so the engine runs
	// those changes after the transactional phase commits).
	ConcurrentIndexes bool

	// RenamedParents maps parent names rewritten by an active rename hint,
	// old name to new — set on UNEXPECTED renders only. A pruned child of a
	// renamed parent (a COLUMN, TRIGGER and the like) must drop through the
	// new parent name: the old one no longer exists once the rename ran.
	RenamedParents map[string]string
}
