# Editable watercolour lines: Note 9 acceptance check, 2026-11-14

**Verdict in plain words:** the editable watercolour line works on the phone. I could draw one, move it, turn it, recolour it,
make it wider and bend it, undo and redo every one of those, save and leave, come back and still edit it, and kill the app
without losing it. Two small things looked wrong, one thing could not be tested with this setup, and the look is a question
for the owner. Nothing here changes any engine file; every problem below is a finding for the paint-engine session.

Checked by an agent with the finger and adb, not by the owner and not with the S Pen. **This is not owner acceptance.**

## What was tested

- Source: current `joy-creator` at `667e38529`, debug APK for arm64 built from it (own build, build lock taken), installed on
  the Note 9 only. The phone ran cool the whole time: 29.2 to 29.4 degrees (30-second log in
  [temps.txt](../evidence/wc-lines-20261114/temps.txt)), no throttling.
- The brush is **"Watercolour line"**, on the **Paint** shelf of the brush drawer. The **Watercolour** shelf holds only the
  wet-sheet brushes (Round, Wash, Bloom ...); the line brush is not there (see finding 1).
- A fresh throwaway drawing ("Drawing 12"). No other drawing was opened. The mirror guide that was left on in the saved
  settings (board note 11-13) was switched off first.
- Screenshots: [evidence/wc-lines-20261114](../evidence/wc-lines-20261114/) (JPEG copies of the key ones; the full-size PNGs
  are kept on the test PC, not in git). "Identical" below means a pixel comparison of the line's area came out with **0**
  pixels different.

## Results

| # | Check | Result |
|---|---|---|
| 1 | Draw a watercolour line, pick it with Select | ✅ Select says **"1 line"**: it really is an editable line (shot 16) |
| 1 | Move it (drag) | ✅ moves, box follows (17) |
| 1 | Turn it (rotate handle) | ✅ (20) |
| 1 | Recolour it (colour dot, picks a blue) | ✅ line becomes blue, position kept (25). The colour applies when the picker closes |
| 1 | Make it wider (width slider, 1.0x to 2.1x) | ✅ with a tap on the slider; the line becomes a wide wash with pooled edges and grain (31). See finding 3 |
| 1 | Reshape it (the "^" button, drag an end) | ✅ end dragged into an L-bend, wash laid along the new path (37) |
| 2 | Undo then redo, each edit, one press = one step | ✅ for the first stroke, move, rotate, recolour, reshape: undo gives back the picture before it with **0 px different**, redo gives back the edited one (18, 21, 26, 39). Width: undo exact; redo see finding 2 |
| 3 | Save (Home), leave, reopen: same look? | ✅ **identical, 0 px different** on an ordinary layer (41); still "1 line" in Select with its blue colour and end points (42) |
| 3 | First edit after the reopen | ✅ moved 100 px right, undo live (43) |
| 3 | Same on an animation frame | ⏳ **not tested**: I did not find a way into a frame from the menus in the time I had. See "Known" below |
| 4 | Kill the app mid-drawing | ✅ force-stopped with an unsaved move on the line: on reopen the line was in its moved place, nothing lost, still "1 line" (46, 48). A second stroke I meant to add during the kill was not drawn (the brush tap opened the drawer), so only an unsaved *edit* was tested, not an unsaved new stroke |
| 5 | Look next to the plain wet-sheet watercolour brush | ⚠️ different, see below. For the owner to judge |

### Look next to the wet-sheet "Round" brush (shot 53)

Same finger stroke, same blue, drawn on its own layer (the wet sheet is refused on a layer that holds editable lines, as
documented). The wet-sheet stroke is wide, deep blue, soft round ends, fading to pale at the tail, and its grain shows as it
dries. The line version is narrower and lighter, with a crackled grain and rougher edges. They are two different brushes, not
the same brush in two modes; which one is the right watercolour look is the owner's call. Finger input only: the line brush
reads pen tilt, so a real S Pen will draw it differently (see "Not checked").

## Findings (for the paint-engine session; nothing was fixed here)

1. **The line brush is hard to find.** "Watercolour line" sits on the Paint shelf, next to Sable and Bristle. Someone looking
   on the Watercolour shelf finds only the wet-sheet brushes and no way to draw an editable watercolour line. Source:
   `BrushShelf.kindOf` puts it in Paint because only media brushes are sent to the Watercolour shelf.
2. **Redo of a width change is not the exact picture you had.** After Undo then Redo of the widening, the line is wide again
   and looks the same to the eye, but about a tenth of the stroke's pixels differ from before, the selection box changed size,
   and the width label read 1.0x instead of 2.1x (shots 32 to 35). Cause not looked into.
3. **Width slider: a drag did nothing, a tap worked.** An adb drag of the slider thumb moved the thumb but left the line at 1.0x
   (shot 28 onward); a tap on the track set 2.1x. This may be how adb draws a swipe rather than a bug with a real finger.
4. **Small cosmetic ones.** After a reopen the Redo arrow looks lit with nothing to redo (pressing it does nothing). After
   Redo of a rotation the rotate handle jumps back to its resting place under the box.
5. **The layer counter reads "1/40" / "2/40" in a drawing that has one or two layers** (it read "1/4" on a new empty drawing).
   Not investigated; may be a count of something else.

## Known, not new

- **Realistic paint on an ordinary layer is not kept wet or liftable after a reopen** (pencil, watercolour, oil): owned by the
  paint-engine session, ATLAS-SOLID "Found 11-14". It does not touch editable lines, which came back intact. I did not change
  that line. (The wet-sheet "Round" stroke on layer 2 was drawn after the reopen check and not reopened.)
- The orchestrator asked for save and reopen on an animation frame as well, to tell the two cases apart. That half is **open**.

## Not checked (do not read these as passed)

- A real S Pen: pressure, tilt, and the full pooling fade at pen-up. Finger strokes are thin and light (no tilt).
- A drawn **curve**: `adb input` draws only straight lines on this Android 10 phone and the touch device refuses raw events
  from the shell, so every stroke here was straight. The bend came from reshaping.
- Rebrush, delete, duplicate, flips; context loss of the graphics; cold/warm first-stroke timing; the original grainy-preset
  look against the owner's proof; cache counters (the finish-only recolour not re-depositing dabs). The Codex handback lists
  these and they stay open.
- Appearance was not compared with the owner's approved proof of concept.

## What I changed on the phone, and put back

- Installed my own debug build over the existing app (no uninstall, no data cleared).
- To let a finger draw, I flipped the app's `penSeen` flag in `joybrush.canvas.xml` while the app was stopped (the same method
  an earlier lane used, saved copy restored at the end): the file is **byte-identical to the original again**.
- Switched the mirror guide off in the app (it had been left on). Created the throwaway "Drawing 12" (two layers) in the
  drawings list: safe to delete. No rotation or system settings were touched; `log.tag.JoyBrushWaterProf` was already INFO
  before I started and I did not set any `log.tag`.
