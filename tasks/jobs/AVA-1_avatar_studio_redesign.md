# AVA-1: Avatar Studio redesign (a real character studio, simple on a phone)

**Milestone:** after launch (Avatar stays hidden for launch, see ANI-1)  ·  **State:** 🔨 web pilot built, owner's look owed  ·  **Owner:** "Avatar Studio" session (Claude Fable 5.2, owner-asked 11-18)  ·  **Checked:** 2026-11-18 against `ffb3af63b`

## Why it matters (for the user)
The owner opened Avatar Studio as a newcomer and could not tell what it does: a 3x3 grid, yaw and pitch,
"part", and taps that sometimes did nothing because a cell was or was not armed. He wants one room where a
whole character, or only a face, is rigged with everything the app already has, more powerful than
Character Animator, Cartoon Animator and Moho for this job and far less tedious, and droppable into Studio.

## Done when
- [ ] The owner has tried the web pilot with his own Topps file and said what to keep and change.
- [ ] The agreed design is written as the phone spec (screens, the character file, the port map).
- [ ] The phone room is rebuilt to it, reusing the engines named in the pilot's README, and phone-proven.
- [ ] A character made there drops into Studio with its take and still takes a hand-drag there.
- [ ] STATE.md rows updated at each of those steps.

## Where it stands (11-20)
- **Web pilot built** in `tools/avatarlab/` and published as a private Claude artifact. Agent-checked in the
  in-app browser at phone size (411x846) and sideways (846x411); not on a phone, not tried by the owner.
