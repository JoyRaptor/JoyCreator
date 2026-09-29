# JB-1.06 — Smudge & nudge: one carried colour per brush, and pixels that move

| | |
|---|---|
| **Tier** | T1 |
| **Status** | 🟨 Draft — **the brush-file shape of a smudge or push brush is a contract question (Q1) and R20's layer rule has no home in core (Q2).** Decisions 1–9 are buildable and are the whole of the maths; the file format cannot be written until Q1 is ruled |
| **Needs** | 1.05 |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/Smudge.kt` (the carried colour + the one-dab mix) · NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/Push.kt` (the displacement geometry) · NEW `joybrush/core/src/commonTest/.../brush/SmudgeTest.kt`, `PushTest.kt` · *(after Q1)* EDIT `joybrush/core/.../brush/BrushPreset.kt` (`SmudgeSpec` + two engine words) · EDIT `BrushValidate.kt` · EDIT `BrushJson.kt` (`wordsNeedingVersion`) · EDIT `commonTest/doc/EnumFreezeTest.kt` · *(T1 only)* NEW `joybrush/androidkit/.../gl/SmudgePass.kt` (the canvas read) · EDIT `GlPaintEngine.kt` |
| **Estimated size** | ~140 lines of core maths + ~230 lines of tests · engine pass ~180 lines |

> **This row is two tools and the board says one.** It also has a **patent constraint that is
> absolute** (blueprint §5, read from the actual claims, R8), and it is quoted verbatim in Decision 1
> because getting it wrong is not a style question — it is the difference between shipping and not
> shipping. Everything else on this row is ordinary engineering.

## Goal

Two of the owner's named brushes (blueprint §1 idea 7: *"the stamp engine — ink, pencil/dry texture,
marker, soft airbrush, **smudge, nudge**, eraser — all presets of the same engine"*).

- **Smudge** drags the paint that is already on the canvas. It carries **one** colour with it,
  mixes toward the canvas and toward the brush's own chosen colour, and does all of that in **one**
  dab.
- **Nudge (push)** displaces the pixels under the tip along the stroke, so a passage of colour can
  be pushed aside without being lost. R17 settles the overlap: *"Liquify is NOT in there (JB-1.06
  nudge covers push)"* — so this row's nudge is **pixel push**, and it is the thing a liquify tool
  would be built from later.

The existing `Nudge` class (JB-2.16a) is the *selection* nudge — move what is selected by one screen
px — and it is built, tested and referred to by JB-2.16. **This spec does not touch it, does not
duplicate it, and does not change what a nudge means there.** See Q3 for why the two coexist.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.brush

/**
 * The ONE carried colour of a smudge brush (blueprint §5; see Smudge's class note for the claims).
 * ONE per brush, for the stroke's life, held here and NOWHERE ELSE. There is no reservoir, no
 * pickup store and no second copy: the whole rule is that this object is the only place a smudge
 * brush's colour lives between dabs.
 */
class SmudgeCarried(
    /** The brush's own chosen colour, straight (not premultiplied), 0..1. The "load" source. */
    val loadR: Float, val loadG: Float, val loadB: Float,
    /** 0..1 — how much of the carried colour a dab's paint becomes. */
    val strength: Float,
    /** 0..1 — how much of the canvas a dab leaves behind in the carried colour. */
    val pickup: Float,
    /** 0..1 — how much of the brush's own colour a dab loads. */
    val load: Float,
) {
    /** The colour as premultiplied RGBA, the form the canvas is in. */
    val carriedR: Float; val carriedG: Float; val carriedB: Float; val carriedA: Float

    /** THE one step. See Decision 1. Returns the new carried colour; the input is not modified. */
    fun afterDab(canvasR: Float, canvasG: Float, canvasB: Float, canvasA: Float): SmudgeCarried
}

