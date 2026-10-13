# Watercolour phone checkpoint — 2026-10-12

Branch `vector/watercolour-phone`, isolated checkout `C:/Temp/jb-watercolour-phone`.
Started at 71e8ffea; rebased cleanly onto f58bf202 before the full regression.
After atlas A1 landed, reviewed source was re-authored on c7b630e3; only LANES
and todo append conflicts existed, and no merge conflicts were resolved. Prior
ac7c51c1 is preserved in vector/watercolour-phone-before-a1. Current source
checkpoint c8c01ece is published. The lead merges
and installs; no phone accessed.
Reference POC and approved settings in Downloads are unchanged.

## Implemented

- W1: pure deterministic watercolour dabber, v10 format, approved preset, exact
  POC stamp fixture (three paths, 1,746 dabs), raw phone-channel fixture and tests.
  Generic size/flow/opacity/spacing knobs preserve approved Double precision until
  actually changed. No second smoothing/scatter pass.
- W2: all 25 POC-aligned image tips, centred R8 coverage in the red channel,
  complete transparent guards, wash, manifest and reproducible asset check.
  No invented upscale or reduction to 16 tips. Provenance documented; no new
  licence claim for owner-generated images.
- W3: instanced R8 image array to three RGBA16F coverage bands; document paper
  height from the existing canonical sampler; nine neighbour tiles; full-resolution
  edge pigment absorption/alpha buildup and directional gravity pooling; seeded
  wash offset; fixed fine grain; premultiplied RGBA commit and one-second cached
  plain/pooled fade. New pen-down, undo/redo and image readback settle pending fade.
  Automated sample previews settle immediately before combining their Undo step.
  Context loss forgets names; release deletes owned objects; no media dependency.
- W4 groundwork: shared raw-sample replay, width/move/recolour tests and
  GlWatercolourLineRenderer for caller-owned look targets, including render scale
  for sharp levels. Recolour keeps the band geometry and reruns finish only.
- Ancillary: tween scale correction/tests; ten separately staged line presets;
  scoped existing SVG exporter port and tests (see SVG_EXPORT_PORT_20261012.md).

## Acceptance and dependencies

Android compilation and StampPassUniformsParityTest succeeded (one test, zero
failures/errors; the test compares 600 random stroke uniform sets). Final full
core regression after rebase: 2,171 tests, zero failures/errors, four optional
corpus skips; BUILD SUCCESSFUL in 5m24s. Watercolour18, line-preset2, tween11,
SVG29 and outline8 tests are included. The sample SVG was emitted to
joybrush/core/build/vector_svg_sample.svg. APK build is queued.
Builds use the shared lock with RAM admission. Next builds use one worker,
in-process compiler and --no-daemon; owned idle build daemons are released.

The initial WebGL checks pass independent band accumulation, CPU finish optics,
three fade values, mutation controls and real-dab/real-Artisan-paper tile seams
with zero mismatches. Source screenshots exposed an intentional appearance
difference: the approved POC pools at half resolution with quantized overlay LUTs,
whereas the plan calls for crisp full-resolution GPU pooling. Original pooled
painted-only DeltaE < 3 has NOT passed. Thresholds are unchanged. A labelled
full-resolution reference diagnostic is prepared, not a substitute for owner
approval of the new rim. Production now uses LINEAR tip sampling: shape/unpooled
comparisons all pass < 3, unlike mipmap filtering on thin strokes. Mip chains
remain available but are not selected for tip filtering.

The base lacks the lead's JB-5.20d record-capture, look-cache and selection hooks.
The adapter is ready for those hooks; W4 phone draw/nudge/recolour acceptance is
not claimed. CPU InkTiles and SVG explicitly refuse image/pooling watercolour
rather than exporting the wrong procedural nib. A GPU-look/image export bridge
belongs with the lead's line renderer; those exports remain a visible limitation.
Generic line dashes/tapers on the watercolour engine are explicitly refused;
the approved brush's pressure/tilt taper is supported.

W5 Note 9 speed and phone feel are unmeasured. Use the existing FrameStats
glFinish-bracketed replay: long 300px wash at 15% spacing, live dab/finish frame,
pen-up finish and cached fade. The 16ms budget is a device gate, not inferred from
SwiftShader. Pencil taste tuning still needs the owner; inherited pencil presets
are not a Proko comparison. Line lab already contains --shaders and tooth capacity.

Evidence scratch directories: C:/Temp/jb-watercolour-check/ and
C:/Temp/jb-watercolour-check-ab/ (reports and PNG pairs). Final commands/results
will be appended before handoff. No install, merge or existing-artwork migration.

## Serial verification commands

