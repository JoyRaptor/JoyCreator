# FACE-1: Face capture (your face drives the character, premium)

**Milestone:** launch (part of Avatar Studio, [AVA-1](AVA-1_avatar_studio_redesign.md))  ·  **State:** 🔨 inventory and first phone proof done 11-22; premium engine being built  ·  **Owner:** "face capture scout" session (Claude Opus 5.5, side session of the 11-22 Avatar lead)  ·  **Checked:** 2026-11-22 against `078fb8f43`

## Why it matters (for the user)
Hold the phone up, make a face, and the character makes it too: a wink is a wink, a half smile is a half
smile, a raised eyebrow is one eyebrow. The owner's bar is an industry leader, not a tech demo. He believes
some of this was built and has never seen it run on his phone; this card says exactly what exists, what was
proven, and what is being built.

## Done when
- [x] Inventory of every piece of face capture in the repo, with where each is reached from (11-22, below).
- [x] What exists run on the Note 9 and recorded (11-22, below; the parts that need a face are owed).
- [ ] A premium face engine with a clean seam for the phone room: continuous, two-sided expression; one-tap
      neutral; smoothing without jitter or lag; the face lost and found gracefully; a small private preview;
      recorded into a take in passes and editable afterwards. Tests shown failing on each fault first.
- [ ] Proven on the Note 9 through a bench screen, with a real face (needs the owner for two minutes).
- [ ] The web pilot reads faces the same way (a new file, hooked in by the AVA-1 lead).
- [ ] STATE.md rows updated.

## Where it stands (11-22, inventory against `078fb8f43`)

**On the phone (Android).** All of it was built on 2026-07-11 and has not changed since (last face commit
`76d7e9385`). The test build on the Note 9 (11-20) carries it.

