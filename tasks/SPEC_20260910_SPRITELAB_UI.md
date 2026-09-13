# SPEC — SpriteLab UI, touch, and skin

**Status:** 🔵 SPECCED · 2026-09-10
**Surface:** `tools/spritelab/SpriteLab.html` (desktop/laptop web, pen + multi-touch)
**Companions:** SPEC_20260910_SPRITELAB_MODEL.md, SPEC_20260910_SEMANTIC_CELLS.md

JoyRaptor is using this tool *right now* to animate the Joybot mascot. Everything here
is graded against that: does it survive a pen, a fingertip, and forty taps in a row.

---

## 0. What does not change

Three columns stay. JoyRaptor, 2026-09-10: *"desktop is big. it's useful to see the whole
sprite sheet AND the animation AND clips."* The earlier recommendation to collapse to two
was wrong and is withdrawn. Sheet left, preview + chips centre, clips + export right.

The workflow does not change either: tap cells → chips → range → clip → bake.

---

## 1. Palette

Lifted from app.spritepop.art, read off the live page rather than guessed. It is Tailwind's
open zinc scale plus two accents, and the reason it looks calm is that **the two accents do
two different jobs**.

| Token | Value | Job |
|---|---|---|
| `--bg` | `#09090b` zinc-950 | page ground |
| `--panel` | `#18181b` zinc-900 | cards |
| `--ctl` | `#27272a` zinc-800 | buttons, inputs |
| `--line` | `#3f3f46` zinc-700 | borders |
| `--ink` | `#e4e4e7` zinc-200 | body text |
| `--dim` | `#a1a1aa` zinc-400 | labels |
| `--dimmer` | `#71717a` zinc-500 | inactive segmented tabs |
| `--sel` | `#22d3ee` cyan-400 | **SELECTED** — the thing you are pointing at |
| `--live` | `#ec4899` pink-500 | **ACTIVE** — the thing that is playing or recording |
| `--warn` | `#facc15` yellow-400 | moved / held / needs attention |
| `--alt` | `#a78bfa` violet-400 | second source in a multi-sheet merge |

Radius 8px on cards and buttons, 6px on segmented tabs, full-round on pills.

**The two-accent rule is load-bearing. Cyan never means "playing". Pink never means
"selected".** If a control needs a third meaning, it gets yellow, not a second blue.

Joy Creator's purple is *not* carried over. JoyRaptor, 2026-09-10: *"purple was just
injected by an AI at a random time for a random part."* It is not a brand, it is a
leftover, and the app's own UI overhaul is a separate future job.

**What is deliberately NOT copied:** SpritePop's display font (Bungee) and its pink-first
identity. The palette is Tailwind's, open to anyone. The typography is Pink Pixel's brand
and cloning it would make SpriteLab look like their product. Use a distinct display face,
loaded from Google Fonts when online with a full system fallback stack — **double-clicking
the file offline must still look finished**, so no layout may depend on the webfont
arriving.

---

## 2. Icons

Every section header and every export button gets a leading icon. Not decoration: eyes rest
on pictures, and JoyRaptor scans for the film strip, not for the word "Animation".

Inline `<symbol>` sprite at the top of the document, `<use>`d everywhere. **No CDN** — the
file must work with no network. 16px stroke icons, `currentColor`, 1.5px stroke.

Minimum set: `grid` (slicing), `film` (animation), `layers` (sequence), `target`
(alignment), `tag` (names), `clips` (saved clips), `export`, `braces` (JSON), `undo`,
`redo`, `wand` (auto/detect), `eye` (onion), `swap`, `mouth` (visemes).

---

## 3. Scrubby numbers — the touch primitive

**Every numeric field in the app becomes one control.** Replaces all `input[type=number]`
and is preferred over sliders: sliders eat horizontal width and cap their range, scrubbing
does neither.

| Gesture | Result |
|---|---|
| Drag horizontally on the number | Change by `step` per 4px |
| Shift (or two-finger) while dragging | ×10 |
| Alt while dragging | ×0.1 |
| Double-tap / double-click | Becomes a text field, select-all, type an exact value |
| Tap the tiny ± caps | One step, for precision without a drag |

**The value bubble floats 48px ABOVE the contact point, never under it.** JoyRaptor,
2026-09-10: *"especially if you can see the number changing where your finger doesn't cover
it."* On a pen this is less critical and on a fingertip it is the entire feature.

Implemented with pointer events only — one code path for mouse, pen and touch. The control
captures the pointer on `pointerdown` so a drag that wanders off the field keeps working.

---

## 4. Touch and pen — the parity pass

The current build uses HTML5 drag-and-drop for chips, which is **dead under pen and
touch**. That is a defect on JoyRaptor's own hardware, not a nice-to-have.

| Interaction | Required support |
|---|---|
| Chip reorder | Pointer events. Long-press (250ms) or immediate drag on the grip zone |
| Cell rearrange | Pointer events, already correct |
| Preview nudge | One-finger drag (already correct) |
| Preview scale | **Two-finger pinch** *and* wheel. Pinch is currently missing |
| Preview rotate | **Two-finger twist** *and* the scrubby field |
| Sheet pan/zoom | Two-finger pan + pinch on the sheet canvas |
| Every button | 40px minimum hit box, even where the visual is smaller |

