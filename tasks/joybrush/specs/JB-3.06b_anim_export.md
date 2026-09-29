# JB-3.06b — Export the animation board: PNG sequence, GIF, sprite sheet, MP4, WebP

| | |
|---|---|
| **Tier** | T2 (the plan layer is pure `:core` and is fully tested; the encoders are `androidkit` and are verified on device) |
| **Status** | 📝 Draft spec — **the owner area names `JoyBrushActivity.kt`, which is an APP FILE under the Lead's serialised app-file order (`D.02a → D.02 → D.02c / D.05`, one at a time, and never beside `JB-0.09`). `D.02c` and `D.05` are both on the board at `🟦 Ready (after D.02)`, so this row is not dispatchable until the Lead says which of the three runs first.** Two formats (MP4, animated WebP) also have no encoder in this repo — see Q1 and Q2, which are the Lead's and are format decisions a cross-reviewer may not take. The plan layer itself is complete and needs nothing. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha` 2026-09-29 · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — verified `AnimOps.frameStartsMs(board): List<Double>` and `AnimOps.totalDurationMs(board): Double` exist (`AnimOps.kt:461`/`:442`), `SpritePacker.pack`/`assertEncodedSize(fileName, pngWidth, pngHeight)`/`data class Clip` exist (`SpritePacker.kt:143`/`:72`/`:28`), `GifEncoder.addFrame(rgba: ByteArray, delayMs: Int)` exists (`GifEncoder.kt:436`), the board's fps range really is 1..60 (`DocOps.kt:87` and `AnimOps.kt:543`), and `JbArchive`'s `unsafeReason` is **private** (`JbArchive.kt:514`) — so Decision 3's "the same set" is a restatement, not a shared constant. Added the stop rule and a hard app-file warning. Left Draft: the app-file order and Q1/Q2. |
| **Needs** | 3.01, 3.06a, 2.13a, 4.03a (as the ROADMAP row states) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlanTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt` · EDIT `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (the export pill and the format sheet — nothing else in that file). **🔴 the last path is an APP FILE — see the Status line and "Do not".** |
| **Estimated size** | ~230 lines of Kotlin in `:core` + ~280 lines of tests, ~260 lines of Android |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher compiles `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` green. |

## Goal

Blueprint §4 Phase 3: *"Export MP4 / GIF / WebP / PNG sequence / sprite sheet"* from the animation
board, and §3.4: *"Export PNG, OpenRaster, PSD (own writer), GIF/WebP/MP4, PNG sequence, sprite
sheet."* All five land as file contracts somebody else already reads, so nothing here invents a
format.

The design in one sentence: **one plan, five encoders.** Which frames, in what order, held for how
long, at what size is a *pure function of the board* and is therefore a `List<Frame>` a test can
check exhaustively; the encoders are dumb consumers of that list. Getting that seam right is the
whole reason this is one task and not five.

## Contract — the plan layer (`:core`, pure, buildable today)

```kotlin
package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.export.SpritePacker

/**
 * WHAT to export, as data. Every encoder below is a consumer of this and adds nothing of its own.
 *
 * The delays come from `AnimOps.frameStartsMs` / `AnimOps.totalDurationMs` and are **never**
 * re-derived by accumulating `holdFrames × 1000 / fps` — the same rule as JB-3.05a and JB-3.03, and
 * for the same reason: a boundary one ulp out is a frame of the wrong length in a file somebody
 * else will play.
 */
data class AnimExportPlan(
    /** Frame ids, in play order. The cells of a sprite sheet and the frames of a GIF or an MP4. */
    val frameIds: List<String>,
    /** How long each frame is shown, ms, parallel to [frameIds]. `delayMs[i] > 0` for every i. */
    val delayMs: List<Int>,
    /** The picture size, in pixels. Every frame is this size; a board has one rect. */
    val width: Int,
    val height: Int,
    /** The board's own fps, for the formats that want a cadence rather than a list of delays. */
    val fps: Float,
    /** The suggested file name WITHOUT an extension, e.g. "Walk cycle". */
    val baseName: String,
) {
    init {
        require(frameIds.isNotEmpty())
        require(delayMs.size == frameIds.size)
        require(delayMs.all { it > 0 })
        require(width > 0 && height > 0)
    }
    val totalMs: Int get() = delayMs.sum()
    val frameCount: Int get() = frameIds.size
}

