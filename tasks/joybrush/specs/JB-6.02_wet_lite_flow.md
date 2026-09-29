# JB-6.02 — Level 1: wet-lite flow, in the wet area only, paused while the pen is down

| | |
|---|---|
| **Tier** | T1 (it decides what a "wet" layer is, what undo means while paint is still moving, and it adds a per-frame scheduler to a reviewed engine) |
| **Status** | 🟨 Draft — **only the Lead or a cross-reviewer may set this to `🟦 Ready`** (ROADMAP §3). Q1 is not a small question: it decides whether a settled wash is a function of the recording, and the three answers cost different amounts of work |
| **Needs** | JB-6.01 (the look on commit, and its nine-tap neighbour read) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/WetFlow.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paint/WetFlowTest.kt` · NEW `joybrush/shaders/jb_wet_flow.frag` · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (the wet set, the step, `flowTiles`, `fastDry`, `draw`) · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (`onDrawFrame` steps, `snapshot` fast-dries) |
| **Estimated size** | ~300 lines of Kotlin (core) + ~120 lines of GLSL + ~200 lines of Kotlin (engine) + ~400 lines of tests |

> **Read this before the contract.** "Wet-lite" is a name for a *deliberately small* thing, and the
> temptation on this row is to build a watercolour simulation. Here is what it is not, stated up
> front so nobody discovers it in week three:
>
> - It is **not** a fluid solver. No velocity field, no shallow water, no depth, no gravity, no
>   tilt-driven runoff, no pigment/water split, no paper capacity. Blueprint §5 forbids lattice
>   Boltzmann (US 8,335,675) and polygon growth (8,917,282/283), and R1's own cost estimate (§5.8) is
>   that a full simulation costs 1.7 ms/step at 512² and **26 ms/step at 2048²** — the Note 9's floor.
> - It is **a bounded, conservative diffusion of premultiplied colour among the tiles of one wet
>   episode**, gated on a per-tile wetness number, at full document resolution (half-res would need
>   a resample the tile format does not have).
> - It therefore **cannot** do backruns, real blooms into a previous stroke, edge feathering from
>   water, or anything you would call "wet" as a noun. It does one thing — a wash **settles** over
>   about a second instead of stopping dead — and on a Note 9 that is most of the perceived
>   difference between a wash and an airbrush. **Level 2 (the full solver, "later" in blueprint §4)
>   is a different row on different hardware, and nothing here is designed to prevent it.**
>
> The one thing this row must get right, and the reason it is T1: **the two interactions with the
> rest of the app are `undo` and `save`, and both of them already have rules** (R11's autosave
> refuses to snapshot mid-stroke; the undo log owns whole tiles). Paint that is still moving is
> paint that is in neither a layer tile nor a recording, and Decisions 5, 6 and 7 are where that
> collision is settled.

## Goal

Blueprint §4 Phase 6, Level 1: *"'wet-lite' flow only in the wet area, paused while the pen is down
(Rebelle's trick)."* In one paragraph: a wash that has just been laid down **keeps moving for about
a second after the pen lifts and then stops**, the way paint on wet paper does, instead of snapping
to a hard edge the instant the pen does. It moves **only where the paper is wet** — a dry wash
beside it never stirs — and it **stops dead while the pen is down**, because a simulation that
competes with the pen for the GPU is a drawing app that feels like it is thinking.

Rebelle's rule is the whole design and it is quoted from R1 §1.2: *"the team paused the fluid
simulation during brush strokes and used the full CPU for painting."* The flag that says "the pen is
down" **already exists** — `GlPaintEngine.strokeInProgress`, added for R11's autosave — so the
trick costs one line and reuses a fact the engine is already keeping.

## Contract (verbatim)

### What already exists and is NOT changed

```kotlin
// core/paint/Tiles.kt — a tile key packs (tx, ty) into ONE Long, and that is why tile arithmetic
// can be done in Long at all (R19). NOTE: key + 1 is NOT the neighbour in x; see Decision 4.
object Tiles {
    const val SIZE = 256
    fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFF_FFFFL)
    fun tx(key: Long): Int = (key shr 32).toInt()
    fun ty(key: Long): Int = key.toInt()
}

// core/paint/UndoLog.kt
class UndoLog<T : Any>(budgetBytes: Long, sizeOf: (T) -> Long, release: (T) -> Unit) {
    fun push(step: Step<T>)     // discards the redo stack
    fun undo(): Step<T>?
    fun redo(): Step<T>?
    fun clear()
    val canUndo: Boolean; val canRedo: Boolean; val undoDepth: Int; val heldBytes: Long
}

