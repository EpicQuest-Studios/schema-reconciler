package reconciler

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"sync"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
)

// coreMajorVersion is the core major version this implementation is
// (spec 10.3) — the number a dialect's CoreMajorVersion must equal.
const coreMajorVersion = 1

// dialectResolver is dialect discovery and deferred selection (spec 3.4,
// 10.3). Discovery runs at Build() time for presence only; the Supports
// selection runs on first use, when a connection exists.
type dialectResolver struct {
	mu         sync.Mutex
	resolved   spi.Dialect
	explicit   spi.Dialect
	candidates []spi.Dialect
}

func newDialectResolver(explicit spi.Dialect, candidates []spi.Dialect) *dialectResolver {
	if candidates == nil {
		candidates = RegisteredDialects()
	}
	return &dialectResolver{explicit: explicit, candidates: candidates}
}

// quoteOf validates that identifier is legal for the dialect — the engine
// runs it on the configured namespace before any work (spec 8.3).
func quoteOf(d spi.Dialect, identifier string) error {
	if _, err := d.QuoteIdentifier(identifier); err != nil {
		return NewConfigurationErrorf("namespace '%s' is not a legal identifier for dialect '%s'",
			identifier, d.ID()).WithCause(err)
	}
	return nil
}

// peek returns the dialect when already known — explicit or previously
// resolved.
func (r *dialectResolver) peek() spi.Dialect {
	if r.explicit != nil {
		return r.explicit
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.resolved
}

// resolve returns the selected dialect, running the supports check once
// against the first connection.
func (r *dialectResolver) resolve(ctx context.Context, conn spi.Conn) (spi.Dialect, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.resolved != nil {
		return r.resolved, nil
	}
	selected, err := r.selectDialect(ctx, conn)
	if err != nil {
		return nil, err
	}
	r.resolved = selected
	return selected, nil
}

func (r *dialectResolver) selectDialect(ctx context.Context, conn spi.Conn) (spi.Dialect, error) {
	meta, err := conn.Metadata(ctx)
	if err != nil {
		return nil, NewConfigurationError("could not read database metadata").WithCause(err)
	}
	var selected spi.Dialect
	if r.explicit != nil {
		if !r.explicit.Supports(meta) {
			return nil, NewConfigurationErrorf(
				"explicitly configured dialect '%s' does not support this database: %s",
				r.explicit.ID(), productName(meta))
		}
		selected = r.explicit
	} else {
		var matching, discovered []string
		var match spi.Dialect
		for _, candidate := range r.candidates {
			discovered = append(discovered, candidate.ID())
			if candidate.Supports(meta) {
				matching = append(matching, candidate.ID())
				match = candidate
			}
		}
		if len(matching) == 0 {
			list := "none"
			if len(discovered) > 0 {
				list = strings.Join(discovered, ", ")
			}
			return nil, NewConfigurationErrorf(
				"no dialect supports this database: %s (discovered dialects: %s)",
				productName(meta), list)
		}
		if len(matching) > 1 {
			slices.Sort(matching)
			return nil, NewConfigurationErrorf(
				"several dialects support %s: %s", productName(meta), strings.Join(matching, ", "))
		}
		selected = match
	}
	if version := spi.CoreMajorVersion(selected); version != coreMajorVersion {
		return nil, NewConfigurationErrorf(
			"dialect '%s' was built against core major version %d; this core is %d",
			selected.ID(), version, coreMajorVersion)
	}
	return selected, nil
}

func productName(meta spi.DatabaseMetadata) string {
	return fmt.Sprintf("%s %d", meta.DatabaseProductName, meta.DatabaseMajorVersion)
}
