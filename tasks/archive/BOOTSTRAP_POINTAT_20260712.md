# BOOTSTRAP — point-at-video + avatar-loop finish (paste-ready prompt, written 2026-07-11 ~21:10)

You are Fable working in C:\+Projects\Screenrecorder\FadCam, branch joy-creator. Mission: finish
the remaining Joy Creator avatar jobs — TODAY'S TASK IS **POINT-AT-VIDEO** (the last product
slice of the bake loop), then the owed device batch when JoyRaptor/a face is available, then A5 AI
rigging if budget remains.

## Read first (in order)
1. tasks/LANES.md — multi-agent protocol, read FRESH before EVERY task. Binding rules:
   NEVER run gradle (the user's watcher is the sole builder — save files, then read build.log,
   UTF-16: PowerShell `Select-String -Path build.log -Pattern 'BUILD (SUCCESSFUL|FAILED)|error:'`;
   verify freshness by mtime(build.log) > mtime(edited file), never tail text alone). Don't touch
   opencode's uncommitted files (FilterBottomSheet.java, effects/GradePresetStore.java,
   res/values/strings.xml — use inline string literals). DEVICE token protocol before any adb.
2. tasks/PLAN_AVATAR_STUDIO.md §Status — bake-to-keyframes is COMPLETE + DEVICE-VERIFIED
   (c2a5e1a storage, 92e6cb3 replay render, 20e2089 record, bbb6204 harness, 6dc770c docs).
   §MINED doctrine stays BINDING: tracks store RESOLVED params; export replays through
   resolve(rig, track.sampleAt(itemLocalMs), fresh DiscreteState stepped in time order);
   the webcam NEVER re-runs at export; preview==export; A/B-proof any export change
   (ab-export-frame-diff-proof memory: absolute-geometry diffs, not symmetric ones).

## State you inherit (all committed, watcher-green)
- Placed avatar item = SpriteOverlayItem carrying avatarRigId + AvatarParamTrack (additive,
  tolerant-read storage in ProjectStorage; insert stamps the linkage).
- avatar/AvatarItemPuppet = the ONE replay renderer both SpriteOverlayView (preview) and
  export/CompositeExportOverlay consume (offscreen PuppetPreviewView, media-clock determinism,
  rewind → fresh DiscreteState + resetReplayState). ExportManager passes project rigs.
- Editor record path: 🎯 Record chip in sprite/SpritePalettePanel for avatar items →
  FaditorEditorActivity.togglePerformanceRecording (studio mount pattern, camera single-owner,
  synthetic fallback, stop on tap/item-end/delete/onPause, one-undo whole-take swap).
- **76d7e93 PREP (your on-ramp): MediaPipeTrackingSource now exposes STATIC
  putHeadPose(params, matrix) and resultToParams(FaceLandmarkerResult) → the live stream
  already calls them. The sweep must map frames through these SAME methods — one axis-knob
  set (MIRROR_YAW/SIGN_PITCH), one param vocabulary, zero drift.**
- Harness: tools/jvm-harness ReplayMappingTest 19/19 + ParamTrackTest 25/25. Recipe: javac vs
  app/build/intermediates/javac/defaultDebug/.../classes + gson-2.11.0 + annotation-jvm-1.9.1
  from ~/.gradle/caches; NOTE java.exe wants Windows-style jar paths in -cp.

## TASK 1 — POINT-AT-VIDEO
An avatar item gets its performance from an EXISTING clip instead of a live take: sweep the
clip's frames through FaceLandmarker in VIDEO mode → AvatarParamTrack on the item. Suggested
slicing (always-green, commit each):
1. SWEEPER: new avatar/VideoFaceSweeper (or similar) — MediaMetadataRetriever (or
   ImageReader+MediaCodec if retriever fps is too coarse; retriever at ~10-15fps sampling is
   plenty — the track lerps) → FaceLandmarker RunningMode.VIDEO (detectForVideo(mp, frameMs))
   → MediaPipeTrackingSource.resultToParams(result) → track.add(frameMs, params). No-face
   frames add NOTHING (hold-don't-fade is the track's job). Model/asset gating identical to
   the studio (isModelPresent). Run on a background executor with a progress callback +
   cancellation; never block the UI thread.
2. UI: on an avatar item in the sprite palette, next to 🎯 Record, add "🎬 From video" —
   sweeps the clip UNDER the item (the master clip at the item's startMs; map clip source
   time correctly: frameMs in the track must be ITEM-LOCAL ms, so sweep the timeline range
   [item.startMs, min(item.endMs, timeline end)] and convert timeline→clip-source time via
   the clip's inPoint/speed like the export path does). Progress toast/indicator; result =
   ONE undo step swapping whole takes (mirror stopPerformanceRecording's undo shape exactly).
3. Replay/export verification rides the existing AvatarItemPuppet path untouched — if you
   change NOTHING in compositor/export, no A/B proof is owed; if you touch them, prove it.
4. JVM-harness what's pure (timeline→clip-source time mapping if you write a helper).
Watch out: FaceLandmarker VIDEO mode wants MONOTONIC frame timestamps per detector instance;
create a fresh landmarker per sweep. detectForVideo is synchronous — no result listener.

## TASK 2 — OWED DEVICE BATCH (take the DEVICE token; needs JoyRaptor or at least her face/grant)
(a) Axis feel: studio 🎯 Track with a real face — head-right must read positive yaw; flip
    MIRROR_YAW/SIGN_PITCH in MediaPipeTrackingSource if inverted (one knob, both paths).
(b) Real-face record on an avatar item → replay → export (the synthetic-injection A/B from
    2026-07-11 already proved the pipeline; this proves the human loop).
(c) Recorder bubble face-button + clear-stage toggle — BLOCKED on "Display over other apps"
    permission (JoyRaptor must grant it in Settings; do NOT grant system permissions yourself).
(d) Point-at-video (Task 1) on a real face-bearing clip.
(e) Older owed: 2ef2d1e export transition≥clip muxer-stall fix (repro: AudioExportVerify
    aeb0517e seam-2 → 600ms, export both paths).
Cleanup note for JoyRaptor: P0 control2 project cebc19e0 carries a stray "Enter text" overlay, an
inserted A6 Warp Smoke item with an injected 2s yaw-sweep track (good for feel-testing), and
pushed test art; project.json.bak (Jul 7) on-device if pristine matters.

## TASK 3 — A5 AI RIGGING (if budget remains; else next session)
describe_sprite_sheet → author_avatar_rig emitting rig JSON against the built-in generalized
biped template (plan §AI rigging). Propose-then-confirm, undoable, key-gated. Design pass
first; don't start it at a session tail.

## Backlog after the avatar loop (do NOT start unprompted; listed for orientation)
A3 spectral visemes (TarsosDSP tier), A6 pin-authoring UI design pass, AV4 wire-up +
AV5 perf (tape-draw tile cache), sprite FF-A/FF-B, S7 relink UI, dead-code cleanup.

Device runbook: tasks/DEVICE_CONTROL_RUNBOOK.md (screenshot→tap loop; PowerShell mangles
exec-out binary — redirect screencaps in Git-Bash; adb push needs MSYS_NO_PATHCONV=1 +
C:/-style source; exports land in Android/data/com.fadcam.beta/files/FadCam/Faditor/, logcat
redacts the path — find by mtime). JoyRaptor permits one Sonnet + one Opus subagent if needed.
