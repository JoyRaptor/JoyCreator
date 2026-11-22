# Codex brief C1: motion blur route proof, bench and shutter core (STU-7, §9 step 0)

**From:** Claude (Opus 5.5), STU-7 manager, 2026-11-22. **Base:** `origin/joy-creator` at or after `37debfa0e`.

## Read first, in this order
1. `AGENTS.md`, then `tasks/AGENT_START.md`. These rules are binding: worktree only, the
   owner is `JoyRaptor`, `phone.ps1` for every device change, cloud build and test.
2. [STU-7 card](../jobs/STU-7_motion_blur.md): the sharing and landing rules.
3. [CHANNEL.md](CHANNEL.md): all of it.
4. [The spec](MOTION_BLUR_PLAN_merged_20261122.md), §1, §2, §4, §8 and §9 in full. Skim the rest.

## Set up
```
git -C "C:/+Projects/JoyCreator" fetch origin
git -C "C:/+Projects/JoyCreator" worktree add C:/Temp/codex-motionblur-20261122 -b codex/motionblur-20261122 origin/joy-creator
```
Add one BOARD claim row (Live claims) through the docs worktree described in the channel:
`Codex motion blur (owner-assigned 11-22) | STU-7 C1 | codex/motionblur-20261122 | new motion/** files; additive KeyframeTrack overload`.

## Goal of C1
Lay the ground every later step stands on, and **change nothing the user can see**. C1 has five
parts.

### 1. Inventory before building
Inventory first, as the owner requires. Read the code and record it with `file:line` in part 5's
report.
- `FxEffectDef` / `FxCompiler.plan` / `AdjustmentLayerGlEffect`: how a new capability could get a
  pass graph of its own.
- `TimeMap`: cuts, seams and transitions.
- `KeyframeTrack.valueAt` and every `animated*At` function on `SpriteOverlayItem`, `TextOverlayItem`,
  PiP (`getOverlayTransform`) and `Clip.spinePoseAt`, including `SpaceLink` parents.
- `LaunchGate` / `hidden_features.json`, or any other existing feature-switch mechanism: pick
  one for motion blur and say why.
- VisLab's `GpuTimer` and TextLab's `TlGpuTimer`: reuse them for the bench; do not write a third.
- `DecodedFrameCursor` / `SurfaceFrameReader`: can export see the neighbouring frames?

### 2. Shutter core: pure Java in `studiokit/.../motion/`
Small, single-purpose classes (owner rule).
- **`MotionShutter`:**
  - The frame period is rational, so 23.976 and 29.97 are exact.
  - Angle 0–720 and phase.
  - Presets: Natural 180° centred, Crisp 90°, Heavy 270°, Trailing 180° ending at t.
  - Timing modes: Centred, Trailing, Leading, Custom.
  - N midpoint strata, with normalised weights and a Box/Soft shape.
  - Sample times in **microseconds**.
  - A single sample is exactly t.
- **Sub-millisecond time on `KeyframeTrack`:** an additive overload `valueAt(double timeMs, …)`. At
  whole-millisecond inputs it must return **exactly** what the existing `long` version returns:
  test every easing on random tracks. Do not change the `long` version.
- **`MotionBreaks`:** builds the list of discontinuities over a window. The sources are `TimeMap`
  (cuts, trims, each side of a transition) and the items themselves (step keyframes, sprite cell
  changes through `SpriteFrameResolver.resolveCellAt`, loop wraps). Expose:
  - a way for producers to add their own breaks;
  - a `splitWindow` that returns the valid sub-window around t, with renormalised weights and a
    centre fallback.
- **`ItemMotionSampler`:** gives an item's full affine (pivot, rotation, scale, x/y, parent chain) at
  a µs time, for sprite, text, image, PiP and the spine clip. Wrap the existing functions; never
  copy their maths. Prove the identity against the existing functions at whole ms.
- **`SweptBounds`:** a conservative screen rectangle covering an item over the window, plus a halo.
- **`VelocityPack`:** packs 12+12-bit velocity plus 8-bit order into RGBA8, ±128 px at 1/16 px.
  Round-trip tests at the edges, including clamping.

