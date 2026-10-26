# A10 run3 — independent five-image UX review

Primary specialist independently opened all five final registered PNGs in
`C:/Temp/jb-select-acceptance-20261026/bench-a10-f7f75869a-run3`.
Manifest: `select_a10_bench_manifest.json` in Select's ui-scripts directory.
Run `eaaea09e705240e0b3f6891d3935bd05`; source
`f7f75869a1df90957196717a4530ac5ff640c5e7`; exact APK
`5d99f3f5fb947129c9f85eb90bcc1f2e413013f28a01ffceb667a36082635132`.
Receipt identifiers read directly from build.json. Coordinator independently validated
completeness and reports6PASS/0FAIL,12 saved roots and owned preferences restored.
No build/device/evidence writes were performed by UX for this review.

| Final PNG | Actual visible result |
| --- | --- |
| 12_a10_lines_on | Ink sheet, Editable lines label, cyan full-pill On. Target fully visible; row/curves still readable. |
| 13_a10_lines_off | Same eligible Ink sheet, neutral Off. Mode-change toast is visible lower down and does not obscure the switch. |
| 14_a10_pixels_only | Proto Pencil sheet, exact D22 explanation fully readable, no Editable lines switch. Preview area is blank; separate triage below. |
| 15_a10_white_canvas_ink_pixels | White canvas with open drawer; selected Ink row and lower-right six-dot raster badge readable. Preview uses its existing own backdrop. |
| 16_a10_dark_canvas_ink_lines | Dark canvas with open drawer; selected Ink row and curve/nodes vector badge readable. Separate top-button contrast defect below. |

**Bounded A10 visual acceptance passes.** All five captures match their expected
stages, with no Home/unrelated/stale target or clipping that prevents these A10 checks.
White/dark refers to canvas only; no preview-paper or full-sheet dark/white rendering
claim. Agent-run synthetic actions are not owner LIVE, pencil feel or S-tier quality.
This does not close C3, D23, pixel PhaseB, during-drag ghost, HSB/menu/layer acceptance
or physical pen/finger feel. The isolated menu9 tests remain separately unrun.

## Separate functional readability finding

Image16 shows enabled Home/Joybot/Guides/Pin/Layers/More glyphs black against the
near-black canvas. These controls are difficult to discover; disabled Undo/Redo are
a separate state. This is a functional contrast defect, not a palette redesign request.
TopButton already supports approved dark/light foregrounds and shadows; Activity
readIcons samples the actual backdrop. Initial source trace finds paper changes can
render without scheduling that sampling: the runtime paper setter requestsRender
without onPaperChanged, and Activity.refreshPaper does not call checkIcons either.
Exact narrow invalidation fix/tests remain separate from the frozen menu test build.

## Blank Proto Pencil preview triage

Image14 contains an empty paper rectangle where Ink images12/13 show preview strokes.
BrushSettingsView advertises a live editable preview; it contains no unsupported-media
placeholder. Activity.openSettings constructs a real JbCanvasView, and onReady redraw
calls replaceWithStrokes -> drawStrokes. Media routes may refuse initialization or
resource admission. This screenshot alone establishes missing preview content, not
which refusal/readiness/render cause occurred and not whether freehand pencil works.
Record as an unresolved preview issue requiring its own diagnostic/refusal evidence;
do not silently accept it as proof of supported preview or alter the brush recipe.
