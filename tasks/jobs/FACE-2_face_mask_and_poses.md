# FACE-2: a face mask and expression poses, so your face drives any creature's head at every angle

**Milestone:** M?  ·  **State:** 🔨  ·  **Owner:** "face capture scout" (side session of the Avatar lead)  ·  **Checked:** 2026-11-27 against `c841e4711`

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
- [ ] Stage 1 · [ ] Stage 2 · [ ] Stage 3 · [ ] Stage 4 · [ ] format addition agreed · [ ] phone spec addition · [ ] his verdict

## Specs, handoffs and evidence
- FACE-1 card (rounds 1-6, his words), `tasks/avatar/AVA_FORMAT.md` B2.1 (landmarks), `tasks/avatar/CHANNEL.md` (claims).

## Files and traps
- New: `tools/avatarlab/al-mask.js`, `al-mask-screen.js`, `al-mask-poses.js`, `al-breaktest-mask.js`; small hooks in
  `al-solve.js` (the mask bend before a drawing is drawn), `al-cards.js` (the entry), `index.html`.
- `al-solve.js` is being ported to Java (X2, golden files `tasks/avatar/golden/`): every behaviour added here must be
  written into the spec for the port; the golden cases have no mask, so they do not change.
- Trap (round 5): measure what is drawn, never the numbers set. Trap (round 6): a context field must be passed down in
  `ctx()` or children never see it.