// androidkit/.../gl/GlPaintEngine.kt — three members this spec adds to, and one it does not touch
val strokeInProgress: Boolean get() = strokeLayer != null      // ALREADY EXISTS (R11)
fun replaceTiles(layerId: String, tiles: Map<Long, ByteArray?>): Int   // already: refuses during a stroke
fun endStroke(): Int
fun snapshotThrough(...)
```

### What is NEW in `core/paint/WetFlow.kt` — the maths, and it is testable on a computer

```kotlin
package cc.joycreator.joybrush.core.paint

/**
 * Level-1 "wet-lite": a bounded, conservative diffusion of premultiplied colour among the tiles of
 * one wet episode (JB-6.02). The CPU twin of joybrush/shaders/jb_wet_flow.frag.
 *
 * It is NOT a fluid solver and does not pretend to be one. See the class note's list of what it
 * cannot do. What it does: paint that is still wet spreads a little into paint that is still wet,
 * conserving exactly, and stops after a fixed number of steps.
 */
object WetFlow {

    /** Tiles a wet episode may hold at once. More than this is REFUSED in words, never truncated. */
    const val MAX_WET_TILES = 4096

    /** Below this a tile's wetness is zero and the tile has left the wet set. */
    const val WET_EPS = 1e-3f

    /**
     * How many flow steps run in one frame. The value is TUNING (it sets how long a wash takes to
     * settle, in seconds: DRY_STEPS / STEPS_PER_FRAME) and is a CONSTANT rather than a field on
     * purpose — see Decision 3, which is the determinism rule and the reason the two numbers
     * multiply out to a whole episode.
     */
    const val STEPS_PER_FRAME = 3

    /** How long a wet episode lasts, in steps. A CONSTANT, never a frame count. */
    const val DRY_STEPS = 90

    /** The share of a pair's difference that moves per step. TUNING. Must be 0 <= rate <= 0.5. */
    const val RATE = 0.18f

    /**
     * Rebelle's rule, as a pure function so it can be pinned: WHILE THE PEN IS DOWN, ZERO steps.
     * (R1 §5.7: "While the pen is down, run 0-1 steps per frame" — and zero, not one, because a
     * single step is a millisecond of GPU time competing with the dab pass for the frame the
     * person is drawing in.)
     *
     * Returns 0 or [stepsPerFrame], never anything in between. A caller that wants a partial frame
     * does not have it, and that is the point: a result that depends on frame timing is not
     * replayable.
     */
    fun stepsThisFrame(strokeInProgress: Boolean, stepsPerFrame: Int = STEPS_PER_FRAME): Int

    /**
     * ONE step, over the tiles of one wet episode.
     *
     * [tiles] is the layer's tiles, key -> 256*256*4 PREMULTIPLIED RGBA FLOATS (the form
     * RefCanvas holds; the engine holds the same numbers as RGBA8 and converts per tap).
     * [wet] is the set of keys that are still wet. Anything not in [wet] is neither read as a
     * destination nor moved into: that is "flow only in the wet area", and it is why a dry wash
     * two tiles away is untouched.
     *
     * [rate] must be in 0..0.5 and finite; anything else is REFUSED with the number in the message.
     *
     * Returns ONLY the tiles that changed, key -> a NEW array (the input arrays are never
     * modified — the engine is copy-on-write and undo depends on it). Conserves the total
     * premultiplied RGBA over [wet] to the bit, and moves the coverage centroid outward.
     *
     * Allocates one array per CHANGED tile and nothing else, so a step over an idle document
     * allocates nothing at all.
     */
    fun oneStep(tiles: Map<Long, FloatArray>, wet: Set<Long>, rate: Float = RATE): Map<Long, FloatArray>

    /** The four keys one step reads: the tile's east, west, north and south. Renders four, not nine. */
    fun neighbours(key: Long): LongArray   // 4 longs, always east/west/north/south; the caller drops absences

    /** The decay, as a pure function: how wet a tile is after one more step. */
    fun decay(wetness: Float, stepsDone: Int, totalSteps: Int = DRY_STEPS): Float

    /**
     * Whether [key] is still wet. The comparison is STRICTLY GREATER THAN wetness > [WET_EPS], and
     * that boundary is pinned by a test: a tile whose wetness is exactly [WET_EPS] is DRY. Making
     * it >= would make the wet set's membership depend on a floating-point equality, which is the
     * kind of thing that is stable for a year and then is not.
     */
    fun isWet(wetness: Float): Boolean

    /**
     * The per-tile bookkeeping, which is the only state in the whole feature: which tiles are wet
     * and how many steps each has had. Sized by [MAX_WET_TILES]; asking to add one more is a
     * REFUSAL with the number in the message (Decision 8), not a silent drop — a dropped tile is
     * paint that stops moving for no visible reason.
     */
    class Session {
        /** A commit just wrote this tile, so it is wet again and its step count restarts. */
        fun enter(key: Long): Int          // throws IllegalStateException past MAX_WET_TILES

