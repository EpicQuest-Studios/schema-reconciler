package postgresql_test

// Shared live-database harness for the PostgreSQL integration tests —
// the Go analog of PgTestDatabase.ts. RECONCILER_TEST_URL points at a
// disposable PostgreSQL 14+ database; the tests default to the
// conventional local container (postgres:18 on :55999) and skip when
// nothing answers there.

import (
	"context"
	"crypto/rand"
	"database/sql"
	"fmt"
	"net/url"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	_ "github.com/jackc/pgx/v5/stdlib"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql"
)

const localURL = "postgres://rcl:rcl@localhost:55999/rcl?sslmode=disable"

// testURL resolves the test database URL from RECONCILER_TEST_URL (with
// RECONCILER_TEST_USER / RECONCILER_TEST_PASSWORD overrides).
func testURL() string {
	u := os.Getenv("RECONCILER_TEST_URL")
	if u == "" {
		u = localURL
	}
	user := os.Getenv("RECONCILER_TEST_USER")
	if user == "" {
		return u
	}
	parsed, err := url.Parse(u)
	if err != nil {
		return u
	}
	parsed.User = url.UserPassword(user, os.Getenv("RECONCILER_TEST_PASSWORD"))
	return parsed.String()
}

var (
	harnessOnce sync.Once
	mainPool    *sql.DB // the tracked data source
	scratchPool *sql.DB // namespace setup + scratch assertions
	dbUp        bool

	runID    string
	sequence atomic.Int64
)

// setup opens the two pools once per test binary. assertCleanSession
// inspects the session via mainPool.Conn — database/sql returns idle
// connections LIFO, and the suite runs sequentially, so the conn it
// gets is exactly the session the last reconcile ran on: the analog of
// the TS Pool whose client.close() is a no-op.
func setup() {
	harnessOnce.Do(func() {
		dsn := testURL()
		var err error
		if mainPool, err = sql.Open("pgx", dsn); err != nil {
			return
		}
		if scratchPool, err = sql.Open("pgx", dsn); err != nil {
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
		defer cancel()
		dbUp = mainPool.PingContext(ctx) == nil
		var b [8]byte
		if _, err := rand.Read(b[:]); err == nil {
			runID = fmt.Sprintf("%x", b[:])
		} else {
			runID = fmt.Sprintf("%x", time.Now().UnixNano())
		}
	})
}

// requireDB opens the pools and skips when the database does not
// answer.
func requireDB(t *testing.T) {
	t.Helper()
	setup()
	if !dbUp {
		t.Skip("no PostgreSQL test database")
	}
}

// dataSource is the tracked data source the dialect under test sees.
func dataSource() spi.DB {
	return spi.NewDB(mainPool, postgresql.MetadataProbe)
}

// scratch is a second data source on the same engine.
func scratch() spi.DB {
	return spi.NewDB(scratchPool, postgresql.MetadataProbe)
}

// execute runs sql on a throwaway scratch-pool connection.
func execute(ctx context.Context, sqlText string) error {
	c, err := scratchPool.Conn(ctx)
	if err != nil {
		return err
	}
	defer c.Close()
	_, err = c.ExecContext(ctx, sqlText)
	return err
}

// serverMajor returns the server's major version (for version-gated
// fixtures).
func serverMajor(ctx context.Context) (int, error) {
	c, err := scratchPool.Conn(ctx)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	var v string
	if err := c.QueryRowContext(ctx, "SHOW server_version").Scan(&v); err != nil {
		return 0, err
	}
	var major int
	if _, err := fmt.Sscanf(v, "%d", &major); err != nil {
		return 0, fmt.Errorf("server_version %q: %w", v, err)
	}
	return major, nil
}

// ensureUuidv7 installs a pg_catalog.uuidv7() stand-in on pre-18
// servers so fixtures written against the v18 built-in still exercise
// the whole pipeline.
func ensureUuidv7(ctx context.Context) error {
	major, err := serverMajor(ctx)
	if err != nil {
		return err
	}
	if major < 18 {
		return execute(ctx, "CREATE OR REPLACE FUNCTION pg_catalog.uuidv7()"+
			" RETURNS uuid LANGUAGE sql"+
			" AS $$ SELECT gen_random_uuid() $$")
	}
	return nil
}

// nextNamespace mints a fresh tck_<run>_<seq> namespace name.
func nextNamespace() string {
	return fmt.Sprintf("tck_%s_%d", runID, sequence.Add(1))
}
