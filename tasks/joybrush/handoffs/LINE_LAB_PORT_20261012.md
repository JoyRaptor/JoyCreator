# Line Lab scoped port — 2026-10-12

Original Line Lab implementation and candidate pencil tuning belong to the vector specialist,
copied from `origin/vector/line-lab` commit `7366c3048dbea55d013e930d02ab4b153b0a14eb`.
Only these eight new files were imported: `core/vector/LineLabSheetTest.kt` (JVM test) and
the seven files under `joybrush/lab/lines/`. No branch core runtime, shaders or shipped presets
were copied. No merge or conflict resolution occurred; preserve the original attribution.

The existing renderer supports `--shaders <dir>` and `u_grainCapacity` from the preset's
`extensions.deposit = "tooth"`. The imported JVM sheet writer matches the current 17-float
stamp ABI and paper APIs. Added checked preset decoding and parse/instance-count gates on the
generated JSON, so invalid fixture data cannot proceed to the GPU image comparison.

Root runs `LineLabSheetTest` through the serialized builder. It writes `pencil.json`, `ink.json`,
`pencil_p1.json`, and `pencil_p2.json` under `joybrush/core/build/linelab/`, plus a summary and
any required packed surface asset. Root's serialized GPU runner produces the four corresponding
PNG sheets; the port itself has not generated or judged those pictures.

## October13 verification and capacity audit

The fixture now passes1/0/0/0 and all four2048×1280 sheets render exit0 with34
strokes each. Visual QA is complete; this is not Proko/raster taste acceptance
or Note9 performance evidence. Source-matched hashes and paths are in
`tasks/joybrush/reviews/evidence/LINELAB_20261013.json`; PNG/log artifacts are
`C:/Temp/jb-linelab-20261013/{pencil,ink,pencil_p1,pencil_p2}.png`.

Read-only audit checked own b3a4fd7c and integrated c8d3f2f8. Exact trace:

- `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/vector/LineLabSheetTest.kt:271`
  maps `extensions["deposit"] == "tooth"` to fixture grainCapacity1.
- `joybrush/lab/lines/render_sheet.js:205` writes u_grainCapacity each stroke;
  the missing uniform is tolerated as a no-op. P1/P2 request1, shipped Pencil0.
- `joybrush/shaders/jb_dab.frag:10` uses only jb_contactCoverage. The active
  `joybrush/shaders/jb_contact.glsl` has no capacity helper/flag.
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/StampPass.kt`
  has no deposit-mode field/binding. Its callers in JbCanvasView/GlPaintEngine
  do not route the extension, so a shader-only port would not enable it live.
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/RefCanvas.kt`
  and `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkRaster.kt`
  accumulate tip coverage; neither has the full CPU paper-contact pass.
  `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkTiles.kt`
  explicitly reports paper grain omission and refuses tip grain. They are not
  full-paper golden oracles.

Current grained rate is d=g*smoothstep(0,.06,t)*flow, where t is tip coverage
and g the combined pressure/tilt-aware grain. Fragment RED=cap*d, source A=d;
over blending gives S'=cap*d+S*(1-d). Repeated identical positive d approaches
cap, so grain controls deposition rate rather than the final gray ceiling.
Commit reads stroke RED, not the accumulated framebuffer alpha.

Experimental `origin/vector/pencil` at6266f1e1503f495eedad4148ba64ec22275c0093
already contains jb_contactParts/jb_contactRateAndTooth in jb_contact.glsl and
an opt-in mode in jb_dab.frag. It uses r=t*flow, C=cap*g, outputs RED=C*r/A=r,
and yields S'=C*r+S*(1-r). With constant inputs, S_n=C*(1-(1-r)^n).
It needs no extra target, texture read or simulation inherently. Do not import
that branch wholesale: it predates and would remove the shared StampPass.

Smallest future contract: explicit experimental STAMP opt-in; absent/unknown
extension stays0, shipped presets unchanged. Resolve the mode into the stroke
look; reset0/1 every batch and through context restoration/live/replay. Preserve
mode0 arithmetic and paint/smudge coverage. Keep flow/cap and WASH/BUILD_UP
commit factors separate; export must implement or disclose unsupported capacity.

Policy prerequisite: if C drops below S, this experiment LIGHTENS existing
pigment. At g=0 and r>0 it relaxes toward zero, whereas the old grain rate0
leaves S alone. This is target relaxation, not monotone pigment deposition.
Keep it lab-only until the lead chooses an explicit policy; a monotone physical
variant needs a different contract/state or destination-aware path.

Required gates/mutants before any implementation:

1. Mode0 byte comparison against current ink/pencil/marker/eraser, preserving
   the known contact baseline result; binding sequence0->1->0 must reset.
2. Repeated1/2/16/64 identical dabs at t=1, flow=.2, g=.25, cap=.8: new ceiling
   .2 vs old .8. First dab alone cannot distinguish the modes. Check RED and
   premultiplied commit, no-grain equivalence, zero flow/cap/t and soft edges.
3. Hard->light->hard pressure, including C=0, tests the chosen relaxation policy.
4. Actual Artisan/tilt/travel, negative origins, seams/zoom, split/whole batches
   and live/replay/export disclosure; fixed-seed P1 must differ with mode active.
5. Mutants: ignored mode, missing reset, g forced1, grain in rate instead of
   capacity, or source A=C*r instead of r must be rejected.

Pencil/P1 PNGs are currently identical because capacity is inactive; P2 differs
through other parameters. No capacity port, preset/default/shader edit or new
build/render was authorized or performed during this audit. Exact owner raster
and Proko references remain required for aesthetic tuning. The lead accepted
this read-only evidence; any active shader contract/fixtures come from the lead.