object Smudge {
    /**
     * What ONE dab writes at one pixel, premultiplied RGBA.
     * [coverage] is the tip's antialiased coverage times flow. A pixel the canvas has nothing in
     * (canvasA == 0) is left alone (Decision 4).
     */
    fun dab(
        carried: SmudgeCarried,
        canvasR: Float, canvasG: Float, canvasB: Float, canvasA: Float,
        coverage: Float,
    ): FloatArray   // 4 floats, into a caller-supplied array where the signature allows
}

object Push {
    /**
     * The displacement one push dab applies, in DOCUMENT px, and the flow that goes with it.
     * Along the stroke only: a push never moves anything across itself.
     */
    fun offsetFor(dab: Dab, amount: Float): FloatArray   // {dx, dy, flow}
}
```

## Steps

1. Write `SmudgeTest.kt` and `PushTest.kt` from the Tests section, first.
2. Write `Smudge.kt` and `Push.kt`. Green. **This is the whole of the maths and needs no answer to
   Q1** — the class is fed numbers, and the numbers' names come later.
3. Stop for the format. Q1 decides the words in `brush.json`, `BRUSH_VERSION`, and `BrushValidate`.
4. The engine pass (T1, after 2): the canvas read and the per-dab commit.

## Decisions

1. **The carried colour is ONE value that mixes toward the canvas AND toward the brush's own colour,
   in one step, and there is nowhere else for it to live.** Quoting blueprint §5 (R8, from the
   actual claims, re-checkable in the USPTO Patent Center before a US launch):

   > *"Smudge / colour pickup: ONE carried colour + amount per brush that mixes toward the canvas
   > and toward the chosen colour, one dab — never separate reservoir and pickup stores, never
   > 'deposit only picked-up paint when there is enough' (8,462,173; 8,599,213). Wet/Load/Mix/Dry
   > sliders are fine."*

   The one step, for one dab, at one pixel, with `canvas` the premultiplied RGBA under the tip and
   `carried` this brush's single carried colour:
   ```
   after = lerp( lerp(carried, canvas, pickup), loadColour, load )
   out   = lerp(canvas, carried, strength × coverage)        // what is written to the canvas
   ```
   Both updates happen in the same dab, from the same one stored colour, and there is no third
   quantity anywhere. `lerp(a, b, t) = a + (b − a) · t` with `t` clamped to `0..1`.
   **Why this shape and not the obvious one.** The obvious smudge is a running average or a
   reservoir that fills and only spills when it is "full"; both are the claimed recipes, and both
   also *look* wrong — they leave a hard edge where the brush starts and a tail where it stops. A
   single exponential move toward whatever is underneath is continuous everywhere, costs one lerp,
   and is reversible by running the brush back.

2. **The three rates are `strength`, `pickup` and `load`, and they are the three numbers a person
   can feel.** `strength` = how much the canvas takes on the carried colour (a hard 1.0 is a full
   smear; 0.2 is a gentle blend). `pickup` = how fast the carried colour forgets the canvas it has
   passed over (1.0 is "purely what came off the canvas"; 0 is a brush that remembers its own
   colour forever and just drags it around). `load` = how fast it takes up the brush's chosen
   colour. Blueprint §5 says outright that Wet/Load/Mix/Dry sliders are fine, so all three are
   ordinary settings. Their names and ranges are the file format (Q1); their *maths* is this
   decision and is not in question.

3. **The carried colour is stroke-lifetime state and it is deterministic.** It starts as
   `loadColour` with `amount = 0` — the brush's own colour, having carried nothing — so the first
   dab smears the brush's colour and picks up as it goes. Because it is a pure function of
   `(carried, canvasSample, loadColour, rates)`, a recorded stroke replayed with the same seed and
   the same canvas produces the same pixels, which is what blueprint §2's "strokes are recordings"
   and R20's "re-brush and it draws as if that pen" both require. The state is reset at
   `beginStroke` and dropped at `endStroke`; it is never written to a tile, a `StrokeRecord`, a
   `Dab`, or a file. **There is a test that the class has no other mutable field, and a second that
   replays a whole sample sequence twice and gets the same result** — that is the machine-checkable
   half of "there is nowhere else for it to live".

4. **A smudge does nothing where the canvas is empty.** `canvasA == 0` returns the canvas
   unchanged and does not update the carried colour. A smudge is a way of moving paint that is
   already there; a brush that deposits its carried colour onto bare canvas is not smudging, it is
   painting, and it does it in a colour the person never chose for that spot. This also removes the
   worst artefact: a soft brush's antialiased rim has `canvasA` near 0 everywhere it has not been,
   so without this rule a single soft smear leaves a wide ghost of the carried colour around itself.
   (Provisional, orchestrator's rule — Photoshop's smudge does deposit a little here. It is one line
   to reverse and I have flagged it rather than buried it.)

5. **A push displaces ALONG the stroke and never across it, by a fraction of the tip's radius.**
   `offsetFor` returns `(cos(dab.angle), sin(dab.angle)) × min(amount, MAX_SHIFT) × dab.radius`, and
   the same direction scaled by `amount` again as the flow, so a push that moves far also moves
   *strongly*. `MAX_SHIFT = 0.5f`: a dab may not displace by more than half its own radius in one
   dab, because a larger one tears the image — it has to sample outside the pixel it is writing.
   `amount ≤ 0` moves nothing. The direction is `dab.angle`, which for `followDirection` brushes is
   the stroke direction and for a round brush is 0 — so **a push only pushes along a brush that
   turns with the stroke**, and the Push preset (JB-1.07) is written with
   `"followDirection": true`. That is a real constraint on the preset, not an accident.

6. **A smudge or push stroke cannot be batched, and that is the whole of the engine cost.** A stamp
   dab is `dst` → nothing; a smudge dab must *read* `dst` and *write* `dst`, so dab *n* needs dab
   *n−1*'s result, and `GlPaintEngine.addDabs`'s one instanced draw per tile with fixed-function
   blending cannot express it. Three ways out, and I am choosing the boring one:
   - **`EXT_shader_framebuffer_fetch`** (R7 [S]: present on Adreno 5xx/6xx and Mali Bifrost
     gen-2+; the Note 9's Adreno 630 qualifies) reads the destination in the same pass, with
     coherent ordering per pixel. It must be **feature-detected** at `init` and never assumed.
   - **Ping-pong**: two textures per tile, `read A, write B, swap`. Works everywhere, costs a second
     texture per active tile and a copy.
   - **One dab at a time** with an FBO read-back per dab. Correct, and slow enough that it is not
     shippable.
   Decision: **detect framebuffer fetch; if it is absent, ping-pong.** The smudge pass is a separate
   shader (`SmudgePass.kt`) and a separate draw, so the stamp path — which is the hot path for every
   other brush — is not slowed, reordered or made conditional by any of this. A `T1` note, not a
   `T2` one: a smudge stroke is a handful of draws per tile and a person drawing with a smudge
   accepts fewer frames per second than with an airbrush.

7. **Smudge and push are PAINT-LAYER brushes and nothing else** (R20, verbatim: *"`smudge` and
   `wet` read the pixels underneath, so they are paint-layer brushes"*). They read the canvas, so on
   an INK layer there is nothing underneath to read — the layer is a recording, and re-rendering it
   at another zoom would smear differently every time. Picking one while ink strokes are selected is
   **refused in words**: *"Smudge reads the paint under it, and an ink layer has none."* Where that
   check lives is Q2, because R20's whole rule is currently implemented nowhere in `core` and this
   row is the first thing that needs it.

8. **The two words are new, so `BRUSH_VERSION` becomes 3 — in the same edit, all of it.** LEAD_RULINGS
   R3: adding a constant to a serialised file's vocabulary requires bumping the version, and
   JB-1.08a's Q2 records exactly what happens if the bump is done halfway — a *second* constant is
   created, `EnumFreezeTest` pins the wrong one, and nothing catches it until the next file format
   question. So the single edit is: `BrushJson.BRUSH_VERSION = 3`; `BrushPreset.version`'s default
   moves with it; `EnumFreezeTest` pins 3; `BrushValidate.ENGINES` gains both words; and
   `BrushJson.wordsNeedingVersion` gains both so a version-2 file using either is refused with the
   sentence `engine "smudge" needs brush version 3`. **No second constant, ever.** The exact
   precedent and the exact failure are in `JB-1.08a_fill_pen.md`; read Decision 1 of that spec
   before editing.

9. **The maths is in `core` and the canvas read is in `androidkit`, and the split is not
   tidiness.** `Smudge` and `Push` are arithmetic over four numbers and a `Dab`, and they are what
   the tests can pin. The read of the canvas is a GPU concern that cannot be tested in the cloud at
   all. Keeping them apart means the patent rule — Decision 1, 3, 4 — is verifiable on any machine
   and forever, and the part that cannot be verified is small and obvious.

## Tests

`SmudgeTest.kt` and `PushTest.kt`, `:core:jvmTest`. **Each of Decisions 1–5 and 8 has a case, and
the patent rule is turned into convergence and monotonicity properties rather than a comment.**

1. **THE PATENT RULE, as two convergence properties (Decision 1).**
   - `load = 0`, `pickup = 1`, a constant canvas colour, 200 dabs: the carried colour converges to
     **the canvas colour**, monotonically, and never overshoots it. The forbidden
     "deposit only picked-up paint when there is enough" behaviour is a **discontinuity**, and a
     monotonic-convergence test is what catches one — a reservoir version of this class would fail
     this test and pass every other test on this page.
   - `pickup = 0`, `load = 1`, 200 dabs: the carried colour converges to the **load colour**,
     monotonically, and `strength` never changes it.
2. **One store, one step.** A dab with a non-zero coverage on a constant canvas, run 500 times,
   moves the carried colour by **exactly** `lerp(lerp(c, canvas, pickup), load, load) − c` on the
   first dab and by the same expression thereafter — i.e. there is no second update, no threshold,
   no "enough paint yet" branch. Written as a loop asserting the per-dab delta against the closed
   form, so an implementation that accumulates differently fails on dab 2, not on dab 500.
3. **The carried colour never leaves `0..1`** for any canvas colour in `0..1` and any rate in
   `0..1` — swept, with `assertTrue(v in 0f..1f)`. And it is a pure function: the input
   `SmudgeCarried` is unchanged by `afterDab` (assert `==` on the original afterwards, which is a
   data-class comparison and so catches a hidden `var`).
4. **Empty canvas (Decision 4).** `canvasA = 0` → the output equals the canvas exactly, **and the
   carried colour is unchanged** (assert the returned instance is equal to the input one). Then a
   full sequence: a smudge drawn over a transparent region writes nothing at all, and the carried
   colour it carries into the painted part afterwards is what it was before the transparent part.
5. **A smudge is a smear, not a paint.** With `canvasA = 1` and `canvas = black`, a dab at
   `coverage = 1` and `strength = 0.5` from a carried colour of white writes a colour that is
   **exactly halfway** between black and white; at `coverage = 0.25` it is a quarter of the way. The
   two cases pin that `coverage` scales the *mix*, not the *output opacity*, which is the difference
   between a soft edge and a soft-looking hard edge.
6. **Determinism (Decision 3).** The same canvas sequence and the same rates run twice give
   bit-identical carried colours and bit-identical dab outputs; and a third run with a *different*
   canvas sequence does not — so the test is not passing because the maths ignores the canvas.
7. **The reflection is symmetric.** A dab over colour A then the same dab over colour B, and then
   over A again, returns the carried colour to where the first A left it, within 1e-6, when
   `pickup = 1` and `load = 0`. Running a brush back over its own path undoes its smear, which is
   the property a person notices immediately when it is missing.
8. **Non-finite and out-of-range rates are REFUSED in words, not clamped.** `strength = NaN`,
   `pickup = +Inf`, `load = -1f` and a `loadR = NaN` each throw `IllegalArgumentException` whose
   message names the field and the value, at construction. `strength > 1` is refused too — the file
   validator owns ranges for files, and this object is reachable from a preset that never went
   through it. (A `NaN` here would poison the whole stroke the way `jb_grainLevel` did — the
   precedent is Decision 1 of JB-1.05c.)
9. **Push geometry (Decision 5).** `angle = 0`, `radius = 10`, `amount = 0.2` → offset `(2, 0)`
   (exactly, and the test writes `0.2 × 10 = 2` in); `angle = π/2` → `(0, 2)`; `amount = 0` →
   `(0, 0)` and flow `0`; `amount = 10` → the offset is **exactly** `0.5 × 10 = 5`, the
   `MAX_SHIFT` cap, not 100. The offset's dot product with the across-stroke direction
   `(−sin, cos)` is `0` within 1e-6 for every angle — a push never moves anything across itself.
10. **Push does not allocate twice.** `offsetFor` writes into a caller-supplied `FloatArray` and a
    three-float result; a test allocates one array and calls it 1000 times, and the identity of the
    array afterwards is the same one (asserted by keeping a reference). Same rule as `GrainMath`'s
    UV functions in JB-1.05c: a hot path does not allocate.
11. **Both `lerp`s are premultiplied-safe.** Sweep canvas colours including
    `(0,0,0,0)`, `(1,1,1,1)` and `(1,0,0,0.5)` against carried colours including `(0,0,0,0)` and
    `(0.2,0.4,0.6,1)`: every result is in `0..1` on all four channels and never NaN. A colour with
    alpha 0 and non-zero RGB is exactly the case an unpremultiplied lerp gets wrong, and it is in
    the sweep on purpose.
12. **Version (Decision 8), once the format lands.** `BRUSH_VERSION == 3`; `BrushPreset.version`'s
    default is `BRUSH_VERSION` (asserted by equality, as `EnumFreezeTest` already does);
    `wordsNeedingVersion` names `engine "smudge"` and `engine "push"`; a **version-2** file using
    either is refused with the exact sentence `engine "smudge" needs brush version 3`; a version-3
    file with neither still encodes as version 3; and a preset using only `fill` still encodes as
    **version 2** (the `versionFor` rule, so a fill pen stays readable by a build that predates
    smudge). That last assertion is the one that is easy to lose and impossible to notice.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

### The device check (T3)

Smudge a coloured passage sideways and the colour moves and the canvas under it is untouched
elsewhere. Push a passage and it is displaced along the stroke, with nothing lost off the edge of
the tip. Both with a round brush and with a `followDirection` brush (Decision 5 — a round brush
without it does not push at all, and the owner will report that as a bug if the shipped Push preset
does not set it).

## Do not

- **Do not add a reservoir, a pickup store, a "load" queue, or any second place a colour is held.**
  Blueprint §5, and Test 1 fails if you do. `SmudgeCarried` is a class with one carried colour, not
  a struct with two slots.
- Do not use the `accumulate` / `flow` / `cap` machinery for a smudge or a push stroke. A smudge
  writes the canvas; it does not add to a stroke buffer. It is a separate pass, not a brush setting.
- Do not touch `Nudge` (JB-2.16a) or `SizeOpacityDrag`. The selection nudge is built, tested and
  referred to by another row.
- Do not make the stamp path conditional on the smudge pass. `GlPaintEngine.addDabs` is the hot path
  for every other brush in the app (Decision 6).
- Do not assume `EXT_shader_framebuffer_fetch` exists. Feature-detect at `init`, as
  `GlPaintEngine` already does for half-float render targets, and fall back to ping-pong.
- Do not widen this spec to iOS, to wet paint (Phase 6), or to liquify (D.03 / JB-2.05c). R17 says
  liquify comes free *from* this row later; it is not this row.
- No new constant without the version bump in the same edit (R3, Decision 8).

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green (paste the output).
- [ ] `git status --short` shows only owner-area files.
- [ ] Committed as `JB-1.06: smudge and push`, pushed.
- [ ] On the phone: smudge and push both draw, and a push with the round default brush correctly
      does **not** push (Decision 5's note, so the owner is not sent looking for a fault).

## Questions

_(Spec writer, 2026-09-29. `⚪ Outline` row: "JB-1.06 Smudge & nudge (ONE carried colour per brush —
patent rule, blueprint §5) | T1 | 1.05". The maths is finished; the file format is not mine.)_

**Q1 — what does a smudge brush's `brush.json` look like? This is a file format and it is yours.**
`BrushPreset` has `engine: "smudge"` as an accepted word and **nothing at all behind it** — there is
no `SmudgeSpec`, no `strength`, no `pickup`, no `load`, so the Smudge and Nudge presets of JB-1.07
cannot be written as files today. My proposal, so you can rule on a shape rather than invent one:

```json
"engine": "smudge",
"smudge": { "strength": 0.7, "pickup": 0.5, "load": 0.15 }
```
with all three plain `Float`s in `0..1`, plus a `SmudgeSpec` on `BrushPreset` and
`PushSpec { amount: Float }` (or one shared `SmudgeSpec` with `amount` for push — **one spec or
two** is itself a small decision, and one is simpler). That is a new field and a new word, so
**R3 applies and `BRUSH_VERSION` becomes 3** (Decision 8). The alternative — three `Param`s so
strength can follow pressure, which is what a person might want — is more expressive and costs the
same version bump, but it puts a curve evaluation in the middle of the canvas read loop.
**I have not written either, because inventing a file format is the one thing this project does not
let a spec writer do.** A ruling of ten words is enough to unblock this row and JB-1.07 together.

**Q2 — R20's layer rule still has no home, and this is the row that needs it.** R20: *"only `stamp`
and `fill` engines are allowed on ink layers: `smudge` and `wet` read the pixels underneath, so they
are paint-layer brushes (picking one while ink strokes are selected is refused in words)."* It was
referred once already, from JB-1.08a Q6, with the same finding: **there is no engine ↔ `LayerKind`
check anywhere in `core`**, and `FillPen` is deliberately layer-agnostic. This row is the first to
need it, so it has to be placed now. The candidates: a function in `core` (e.g. on `DocOps`, next
to `DocOps.validate`, which is already the place a screen asks "can I do this to this document?");
a method on `BrushPreset`; or the brush-pick UI, which is JB-2.01's. **I would put it in `core`**
— a refusal that only exists in a picker is a refusal the keyboard, a stroke re-brush (JB-5.03a)
and a file can all walk past — but it is a place in the architecture, and R20 is yours.

**Q3 — two nudges, one name. Is that what you want?** The board row says "Smudge & nudge" and
R17 says "JB-1.06 nudge covers push", so this row's nudge is **pixel push** (Decisions 5, 6).
Meanwhile `Nudge.stepDoc` (JB-2.16a, `🟩 Reviewed`) is a **selection** nudge — "a nudge moves the
same distance on screen; zoom in for fine work", which is the owner's own sentence in
blueprint §2 and `OWNER_CONSTRAINTS` — and it is a different operation with different undo
semantics, a different layer interaction, and no brush at all. I have kept them apart and touched
neither, which means a person will meet two things called "nudge". Options: (a) keep both, and the
Push brush is named **"Push"** in the brush list while the tool-finger nudge stays "nudge" (my
proposal, and it is why the Decision 5 device check says a push with a round brush does nothing);
(b) rename the pixel one "smear" or "drag" and leave "nudge" to the selection; (c) make the pixel
one a *tool* rather than a brush, which contradicts blueprint §1 idea 7's list. I did not rename
anything, because the name a person sees is a product decision and the board row's own parenthetical
("ONE carried colour per brush") is about smudge, not about which nudge it meant.
