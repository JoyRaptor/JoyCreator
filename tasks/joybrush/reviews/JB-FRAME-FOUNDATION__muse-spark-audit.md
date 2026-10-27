# Frame-projection foundation — adversarial audit (muse, for Codex Lead + bunny)

- Auditor: `muse-spark-1.3-contributor-free` (open-code harness). Read-only: no builds, nothing
  touched. Read at branch `joy-creator` `63c4b9bb` (+ local `d1840ded` frame work).
- Lead-invited via `AGENT_BOARD.md`. Evidence read first: `AGENT_BOARD.md`,
  `INTEGRATION_20260930.md`, `JB-3.00a_boards_owner_model.md` §B/§G/§G7a/§K.

**AUDIT-VERDICT: FINDINGS-OPEN (0 BLOCKER, 0 MAJOR, 1 MINOR).**

## MINOR

**F1 — stale frame id surfaces a stdlib message instead of a named refusal.**
`JbCanvasView.kt:303` (`changeHold`, inside `changeFrames` at `:307`): `changeHold(frameId, delta)`
with an id absent from the board — reachable via a stale long-press "Hold longer/shorter" popover
(`JoyBrushActivity.kt:1859-1860`) if the frame disappears first (undo of the creating `addFrame`
while the menu is open). `board.frames.first { it.id == frameId }` throws
`NoSuchElementException` ("Collection contains no element matching the predicate."), posted
verbatim via `onRefused` (`:345`). Fail-closed (`:342-344` restores `currentFrameId`,
re-projects `before`, prunes) — no pixel/history risk; the defect is a programmer-facing message
shown to the owner. `AnimOps.setHold` would have refused in words but is never reached. No
spec/AGENT_BOARD contradiction; contradicts the codebase's refusal-in-words convention. Fix is one
line (`firstOrNull ?: throw JbArchiveException(...)`), builder's call.

## Confirmations (attacked, held)

- **No shortcut resurrection.** `animateActiveLayer()` (`JbCanvasView.kt:289`) has zero callers
  tree-wide; the ⋯ menu (`:713-719`) carries the "Region boards own animation" guard and no Animate
  row. (The method itself is dead surface, not wiring — noted, not filed.)
- **Session-only selection.** `currentFrameId` (`:270`) `@Volatile` memory-only, never archived;
  sole consumer is PNG export (`JoyBrushActivity.kt:1507,1571`); cold start falls back to first frame.
- **Undo preserves animation metadata.** Frame ops push `Step(emptyList(), documentBefore,
  documentAfter)` (`:339`); `mergeNewest` keeps first-before/last-after (`UndoLog.kt:114-116`,
  `FrameProjectionTest.kt:169-179`); `applyFrameHistory` (`:356-375`) restores graph + reselects,
  incl. pixel-step cel-owner fallback (`:363-372`).
- **Paint-save/undo safety.** `storeId` (`CelProjection.kt:8-12`) collision-free (length-prefixed;
  `\0`-ids refused at `:1346`); first-cel legacy addresses preserved; undo records physical store
  ids; `referencedStores`/`referencedDocuments` + `pruneCels` (`:201-211`) provably retains
  redo-needed cels (audit re-derived all counts); snapshot derives tile lists from GPU reality
  (self-healing); both-directions completeness in `JbArchive.write` (`:206-214`) and `read`
  (`:426-453`); foreign/truncated cel payloads refused (`CanvasSnapshot.kt:69-75`, tested).
- **GPU-vs-export parity (frame dimension).** Screen follows `layer.paint`
  (`GlPaintEngine.kt:1085`); export follows `DocOps.celFor` (`RegionRenderer.kt:268`) over snapshot
  contents carrying all cels; selected/linked/masked cases pixel-proved (`:68-76,181-194`, zero-mask
  case non-vacuous).
- **Bounded memory.** `canAddTiles` (`CelProjection.kt:20-24`) reserves 64 MiB + CPU-save headroom;
  duplicate-copy path guarded (`:323`) fail-closed; large Open holds one CPU set via `StagedDrawing`
  + single-executor release (`:1584-1611`); OOM caught at snapshot (`:1253`) and selection-read
  (`:1589`); `StagedDrawing` close paths verified (`:1491,1515,1714`).
- **Malformed files.** Multi-board/wrong-kind/INK/multi-cel/static-cel/paper-texture/`\0`-id all
  refused before GL work (`:1327-1381`); zero-frame boards, dangling `frameCel`, bad holds refused
  by `DocOps.validate`; `CanvasPng` refuses missing selection (`:20-22`, tested). All checked
  `.single()`/`first{}` sites sit behind `checkContents`/single-board construction — no reachable
  throw (hardening to typed refusals optional, not filed).

## Addendum 2026-10-02 (re-verified vs `origin/joy-creator@82a16eba`, read-only)

- **F1 FIXED by removal.** `changeHold`/`changeFrames`/`currentFrameId`/`animateActiveLayer` and the
  long-press reachability are gone; the surviving `AnimOps.setHold` refuses unknown frames in
  words (`AnimOps.kt:368-370` → `DocException` at `:596-598`). Do not re-fix F1.
- **Scope changed by design, not broken:** selection is now persisted per-board
  `Board.currentFrameId` (v6, validated, round-trip tested — owner-model R3); `UndoLog`
  document-steps, `CelProjection` (`storeId`/`canAddTiles`), and the multi-cel GPU projection
  are deleted with the code they served; `CanvasSnapshot.merge` revised to a single static cel;
  `\0`-id refusal dropped consistently with its scheme. GPU-vs-export, bounded-memory, and
  malformed-file lines hold in the reduced scope (region layers fail closed on both screen and
  export; `RegionPaintPlan` contradicts nothing — pure geometry, fuzz-proved partition).
- **No new BLOCKERs** in `b5ec6811`/`15af73f5` (entry-point `!!`/`getValue` sites unreachable
  behind `valid(doc)` + `DocOps` validation, refusals tested). One advisory nicety (not filed):
  `CanvasPng.encode` (`:18-19`) has no explicit `regions`-nonempty guard — direct API misuse
  reaches `DocOps.celFor` → `DocException` (words, pre-write, caught by toast handlers);
  unreachable via UI. Exception-type hardening only, at will.

## Expected staged-WIP gaps (not defects — Lead-scoped)

Whole-layer cels vs §B8; no `Board.currentFrameId` persistence vs §G2 (session-only by design;
JB-3.01b/c owns); one-board load refusal; no delete/move-frame controls or `DropCel` path; G7a
preview framing (documentation only); K6 shared export sheet (Lead review); region migration
(JB-3.01b/c); onion/playback/GIF/Studio (out of foundation scope).
