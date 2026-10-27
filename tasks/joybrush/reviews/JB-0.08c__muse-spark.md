# JB-0.08c — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from writer `openrouter/stealth/space-bunny-alpha`.
- Spec reviewed: `tasks/joybrush/specs/JB-0.08c_save_open_fixes.md` (957 lines).
- Method: read-only. No gradle, no edits. 63 `:<n>` citations counted via spec-command logic.
- Board action (bunny's): stays 📝 Draft spec; consider closing as already-built (see below).

**Verdict: SEND-BACK — the draft is written against a ~1613-line base that no longer exists.**
Current `JoyBrushActivity.kt` is 2080 lines, `JbCanvasView.kt` 1652 lines. All four findings
already landed differently. Applying the 19 sites would duplicate/conflict, drop v4 fields, and
create a second recent-ring.

## Load-bearing findings

- **F6 vs JB-0.02d separation: principle right, version base stale.** Codec refusal
  (`DocJson.refuseUnknownKeys`, `DocJson.kt:112-120`, walk `:145-180`) verified; snapshot path
  (`JbCanvasView.readContents:1391-1472` → fresh `DocOps.newDocument`, partial copy) verified;
  `LayerState` has no `locked` (verified). **Stale:** spec assumes `DOC_VERSION=3`
  (`DocModel.kt:8`); current is **4** (`:11`, v4 paper fields `:66-70`). Spec'd `DocMerge` carries
  only `textureScale/includeInExport`, keeps `boards[0].id/name/clipToBoard`, ignores
  `lookId/tint/show/bite/light`, `fps/frames/grid/kind` — against the v4 tree it reintroduces
  F6-class loss. Merging an ANIMATION board via spec keeps fresh defaults, drops `frames/fps`.
- **19 sites: not real/contiguous.** Anchors 10–400 lines off; A5 fictitious (no `activeSave`,
  no `onSnapshotFailed` lambda — current `beginSave:1549-1616` uses `canvas.snapshot` callback);
  A8/A12/A15 already guarded (0 hunks); A19 duplicates existing `onDestroy:447-456`;
  A7/A13/A14/A16/A17 wrong shape (split `showContents:1718-1749` + `uploadContents:1751-1759`,
  queued `SaveDest.Open` + `StagedDrawing`, no `openedByPerson`, debt-wipe already landed as
  `uploadContents changes=0:1754` + Open `changes=1+IDLE:1605-1606`). `@@==19` cannot hold.
- **`DocMerge` redundant:** `CanvasSnapshot.merge:24-89` already does F6 completely
  (`retained.paper.copy(color=fresh):80`, whole `boards` retained, `locked` kept `:47-52`,
  `thumbnailPng:88`, animation cels + `validate:84-87`); `readContents:1471` calls it.
- **Non-vacuity claims false:** `sorted→sortedDescending` breaks test2 as well (not "still
  passes"); dropping `boards=…` breaks only test4 (not 4+7+8).
- **`.bak`/recent derivation unsound as coded:** spec reuses `File(dir,"$stamp.joybrush")` on
  ms-collision; reuse → second `save` rotates `.bak` over the safety copy. Current
  `DrawingHistory.preserve` (`millis-UUID.joybrush`) + `drop(KEEP-1)` is collision-free and
  already satisfies uniqueness.
- **Out-of-band reasoning obsolete:** `SaveDest.Open` now queues Open itself
  (`:271,1499,1568,1582-1609`); `preserve` runs before selection read, so the queued snapshot
  sees OLD — out-of-band `keepCurrentDrawing` would double-preserve and race. F7 premise gone:
  `paperArgb` already inside `onGl` (`:1314,1318-1319`, setter+render `:127-128`,
  `draw:658` → `GlPaintEngine.draw:1063-1069`).
- **Rulings:** R26 already landed as `DrawingHistory` (KEEP=5, preserve, Recent UI) under
  different names; spec Q7 stale. R30 order holds (row is next) but base already contains
  `BrushHotReload`, `SaveDest.Open/Png`, recovery/history. Spec hardcodes `DOC_VERSION=3`,
  violating its own "read current" rule. Debt-wipe intent landed; `GlPaintEngine.release`
  verified unreachable (Q2 correctly Lead-only).
- **Citations:** ~45 of 63 stale by >3 lines or wrong file/shape (full table in the report
  appendix of the reviewer's transcript). Stop/Do-not lists would stop a builder but cannot
  make this Ready.

**Send back for:** rebase to the 2080-line Activity + `CanvasSnapshot`/`DrawingHistory` reality
(or close as already-built), fix/veto `DocMerge` vs v4, replace timestamp-reuse with UUID or prove
uniqueness, correct non-vacuity claims, refresh anchors or narrow scope. Keep the F6/JB-0.02d
separation table (§51-65) — correct and reusable.
