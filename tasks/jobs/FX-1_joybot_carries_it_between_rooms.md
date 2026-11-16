# FX-1: Joybot carries your work from one room to the next

**Milestone:** M1/M2 (signature polish)  ·  **State:** 🟨 building in the app since 11-16 night (first route JoyBrush to Studio); not yet seen on a device  ·  **Owner:** "FX-1 hand-off" (Claude Fable 5.2)  ·  **Checked:** 2026-11-16

## Why it matters
The owner, 11-15: "We've built up this huge library of effects. Why don't we start using it on our own app to really
spice things up in a way that nobody else can?" Sending work between rooms (a drawing to Studio or SpriteLab, a
visualiser to Studio, a title from TextLab) should be a moment people remember, and Joybot is the one who carries it.

## The picture, in his words, condensed
1. You tap Send. The app takes a picture of the room you're in, and one of the room you're going to (built off screen).
2. The two sit side by side with a warp between them, and the view slides across: one seamless move into the new room.
3. Over the top, Joybot flies out of his button with all his flight effects (trail, rings, landing flash: BOT-5), grabs
   the screen's edge and pulls the new room in, cartoon style: big squash and stretch, rubbery warping.
4. He slaps the item exactly where it lands (on the timeline, the canvas, the sheet). It flashes.
5. You are now in the new room. The item glows and is selected, ready to work on. Joybot flies back up to his place.

**Owner, 11-15 (later):** this is Fable work, and there should be **several different transitions**, not one: the
owner will talk it through with the Fable session himself before it builds.

## Done when
- [ ] One route first, end to end: **JoyBrush → Studio** ("Send to Studio", including into an existing project). On the
      Note 9 it runs smoothly, and the real room is live underneath the moment the effect ends.
- [ ] Then: VisLab → Studio, TextLab → Studio, JoyBrush → SpriteLab, and any other route that sends work.
- [ ] **Fast and skippable:** about 1.2 to 1.6 s, and a tap skips straight to the end. It never delays the real work: the
      file is already saved and placed before the show starts, so the show only decorates it.
- [ ] Works when the receiving room takes a while to open: Joybot holds the edge, tugging, until it's ready.
- [ ] The item's landing spot is real: the receiving room reports the on-screen rectangle of the new item, and the glow
      and selection are the room's own selection, not a fake.
- [ ] A Settings switch "Hand-off animation" (on by default). Joybot still animates, per his rule, but the transition can
      be turned off for speed.

## How (suggested; the lane may find better)
Reuse, don't rebuild: picture capture of a view, VisLab's GL programs and offscreen targets (`GlProgram`, `GlTarget`,
`OffscreenGl`), the TextLab warp shaders, Joybot's GL flight and landing from the arrival (BOT-5, joybot-sprites lane).
One overlay component (a full-screen GL view above the receiving room) plays: the two snapshots with a warp seam and slide,
the edge pull, Joybot on top, then a reveal of the live room. Budget: no new heavy assets; keep frame time flat on the Note 9.

## 11-16: four options out to the owner (no app code yet)
- Options page: [UI-Handoff-transitions-options.html](../design/UI-Handoff-transitions-options.html): The Big Pull, Peel and Carry,
  The Box Kick, Made of Its Own Stuff; real Joybot sprites, sketched rooms, warps written as the phone's shaders would be.
  Checked in a browser only. Nothing measured on the Note 9. He answers with the page's "FX-1 picks" line.
- Direction of travel follows the lobby dial (Studio is left of JoyBrush); the seam glows in the destination room's colour.
- Found while reading the route (origin/joy-creator 11-15):
  - `JoyBrushBoardImportActivity` makes the new item's id but does not pass it to `FaditorEditorActivity`; the editor has
    `selectAndRevealNewObject` (private) and `LayerRowRenderer.itemBodyRect` to build "report the new item's rectangle" from.
  - No transition overrides on the route; three Activities, one process. The "New project / which project" dialog comes first.
  - VisLab has no Send to Studio (Studio pulls looks from VisLab), so that route needs a send button before it can have a show.
  - Joybot has no grab, pull, carry or slap drawings; the stage draws only `idle` and `spin`. Closest: arranging-clips,
    discarding-clip, salute. Asked the owner whether he will draw poses.