`jbgradle2.sh C:/Temp/jb-watercolour-phone :androidkit:test --tests
'*StampPassUniformsParityTest'` passed, 1m42s; XML test1/failures0/errors0.

`jbgradle2.sh C:/Temp/jb-watercolour-phone :core:jvmTest --no-daemon
--max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` passed,
2,171/0/0 (four skips), 5m24s, October12 12:29EDT.

An initial full run found the old two-engine INK_ENGINES pin; it was updated to
include the explicitly replayable watercolour engine and all tests rerun green.

Shared lock released normally, no persistent daemon left by this final run.
Lead priority hold was respected; long waiting APK/backend wrappers timed out
and the owned waiting browser wrapper was stopped. Followups now defer promptly
when admission/slot is unavailable. The owner authorized direct lead coordination.

## GPU terminal evidence — October12 17:26EDT

One admitted sequential browser batch, exclusive jb-gradle.lock, every browser
closed and lock released. No concurrent build or phone access.

- stamp_pass_check: all gates pass; both deliberately broken controls fail.
- shader_check: passes the actual shaders and current generated paper fixtures.
- commit_blend_check: 59 result cases, zero mismatches/GL errors. Existing
  coverage/smudge modes are bit-exact against saved pre-change 71e8ffea shader;
  new RGBA normal/multiply/screen/erase with source/layer opacity pass.
- contact_check current and saved71e8ffea fail identically, as expected:
  light=0.72900390625, firm=1. This pre-existing failure was not hidden or fixed.
- Watercolour: CPU optics at pooling0/.37/1 byte-exact; independent band
  accumulation exact; actual cached mix endpoints/clamps exact and .37 at most
  one byte. Wrong alpha/tint/mix amount/mix alpha/neighbour controls fail.
- All eight sparse tile corner comparisons: zero mismatches with constant or
  actual Artisan paper, scale1/2, origins[-64,-64] and[-128,-128]. This does not
  prove periodic Tile-board boundaries; the engine refuses that mode for now.
- Original half-res POC appearance: approved painted DeltaE13.12/11.52/6.61;
  paper/grain-disabled isolation7.71/6.12/3.51. All fail the strict <3 gate.
- Additional full-resolution reference (loaded-copy only) passes1.94/1.99/2.03.
  Shape and unpooled comparisons all pass <3. The extra diagnostic isolates the
  planned crisp rim difference; it does not replace original appearance approval.

Report/PNG pairs: C:/Temp/jb-watercolour-check-final/report.json.
Logs: C:/Temp/jb-watercolour-evidence/{stamp,commit,shader,contact-current,
contact-base,watercolour}.log. Visual review: pooling-comparison.html in that
evidence directory (same dabs/tint/wash, no procedural paper/grain in either).

The save boundary now refreshes Undo after settling the cosmetic tail. All
board/layer transaction guards settle the finished watercolor before checking
for an actual active stroke; key enumeration settles before collecting new keys.
Physical in-progress strokes and old brushes retain their existing guards.
Latest snapshot/transaction hooks and LineLab port still need final backend/APK
checks. Backend/LineLab/APK defer until lead's explicit next slot after UX and
JB-5.20a suites; no waiting wrappers or persistent owned JVM remain.

## Owner appearance ruling and follow-up

October12, direct owner message in JoyBrush Lead Coordinator (verified from
the user turn): “the pooling being higher resolution is preferable” and “it
looks good overall.” The slightly darker pooling, grain and whiteness can be
tuned in parameters. Full-resolution finish remains default. The proposed
half-resolution restore is superseded and was never implemented.

Original DeltaE failures stay in the report as actual diagnostic failures;
pooling-resolution difference alone no longer blocks the owner's accepted
appearance. This does not waive optics, seam, commit, mutation, W4 or W5 gates.

Control audit: WatercolourPass forwards edgePool/edgeAlpha/gravityPool/widths,
grain, paperCut, texture strength/contrast/invert to the actual finish shader.
The shader independently changes absorption concentration, exponential edge
alpha, lower-side concentration and grain/wash coverage. The prior perceptual
checker omitted actual fine grain and used constant paper; next control checks
must include the production fine-grain bytes and canonical real paper.

Native UI gap: BrushKnobs.forBrush falls through to generic stamp/paper/smoothing
controls for the new engine; response handles edit p.response rather than the
watercolour Bézier fields. Only generic flow/spacing (and external size/opacity
base controls) are mapped by watercolourRuntime. Dedicated watercolor registry
and curve routing are needed so its advanced sheet has meaningful controls.
No defaults have been blindly retuned. Pencil comparisons require exact raster
snapshots and owner Proko references from the lead before aesthetic changes.

## Backend checkpoint and native controls — October13

