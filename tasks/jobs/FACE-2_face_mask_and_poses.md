# FACE-2: a face mask and expression poses, so your face drives any creature's head at every angle

**Milestone:** M?  ·  **State:** 🔨 built in the pilot; the face test's head turn fixed 11-27 (his report); his look when everything is done  ·  **Owner:** "face capture scout" (side session of the Avatar lead)  ·  **Checked:** 2026-11-27 against `c841e4711`

## Why it matters (for the user)
Pinning points of your face to a painted, sliced head one by one tears it apart (Topps, 11-22) and has to be redone for
every angle. With a mask you place once per drawing (most of it guessed for you), and a handful of expressions you pose
yourself, your face drives a triceratops, a brachiosaurus whose jaw drops to its neck or a duck-billed archaeopteryx,
at every head angle and in between. The owner: "if we could make this a really easy workflow, then I think we've got an
excellent product that people are going to want."

## Done when
- [ ] On the Note 9 (pilot in Chrome), the owner puts the mask on Topps's Frontal, the other painted angles are guessed
      and he only fixes what reads wrong; he makes his poses once; his face drives Topps at every angle and across the
      head turn without tearing. His verdict recorded.
- [ ] Break test for each stage shown failing first (planted faults), every pilot suite still passing.
- [ ] The format addition agreed in `tasks/avatar/CHANNEL.md` and written into `AVA_FORMAT.md`; the phone half written
      as a spec addition for the phone room (the room's sessions build it).
- [ ] STATE.md row (proof level, entry point, limits).

## Where it stands
- 11-22: his design in two rounds of clickable mockups on the Note 9 (scratchpad, not in the repo), then **"Build it"** (11-27).
  Mockup notes: mask of 20 points (a centre line of 8 for foreheads, brows, snouts and chins; brow, eye, cheek, mouth end,
  jaw each side), five big handles, Snap to parts by slice names, Show every point, Add a point (horn tip), Hidden on
  this drawing, each drawing's REAL turn worked out from its points (his Frontal is a shallow 3/4 to his left, his Right
  Quarter is face on: names and pad positions are only his controls), poses made on one angle and carried, Swap a drawing
  per angle, Test.
- What it builds on: FACE-1 face points (head turn, tilt, distance taken out); round 6's one-piece bend (`al-solve.js`
  sheet pass); step 0's landmarks (`set.marks`, one numbered set per piece, here / behind / not here, `al-format.js`),
  which the head turn already bends through.

## Stage 1 built (11-27): the mask on every angle
| | Built | Check |
|---|---|---|
| ✅ | **Face mask** on the head's card (Set up, Motion); one screen: the head on the stage with the mask drawn inside the stage, the panel below | 1, 9 |
| ✅ | Putting it on: 20 named points linked to face points, on every drawing, guessed (Snap to parts by slice names, else the mask turned), one undo step; the file stays valid | 1 |
| ✅ | Drag a big handle and the points you have not placed follow; a point you placed stays exactly where you put it (his round-2 complaint) | 2 |
| ✅ | Hidden on this drawing (dashed), Not here; Add a point (a diamond, numbered, on every drawing); rename; make a point a big handle; Follow the mask again; Reset this drawing; Looks right | 3, 4 |
| ✅ | Each drawing's real turn from its points (a made-up placing at 25 degrees left and face on is read back within 6 degrees); Adjust / Use the worked-out turn | 5 |
| ✅ | Carried to a drawing with only four points placed, the rest land on its features: the creature's own face shape is learned (error under 0.7 of the plain mask's, under a quarter of the face) | 6 |
| ✅ | The mask points are the head's landmarks: the head turn bends through them | 7 |
| ✅ | One undo step per drag, Undo puts it back; Done closes | 8, 9 |

12 checks, 8 of 8 planted faults caught (nostay, norefit, nohide, noadd, noturn, nolearn, noundo, noentry). Found and
fixed on the way: the view still gliding after the head was chosen made points land 1-2 points off (the view now snaps
when the mask opens or the drawing changes). Rough: a drawing's turn guessed from slice names alone (before any point is
placed) can be far off; it says "?" until three points are placed.

