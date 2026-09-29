# JB-0.10 — CPU benchmark harness: flood fill, tile compression, region render, archive read

| | |
|---|---|
| **Tier** | T2 (the numbers themselves are collected on the phone, which is a separate act and a separate row — see *The entry point* and Definition of done) |
| **Status** | 🟦 **Ready**, scoped to the core + androidkit harness. The **budgets are PROVISIONAL** (R39) and this very harness, run on the Note 9, is what settles them. The **entry point is an explicit follow-up**, not this row: R39 puts it in the hidden pen-diagnostics panel, and R30 item 1 puts `JoyBrushActivity.kt` fourth in a one-row-at-a-time lock order, so folding it in would block this row behind three app-file rows for the sake of a button. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha`, reviewed and revised 2026-09-29. **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — re-verified every claim below against the files, applied the review's four fixes (provisional budgets, entry point deferred, one public deflate constant, two more cases), and found three more: the deflate level is written in **two** files, not one; the two new cases need a stated **fold** so a 33 MB output cannot make the checksum a second benchmark; and the archive case's fixture must be built **outside** the timed closure or the number measures the write. |
| **Needs** | 0.05 |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/bench/Bench.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/bench/BenchTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/bench/BenchCases.kt` · NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/bench/BenchCasesTest.kt` · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt` — **one line added, one line changed** (Decision 12) · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/OraExport.kt` — **one line changed** (Decision 12) |
| **Estimated size** | ~230 lines of Kotlin, ~330 lines of tests, 3 changed lines in two existing files |

## Goal

Blueprint §3.1, the owner's performance rule: *"Phase 0 benchmarks the few heavy CPU jobs (flood
fill, tile compression, PSD writing) on the Note 9; any that miss their budget move into a small C++
library that Kotlin calls on every platform. C++ is used where a measurement says so, not by
default."*

This spec builds the **harness that produces those measurements**, and nothing else. It is not a
performance claim and it asserts nothing about how fast the phone is — it measures, prints numbers
in a form that can be pasted into a review, and compares them with a budget table the Lead owns.
The two extra cases (`RegionRenderer.render`, `JbArchive.read`) are the other two jobs on the paths
a person actually waits on: opening a drawing and exporting one.

The one thing this harness must never do is **look fast**. A benchmark whose input shrank, whose work
was optimised away, whose checksum is a constant, whose fixture was built inside the timed region,
or whose clock started before the work and stopped after the *previous* iteration reports a number
nobody can act on. Every one of those is a Decision below, and every one has a test.

## Contract (verbatim)

```kotlin
package cc.joycreator.joybrush.core.bench

/** One measured job. [run] does the work and returns a number derived from it. */
class BenchCase(val name: String, val run: () -> Long)

enum class Verdict { WITHIN_BUDGET, OVER_BUDGET, NO_BUDGET }

sealed class BenchOutcome {
    data class Measured(
        val name: String,
        val samplesMs: List<Double>,   // [repeats] entries, warm-ups already discarded
        val budgetMs: Double,
        val checksum: Long,            // fold of every run() result, so "did nothing" is visible
    ) : BenchOutcome() {
        val medianMs: Double
        val p95Ms: Double
        val worstMs: Double
        fun verdict(): Verdict
    }

    /** The work exists in the plan but not in the tree yet. NOT a pass and NOT a fail. */
    data class Unavailable(val name: String, val reason: String) : BenchOutcome()
}

object Bench {
    const val DEFAULT_WARMUP = 3
    const val DEFAULT_REPEATS = 9

    /** Runs [case] [warmup] times discarding the result, then [repeats] times keeping it. */
    fun measure(
        case: BenchCase,
        warmup: Int = DEFAULT_WARMUP,
        repeats: Int = DEFAULT_REPEATS,
        nowMs: () -> Long,
    ): BenchOutcome.Measured

    /** Middle of the sorted samples; the mean of the two middle ones when the count is even. */
    fun median(samples: List<Double>): Double

    /** Nearest-rank: the sorted sample at index `ceil(0.95 * n) - 1`. Never interpolated. */
    fun p95(samples: List<Double>): Double

    /** The whole run as plain text, one line per outcome, then a one-line summary. */
    fun report(outcomes: List<BenchOutcome>, device: String): String
}
```

```kotlin
package cc.joycreator.joybrush.androidkit.bench

/**
 * The jobs the blueprint names, plus the two the paths a person waits on, plus the budgets they are
 * measured against. Every number in [budgetMsFor] is PROVISIONAL — see Decision 7.
 */
object BenchCases {
    /** "flood-fill-2048x2048-gap0" and "flood-fill-2048x2048-gap3", two of them. */
    fun floodFill(): List<BenchCase>

    /** "tile-deflate-<level>", one — the level read from `JbArchive.ARCHIVE_DEFLATE_LEVEL`. */
    fun tileDeflate(): List<BenchCase>