object AnimExport {

    /**
     * The whole board, in play order, at [board]'s rect.
     *
     * @param baseName the file name without an extension; sanitised (see Decision 4).
     * @throws IllegalArgumentException on a board with no frames, a non-positive rect, or an fps
     *   outside 1..60 — which is `AnimOps`' own refusal, surfaced, not a second rule.
     */
    fun plan(board: Board, baseName: String): AnimExportPlan

    /**
     * A SUB-RANGE of the board: frames [first]..[last] inclusive, in play order.
     *
     * A sub-range is what "export just this shot" means, and it is the same arithmetic the
     * `PlaybackClock` range does — so this refuses and clamps exactly as it does (swapped,
     * clamped) rather than inventing a second convention. See Decision 3.
     */
    fun planRange(board: Board, first: Int, last: Int, baseName: String): AnimExportPlan

    /**
     * The cells of a sprite sheet for [plan]: the frames in order, laid out [cols] per row.
     * `rows = ceil(frameCount / cols)`; the surplus slots in the last row are transparent, and the
     * sidecar's grid still counts them.
     *
     * @throws IllegalArgumentException if [cols] < 1 or > [SpritePacker]'s own limits — checked by
     *   CALLING `SpritePacker.pack` and letting IT refuse, so the two cannot disagree (R23).
     */
    fun sheetCols(frameCount: Int, requested: Int): Int

    /** The sidecar's one clip for this plan: every frame, [type] verbatim, the board's own fps. */
    fun sheetClip(plan: AnimExportPlan, id: String, name: String, type: String): Clip

    /**
     * A file name that is safe on every filesystem the app writes to: no path separators, no
     * control characters, no `:` (a Windows drive letter or an NTFS stream — `JbArchive`
     * `unsafeReason` refuses the same set), trimmed, and never empty.
     */
    fun safeBaseName(raw: String): String
}
```

**The landed functions this plan layer calls, pasted so nothing above says "see the other file"**
(all verified in the tree 2026-09-29):

```kotlin
// cc.joycreator.joybrush.core.doc.DocModel.kt:69 — the whole of what `plan(board)` is given
@Serializable data class Board(
    val id: String,
    val name: String,
    val kind: BoardKind,                   // CANVAS | ANIMATION | SPRITE | PUPPET | CHARACTER
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                  // ANIMATION only. DocOps.kt:87 refuses `fps !in 1f..60f`
    val frames: List<Frame>,               // ANIMATION only, IN PLAY ORDER
    val grid: SpriteGrid? = null,          // SPRITE only
)
@Serializable data class Frame(val id: String, val holdFrames: Int = 1)
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)

// cc.joycreator.joybrush.core.doc.AnimOps.kt — THE ONLY source of a frame's length (Decision 2)
fun totalDurationMs(board: Board): Double                        // :442
fun frameStartsMs(board: Board): List<Double>                     // :461  — starts, not lengths
// A frame's own length is `holdFrames * 1000.0 / fps`, multiplying before dividing (AnimOps.kt:475).

// cc.joycreator.joybrush.core.export.SpritePacker.kt
data class Clip(/* :28 */ …)                                     // the sidecar's one clip
fun assertEncodedSize(fileName: String, pngWidth: Int, pngHeight: Int)   // :72
fun pack(/* :143 */ …)                                           // the limits live HERE, not here

// cc.joycreator.joybrush.core.export.GifEncoder.kt:436
fun addFrame(rgba: ByteArray, delayMs: Int)
```

**`frameStartsMs` returns the STARTS of frames, not their lengths.** The plan's `delayMs[i]` is a
length, so it is `frameStartsMs[i + 1] - frameStartsMs[i]` for every `i` but the last, and the last
is `totalDurationMs - frameStartsMs.last`. Read that derivation out of those three functions; do not
write `holdFrames * 1000 / fps` here (Decision 2, and the house rule from JB-3.05a).

## Contract — the encoder layer (`androidkit`)

```kotlin
package cc.joycreator.joybrush.androidkit.io

