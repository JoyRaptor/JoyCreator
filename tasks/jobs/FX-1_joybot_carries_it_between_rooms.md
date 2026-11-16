# FX-1: Joybot carries your work from one room to the next

**Milestone:** M1/M2 (signature polish)  ·  **State:** 🟨 options page with the owner 11-16; nothing built in the app  ·  **Owner:** "FX-1 hand-off" (Claude Fable 5.2)  ·  **Checked:** 2026-11-16

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

## Rules that apply
Phone-first; the owner's "three bars": his wife (impatient), toddlers, and himself as a pro. Nothing breaks, and no
technical errors are ever shown. Effects are made of the same stuff as the scene (a warp of the real screens, not stickers).
Joybot's actor and flight code belong to the joybot-sprites lane: use its API and agree any change with it.
