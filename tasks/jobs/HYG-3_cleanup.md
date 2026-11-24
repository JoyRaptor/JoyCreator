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

**2026-11-23 evening, website lane (build queue job 5):** full inventory in [HYG-3_worktree_inventory_20261123.md](../HYG-3_worktree_inventory_20261123.md), the list the lead asked for.
- **Worktrees: 212 to 167.** Removed 45 that were clean (untracked files counted), had no commit missing from `origin/joy-creator`, were not on the BOARD, not locked, and not the folder of any linked session. `git worktree remove` without force; no branch deleted. Disk freed not measured (C: 68 GB free after).
- **Left on purpose (167):** live or linked sessions, BOARD-named, 63 with uncommitted changes, 96 with unique commits. Each row says why.
- **Branches: 454 local (274 landed, 115 with unique commits but on a remote, 65 LOCAL ONLY), 317 remote.** The 65 local-only ones are the **save-the-orphans candidates** for the lead (table in the inventory; some may be squash-landed, so check each before saving). Nothing deleted.
- Left: decide the 65 orphans; push them to `backup/<name>`; then the 274 landed local branches can be deleted and more worktrees removed; `tasks/` root clean-up and spec archiving not started; the OpenCode temp folders not looked at.
- **Later, 11-23:** deleted 203 landed local branches (no commit missing from `origin/joy-creator`, not checked out anywhere; `git branch -d`). 14 refused by git and kept. 251 local branches remain, including the 65 local-only orphans. Landed commits stay reachable from `joy-creator`.

## Save-the-orphans: 64 local-only branches pushed as `backup/<name>` (11-23 evening)
All 64 pushed to origin under `backup/<branch-name>` with `git push origin refs/heads/<b>:refs/heads/backup/<b>` (no force; none existed before). The local branches stay; nothing deleted. "Unique" = commits not on `origin/joy-creator`; a squash-landed branch still shows its commits, so check before reviving. **Real unfinished feature?** is my reading of the subject only (not read in depth): **yes** = looks like built-but-unlanded feature work for the lead to rule on; blank = tests, measures, experiments or duplicates. The job column is a guess from the name and subject.

