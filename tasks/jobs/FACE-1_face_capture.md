# FACE-1: Face capture (your face drives the character, premium)

**Milestone:** launch (part of Avatar Studio, [AVA-1](AVA-1_avatar_studio_redesign.md))  ·  **State:** 🟢 engine built, PC-tested (every planted fault caught), and tried by the owner on the Note 9 with his face, 11-22 ("Done, all fine"); his find, the outline a quarter turn off, fixed in `0e999f1f4` and awaiting his look  ·  **Owner:** "face capture scout" session (Claude Opus 5.5, side session of the 11-22 Avatar lead)  ·  **Checked:** 2026-11-22 against `0e999f1f4`

## Why it matters (for the user)
Hold the phone up, make a face, and the character makes it too: a wink is a wink, a half smile is a half
smile, a raised eyebrow is one eyebrow. The owner's bar is an industry leader, not a tech demo. He believes
some of this was built and has never seen it run on his phone; this card says exactly what exists, what was
proven, and what is being built.

## Done when
- [x] Inventory of every piece of face capture in the repo, with where each is reached from (11-22, below).
- [x] What exists run on the Note 9 and recorded (11-22, below; the parts that need a face are owed).
- [x] A premium face engine with a clean seam for the phone room: continuous, two-sided expression; one-tap
      neutral; smoothing without jitter or lag; the face lost and found gracefully; a small private preview;
      recorded into a take in passes and editable afterwards. Tests shown failing on each fault first.
