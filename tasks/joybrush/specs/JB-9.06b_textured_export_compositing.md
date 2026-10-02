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
- Include-paper false yields the same RGBA bytes as before and makes zero paper calls; the background callback returns opaque pixels (ERASE_BELOW may subsequently change final alpha); changing paper cannot mutate any source tile.
- Wrong renderer length, nonopaque paper, conflicting arguments and callback exceptions are refused. Oversized/negative/empty region behaviours retain their existing contracts and do not call the paper renderer unnecessarily.
- PNG decoded pixels, animation frame pixels and ORA mergedimage use identical paper-aware compositing. Existing region/export suites pass; APK builds from this row worktree under jb-gradle.lock and --no-daemon, never installed.
- Mutation: move paper back to post-stack compositing; Multiply parity must go red. Remove the block bound/length guard and verify its corresponding guard test fails.

## Questions
2026-10-02 Lead approves the contract with an explicit None rule: saved Paper.screenTransparent=true
always excludes paper from export; the Include checkbox is disabled in this screen state. It retains
surface/Bite for painting and never exports the checkerboard. See LEAD_DESK Active paper UI ruling.
Wait for the current Lead v7 UI/IO edits to land before integration/rebase; isolated preparation may
proceed. This row owns no schema change and must preserve the Lead's new transparency guards.
2026-10-02 Requested the Lead's ruling by authorised thread message. This follows the known discrepancy documented in JB-9.06 Questions; it changes that row's former post-stack export decision rather than quietly contradicting it. Await agreement/amendment before implementation; continue independent library QA meanwhile. Please pin whether export Include-paper can be on while the screen None state is selected, and how the UI labels/disables that combination; do not infer export semantics from Show or checkbox alone.

## Done when
- [x] Lead contract agreed; implementations/tests/mutations complete with exact commands, own XML counts/timestamps.
- [x] Rebased/pushed; affected old specs updated to supersede post-stack composition accurately.
- [ ] Phone export comparisons recorded separately by the Lead/owner; never claimed from desktop tests.

2026-10-02 Wrap-up handoff: no export implementation landed. Start from this approved spec after Lead v7 lands. ORA bottom-paper encoding needs bounded/streaming handling; callback opacity does not imply final opacity after ERASE_BELOW. See reviews/PAPER_OPENCODE_HANDOFF.md.

2026-10-02 LANDED, on base 0dd712ed (Lead v7 `9ff49d81`/`017cd4ed` included, so the gate above was open).
`RegionRenderer.render`/`renderPremultiplied` take an optional `paperRenderer` and lay it down as the
floor of the stack BEFORE the first layer, in blocks of at most `PAPER_BLOCK_W` x `PAPER_BLOCK_H`
(256 x 32) requested in exact document coordinates; `CanvasPng`, `AnimExportRunner.encodeOne`,
`writeSequence` and `OraExport.write` pass their already-resolved renderer through and the post-stack
`overPaper` composition is DELETED, so the second box of this spec is now the only description of the
code. Every existing positional call still compiles. Own XML: core 82/0 (5 suites, newest
2026-10-02T15:55:04.403-04:00), androidkit 132/0 (8 suites, newest 2026-10-02T15:56:47.018-04:00).
Mutations: post-stack restored -> PaperExportBlendTest 2/4 failed; block-length guard removed ->
PaperBackdropTest 1/11 failed with an unchecked ArrayIndexOutOfBounds instead of a sentence.

THE ORA PAPER LAYER WAS HANDLED BY ORDERING, NOT A NEW WRITER. Contract item 4 and the wrap-up note
asked for bounded/streaming handling of `data/0.png`; it is now produced inside the write loop, where
the array is a temporary of one statement and is unreachable before `merged` is rendered, so the saving
is one transient region-sized array instead of one held for the whole method beside the 160 MiB
composite. A streaming PNG row writer remains a JB-3.06a/JB-3.06c question and `PngWriter.encode` was
not changed. The shape of the saving is asserted: exactly one whole-region request per export, the
merge in blocks.

OPACITY IS CHECKED ON THE INPUT ONLY. `requirePaperBlock` refuses any block with alpha below 255 and
names the document pixel; the finished export may still be translucent, because ERASE_BELOW is
destination-out and takes that alpha away exactly as it takes a flat backdrop's. Pinned both ways in
`eraseBelowStillRemovesAlphaFromAnOpaquePaperCallback`.

NO SCHEMA CHANGE, NO UI CHANGE, NO HOT FILE. `Paper.screenTransparent` at DOC_VERSION 7 is untouched
and `PaperNoneExportTest` still passes: excluded paper is never read, not even to check. Phone export
comparisons are the Lead's/owner's and are NOT claimed here — the third box above stays open.
See reviews/JB-9.06b__paper_export_compositing.md.