| Branch (backup/ prefix on origin) | Last commit | Unique | Subject | Likely job | Real feature? |
|---|---|---|---|---|---|
| `bunny/JB-8.01b` | 2026-10-01 | 1 | JB-8.01b: read a real .abr - the VlLs byte length never existed, and neither did | JoyBrush engine tests and fixes |  |
| `bunny/D02a-rotation-normalise` | 2026-10-02 | 1 | D.02a: fold what a rotation gesture stores, so a dragged turn stops corrupting k | D.02a rotation fold |  |
| `bunny/JB-2.03a-audit-fixes` | 2026-10-02 | 1 | JB-2.03a: a cancel circle that means something only after the touch has left it | JoyBrush engine tests and fixes |  |
| `bunny/JB-9.01-audit-fixes` | 2026-10-02 | 2 | JB-9.01: fix the ByteArray literal types in the new sign test | JoyBrush engine tests and fixes |  |
| `bunny/jvmTest-corpus-input` | 2026-10-02 | 4 | JB-8.05: take the real-files corpus as a directory, not an Ant pattern | JoyBrush engine fixes (OpenCode "bunny" run) |  |
| `codex/jb-board-chrome` | 2026-10-02 | 5 | JB-3.00a: preserve interrupted fades and define polish acceptance | JoyBrush boards (JB-3.00a) | yes |
| `tmp-203a` | 2026-10-02 | 1 | JB-2.03a: a cancel circle that means something only after the touch has left it | duplicate of a bunny/ branch |  |
| `tmp-901` | 2026-10-02 | 1 | JB-9.01: pin the hex centres, read HEX_GAMMA, and say what the A channel measure | duplicate of a bunny/ branch |  |
| `tmp-901b` | 2026-10-02 | 2 | JB-9.01: fix the ByteArray literal types in the new sign test | duplicate of a bunny/ branch |  |
| `tmp-d02a` | 2026-10-02 | 1 | D.02a: fold what a rotation gesture stores, so a dragged turn stops corrupting k | D.02a rotation fold |  |
| `claude/friendly-bun-31ccb1` | 2026-10-07 | 1 | DrawingHistory: never reuse a name stamp, so pruning drops the true oldest | JoyBrush undo history |  |
| `codex/worker2-composer` | 2026-10-07 | 1 | Document JB-5.20b blend-preserving bake contradiction | JoyBrush composer |  |
| `codex/jb-measure-r51` | 2026-10-08 | 2 | Measure R51 CPU frame rebuilds, archive storage and fill tracing | JoyBrush engine tests and fixes |  |
| `codex/jb-tile-paper` | 2026-10-08 | 1 | Add tile-periodic paper sampler with explicit pre-wrap gradients | JoyBrush tile and paper | yes |
| `codex/worker2-board-strip` | 2026-10-08 | 1 | Reuse shared outline FilmStrip and weighted renderer for Animation boards | JoyBrush boards (JB-3.00a) | yes |
| `opencode/dry-spatial-plan` | 2026-10-09 | 1 | Add pure dry spatial metadata planner with bounded grid ownership | JoyBrush media engine (dry spatial plan) | yes |
| `opencode/media-dry-apply-parity` | 2026-10-09 | 2 | Add exact dry apply/copy float and colour differential checker | JoyBrush media engine (dry spatial plan) | yes |
| `opencode/media-dry-paper-only` | 2026-10-09 | 2 | Retain dry paper shader programs across same-context sleep | JoyBrush media engine (dry spatial plan) | yes |
| `opencode/media-render-benchmark` | 2026-10-09 | 1 | Add optional paired GPU timing with sized pixel parity proofs | ? |  |
| `opencode/mid-09-undo-sequence` | 2026-10-09 | 1 | JB-NOW-09: Undo transaction sequence regression (finds prod double-release; bloc | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-10-codec-corpus` | 2026-10-09 | 1 | JB-NOW-10: scoped mid-tier test/tool (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-11-bake-partition` | 2026-10-09 | 1 | JB-NOW-11: plain-bake partition regression (9 tests pass) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-12-archive-report` | 2026-10-09 | 1 | JB-NOW-12: archive inspection CLI (13 py tests pass) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-13-board-boundary` | 2026-10-09 | 1 | JB-NOW-13: scoped mid-tier test/tool (isolated, unverified) | JoyBrush boards (JB-3.00a) |  |
| `opencode/mid-14-board-lifecycle` | 2026-10-09 | 1 | JB-NOW-14: scoped mid-tier corpus test (isolated, unverified) | JoyBrush boards (JB-3.00a) |  |
| `opencode/mid-15-sprite-grid` | 2026-10-09 | 1 | JB-NOW-15: scoped mid-tier corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-16-swap-plan` | 2026-10-09 | 1 | JB-NOW-16: scoped mid-tier corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-17-anim-ops` | 2026-10-09 | 1 | JB-NOW-17: scoped mid-tier corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-18-region-frames` | 2026-10-09 | 1 | JB-NOW-18: scoped mid-tier corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-19-layer-clone` | 2026-10-09 | 1 | JB-NOW-19: scoped corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-20-docjson` | 2026-10-09 | 1 | JB-NOW-20: scoped corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-21-playback` | 2026-10-09 | 1 | JB-NOW-21: playback oracle (9 tests pass; MAX_SLEEP cap pinned) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-22-filmstrip` | 2026-10-09 | 1 | JB-NOW-22: scoped corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-23-filltrace` | 2026-10-09 | 1 | JB-NOW-23: scoped corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-24-floodfill` | 2026-10-09 | 1 | JB-NOW-24: flood-fill barrier corpus (10 tests pass; 2n+1 edge pinned) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-25-strokeedit` | 2026-10-09 | 1 | JB-NOW-25: scoped corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-26-composer` | 2026-10-09 | 1 | JB-NOW-26: composer interleave corpus (13 tests pass) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-27-inktile` | 2026-10-09 | 1 | JB-NOW-27: scoped corpus test (isolated, unverified) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-28-archive` | 2026-10-09 | 1 | JB-NOW-28: archive roundtrip matrix (13 tests pass) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-29-manifest` | 2026-10-09 | 1 | JB-NOW-29: evidence manifest CLI (18 py tests, 1 platform skip) | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-05` | 2026-10-11 | 1 | Add saved memory refusal evidence reader with strict tag filtering | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-07` | 2026-10-11 | 1 | Add saved CPU trace report with strict CSV diagnostics | JB-NOW corpus tests (OpenCode) |  |
| `opencode/mid-08` | 2026-10-11 | 1 | Benchmark real dry spatial planner workloads with bounded invariants | JB-NOW corpus tests (OpenCode) |  |
| `vector/watercolour-phone-before-a1` | 2026-10-12 | 6 | JoyBrush: verify cached watercolor fade shader | JoyBrush watercolour | yes |
| `claude/dazzling-satoshi-75a363` | 2026-10-14 | 16 | Studio: undo/redo puts the preview back on the right kind of clip | Studio undo/redo preview (STU) | yes |
| `backup/main-folder-presync-20261015` | 2026-10-15 | 29 | Joybot: one action registry, Director mode, recipes, hands-free; phone-tested | Joybot Director mode, recipes, hands-free (BOT lane) | yes |
| `lead/symmetry-a1` | 2026-10-19 | 1 | Symmetry: the review's fixes (the count cap, the barrel as a direction, one rule | JoyBrush Symmetry (JB roadmap) | yes |
| `lead/symmetry-a2` | 2026-10-19 | 3 | Symmetry A3: a copy's tracker starts at the copy's own heading | JoyBrush Symmetry (JB roadmap) | yes |
| `lead/symmetry-c` | 2026-10-19 | 1 | Symmetry: the owner-facing UI (the floating widget, the line and its handles, th | JoyBrush Symmetry (JB roadmap) | yes |
| `lead/crash-diag` | 2026-10-20 | 3 | Media stroke: a stale-payload drop of a texture the stroke itself made ends that | JoyBrush media stroke crash | yes |
| `lead/follow-water-wip` | 2026-10-20 | 1 | WIP (not wired, not for main yet): MediaWindowMath.waterPlacement, where the med | JoyBrush watercolour | yes |
| `lead/main-hash` | 2026-10-20 | 1 | Matrix bench: a hash of the after-state planes per cell (pixel-exact proof betwe | JoyBrush bench |  |
| `lead/symmetry-b` | 2026-10-20 | 2 | Symmetry B fix: the caps are counted in COPIES, and the note is said once | JoyBrush Symmetry (JB roadmap) | yes |
| `lead/symmetry-d` | 2026-10-20 | 1 | Symmetry D1: the copies are ONE group of editable lines, ONE undo | JoyBrush Symmetry (JB roadmap) | yes |
| `lead/wc-live-land` | 2026-10-24 | 8 | Watercolour wash test: name the undo step's texture type, as the line above it d | JoyBrush watercolour (vector wash, wet sheet) | yes |
| `docs/board-state-map-20261027` | 2026-10-27 | 1 | docs: map JoyBrush board capabilities in STATE | JoyBrush boards (JB-3.00a) |  |
| `docs/paper-state-map-20261027` | 2026-10-27 | 1 | docs: map JoyBrush paper surfaces in STATE | STATE docs |  |
| `backup/s19-pre-rebase-20261028` | 2026-10-28 | 7 | S19: Pro and Pro+ build types, the three disguise flavours and their sources rem | S19 build types (landed otherwise; old rebase point) | yes |
| `claude/test-build-20261028` | 2026-10-28 | 3 | Merge branches 'claude/joybot-icon-aspect-20261027' and 'claude/stu2-undo-previe | Studio test build merge |  |
| `lead/unique-names-20261028` | 2026-10-28 | 1 | Studio: a new blank audio project is 'Untitled audio 2', '3'... instead of anoth | Studio audio project names |  |
| `claude/workloss-20261110` | 2026-11-12 | 9 | Merge remote-tracking branch 'origin/claude/ai-fixes-20261112' into claude/workl | work-loss and save fixes (SAVE / FILE) | yes |
| `claude/atlas-save-buffers-hold` | 2026-11-14 | 5 | HoverFootprint: the make-believe drag runs at least four brush widths, so a star | ATLAS-SOLID | yes |
| `claude/atlas-solid-20261109` | 2026-11-14 | 5 | HoverFootprint: the make-believe drag runs at least four brush widths, so a star | ATLAS-SOLID | yes |
| `claude/shell-launchtest-20261115` | 2026-11-15 | 4 | TEST ONLY (not for joy-creator): launch list with four keys to prove the switch  | launch switch test |  |

**2026-11-24 specs (website lane):** 59 spec-named files sat in the `tasks/` root. Checked each by name against the whole repo (cards, STATE, roadmap, handoffs, source): 11 were named nowhere, and were moved with `git mv` to `tasks/archive/specs/` with an [INDEX](../archive/specs/INDEX.md) (one line each; the index says they may describe code that was built, so check before building from one). The other 48 are linked from something and stay. Nothing deleted. `check_joybrush_docs.py`: ok.
