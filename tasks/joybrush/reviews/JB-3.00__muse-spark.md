# JB-3.00 — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from writer `openrouter/stealth/space-bunny-alpha`.
- Spec: `tasks/joybrush/specs/JB-3.00_create_board.md` (1165 lines, 2026-09-30).
- Method: read-only. No gradle, no edits. Every claim re-derived from the current tree.

**Verdict: SEND-BACK — 3 BLOCKERs + 4 MAJORs. The tree moved at design level (locked 3.00a §K,
region model, DOC_VERSION 4, 2080-line Activity with a prohibition where the spec builds, Lead
handoff giving the Activity away). As written the row builds the wrong UI on the wrong frame
semantics. A thin board-creation row is still needed — rebased, not as specified.**

## Verified (substance holds, anchors stale)

- `boardGradient` zero production callers: CONFIRMED (`JbColors.kt:62-70`). First-caller claim holds.
- `ThreeFingerSwipe` never constructed in production: CONFIRMED in substance (18 test-site
  constructions, spec says 16; zero production callers). Predicate/kind-check/MIN_FLIPPABLE/STEP_DP/begin/move all hold.
- `newDocument` CANVAS-only: CONFIRMED (`DocOps.kt:36`); call site now `JbCanvasView.kt:1451`
  (1652-line file) — spec's `:1173` already stale.
- `activeBoardId` pre-exists, no bump *by this row*: substance holds (`DocModel.kt:197`,
  validated, known-keys); but **DOC_VERSION is 4** (`:11`) and `EnumFreezeTest` asserts 4 —
  "stays 3" stale everywhere.
- 3 Activity sites = 3 hunks: NOT verifiable — file is 2080 lines (was 1613); `moreMenu :713`,
  `toggleChrome :884-900`, `ui :264`, `Board` import already exists at `:28`; new `frameBar`
  state/show (`:234-236`, `:352-358`, `:1819-1865`) unmentioned by the spec.
- 24 tests: arithmetic holds (15+4+5), key derivations re-derived OK
  (NEW_FRAMES=2, NEW_FPS=12f, MAX_NAME=40, nextName/freshId/delete/rename rules, flat id set,
  `addFrame` via `FilmStrip.addAfter :212-218`, sprite 1×1, T14 one-step, T15 order); several pins
  stale (`FilmStrip.add` name, `EnumFreezeTest:98`, suite count).
- Q1 honestly blocking with a stated core-only seam (§1e, gated Step 2, DoD checkbox): passes as
  honesty — but the seam's far end moved (`DocMerge.kt` does not exist; landed seam is
  `CanvasSnapshot.merge :24-89` keeping retained boards; AGENT_BOARD handoff gives Activity/adapter
  to the Lead with JB-3.01b/c first). Q1 needs Lead re-ruling under the region model.
- R30 order vs 3.02b: NOT stated — two rows, one serialised file, no order (also open from my
  3.02b review m8).

## BLOCKERs

**B1 — chrome + kinds + state superseded by locked 3.00a §K.** Spec: ⋯-menu row +
`BoardPopoverView` offering CANVAS/ANIMATION/SPRITE/PUPPET/CHARACTER. Locked model: placeable
kinds **Image (=CANVAS), Tile (switch, not a kind), Animation, Sprite** — Puppet/Character later;
boards are **on-canvas regions** (icon-only rest, satellite pill, top-left pivot, pen-down fade,
peg-tab extension, tile takeover, sprite shelf, layer markers, shared Studio export sheet).
No Lock/Arm, no fixed-after-2, no `currentFrameId`, no `tiled`, no G7a previews in the spec.
Following §7 ships the wrong feature exactly. (Evidence: 3.00a §K `:542-707`, §D `:212-241`,
G7a `:411-426`, K10 `:692-707`.)

**B2 — Activity edits + DocModel freeze contradict the Lead handoff and §D.3.** Handoff: Lead
owns Activity + region adapter; specialist keeps protected files untouched. Spec's A1–A3 edit the
Activity directly — whose `moreMenu` region now carries "Region boards (JB-3.00a) own animation.
Do not expose legacy whole-layer creation." (`:717`). The Activity half is the Lead's, after JB-3.01b/c.

**B3 — Step 3 not dispatchable.** Q1 unanswered + Q2 seam replaced + no R30 order vs 3.02b
(5 sites)/0.08c (19 sites). Only Step 1 (core) + Step 2 (shell) dispatchable, and Step 2's design
is B1-stale.

## MAJORs

**M1 — "no board-creation path" partially overtaken: qualify.** `animateActiveLayer` (`:289-295`)
+ `addFrame` CAN build 1→2-frame ANIMATION docs programmatically now. Still true: no UI path
(menu removed at `:717-719`; zero callers; no boards list — only `frameBarState`; swipe never
constructed; `boardGradient` zero callers; no `JbDocument`). Correct sentence: "programmatic
single-board path with no UI, no multi-board, no gesture wiring." T14/T15 stand, re-anchored,
plus the 1-frame output as an extra matrix row.

**M2 — frame semantics changed (D4/D8/T7).** Region model: frames are region-only (shared outside
pixels, pixel-exact clip, held layers, one stroke/one undo). Whole-layer seeds, `animatedIn`
wording, 1×1-grid-no-resize need re-derivation against 3.01b data. List algebra survives.

**M3 — "no new field, stays 3" contradicts G2/K10/§D.3** (`currentFrameId`, `tiled`, lock/arm,
per-layer markers all bump elsewhere). Rewrite as "no bump *for the list algebra*; region fields
bump per R30-item-3 at landing (reads current number, 4)".

**M4 — G7a preview-framing absent.** Owner clarification + G7a acceptance unmentioned. Add or
explicitly defer.

## MINORs

m1 census anchors (T22 `:98`/4; T20/T23/T24 sound, refresh only). m2 Q4's three answers stale
under regions (new region board = empty region; nothing for a popover to say). m3 Q5/Q6 stand
(10dp badge provisional; no-delete-in-popover), re-anchor to §K.

## Refresh list → READY-conditional

1. Rebase kinds/chrome/state onto 3.00a §B+§G+§K + §D.3 (drop/defer Puppet/Character with reason).
2. Re-derive seeds/deletion against 3.01b region frames (or gate after 3.01b/c); rewrite D4/D8/T1/T2/T7/T13.
3. Replace Q2 seam with `CanvasSnapshot.merge` reality + handoff ownership; state R30 order vs
   3.02b and 0.08c explicitly.
4. Refresh every numeric anchor (newDocument :1451; Activity A1–A3 on the 2080-line file; DOC_VERSION
   4; 18 swipe sites; `addAfter :212-218`; rule 11 `:208-209`; suite count).
5. Rewrite version language; add G7a acceptance; re-rule Q4; keep Q1 honestly blocking with
   Step-1-only dispatch until the Lead rules (a).
