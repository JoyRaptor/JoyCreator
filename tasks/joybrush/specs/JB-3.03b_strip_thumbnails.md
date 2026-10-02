# JB-3.03b — Film-strip thumbnails: the CPU render, the pixel budget, and when a picture is stale

| | |
|---|---|
| **Tier** | **T2** (pure arithmetic over a `Board` and a `TileSource`; no Android, no view, no engine, no clock) |
| **Status** | 📝 **Draft spec, round 2** — written 2026-09-30 by `openrouter/stealth/space-bunny-alpha`; cross-reviewed (`xr`) and **back with 2 BLOCKERs, 4 MAJORs and 10 MINORs, all addressed in this revision** — the admission predicate was unstated and two tests demanded opposite answers from one board, and the filter's un-premultiply was wrong. **No question below blocks the build.** |
| **xr round 1** | *2 BLOCKERs, 4 MAJORs, 10 MINORs.* Fixed here: the predicate is now **cumulative and stated in prose, in pseudo-code and in a named public function** (Decision 6, `admitted()`), and tests 6, 7, 8, 8b, 6b, the scale table's 210/211 rows and Q1 are all reconciled to it; the un-premultiply is `colour = acc[c]/acc[3]`, `alpha = acc[3]/total`, matching `RegionRenderer.kt:197-205`, and tests 11, 12b and 14 now carry **non-uniform alpha** so they can see it; J1 censuses every class the file compiles to, J2 reads the constant pool and the non-comment source lines; the 240-cell density-3 fixture is replaced by a **1 024-cell density-16/11 fixture that lands exactly on the cap** so `>` vs `>=` is testable; `4194304 / 17424` corrected to **240.7199**; the unsourceable "3 960 px / 132 px tall" attribution removed (Decision 2 is PROVISIONAL and Q5 is the open item); `DocOps` added to the import block; Decision 1's throw list completed; the check order pinned; the peak-memory table corrected; `coerceIn` recorded as unreachable rather than claimed as tested. |
| **Needs** | **JB-3.03** (`FilmStrip`, Built) · **JB-2.13a** (`RegionRenderer`, Built, 47 tests) · JB-0.02 (`Board`, `Layer`, `DocOps.celFor`) |
| **Owner area** | (1) NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/StripThumbnails.kt` · (2) NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/StripThumbnailsTest.kt` · (3) NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/anim/StripThumbnailsNoSecondConstantTest.kt` · **NOTHING ELSE.** In particular **NOT** `FilmStrip.kt` (it is JB-3.03's, landed and reviewed), **NOT** `RegionRenderer.kt`, **NOT** `AnimOps.kt`, **NOT** `DocModel.kt` (R30 item 3), **NOT** `GlPaintEngine.kt` or `JbCanvasView.kt` (R30 item 2, Lead only), **NOT** `JoyBrushActivity.kt` (R30 item 1, and this row has no business there), nothing in `joybrush-android/` or `app/`, no Gradle file, no build file, no resource, no `document.json` field. |
| **Estimated size** | ~240 lines of Kotlin, ~450 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures under `joybrush/core/build/test-results/jvmTest/` |

## Goal

A film strip whose cells are numbered is legible and completely useless for animating: an animator
reads the strip by *looking* at it, and a strip of numbers tells them nothing about whether frame 7 is
the one where the hand is up. So the cells get pictures, and they get them the only way available on a
phone today without touching the GL engine: **a CPU render of the board, once per distinct picture,
squeezed down to the cell's own pixel size.**

The hard part of this row is not the render — `RegionRenderer` has done that since JB-2.13a. The hard
part is that **a cell is not a cell.** JB-3.03's whole premise is that a cell's width *is* its hold, so
a frame held 999 ticks is a cell 43 956 **dp** wide — 131 868 **px** at density 3 — and a thumbnail
sized to that cell is 17 406 576 px, or **66.4 MiB**, on its own. Scale, not rendering, is what this row
has to answer, and it has to answer it in **words**:
every cell that cannot have a picture says so, and the strip falls back to the numbered cell R33 (c)
already gave it.

## What this row is, and what it is not — the Decision that matters most

**This row produces BOTH the bitmaps AND the half of the staleness rule the document can express.**
Splitting them would be the easy way out and it is wrong here, because the two disagree by
construction if they are written separately: the key is what says *which* cells need re-rendering, and
if the key and the render are written by two different rules, a cell can be re-rendered into a size its
key does not describe and nothing will notice.

| This row **does** | This row **does not** |
|---|---|
| the pixel size of every cell's picture | where a picture is stored, or for how long |
| which frames share one render, and why | **when** any of it runs — thread, priority, window |
| the total pixel budget and the refusal, per cell, in words | drawing, scrolling, a `Bitmap`, a `RecyclerView` |
| the bytes, straight RGBA8, one array per cell | reading pixels back off the GPU (`GlPaintEngine`) |
| a key per cell, and the list of cells whose key is missing | a cache, an LRU, an invalidation hook |
| a total, never-throwing budget path | an ink-layer rasteriser (Q3) |

**The scheduling half is deliberately out of scope, and it is the Lead's.** Q7 gives the number that
makes it somebody's decision: the JB-0.10 budget table prices a 1024² `RegionRenderer.render` at
**250 ms** (`BenchCases.kt:134`), so a 30-frame strip on a 1 M px board is about **7.5 s** of CPU by
that table's own arithmetic. This row is what the strip *would* draw; when it draws it, and in what
order, is a phone decision about a phone. A builder must not answer it.

## The seam: this row has no clock, no engine and no view, and that is a claim with a test

JB-3.03's seam section is the model here. Three facts force this row's shape:

1. **A GL read path is not available and not wanted.** `GlPaintEngine.kt` is R30 item 2, Lead only.
   Cut Decision C3 of JB-3.03 ruled that a GL read "means touching the engine R14 just stabilised".
2. **`RegionRenderer` is the one way a rectangle becomes pixels on the CPU**, and its KDoc
   (`RegionRenderer.kt:99`) already claims "thumbnail" as one of its callers by name.
3. **The owner wants the core platform-neutral** (OWNER_CONSTRAINTS 2026-09-28, "keep the door open to
   iOS"). A `Bitmap` in this file would put `NoClassDefFoundError` between it and every test below it —
   `android.jar` is `compileOnly`.

**J2** is the mechanical form of that, in `jvmTest`.

## Contract (verbatim from the landed source)

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/FilmStrip.kt` — the geometry this
row reads and **never edits**:

```kotlin
class FilmStrip(val board: Board, val density: Float = 1f) {          // FilmStrip.kt:51
    val tickPx: Float get() = TICK_PX_DP * density                     // FilmStrip.kt:54
    fun cellWidth(frameIndex: Int): Float                             // FilmStrip.kt:90 — frames[i].holdFrames * tickPx,
                                                                    //   0f for an index out of range
    companion object {
        const val TICK_PX_DP: Float = 44f                             // FilmStrip.kt:313
        const val EDGE_GRAB_PX_DP: Float = 24f                        // FilmStrip.kt:326
    }
}
```

**`FilmStrip` knows nothing about how TALL a cell is.** There is no height constant anywhere in
JB-3.03, its spec, or R32. This row therefore supplies the strip's cell height (Decision 2), and that is
a new fact about the strip's geometry, not something read out of it. It is Q5.

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt`:

```kotlin
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? }  // :24-26

class RegionException(message: String) : Exception(message)          // :40

const val MAX_REGION_PX = 8_388_608L                                  // :89

object RegionRenderer {
    const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4                   // :164
    /** STRAIGHT (un-premultiplied) RGBA8, rect.w * rect.h * 4, row 0 = top. */
    fun render(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): ByteArray      // :184
    /** The same picture, PREMULTIPLIED in 0..1 floats. */
    fun renderPremultiplied(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): FloatArray  // :221
    // private: requireSize (:357), toByte255 (:386) — `(v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()`
}
```

The three facts about `RegionRenderer` this row is built on, all read from the file and not assumed:

- **`renderPremultiplied` allocates `rect.w * rect.h * 4` Floats** (`:230`) — so the transient cost of
  one picture is **16 bytes per board pixel**, once, no matter how many cells that picture serves.
- **It skips tiles that are not there** (`tiles.tile(...) ?: continue`, `:286`), so its cost on a sparse
  board is far below the allocation. That is why this row's cost claim is stated in **tile fetches**
  and not in milliseconds (Decision 12).
- **`render` refuses in `RegionException`** when `w * h > MAX_REGION_PX`, with its own sentence
  (`:363-366`). That sentence ends "Export a smaller area, or a piece of it at a time", which is
  **the wrong instruction for a film strip** — see Decision 8.

`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocOps.kt`:

```kotlin
fun celFor(layer: Layer, frameId: String?): Cel? {          // :218-222
    if (layer.animatedIn == null) return layer.cels.singleOrNull()
    val celId = frameId?.let { layer.frameCel[it] } ?: return null
    return layer.cels.firstOrNull { it.id == celId }
}
```

`DocOps.kt:152-157` (**validate rule 8**, quoted because Decision 7 rests on it):

> `LayerKind.INK -> if (c.tiles.isNotEmpty()) out += "layer \"${l.id}\" is ink, so cel \"${c.id}\" cannot have tiles"`

So **an INK layer's cel has no tiles, by the document's own rule.** A `TileSource` asked for one gets
`null`, `RegionRenderer` skips it, and the "thumbnail" is a blank rectangle of paper. That is the worst
possible outcome for a thumbnail, and Decision 7 is what stops it.

`DocModel.kt` — the four types this row reads, all verbatim:

```kotlin
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)     // :40
@Serializable data class Frame(val id: String, val holdFrames: Int = 1)              // :90-93
@Serializable data class Board(                                                       // :97-106
    val id: String, val name: String, val kind: BoardKind, val rect: RectPx,
    val clipToBoard: Boolean = false, val fps: Float = 12f,
    val frames: List<Frame> = emptyList(), val grid: SpriteGrid? = null,
)
@Serializable data class Cel(val id: String, val tiles: List<String> = emptyList(), val strokesFile: String? = null)  // :161-165
@Serializable data class Layer(                                                        // :167-186
    val id: String, val name: String, val kind: LayerKind, val visible: Boolean = true,
    val locked: Boolean = false, val opacity: Float = 1f, val blend: BlendMode = BlendMode.NORMAL,
    val animatedIn: String? = null, val cels: List<Cel>, val frameCel: Map<String, String> = emptyMap(),
)
```

## Decisions already made

1. **The row is TOTAL. It returns one answer per cell and never throws for a budget, a size, an ink
   layer or a board that is too big.** *Why:* a paint loop on a phone must not be handed an exception
   it has to catch, and the four refusals below are all facts a person can be shown in a cell.
   **The COMPLETE list of things that do throw, and it is four, not two:**
    - `IllegalArgumentException` from `RegionRenderer.kt:358` — a **negative** board rect. Reachable from
     a hand-edited file, because `DocOps.kt:72` only refuses `w <= 0 || h <= 0`, not a negative side,
     and `RectPx` is four `Int`s. A caller bug, and the landed message says so.
    - `IllegalArgumentException` from `RegionRenderer.kt:287-289` — a `TileSource` that hands back a tile
     that is not `TILE_BYTES`. A storage bug, not a short read.
    - `IllegalArgumentException` from `RegionRenderer.kt:433-436` — a `paper` that is not a `#RRGGBB`
     colour. The landed rule is REFUSE, never default, and this row propagates it unchanged.
   - `DocException` from `keyOf` for a `frameId` that is not a frame of the strip — the landed idiom
     `FilmStrip.holdOf` uses at `FilmStrip.kt:284`.
   **Only `RegionException` is caught**, and only around the `renderPremultiplied` call (Decision 8). The
   three `IllegalArgumentException`s must **not** be caught: two are storage/caller bugs and one is a
   rule that exists so a bad background never paints silently.

2. **PROVISIONAL — the Lead's number, not a derivation. A cell's picture is exactly the cell:
   `wPx = roundHalfUp(strip.cellWidth(i))`, `hPx = roundHalfUp(strip.tickPx)`, so the cell is a SQUARE
   of one tick.** *Why I chose the square:* R32 puts every layout number in dp at the use site, and the
   only two numbers the strip owns are `TICK_PX_DP` and `EDGE_GRAB_PX_DP`. Reusing `tickPx` as the height
   means **this row adds no layout constant at all**, which is also the only way the strip's height
   cannot drift from its width.
   **What I did NOT do, and it is the honest correction of this Decision's first draft:** the earlier
   version of this spec justified the square by quoting "30 cells is 3 960 px of width and a 132 px
   height" as *the board's own brief*. **That sentence is not in the repository.** It came from the
   dispatch brief that asked for this spec, and a repository-wide search finds it nowhere else — the
   board's JB-3.03b row says only "THERE IS NO SPEC FILE" and R33 says the same. It does not carry the
   decision in any case: `3 960 = 30 × 132` follows from a cell **width**, which R32 and `FilmStrip.kt:54`
   already give, and **nothing anywhere fixes the cell's HEIGHT**. So this Decision is a decision, Q5 asks
   the Lead to confirm it, and the cost of the alternative is in Q5. Treat it as open, not settled.

