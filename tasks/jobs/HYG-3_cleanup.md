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
- [ ] **Docs:** move superseded root docs to `tasks/archive/` (git mv, so history follows): every
      `NEXT_SESSION_PROMPT*`, dated `HANDOFF_*` older than two weeks, `FEEDBACK_*`, `DIAG_*`, `BOOTSTRAP_*`,
      `todo.md`, `LANES.md`, `road_map.md`. Keep: `ROADMAP.md`, `STATE.md`, `AGENT_START.md`, `BOARD.md`,
      `INBOX.md`, `lessons.md`, `LAUNCH_STRATEGY.md`, live `SPEC_*` files that a job card links. Check every
      card's links still resolve afterwards.
- [ ] **Specs:** a spec no job card links and no code implements goes to `tasks/archive/specs/` with one line
      in the archive index saying why. A spec for built code stays, linked from STATE.
- [ ] **Junk:** `git rm` the tracked logs; ask the lead about the two pngs.
- [ ] **Worktrees:** for each, record branch, last commit date, `git status` (dirty?), and whether its HEAD is
      on any remote branch. Dirty or unpushed: push to `backup/<name>` first. Then `git worktree remove`.
      Never remove one named on the BOARD or the JoyBrush roadmap.
- [ ] **Branches:** delete local branches that are fully merged into `origin/joy-creator`; leave the rest.
- [ ] OpenCode leftovers in `C:/Temp/jb-opencode-*` and `AppData/Local/Temp/jb-*`: same rule as worktrees.

## Files and traps
- JoyBrush's own folders (`tasks/joybrush/**`, `C:/Temp/jb-*`) belong to the JoyBrush lead: inventory them,
  but archive or remove only with its OK. Its roadmap names the worktrees it still needs.
- "Half-built means unfinished, not abandoned": anything with unique work is pushed and listed, never deleted.