### 3. Ground-truth renderer, in the JVM harness
A CPU brute-force accumulator: a textured, alpha-premultiplied quad under an `ItemMotionSampler`
track, 128 to 256 samples, linear-light averaging. It writes a PNG and coverage/alpha metrics. Cover
the fixture scenes C2 will need:
- a slide, fast then slow
- a logo zoom 1×→3× about a pivot
- a spin about the pivot
- a parent-and-child move
- a step-key teleport (a break, so no smear)
- a still item (bit-exact)

Add a convergence check (128 vs 256 samples under a threshold). Each scene's test is **shown failing
on a deliberately wrong renderer first**, for example one that ignores rotation, smears across a
break, or rounds time to ms. Log that failure in the report (owner rule: break tests fail first).

### 4. MotionBench, Note 9
Build it as a debug-only screen, in the manner of `DecoderBudgetProbeActivity` / `JoyBrushBenchActivity`.
- **What it reports:** GPU timer-query availability, the renderer string and the driver version.
- **Baseline:**
  - Studio's current preview with blur off, on a 720p surface, over 60 s: presented-frame intervals
    P50/P95/P99.
  - A calibration pass: a 12-tap textured gather over a 720p quad, the cost the C2 shader will pay.
- **Temperature:** logged every 30 s (owner rule).
- **Running it:**
  - Claim the Note 9 with `phone.ps1`.
  - Press Home first if JoyBrush is in front.
  - Never uninstall or clear data.
  - Launch with `am start` through `phone.ps1`, never `monkey`.
  - Never leave `setprop` profiling flags set.
  - Release the phone when done.
- **Devices:** the Exynos Note 9 is not here, so mark it "owed".

### 5. Route report: `tasks/motionblur/C1_ROUTE_REPORT.md`
Trace one fixture project through the live preview and through the real export path, with
`file:line`. The fixture is a zooming title, a moving sprite, a hard cut, a half-transparent moving
picture and an adjustment layer over a PiP. Answer:
- Where a per-item MOTION stage sits, in preview and in export.
- How a moving item gets promoted into GL in the preview (the below-a-blend path).
- Whether export can hold the neighbouring decoded frames, and at what cost.
- Exactly what the effect-layer render-order fix (C4) must change.
- **The stalled GPU compositor.** The export-speed stage 2, "one GPU pass per frame"
  (`tasks/archive/ROADMAP_20261015.md:79`; 2a built in `db486113`, device test owed; STU-5 "Export
  speed" row), is still open. Answer:
  - What did 2a actually build, and is it still live in `ExportManager`?
  - What still goes Canvas → picture → upload? Sprites, text and avatars, through
    `CompositeExportOverlay` and `SpriteBlendGlEffect`.
  - What would one GL item compositor, shared by preview and export and drawing items in lane
    order, have to replace?

  C3/C4 will build that compositor rather than a motion-blur-only path (see the card).

Then add the bench numbers and the inventory from part 1. End with a proposed C2 file list.

## Done when
- [ ] Parts 2 and 3 are covered by a new suite: `tools/jvm-harness/run-motion.sh`, added to
      `run-studio-all.sh` so the cloud `studio` suite runs it. Green in the cloud, each test with its
      fail-first record.
- [ ] `KeyframeTrack` overload identity tests are green, and the existing `timemap`/`fx`/keyframe
      suites are still green.
- [ ] The bench has run on the Note 9, with numbers in the report. Nothing about the app changes
      for a user.
- [ ] Pushed. Landed on `joy-creator` under the card's rules: C1 only adds code, so it may land.
      Then `git ls-remote` confirms it.
- [ ] A channel entry with commits, proof, what was not checked, your questions and "Next".
- [ ] The card's "Where it stands" and the roadmap row's "Checked" date are updated. The BOARD claim
      is kept if you go on to C2, otherwise removed.

## Not in C1
No blur shaders, no UI, no change to preview or export behaviour, no STATE.md row (nothing a user can
see yet).

## Then
If Claude has not posted a C2 brief, draft your own C2 plan in the channel from §5 and §9 step 1, and
start it on your branch. Under the card's rules it may land once it is behind the switch and off by
default.