3. **The source is `RegionRenderer.renderPremultiplied(doc, tiles, strip.board.rect, frameId, paper)`
   at 1:1, and the squeeze is a box filter over the premultiplied floats.** *Why:* averaging STRAIGHT
   RGBA8 is the black-fringe bug, and it is the single most likely wrong turn in this row — so test 14
   exists to catch exactly it, and it catches a builder who reached for the `render` door by mistake.

4. **The filter is an AREA average, with the spans in `Double` and the accumulation in `Float` in a
   pinned order (`i` ascending inside `j` ascending, channels 0,1,2,3), and it un-premultiplies ONCE at
   the end using the box's own alpha — NOT using the weight sum.** *Why the area average:* a thumbnail is
   a minification, and minification without an area filter throws away most of the picture — nearest
   samples one pixel in 40. Pinned order because a `Float` sum is order-dependent and an unpinned one is
   a test that fails on a different JVM. *Why the un-premultiply is stated this precisely:* the first
   draft of this spec divided every channel by the weight sum, which is **wrong** — `total` is a weight
   and has nothing to do with alpha — and in the first draft of this spec **only one test out of the
   twenty-two it then had could see it**, because every other fixture had uniform alpha and the two
   rules coincide there. It is now written as
   `RegionRenderer.kt:197-205` with the box-filtered mean in place of `p[]`, and tests 11, 12b and 14
   carry non-uniform alpha so that a wrong divisor is caught with a number rather than argued about.

5. **Frames that show the same picture share ONE render.** The identity is
   `PictureKey = ["<layerId>=<celId>", …]` over the layers `RegionRenderer` will actually composite, in
   `doc.layers` order. *Why:* the bytes come from `(layerId, celId)` tiles and nothing else, so one
   `renderPremultiplied` answers every cell that shares the key, and the downscale — which is the cheap
   half — still runs once per cell because the sizes differ. A board of four LINKed frames costs one
   render, not four. **This is the single largest saving in the row** and test 9 pins it with a counter.

