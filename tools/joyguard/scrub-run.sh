#!/usr/bin/env bash
# The privacy scrub of the public repository, run only at a safe moment.
#
#   bash tools/joyguard/scrub-run.sh --check     is now a safe moment? (changes nothing)
#   bash tools/joyguard/scrub-run.sh --go        check, then do it all, stopping at the first surprise
#
# Steps: safety check -> private backups (verified) -> rewrite of a copy of GitHub plus every local
# branch -> verification counts -> GitHub unchanged since the backup? -> old-history push refusal
# switched on -> force-push of branches and tags -> local re-sync -> all-clear line on BOARD.md.
# Nothing public changes before the force-push, and the force-push happens only after every
# verification passed. A refused force-push stops the run: it is never retried another way.
# Never prints a guarded word. Writes a plain report to $WORK/RUN-REPORT-<time>.md.
set -u
LIVE="${JOYSCRUB_LIVE:-C:/+Projects/JoyCreator}"
# Work copies live on C: (NTFS). git refuses a repository on D: whenever one git process has to
# read another's (fetch, clone), because D: records no file owner. Backups stay on D:.
WORK="${JOYSCRUB_WORK:-$HOME/.joycreator-private/scrub-work}"
PRIVDIR="${JOYSCRUB_PRIVATE_BACKUPS:-D:/JoyCreator-private}"
PRIV="${JOYSCRUB_PATTERNS:-$(git -C "$LIVE" config --get joyguard.patternsFile || echo "$HOME/.joycreator-private/guard-patterns.txt")}"
TJ="$LIVE/tools/joyguard"
URL="${JOYSCRUB_URL:-$(git -C "$LIVE" remote get-url origin)}"
LOCKDIR="${JOYSCRUB_LOCKDIR:-$HOME/AppData/Local/Temp}"
QUIET_MIN="${JOYSCRUB_QUIET_MINUTES:-20}"
mode="${1:-}"
case "$mode" in --check|--go) ;; *) echo "usage: scrub-run.sh --check | --go"; exit 2 ;; esac
TS=$(date +%Y%m%d-%H%M%S)
REPORT="$WORK/RUN-REPORT-$TS.md"
RW="$WORK/rewrite-$TS.git"
BK_GH="$PRIVDIR/github-before-scrub-$TS.git"
BK_LOCAL="$PRIVDIR/local-before-scrub-$TS.git"
# the D: drive records no owner, so git calls the copies "dubious"; allow them for this run only
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0='*'
mkdir -p "$WORK" "$PRIVDIR"
public_changed=0
note() { echo "$*"; echo "- $*" >> "$REPORT"; }
stop() {
  note "STOPPED: $*"
  git -C "$LIVE" for-each-ref --format='%(refname)' refs/joyguard-detached 2>/dev/null | while read -r r; do git -C "$LIVE" update-ref -d "$r"; done
  if [ $public_changed = 1 ]; then
    note "GitHub already holds the rewritten history. Nothing is lost: the old history is in $BK_GH. A Claude session should finish with: bash tools/joyguard/resync.sh --apply (in $LIVE)."
  else
    note "GitHub was not changed. Nothing in the work folders was changed."
  fi
  echo "RESULT: STOPPED" >> "$REPORT"; exit 1
}
{ echo "# Privacy scrub run $TS ($mode)"; echo; } > "$REPORT"

# ---------------------------------------------------------------- 0. is this a safe moment?
unsafe=0
why() { note "not safe: $*"; unsafe=1; }
[ -s "$PRIV" ] || why "the private guard list is missing"
git filter-repo --version > /dev/null 2>&1 || why "git filter-repo is not installed"
for f in guard.pl pre-push guard-hashes; do [ -f "$LIVE/.githooks/$f" ] || why "the new guard ($f) is not in the main folder"; done
[ -e "$LIVE/.githooks/_patterns" ] && why "the retired pattern file is still in the main folder"
for f in resync.sh rewrite.sh; do [ -f "$TJ/$f" ] || why "$TJ/$f missing"; done
now=$(date +%s)
for l in "$LOCKDIR"/jb-gradle.lock "$LOCKDIR"/jb-phone*.lock; do
  [ -e "$l" ] || continue
  age=$(( (now - $(stat -c %Y "$l")) / 60 ))
  if [ "$(basename "$l")" = jb-gradle.lock ] || [ $age -lt 30 ]; then why "$(basename "$l") is held (touched $age min ago)"; fi
