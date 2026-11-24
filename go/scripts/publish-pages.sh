#!/usr/bin/env bash
# publish-pages.sh — emit a static GOPROXY module version into a
# checkout of the `gh-pages` branch, alongside maven/ and npm/.
#
#   scripts/publish-pages.sh --pages <gh-pages-checkout> --version v0.10.0
#
# Writes <pages>/go/<escaped-module>/@v/{list,<v>.info,<v>.mod,<v>.zip,
# @latest} per the GOPROXY protocol, so consumers can
#
#   GOPROXY=https://epicquest-studios.github.io/schema-reconciler/go \
#       go get github.com/EpicQuest-Studios/schema-reconciler/go@v0.10.0
#
# The zip packs every file of this module at its
# `<module>@<version>/` prefix — go.mod, all sources, tests, the synced
# sql/ queries and README — exactly the tree `go get` needs.
set -euo pipefail

MODULE="github.com/EpicQuest-Studios/schema-reconciler/go"
SELF="$(cd "$(dirname "$0")/.." && pwd)"   # the go/ module root

pages="" ; version=""
while [ $# -gt 0 ]; do
    case "$1" in
        --pages)   pages="$2";   shift 2 ;;
        --version) version="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
[ -n "$pages" ]   || { echo "--pages <gh-pages-checkout> required" >&2; exit 2; }
[ -n "$version" ] || { echo "--version <vX.Y.Z> required" >&2; exit 2; }
[ -d "$pages" ]   || { echo "pages checkout not found: $pages" >&2; exit 1; }
case "$version" in
    v[0-9]*.[0-9]*.[0-9]*) ;;
    *) echo "version must look like v0.10.0, got: $version" >&2; exit 2 ;;
esac

# GOPROXY path escaping: uppercase letters become !lowercase (bash3-safe).
escape() {
    local s="$1" out="" c i
    for (( i = 0; i < ${#s}; i++ )); do
        c="${s:i:1}"
        case "$c" in
            [A-Z]) out+="!$(printf '%s' "$c" | tr 'A-Z' 'a-z')" ;;
            *)     out+="$c" ;;
        esac
    done
    printf '%s\n' "$out"
}
ESCAPED="$(escape "$MODULE")"
VDIR="$pages/go/$ESCAPED/@v"
mkdir -p "$VDIR"

# <version>.info — RFC3339 timestamp (UTC) of the tag/commit time.
timestamp="$(git -C "$SELF" log -1 --format=%cI 2>/dev/null \
    || date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '{"Version":"%s","Time":"%s"}\n' "$version" "$timestamp" \
    > "$VDIR/$version.info"

# <version>.mod — the module file as of this version.
cp "$SELF/go.mod" "$VDIR/$version.mod"

# <version>.zip — the whole module tree at <module>@<version>/ paths.
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
prefix="$stage/$MODULE@$version"
mkdir -p "$prefix"
(cd "$SELF" && find . -type f \
    ! -path './.git/*' ! -name '.DS_Store' \
    -exec sh -c 'mkdir -p "'"$prefix"'/$(dirname "$1")"; cp "$1" "'"$prefix"'/$1"' _ {} \;)
rm -f "$VDIR/$version.zip"
if command -v zip >/dev/null 2>&1; then
    (cd "$stage" && zip -q -r -X "$VDIR/$version.zip" "$MODULE@$version")
else
    # portable fallback: python3's zipfile
    STAGE="$stage" OUT="$VDIR/$version.zip" python3 - <<'PY'
import os, zipfile
stage, out = os.environ["STAGE"], os.environ["OUT"]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(stage):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, stage))
PY
fi

# @v/list — one version per line (order is informational; `go` picks
# the max itself, keep it sorted for humans).
if [ -f "$VDIR/list" ]; then
    grep -qx "$version" "$VDIR/list" || echo "$version" >> "$VDIR/list"
else
    echo "$version" > "$VDIR/list"
fi
sort -u -o "$VDIR/list" "$VDIR/list"

# @latest — the highest version (sort -V, then strip v-prefix compare).
latest="$(sort -V "$VDIR/list" | tail -1)"
printf '%s\n' "$latest" > "$VDIR/@latest"

# .nojekyll so GitHub Pages serves the files raw.
touch "$pages/.nojekyll"

echo "published $MODULE $version -> $VDIR"
ls -la "$VDIR" | grep -v '^total' >&2