6. **The budget is `MAX_STRIP_THUMB_PX = MAX_REGION_PX / 2` (4 194 304 px = exactly 16 MiB of straight
   RGBA8), and the admission predicate is CUMULATIVE in strip order.**
   - **The predicate, in one line of prose:** *walk the cells left to right carrying a running total of
     the pixels already admitted; a cell is admitted exactly when that total plus the cell's own pixels
     is at most the cap; a refused cell adds nothing to the total and the walk continues.*
   - **The predicate, in one line of pseudo-code:**

     ```kotlin
     var running = 0L
     for (i in sizes.indices) {
         val px = sizes[i].px
         admit[i] = (running + px <= MAX_STRIP_THUMB_PX)      // <= , not <
         if (admit[i]) running += px                          // a REFUSED cell spends nothing
     }
     ```
   - **Why cumulative and not per-cell, which is the alternative and the reason this needed deciding:**
     a per-cell predicate (`cellPx <= cap`, no running total) is **not a budget**. Every cell of a
     300-frame board at density 3 is 17 424 px and individually fine, so all 300 would be admitted and
     the strip would retain `300 × 17 424 × 4 B` = **20 MiB against a 16 MiB cap** — and a 10 000-frame
     board would retain 664 MiB, which is the `OutOfMemoryError` the cap exists to prevent. Cumulative
     is the only reading of the two that actually caps the retained total, so cumulative it is, and the
     cost of that choice is stated rather than discovered later.
   - **What cumulative costs, and it is not small: the admitted total DEPENDS ON STRIP ORDER.** The same
     31 cells give different answers depending on where the held frame sits — 30 one-tick cells plus one
     211-tick frame is `522 720` px admitted if the held frame is **last** and `4 181 760` px if it is
     **first** (because then only 29 of the small ones fit behind it). The property is that **the strip
     fills from the left**: the refusal is always at the right-hand end of what the budget can hold, and
     everything before it has a picture. That is predictable to a person and derivable in a test; a
     size-sorted admission would admit more pictures but would leave holes nobody could explain ("why
     does cell 12 have no picture and cell 40 does?"). **Test 8b pins the order-sensitivity as a
     property, not as an accident**, and it is part of Q1.
   - *Derived, not chosen:* written as `MAX_REGION_PX / 2L` so the derivation cannot rot, exactly as
     `AnimExport.kt:395` wrote `MAX_EXPORT_PX = MAX_REGION_PX * 2L` and said why in prose. 16 MiB is
     one tenth of the 160 MiB one render may hold, and one quarter of the 64 MiB this tree already
     calls one decoded picture. The multiplier is a PROVISIONAL choice — Q4.

7. **A visible INK layer refuses its cell, in words, naming the layer, decided before any render.**
    *Why:* `DocOps.kt:152-157` says an ink cel cannot have tiles, so `RegionRenderer` will silently skip
   it and hand back a blank rectangle. A blank thumbnail is worse than no thumbnail, because a person
   reads "the picture is empty" as a fact about their drawing. The refusal is honest and it names the
   cause. Closing it properly is a separate row (Q3).

8. **A board too big for one render refuses every cell, and this row RE-WORDS `RegionException`'s
   sentence rather than passing it on.** *Why:* the landed message ends "Export a smaller area, or a
   piece of it at a time", which is the wrong instruction for a strip that is not an export, and
   `RegionException` is caught — the documented, expected, non-error outcome of a legal call, not a bug.
   Every cell is refused, because no cell of a board that cannot be rendered once can be rendered at
   all. The re-worded sentence names the board, both numbers and the action; test 17 asserts both
   numbers are in it.

9. **A cell smaller than 1 × 1 px is REFUSED in words. It is never clamped up to 1 px.** *Why:* a
   clamped 1 × 1 picture is a lie (the cell has no picture) and the house rule is refuse, do not clamp.
   Reachable two ways, both real: a degenerate `density` (0, negative or `NaN` — `FilmStrip` does not
   guard it, `tickPx` is just `44f * density`) and a hand-edited `holdFrames` of 0, which
   `DocOps.validate` rule 4 already calls broken (`DocOps.kt:81-84`).

10. **The key is everything the document CAN say about a picture: frame id, size, paper, the board
    rect, and the picture key. The PIXELS are not in it, and that gap is Q2.** *Why:* `Cel` carries
    `tiles: List<String>` and nothing else — no revision, no timestamp, no counter. A stroke into a tile
    that already exists changes no field of the document at all, so "is this thumbnail stale?" has no
    answer in core today for the most common event there is. This row therefore ships the *derivable*
    half (the key, and `missing`, the list of cells whose key the host does not hold) and refers the
    other half rather than inventing a field, because a new serialised field is R30 item 3 and R31
    (version assigned at landing, never in a spec).

11. **Nothing in this file draws, schedules, caches or imports a phone type.** No `Bitmap`, no
    `Canvas`, no `Context`, no `Handler`, no thread, no coroutine, no `Looper`. *Why:* the iOS door
    (OWNER_CONSTRAINTS, 2026-09-28) and `android.jar` being `compileOnly`. This is also the answer to
    "can the row be done without a view": **it can, and that is why the view is not asked for.**

12. **Cost is asserted with a DETERMINISTIC WORK COUNTER — tile fetches — and never with a clock.**
    *Why:* R28, from the vector eraser: a wall-clock assertion is flaky by construction and failed for
    the Lead only while another Gradle job was running. A `TileSource` that counts its calls makes
    "one render per distinct picture" and "an ink layer is refused before anything is asked for"
    testable in microseconds. The milliseconds are *reported* in Q7, never asserted.

13. **`FilmStrip.kt` is called, never changed — and this row holds no copy of `MAX_HOLD_FRAMES`.**
    *Why:* same rule as JB-3.03 Decision 11 (`MAX_HOLD_FRAMES` is `private` in `AnimOps.kt:100`, so a
    local copy could drift with nothing to catch it). This row needs no copy at all: it reads
    `board.frames[i].holdFrames`, which the model has already clamped. **J1** is the test.

## The numbers, derived — this is the scale section

`tickPx = 44 × density` (`FilmStrip.kt:54`). A one-tick cell is `tickPx × tickPx`.

| density | one-tick cell | its pixels | its bytes |
|---|---|---|---|
| 1 | 44 × 44 | 1 936 | 7 744 |
| 2 | 88 × 88 | 7 744 | 30 976 |
| 2.5 | 110 × 110 | 12 100 | 48 400 |
| 3 | **132 × 132** | **17 424** | **69 696** |

**The strip, at density 3, all frames held one tick.** Each cell is 132 px wide and 132 px tall
(Decision 2), so the strip is `132 × cells` px wide and 132 px tall — 30 cells is `30 × 132 = 3 960` px
of width, and `3 960 × 132 = 522 720` px of thumbnail. The width half of that follows from a cell width,
which R32 and `FilmStrip.kt:54` already give; **the height half follows from Decision 2, which is
PROVISIONAL and is Q5.**

| cells | strip width | total pixels | total bytes | of the 16 MiB budget |
|---|---|---|---|---|
| 1 | 132 | 17 424 | 69 696 | 0.42 % |
| **30** | **3 960** | **522 720** | **2 090 880 (1.99 MiB)** | 12.5 % |
| **60** | **7 920** | **1 045 440** | **4 181 760 (3.99 MiB)** | 24.9 % |
| **240** | **31 680** | **4 181 760** | **16 727 040 (15.95 MiB)** | **99.7 % — the last one that fits** |
| 241 | 31 812 | 4 199 184 | 16 796 736 | 100.1 % — **refused** |

240 is the derived ceiling, not a typed one, and **the division is exact to four places**:
`4 194 304 / 17 424 = 240.7199…` (the earlier draft of this spec said 240.66, which is wrong; the
cross-review caught it against this table's own adjacent sentence, and the check that caught it is
Stop rule 6). `240 × 17 424 = 4 181 760` is **12 544 px under** the cap, and
`12 544 / 17 424 = 0.7199` — the same four places, so the two figures agree and the ceiling is
`240 + 0.7199 = 240` cells. `241 × 17 424 = 4 199 184` is **4 880 px over**. Tests 6 and 7 pin both
halves. **Note that no cell total at density 3 ever lands exactly on the cap** — `17 424 = 2³ × 3² ×
11²` and `4 194 304 = 2²²`, and 17 424 has no factor of 2²² — which is why test 6b uses a different
density and not this one. That is a property of the numbers, not a gap in the fixture.

**The same arithmetic with a held frame in it**, at density 3. **The index of the held frame is part of
the answer** (Decision 6: the strip fills from the left), so every row states it:

| board, in strip order | what it costs | answer |
|---|---|---|
| 30 one-tick cells, then one frame held **210** | `30 × 17 424 = 522 720`, then `210 × 17 424 = 3 659 040` → `4 181 760` | **all 31 admitted**, 12 544 px under |
| 30 one-tick cells, then one frame held **211** | `522 720`, then `211 × 17 424 = 3 676 464` → `4 199 184` | cells 0–29 admitted, cell **30 refused**, 4 880 px over |
| one frame held **211** FIRST, then 30 one-tick | `3 676 464` admitted, then 29 × `17 424 = 505 296` → `4 181 760` | cells 0–29 admitted, cell **30 refused** — and the admitted total is 8× the row above |
| one frame held **999** | `999 × 132 × 132 = 17 406 576` | **refused** — 4.15× the whole budget, 66.4 MiB, and it spends nothing, so the cells behind it are unaffected |
| a single frame held 999, at **density 1** | `999 × 44 × 44 = 1 934 064` | **fits** — 46 % of the budget, 7.4 MiB |

Three things fall out of that table and all three are the point of the row:

- **The budget is in PIXELS, not in cells**, so **the same board is admitted at density 1 and refused at
  density 2** (999 ticks: `1 934 064` admitted at density 1, `999 × 88 × 88 = 7 736 256` refused at
  density 2). Test 6b's family of fixtures is where that is pinned, because a builder who wrote the cap
  in cells would pass a cells-only test.
- **The admitted total depends on strip order**, as Decision 6 says out loud: the two 211-tick rows above
  are the *same 31 cells* and admit `522 720` px and `4 181 760` px respectively. Test 8b pins it.
- **JB-3.03's headline feature is the case the budget refuses.** A frame held 999 ticks is the thing
  JB-3.03 exists for, and at density 3 it cannot have a picture. It is not a contradiction — JB-3.03
  Decision 4's "no cap on the drawn width" is about the CELL and is untouched; the cell is still
  43 956 dp wide and still draws its number — but it is a fact the Lead should see, and the alternative
  (cap the *picture's* width and anchor it to the cell's left cap) is Q1.

**Peak memory, per `render` call.** Three things are alive at once: the **retained** thumbnails, the
group's **float scratch** (`boardPx × 16 B`), and the **destination** `ByteArray` for the cell currently
being written (`cellPx × 4 B`).

| board rect | float scratch (`boardPx × 16 B`) | retained, **including** the cell being written |
|---|---|---|
| 128 × 64 (the fixture) | 131 072 B = 128 KiB | ≤ 16 MiB |
| 1024 × 1024 | 16 MiB | ≤ 16 MiB |
| 2048 × 2048 | 64 MiB | ≤ 16 MiB |
| 2 896 × 2 896 (at `MAX_REGION_PX`) | 128 MiB | ≤ 16 MiB |

**The retained column includes the destination array, and cannot exceed 16 MiB because the destination
IS one of the admitted cells** — by the time it is allocated the running total has already been charged
for it, so `retained + destination ≤ MAX_STRIP_THUMB_PX × 4 B`. The earlier draft of this spec listed
only two of the three arrays; this is the correction.

The float scratch is `MAX_REGION_PX × 16 B = 128 MiB` at worst, which is **under** the 160 MiB the export
path already accepts (`RegionRenderer.kt:57-64` prices one render at 20 B/px because it holds the
straight result *and* the float scratch; this row only ever holds the scratch, so it is 16 B/px and
128 MiB). **Nothing here asks for a bigger heap than `RegionRenderer` already asks for.**

## The exact algorithm — a builder must not be left to invent a filter

```kotlin
// Source: `src` = RegionRenderer.renderPremultiplied(doc, tiles, rect, frameId, paper),
//         srcW = rect.w, srcH = rect.h, premultiplied RGBA floats in 0..1, row 0 = top.
// Dest:   out = ByteArray(dstW * dstH * 4), STRAIGHT RGBA8, row 0 = top.
//
// for dy in 0 until dstH:
//     ya = dy.toDouble() * srcH / dstH
//     yb = (dy + 1).toDouble() * srcH / dstH
//     j0 = floor(ya).toInt();  j1 = ceil(yb).toInt()
//     for dx in 0 until dstW:
//         xa = dx.toDouble() * srcW / dstW
//         xb = (dx + 1).toDouble() * srcW / dstW
//         i0 = floor(xa).toInt();  i1 = ceil(xb).toInt()
//         acc[0..3] = 0f;  total = 0f
//         for j in j0 until j1:
//             wy = min(yb, j + 1.0) - max(ya, j.toDouble());  if (wy <= 0.0) continue
//             for i in i0 until i1:                                  // ASCENDING, pinned
//                 wx = min(xb, i + 1.0) - max(xa, i.toDouble());    if (wx <= 0.0) continue
//                 w  = (wx * wy).toFloat()
//                 si = (j * srcW + i) * 4
//                 acc[0] += src[si]     * w;  acc[1] += src[si + 1] * w
//                 acc[2] += src[si + 2] * w;  acc[3] += src[si + 3] * w
//                 total += w
//
//         // THE MEAN, then the un-premultiply — two DIFFERENT divisors, and this is the whole of it.
//         // `total` is a WEIGHT SUM. It is the divisor for the MEAN only. The colour is then divided
//         // by the mean's ALPHA, which is a different number, and the alpha is written UNSCALED.
//         // This is `RegionRenderer.kt:197-205` with `p[]` replaced by the box-filtered mean.
//         val inv = if (total > 0f) 1f / total else 0f
//         val a   = acc[3] * inv                 // the box's mean alpha
//         val k   = if (a > 0f) 1f / a else 0f   // and the colour's own divisor
//         val di  = (dy * dstW + dx) * 4
//         out[di]     = toByte255(acc[0] * inv * k)
//         out[di + 1] = toByte255(acc[1] * inv * k)
//         out[di + 2] = toByte255(acc[2] * inv * k)
//         out[di + 3] = toByte255(a)
```

**THE TWO DIVISORS, and why getting them the same is the bug this row's tests exist to catch.** Dividing
all four channels by `total` — the first draft of this spec's pseudo-code — produces an array that is
**premultiplied but labelled straight**, which is exactly the black-fringe defect, and it is *invisible*
on any fixture whose alpha is uniform: where `acc[3] == total` the two rules coincide. The three tests
that carry non-uniform alpha (11, 12b, 14) each go red with a specific number:

| fixture | correct | all-four-by-`total` |
|---|---|---|
| **test 11** pixel 1, half-transparent blue premultiplied `(0,0,128,128)` | blue **255**, alpha 128 | blue **128**, alpha 128 |
| **test 12b** dest 2, one third opaque red and two thirds nothing | red **255**, alpha 64 | red **64**, alpha 64 |
| **test 14** one 50 % red pixel in a 2 × 2 | red **255**, alpha 32 | red **32**, alpha 32 |

**A premultiplied array labelled straight is the fringe. Do not "simplify" the two divisors into one.**

`toByte255(v) = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()` — **the two lines of
`RegionRenderer.kt:386`, spelled out and not called**, because it is `private` there. This is the same
situation as `FilmStrip`'s `dragStep` against `SpriteGridMath.roundedPx`
(`FilmStrip.kt:344-350`): a precedent, not a call. **Test 11 is what pins the two copies together** — at
1:1 the filter is the identity, so the thumbnail must be byte-for-byte
`RegionRenderer.render`'s own answer for the same rect. A drift in either copy turns that test red.

**`coerceIn` is here because the landed code has it, and no test in this file can reach it.** A
premultiplied pixel has `rgb <= alpha` by construction, and every blend in `Blend.kt` preserves it, so
`mean[c] / mean[3]` can never exceed 1 for a valid document — the clamp is defence against a storage
violation, not against arithmetic. **It is therefore NOT in the mutation list**, because a mutation no
test can redden is not a mutation and listing it would be a false claim about coverage. Recorded here
instead, which is the honest form. **The `+ 0.5f` half of the same two lines IS tested**, by test 12b
(byte **64**, where truncating instead of rounding gives **63**).

**The `Double` spans are exact, and here is the bound.** `srcW × srcH ≤ MAX_REGION_PX = 2²³` is
guaranteed by `RegionRenderer.requireSize` before this code runs, so `srcW ≤ 2²³`; and the plan has
already refused any cell wider than the whole budget, so `dstW ≤ 4 194 304 = 2²²`. The largest product
is therefore `2²² × 2²³ = 2⁴⁵`, comfortably under `2⁵³`, so every `dx.toDouble() * srcW` is exact and
no boundary is off by a thousandth. **If you find yourself computing these products in `Float` or in
`Int`, stop** — `Int` is the wrap this project keeps meeting at a seam.

**The algorithm handles UPSCALE, and it must.** A 64 × 64 board with a 132 px cell at density 3 is an
*upscale*, not a downscale: the cell is bigger than the board. Nearest-neighbour would give a blocky
picture there and nobody would notice until they looked. The same loop handles it, because a dest box
that falls inside one source pixel gets that pixel with weight 1. Test 13 pins it.

## Steps

1. Create `StripThumbnails.kt` in `core/anim/`, package `cc.joycreator.joybrush.core.anim`, with
   `ThumbSize`, `CellThumb`, `PictureKey`, `ThumbKey` and `object StripThumbnails` exactly as the
   contract below spells them. Every KDoc says **which decision** it implements — the house rule, and
   the only way a reviewer can tell a decision from an accident.
2. `sizeOf` — Decision 2. `ThumbSize(roundHalfUp(strip.cellWidth(i)), roundHalfUp(strip.tickPx))`, and
   nothing else. `roundHalfUp` is a private top-level `Float → Int` (ties away from zero, `NaN` → 0,
   saturating), spelled out like `dragStep` and `roundedPx` were.
3. `pictureKeyOf` and `undrawableLayerOf` — Decisions 5 and 7. Both walk `doc.layers` in order and both
   apply **the same** inclusion rule, which mirrors `RegionRenderer`'s three `continue`s at `:265`, `:268`
   and `:270` exactly: skip `!visible`, skip `celFor(layer, frameId) == null`, skip an opacity that is
    `NaN` or `≤ 0` (`RegionRenderer.opacityOf`, `:393-399`). The rule errs towards **including** a layer:
   an extra entry in the key costs a render, a missing entry would hand back the wrong picture.
4. `admitted` — Decision 6, and it is the **whole** predicate as one public function: pure, no document,
   no tiles, no render, so tests 6, 6b, 7, 8 and 8b run against the budget directly and in
   microseconds. The pseudo-code in Decision 6 is this function's body, and it is the only place in the
   file where `MAX_STRIP_THUMB_PX` is compared to anything.
5. `render` — Decisions 1, 3, 4, 6, 7, 8, 9, **in this order, and the order is part of the contract**
   because two cells can be refused for two different reasons and a caller must get the same sentence
   every time:
   **(1) size** — a cell with `wPx < 1 || hPx < 1` is refused (Decision 9);
   **(2) budget** — a cell `admitted()` says no to is refused (Decision 6);
   **(3) ink** — a cell whose picture has an undrawable layer is refused (Decision 7);
   **(4) board size** — a `RegionException` from the group's `renderPremultiplied` refuses every cell in
   that group (Decision 8).
   The order is size, budget, ink, board: **the cheapest and most local reason wins, and the two that
   cost a render to discover come last.** A cell that is both degenerate and on an ink layer gets the
   **size** sentence and is never asked whether there is ink, which is what test 18b pins.
   Then: group the admitted cells by picture key → for each group in first-appearance order, one
   `renderPremultiplied` (catching `RegionException` only) → one downscale per cell. **One `FloatArray`
   alive at a time**, and it is dropped before the next group is rendered.
6. `keyOf` and `missing` — Decision 10. Pure: no tiles, no render, no clock.
7. Write the tests, from the list below, **before** running anything.
8. Run the **twelve** non-vacuity mutations and paste the output.

## Contract (the new file, spelled out)

```kotlin
package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One cell's picture size, in whole SCREEN px. Never clamped: a size under 1 is refused (Decision 9). */
data class ThumbSize(val wPx: Int, val hPx: Int) {
    val px: Long get() = wPx.toLong() * hPx.toLong()
    val bytes: Long get() = px * 4L
}

/** What ONE cell of the strip gets. Exactly one of the two, for every cell, always (Decision 1). */
sealed class CellThumb {
    /**
     * NOT a `data class`, and that is a decision: a generated `equals` on a `ByteArray` compares by
     * REFERENCE, so two identical pictures would say they differ. Precedent: `AnimExport.AnimFile`
     * (`AnimExport.kt:56`) and `OraExport.Thumb`.
     */
    class Ready(val frameIndex: Int, val frameId: String, val size: ThumbSize, val rgba: ByteArray) : CellThumb()

    /** [reason] is a SENTENCE naming the frame, both numbers and what the strip does instead. */
    data class Refused(val frameIndex: Int, val frameId: String, val reason: String) : CellThumb()
}

/** What a picture is MADE of: "<layerId>=<celId>" for every layer the renderer will composite, in
 *  `doc.layers` order. Two frames with the same key have the same bytes (Decision 5). */
data class PictureKey(val layers: List<String>)

/** Everything the document can say about a cell's picture. NOT the pixels — see Q2 (Decision 10). */
data class ThumbKey(
    val frameId: String,
    val size: ThumbSize,
    val paper: String?,
    val rect: RectPx,
    val picture: PictureKey,
)

object StripThumbnails {

    /**
     * The most pixels one strip's thumbnails may hold in all. DERIVED from [MAX_REGION_PX] so the
     * derivation cannot rot. See Decision 6 for the whole argument and Q4 for what is provisional.
     */
    const val MAX_STRIP_THUMB_PX: Long = MAX_REGION_PX / 2L

    /**
     * Which cells get a picture, as the WHOLE of Decision 6's predicate and nothing else.
     *
     * PURE — no document, no tiles, no render — so the budget is testable on its own and the 1 024-cell
     * fixture of test 6b costs nothing. The body is the pseudo-code in Decision 6, line for line:
     *
     *     var running = 0L
     *     for (i in sizes.indices) {
     *         val px = sizes[i].px
     *         out[i] = running + px <= MAX_STRIP_THUMB_PX      // <= , not <
     *         if (out[i]) running += px                        // a REFUSED cell spends nothing
     *     }
     *
     * @return one entry per entry of [sizes], in order. A size under 1 px is this function's business
     *   only in the arithmetic (`px` may be 0 or negative); the REFUSAL for a degenerate size belongs
     *   to [render], which decides it first. Do not fold that check in here — [render] has to give the
     *   size sentence in preference to the budget one, and two answers in two places is two opinions.
     */
    fun admitted(sizes: List<ThumbSize>): BooleanArray

    /** Cell [frameIndex]'s picture size. `roundHalfUp` of `strip.cellWidth(i)` × of `strip.tickPx`. */
    fun sizeOf(strip: FilmStrip, frameIndex: Int): ThumbSize

    /** The key two frames must share to be rendered once (Decision 5). Never null, never empty-checked. */
    fun pictureKeyOf(doc: JbDocument, frameId: String): PictureKey

    /** The first layer [pictureKeyOf] includes that is INK, or null. Decision 7. */
    fun undrawableLayerOf(doc: JbDocument, frameId: String): Layer?

    /** Cell [frameId]'s staleness key. @throws DocException if the strip has no such frame. */
    fun keyOf(doc: JbDocument, strip: FilmStrip, frameId: String, paper: String?): ThumbKey

    /** The cell INDICES whose key is not in [held], in strip order. Never throws. */
    fun missing(doc: JbDocument, strip: FilmStrip, paper: String?, held: Set<ThumbKey>): List<Int>

    /**
     * One cell per frame, in strip order. TOTAL: never throws for a budget, a size, an ink layer or an
     * over-large board (Decision 1). The four refusal sentences are in the Decisions, and the MiB and
     * pixel figures in them are COMPUTED from the values, never typed beside them.
     */
    fun render(doc: JbDocument, tiles: TileSource, strip: FilmStrip, paper: String?): List<CellThumb>
}
```

### The four refusal sentences, exactly

Every one names the subject, both numbers, and what the strip does instead. **The numbers are
interpolated from the values** — the same discipline as `MAX_REGION_PEAK_MIB` at `RegionRenderer.kt:95`
— so a message cannot contradict the constant beside it. The MiB figure is
`MAX_STRIP_THUMB_PX * 4 / (1024 * 1024)`, which is **16**.

| Cause | `reason` |
|---|---|
| budget (Decision 6) | `frame "f1" is 27852 by 132, which is 3676464 pixels, and this strip may hold 4194304 pixels (16 MiB) of thumbnails in all; draw this cell as a numbered cell.` |
| ink layer (Decision 7) | `frame "f0" is drawn on ink layer "l-ink", and this renderer draws pixels, not stroke records; draw this cell as a numbered cell.` |
| degenerate size (Decision 9) | `frame "f0" is 0 by 0 pixels, and a cell with no picture in it is drawn as a numbered cell.` |
| board too big (Decision 8) | `board "b-anim" is 4096 by 4096, which is 16777216 pixels, and one render of that is more than this renderer will allocate (8388608 pixels); every cell of this strip is drawn as a numbered cell.` |

**Every test that asserts a refusal asserts the numbers are IN the message**, so a builder cannot
hard-code a sentence that drifts from the constant.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (total, never throws for a request) | 16, 17, 18, 22, **18b** |
| 2 (the cell is the cell, and it is square) — **PROVISIONAL, Q5** | 1, 2, 3 |
| 3 (premultiplied door, 1:1 source) | 11, **14** |
| 4 (area average, pinned order, `Double` spans, **two divisors**) | 12, **12b**, 13, 14, 15 |
| 5 (one render per picture) | 9, 10, 21 |
| 6 (the budget; **cumulative**, refused cells spend nothing, order matters) | 4, 5, 6, **6b**, 7, 8, **8b** |
| 7 (ink refuses in words) | 16, **18b** |
| 8 (over-large board refuses every cell, re-worded) | 17 |
| 9 (under 1 px refuses, no clamp — **on `wPx`/`hPx`, not on `.px`**) | 18, **18b** |
| 10 (the key is what the document can say) | 19, 20 |
| 11 (no phone, no view, no engine) | **J2** |
| 12 (a counter, never a clock) | 9, 10, 16, 17 |
| 13 (no copy of 999, `FilmStrip` untouched) | **J1**, 6b |
| — (the seam: no clock, ever) | **J2** |

## Tests

### `commonTest` — `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/StripThumbnailsTest.kt`

**Fixture A — JB-3.03's own, copied from `FilmStripTest.kt:79-107`** so every number here is traceable
to a fixture that already exists in the tree and is already green:

```kotlin
private fun heldFrames() = listOf(Frame("f0", 1), Frame("f1", 2), Frame("f2", 1), Frame("f3", 3))

/** board "b-anim" = RectPx(0, 0, 128, 64), 12 fps, holds [1,2,1,3];
 *  "l-static" PAINT with cel "c-static", and "l-anim" PAINT animated in "b-anim" whose
 *  frameCel maps EVERY frame to the one cel "c-anim". */
private fun fixture(): JbDocument
private fun JbDocument.board(id: String = "b-anim"): Board
```

**Fixture B — the tiny boards.** Every filter test needs a source it can write four pixels into, and the
size is the point, so the board is built at the size the test wants. Helpers, modelled on
`RegionRendererTest.kt:1079-1148`:

```kotlin
/** A counting TileSource, so "one render per picture" is a number and not a claim. */
private class CountingTiles(private val map: Map<Triple<String, String, Pair<Int, Int>>, ByteArray>) : TileSource {
    val asked = ArrayList<Triple<String, String, Pair<Int, Int>>>()
    override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? {
        asked += Triple(layerId, celId, tx to ty)
        return map[Triple(layerId, celId, tx to ty)]
    }
}

/**
 * A tile whose first `pixels` RGBA bytes are [first], the rest zero. **PREMULTIPLIED** when any alpha
 * is below 255, which is what `TileSource` is documented to supply (`RegionRenderer.kt:10-11`).
 */
private fun tileOf(first: IntArray, pixels: Int): ByteArray

/** An ANIMATION board [w] x [h] with [holds], one PAINT layer, every frame showing [celId]. */
private fun anim(w: Int, h: Int, holds: List<Int>, celId: String = "c-1", boardId: String = "b-anim"): JbDocument

/** [anim] with every hold set to 1 and the given number of frames — the budget fixtures only. */
private fun animTicks(n: Int, w: Int = 128, h: Int = 64): JbDocument
```

**`paper` IS `null` IN EVERY TEST THAT EXERCISES THE FILTER — 11, 12, 12b, 13, 14 and 15 — AND EVERY
"BLACK" IS OPAQUE `(0, 0, 0, 255)`.** Both are load-bearing and both are ways a builder following the
fixture habits gets numbers this spec never mentions:

- **With a `paper` backdrop, every pixel's alpha becomes 1** (`RegionRenderer.kt:237-246` fills the whole
  scratch with the paper at alpha 1 before any layer is composited, and a `NORMAL` composite gives
  `alpha = sa + 1 × (1 − sa) = 1` for every pixel). That makes the alpha uniform, which makes the two
  divisors of Decision 4 coincide, and it changes the answers: **test 14 would be `(255, 223, 223, 255)`**
  instead of `(255, 0, 0, 32)`, and tests 11, 12, 12b and 13 would all become opaque pictures that cannot
  see the bug at all.