done
common=$(git -C "$LIVE" rev-parse --path-format=absolute --git-common-dir)
recent=$(find "$common" -maxdepth 3 \( -name index -o -name HEAD -o -name ORIG_HEAD -o -name index.lock -o -name packed-refs \) -newermt "-$QUIET_MIN minutes" 2>/dev/null | wc -l)
recent_refs=$(find "$common/refs/heads" "$common/logs/refs/heads" -type f -newermt "-$QUIET_MIN minutes" 2>/dev/null | wc -l)
[ $((recent + recent_refs)) -gt 0 ] && why "git was used in the last $QUIET_MIN minutes ($((recent + recent_refs)) recent changes): someone is working"
find "$common" -maxdepth 3 -name index.lock 2>/dev/null | grep -q . && why "a git operation is running (index.lock)"
tasklist 2>/dev/null | grep -qi '^codex' && note "note: the Codex app is open (not a blocker by itself: the locks and the quiet-git check show real work)"
git -C "$LIVE" ls-remote --heads "$URL" joy-creator > /dev/null 2>&1 || why "GitHub cannot be reached"
if [ $unsafe = 1 ]; then echo "RESULT: NOT SAFE NOW" >> "$REPORT"; echo "NOT SAFE NOW (see $REPORT)"; exit 3; fi
note "safe: no locks held, no git activity for $QUIET_MIN minutes, GitHub reachable"
if [ "$mode" = --check ]; then echo "RESULT: SAFE" >> "$REPORT"; echo "SAFE"; exit 0; fi

# hold the build lock for the whole run, so nobody starts a heavy job meanwhile
LOCK="$LOCKDIR/jb-gradle.lock"
( set -o noclobber; printf 'privacy-scrub %s\n%s\nhistory rewrite and re-sync; do not build\n' "$TS" "$(date -Iseconds)" > "$LOCK" ) 2>/dev/null || stop "could not take jb-gradle.lock"
trap 'rm -f "$LOCK"' EXIT

# ---------------------------------------------------------------- 1. private backups, verified
git -C "$LIVE" ls-remote "$URL" | sort > "$WORK/github-refs-before-$TS.txt" || stop "cannot list GitHub's refs"
git clone -q --mirror "$URL" "$BK_GH" || stop "backup of GitHub failed"
git -C "$BK_GH" for-each-ref --format='%(objectname)	%(refname)' | sort > "$WORK/bk-refs-$TS.txt"
cmp -s "$WORK/bk-refs-$TS.txt" <(grep -v -e '	HEAD$' -e '\^{}$' "$WORK/github-refs-before-$TS.txt") || stop "the GitHub backup's refs differ from GitHub's"
git -C "$BK_GH" fsck --connectivity-only --no-dangling > /dev/null 2>&1 || stop "the GitHub backup fails git fsck"
note "backup of GitHub: $BK_GH ($(wc -l < "$WORK/bk-refs-$TS.txt") refs, $(git -C "$BK_GH" rev-list --all --count) commits, fsck clean)"
# detached work folders sit on commits no branch names: give each a temporary local ref so the
# backup and the rewrite include them (removed again at the end)
n=0
while IFS= read -r line; do
  case "$line" in "HEAD "*) h="${line#HEAD }" ;; detached) n=$((n+1)); git -C "$LIVE" update-ref "refs/joyguard-detached/$n" "$h" || stop "could not mark a detached work folder" ;; esac
done < <(git -C "$LIVE" worktree list --porcelain)
git clone -q --mirror "$LIVE" "$BK_LOCAL" || stop "backup of the local repository failed"
nl=$(git -C "$LIVE" for-each-ref | wc -l); nb=$(git -C "$BK_LOCAL" for-each-ref | wc -l)
[ "$nl" = "$nb" ] || stop "local backup has $nb refs, the repository $nl"
git -C "$BK_LOCAL" fsck --connectivity-only --no-dangling > /dev/null 2>&1 || stop "the local backup fails git fsck"
note "backup of the local repository: $BK_LOCAL ($nb refs, fsck clean)"

