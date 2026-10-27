# JB-3.04b — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from writer `openrouter/stealth/space-bunny-alpha`.
- Method: read-only. No gradle, no edits. Branch `joy-creator` verified.

**Verdict: SEND-BACK — 7 BLOCKERs + 2 MAJORs. Spec file missing, gate unsatisfied, board cells wrong.**

## BLOCKERs

**B1 — spec file does not exist.** `specs/` (119 entries) has only `JB-3.04_onion_skin_shared.md`;
`Test-Path specs/JB-3.04b_onion_view.md = False`; `git ls-files` + `git log` confirm. Nothing to
check (shown→visibility, ghost list, owner area, stop rule, Do-not, view cut).

**B2 — needs JB-3.04a, not Built.** Board: 3.04a is 📝 Draft spec. `core/anim/` has no
OnionSkin/OnionMath/Ghost. R34's own rationale: the view half cannot be specified honestly
until the maths exists.

**B3 — R34 ordering unsatisfied.** `studiokit/` exists but `grep OnionMath|OnionSkin|onionPast`
= nil; maths still private-inner in `SpriteSheetEditorActivity.java` (`onionPast = 1` at `:81`,
`CellCyclePreview` at `:4521`, loop at `:4651`). Blocked on extraction, not just on 3.04a.

**B4 — board Who cells are copy-paste, not xr evidence (for bunny: board accuracy).**
3.04b's Who cell is verbatim JB-3.02b's text (peg bar, TalkBack nodes, `pegBarWidthPx`, 9 anchors,
7 tokens, FIVE regions — all peg-bar content, nothing about ghosts). 3.04a's Who cell is verbatim
JB-3.03b's text (budget predicate, un-premultiply, 240.7199, ~60 citations). No same-family xr of
the onion specs is on record; claimed "fixes" cannot be verified because there is no spec text.

**B5 — cited prior history absent.** `ORCHESTRATOR_LOG.md` (279 lines) mentions 3.04a/b only in
the JB-3.00 blocks list and the R34 note; there is no "JB-3.04b section" and no 3-BLOCKER xr.
The only xr table covers 3.03b/3.08/3.02b.

**B6 — anchors uncountable; pasted numbers misattributed.** Verifiable anchor count = 0.
Spot check: current `JoyBrushActivity.kt` is 2080 lines; `:355` is now `frameBar` visibility,
not the quoted rule sentence. R30 item 1 satisfied (JB-2.01 🟧 Built) unblocks Activity wiring
in principle but supplies no onion anchors.

**B7 — JB-3.00 hole independently blocks 3.04b.** Screen cannot create an animation board
(`JbCanvasView.kt:1173` CANVAS-only; `BoardKind.ANIMATION` only in core/tests/`JbColors.kt:66`;
no `JbDocument` in the Activity). Even with maths, the view ships no feature until JB-3.00 lands.

## MAJORs

**M1 — ghost-list contract (R34 Q3a) unverifiable.** No text to check for range-computation
leakage; no fail-if-computes-ranges tests. (Ruling at `LEAD_RULINGS.md:366` confirmed.)

**M2 — contradiction check impossible.** Blueprint §1-10c, OWNER_CONSTRAINTS (iOS door, no
Android in core), R30/R32/R33/R34 all read; nothing to rule in or out without the spec.

## MINOR

**N1 — board maintenance:** replace both Who cells with real onion history (or blank); the
3.03b row's dead-link warning pattern applies to 3.04a/b too.

## What Ready requires

(1) R34 order (extract → golden → 3.04a Built); (2) a real `JB-3.04b_onion_view.md`
(host-supplied ghost list, shown→visibility failing-if-unwired test or honest device backstop,
re-based anchors/tokens, complete owner area, stop rule, Do-not, view-half cut); (3) fixed Who
cells; (4) JB-3.00 resolved; (5) different-family xr with counts.