- **Topps opens** (the owner's Character Animator PSD, 191 layers, read by a new dependency-free PSD reader):
  rigged from layer names alone into a head of 7 angles, eyes that follow and blink, a mouth on a hinge with
  "smile" as a button, two arms of 8 drawings each (the right arm's behind-the-body drawings folded into the
  same fan, keeping their place in the stack), a breathing chest, planted legs and a swinging tail. All 48
  arm pins and the mouth hinges were guessed from the pixels.
- **Checked by script in the browser:** record a take, record a second pass that replaced only the hand,
  replay, save the character with its take, reopen it (140 drawings, 60 frames), bake a 5x5 sprite sheet with
  a Sprite Lab sidecar, undo a pin move.
- **Four ways in, each run once:** PSD (Topps), a sheet (tap the parts: body, head and a 7-drawing arm became
  a working character with no pin placed), one whole drawing (7 taps, then it bends), and Pip the built-in.

- **Added the same evening, on the owner's ask (masks, lids, swinging, wind):**
  - Masks: any part can show only inside another part or be hidden where another part is; the masking part
    is drawn or invisible, and moves like any part. Pip's pupils now stay inside the whites of his eyes.
  - Eyelids without art: an upper lid (relaxed), its tilt (angry, sad) and a lower lid (cheery), as invisible
    lids that cut the eye (Topps) or skin-coloured lids inside the eye white (Pip). Seven mood buttons in
    Perform. Every imported character gets lids by default; "Remove eyelids" takes them away in one tap.
  - Swinging comes in two kinds: bendy (hair, braids, feathers, tails: a jointed chain) and stiff (chain
    links, earrings, charms: never bends, swings and rattles, links hang from each other). Pip has a hair
    tuft and a two-link chain with a charm to show both.
  - A Breeze slider in Perform leans and waves anything with Breeze turned on; it is recorded in a take.
  - Checked by script: lids on and off with undo, a take that carries mood and wind, save and reopen keeps
    lids and a stiff tail, all cards open. Seen in stills: five moods on both characters, a mirrored angle,
    the chain swinging and settling, hair and tail leaning in wind. **Not seen in motion by a person.**

- **Round 3, 11-19, from the owner's first hands-on (Topps and Pip on the PC and the phone):**
  - **Rulings:** Topps ships as the demo character. The magnifier while dragging a pin is wanted on the phone.
    Undo: hover names the step, hold opens the history, as in Studio. Blink: tap blinks, hold keeps the eyes shut.
    The whole-character turn must offer "instant" beside the cardboard turn.
  - **Fixed (his reports):** a chosen arm drawing was shown bent to the resting hand, and drawings that pass
    behind the body were hidden behind it, so they could not be tuned: now shown as drawn, in front, with a
    "Try this drawing" button that plays just that one. Lids tilted the same way on both eyes at side angles
    (pouty instead of angry): the two eyes are now a mirrored pair at every angle, with a per-angle "tilt the
    other way". A flash when Pip blinked (a lid lost its mask for the blink): fixed. Hair "freaked out" when
    the head swapped angle: the new drawing now starts in the bend and at the speed the last one had. The
    magnifier did not follow the pin: fixed.
  - **Built:** walking by dragging the body (planted feet, steps or hops, side view or from above, tuck or
    hang when lifted, fall and land); legs that bend; many fingers at once in Perform; bridges that warp one
    head angle into its neighbour from matched landmarks; in front / behind per angle; a pad for any set of
    angles; the face camera "while talking", steering from where the hand left off; an elbow/knee flip helper
    on the stage; undo history. Topps is inside the published page and opens first.
  - **Checked by script and in stills:** Topps walks right and left, turns to face, tucks when lifted, falls
    and lands; a walking take replays to the same footfalls; bridge points warp the face; every card opens.
    **Not checked:** any of it in motion by a person, many fingers with real fingers, the face camera.
  - **Round 4 (11-20): the Codex audit, competitor research, general tools, and a second character.**
    - **Audit fixes, data loss first.** A take is saved with the character and comes back after a reload. Recording
      over a take is punch-in, punch-out per owner of a signal (head, mouth, mood, blink, each hand, body, each
      button, each pin), handed back over a quarter second, and one tap takes a whole pass back. An unfinished
      landmark is never saved (two taps, one undo step). Adding a drawing to an arm no longer re-guesses pins that
      were set. A file is checked before it replaces anything; a bad one changes nothing. Backing out of "Pin one
      whole drawing" gives back what was there. A hat beside the head angles is copied onto every angle instead of
      dropped. The take strip can be dragged, and nudged with the arrow keys. The sheet cutter only removes
      background that touches the edge. Pictures that share one canvas keep their places. The sprite sheet covers
      the whole take, framed on everything it reaches, saved by two taps. The arm that comes round from behind is
      cut exactly on a line, and a hand it carries goes behind the body with it. A saved character is now
      `<Name>.character.json` (format `joycreator.character/1`), so it no longer shares a name with the phone
      app's older `.avatar.json`; older pilot files still open.
    - **From the research** (complaints about Character Animator, Cartoon Animator, Moho and Live2D; sources in
      the session, not all pages could be read): **Update the art** re-opens the repainted layered file and keeps
      every pin and setting, with new layers landing in Extras; run-together layer names ("LeftArm", "SadMouth")
      are read; clipping and blend layers that were read as plain layers are counted and said.
    - **General tools. None knows what character it is on; each has a control on a card.**
      1. **Loop**: a part whose drawings are frames (`pick.by:'cycle'`; on a limb, `pick.loop`).
      2. **Rubber-hose limb**: no elbow; it bows, stretches and wobbles (`limb.style:'hose'`), with a pin for
         where the hand rests.
      3. **Carried by**: any part rides the end of a limb or of a swinging part, turns with it on a loose wrist,
         and can swap drawing by the way the limb points (`ride`, a set by `'dir'`).
      4. **Stream**: one part sent out as copies that drift, grow and fade (`emit`).
      5. **Round head**: one drawing turns like a ball. Parts are painted on (they slide and foreshorten), ride
         it in one piece (an eye with its lids), or stick out and go behind (`ball`, `wrap`, `flat`).
      6. **Follows**: a button as a smooth level, "all the time", shake, spin, and Bouncy (overshoot and settle).
      7. A part's own Size, Turn and Mirror; a button for each look of a part; an even-grid cut with "one part
         that swaps" and "frames of a loop".
    - **Joybot draft**, built from those tools and pictures only (the owner's lamp, ear and mouth sheet; the kit's
      shell, gloves, arc and ring frames): real upper and lower lids inside each lamp, moved by moods, blinks and
      the face camera; eyes that swell and squash; a head that turns like a ball with ear plates that are thin
      from the front; hose arms of looping plasma; gloves chosen by arm direction on loose wrists; a ring stream
      that rounds out on a "Nervous" button; a pendulum peg. He is in the owner's private page and in
      `tools/avatarlab/local/` only. **His art is not in the repository.**
    - **Checked by script and in stills:** every card opens for Topps, Pip and Joybot without an error; takes
      punch in and out and survive a reload; the seam, riders, streams, loops and the ball by numbers; Joybot in
      an eight-pose sheet. **Not checked:** any of it by a person or on a phone. The 36-mouth sheet the owner
      pasted is not on disk, so it is not in Joybot yet.
  - **Round 5 (11-20, later): the owner's first hands-on notes on round 4, and Joybot again.**
    - **Match two angles, put right.** The owner called the two-tap flow a regression: the drawing swapped under his finger
      and only one drawing's dots could be moved. Now one drawing is solid and the other a ghost, and nothing swaps: tap the
      spot on the solid drawing, then on the ghost. The first drawing is always blue (dots and ghost), the second always
      pink. Every dot of both drawings can be dragged at any time; where two overlap the solid drawing's dot is picked up.
    - **Each piece of the head can warp by its own landmarks.** A frill, a horn or a snout that exists by name in both angles
      can be matched alone (everything else steps back), so it bends on its own layer and slides over the rest. A piece
      warps with the whole head (default), by its own landmarks, or with another piece (`bridges[].part`, `set.warpWith`).
      The head pad now shows on every face-part card and every layer card inside the head.
    - **Shapes.** Any part, or a folder of parts, gets pins and any number of named shapes posed by dragging them. A shape
      is blended in by a signal or a button and can be bouncy, so shapes melt into each other (`shapes[]`, pins in `mesh`).
    - **Blend two looks.** Two drawings of a part that swaps can be matched with landmarks the same way; changing look then
      bends one into the other while they cross-fade. Looks can be renamed, mirrored, removed, added, and given a new
      picture.
    - **Walking.** Ground is a switch in Perform: on, there is a ground line where the character stands and pulling it up
      is a jump; off, up and down the stage is walking too. It goes up on its toes before its feet leave the ground, folds
      its legs going up and swings them forward coming down. A foot bar (heel to toe, two pins, guessed from the art) keeps
      the foot level and rolls it. Walk settings are in Perform and on the Body card: stride, step height, body bob, step
      (heel first to tiptoe), heel to toe, lean, arm swing. A character with no legs can float where the body is dragged.
    - **A small preview beside every part** in the layer list, the Extras list and every part picker; a mirror icon; each
      drawing of a hand that swaps by direction shows its picture and has its own Mirror.
    - **Round shapes, more than a ball.** A part can turn by its own outline (an egg, a pear), be spun right round with a
      slider in Perform, carry parts on its back, and painted-on parts can keep their shape instead of squashing. A part
      can be inside two masks at once. A link can rock back and forth by itself. Hose limbs have a thickness.
    - **Joybot, third draft.** Head and body are two round shapes on the egg's own outline, so the head turns far more than
      the body; the shoulders sit on the body's surface and the arms and hands go behind the shell when turned away; he can
      be turned right round to his backpack. The hover cone is one pendulum that lags and overshoots. Eyes change shape with
      seven shapes on nine pins; all 36 of the owner's mouths are in (14 speech shapes, 11 on buttons). Plasma arms are
      painted thick. Still local only; his art is not in the repository.
    - **Checked by script and in stills:** every card opens for Topps and Joybot without an error in the built page; the
      two-tap flow by script; a frill moving 15.8 px by its own landmarks while the face moves 5.2 px; a shape blending
      in and out; walking, tiptoe, jump and landing in rendered strips; the hover cone lagging 13 degrees, overshooting 20
      and settling. **Not checked:** any of it by a person or on a phone; the feel of the walk; blending two looks by
      hand; the foot-bar guess on art other than Topps.

## What the pilot settles (proposed, the owner rules)
1. First screen: a dotted body with a + on every part, and three doors for bringing a whole character in.
2. Two modes with one meaning each: **Build** (everything still, pins draggable) and **Perform** (everything
   alive). No arming. "Try it" brings the stage to life from inside a part's card.
3. A body map to say which part; one card per part that says in a sentence what the part does.
4. Head angles are dots on a pad (any number, anywhere, mirrored when a side is missing), not a 3x3 grid.
5. Arms: several drawings swap to the nearest hand and bend the rest of the way; pins are guessed.
6. Mouth: a hinge, a squash, or shapes picked by the voice; other mouths are buttons.
7. The whole character can turn around when the head turns the other way.
8. Takes are layered: a second pass replaces only what you touch.

## Round 6 (11-20, evening): the pop, copying an angle, turning round
The owner: "almost ready to port it over to the phone". Three things first.
- **The pop at half way (fixed).** A piece matched with landmarks slid right, jumped back left at the swap and
  slid right again. Cause: the depth slide (a guess at how far a layer travels in a turn) was still added on top
  of the landmarks, from each side in opposite directions (about 2 px each way at depth 0.6, 4 px across the
  swap, far more when zoomed in). Now the slide keeps only the part of the turn that no matched neighbour
  accounts for. Measured: a piece with its own landmarks steps 0.00 px across the swap. Pieces with no landmarks
  at all still slide and cross-fade as before.
- **Copy an angle.** Head card, "This angle": Copy this angle, Rename, Remove, "Tip the whole angle", "Size of the
  whole angle". The copy is complete (every layer, mask, pin, shape), arrives matched to the angle it came from,
  and its dot is ready to drag. Between dots the tip and size blend (measured 0.01 px step at the swap). A
  mirrored angle can be copied and tipped too, but still follows its source drawing.
- **Turning right round.** Three ways: Smooth (new, the usual one), Instant, A card. Smooth: the body tucks toward
  its middle for about 3 frames, swaps side while tucked and opens out; a head with both sides drawn keeps its
  width and simply keeps turning. Sliders: how long, how far it tucks. Not built: drawn in-between poses for the
  body (a front view of the body used during the turn), which is what a studio would do.
- Proof: numbers by script and one rendered strip. Nobody has driven it.
- For the phone, noted from the owner: Topps opens ready to play; Joybot flies from his corner to the menu and
  circles "new character". That animation belongs to the Joybot sprite-sheet lane, which already knows how he moves;
  this lane supplies the target (which button, when).

## Round 7 (11-20, night): stances, any number of legs, colour sets, fitting by hand
The owner's notes after round 6, plus a note sent mid-build ("some characters start out as quadrupeds; as many legs as
they want, a centipede; arms that can become feet with one toggle").
- **Fit it by hand** (`al-fit.js`). *Pick a piece* on the stage bar: tap any layer on the picture, it is chosen alone and the
  rest steps back. A transform box (drag inside to move, a corner to resize, the round handle below to turn) on any layer,
  folder, head angle or whole stance; the part keeps a move, a turn and a size (`dx`, `dy`, `rot`, `sc`) and "Put it back"
  clears them. *Reshape with pins*: tap the part to drop pins, drag one, the others hold (a Shape that is always on).
  A whole head angle now has the box too, and its move blends between dots like its tip and size.
- **Stances** (`al-stance.js`). A whole-body pose with its own button: each part moved, turned or resized by a set amount,
  optionally swung round a point so it travels an arc. Eased on a spring. While a stance is being worked on, the box
  writes to the stance. *All fours* is worked out from the art: the torso tips about the hips, the legs and tail are
  turned back so they stay, the head rides down on the neck and stays level, the shoulders are slid to where front
  legs stand. Pushed low on the ground line the character goes down by itself; pulled up it stands again.
- **Any number of legs** (`al-walk.js`, rewritten). Every limb that walks takes part, each with a step timing; feet with
  the same timing step together and the rest take turns in order, so a biped alternates, four legs walk hind-far,
  fore-far, hind-near, fore-near, and a row of legs moves as a wave. *Copy this leg* on any leg card; *Move the whole
  leg*; *Step timing*; *Lets go when lifted*. An arm told "these arms can become feet" plants whenever the ground is
  within its reach and is an arm again when lifted out of reach: no other switch. The body map can be switched to a
  four-legged one; every limb is listed on the Body card.
- **Colour sets** (`al-tone.js`, a second shader program in `al-gl.js`). "This colour becomes that colour" by hue, greys
  left alone, arriving as a wave with a blotchy front. *From a picture*: the picture is laid over the character and every
  spot votes, so each main colour of the art becomes what the picture has there. Buttons in Perform; sets take turns.
- **The body's own turn drawings.** Body card: the body becomes a set of angles on a pad (add pictures, copy an angle,
  mirror), following the head by a chosen amount; with both sides drawn it keeps its width in the Smooth turn-around.
