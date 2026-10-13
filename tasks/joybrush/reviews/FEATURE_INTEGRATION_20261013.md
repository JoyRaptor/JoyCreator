# Select and watercolor integration — October13, 2026

Lead worktree: C:/Temp/jb-feature-integration, branch lead/jb-feature-integration.
Base c99a25be preserves the published atlas A1 and JB-5.20a document/save foundation.
Select source a16059d6 replays the complete f1bfc967..b9647c1c source delta.
Watercolor source c8d3f2f8 replays the complete c7b630e3..965192e3 source delta,
with one reviewed SVG ordering repair. Neither application used three-way conflict resolution.
Original specialist branches/worktrees remain preserved.

## Behavior

Approved cyan-gradient full pills and borderless paper-backed Select controls are integrated,
including cancellation rollback, independent weight snapshots, failed-lift capability hiding,
translated paint clipping and start-relative drag/pinch handling. The default-compatible
Studio cancellation hook leaves existing hosts with their default no-op.
The LayerColumn animation fixture retains v10 PAINT plus strokesFile metadata.

The image-dab watercolor brush, its paper/grain/pooling controls, full-resolution pooling,
pressure/tilt curves, deterministic preview seeds and live raster painting/fade hooks are
integrated with the document foundation. Existing presets and the default brush choice are
unchanged. Line preset, SVG outline and LineLab foundations are included from the reviewed
specialist source. SVG interleaving now defaults to persisted record.seq rather than input
list index; a regression puts an unsorted pair of lines on opposite sides of a pixel slab.
Explicit sequence overrides and stable equal-sequence fixture ordering remain supported.

Document version10 and brush version10 are independent, both retained in EnumFreezeTest.
Main's archive, screen/recovery/snapshot and whole-document export capability guards remain
unchanged. Asset-rendered watercolor still refuses CPU ink and SVG-outline approximations.
The live watercolor path currently saves ordinary raster tiles; editable-record capture,
selection host and GPU-backed record/image export are later slices.

## Verification

- UI-only integrated native suite: PASS4m45 on a16059d6;144 tests, zero failures/errors/skips,
 16 suites independently counted from fresh XML. C:/Temp/jb-lead-ops/ui-integrated-native.log.
- Combined core/backend regression: PASS7m15 on c8d3f2f8. Core2201 tests, zero failures/errors,
  four optional skips (195 suites); backend408 tests, zero failures/errors/skips (47 suites).
  All counted independently from fresh XML. New LineSvgTest10/0/0/0 includes the persisted-order
  repair. Log C:/Temp/jb-lead-ops/features-core-backend.log.
- Combined native regression: first run146 tests,11 failures, zero errors/skips (2m34).
  All failures were FillPenDispatchTest's reflective helper still looking for the old one-argument
  private startStroke. Test-only df8df716 passes the new optional preview seed as null; all eleven
  dispatch/refusal assertions are unchanged. Full native repair PASS1m9:146 tests, zero
  failures/errors/skips,17 suites independently counted. Production source
  and passing core/backend evidence are unchanged; no core/backend repeat is required.
- Combined app assembly: PASS4m33 on df8df716, app-only repository2048MiB heap/CPU2,
  minimum3200MiB. C:/Temp/jb-lead-ops/features-app.log. Arm64/universal165076648 bytes,
  SHA256 AE90642B3FB07202AE29DD36963E2DACFB3FE61894D0615EA7EA8D2B0AFF16BA;
  armv7 SHA256 BA576907DE4EB4D5DBD6DBB6280E6973E9D5EEC206F1E0D417748B11457D2CF0.
  Outputs: C:/Temp/jb-feature-integration/app/build/outputs/apk/default/debug.
  APK inspection finds all25 watercolor dab images and the watercolor brush; packaged
  jb_wc_finish.frag/jb_commit.frag hashes match the tested source exactly. No APK installed.
- GPU shaders/pass source is unchanged from the source-matched specialist evidence.
  WATERCOLOUR_GPU_20261013.json retains37 passing nonperceptual gates, eight zero-mismatch
  seam cases and six failing original half-resolution appearance diagnostics. Owner approval
  of higher-resolution pooling is recorded separately; checker exit1 is not rewritten as green.
- LineLab evidence: fixture1/0/0/0, four rendered/inspected sheets, all render exits0.
  Pencil/P1 images are identical because production u_grainCapacity is inactive.
  P2 changes other parameters; these sheets do not establish tooth capacity or S-tier quality.
- No device installation or phone performance/acceptance is included.

## Remaining engine boundaries

SelectController still needs Activity lifecycle/control routing and a real SelectionHost.
Count scrubber is assigned separately to UX; Lock/Group/Invert and pen/finger acceptance
remain open. All-layer edits need explicit layer/cel addresses: record IDs are not global.
Reuse scoped UndoLog.LineChange; engine Undo/Redo still needs to apply Step.lines.
The minimal coherent selection migration carries LineAddress(layerId, nullablecelId, recordId)
from picker candidates through state/flags, hover/tips, lift/delete/bake and addressed edit pairs.
Do not encode a composite ID into InkLine.id, synthesize global record IDs, or sort transformed
record lists before pairing them to their addresses. Add same-record-ID visible-cel tests for
cycling, flags/groups, transform/nudge/colour, delete/bake and duplicate destination/new seq.

JB-5.20d must capture actual raw samples/live seed, freeze immutable brush definitions,
compose ordered slabs and looks, and retain one-history Undo/save/recovery semantics before
enabling editable document capability. Newly tuned definitions cannot replace old frozen IDs.
The unbound GlWatercolourLineRenderer returns pooled targets before final stroke-opacity/blend
composition; its future caller must apply stroke opacity once. Imported/editable watercolor
replay needs an explicit policy for the6000-dab cap before accepting oversized records.
Preserve cosmetic-fade settlement before all snapshots, tile enumeration, layer/board changes,
new strokes and history operations. Periodic Tile watercolor and real device half-float/speed
acceptance remain open. Atlas A2–A6 remains separate from the published A1 foundation.
