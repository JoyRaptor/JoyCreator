# SpriteLab

Fix an AI-rendered sprite sheet on a desktop or a pen laptop, then hand Joy Creator a clean
sheet and a `.sprite.json` it can already read.

**To run it: double-click `SpriteLab.html`.** No install, one file. It wants the network once
for its display font and looks finished without it.

If your browser blocks local storage and autosave stops working (everything else still
works — the app catches it), serve the folder instead and open
<http://127.0.0.1:8777/SpriteLab.html>:

```bash
python -m http.server 8777
```

Specs: `tasks/SPEC_20260910_SPRITELAB_UI.md`, `_MODEL.md`, `_SEMANTIC_CELLS.md`.

---

## The workflow

1. **Drop the sheet in.** SpriteLab guesses the grid — first by looking for gutters (the same
   scan Joy Creator uses), then, if there are none, by finding the columns×rows that make the
   cells squarest. **Drop more sheets to merge them** into one character.
2. **Tap cells in the order you want them.** Each tap appends a chip and stamps a cyan number
   on the square. Tap a cell four times, it plays four times.
   **Tap the number badge to take one back**, long-press it to clear that cell entirely.
3. **Drag chips** to reorder — a cyan caret shows exactly where one will land.
   **Drag a grid square onto another** to rearrange the sheet itself; *Swap* trades two,
   *Ripple* pulls one out and shoves the rest along. The art moves under your finger as you
   drag, and your existing animations do **not** change — chips follow the picture, not the
   position, so "does 7 still mean 7" is not a question you can ask here.
4. **Onion skin** ghosts past frames pink and future frames cyan, both reduced to luminance
   so you judge position and not colour. Each side has a **colour swatch** beside it: tap to
   turn that side off, hold to recolour it. **Δ** shows a straight difference against the
   previous frame when you need the exact drift.
5. **Align.** Drag in the preview to move, pinch to scale, twist to rotate, arrows to nudge
   (Shift for ×10). The frame also has **handles on the preview** — corners scale, the stalk
   above it rotates (Shift snaps to 15°), the pink crosshair is the pivot. **Drift…** lists how
   far each frame sits from the average, so you can fix it by typing instead of squinting.
   Or sweep the lot: **Auto-centre** puts every frame's art on the pivot,
   **Plant feet** lines up the BOTTOM of every frame instead so a walk keeps its feet on the
   ground while the body bobs, and **Match to current** aligns everything to the frame you
   are on. Alignment is per-cell, so every use of a frame moves together.
6. **Select a range** of chips (tap, then Shift-tap) and playback loops only that range. One
   sheet holds many animations; this is how you find them.
7. **Name cells.** Long-press one to name, tag and assign a viseme — or hit **Name…** in the
   Sequence row to do many at once: a base name plus automatic numbering (`talk_01`, `talk_02`…),
   names taken from your clips, or names carried across to another sheet of the same character.
   The tag button in Grid & Slicing hides the names when they get in the way. Long-press a cell to name it** — see *Names* below, it matters more than it looks.
8. **Save clip** — name it, set loop / ping-pong / once and its fps in one dialog. The ✎ on
   a saved clip reopens the same dialog. Then **Bake**.

Every number in the app is **scrubbable**: drag it sideways to change it, with the value
shown above your finger. Shift ×10, Alt ×0.1, double-tap to type an exact number. Each one
is led by an icon rather than an abbreviation — hover for the full name.

**Knowing which frame you are on** is said the same way in all three places: **pink** is the
frame the preview is showing right now, **cyan** is the one you have selected. A chip can be
both — pink fill, cyan border. The grid badge turns pink for the same frame, and so does the
scrub bar. **Preview zoom** lives beside the preview (magnifier); it changes how big the art
looks and nothing about what gets exported.

The round swatch by the preview zoom is the **background**: tap to cycle checker, dots,
black, white, zinc, cyan, magenta, green; hold to pick one from a list.

Section icons are colour-coded by category — amber grid, pink sequence, cyan alignment,
violet clips, green export — and the wrap modes are icons: amber loop, violet ping-pong,
pink play-once, blue reverse.

**Undo is one press per action** — a whole drag is one step. `Ctrl+Z` / `Ctrl+Shift+Z`.

## The three exports

| Button | What you get | Use it when |
|---|---|---|
| **Bake sheet + JSON** | A fresh sheet holding only the cells you used, with alignment burned into the pixels, plus a `.sprite.json` numbered against the *new* sheet | Normal. This is the one. |
| **JSON only** | Just the animations, numbered against your original image | You reordered frames and never nudged anything. One source sheet only. |
| **Frames** | `robot-run-01.png`, `-02`, … one file per frame per clip | Another tool needs loose frames |

**Fit to art** measures every frame's real ink and sizes the baked cell to it, so rotation
never clips and dead margin is trimmed. **Keep cell size** preserves the original geometry
when you need the bake to match an existing sheet.