Authorized serial `:androidkit:test --no-daemon --max-workers=1
-Pkotlin.compiler.execution.strategy=in-process` finished successfully in
4m56s: XML393 tests, zero failures/errors/skips. The shared lock was released
normally. This verifies source through ed2b1464, including snapshot/transaction
settling hooks and the LineLab production port; it does not verify subsequent
native control edits. No APK, LineLab fixture rendering, additional GPU run or
phone commands were launched after this check; the lead owns the next slot.

The lead approved a bounded control fix and UX explicitly cleared overlapping
ownership. Dedicated watercolor sliders/toggles now replace unused generic
stamp/paper/smoothing controls. Existing pressure/tilt curve editors read and
edit the engine's own curves; tilt markers account for the tip-angle allowance.
Automated preview strokes keep approved sample seeds as parameters change;
freehand stroke seeds remain time-based. Preset defaults and full-resolution
pooling are retained. The checkbox list is initialized before the settings
sheet constructs toggle rows, fixing a constructor-order null access exposed
by the new controls. Core tuning checks and actual grain/paper shader control
checks accompany the source; these new changes await their authorized runtime
checks. Earlier passing totals must not be claimed for this source revision.

Follow-up source checks: seven focused core tests cover persisted no-op tuning,
actual pressure/tilt dab changes with fixed seeds, monotone/corrupt handles,
finish-field independence, runtime/HUD synchronization, quarter-pixel minimum
range and unchanged generic markers. The checker adds production Mulberry32
grain (seed521932, 128² R8 at256 world px) and actual Artisan height tests with
grain/paper-cut mutants. `node --check` and `git diff --check` pass; runtime
results remain pending. Helpers stopped at their usage limit; root completed
the missing tests/checker and degenerate-range repair. No extra worker or
resource-consuming job was launched.

Owner optics question: pooling measures relative drops in assembled coverage,
with four cardinal edge taps and four downward gravity taps. It is not a blur
or a linear distance gradient. Pigment is darkened by linear-light reflectance
raised to concentration; edge opacity has independent exponential buildup.
A seeded dendritic texture could cheaply modulate concentration inside the
existing alpha. This would imitate branching, not simulate capillary growth.
Kubelka–Munk adds scattering/absorption and pigment-aware mixture/layer optics,
but requires calibrated pigments or an RGB-to-pigment approximation. A LUT is
plausible on a phone; Note9 cost is unmeasured. Neither proposed feature was
implemented as part of this control fix.

## Continuation: resource audit and pending acceptance — October13

Pushed controls source251beb972f2c6835bf3a9ffb312fd6a4f6ff4517 is clean.
The lead retains the shared slot for core/native/app regression checks before
assigning this lane's narrow core/kit/native control checks. No queued wrapper
or browser was started. Owned scratch runner `C:/Temp/jb-watercolour-ops/
jbgradle_now.ps1` checks RAM, lead hold and exclusive shared lock once and
defers immediately; it never waits while holding the lock. Its PowerShell
syntax was checked without launching a build.

Static GPU payload accounting from production allocation formats (not measured
phone memory or frame timing):

| Allocation at256² pixels | Payload |
|---|---:|
| One RGBA16F band/cached tile | 512KiB |
| Bands + plain + pooled + engine stroke output when half-float is enabled | 2MiB per touched tile during fade |
| Pass free pool, capped at32 half-float textures | 16MiB |
| Engine freeSmudge pool, capped at16, when half-float is enabled | 8MiB |
|25×251² R8 tips including allocated mip levels |2,092,875 bytes |
|1254² R8 wash including mip levels |2,096,018 bytes |
|128² R8 grain including mip levels |21,845 bytes |

One image set totals4,210,738 bytes (about4.02MiB), excluding driver alignment,
bitmap decode/upload transients, paper, document layers and Undo. The current
pass has a four-set cap. At renderScale2 the tile payload is four times larger.
The separate look renderer and live pass each own their resources. The fade
reads two cached images per pixel and does not rerun optics or read back pixels.
If device measurements justify a later change, RGBA8 cached fade targets and
pool budgets are candidates; no formats or pool limits were changed by this
audit. Owner-approved sharp pooling remains the default.

Next runtime gates in authorized serial slots: seven new core tuning cases plus
existing tuning regression where admitted; Androidkit/native controls compile;
actual grain/Artisan knob and mutant GPU results; LineLab four sheets and APK.
W4 remains dependent on the lead's recorded-line capture/ordered slab/look
cache/selection host. W5 requires actual Note9 timings and owner drawing feel.
The existing6000-dab cap reports a refusal asking the user to lift and continue;
long thin strokes should explicitly exercise this limit on the phone. Verify
pen-down/up fade, rapid next stroke, Undo/Redo, save during fade, reopen, real
paper dryness, same-seed live previews and recolour/move after W4 integration.

