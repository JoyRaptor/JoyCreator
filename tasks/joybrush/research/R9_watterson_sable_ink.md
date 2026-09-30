# R9: The Watterson ink brush — one sable brush from whisker to bold black

**For:** Joy Brush (painting wing of Joy Creator, Android, GPL-3.0)
**Date:** 2026-09-30
**Author:** research agent (Claude)
**Question from the owner:** *"One pen tool that can give the line character that Bill Watterson uses in Calvin and Hobbes … thin detail work and the big thick bold lines … depending on tilt, direction, pressure etc. can cover the gamut of that range. We already have a lot built; is there any fine tuning we could do on one or two brushes?"*
**Companion:** the owner's earlier general brief `natural-media-shader-research.md` (pencil / pen / watercolour, written by another AI and kept outside the repo). This file narrows it to one brush, checks it against the code as it stands today, and corrects one point in it (§3.4).

**Legend**
- **[S]** sourced. The link is in §9.
- **[P]** practitioner. An artist or brush maker says it from their own hands. Single voices are marked.
- **[CODE]** read from this repo today. Every line reference was checked on 2026-09-30.
- **[I]** inference. My synthesis. Test it on the phone before relying on it.
- **[VERIFY]** has to be measured on the owner's Note 9 or Note 20 before a design depends on it.

---

## 0. The short version

1. **Watterson inked with a small sable brush and India ink on Strathmore bristol.** He used a crowquill pen for "odds and ends" and a technical pen for lettering. [S: Wikipedia, citing the *Tenth Anniversary Book*] Jake Parker, an inker, names the brush as a Winsor & Newton Series 7 [P]. Watterson himself described his late style as *"a sort of calligraphic brush line"* [S: 2014 Robb interview].
2. **The stick rumour has no support.** No interview, book, or artist write-up found says he inked with sticks. Treat it as myth. [S: absence across the sources in §9] The rumour may be mixing him up with artists who really do draw with sticks, bamboo pens or reed pens.
3. **The whole range comes from one tool.** A good sable round has the **same needle point at every size**, so the belly gives the bold swell and the tip still gives the whisker [P: Houghton]. Its response is **"logarithmic"**: light pressure barely flexes it, then it opens fast [P: Houghton]. It **snaps back** crisply on release [P]. The Joy Brush design has to reproduce those three facts. They are all pressure behaviour, so they reach a phone with no tilt.
4. **Today's Ink brush cannot cover the range** [CODE]. It goes from 15% to 100% of its size in a straight line (about 1:7). Watterson's whisker-to-hair-spike range is roughly 1:20 or more [I, measured by eye off the reference images]. Ink also ignores speed and tilt, and has no end taper and no dry brush.
5. **Most of the gap closes with no code (§5, Step A).** Add a new brush file using inputs the engine already reads: a *hairline shelf* pressure curve, slight thinning at speed, and belly spread on tilt. That is one new folder and one line in `index.txt`.
6. **The owner described how the brush behaves in his hand (§3A, O1–O13).** Those notes go past what a stamp brush can do: a trailing tip that steadies the line, thick spots at corners, dry outer edges on sweeping curves, rough ends on a fast lift, broken hairs, and spatter on jolts. **Revised recommendation:** build this as a **new `"tuft"` engine** (§3B, §5 Step B). That is R2 §4.3's 2-D tuft, extended with O1–O13. It has its own instance layout, so the reviewed stamp contract (JB-0.07 / JB-6.03 Q1) never has to be reopened for this brush. This replaces the earlier "batch it into JB-6.03 Q1" advice.
7. **Tilt on the owner's phone is unknown** ([VERIFY], R7 §1.4). The brush must feel right on **pressure and speed alone**. Tilt is only a bonus on devices that report it.
8. **No invented wobble.** The texture comes from the hand's own waver, which the brush smooths into a calm swell (O7, O13). Do not port the earlier brief's seeded waver (§4.2D there) to this brush.

---

## 1. What Watterson actually used

| Tool | What it did | Source |
|---|---|---|
| Light pencil on **Strathmore bristol board** | Minimal layout sketch. Sundays got more. He did *not* trace his pencils with the brush. | [S] Wikipedia *Calvin and Hobbes* (cites *Tenth Anniversary Book*) |
| **Small sable brush + India ink** | "Most of the remaining drawing": contours, blacks, texture | [S] same |
| **Crowquill pen** | "Odds and ends". An artist copying his originals found the nib was needed for items like Calvin's shirt stripes. | [S] Wikipedia; [P] Tyson Pease |
| Technical pen (Rapidograph) | Lettering | [S] Wikipedia |
| Correction fluid, including typewriter fluid | Fixes. It is visible as raised white on the owner's second reference image (the mouth). | [S] Wikipedia; owner's image 2 |
| Winsor & Newton **Series 7** Kolinsky sable | Brand, per an inker's recommendation page | [P] Jake Parker (single voice, not Watterson's own words) |

