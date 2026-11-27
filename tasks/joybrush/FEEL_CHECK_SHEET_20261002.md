# Stylus feel check — Pencil, Bristle, Flat Paint

Owner-only. Nobody else can do this row. Machines have already proved the arithmetic; this sheet
judges whether the brushes are any good in the hand.

Read all of §0 first. Then do §A, §B, §C, §D in that order. It should take about 40 minutes.

---

## §0 Before you start

**What you are testing.** Three new brushes. The Pencil is the important one: upright it is a fine
point, leaned to about 45° a long side of graphite reaches out for shading. Flat Paint is the other
one that matters: it should pick up the colour it is dragged across, the way a real flat brush wipes
up whatever is on the paper. Bristle is a quick look — it is the dry, dusty counterpart to Sable.

**What "pass" means.** For every test below there is a stated expectation. You are not being asked
"do you like it". You are being asked **does it match the expectation, yes or no**. Anything that
disagrees with the expectation is a real finding and we want it. Preferring the old behaviour is a
legitimate answer.

**How to choose a brush.** The brush list runs in this order, top to bottom:

`ink · sable · **pencil** · **bristle** · **flatpaint** · marker · softair · smudge · eraser · fill`

The drawer also groups them by kind. **Note this, it will save you hunting:** Pencil is under
**Pencils**. Bristle is under **Paint**. **Flat Paint is under "Smudge", not under "Paint"** — that is
because it runs on the same engine as Smudge. That is a mistake in our labelling, not in the brush,
and it is written up as a finding. Look under Smudge for it.

**How to undo and redo.** Two-finger tap on the canvas = undo. Three-finger tap = redo. The undo and
redo buttons at the top do the same thing.

**How to save.** You do not have to do anything. The drawing saves itself 30 seconds after you stop
drawing, and again when you leave. Tap **Home** (top left, "your drawing is kept") when you are done.

**Coordinates.** The canvas is your whole screen and it starts at true size with no zoom, so one
number here is one screen pixel. Your Note 9 is set to **1080 × 2220**. All the points below are in
those pixels, measured from the top-left corner of the screen.

> If your canvas is a different height, every "y" number below should be multiplied by
> (your canvas height ÷ 2220). The "x" numbers are fine as they are — the width is 1080.

Keep clear of the floating chrome: do not draw above **y = 260** (that is the top bar) or below
**y = 1900** (that is the tool strip).

---

## §A The Pencil

Pick **Pencil**. Leave every slider at its default. Change nothing except how you hold the pen and
how hard you press.

### A0 — Calibration (30 seconds)

One firm, slow, horizontal stroke: **(150, 300) → (930, 300)**.

**Expect:** one continuous unbroken line. No gaps, no stair-stepping, no doubling.

If this is not one clean line, stop and tell us — everything after it depends on it.

### A1 — Upright, soft pressure

Pen **perpendicular** to the screen (straight up), light pressure — about 15% of the way down.

Five short dashes across the top, all at **y = 420**:
`(150,420)→(250,420)` · `(300,420)→(400,420)` · `(450,420)→(550,420)` · `(600,420)→(700,420)` ·
`(750,420)→(850,420)`

**Expect:** a hairline about **2–3 pixels** wide, and **faint**.

**Judge:** is the point still sharp, or is it a fuzzy blob? A blunt point at light pressure is wrong.

### A2 — Upright, firm pressure

The same five dashes, at **y = 540**, at **full pressure**.

**Expect:** about **4 pixels** wide and **clearly much darker** — nearly solid.

**Judge — this is the important question in A1/A2:** does pressing harder make the line **wider**,
or only **darker**? A real pencil does a bit of both. Ours currently widens about 1.75× and darkens
about 7×. Write down which one you actually feel dominates. If it feels like *only* darkness, say so
— that is a legitimate complaint and we will widen the pressure curve.

### A3 — Leaned to 45°, shading across the lean  ← the core test

Pen leaning **about 45°** — halfway between upright and flat. Lean it to the **right**.