    /** "region-render-<w>x<h>", two: a typical board and a 4K one. Decision 13. */
    fun regionRender(): List<BenchCase>

    /** "archive-read-<tiles>", one. The fixture is built ONCE, outside the timed closure. */
    fun archiveRead(): List<BenchCase>

    /** "psd-write" — Unavailable on this row; see Decision 6. */
    fun psdWrite(): BenchOutcome.Unavailable

    /** Everything above, in the order it is reported: fills, deflate, renders, archive, then the
     *  unavailable one last, so the report ends on the thing that is missing. */
    fun all(): List<BenchOutcome>

    /** The budget table, in ms. PROVISIONAL — the Lead owns these numbers. */
    fun budgetMsFor(caseName: String): Double

    /**
     * The one number every case's `run()` derives its result into. Sampled at a fixed stride, not a
     * full CRC — see Decision 14.
     */
    fun fold(bytes: ByteArray): Long
}
```

**The landed APIs the cases are written against**, pasted from the tree (all verified 2026-09-29):

```kotlin
// core/fill/FloodFill.kt
const val MAX_FILL_PX = 8_388_608L
data class FillOptions(val tolerance: Float = 0.1f, val gapClosePx: Int = 0, val grow: Int = 1)
object FloodFill {
    fun fill(w: Int, h: Int, rgba: ByteArray, seedX: Int, seedY: Int, options: FillOptions): ByteArray
}

// core/render/RegionRenderer.kt
const val MAX_REGION_PX = 8_388_608L
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? }
class RegionException(message: String) : Exception(message)
object RegionRenderer {
    const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4
    fun render(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): ByteArray
    fun renderPremultiplied(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): FloatArray
}

// androidkit/io/JbArchive.kt
const val TILE_BYTES = TILE_SIZE * TILE_SIZE * 4            // JbArchive.kt:31 — THE one to import
data class JbContents(
    val doc: JbDocument,
    val tiles: Map<Triple<String, String, String>, ByteArray>,
    val strokes: Map<Pair<String, String>, List<StrokeRecord>>,
    val thumbnailPng: ByteArray? = null,
)
object JbArchive {
    fun write(out: OutputStream, contents: JbContents)     // [out] is NOT closed
    fun read(input: InputStream): JbContents              // [input] is NOT closed
}

