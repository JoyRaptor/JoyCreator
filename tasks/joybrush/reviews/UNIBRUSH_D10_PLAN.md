# D10 preparation — no benchmark or phone result yet

This is the original performance plan, retained as source evidence. The bounded circle optimization
and debug helper sources are now staged, with PC GPU parity proved separately; the benchmark host
adapter remains held. D9 and protected host acceptance take precedence. No phone installation or
benchmark has run. Proposed performance thresholds below are future gates, not measured passes.

## Existing protocol and source evidence

Spec DIGITAL_PEN_SPEC_20261016.md128–132 requires pen-line modes60fps with no spiral/scribble hitches,
Soft2000px at least45fps, automatic soft spacing, and a half-resolution buffer proven by pixel diff.
The brief requires a Note9 SM-N960U phone lock and Gradle lock, Home before install, install-r only,
never clear/uninstall and never touch Note20. This preparation takes no locks or device actions.

The real debug entry is app/src/debug/java/com/fadcam/joybrush/JoyBrushBenchActivity.java. It forwards
extras to JoyBrushActivity and never clears other tasks. BenchRunner.kt selects built-in brushes,
opens a fresh unsaved drawing through its host, uses real MotionEvent dispatch, and logs JoyBrushPerf.
StrokeReplay runs240Hz view-space input: fastC120ms, slowC600ms, scribble3000ms, spiral1500ms,
topScribble3500ms, blue1..3 900ms. Pressure spans.25..8 with tilt.6rad and varying orientation.
FrameStats brackets queued work/media/display and glFinish; busy includes actual GPU completion,
gap is frame-start interval during contact, latency is input-to-next-frame-start rather than full
screen/photon latency. snapshot filters stroke frames through UP+20ms, so its busy percentiles do
NOT prove that final commit/taper work later in the250ms tail is cheap. Add a separate lift-to-final
completion measurement; do not fold it into an unsupported claim about contact FPS.

BENCH_BASELINE_20261016.md is historical evidence for source1198fb2f1, not a Unibrush result. Its
two-run baseline reports battery temperature and serialized busy p50/p95, excluded one stated warmup.
Do not copy those times into a D10 result row or assume that phone/device conditions are unchanged.
BenchCases.kt's CPU jobs deliberately build fixtures outside timed closures and have provisional
budgets; they are not the GPU stroke-FPS acceptance harness.

Current source pointers (repository-relative, read-only):

- core/paint/DabPlacer.kt104–122: one look per emitted dab, step=diameter*spacing*sampleMultiplier,
  min.5docpx; leftover arclength and RNG-index order survive arbitrary event batches.
- core/brush/BrushDabber.kt72,168–169,185–187: spacing is exactly preset.spacing; resolved flow and
  wash cap are per-dab; sampled spacing jitter is independent. Dabs also carry profile/quad/fade,
  per-dab colour and shared sampled/dual owners. Changing count changes later RNG/dynamics inputs.
- shaders/jb_dab.vert53 onward: current square half-extent=r*(1.4143+anchor)+2/targetScale,
  native sampled support has a separate larger branch. Positions and appearance stay document-space.
- shaders/jb_contact.glsl: shape/sample then dual mask then flow; grain can replace soft coverage
  but smoothstep(tipCov) still zeros outside the primary support. Native image/dual coordinates are
  reconstructed from target pixel centers, not interpolated offsets.
- shaders/jb_dab.frag: red=c*d, alpha=d with ONE,ONE_MINUS_SRC_ALPHA; MRT RGB moment=ink*c*d.
- canonical GlPaintEngine.addDabs4593 onward allocates sparse stroke tiles before grain binds,
  buckets in document256px tiles, sets target size256/scale1, draws all dabs per target.
- canonical GlPaintEngine.newStrokeTile6157 and releaseStrokeTiles6170 pool full256px allocations.
  strokeBufferIsHalfFloat means R16F precision, NOT half spatial resolution.
- canonical endStroke4814 onward and renderStrokePreviews6021 onward both run full-resolution
  commit math. Physical slab/capture/replay also render stroke units; every such entry needs the
  same target specification if spatial resolution changes.
- D6 held final GlPaintEngine adds StampColorTargets(size), sampled/dual owners and precise working
  texture inventories. Any later buffer patch must be rebased on the Lead-accepted final owner,
  not copy the canonical engine and accidentally drop D4/D5/D6 held integration.