Same five dashes at **y = 700**, at **half pressure**, and travel **across** the lean (left to
right, which is the same direction you are leaning, so the pencil is being dragged broadside).

**Expect:** each dash now about **70 pixels** wide — roughly the width of your fingertip.

**Judge:** does it look like **the flat side of a pencil** — a long mark with a **darker edge on the
side nearest the tip** and a **fainter, grainier body trailing away** — or does it look like **the
same thin line, just scaled up**, i.e. an evenly shaded wide blob?

The dark-edge/faint-body difference is the whole point of the design. If you cannot see it, we need
to know.

### A4 — Leaned to 45°, shading along the lean

Same lean and pressure, same five dashes at **y = 860**, but travel **down the lean** — that is, drag
the stroke in the direction the pen is pointing, rather than sideways to it.

**Expect:** **narrower** than A3.

**Judge:** is it actually narrower? We have claimed that dragging across the lean shades broad and
dragging along it stays narrower, like a real pencil held two ways. **If they come out the same
width, that claim is false and we need to fix the geometry, not the wording.** This is a genuine
either/or answer; do not assume it should be narrower.

### A5 — Upright → 45° → upright, one unbroken stroke  ← the single most important mark in this sheet

**One continuous stroke, no lifting the pen**, all at constant half pressure:

- start upright at **(200, 1050)**, draw right
- gradually lean over to 45° by **(480, 1050)**
- stay leaned, shade, to **(700, 1050)**
- gradually bring it back upright by **(950, 1050)**, and lift

**Expect:** a single mark that starts as a point, grows into a broad side, and shrinks back to a
point — smoothly, the whole way.

**Judge, and be strict:**

1. Does the **fine point stay in the same place** the whole time? As you lean, the tip must not slide
   sideways along the stroke. Any sideways drift of the thin end is a bug.
2. Are there any **jumps, kinks or sudden changes of width** where you started or stopped leaning?
3. Or does it read as **one stroke that breathes**?

If the width snaps between "thin" and "wide" instead of moving continuously, that is the thing to
report. It is a curve problem and it is easy for us to fix once you have seen it.

### A6 — Pressure change without moving

Put the pen down at **(540, 1250)** and **hold it still**. Ramp pressure light → firm → light over
about two seconds. Do not move the pen at all. Pencil still upright.

**Expect:** a single dot that grows darker (and slightly wider) and then fades back.

**Judge:** does the dot **stay in exactly the same place**, or does it **wander or grow off-centre**?
Any drift means the point anchor is loose. This is a pass/fail, not a preference.

### A7 — Reverse lean

Redo A3 exactly, but lean the pen to the **left** instead of the right. Same **y = 1450**.

**Expect:** a mirror image of A3, and the **dark edge should now be on the opposite side**.

**Judge:** does the dark side **follow the lean**, or is it stuck on one side regardless? If the
dark edge is always on the same side no matter which way you lean, the azimuth handling is wrong.

### A8 — Small marks at 45°, firm pressure  ← the hardest case, and the one that decides the brush

Pencil leaned **45°**, **full pressure**. Try to make **small dots and tiny tight circles** around
**(540, 1650)**.

**Expect, honestly:** this is where a side-contact brush is *supposed* to struggle. At 45° and full
pressure the mark is about **100 pixels** across, so your smallest possible mark is a 100-pixel blob.

**Judge:** **can you still make a small mark at 45°?**

This matters more than it looks. Shading at 45° and drawing a small detail at 45° are the *same
hand position*. If the brush can only do one of them, it is only half a tool. If you cannot get a
small mark at 45° at all, that is the most important thing you will tell us today, and the fix is a
different design rather than a slider.

### A9 — Does the paper still show through?

Three firm upright dashes, **all in exactly the same place**: `(150, 1830)→(930, 1830)`. Do not move
between passes.

**Expect:** the mark darkens across the three passes, but the **paper grain stays visible** right
through it. It should never turn into a flat solid slab of colour.