- **Under a transparent `(0, 0, 0, 0)` "black", the pixel is not black, it is absent.** Test 13's middle
  pixel would be **64** instead of 32 (the transparent neighbour would contribute nothing to the
  numerator), and test 12's "black" end would come out with alpha 191 and a red of 255 rather than an
  opaque black.

Every one of those six tests therefore passes `paper = null` in its own call and writes its blacks as
`(0, 0, 0, 255)`. The budget tests (4–8b) and the refusal tests (16–18b) may pass whatever they like,
since they never reach the filter.

**Every expected value below is DERIVED, and the derivation is in the test's own comment.** A test that
cannot say where its number came from is a test that gets "fixed" to match whatever the code did.

1. **`aCellIsAsWideAsItsHoldAndAsTallAsOneTick`** — fixture A at density 1, `tickPx = 44 × 1 = 44`:
   f0 **44 × 44**, f1 **88 × 44**, f2 **44 × 44**, f3 **132 × 44** — the widths are JB-3.03 test 1's own
   four numbers (`44`, `88`, `44`, `132`) read back through this row, and every height is 44. The total
   is `1936 + 3872 + 1936 + 5808` = **13 552 px** = 54 208 B. The same board at density 3:
   **132 × 132, 264 × 132, 132 × 132, 396 × 132** = 17 424 + 34 848 + 17 424 + 52 272 = **121 968 px**.
2. **`theSizeIsRoundedToWholePixelsAtEveryDensity`** — the **scale law**, not two literals. For
   `k in 1..64`, build the strip at `density = 1f + k / 64f` (every one of those is a multiple of
   1/64, so it is **exactly** representable and `44f × density` is exact) and assert
   `sizeOf(strip, 0).wPx == (44 * (64 + k) + 32) / 64` with **integer** arithmetic — that expression is
   `round(44 × (64+k) / 64)` with ties away from zero, written without a float. All 64 cases. The
   comment says why 64 and not 2: "two literals only say the code moves when density moves; this says
   **why** it moves", which is JB-3.02's own wording and the reason the row exists.
3. **`aCellIsSquareAtEveryDensity`** — for densities 1, 2, 2.5, 3, 3.0625 and 4, a one-tick frame gives
   `wPx == hPx` ∈ {44, 88, 110, 132, **135**, 176}. The comment derives 135: `44 × 3.0625 = 134.75`, and
   3.0625 is `49/16`, so the product is exact and the rounding is a real decision — a truncating
   implementation gives **134** and fails. **Every density here is a dyadic rational, so every product
   is exact**; do not "simplify" a fixture into a density like 1.1, whose float product is not exact and
   whose expected byte is then a rounding accident.
4. **`thirtyOneTickCellsAreTheBudgetsQuietestCase`** — a 30-frame board, all holds 1, density 3: 30
   admitted, 0 refused, total **522 720 px** = 2 090 880 B. The comment restates it as
   `30 × 132 = 3 960` px of strip wide and 132 px tall, so the width half is traceable to a cell WIDTH
   (which R32 and `FilmStrip.kt:54` already give) and the height half to Decision 2 (PROVISIONAL, Q5).
5. **`sixtyCellsStillFitAndTheFigureIsDerived`** — the same at 60: `60 × 132 = 7 920` px of strip,
   `60 × 17 424 = ` **1 045 440 px** = 4 181 760 B (3.99 MiB), 0 refused. Also assert the two halves
   agree: the bytes are exactly **twice** test 4's, because 60 is twice 30.
6. **`theBudgetIsHalfTheRenderBudgetAndSixteenMebibytes`** — `MAX_STRIP_THUMB_PX == MAX_REGION_PX / 2`
   and `MAX_STRIP_THUMB_PX == 4_194_304` and `MAX_STRIP_THUMB_PX * 4 == 16 * 1024 * 1024`. Then the
   ceiling, through `admitted` alone (no document, no render): a 240-frame board at density 3 →
   **240 admitted, 0 refused**, total `240 × 17 424 = 4 181 760` (12 544 under the cap); a 241-frame
   board → **240 admitted, 1 refused**, and the refused cell is index **240**. Both derivations in the
   comments, including the division `4 194 304 / 17 424 = 240.7199…`. **A 241-frame board is a VALID
   document** — `DocOps.kt:77-88` caps nothing about frame count — so the fixture is not a broken one.
   **And the same two boards must come out of `render`**, with the same `admit` flags, or `render` is not
   using `admitted`.
6b. **`theCapIsHitExactlySoThatGreaterThanAndGreaterOrEqualDiffer`** — **the fixture that makes
   `>` vs `>=` testable at all**, and it exists because the density-3 fixture cannot: `17 424 = 2³ × 3² ×
   11²` and `4 194 304 = 2²²`, so no density-3 cell total can land on the cap and both operators refuse
   cell 240 identically. So use a density where the cell is a power of two: **`density = 16f / 11f`**,
   which gives `tickPx = 44 × 16/11 = 64.000002` → `roundHalfUp` → **64**, so every one-tick cell is
   `64 × 64 = 4 096 = 2¹²` px, and `2²² / 2¹² = 2¹⁰` = **1 024 cells exactly on the cap**.
   Assert: a 1 024-frame board has **all 1 024 admitted** and its running total is **exactly
   4 194 304**; a 1 025-frame board has **1 024 admitted and cell 1 024 refused**. A `>=` implementation
   refuses cell **1 023** and the test says so. The comment derives 64 from 16/11 and notes that 16/11 is
   **not** a dyadic rational — the exemption M6 asks for, justified here because the product lands
   within 2e-6 of 64 and the rounding boundary is 0.5 away, which is five orders of magnitude of headroom.
   1 024 `Frame` objects in a list is nothing, and because this goes through `admitted` it never renders.