## Exact cases and reporting

Use the actual accepted D9 catalog/resources, not a look-alike brush. `bench_cases.json` identifies
the cases and candidate toggles. Existing BenchRunner has NO size override; selecting unibrush.soft
alone draws its256px default, not2000px. A future debug-only appended optional size override must
apply ToolMemory.sized to the frozen brush before both assignment AND reporting. Report resolved
BrushJson/SHA, size, flow, spacing, cap mode, actual stabilizer, seed, resource digests and chosen
target scale/size. Never tune a shared built-in file just to make one bench job2000px.

Control device model, APK SHA/source revision, Android/build, display Hz, viewport, orientation,
document and board bounds, zoom, density, battery temperature/level and available memory. Use the
same sample bytes and board clipping for paired runs. The huge tip must not be cropped by a small
board; log actual footprint/touched tiles. At zoom1, keep the path plus radius1000+AA inside the
board; enlarge its unsaved bounds from the measured view/path footprint, rather than guessing the
phone resolution. A second board-edge run deliberately tests clipping, separately from throughput.

Warm up each recipe/shader/PNG resolver once, then alternate reference/candidate A-B-B-A with at
least5 measured runs per case. Retain every raw JSON and rejection, including slow tails; state
any restart/cooling exclusion up front. Temperature drift or active low-memory pressure makes a
pair inconclusive until matched conditions are restored. A persisted dry result table uses null
measurements, never zero. A refused stroke or blank output is INCONCLUSIVE, never a fast pass.
BenchRunner currently does not latch onRefused; add scoped callback ownership and an output check
before accepting a job. Taper live contact is still gated pending the actual D7 host transaction;
a standalone taper render cannot be labeled live60fps.

Required metrics: actual drawn strokeFrames (minimum30 for long cases), gap p50/p95/max/mean,
1000/meanGap FPS, busy/work/finish p50/p95/max/mean, gapsOver17/34, first frame, input latency,
raw/emitted/scattered dab counts, tile count, peak held texture bytes including pools, GL errors,
lift-to-final-publication milliseconds, input sample hash, committed RGBA hash and nonzero alpha.
fastC is a responsiveness/first-frame stress case, too short for an honest sustained FPS verdict.

Acceptance reports the spec targets literally: pen spiral/scribble at60fps, Soft2000 at>=45fps.
Publish measured display refresh as well; a59.94Hz display cannot produce an exact60.000 delivered
rate, so disclose a refresh-relative verdict separately instead of quietly weakening the target.
Proposed hitch guard: pen gapsOver34=0 and p95 busy<=1000/60; Soft p95 busy<=1000/45 and max contact
gap<=2 display periods. These are additional proposed stability gates, not numbers in the spec.
Do not demand Soft p95 gap<=22.22ms: a60Hz vsync schedule sustaining45fps includes33.3ms intervals.
Compare commit/lift tail separately and require no new long stall relative to the matched reference.

## Auto-spacing: a controlled approximation, not an identity

For a constant cap c and constant colour, accumulated opacity is c*(1-product(1-d_i)), where
d_i=finalCoverage_i*flow_i includes all shape/dual/grain effects. Moving from spacing.04 to.15
reduces the number of dabs and darkening. A smooth outline alone does not preserve the stroke.
With coincident equal dabs, grouping k gives d'=1-(1-d)^k exactly. Matching peak flow by
f'=1-(1-f)^k and then using coverage*f' is NOT this equation away from coverage1. For f=.08,k4,
coverage.5, baseline=1-.96^4=.15065344 while peak-adjusted=.5*(1-.92^4)=.14180352: a real falloff
change even before placing centers farther apart. This is algebra, not a phone measurement.

Changing cap, per-dab colour, shape/morph, pressure endpoints, sampled selection/spacing jitter,
scatter, per-dab angle jitter and filtered speed all make count changes semantic. A spacing change
also changes once-per-dab RNG consumption. Never globally edit DabPlacer or historical recipes.
Soft D9 already chooses.15 spacing as a NEW recipe, so it needs no hidden automatic correction.