enum class AnimFormat(val extension: String, val mime: String) {
    PNG_SEQUENCE("png", "image/png"),
    GIF("gif", "image/gif"),
    SHEET_PNG("png", "image/png"),   // one sheet PNG + a .sprite.json beside it
    MP4("mp4", "video/mp4"),
    WEBP("webp", "image/webp"),
}

object AnimExportRunner {
    /**
     * Renders and writes. Never on the UI or the GL thread: tiles are read on the GL thread
     * (the same rule as JB-2.13b) and the encode and the write are on a background executor.
     *
     * @param includePaper defaults to `doc.paper.includeInExport`, the document's own setting
     *   (blueprint §2: "Export has an Include paper checkbox").
     * @param onFrame (index, total) so the dialog can say "frame 7 of 40" rather than freeze.
     * @return the files written, in the order they were written.
     */
    fun export(
        format: AnimFormat,
        board: Board,
        plan: AnimExportPlan,
        tiles: TileSource,
        paper: String?,
        destination: Destination,
        onFrame: (Int, Int) -> Unit = { _, _ -> },
    ): List<String>
}

/** Where the bytes go. One file, or a folder (the sequence writes many). */
sealed interface Destination {
    data class OneFile(val uri: android.net.Uri) : Destination
    data class Folder(val treeUri: android.net.Uri) : Destination
}
```

## Decisions

1. **One plan, five encoders, and the plan is a `data class` that can be asserted field by field.**
   *Why:* the moment each format computes its own delays, two formats drift — and the drift shows
   up as "the GIF is a frame short", which is a bug report with no cause anybody can find.
2. **`delayMs[i]` is the frame's own length in ms, ROUNDED TO NEAREST with ties away from zero, and
   every delay is at least 1.** The rounding is the same idiom as `SpriteGridMath.dragEdge` and as
   JB-3.03 Decision 9 — one rounding rule in the project, and **not** Kotlin's `round`
   (ties-to-even). *Why:* a GIF's delay is in centiseconds and an MP4's is in timescale units; a
   delay of 0 is a frame nobody ever sees, which is a silent frame loss, so the floor is 1 and not 0.
3. **Sub-ranges behave exactly as `PlaybackClock`'s range does**: swapped if given backwards,
   clamped into the board, and **refused in words** for a board with no frames or a bad fps. The
   arithmetic comes from `AnimOps`, so "export frames 2..5" and "play frames 2..5" are the same four
   frames by construction. *Why:* an export that disagrees with what played is the one bug a person
   cannot argue with.
4. **`safeBaseName` refuses rather than repairs, and falls back to a fixed name.** A name that
   reduces to nothing is not an error the person caused deliberately, so: strip what a filesystem
   cannot hold, trim, and if the result is empty use **"Animation"**. *Why:* the house rule —
   refuse bad input in words, never clamp silently — with one exception, because here the caller is
   not typing a name into a field and a hard failure would be worse than a sensible default. The
   fallback is *named*, so a test can assert it.
5. **Every frame is rendered at the BOARD's rect, and every frame is the same size.** A board is one
   rectangle; a frame that needed less space than another would be a different size, and a GIF or an
   MP4 of mixed sizes is a file other apps will not open. *Why:* a frame with nothing on it is still
   the size of the board — that is what "the picture at this frame" means, and `RegionRenderer`
   already answers exactly that question.
6. **The plan layer NEVER touches pixels and NEVER touches a file.** It takes a `Board` and gives
   back numbers. `RegionRenderer` and the encoders do the rest. *Why:* this is what makes the plan
   testable with no canvas, no tiles and no phone, which is the only reason it can be pinned at all.
7. **Paper follows the document's own `includeInExport`, and the export sheet's checkbox writes
   back to it** so the next export starts where the person left off. *Why:* blueprint §2, and
   JB-2.13b already made this the document's setting rather than the dialog's.
8. **A PNG sequence is `NNNN.png`, four digits, zero-padded, starting at 0001**, plus a
   `NNNN.txt` manifest beside them listing `filename<TAB>delayMs` per line. *Why:* four digits
   sorts correctly in every file manager up to 9 999 frames and is a number a person can read; and
   the manifest is what makes the sequence playable with the right timing by anyone who is not this
   app — the delays are the whole content of a sequence and a folder of PNGs has none. (A manifest
   nobody reads is still worth writing: it is the difference between a folder and a document.)
9. **`sheetCols` defaults to 8, wraps to 1 for a single frame, and is passed to `SpritePacker.pack`
   which does the refusing.** This class does not hold a copy of the packer's limits — it asks.
   *Why:* R23 and the `MAX_SIZE_PX` lesson. A second copy of a limit is a limit that can drift, and
   nothing can catch it.
10. **The sheet's clip is ONE clip covering every frame, `type` written verbatim from the app's own
    three words, `fps` = the board's.** The sidecar is then describing exactly what was exported,
    with no weights and no omitted keys. *Why:* `SpritePacker` already omits a default rather than
    writing it, and inventing a second clip ("the first half", "the second half") is a product
    decision nobody asked for.
11. **`SpritePacker.assertEncodedSize` is called BEFORE either file is written.** It is the
    packer's own rule, already there for exactly this, and the alternative is a pair of files on
    disk that disagree. *Why:* a disagreeing pair is worse than a message.
12. **GIF is `GifEncoder` with `loop = true` and the plan's delays**, and the encoder's own
    centisecond rounding and 2 cs minimum apply — this spec does not second-guess them.
    *Why:* the encoder is Built, reviewed and cleared; a second rounding here would be a second
    answer.
13. **A render that exceeds `RegionRenderer.MAX_REGION_PX` is refused in words before the first
    frame is rendered**, not partway through. The check is on the plan's `width × height`, so it
    costs nothing and it is the same number the renderer uses. *Why:* the renderer's rule 2 says
    every exporter catches `RegionException` and says what it wanted in a sentence; catching it in
    the plan makes the sentence name the board.
14. **Nothing is written anywhere until the whole export has succeeded, except that a PNG sequence
    is many files and is therefore written one at a time.** For the single-file formats the bytes
    are built in full in `cacheDir` and then streamed to the Uri — **the R11 rule, verbatim, and for
    the same reason**: a SAF `Uri` has no rename, so "write it and hope" leaves a half file at a
    name the person chose. For a sequence, a failure at frame 20 leaves 19 good frames and the
    manifest **is not written** — so an incomplete sequence is recognisable by its absence.
    *Why:* a file that announces itself as complete and is not is the worst outcome available, and
    the manifest is the cheapest honest signal.
15. **Every refusal reaches the user as a sentence with the reason, and a partial export says how
    far it got.** "Exported 19 of 40 frames — frame 20 would not fit in memory." *Why:* the house
    rule from JB-0.08b, and the only thing that makes a 40-frame export on a Note 9 debuggable.
16. **MP4 and WebP are NOT specified in this spec.** They are in the format list and in the enum so
    the sheet is complete, and the runner refuses them in words — *"MP4 needs the encoder task
    first"* — rather than writing a file with the right extension and the wrong contents. *Why:*
    this is the single most important line in the file. Q1 and Q2 explain why.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (one plan) | `everyFormatIsAConsumererOfTheSamePlan` — all five produce byte-identical `delayMs` for the same board |
| 2 (delay rounding, floor 1) | `aDelayIsRoundedTiesAwayFromZero`, `noDelayIsEverZero` |
| 3 (sub-range = the clock's range) | `aSubRangeIsSwappedAndClampedLikeTheClocks`, `aRangeOverTheWholeBoardIsTheWholeBoard`, `anEmptyBoardIsRefused` |
| 4 (safe name) | `aFileNameWithAPathSeparatorIsRefusedNotHonoured`, `anEmptyNameFallsBackToAnimation` |
| 5 (one size) | `everyFrameIsTheBoardsOwnRect` — asserted across a board at a negative origin |
| 6 (no pixels, no files) | `thePlanLayerTouchesNoPixelAndNoFile` — reflection over `AnimExport`'s members |
| 7 (paper from the document) | `paperDefaultsToTheDocumentsOwnSetting` |
| 8 (0001 + manifest) | `aSequenceIsNumberedFromOneWithFourDigits`, `theManifestIsWrittenOnlyWhenEveryFrameIs` |
| 9 (ask the packer) | `sheetColsHoldsNoCopyOfThePackersLimits` — reflection, plus a case the packer refuses |
| 10 (one clip) | `theSheetCarriesExactlyOneClipCoveringEveryFrame` |
| 11 (assert before write) | `theEncodedSizeIsCheckedBeforeEitherFileIsWritten` — asserted by ordering in the runner's own log |
| 12 (GIF is the encoder's) | `gifDelaysAreTheEncodersOwnRoundedCentiseconds` — the plan's ms, the encoder's cs, compared with the encoder's rule written out |
| 13 (refuse before frame 1) | `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered` — the render callback never fires |
| 14 (nothing half-written) | `aFailedSingleFileExportLeavesNoTargetBytes`, `anInterruptedSequenceHasNoManifest` |
| 15 (sentences) | `aPartialExportSaysHowFarItGot` |
| 16 (MP4/WebP refused) | `mp4AndWebpAreRefusedInWordsAndWriteNothing` |

## Tests

`joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlanTest.kt`

Fixture: a 12 fps board, holds `[1, 2, 1, 3]`, frames `f0..f3`, rect `(0, 0, 64, 48)`, plus a
four-frame board at rect `(−40, 300, 32, 32)`. **Every expected delay below is written as an
expression over `AnimOps.frameStartsMs`, never as a hand-typed `hold * 1000 / fps`**, and the test's
own KDoc says why (JB-3.05a's rule 1, verbatim).

1. `aDelayIsRoundedTiesAwayFromZero`: at 3 fps a hold of 1 is 333.33… ms → **333**; a hold of 2 is
   666.67 → **667**. Two exact halves are constructed from a 60 fps board (`hold × 1000/60` lands on
   .5 at some holds) and the tie goes **away from zero**, not to even. Comment names
   `kotlin.math.round` as the wrong answer.
2. `noDelayIsEverZero`: a board at 60 fps with a hold of 1 gives 16.67 → 17; the property is checked
   over fps 1..60 × hold 1..50 — **3 000 cases, every delay ≥ 1.**
3. `everyFrameIsTheBoardsOwnRect`: the plan's `width`/`height` equal `board.rect.w`/`h` for a board
   at `(−40, 300, 32, 32)`. Assert the **negative origin is irrelevant** to the size, which is the
   half a builder gets wrong.
4. `aSubRangeIsSwappedAndClampedLikeTheClocks`: `planRange(board, 3, 1)` equals `planRange(board, 1,
   3)`; `planRange(board, 0, 99)` equals `plan(board)`; `planRange(board, -5, -9)` yields frame 0
   alone. Compared **against `PlaybackClock`'s own answers** for the same range, not against a second
   implementation — Decision 3's whole claim is that they cannot disagree.
5. `anEmptyBoardIsRefused` / `aBoardAtZeroFpsIsRefusedInWords`: `IllegalArgumentException` with the
   reason in the message, and the message names the fps.
6. `aFileNameWithAPathSeparatorIsRefusedNotHonoured`: `"../etc/passwd"`, `"a/b"`, `"a\\b"`,
   `"C:evil"`, `"a\u0000b"` all reduce to a single safe token; the result contains no separator, no
   colon and no control character. Assert the **properties**, not one expected string.
7. `anEmptyNameFallsBackToAnimation`: `""`, `"   "`, `"///"` all → `"Animation"`.
8. `aSequenceIsNumberedFromOneWithFourDigits`: frames 1..12 → `0001.png` … `0012.png`; frame 10 000
   would be five digits, and the plan **says so in its KDoc** rather than silently truncating
   (asserted as a comment-derived property: `name.length >= 8` for the padded form).
9. `sheetColsHoldsNoCopyOfThePackersLimits`: reflection over `AnimExport`'s constants — no `Int`
   named like a cap. Plus a functional case: `sheetCols(40, 3)` = 3, and packing 40 cells in 3
   columns is `SpritePacker`'s to accept or refuse, which the test **delegates** and asserts the
   delegated outcome.
10. `theSheetCarriesExactlyOneClipCoveringEveryFrame`: the `Clip` from `sheetClip` has
    `frames == plan.frameIds`, `type` verbatim, `fps == plan.fps`. And packing it with the real
    `SpritePacker.pack` produces a sidecar whose `presets` array has **one** entry with those frames —
    asserted by parsing the JSON, so it is the file that is checked and not the object.
11. `everyFormatIsAConsumererOfTheSamePlan`: GIF, sheet and sequence on the same board all
    `addFrame`/`pack` the **same** `delayMs` list. This is the test that makes Decision 1 real.
12. `gifDelaysAreTheEncodersOwnRoundedCentiseconds`: the plan says 83 ms; `GifEncoder` writes
    `round(83/10) = 8` cs. Asserted by decoding the produced GIF's Graphic Control Extension — i.e.
    **by reading the bytes the encoder wrote**, not by asking the encoder what it thinks it wrote.
13. `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered`: a rect of 4 000 × 4 000 (16 M px, over
    `MAX_REGION_PX` = 8 388 608) → `RegionException` and the render callback's counter is **0**.
14. `paperDefaultsToTheDocumentsOwnSetting`: `Paper(includeInExport = true)` → the plan's export
    passes the paper colour; `false` → null. Asserted on the argument the runner receives.
15. `aFailedSingleFileExportLeavesNoTargetBytes` / `anInterruptedSequenceHasNoManifest`: the write
    is to a fake `Destination` that fails at frame 20 of 40. The single-file case leaves **0 bytes**
    at the target; the sequence case has 19 files and **no manifest**. Both are counts, not
    descriptions.
16. `aPartialExportSaysHowFarItGot`: the message from the failure contains `19` and `40`.
17. `mp4AndWebpAreRefusedInWordsAndWriteNothing`: both formats throw with a message that names the
    missing task, and the fake destination's byte count is **0**.
18. `thePlanLayerTouchesNoPixelAndNoFile`: `AnimExport`'s and `AnimExportPlan`'s declared members
    mention no `TileSource`, no `Uri`, no `File`, no `ByteArray`. It is numbers in and numbers out,
    and the test is the mechanical form of that promise.
19. **Non-vacuity, which the builder must run and paste:** change the rounding in Decision 2 from
    ties-away-from-zero to `kotlin.math.round`, run, and watch test 1 go red. Then change
    `frameIds` in Decision 3 from `frameIds` to `frameIds.drop(1)`, and watch test 4 go red — that
    is the "export drops a frame" bug, and the test has to be seen failing once.

Command: `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not

