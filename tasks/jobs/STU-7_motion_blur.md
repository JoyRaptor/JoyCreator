# STU-7: Motion blur in Studio (items, puppets, effect layers, filmed video)

**Milestone:** after launch (FUTURE_PLANS S14), started early on the owner's word 2026-11-22  ·  **State:** 🔨  ·  **Owner:** Codex builds, Claude (Opus 5.5) manages and reviews  ·  **Checked:** 2026-11-22 against `37debfa0e`

## Why it matters (for the user)
Fast moves should look filmed, not stuttery: a zooming title, a swinging puppet arm or a whip pan gets
the smear a real camera would give it. It must run on the Note 9 and look as good as ReelSmart.

## The plan
**[tasks/motionblur/MOTION_BLUR_PLAN_merged_20261122.md](../motionblur/MOTION_BLUR_PLAN_merged_20261122.md)
is the spec.** The two source plans sit beside it, for reasons only. Section numbers below (§) are the
merged plan's.

## Resume here (whoever picks this up: read this block first, then the newest channel entry)
**Owner, 2026-11-23: Codex finishes ALL remaining sections (C2 landing through C7).** Claude is
standby. If Codex runs out of usage, Claude continues from this block with no loss. Codex keeps
this block true at every landmark; it is the hand-over.
- **Active step:** C4b LaneCompositor export, debug-only route.
- **Code branch / head:** `codex/motionblur-20261122` @ `006960448` (pushed).
- **Last landed on `joy-creator`:** C3 `add742305` and C2 `665409126`. C4a `LanePlan`, C6 flow core and `tools/motionref` also landed.
- **Exact next action:** Run the legacy grouped, ordered same-order, and ordered picture-below-effect fixtures on the exact raw-capture APK, then compare the three pairs of matched raw frames.
- **Open owner asks:** Look #1. Review the controls and matched frames in the local bundle at `C:/workspace/motion-c2-evidence/C3_OWNER_REVIEW.zip`, and flag any default that feels wrong.
- **Evidence outside the repo:** `C:/Temp/motionref-evidence-20261122/` (owner slow-mo pairs).

### No-loss rules (binding while Codex runs the job)
1. **Landmark = commit + push + a channel entry + this block updated**, in that order, every time.
   A landmark is any of these:
   - a sub-step done (C2 landed, C3 pictures, C3 sprites, C4b, …);
   - a proof run (cloud CI, a Note 9 check);
   - a design decision;
   - a blocker.
2. **Push work in progress at least every 45 minutes of work**, even unfinished
   (`WIP: <what>, builds/does not build`), so nothing lives only on this PC.
3. **This block names one exact next action.** "Continue C4" is not an action; "write
   `LaneCompositor.drawEntry` for TEXT using `TextRasterCache`, then run `run-lane`" is.
4. **Before any long or risky step** (a large refactor, a phone session, a full export
   corpus), post a channel entry saying what you are about to do and what state you leave if cut
   off.
5. **Evidence:** receipts in `tasks/motionblur/*_REPORT.md`; raw media outside the repo (D: when
   plugged in, otherwise `C:/Temp/motionblur-evidence-*`), with the path in the report.
6. **Owner looks** (§9 steps 2 and 6): build the comparison page, post the ask in the channel and
   in this block's "Open owner asks", and continue with the next step that does not depend on his
   answer. Never sit waiting.
7. **Decisions:** the spec, the C4 design and the C6 GPU plan are approved. Where code disagrees
   with them, decide, record "decided: … because …" in the channel, and carry on. Claude reviews
   afterwards.

## How Claude and Codex share this job
- **Talk in [tasks/motionblur/CHANNEL.md](../motionblur/CHANNEL.md)**, and nowhere else. Read the
  whole thing before starting a session. Append an entry at every checkpoint, question or stop.
- **"Where it stands" below is the pick-up point.** Whoever stops last leaves it true, so the other
  can carry on without asking.