7. **`theBudgetRefusesTheCellThatBustsIt`** — 30 one-tick frames at density 3 with a **211**-tick frame
   at **index 30**: the first 30 are admitted (`522 720` px), cell 30 is refused with the budget sentence,
   and the sentence contains `"3676464"` and `"4194304"`. Derivation: `211 × 132 = 27 852`,
   `27 852 × 132 = 3 676 464`, and `522 720 + 3 676 464 = 4 199 184` is 4 880 **over** the cap. The
   control case is the same board with **210** ticks instead: `210 × 17 424 = 3 659 040`, total
   `4 181 760`, **nothing refused**. Note in the comment that `30 + 211 = 241` ticks, which is why this
   and test 6's 241 are the same number reached two ways — an internal consistency a builder can check
   for themselves.
8. **`aRefusedCellSpendsNoBudgetAndTheStripKeepsFilling`** *(rewritten — the first version of this test
   asserted the wrong thing, and the cross-review is right that it did)* — the **999**-tick frame at
   **index 0**, then **240** one-tick frames, density 3. Cell 0 costs `999 × 17 424 = 17 406 576`, which
   is over the cap on its own, so it is **refused and the running total stays 0**. Then cells 1..240 at
   `17 424` each reach `240 × 17 424 = 4 181 760`, which is under, so **all 240 are admitted**.
   Two assertions and a third control:
   - cell 0 is `Refused` and its reason is the budget sentence naming `"17406576"`;
   - cells 1..240 are **all** `Ready` — *this is the "the walk continues" clause. A loop that stops at
     the first refusal admits NOTHING and this is where that is caught;*
   - the admitted total is **4 181 760 px**, i.e. 99.7 % of the budget, *and* the admitted cells' own
     pixels are what filled it — *this is the "a refused cell spends nothing" clause. An implementation
     that added the refused cell to the running total would admit 0 of the 240, because
     `17 406 576 + 17 424` is over the cap before the first one.*
   *(The first draft of this test used a 211-tick cell at index 0 and asserted a 522 720 px admitted
   total, which is only true under a **per-cell** predicate — and a per-cell predicate is not a budget,
   so the test asserted a wrong rule. The 999-tick cell is used here precisely because it is refused
   under either reading, so the test cannot be satisfied by the wrong one.)*
8b. **`theAdmittedTotalDependsOnStripOrderAndThatIsTheProperty`** — the **same 31 cells** as test 7, with
   the 211-tick frame at **index 0** instead of index 30. `admitted` gives: cell 0 **admitted**
   (`3 676 464` ≤ cap), cells 1..29 admitted (`29 × 17 424 = 505 296`, total `4 181 760`), cell 30
   **refused**. Assert the admitted total is **`4 181 760`**, and assert side by side that test 7's
   ordering of the same cells admits only **`522 720`**. The comment says why the property is kept rather
   than designed away: the strip fills from the left, so the refusal is always at the right-hand end of
   what the budget holds, and that is predictable; a size-sorted admission would admit more pictures and
   leave holes nobody could explain. **It is also part of Q1.**
   a budget that is 99 % empty. *This is the test for Decision 6's third clause:* a loop that stops at
   the first refusal admits **nothing** and this is where it is caught. The comment says so.
9. **`oneRenderServesEveryFrameThatShowsTheSameCel`** — fixture A: all four frames map to `c-anim` and
   the static layer is frame-independent, so **one** `PictureKey` and **one** render. `counting.asked`
   has exactly **2** entries (one per visible PAINT layer, tile 0_0) and there are 4 `Ready` cells. The
   comment names the wrong number: a row that rendered per cell asks **8**, and the sizes differ per
   cell, so a row that shared the *bytes* instead of the *render* would be caught by test 21.
10. **`twoCelGroupsCostTwoRenders`** — f0, f1 → `c-a`; f2, f3 → `c-b`. Two keys, so `asked.size == 4`.
    `c-a`'s tile is opaque red and `c-b`'s is opaque blue, and the test asserts f0's first pixel is red
    and f2's is blue: **a share that handed back the wrong group's picture is a wrong picture**, and
    only a two-colour fixture can see it. The comment says so.
11. **`aThumbnailAtCellSizeIsTheRegionsOwnAnswerByteForByte`** — **the strongest single test in the
    file, and it is stronger than the first draft of it was.** A 44 × 44 board, a one-tick frame,
    density 1: `dstW = 44 = srcW` and `dstH = 44 = srcH`, so every weight is exactly `1.0`, `total` is
    exactly `1.0f`, the mean is bit-for-bit the source, and the downscale is the identity — including
    the two divisors, because `mean == src` means `mean[3] == src[3]` and `k` is computed from the same
    alpha `RegionRenderer` computes. Assert
    `assertContentEquals(RegionRenderer.render(doc, tiles, RectPx(0, 0, 44, 44), "f0", paper), ready.rgba)`
    and `ready.rgba.size == 7_744`.
    **The source is NOT uniform-alpha, `paper` is `null`, and that is the correction the cross-review
    forced.** The tile is `paper`-free and holds: pixel 0 **opaque red `(255, 0, 0, 255)`**, pixel 1
    **half-transparent blue PREMULTIPLIED `(0, 0, 128, 128)`** — i.e. premultiplied
    `(0, 0, 0.50196, 0.50196)` — and the remaining 1 934 pixels `(0, 0, 0, 0)`. So the alpha runs from 1
    to 0. **`paper` is `null` here, and passing `"#ffffff"` instead would destroy the whole point**:
    `RegionRenderer.kt:237-246` fills the scratch at alpha 1 before compositing, so a `NORMAL` composite
    gives `src + dst × (1 − sa)`, alpha `1 + 1 × (1 − sa) = 1` for **every** pixel, and the fixture is
    uniform again. Assert
    `assertContentEquals(RegionRenderer.render(doc, tiles, RectPx(0, 0, 44, 44), "f0", null), ready.rgba)`
    and `ready.rgba.size == 7_744`.
    **Assert the two interesting pixels explicitly as well**, because an identity assertion alone does not
    say what they are: pixel 0 is `(255, 0, 0, 255)` and pixel 1 is `(0, 0, 255, 128)` — **the blue is
    255, not 128**, and an implementation that divided all four channels by `total` gives **128** there.
    The comment says: *"at 1:1 the two normalisation rules coincide only when the alpha is uniform, so a
    uniform-alpha fixture would let the one wrong rule in this file's whole filter through; this fixture
    is deliberately uneven."*
    **What this pins:** the private `toByte255` against `RegionRenderer`'s, the row order against the
    landed render's, **and** the un-premultiply. A drift in either copy of the two lines, or one
    divisor instead of two, turns it red.
12. **`theFilterIsAnAreaAverageAndNotTheNearestPixel`** *(rebuilt on a uniform-alpha source on purpose,
    and the comment says so)* — a 4 × 1 board, source row **opaque black `(0,0,0,255)`, opaque black,
    opaque black, opaque white `(255,255,255,255)`**, `paper = null`, one-tick cell at `density =
    3f / 44f`, so `wPx = 3` and, because the cell is square, **`hPx = 3`** — the thumb is **3 × 3** and
    the source is **4 × 1**, which is the point: **this test and test 13 are the only coverage of the
    VERTICAL axis.** Horizontal spans: `[0, 4/3)`, `[4/3, 8/3)`, `[8/3, 4)`. Dest 0 = `(0 × 1 + 0 × 1/3)
    / (4/3)` = **0**; dest 1 = `(0 × 2/3 + 0 × 2/3) / (4/3)` = **0**; dest 2 = `(0 × 1/3 + 1 × 1.0) /
    (4/3)` = **0.75** → `(0.75 × 255 + 0.5).toInt()` = **191**. So **all three rows are
    `(0, 0, 0, 255)`, `(0, 0, 0, 255)`, `(0, 0, 191, 255)`** and every one of them is asserted, not
    just the first: each vertical span is `[k/3, (k+1)/3)` of the ONE source row, weight `1/3`, total
    `1/3`, so each output row is the source row.
    **The wrong numbers, all three named in the comment:** nearest gives `(0, 0, 255)` in the third
    column — **and note it is the SECOND source pixel's index that is 2, which is black, so "take the
    first pixel of the box" gives 0, not 255** — "sample the centre" gives `(0, 0, 0)`, and a transposed
    output index gives the columns in the wrong rows.
    **And the comment must say that this fixture's alpha is uniform, so it CANNOT see the
    un-premultiply.** Its job is the weights. Tests 11, 12b and 14 have the uneven alpha.
12b. **`oneThirdOfARedStaysARedAndNotADarkRed`** *(NEW, and it is the test that makes the wrong divisor
    impossible to ship)* — a 4 × 1 board, source row **opaque red `(255,0,0,255)` × 3, then fully
    transparent `(0,0,0,0)`**, `paper = null`, to a 3 × 3 cell at `density = 3f / 44f`.
    Dest 2's box is `[8/3, 4)`: source pixel 2 with weight `1/3` and source pixel 3 with weight `1.0`, so
    `total = 4/3`, `acc[0] = 1 × (1/3) + 0 × 1.0 = 0.33333334`, `acc[3] = 0.33333334`.
    **Correct:** `inv = 0.75`, `a = 0.33333334 × 0.75 = 0.25`, `k = 1 / 0.25 = 4.0`, so
    `out[0] = toByte255(0.33333334 × 0.75 × 4.0) = toByte255(1.0) = (255.5).toInt() = **255**`, and
    `out[3] = toByte255(0.25) = (63.75 + 0.5).toInt() = **64**`. So the third column is
    `(255, 0, 0, 64)` — a third of the box is opaque red and two thirds is nothing, so the average is
    **64 alpha of FULL red**.
    **Wrong, and this is the one-line fix that turns a full red into a dark one:** dividing all four
    channels by `total` gives `out[0] = toByte255(0.33333334 × 0.75) = (63.75 + 0.5).toInt() = **64**`,
    i.e. `(64, 0, 0, 64)` — the same alpha, a third of the colour, which is precisely the
    "premultiplied labelled straight" failure. **The two answers differ by a factor of four in one
    channel and the alpha is identical**, so a test that checked only the alpha would pass against both.
    Also assert the first two columns are `(255, 0, 0, 255)` (both sources are opaque red there).
    *This test also pins the `+ 0.5f`: truncating instead of rounding gives 63 for the alpha byte.*
13. **`theFilterHandlesUpscalingBecauseASmallBoardOnADenseScreenIsOne`** — a 2 × 1 board, source
    **opaque black `(0,0,0,255)`, opaque byte-64 grey `(64,64,64,255)`**, `paper = null`, to a 3 × 3
    cell at `density = 3f / 44f` — an **upscale**, since the cell is wider than the board. Spans
    `[0, 2/3)`, `[2/3, 4/3)`, `[4/3, 2)`. Dest 0 = the whole of source 0 → **0**. Dest 1 =
    `(1/3 × 0 + 1/3 × 64/255) / (2/3)` = `192/1530` = `32/255` → `(31.99997 + 0.5).toInt()` = **32**.
    Dest 2 = the whole of source 1 → **64**. So **all three rows are `(0,0,0,255)`, `(32,32,32,255)`,
    `(64,64,64,255)`** and `rgba.size == 36`. Nearest gives `(0, 0, 64)` or `(0, 64, 64)`. The comment
    says why the middle is 32 and not 64: each source pixel overlaps it by exactly a third, so it is the
    mean of the two — **and it says the alpha here is uniform, so this test pins the upscale WEIGHTS and
    not the un-premultiply.**
