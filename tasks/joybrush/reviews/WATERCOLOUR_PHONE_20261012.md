# Watercolour phone checkpoint — 2026-10-12

Branch `vector/watercolour-phone`, isolated checkout `C:/Temp/jb-watercolour-phone`.
Started at 71e8ffea; rebased cleanly onto `origin/joy-creator` f58bf202 before
the final regression. Source checkpoint 63c86026 (published). The lead merges
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
Lead priority hold is respected. Browser checks and root APK build are queued;
the browser runner also exclusively acquires jb-gradle.lock so SwiftShader
cannot overlap a build. It runs stamp, RGBA commit, shader, contact against a
saved 71e8ffea baseline, and final watercolour diagnostic in sequence, closing
each browser. Evidence goes to C:/Temp/jb-watercolour-evidence/.
