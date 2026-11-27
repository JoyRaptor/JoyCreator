# Pencil that works with no slider touched — 2026-10-02

Status: **specification for the Lead.** Row id not assigned; propose a follow-on to JB-1.07
(`🟧 Built`, device check owed) or a new T1+T3 row. Do not assign a number here.

Owner brief, verbatim, and it governs everything below:

> "each brush should be incredibly versatile without having to touch a single slider. All the sliders
> do is add the ability to tweak things for artists who want things a specific way."

Owner verdict on the current build, verbatim:

> "the pencil brush doesn't look like a pencil brush. It actually acts more like a marker … it makes
> a semi decent marker ish and a terrible pencil."

Owner reference: **one Infinite Painter pencil preset, no sliders adjusted**, producing crisp
tapering hairlines, a clean 1–6 pressure ladder of clean lines, and broad soft heavily-grained
shading with feathered ragged edges and vanishing ends.

---

## 1. The verdict in one sentence

**Both ends of the pencil's pressure ladder are at the wrong end of both of its curves.** The light
end is speckled dust instead of a clean hairline, and the heavy end is a solid hard-edged bar instead
of soft grainy shading. A uniform-width line whose darkness tracks pressure *is* a marker, so the
owner's description is literally accurate.

There is also a second, independent cause that I think is the bigger one, in §3.

**What I am NOT claiming.** I cannot pick the right absolute numbers — `flow`, `edge`, `radial`,
opacity floor. Nobody can, and `ROADMAP.md:177` already says so: *"Soft air's flow 0.05 is the most
felt number in the row and no test can say if it is right."* Those are T3, the owner, on the phone.
**What I can do is prove which direction each curve must move**, and that is objective: the engine's
own semantics make one end of each curve unreachable-for-purpose. That is what this document settles.

---

## 2. Grain `depth` semantics — the thing that has to be understood first

`joybrush/shaders/jb_grain.glsl` states the model (`:7-10`):

> "Picture the texture as a landscape of bumps: height 1 = the highest bump, 0 = the deepest pit.
> Pressing the brush lowers a flat 'paint level' into it… **Light pressure → only the tops of the
> bumps catch paint (dry, clumpy marks); heavy pressure → paint fills the pits too (solid).**"

And the arithmetic, `jb_heightCoverage` (`:22-26`):

```glsl
float threshold = 1.0 - level;
return clamp((height - threshold) / w + 0.5, 0.0, 1.0);
```

So **`depth` ↑ ⇒ threshold ↓ ⇒ more coverage ⇒ LESS visible texture.** `depth` is *pressure into the
paper*, not *amount of texture*. Two consequences that matter here:

- **`depth` is already a pressure curve.** The pencil sets `paperGrain.depth` to
  `[[0,0.12],[0.2,0.27],[0.55,0.6],[1,0.95]]`. A `depth` *Param* whose input is already `pressure` is
  the pressure response. **Do not add a second pressure term.**
- **The "Paper grain" slider is inverted relative to its own hint.** `BrushKnobs.kt:187-189` binds the
  knob straight to `depth.base`, and its hint reads *"How much the paper's grain shows through the
  stroke."* Dragging it right, as a user reads that sentence, produces **less** grain. This directly
  contradicts the owner's stated model of what a slider is for, and it is a one-line fix (invert the
  binding and the hint). Fix it regardless of everything else in this document.

---

## 3. PRIMARY CAUSE — pressure is almost not a size driver, so the pencil *is* a marker

This is the finding I would fix first, and I think it is the one the owner is actually describing.

Shipped pencil size law:

```
size.base = 3
pressure → [[0,0.8],[0.5,1],[1,1.4]]        = a 1.75x range
tilt     → [[0,1],[0.1,1.7],[0.25,9],[0.5,24],[1,32]]   = a 32x range
```

`size` is a **diameter** (`BrushDabber.kt:117-122`, `radius = max(diameter, minPx)/2`). So the
achievable widths are:

| pose | pressure 0.1 | pressure 0.5 | pressure 1.0 |
|---|---|---|---|
| **upright** (tilt 0) | 2.4 px | **3.0 px** | **4.2 px** |
| 22.5° | 22 px | 27 px | 38 px |
| 45° | 59 px | **72 px** | 101 px |

**Upright, the entire pressure range spans 2.4 → 4.2 pixels.** That is a constant hairline. An artist
drawing normally — upright, varying pressure — gets a line whose **width barely moves and whose
darkness moves**, and that is the textbook definition of a marker. The reference sample's numbered
1–6 ladder shows the opposite: six *clean lines of clearly different widths* from pressure alone,
upright.