- [x] Proven on the Note 9 through a bench screen, with a real face (the owner, 11-22: "Done, all fine").
- [ ] The web pilot reads faces the same way (`al-face.js` built and checked equal to the phone, 11-22; the hook is the
      AVA-1 lead's).
- [x] STATE.md rows updated (11-22).

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

## Built (11-22): the premium face engine
All in `app/src/main/java/com/fadcam/ui/faditor/avatar/face/`, small single-purpose files; the old pieces are reused, not
replaced (the same tracker, bus, pipeline, One-Euro filter, life signals and take format).

| Piece | What the user gets |
|---|---|
| `FaceSignals` | One vocabulary with the web pilot. Head: turnX, turnY, roll (and the old yaw, pitch, now calibrated). Eyes: lookX, lookY from both eyes; blinkL, blinkR; squintL/R; wideL/R. Brows: browL, browR, browInner. **Jaw apart from lips** (the owner's 11-22 question): jaw, jawX (sideways), jawFwd; mouth = the lips' opening (jaw less lips held shut); lipUpL/R (upper lip lifted on one side), lipDownL/R; smileL/R, frownL/R; pucker, funnel, mouthWide, press, mouthX (the mouth slid sideways), cheekPuff, sneer; the pilot's lidUp, lidLow, lidTilt. **Lean** (a bust): leanX, leanY, near. A sheet mouth's shape (vis) and how much the live face drives (face). Lanes: head, mouth, mood, blink, lean |
| `FaceMapper` | Two sides stay two sides; continuous blinks with a firm end; the false half-blink of looking down at a phone taken out; eyes that the tracker reads a little unevenly blink together while a real wink stays a wink; moves like your reflection, Mirror turns it round |
| `FaceCalibration` | **One-tap neutral** (a still half-second; a resting frown, squint or a low-held phone become zero, a full expression still reaches its end); the first one is automatic. A **direction check** (turn right, look down, tilt right, lean right, close your right eye) settles which way is which on each phone: nobody had ever checked the old code or the pilot against a real face |
| `FaceStage` | **Lost and found**: the last face holds a third of a second (a flicker is not a loss), then eases to a relaxed rest over 0.8 s while the idle life keeps blinking; a returning face crosses over in 0.3 s instead of jumping; eyes keep their gaze through a blink; a sheet mouth changes shape only when the new shape holds two frames; before the first neutral the character waits at rest |
| Smoothing (`DriverParamSmoother` with a per-name tuner) | Each signal at its own speed: blinks barely smoothed (a blink lands in one tracker frame; the old single setting softened it to half), the head smoothed most while still (7% of the raw jitter left) and opened up as it moves |
| `FaceFollower` | **No stair steps, no lag**: the tracker gives about 15 faces a second, the screen draws 60; this follows each signal along its own motion (critically damped, predicts one frame ahead), off by at most 0.02 of a full head turn on a moving head; a head that stops runs past by about 0.06 and settles |
| `FacePass` | **Recorded in passes** like every other channel: a pass writes only its lanes, hands back over a quarter second, everything else identical sample for sample; **editable afterwards** by lane: clear, stronger or softer, smoother, earlier or later |
| `FaceCapture` | The one object a room uses (below) |
| `FacePreviewView` | A small preview of what the camera sees, mirrored like a mirror, never stored or recorded; tap for the outline only (eyes, brows, lips, the face's edge, no picture); cyan while a face is found; a dot while the camera is on |
| `FaceBenchActivity` + `FaceBenchFace` | The bench: a test face showing every signal (the owner's mouth idea built in: lower teeth on the jaw, upper teeth on the head, both seen only through the lips' opening, which slides with the mouth), the preview, Neutral, Directions, Mirror, Graphics chip, Picture, Record by lane, Play, Undo, hold a lane to edit it, and a status line: faces a second, delay from the camera to the character, the model's time, the phone's temperature (also logged once a second under `FaceBench`). Reached from the old Avatar Studio's new **🙂 Face test** button, beside Track |

Also changed: `MediaPipeTrackingSource` sends the raw face (all 52 values by their own names, head degrees, the face's
place in the picture), a frame for "no face" (it used to send nothing, so characters froze), the camera's capture time,
the preview, an optional graphics-chip model with the processor as fallback, and plain-words messages. The bus keeps
one face stage across mounts and measures faces a second and delay. `LifeSignals` and `SyntheticTrackingSource`: the
random mask the 09-18 colour sweep broke is restored to `0x7FFFFFFF`. Every host of the bus (the old studio's Track,
Studio's Record, the webcam bubble) gets the neutral, the smoothing and lost-and-found without a change of its own.

**Tests** (`bash tools/jvm-harness/run-face.sh`): 50 checks, each named for what the owner would see, plus the
phone/pilot parity (6,336 values over 54 faces and a 90-frame lost-and-found run agree within 0.0001).
`run-face.sh --proof` plants 31 faults (28 in the phone engine and the take, 3 in the pilot's file) and all 31
are caught (11-22). The older suites still pass: TrackingCore 18, OneEuro 6, ParamTrack 25, ReplayMapping 19,
LifeMath 16. The tests found one real fault in this work before it shipped: the fill-in trailed a moving head by 0.10 of
a full turn (it measured against the wrong moment); now 0.02.

## Proven on the Note 9: the bench (11-22, cloud builds `6f3aa52e0` and `48c739e06`, installed over the test build, no uninstall)
Evidence: `tasks/screens/FACE-1_20261122/new_*.jpg` (the preview corner is blacked out in any shot where the picture
was on) and `new_bench_run_note9.csv`. No face was in front of the phone for any of this.

| Check | Result |
|---|---|
| Reach it | ✅ Lobby, the library card "Avatar", the old studio; 🙂 Face test sits beside 🎯 Track and opens the bench |
| Camera | ✅ opens on entering (the phone reports the app holding the front camera); let go on Home (0 clients, app back to 3% of a core) |
| Status line | ✅ "Looking for your face · 12 a second · 104 ms behind you · model 22 ms · phone 31.4 °C · processor" |
| Faces a second | ✅ 12 to 14 (the tracker's cap is 15) |
| Camera to character | ✅ about 100 ms (99 to 111) on the processor, 97 to 100 on the graphics chip: most of it is the camera itself, before the model sees the picture. With a face the model does more work, so expect a little more |
| Graphics chip | ✅ starts on the Note 9 (about 2 s to load), the model's time per frame 22 → 16 ms (no face: only the "find a face" step) |
| Picture / outline only | ✅ one tap; outline only shows no picture at all, just the frame and the pink camera dot |
| Record, Play, Undo, edit a lane | ✅ "Take: 4.7 s. Play it, or record another pass"; holding a lane opens Clear / stronger / softer / earlier / later; Undo takes it back |
| Directions with no face | ✅ "1 of 5: Turn your head to your right", then honestly "No face seen, so nothing changed" (it would have said "set") |
| Old Track still works | ✅ the old studio's Track on the new pipeline: camera on, "Tracking (face)", no crash |
| Heat, 4 minutes | Battery 31.4 → 32.5 °C, skin 32.0 → 33.4 °C, thermal status 0 (no throttling). Plugged into USB, so battery drain could not be read |
| Cost | About 80 to 85% of one core, on the bench and on the old Track alike (final build `48c739e06`, `new_bench_run2_final_build.csv`, `new_old_track_run_final_build.csv`). Split by thread: the camera and the model about the same as before (the old build measured 55% in all), and drawing the screen about a third of a core, because a face that is not there now eases to rest and keeps blinking, so the character animates continuously instead of standing frozen. Cutting the bench's status text to four times a second changed nothing measurable |
| Crashes | ✅ none logged |
| **A face drives the test face** | ❓ **not checked: needs a person in front of the Note 9** (every expression, lost and found, low light, glasses, a turned face, the directions, and whether the outline lines up with the picture) |

## The owner's own test (11-22, the Note 9, guided)
- **His verdict:** "Done, all fine" after the guided test (expressions, head, lean, covering the camera, Directions).
- **His find:** "the wireframe face in the preview is rotated 90 degrees clockwise, so it's not actually matching up
  with my face" (the picture itself was right). Cause: the face model reads the face upright but returns its 478 points
  in the camera's sideways frame; the picture was turned and the points were not. The same fault made lean (where
  the face sits in the picture) sideways. **Fixed in `0e999f1f4`** (`FaceFrame` turns every point as the picture turns;
  a neutral saved before it is re-taken by itself, directions kept; a check and a planted fault for each). Not yet seen
  by him on the phone: the phone stopped answering over USB after he re-plugged it (Windows still saw it).
- **His question, how many points:** 478 (468 on the face, 10 for the irises), plus 52 expression values and the head's
  angle. The preview's outline draws about 130 of them (edge, eyes, brows, lips).

## The owner's ideas (11-22), for the Avatar room
1. **Face pins**: tap a point on a face map, then tap the place on the character where that pin should live (a mouth
   corner on the face drives the mouth corner of the drawing); the face map shows how many pins are live. Quick and
   painless. Engine side owed: the chosen points' movement from neutral, measured in the head's own frame (so turning
   the head does not drag every pin), as signals the take records in their own lane. A short named list first (each
   mouth corner, the lips' middles and sides, each lid, each brow's inner, middle and outer, the nostrils, the cheeks,
   the chin), all 478 on request.
2. **A strength slider on each pin** (a multiplier on its range).
3. **Any face signal drives any property**, e.g. eyes wide makes a fire emitter throw more fire. The pilot already has
   links (a signal drives a property by an amount; `al-model.js`, the "links" on the cards); adding the camera's new
   signals to that list makes this work without a new system. The fire emitter on a character is the new part.

## The seam: what the phone room must call
```
onCreate      FaceCapture face = new FaceCapture(this);
              face.setPreview(previewView, /*picture*/ true);       // FacePreviewView; false = outline only
onResume      if (face.hasCameraPermission()) face.start();          // else ask for CAMERA, then start()
              previewView.setCameraOn(face.isRunning());
every frame   Map<String, Float> s = face.sample(System.nanoTime()); // FaceSignals names; drives the solver
onPause       face.stop();                                           // the camera is let go with the room
Neutral       face.setNeutral();                                     // status().capturingNeutral, neutralProgress
Directions    face.checkDirection(FaceCalibration.DirectionCheck.YAW / PITCH / ROLL / LEAN / EYE);
              face.lastDirectionCheck()                              // answer() != 0 when settled; saved for next time
Settings      FaceTuning t = face.tuning(); t.mirror, t.head, t.eyes, t.brows, t.mouth (0..2), t.blinkSync;
              face.setTuning(t);                                     // saved as small JSON
Graphics chip face.setUseGpu(true) (next start); status().onGraphicsChip says where it really ran
Status        face.status(): state OFF / NEEDS_PERMISSION / STARTING / LOOKING / TRACKING, presence, rate,
              delayMs, modelMs                                       // the room says these in its own words
Recording     FacePass pass = new FacePass(take, lanes);            // lanes from FaceSignals.LANES
              pass.add(ms, s) each frame; take = pass.finish();      // one Undo = put the old take back
Editing       FacePass.clearLane / scaleLane / smoothLane / shiftLane(take, lane, ...)
```
The take is the existing `AvatarParamTrack`, so a take recorded in the room plays in Studio through `AvatarItemPuppet`
like any other. The room's solver (the port of the pilot's `al-solve.js`) reads the same names the pilot reads.

**The web pilot:** `tools/avatarlab/al-face.js` (new; `al-inputs.js` untouched). The hook for the AVA-1 lead, written
at the top of that file: add `<script src="al-face.js"></script>` before `al-inputs.js` in `index.html`, and in
`al-inputs.js` `P.watch` replace everything after `catch (e) { return; }` with
`c.face = AL.face.pilotFace(r, c, now / 1000);` (optional second line for two-sided brows in `P.step`).
`AL.face.neutral(cam)` is the one-tap neutral there.

## What is left
- [x] Engine (`app/src/main/java/com/fadcam/ui/faditor/avatar/face/`), pure Java where it can be, each piece
      harness-tested with its planted faults caught first.
- [x] Wired into the tracker, the bus and the pipeline; the old studio, Studio's Record and the webcam bubble still
      read `yaw` and `pitch` as before, now calibrated (type-checked; the cloud build compiles, 11-22).
- [x] The bench screen, reached from the old studio's 🙂 Face test; cloud-built and put on the Note 9 (below).
- [x] The web pilot's face file (`al-face.js`) and the hook, written for the AVA-1 lead.
- [x] **The owner's two minutes in front of the Note 9** (11-22): "Done, all fine"; he found the outline a quarter
      turn off, fixed in `0e999f1f4`.
- [ ] Put `0e999f1f4` (or later) on the Note 9 and let the owner see the outline sit on his face (the phone has to
      answer over USB again first); then the numbers with a face: faces a second and the delay.
- [ ] Face pins (the owner's idea 1 and 2 above): the engine's point signals, then the room's tap-a-point,
      tap-the-character setup with a strength slider.
- [ ] The camera's new signals in the pilot's links list (idea 3), and a fire emitter part if he wants one.
- [ ] The AVA-1 lead adds the pilot hook, and the owner tries the pilot's face on the PC page.
- [ ] The phone room (AVA-1 "path to the phone") plugs in `FaceCapture` as written above, with a masked mouth and teeth
      layers in the character if the owner wants his 11-22 idea (the signals for it exist; the rig part does not).
- [ ] `VideoFaceSweeper` ("From video" in Studio) still writes the old raw values; move it onto the face stage so
      a take from a clip and a live take speak the same names.
- [ ] Full-body tracking (owner's question 11-22): not built. MediaPipe Pose (33 points) and Hands as a separate
      mode with the phone on a stand; the bus already carries `pinTarget.<part>` for the limb solver. Its own job.
- [ ] Studio's Record still replaces the whole take: give it passes through `FacePass` (one file, `FaditorEditorActivity`,
      held by Studio sessions).

## Specs, handoffs and evidence
- Earlier plan: [PLAN_AVATAR_STUDIO.md](../PLAN_AVATAR_STUDIO.md) (A2 tracking, D4 MediaPipe, A4 recorder).
- Evidence: [tasks/screens/FACE-1_20261122/](../screens/FACE-1_20261122/).

## Files and traps
- New files go under `app/src/main/java/com/fadcam/ui/faditor/avatar/face/`. Existing ones touched only where
  the seam needs it.
- `tools/avatarlab/al-inputs.js` belongs to the AVA-1 sessions (walking, voice): never edit it here.
- The camera has one owner at a time (the bus): the bench, the old studio and the bubble never run together.
- The Note 9's screen is set to 1080 x 2220 (its panel is 1440 x 2960): scale taps from screenshots by that.