- **Fixes.** The tail no longer spins when the character is picked up (a big sudden move of its root is mostly carried,
  its speed is capped, and "swings at most" now holds for bendy parts too). Elbows and knees are round (each point is
  turned about the joint by an in-between angle; denser mesh on limbs). Coming down from a jump the legs reach for
  the ground as soon as it starts down. A hand changes owner (finger, rest, ground) over a few frames, not in one.
- **Talking from the side.** Topps's side-view mouths are small in the file; the Jaw card has "The face stretches with
  it" (a Shape called Talking on each face drawing). On in the built-in Topps.
- **Joybot's button** is in the upper right corner, the Studio's orb with his own face (the app's `joybot_neutral.png`).
  In the pilot it lists things to do with "take me there"; on the phone it opens him.
- **Built-in Topps** now has: All fours, a Coral colour set made from the owner's reference picture (plus one stop added
  by hand for the red marks), the jaw drop, the tail's swing limit at 60 and the right arm resting on drawing 5 ("04").
- Proof: 588 card opens without an error; the box by numbers (move, 20 degrees, 1.25x, pivot drift 0); rendered strips
  of the stance going down, the four-legged walk and its step order, a third and fourth leg stepping in a wave, the
  push-low and pull-up, a jump and landing, the colour wave. **Not checked:** anything by hand or on a phone; the
  feel of any of it in motion; the all-fours pose is a first guess the owner means to doctor (the front legs overlap
  the far hind leg; the far arm's half-behind split shows a notch); "From a picture" on other art.
- For the port: stances and colour sets are plain numbers in the character file; the walker is arithmetic; the colour
  program is a second, small shader used only while a colour set is on (the Note 9 rule: program size is the cost).

## What is left
- [ ] Owner tries the pilot (PC page with Topps; the artifact on the phone with Pip) and rules.
- [ ] Not built in the pilot: clipping layers and blend modes from the PSD (Topps uses neither); eye whites
      (built since: see the masks note above); legs that bend and walk cycles; hands tracked by camera; a motion library; saving a
      video; per-drawing art nudge for a mouth that sits wrong at one angle is manual (Move art).
- [ ] Not checked: face camera with a real face; microphone with a real voice; any phone.
- [ ] Topps notes for the owner: "smile" sits off the face at Left Profile, Left Quarter and Frontal in the
      file itself (it is aligned at Right Quarter); the Upward mouth is hidden in the file and stays hidden.
- [ ] Joybot: the owner's verdict on the third draft (cuteness, hands, eyes, arms). The shell, badge, backpack, gloves
      and ring frames are still the kit's; the eyelid is cut from his half-lid lamp.
- [ ] The owner's verdict on the new Match two angles, per-piece warps, Shapes and the walk.
- [ ] The owner's verdict on round 7 (all fours and the four-legged walk, the box, reshape, colour sets, more legs,
      the body's own angles, Joybot's button). Not built: drawn in-between poses used during the turn-around.