Native source follow-up adds `WatercolourBrushSettingsTest` (two Robolectric
SDK28 cases): opening the real sheet catches the pre-init checkbox-list crash,
keeps the live preview outside its scrolling controls, and edits an actual
checkbox through persisted tuning without changing seeds/curves. Reset restores
checkboxes without extra tuning callbacks. This class awaits the same authorized
narrow native slot; production controls source remains251beb97.

## Controls runtime verification — October13, completed

Lead authorized one serial sequence: core7 + existing BrushTuningTest,
Androidkit compile, native compile + focused settings2. Tested source is
4de4fe1a8a54c928e5cc1221bed75395538688b9; production controls still251beb97.
No main-ref update changed this checkout's source during the tests.

The first core run stopped at test compilation (1m18s): the newly added generic
brush fixture omitted required `size`. Commit4de4fe1a supplies Param(6f).
No production change was needed; no failing runtime result is hidden.

| Check | Actual result |
|---|---|
|Core focused rerun |BUILD SUCCESSFUL1m3s; WatercolourKnobsTest7/0/0/0 + BrushTuningTest13/0/0/0 |
|Androidkit compileKotlin |BUILD SUCCESSFUL31s |
|Native compileDebugKotlin + WatercolourBrushSettingsTest |BUILD SUCCESSFUL1m13s; XML2/0/0/0 |

All22 focused tests passed without skips. Native XML also emits an unrelated
BoardBackdropTest suite with zero selected tests; it adds zero to the total.
These are source/JVM checks, not phone drawing or hardware performance evidence.

All jobs used no-daemon, one worker, in-process Kotlin and CPU2. Gradle heap
1536MiB, test heap512MiB. Initial run admitted5401MiB; focused rerun5373MiB,
kit5404MiB, native5172MiB. The lead clarified that1536+512 jobs require at least
2800MiB, so every job after the initial run used that threshold. Each runner
checked RAM once, acquired the shared lock exclusively, and released it normally
on terminal. Final shared lock absent, clean checkout, about5189MiB free.

This completes the controls compile/tuning/native construction gates that were
pending above. GPU actual grain/Artisan checks, LineLab four PNGs, integration
APK, JB-5.20d host/W4 and physical W5 remain open. No GPU, browser, app assembly
or phone command was launched in this slot. The slot was released to the lead.

## Actual grain and Artisan GPU run — October13, completed

ONE authorized watercolor checker job, source54cf9c41e9ee48898f3d6de7e70e8cb0c9726505,
admitted4790MiB against a3200MiB minimum, exclusive shared lock. Actual shader
finish, production Mulberry32/R8 grain bytes and canonical Artisan height were
used. No LineLab, other GPU checker, build, server, benchmark or phone was run.

- All37 nonperceptual gates pass, including deliberately broken mutation controls.
- Edge/gravity pigment sliders change RGB by up to73/60 bytes, with zero alpha
  changes. EdgeAlpha increases summed coverage182874->216597 with no decreases.
- Grain increases breakup: summed coverage254565->154618, no pixel increases.
  Paper height decreases227430->205088 and changes interior alpha range0->30.
  Grain/paper-cut mutant errors187/42 bytes prove those controls were exercised.
- CPU pooling0/.37/1 is byte-exact. Bands accumulate exactly. Cached fade
  endpoints/clamps are exact; intermediate .37 differs by at most one byte.
- All8 seam cases (constant/actual paper, scales1/2, two negative origins) have
  zero mismatches. Neighbour mutants fail by up to118/119 bytes. GLerror0.
- Full-resolution reference painted DeltaE1.936/1.985/2.032 passes<3. Original
  half-res approved13.120/11.519/6.612 and isolated7.715/6.116/3.515 still FAIL.
  Unpooled and shape comparisons pass. All15 appearance checks retain their
  original thresholds; six historical differences remain reported failures.

Checker exit1 reflects those six original appearance failures, not a fully green
command. Full-resolution owner approval stands separately; no threshold or
production/default shader change was made. POC loaded-copy reference quality1
remains distinct from the approved UI preview renderScale.65. The new grain and
actual paper tests are separate from the unchanged perceptual fixture.

Raw report/source/terminal/exit and30 PNG pairs:
`C:/Temp/jb-watercolour-gpu-20261013/`. Numeric source-matched evidence is pushed
at `tasks/joybrush/reviews/evidence/WATERCOLOUR_GPU_20261013.json`. Browser finally
closed normally; post-run owned SwiftShader Edge process count0 and shared lock
absent. Original POC SHA256004B563B6467ED5775D56496EE05EAF56D4A5F187D2321799664146BDE60AF6E
is unchanged. W4 host, LineLab sheets, integration APK and actual W5 remain open.