To get any width at all, our pencil demands the pen be leaned to ~45°. That is not a discovery an
artist makes; it is a trap. And the tilt curve is so aggressive (1 → 32) that once they do lean, the
jump from 4 px to 72 px is violent.

**Required change — direction, not value:** make **pressure the primary width driver** so that an
upright stroke spans hairline → bold on pressure alone, and keep **tilt as the contact-shape driver**
(point → long side) rather than the primary width driver. The reference demonstrates pressure→width
as the main axis and tilt as the side-contact axis. Ours has them almost exactly swapped.

Note this does **not** contradict `R11:16`, which claims the advantage is *"gradual pressure-dependent
tooth engagement"* — that is about grain, not width, and it survives.

There is prior art in our own tree for exactly this. `research/R9_watterson_sable_ink.md:211`:

> "`size.base` 18 … This is the *belly*. It is 3× Ink, so **one brush reaches bold without touching the
> Size slider**."

Sable was built to this principle. The pencil was not.

---

## 4. SECONDARY CAUSE — hardness runs the wrong way, so the shading end can never be soft

Shipped: `tip.hardness` = `base 1`, pressure curve `[[0,0.55],[0.3,0.75],[1,1]]`.

`jb_tip.glsl:56-62` is the whole mechanic:

```glsl
float inner = min(clamp(t.hardness, 0.0, 1.0), 1.0 - aa);
float cov = 1.0 - smoothstep(inner, 1.0 + 0.5 * aa, d);
```

`hardness` is the **inner radius of the solid core as a fraction of the superellipse distance
field**. 1.0 = a hard-edged disc; 0.0 = a soft radial falloff from the centre to the rim.

**So the shipped pencil can never produce a soft edge.** Its floor is 0.55 at zero pressure and 1.0
at full. The reference's broad shading strokes have **feathered, soft** rims. We cannot draw them at
all.

And the direction is wrong for the wrong reason. `specs/JB-6.03_the_one_great_brush.md` already
decided this, in the owner's own terms:

> "A hard-edged small dab is a hairline; a **soft-edged** small dab is a smudge-blob. The brush has to
> get *harder* as it gets smaller, and one hardness per stroke cannot do that." (`:26-32`)
>
> "**a single hardness per stroke cannot be a hairline at one end and a wash at the other, and that
> sentence is the whole row.**" (`:204`)

and its tuning targets are **`hardnessLight 0.9`, `hardnessFull 0.15`, "Must be below T4"**
(`:328-329`).

**The shipped file has this exactly backwards: light 0.55 → firm 1.0, when the ruling was light 0.9 →
firm 0.15.** The ruling exists; it was never applied to the file. That is a straightforward miss, not
a judgement call, and it should be corrected before any owner tuning happens — otherwise the owner
tunes numbers on top of an inverted curve.

---

## 5. THIRD CAUSE — grain depth also runs the wrong way at the shading end

Shipped `paperGrain.depth` pressure curve: `[[0,0.12],[0.2,0.27],[0.55,0.6],[1,0.95]]`.

Worked through §2's arithmetic:

| pressure | depth | threshold | effect |
|---|---|---|---|
| 0.08 | ≈0.18 | ≈0.82 | only texels above ~0.90 take any paint → **speckled, mostly-empty dab** |
| 1.0 | 0.95 | ≈0.05 | any texel above ~0.21 takes paint → **solid, untextured bar** |

Combined with `hardness` climbing to 1.0 at the same moment, **full pressure produces exactly a solid
hard-edged bar.** That is the marker, spelled out in numbers.

Two separate things are wrong here and they need opposite fixes:

- **The shading end must stay grainy.** At full pressure/45° the reference is *still visibly toothy*.
  `depth` reaching 0.95 is too solid. Required direction: **depth should NOT saturate at the top of the
  pressure range.** Cap it well below 1.
- **The light end must be a clean hairline, not dust.** There is real tension here, and it is worth
  stating precisely rather than papering over: at `pressure 0.08` we get near-invisible speckle *and*
  `opacity 0.035` on a 2.4 px tip. The reference's thin lines are **clean and dark**, not faint dust.
  `LEAD_RULINGS.md:427-429` already ruled on the underlying paradox — a zeroed depth is *not* "nothing
  paints", it is 50% coverage on the brightest texel — and accepted it as *"a dry pencil should speckle
  at a feather touch"*. I think that ruling is right for a **broad** light stroke and wrong for a
  **thin** one, which is precisely why the fix is to decouple the two (§6) rather than to reverse the
  curve globally.