# ---------------------------------------------------------------- 2. the rewrite, on a copy
cp -a "$BK_GH" "$RW" || stop "copy for the rewrite failed"    # a plain copy of the verified backup
git -C "$RW" fetch -q "$LIVE" '+refs/heads/*:refs/local/heads/*' '+refs/joyguard-detached/*:refs/local/detached/*' || stop "could not add the local branches"
git -C "$RW" for-each-ref --format='%(refname) %(objectname)' | sort > "$WORK/tips-before-$TS.txt"
c_before=$(git -C "$RW" rev-list --count --branches --tags); c_all_before=$(git -C "$RW" rev-list --count --all)
note "rewrite copy: $(wc -l < "$WORK/tips-before-$TS.txt") refs (GitHub's plus $(git -C "$RW" for-each-ref refs/local | wc -l) local), $c_all_before commits"
rx0=$(grep -v '^[[:space:]]*#' "$PRIV" | grep . | tr -d '\r' | paste -sd'|')
note "BEFORE the rewrite: commits whose changes hold a guarded word: $(git -C "$RW" log --all --format=%H -G"$rx0" --regexp-ignore-case | wc -l); commits that hold the old pattern file: $(git -C "$RW" log --all --format=%H -- .githooks/_patterns | wc -l)"
t0=$(date +%s)
bash "$TJ/rewrite.sh" "$RW" "$PRIV" > "$WORK/filter-repo-$TS.out" 2>&1 || stop "filter-repo failed (see $WORK/filter-repo-$TS.out)"
note "filter-repo finished in $(( $(date +%s) - t0 )) s"

# ---------------------------------------------------------------- 3. verification
cd "$RW" || stop "cannot enter the rewrite copy"
rxall=$(grep -v '^[[:space:]]*#' "$PRIV" | grep . | tr -d '\r' | paste -sd'|')
hits=$(git log --all --format=%H -G"$rxall" --regexp-ignore-case | wc -l)
note "commits whose changes hold a guarded word: $hits"; [ $hits = 0 ] || stop "guarded words remain in $hits commits"
git log --all --format='commit %H%n%an <%ae>%n%cn <%ce>%n%B' | JOYGUARD_PATTERNS_FILE="$PRIV" perl "$LIVE/.githooks/guard.pl" stream history > /dev/null 2>&1 \
  || stop "a guarded word remains in a commit message or author line"
git for-each-ref refs/tags --format='commit 0000000000000000000000000000000000000000%0a%(taggername)%0a%(contents)' | JOYGUARD_PATTERNS_FILE="$PRIV" perl "$LIVE/.githooks/guard.pl" stream tags > /dev/null 2>&1 \
  || stop "a guarded word remains in a tag message"
note "commit messages, author/committer lines, tag messages with a guarded word: 0"
k=$(git log --all --format=%H -- .githooks/_patterns | wc -l); note "commits that hold the retired pattern file: $k"; [ "$k" = 0 ] || stop "pattern file still in history"
tiphits=0; ntrees=0
pargs=(); while IFS= read -r rx; do pargs+=(-e "$rx"); done < <(grep -v '^[[:space:]]*#' "$PRIV" | grep . | tr -d '\r')
while read -r tree; do   # each distinct tip tree once (many branches share one)
  ntrees=$((ntrees+1))
  k=$(git grep -I -E -i -l "${pargs[@]}" "$tree" -- . 2>/dev/null | wc -l); tiphits=$((tiphits+k))
