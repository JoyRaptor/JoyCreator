# FX-1: Joybot carries your work from one room to the next

**Milestone:** M1/M2 (signature polish)  ·  **State:** ⬜ card written 11-15  ·  **Owner:** hand-off lane  ·  **Checked:** 2026-11-15

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

## Rules that apply
Phone-first; the owner's "three bars": his wife (impatient), toddlers, and himself as a pro. Nothing breaks, and no
technical errors are ever shown. Effects are made of the same stuff as the scene (a warp of the real screens, not stickers).
Joybot's actor and flight code belong to the joybot-sprites lane: use its API and agree any change with it.