**Brush size is not documented by Watterson.** Tyson Pease's "#6" is **Pease's own choice** for his copies, not Watterson's. An earlier fetch summary attributed it wrongly, and the page's own wording is "I went with a 'standard' #6". Houghton gives the general rule inkers follow: *"the biggest brush that creates the finest line needed"* [P].

**Influences that shaped the line:** Herriman, Walt Kelly and Schulz [S: Lines and Colors]. Kelly's *Pogo* is the nearest ancestor of the swelling, springy contour.

---

## 2. The line character, defined (read from the owner's three reference images)

Image 1 is the Sunday title panel with the wagon. Image 2 is the Calvin head, pencils still visible. Image 3 is a forest-and-house panel whose artist is not identified; treat it as the "bold end" target, not as Watterson.

| # | Mark | Where it shows | Width (relative) | Behaviour that makes it |
|---|---|---|---|---|
| M1 | **Hairline** | Hobbes's whiskers, fur ticks, pencil-thin speed lines (img 1) | 1 | Tip only, lightest touch, fast. Ends in a needle. |
| M2 | **Stripe / detail line** | Calvin's shirt stripes and eye lines (img 2) | 2–3 | Nearly even width. This is the crowquill-like mark. The brush has to *hold* a thin width steadily at light-medium pressure. |
| M3 | **Swelling contour** | Hobbes's back, the wagon body, Calvin's cheek (img 1, 2) | 3 → 8 → 3 | Pressure rises mid-stroke and falls again. The swell is smooth and never stepped. |
| M4 | **Bold flick** | Calvin's hair spikes, Hobbes's stripes, the tail stripes (img 1, 2) | up to ~20 | A **blunt, heavy start** (brush pressed down) and a **long needle end** (lifted while still moving). This is the signature mark. |
| M5 | **Dry-brush drag** | The grey streaks and spatter left of the title (img 1); grass tufts (img 3) | wide | Fast, low ink. The mark breaks into **streaks running along the stroke** (bristle channels), not into random dots. |
| M6 | **Solid black** | Hobbes's stripes, the wagon wheels (img 1); the forest (img 3) | fill | Full-pressure belly strokes. The edges stay crisp. |

What ties M1–M6 together [I]:
- **Ink is always black.** Width changes; darkness never does. The only grey is dry-brush breakup and anti-aliasing.
- **Edges are crisp.** India ink on smooth plate bristol does not feather. Paper texture shows only when the brush runs dry.
- **The line records speed and confidence.** Houghton: brushes *"record everything: speed, hesitation, confidence, doubt"* [P]. Heavy smoothing erases the character.

---

## 3. How a sable brush produces that: behaviours to build