## Stages 2-4 built (11-27): your face drives the mask, poses, across the turn
| | Built | Check |
|---|---|---|
| ✅ | **Test** on the mask screen (Set up too): your face moves the head through the mask; the drawing bends LAST as one piece (several slices together), its outline held; points with no face (your own, landmarks made elsewhere) hold still | 10 |
| ✅ | Your face's movement (head turn, tilt, distance already out) is turned to each drawing's REAL turn: on a profile a smile's sideways pull goes into the depth | 11 |
| ✅ | A pass records the mask (lanes `mk.<n>.x/y`) and the poses (`mp.<pose>`), played back with the camera off | 12, 19 |
| ✅ | **All points** dial (the mask) | 13 |
| ✅ | **Poses** tab: Smile, Jaw open, Brows up, Cheeks puffed, Pucker, Add your own. **Make the face** (a count of three, only the points' movement kept, no picture) | 14 |
| ✅ | **Pose him**: drag his points on one drawing; the pose keeps the move, the mask stays where you placed it; **Start from your face**, **Clear here**, **See it at** | 15 |
| ✅ | Live, how much of each pose your face holds is worked out from your captured faces (a full smile about 1, half a smile about 0.5) and the head takes YOUR pose; what the poses do not explain follows directly (**Directly** dial) | 16 |
| ✅ | A pose made on one drawing is carried to the others by their real turns; a fix made on a drawing stays there | 17 |
| ✅ | **Swap a drawing**: while the pose is strong, a part shows another of its drawings (Topps's Mouth: his smile drawing), carried to the other drawings by name | 18 |
| ✅ | Mid-turn (Frontal toward Left Quarter), the pose bends the mouth where the turn has carried it, not where it rests | 20 |

Timed on the Note 9 (Chrome, a copy of Topps with the mask and five poses): putting the mask on 41 ms; refitting after a
point is dropped (all 7 drawings, poses carried) 22 ms; a frame with the mask and all poses bending 1.2 ms (0.8 without);
reading the face into the mask and poses 0.3 ms a frame.

23 checks; planted faults: 20 (stage 1's 8; nodrive, nopass, noturnbend, norecordmk, nogain2; nocapture, noposemove,
noblend, nocarrypose, noswap, norecordmp; noturnfollow). Limits: your face's depth movement is not used yet (a profile
shows the lift of a smile, not its pull back: the poses are where you say what a profile does); a mirrored drawing (a
`mirror` angle) does not bend by the mask yet; Make the face counts in real time and needs the camera on.

## The face test did not turn the head (the owner, 11-27, relayed by the lead; fixed the same day)
His words: "it doesn't seem to be doing the left to right. It only seems to be going closer and further away. When I look
up or down, it doesn't really look up or down." Measured with a made-up face turned 30 degrees and nodded 20: in Perform
the head turns (the head pad reaches about 0.9 and Left / Right Profile, Upward, Downward show); but the **Test** button on
the Face mask screen and on Face pins (Set up) passed on only the face's points and expressions, never the head's turn and
nod, and the mask screen also held the head on the drawing being placed: the pad stayed at 0 and Topps on Frontal. In
that Test the only thing that moved was the face bend (which also shifts a little as you lean in and out, from the
camera's perspective). **Fix:** while Test is on, your head's turn and nod move the head pad and your tilt the head, as in
Perform (from where the pad was when Test began; Stop puts it back); the mask screen lets go of the drawing it was holding
so the pad picks the angle (its points are hidden meanwhile) and comes back to it on Stop. Check 21 (the mask's Test and
Face pins' Test; Stop) failed first on the old code (pad 0, Frontal at every turn) and passes now; planted fault
`noheadturn` caught. The made-up face of the break test now has open lids: a face with no lid gap kept being taken for a
new neutral, which washes any held turn back to straight.

## For the phone room (the spec addition; the room's sessions build it)
**Built and landed 11-27 evening (f9c208acf):** `solve/MaskBend` + `Frame` (the pass, mid-turn, swaps) and `face/MaskDrive` (the lanes from your face, poses by NNLS); Topps wearing the pilot's mask matches the pilot within 0.005 px (`tasks/avatar/golden/topps-mask.*`). The mask is still made in the pilot: placing and posing it on the phone is not built.

- **Data** (the turn set, `joycreator.character/2`, kept by both checkers as they are): the mask points are ordinary
  landmarks of `marks['']` with extra fields `key`, `face` (MediaPipe indices), `handle`, `mask` (an added point), `xyz`
  (its 3D place, mask units). `set.face = { turn, mine, hid, ok, fit: { <angle>: [yaw, pitch, sx, sy] }, gain, direct,
  poses: [{ id, name, cue, idea, face: { <n>: [x, y] }, at: { <angle>: { <n>: [dx, dy] } }, swap: { <angle>: { <set>: <kid> } } }],
  posed: { <pose>: { <angle>: { <n>: [dx, dy] } } }, swapped: { <pose>: { <set>: <kid> } } }`. `fit`, `posed` and
  `swapped` are written when the mask or a pose is edited (al-mask.js `carry`, al-mask-poses.js `carry`): the phone READS
  them every frame and works nothing out.
- **Lanes** (the face's, recorded and played back): `mk.<n>.x/.y` (what the poses do not explain, mask units, times
  `direct`) and `mp.<pose>` (how much of each pose, 0 to 1.5). Made each frame from the face points (`al-mask.js drive`:
  the character mirrors the front camera, its right side following your left; mask units = 0.9 between the eyes).
- **Solver pass** (al-solve.js, after a drawing's own moves, shapes and the face-pin sheet; the last thing): for a drawing
  of a turn set that has `set.face`: each point of its row that is here (not behind, not off) is a control point at its
  place (carried by the turn's own landmark bend mid-turn); its move = `fwd(R(yaw, pitch), [mk.x, mk.y, 0])` scaled by
  `(sx, sy)` times `gain`, plus the sum over poses of `mp.<pose>` times `posed[pose][angle][n]`; points with no face
  move 0. Bent by rigid moving least squares with weights 1 / (distance squared + 1) squared, held at the drawing's edges
  (a ring of still points 4% outside its rest bounds, seven a side), worked out once a frame on a lattice of about 16
  units a cell that every slice reads (so slices cannot tear). Swaps: a set listed in `swapped[pose]` shows that kid while
  `mp.<pose>` is over 0.5.

## The plan
**The mask IS the head's own landmarks.** Mask points are the whole-head piece's numbered landmarks (`set.marks['']`)
with a name, a key (`eyeL`) and the face point they follow; so placing the mask also gives the head turn its landmarks.
Which places are the user's and which are guessed, each drawing's real turn, and the poses go in a new `set.face`
(proposed in the channel; unknown fields are kept by both checkers).

1. **Stage 1, the mask on every angle** (`al-mask.js` model, `al-mask-screen.js`): the 20-point template; handles refit
   only the points you have not placed; a point you place stays put; hidden here; add a named point; Snap to parts
   (naming guide); every drawing's real turn worked out from its points (Adjust); the mask carried to the unplaced
   angles in 3D, learning the creature's own face from the drawings placed. Entry: the head's card, **Face mask**.
2. **Stage 2, your face drives the mask**: each mask point follows its face point's 3D movement (head turn, tilt,
   distance already out), turned to the drawing's real turn, scaled to the drawing; the drawing bends last as one piece
   (round 6's pass, edges held; added points hold still). All points dial; Test. Recorded and played back (the face
   points are the take's lanes).
3. **Stage 3, poses**: Make the face (your face's points at that moment, nothing else kept) and Pose (drag mask points;
   the head bends as one piece), made on one angle and carried by real turn, fixed per angle; Swap a drawing per angle.
   Live: how much of each pose your face holds now (fitted from your captured faces), blended; what the poses do not
   explain can still follow directly (a dial).
4. **Stage 4, across the turn**: with the mask as landmarks, in-betweens bend through the same points; check that
   face and poses carry smoothly from Frontal to Left Quarter, Upward, the profiles.

## What is left
- [x] Stage 1 · [x] Stage 2 · [x] Stage 3 · [x] Stage 4 (in the pilot) · [ ] format addition agreed in the channel · [x] phone spec addition (above) · [x] timing on the Note 9 · [ ] his verdict (11-27: "Works well" was NOT about the whole: the owner, 11-27, relayed by the Avatar lead: "As far as the face test goes, looks all right, except it doesn't seem to be doing the left to right. It only seems to be going closer and further away. When I look left to right, it doesn't look left or right."; being fixed) · [ ] the phone room builds its half

## Specs, handoffs and evidence
- FACE-1 card (rounds 1-6, his words), `tasks/avatar/AVA_FORMAT.md` B2.1 (landmarks), `tasks/avatar/CHANNEL.md` (claims).

## Files and traps
- New: `tools/avatarlab/al-mask.js`, `al-mask-screen.js`, `al-mask-poses.js`, `al-breaktest-mask.js`; small hooks in
  `al-solve.js` (the mask bend before a drawing is drawn), `al-cards.js` (the entry), `index.html`.
- `al-solve.js` is being ported to Java (X2, golden files `tasks/avatar/golden/`): every behaviour added here must be
  written into the spec for the port; the golden cases have no mask, so they do not change.
- Trap (11-27): the pilot autosaves into the page's own database (IndexedDB, not only localStorage); a test run that
  ends mid-edit leaves a half-tested character that the next suite opens. Clear both before each suite.
- Trap (round 5): measure what is drawn, never the numbers set. Trap (round 6): a context field must be passed down in
  `ctx()` or children never see it.