- Joybot sprites lane, 11-15: the stage is tied to the chat (`Show` + `Reveal`); the seam is an interface `StageRenderer` reads
  (pose(t), backdrop textures, effect triggers, optional Reveal, a clock that can pause). Send it to them as a patch; never edit
  `ui/lobby/arrival/**`. Carry the show in a translucent window that outlives the Activity switch; Choreographer only;
  `overridePendingTransition(0, 0)`; prewarm shaders (67 ms first-open gap); `HomeFlight.flyInto` on request for the ending.

## 11-16 (later): the owner's notes on the four, built into the options page (version 2)
His rulings, each a requirement:
- **Joybot is already out when the "which project?" question shows**, and he is the one asking. The show starts from where he sits.
- **He travels more, in smooth arcs** the eye can follow, and **comes towards you out of his button**: at least twice his
  resting size while he works, close to three times if the sprite holds up.
- **Big Pull:** a first tug pulls out a small, steep piece; not enough; a harder tug, wider; then the whole side goes.
- **Bed-sheet ending (his sketch):** the old room, hauled or kicked mostly off screen, is grabbed at the screen edge and dragged
  back in like a bed sheet that spirals down into the item's rectangle. The old room BECOMES the clip. Used by Pull and Kick.
- **Box Kick:** he flies out away from the edge for a run-up, charges, and the hit has an impact spark like his landing.
- **Peel and Carry:** liked. The tunnel is chromatic streaks and neon rings (his two reference stills); streak colours start in the
  leaving room's gradient and give way to the arriving room's. A VisLab look or a fresh shader: my call (fresh, small, lifted from
  VisLab's tunnel and rays, so the show never waits on VisLab's engine).
- **Melt, replaced:** it read too fast and unclear. Now a vacuum: he makes one large sweep and the room streams radially towards
  him into a ball he carries.
- **Sprites:** his nine back-to-camera pulling frames arrived 11-16 (3x3, transparent, not yet aligned or reordered by him); they
  also serve for dragging. The nervous `oops` (leaning back, gritted teeth) is the heavy carry.
- Room colours are the app's own: JoyBrush `#5C43FD` to `#4397FD`, Studio `#35F6BF` to `#97FE8B` (`RoomColours`).
- Open: each now runs about 2 s from the pick (the card's 1.2 to 1.6 s was written before he asked for more travel); the page asks him.

## 11-16 (evening): his notes on version 2, built into version 3 of the options page
"He looks pretty good so far." Rulings:
- **Hands sell it.** Whatever he grabs is pinned to his fists through the motion (the seam, the peeling corner, the bed sheet,
  the wipe edge); a seam centred on his body with the fists past it does not sell. The page pins per frame (`HAND` table measured
  from the pulling frames). Carried things sit in his fists: the scroll high at his chest, only his mouth showing over it.
- **Waiting on the question:** his neutral "Eve" hover from the chat (`JoybotBody.target`: figure eight 2.6 x 3.4 dp, periods
  5.2 s and 2.9 s, 3 degree lean, soft squash, 12 dp above the seat). The page uses those numbers. The question waits for a tap.
- **Vacuum Sweep accepted as a radial wipe with distortion**, with changes: he holds the wipe's edge and turns with it
  (perpendicular to it), spiralling from the outside in to the middle, where the ball forms; add a circular distortion as what
  is left collapses to the middle.
- **Star field:** use the zooming field from VisLab's Lyric Flight (`assets/vislab/scenes/lyric-flight.vis.json`: 8000 particles
  flying at the camera, echo zoom 1.025, bloom, chroma), its palette swapped from the leaving room's gradient to the arriving
  room's. "That should be good enough, it already looks nice." This replaces my 11-16 note about writing a fresh tunnel shader.