**Judge:** can you still see the paper texture in the darkest part? And does it build up **smoothly**,
or does it **blot and go patchy**?

**One pass will be much lighter than you expect, and that is by design.** The pencil deposits only
38% per dab and needs two or three passes to reach full strength. Do not report "too light" after a
single pass — but do report it if three passes still will not go dark.

---

## §B Bristle — two minutes

Pick **Bristle**. It is the rough, dry one.

- **B1:** one firm horizontal stroke, `(150, 300)→(930, 300)`.
  **Expect:** a broken, grainy, textured line — like dragging a dry brush on rough paper.
- **B2:** the same at **light pressure**.
  **Expect:** sparse scratches, with the paper showing through.

**Judge — and this is a real distinction:** does it read as **dry**, or merely as **faint**?

- **Dry** = broken up, with gaps that follow the paper's tooth, and the edges are ragged.
- **Faint** = uniformly pale and smooth, just less colour.

If it is only faint, the paper tooth is not engaging and the brush is not doing its job. Compare it
against Sable on the same stroke — Sable should look smooth and wet by comparison.

---

## §C Flat Paint and colour pickup  ← the second core test

### C0 — Lay down two separate marks first

Use **Marker** or **Ink** for this, *not* Flat Paint — we do not want pickup happening while we set
up.

- **Red band:** `(150, 400)→(930, 520)`, solid.
- **Blue band:** `(150, 600)→(930, 720)`, solid.

Leave the gap between **y = 520** and **y = 600** completely empty. **The gap is the point of the
test** — it proves the colour is coming off the canvas rather than off the brush.

### C1 — Choose Flat Paint and a colour that appears nowhere else

Flat Paint, and pick **green**. Green is not in the setup, so **any red or blue you see later must
have been picked up off the canvas.** (Remember: Flat Paint is under the **Smudge** shelf.)

### C2 — The pickup stroke

One stroke, travelling **downwards**, crossing the red band, the empty gap, and the blue band:

**(200, 380) → (200, 800)**

**Expect:**

- over the **red** band, the mark should turn **red**;
- through the **empty gap**, less pickup;
- over the **blue** band, the mark should turn **blue**.

**Judge, in this order of importance:**

1. **Do you see distinct red and distinct blue in the mark — or one muddy averaged colour?**
   This is the whole feature. Distinct = it works. Averaged = it is only averaging colour and the
   "picks up what it drags over" claim is **false**, which we need to know immediately.
2. **Is the pickup streaky** — separate red and blue fragments sitting side by side, like a brush with
   real bristles loaded with two colours — or has it **smoothed into a blend**?
3. **Does the pickup persist** along the length of the stroke, or does it fade out after a few
   millimetres and then you are just painting flat green?

### C3 — Drag the other way

Now drag back: **(200, 800) → (200, 380)**, same brush, same pressure.

**Judge:** does the pickup behave the same in both directions? It may reasonably differ, but if one
direction picks up strongly and the other does not, tell us which.

### C4 — Bare canvas

Move to empty space, around **(700, 1000)**, and make a short firm stroke.

**Expect:** solid, opaque **green**. No red or blue dragged in from the pencil marks elsewhere.

**Judge:** does it paint cleanly onto nothing? Does it pick up anything it should not?

### C5 — Erase

Pick **Eraser**. Erase straight across the middle of that C4 stroke: **(600, 1000)→(800, 1000)**.

**Expect:** the flat paint disappears cleanly.

**Judge:** is there a **coloured ghost** left behind? A ghost means the pickup wrote colour outside
where the eraser passed, which is a real bug.

---

## §D Undo, save, reopen

Do these in order, on the same drawing.

- **D1 — Undo once.** Two-finger tap.
  **Expect:** exactly **one** step comes off. Not two. Not zero.
- **D2 — Redo once.** Three-finger tap.
  **Expect:** it comes back, unchanged.
- **D3 — Undo once more**, and confirm the canvas now looks identical to how it looked before D1.
- **D4 — Save.** Stop touching it and **wait a full 30 seconds**, then tap **Home**.
- **D5 — Reopen** the drawing from the lobby.

