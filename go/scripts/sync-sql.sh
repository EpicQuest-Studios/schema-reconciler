#!/bin/sh
# sync-sql.sh — copies the shared PostgreSQL catalog queries
# (sql/postgresql/*.sql, the same files the Java dialect reads as resources
# and TypeScript loads at runtime) into go/postgresql/internal/queries/sql/
# for //go:embed — embed cannot reference paths outside its own module
# directory. Run after editing the shared files (//go:generate in postgresql.go).
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
src="$here/../sql/postgresql"
dst="$here/postgresql/internal/queries/sql"
mkdir -p "$dst"
for f in catalog.sql annotations.sql dependencies.sql; do
    cp "$src/$f" "$dst/$f"
done
echo "synced $src -> $dst"
