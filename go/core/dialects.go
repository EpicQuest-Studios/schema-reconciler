package reconciler

import (
	"sync"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// Dialect discovery (spec 3.4). Java uses ServiceLoader; Go modules have no
// service-loading facility, so — like the TypeScript binding — dialects are
// registered explicitly: import the dialect package for its side-effect
// register (or call RegisterDialect), or pass one directly to
// Builder.Dialect. When no dialect is set, the engine selects the single
// registered candidate whose Supports accepts the connection's metadata on
// first use; zero or several matches raise ConfigurationError.
var registry struct {
	mu       sync.RWMutex
	dialects []spi.Dialect
}

// RegisterDialect adds a dialect to the discovery registry — the Go analog
// of META-INF/services registration.
func RegisterDialect(d spi.Dialect) {
	if d == nil {
		panic("reconciler: RegisterDialect of nil dialect")
	}
	registry.mu.Lock()
	defer registry.mu.Unlock()
	registry.dialects = append(registry.dialects, d)
}

// UnregisterDialect removes a previously registered dialect; it reports
// whether one was found.
func UnregisterDialect(d spi.Dialect) bool {
	registry.mu.Lock()
	defer registry.mu.Unlock()
	for i, candidate := range registry.dialects {
		if candidate == d {
			registry.dialects = append(registry.dialects[:i], registry.dialects[i+1:]...)
			return true
		}
	}
	return false
}

// RegisteredDialects returns a snapshot of the discovery registry in
// registration order.
func RegisteredDialects() []spi.Dialect {
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	out := make([]spi.Dialect, len(registry.dialects))
	copy(out, registry.dialects)
	return out
}
