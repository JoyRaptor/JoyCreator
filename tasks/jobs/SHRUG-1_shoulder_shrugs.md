# SHRUG-1: shoulder shrugs from the camera

**Milestone:** Avatar Studio (with FACE-1)  ·  **State:** 🔨  ·  **Owner:** "face capture scout" (side session of the 11-22 Avatar lead)  ·  **Checked:** 2026-11-22 against `bdf9f704a`

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

## What is left
- [ ] Steps 1 to 4 above, then the owner on the Note 9.
- [ ] Phone half (step 5) with the phone room.

## Specs, handoffs and evidence
- [FACE-1](FACE-1_face_capture.md): the face engine this sits beside (neutral, Mirror, lost and found, passes).

## Files and traps
- New: `tools/avatarlab/al-pose.js`, `app/src/main/java/com/fadcam/ui/faditor/avatar/face/ShoulderStage.java`.
- Small hooks: `al-inputs.js` (owner, feed, take), `al-solve.js` (the mirrored signal set), `al-cards.js` (Links list,
  Shrug section), `al-perform.js` (tick box, lane), `index.html` (script tag).
- The pose model needs the shoulders in the picture: at selfie arm's length they are often cut off at the bottom.
