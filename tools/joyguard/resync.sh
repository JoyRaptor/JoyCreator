#!/usr/bin/env bash
# After the privacy scrub rewrote history, move every local branch and every work folder
# (worktree) of this repository onto its rewritten commit, keeping uncommitted work exactly
# as it is. Safe to run again: anything already moved is left alone.
#
#   bash tools/joyguard/resync.sh            show what would move (changes nothing)
#   bash tools/joyguard/resync.sh --apply    move them
#
# It needs <git common dir>/joyguard/commit-map (filter-repo's "old new" list, copied there by
# the scrub run) and the rewritten commits already fetched into this repository.
# Per work folder: git read-tree -m -u OLD NEW (a two-tree switch: files that differ between the
# two commits are updated, local edits elsewhere are kept; it refuses rather than overwrite a local
# edit), then git update-ref HEAD NEW OLD (refuses if someone committed meanwhile).
# Work folders in the middle of a merge, rebase or cherry-pick are left alone and listed.
# It never resets, cleans, stashes or deletes anything. Prints no file content.
set -u
apply=0; [ "${1:-}" = "--apply" ] && apply=1
common=$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || { echo "resync: not in a git repository"; exit 2; }
map="$common/joyguard/commit-map"
[ -s "$map" ] || { echo "resync: no commit map at $map - nothing to do"; exit 0; }
log="$common/joyguard/resync-$(date +%Y%m%d-%H%M%S).log"
[ $apply = 1 ] && exec > >(tee -a "$log") 2>&1
Z=0000000000000000000000000000000000000000

