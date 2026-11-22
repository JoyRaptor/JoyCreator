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
- [x] Round 7b (11-20, late): his report that both arms sit in front of the head on all fours. A stance can now put
      a part behind or in front of another part (card: "In front or behind, in this stance", then tap the other part;
      "Changes over at" picks the moment). Built-in Topps: both arms behind the head on all fours. Checked by a
      rendered strip and a scripted pass through the card (set, undo); not driven by hand. Placing a part for one
      stance only (move, turn, resize) was already there from round 7; the stance note now says so.
- [x] Round 7c (11-21): a limb that is behind a part in a stance can bring its hand or foot back in front
      ("The hand comes in front", "Comes in front from"; `z.tip`). In a stance, Pick a piece takes the whole part
      on the first tap (the whole head) and the layer on the second. Built-in Topps: near hand in front on all fours.
      His report of a broken crest on the phone was NOT reproduced (the built-in crest is whole at every angle
      here); the likeliest cause is the crest layer moved with the box outside a stance. His file is needed to be sure.
- [x] The crest and the "limp" (11-21, from his own saved file): the crest layer carried a 99 degree turn saved
      outside any stance (how it got there is not known; the box's turn handle hidden behind the stage buttons is the
      likeliest). The raised paw was the far front paw: hands that walk stood a guessed palm's height above the
      ground. Built-in Topps now has both hands on the ground (`pick.walk.palm = 0`); a repaired copy of his file
      went back to him (crest upright, far paw down, round 7b/7c order added, which his older copy did not have).
- [x] Round 8 (11-21), his rulings on the audit: the small body map stays; the header gains shortcuts for the card in
      hand; Stances go first on the Body card; "On four legs" puts the character in its all-fours stance; the drawer
      becomes three file buttons plus a row of characters; on/off is a tick box and a sliding switch means two equal
      choices; in front / behind gets one control (the stack, his ideas still to come). Built: all of those, folding
      sections, the picture fitted clear of the hint and the stage buttons, a card opens at its top, a stance's
      controls on a part chosen from the body map, the save dot no longer says "saved" for an untouched character.
      Checked in the page by script and by screenshot at phone size; not by hand, not on a phone.
- [x] Round 9 (11-21), from his second pass on the phone. Fixed: his browser's own Topps is put right once when it
      opens (crest, arms behind the head; one undo step); a walking hand's height is guessed against the feet, so the
      far front paw no longer hangs; a small tip no longer slides the arms off the shoulders; in a stance Build shows
      the hands planted as Perform does; pressing a button no longer scrolls the card to its top; a part brought into
      focus in a stance is framed where it is drawn, clear of the stage buttons. Built: "Move it" on the stage for any
      part (and the box on the eyes, mouth, brows, nose and frill cards); drag in the stack; a Ground bar on the body
      map opening one walking card; sections open by default with the seldom-used ones behind one "More"; the pictures
      he approved on their controls. His order for a card, to hold everything to: bring it in, wire it up, make it look
      right in one place, then in every pose, then moving, then playing. Checked by script and phone-size screenshots.
- [x] Round 10 (11-21), "really think through the UI": the room is re-laid in the order a character is made, as four
      stages: Set up (pins), Fit (the box, per pose), Motion (alive), Perform. Every section of every card is assigned
      to a stage; the floating modes (Try it, Move it, Pick a piece, stance editing) became places. Design and the full
      map of where each control went: [the four stages](../design/AVA-1_stages_20261121.md). The earlier layout is kept
      and selectable. Checked by script for every part in every stage (taps, drags, stances, undo, switching layouts)
      and by phone-size screenshots; not by hand, not on a phone. **His verdict on the structure is owed.**
- [x] Round 11 (11-21), his first pass through the four stages. He keeps them, likes that the long-card layout is
      still offered (keep it as a user option), and approved every picture (gait as footprints going across).
      Fixed: in a stance Fit now shows a limb exactly as Motion does (planted, on the one drawing it stands on; the
      drawing last looked at no longer overrides it); the stray "Move art" button is gone from the stages layout (it
      moved the standing arm from inside a stance); a part behind another shows as a cyan echo over the picture;
      "Hips come down" is shown as he slides it (hips sink, feet stay); a tap in Motion chooses a part without taking
      hold of it; turning to walk the other way turns the head with it unless a finger holds the head; stack tiles
      are drawn as the stage draws them (no white patches). Built: "The drawing it stands on" (a hand on the ground
      uses one drawing, chosen there); "Showing now" on a limb's Motion card with Flip and Mend its pins for the
      drawing in use that instant; "Hold this pose" (freeze the living pose and mend it in Set up); dipping between
      Fit and Motion keeps the stance, the part and the scroll, with a Back button; "Rise and fall" of the walk from
      flat to cartoony. Checked by script and screenshots, not by hand.