// core/doc/DocOps.kt
fun newDocument(id: String, name: String, w: Int, h: Int, ids: () -> String): JbDocument
fun validate(doc: JbDocument): List<String>
```

## Steps

1. Write `BenchTest.kt` first, from the Tests section below, and watch it fail to compile.
2. Write `Bench.kt` until it is green.
3. Write `BenchCasesTest.kt`, then `BenchCases.kt`.
4. Make the three line-edits of Decision 12 — one declaration in `JbArchive.kt`, one changed call
   site in each of `JbArchive.kt` and `OraExport.kt` — and do them **before** `BenchCases.kt` imports
   the constant, so the compiler finds the file for you.
5. Run both commands in **Tests** and paste the output.

## Decisions

1. **The harness owns no clock.** `nowMs: () -> Long` is a parameter, and the harness never calls
   anything else for time. A harness that reads a clock itself is a harness that cannot be tested
   without a wall, and the median/p95/verdict arithmetic is exactly the part most worth testing. The
   caller passes a monotonic millisecond counter (`System.nanoTime() / 1_000_000`). Each `run()` is
   bracketed by exactly two `nowMs()` calls, and the elapsed value is `(after − before).toDouble()`.
   A `before` that is not strictly less than `after` yields a **negative** sample, which is kept: a
   clock that went backwards is a fact about the run and hiding it would make the median lie. (The
   caller is expected to pass a monotonic clock; the harness does not enforce it, because refusing
   to report a run is worse than reporting it.)

2. **Warm-ups run and are thrown away; `warmup` and `repeats` below 1 are REFUSED in words.**
   `require(warmup >= 1) { "bench \"${case.name}\": warmup must be at least 1, was $warmup" }` and
   the same shape for `repeats`, both naming the case and the number. This is the project's standing
   rule (non-finite and out-of-range values are refused, never silently clamped), and here it is
   load-bearing: a `repeats` of 0 gives a median of nothing, and the natural way to render "median
   of no samples" is `0.0` — a benchmark that reports **0 ms** for a job it never ran is the single
   most expensive bug this file could have.

3. **The median is the verdict, not the p95.** `verdict() = if (budgetMs is not a positive finite
   number) NO_BUDGET else if (medianMs > budgetMs) OVER_BUDGET else WITHIN_BUDGET`. The comparison
   is `>`, so a case landing *exactly* on its budget passes. The p95 is printed because the tail is
   information, but a fill that is 380 ms usually and 900 ms when the phone is warm is a different
   conversation from one that is 500 ms every time, and the median is the number that describes what
   a person feels. (Provisional — the Lead may rule the other way; the report prints all three so
   the change is a one-line edit plus one test.)

4. **A budget that is not a positive finite number is `NO_BUDGET`, never `WITHIN_BUDGET`.**
   `budgetMs > medianMs` with a NaN budget is `false`, so a naive implementation calls a case with
   no budget "within budget" — the NaN row the whole project keeps having to add. Same discipline as
   `jb_grainLevel` (JB-1.02's F1): guard the *value*, never rely on the comparison laundering it.
   A zero or negative budget is also `NO_BUDGET`, because "this must take no time" is not a budget,
   it is a missing one. The report prints `no budget set`.

5. **The checksum is printed on every measured line.** `checksum` is the fold of every `run()`
   result, XORed (a fold, so a run that returns a different value every time still gives a stable
   report rather than a number nobody can compare). A case whose work the JIT or a future refactor
   has eliminated returns a constant, and its checksum line is the only place that shows. It is on
   the line for the same reason the review of JB-2.20a wanted a test that *partitions* an enum: a
   claim nothing can falsify is a claim that quietly stops being true.

6. **`psd-write` stays `Unavailable`, and it is neither a pass nor a fail.** The blueprint names
   three jobs; the PSD writer is **JB-2.14c, which is 🟸 Draft and not built**, so
   `psdWrite()` returns `Unavailable("psd-write", "the PSD writer is JB-2.14c and is not built")` and
   the report prints it as a `NOT MEASURED` line. **It does not return a zero, a null, or an empty
   list**, because a summary reading "6 cases, 0 failures" for a run that measured five is the lie
   this decision exists to prevent. The summary counts the three kinds separately:
   `N measured, M over budget, K not measured`. If `K > 0` the summary adds a second line naming
   them, so a reader who sees only the summary still knows a number is missing. When JB-2.14c lands,
   adding the case is a one-method addition to `BenchCases` plus one line of table.

7. **The budgets are a table in `BenchCases`, they are PROVISIONAL, and this harness is what
   settles them.** R39: *"budgets provisional."* The provisional numbers, each with its reasoning,
   so the argument can be about the number rather than a vibe:

   | case | budget | why |
   |---|---|---|
   | `flood-fill-2048x2048-gap0` | 400 ms | a tap-fill that takes half a second is a dropped frame the person sees |
   | `flood-fill-2048x2048-gap3` | 1200 ms | gap closing is three extra full-image passes; it is the fill tool's *slow* path and the one that would be noticed |
   | `tile-deflate-6` | 60 ms | per tile; a 4-tile stroke's worth of work per save, on a writer thread |
   | `region-render-1024x1024` | 250 ms | a board-sized flatten on an export path a person is waiting on; 1 048 576 px |
   | `region-render-3840x2160` | 4000 ms | the largest thing the app is realistically asked to export (8 294 400 px, 94 208 under `MAX_REGION_PX`). The work is linear in pixels, so 8 294 400 / 1 048 576 = **7.91×** the 1024² case, and 250 × 7.91 = 1 978 ms; the budget is **2× that**, deliberately loose, because this case is about the **160 MiB peak** (`MAX_REGION_PX × 20 B`) and whether the renderer needs strip-stitching on the Note 9 — not about a person waiting, and a false alarm here costs real C++ work |
   | `archive-read-16` | 400 ms | 16 tiles × `TILE_BYTES` = 4 194 304 B of deflate on the **open** path, where a person sees a spinner after tapping a file. Tens of ms is the expectation; anything past 400 ms is a visible hang |
   | `psd-write` | — | no implementation; a budget for a job nobody can run is a fiction |

   **What would settle them, exactly: this harness, run on the Note 9, with the device string the
   Note 9's own model and Android version.** Not a laptop, not the cloud, not a Pixel. The first run
   on real hardware is the measurement the table is a guess at, and the table is then rewritten
   from those numbers with the derivation kept. A budget that has never been measured against is a
   target, and this row is careful about the difference. A name that is not in the table gets
   `Double.NaN`, which Decision 4 turns into `NO_BUDGET`, so changing a budget — or adding a case —
   cannot break the harness.

8. **The report is plain text, one line per outcome, then the summary.** Layout is deterministic so
   a test can pin it: names padded to the longest name in the report, every millisecond figure
   formatted `%.1f`, no colour and no box drawing. It gets pasted into a review file by hand, and
   the person doing that should not have to strip anything.
   A measured line is
   `<name>  median <M> ms  p95 <P> ms  worst <W> ms  budget <B> ms  <VERDICT>  checksum <C>`
   with `<VERDICT>` one of `WITHIN` / `OVER` / `no budget set`. An unmeasured line is
   `<name>  NOT MEASURED  <reason>`. `device` is a caller-supplied string (the phone's model and
   Android version) and is **never guessed**: the header line is `device: <string>` and an empty
   string prints as `device: (not stated)`, because a number measured on an unnamed device is not a
   measurement of the Note 9 no matter what it says.

9. **A benchmark that misses its budget is REPORTED, never enforced.** `Bench` has no exit code, no
   exception and no failure path for `OVER_BUDGET`. The consequence — "this moves to C++" — is the
   Lead's decision made on the numbers (blueprint §3.1), and a builder cannot make it. A harness
   that throws would also make the device run a failure, and the numbers would be re-run until they
   looked good, which is the opposite of the point.

10. **The harness is pure Kotlin in `core`; the cases are JVM in `androidkit`.** The split is not
    tidiness, it is `java.util.zip`: tile compression is measured through the same deflater the
    archive uses, and that is a JVM class. The harness half therefore runs and is tested in
    `:core:jvmTest` on any machine, and only the four workloads need the JVM. Nothing here uses
    `java.*` outside `androidkit`, so the core stays portable (blueprint §3.1, the iOS door).

11. **The two flood-fill cases are the SAME image at two settings, and the sizes come from the cap,
    not from a copied number.** `MAX_FILL_PX` is 8 388 608 (2²³) and its own KDoc already costs one
    fill at that size; 2048 × 2048 = 4 194 304, comfortably inside it, and 2048 is a size a person can
    actually have painted. The image is a synthetic black-on-white line drawing built by a private
    function, so a test can assert it is byte-identical between two runs — a benchmark whose input
    could differ between runs is measuring the input. `gap0` uses `FillOptions(gapClosePx = 0)` and
    `gap3` uses `FillOptions(gapClosePx = 3)`, because gap closing allocates two more full-image
    arrays and runs two dilations and is the path that would ever be slow. Both cases return the
    count of filled pixels, so the checksum says whether the fill actually filled anything, and the
    test asserts a non-zero count.

12. **There is ONE deflate level, it is a public constant, and both zip writers read it.** Verified
    in the tree on 2026-09-29: `JbArchive.kt:207` and `OraExport.kt:230` **both** call
    `zos.setLevel(6)` with the `6` inline. The earlier draft of this spec said the duplication was
    once and pointed only at `JbArchive`; it is twice, and the two files are in the **same package**
    (`cc.joycreator.joybrush.androidkit.io`), so a single top-level constant is readable by both
    with no new import. The three-line edit, all of it in the owner area and none of it changing a
    byte of any file the app writes:
    - `JbArchive.kt`: add, beside `const val TILE_BYTES` at line 31 ——
      `/** Deflate level every zip this module writes uses. ONE number, read by `JbArchive` and
      `OraExport`; a second copy of `6` in either is a bug nothing can catch. */`
      `const val ARCHIVE_DEFLATE_LEVEL = 6`
    - `JbArchive.kt:207`: `zos.setLevel(6)` → `zos.setLevel(ARCHIVE_DEFLATE_LEVEL)`
    - `OraExport.kt:230`: `zos.setLevel(6)` → `zos.setLevel(ARCHIVE_DEFLATE_LEVEL)` (same package,
      so no `import` line)
    R39 allows this as a one-line edit per file, and this is that edit: a declaration plus the two
    call sites. `BenchCases` **imports** `ARCHIVE_DEFLATE_LEVEL` and does not carry a copy of its
    own — this is the `SizeOpacityDrag.MAX_SIZE` / `BrushValidate.MAX_SIZE_PX` trap (R19) and the
    R39 answer to it, and after this there are **zero** literals of `6` for a level in this module
    instead of two. The case is named `"tile-deflate-$ARCHIVE_DEFLATE_LEVEL"`, so the report says
    which level was measured even if the constant is later changed. A test asserts the deflated
    output is **strictly shorter** than the input, so the case cannot silently become a memcpy
    benchmark. *Do not* rewrite the prose in `OraExport`'s KDoc (line 96 says "level 6"): it is a
    sentence about a convention, the convention now has a name, and editing prose is not this row's
    work.

13. **`region-render` is a real flatten over a real `TileSource`, at two sizes, and the size is
    derived.** The `TileSource` is a `fun interface`, so the fixture is a lambda returning a
    synthetic tile built once per `(tx, ty)` by a private function the test can also call (same rule
    as the flood-fill image, and for the same reason: a benchmark whose input could differ between
    runs is measuring the input). The document comes from `DocOps.newDocument`, whose board rect is
    the case's own size, with one PAINT layer and one cel declaring every tile key the source
    serves. Sizes: **1024 × 1024** (1 048 576 px — a board a person actually has) and **3840 × 2160**
    (8 294 400 px, which is inside `MAX_REGION_PX = 8 388 608` by 94 208 px, and is the size
    `MAX_REGION_PX`'s own KDoc names as the largest real export). Both `run()`s return
    `fold(render(...))` — the whole 4 MB / 33 MB output goes through the case, and the fold samples
    it (Decision 14). `paper` is `"#ffffff"`, because "a region no layer covers is TRANSPARENT"
    (`RegionRenderer`'s KDoc) and a transparent 4K render is a different job from a paper-backed
    one. `frameId` is `null` (a CANVAS board has no frames).

14. **Every case's `run()` returns a value derived from its own output by `BenchCases.fold`, which
    samples at a fixed stride and is NOT a full CRC.** The fold is exactly:
    ```kotlin
    fun fold(bytes: ByteArray): Long {
        var h = 0L
        var i = 0
        while (i < bytes.size) { h = h * 31L + bytes[i].toLong(); i += 4096 }
        return h * 31L + bytes.size.toLong()
    }
    ```
    Stride 4096 so a 33 MB render is eight thousand adds rather than thirty-three million, and the
    length is mixed in last so a truncated output is a different number from a full one. *Why not
    `CRC32` over everything:* a CRC of 33 MB is itself a benchmark, and on the `tile-deflate` case it
    would be measured against a deflater and could plausibly win — the checksum would then be
    reporting on itself. *Why a stride rather than one byte:* a single byte could sit in a constant
    region of a synthetic image. Two tests pin it (see Tests 16–17): the same bytes always fold to
    the same number, and flipping one byte in the first 4 KiB changes it while flipping the 5 000th
    byte alone may not — which is the honest statement of what a sample is.

15. **The archive fixture is built ONCE, outside the timed closure, and this is load-bearing.**
    `archiveRead()` builds a `JbContents` (a 1024 × 1024 board → 4 × 4 = 16 tiles of exactly
    `TILE_BYTES`, `cel.tiles` declaring the 16 `"tx_ty"` keys, `strokes` empty), writes it once with
    `JbArchive.write` into a `ByteArrayOutputStream`, and keeps the bytes. The case's `run()` is
    `fold`-plus-length over `JbArchive.read(ByteArrayInputStream(bytes))` and **nothing else**.
    *Why:* building the document and deflating 4 MB inside the closure would make the reported number
    the cost of *writing* an archive, which is the `tile-deflate` case's job and not this one's — the
    single most expensive way to get a plausible-looking number in a benchmark. *And the bytes are
    not asserted byte-identical between two builds:* `ZipOutputStream` stamps each entry with the
    current time (`JbArchive`'s own KDoc says so and `JbArchiveTest` asserts the same thing for a
    different reason), so two writes differ. What the test pins instead is the **round trip**:
    `read` returns a document equal to the one written and exactly 16 tiles, each `TILE_BYTES` long.
    The case name is `"archive-read-16"` and the 16 is the tile count, so the report says what was
    read.

16. **`tileDeflate()` imports `TILE_BYTES` from `androidkit`, never from `core`.** There are **TWO**
    `TILE_BYTES` in the tree and both are `262_144`: the top-level
    `cc.joycreator.joybrush.androidkit.io.TILE_BYTES` at `JbArchive.kt:31` (the one the archive
    writes with, in the same module `BenchCases.kt` lives in) and
    `cc.joycreator.joybrush.core.render.RegionRenderer.TILE_BYTES` at `RegionRenderer.kt:176` (a
    `const` inside the object). Neither is wrong; importing the core one from `androidkit` would be
    a bet that two files that agree today keep agreeing. The image is a smooth gradient plus a
    filled rectangle, `TILE_BYTES` long: synthetic white noise does not compress, and a case that
    measures "deflate noise" is measuring a different job from the one the archive does.

## Tests

### `BenchTest.kt` — the harness, in `:core:jvmTest`

1. `measure` returns exactly `repeats` samples and the warm-ups are not among them: a case whose
   `run()` increments a counter, `warmup = 2`, `repeats = 5` → the counter reached 7, and the list
   has 5 entries.
2. A fake clock — a `Long` advanced by a scripted amount on every call — makes the samples exactly
   the gaps between consecutive calls. A case taking 3 calls' worth of clock per iteration with
   `repeats = 4` gives four equal samples. (This is the test that proves the bracket is where
   Decision 1 says it is.)
3. `warmup = 0` and `repeats = 0` each throw `IllegalArgumentException` whose message contains the
   case's name and the offending number. `repeats = 1` works and gives one sample.
4. `median([1,2,3]) == 2.0`; `median([1,2,3,4]) == 2.5`; `median([7]) == 7.0`.
5. `p95([5]) == 5.0`; `p95([1,2]) == 2.0`; `p95((1..20).map { it.toDouble() }) == 19.0` — the
   nearest-rank value, written into the test as `ceil(0.95 * 20) - 1 = 18`, the 19th smallest.
6. Verdict, all four: median 100 / budget 100 → `WITHIN_BUDGET`; median 100.1 / budget 100 →
   `OVER_BUDGET`; budget `NaN` → `NO_BUDGET`; budget 0 → `NO_BUDGET`; budget −5 → `NO_BUDGET`.
   The NaN case is the one that must exist: without it a NaN budget passes silently.
7. The checksum is the fold of the run's own return values: a case returning a counter gives the
   same checksum for the same `repeats` whatever order the timings arrived in, and a case returning
   a constant 0 gives checksum 0 (so "it did nothing" is legible on the report line).
8. A clock that goes backwards gives a **negative** sample that is kept in the list (Decision 1).
9. `report` output is pinned **exactly**, as a string equality, for: one `Measured` within budget,
   one `Measured` over, one `Unavailable`, and an **empty list**. The empty report says so in words
   and does not print a bare header — a report of nothing that looks like a report of a fast
   machine is the worst output this file can have.
10. `report` puts `device: (not stated)` when `device` is blank, and the caller's string otherwise.
11. The summary counts the three kinds separately and **names** the unmeasured ones: a report of two
    measured and one `Unavailable` says `2 measured, 0 over budget, 1 not measured` **and** a
    second line naming `psd-write`. The number in that second line is the whole of Decision 6.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

### `BenchCasesTest.kt` — the jobs, in `:androidkit:test`

12. **The fill and tile inputs are deterministic.** `BenchCases.floodFill()`'s two cases and
    `tileDeflate()`'s one each build their image through a function this test can call: two builds
    produce `contentEquals` byte arrays, and the sizes are `TILE_BYTES` for the tile and
    `2048 * 2048 * 4` for each fill reference. (A "faster" number from a smaller input is the failure
    this pins.) The render and archive inputs are covered by tests 18 and 20 instead, because they
    are built once rather than rebuilt per call.
13. **The work is real.** Running each of the five measured cases once directly (not through
    `measure`) gives a non-zero checksum; the fill cases' results are non-empty masks (at least one
    filled pixel), the deflate case's output is **strictly shorter** than its input, the two render
    cases' outputs are exactly `w * h * 4` bytes long, and the archive case returns 16 tiles.
14. **The budget table covers every case that exists** and no case that does not:
    `budgetMsFor` is a positive finite number for each name in `floodFill()` + `tileDeflate()` +
    `regionRender()` + `archiveRead()`, and `NaN` for `"psd-write"`, for a name not in the table,
    and for `""`.
15. `all()` has **six** entries in this order — two fills, one deflate, two renders, one archive
    read, and the `Unavailable` **last** — and the last one is `BenchOutcome.Unavailable` with a
    reason that names JB-2.14c.
16. **`fold` is a function of its bytes.** The same array always folds to the same `Long`; an empty
    array folds to `0L`; and flipping one byte inside the first 4 KiB changes the result. The
    stride is stated in the test's comment as 4096 with the reason (Decision 14), and the test
    records that a byte at index 5 000 is **outside** the sampled window rather than pretending
    otherwise — that is what a sample is.
17. **The deflate case measures the archive's own level and not a copy of it.** The case name is
    `"tile-deflate-" + JbArchive.ARCHIVE_DEFLATE_LEVEL`, asserted by importing the constant, and
    the test contains **no literal `6`** — grep the test file and there must not be one. (The
    point of Decision 12 is that the number is read, not retyped; a test that retyped it would
    restore the duplication it is there to prevent.)
18. **The render cases are real renders at the sizes the table names.** `regionRender()`'s two
    cases carry `1024x1024` and `3840x2160` in their names; each output is `w * h * 4` bytes; the
    3840 × 2160 case is inside the engine's own cap and the test says so by computing
    `3840L * 2160L <= MAX_REGION_PX` and `1024L * 1024L <= MAX_REGION_PX` — the constants imported,
    never retyped. The two cases produce **different** checksums (different sizes), so a source that
    quietly answered every rect with the same buffer is caught.
19. **The render source serves what the document declares.** The `TileSource` the fixture builds is
    asked for every `(tx, ty)` the cel lists and returns a `TILE_BYTES` array for each, and returns
    `null` outside the board — asserted, because a `TileSource` that returned the same tile for
    every address would make the render fast and wrong, and no timing would show it.
20. **The archive case is a round trip, and its bytes are not rebuilt per call.** `archiveRead()`'s
    one case, run once directly, returns a `JbContents` whose `doc` equals the document the fixture
    was built from and whose `tiles` has exactly 16 entries, each of exactly `TILE_BYTES` bytes;
    and running the case's `run()` twice gives the **same** checksum, which is what "the fixture is
    outside the closure" looks like from outside. The test does **not** assert two writes are
    byte-identical, and its comment says why (`ZipOutputStream` timestamps).
21. `Bench.report(all(), "SM-N960F Android 10")` runs end to end and its text contains one
    `NOT MEASURED` line for `psd-write`, a summary saying one job was not measured, and **six**
    outcome lines. (The exact millisecond figures are not asserted — they are a measurement, and a
    test that asserts them fails on every machine but this one. The exact *format* is asserted in
    `BenchTest` 9, where the inputs are numbers that test chose.)
22. **A 4K render does not need a bigger JVM than the default.** The test JVM runs the 3840 × 2160
    case once, and the test says in a comment why that is safe to assume: `MAX_REGION_PX`'s own KDoc
    costs one render at 20 bytes per pixel, so 8 294 400 px is 160 MiB live, inside the 512 MB
    default Gradle test heap. If this test ever OOMs, the answer is the case's size, not the heap.

**Command:** `./gradlew -p joybrush :core:jvmTest :androidkit:test` — both green, 0 failures.

## Do not

- **Do not add a clock, a `System.currentTimeMillis`, a `Thread.sleep`, or a warm-up sleep to the
  harness.** Time comes in as a parameter and a sleep in a benchmark is a lie about a phone.
- Do not touch `FloodFill`, `RegionRenderer`, `Tiles`, `DocOps` or anything else outside the owner
  area — this spec *measures* those, and changing the thing being measured in the same commit makes
  the number meaningless. The only edits to existing files are the three lines of Decision 12, and
  they change no behaviour.
- **Do not widen Decision 12.** Two call sites and one declaration. In particular do not move the
  constant to a third file, do not make it `internal` (a third package that must read it does not
  exist), and do not re-add a `6` anywhere, including in a test.
- Do not tune the code you are measuring. A 30 % win found by a benchmark is a finding, not a
  result.
- Do not make a missed budget fail. Decision 9.
- Do not guess the device name, and do not report a cloud number as a Note 9 number. A run on any
  other machine is a smoke test of the harness (and a useful one: it is what Tests 12–22 are).
- **Do not build the archive fixture inside a case's `run()`.** Decision 15, and it is the most
  expensive mistake available in this file.
- **Do not add a `main()` to `androidkit`** and do not add an Android entry point. `androidkit` is a
  plain Kotlin/JVM library compiled against `android.jar` with `compileOnly`; a `main()` there
  measures the machine it ran on, not the phone, and a `Handler` in a `:androidkit:test` throws
  `Stub!` because there is no Robolectric in this build.
- Do not edit `JoyBrushActivity.kt`, `JbCanvasView.kt` or `PenDiagnosticsView.kt`. The entry point
  is a follow-up; see below.
- No `kotlin.random` and no `java.*` outside `androidkit`.
- There are **TWO** `TILE_BYTES` and they are both `262_144`. Import the androidkit one. Decision 16.

## The entry point — an explicit follow-up, NOT this row

R39: *"the entry point is a 'Run benchmarks' button in the hidden pen-diagnostics panel (after the
Activity lock order)."* This row does not build it, for a reason that is in the rulings rather than
in taste: R30 item 1 serialises `JoyBrushActivity.kt` as **JB-1.21 → JB-0.08b/2.15 wiring →
JB-0.08c → JB-0.10 entry → then the chrome**, one row at a time. Folding a button into this row
would make it the fourth waiter in a queue, so the harness — which touches no app file at all — would
sit behind three app-file rows for the sake of a diagnostic button. **The row is dispatchable now
and the entry point is a separate, named piece of work:**

- **Where it lives:** the hidden pen-diagnostics panel, which already exists and is already toggled
  (`PenDiagnosticsView` in `androidkit`, `report(): String`, opened by the long-press "×" on the
  screen). A "Run benchmarks" row in that panel, printing through `Log.i("JoyBrush", …)` and
  writing the report to `getExternalFilesDir("joybrush")/bench.txt` so it can be pulled off the
  device.
- **What it costs:** the panel row is `androidkit`; the wiring that shows it and runs it on the main
  thread is `JoyBrushActivity.kt`, which is the locked file. The follow-up's owner area is
  `PenDiagnosticsView.kt` + `JoyBrushActivity.kt`, and it may only be dispatched **after** JB-0.08c.
- **Why deferring costs nothing:** the harness is a plain library. `BenchCases.all()` and
  `Bench.report(...)` are callable from a JVM harness, a unit test or a button, and Tests 12–22
  already call all six cases. The entry point adds a button, not a capability.

**The Lead may rule the entry point into this row instead**, in which case this row's owner area
grows by those two files and nothing else changes — but that trades a dispatchable row for a queued
one, so the default here is the deferral.

## Stop rule

If anything here is ambiguous, or a claim about landed code turns out to be false when you open the
file, **STOP**: write the question in *Questions* under a heading `for the Lead`, set this row
`⛔ Blocked`, commit, push, and take another task. Do not widen the owner area to reach the answer and
do not substitute a number you invented for one you could not find — a benchmark of a job you guessed
at is worse than no benchmark, because the number is real and the job is not. Two examples of what
counts: `ARCHIVE_DEFLATE_LEVEL` is not there when you open `JbArchive.kt` (Decision 12 is then
already done, say so and carry on), and `MAX_FILL_PX` is not 8 388 608 (then every size derived from
it in Decisions 7 and 11 is wrong, and that is a stop).

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green (paste the summary line).
- [ ] `./gradlew -p joybrush :androidkit:test` green (paste the summary line).
- [ ] `git status --short` shows only the six owner-area paths (four new files, two edited).
- [ ] `rg -n "setLevel\(" joybrush/androidkit` shows **two** hits, both naming
      `ARCHIVE_DEFLATE_LEVEL`, and `rg -n "ARCHIVE_DEFLATE_LEVEL" joybrush` shows the one
      declaration. (Paste both outputs. This is the check that the duplication is actually gone.)
- [ ] Committed as `JB-0.10: CPU benchmark harness`, pushed.
- [ ] **The Note 9 numbers are NOT this builder's job and are not in this box.** The harness
      measures; running it on a device needs the entry point above, which is a follow-up. A builder
      who runs the harness in the cloud has proved the harness works, which is what this row is for.
      Say so in the report rather than pasting a laptop number as if it were the phone's.

## Questions

_(Spec writer, 2026-09-29; revised against the review and R39 the same day.)_

### For the Lead

**Q1 — the budgets are provisional and I have ruled them that way (Decision 7), as R39 allows.**
Every number in the table carries a reason and the two new cases carry their arithmetic
(8 294 400 / 1 048 576 = 7.91×, so 250 × 7.91 = 1 978 ms, budget 4 000 ms; 16 × `TILE_BYTES` =
4 194 304 B for the archive case). **What settles them is this harness, run on the Note 9 with the
device string set to that phone's own model and Android version** — the first real run is the
measurement the table is a guess at, and the table is then rewritten from those numbers with the
derivations kept. Nothing else settles them, and in particular a laptop run does not. If you want
different numbers before the phone run, they are one line each in a table and Decision 4 means a
mistyped one becomes `NO_BUDGET` rather than a false pass.

**Q2 — the entry point is deferred, and that is a change of scope you may overrule (see *The entry
point*).** R39 asks for a "Run benchmarks" button in the hidden pen-diagnostics panel, and R30 item
1 puts `JoyBrushActivity.kt` fourth in a one-row-at-a-time lock order behind JB-1.21, the save-queue
wiring and JB-0.08c. My reading is that folding it in makes an otherwise-unblocked row wait behind
three app-file rows, so the harness ships now and the button is a named follow-up with its own owner
area. **Say the word and I will fold it in** — the only change would be two more paths in the owner
area and one more step, and the row would then need to be queued rather than dispatched.

**Q3 — now RULED and implemented: one public deflate constant (Decision 12).** R39 allows
`JbArchive`'s level to become a public constant as a one-line edit, and says the duplication is
real. I verified it and it is worse than the first draft of this spec said: **two** literals, at
`JbArchive.kt:207` and `OraExport.kt:230`, not one — and the two files are in the *same package*,
so a single top-level `const val ARCHIVE_DEFLATE_LEVEL = 6` in `JbArchive.kt` is readable by both
with no import. The spec therefore names that one constant, has both writers read it, has
`BenchCases` import it, and forbids the literal appearing in a test. That is one declaration plus two
changed call sites: no behaviour change, no byte of any saved file differs, and after it there are
zero literals of `6` for a deflate level in the module instead of two. **The only thing I would
like you to confirm is the placement** (top-level in `JbArchive.kt`, next to `TILE_BYTES`, rather
than a new small file) — it is the choice that makes the import in a third package work with no
extra ceremony.

### Ruled here, low-risk and reversible, flagged for you to confirm

**Q4 — PROVISIONAL (Claude to confirm): `psd-write` stays `Unavailable` and I did not substitute a
third job for it.** The blueprint names "flood fill, tile compression, PSD writing"; the PSD writer
is JB-2.14c, `🟸 Draft`, not built, and a budget for a job nobody can run is a fiction (Decision 6).
R39 says it stays Unavailable, so the report ends on a `NOT MEASURED` line and a summary that says
so in words. When JB-2.14c lands this is a one-method addition.

**Q5 — PROVISIONAL (Claude to confirm): the two new cases are `RegionRenderer.render` and
`JbArchive.read` (Decisions 13–15), and I have written their fixtures.** R39 names both; the
earlier draft of this spec asked you to choose between them and a substitute, and you have now
chosen. `render` is measured over a real `TileSource` at 1024² and at 3840 × 2160 (inside
`MAX_REGION_PX`, which is the size that engine's own KDoc calls the largest real export), and
`read` over a 16-tile archive whose bytes are built **once**, outside the timed closure. The fold
they share (Decision 14) samples at a stride of 4 096 so the checksum cannot become a second
benchmark. Both are pure `:core` work behind an `androidkit` fixture and neither touches the phone.