### 3.1 Hairline shelf, then the belly opens [P: Houghton; I]
Houghton, on a sable's response: *"light pressure produces minimal flex, medium pressure opens the belly, heavy pressure lays the brush nearly flat."* So the width-vs-pressure curve is **flat and low at first (a shelf), then steep**. It is convex. A straight line (today's Ink) or a square root (JB-6.03's wash law) is the *opposite* shape at the light end: both put a third of the width on the first tenth of the pressure. That leaves no room for M1 and M2.

### 3.2 The point does not grow with the brush [P: Houghton]
*"Quality brushes taper to nearly identical fine points regardless of size."* In engine terms, **the hairline width should be absolute, not a fraction of Size**. If the owner turns the Size slider up for bold work, the whiskers should still be available. [I] Today the minimum is `size × curve(0)`, so it scales with Size. Step A handles this with a very low curve floor; a proper fix is §5 Step B4.

### 3.3 Snap: fast recovery [P: Houghton]
Sable springs back *"crisply and immediately"*. **Width follows pressure with almost no lag.** Synthetic brushes are the laggy ones.

### 3.4 Correction to the earlier brief
The earlier `natural-media-shader-research.md` §4.2A gives the *flex nib* a slow return (100–250 ms) because a flooded steel nib is slow to go thin again. **Do not apply that to this brush.** A sable brush does the reverse. Put any lag on the *way down* (belly settling, ≤ 20 ms) and none on the way up.

### 3.5 Flick taper: the end is made by lifting while moving [P: TipTop, bisgedi; I]
Procreate brush makers chasing this exact look say the variable width comes from making size **very** pressure-sensitive *and varying pen speed* (bisgedi, Procreate Folio). TipTop, whose paid version became "Master Ink", says the taper needed a *combination* of opacity, pressure sensitivity and shape, and that *"set it and forget it"* was the goal. On paper the needle end of M4 is the brush leaving the paper *while travelling*. A fixed "taper length" setting (Procreate's approach) is the fiddly workaround this project wants to avoid. [I] Derive the taper from lift speed: a fast lift gives a long needle, and a slow, stationary lift gives a blunt end.

**Note:** TipTop varied *opacity* for the taper. For India ink, **do not** tie opacity to pressure. It turns hairlines grey, which is the "airbrush" failure the earlier brief lists.

### 3.6 Dry brush is streaks along the stroke [S: Strassmann 1986; P: Houghton; I]
Houghton: a nearly dry brush dragged over paper leaves *"broken, textured marks"*, and it is *exclusively a brush technique*. Strassmann's *Hairy Brushes* (SIGGRAPH '86) models the sumi brush as **bristles that each run out of ink over the stroke**. That is what makes the parallel streaks in M5. Paper tooth then breaks up each streak. Joy Brush's page-anchored grain threshold already gives the tooth part. The **cross-stroke bristle pattern** is what is missing. R2 (Expresii) covers the tuft and dry-map form of the same idea.

### 3.7 Ink load that never gets in the way [I]
A real brush is re-dipped. The digital one should **start every stroke full**. Load should drain only on long, fast, heavy strokes, and it only drives dryness in M5. It must never make an ordinary contour fade out. The owner must never have to "re-dip".

---

## 3A. The owner's hand-knowledge (2026-09-30, owner rulings)

The owner described these from his own inking. **They outrank §3 wherever the two differ.** The wording is condensed from his message; the "Engine meaning" column is my translation, marked [I] where I interpreted.

| # | The owner's observation | Engine meaning |
|---|---|---|
| **O1** | **Spatter** on quick flicks, *and* when pressure goes from light to hard very quickly. "Anything jolty" is more likely to spatter. | Spatter rate ∝ ink load × **jolt**. Jolt = the largest of: sudden speed change, fast pressure rise, sharp direction snap, and a fast lift. Seeded, so replay matches. |
| **O2** | The belly sweeps large, wide areas. Size can go beyond a real brush. | The model must stay stable and fast at very large belly widths. The Size slider already reaches 4096 px [CODE]. |
| **O3** | Long strokes: **splitting** and **variable dryness**. | An ink load that drains with distance × width × pressure. Dryness shows as bristle streaks and splits, not uniform grey. |
| **O4** | A **broken hair** at the outside shows *only* when painting with the belly. It is a very thin line, sometimes intermittent depending on the angle. | 0–3 seeded "stray hairs" per stroke, sitting just outside the belly edge. They touch only above a belly-engagement width. Contact switches on and off with the angle between the tuft and the direction of travel. Each is drawn about 1 px wide. |
| **O5** | Very light work is **thin in every direction**, fast or slow. | At light pressure the contact is a small round tip: no elongation, no directional bias, no trailing. |
| **O6** | **Thin and slow**: a little thicker, because the ink settles. If the brush is dry, slow makes it *more solid*. | Slow + light gives width about +10–15% and less dryness breakup. Speed lowers both. |
| **O7** | A steady stroke in one direction at a constant rate looks thin. Hand jitter is smoothed "like a lazy mouse" because the contact is long and thin: **the whole width drifts up and down in a calm waver**, instead of 1–2 px of jitter. | **The brush is its own stabiliser.** Cross-track pen motion is low-passed over a distance equal to the contact length. Along-track, the mark's leading edge stays at the pen, so the line never lags. |
| **O8** | Pressing a little while moving: **still thin but long**. That is the natural stabilisation and the calligraphic feel. | Contact length grows with speed × pressure. Width grows with pressure only. So speed lengthens the footprint without fattening the line. |
| **O9** | A **sharp change of direction** (a switchback, even a 30° turn): the bristles rotate and settle, leaving a **thicker spot** and some **intermittent areas**. | The bend direction lags the new travel direction (distance-damped, R2 §4.3). While they disagree the footprint is side-on, so wider, and splitting and dryness rise. Both fade as the bristles realign. |
| **O10** | **Fast, broad, sweeping curves**: the **inside is thick** because the ink is pushed there. The **outside is drier**, with intermittent bristle touches. | A lateral ink bias ∝ speed² × curvature. Bristles on the inner side stay solid; outer-side bristles lose contact and run dry first. |
| **O11** | **Lifting:** if the brush is wet and lifts slowly, the bristles come back together to a point. A **quick lift** gives them no time to close, so the end is **wider and rougher**. | A "splay" state that closes over time: fast when wet, slower when dry. The end of the stroke uses the splay at lift, so a slow lift gives a clean needle and a fast lift a split, rough end. |
| **O12** | Very good at **filling large areas dark**, noir-comic style, with natural texture from waver. | A loaded belly sweep is solid black. Texture appears only from dryness, splay, corners and waver, never as a grey tint. |
| **O13** | The texture comes from the waver. [I: read as the *hand's* waver, not a synthetic one] | No added random wobble by default. The texture comes from O7's filtered hand motion plus O3/O9/O10/O11. See open question §8.5. |

These agree with Chu & Tai's virtual Chinese brush (§7), which the owner singled out: bristle spreading under lateral friction, and **plasticity**, meaning a wet brush does not instantly return to shape.

---

## 3B. One model that produces O1–O13: the 2-D tuft [I]

The base is **R2 §4.3** (bend direction `b`, contact length `ℓ`, belly width `w`, plasticity, split and dry maps, anchor at the pen). The additions are marked ➕. Everything runs **per input sample on the CPU**, uses distance-based damping where possible, and depends only on the recorded samples plus the stroke seed. Replay and re-brushing therefore stay identical (JB-5.01).

**Per-sample inputs:** `p` (pressure after the file's curve), velocity `v` (eased, as `BrushDabber` does), curvature `κ`, `dp/dt`, and tilt/azimuth when reported (NaN means skip).

**State:**

| State | Meaning | Rule |
|---|---|---|
| `w` | Belly half-width | The hairline-shelf curve of `p` (§3.1, **not** R2's square root). ➕ Slow + light gives +10–15% (O6). ➕ Side-on misalignment widens it (O9). |
| `ℓ` | Contact length along the tuft | `ℓ = ℓ_tip + k_p·p + k_v·p·|v|`. Light touch gives `ℓ ≈ ℓ_tip`, a round footprint (O5). Pressure × speed lengthens it without widening (O8). |
| `b` | Bend direction | R2 §4.3: slerp toward −v̂ (plus tilt when reported) over lag length `λ_b`. ➕ Misalignment `m = angle(b, −v̂)` drives the O9 thick spot and a split/dry boost ∝ `sin m`. |
| ➕ `c` | Cross-track offset of the footprint | **The built-in stabiliser (O7).** Split the pen's motion into along-track and cross-track parts. Only the cross-track part is low-passed, over a distance ∝ `ℓ`. The front of the mark stays at the pen along the track. Light touch (`ℓ` small) means almost no filtering, so fine control is untouched. It works *with* `StrokeSmoother`, not instead of it; the owner's slider still applies on top. |
| ➕ `σ` | Splay (0 closed … 1 spread) | It rises quickly with pressure, jolt and dryness. It closes with time constant `τ_close`, which is short when wet and long when dry. The end of the stroke keeps the `σ` it had at lift, which gives O11. It drives the split count and edge roughness. |
| `L` | Ink load | 1 at every touchdown (no re-dipping, §3.7). It drains ∝ `w·p·ds` (O3). |
| `D` | Dryness | `f(1 − L, |v|)`, **lowered when slow** (O6), raised by `sin m` (O9) and on the outer side of a curve (O10). |
| ➕ `β` | Lateral ink bias | `β ∝ |v|²·κ`, signed toward the inside of the curve. Across the width `y ∈ [−1, 1]`, each bristle's threshold shifts by `β·y`: the inner side stays solid, the outer side breaks up (O10). |

**What gets drawn:**
- **The footprint.** A teardrop SDF from the belly at the pen (width `w`) to the trailing tip (`ℓ` behind, along `b`), swept between samples (R2 §4.3 "stretched tuft"). Its edge stays crisp (India ink).
- **The bristle strip.** About 32 seeded bristles across the width, each with its own ink level and contact height. The CPU writes a stroke-space strip (lateral bristle × arc length). The footprint shader samples it and thresholds it against **the page grain Joy Brush already has**. Bristles running dry leave **streaks along the stroke**, and the tooth breaks each streak up. Splits come from `σ`, dry areas from `D`, and the inside/outside difference from `β`.
- ➕ **Stray hairs (O4).** Drawn as ordinary hard 1 px dabs through the **existing stamp path**. Each is placed at `±(1 + δ)·w` and switches on and off along the stroke with a slow seeded pattern, weighted by the angle between `b` and travel. It exists only while `w` is above the belly threshold.
- ➕ **Spatter (O1).** Also ordinary hard round dabs through the stamp path.
  - Jolt `J` is the largest of: `|d|v|/dt|`, the positive part of `dp/dt`, the direction snap `|dθ/dt|·|v|`, and the lift rate at pen-up. Each is normalised by its own threshold.
  - The rate is `λ = k·L·J`. The droplet count per sample is seeded Poisson.
  - Each droplet is thrown along `v̂`, plus an outward part on curves, within about ±15°. It lands at a distance ∝ `|v|` × a seeded factor, is mostly tiny with a few large (power law), and is stretched along the throw when fast.
  - Default: on, and low.

**Why a new engine and not more stamp fields:** the footprint needs about ten numbers per step (`P`, trailing tip, `w`, `b`, `σ`, `D`, `β`, arc length, strip offset). Widening the stamp engine's reviewed per-dab record that far would reopen JB-0.07 for every other brush. A `"tuft"` engine (`engine` is a free string; `"wet"` is already reserved but unrendered [CODE]) gets its own instance layout and shader. It reuses the page grain, the smoother, the speed filter, and the stamp path for hairs and spatter.

**Patents (R8):** this is a **2-D** footprint. It has no 3-D tool model and no captured-deformation table, which clears US 9,030,464 per R8 §2.5. The bristle strip is a raster mask inside one footprint. **Never export one vector path per bristle** (US 8,605,095, active until 2032, R8 §2.7). Vector export, if ever built, is one outline per stroke.

**Phone cost [I]:** a handful of scalar updates per sample, one strip-texture row per sample, and one SDF quad per swept step. That is lighter than the grain shader already shipping. Measure it on the Note 9 when B1 lands.

---

## 4. Joy Brush today [CODE, verified 2026-09-30]

| Area | Today | Where |
|---|---|---|
| Built-in Ink | `size 6`, pressure `[[0,0.15],[1,1]]` (≈1:7, linear), hardness 0.95, opacity/flow 1, spacing 0.04, smoothing 0.35. **Pressure only.** | `joybrush/brushes/ink/brush.json` |
| Inputs a file can use | `pressure, tilt, speed, direction, lean, attack, distance, random, strokeRandom, barrel` | `core/brush/BrushPreset.kt` (`BrushInput`) |
| Speed | `px/s ÷ 3000`, capped at 1; eased per dab with a 50 ms time constant | `core/brush/Dynamics.kt` `normalise`; `BrushDabber.stepSpeed` |
| Tilt | `tilt ÷ (π/2)`, where 0 means upright. An unreported axis is **skipped** (NaN), not treated as zero. | `Dynamics.kt` |
| Curve `y` | Not range-checked, so a size multiplier above 1 is legal (a tilt spread). `x` must be 0..1. | `BrushValidate.kt` rule 19 |
| Per-dab vs per-stroke | Size, angle and flow are per dab. **Hardness, both grain depths and stroke opacity are fixed at the first dab.** | `BrushDabber.kt` (`if (dabCount == 0)` block) |
| Tip | Superellipse, aspect, shape taper, `minPx` fade, hardness. Aspect is **one value per stroke** (uniform). | `joybrush/shaders/jb_tip.glsl`; JB-6.03 intro |
| Paper grain | Page-anchored height threshold. This is the right model. | `jb_grain.glsl`, `GrainMath.kt` |
| Stroke-end taper | **None.** The Procreate importer notes the missing field. | `ProcreateImport.kt` |
| Reservoir / dry brush | **None.** `"wet"` validates but nothing renders it. | `BrushValidate.kt`, `BrushPreset.kt` |
| Smoothing | One slider, Gaussian along the arc length, wider when slow, corners protected. The 1€ filter exists but is unused. | `core/input/StrokeSmoother.kt` |
| Related specs | JB-6.03 "The one great brush" (🟨 Draft; its Q1 is the per-dab hardness/aspect contract change); JB-6.01/6.02 (watercolour); JB-1.07 (preset numbers provisional) | `tasks/joybrush/specs/`, `ROADMAP.md` |

---

## 5. The tuning ladder

### Step A: zero code, one new brush file (do this first)

A new folder, `joybrush/brushes/sable/brush.json`, plus the line `sable` in `index.txt`, placed after `ink`. It **does not replace Ink**. The owner compares the two side by side on the phone.

```json
{
  "format": "joybrush.brush",
  "version": 1,
  "id": "joybrush.sable",
  "name": "Sable",
  "engine": "stamp",
  "tip": { "corner": 2, "hardness": { "base": 0.98 }, "minPx": 1 },
  "size": {
    "base": 18,
    "inputs": [
      { "input": "pressure", "curve": [ [0, 0.04], [0.3, 0.08], [0.6, 0.35], [0.85, 0.75], [1, 1] ] },
      { "input": "speed",    "curve": [ [0, 1.12], [0.08, 1], [0.25, 1], [1, 0.8] ] },
      { "input": "tilt",     "curve": [ [0, 1], [0.55, 1], [1, 1.4] ] }
    ]
  },
  "opacity": { "base": 1 },
  "flow": { "base": 1 },
  "spacing": 0.04,
  "accumulate": "wash",
  "blend": "normal",
  "smoothing": 0.25,
  "license": "CC0"
}
```

Why each number (all provisional; the owner's hand decides):

| Setting | Value | Reason |
|---|---|---|
| `size.base` | 18 | This is the *belly* (M4/M6). It is 3× Ink, so one brush reaches bold without touching the Size slider. |
| Pressure curve | 0.04 → 0.08 over 0–30%, then opens to 1 | The **hairline shelf** (§3.1). The first third of the pressure stays at 0.7–1.4 px (M1/M2); the swell (M3) and the bold end (M4) happen above 60%. The range is ≈ 1:25. |
| Speed | ×1.12 when very slow, flat from 8% to 25%, ×0.8 at full speed | Very slow lines thicken slightly as the ink settles (O6). Fast strokes thin (§3.5, bisgedi). The flat middle stops ordinary contour work from wobbling in width. |
| Tilt | Flat to 0.55, then up to ×1.4 flat | The belly laid down. It is **flat across a normal writing grip**, so a phone without tilt, or with coarse tilt, feels the same. A missing tilt reading is skipped (NaN), so the brush is identical with no tilt. |
| Hardness | 0.98 | India ink on plate bristol: a crisp edge (§2). |
| Opacity / flow | 1 / 1, no pressure input | Ink is always black (§2, §3.5). |
| Smoothing | 0.25 (Ink 0.35) | Keeps the line's life (§2). The owner's slider still overrides it. |

Test, from the jvmTest build-gap note in ROADMAP: brush JSON files are not declared test inputs, so run `:core:jvmTest --rerun-tasks` after adding the folder. `ShippedBrushFilesTest` and `DefaultPresetsTest` must pass.

**What Step A cannot do:** end tapers, dry brush, a lean-shaped footprint, the built-in stabiliser, or a hairline that stays fixed when Size changes. Step A is a first taste of the pressure and speed range on the phone, nothing more.

### Step B: the `"tuft"` engine (a new spec row; the Lead assigns the number)

§3B is the design. Build it in four slices, each one the owner can judge on the phone:

| Slice | Delivers | Owner notes | Tests (§6) |
|---|---|---|---|
| **B1: the feel** | Footprint SDF, contact length, bend lag, built-in cross-track stabiliser, corner thick spot, absolute hairline (§3.2) | O5, O7, O8, O9 | T1–T3, T5, T6, T9–T11 |
| **B2: ink and dryness** | Load, bristle strip on the page grain, inside/outside bias, splay and lift ends, slow-settle | O3, O6, O10, O11, O12 | T4, T7, T12–T14 |
| **B3: accidents** | Spatter on jolts; stray hairs on belly strokes | O1, O4 | T15, T16 |
| **B4: big sweeps** | Performance and stability at large belly widths; noir fills | O2, O12 | T17 |

- **Before B1, [VERIFY] on the Note 9:** does the pen report tilt, and how many pressure samples arrive during a fast flick lift? Both come from R7's Stylus Probe. If the S Pen already ramps pressure down over enough samples, the O11 end shape comes straight from pressure plus `σ`, with no synthetic tail.
- **JB-6.03, for the Lead:** R2 §4.3 is the source for both JB-6.03 (a *reduced* tuft on the stamp engine: radius + hardness) and this engine (the full 2-D tuft). The owner's notes show the reduced form cannot reach O7–O11. The Lead should decide whether JB-6.03 stays a watercolour-wash row on the stamp engine or folds into the tuft engine. I have not changed JB-6.03.
- **Patents:** see §3B. Counsel reviews before a US launch.

---

## 6. Acceptance tests (the owner judges, on the Note 9)

| # | Test | Pass |
|---|---|---|
| T1 | **Whisker:** light, fast flicks | A continuous black needle line with no dotting and no grey fade beyond anti-aliasing |
| T2 | **Stripes:** ten parallel light-medium lines | Even width along each line; no swelling from hand tremor |
| T3 | **Contour:** a slow press–release–press arc | A smooth swell with no stepped widths; width follows the pen with no visible lag |
| T4 | **Hair spike (M4)** | A blunt heavy start. A slow lift gives a needle; a fast lift gives a wider, split, rough end (O11). |
| T5 | **Range without Size:** whisker and wheel-black in one brush at one Size | Both reachable by pressure alone |
| T6 | **No tilt:** the same strokes by finger or tilt-less pen | Same feel. Tilt only adds spread. |
| T7 | **Dry drag** | A long, fast, heavy drag breaks into streaks *along* the stroke; a slow drag stays solid (O3, O6) |
| T8 | **Side by side with Ink** | The owner prefers Sable for C&H-style work, or names what is wrong |
| T9 | **Very light, any direction** (O5) | Thin and round in every direction; no calligraphic bias |
| T10 | **Steady horizontal line with deliberate tremor** (O7, O8) | The whole line drifts in a calm, slow waver; no 1–2 px jitter; the front of the line never lags the pen |
| T11 | **Switchback and 30° turn** (O9) | A thicker spot at the turn, with a little breakup that settles within a short distance |
| T12 | **Fast broad S-curve** (O10) | Solid on the inside of each bend; dry and broken on the outside; the sides swap where the curve flips |
| T13 | **Thin and slow** (O6) | Slightly thicker than the same line drawn fast, and more solid when the brush is dry |
| T14 | **Noir fill:** large area with belly sweeps (O12) | Solid black; texture appears only at fast or dry edges, never as grey |
| T15 | **Jolts** (O1): a quick flick, a sudden light-to-hard press, a direction snap | Spatter appears. A smooth stroke has none. Replaying the stroke gives the same droplets. |
| T16 | **Stray hair** (O4) | Appears only on belly strokes; a thin, intermittent line just outside the edge that changes with angle; never on light work |
| T17 | **Big sweep performance** (O2) | No visible lag on the Note 9 at the largest practical belly width |

---

## 7. Other artist-written material worth a look

- **C. Merritt Houghton**, *"INKING: Brushes Explained — Size, Snap, Flow, and Control"* (Substack, April 2026). The clearest artist description of snap, belly and tip, and of logarithmic vs linear response. It is the source for §3.1–3.3. [P]
- **Tyson Pease**, *"Wattersmith 1: drawing from Bill Watterson."* A working artist copying Watterson with a red sable brush. Its findings: "incredible vivacity", real limits on how fine the brush can go, and a nib needed for stripes. [P]
- **Procreate Folio thread "TipTop Brushes – C&H"** (the owner's link). Brush makers chasing the same brush. It supports the scan-based crisp tip, "set it and forget it", and size very sensitive to pressure plus pen speed. [P]
- **Jake Parker, Tools page.** Names the W&N Series 7 as Watterson's brush and describes the snap of brush pens. [P]
- **Chu & Tai, Virtual Chinese Brush** (project site, with samples the owner liked; IEEE CG&A 2004). A deformable 3-D tuft with flattening, lateral bristle spread, **plasticity** of the wet brush, and paper-pore resistance at the tip. It matches O9–O11 in the owner's own terms. Use it as the **visual reference** for B1–B2, **not** as code to ship: blueprint §5 and R8 forbid a 3-D tool model. [S]
- **Strassmann, "Hairy Brushes"** (SIGGRAPH '86). Bristles as ink carriers, the origin of the dry-brush streak model. [S]

Not found: a Watterson interview naming brush size or brand in his own words; any artist-written quantitative spec for a digital sable brush.

---

## 8. Open questions for the owner

1. **Name and place:** "Sable" next to Ink, or replace Ink once Sable wins T8?
2. **Tilt:** does the Note 9 report tilt at all? The Stylus Probe answers this (R7 [VERIFY]).
3. Is image 3 (forest and house) yours, or another artist's? It is the target for M5/M6 either way.
4. ~~Spatter: wanted, or never?~~ **Answered 2026-09-30 (O1):** yes, on quick flicks, sudden light-to-hard presses, and anything jolty.
5. **O13:** is the texture meant to come *only* from the hand's own waver, filtered by the brush (my reading)? Or should there also be a gentle waver of the brush's own, with a knob?

---

## 9. Sources

- Wikipedia, *Calvin and Hobbes*, art and technique (cites the *Tenth Anniversary Book*): https://en.wikipedia.org/wiki/Calvin_and_Hobbes
- Wikipedia, *Bill Watterson*: https://en.wikipedia.org/wiki/Bill_Watterson
- Watterson–Robb interview excerpt (*Exploring Calvin and Hobbes*, 2014/2015), "calligraphic brush line": https://www.washingtonpost.com/news/comic-riffs/wp/2015/03/10/read-heres-an-excerpt-from-bill-wattersons-rare-new-calvin-and-hobbes-interview/ · Billy Ireland Cartoon Library: https://library.osu.edu/site/cartoons/tag/bill-watterson/
- Tyson Pease, Wattersmith 1: https://tysonpease.com/portfolio/wattersmith-1-drawing-from-bill-watterson/
- Lines and Colors, Bill Watterson: https://linesandcolors.com/2008/06/07/bill-watterson/
- Jake Parker, Tools: https://www.mrjakeparker.com/tools
- C. Merritt Houghton, Brushes Explained: https://cmerritthoughton.substack.com/p/art-inking-brushes-explained-size
- Procreate Folio, TipTop Brushes – C&H: https://folio.procreate.com/discussions/12/1/24482
- Strassmann 1986, Hairy Brushes: https://history.siggraph.org/?p=116042
- Chu & Tai, Virtual Chinese Brush: https://cse.hkust.edu.hk/VCB/
- Baxter & Lin, A Versatile Interactive 3D Brush Model (PG 2004): https://gamma.cs.unc.edu/BRUSH/Baxter-Lin-PG04-Submission.pdf
- In-repo: R2 (Expresii tufts/dry map), R7 (stylus, tilt [VERIFY]), R8 (patents), JB-6.03 spec.

---

## 10. Built (2026-09-30)

The owner asked for all of §3B, with sliders. Everything below is on `joy-creator` and was checked on the Note 9 with the tuning sheet's **Test** strokes.

| Owner note | Where | Slider |
|---|---|---|
| Hairline shelf, absolute needle point (§3.1, §3.2) | `TuftStroke.curve`, `tipR` | Hairline, Light touch |
| O5 light work is round · O8 thin but long at speed | `TuftStroke.look` (`len`: 0 at a feather touch; grows with pressure × speed; never trails behind the touch-down) | Trail |
| O7 built-in steadiness, across the stroke only | `TuftStroke.step` (distance-eased travel direction; cross-track low-pass over the contact length; bounded) | Steadiness |
| O9 turn blot | bend direction lags the travel direction by distance; misalignment widens and dries | Spring, Turn blot |
| O6 slow settle · fast thinning | `look` / `dryness` | Slow settle, Fast thinning |
| O3 ink load and dry brush | `load` drains with width × distance; full at every touch-down | Ink load, Dry brush |
| O10 sweep | lateral acceleration → `bias` → `jb_tuft.frag` dries the outside | Sweep |
| O11 splay, quick-lift ends | `splay` opens fast, closes slowly when dry; the lift tail is a needle that dries out, rougher when splayed | Splay, Trail |
| O1 spatter on jolts (not at touch-down) | jolt = sudden press, speed change or direction snap; seeded | Spatter |
| O4 stray hairs on belly strokes only | 0–3 seeded hairs, on/off along the stroke, weighted by swing | Stray hairs |
| Bristle streaks and paper tooth | `jb_tuft.frag`: streaks in stroke space, the page-anchored tooth on dry parts only | Bristles, Paper tooth |
| Tilt spread (pens that report tilt) | `look` | Tilt spread |

- **Format:** brush version 4, `engine: "tuft"` plus the `tuft` section (`TuftSpec`). `brushes/sable` ships after Ink.
- **Tuning:** ⋯ → *Tune Sable…* opens a sheet that leaves the canvas drawable. Each slider applies to the next stroke. Tuning is saved per brush and laid over the file. **Reset** forgets it. **Test** draws the fixed `TuftTestSheet` strokes as ONE undo (`UndoLog.mergeNewest`).
- **Tests:** `TuftStrokeTest` has one or more per ruling, and `TuftTuningTest` checks that every knob is wired, the tuning round-trips, Test is one undo, and the in-between tiles are released.
- **Not done:** the PC Brush Lab (WebGL pilot) does not draw tuft brushes. Ink layers (JB-5.01 replay) do not know the tuft engine, and this screen refuses ink files anyway. The drawer sample shows the silhouette only, not the streaks.