done < <(git for-each-ref --format='%(objectname)' | while read -r o; do git rev-parse -q --verify "$o^{tree}"; done | sort -u)
note "distinct branch/tag tip trees searched: $ntrees"
note "files with a guarded word at any branch or tag tip: $tiphits"; [ $tiphits = 0 ] || stop "guarded words at branch tips"
export GIT_ALTERNATE_OBJECT_DIRECTORIES="$BK_GH/objects:$BK_LOCAL/objects"
pushed_diff=0; local_diff=0; missing=0
while read -r ref old; do
  new=$(git rev-parse -q --verify "$ref^{commit}") || { missing=$((missing+1)); continue; }
  oc=$(git rev-parse -q --verify "$old^{commit}") || continue
  d=$(git diff --name-only "$oc" "$new" | grep -v -x '\.githooks/_patterns' | wc -l)
  [ "$d" = 0 ] && continue
  case "$ref" in refs/heads/*|refs/tags/*) pushed_diff=$((pushed_diff+1)) ;; refs/local/*) local_diff=$((local_diff+1)) ;; esac
done < "$WORK/tips-before-$TS.txt"
unset GIT_ALTERNATE_OBJECT_DIRECTORIES
note "branch/tag tips whose files changed other than the pattern file: GitHub $pushed_diff, local $local_diff; refs lost: $missing"
[ $pushed_diff = 0 ] || stop "$pushed_diff GitHub branch tips changed beyond the pattern file"
[ $missing = 0 ] || stop "$missing refs disappeared in the rewrite"
c_after=$(git rev-list --count --branches --tags)
note "commits on GitHub's branches and tags: before $c_before, after $c_after"
[ "$c_before" = "$c_after" ] || stop "commit count changed"
[ -s filter-repo/commit-map ] || stop "no commit map"
cd - > /dev/null

# ---------------------------------------------------------------- 4. GitHub unchanged since the backup?
git -C "$LIVE" ls-remote "$URL" | sort | cmp -s - "$WORK/github-refs-before-$TS.txt" || stop "someone pushed to GitHub during the run; start again later"

# ---------------------------------------------------------------- 5. refuse old history from now on
mkdir -p "$common/joyguard"
cp "$RW/filter-repo/commit-map" "$common/joyguard/commit-map"
awk 'NR>1 && $1!=$2 {print $1}' "$RW/filter-repo/commit-map" | sort > "$common/joyguard/old-commits"
cp "$common/joyguard/old-commits" "$common/joyguard/commit-map" "$PRIVDIR/" 2>/dev/null
note "old-history push refusal switched on ($(wc -l < "$common/joyguard/old-commits") old commits listed)"

# ---------------------------------------------------------------- 6. force-push
git -C "$RW" push --atomic --force --porcelain "$URL" 'refs/heads/*:refs/heads/*' 'refs/tags/*:refs/tags/*' > "$WORK/push-$TS.out" 2>&1
prc=$?
if [ $prc != 0 ]; then
  rm -f "$common/joyguard/old-commits" "$common/joyguard/commit-map"
  note "old-history push refusal switched back off (nothing was rewritten on GitHub)"
  stop "GitHub refused the force-push (exit $prc; see $WORK/push-$TS.out). Not retried. The owner may need to allow force-pushes in the repository settings."
fi
public_changed=1
git -C "$LIVE" ls-remote "$URL" 'refs/heads/*' 'refs/tags/*' | sort > "$WORK/github-refs-after-$TS.txt"
git -C "$RW" for-each-ref --format='%(objectname)	%(refname)' refs/heads refs/tags | sort | cmp -s - <(grep -v '\^{}$' "$WORK/github-refs-after-$TS.txt") \
  || stop "GitHub's branches do not match the rewritten copy after the push"
note "force-push done: GitHub's $(grep -v -c -F '^{}' "$WORK/github-refs-after-$TS.txt") branches and tags now hold the rewritten history"
note "GitHub pull-request refs (cannot be rewritten by us; need GitHub Support): $(grep -c 'refs/pull/' "$WORK/github-refs-before-$TS.txt")"

# ---------------------------------------------------------------- 7. local re-sync
git -C "$LIVE" fetch -q "$RW" '+refs/heads/*:refs/joyguard-new/heads/*' '+refs/local/*:refs/joyguard-new/local/*' || stop "could not bring the rewritten commits into the local repository"
git -C "$LIVE" fetch -q --prune --force origin '+refs/heads/*:refs/remotes/origin/*' '+refs/tags/*:refs/tags/*' || stop "fetch from GitHub failed"
( cd "$LIVE" && bash "$TJ/resync.sh" --apply ) > "$WORK/resync-$TS.out" 2>&1
rrc=$?
note "re-sync: $(tail -2 "$WORK/resync-$TS.out" | head -1)"
grep -E '^  (SKIP|STOP|CHECK)' "$WORK/resync-$TS.out" | sed 's/^  /  - /' >> "$REPORT"
git -C "$LIVE" for-each-ref --format='%(refname)' refs/joyguard-new refs/joyguard-detached | while read -r r; do git -C "$LIVE" update-ref -d "$r"; done
[ $rrc = 0 ] || note "some work folders were left as they were (listed above); nothing in them was lost. A Claude session can move them later with tools/joyguard/resync.sh."

# ---------------------------------------------------------------- 8. all-clear
line="- $(date '+%Y-%m-%d %H:%M') privacy scrub: DONE. GitHub history rewritten (owner's private words and the old pattern file removed); local branches and work folders re-synced. Pushing a branch from before the scrub is refused by the pre-push hook: run \`bash tools/joyguard/resync.sh --apply\`."
if [ -z "$(git -C "$LIVE" status --porcelain -- tasks/BOARD.md)" ] && [ "$(git -C "$LIVE" symbolic-ref -q --short HEAD)" = joy-creator ]; then
  printf '%s\n' "$line" >> "$LIVE/tasks/BOARD.md"
  git -C "$LIVE" add tasks/BOARD.md && git -C "$LIVE" commit -q -m "BOARD: privacy scrub all-clear" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" -- tasks/BOARD.md \
    && git -C "$LIVE" push -q origin joy-creator && note "all-clear line committed and pushed on tasks/BOARD.md" \
    || note "could not commit/push the BOARD line; add it by hand: $line"
else
  note "BOARD.md has someone's uncommitted edits (or the main folder is not on joy-creator); add this line by hand: $line"
fi
echo "RESULT: DONE" >> "$REPORT"
echo "DONE (see $REPORT)"
