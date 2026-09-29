# JB-0.10 — CPU benchmark harness: flood fill, tile compression, PSD write

| | |
|---|---|
| **Tier** | T2 (the numbers themselves are collected on the phone, which is a separate act — see Definition of done) |
| **Status** | 🟦 Ready — buildable as written; the budget numbers are provisional and the Lead may change them freely |
| **Needs** | 0.05 |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/bench/Bench.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/bench/BenchTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/bench/BenchCases.kt` · NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/bench/BenchCasesTest.kt` |
| **Estimated size** | ~180 lines + ~200 lines of tests |

## Goal

Blueprint §3.1, the owner's performance rule: *"Phase 0 benchmarks the few heavy CPU jobs (flood
fill, tile compression, PSD writing) on the Note 9; any that miss their budget move into a small C++
library that Kotlin calls on every platform. C++ is used where a measurement says so, not by
default."*

This spec builds the **harness that produces those measurements**, and nothing else. It is not a
performance claim and it asserts nothing about how fast the phone is — it measures, prints numbers
in a form that can be pasted into a review, and compares them with a budget table the Lead owns.

The one thing this harness must never do is **look fast**. A benchmark whose input shrank, whose
work was optimised away, or whose clock started before the work and stopped after the *previous*
iteration reports a number nobody can act on. Every one of those is a Decision below, and every one
has a test.

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