14. **`averagingHappensOnPremultipliedPixelsSoThereIsNoBlackFringe`** — a 2 × 2 board whose cells are
    **(128, 0, 0, 128) premultiplied** — i.e. 50 % red — and three **(0, 0, 0, 0)**, into a 1 × 1 cell,
    `paper = null`. Correct: `total = 4`, `acc = (0.50196, 0, 0, 0.50196)`, `inv = 0.25`, so
    `a = 0.12549`, `k = 1 / 0.12549 = 7.96875`, red comes back at `0.12549 × 7.96875 = 1.0` → **255**,
    and alpha is `(0.12549 × 255 + 0.5).toInt()` = **32**. So **(255, 0, 0, 32)**.
    **Two wrong answers, both named in the comment with their own arithmetic:**
    (a) dividing all four channels by `total` gives `out[0] = toByte255(0.50196 × 0.25) = **32**` →
    `(32, 0, 0, 32)`; (b) averaging the STRAIGHT bytes — what a builder gets by calling `render` instead
    of `renderPremultiplied` — gives the straight render `(255,0,0,128)` and `(0,0,0,0)` per pixel, whose
    mean is `(63.75, 0, 0, 32)` → **(64, 0, 0, 32)**. "The red is 32 or 64 and it should be 255" is the
    whole black-fringe bug, and a person needs to know it is 32 and 64 and not a fudge.
15. **`fourColoursBecomeTheirExactMean`** — a 2 × 2 board of **opaque red, green, blue, white** into
    1 × 1. Every weight is exactly `1.0` and the divisor exactly `4.0`, so every sum is exact: each
    channel is `(1+0+0+1)/4 = 0.5` and alpha is `1.0` → **(128, 128, 128, 255)**. The comment must say
    **why 128 is safe here and was avoided elsewhere**: `0.5 × 255 = 127.5` exactly and `+0.5` gives
    exactly `128.0`, whereas an inexact `1/3` lands at `127.99999` and truncates to 127. A future reader
    who swaps in a mean that is not a power of two will get **127** and think the code is wrong.
16. **`anInkLayerRefusesItsCellInWordsAndAsksForNothing`** — fixture A with `l-anim`'s kind changed to
    `LayerKind.INK` and a `Cel` with no tiles (which is what rule 8 requires). **All four cells** are
    `Refused`, every reason contains `"l-ink"`, and `counting.asked.size == **0**` — the refusal is
    decided before any render, so a strip full of ink costs nothing.
17. **`aBoardTooBigForOneRenderRefusesEveryCellWithItsOwnSentence`** — a 4096 × 4096 animation board
    (`16 777 216` px, which is `2 × MAX_REGION_PX`). Every cell is `Refused`; the reason contains
    `"16777216"` and `"8388608"`; `counting.asked.size == 0`; and the reason does **not** contain
    `"Export"` (Decision 8: the landed sentence's instruction is wrong for a strip, and this is what
    keeps it out).
18. **`aCellWithNoSizeIsRefusedRatherThanGivenAZeroSizedBitmap`** — fixture A with
    `FilmStrip(doc.board(), density = 0f)`: every cell's size is 0 × 0, every cell is `Refused`, the
    reason contains `"0 by 0"`, and **no `Ready` exists at all** — so a zero-length bitmap cannot have
    been produced. The same for a `NaN` density, and the control: at density 1 the same board is all
    `Ready`.
    **And `density = -1f`, which is the case the first draft of this test missed and the cross-review
    caught.** A negative density gives `wPx = hPx = -44`, so the size is `-44 × -44` and
    **`ThumbSize.px` is `wPx * hPx` = a POSITIVE 1 936**. **The refusal therefore cannot be written
    against `.px`** — every such cell looks perfectly affordable — and the check must be
    **`wPx < 1 || hPx < 1`**, on the two sides, exactly as the contract says. Assert: at density −1 every
    cell is `Refused` with a reason containing `"-44 by -44"`, and the comment says *"`px` is the product
    of two negatives and says nothing is wrong; that is the whole reason the check is on the sides and
    not on the area."* Comment also: a clamped 1 × 1 would be a picture of a cell that has no picture.
18b. **`theChecksRunSizeThenBudgetThenInkThenBoard`** *(NEW — the cross-review is right that two
    sentences were reachable for one cell and that nothing pinned which one)* — one fixture that is
    **degenerate AND on an INK layer AND over budget**: fixture A with `l-anim` made `LayerKind.INK`,
    at `density = 0f` (so every cell is 0 × 0, which is also refused by the budget since `0 ≤ cap` is
    true but the size check comes first). Assert: every cell's reason contains `"0 by 0"` and
    **does not** contain `"l-ink"` nor `"4194304"`, and `counting.asked.size == 0` (nothing was asked
    for, so neither the ink nor the board check ran). The comment states the whole order — size, budget,
    ink, board — and the reason for it: **the cheapest and most local reason wins, and the two that cost
    a render to discover come last.** A second half asserts the order between ink and board: a 4096 ×
    4096 board that is ALSO on an ink layer gives the **ink** sentence, because the ink check is decided
    from the document and the board check needs the render to fail.
19. **`theKeyIsEverythingTheDocumentCanSay`** — `keyOf` for f0 on fixture A at density 1, paper
    `"#ffffff"`: `frameId == "f0"`, `size == 44 × 44`, `paper == "#ffffff"`, `rect == RectPx(0,0,128,64)`,
    `picture == PictureKey(listOf("l-static=c-static", "l-anim=c-anim"))` — **in `doc.layers` order**,
    and exactly two entries. Then four single-change mutations, each of which must change the key:
    a different `paper`; the board rect resized; the hold of f0 changed to 2 (which changes `size`); and
    f1 pointed at a different cel (which changes `picture`). And one that must **not**: adding an
    invisible layer leaves the key alone.
20. **`missingAsksOnlyForWhatTheHostDoesNotHold`** — build all four keys, hold three, and
    `missing(...) == listOf(3)`. Hold all four → `emptyList()`. Hold a key built with a **different
    paper** → that index is asked for again. Hold a key whose `size` is stale → asked for again. The
    comment says what this does **not** cover, because that is Q2 and a reader must not mistake it for
    a complete cache check: nothing here notices a stroke painted into a tile that already existed.
21. **`linkedFramesShareAPictureButNotASize`** — two frames, both showing `c-1`, holds **1** and **3**.
    One render (`asked.size == 1`), two `Ready`, and the sizes are **44 × 44** and **132 × 44** with
    `rgba.size` of 7 744 and 23 232. *A row that shared the finished bitmap between the two cells would
    hand the second cell a 44 px picture for a 132 px cell* — the test is in the fixture, not in the
    counter.
22. **`anEmptyOrRoomlessBoardIsNeverAnException`** — a board with `frames = emptyList()` →
    `render(...) == emptyList()` and `missing(...) == emptyList()`; and a board with `rect =
    RectPx(0, 0, 0, 64)` (which `DocOps.validate` rule 3 already calls broken) → one `Ready` of the
    cell's size whose every byte is **0**, i.e. fully transparent, because there is no room to render.
    `DocOps.kt:78-79` refuses an animation board with no frames, and this row is still total over one.

### `jvmTest` — `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/anim/StripThumbnailsNoSecondConstantTest.kt`

**These are here and not in `commonTest` because they use Java reflection** (`::class.java`), which is
not available in a multiplatform `commonTest` source set. (The same slip put source-level tests in
`commonTest` in JB-1.07, JB-2.04, JB-2.12, JB-2.16, JB-2.17, JB-2.23 and JB-3.04; it is a build failure,
not a style note.)

**BOTH OF THESE ARE COPIED FROM A LANDED TEST IN THE SAME PACKAGE, `FilmStripNoSecondCopyTest.kt`, and
the cross-review is right that my first drafts of both were inert.** That file's own KDoc
(`FilmStripNoSecondCopyTest.kt:148-178`) records the same mistake: asking `Companion.declaredFields` for a
`const val` is wrong, because Kotlin does not put the field there — `javap` shows it as a
`public static final` on the **enclosing** class. **Use its helpers, named as they are there:
`classesInThisFile()` and `primitiveConstantsInThisFile()`.**

**J1. `theFileOwnsExactlyOneNumberAndItIsTheBudget`** — `primitiveConstantsInThisFile()` over
`classesInThisFile()`, asserting `listOf("MAX_STRIP_THUMB_PX")`. That means:
- **every** `static final` field whose type `isPrimitive`, in **every** class the file compiles to —
  `StripThumbnails`, `StripThumbnails.Companion` if the budget is written in one, `ThumbSize`,
  `CellThumb`, `CellThumb.Ready`, `CellThumb.Refused`, `PictureKey`, `ThumbKey`, and the **file facade
  `StripThumbnailsKt`**, which is what a top-level `const val` lands in. The facade is fetched with
  `runCatching { Class.forName("cc.joycreator.joybrush.core.anim.StripThumbnailsKt") }.getOrNull()` and
  appended when present, exactly as `FilmStripNoSecondCopyTest.kt:263-264` does, because a test that
  could not compile on an *absent* class is a worse failure than one that scans one class fewer.
- **This is the edit J1 exists for, and the census is the assertion:** `const val MAX_CELL_PX = 64f`
  added in the companion, at the top of the file, or as a class-level constant is a static final
  primitive in one of those classes, and all three are caught. Assert each survivor is `Long`, and
  `Modifier.isStatic && Modifier.isFinal`, and that the value is `4_194_304`.
- **Why not `declaredFields`:** `StripThumbnails` is an `object`, so its `declaredFields` is
  `[INSTANCE, MAX_STRIP_THUMB_PX]` and "exactly one field" is red on arrival — which is precisely the
  failure the landed file documents.
- **And a name check over every field of every class in the file**, as the landed one does: no field may
  be called `MAX_CELL`, `MIN_THUMB`, `MAX_HOLD`, `CELL_HEIGHT` or `MAX_PIXELS`, because each of those is
  a number this class has stopped asking somebody else about. The list is by exact name and not by the
  words "max"/"cell"/"px", because those are the contract's own vocabulary — `ThumbSize.wPx` and
  `CellThumb.Refused.reason` are in the spec and a rule that forbade them would forbid the contract.
- **The claim that cannot be made, stated rather than implied:** a copy that is neither `const` nor
  `static` is not catchable by reflection at all. The honest place for that is the behaviour, and tests
  6, 6b, 7, 8 and 8b are it — they assert the budget's *effect*, not its name.

**J2. `theFileNeverAsksAClockAndNeverNamesAPhone`** — the same three layers the landed test uses, and
all three are needed, because each catches a different edit:
1. **Signatures and field types**, over every class in `classesInThisFile()`, with the landed
   `assertNoClock`-style reachability walk (it has a `theTypeWalkCanStillSeeTheClock` self-test proving
   the walk descends generics — **port that self-test too**, or the walk is a tripwire nobody has proved
   can fire). The names checked are **`PlaybackClock`, `nextChangeMs` and `FrameStepper`**, and they are
   falsifiable here because **all three are in `core.anim`, this file's own package** — a redundant
   import of a same-package class is itself proof somebody meant to use it, which is the landed test's
   own point.
2. **The class file's own constant pool**, read as ISO-8859-1 one byte per character via
   `classFileText(klass)` — the landed helper at `FilmStripNoSecondCopyTest.kt:487-502`, read out of the
   code source the class was LOADED from and failing loudly if it is not there rather than falling back
   to some other copy. **This is the layer that catches a forbidden call inside a method body**, which
   my first draft missed: `render` calling `FrameStepper.frameIndexAt` and throwing the answer away
   leaves no trace in any signature. It also catches `Bitmap` and `android.graphics.Canvas`, which is
   the layer that matters, because those are exactly the imports J1 cannot see.
3. **The source file's non-comment lines**, via
   `File(joybrushRoot(), "core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/StripThumbnails.kt")` —
   `joybrushRoot` is `internal` in `core/src/jvmTest/.../brush/DefaultPresetsTest.kt` and is
   `import`able, as the landed test does. Assert the file exists (itself worth asserting), that no
   `import` line names a forbidden type, and that **no non-comment line** does — which is a strictly
   stronger form and is what the landed test does for the same reason: "a grep for the word would be a
   test that forbids the documentation of the rule."
   **THE NAMES CHECKED ARE `PlaybackClock`, `nextChangeMs`, `FrameStepper`, `Bitmap`, `Canvas` and
   `android.` — and `GlPaintEngine`/`JbCanvasView` are DELIBERATELY NOT LISTED.** They live in
   `androidkit`, which `core` does not depend on, so naming them would be a test that cannot fail: the
   compiler already forbids it. **A check that cannot fail is not a check, and listing one would be a
   false claim about coverage.** The entries with teeth are `Bitmap`, `Canvas` and `android.`.

**Non-vacuity the builder must run and paste.** Each mutation, and the test that must go red:

**TWELVE, and the cross-review found that three of my first ten could not redden anything. Those three
are replaced, not reworded, and the replacements say what number each one produces.**

