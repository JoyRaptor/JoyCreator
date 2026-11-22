# SHRUG-1: shoulder shrugs from the camera

**Milestone:** Avatar Studio (with FACE-1)  ·  **State:** 🟡  ·  **Owner:** "face capture scout" (side session of the 11-22 Avatar lead)  ·  **Checked:** 2026-11-22 against `9991eae1c`

## Why it matters (for the user)
The owner asked while testing face capture on his Note 9 (11-22): "Is there a way for us to get the shoulders animated
for shoulder shrugs?" Shrug, and the character shrugs with you, one shoulder or both, with no setting up.

## Done when
- [ ] Shrugging on the Note 9 (the web pilot in Chrome) shrugs the character: one side or both, like a reflection with
      Mirror on, the other way with it off; a head tilt, a nod, leaning or coming closer is no shrug.
- [ ] Out of the picture, the shoulders ease to rest and a plain hint says so; nothing jerks.
- [ ] Recorded in a take (its own lane), played back with the camera off.
- [ ] A shrug drives any part through Links, and Set up has a Shrug section (how far the arms lift, how far the head sinks).
- [ ] The face's smoothness on the Note 9 measured with and without the body tracker.
- [ ] STATE.md row; FACE-1 card and roadmap row updated.

## Where it stands
Nothing reads the body today: the face tracker sees only the face, and a shrug barely moves the head. MediaPipe's body
tracker (PoseLandmarker, 33 points with both shoulders and ears) runs from the same Tasks Vision bundle the pilot already
loads for the face.

## Plan (11-22)
1. **Signals:** how far each shoulder sits below the middle of the ears, in shoulder widths, against the person's own
   rest (taken at the first still half-second with shoulders in view; Re-centre takes it again). A shrug shortens it.
   The ears' middle barely moves with a head tilt or nod; widths cancel coming closer; leaning moves both. Out come
   `shrugL`, `shrugR` (the character's sides: Mirror on, the person's right shoulder is the character's left) and `shrug`.
   Lost shoulders hold a moment, then ease to rest, as the face does. Pure maths, phone (`avatar/face/ShoulderStage.java`)
   and pilot (`tools/avatarlab/al-pose.js`) line for line, checks written first, planted faults, phone/pilot parity.
2. **The tracker (pilot):** `al-pose.js` runs the lite body model beside the face model on the same video, about ten
   times a second, only while the face camera is on and the **Shoulders** tick box in Perform is on.
3. **The character:** no new rig machinery. A shrug is three Links the pilot already draws: each arm folder moves up
   with its side's shrug, the head moves down a little with `shrug`. Added for you the first time the shoulders are
   seen (amounts from the arm's length and the head's size), shown in Set up as a Shrug section with two sliders, and
   listed in every part's Links, so a shrug can drive anything.
4. **Takes:** a `shoulders` owner, so a pass records shrugs without touching the head, the face or the walk.
5. **Phone half later:** the phone room runs PoseLandmarker in `MediaPipeTrackingSource` and calls the same stage.

## Built (11-22, the web pilot)
| | What | Checked |
|---|---|---|
| ✅ | `ShoulderStage.java` (phone) and `al-pose.js` (pilot), line for line: shoulders below the ears' middle in shoulder widths, against your own rest; Mirror; lost and found; Re-centre | 15 checks in `FaceEngineTest` (a full and a half shrug, one side, Mirror, a head tilt, a nod, closer or aside, still a shrug up close, the rest, a moving body, jitter, lost, back, Re-centre); 13 of 13 planted faults caught (`run-face.sh --proof`); phone and pilot agree on every value (672 shoulder values in the 12,504) |
| ✅ | The body model on a thread of its own (a worker), on the processor | Note 9: screen 60 frames a second and the face 17 readings a second, as with it off; the shoulders 4 to 6 readings a second. On the graphics chip it read 7 a second but dropped the screen to 23 frames and the face to 13.5; on the page's own thread the screen fell to 24 |
| ✅ | Shown with a critically damped glide every frame between readings | browser, frame by frame: a full shrug rises over about a quarter of a second, never more than 0.07 of a shrug in one frame |
| ✅ | On the character: three Links (each arm up with its side, the head down a little with both), added the first time your shoulders are seen; Set up, Body, **Shrug** (on/off, Arms lift, Head sinks, Try); any part's Links can use shrug, shrugL, shrugR | browser: a right-shoulder shrug lifts the character's left arm (a reflection), both lift both, the head sinks |
| ✅ | Perform: a **Shoulders** tick box beside Mirror (kept on the device); a hint when the shoulders are out of the picture | browser |
| ✅ | Takes: shrugs are their own owner (`shoulders`), shown in the Body lane | the pilot's suites with the hooks in: body 184/0, head turn, stage, walk, draw order, scene (simulated time), walk across the stage, walk with your face, face pins 12/0 |

## The owner's first try (11-22, the Note 9, a guided test with a beep per step)
His words: "the shrugs looked a bit too small, and it also did not seem to be terribly smooth. Seemed a little bit jerky."
The numbers: his shrugs reached the full signal on the right side each time (one shoulder 0.19 shoulder widths, both
0.13 to 0.15), so the signal was not small; the arms' default lift was (a fifth of the arm, 11 px on Topps). The
readings came 4 a second and each one was shown as it came, in steps. After a one-sided shrug the other side drifted
up to 0.07 for the rest of the test, and a head tilt gave up to half a shrug.

| | Change | Checked |
|---|---|---|
| ✅ | A glide every frame between readings (above) | browser |
| ✅ | Arms lift about a third of their length (head a bit under half of that): 1.75 times; a character set up before is made bigger once | browser (Topps: arms 19 and 14.5 px, head 8.5 px) |
| ✅ | A full shrug is 0.16 shoulder widths and less than 0.04 is none (was 0.14 and 0.02), from his numbers | PC checks |
| | His look at the smoother, bigger shrug | owed |

## What is left
- [ ] His look at the smoother, bigger shrug on the Note 9.
- [ ] The drift after a one-sided shrug and the half shrug from a head tilt: find the cause (an ear out of view is the
      first suspect: one ear then stands for both) before widening the dead zone again.
- [ ] Phone half (step 5) with the phone room: PoseLandmarker in `MediaPipeTrackingSource`, `ShoulderStage` per reading,
      `FaceFollower` for the glide.

## Specs, handoffs and evidence
- [FACE-1](FACE-1_face_capture.md): the face engine this sits beside (neutral, Mirror, lost and found, passes).

## Files and traps
- New: `tools/avatarlab/al-pose.js`, `app/src/main/java/com/fadcam/ui/faditor/avatar/face/ShoulderStage.java`.
- Small hooks: `al-inputs.js` (owner, feed, take), `al-solve.js` (the mirrored signal set), `al-cards.js` (Links list,
  Shrug section), `al-perform.js` (tick box, lane), `index.html` (script tag).
- The pose model needs the shoulders in the picture: at selfie arm's length they are often cut off at the bottom.