## Names — the part that is not about labelling

You navigate by picture. An AI cannot. Naming a cell `surprise` or `wide_smile` is what lets
the model in Joy Creator reason about your character at all — and a well-named cell turns out
to have more uses than you gave it: `surprise` is an expression, and it is also the "O" mouth
shape; `wide_smile` doubles as "E". Assign the six **viseme** classes in the cell editor and
lip-sync becomes possible the moment the sheet lands on the phone.

It also makes the JSON readable, which means you — or an AI — can open it later and add an
animation by hand without touching the tool.

## The JSON

Field-for-field what `SpriteSheet.fromJson()` reads, with names before animations because a
person reading the file needs the vocabulary before the sentences:

```json
{
  "spriteSchemaVersion": 1,
  "name": "joybot",
  "sheetUri": "joybot.png",
  "cols": 6, "rows": 5, "fps": 12,
  "pivotX": 0.5, "pivotY": 0.5,
  "cellNames": { "0": "idle", "12": "surprise", "13": "wide_smile" },
  "cells": [ { "index": 13, "name": "wide_smile", "tags": ["face","mouth","happy"] } ],
  "visemeMap": { "REST": 0, "EE": 13, "OO": 12 },
  "presets": [
    { "id": "p1", "name": "thinking", "type": "loop", "fps": 6, "frames": [7,8,9,8] }
  ],
  "x_spritelab": { "…": "how it was built — the app ignores this" }
}
```

`type` is `loop`, `pingpong` or `once`. A held frame becomes a `weights` entry. Everything
under `x_spritelab` is SpriteLab's own record of the sources, the arrangement and the
alignment, so a baked sheet can be reopened and re-edited.

## Geometry parity

Cell rects are a port of `SpriteSheetRenderer.cellRectSource()`: symmetric margins, uniform
spacing, row-major, `innerW = W − 2·marginX − (cols−1)·spacingX`. SpriteLab deliberately has
**no** independent X/Y offset control, even though other sprite tools do, because the phone
has nowhere to store one. Anything this app can express, the phone slices identically.

## Saving work

Slicing, ordering, alignment, names and clips autosave to the browser, keyed to the sheets you
loaded, and come back when you load them again. **Save project** writes a `.spritelab.json`
you can keep or move between machines — load the images first, then open the project.
Version 1 project files still open.

## Known limits

- Changing columns or rows renumbers every cell, so it clears frames from that sheet. It is
  **one undo step**, so it is survivable — but get the grid right first.
- **JSON only** needs a single source sheet. Bake to merge several.
- Removing a sheet from the Sources rail does **not** delete frames — they grey out and the
  bake leaves them out, so you can put the sheet back. Tap ↺ to restore it.
- Sheets of different cell sizes merge without stretching: **Fit** letterboxes (default),
  **Fill** covers and crops. A gutterless sheet with non-square cells cannot be auto-detected;
  set columns and rows by hand.
- **Frames** fires one download per frame; Chrome asks once to allow multiple files.
- Undo history is not saved; reloading starts a clean stack.

---

## The phone build (2026-09-13)

`SpriteLabMobile.html` is not a second tool. It is the **design document** for the sprite
screen inside Joy Creator, and since 2026-09-13 the Android build is made to it: open a sprite
in the editor, tap the amber grid button in the drawer, and you are in the same room.

The icons are literally the same drawings. `genicons.py` lifts the `<symbol>` block out of
`SpriteLabMobile.html` and writes
`app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteIcons.java` from it, converting `<rect>`
and `<circle>` to the identical path along the way. To change an icon on both surfaces:

```bash
python tools/spritelab/genicons.py
```

Never hand-edit `SpriteIcons.java` — the point of generating it is that the two surfaces
cannot drift.

### What the phone has

Grid with per-cell order badges, scrubbable alignment numbers (drag to change, tap to type),
coloured onion skin with a background swatch, roll building by tapping cells, playback that
honours holds and the loop / ping-pong / once mode, named animations you can pick and load,
the `.sprite.json` shown on screen with a Copy button, and snapshot undo where one gesture is
one press.

### What is still desktop-only

Multi-sheet merge and the Sources rail, baking a new sheet (fit / fill / keep, PNG / JPG),
numbered frame export, and drag-to-reorder on the film strip and the clips shelf.

Swap / Ripple / Reset order DID land, 2026-09-13. Arm one in Slice's drag segment and drag a
cell onto another; the drawing rides your finger and the target lights amber. A drawing's name,
alignment and viseme travel with it, which is JoyRaptor's ruling — so Reset order restores
everything, because none of it was ever attached to the slot.

Alignment travels as **data** now — `cellXf` on the sheet — so nothing has to be baked to move
work from here to the phone or back. The rest of that list is owed, because the standing rule
is that no capability may be desktop-only.