- [ ] The owner's verdict on round 6 (the pop, Copy this angle, the Smooth turn). He said the pilot is nearly ready for the phone;
      the phone spec starts when he says go.
- [ ] A blank start: the first-run door and the guided path for someone with no art ready (answered in chat 11-20, not built).
- [ ] From the audit, before the port: one versioned character format shared by the web pilot and the phone,
      with round-trip test files; the list of native engines each tool maps to; a closed-loop phone test
      with Topps; soft-edged masks checked on the phone's GPU; the voice recording kept with a take; camera
      and microphone let go when the room is left.
- [ ] Asked for elsewhere and not built: pose keys beside performed takes; a library of saved looks.
- [ ] Then the phone spec and the port. Engines to reuse are listed in `tools/avatarlab/README.md`.

## Specs, handoffs and evidence
- Pilot and its README: `tools/avatarlab/`. Earlier plan: [PLAN_AVATAR_STUDIO.md](../PLAN_AVATAR_STUDIO.md).
- Related: [ANI-1](ANI-1_puppets_and_sprites.md) (puppets, sprites, the launch decision for Avatar),
  JoyBrush boards JB-7.01 to 7.04 (draw a character in JoyBrush and send it here).

## Files and traps
- New files only: `tools/avatarlab/**`. The owner's PSD is never committed (`tools/avatarlab/local/` is ignored).
- The pilot's solver is the reference for behaviour, not a second engine to ship: the phone reuses
  `PuppetPoseResolver`, `PinWarpStrip`, `FabrikSolver`, `DangleSim`, the puppet mesh engine and the tracking bus.