`touch-action: none` on every interactive canvas, and no reliance on `:hover` to reveal a
control that is required to complete a task. Hover may *enhance* (the delete × on a chip
may fade in) but the same action must always be reachable by a deliberate touch.

---

## 5. Drop caret

While a chip is being dragged, a **2px cyan caret** draws in the gap where it will land,
between the two chips it will separate. Currently the insert point is invisible and the
semantics (insert *before* the target) have to be learned by trial.

The caret animates its position; the dragged chip ghosts at 35%; the strip auto-scrolls
when the pointer is within 40px of either end.

Same caret in the **clips list** — clips are reorderable too, and their order is the order
they are written into the JSON, which is the order they will appear on the phone.

---

## 6. Add and un-add on the grid

Adding is a tap on the cell body. **Un-adding is a tap on the order badge.**

| Gesture on a cell | Result |
|---|---|
| Tap the cell body | Append this cell to the sequence |
| Tap the order badge | Remove the **most recent** instance of this cell |
| Long-press the order badge | Remove **all** instances of this cell |
| Long-press the cell body | Open the cell's name/tag editor (SEMANTIC_CELLS §2) |

This needs no new chrome, the badge only exists when there is something to remove, and it
sits exactly where the eye already is. The badge gets a **40px minimum hit area** regardless
of zoom, expanding beyond the cell bounds if the cell is small.

Shift-click on the cell body keeps its current meaning (clear all instances) for mouse
users; long-press is the touch equivalent of the same thing.

---

## 7. Layout inside the three columns

**Left — Sheet.** Sheet canvas fills the column and does not scroll away. Grid/slicing
controls dock to the bottom as a fixed strip, so zooming the sheet never pushes the
controls off screen.

**Centre — Preview + chips, fused.** The preview and the chip strip are **one unit that
never scrolls apart**. SpritePop gets this right and the current build does not: the chip
strip drifts below the fold as cards stack above it. Transport, onion and alignment become
a compact toolbar between them, plus on-canvas handles (§8), not a separate card lower down.

**Right — Clips + export.** Clip rail on top, export below. Export gets icons and the JSON
preview collapses by default — it is reassurance, not a workspace.

**Both side rails get a collapse toggle** (not collapsed by default) so the sheet or the
preview can go full width on the laptop screen when a fingertip needs the room.

---

## 8. Alignment moves onto the canvas

Today alignment is a card below the preview, so you look away from the thing you are
aligning. Instead:

- A **bounding frame** draws around the current frame's art in the preview with corner
  handles (scale), an edge handle (rotate) and the body (move).
- A small readout rides the frame: `x -6 · y 4 · 1.15× · 8°`.
- Numbers stay available as scrubby fields in the toolbar for precision.
- **Rotation** is new — see MODEL §4 for what it does to the bake.
- **Pivot** gets a draggable crosshair (default centre). It exports as `pivotX/pivotY`,
  which the phone already reads and which currently ships hardcoded at 0.5/0.5.

Onion ghosts draw beneath the frame, unaffected by the handles.

---

## 9. Playback feedback

Copy the one thing SpritePop does better than the current build: **the chip that is playing
lights up.** Use `--live` (pink) for the playing chip and `--sel` (cyan) for the selected
one, so "where am I" and "what am I editing" are never the same colour.

Add what they lack:

- A **scrub bar** under the preview, one tick per frame, draggable, showing hold widths
  proportionally so a ×4 hold is visibly four times wider.
- Chips show the **actual frame image** (already correct) and **drag** (SpritePop teases
  this and does not deliver — JoyRaptor tried it and was disappointed, which makes it a
  requirement here).
- **Range shading**: when a play range is set, chips outside it desaturate rather than
  disappear, so you keep your bearings.

---

## 10. Undo

A visible undo/redo pair in the top bar, `Ctrl/Cmd+Z` and `Ctrl/Cmd+Shift+Z`, and a
two-finger-swipe-left gesture on the trackpad is not required.

**One press = one step.** JoyRaptor's standing ruling, and it is the reason a naive
implementation fails: a transform drag fires hundreds of updates. Coalescing rules:

| Action | Steps |
|---|---|
| One pointer-drag of position/scale/rotation | **1** (open on `pointerdown`, close on `pointerup`) |
| One scrubby-number drag | **1** |
| One cell tap (add) | 1 |
| One badge tap (remove) | 1 |
| One chip reorder | 1 |
| One cell swap or ripple | 1 |
| "Apply to all frames" | 1 |
| Grid change that clears the sequence | **1** — and it must be undoable, which it is not today |

History depth 100. The stack is captured in the project file so undo survives nothing —
reload starts clean, which is honest and cheap.

---

## 11. Acceptance

- [ ] Every numeric value can be set by dragging it, with the bubble above the finger
- [ ] A chip can be reordered with a fingertip and with a pen, and the caret shows where
- [ ] Pinch scales the current frame; twist rotates it
- [ ] Tapping an order badge removes exactly one instance; long-press removes all
- [ ] The playing chip is pink, the selected chip is cyan, never both
- [ ] Undo returns a full transform drag in one press
- [ ] The file still looks finished with the network disconnected
- [ ] Nothing required to finish a task is hover-only
