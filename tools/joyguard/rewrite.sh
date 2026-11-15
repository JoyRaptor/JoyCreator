#!/usr/bin/env bash
# The history rewrite itself, on a BARE copy (never on a work folder):
#   bash tools/joyguard/rewrite.sh <bare-repo> [private-list]
# Every guarded word (the private list's regexes) becomes "[removed]" in every file of every
# commit and in every commit message; the retired .githooks/_patterns file is removed from all
# history. Prints no guarded word. The replacement rules live in a private temp file that is
# deleted at the end. filter-repo is deterministic: the same commit always gets the same new id,
# so a later run on another copy maps shared commits identically.
set -u
repo="${1:?bare repo}"
priv="${2:-$(git config --get joyguard.patternsFile || echo "$HOME/.joycreator-private/guard-patterns.txt")}"
[ -f "$priv" ] || { echo "rewrite: no private list"; exit 2; }
[ "$(git -C "$repo" rev-parse --is-bare-repository 2>/dev/null)" = true ] || { echo "rewrite: $repo is not a bare repository"; exit 2; }
rules=$(mktemp "${TMPDIR:-/tmp}/joyguard-rules-XXXXXX") || exit 2
trap 'rm -f "$rules"' EXIT
grep -v '^[[:space:]]*#' "$priv" | grep . | tr -d '\r' | sed 's/^/regex:(?i)/; s/$/==>[removed]/' > "$rules"
[ -s "$rules" ] || { echo "rewrite: empty rules"; exit 2; }
cd "$repo" && git filter-repo --force --replace-text "$rules" --replace-message "$rules" \
    --invert-paths --path .githooks/_patterns