If adding automatic spacing, start as explicit opt-in frozen policy for new family Gaussian soft
recipes only; effective spacing belongs in shared BrushDabber so every live/replay/preview path
agrees. A scalar flow correction is a candidate approximation that must pass the full stroke
pixel gates below. Exclude image/dual/grain/colour/scatter/morph/dash/taper/dynamic-width cases
until each is demonstrated; do not silently drop their features. Keep serialized user spacing
meaningful and expose effective policy, rather than overwriting the requested value. Deterministic
frozen policy must survive save/reopen and batch changes; never choose it from current phone load
or current zoom. Do not activate a schema/extension policy before its compatibility contract is agreed.

## First owned optimization candidate: tighter modern-circle quad

`TIGHT_CIRCLE_EXTENT_CANDIDATE.patch` is an OUTSIDE, uncompiled/unrendered experiment touching only
jb_dab.vert. After the first actual PC GPU run exposed3 failures, its bounded proposal recognizes
only exact canonical modern unit-circle quad, normalized depth1, live aspect0/anchor0, taper0,
fully soft Gaussian profile without profile mix, exact target scale1 or.5, no primary sampled
stamp, radius>=256, and minPx not expanding past r. GPU_FINDINGS.md records the rejected unbounded
candidate; no canonical candidate has landed and the bounded replacement still needs its GPU run.
All other data uses the existing extent expression untouched; disabled legacy attrs cannot enter.
It changes no dabs, flow, cap, profile evaluation, colour moments, grain, masks or VBO layout.
Dual modes only remove primary coverage, so they need no additional extent; native images fall back.

The unit circle's normalized distance is1-Lipschitz. Forward derivatives bound its AA edge at one
target pixel beyond r; keep2 pixels plus r*1e-4 to cover the profile's explicit aa>=1e-4 floor at
very high render scale and float roundoff. Tight half-extent is r+2/scale+r*1e-4 instead of sqrt2*r.
The large-r bounding square shades roughly half the area; this is a geometric estimate, not a
measured speedup. CPU tile bounds remain conservative. Smaller strokes retain the historical quad.
Changed vertices can perturb interpolated v_offset through rasterizer subpixel snapping, so this
candidate requires actual production GPU pixel parity before acceptance, even though support is
mathematically contained. Do not replace offset calculation just to hide a failing comparison.

## Half-resolution buffer: minimal held Lead boundary

Do NOT just set viewport128 on a256 texture, change global tile size, or set u_scale=.5 with target256.
Those respectively fill only part of a texture, change document bucket identity, or change span to512.
A stroke target needs its own immutable descriptor: document tile span256, content texels128,
scale.5, halo1 low-res texel per side, allocation130. Target origin=tileOriginDoc-2; targetSize130;
StampPass already accepts targetSize/scale independently. Full-resolution layers/Undo keep256.

A per-tile128 buffer with CLAMP_TO_EDGE/linear upsampling creates seams at tile boundaries: its
last sample repeats instead of blending with the neighbor's first sample. Render a1-lowres-texel
halo on BOTH sides. Bucket dabs against each tile's expanded document rectangle, including the
target AA extent. Both neighbors must evaluate the same global pixel-center lattice. Commit
stroke UV = v_uv*(128/130)+1/130, with source layer UV unchanged. The display preview, commit,
physical captured source bake, recorded replay and source/slab composer must all use this mapping.
Pixel-diff a stitched tile grid against one monolithic low-res target as a separate exact test.

Exact Lead-owned changes required before enabling:

1. Latch target descriptor at contact/prepare; never switch a live stroke's resolution midflight.
   Initial eligibility: explicit new-family fully soft Gaussian, fixed round geometry/radius>=256,
   no quad morph/hardness dynamics/image/dual/grain/paper interaction/per-dab colour/erase/smudge/
   watercolor/mask-layer/complex blend/line-shaping. Fall back
   for any unproven feature; those features remain fully rendered at scale1. A fixed-size Soft
   with pressure->ink is the first candidate. Dynamic width could cross the threshold midstroke.
2. Allocate and pool130 separately from256; track dimension/format/context epoch in resource owner
   identity, not just GLuint. Inventory actual130²*bytes and pooled allocations. MRT, if supported
   later, must match the descriptor for BOTH red cap and colour-moment targets, not just alpha.
3. addDabs keeps doc256 buckets and origins but renders expanded halos at target130/scale.5.
   Apply scissor/board/tile-wrap/capture-domain clipping in the correct target frame. Halos are
   private scratch, never extra published document tiles or history entries. Allocate before
   binding PNG/dual/grain textures, retaining existing binding invariants.