- [ ] **Codex pre-port review (11-21)**, branch `codex/avatar-round10-review-20261121`, file
      `tasks/audits/codex/Avatar_Studio_Round10_Preport_Review_20261121.md`. Its advice: keep the four stages; before
      porting the editor, fix the work-loss bugs and prove one editable character reaches a Studio video.
      Fixed in round 11 (in this lane's own shelf code): a character opened from the shelf keeps its take; nothing
      is dropped off the shelf at twelve; a save that failed is said before anything replaces the character, and the
      work stays marked unsaved; a damaged shelf entry no longer empties the open character's pictures; Open, Save
      and Send are 44 px targets, the stage row 36 px. **Not fixed, not yet verified by me:** a damaged FILE import
      emptying the open rig's art; a take on an untouched built-in getting no shelf entry; sprite bake leaving out
      colour changes; a late microphone permission starting capture after leaving Perform; Record ignoring the
      timeline position; a repainted PSD layer that moved leaving its pins behind; the take clock clamped with the
      frame step; a new pin track written back over earlier frames; saving re-encoding every image (181 to 400 ms);
      still stages redrawing every frame; sheet focus and Escape; the landscape scrubber.
- [ ] **For the phone, his idea (11-21): send one part to JoyBrush and back.** Select a part, "Send to JoyBrush": the
      hand-off animation, a fresh canvas with that layer at pixel size, and a button on the board that remembers
      where it came from ("Send back to Avatar Studio"). Coming back writes the layer into the layered file where it
      was, and the puppet refreshes. Not buildable in the web pilot; it rides on the FX-1 hand-off moves.
- [x] Rounds 12 and 13 (11-21/22): the part in hand is shown along its own outline, in five looks to compare (Fine
      dashes, Glow, Spotlight, Corners, Wash; the round target button on the stage cycles them; **his pick is owed**);
      the box is one cyan pixel; a tap takes what is in front, a tap on empty space steps out a level; the card's title
      is a path of slanted tabs with pictures (lobby shape: every corner softened alike), and tapping the current tab
      shows the part bare without leaving it; arms and the body take the box; walking upright / all fours / jump
      thresholds are two sliders on the Ground card with the bands drawn on the picture in Motion; arms swing against
      the leg on their own side.
- [x] Round 14 (11-21): Spotlight is the selection look; path-tab gaps; walk bands as two dotted lines with
      pictures; a Layers row opens that layer as the part in hand; Reshape with pins inside a stance makes a shape
      that comes in with the stance's button only.
- [x] Round 15 (11-21), two things he reported. (1) A stance reshape stayed on when he chose Standing: the reshape
      was still being held at full while the pose changed. It is now put down when the pose changes. Checked with
      a real mouse drag of a pin on Topps' Face on all fours: stretched on all fours, as drawn standing, stretched
      again on all fours. Not checked: the same in Motion (the shape should ease in with the stance's button).
      (2) In Fit a second tap on the head did nothing. A tap on the part already in hand now goes into the layer
      under the finger at the angle showing (Head, then Face), and the path names the angle (Head › Frontal › Face).
      Eyes, mouth, brows and nose keep their own cards; limbs are unchanged. Known rough edge: the picture re-frames
      after each tap, so a second tap in the same place can land on a different thing.
- [x] Round 16 (11-21), "the body gets all broken". Cause: the **How far it tips** slider. Parts placed by hand
      for a steep tip kept their numbers in the torso's frame, so at a shallow tip the arms and legs swung off the
      body (reproduced with his saved Topps). Now a hand placement stays where it was put on the picture when the
      tip changes (`keep(…, ride)` in `al-stance.js`), a stance already left broken is put right when the character
      opens (`stance.heal`, tested at 40°, 45° and 60°; a good stance is left alone; a mild case near 80° is not
      detected), and the stance section has **Work it out again** (forgets every hand move, one undo). Motion has
      the same pose row as Fit, alive: tap All fours and it goes down, and the row follows when it drops by itself.
      Stepping into a layer by a second tap no longer re-frames the picture. Checked with real mouse drags.
      Not done: the wider editing-flow pass he asked for (only these were walked); "Hips come down" shows a stale
      value after the tip is dragged; the Body card lists "In front or behind" twice; no pose row in Set up.
- [x] Round 17 (11-21), "don't be lazy about testing how things can break". A break test now lives beside the
      pilot: `tools/avatarlab/al-breaktest.js` (not in the built page; load it by a script tag and call
      `AL.breakTest.run()`). It checks every tip, hip drop, ground height, blend, hand placements made at one tip
      with the tip then changed, a body turned when standing, undo and redo, the stance removed and remade, Work it
      out again, and Motion with the button on and off. **The first version passed the old broken behaviour, so it
      was thrown out**; the kept one measures each part's joint and turn against the worked-out place, and was
      shown to fail on the old behaviour (20 failures) before being trusted. It found two more faults, both fixed:
      an arm whose shoulder is not on its pivot drifted up to 15 px when the tip changed; and a character drawn
      facing straight out (Pip) was offered an all-fours that swings the body off one leg, so none is worked out
      for it now. Result: his saved Topps 240 cases, 0 failures (two seeds); built-in Topps 156, 0. Also fixed:
      "Hips come down" shows its real value after the tip is dragged; the Body card listed the draw-order section
      twice. Limits: outside the hand-placement cases the test only measures distance from the torso's box, which
      is coarse; Motion is checked settled, not mid-walk; it tests the all-fours stance only, not hand-made
      stances, colour sets, head angles or pins.
- [x] Round 18 (11-21), "break test the rest of the room", done by a lighter model (Sonnet) and checked by the
      lead. Six more test files beside the pilot, `tools/avatarlab/al-breaktest-<area>.js` (fit, reshape, turn,
      stack, walk, stage; load after `al-breaktest.js`). It found four real faults; each was reproduced on his
      saved Topps, fixed, and its check seen to fail on the old behaviour first:
      1. **A held pose left the limbs stuck.** "Hold this pose" froze each limb on the drawing it was showing and
         letting go never gave them back, so an arm stayed raised in Set up and Fit. `stages.letGo` puts back what
         Hold changed (a drawing he chose himself while held stays his).
      2. **A pin dropped in a stance changed the standing shape** (4 to 13 px), because pins are shared by every
         pose and a bare pin holds its spot. A pin now belongs to the pose it was dropped in (`pin.home`): a
         stance's pin holds nothing while standing (exact, 0 px) and comes in with the stance. A new pin lands
         under the finger on the part as drawn and is given, in every shape that already bends the part, the
         place that shape was carrying that spot to (`fit.dropPin`). "Remove the last pin" takes out the last pin
         of the pose in hand only. Also found on the way: a pin the standing shape had moved jumped by that
         offset at the first touch in a stance (pins are now shown, and dragged, from where the picture has them).
      3. **Forward one / Back one / Very front did nothing in all fours** where another part's own rule said the
         opposite (his Topps: the front left leg is behind the head with its hand in front of it). The newest
         word now wins, in the same undo step (`stack.settle`); and the draw-order step places a part against a
         part that has an order of its own (it used to drop it on top). All seven parts walk to both ends.
      4. **The "See it" slider popped** (left front leg 65.6 px between 0.72 and 0.74, right 21.5 px at 0.6): the
         hand was planted by a yes/no test at each slider position. Whether the stance plants a hand is now
         settled at the full stance and the slider carries it there (worst step 5 px per 2%).
      **Results on the fixed code, his saved Topps:** stage switching 43 checks 0 failures, hand-made stances 27/0,
      the box 429/0, pins 66/0, draw order 186/0, head turning 33/0, walking 50/0, all fours 240/0 (and still 20
      failures when fed the old all-fours behaviour). Built-in Topps: all fours 184/0, stances 27/0, pins 66/0.
      Pip: stage 36/0, pins 66/0, stances and box 493 with one miss the helper had already judged a too-tight bar
      (right leg moves 3.9 px against a 5 px bar when a stance comes on); one earlier Pip pins run missed once on an
      eye mid-blink and passed on the re-run. With the real mouse on his Topps: Hold this pose then the Held pose
      chip gives the arm back; two pins tapped in All fours land under the tap, one dragged follows the drag, a
      third tapped on the reshaped frill lands under the tap; back on Standing the frill's box is identical.
      Several of the helper's checks assumed things that are not so (a limb drawn in two pieces counted twice, the
      two end buttons on a layer that is not offered them, a clean slate before Hold) and were corrected, each
      noted in the test file.
      Left as they are, by decision: "Very back" keeps parts that are tied behind this one behind it; "As when
      standing" forgets a stance's own order (undo brings it back); Perform keeps the stance in hand for the way
      back to Fit. **Known and not fixed:** dropping another pin on a part that is already reshaped still moves
      the rest of the part a few pixels (2 to 7 px measured), because this warp refits round every pin; the spot
      under the finger does not move. A pin added while standing moves a stance's own reshape by 0.5 to 2.8 px.
      A mesh solver that keeps its shape when a pin is added would cure both; note it for the phone engine.
      **Not run by anyone yet:** the walk sliders beyond Rise and fall and Jumps 0.2 (Stride, Step height, Lean,
      Arm swing, Drops to all fours, ground moved); order on Pip and in hand-made stances; reshape on Pip and on
      a layer inside a head angle; a real finger drag on a reshape pin; take playback in Perform.
- [x] Round 19 (11-21), his question: can a 512 Topps take the same file painted at 1024 or 2000 and get a
      free upscale. **Yes, through Menu, Update the art.** A layered file of the same shape but another size
      comes in as *finer art*: the character keeps its own size, so no pin, joint, stance or number changes at
      all; each picture is kept twice (`AL.img.putFine`: a working copy at the character's size for hit tests
      and measurements, the fine one for drawing and saving; saved under `fine` in the character file).
      Tested with his own 1223 px Topps file onto his rigged 512 Topps: all 120 layers matched and finer, 0
      strays in Extras, the picture within 1 px standing and on all fours, the rig's numbers identical, save
      and reopen exact, one undo. Fed the old way first, the same check was 710 px out. Two older faults in
      Update the art fixed on the way: layers were never matched by their folder (a stray empty name in the
      path), only by a unique name, so 113 of his 120 layers went to Extras; and a leading + mark was not
      ignored. The file decides where each drawing sits (his ruling: moves made in the pilot were experiment).
      His 1223 px Topps is saved as a character file beside his test assets (12.8 MB; personal use, too big to
      ship). Not checked: memory and speed with big art on a
      phone (a 2000 px character is several times the texture memory); files whose shape differs; a file
      smaller than the character (coded, coarser art, not run).
- [x] **Scene (round 20, 11-21; he said "get set, GO", best judgment).** Rulings so far: the menu reads Create
      new avatar, a line, Create new scene; a scene is its own thing on the shelf, not a tab in Set up; work
      stays in house (a take or scene goes to Studio un-flattened and is rendered at Studio's final export),
      quick video out is the side door; scenes, cast and camera shots are always at hand in Perform, and on a
      tablet a tapped character's expressions sit right there. Plan as built in `tools/avatarlab/al-scene*.js`:
      - **One rule for depth:** everything that stands in a scene stands on its feet. Drag it up the floor and it
        goes back, shrinks and passes behind whatever stands lower. No layer list to manage.
      - **Three kinds of thing:** Backdrop (fills the frame, behind all), Object (stands on the floor; one
        picture or several as a loop; can sway in the wind), Foreground (in front of all), plus Characters
        from the shelf, each with its own engine, hands, voice and take.
      - **The frame is the shot:** a scene has a shape (16:9, 9:16, 1:1, 4:5, 4:3) and is 1920 on its long
        side whatever the screen. Outside the frame stays visible but dimmed, so entrances can wait off-screen.
        Safe margins (title, social) are an overlay, never the frame.
      - **Shots, not a zoom toggle:** Wide, Two-shot, Close on each character (found from the head, and it
        follows), and any view saved by hand. Tap cuts, hold eases. Cuts are recorded on a camera lane.
      - **Passes:** the character in hand records; the others play their takes back in step; idle ones stay
        alive. One clock for the scene.
      - **Two stages:** Arrange and Perform; a character in the scene opens into its own four stages and
        comes back.
      - **Agents and Joybot:** every scene act is one named command with plain arguments
        (`AL.agent.tools`, `AL.agent.run`), the same list Joybot and a connected model use.
      Not in this round: video file export, the live Studio link itself (the scene file is the package),
      characters walking in depth, props held in a hand, scene moods.
      **Built and pushed 11-21 (round 20).** Five files beside the pilot (`al-scene.js` the scene and its cast,
      `al-scene-draw.js` the frame loop and fingers, `al-scene-take.js` the clock, camera lane and shots,
      `al-scene-ui.js` Arrange, the dock, the menu row, `al-agent.js` the room as named tools) and two one-line
      hooks in the old engine (a take can run as long as the scene; the GL surface can draw without clearing).
      What works: menu row (Create new avatar, a line, Create new scene and the scenes you have); four sets
      that come with the room (Meadow, Room, Night, Studio); backdrops, objects (a loop from several pictures;
      wind sway), foregrounds; any number of characters, the same one twice; the floor rule for depth with haze;
      five frame shapes and two kinds of margin; Wide / Two-shot / Close on each / kept shots, cut or glide, a
      close-up that follows; backdrop and foreground slide by depth when the camera moves; passes with one
      clock; idle characters watch the one in hand; the camera lane with punch-in; the dock (one row with tabs
      on a phone, three rows on a tablet) with Record and Play on it; a character opens into its four stages
      and comes back; scenes kept on the shelf and reopened where you left off; a scene file that carries its
      characters; Send to Studio sheet with what goes and what is behind it; Joybot's sheet in a scene; 18
      tools (`AL.agent.manifest()`), including building a character from cut-out parts and puppeteering by word.
      **Checked:** `al-breaktest-scene.js`, 46 checks, 0 failures, and each of 8 planted faults caught (order,
      shape, the wrong one moved, passes, camera lane, the camera off the stage, undo, a file that lost its
      takes); the older tests after the engine hooks (all fours 156/0, stages 43/0, stances 27/0, walking 50/0);
      with the real mouse: a character dragged up the floor (smaller, one undo), a touch on the other character
      taking it in hand and turning its head in the same drag, a cut to a close-up, the menu row, the Send
      sheet; phone tall and tablet looked at. The agent path was run end to end by script (new character, two
      parts landing where they were cut from, a scene, an object, 9:16 with margins, a mood, back to Topps).
      **Not checked:** any phone; several characters' speed on the Note 9; the microphone, a voice file or the
      face camera inside a scene; phone held sideways; a real picture file through the file picker (pictures
      were made by script); a real model driving the tools. **Known limits:** a character walks in its own
      space, not across the stage or into depth; Undo for scene changes works in Arrange only; the camera lane
      has no list to edit, only Clear; nothing is exported as video; the Studio link is the saved file.
- [ ] **SCENE, SECOND DESIGN (the owner's, 11-21 night). THE SPEC FOR THE REBUILD. Supersedes the doorway of round 20.**
      His verdict on round 20: the engine is welcome, the doorway is wrong. It read as "a separate app taped on":
      a second room entered from the menu, hard to get into and out of, with Play in two places. Keep
      `al-scene.js` (the scene, the cast, the acts), `al-scene-take.js` (clock, shots, lane), the drawing in
      `al-scene-draw.js` and `al-agent.js`. Replace how it is reached and shown (`al-scene-ui.js`, the scene's own
      rail, Arrange as a stage of its own, "Back to the scene", the dock's tabs and its Record / Play).
      **His standing rule for this job: before building anything ask "does Video Studio already do this? Does it
      do it well?" If yes, plug it in. If not well, fix it there, then use it.** (Voice recording, the export
      button, the exporter.)
      1. **One room.** Perform is the room it always was. There is no scene room, no second rail, no way "back".
      2. **The four stages work on whatever is in hand**, shown by the path tabs: `Scene > Topps > Head`.
         A character in hand: exactly as today, alone on the plain stage in the first three stages. The scene
         in hand: Set up brings things in (the body-map slot shows a scene picture and a way to choose or add
         scenes, not a biped or quadruped; backdrop, objects, foreground, characters); the second stage places
         them (drag, size, how far back, flip, try other frame shapes); Motion is the scene alive without
         recording (wind, loops, try the shots); Perform is the same room. The scene's Arrange panel of round
         20 is taken apart into these.
      3. **The second stage needs a word that fits a character and a scene.** He offered Place, Adjust, Modify,
         Arrange. Lead's pick: **Arrange** (it covers where a thing is, what is in front, and a stance). Keep it
         one constant so it can be changed in one place; the code id stays `fit`. He has not confirmed the word.
      4. **Two quiet grey round buttons at the bottom right of the stage in Perform** (his sketches, 11-21):
         - **The frame button** shows the shape in brackets, 16:9 to start. Tap: the frame on or off (on =
           solid cyan). Long press: the other shapes and the margins. **The frame is only a shape**: it previews
           the bounds and tells export what to cut. It is NOT a scene mode. It must look handsome and quiet: a
           zinc-grey hairline, a little darker outside (not the 90% black of round 20), with room round it on
           every side so the character can go part-way out, above, below and to the sides.
         - **The director's chair** opens upward into three toggles: **Scene, Character, Shots**. Each goes
           from grey to on. A strip above the stage rail (like round 20's dock) shows whichever are on and
           shares its whole width between them: one alone has all of it, two have a half each, three a third
           each. Each part scrolls sideways by itself and ends in its own add card.
         - **Shots need the frame.** Turning Shots on turns the frame on; with the frame off there are no shots.
         - **A card:** tap chooses it (the scene changes; the character is in hand and its expressions fill the
           card below; the camera goes to the shot). **Long press, or a second tap on the one already chosen,
           sets it up**: a scene or a character goes in hand at the second stage; a shot is framed by hand,
           named, kept or removed. Cut or glide is a two-way switch in the Shots part (tap and hold can no
           longer mean both).
      5. **The empty scene is a scene, and always the first card.** It is the plain dark stage from before
         scenes existed, left exactly as it is (he likes it: "quiet, clean"). It cannot be deleted. A take
         recorded there is the character's own, as today. Other scenes are added from the Scene part and the
         menu. The scene in use is remembered, through the frame going off and on and through a reload.
      6. **One transport.** The existing take bar is the only Record / Play. In a scene with several characters
         it is the scene's clock: red records the one in hand while the others play back; Play plays everything.
      7. **Export button**: exactly Video Studio's export button (take it from the Studio code, do not redraw
         it), in Avatar Studio's gradient, where the paper-plane icon is now. It offers the **Joy Creator file**
         (the character or the scene, picked up live inside Joy Creator), **one frame** (a picture; a tick box
         for see-through), and **video** (a key colour to set; no see-through video). Tick boxes for each
         character and for the background. Show only what works: a dead choice is worse than a missing one.
      8. **The menu** keeps Create new avatar, the line, Create new scene and the scenes. Choosing one opens
         Perform with the Scene part up and that scene chosen.
      9. **Voice** is kept with a take. On the phone that is Video Studio's recorder, plugged in.
      10. **Walking across the stage**: dragging the body walks the character across the scene and off either
          side (entrances), not only inside its own drawing space. Hard piece: for the frontier model.
      11. **Tests**: `al-breaktest-scene.js` follows the new doorway and gains a character on all fours in a
          scene (standing, turned round, walking). Fail first, as always.
      **The broken Topps he saw inside a scene on his phone (limbs off on all fours, an old throat pin) is NOT
      found.** Tried on the PC 11-21, all whole: his saved Topps on all fours in a scene; turned round; the
      built-in Topps brought in by name; and an old broken copy (tip changed the old way, no version mark)
      brought in through a scene, which is mended exactly as when opened alone. The copy on his phone is needed:
      in the scene, Menu, the save icon, saves a scene file that carries its characters. Until then this is open.
      **Who builds what (his question; agreed):** spec and the fault hunt with the lead; the room from this spec
      by Opus in a fresh session; break tests by Sonnet; video export and walking across the stage back to the
      frontier model.
- [ ] **For the phone (his rule, 11-22):** take cyan and magenta from the app's global palette, never fixed values in
      this room, so they can be shifted in one place and match every wing.
- [ ] Owed from round 9: stance and colour-set buttons showing the thing itself; the three icons he sent back and
      the seven he has not decided (second examples page); the cards re-ordered to his flow one by one (only the Body
      card and the shared tail of every card are done); phone landscape and tablet.
- [ ] Interface audit (11-21), asked for by the owner before the port: 34 findings, a layout per screen size, a
      re-organisation of the Body and limb cards, and the list of controls that need a picture, in
      [the audit](../audits/AVA-1_ui_audit_20261121.md). Round 8 built the phone-portrait part. Still open from
      it: phone landscape and tablet layouts, one section for every front/behind control on the limb card (the
      per-drawing three-way switch is still separate), the pictures on controls (an examples page is with the owner),
      the raw error messages in the file doors, the empty undo step from Reshape with pins.
- [ ] Known limits of the stack: two parts tied to the same third part (both arms "behind the head") can only be
      stepped past each other as a pair; a step past a part that is in two pieces lands in front of both pieces.
      The shelf has no way yet to take a character off it. Built-in characters get their picture after first opened.
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