- His frame-alignment method, for whoever packs sprites: SpriteLab's difference mode, slide until the overlap is blackest. Run by
  machine on the nine pulling frames: 6 px at most, 13% less mismatch; the page uses that set in the order he drew them.
- Sprites lane, 11-16: 240 px cells go soft past about 92 dp on the Note 9; flight poses at 2 to 3 times need 480 px strips
  (as `hi/home.webp`), about 0.3 to 0.5 MB each. BOT-5 landed at 4a8ce14db: build the stage seam against that.

## 11-16 (night): building it in the app (owner: "Go ahead and start building directly into the app")
His last notes before the go: carrying = the chat's nervous shaking, sweating Joybot (`oops` + `JoybotBody`'s "nervous" shiver);
use ALL his flight elements (trail, rings, sparkles); the star field is VisLab's Lyric Flight without its words, its colours
moved from room A's gradient to room B's; he liked the highlight edge on the seam; "Pixar quality of animation".

How it is built (branch `claude/fx1-handoff-20261116`):
- `cc.joycreator.handoff` (new, small files):
  - `HandoffMoves` + `HandoffScript`: pure maths, a port of the picks page v3: every pose, the tugs, the hands pinned to
    what he grabs, the wait while the next room opens (left only on a beat, so never a jump), the slap. PC test
    `bash tools/jvm-harness/run-handoff.sh`: 4 moves x 5 screen shapes x rooms that open early, late, never, and a skip.
  - `HandoffEngine`: one GL thread and context that belongs to the app, not to a screen. It draws into whichever
    surface is lent to it, so the same show carries on from the question screen into Studio on the same clock, and
    keeps moving while the main thread opens Studio. Choreographer only. Watchdogs end a show whose room never reports.
  - `HandoffRenderer` + `HandoffFx`: the rooms warped into each other (`assets/handoff/*.frag`, one small program a move),
    and Joybot's light drawn with the ARRIVAL'S OWN shaders (`assets/joybot/stage/`, read, never changed) in the same
    order: trail, rings, sparks, glints, landing flash. `HandoffFx` is the arrival's `Fx` driven by any pose.
  - `HandoffSurfaceView` (a SurfaceView: it reaches the screen without the main thread), `HandoffHost` (the question
    screen), `HandoffArrival` (Studio), `Handoff` (the switch, the choice of move in turn, a picture of a window).
- The route: JoyBrush pictures itself (`joybrush-android/.../handoff/RoomShot.kt`: window + GL painting) and starts
  `JoyBrushBoardImportActivity`, which shows that picture, has Joybot fly out and wait beside "Where should I take it?",
  does the real save, then opens `FaditorEditorActivity` marked with the arriving item's id. Studio opens under the
  show, reveals the item's row, is pictured, reports the tape's rectangle (`EditorTimelineView.layerItemRectInView`, new),
  and selects the item with its own selection at the slap. From the slap on the show draws only Joybot: the real room is live.
- A different move each time, in turn. Switch: prefs `handoff` / `show` (on); the Settings row is asked of the shell lane.
- Pulling frames: the owner's nine, least-difference aligned, at `assets/handoff/joybot-pull.webp` until the sprites lane packs them.

Not done yet (say so, do not claim): on a device at all; the real Lyric Flight engine behind Peel and Carry (a stand-in
star field in the two rooms' colours is in; `VisRenderer` has the API: `setScene`, `setBrandPalette`, `render` to an fbo);
the speech tail from the question to Joybot; his flight back to his button if the question is cancelled; 480 px strips
for his flight poses (240 px cells are soft at 2.3x, sprites lane); the other routes; the Settings row.

## Rules that apply
Phone-first; the owner's "three bars": his wife (impatient), toddlers, and himself as a pro. Nothing breaks, and no
technical errors are ever shown. Effects are made of the same stuff as the scene (a warp of the real screens, not stickers).
Joybot's actor and flight code belong to the joybot-sprites lane: use its API and agree any change with it.