Also: `JB-1.05c` Decision 10 and Q4 still say grain depth is per-stroke and recommend *deferring*
per-dab. That text is **stale** — brush v7 ships per-dab `tipDepth`/`paperDepth`
(`BrushDabber.kt:149-153`). Update the spec text so it stops contradicting the code.

---

## 6. THE FIX THAT SOLVES THE LIGHT-END CONTRADICTION — separate grain for the tip and the paper

The light end must be **clean** and the shading end must be **toothy**. Those are opposite demands on
one curve, which is why fighting over the depth curve alone cannot win.

The engine already has the separation — v7 gives two independent depths
(`BrushPreset.kt` / `BrushDabber.kt:149-153`):

- **`tipDepth`** — grain in **dab space**: the tooth *on the pencil lead itself*.
- **`paperDepth`** — grain in **canvas space**: the tooth *of the sheet*.

The reference is a woodless graphite pencil on paper, and those two are physically different things:

- **A thin upright line should be dominated by the paper's tooth, and mostly clean** — the point is
  small, it sits in one spot on the sheet, and it either catches that spot or it doesn't.
- **A broad 45° side stroke should be dominated by the lead's own tooth** — the long side has a
  graded hardness along its length from the point to the barrel, and that grading is *in the pencil*,
  not in the paper. It travels across the sheet, so the paper contributes streaks, not the shape.

**Required direction:** drive `tipDepth` up with tilt (the side contact is what exposes the lead's own
grading) and hold `paperDepth` from saturating, so broad strokes keep visible tooth; and do **not**
let either depth go so low at the light end that a thin upright line becomes speckled dust. The
mechanism that makes the far body fainter than the near edge already exists and is already switched
on — `paperGrain.tiltGradient 0.95`, which `jb_grain.glsl:39-44` turns into a plane aimed along the
lean, asserted by `AdaptiveBrushFilesTest.pencilSideHasANearToFarToothGradientUsingItsLiveGrainDepth`.
**Keep that. It is the correct mechanism and it is the thing `R11:69` already specified.**

---

## 7. Missing capability — taper, and why the reference's ends vanish

Every stroke in the reference tapers to nothing at both ends. Ours cannot: the pencil declares **no
`taper`** (default 0), and even if it did, `taper` is declared as a plain `Float`, not a `Param`
(`BrushPreset.kt:36`) — it is the only numeric tip property besides `corner` that cannot follow
pressure, and there is no knob for it.

`R9:83` already ruled out the slider route, in the owner's terms:

> "**A fixed 'taper length' setting (Procreate's approach) is the fiddly workaround this project wants
> to avoid.** Derive the taper from lift speed: a fast lift gives a long needle, and a slow,
> stationary lift gives a blunt end."

`R9:85` adds a warning that our pencil currently violates:

> "**For India ink, do not tie opacity to pressure. It turns hairlines grey, which is the 'airbrush'
> failure.**"

The shipped pencil's `opacity` goes to `0.035` at zero pressure — the exact "airbrush failure"
failure mode named there.

**Two required changes:**
1. **`taper` becomes a `Param`** so it can follow pressure and/or lift speed. Small format change, and
   it closes a gap `R4:504` already lists (*"a generic control curve per parameter"*).
2. **Lift-speed taper is the target, not a taper-length slider.** Note honestly that this is new
   engine work and is not in the current build.

---

## 8. The one new engine capability — hardness that varies ACROSS the dab

This is needed by the pencil *and* by the owner's separate "one pen" request, which is the elegant
part of this whole problem.

`hardness` is uploaded as a **`flat` varying** (`jb_dab.vert:27-29`):

```glsl
flat out vec4 v_contact;   // aspect, hardness, tip depth, paper depth
```

`flat` means one value for every fragment of the dab. `jb_tipCoverage` has no spatial hardness term of
any kind; `inner` comes from a scalar. **Answer: the engine cannot produce a hard-to-soft gradient
within one dab. Full stop.** Confirmed by `rg hardness` over `joybrush/shaders/` — four hits, all of
them the scalar field, its doc line, and the single `min(...)` above.

What *does* vary across the footprint is only the **grain level** (`jb_grain.glsl:39-44`):