| Piece | What it does | State |
|---|---|---|
| MediaPipe Tasks Vision 0.10.26.1 (`app/build.gradle.kts`) | Google's face tracker library | ✅ built, in the app |
| `assets/mediapipe/face_landmarker.task`, 3.6 MB (3,758,596 bytes) | The face model (float16 v1): 478 face points, 52 expression values, head angles | ✅ ships in the app |
| `MediaPipeTrackingSource` (429 lines) | Front camera at 320x240 into the model on the processor (no graphics chip), capped at 15 frames a second (10 when the phone is very hot). Gives head turn, nod and tilt (full at 45°, 30°, 45°) and the 52 expression values raw (two renamed: blinkL, blinkR). Face lost: sends nothing, so the character freezes as it was. Face back after a second: smoothing snaps | ✅ built and reachable |
| `TrackingDriverBus`, `TrackingSource`, `TrackingFrame` | One tracker at a time; the screen pulls the newest values each frame | ✅ built, harness-tested (`TrackingCoreTest`) |
| `TrackingParamPipeline`, `DriverParamSmoother`, `OneEuroFilter` | Smooths every value with the same setting (1 Hz, 0.3); mouth from the mic level when there is no face | ✅ built; one setting for head, blinks and mouth alike, so blinks are softened |
| `LifeSignals` | Breathing, eye darts, uneven blinks when idle | ⚠️ built but **nothing reads its output**; and an automated colour sweep on 09-18 (`29a1f8b78`) replaced its random-number mask with a colour value (`Studio.alpha(Studio.INK, 0x7F)`), so its randomness is skewed (same fault in `SyntheticTrackingSource`) |
| `SpectralVisemeAnalyzer`, `MicVisemeSource`, `AudioLevelViseme` | Mouth shapes from the microphone | ✅ built (not face capture, but feeds the same mouth) |
| Calibration (a neutral face) | | ❌ missing on the phone |
| **What the camera actually moves** | `PuppetPoseResolver` reads only the drivers a rig names: the built-in rigs name `yaw` and `pitch` (a 3x3 head grid) and `angle` (limbs, which no tracker sends) | ⚠️ **only head turn and nod move a character.** Mouth, blinks, brows, eyes, tilt and smile are computed and reach nothing |
| Old Avatar Studio (`AvatarStudioActivity`), the 🎯 Track button | Track = face camera if allowed, else a fake moving source; the 3x3 grid dot follows head turn | ✅ reachable: lobby, Avatar room, Open (also Library, Studio, Joybot) |
| Studio: "Record" chip on an avatar item (`FaditorEditorActivity.togglePerformanceRecording`) | Records the face into the item's take while the video plays; one Undo swaps whole takes | ✅ reachable from Studio's sprite drawer; **one take replaces the last, no passes, no editing afterwards** |
| Studio: "From video" chip (`VideoFaceSweeper`) | Reads a face out of a clip already in the project into the take | ✅ reachable; device-smoked 07-11 on a clip with no face only |
| Screen recorder's webcam bubble (`FloatingWebcamService`) | The bubble's avatar button swaps your camera picture for a saved avatar that follows your face | ✅ reachable from the screen recorder; needs a saved avatar (the Note 9's list is empty) |
| Messages shown | "Face model failed to load — avatar won't track", "Camera unavailable for face tracking" | ⚠️ technical words shown to the user (house rule: never) |
| A preview of what the camera sees | | ❌ none (the bubble hides the picture on purpose) |

**In the web pilot** (`tools/avatarlab/al-inputs.js`, `camStart` and `watch`, last changed 11-21):

| Piece | State |
|---|---|
| Camera at 320x240, MediaPipe tasks-vision 0.10.14 from a public CDN, model from Google, graphics chip, about 20 reads a second | ✅ works on the PC's local page only; the Face chip is hidden in the published artifact (`hosted`) |
| Head turn, nod, tilt; the first face seen is "straight ahead" (head only) | ✅ |
| Mouth = jaw × 1.8; a smile only picks the "Ee" mouth | ✅ |
| Blinks: **on or off** at half closed (no half blink, no slow lid) | ⚠️ |
| Brows: **one value for both** (no single raised brow) | ⚠️ |
| Eyes: from the left eye only | ⚠️ |
| Lids: relaxed, cheery and lid tilt from blink, squint and brow | ✅ |
| Face lost: holds the last values | ⚠️ freezes |
| Two modes: only while Talk is held, or always | ✅ |
| Takes: recorded in passes by lane (Head, Mouth, Mood, Blink) with a quarter-second hand-over | ✅ the model the phone should follow |

## Proven on the Note 9 (11-22, the 11-20 test build, no install)
Evidence: [tasks/screens/FACE-1_20261122/](../screens/FACE-1_20261122/) (`old_*.jpg`, `old_track_run_note9.csv`).

| Check | Result |
|---|---|
| Reach it | ✅ Lobby, Avatar room, Open: the old studio opens on the test character "Avatar" (project "Restore video test", no parts) |
| Camera opens | ✅ Track: the phone reports the app holding camera 1 (front); hint "Tracking (face): move your head — the rig follows" |
| Model loads | ✅ Three models load on the processor (XNNPACK) in about 50 ms |
| Cost, no face in view, 3¾ minutes | App about 55% of one processor core (3% idle). Battery 33.7 → 33.8 °C, skin 33.3 → 33.6 °C, thermal status 0 (no throttling). Plugged into USB, so battery drain could not be read |
| Camera let go | ✅ Home: no camera client, app back to 3% |
| A face drives a character | ❓ **not checked**: no face was in front of the phone, and the test character has no art |
| Frame rate, face-to-puppet delay, low light, glasses, turned face, lost and found | ❓ not checked: each needs a face. The code has no frame-rate or delay readout, so even with a face nothing would show them |

## What is left
- [ ] Engine (`app/src/main/java/com/fadcam/ui/faditor/avatar/face/`), pure Java where it can be, each piece
      harness-tested with its planted faults caught first.
- [ ] Wire it into the tracker, the bus and the pipeline without breaking the old studio, Studio's Record and
      the webcam bubble (they keep reading `yaw` and `pitch` as before, now calibrated).
- [ ] A bench screen reached from the old studio's toolbar, then a cloud build on the Note 9 (the owner told
      before installing).
- [ ] The owner's two minutes in front of the Note 9 (the face checks above).
- [ ] The web pilot's face file and the one-line hook for the AVA-1 lead.

## Specs, handoffs and evidence
- Earlier plan: [PLAN_AVATAR_STUDIO.md](../PLAN_AVATAR_STUDIO.md) (A2 tracking, D4 MediaPipe, A4 recorder).
- Evidence: [tasks/screens/FACE-1_20261122/](../screens/FACE-1_20261122/).

## Files and traps
- New files go under `app/src/main/java/com/fadcam/ui/faditor/avatar/face/`. Existing ones touched only where
  the seam needs it.
- `tools/avatarlab/al-inputs.js` belongs to the AVA-1 sessions (walking, voice): never edit it here.
- The camera has one owner at a time (the bus): the bench, the old studio and the bubble never run together.
- The Note 9's screen is set to 1080 x 2220 (its panel is 1440 x 2960): scale taps from screenshots by that.
