# Brush defects from the owner's device pass — 2026-10-02

Status: **findings for the Lead.** Row ids not assigned. Every claim below is traced to code; nothing
here is a taste judgement unless it is labelled as one.

Source: the owner tested all ten shipped brushes on the Note 9 and reported, verbatim:

> "almost none of them work as advertised. The soft brush is terrible. Almost all the brushes are
> absolutely awful, except for the sable and bristle brush. But the bristle brush, while it doesn't
> look that bad, it just looks like the Sable brush with different sliders."

> "The smudge brush does not work. It doesn't smudge. The fill pen, which is supposed to work similar
> to a Lasso tool to draw a shaped fill does not work like that. It just looks like a regular line
> drawing."

Governing principle, verbatim:

> "each brush should be incredibly versatile without having to touch a single slider. All the sliders do
> is add the ability to tweak things for artists who want things a specific way."

**Good news first:** Sable and Bristle are the two the owner singled out as acceptable. Both are the
`tuft` engine, and `R9:211` shows Sable was deliberately built to the principle above (*"one brush
reaches bold without touching the Size slider"*). **The two that work are the two that were designed
for it.** That is the pattern, and it is a useful one.

---

## Priority order

| # | Brush | Class | One-line cause |
|---|---|---|---|
| 1 | **Fill** | **wiring bug — no renderer exists** | `JbCanvasView` has no `ENGINE_FILL` branch, so it falls through to the stamp engine and draws a plain 8 px line |
| 2 | **Smudge** | **mis-set preset + real bug** | `paint: false` makes it a byte-exact no-op on bare canvas; smudge commit can only *increase* alpha |
| 3 | **Pencil** | **two inverted curves** | see `PENCIL_WORKS_BY_DEFAULT_20261002.md` |
| 4 | **Bristle** | **wiring/scaling bug** | `dryness()` is speed-gated to **0** below 750 px/s, so `dry` contributes nothing at drawing speed |
| 5 | **Soft air** | **real engine gap** | the dwell path that fixes build-up-while-stationary is **tuft-only**; soft air is a stamp |
| 6 | **Flat Paint** | **shelf misfiling** | files under **Smudge**, not Paint — the owner cannot find it |
| 7 | all | **inverted slider** | the "Paper grain" knob is wired backwards relative to its own hint |

---

## 1. FILL PEN — no renderer exists at all. This is the clearest defect in the set.

**The owner's expectation is a verbatim restatement of the spec's own goal.** `JB-1.08a_fill_pen.md:11-20`,
in the owner's words:

> "The lasso fill is actually a PEN … the fill pen fills the closed shape the line makes … with a line
> joining the ends."

`JbCanvasView.kt:939-957` is the complete engine dispatch and it has **no `ENGINE_FILL` test at all**.
`engine: "fill"` therefore falls to the final `else` → `BrushDabber` + `DabPlacer` → `jb_dab.frag`.

**Result on screen:** `size.base 8`, `flow` absent → 1, `accumulate` absent → `"wash"` so cap 1,
`spacing` absent → 0.04, `tip` absent → `TipSpec()` default `hardness 0.9`. **A solid, opaque,
hard-edged 8 px round line.** That is exactly *"it just looks like a regular line drawing."*

Confirming it is a missing renderer and not a broken one: `FillPen.outline` (`FillPen.kt:40`) is called
**only from tests**; `InkRaster.fill` (`InkRaster.kt:156`) — the thing that would rasterise it — has
**no caller outside tests**; `FloodFill` appears in main source only in a benchmark
(`BenchCases.kt:217`). The geometry is built and correct. **Nothing draws it.**

This was scoped out, deliberately, in the row's own Definition of Done — `JB-1.08a:76-77`:

> **Do not** — "No rendering here (paint layers: JB-2.06b; ink layers: the Lead's JB-5.01)."

Both follow-ups are unbuilt: `ROADMAP.md:196` has **JB-2.06b** at `🟦 Ready`, `JB-5.01b` at
`⚪ Outline`.

**So this is not a bug in the sense of "someone broke it" — it is a half-shipped row that was marked
as if it were whole.** It is on the brush list the owner sees and in the drawer, so from the outside it
is simply a broken brush. **Recommendation: either build the renderer (JB-2.06b is already Ready) or
remove Fill from the shipped shelf until it exists.** Shipping a visible brush that cannot do its one
job is worse than not shipping it.

Two traps worth noting: `JB-1.08a` Decision 2 declares `tip, size, spacing, grain, scatter` *ignored*
by a fill brush — yet they are in the shipped file, and because no renderer exists they are **not**
ignored, they are the visible line. And `blend: "behind"` exists in the format with no reader, so that
value is currently dead too.

---

## 2. SMUDGE — "it doesn't smudge"

Three separate causes. The first two explain the symptom; the third is a real bug the owner has not
hit yet but will.

### 2a. On bare canvas it is a byte-exact no-op

`joybrush/shaders/jb_commit.frag:32`:

```glsl
float m = u_smudge == 2 || dst.a > 0.0 ? 1.0 : 0.0;
o_color = (s * m + dst * (1.0 - s.a * m)) * u_layerOpacity;
```

`GlPaintEngine.kt:1290` sets `u_smudge = 2` **only when `smudgePaint` is true**. The smudge preset
declares only `{"pickup": 0.08, "load": 0}`, so `SmudgeSpec`'s defaults apply
(`BrushPreset.kt:72-73`): **`texturePickup = 0`, `paint = false`** → `u_smudge = 1` → `m = dst.a > 0 ? 1 : 0`
→ **wherever the layer has no alpha, `o_color = dst` exactly.** The GPU discards the whole stroke buffer.
CPU-side, `SmudgeStroke.canvasUnder` returns `false` when nothing has alpha (`SmudgeStroke.kt:104`), so
the carried colour never updates either.

This is deliberate and tested — `SmudgeTest.kt:107` *"an empty canvas is left alone"*, and the spec
`JB-1.06_smudge_and_nudge.md:136`:

> **"A smudge does nothing where the canvas is empty."** … "A smudge is a way of moving paint that is
> already there"

**Working as designed, but the design collides with the obvious test.** If the owner picked Smudge and
drew on empty canvas — or on the paper, which is a *transparent layer* with paper as background — they
got precisely nothing. **`flatpaint/brush.json:134` already sets `"paint": true` on the same engine.**
One word fixes the symptom.

### 2b. `pickup: 0.08` is 6.25× below the spec's own "gentle" value

`Smudge.kt:44-55` makes the carried colour a first-order lag toward the footprint mean, so `pickup` is
a per-dab rate:

| quantity | value |
|---|---|
| `pickup` | **0.08** (spec's own proposal: `0.5`) |
| time constant | `1/0.08` = **12.5 dabs** |
| dab step at full pressure | `2 × 14 × 0.06` = 1.68 px |
| ⇒ smear transition width | **≈ 21 px**, 40–60 px for 2–3τ |

So dragging across a hard colour edge gives a ~40 px blur ramp rather than a defined smear.
`JB-1.06:116-120` describes `0` as *"a brush that remembers its own colour forever"* and proposes
`0.5` for the preset. **0.08 was set well below the guidance and needs raising.**

To be fair to the preset: the deposit is *strong*, not weak. Per-dab alpha converges
`0.550 → 0.798 → 0.909 → 0.959 → …` to ~1.0 within about eight overlapping dabs, so a pixel under the
brush is essentially replaced by the carried colour. **The smear is not invisible — it is a
flatten-to-local-mean, which is invisible over flat paint and only appears at a boundary.** That is a
correct diagnosis to check first with the owner: *did they smudge across an edge, or over flat paint?*

### 2c. Real bug: a smudge can only ever make paint **more** opaque

`jb_commit.frag:33` gives `out.a = s.a + dst.a·(1 − s.a)`, which is `≥ max(s.a, dst.a)`. **There is no
path by which a smudge reduces alpha.** Dragging Smudge through a soft, semi-transparent edge
(`dst.a = 0.3`) yields `out.a = 0.51` — **the smear darkens and hardens soft paint.** A finger smudge
thins. This is a genuine defect, not a design choice, and it needs a ruling: a smudge should either be
permitted to reduce alpha, or the spec should say plainly that it only mixes.

### 2d. UI gap

`BrushKnobs.kt:191-196` exposes only `smudge.pickup` and `smudge.load`. **`smudge.paint` and
`smudge.texturePickup` have no knob** — so the owner cannot see or change the setting that decides
whether the brush does anything. Under the owner's stated model of sliders, *this* is the one place a
knob is genuinely needed: it is the difference between a working tool and an inert one.

---

## 3. BRISTLE — the owner's "Sable with different sliders" is literally, verifiably true

Both files declare `"engine": "tuft"`. **Same engine, same code path, same brush version (6).** There is
only one tuft implementation. `TuftSpec.kt:5`: *"The tuft engine (brush version 4): a 2-D sable brush —
tip, belly, trailing bristles, ink load, splay."*

`BRUSH_SPECIALIST_HANDOFF_20261002.md:10` already admitted it:

> "Bristle: rough/dry counterpart to Sable, **preserving Sable's existing deformation engine**."

Nineteen tuft values plus `size` (18 → 32) differ. Nothing else. So yes: sliders only.

**But the reason it *feels* identical is a specific, fixable defect.**

### ROOT CAUSE — `dryness()` is speed-gated to zero below ~750 px/s

`TuftStroke.kt:445-453`:

```kotlin
val dSpeed = u(spec.dry) * smooth(0.3f, 1f, vN) * (0.3f + 0.7f * pc)
```

with `vN = speed·zoom / SPEED_REF` and `SPEED_REF = 2500f` (`TuftStroke.kt:631`):

| drawing speed | `smooth(0.3,1,vN)` | Bristle `dSpeed` (dry 0.8) | Sable (dry 0.5) |
|---|---|---|---|
| 125 px/s | 0 | **0** | **0** |
| 400 px/s | 0 | **0** | **0** |
| **750 px/s** | **0** | **0** | **0** |
| 1250 px/s | 0.198 | 0.159 | 0.099 |
| 2500 px/s | 1.0 | 0.800 | 0.500 |

**Below 750 screen px/s, Bristle's `dry` slider contributes exactly nothing.** And the load term
cannot rescue it: `capacity = 2·bellyR·(30 + 900·ink²)` (`TuftStroke.kt:304`) gives ~6000 px of ink, so
`dLoad` is ~0.02 across a normal stroke.

**At any normal drawing speed Bristle is a solid, fully-loaded, slightly wider Sable.** Only `action`
(0.62 vs 0.4), `tooth` (0.9 vs 0.5) and `size` differ perceptibly. That is precisely the complaint.

### A second inert slider, on both brushes

`tuft.ink` is *"more ink means longer strokes before it runs dry"* — and at ~6000 px of capacity it
**never visibly runs dry**. That is a dead control on Sable too, and Sable is a brush the owner likes,
so it is worth fixing rather than leaving.

### What the engine already has for dry brush (the owner is not asking the impossible)

`jb_tuft.frag` genuinely contains dry-brush machinery: `dry` → `dLocal` (`:125`), sweep bias, graze,
`u_action` edge break-up (`:131`), **bristle streaks** (`:134-135`), **splits and gaps** (`:138-139`),
paper tooth (`:142-144`). `TuftSpec` has `dry`, `bristles`, `tooth`, `splay`, `action` — exactly the
dry-bristle knobs, all four with UI sliders. **The capability exists; the drive signal is gated off.**

### What a genuinely distinct dry bristle would still need

1. **No flow control.** `TuftStroke.kt:480` hardcodes `flow = 1f`. Breakup is only ever a multiplier on
   coverage; a real bristle controls per-bristle ink load.
2. **No spacing control.** `TuftStroke.kt:645` `SPACING = 0.2f` is a private const; `preset.spacing` is
   ignored on tuft.
3. **No per-bristle identity.** Streaks are value noise
   (`jb_vnoise(vec2((y*0.5+0.5)*u_bristles, …))`, `:134`) — no simulated bundle, no bristle that keeps
   its own ink, no splay-driven gap *geometry*.
4. **`tip`, opacity dynamics, `scatter`, `color`, `sizeJitter` are all dead on tuft.** Bristle has no
   `tip` block; opacity is frozen at `preset.opacity.base` (`TuftStroke.kt:58`).
5. **No load-independent dryness floor.** Every term in `dryness()` is speed- or load-gated, so there is
   no way to say "this brush is *always* dry" — which is exactly what Bristle is.

**Recommendation:** fix the speed gate first (small, and it is the actual cause of the complaint), then
decide whether Bristle earns its place as a distinct instrument or should be re-scoped honestly as "a
dry-flow Sable preset". Do not ship it as a separate brush while it is indistinguishable.

---

## 4. SOFT AIR — "terrible"

The preset numbers are a **defensible airbrush and match the spec**. The complaint is one real engine
bug plus numbers nobody has ever checked on a device.

### ROOT CAUSE — an airbrush that cannot build while you hold the pen still

`DabPlacer.kt:69-70`:

```kotlin
val len = hypot(s.x - p.x, s.y - p.y)
if (len <= 0f) { prev = s; return }
```

**A stationary pen emits zero dabs.** And the dwell path that fixes exactly this
(`JbCanvasView.kt:1113-1118`, `t.dwell(s)`) is gated on `strokeTuft != null`. Soft air is a `stamp`, so
`strokeTuft` is null and **the branch never runs.** Holding the nozzle down sprays nothing extra.

For the one shipped preset whose entire identity is `accumulate: "buildup"`, that is an engine gap, not
a preset value. **It is the most likely single cause of "terrible"** — an airbrush that stops when you
stop is the defining failure of an airbrush.

### Secondary, labelled as taste

- **Ceiling 0.9.** `accumulate: buildup` moves opacity to commit-time, so `u_strokeScale = 0.9`
  (`GlPaintEngine.kt:1288`) and the darkest Soft air is 90% grey. It can never reach black. Spec chose
  this deliberately (`JB-1.07:284`) but it is visible.
- `paper.wet 0.3` with `influence 0.2` lays a ±20% mottle over an already-very-soft gradient
  (`jb_contact.glsl:87`). Reads as mush rather than spray.
- `spacing 0.02` on a 60 px soft blob = a 1.2 px dab step = **50 draw quads per 60 px of travel.** A
  cost, not a quality bug.

### The honest summary

`ROADMAP.md:177` already predicted this verdict: *"Every number is a PROVISIONAL T1 value and the owner
judges it on the Note 9 … Device check owed and not done."* And: *"Soft air's flow 0.05 is the most felt
number in the row and no test can say if it is right."* **Fix the dwell gap; do not tune the numbers
without the owner holding it.**

---

## 5. FLAT PAINT — findable only under the wrong shelf

`BrushShelf.kt:37` routes on `p.engine == ENGINE_SMUDGE`, and `flatpaint/brush.json` sets
`"engine": "smudge"`. So `BrushShelf.kindOf` files **Flat Paint on the SMUDGE shelf**, next to the
Smudge utility brush — while Bristle (engine `tuft`, no matching keyword) lands on **PAINT**.

An owner looking for a flat *paint* brush under **Paint** will not find it. One-line fix: check the id
and name before the engine, or give `flatpaint` a `tuft`-style id word. Cosmetic but it is the
difference between a feature being found and being reported as absent.

---

## 6. THE OWNER'S NEW REQUEST — "an upgraded version of Photoshop's stock pen"

> "a pen that would have a square that could become a circle or a trapezoid and have its directions
> change. and be basically like an upgraded version of Adobe Photoshop's stock thing that can go hard
> or soft or have a gradient from hard to soft and all those parameters are all in one pen"

**Good news: four of the five capabilities already exist.** This is much closer than it sounds.

| Requested | Status | Evidence |
|---|---|---|
| square shape | **already supported** | `corner` is the superellipse exponent, clamped `0.5..64`; `2` = circle/ellipse, `4` ≈ rounded square, `16+` ≈ square (`jb_tip.glsl:27-32`) |
| becomes a circle | **already supported** | `corner = 2`, the same control |
| **becomes a trapezoid** | **NOT supported** | a trapezoid is *not* centrally symmetric, so no superellipse exponent produces one. Needs a genuinely new tip shape (a 4-sided asymmetric polygon, or a superellipse with an independent top/bottom width) |
| direction changes | **already supported** | `tip.angle` with `lean` dynamics; `jb_contact.glsl:51` rotates the tip by `v_angle`, and the GPU/CPU twins are algebraically identical |
| hard **or** soft | **already supported** | `hardness`, per-dab since brush v7 (`BrushDabber.kt:151`) |
| **gradient from hard to soft** | **NOT supported — the one real gap** | `hardness` is a **`flat` varying** (`jb_dab.vert:27`), i.e. one value for every fragment of the dab. `jb_tipCoverage` has no spatial hardness term; `inner` comes from a scalar |

**So: one new engine capability.** A per-fragment edge profile — a `softnessSpan` across the footprint
over which the inner radius goes from hard to soft — which is specified in
`PENCIL_WORKS_BY_DEFAULT_20261002.md` §8.

**This is the elegant part of the whole problem, and worth stating plainly as such: that single missing
capability is simultaneously (a) the only thing stopping the pencil drawing the reference's soft
shading, and (b) the missing half of this new pen.** Building it once moves both. It is the highest
value-per-unit-of-work item in the brush programme.

Two honest caveats: the trapezoid is separate work and is *not* covered by `softnessSpan`. And under
the owner's own principle — defaults must do the work — the pen's defaults should arrive preset to a
useful configuration, with `corner`/`angle`/`hardness` present as the deliberate-deviation knobs they
are meant to be, not as something to be dialled in per stroke.

---

## 7. Cross-cutting: one inverted slider, affecting every brush with grain

`BrushKnobs.kt:187-189` binds the **"Paper grain"** knob straight to `paperGrain.depth.base`, with the
hint *"How much the paper's grain shows through the stroke."* But `depth` is **pressure into the
height field**, and `jb_heightCoverage` computes `threshold = 1 - level` — so **depth ↑ means LESS
grain shows through.**

**Dragging the slider right, as any artist reads that sentence, produces less grain.** This inverts the
control relative to its own label, and it is the clearest possible contradiction of the owner's stated
model of what a slider is for. One-line fix: invert the binding and correct the hint. Worth doing
before any owner tuning, or they will tune against a reversed control.

---

## 8. Test and evidence notes

- **The build hole is fixed — do not repeat the stale warning.** `ROADMAP.md:177` warned that brush JSON
  was not a declared test input and `:core:jvmTest` returned green while Soft air was still broken.
  `joybrush/core/build.gradle.kts:44` now declares
  `inputs.dir(rootDir.resolve("brushes")).withPropertyName("shippedBrushes")`, so a preset edit *is*
  covered. **Still pass `--rerun-tasks`** when you need certainty.
- **No version or validation defect exists.** All ten files are consistent with the lowest-version rule
  and pass `BrushValidate`. `eraser` v1, `fill` v2, `smudge` v3 are correct, not mismatches.
- **Every device verdict above is unverified by me.** I read the code; I did not hold the stylus. Per
  `AGENT_BOARD.md`: *"Do not claim phone verification from a build or test report."* These are root
  causes in code for symptoms reported on a device, and the device half is still owed.
- `JB-1.30 📱 Owner signs off each brush on the note 9` is still `⚪ Outline` — never started. Given
  that ten brushes just failed their first real device pass, that row is now the critical path for the
  whole brush programme, not a formality at the end.