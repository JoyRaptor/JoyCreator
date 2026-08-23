# LANES.md — live file-lock board for parallel agents
#
# ★ CURRENT PRIORITY TRACK: Layers/Timeline UX overhaul BUILD (design DONE 2026-07-06).
#   New session? Start with tasks/BOOTSTRAP_LAYERS_BUILD_20260706.md (paste-ready prompt).
#   Design contract = tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md; slices = tasks/PLAN_LAYERS_UX_EXECUTION.md.
#   Model: OPUS high effort. Begin at Slice A (caption/visualizer Track kinds).

Two agents may work this repo at the same time (Fable/Claude + opencode). This file is
how they avoid clobbering each other. Protocol — no exceptions:

1. **Before EVERY task**, read this file fresh. If any file your task touches appears in
   the OTHER agent's `files:` list while its `status:` is ACTIVE, **SKIP that task**
   (note "skipped: lane conflict" in your progress log) and take the next task whose
   files are free. Come back to skipped tasks later.
2. **Before your first edit of a task**, update YOUR section: set `status: ACTIVE`,
   the date/time, and the exact files you will touch. Save this file.
3. **After the task's commit** (or when you abandon it), set your section back to
   `status: IDLE` and clear the files list. Never leave a stale ACTIVE claim when you
   stop working — a dead claim blocks the other agent for hours.
4. This file is coordination state, not history — keep each section tiny, overwrite it
   freely, and DO NOT commit it with unrelated changes (committing it is optional;
   the on-disk state is what matters).
5. `git status` is the fallback truth: if the other agent's claimed files are dirty in
   the tree, treat the claim as live even if the timestamp is old.