        fun exit(key: Long)
        fun isWet(key: Long): Boolean
        fun wetKeys(): Set<Long>
        fun stepCount(key: Long): Int
        val size: Int
        fun clear()
    }
}
```

### The new shader, `joybrush/shaders/jb_wet_flow.frag`

```glsl
// jb_wet_flow.frag — one Level-1 flow step over one layer tile (JB-6.02). SHARED by phone and lab.
// GLSL ES 3.00. NO #version, NO main, NO uniforms. Include from jb_wet.glsl's neighbour contract:
// the caller binds the centre on unit 0 and east/west/north/south on 1..4, absent ones as 1x1 zero.
// CPU twin: core/paint/WetFlow.kt. Any change to this maths is made there too, in the same commit.

// One step at one texel, for one neighbour. `self` and `neighbour` are PREMULTIPLIED RGBA.
// Moves `rate * max(0, neighbour.a - self.a)` of the neighbour's pigment INTO self, which means the
// caller must subtract exactly the same amount from the neighbour. Written as a function so the
// amount and the two writes cannot disagree: one call, one number.
vec4 jb_wetFlowTransfer(vec4 self, vec4 neighbour, float rate);

// The tile's new value after all four neighbours have given and taken. Sum of the four transfers
// applied to `self`, in east, west, north, south order. The order is part of the contract: the
// transfers are additive, so a different order is a different answer, and a CPU twin and a GPU
// must agree.
vec4 jb_wetFlowSum(vec4 self, vec4 east, vec4 west, vec4 north, vec4 south, float rate);
```

### The engine additions, `GlPaintEngine`

```kotlin
/**
 * The wet set: tiles whose paint is still moving. SPARSE and transient — it is never written to a
 * file, never a layer, and never survives a document close.
 */
private val wet = WetFlow.Session()

/** True while any tile is still wet. The frame loop's reason to keep rendering. */
val wetInProgress: Boolean get() = wet.size > 0

/**
 * Runs up to [WetFlow.STEPS_PER_FRAME] flow steps, or ZERO while a stroke is in progress
 * (Rebelle's rule, R1 §1.2). Returns how many ran. Called from the frame loop; see Decision 2.
 */
fun stepWet(): Int

/**
 * Writes tiles the FLOW changed, as part of the wet episode's EXISTING undo step: no new
 * UndoLog.Step is pushed. The undo snapshot for a wash is the state before the stroke, and
 * undoing it removes the stroke and the whole of its settling at once (Decision 5).
 *
 * Refuses while a stroke is in progress, for the same reason [replaceTiles] does.
 */
private fun flowTiles(layerId: String, changed: Map<Long, ByteArray?>)

/**
 * Ends every wet episode at once, writing nothing back — the pixels stay exactly as the last step
 * left them. Called before a snapshot (R11's autosave, Decision 6) and before a document is closed
 * or reset, and by [undoStep] (Decision 5). Returns how many tiles left.
 */