declare -A NEW
while read -r o n; do
  [ "$o" = old ] && continue
  [ ${#o} = 40 ] && [ ${#n} = 40 ] && [ "$o" != "$n" ] && NEW[$o]=$n
done < "$map"
echo "resync: ${#NEW[@]} rewritten commits in the map; mode: $([ $apply = 1 ] && echo APPLY || echo dry run)"

moved=0; skipped=0; problems=0; same=0
declare -A DONE_BRANCH

late() { # a commit not in the map that sits on old history (made after the scrub's snapshot)
  local c
  for c in $(git rev-list --max-count=200 "$1" 2>/dev/null); do [ -n "${NEW[$c]:-}" ] && return 0; done
  return 1
}

snap() { # a fingerprint of a work folder's uncommitted state, leaving out the rewritten paths
  local wt="$1"; shift
  { git -C "$wt" diff --binary --full-index -- . "$@"; echo "@@cached"; git -C "$wt" diff --cached --binary --full-index -- . "$@"
    echo "@@untracked"; git -C "$wt" ls-files --others --exclude-standard -- . "$@"; } 2>/dev/null | git hash-object --stdin
}

move_worktree() { # path branchref(or empty if detached)
  local wt="$1" bref="$2" gd old new ex=() f
  if [ ! -d "$wt" ]; then echo "  SKIP  missing folder: $wt"; skipped=$((skipped+1)); return; fi
  gd=$(git -C "$wt" rev-parse --absolute-git-dir 2>/dev/null) || { echo "  SKIP  not readable: $wt"; skipped=$((skipped+1)); return; }
  for f in MERGE_HEAD rebase-merge rebase-apply CHERRY_PICK_HEAD REVERT_HEAD BISECT_LOG index.lock; do
    if [ -e "$gd/$f" ]; then echo "  SKIP  $wt is in the middle of an operation ($f): left as is"; skipped=$((skipped+1)); problems=$((problems+1)); return; fi
  done
  old=$(git -C "$wt" rev-parse -q --verify HEAD) || { echo "  SKIP  no HEAD: $wt"; skipped=$((skipped+1)); return; }
  new="${NEW[$old]:-}"
  if [ -z "$new" ]; then
    if late "$old"; then
      echo "  SKIP  $wt: has commits made on the old history after the scrub; left as is, nothing lost"
      problems=$((problems+1))
    else same=$((same+1)); fi
    return
  fi
  if ! git -C "$wt" cat-file -e "$new^{commit}" 2>/dev/null; then
    echo "  STOP  $wt: the rewritten commit ${new:0:10} is not in this repository yet (fetch first)"; problems=$((problems+1)); return
  fi
  while IFS= read -r f; do ex+=(":(exclude,top)$f"); done < <(git diff --name-only "$old" "$new")
  if [ $apply = 0 ]; then echo "  would move $wt  ${old:0:10} -> ${new:0:10}  (${#ex[@]} file(s) differ)"; moved=$((moved+1)); return; fi
  local before after
  before=$(snap "$wt" "${ex[@]}")
  git -C "$wt" update-index -q --refresh > /dev/null 2>&1
  if ! git -C "$wt" read-tree -m -u "$old" "$new" > /dev/null 2>&1; then
    echo "  SKIP  $wt: an uncommitted edit touches a file the scrub changed; left on the old commit, nothing lost"
    problems=$((problems+1)); return
  fi
  if [ -n "$bref" ]; then
    git -C "$wt" update-ref -m "joyguard resync" "$bref" "$new" "$old" 2>/dev/null
  else
    git -C "$wt" update-ref --no-deref -m "joyguard resync" HEAD "$new" "$old" 2>/dev/null
  fi
  if [ $? != 0 ]; then
    git -C "$wt" read-tree -m -u "$new" "$old" > /dev/null 2>&1
    echo "  SKIP  $wt: its branch moved while I worked (a new commit?); put back, nothing lost"; problems=$((problems+1)); return
  fi
  after=$(snap "$wt" "${ex[@]}")
  if [ "$before" != "$after" ]; then
    echo "  CHECK $wt moved, but its uncommitted state fingerprint changed - look at it"; problems=$((problems+1))
  fi
  [ -n "$bref" ] && DONE_BRANCH[$bref]=1
  moved=$((moved+1))
}

echo "work folders:"
wt=""; br=""
while IFS= read -r line; do
  case "$line" in
    "worktree "*) wt="${line#worktree }"; br="" ;;
    "branch "*) br="${line#branch }" ;;
    "") [ -n "$wt" ] && { move_worktree "$wt" "$br"; [ -n "$br" ] && DONE_BRANCH[$br]=1; }; wt="" ;;
  esac
done < <(git worktree list --porcelain; echo)

echo "branches not open in a work folder:"
while read -r ref sha; do
  [ -n "${DONE_BRANCH[$ref]:-}" ] && continue
  new="${NEW[$sha]:-}"
  if [ -z "$new" ]; then
    if [ "${ref#refs/heads/}" != "$ref" ] && late "$sha"; then echo "  SKIP  $ref: commits made on the old history after the scrub"; problems=$((problems+1))
    else same=$((same+1)); fi
    continue
  fi
  if ! git cat-file -e "$new^{commit}" 2>/dev/null; then echo "  STOP  $ref: rewritten commit not here yet"; problems=$((problems+1)); continue; fi
  if [ $apply = 0 ]; then echo "  would move $ref"; moved=$((moved+1)); continue; fi
  if git update-ref -m "joyguard resync" "$ref" "$new" "$sha"; then moved=$((moved+1)); else echo "  SKIP  $ref changed meanwhile"; problems=$((problems+1)); fi
done < <(git for-each-ref --format='%(refname) %(objectname)' refs/heads refs/tags)

left=0
while read -r ref sha; do [ -n "${NEW[$sha]:-}" ] && left=$((left+1)); done < <(git for-each-ref --format='%(refname) %(objectname)' refs/heads refs/tags)
echo "resync: moved $moved, already current $same, skipped $skipped, problems $problems; branches/tags still on old history: $left"
[ $apply = 1 ] && echo "log: $log"
[ $problems = 0 ] || exit 1
exit 0