6. **SOLE BUILDER: never run gradle/gradlew.** The user's watcher is the ONE builder.
   Compile-verify by SAVING and reading `build.log` for a fresh `BUILD SUCCESSFUL`.
   Two gradle processes corrupt the resource merge (missing `.flat` → "100 class R
   errors"). If `build.log` won't update, STOP and tell the user — never invoke gradle.
7. **DEVICE is single-owner.** Before ANY device command (`am start`, `input`,
   `screencap`, `screenrecord`, `logcat`), read the `DEVICE:` token below. Take it only
   when it reads `free` (or the holder is IDLE): set `DEVICE: <you>`, do your batch,
   then set it back to `free`. Never drive the device while the other agent holds it.

## SPEC TOKEN  (added 2026-08-22 — the second single-writer resource)

`tasks/SPEC_AUDIO_UX_V1.md` is edited by EVERY row (§7 status cells), so parallel agents
collide on it even when their SOURCE files are disjoint. Same rule as DEVICE, but held for
seconds not minutes: take it only to write your row, then release. Never hold it while you
code. If it is taken, finish your code, wait, then update the row.

SPEC: free

## DEVICE TOKEN
DEVICE: LANE C (opencode agent, E4 export batch) (Fable released 2026-07-12 ~05:15, usage-capped session end. BATCH RESULTS:
  ✓ REPLAY-IK/bake replay: injected 2s yaw sweep drives the puppet in live playback —
    continuous motion + discrete cell swap (yellow→red at extreme) + pin-warp bend all
    caught on screenshots. control2 (cebc19e0) RESTORED byte-exact (md5 a522728a…),
    CAMERA re-granted.
  ✓ Standalone Avatar Studio: person icon on Faditor tab → library chooser (A6 Warp
    Smoke listed = Library ⇪ round-trip proven) → opens library-backed, warp renders
    from bundle sheets. "+ New avatar from image…" UNTESTED (needs picker flow).
  ✗ 313e7fa export fix FAILED device verify → muxer stall REPRODUCED (AudioExportVerify
    seam-2 @600ms; error log 20260712_050701). ROOT CAUSE FOUND + FIXED (148c155
    audio-coverage guard, build-green): residual window past source AUDIO end = zero
    audio samples = AudioGraph stall. DEVICE RE-VERIFY OWED: aeb0517e is LEFT AT the
    600ms repro state — just re-run the export; expect completion now.
  Partial: 🎯 Record synthetic take STARTS (toast proven) but the stop-tap keep-swap
    didn't persist (disk unchanged; likely stop-on-onPause discard when I exited) —
    needs one hand-run: record ~3s, tap Stop, check ✦ badge grows, undo.
  Blocked on JoyRaptor: bubble face-button + clear-stage (needs "Display over other apps"
    grant), real-face axis checks (MIRROR_YAW/SIGN_PITCH), new-from-image picker.)
prior: free (Fable 2026-07-12: replay-IK verify DEFERRED to the end-of-mission batch —
  the watcher reinstalls the APK on every subagent save, killing any interactive session
  mid-take. Batch item: pm revoke CAMERA → synthetic 🎯 Record on the control2 avatar item
  → replay must show the FABRIK pin-target orbit (tests the AvatarItemPuppet fix) → undo
  take → pm grant CAMERA.)
prior: free (Fable released 2026-07-11 ~22:05 after the point-at-video no-face smoke on
  SM-N960U — PASS: 🎬 From video chip on the control2 avatar item → progress dialog →
  completion toast with the take KEPT (cat clip, no human face; ✦21 samples + undo count 0
  unchanged), and a second run cancelled mid-sweep dismissed cleanly. Zero crashes. Device
  left at HOME; control2 state note for JoyRaptor unchanged below. Face-bearing verify owed.)
prior: free (Fable released 2026-07-11 ~20:50 after the avatar verify batch — results in
  the FABLE lane block below. NOTE for JoyRaptor: P0 control2 project cebc19e0 was used as the
  avatar test bed and now carries (a) a stray "Enter text" overlay from a mis-tap, (b) an
  inserted "A6 Warp Smoke" avatar item WITH an injected 2s yaw-sweep performance (good for
  feel-testing replay), (c) real art pushed into its assets for the previously-missing
  "Sprite" sheet. project.json.bak from Jul 7 still on-device if pristine matters.)
prior: free (Fable released 2026-07-11 ~01:10 after full verify batch: AV3 layout, W2 zoom,
  smoke i/k/l, G5a attach+detach, GL More-effects — ALL PASS. Sandbox bdd51919 restored pristine
  (md5 e81a6df8…), undo_history cleared. See handoff 2026-07-11 block.)
(Fable owns it during hard interactive device-loops; opencode does code+compile-only
and BATCHES its device-verify into windows when this reads `free` / Fable is IDLE.)

**Standing (permanent) Fable locks — never touch regardless of this board:**
`export/ExportManager.java`, `export/BlendModeGlEffect.java`, `export/PipFrameOverlay.java`,
`export/CompositeExportOverlay.java`, `project/ProjectStorage.java`,
`compositor/MasterPlaybackEngine.java`, `compositor/OverlayVideoPreviewView.java`,
`compositor/LayerPreviewController.java`, `compositor/DecoderBudgetProbeActivity.java`,
`avatar/PuppetPoseResolver.java`, `avatar/AvatarRig.java`, `avatar/PinWarpStrip.java`,
`avatar/DangleSim.java`, `avatar/PuppetPreviewView.java`, `avatar/FabrikSolver.java`.

---

## FABLE (Claude) — dynamic lane
status: IDLE (claim above was STALE from 2026-07-14 and listed FaditorEditorActivity.java,
        which LANE A holds today. Cleared by review 2026-08-23 per rule 5 — every file it named
        was clean in the tree. History preserved below as prior-status.)
files: (none)
since: 2026-08-23

## REVIEW (Claude Opus 5) — integration lane
status: IDLE (2026-08-23 14:00 — B2.U3 picked up by LANE A (host undo + inspector) per row queue; files released)
files: (none — released)
since: 2026-08-23 14:00

prior-status: ACTIVE (2026-07-14 — Opus-4.8 autonomous device-verify run, device SANDBOX_SERIAL REPLUGGED,
        holds DEVICE token. This session's PASSES (see DEVICE_VERIFY_QUEUE_20260712.md): A1 export
        re-verify BOTH paths (video @ aeb0517e repro + audio-only .m4a @ cebc19e0, 66.88kB output) —
        confirms 313e7fa+148c155; bonus da96248 audio-copy verified on-device; A5 AV4 waveform settings
        (opens populated, ANALYSIS toggle persists across sheet reopen, default restored); A4 grade
        presets (save→named chip→persist→apply→long-press-delete, cebc19e0 restored). NEXT (device):
        A2 G5b piggyback-looks A/B frame-diff proof; A6 record stop-swap; A7 clip-audio drawer feels;
        A3a SAF style round-trip; AV4 first-import eager/lazy chooser. NEXT (JoyRaptor): 4 de-politicize
        scope calls; bookmarks/time-chip. NEXT (solo build): clip-audio drawer→PiP, H.264-baseline
        encoder hook, TODO(strings). opencode's lane files were committed as 8332370, not modified.)
prior-status: ACTIVE (2026-07-12 — FINISH-THE-ROADMAP mission, JoyRaptor go-ahead, two Fable-directed
        subagents (Opus+Sonnet, waves). Wave 1: Opus → A5 AI rigging + FF-B AI sprite tools
        (ai/*, new avatar template file, sprite AI glue); Sonnet → A3 spectral visemes
        (avatar/TrackingParamPipeline + new analyzer, dependency-free FFT). Fable → A6
        pin-authoring UI + FABRIK→posed-pins hookup (standing-locked avatar files), review +
        commit. Later waves: S7 relink, FF-A dope-sheet, S2b polish, AV4 wire-up, AV5 perf,
        dead-code cleanup. opencode's grade-presets files UNTOUCHED (inline literals only).)
files: avatar/* (locked ones Fable-only), ai/*, sprite/*, FaditorEditorActivity.java,
        tools/jvm-harness/*, tasks/* — EXCEPT FilterBottomSheet.java,
        effects/GradePresetStore.java, res/values/strings.xml (opencode)
since: 2026-07-12
WAVE (2026-07-12 ~05:00, Fable-directed): Opus sub → FF-A wiring (SpritePresetStamper →
        dope-sheet preset chips; FaditorEditorActivity + sprite/*) then G5 fast-follows.
        Sonnet sub → perf quickwins in NON-editor files only (AssetScanner, audio extract
        pcmToFloat, photo glReadPixels, I-frame default, docs/project-schema.md regen);
        any FaditorEditorActivity site is DEFERRED to the report, not edited.
        Fable → holds DEVICE token, tolerant smokes continue between watcher reinstalls;
        take-critical replay-IK item DONE (see token block).
prior: IDLE (2026-07-11 ~22:05 — POINT-AT-VIDEO CODE-COMPLETE + NO-FACE DEVICE-SMOKED,
        all committed, tree green. Two always-green commits:
        • db1c1fb sweep core: VideoFaceSweeper (fresh VIDEO-mode FaceLandmarker, sync
          detectForVideo, MMR ~12.5fps/OPTION_CLOSEST/~320px, one retriever per clip,
          cancel + progress, no-face adds NOTHING) + SweepTimeMapper (pure timeline→clip
          walk mirroring Timeline.segmentStartMs; the clip-local→source hop is the EXISTING
          Clip.mapToSourceMs — loop reps included, not re-derived). Every frame maps through
          MediaPipeTrackingSource.resultToParams — one param vocabulary/axis knob with live.
          Harness SweepTimeMapperTest 19/19 GREEN.
        • c6ef7cf UI: "🎬 From video" chip next to 🎯 Record (inline literals), activity
          handler gated on model-present (NO synthetic fallback), progress dialog + cancel,
          item-delete cancels, whole-take swap = ONE undo step, empty keeps prior take.
          Replay/export ride AvatarItemPuppet untouched → no A/B proof owed.
        DEVICE SMOKE (SM-N960U): no-face sweep completes + take kept + cancel clean, zero
        crashes (see DEVICE token note). OWED to JoyRaptor: face-bearing clip verify (Task 2d)
        incl. video-vs-front-cam MIRROR_YAW feel; rest of the owed device batch per
        BOOTSTRAP_POINTAT_20260712 Task 2. NEXT: A5 AI rigging (design pass first —
        don't start at a session tail). opencode's grade-presets files UNTOUCHED.)
files: (none — released)
since: 2026-07-11 ~22:05
prior: IDLE (2026-07-11 ~21:05 — point-at-video PREP landed: ae9dc61 extracts static
        putHeadPose + resultToParams(FaceLandmarkerResult) in MediaPipeTrackingSource, so
        the VIDEO-mode sweep maps frames through the SAME code as live tracking (one axis
        knob, one param vocabulary). No behavior change; build-green. The sweep itself is
        the next session's first task: VIDEO-mode FaceLandmarker over a clip's frames →
        resultToParams → track.add(frameMs, params) → item.setAvatarTrack.)
files: (none — released)
since: 2026-07-11 ~21:05
prior: IDLE (2026-07-11 ~20:50 — BAKE-TO-KEYFRAMES COMPLETE + DEVICE-VERIFIED, all committed,
        tree green. Four always-green commits:
        • 41908c5 storage: avatarRigId + AvatarParamTrack ride the placed sprite item
          (additive/tolerant-read in ProjectStorage; insert stamps the linkage).
        • 4b10fb1 replay render: AvatarItemPuppet (offscreen PuppetPreviewView, fresh
          DiscreteState, rewind reset, MEDIA-clock crossfade/dangle determinism) wired into
          SpriteOverlayView preview AND CompositeExportOverlay export (rigs via ExportManager).
        • 685e056 record: 🎯 Record chip on avatar items in the sprite palette — studio
          tracking-mount pattern, rolls playback, samples bus at item-local playhead ms,
          stop on tap/item-end/delete/onPause, one-undo-step whole-take swap.
        • 9158cb1 harness: ReplayMappingTest 19/19 GREEN (time mapping + render gate).
        DEVICE VERIFY (SM-N960U, build 20:13:07) — PASS: standalone studio entry (person
        icon → library chooser), studio opens A6 Warp Smoke + pin-warp renders real art,
        🎯 Track mounts REAL MediaPipe face source (camera+model present; no face on desk →
        correct freeze/loss hold), Library ⇪ publishes a complete bundle (avatar.json +
        sheet bytes), editor ⇓ Insert bakes avatar-a6-smoke-neutral.png + places item with
        avatarRigId persisted, 🎯 Record chip mounts camera + rolls + empty-take restore
        toast, REPLAY: injected 2s yaw-sweep track renders live puppet in preview at
        distinct poses per time (incl. a discrete cell swap), EXPORT: same poses at the
        same times in the exported mp4 (t=0.5 absent before item start / t=2.0 S-bend
        green-tip / t=2.6 chevron) = preview==export proven, webcam never ran at export.
        NOT RUN (blocked/needs-JoyRaptor): axis-inversion feel (needs a real face in front of
        the camera), recorder bubble face-button + clear-stage (blocked on ungran­ted
        "Display over other apps" — system permission, user must grant), record-with-face.
        NEXT: point-at-video (VIDEO-mode FaceLandmarker sweep → track; extract putHeadPose,
        don't copy) → A5 AI rigging. opencode's grade-presets files UNTOUCHED.)
files: (none — released)
since: 2026-07-11 ~20:50
prior-claim: ACTIVE (2026-07-11 ~20:15 — BAKE-TO-KEYFRAMES session per BOOTSTRAP_BAKE_20260711.)
prior: IDLE (2026-07-11 ~19:45 — JOY CREATOR finish push, A4 PRODUCT LOOP COMPLETE, all
        committed, tree green (subagents were user-stopped early; Fable did all three inline):
        • c0c4020 A4 HEART: puppet RENDERS into the webcam bubble (camera single-owner:
          Camera2 preview never opens in avatar mode, MediaPipeTrackingSource owns the front
          cam, feeds ONLY the tracker; PuppetPreviewView cleanRender in the card, vsync pull
          loop) + CLEAR STAGE toggle (transparent card — only the puppet floats over the
          screen, JoyRaptor's manual-puppeteering request) + AvatarParamTrack bake foundation
          (ParamTrackTest 25/25 GREEN, new harness in tools/jvm-harness).
        • 7b4edb3 Avatar Studio STANDALONE mode (library-backed, no project; chooser +
          new-avatar-from-image) + main-menu entry (person icon, Faditor tab header).
          FIXED data-loss landmine: AvatarLibrary.save now temp-then-rename (was: delete
          bundle then copy sheets FROM the deleted bundle on save-over).
        • 16a712f editor avatar item: '⇓ Insert' entries in the avatar dialog → neutral-pose
          bake (AvatarNeutralBaker, offscreen PuppetPreviewView) → 1-cell sprite sheet →
          placeSpriteOnVideo (full sprite pipeline, zero new compositor surface). Rig+sheets
          import idempotently; avatar-<rigId8>-neutral.png carries the rig linkage.
        NEXT (ordered): #4 bake-to-keyframes (record face-tracked performance on a placed
        avatar item, live puppet render preview+export — heavy, locked-file surgery, wants a
        fresh session) → #5 point-at-video → #6 A5 AI rigging. DEVICE VERIFY OWED on ALL of
        today (batch checklist in the session task list #7): studio 🎯 track axes, Library ⇪,
        bubble face-button → puppet renders, clear-stage toggle, main-menu studio entry,
        standalone chooser + new-from-image, editor insert-from-library.)
files: (none — released. opencode's grade-presets files still live-untouched.)
since: 2026-07-11 ~19:45
prior: IDLE (2026-07-11 ~15:45 mission chunk 1 WRAPPED, all committed, tree green:
        bdb9be8 D4 MediaPipe face tracking (Opus sub, reviewed) — DEVICE VERIFY OWED,
        checklist in the D4 commit + PLAN_AVATAR_STUDIO; tuning knobs MIRROR_YAW/SIGN_PITCH.
        693a943 sprite S2b gap-close (Sonnet sub, reviewed) — most of S2b pre-existed, docs were stale.
        4860b8f AvatarLibrary cross-project bundles (Fable) — A4 foundation.
        6decf21 A4 slice 2 (Fable): studio "Library ⇪" publish chip + recorder avatar
        selector ("face" btn before Rotate on the webcam bubble, cycles library entries,
        persists avatarEntryDir, green tint). HONEST STUB: puppet does NOT render into the
        bubble yet — that's the next A4 slice (renderer replaces preview surface, camera
        feeds only the tracker, read avatarEntryDir from PREFS floating_webcam_prefs).
        NEXT (ordered): studio save-to-library btn → recorder avatar picker (A4) → editor avatar
        item → bake-to-keyframes → point-at-video → A5. Then FF-A/FF-B sprites, Slice E/F.)
files: (none — released. opencode's grade-presets files still live-untouched.)
prior-claim: ACTIVE (2026-07-11 ~15:15 — EXECUTION MISSION, JoyRaptor full go-ahead: MediaPipe UNGATED,
        avatar product loop (library→A4→bake→point-at-video→A5), sprite FF-A/FF-B + S2b,
        Slice E/F. JoyRaptor permits TWO Fable-directed subagents (one Opus, one Sonnet) this
        mission — scoped exception to the one-subagent rule, to save Fable credits.
        Opus sub → D4 MediaPipe TrackingSource adapter (avatar/* NEW files + app/build.gradle).
        Sonnet sub → sprite S2b setup-editor polish (sprite/* only).
        Fable → avatar library/sidecar groundwork + review/commit + docs.
        NO DEVICE connected this session — all work is build-green + hand-test checklists.
        opencode's uncommitted grade-presets files (FilterBottomSheet/strings/GradePresetStore)
        remain UNTOUCHED.)
files: avatar/* (new files; AvatarRig lock respected — additive only), app/build.gradle,
        sprite/SpriteSheetEditorActivity.java, sprite/SpriteGridEditorView.java,
        sprite/SpriteGridDetector.java, sprite/SpriteSheetRenderer.java, tasks/*
since: 2026-07-11 ~15:15
prior: (2026-07-11 autonomous push wrapped mid-flight; filmstrip T1 landed 78a6c6b. opencode's
        uncommitted grade-presets files (FilterBottomSheet/strings/GradePresetStore) are LIVE —
        nobody touches them.)
prior: IDLE (2026-07-11 continuation wrapped. This session committed + verified:
        • 4ff1707 audio layerId-clone + stale-selection fixes (both AUDIO#3 findings) — DEVICE-VERIFIED
        • 313e7fa export transition≥clip muxer-stall fix — build-green, DEVICE VERIFY OWED (see below)
        • fdad81f AV3 audio-row expand/collapse layout — DEVICE-VERIFIED
        • e7a863c AV4-groundwork (TapeWaveformStyle prefs + settings sheet, UNWIRED)
        • acaeace AV5 perf/cleanup PLAN doc
        Full device verify batch ALL PASS: AV3, W2 zoom, smoke i/k/l, G5a attach+detach, GL cards.
        NEXT: AV4 wire-up (settings sheet under toolbar Settings + first-import eager/lazy popup),
        then AV5 perf (tile-cache the tape draw) + dead-code removal. Also OWED device verify:
        313e7fa export fix (repro = AudioExportVerify aeb0517e seam-2 → 600ms, export both paths).)
files: (none — released; all committed)
since: 2026-07-11
prior-status: ACTIVE (2026-07-09 crunch, Opus. Landed + committed this session:
        • 9b37f99 audio-band clipping fix — DEVICE-VERIFIED (both audio rows fully visible even
          under a compressed view; was: bottom band clipped off-screen when view height-constrained).
        • 2593bdb GL-transition card baker (Opus subagent) — P0 NATIVE-ABORT CRASH FIXED
          (glGetShaderInfoLog(int) → invalid-UTF-8 → NewStringUTF CheckJNI abort, uncatchable).
          DEVICE-VERIFIED editor opens clean. Feature INERT: shaders fail headless compile on
          SM-N960U → cards keep proxy (no regression). Follow-up documented in class javadoc.
        • bca07d3 G9 link-engine design doc (Sonnet subagent, design only).
        NEXT: G5a attach/detach device verify (owed), then G9a plumbing if budget.)
files: (releasing timeline/layers files; may take FaditorEditorActivity for G5a verify only)
prior-status: IDLE (2026-07-07 continuation session wrapped). Landed + committed this session:
  • 5b492e0 G1-groundwork: held-item MOVE reaches hidden lanes (M6 vertical edge-scroll) +
    honest drop-target affordance. Drag-FEEL hand-test owed (see handoff checklist).
  • 45f6c52 Slice F PiP no-overlap → invariant COMPLETE all types (text/img/sprite/audio/PiP).
    Verified by a 14-case logic harness.
  • bf7b63e G6.1 resizable timeline (grab bar) — DEVICE-VERIFIED both directions + persistence.
  • dfe868c + 1564b2f DURABILITY (road_map Tier-1): extracted audio → getFilesDir (survives OS
    cache wipe) + load-path migration rescuing existing cache-path audio. DEVICE-VERIFIED.
  • e004e80 docs: preserved authoritative design contracts in git.
  NEXT SESSION (want JoyRaptor present for drag-FEEL iteration per the hand-test rule): the UX gesture
  state machine — G1 proper (hold/tap/double-tap) + G2 menu + G3 keyframes + G4 preview handles.
  These are coupled + FEEL-gated; building blind risks regressing the tap/scrub/pickup that work.
  Other safe road_map: audio old→unified-row consolidation, dead-code cleanup (drawLayers/
  selectedLayerKind — unblocked now Slice F landed), perf (MMR width/height memoize).
  --- prior ---
  SLICE F: F1 + F2 done, device-verified.
  F1 (98c5a76): Timeline.enforceNoOverlapTextLanes() — text can't overlap on a shared lane (smart
    packing, idempotent, runs at load). Verified: separated 2 overlaps, 0 remain, butted pair kept.
  F2 (97ec804): "Compact lanes" tool-row button (JoyRaptor chose tool-row). Timeline.compactOverlayLanes()
    greedy-packs ALL text+sprite into fewest no-overlap lanes; compactLayers() = one undo step +
    refreshAfterLaneChange() persists compact/undo/redo. Verified: text 5 lanes→3 (0 overlaps), toast,
    undo reverts, persists. PiP/image compaction deferred (locked subsystem, needs 2+PiP test project).
  SLICE F REMAINDER (breakdown):
    - No-overlap invariant status: TEXT ✓ (F1), SPRITE ✓ (T8 one-per-lane), AUDIO ✓. PiP/IMAGE
      (overlayClips) NOT yet — same pattern (enforceNoOverlapVideoLanes via getOverlayStartMs +
      getEffectiveDurationMs, "video-<id>" lane) BUT: PiP is a standing-locked complex subsystem and the
      test project has only 1 PiP clip → couldn't test multi-PiP overlap. Do with a 2+PiP test project.
    - COMPACT LANES (JoyRaptor's CapCut-pain headline want): a generic packing that merges ALL items of a
      type across lanes into the FEWEST no-overlap lanes (same greedy algo as F1 but across all items,
      not per-lane). Model method is easy; BLOCKED on a UI decision — WHERE does the "compact lanes"
      control live (tool-row button? row-gutter action? gesture?). Ask JoyRaptor before building the trigger.
    - MOVE-BETWEEN-LANES: the M10 cross-row drag machinery already exists + is generic
      (LayerGestureController resolveNoOverlapStart / onItemMovedToTrack / onItemDroppedOnNewLayer). Needs
      a device HAND-TEST that it works for text/image/sprite/PiP (drag gesture → one scripted attempt +
      numbered checklist per the rules), not more code (likely).
  --- earlier this session (all always-green + device-verified on SM-N960U): ---
  ✓ Slice E vertical re-layout (7e89fb5 centralize, 4608da1 flip): ruler → LAYERS → MASTER → AUDIO.
  ✓ Master filmstrip delineation (84b570b) + refinement (3409931): sprocket frame TRAVELS with scroll,
    green trim handles paint in FRONT of the film, handles narrowed 14→11dp. (JoyRaptor feedback loop.)
  ✓ Accurate waveform W1 (38b29ee): peak-preserving extraction (max not mean, ~60 bins/sec) + peak
    render (max-of-range not point-sample) + perceptual .6. Existing clips render better now; new
    extractions get accurate peak data. W2 HD-zoom tier still open (FEEDBACK_20260703_timeline_fidelity).
  NEXT (JoyRaptor's "quick wins first" → then the big audio work, see FEEDBACK_20260706_audio_and_delineation.md):
    #3 audio as a full multi-track editor (stack/arrange/move like layers, dual-scroll band below master;
    needs the ~9 getSelectedAudioIndex ops re-anchored to selectedItemId) — the multi-session backbone;
    #4 audio-only export (new ExportManager mode). Then Slice F / G-series.
  --- prior (still valid) ---
  Slice E vertical re-layout DONE + device-verified (SM-N960U). 2 always-green commits:
        7e89fb5 Step 1 (centralize band-top geometry into 5 accessors, pixel-identical) →
        4608da1 Step 2 (flip: getM6RowsTopPx→rulerHeightPx, masterTopPx→below band; onMeasure reserves
        divider gap). New order: ruler → LAYER SUBSTRATE (Text/CC/viz/sprite) → MASTER (56dp, dominant)
        → AUDIO. Verified: master-seg select, M6 layer-item select, audio-clip select, scrub all
        hit-test at new positions; render order correct; plain single-track keeps master at top (no regress).
since: 2026-07-06 (continuation session)
files: (none — released; committed, code tree clean)
SLICE E FOLLOW-UPS (open, non-blocking — next session / G-series):
  • Within-band CC ordering + the "overlays&CC" vs "LAYERS" SUB-split (CC visually at the TOP of the
    substrate, above images/sprites/text). Currently layerBand = getLayers()+viz+captions (captions at
    BOTTOM). This is really G5 territory (piggyback/stratified fade-inheritance) — the mere row order is
    cosmetic; do it WITH the tether semantics, not standalone. File: FaditorEditorActivity#syncTimelineOverlays.
  • Cross-row MOVE-drag drop-zone can transiently overlap master's top edge (~28dp) during an ACTIVE
    pick-up-drag when the band is uncapped: viewportHeightPx grows by the drop-zone reserve past
    m6BandFootprintPx (which excludes it). Clipped + translucent + transient; NOT verified on device yet
    (needs a hold→drag hand-test). Address in G1/G6 drag work. HAND-TEST: pick up a Text/Sprite item,
    drag toward a new lane, watch whether the purple "+ new layer" strip bleeds over the master track top.
  • G6 (resizable timeline grab bar) is the long-term "master larger / more rows" answer and is independent
    — good next candidate; complements Slice E.
LAYERS-UX PROGRESS (this session, all DEVICE-VERIFIED + always-green):
  ✓ Slice A (0d0c5a1): CAPTION/VISUALIZER TrackKinds + TimedItem payload sockets (waveform +
    CaptionSpanRef) + Timeline getCaptionTracks/getVisualizerTracks. No visual change.
  ✓ Slice B+C (1f35695): captions & visualizers render as headered rows in LayerRowRenderer
    (amber CC w/ per-keyframe style segments + diamonds; cyan VIZ); OLD drawLayers
    text/caption/viz bars retired → SINGLE-render, reserved band collapsed, preview grew.
    Viz delete moved to the selection badge (deleteVisualizerWithConfirmation).
  ✓ FEEDBACK #4 (41dd79e): caption style chooser (Pop/Zoom/…) auto-hides when no caption is
    under the playhead (mirrors the caption-overlay visibility in updateCurrentTimeDisplay).
  ✓ AUDIO double-render (93fe745): suppressed the half-functional NEW audio rows (they didn't
    drive the ~9 getSelectedAudioIndex-anchored audio ops). Old complete audio system is the
    single audio UI. DEVICE-VERIFIED (new "Audio" row gone, CC is now last row, no crash).
  ⏭ Slice E vertical re-layout (FEEDBACK #3): DEFERRED to a dedicated next session (JoyRaptor's call
    2026-07-06 — too risky to rush at session tail). PLAN (always-green, 2 steps):
      Step 1 (safe, no visual change): centralize the band-top math into methods
        — masterTopPx()/masterBotPx(), audioBandTopPx(), overlayLayerBandTopPx() — and route ALL
        current inline sites through them. Sites to convert (EditorTimelineView):
          render: tTop/tBot (1663-64), audioTop/Bot (1667-68, 1588, 5576-78), getLayerTopPx (2210),
                  getM6RowsTopPx (2223), computeRects segRects tTop (1567), backgrounds (1671-74),
                  playheadBot (1739), drawCenterPlayhead (3268), ruler ticks (2493-2518).
          hit-test: master/segment (5548, 5560, 5771-72), audio (5576-79), ruler-scrub bounds (5793).
        Compile + device-verify NOTHING moved (pixel-identical) before committing Step 1.
      Step 2 (the flip): change ONLY the centralized methods to the new order — ruler → overlays+CC
        band → LAYERS band → MASTER (centered, ~1.15-1.3× taller) → AUDIO → tool drawer. Note the
        M6 rows have a capped internal scroll viewport (viewportHeightPx) — the master goes BELOW it.
        Device-verify EVERY tap target (master trim L/R, segment select, audio select+trim, ruler
        scrub, M6 row select/drag, header icons) before committing.
    Also relevant: contract §5/G6 (resizable timeline) is the better long-term answer to "MASTER
    larger" (user sizes it) and is independent — could land instead of / before the hardcoded flip.
  ALSO OPEN (from earlier): dead-code cleanup (inert old drawLayers/hitTestLayer*/selectedLayerKind
    + now the old audio path stays as the PRIMARY audio UI, so do NOT remove it); caption delete-badge
    no-op; full audio→unified-row migration (re-anchor ~9 getSelectedAudioIndex ops to selectedItemId).
  DEFERRED (still open, honest): (a) AUDIO double-render — drawAudioTrack (old, EditorTimelineView
    line ~1711, reads `audioClips`) still coexists with the new audio rows (`audioLayerTracks`
    from getAudioTracks); it's a LARGE independently-interactive subsystem (trim/mute/volume/
    waveform + own hit-testing) → needs its own focused slice + thorough audio-edit regression
    test before retiring the old path. (b) Dead-code cleanup: the now-inert
    drawLayers/hitTestLayer*/hitTestLayerRow/hitTestLayerTap/activeLayerIndex/Drag.LAYER_*/
    selectedLayerKind/Value + old caption/viz listener callbacks in EditorTimelineView are
    harmless (empty lists guard them) but should be removed in a pure-removal commit.
    (c) Minor wart: a selected CAPTION shows a delete badge that no-ops (onItemDeleteRequested
    has no caption branch — captions are clip-owned; a real "delete" = disable captions on the
    clip w/ undo, or suppress the badge for captions).
  NEXT candidates (JoyRaptor's FEEDBACK order): #5 minimal header (solo+twirl per contract §4.5 —
    move eye/lock/mute to per-OBJECT badges; LARGE); #3 vertical re-layout (overlays+CC top,
    MASTER centered/larger, AUDIO below — deep layout surgery in EditorTimelineView); then the
    audio consolidation above; #2 no-overlap all types (Slice F); G1–G9 gestures.
NEXT (ordered, per tasks/FEEDBACK_20260706_layers_ux.md): LAYERS-UX OVERHAUL — (1) consolidate the two
       row-render systems into ONE (EditorTimelineView#drawLayers + LayerRowRenderer) [prereq for the
       rest]; (5) header hit zones; (4) caption-chooser autohide; (3) vertical re-layout; (2) no-overlap
       ALL item types + move-between-layers; (6) gesture language DESIGN-FIRST with JoyRaptor. THEN sprite
       FF-A/FF-B, S7 relink, S2b polish. Watcher was live+green (BUILD SUCCESSFUL 14s).
NEXT (ordered, for the continuing session): (1) hand-test the one owed item — is the sprite
       VISIBLY composited on video in preview+export (see PLAN ⚠️); (2) S7 relink UI; (3) S2b
       polish; (4) FF-A presets/dope-sheet UI; (5) FF-B AI sprite tools. Watcher was live+green.
NOTE (build infra): watcher + opencode gradle running CONCURRENTLY corrupts the
incremental resource merge (missing .flat → 100 "class R" errors; fix = delete
app/build/intermediates + retrigger). While the watcher runs, opencode must NOT
invoke gradle — save and read build.log instead.

## OPENCODE — dynamic lane
status: IDLE (2026-08-23 — D3/D2/A3 all BUILT, typecheck 635 green, lane released)
files: (none)
since: 2026-08-23

## LANE C — dynamic lane (audio export: A8 → E4 → A5.E/U → A6 → C1.E)
status: ACTIVE (2026-08-23 — 5-row autonomous run, in order, A8 gates the rest. Rule 9 ENFORCED:
        literal harness tail in every row report. build.log timestamp checked against commit time.)
files: export/ExportManager.java (JoyRaptor-instructed exception to standing lock),
        export/VolumeAudioProcessor.java, tools/AudioDrawerTabs.java,
        NEW audio/fx/*, tasks/export_audio_probe.py, tasks/LANES.md, tasks/SPEC_AUDIO_UX_V1.md (my rows only)
since: 2026-08-23

## LANE A — dynamic lane (B2.U3 → D9, 4 rows in order)
status: ACTIVE (2026-08-23 14:00 — ROW 1 B2.U3 in progress (host undo + inspector))
files: FaditorEditorActivity.java, ObjectMenuSheet.java, timeline/EditorTimelineView.java, layers/LayerGestureController.java, layers/LayerRowRenderer.java, model/AudioCrossfade.java
since: 2026-08-23 14:00