**Judge on reopen:**

- is **every** stroke still there, including the red/blue pickup?
- is the **Pencil still a pencil** and the Flat Paint still a Flat Paint? (A brush that reverts to a
  flat blob after reopening is a format bug, not a taste question.)
- has anything **moved, faded or changed colour**?

---

## §E What to send back

For each test, one line. `pass`, `not quite`, or `broken`, plus **one sentence**.

```
A0 calibration        :
A1 upright soft       :
A2 upright firm       :
A3 45° across lean    :
A4 45° along lean     :
A5 upright→45°→upright:
A6 pressure, no move  :
A7 reverse lean       :
A8 small marks at 45° :
A9 paper shows through:
B1 bristle firm       :
B2 bristle soft       :
C2 pickup red/blue    :
C3 pickup reversed    :
C4 bare canvas        :
C5 erase              :
D1/D2/D3 undo redo    :
D5 save and reopen    :
```

Plus, in your own words:

1. **A2:** when you press harder, does the line get **wider**, **darker**, or both — and which do you
   feel more?
2. **A3:** can you see a **darker edge and a fainter body** on the 45° mark, yes or no?
3. **A4:** is the along-lean stroke **actually narrower** than the across-lean one, yes or no?
4. **A8:** could you make a **small mark at 45°**, yes or no? If no, that is the headline.
5. **C2:** **distinct** red and blue, or **averaged**? This is the one that decides whether Flat
   Paint works at all.
6. Anything that felt **wrong, twitchy, laggy or unpredictable** that is not on this sheet. This is
   worth more than the pass/fail marks — tell us about anything that felt bad.

**Stop and tell us straight away, without finishing, if any of these happen:**

- the app will not open, or closes by itself
- the canvas is blank or white
- a stroke comes out as a dead straight line no matter how you press or lean
- the canvas shifts, zooms or scrolls while you are drawing
- the stylus stops responding partway through a stroke

---

## §F What this sheet cannot tell you

Stated plainly so nobody is misled later.

- These are **untuned placeholder values**. Every number in the Pencil and Flat Paint files was chosen
  by reading the code, not by drawing. The *shape* of the response is designed and tested; the
  *amounts* are guesses until you set them. Expect to change numbers after this session — that is
  the session working, not the session failing.
- **No fluid watercolour, no paint mixing within a single stroke, no impasto.** Paint that flows and
  spreads is not built. What exists now is a flat brush that picks up colour from the canvas under
  it. Do not report "it doesn't feel like oil" as a bug — that part is unbuilt, and we know.
- The desktop Brush Lab tool cannot be used to judge any of this. It does not model these new
  controls. **Only the tablet counts.**
- Passing this sheet does not make the brushes finished. It makes them *tuned*.

## §G Where the numbers came from

So that nobody has to take the expectations on faith, every expected width in §A and §C is computed
from the actual brush files rather than estimated:

- **Pencil** (`joybrush/brushes/pencil/brush.json`): base size 3, which is a *diameter*. Pressure
  multiplies it by 0.8 → 1.0 → 1.4. Tilt multiplies it by 1 at upright, 1.7 at 9°, 9 at 22.5°,
  **24 at 45°**, 32 at flat. 3 × 24 = 72, which is where the 70-pixel figure comes from.
- **Flat Paint** (`joybrush/brushes/flatpaint/brush.json`): base size 60 as a diameter, ×1.65 at 45°.
- **Tilt is measured in radians from upright**, 0 upright and π/2 flat, and the curves divide that by
  90° — so **45° lands exactly on the midpoint of every tilt curve**, which is where they were
  designed to peak. The 45° figures are the design centre, not a coincidence.
- The view opens at true size with no zoom (`JbCanvasView` starts at zoom 1, no pan), and a new
  document's page is the size of the canvas itself, so one document pixel is one screen pixel. That
  is why real pixel coordinates work.
