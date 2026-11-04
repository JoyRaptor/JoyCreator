# HYG-3: Clean up the clutter (docs, junk files, worktrees, branches)

**Milestone:** M0  ·  **State:** ⬜  ·  **Owner:** open (a cheap model can do the inventory; the lead approves removals)  ·  **Checked:** 2026-10-27

## Why it matters
Agents keep reading stale lists and treating them as current, and the disk filled up on 10-24 and broke
every build. Clutter is how work slips through the cracks.

## Done when
- [ ] `tasks/` root holds only live files; everything historical sits in `tasks/archive/` with a pointer.
- [ ] No junk is tracked in git at the repo root.
- [ ] Every worktree and local branch is either live (named on the BOARD), landed, or archived as a pushed
      branch, and its folder is removed. **No unique commit or uncommitted change is lost.**
- [ ] C: has comfortable free space.

## Where it stands (10-27 counts)
- `tasks/`: **1,047 tracked files**; 242 at the root, including 20 `NEXT_SESSION_PROMPT*`, 16 `HANDOFF*`,
  19 FEEDBACK/DIAG/AUDIT and 50 `SPEC*` files. `tasks/joybrush/` alone has 543.
- Tracked junk at the repo root: `build_check*.log` (4), `hs_err_pid*.log` (4), `replay_pid56508.log`,
  `clean.log`, `wf_ed.png`, `star-guy-spritesheet.png` (check before removing the last two).
- Untracked junk in the main folder: `word30.mp4`, `ws20.mp4`, `ws30.mp4`, `project_a32d_before_transcript_fix_20260923.json`, `.kotlin/`.
- **156 worktrees** (37 from OpenCode, "bunny" and "muse" runs), **256 local branches**, **147 remote branches**.
- Disk on 10-27: C: 34 GB free; D: 93 GB free. **`C:\Temp` (the worktrees) holds 83 GB**: pruning merged
  worktrees is the biggest disk win.

## What is left
- [x] **Docs (10-28, lead):** 47 moved (`NEXT_SESSION_PROMPT*`, pre-10-14 `HANDOFF_*`, `FEEDBACK_*`, `DIAG_*`, `BOOTSTRAP_*`); `todo.md`, `LANES.md`, `road_map.md` stay put with their RETIRED banners because AGENTS.md names them. Links repointed; 0 broken in live docs. Originally: move superseded root docs to `tasks/archive/` (git mv, so history follows): every
      `NEXT_SESSION_PROMPT*`, dated `HANDOFF_*` older than two weeks, `FEEDBACK_*`, `DIAG_*`, `BOOTSTRAP_*`,
      `todo.md`, `LANES.md`, `road_map.md`. Keep: `ROADMAP.md`, `STATE.md`, `AGENT_START.md`, `BOARD.md`,
      `INBOX.md`, `lessons.md`, `LAUNCH_STRATEGY.md`, live `SPEC_*` files that a job card links. Check every
      card's links still resolve afterwards.
- [ ] **Specs:** a spec no job card links and no code implements goes to `tasks/archive/specs/` with one line
      in the archive index saying why. A spec for built code stays, linked from STATE.
- [x] **Junk (10-28):** the 10 tracked logs removed. Left: `wf_ed.png`, `star-guy-spritesheet.png` (check what uses them first).
- [ ] **Worktrees:** for each, record branch, last commit date, `git status` (dirty?), and whether its HEAD is
      on any remote branch. Dirty or unpushed: push to `backup/<name>` first. Then `git worktree remove`.
      Never remove one named on the BOARD or the JoyBrush roadmap.
- [ ] **Branches:** delete local branches that are fully merged into `origin/joy-creator`; leave the rest.
- [ ] OpenCode leftovers in `C:/Temp/jb-opencode-*` and `AppData/Local/Temp/jb-*`: same rule as worktrees.

## Files and traps
- JoyBrush's own folders (`tasks/joybrush/**`, `C:/Temp/jb-*`) belong to the JoyBrush lead: inventory them,
  but archive or remove only with its OK. Its roadmap names the worktrees it still needs.
- "Half-built means unfinished, not abandoned": anything with unique work is pushed and listed, never deleted.

## Inventory snapshot 2026-11-04 (read-only, Haiku scout; nothing removed)

- Worktrees: 144. Clean and merged into `joy-creator` (safe once the owning lane agrees): 31 (7 under `.claude/worktrees`, 16 `jb-*` that need the JoyBrush lead's OK, 8 others such as `jc-lead-docs`, `jc-vl-lane`, `joybot-20261028`, `vl-studio-fx`).
- Dirty or unpushed: 98 (47 dirty, 4 dirty and unpushed, 51 clean with unpushed commits). Each dirty one needs a push to `backup/<name>` before any removal. 14 more are pushed but not merged.
- Local branches: 315; 170 merged (none unpushed); 145 not merged, 60 of those exist on no remote.
- Tracked junk candidates: `tasks/logcat_play.txt` (9.7 MB), `tasks/fade_debug_live.log`, `tools/jvm-harness/ln_*.log`, root `wf_ed.png` and `star-guy-spritesheet.png` (check references first), `design-assets/PSDs/fadcam_*.zip` (check use). No APKs or build output tracked.
- Counts drift while sessions run; re-run before acting.