- **Code:** Codex works on `codex/motionblur-20261122` (worktree `C:/Temp/codex-motionblur-20261122`).
- **Channel, this card and the roadmap row** go to `joy-creator` straight away as docs-only commits
  (see the channel's "How to post"), so both sides always read the same page.
- **Landing on `joy-creator`.** Codex may land its own work when all of these hold:
  1. Motion blur is behind its switch and **off by default**, so the app looks and behaves exactly
     as before.
  2. The cloud tests are green: `studio` plus the new motion suites.
  3. Each new test was shown failing on the wrong behaviour first.
  4. A channel entry lists the commits and the proof.

  Anything that changes behaviour **with the switch off** waits for Claude's "go" in the channel. That
  includes the effect-layer render-order fix, `TimeMap` changes used elsewhere, and export routing.
  If Claude has not answered, Codex keeps building the next step on its branch and does not land the
  change.

## Done when
- [ ] Steps 0–6 of §9 landed, each with the proof §8 asks for.
- [ ] Note 9 gates (§8 table) met on **both** variants. Development runs on the Snapdragon Note 9.
      Until an Exynos Note 9 passes, nothing claims full Note 9 support.
- [ ] 20-shot blind A/B ≥ 80 % comparable or better; zero severe cut or alpha failures.
- [ ] The owner has looked twice (§9 steps 2 and 6) and approved the defaults.
- [ ] STATE.md row (proof level, entry point, help topic), help topic and Joybot tip written.

## Where it stands
- 2026-11-22: C1 landed at `1205b80ce` with 62/62 GitHub Studio suites, a Snapdragon
  Note 9 seek and export proof, and the GPU calibration. [C1 evidence](../motionblur/C1_ROUTE_REPORT.md).
  Keep the observed paused-preview PiP gap as a C3/C4 parity case.
- 2026-11-22: C2 started on `codex/motionblur-20261122` at `d6be0294c`. Inverse sampling
  math and scissor coverage tests pass.
- 2026-11-22: The C2 branch has opt-in SDR main-clip placement blur and a single-clip
  trim-path and linear-light controls verified on Snapdragon Note 9. See [C2 spine checkpoint](../motionblur/C2_SPINE_EXPORT_REPORT.md).
- 2026-11-22: Plain picture overlay blur is verified on the Snapdragon Note 9 at `9abf49e33`.
  [Picture export evidence](../motionblur/C2_PICTURE_EXPORT_REPORT.md).
- 2026-11-22: Sprite cells and plain titles verified in SDR export.
  [Sprite/title evidence](../motionblur/C2_SPRITE_TITLE_EXPORT_REPORT.md).
- 2026-11-22: Ordinary PiP placement blur verified on the Snapdragon Note 9 at `8d94fc4ac`.
  [PiP export evidence](../motionblur/C2_PIP_EXPORT_REPORT.md).
- 2026-11-22: Verified the 60 fps shutter after a 2× retime and rechecked all five export routes.
  [Cadence evidence](../motionblur/C2_CADENCE_EXPORT_REPORT.md).
- 2026-11-22: Verified saved shutter settings, Trailing and Mix in export, and per-item controls with Undo and Redo on the Note 9.
  [Shutter settings and controls evidence](../motionblur/C2_SHUTTER_CONTROLS_REPORT.md).
- 2026-11-22: The switch and Inspect Frame are verified on the Snapdragon Note 9.
  C2 is implemented and tested on `codex/motionblur-20261122` at `7f34e7737`.
  Landed as `665409126` after the tool was hidden and the gate was verified on the Note 9.
- 2026-11-22: Claude reviewed C2: hide the tool behind LaunchGate `studio_motion_blur` until C3 parity, then Codex lands it (channel).
- 2026-11-22: Claude wrote the [C4 design](../motionblur/C4_COMPOSITOR_DESIGN.md) (Claude's go for C4, with gates).
  Claude also landed the C6 flow core at `a7d01ad7f` (pure Java, not called by the app yet; `run-motionflow`, 63/63 Studio CI,
  APK build green), plus the [GPU port plan](../motionblur/C6_FLOW_GPU_PLAN.md).
- 2026-11-22: Claude landed C4a `LanePlan` and the footage reference tool `tools/motionref` at `f4e80974e`
  (64/64 Studio CI). C4 design revised: old projects keep the old route unless `legacyLooksTheSame()`.

## What is left (the queue; Codex works down it unless the channel says otherwise)
- [x] **C1 = §9 step 0: route proof, bench and shutter core.** Brief: [tasks/motionblur/CODEX_C1_brief.md](../motionblur/CODEX_C1_brief.md).
- [x] **C2 = §9 step 1:** rigid items (sprite, picture, text, PiP, spine clip) blurred exactly in
      export. Includes the per-item tick box and the switch, the shutter sheet with presets, and the
      accumulation reference. Brief written by Claude after C1's review; if Claude is unavailable,
      Codex drafts it in the channel from §5 and §9.
- [x] **C3 = §9 step 2:** rigid preview landed gated at `add742305`. [Snapdragon timings](../motionblur/C3_TIMING_REPORT.md).
- **C3 and C4 finish the stalled "everything on the GPU" work. Do not build a motion-only shortcut.**
  - **What is stalled:** the 09-23 export-speed roadmap's stage 2 was "one GPU pass per frame, a
    single compositor like the preview's" (`archive/ROADMAP_20261015.md:79`). Stage 2a is built
    (`db486113`) but its device test is owed. STU-5 lists it as "not built"; its 11-12 profile says
    Canvas blits and GL draws dominate export.
  - **Today:** sprites, text and avatars are still drawn on a Canvas and uploaded as pictures, in
    export (`CompositeExportOverlay`, `SpriteBlendGlEffect`). In the preview they are Android Views
    above the GL surface.
  - **The job:** motion blur needs those items drawn by GL in lane order, in preview and export
    alike. So C3/C4 build that one shared item compositor. It is also the export-speed stage 2 and
    the adjustment-layer render-order fix.
  - **Gate:** Claude's go is needed before landing, because it changes behaviour with the switch
    off. C1's route report must say what 2a already built.
  - **Before exposing the Motion Blur tool:** demonstrate preview/export parity, explain unsupported items
    in the sheet instead of showing a silent tick box, and apply the house tick-box and hover-label rules.
- [ ] **Owner look #1:** a mix-and-match page (presets, Mix, exposure shapes) plus a phone session.
      Claude, or Codex if Claude is out.
- [ ] **C4 = §9 step 3:** C4a ✅ (`LanePlan`). Next C4b-f per [C4_COMPOSITOR_DESIGN.md](../motionblur/C4_COMPOSITOR_DESIGN.md) (Claude's go,
      gated by its §6 equivalence corpus).
- [ ] **C5 = §9 step 4:** puppets and avatars.
- [ ] **C6 = §9 step 5:** filmed video. **Done:** the Java oracle (`FlowField`, `LumaImage`, `PyramidFlowEstimator`,
      `FlowConfidence`, `GlobalMotionFit`, `SourceFrameRing`). **Left:** the GPU port per
      [C6_FLOW_GPU_PLAN.md](../motionblur/C6_FLOW_GPU_PLAN.md), the high-speed reference shoot, the OpenCV DIS bake-off,
      the live and export wiring, the analyser and the cache.
- [ ] **C7 = §9 step 6:** finish, A/B, help, Joybot, STATE. **Owner look #2.**

## Files and traps
- Main areas: new `studiokit/.../motion/**` and `app/.../motion/**`, plus hooks in `FxEffectDef`,
  `FxCompiler`, `FxPanel`, `TimeMap`, `KeyframeTrack`, `FxPreviewTextureView`,
  `FxLivePreviewController`, `ExportManager`, `AdjustmentLayerGlEffect`, `SpriteFrameResolver`,
  `PuppetPoseResolver`. Check the BOARD before touching a hook file; on 11-22 nobody held them.
- The preview context is ES 2 and shaders are `#version 100`. Note 9 rule: small programs, never one
  big program with uniform switches.
- Preview and export must agree (AGENT_START rule 5).
- C: had 26 GB free on 11-22; put big evidence on D:.