fun fastDry(): Int
```

## Decisions

1. **The maths is a pure function in `core`, and the engine is a transcription of it.** This is the
   whole architecture of the row and the reason any of it is testable in the cloud at all: `oneStep`
   is a function from `(tiles, wet keys, rate)` to `changed tiles`, with no clock, no GL, no
   platform type, and no state. It is `TipMath`/`jb_tip.glsl` and `GrainMath`/`jb_grain.glsl` again,
   and the same rule applies — **any change to the shader's maths is made in `WetFlow.kt` in the
   same commit.** Without this split, the only proof available would be a phone, and Phase 6's
   owner check is a *feel* judgement that a test cannot make anyway.

2. **The pause is `strokeInProgress ? 0 : STEPS_PER_FRAME`, on a flag that already exists.**
   `GlPaintEngine.strokeInProgress` is true between `beginStroke` and `endStroke`/`cancelStroke`,
   and it was added for R11 so an autosave would not snapshot a half-drawn stroke. It is the exact
   fact Level 1 needs, and it is the *whole* of the rule: while the pen is down, zero steps. Not
   "the flow is suspended near the pen" (which would need the pen's position in the shader, a
   coupling nobody can test) and not "one step per frame" (R1 allows 0–1; one step is a millisecond
   of GPU taken from the frame the person is drawing in, and the whole point of Rebelle's trick is
   that painting gets the machine to itself). `stepsThisFrame` is a two-line pure function and
   Test 3 is its table.

3. **A wet episode is a FIXED number of steps, and no rule in this file reads a clock.** This is the
   determinism decision, and it is what separates "a wash that settles" from "a wash that settles
   differently on a different phone at a different frame rate". Concretely:
   - `DRY_STEPS` steps, and a tile leaves the wet set when it has had all of them. The episode's
     **length in seconds is tuning** (`DRY_STEPS / STEPS_PER_FRAME` ≈ 1 s at the proposed defaults)
     and its **result is not**.
   - Nothing in `oneStep`, `decay` or `Session` takes a timestamp, reads a frame counter, or calls
     `uptimeMillis`. There is no elapsed-time damping.
   - **Consequence, and it is a real one:** a tile's wetness is `1 − stepsDone / DRY_STEPS`, so two
     strokes laid on the same tile in quick succession are affected by *how many steps happened
     between them* — which is frame history, not recording history. **The settled pixels of a
     wash are therefore not a pure function of its dabs.** Test 4 pins the half that *is* exact, and
   - that half is what gives the property worth having: **N steps in one go and N steps in batches
     produce identical bytes.** That is the property that makes a re-render, a time-lapse replay or
     a second device agree, and it is the one that would be impossible if the step count were
     derived from a clock. Q1 is about the half that is not exact.
   - The decay is *linear in steps* (`decay = 1 − stepsDone / DRY_STEPS`) and not a rate. A linear
     decay is chosen over an exponential one because an exponential never reaches `WET_EPS` and so
     would need a separate cutoff, and because a linear one makes "steps done" and "how wet" the
     same number, which is a property Test 4 can state rather than a curve a test has to sample.

4. **The four neighbours are `key ± (1L shl 32)` and `key ± 1L`, computed in Long, never in Int.**
   `Tiles.key(tx, ty) = (tx.toLong() shl 32) or (ty and 0xFFFFFFFF)`, so:
   - **east is `key + 0x1_0000_0000L`**, west is `key − 0x1_0000_0000L`;
   - **north is `key + 1L`**, south is `key − 1L`;
   - and **`key + 1L` is NOT east.** It is north. A builder who adds 1 for "the next tile" produces
     a flow that walks up the canvas and is otherwise perfectly plausible on a horizontal stroke.
     Test 1 pins all four by name.
   This is R19's rule in its tile form: the arithmetic is done in the `Long` the key already is,
   because the canvas has no edge and `tx ± 1` must wrap correctly at `Int.MIN_VALUE`/`MAX_VALUE`,
   where `Int` arithmetic would silently produce the wrong tile rather than fail. `Tiles.key` is
   used to build every key, never bit-shift literals written by hand elsewhere.

5. **One undo step per wet episode: the flow never pushes one, and undo fast-dries.**
   The naive reading — "the flow edits layer pixels, so each step is an edit" — produces sixty undo
   steps a second, which is unusable and would blow the 192 MB budget in seconds. What is right, and
   it is free:
   - `endStroke` already pushed one `UndoLog.Step` whose `before` is the pre-stroke tile. That step
     covers the **whole episode**, settling included. Undoing a wash removes the wash and every
     motion it made afterwards, in one press, which is what a person means by undoing a wash.
   - The flow therefore writes through a path that **does not push**: `flowTiles` is
     `replaceTiles` with the `undo.push` line removed, and it keeps `replaceTiles`'s existing
     refusal while a stroke is in progress.
   - `undoStep()` calls `fastDry()` **first**. A wet tile's step count is not in the undo step, so
     leaving the wet set alive across an undo means the next stroke flows into paint that is no
     longer on the canvas. Fast-drying discards the remaining motion and leaves the layer exactly
     what the undo step says, which is the honest reading of "undo".
   - `redoStep()` does **not** re-enter the wet set. A redone wash appears settled. That is stated,
     tested, and is the one asymmetry, because re-entering would mean the redo also had to replay
     `DRY_STEPS` steps to reach the state the redo is supposed to restore.

6. **A snapshot fast-dries first (R11, carried onto the wet state).** R11's rule is already *"an
   autosave that finds a stroke in progress sets a save owed flag and saves right after the stroke
   ends"*. The wet set is the same hazard one step later in the stroke's life: at snapshot time a
   settled-but-still-moving tile holds paint that is in **neither** a layer tile **nor** a
   recording, so an archive taken then loses it silently — which is the one thing this project
   treats as the worst outcome (blueprint §3.5, *"Autosave that never loses work — the #1
   complaint across 34,000 reviews of competitors"*). So: `JbCanvasView.snapshot` calls
   `engine.fastDry()` on the GL thread before reading any tile, and **the snapshot then contains
   everything**. The cost is that saving interrupts a settling wash where it stands; the alternative
   is a save that quietly omits paint, and there is no version of that worth having. Test 8 pins
   that a snapshot with a live wet set leaves the set empty and the layer holding the settled paint.
   **The "save owed" flag is NOT extended to the wet set** — a snapshot is cheap, fast-drying is
   immediate, and deferring a save behind a settling animation would be R11's rule applied where it
   does not belong.

7. **The flow reads the LAYER's own pixels, so the layer is the truth at every instant and the
   preview is right with no extra work.** There is no separate wet texture and no suspended-pigment
   buffer (R1 §6.1 has both; they are what makes re-wetting lossless and pigment-true, and both are
   blueprint §4's *"Later"* row, not this one). The layer holds the paint, the wet set says which
   tiles of it may still move, and `draw()` needs to know nothing: a moving wash is already in the
   tiles it is drawn from. **What this costs, stated plainly: a wash cannot be re-wetted, lifted or
   rewound, because there is no pigment/water split to lift from.** It is the cheapest design that
   is honest, and it is the one the Note 9 can afford.

8. **The wet-tile budget is a REFUSAL with a number in the message, not a silent cap.** More than
   `MAX_WET_TILES` wet tiles is `IllegalStateException("wet set is 4097 tiles, the most is 4096:
   fast-dry the document before wetting this much")`. The house idiom is refusing bad input in
   words and never clamping silently, and this is the case where it bites hardest: a dropped tile
   is paint that mysteriously stops moving, which is a bug a person reports and a developer cannot
   reproduce. The number is a `const val` in `WetFlow`, imported by whoever needs it, and **it is
   not a second copy of any other limit in this project** (R19).
   `4096` tiles is 4096 × 256² px ≈ 268 Mpx of settling paint, which is far more than a person can
   wet in one sitting and is therefore a guard against a runaway, not a design target. **The number
   is Tuning (Q2).**

9. **The neighbour binding is 5 textures, not 9, and it is ONE implementation shared with
   JB-6.01.** Level 0 needs the 3×3 ring (a blur is symmetric); Level 1 needs only the four
   cardinal neighbours (a flux is directed, and a diagonal flow would not conserve with a
   four-neighbour stencil). Writing a second binding helper for four where 6.01 already has nine is
   how a shader ends up with two different notions of "the neighbour tile". So: one private helper,
   one call site per pass, and a note in it saying which of the two shapes each caller needs. Both
   passes read the layer tile, which is a single RGBA8 texture per tile and is not a new allocation —
   it is the tile the flow is writing.

10. **The flow is a PASS, so it is not free, and its cost is bounded by the wet set rather than by
    the screen.** A step draws the wet tiles, each needing its own texture and its four neighbours
    bound, so the cost is `wet.size × 5` texture fetches' worth of work per step and
    `wet.size × STEPS_PER_FRAME` tile-sized draws per frame. The engine **draws only the wet tiles**,
    never the viewport, and the frame loop only asks for a frame at all when `wetInProgress` is true
    (so a dry document costs exactly what it costs today — the frame is only requested on input
    anyway, `RENDERMODE_WHEN_DIRTY`). **The hard cap on how many steps run per frame is
    `STEPS_PER_FRAME` and it is never exceeded even if a frame is late**, which is the R1 §5.7
    "time-slice rather than drop" rule in its smallest form: a slow frame means the wash settles in
    fewer frames, never in a different shape.

11. **Everything is document px, and the wet set is document-tiled (R10).** There is nothing
    screen-scaled in this spec — a wash settling at 4× zoom settles by the same steps over the same
    tiles, so it looks the same at every zoom, which is R10's actual requirement. `MAX_WET_TILES` is
    a count of document tiles and says so in its KDoc.

12. **A `stamp` brush's stroke never enters the wet set, and a wet brush's flow is a no-op while
    the pen is down — both are Decisions 2 and 12 of JB-6.01 and are not repeated.** The one
    addition here: a stroke committed with `blend == ERASE` also never enters the wet set (there is
    nothing to settle), and a stroke with `engine != "wet"` never enters it (that is the entire
    meaning of "wet"). A dry document is bit-identical to today's after this spec, and Test 9 is
    that test.

## Tuning — the owner's eye, NOT a contract

Same rule as JB-6.01: **no test asserts a number from this table.** The tests assert the rules the
numbers feed, so a tuning pass is a value change with a green suite.

| # | Parameter | Proposed default | What it looks like at that number | Where it lives |
|---|---|---|---|---|
| T1 | `STEPS_PER_FRAME` | **3** | `DRY_STEPS / STEPS_PER_FRAME` = **30 frames ≈ 0.5 s** at 60 Hz for a wash to settle. At 1 it is a 1.5 s drift that reads as a bug; at 10 it is over in 9 frames and the flow is barely visible. | `WetFlow.STEPS_PER_FRAME` |
| T2 | `DRY_STEPS` | **90** | The shape of the settling, not its duration. Bigger is a longer, gentler creep. | `WetFlow.DRY_STEPS` |
| T3 | `RATE` | **0.18** | Each step moves 18 % of the difference between a wet pixel and its wet neighbour. **The ceiling is 0.5 and it is a real one**: a symmetric exchange above 0.5 overshoots and oscillates, so `rate > 0.5` is refused rather than clamped. At 0.4 a wash visibly runs; at 0.05 it barely moves. | `WetFlow.RATE` |
| T4 | `MAX_WET_TILES` | **4096** | ≈ 268 Mpx of settling paint. A guard against a runaway, not a target. | `WetFlow.MAX_WET_TILES` |
| T5 | `WET_EPS` | **1e-3** | Where a tile counts as dry. Below the stroke buffer's own noise floor (R8, or R16F), so it never decides anything by itself. | `WetFlow.WET_EPS` |

**Q2 (below) asks whether these are T1's or T3's.** My recommendation is on the record, and for
T1–T3 it is the same as for 6.01's.

## Tests

`WetFlowTest.kt` (NEW, `:core:jvmTest`). **Every numbered Decision has at least one case. No test
asserts a number from the Tuning table.**

1. **The four neighbours, by name, and `key + 1` is north** (Decision 4). For a set of tile keys
   including `(0,0)`, `(1,0)`, `(0,1)`, `(-1,0)` and `Int.MAX_VALUE` in `tx` and `ty`:
   `neighbours(key)` returns exactly `{east, west, north, south}` as computed by `Tiles.key` with
   `tx ± 1` and `ty ± 1`. Then the load-bearing pair: for `key(5, 7)`, `key + 1L` is `key(5, 8)`
   (**north**) and `key + 0x1_0000_0000L` is `key(6, 7)` (**east**) — asserted by identity, so a
   builder who transposed the two gets a red test naming them. `Int.MAX_VALUE` in `tx` and `ty`:
   the neighbour is `key(Int.MAX_VALUE + 1, 0)`, computed without wrapping to a negative tile, and
   the test states that the `Long` arithmetic is what makes it so (R19).

2. **The tile budget is a refusal with numbers in it** (Decision 8). Filling a `Session` to
   `MAX_WET_TILES` is fine; the next `enter` throws `IllegalStateException` whose message contains
   both the count it reached and `MAX_WET_TILES` **by value** (the test reads the constant, never a
   literal). And the session still holds exactly `MAX_WET_TILES` keys afterwards — the refused tile
   was not silently added.

3. **The pause is a two-valued function of one boolean** (Decisions 2, 10).
   `stepsThisFrame(strokeInProgress = true)` is `0` — for `stepsPerFrame` in `0, 1, 3, 60`; and
   `stepsThisFrame(false)` is `stepsPerFrame` exactly. Assert the result is never a partial frame,
   and that a negative `stepsPerFrame` is refused with the number in the message.

4. **The episode is exactly `DRY_STEPS` steps and the result does not care how they were batched**
   (Decision 3). Two fixtures: a 2×2 block of 64×64 tiles with a step-count gradient (a plausible
   "wet blob"), and a 1×1 tile. For each, run all `DRY_STEPS` in one call and in
   `ceil(DRY_STEPS/7)` batches, and assert the final tiles are **`==` array for array**. Then:
   `decay(w, DRY_STEPS) <= WET_EPS` and `isWet(decay(w, DRY_STEPS)) == false`, while
   `isWet(decay(w, DRY_STEPS - 1))` is true — the episode ends at a stated step and not before.
   And the strictness: `isWet(WET_EPS)` is **false**, `isWet(nextUp(WET_EPS))` is true.

5. **The flow CONSERVES, exactly** (Decisions 1, 7). A fixture with a strong left-to-right
   gradient across four wet tiles: after one step, the sum of premultiplied RGBA over the wet set
   equals the sum before **to within 1e-6 relative per channel** (the tolerance is written into the
   test with the reason: float32 accumulation over 4 × 64² × 4 values). And the **coverage centroid
   moves strictly along the gradient**. Then the same assertion with a *circular* fixture — total
   conserved, centroid unchanged within 1e-4 — which is the case a directional bug cannot fake.

6. **A dry tile is not a destination and is not read** (Decisions 1, 12). A wet tile with a dry
   neighbour of known colour: after one step the wet tile is unchanged (`==`), the dry tile is
   absent from the returned map, and the dry tile's own array in the input map is **the same
   instance** it was before. Two wet tiles with a dry tile between them: neither moves toward the
   other, because the flow is one tile per step — asserted, because "only in the wet area" is
   ambiguous between "only wet pixels move" and "only wet tiles are involved" and this pins the
   second, stronger reading.

7. **The input is never modified, and a no-op step allocates nothing** (blueprint §3.1, and the
   engine's copy-on-write contract that undo depends on). Hold references to every input array, run
   a step on a uniform fixture where nothing should move, assert every returned value is
   `assertSame` to its input — and that the returned **map is empty**. Then a second test that runs
   10 000 no-op steps and asserts the map is empty every time.

8. **A snapshot fast-dries** (Decision 6, R11). The engine-level assertion is an `:androidkit` test
   (`fastDryWhileWetLeavesThePixelsWhereTheLastStepLeftThemAndEmptiesTheSet`): plant a wet episode,
   assert `wetInProgress`, `fastDry()`, assert `!wetInProgress` and that the tiles are byte-equal to
   the pre-`fastDry` state — **fast dry is a stop, not a settle**, and a test that let it settle
   would be asserting a different feature. This is the R11 rule carried onto the wet state and it is
   the one that stops a save silently dropping paint.

9. **A dry document is bit-identical to today's, and the whole feature is off for a non-wet brush**
    (Decision 12, JB-6.01 Decision 2). Drive the **shipped** `joybrush/brushes/ink/brush.json`
    through `BrushDabber` → `DabPlacer` → `RefCanvas` as `PaintTest` does, commit, and assert
    `wet.size == 0` and that calling `stepWet()` a hundred times changed nothing. Same for a
    `blend: "erase"` stroke on a wet engine word.

10. **`rate` is refused, not clamped, and above 0.5 is a real refusal** (Decision 3's stability
    argument, T3). `NaN`, `+Inf`, `−Inf`, `-0.01`, `0.51`, `2.0` each throw with the value in the
    message; `0.0` is legal and makes `oneStep` return an empty map; `0.5` is legal. The test
    states *why* 0.5 is the boundary (a symmetric exchange above half overshoots) so the constant
    cannot be raised without someone reading the reason.

11. **Nothing in the core reads a clock** (Decision 3). A sweep that runs the whole episode with
    every one of `System.currentTimeMillis`, `System.nanoTime` and any other time source made to
    return a different value on each call, plus `WetFlow` and `Session` and their test fixture
    instrumented the same way, still produces **bit-identical** tiles to a run with the clock
    frozen. If this is awkward to write, the honest cheaper version is: assert by reading that
    `WetFlow.kt` and `Session`'s signatures contain no time parameter and no `System.` /
    `uptime` / `nanoTime` token, and fail with the file and line if one appears. **Write the
    behavioural one if you can; the textual one is a floor, not a substitute.**

12. **The scheduler's cap is never exceeded** (Decision 10). A `stepWet()` that is asked to run with
    an artificially small per-frame budget returns at most that many steps and leaves the episode
    in a **legal intermediate state** — `Session.size` unchanged, every surviving tile's step count
    advanced by exactly the number of steps that ran, and the total across the episode still equal
    to the number of steps that actually ran. A wash that settles in fewer frames looks the same as
    one that settles in more, and that is the assertion.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures, and the count
is not one lower than before the change.

## Do not

- Do not build a fluid solver. No velocity, no depth, no gravity, no water/pigment split, no paper
  capacity, no half-resolution grid, no LBM, no polygons. Blueprint §5 names the patents those
  walk into and the class note names the limits; Level 2 is a different row on different hardware.
- **Do not push an `UndoLog.Step` per step.** One per episode (Decision 5). Sixty steps a second is
  not a feature and it blows the 192 MB budget in seconds.
- Do not leave the wet set alive across an `undoStep()` (Decision 5), and do not re-enter the wet
  set on `redoStep()`.
- Do not snapshot a document with a live wet set without fast-drying first (Decision 6). That is
  the R11 hazard, one step later, and it is silent.
- Do not read a clock or a frame count in `WetFlow.kt` or `Session` (Decision 3). A wash that
  settles differently at 60 Hz and 120 Hz is a wash that re-renders differently on another device.
- Do not compute a neighbour key as `key ± 1` for east/west, and never write a shifted literal by
  hand — use `Tiles.key(tx ± 1, ty)` or the `WetFlow` helper (Decision 4, R19).
- Do not let the flow touch a tile that is not in the wet set, and do not flow **into** one
  (Decision 6, Test 6).
- Do not put the wet state in a document, a layer, a `StrokeRecord`, a `Dab` or a file. It is
  transient and it dies with the document (Decision 7).
- Do not widen the wet-tile budget by clamping or by dropping a tile. Refuse in words (Decision 8).
- Do not write a second neighbour-binding helper. Reuse 6.01's (Decision 9).
- Do not re-type `256`, `2048`, `4096` (brush), or `MAX_WET_TILES`. Import them; R19 is about every
  shared number, not just the brush size.
- Do not assert a number from the Tuning table in a test.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green, output pasted, count quoted before and after.
- [ ] `git status --short` shows only owner-area files.
- [ ] Committed as `JB-6.02: wet-lite flow, paused while the pen is down`, pushed.
- [ ] ROADMAP's Phase 6 row for JB-6.02 updated (the Lead's file).
- [ ] On the phone: a wash visibly settles for about half a second after the pen lifts; **drawing
      feels exactly as fast as it did before this row** (that is the whole point of the pause, and
      it is the thing the owner will notice if it is wrong); a dry stroke beside a wet one does not
      move; undo takes a whole wash back in one press; and an autosave mid-settle does not lose
      paint.

## Questions

_(Spec writer, 2026-09-29. The `⚪ Outline` row: "JB-6.02 Level 1: wet-lite flow only in the wet
area, paused while the pen is down | T1 | 6.01". Decisions 1–12 are complete and buildable. Q1 is
the one question where the honest answer changes what gets built.)_

**Q1 — a settled wash is not a function of its recording, and that is a product decision.**
Everything the app promises about strokes is that they are recordings: re-render at another zoom,
replay a time-lapse, re-brush with a different brush, draw the same file on another device
(blueprint §2, and R13's rule that a stroke keeps the exact seed it was drawn with). Decision 3
buys frame-rate independence and step-batching independence by making the episode a fixed **90
steps** — and that means a tile's wetness when the *second* stroke lands on it depends on **how
many steps happened between the two strokes**, which is frame history. So:

- **Two strokes, one frame apart, settle differently from the same two strokes one second apart.**
  That is not a bug; it is what settling means. But it means the settled pixels cannot be recomputed
  from the `StrokeRecord` alone.
- What **is** exact, and is pinned by Test 4: the same N steps produce the same bytes however they
  are batched, so a re-render that replays the same *step history* is bit-identical, and a second
  device running the same episode from scratch is bit-identical.

Three ways forward, and they cost different amounts:

- **(a) Accept it (my recommendation).** The recording says what the pen did; the settling is a
  property of the session. Consequence for the rest of the project: re-brushing a wash (JB-5.03a)
  and ink-layer replay (JB-5.01) re-run the commit maths and get **the look**, not **the exact
  settled pixels**, and nothing in the file format has to change. Cost: two `StrokeRecord`s drawn
  seconds apart and re-brushed together will settle as if they were drawn together.
- **(b) Make it exact: record the step count per stroke.** Add one integer to `StrokeRecord` — the
  number of flow steps that had run on that tile when this stroke began — and the episode becomes a
  pure function of the file. **This is a `StrokeRecord` change and `stroke/` is R15's, and JB-5.03a
  is already `🟦 Ready` and owns stroke records v2.** So (b) is a coordination cost, not a
  difficulty, and it is the wrong row to pay it in. If you want (b), it wants its own small row
  after 5.03a lands.
- **(c) Defer the settling: dry explicitly.** The wet set only advances when something asks it to
  (a `Dry` action, or the autosave), never on a frame timer. Fully deterministic, no clock anywhere —
  but the wash stops moving when you stop touching the screen, which is the opposite of the effect
  and of the reason this row exists. I mention it only because it is the only option with no
  trade-off at all, and there is one.

**I have specified (a)** and written the tests so (b) is additive later (the step count would live
on the `Session`, not inside `oneStep`).

**Q2 — are the five tuning numbers T1's or T3's?** Same question, same answer as JB-6.01's Q3,
which I will not repeat here except in one line: **the settling time is a feel judgement made with
a brush in the hand on the Note 9, and it should be the owner's.** The two I would most want ruled
rather than defaulted are `STEPS_PER_FRAME` (it is the *entire* user-visible duration of the effect)
and `RATE` (its ceiling of 0.5 is a stability bound and the value below it is a look). My
recommendation is the same as 6.01's: wire the whole feature, default the strengths so the effect
is *invisible*, and let 6.03's tuning pass turn it on with the owner watching — with the caveat
that for this row "invisible" is easier, because the whole feature is behind a constant that a
single line turns on.

**Q3 — should `fastDry()` be user-visible?** R1 §5.9 and Rebelle's UI both give the person a
"Dry the layer" and a "Fast dry", and blueprint §2's "open and draw, no setup questions" argues
against chrome. Today the only way to reach `fastDry()` is automatically (snapshot, undo, close),
which is a defensible answer and is what Decision 6 specifies. But it means a person who wants to
inspect a settling wash at leisure has no way to stop it settling. Options: (a) automatic only, as
specified; (b) a long-press or the S Pen button, which is JB-2.01's chrome and not this row's;
(c) expose it in the JB-1.06 smudge/push row where a water tool cluster may already exist. **I have
specified (a)** and it costs nothing; I flag it because the first time the owner notices it missing,
nobody will remember this question.
