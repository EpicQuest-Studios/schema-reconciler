package postgresql

import (
	"context"
	"database/sql"
	"errors"

	core "github.com/EpicQuest-Studios/schema-reconciler/go/core"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/model"
	"github.com/EpicQuest-Studios/schema-reconciler/go/core/spi"
	"github.com/EpicQuest-Studios/schema-reconciler/go/postgresql/internal/pg"
)

const defaultHookTable = "schema_reconciler_hooks"

// HookStore is PostgreSQL's durable core.HookStore: one versioned history
// table keyed by (namespace, hook_id), created lazily and idempotently.
type HookStore struct {
	schema string
	table  string
}

// NewHookStore builds a PostgreSQL hook store. An empty table selects the
// default schema_reconciler_hooks table; schema must not be empty.
func NewHookStore(schema, table string) (*HookStore, error) {
	if schema == "" {
		return nil, core.NewConfigurationError("schema must not be empty")
	}
	if table == "" {
		table = defaultHookTable
	}
	return &HookStore{schema: schema, table: table}, nil
}

// RecordedChecksum returns the recorded checksum and whether it exists.
func (s *HookStore) RecordedChecksum(ctx context.Context, exec spi.Executor,
	namespace, hookID string) (string, bool, error) {
	if err := s.ensureTable(ctx, exec); err != nil {
		return "", false, err
	}
	var checksum string
	err := exec.QueryRowContext(ctx,
		"SELECT checksum FROM "+s.qualified()+" WHERE namespace = $1 AND hook_id = $2",
		namespace, hookID).Scan(&checksum)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	return checksum, err == nil, err
}

// Record stores a successful hook execution.
func (s *HookStore) Record(ctx context.Context, exec spi.Executor,
	namespace, hookID string, phase core.HookPhase, checksum string) error {
	if err := s.ensureTable(ctx, exec); err != nil {
		return err
	}
	_, err := exec.ExecContext(ctx,
		"INSERT INTO "+s.qualified()+" (namespace, hook_id, phase, checksum) VALUES ($1, $2, $3, $4)"+
			" ON CONFLICT (namespace, hook_id) DO NOTHING",
		namespace, hookID, phase.String(), checksum)
	return err
}

// ManagedTable auto-excludes the history table when it lives in the target schema.
func (s *HookStore) ManagedTable(targetNamespace string) *model.ObjectId {
	if s.schema != targetNamespace {
		return nil
	}
	return &model.ObjectId{Kind: model.KindTable, Name: s.table}
}

func (s *HookStore) ensureTable(ctx context.Context, exec spi.Executor) error {
	_, err := exec.ExecContext(ctx,
		"CREATE TABLE IF NOT EXISTS "+s.qualified()+" ("+
			"namespace text NOT NULL,"+
			"hook_id text NOT NULL,"+
			"phase text NOT NULL,"+
			"checksum text NOT NULL,"+
			"applied_at timestamptz NOT NULL DEFAULT now(),"+
			"PRIMARY KEY (namespace, hook_id))")
	return err
}

func (s *HookStore) qualified() string {
	qs, _ := pg.Quote(s.schema)
	qt, _ := pg.Quote(s.table)
	return qs + "." + qt
}