- **Do not write a file with the right extension and the wrong contents.** Decision 16. A refused
  MP4 is a message; a broken MP4 is a support ticket.
- Do not re-derive a frame's length. `AnimOps.frameStartsMs` / `totalDurationMs`, indexed.
- Do not hold a second copy of `SpritePacker`'s limits, `RegionRenderer.MAX_REGION_PX` or
  `GifEncoder`'s rounding rule. Ask, catch, delegate.
- Do not write to a SAF `Uri` incrementally for a single-file format. R11: build it whole in
  `cacheDir` first, then stream.
- Do not add a scale factor, a background colour, a transparent-background toggle or a "reverse the
  animation" option. Every one of those is a product decision (Q3) and none is in the blueprint.
- Do not touch `DocModel.kt` / `DocJson.kt` / `JbArchive.kt`. Writing paper into the document is
  JB-2.13b's and it has done it.
- No new dependencies.
- **🔴 Do not edit `JoyBrushActivity.kt` in this row without the Lead naming the slot.** It is an
  app file. The board's own note (Lead, 2026-09-29) is *"App-file work is serialised … Never two of
  these in the tree together, and never alongside `JB-0.09`"*, and `D.02c` and `D.05` are both
  sitting at `🟦 Ready (after D.02)`. **Three of the specs in flight name this one file — JB-3.06b
  (this row), JB-2.15 and JB-2.13b — and JB-0.10's Q2 wants a fourth** (a hidden gesture on the same
  screen). Four rows, one `Activity`. If the Lead wants the plan layer now, the clean split is to keep
  `AnimExportPlan.kt` + its test as this row and move `AnimExport.kt` and the `Activity` edit into a
  follow-up that owns the file outright. Say so and I will split the spec that way.