/** The three jobs the blueprint names, plus the budgets they are measured against. */
object BenchCases {
    /** "flood-fill-<w>x<h>-gap<g>", four of them. */
    fun floodFill(): List<BenchCase>
    /** "tile-deflate-<level>", one. */
    fun tileDeflate(): List<BenchCase>
    /** "psd-write" — Unavailable on this row; see Decision 6. */
    fun psdWrite(): BenchOutcome.Unavailable
    /** Everything above, in the order it is reported. */
    fun all(): List<BenchOutcome>
    /** The budget table, in ms. PROVISIONAL — the Lead owns these numbers. */
    fun budgetMsFor(caseName: String): Double
}
```

## Steps

1. Write `BenchTest.kt` first, from the Tests section below, and watch it fail to compile.
2. Write `Bench.kt` until it is green.
3. Write `BenchCasesTest.kt`, then `BenchCases.kt`.
4. Run both commands in **Tests** and paste the output.

## Decisions

1. **The harness owns no clock.** `nowMs: () -> Long` is a parameter, and the harness never calls
   anything else for time. A harness that reads a clock itself is a harness that cannot be tested
   without a wall, and the median/p95/verdict arithmetic is exactly the part most worth testing.
   The caller passes a monotonic millisecond counter (`System.nanoTime() / 1_000_000`).
   Each `run()` is bracketed by exactly two `nowMs()` calls, and the elapsed value is
   `(after − before).toDouble()`. A `before` that is not strictly less than `after` yields a
   **negative** sample, which is kept: a clock that went backwards is a fact about the run and
   hiding it would make the median lie. (The caller is expected to pass a monotonic clock; the
   harness does not enforce it, because refusing to report a run is worse than reporting it.)

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

5. **The checksum is printed on every measured line.** `checksum = fold of every run() result`, XORed
   (a fold, so a run that returns a different value every time still gives a stable report rather
   than a number nobody can compare). A case whose work the JIT or a future refactor has eliminated
   returns a constant, and its checksum line is the only place that shows. It is on the line for the
   same reason the review of JB-2.20a wanted a test that *partitions* an enum: a claim nothing can
   falsify is a claim that quietly stops being true.

6. **A job that is planned but not built is `Unavailable(name, reason)`, and it is neither a pass
   nor a fail.** The blueprint names three jobs; only two exist. `psdWrite()` returns
   `Unavailable("psd-write", "the PSD writer is JB-2.14c and is not built")` and the report prints
   it as a `NOT MEASURED` line. **It does not return a zero, a null, or an empty list**, because a
   summary reading "3 cases, 0 failures" for a run that measured two is the lie this decision
   exists to prevent. The summary counts the three kinds separately:
   `N measured, M over budget, K not measured`. If `K > 0` the summary adds a second line naming
   them, so a reader who sees only the summary still knows a number is missing.

7. **The budgets are a table in `BenchCases`, and they are PROVISIONAL.** A budget is *when a person
   would notice*, not a target. The provisional numbers, with the reasoning, so the Lead can argue
   with a number instead of a vibe:

   | case | budget | why |
   |---|---|---|
   | `flood-fill-2048x2048-gap0` | 400 ms | a tap-fill that takes half a second is a dropped frame the person sees |
   | `flood-fill-2048x2048-gap3` | 1200 ms | gap closing is three extra full-image passes; it is the fill tool's *slow* path and the one that would be noticed |
   | `tile-deflate-6` | 60 ms | per tile; a 4-tile stroke's worth of work per save, on a writer thread |
   | `psd-write` | — | no implementation; a budget for a job nobody can run is a fiction |

   A name that is not in the table gets `Double.NaN`, which Decision 4 turns into `NO_BUDGET`. So
   changing a budget, or adding a case, cannot break the harness.

8. **The report is plain text, one line per outcome, then the summary.** Layout is deterministic so
   a test can pin it: names padded to the longest name in the report, every millisecond figure
   formatted `%.1f`, no colour and no box drawing. It gets pasted into a review file by hand, and
   the person doing that should not have to strip anything.
   A measured line is:
   `<name>  median <M> ms  p95 <P> ms  worst <W> ms  budget <B> ms  <VERDICT>  checksum <C>`
   with `<VERDICT>` one of `WITHIN`/`OVER`/`no budget set`. An unmeasured line is
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
    `:core:jvmTest` on any machine, and only the three workloads need the JVM. Nothing here uses
    `java.*` outside `androidkit`, so the core stays portable (blueprint §3.1, the iOS door).

11. **The two flood-fill cases are the SAME image at two settings, and the sizes come from the
    cap, not from a copied number.** `MAX_FILL_PX` is 8 388 608 (2²³) and its own KDoc already costs
    one fill at that size; 2048 × 2048 = 4 194 304, comfortably inside it, and 2048 is a size a person
    can actually have painted. The image is a synthetic black-on-white line drawing built by a
    private function, so a test can assert it is byte-identical between two runs — a benchmark whose
    input could differ between runs is measuring the input. `gap0` uses `FillOptions(gapClosePx = 0)`
    and `gap3` uses `FillOptions(gapClosePx = 3)`, because gap closing allocates two more full-image
    arrays and runs two dilations and is the path that would ever be slow.
    Both cases return the count of filled pixels, so the checksum says whether the fill actually
    filled anything, and the test asserts a non-zero count.

12. **The deflate case measures the archive's own settings, and the level is a copy.** `JbArchive`
    writes tiles with `ZipOutputStream.setLevel(6)` and that `6` is a **private literal inside
    `JbArchive.kt`**, which is not this spec's owner area. `BenchCases` therefore carries its own
    `private const val ARCHIVE_DEFLATE_LEVEL = 6` with a comment naming the drift, and a test
    asserts the deflated output is strictly smaller than the input (so the case cannot silently
    become a memcpy benchmark). **This is the same shape as the `SizeOpacityDrag.MAX_SIZE` copy that
    LEAD_RULINGS R19 had to fix**, and it is Question 3.

13. **The tile fed to the deflate case is `TILE_BYTES` long and `TILE_BYTES` is not written out
    here.** `TILE_BYTES` (262 144) is derived from `TILE_SIZE` inside `androidkit`'s `io` package and
    is imported, per the same "a shared constant is never a second number" rule that put
    `MAX_SIZE_PX` in `BrushValidate` and `TILE_SIZE` in `DocModel`. The image is a smooth gradient
    plus a filled rectangle: synthetic white noise does not compress, and a case that measures
    "deflate noise" is measuring a different job from the one the archive does.

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

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

### `BenchCasesTest.kt` — the three jobs, in `:androidkit:test`

11. **The inputs are deterministic.** `BenchCases.floodFill()`'s two cases and `tileDeflate()`'s one
    each build their image through a function this test can call: two builds produce
    `contentEquals` byte arrays, and the sizes are `TILE_BYTES` for the tile and `2048 * 2048 * 4`
    for each fill reference. (A "faster" number from a smaller input is the failure this pins.)
12. **The work is real.** Running each of the three measured cases once directly (not through
    `measure`) gives a non-zero checksum; the fill cases' results are non-empty masks (at least one
    filled pixel), and the deflate case's output is **strictly shorter** than its input.
13. **The budget table covers every case that exists** and no case that does not:
    `budgetMsFor` is a positive finite number for each name in `floodFill()` + `tileDeflate()`, and
    `NaN` for `"psd-write"`, for a name not in the table, and for `""`.
14. `all()` has four entries (two fills, one deflate, one unavailable) and the unavailable one is
    `BenchOutcome.Unavailable` with a reason that names JB-2.14c.
15. `Bench.report(all(), "SM-N960F Android 10")` runs end to end and its text contains one
    `NOT MEASURED` line for `psd-write` and a summary saying one job was not measured. (The exact
    numbers are not asserted — they are a measurement, and a test that asserts them fails on every
    machine but this one. The exact *format* is asserted in `BenchTest` 9, where the inputs are
    numbers this test chose.)

**Command:** `./gradlew -p joybrush :core:jvmTest :androidkit:test` — both green, 0 failures.

## Do not

- **Do not add a clock, a `System.currentTimeMillis`, a `Thread.sleep`, or a warm-up sleep to the
  harness.** Time comes in as a parameter and a sleep in a benchmark is a lie about a phone.
- Do not touch `FloodFill`, `JbArchive`, `Tiles` or anything else outside the owner area — this
  spec *measures* those, and changing the thing being measured in the same commit makes the number
  meaningless.
- Do not tune the code you are measuring. A 30 % win found by a benchmark is a finding, not a
  result.
- Do not make a missed budget fail. Decision 9.
- Do not guess the device name, and do not report a cloud number as a Note 9 number. A run on any
  other machine is a smoke test of the harness (and a useful one: it is what Tests 11–15 are).
- No `kotlin.random` and no `java.*` outside `androidkit`.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green (paste the summary line).
- [ ] `./gradlew -p joybrush :androidkit:test` green (paste the summary line).
- [ ] `git status --short` shows only the four owner-area files.
- [ ] Committed as `JB-0.10: CPU benchmark harness`, pushed.
- [ ] **The Note 9 numbers are NOT this builder's job and are not in this box.** The harness
      measures; running it needs a device and an entry point, and the entry point is Question 2. A
      builder who runs the harness in the cloud has proved the harness works, which is what this row
      is for. Say so in the report rather than pasting a laptop number as if it were the phone's.

## Questions

_(Spec writer, 2026-09-29. `⚪ Outline` row: "JB-0.10 CPU benchmark harness (flood fill, tile
compression, PSD write) on the Note 9 | T2 | 0.05". All four are for the Lead.)_

**Q1 — the budget numbers in Decision 7 are mine, not yours.** They are the four numbers the whole
harness exists to compare against, and every one of them is a judgement about when a person notices.
I have given each a reason so the argument can be about the number. A budget that is too tight sends
Kotlin to C++ for nothing; one that is too loose never sends anything anywhere. The harness works
with whatever table you give it (Decision 4, 7), so changing a number is a one-line edit — but
"over budget" with no budget table is not a finding, it is a number.

**Q2 — how does a person start this on the Note 9, and it is not a T2 decision.** The harness has
no `main()`: `androidkit` is a plain Kotlin/JVM library compiled against `android.jar`, so a
`main()` there would measure the machine it ran on, not the phone. Reaching the phone needs an
entry point on the Joy Brush screen, and `JoyBrushActivity.kt` is an **app file under the Lead's
serialised app-file order** (`D.02a → D.02 → D.02c / D.05`, one at a time, never beside JB-0.09).
The natural answer is a second hidden gesture beside the existing long-press diagnostics (JB-0.06),
printing to `Log.i("JoyBrush", …)` and writing the report into
`getExternalFilesDir("joybrush")/bench.txt` so it can be pulled. I have not specified it, because
choosing it means touching an app file, and that is yours.

**Q3 — `JbArchive`'s deflate level is a private literal, and this is the `MAX_SIZE_PX` trap again.**
`JbArchive.write` calls `zos.setLevel(6)` with the `6` inline; `BenchCases` now has a second `6`
(Decision 12) and nothing can catch them drifting apart. R19 fixed exactly this for
`BrushValidate.MAX_SIZE_PX` by making the constant public. The same one-line fix here is to make the
archive's level a public constant in `JbArchive.kt` and have the benchmark import it — that file is
not this spec's area, and a builder must not widen its own area to fix a number. Rule it and it is a
one-line follow-up; leave it and the benchmark measures a level the archive may not be using.

**Q4 — the third job does not exist.** The blueprint names "flood fill, tile compression, PSD
writing", and the PSD writer is JB-2.14c, which is `⚪ Outline` with `DOC_VERSION` work of its own.
I have specced it as `Unavailable` with a reason naming JB-2.14c (Decision 6) rather than inventing
a budget for a job nobody can run. Two ways forward: land JB-2.14c early and add the case as a
one-method addition to `BenchCases`, or substitute a different third job — the honest candidates are
`RegionRenderer.render` over a board-sized rectangle (JB-2.13a, built, and it is on the fill path)
and `JbArchive.read` of a full document (JB-0.08a, built, and it is on the open path). Say which,
and I will add it as a fourth case; do not add it silently.
