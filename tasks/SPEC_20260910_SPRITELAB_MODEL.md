# SPEC — SpriteLab data model: art identity, live rearrange, bake

**Status:** 🔵 SPECCED · 2026-09-10
**Surface:** `tools/spritelab/SpriteLab.html`
**Companions:** SPEC_20260910_SPRITELAB_UI.md, SPEC_20260910_SEMANTIC_CELLS.md

---

## 1. The defect this spec exists for

JoyRaptor, 2026-09-10, after swapping two cells:

> *"if I have swapped 7 and 8 and I click 5 6 7 — it's unclear if 7 means 7 now or seven
> means 8. From preview I can tell that 7 means 8."*

**That he had to go to the preview to find out is the bug.** The sheet is blitted as one
image with outlines drawn on top, so rearranging cells moves *nothing visible*. The app
then shows two numbers per cell — the slot and a `←8` origin tag — and asks the user to
hold the mapping in their head. With one swap that is merely annoying. With four it is
unusable.

Three changes remove the question entirely.

---

## 2. Draw the sheet from the current order

Stop blitting the source image. **Draw cell by cell, each from its current source rect.**

```
for slot in 0..n-1:  drawImage(src, rect(cellOrder[slot]) → rect(slot))
```

Cost at a 16×16 sheet is 256 `drawImage` calls per repaint — nothing.

Consequences, all of them wanted:

- Swapping two cells **visibly moves the art**, which is what everyone expects.
- The background key, per-source keying and multi-sheet merge (§6) all compose naturally,
  because every cell is already an independent draw.
- **Live rearrange preview** becomes free: on `pointermove` during a cell drag, compute the
  *prospective* order and draw from that. Cells slide or swap under the finger before
  release. JoyRaptor asked for exactly this; it falls out of the change rather than being
  bolted onto it.

Ripple and swap both animate: ripple shows the intervening run shifting by one, swap shows
two cells trading. Escape or dropping outside the grid cancels and restores.

---

## 3. One number per cell, and chips follow the art

### 3a. Numbering

After a rearrange the grid **renumbers 0…N in reading order** with no origin tag. Those are
the numbers the phone will see after bake, so what you see is what exports. Provenance
("this came from position 8") is history, not identity — it goes in a hover tooltip and in
`x_spritelab`, never in a second label competing with the first.

### 3b. Art identity

A chip currently stores a **grid slot**. That means swapping two cells silently rewrites an
animation you already built. It must not: you rearrange the sheet for tidiness and export,
not to scramble your choreography.

So the model gains a stable art identity:

| Concept | Meaning | Stable? |
|---|---|---|
| `srcId` | which picture — `{sheet, sourceIndex}`, assigned at load | **yes, forever** |
| `slot` | where it currently sits on the grid | no, changes on rearrange |
| `cellOrder[slot] → srcId` | the arrangement | the thing being edited |

- **Chips store `srcId`.** So do clips, transforms, names, tags and viseme assignments.
- `slot` is derived on demand for display and for export.
- Rearranging changes `cellOrder` and **nothing else**. Every animation plays identically.
- The chip label shows the art's *current* grid number, which updates live.

This also fixes a second latent bug: transforms are currently keyed by slot and are
hand-shuffled on every ripple, which is fragile and was a guaranteed source of
"my alignment jumped" reports later.

### 3c. Grid changes

Changing cols/rows genuinely renumbers everything and there is no honest way to preserve a
sequence across it. Current behaviour (silently clear, show a toast) becomes:

1. The clear is **one undo step** — today it is unrecoverable, which is indefensible in a
   tool where you have tapped forty things.
2. Before clearing, offer *Keep positions* (frames keep their index numbers, art changes) —
   occasionally right when the user is correcting a near-miss detection, e.g. 4×4 → 4×3.

---

## 4. Bake: content-fit cells, rotation, and no clipping

Rotation (new, §UI 8) breaks the current bake: rotating art inside a fixed cell box clips
the corners. Rather than special-case it, fix the underlying assumption.

**The baked cell size stops being the source cell size and starts being measured.**

1. Render every frame that will be baked, with its full transform applied, onto an
   unbounded scratch surface.
2. Measure each frame's **ink bounding box** (alpha > threshold).
3. The baked cell is the union of those boxes, plus a **padding** value (scrubby, default 2px).
4. Bake every frame into that cell, centred on its own **pivot**.

This buys three things from one pass:

- **Rotation never clips.**
- **Dead space is trimmed** — AI sheets are mostly empty margin, and a tighter sheet is a
  smaller PNG and a crisper draw on the phone.
- It is the same measurement the **auto-centre** tool needs (§5), so they share one function.

The user can override with *Keep source cell size* when they need the bake to match an
existing sheet's geometry exactly.

**Bake columns** stays user-set; rows derive. Unused trailing cells are transparent.

---

## 5. Auto-centre and the cleanup tools

The single biggest problem with an image-model sheet is that the character is drawn in a
different spot in every cell. One button should do most of that work.

| Tool | What it does |
|---|---|
| **Auto-centre** | Per frame, measure the ink box and set `dx/dy` so its centre lands on the pivot. One undo step for the whole sweep. |
| **Auto-centre (feet)** | Same, but aligns the *bottom* edge — a walk cycle should keep its feet planted, not its centre of mass. |
| **Match to current** | Align every frame's ink box to the currently-selected frame's box. For when the AI drew frame 3 correctly and the rest drifted. |
| **Trim** | Report per-frame ink boxes so the user can see the drift as numbers. |

All four write ordinary `dx/dy` values the user can then nudge by hand. Nothing is hidden
or irreversible.

### Difference view

A toggle that draws the current frame against the previous one as a **red/cyan difference**
rather than as onion ghosts. Onion tells you roughly; difference tells you exactly how far
frame 7 drifted. Cheap: two draws with `difference` blend.

### Suspect frames

A pass that flags cells which are **empty**, **near-duplicates** of a neighbour, or whose
ink box is a wild outlier. AI sheets reliably contain two or three junk cells and finding
them by eye is the boring part. Flags are advisory — a small yellow corner dot, never an
automatic deletion.

---

## 6. Multi-sheet merge

JoyRaptor generates several sheets of one character in ChatGPT and wants one character
sheet out. The model change in §3 makes this nearly free, because `srcId` already carries
a sheet reference.

- **Sources rail** on the left. Each loaded sheet keeps **its own** grid, margins, spacing
  and background key — they will not agree and must not be forced to.
- Every source gets a colour (cyan / violet / yellow / pink). Cells carry a 2px border in
  their source colour so provenance is visible at a glance.
- The cell pool is the concatenation of all sources in rail order. Rearrange, sequence and
  clip exactly as with one sheet.
- **Bake normalises.** Sources will have different cell sizes; the measured-box bake (§4)
  resolves this automatically because it works in ink space, not cell space. Where a frame
  must still be scaled, the default is **fit (letterboxed, never crops)**. Fill is available
  and warned about: fill crops, and cropping a character costs it a head.
- A source can be removed; cells referencing it grey out rather than vanish, so the user
  can see what they broke and either re-add the sheet or delete the frames.

---

## 7. Project file

`.spritelab.json` gains: sources, `srcId`-keyed everything, rotation, pivot, names, tags,
visemes, undo-free. Version bumps to 2. **Version 1 files load** — v1's slot-keyed data is
migrated by treating `slot == srcId`, which is exactly true for any v1 project that never
rearranged, and correct-by-construction for those that did because v1 stored `cellOrder`.

Autosave to `localStorage` stays keyed by image name + size. With multiple sources the key
becomes a hash of all loaded source names and sizes.

---

## 8. Export, restated for the new model

Nothing about the Joy Creator contract changes. `presets[].frames` are still cell indices
into the exported sheet, resolved as:

| Export | Index resolution |
|---|---|
| **Bake** | `srcId → position in the baked sheet` |
| **JSON only** | `srcId → its slot in the current arrangement`, which for a rearranged sheet is the position the art *actually occupies* in the original image |
| **Frames** | no indices; files are numbered per clip |

JSON-only still refuses to silently lose transforms: if any frame carries a non-identity
`dx/dy/scale/rotation`, say so before writing.

---

## 9. Acceptance

- [ ] Swapping two cells visibly moves the art on the sheet
- [ ] A drag shows the resulting arrangement before release; Escape cancels it
- [ ] Each cell shows exactly one number
- [ ] Building a 6-frame sequence, then swapping two of its cells, plays the same 6 pictures
- [ ] Rotating a frame 30° and baking clips nothing
- [ ] Auto-centre on a drifting AI sheet visibly settles the character
- [ ] Two sheets of different cell sizes merge into one sheet with no cropped heads
- [ ] A v1 project file opens without loss
- [ ] Undo returns a cleared sequence after a grid change
