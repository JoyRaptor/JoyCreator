# STU-7: Motion blur in Studio (items, puppets, effect layers, filmed video)

**Milestone:** after launch (FUTURE_PLANS S14), started early on the owner's word 2026-11-22  ·  **State:** 🔨  ·  **Owner:** Codex builds, Claude (Opus 5.5) manages and reviews  ·  **Checked:** 2026-11-22 against `37debfa0e`

## Why it matters (for the user)
Fast moves should look filmed, not stuttery: a zooming title, a swinging puppet arm or a whip pan gets
the smear a real camera would give it. It must run on the Note 9 and look as good as ReelSmart.

## The plan
**[tasks/motionblur/MOTION_BLUR_PLAN_merged_20261122.md](../motionblur/MOTION_BLUR_PLAN_merged_20261122.md)
is the spec.** The two source plans sit beside it, for reasons only. Section numbers below (§) are the
merged plan's.

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
- 2026-11-22: plans compared and merged by Claude. **Nothing built.** Codex starts at step C1 below.

## What is left (the queue; Codex works down it unless the channel says otherwise)
- [ ] **C1 = §9 step 0: route proof, bench and shutter core.** Brief: [tasks/motionblur/CODEX_C1_brief.md](../motionblur/CODEX_C1_brief.md).
- [ ] **C2 = §9 step 1:** rigid items (sprite, picture, text, PiP, spine clip) blurred exactly in
      export. Includes the per-item tick box and the switch, the shutter sheet with presets, and the
      accumulation reference. Brief written by Claude after C1's review; if Claude is unavailable,
      Codex drafts it in the channel from §5 and §9.
- [ ] **C3 = §9 step 2:** the same in the preview (moving items drawn by GL), with bench lines.
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
- [ ] **Owner look #1:** a mix-and-match page (presets, Mix, exposure shapes) plus a phone session.
      Claude, or Codex if Claude is out.
- [ ] **C4 = §9 step 3:** effect-layer render order (needs Claude's go), then the `MOTION` capability
      and Exact per-sample scope export.
- [ ] **C5 = §9 step 4:** puppets and avatars.
- [ ] **C6 = §9 step 5:** filmed video (LiveFlow, estimator bake-off with OpenCV DIS, analyser,
      cache).
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
