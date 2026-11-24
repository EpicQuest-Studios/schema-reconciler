package postgresql_test

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql"
	"github.com/EpicQuest-Studios/schema-reconciler/go/tck"
)

// The dialect-conformance suite of spec 9.2 wired to PostgreSQL — the
// Go analog of dialectConformanceSuite(pgHooks).

func TestDialectConformance(t *testing.T) {
	requireDB(t)

	d := postgresql.Dialect{}
	quote := func(id string) string {
		q, err := d.QuoteIdentifier(id)
		if err != nil {
			t.Fatalf("quote %q: %v", id, err)
		}
		return q
	}

	tck.RunConformanceSuite(t, tck.Hooks{
		DataSource:        dataSource,
		ScratchDataSource: scratch,
		Dialect:           func() spi.Dialect { return d },

		CreateNamespace: func(ctx context.Context) (string, error) {
			name := nextNamespace()
			if err := execute(ctx, "CREATE SCHEMA "+quote(name)); err != nil {
				return "", err
			}
			return name, nil
		},
		DropNamespace: func(ctx context.Context, ns string) error {
			return execute(ctx, "DROP SCHEMA "+quote(ns)+" CASCADE")
		},

		AssertNoScratchNamespacesLeft: func(ctx context.Context) error {
			c, err := scratchPool.Conn(ctx)
			if err != nil {
				return err
			}
			defer c.Close()
			var n int
			if err := c.QueryRowContext(ctx,
				"SELECT count(*) FROM pg_namespace"+
					" WHERE nspname LIKE '%\\_\\_rcl\\_%' ESCAPE '\\'").
				Scan(&n); err != nil {
				return err
			}
			if n != 0 {
				return fmt.Errorf("scratch namespaces leaked: %d", n)
			}
			return nil
		},

		// mainPool returns idle connections LIFO, so this Conn is the
		// session the reconcile just ran on.
		AssertCleanSession: func(ctx context.Context) error {
			c, err := mainPool.Conn(ctx)
			if err != nil {
				return err
			}
			defer c.Close()
			var n int
			if err := c.QueryRowContext(ctx,
				"SELECT count(*) FROM pg_locks"+
					" WHERE locktype = 'advisory'"+
					" AND pid = pg_backend_pid()").Scan(&n); err != nil {
				return err
			}
			if n != 0 {
				return fmt.Errorf("advisory lock leaked: %d", n)
			}
			for _, setting := range []string{"lock_timeout", "statement_timeout"} {
				var v string
				if err := c.QueryRowContext(ctx, "SHOW "+setting).Scan(&v); err != nil {
					return err
				}
				if v != "0" {
					return fmt.Errorf("%s left set: %s", setting, v)
				}
			}
			var searchPath string
			if err := c.QueryRowContext(ctx, "SHOW search_path").Scan(&searchPath); err != nil {
				return err
			}
			if strings.Contains(searchPath, "rcl") {
				return fmt.Errorf("search_path left bound to a scratch"+
					" namespace: %s", searchPath)
			}
			return nil
		},

		IsScratchBoundary: func(sql string) bool {
			up := strings.ToUpper(sql)
			return strings.Contains(up, "SAVEPOINT RCL_SCRATCH") ||
				strings.Contains(up, "__RCL_")
		},
		IsScratchEnd: func(sql string) bool {
			return strings.Contains(strings.ToUpper(sql),
				"ROLLBACK TO SAVEPOINT RCL_SCRATCH")
		},

		UnsupportedScript: func() *tck.UnsupportedScript {
			return &tck.UnsupportedScript{
				Script: "CREATE TABLE unsupported_base (a int);" +
					" CREATE TABLE unsupported_i (b int)" +
					" INHERITS (unsupported_base);" +
					" CREATE VIEW dep_v AS SELECT * FROM unsupported_i",
				DependentName: "dep_v",
			}
		},
		UnsupportedCategory:  func() string { return "inherited" },
		ColumnAlterSupported: func() bool { return true },
		IdentityColumnDdl:    func() string { return "bigint GENERATED ALWAYS AS IDENTITY" },
		SerialColumnDdl:      func() string { return "bigserial" },

		ExtensionDdl: func(ctx context.Context) (string, bool, error) {
			c, err := scratchPool.Conn(ctx)
			if err != nil {
				return "", false, err
			}
			defer c.Close()
			var name string
			err = c.QueryRowContext(ctx,
				"SELECT a.name FROM pg_available_extensions a"+
					" WHERE a.name IN ('btree_gist', 'citext', 'hstore',"+
					" 'pg_trgm', 'unaccent', 'uuid-ossp')"+
					" AND NOT EXISTS (SELECT 1 FROM pg_extension e"+
					" WHERE e.extname = a.name) ORDER BY a.name LIMIT 1").
				Scan(&name)
			if err != nil {
				return "", false, nil // nothing available — skip
			}
			return "CREATE EXTENSION IF NOT EXISTS " + quote(name) +
				" WITH SCHEMA ${namespace}", true, nil
		},

		ForeignServerDdl: func(ctx context.Context) (string, bool, error) {
			c, err := scratchPool.Conn(ctx)
			if err != nil {
				return "", false, err
			}
			defer c.Close()
			var super bool
			if err := c.QueryRowContext(ctx,
				"SELECT rolsuper FROM pg_roles"+
					" WHERE rolname = current_user").Scan(&super); err != nil || !super {
				return "", false, err
			}
			if _, err := c.ExecContext(ctx,
				"CREATE EXTENSION IF NOT EXISTS postgres_fdw"); err != nil {
				return "", false, err
			}
			if _, err := c.ExecContext(ctx,
				"DROP SERVER IF EXISTS tck_srv CASCADE"); err != nil {
				return "", false, err
			}
			return "CREATE SERVER tck_srv FOREIGN DATA WRAPPER postgres_fdw",
				true, nil
		},
		ForeignServerTeardownDdl: func(ctx context.Context) (string, bool, error) {
			return "DROP SERVER IF EXISTS tck_srv CASCADE", true, nil
		},

		PublicationDdl: func(ctx context.Context) (string, bool, error) {
			c, err := scratchPool.Conn(ctx)
			if err != nil {
				return "", false, err
			}
			defer c.Close()
			var ok bool
			if err := c.QueryRowContext(ctx,
				"SELECT has_database_privilege(current_user,"+
					" current_database(), 'CREATE')").Scan(&ok); err != nil || !ok {
				return "", false, err
			}
			if _, err := c.ExecContext(ctx,
				"DROP PUBLICATION IF EXISTS tck_pub"); err != nil {
				return "", false, err
			}
			return "CREATE PUBLICATION tck_pub", true, nil
		},
		PublicationTeardownDdl: func(ctx context.Context) (string, bool, error) {
			return "DROP PUBLICATION IF EXISTS tck_pub", true, nil
		},

		OwnershipRoleDdl: func(ctx context.Context) (string, bool, error) {
			c, err := scratchPool.Conn(ctx)
			if err != nil {
				return "", false, err
			}
			defer c.Close()
			var ok bool
			if err := c.QueryRowContext(ctx,
				"SELECT rolsuper OR rolcreaterole FROM pg_roles"+
					" WHERE rolname = current_user").Scan(&ok); err != nil || !ok {
				return "", false, err
			}
			var user string
			if err := c.QueryRowContext(ctx,
				"SELECT current_user").Scan(&user); err != nil {
				return "", false, err
			}
			for _, sql := range []string{
				"DROP ROLE IF EXISTS tck_owner",
				"DROP ROLE IF EXISTS tck_owner2",
				"CREATE ROLE tck_owner",
				"CREATE ROLE tck_owner2",
				"GRANT tck_owner, tck_owner2 TO " + quote(user),
			} {
				if _, err := c.ExecContext(ctx, sql); err != nil {
					return "", false, err
				}
			}
			return "SELECT 1", true, nil
		},
		// per role — a multi-role DROP OWNED BY trips on a default ACL
		// of one listed role granting to another
		OwnershipRoleTeardownDdl: func(ctx context.Context) (string, bool, error) {
			return "DROP OWNED BY tck_owner; DROP OWNED BY tck_owner2;" +
				" DROP ROLE tck_owner; DROP ROLE tck_owner2", true, nil
		},
	})
}
