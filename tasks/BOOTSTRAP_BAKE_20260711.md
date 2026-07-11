# BOOTSTRAP — bake-to-keyframes session (paste-ready prompt, written 2026-07-11 ~20:00)

You are Fable working in C:\+Projects\Screenrecorder\FadCam, branch joy-creator. Mission: finish
Joy Creator's avatar loop — TODAY'S TASK IS **BAKE-TO-KEYFRAMES** (the last big chunk), then
point-at-video if budget remains.

## Read first (in order)
1. tasks/LANES.md — multi-agent protocol. Binding rules: NEVER run gradle (the user's watcher is
   the sole builder — save files, then read build.log, which is UTF-16: use PowerShell
   `Select-String -Path build.log -Pattern 'BUILD (SUCCESSFUL|FAILED)|error:'`. Quiet log ≠ stalled
   watcher — it only rebuilds on saves). Don't touch opencode's uncommitted files
   (FilterBottomSheet.java, effects/GradePresetStore.java, res/values/strings.xml — use inline
   string literals). DEVICE token protocol before any adb.
2. tasks/PLAN_AVATAR_STUDIO.md — §Status: A4 is COMPLETE (commits c0c4020, 7b4edb3, 16a712f).
   The §MINED bake-to-parameter-track doctrine is BINDING.

## State you inherit (all committed, watcher-green, device verify OWED)
- Recorder: FloatingWebcamService renders the selected library avatar into the webcam bubble
  (MediaPipeTrackingSource owns the front cam → TrackingDriverBus → PuppetPoseResolver →
  PuppetPreviewView cleanRender; clear-stage toggle = transparent card).
- Studio: standalone library mode (main-menu person icon on the Faditor tab header) + project mode.
- Editor: avatar dialog "⇓ Insert" places a library avatar as a 1-cell sprite sheet holding the
  NEUTRAL pose baked by avatar/AvatarNeutralBaker (offscreen PuppetPreviewView). Sheet id
  convention `avatar-neutral-<rigId>`, asset `avatar-<rigId8>-neutral.png`; the rig + its real
  sheets are already imported into the project (project.getAvatarRigs()).
- avatar/AvatarParamTrack: the performance model — record resolved driver params, lerp replay,
  hold-don't-fade on loss, JSON round-trip. PROVEN: tools/jvm-harness/ParamTrackTest 25/25
  (javac recipe: compile vs app/build/intermediates/javac/defaultDebug/.../classes +
  gson-2.11.0 + annotation-jvm-1.9.1 from ~/.gradle/caches).

## THE TASK — bake-to-keyframes
A placed avatar item upgrades from static neutral PNG to a LIVE PUPPET driven by a recorded
AvatarParamTrack. Design intent (§MINED, binding): recording saves RESOLVED driver params; export
replays them through the resolver — the webcam NEVER re-runs at export; preview==export.
Suggested slicing (always-green, commit each):
1. STORAGE: attach an AvatarParamTrack + rigId to the placed sprite item (SpriteOverlayItem
   carries a FrameTrack already — study how it serializes; ProjectStorage dual-write stamp
   precedent v10). Keep it additive + tolerant-read.
2. RECORD: in the editor, with an avatar item selected, a "🎯 Record performance" affordance
   mounts TrackingDriverBus + MediaPipeTrackingSource (camera single-owner — studio pattern,
   AvatarStudioActivity startTracking is the reference), samples bus.latest() per frame into an
   AvatarParamTrack (t = playhead-relative ms), stops on tap/end. Store on the item.
3. RENDER: where sprite overlay items draw in preview AND export, if the item has a rig+track:
   resolve(rig, track.sampleAt(itemRelativeMs), per-consumer DiscreteState stepped in time
   order) and draw the puppet (parent∘child composition — PuppetPreviewView.onDraw is the
   reference implementation; extract shared math rather than duplicating if feasible). This is
   locked-file surgery (compositor/, export/ are Fable-owned — you ARE Fable, edit carefully,
   A/B-proof export changes per the ab-export-frame-diff-proof memory).
4. JVM-harness what's pure (track sampling determinism across replay is already covered; add
   coverage for item-relative time mapping).
Then #5 point-at-video: VIDEO-mode FaceLandmarker sweep over a clip → AvatarParamTrack on an
avatar item (reuse MediaPipeTrackingSource's putHeadPose math — extract, don't copy).

## Device-verify batch owed (when the user plugs in; take the DEVICE token)
(1) Studio 🎯 Track: head follows? Axis inversions → flip MIRROR_YAW/SIGN_PITCH in
MediaPipeTrackingSource. (2) Library ⇪ on a rig. (3) Bubble face button: puppet renders + follows;
cycle back to webcam. (4) Opacity toggle: character alone floats over the screen. (5) Person icon
on Faditor tab → standalone studio chooser; new-avatar-from-image. (6) Editor avatar dialog →
"⇓ Insert" places the neutral puppet on video; export contains it.

Session task list already has these as #4 (bake), #5 (point-at-video), #6 (A5), #7 (device batch).