4. Add/reset commit stroke-UV mapping uniformly in all full and half paths. Only stroke/moment
   inputs are linearly upsampled; no base-layer resampling. Probe chosen half-float/filter path
   on actual hardware; fallback must preserve existing R8/R16F/cap semantics. Never replace cap
   with alpha or cap the blend factor: source blend alpha remains uncapped d.
5. Display/live preview, final commit and captured/source publication use the SAME full-resolution
   upsampled result. Cancel, context loss, scratch failure, pool pressure and retirement retain
   no half target and never publish partial output. Update all ownedTextureHandles/memory/epoch
   inventories and failure-injection tests. Do not connect half targets to unrelated watercolor/oil.
6. Choose a deterministic frozen policy and record/capture provenance before claiming editable
   replay equivalence. CPU dabs stay identical, while lower-res raster is an APPROXIMATION to
   the full-resolution oracle. Document this pixel tolerance explicitly; replaying the same
   optimized target plan must be exact, and legacy scale1 output must stay byte-identical.

No half-buffer production patch is supplied: the public engine currently has no per-stroke target
descriptor or halo-aware target owner; a partial proposal would miss protected publication paths.

## Pixel-diff matrix and broken controls

Use production shaders/packer/commit and full committed RGBA over transparent, opaque neutral and
translucent checker backgrounds, not just isolated coverage. Compare linear-alpha/premultiplied
channels directly and also composites over black/white to catch RGB-moment/alpha disagreement.
Full-image every-pixel probes are required for transport, halos and commit; isolated derivatives
may keep the existing even/even CPU-oracle probes, but those cannot certify stitched output.

- Historical fixtures and every fallback branch: exact byte equality, unchanged dab bytes/RNG;
  null modern state after a candidate draw must reset all defaults.
- Same selected target plan drawn batch vs one dab at a time, arbitrary event chunks, live vs
  saved/reopened replay, full target vs stitched halo tiles: exact bytes where raster geometry
  and target lattice are identical. Do not weaken equality to mask a transport/state error.
- Tight extent vs original extent on eligible circles: start with exact RGBA8 equality; if vertex
  interpolation rounding differs, require max<=1/255 and explain affected pixels, with no lost
  support. Independent full legacy branch remains exact. Large/deep-zoom AA probes required.
- Half-res vs full-res, and changed-spacing vs reference: proposed gate max<=2/255 per premultiplied
  channel, whole-footprint RMSE<=.5/255, absolute summed-alpha change<=1%, no visible rings/seams.
  Compute max/RMSE over union of nonzero support plus halo so blank background cannot dilute it;
  transparent output alone cannot waive composite/background errors. Owner judges gallery before
  accepting approximation. If a case fails, keep full resolution/requested spacing for that case.

Fixture coordinates include dab centers integer,.25,.5,.75, tile-edge255.5/256/256.5 and negative
-256.5/-256/-.5. Sizes511/512/513/2000/4096, scales.125/.5/1/2/16/64/256, low pressure.01,.1,1;
tap/start/lift, short slow stroke, straight line, reversal, dense spiral, crossing scribble and a
stitch across4 tiles. Test cap.25→.9 pressure wash and flow .001/.08/.6/1, primary/secondary colours,
MRT varying colour, razor/minPx>diameter, native1×1/1×64, every dual mode and a custom stepped LUT.
Unsupported combinations must visibly stay at the reference plan, rather than run the approximation.

Broken controls must fail a relevant fixture: force half on a hard edge/native1×1 image; omit
halo; wrong UV bias; viewport128+texture256; scaled minPx/radius twice; draw spacing.15 with no
ink-density compensation vs.04; peak-flow-only correction; cap as blend alpha; omit MRT moment;
use the sampled atlas with the wrong native extent; choose policy from input batch/zoom. A
control that produces no meaningful difference means the fixture did not exercise its feature.

## Next work after D8/D9

First run pure/GPU correctness for the tight-extent candidate off the phone. Only after the parent
accepts those results queue a locked Note9 A/B benchmark. If it reaches the target, still report
half-buffer as pending rather than calling D10 complete without the specified half-res proof.
If not, ask Lead to accept the complete target/halo ownership boundary above before writing it.
