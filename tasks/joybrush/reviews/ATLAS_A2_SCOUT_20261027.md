# Atlas A2 scout — 2026-10-27 (read-only)

Tags: [verified in code/git] = checked by command or file read this session. [doc only] = stated in a document, not re-checked in code.

## 1. What A2 is supposed to deliver

Two different definitions of "A2" exist in the docs:

- **Media-atlas GL dry driver (older, Oct 12-14).** [doc only] `tasks/joybrush/specs/MEDIA_ATLAS_A2_SOURCE_GATES_20261012.md:1` ("Atlas A2 — verified source constraints") and `:9-10` propose a concrete GPU driver (`MediaAtlasDryDriver.kt`) with a halo, tile apply, frozen pre-frame paper generation and parity proof. [doc only] `tasks/joybrush/HANDOFF_20261014.md:253-254` lists "AtlasA2–A6: actual GL driver, crease-free media and periodic Tile, byte-budgeted halo/staging/gather/pingpong work ... undo/save and device proof."
- **Atlas room / residency (current, Oct 23-27).** [doc only] `tasks/joybrush/ROADMAP.md:30` ("ATLAS-A2: room/residency"): "Prove actual GL ledger, commit ownership/allocation, durable atomic writeback, phase publication/save/undo and P3/P4 capacity/exhaustion/parity before integration." The room plan is `specs/ATLAS_ROOM_20261023.md` (in the repair worktree, not canonical), with phases P1-P4 (water park L2, block-by-block steps, look/stores/drying, sizing).

The current handoff uses the room/residency meaning: [doc only] `tasks/joybrush/HANDOFF_20261027_COORDINATOR.md:183` leaves "AtlasA2" unchecked under main JB5.20d activation, and `:31` says to "Leave Claude explicit live-source/Atlas/save and activation gates."

## 2. Repair worktree

- Path: `C:/Temp/jb-atlas-room-repair-20261025` [verified in code/git]
- Branch `lead/atlas-room-repair-20261025`, tip `52dbb2716ce480f0fdca9e3ad751266f36824216` [verified in code/git]
- Working tree: clean, `git status --short` empty [verified in code/git]
- Canonical HEAD `b29573d9e` (`lead/select-reconcile-20261024`). Merge-base with the worktree is `147c19c00` [verified in code/git]. The tip is not an ancestor of canonical HEAD [verified in code/git].
- `git log --oneline b29573d9e..HEAD` shows 16 commits, newest first: 52dbb2716, 395570fa2, 9b2e07988, 427adc166, 34b27b6f8, 34b27b6f8's predecessors 4c18b39c7 and ffbd23d66, 8a0680ced, cfbd6b70f, a6e8eb938, 264fc1f03, fb4ca966a, 9fe8950fa, c5a8e466f, 2da11fd3f, 251a4d96d [verified in code/git]
- `git cherry` marks all 16 with `+`, meaning none is in canonical by patch-id [verified in code/git]. A2 work is not integrated.
- Diff from merge-base: 18 files, +1691/-34, including `MediaAtlas.kt`, `MediaLayerEngine.kt`, `MediaAtlasResidency.kt`, `WaterBlocks.kt`, `WaterParkPlan.kt`, and `MediaAtlasResidencyTest.kt` [verified in code/git]

## 3. Test and receipt status

- `ATLAS_PARK_OWNERSHIP_REPAIR_20261025.md` (worktree): only `git diff --check` passed. No compile, Android test, GPU or save proof. Says the GL-backed park ownership is unproven and existing guards remain [verified in code/git, file text].
- `ATLAS_ADMISSION_CHECKPOINT_20261026.md` (worktree): source `395570fa2`, all production and test Kotlin compiled, 8 `MediaAtlasResidencyTest` cases, 0 failures/errors/skips. Tests use a fake backing, not the GL park ledger. [verified in code/git, file text]
- Receipt `C:/Temp/jb-lead-ops/atlas-residency-395570fa2-root-count.json` confirms tests 8, failures 0, errors 0, skipped 0, source 395570fa2, shared lock absent [verified in code/git]
- No phone or device receipt for A2 found. Device receipts that mention atlas are for B2/B3 (`MEDIA_ATLAS_B2_*`, `MEDIA_ATLAS_B3_*`), not A2 [verified in code/git]

## 4. Gates still open (all doc only)

From `ATLAS_ADMISSION_CHECKPOINT_20261026.md` and `ROADMAP.md:30`, `:65`:
- Real park-ledger injected-failure and allocator ownership proof
- Commit hook no-throw / no-allocation guarantee (`ArrayDeque<Int>` boxing not proven)
- Atomic durable `writeBackSlot` / payload publication in the GL store; rollback cannot undo partially changed GPU stores
- Production-host forced-capacity raw-plane GPU equality, phase/look publication
- P3 finished evicted state persistence; P4 sizing and exhaustion
- Save / reopen / undo equivalence; device proof
- `reviews/JOYBRUSH_CHECKLIST_20261025.md:10` still says "isolated source only", and `:88-89` says the repair checkout "has no repair edits yet". Both are stale now that the 16 commits exist.

## 5. Blockers named in docs

- [doc only] `ROADMAP.md:30`: must prove GL ledger, ownership, atomic writeback, save/undo and P3/P4 "before integration."
- [doc only] `HANDOFF_20261026_COORDINATOR.md:166-167`: "Durable writeback, actual GLES/commit ownership and P3/P4 still gate integration."
- [doc only] `HANDOFF_20261027_COORDINATOR.md:31, 45`: Atlas stays gated for Claude; no new runtime source pushed to shared joy-creator this push.

## Summary

Atlas A2 has 16 unmerged commits on `lead/atlas-room-repair-20261025`, tip 52dbb2716. The worktree is clean. None of the commits is in canonical HEAD. The only passing test receipt is the 8-case residency test at 395570fa2, which uses a fake backing and does not exercise the GL park ledger. No phone receipt exists. The gates listed in section 4 remain open. The docs define A2 in two incompatible ways (GL dry driver vs room/residency), so the coordinator should confirm which one is meant before any gate is ticked.
