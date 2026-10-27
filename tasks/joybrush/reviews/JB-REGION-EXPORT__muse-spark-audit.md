# Region frames in exports (0f4e57cd) — adversarial audit (muse)

- Auditor: `muse-spark-1.3-contributor-free` (Meta). Read-only: no builds, nothing touched.
  Read at `origin/joy-creator@44b4f31c`. Prior foundation audit + addendum re-read first.
- Scope: what `0f4e57cd` (+ `44b4f31c` paper contract) changed on the export side.

**EXPORT-AUDIT-VERDICT: FINDINGS-OPEN (0 BLOCKER, 2 MAJOR). No wrong bytes anywhere — both
findings are exporter/screen disagreements, all fail-closed.**

## What region export now renders (verified)

- Frame: `RegionDocumentOps.kt:69,74-77` — explicit `frameId` overrides only boards containing
  it; others fall back to saved `board.currentFrameId` (`:76`), else `DocException("Select a
  frame first")`. `RegionTileSource.kt:13-15` enforces single-owner override.
- Layers/clip/outside: `RegionRenderer.kt:253,271,281,289-297,360-362` — per-tile projection via
  `RegionPaintPlan.tileSlices` (`:64`) + `copyRgbaSlice` (`:84`): pixel-exact clip, shared plane
  outside, fast path for full-shared tiles, no image cache. Held → shared (`:62-63,73`); blank
  cel → transparent (`:28-29`), never stale paint. Masks direct; clip base via its own plan.
- Callers: `CanvasPng.kt:30` (saved cursors), `AnimExport.kt:149,218` (per-frame override),
  `OraExport` merged + per-layer through the same renderer.

## MAJORs

**F1 — screen/export diverge on every valid region doc.** Screen refuses to open
(`JbCanvasView.kt:1218,1224,1228,1243` → `refusalFor` → `:1276`); PNG/Anim write the board's
current/overridden frame pixels. Same `currentFrameId`, same `RegionPaintPlan` ownership exist
only export-side — GL has no board-aware projection. Contradicts 3.00a §G2/§B8 and the prior
audit's both-sides-fail-closed (now screen-only). Staged and disclosed (`REGION_ROUTING` "not
the live GL compositor"; AGENT_BOARD "no equivalence claimed") — still a real divergence.

**F2 — ORA refuses valid region docs while PNG/Anim render them.** `OraExport.kt:357,364`
`omittedBecause` → `DocOps.celFor` → `:259-260` throws "region pixels require a board-aware
paint plan" (wrapped `:248-252`), where CanvasPng (`:16-18,30`) and AnimExport succeed on the
identical doc. Contradicts `RegionRenderer` KDoc ("Every export goes through here, so they cannot
disagree") and §B14/D (engine+export share one clip rule — ORA opted out via stale gate).

## Held (not filed)

Validated docs: outside→shared, held→shared, overlap refused at open (`DocOps.validate`,
`JbArchive.read:420-422`) and by `RegionPaintPlan` requires; blank≠leak and held==shared tested.
`first{}`/`getValue`/dangling-`sharedCelId` sites unreachable except via direct API misuse
(file-open + `CanvasPng:16` re-validation guard production). Bounded memory holds (`requireSize`,
per-tile buffers, no image cache). Paper/`44b4f31c`: untouched by `0f4e57cd`, no bump (stays 6),
below-stack respected. Locked 3.00a §§B8–B14/G2/G7/§K + G7a: export matches the model.