1. Replace the area average with nearest-neighbour (take the source pixel at the box's left edge, weight
   1) → **test 12** red. The box's left edge for dest 2 is at `8/3`, so `floor(8/3) = 2` and **pixel 2 is
   the black one** — the wrong answer is **`0`, not 255**. *(The first draft of this line said 255 and was
   wrong; taking the first pixel of the box picks index 2, and this row's source is black, black, black,
   white.)* Also **test 12b** red on the alpha byte (**255** where it says 64) and **test 13** red.
2. Render per cell instead of per `PictureKey` → **test 9** red: `asked.size` is 8, not 2.
3. Share the finished `rgba` between two cells of the same `PictureKey` → **test 21** red on the size.
4. Stop the admission walk at the first refusal → **test 8** red: cells 1..240 are not admitted at all.
5. **Add a refused cell's pixels to the running total** (the other half of Decision 6's last line) →
   **test 8** red: `17 406 576` is charged before cell 1, so **0 of the 240** are admitted.
6. `<=` → `<` in the predicate (the off-by-one the other way) → **test 6b** red: the 1 024th cell is
   refused where it says admitted. *(This is the mutation the density-16/11 fixture exists for; at density
   3 no cell total can land on the cap, so `>` and `>=` — and `<` and `<=` — behave identically there and
   the test would be vacuous.)*
7. Build the source with `RegionRenderer.render` (straight) instead of `renderPremultiplied` →
   **test 14** red with **64** where it says 255, and **test 11** red.
8. **Divide all four channels by `total` instead of un-premultiplying by the mean alpha** — *this is the
   BLOCKER-2 bug itself, run as a mutation* → **test 11** red with blue **128** where it says 255,
   **test 12b** red with red **64** where it says 255, and **test 14** red with red **32** where it says
   255. Three tests, three numbers, all from fixtures with non-uniform alpha.
9. **Drop the `+ 0.5f` from `toByte255`** (truncate instead of round) → **test 12b** red with alpha
   **63** where it says 64. *(The first draft of this mutation was "change `+ 0.5f` to a `round`, or
   `coerceIn` to a cast", and **both halves were inert**: at uniform alpha only `b/255` is ever
   converted, so `floor(b + 0.5)` and `round(b)` both return `b`; and `coerceIn` is unreachable from a
   valid premultiplied source — see the note beside the pseudo-code. Both claims are withdrawn and the
   replacements are named above.)*
10. **Transpose the destination output index** — write `di = (dx * dstH + dy) * 4` instead of
    `(dy * dstW + dx) * 4` → **tests 12, 12b and 13** red, because those three are the only fixtures with
    a non-square source. *(The first draft of this mutation was "swap the two destination loops to `dy`
    outer", which is **exactly what the pseudo-code already says** and is therefore a no-op. A transposed
    OUTPUT INDEX is a real edit that a real builder makes.)*
11. Move the ink check to after the first render → **test 16** red on `asked.size`.
12. Make `undrawableLayerOf` return a layer only when exactly one layer is included → **test 16** red with
    `"l-ink"` absent from the reason.

A helper that has never been seen wrong is a helper nobody can rely on.

**Command:** `./gradlew -p joybrush :core:jvmTest` — `BUILD SUCCESSFUL`, 0 failures.

## Do not

- **Do not call `RegionRenderer.render`.** Call `renderPremultiplied`. The straight door throws away the
  information a box filter needs and produces a black fringe; test 14 is the proof and the numbers are
  in it.
- **Do not merge Decision 4's two divisors into one.** The mean is `acc × (1/total)`; the COLOUR is then
  `mean[c] / mean[3]` and the ALPHA is `mean[3]` unscaled. Dividing all four by `total` returns a
  premultiplied array labelled straight — the fringe — and on a uniform-alpha fixture it is invisible,
  which is why tests 11, 12b and 14 carry uneven alpha. Three numbers, in the table beside the
  pseudo-code.
- **Do not write your own filter.** Nearest, bilinear and "sample the centre" are all wrong for a
  minification, and the pseudo-code above is the whole of it. The `Double` spans, the pinned `Float`
  accumulation order, the `continue` on a non-positive weight and the un-premultiply **once at the
  end** are all load-bearing.
- **Do not compute the span products in `Int` or in `Float`.** `2²² × 2²³ = 2⁴⁵` needs `Double`; `Int`
  is the wrap this project keeps meeting at a seam.
- **Do not add a layout constant.** The height is `strip.tickPx` and the width is `strip.cellWidth(i)`.
  A `CELL_HEIGHT_DP` here is a second copy of `TICK_PX_DP` that nothing can catch. **J1**.
- **Do not restate `MAX_REGION_PX`,** import it. And do not type `4194304` or `16` in a message —
  compute both from `MAX_STRIP_THUMB_PX`.
- **Do not make the predicate per-cell.** `cellPx <= cap` with no running total is not a budget: every
  cell of a 300-frame board passes it and the strip retains 20 MiB against a 16 MiB cap. Cumulative, in
  strip order, and **do not charge a refused cell to the total**. Do not sort the cells by size either —
  the strip fills from the left, which is predictable, and a sorted admission leaves holes nobody can
  explain. Tests 6, 6b, 7, 8 and 8b are the five ways of getting this wrong.
- **Do not clamp a size under 1 px up to 1 px,** and do not clamp a hold. **And check `wPx < 1 ||
  hPx < 1`, never `.px < 1`**: a negative density gives `-44 × -44`, whose `.px` is a healthy positive
  1 936. Test 18 pins both.
- **Do not catch `IllegalArgumentException`.** Three of them reach out of `RegionRenderer` — a negative
  rect (`:358`, reachable from a hand-edited file), a short tile (`:287-289`) and a `paper` that is not
  a colour (`:433-436`) — and all three are caller or storage bugs that must stay loud. **Only
  `RegionException` is caught**, and only around the `renderPremultiplied` call.
- **Do not add a field to `DocModel` or `Cel`** for a revision counter. That is R30 item 3 (versions
  assigned at landing, never in a spec) and R31, and it is Q2 — a question, not a field.
- **Do not touch `FilmStrip.kt`.** It is JB-3.03's, landed and xr-cleared. This row is called by it and
  calls it. In particular do not "helpfully" add a cell height to `FilmStrip` — the height lives here,
  and moving it is the Lead's (Q5).
- Do not touch `RegionRenderer.kt`, `AnimOps.kt`, `DocModel.kt`, `DocOps.kt`, `GlPaintEngine.kt`,
  `JbCanvasView.kt` or `JoyBrushActivity.kt`. They are called, never changed.
- **No `Bitmap`, no `Canvas`, no `Context`, no `Handler`, no thread, no coroutine, no `Looper`, no
  `Dispatchers`.** The answer to "does this row need a view?" is no, and this is what makes that true.
- **Do not assert a millisecond anywhere.** Report them; count tile fetches. R28.
- **Do not use a density that is not a dyadic rational in a filter test — with one stated exemption.**
  `1.1f × 44f` is not exactly 48.4 and the expected byte becomes a rounding accident. `3.0625f`, `2.5f`
  and `1 + k/64f` are exact.
  **THE EXEMPTION, because the cross-review caught this spec breaking its own rule twice:** tests 12,
  12b and 13 use `density = 3f / 44f` and test 14's cell is one tick, and none of those is dyadic. The
  rule that is actually wanted is **"not inexact in a way that reaches the expected byte"**, and the
  test is that: `44f × (k/44f)` lands within **1e-7** of `k` and the rounding boundary is **0.5** away, so
  the product rounds to exactly `k` with six orders of magnitude to spare. Test 6b's `16f / 11f` is the
  same argument with the same margin (`64.000002` against a boundary at 64.5). The rule is therefore
  *dyadic where you can, and state the margin where you cannot* — and a fixture with no margin stated is
  a fixture whose expected value is an accident.
- **Never expect the byte 128 from an inexact mean.** `1f/3f` lands at 127.99999 and truncates to 127.
  A fixture that produces an exact 0.5 is fine (test 15 does, and its comment says why it is safe); one
  that produces an approximate 0.5 is a badly chosen fixture, and "fixing" the expected value to 127
  hides a real change.
- **Do not pass a `paper` in ANY test that has to see the un-premultiply** — which is every one of
  them, 11 through 15. A `paper` backdrop fills the scratch at alpha 1 (`RegionRenderer.kt:237-246`), so
  every pixel comes out opaque, the alpha is uniform, and the two divisors of Decision 4 coincide again.
  It also changes the answers: **test 14 would be `(255, 223, 223, 255)`** instead of `(255, 0, 0, 32)`.
  **Every filter test and test 11 pass `paper = null`.** There is no test in this file that passes a
  paper, and a builder who adds one to "see what the strip looks like on white" has just made the
  fixture unable to see the bug. And **do not write a "black" as `(0, 0, 0, 0)`** — that is not black,
  it is absent, and test 13's middle pixel becomes 64 instead of 32. Blacks are `(0, 0, 0, 255)`.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the **twelve** non-vacuity runs pasted, each with the name of the test that went red and the number
      it went red **with**
- [ ] `git status --short` shows **only** the three owner-area paths
- [ ] committed `JB-3.03b: film-strip thumbnails`, pushed
- [ ] **The ROADMAP row is the orchestrator's, not yours — do not edit `tasks/joybrush/ROADMAP.md`.**
      `specs/INDEX.md` was retired ("# Moved"), so there is no index line to update; report the new
      status in the build report and the orchestrator sets it.

## Stop rule

**Stop, set the row `⛔ Blocked`, and write the question in this file's Questions section — do not guess
and do not widen the owner area — if any of these happen:**

1. A signature in the "Contract" section does not match the landed file it is pasted from
   (`FilmStrip.tickPx`, `FilmStrip.cellWidth`, `RegionRenderer.renderPremultiplied`, `MAX_REGION_PX`,
   `DocOps.celFor`, `Board`, `Layer`, `Cel`). The contract is authoritative over this spec and this spec
   is wrong.
2. A test's expected value cannot be derived from the numbers in this spec. That means a decision is
   missing, and a builder must not invent one.
3. Making something pass would need an edit outside the three owner-area paths — **especially**
   `FilmStrip.kt`, which is JB-3.03's, or `JoyBrushActivity.kt`, which R30 item 1 reserves.
4. Something here turns out to need an Android type, a `View`, a `Bitmap`, a density READ or a file.
   That means the scope is wrong, and the answer is a question, not an import.
5. `DocOps.celFor` or `RegionRenderer` behaves in a way the four refusals do not describe. Report it;
   do not repair it.
6. A number in the "scale section" above does not come out. Every one of them is a multiplication shown
   in full — recompute it, paste the recomputation, and say which one is wrong. A wrong number here is
   the expensive kind of wrong.

## Questions

_(Spec writer: `openrouter/stealth/space-bunny-alpha`, 2026-09-30, **revised after the round-1
cross-review**. **No question below blocks the build.** The core half is decided and pinned by **26
`commonTest` cases** (22 numbered plus 6b, 8b, 12b, 18b) and 2 `jvmTest` ones, plus **twelve** non-vacuity
runs. Q1, Q2 and Q3 are the Lead's and are about what a person sees or what a contract would have to
become; Q4, Q5 and Q6 are confirmations.)_

### Q1 — for the Lead: a long-held cell cannot have a picture, and the strip fills from the left

**(a) The scale finding, which is unchanged and re-derived.** At density 3 a 999-tick cell is
`999 × 132 × 132 = 17 406 576` px — **4.15× the whole strip budget**, 66.4 MiB on its own. The same cell
at density 1 is `1 934 064` px and fits comfortably, so the answer changes with the screen and not with
the drawing. The reachable case is not only 999: on a **30-cell** strip at density 3, a frame held
**210** ticks still fits and **211** does not (210 ticks is 17.5 s at 12 fps).

**(b) The correction the review forced, and it is a correction to the QUESTION, not only to the spec.**
The first draft of this question said "a frame held 210 ticks still fits and 211 does not" as though
that were a property of the hold. **It is a property of the hold AND the position AND the size of
everything else on the strip**, because the predicate is cumulative (Decision 6). The same 31 cells —
30 one-tick and one 211-tick — admit `522 720` px with the held frame **last** and `4 181 760` px with it
**first**, and the single-frame boundary at density 3 is **241** ticks, not 211. **Asking the Lead to
rule on "211" would have been asking about a number that the design does not own.** So the question is
now about the *policy*, with the numbers as illustrations.

This does not contradict JB-3.03. Its Decision 4 caps nothing about the drawn width, and the cell is
still 43 956 dp wide and still draws its number — Decision 6 refuses the **picture**, and the cell falls
back to the numbered cell R33 (c) already gave the strip. But it is worth a ruling, because the headline
feature of the row before this one is the case this row refuses a picture for.