```glsl
float plane = tiltGradient * tiltAmount * dot(localN, leanDir);
float dome  = radial * dot(localN, localN);
return clamp(depth * tipCov + plane - dome, 0.0, 1.0);
```

Those modulate the *grain threshold*, not the tip's edge hardness. So today's only route to a soft edge
is `hardness` near 0 — uniformly soft, all round, which gives the smudge-blob that `JB-6.03:26` warns
about, not a shading stroke with a defined near edge.

**Required engine work: replace the scalar `hardness` with a per-fragment edge profile.** The natural
form, reusing prior art we already hold in `R4:106-119` (GIMP/MyPaint/Krita/brushkit falloffs) and
`R4:479-490` (the owner's own formalised model with a signed-distance falloff):

- Add a **`softnessSpan`** — a distance across the footprint over which the edge goes from hard to soft.
- Evaluate the inner radius as a function of the fragment's position in tip space, e.g.
  `inner = hardness * mix(1.0, smoothstep(...across the span...), ...)`, so the core is hard on one
  side and feathers out on the other.
- Drive `softnessSpan` from **tilt**, so upright is fully crisp and a 45° side contact feathers along
  the barrel. That single driver gives the reference's thin-crisp / broad-soft behaviour **with no
  slider touched**, which is the owner's stated principle.

**This is the highest-value piece of engine work in the brush programme**, because it is the one thing
standing between the current pencil and the reference, *and* it is the missing half of the owner's new
pen request (§ BRUSH_DEFECTS doc, "the one pen"). Ship it once, both problems move.

---

## 9. Acceptance criteria — all testable without the owner, except the last

Every one of these is currently unasserted anywhere. The existing
`AdaptiveBrushFilesTest` methods all carry the disclaimer *"these tests make no visual-quality claim"*
(`:20`), which is honest and is exactly the hole.

- **A1.** Upright, pressure 0.1 → 1.0: **width is monotonically increasing and spans at least 8×**.
  Today it spans 1.75×. This is the marker test.
- **A2.** Upright, light pressure: a stroke is **clean and continuous**, not speckled dust. Assert the
  fraction of uncovered pixels inside the footprint is below a threshold.
- **A3.** Full pressure, 45°: the mark is **not solid** — assert visible variance across the
  footprint's coverage, i.e. tooth survives at the top of the pressure range.
- **A4.** `hardness` **decreases** with pressure. Assert `hard(0.1) > hard(1.0)`, and that
  `hard(1.0) < 0.3`. Directly encodes `JB-6.03`'s already-ruled tuning targets.
- **A5.** Soft shading rim: at 45° and full pressure, coverage at `0.9 × radius` is **strictly between
  0 and 1** (the `JB-6.03` Test 2 belly assertion), and **also** at `0.55 × radius` on the soft side
  of the new span.
- **A6.** Near/far tooth gradient still holds at the corrected depths — keep the existing
  `pencilSideHasANearToFarToothGradientUsingItsLiveGrainDepth` test green after the curve changes.
- **A7.** CPU/GPU agreement: every assertion above must hold identically in `TipMath` and in
  `jb_contact.glsl`. A pencil that looks right on screen and wrong in export is not fixed.
- **A8.** Deterministic replay unchanged: the same recorded stroke must reproduce byte-identically.
- **A9. Owner, T3, and only the owner:** does it look like the Infinite Painter sample. **Do not
  promote any of A1–A8 to "the pencil is right."** They prove the curves are no longer inverted. The
  numbers are still T3.

## 10. What I am explicitly not doing

- **Not picking absolute values.** `flow 0.38`, `edge 0.16`, `radial 0.04`, the opacity floor and the
  new pressure→width curve's endpoints are the owner's on the phone. I have specified the *direction*
  of every one of them, which is the part that is objectively determinable.
- **Not retuning on the PC Brush Lab.** `BRUSH_SPECIALIST_HANDOFF_20261002.md:23`: it *"does not model
  these new contact fields faithfully. Do not use it to retune v7 files."*
- **Not claiming the competitive win.** `R11:16` is explicit that a tilt-aimed pencil gradient is *not*
  unique against Infinite Painter, and `R11:74` that superiority *"remains unproven until rendered
  comparisons and target-device trials exist."* The reference sample is a **target**, not a
  comparison won.
- **Not touching `corner`.** `corner` is the superellipse exponent (2 = circle, 16+ = square). It is
  not a pencil control and should stay out of it.