- **Do not treat `JbArchive.unsafeReason` as a shared constant.** It is **private**
  (`JbArchive.kt:514`) and lives in `androidkit`, which `commonMain` cannot see at all — so Decision
  3's "the same set" is a *restatement*, not an import. Write the rules out in `commonMain` and put a
  comment on both sides naming the other, so a future reader knows it is one rule in two places. The
  exact set `JbArchive` refuses: an empty name; longer than its `MAX_NAME_CHARS`; any char with
  `code < 0x20` or `code == 0x7F`; a `\`; a leading `/`; a `:`; a path that is nothing but a
  separator; an empty path segment; a segment that is `.` or `..`; and a segment whose
  `trimEnd(' ', '.')` is empty, `.` or `..` (Windows drops trailing dots and spaces, so `".. "` *is*
  the parent folder). **`..` is not refused by a check for the substring `".."`** — it is refused by
  the segment rule, so a builder who writes only the substring check misses `".. "`, `"foo/./bar"`
  and `"a/ /../b"`.

## Stop rule

If anything here is ambiguous, or a claim about landed code turns out to be false when you open the
file, **STOP**: write the question in *Questions* under a heading `for the cross-reviewer`, set this
row `⛔ Blocked`, commit, push, and take another task. Two things are never a builder's call in this
file: **which encoder a format uses** (Q1, Q2 — refuse the format in words instead) and **whether an
app file may be edited** (see the Status line). Never write a file with the right extension and
plausible-looking bytes to get past an unanswered question.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (ties-to-even → test 1 red; `drop(1)` → test 4 red)
- [ ] watcher `build.log` shows `:androidkit:compileKotlin` and `:joybrush-android:compileDebugKotlin` EXECUTED inside `BUILD SUCCESSFUL`
- [ ] `git status --short` shows only the owner-area paths
- [ ] **owner check, Note 9:** export a 6-frame loop as GIF (open it in Gallery — it must loop and
      the timing must be right), as a PNG sequence (open the folder — the manifest must be there),
      and as a sheet + sidecar (open it in SpriteLab — the cells must be in order)
- [ ] committed `JB-3.06b: animation export`; ROADMAP row set by the Lead

## Questions

_(Spec writer: openrouter/stealth/space-bunny-alpha, 2026-09-29. The plan layer is finished and
pinned. Two formats are undecided and one product question is open; none of the three changes a
single line of the code above.)_

### ⛔ for the cross-reviewer — why this is still Draft

1. **The owner area names an app file that two other Ready rows also name.** See the Status line and
   the app-file bullet in *Do not*. This is not a defect in the spec — the spec is right that the
   export sheet belongs on that screen — it is a **dispatch** collision, and it is the Lead's to
   order. A builder handed this file as written would have to decide whether it may edit
   `JoyBrushActivity.kt` at all.
2. **Q1 (MP4) and Q2 (animated WebP) are file-format decisions.** Which encoder writes MP4, and
   whether a third-party bitstream format goes inside `commonMain` or inside `androidkit`, are
   exactly the class of question the cross-reviewer brief forbids answering: *"Contracts, file
   formats, app build files, or anything that touches the phone: you must NOT decide."* Decision 16
   already makes the row safe in the meantime — both formats are **refused in words** and write zero
   bytes — so nothing is half-specified; there is simply no MP4 in this row, and the board's row text
   promises one.
3. **Q3's three product options** (scale factor, transparent background, a range picker in the sheet)
   are product calls the blueprint does not make. The spec has ruled "no" to all three and said so in
   *Do not*, which is defensible, so I did not treat them as blocking.

**Checked and found TRUE** (tree, 2026-09-29): `AnimOps.frameStartsMs(board): List<Double>`
(`AnimOps.kt:461`) and `totalDurationMs(board): Double` (`:442`) exist with the signatures Decision 2
assumes; `frameStartsMs` really does return **starts**, not lengths, so the derivation added to the
contract is needed and is not decoration; `SpritePacker.pack` (`:143`), `assertEncodedSize` (`:72`)
and `data class Clip` (`:28`) exist; `GifEncoder.addFrame(rgba: ByteArray, delayMs: Int)`
(`GifEncoder.kt:436`) matches test 11's description; the board's fps range is `1f..60f` in **two**
places (`DocOps.kt:87` and `AnimOps.kt:543`), so the contract's "1..60" refusal is `AnimOps`' own and
not a second rule. The one claim that needed correcting is in *Do not*: `unsafeReason` is private and
in another module, so it cannot be shared and "the same set" is a restatement.

### Q1 — for the Lead: MP4 has no encoder in reach, and both candidates are awkward

`grep` over the whole `joybrush/` tree finds **no MP4, no `MediaCodec`, no muxer of any kind**. So
"Export MP4" is not a wiring job; it is a new encoder, and there are exactly two roads:

- **(a) Android `MediaCodec` + `MediaMuxer` in `joybrush/androidkit`.** The platform encoder, no
  dependency, H.264 baseline, and every phone since 2014 has it. The costs: it is **untestable off
  a device** (`MediaCodec` needs a real codec; there is no JVM or Robolectric path in this build),
  it needs an `EGL` input surface or a byte-buffer mode with its own colour-conversion rules, and it
  is a few hundred lines of surface handling that no cloud CI can check. The Note 9 is the floor and
  it can encode 1080p30 — but "it can" is a claim I cannot make from here.
- **(b) The app's own media3 pipeline** (`app/…/export/ExportManager.java`, 407 209 bytes, already
  using `Composition`/`EditedMediaItem`/`Transformer`). R23 says *"Integration code that calls kit
  classes lives in `joybrush-android` (the only Joy Brush module in the app build)"* — so this is
  permitted. It is also **the Studio's video exporter**, and driving someone else's 400 KB export
  manager from Joy Brush is a coupling the Lead should choose deliberately rather than have a
  spec-writer assume.

**My recommendation: (a), in its own row (JB-3.06c), tier T2 with a T1 review and a mandatory
device check** — because it keeps `joybrush/` self-contained, which is the blueprint's whole
modularity claim, and because (b) would make Joy Brush's export quality the Studio's export quality
without anybody having decided that. **But the honest cost is that this row would then ship GIF,
PNG sequence and sprite sheet, and MP4 would be a follow-up**, which is not what the ROADMAP row
promises.

**What I need ruled:** (a) or (b), and — if (a) — whether a spec is written for it now or after
this row lands.

### Q2 — for the Lead: **animated WebP cannot be written by anything in this repo**

`Bitmap.CompressFormat.WEBP` writes a **still**. There is no `compressToFile(stream, "image/webp")`
that takes several frames — Android's WebP writer has never had an animation path. So "Export WebP"
for an animation board means one of:

- **(a) Write a WebP (VP8L/VP8 + ANMF chunk) encoder in pure Kotlin**, the way `GifEncoder` was
  written. That is a serious piece of work — container, lossy and lossless bitstreams, a muxing
  chunk format, and unlike GIF there is no `javax.imageio` reader in the JDK to check the result
  against. **There would be no third-party decoder to oracle the file**, which is precisely the
  lesson JB-3.06a learned at cost ("the suite must contain at least one assertion made by a decoder
  that did not write the bytes"). I would want a plan for that oracle before starting, and I do not
  have one.
- **(b) Export a STILL WebP** — the board's current frame, as a single image. Useful, honest, and
  **not** what "Export WebP" on an animation board means to the person who taps it.
- **(c) Drop WebP from this row** and let it arrive with a proper encoder task, the way the still
  formats did.

**My recommendation: (c), with (b) offered as a clearly-labelled "current frame as WebP"** if you
want *something* under the name. But the row says WebP, so the Lead should say which of the three
the row means.

### Q3 — for the Lead: three export options I deliberately did not build

The blueprint's Phase 3 line is a list of five formats and nothing else. Each of these is something
a person will ask for within a week of using the export sheet, and each is a product decision:

1. **A scale factor.** JB-2.13b's PNG export has 1× / 2× / 4×. The animation export has none, and
   `RegionRenderer` renders at 1:1 by definition. **Should an animation export scale, and is
   nearest-neighbour upscaling right for it?** (For pixel art yes; for a painted loop it produces
   the blocky edge the owner dislikes everywhere else.) My inclination is **no scale on the
   animation formats**, so the exported file is exactly what played.
2. **A transparent-background toggle for MP4/WebP.** H.264 has no alpha; WebP does. A video with a
   transparent background is a different file and a different pipeline. **In, or out?**
3. **Exporting a sub-range** (Decision 3) is in the code, but the **export sheet does not offer a
   range picker** — the plan's sub-range is there for JB-3.05 to call and for tests to pin. Should
   the sheet offer "frames 3–7 of 12"? I ruled **no**, because the playback range is already a
   concept on this board (JB-3.05's chip) and two range pickers on one board is one too many.