**(c) The two things I need ruled, and they are separable:**

1. **Is "a long-held cell is a numbered cell" acceptable, or should the PICTURE be capped and
   left-anchored?** The alternative: a picture is at most `PICTURE_MAX_W_PX` wide and is anchored to the
   cell's **left cap** (JB-3.03's Cut Decision C5 already reserves the left cap and forbids anything
   living at the right edge). Cost: one constant; `sizeOf` stops being `round(cellWidth(i))` and becomes
   `min(round(cellWidth(i)), PICTURE_MAX_W_PX)`; every derived number in the scale section changes; and
   it is a **visual** decision — what a long-held cell looks like with a small picture in its left cap and
   empty space beside it — which is T2-V territory for the view half. **I have not implemented it.**
2. **Is "the strip fills from the left" the right fairness?** The alternative is a size-sorted admission,
   which would admit more pictures on the same board and leave a hole in the middle of the strip when the
   budget runs out. I chose left-to-right because a person can predict it — the refusal is always at the
   right-hand end of what the budget holds — and because it is derivable in a test without knowing
   anything about the art. **Test 8b pins the property, so changing it is a deliberate act with a red
   test rather than a quiet edit.** Either answer is one function (`admitted`) and one test.

### Q2 — ⛔ Blocked for Claude: nothing in the document can say whether a thumbnail's PIXELS are stale

**This is the row's real hole and it is a contract question, not a design preference.**

`Cel` carries `tiles: List<String>` and nothing else (`DocModel.kt:161-165`). There is no revision, no
counter, no timestamp. So a stroke painted into a tile that **already exists** changes no field of the
document at all — and that is the most common event in the app. Concretely: the person draws on frame
7, the strip is asked which cells need re-rendering, and the honest answer is "cell 7", which this row
cannot produce from the document. The derived half (Decision 10) covers the frame id, the cell size, the
paper, the board rect and the picture key — everything the file **can** say — and refers the rest.

**What closing it costs, stated so it can be weighed:**

1. A new serialised field — `Cel.paintRevision: Int = 0`, or a board-level `Board.revision` bumped by
   every write that touches a cel. **`Cel` lives in `document.json`, so R31 applies: a new field bumps
   the version, and R30 item 3 says the number is assigned AT LANDING by the Lead, never in a spec.**
2. A writer in the engine, which is **`GlPaintEngine.kt` — R30 item 2, the Lead's file, in a stated
   order.** Not a T2 row.
3. An unknown-key consequence: a file written by a build with the field and read by one without it. R31
   plus JB-0.02d (Built) now **refuse** a same-version file carrying keys this build does not know — so
   the field cannot be added without the version bump, which is the same conclusion.
4. The undo stack: a revision is a document value, so undo must restore it, or a thumbnail goes stale
   on every undo.

**Until that exists, the host must re-ask this row whenever it knows the document changed.** That is a
real sentence with a real cost, and it belongs to the view half — which is not written and is behind
JB-2.01. **I have not invented a field, and no builder may.**

### Q3 — ⛔ Blocked for Claude: ink layers refuse their cells, and drawing them is another row

Decision 7 refuses a cell whose picture includes a visible `LayerKind.INK` layer, in words, naming the
layer. That is correct and it is also a **visible gap**, because the blueprint calls ink "ideal for
animation line art" (§2) and JB-5.01 shipped the replay. `InkRaster` (`InkRaster.kt:52`) can rasterise
one stroke's dabs and `InkReplay` can produce them from a record, but **nothing in the tree composes a
whole ink layer into a region**, and there is no production caller of `InkRaster` outside tests. The
blueprint promises ink layers render "crisp at any zoom" and R37 split that into 5.01 (core, Built) and
**5.01b (the GL side, `⛔ Blocked`, Lead-only)** — so the GL side of ink is already spoken for and is
waiting.

**What closing it on the CPU would cost:** a `StrokeSource` door beside `TileSource` (the caller reads
`cel.strokesFile` out of the archive), replay per record through `InkReplay.dabs`, a per-stroke
`InkRaster.stamps` composite in drawing order, the layer's opacity and blend applied through
`RegionRenderer`'s own rules, and a per-record refusal policy for the strokes that cannot be replayed
(`InkReplay.refusal` already writes those sentences). That is a **T1 row** — it decides the composite
order and the refusal policy — and it would touch `InkRaster.kt` and `InkReplay.kt`, which are landed
and reviewed. **I have not started it and this row does not wait for it**: the refusal is in words, so
a strip with ink on it degrades to numbered cells rather than to blank ones.

### Q4 — PROVISIONAL — Claude to confirm: `MAX_STRIP_THUMB_PX = MAX_REGION_PX / 2`

The derivation, in full: `MAX_REGION_PX = 8 388 608` px; one render's live footprint is
`MAX_REGION_PX × 20 B = 160 MiB` (`RegionRenderer.kt:57-64`); this row retains `4 B/px` of straight
RGBA8, so `MAX_REGION_PX / 2 × 4 = 16 777 216 B` = **exactly 16 MiB**, which is **one tenth of one
render's peak** and **one quarter of the 64 MiB this tree already calls one decoded picture**
(`AnimExport.kt:370-395`, which derived its own budget from the same constant and said so). The
consequence is a derived, non-typed ceiling: **240 one-tick cells at density 3**, and a strip of 60
costs a quarter of it.

**The cost of being wrong: one constant, and then every number in the scale section and tests 4, 5, 6,
6b, 7, 8 and 8b move** — they are all derived from it, which is the point of deriving it. Raising it to
`MAX_REGION_PX` (which is 8 388 608 px = 32 MiB of straight RGBA8) would make the density-3 ceiling
`8 388 608 / 17 424 = 481.2…` → **481** cells, and would put the retained strip at 32 MiB alongside a
128 MiB float scratch — **160 MiB of the 256 MB per-app heap `RegionRenderer` documents, i.e. 62.5 %**,
for a chrome element. I would not. **This is a number nobody has measured on a phone**, exactly like
`BenchCases`' budget table, which says in terms that a budget that has never been measured against is a
target. Q7 is the same caveat about time.

### Q5 — PROVISIONAL — Claude to confirm: the cell is a SQUARE of one tick, and that is new

Decision 2 makes `hPx = roundHalfUp(strip.tickPx)`, so the cell is `44 dp × 44 dp` and a held cell is
`44 × hold dp` wide and 44 dp tall. **Nothing in the repository says this.** JB-3.03 has a tick and an
edge grab and no height at all; R32 lists "tick 44 dp" and nothing for the strip's height; the board's
JB-3.03b row says only "THERE IS NO SPEC FILE" and R33 says the same.

**The correction, because the first draft of this question leaned on a number that is not in the tree.**
That draft cited "30 cells is 3 960 px of width and a 132 px height" as a *board* statement, and quoted it
three times, once as "traceable to two places". A repository-wide search finds it **only inside this
spec**: it came from the dispatch brief that asked for this spec, which is not a repository artefact and
is not in the row. **And it would not have carried the decision anyway** — `3 960 = 30 × 132` follows
from a cell **WIDTH**, which R32 and `FilmStrip.kt:54` already give, and nothing anywhere fixes the cell's
**HEIGHT**. So the attribution is withdrawn and this question is the open item it should have been.

The cost of the other shapes: a cell of `44 × 32 dp` would move every derived number (a 30-frame strip
at density 3 would be `522 720 × 32/44` px, and the 240-cell ceiling would become 330), and a cell
height in dp would be a **new** constant in this file, which is what **J1** exists to catch. **I have
picked the square because it introduces no constant, not because it is a design ruling** — and the
practical consequence of that is that a builder changing it is not fighting a decision, they are taking
one.

### Q6 — for the Lead, a limitation rather than a question: a thumbnail cannot show paper TEXTURE

`RegionRenderer` takes `paper` as a `#RRGGBB` string and nothing else (`RegionRenderer.kt:167`, and its
KDoc says a colour it cannot read is **refused, never defaulted**, so there is no way to pass a
texture). `Paper` carries `textureId` and `textureScale` (`DocModel.kt:61-71`), so **a board on a
textured paper gets thumbnails with the right flat colour and no grain.** Doing it properly means
reading the grain asset, choosing a scale for a 132 px cell (at which most grain is sub-texel anyway)
and deciding whether a thumbnail should show it at all.

**Recommendation: do not.** A 132 px thumbnail of paper grain is noise, and the honest cheap answer is
the flat colour. Recorded here so nobody later reads the absence of grain as a bug.

## What this spec verified, and how — no gradle, no git

I did not compile anything and did not run git. Every signature above was **read out of the landed
file** and is cited with its line number, which is the only verification available to a spec writer and
the only one that is worth claiming:

| Claim | How it was verified |
|---|---|
| `FilmStrip.tickPx`, `cellWidth`, `TICK_PX_DP`, `EDGE_GRAB_PX_DP`, the `Board` it was built with | read at `FilmStrip.kt:51, 54, 90-94, 313, 326` |
| `RegionRenderer.render` / `renderPremultiplied` signatures, `MAX_REGION_PX = 8_388_608L`, `TileSource`, `RegionException`, `TILE_BYTES`, the 16 B/px transient, the 20 B/px peak, the 160 MiB figure, and the four refusals | read at `RegionRenderer.kt:24-26, 40, 89, 99, 164, 184, 221, 230, 286, 357-369, 386, 393-399` |
| **`RegionRenderer.kt:197-205`'s two-divisor un-premultiply**, which is what BLOCKER 2's correction copies | read at `:198-205` — `val a = p[i + 3]` at `:198`, `val k = if (a > 0f) 1f / a else 0f` at `:201`, the three colour channels at `:202-204` and `out[i + 3] = toByte255(a)` unscaled at `:205` |
| `toByte255`'s exact two lines, and that `InkRaster.kt:83-87` is a third copy of them | read at both |
| `DocOps.celFor` and the three `continue`s a picture key must mirror | read at `DocOps.kt:218-222` and `RegionRenderer.kt:265, 268, 270` |
| rule 8 (an INK cel cannot have tiles) and rule 4 (holds ≥ 1, no frame cap) | read at `DocOps.kt:77-88, 152-157` |
| `RectPx`, `Frame`, `Board`, `Cel`, `Layer` field lists | read at `DocModel.kt:40, 90-93, 97-106, 161-165, 167-186` |
| the `AnimExport` precedent for a derived budget and the 64 MiB blob ceiling | read at `AnimExport.kt:370-395` |
| the 250 ms price of a 1024² region render, used in Q7 | read at `BenchCases.kt:99-100, 134` |
| the fixture this row copies, and the four cell widths it is copied for | read at `FilmStripTest.kt:79-107` |
| **`classesInThisFile()` / `primitiveConstantsInThisFile()` / `classFileText()` / `isCommentLine()` / `joybrushRoot()`**, which J1 and J2 are copied from, and the `javap` evidence that a `const val` is a static field on the enclosing class and not on the Companion | read at `FilmStripNoSecondCopyTest.kt:148-178, 239-265, 487-508`; `joybrushRoot` is `internal` in `core/src/jvmTest/.../brush/DefaultPresetsTest.kt` and is `import`able, as the landed test does |
| every pixel count, byte count and MiB in the scale section | multiplied out in the text beside each figure; the derivations are in the tests' own comments as well, so a builder recomputes rather than trusts. **The cross-review re-derived all of them from first principles — 44²/88²/110²/132², fixture A at both densities, 30 and 60 cells, 240/241, 999 ticks, and the transient table — and found every one right except `4 194 304 / 17 424`, which I had written as 240.66 and which is 240.7199. The round-1 review's arithmetic is better evidence than my own multiplication, because it was done twice.** |
| the box-filter arithmetic for tests 11, 12, 12b, 13, 14, 15 | worked out span by span in the test comments, with the wrong answers (nearest, centre, transposed, straight-average, all-four-by-`total`) named beside the right one and, where the difference is a single byte, given as that byte |

**Not verified, and claimed as not verified:** that the file compiles (no gradle); that
`kotlin.math.max`/`min` on `Double` are available in `commonMain` in this project's Kotlin version
(they are in the stdlib's common API and `FilmStrip.kt` already imports `kotlin.math.floor` and `ceil`,
but the builder's first compile is the check); that `1.1f`-style inexact densities were not used (the
exemption and its margin are stated in the Do-not list instead); that the suite is currently green (the
board's last recorded `:core:jvmTest` counts are 718/0 for JB-3.08a and 1075/0 for JB-3.02, and several
rows have landed since — **run the whole suite, not just this file's tests**); and anything at all about a
phone, which is Q1, Q4 and Q7.
