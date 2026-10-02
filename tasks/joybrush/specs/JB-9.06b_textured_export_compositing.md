# JB-9.06b — Textured paper participates in export layer compositing

| | |
|---|---|
| Tier | T1 + T3 |
| Status | Draft for the Lead's contract ruling; independent specification work while the build lock is held |
| Builder | Paper specialist |
| Depends on | JB-9.06 and landed board-region export model (0f4e57cd); contract agreement below |
| Owner area | core/render/RegionRenderer.kt; androidkit/io/CanvasPng.kt, AnimExport.kt, OraExport.kt; their tests. No Activity/view/shader changes |

## Problem and result
A Multiply layer currently blends against transparency, then CanvasPng overlays the result onto paper. Screen blending uses paper as the backdrop, so the PNG can disagree. Textured paper must initialise the export compositor before every layer, just as a flat paper already does. Include-paper off continues to export transparency and does not read/render paper. Saved paint bytes and layer blend metadata remain untouched.

## Contract proposed to the Lead
1. Append an optional `paperRenderer: ((RectPx) -> ByteArray)? = null` to RegionRenderer.render and renderPremultiplied, preserving all existing positional calls. It supplies straight opaque RGBA8 in document coordinates. Reject simultaneous flat paper String and renderer; validate every returned shape/alpha rather than silently padding/truncating it.
2. Invoke only AFTER existing region/allocation guards. Initialise the premultiplied float backdrop using small rectangular blocks, at most256x32 pixels (32 KiB return bytes), with exact global document coordinates. This avoids an additional whole-region paper buffer and preserves the existing160MiB worst-case main compositor allocation plus bounded temporary work. One renderer failure aborts the export, never writes a partial file or silently changes the paper.
3. CanvasPng loads/resolves paper once, forwards its block renderer to RegionRenderer when included, and removes the post-stack overPaper composition from the export path. Preserve structured warnings/fallback when an asset is unreadable. Transparent export never invokes the renderer.
4. Animation GIF/WebP/frame PNG and ORA mergedimage/thumbnail use the same pre-layer backdrop. Keep board rectangles, current frame projection, layer metadata and individual ORA paint-layer pixels unchanged. An ORA paper image remains its own bottom layer; it is a visual background, never editable paint in the document model.
5. Reuse one loaded material per export and frame sequence. Do not allocate/cache a full paper rectangle in addition to full frame pixels. Bounded-region/background tests must prove refusal happens before callback/allocation.
6. Respect the Lead's separately persisted screen-transparency contract/version7. No format change in this row. The export Include-paper choice remains explicit; screen checkerboard is a display affordance and must not be exported as the paper image.

## Tests and proof
- Layer Multiply over a two-colour backdrop equals per-pixel BlendModes results and differs from the old post-stack composition. Normal/semi-transparent, Screen and Erase-below branches pinned too; compare all supported blend modes against a flat backdrop already supported by RegionRenderer.
- Nonuniform paper with negative document origin and board/frame projection matches expected global-coordinate sampling; block boundaries introduce no seam and block sizes never exceed256x32.
- Include-paper false yields the same RGBA bytes as before and makes zero paper calls; included output is opaque; changing paper cannot mutate any source tile.
- Wrong renderer length, nonopaque paper, conflicting arguments and callback exceptions are refused. Oversized/negative/empty region behaviours retain their existing contracts and do not call the paper renderer unnecessarily.
- PNG decoded pixels, animation frame pixels and ORA mergedimage use identical paper-aware compositing. Existing region/export suites pass; APK builds from this row worktree under jb-gradle.lock and --no-daemon, never installed.
- Mutation: move paper back to post-stack compositing; Multiply parity must go red. Remove the block bound/length guard and verify its corresponding guard test fails.

## Questions
2026-10-02 Requested the Lead's ruling by authorised thread message. This follows the known discrepancy documented in JB-9.06 Questions; it changes that row's former post-stack export decision rather than quietly contradicting it. Await agreement/amendment before implementation; continue independent library QA meanwhile. Please pin whether export Include-paper can be on while the screen None state is selected, and how the UI labels/disables that combination; do not infer export semantics from Show or checkbox alone.

## Done when
- [ ] Lead contract agreed; implementations/tests/mutations complete with exact commands, own XML counts/timestamps.
- [ ] Rebased/pushed; affected old specs updated to supersede post-stack composition accurately.
- [ ] Phone export comparisons recorded separately by the Lead/owner; never claimed from desktop tests.
