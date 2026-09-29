# JB-3.06b — Export the animation board: GIF, PNG sequence, sprite sheet

| | |
|---|---|
| **Tier** | T2 (the plan layer is pure `:core` and is JVM-testable with no canvas and no phone; the encoders are `androidkit`, plain JVM against landed code) |
| **Status** | 🟦 Ready — **ruling R35 applied in full: MP4 is out of this row, WebP is out of this row, no scale factor, no range picker.** The two questions that held this at Draft (Q1 MP4, Q2 animated WebP) are answered by R35 and are no longer questions. The owner area names **no app file**: the export pill belongs to the chrome row (JB-2.01, R30 item 1 and R39), which is *not* this row — see *Questions*. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha` 2026-09-29 · **xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — R35 applied; verified against the landed files: `AnimOps.frameStartsMs` returns **starts, not lengths** (`AnimOps.kt:461`) and `PlaybackClock.kt:64-78` is the landed derivation of a delay from it; `Clip` is `Clip(name, frames, type, fps)` with **no `id`** (`SpritePacker.kt:28`), which the previous contract got wrong; `assertEncodedSize` is a member of `PackedSheet`, not of `SpritePacker` (`:72`); the board's fps range really is in **two** places (`DocOps.kt:87` and `AnimOps.kt:543`) and both reach the caller as a `DocException`, not an `IllegalArgumentException`; `JbArchive.unsafeReason` is **private** (`JbArchive.kt:514`), so the name rules are pasted out of it rather than shared. **Restructured:** the encoder layer no longer names `android.net.Uri` (the repo's own lesson from `JbArchive`/`OraExport`/`PngWriter`, which are pure JVM precisely so they can be tested) — it produces bytes and hands them to a sink, and the SAF/`cacheDir` staging belongs to the wiring row. |
| **Needs** | 3.01 (animation model), 3.06a (`GifEncoder`), 2.13a (`RegionRenderer`), 2.14a (`PngWriter`), 4.03a (`SpritePacker`) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt` · NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlanTest.kt` · NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt` · NEW `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExportTest.kt`. **Nothing else. No app file, no build file, no `document.json` field.** |
| **Estimated size** | ~210 lines of Kotlin in `:core` + ~170 lines of Android-side (JVM) + ~330 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` then `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` — both BUILD SUCCESSFUL, 0 failures |

## Goal

Blueprint §4 Phase 3: *"Export MP4 / GIF / WebP / PNG sequence / sprite sheet"* from the animation
board. **R35 keeps three of those five in this row** — GIF, PNG sequence, sprite sheet — and says
where the other two went, so this spec does not argue with it and does not quietly re-add them.

The design in one sentence: **one plan, three encoders.** Which frames, in what order, held for how
long, at what size is a *pure function of the board*, so it is a `List<Frame>` that a test can check
exhaustively with no canvas, no tiles and no phone; the encoders are dumb consumers of it and add
nothing of their own. Getting that seam right is the whole reason this is one task and not three.

### The two formats this row does NOT ship, and why (R35 — read this before adding either back)

- **MP4 is not in this row and is not a gap in it.** R35: MP4 *is delivered*, by **"Send to Studio"
  (JB-3.07)**, where the Studio's own exporter produces it. There is no in-app MP4 encoder in this
  repo today, and a `MediaCodec` path is a later T2 row with a **mandatory device check** (the
  encoder cannot be exercised off a phone, so it cannot be a cloud-tested row). So the enum in this
  spec has no MP4 entry and the export sheet will not offer one. If you are reading this and thinking
  "just add MediaMuxer", you are about to build the later row inside this one, without its device
  check.
- **Animated WebP is dropped, permanently for now, and here is the reason so nobody re-derives it.**
  Android's `Bitmap.CompressFormat.WEBP` writes a **still**; the platform has never had an animation
  path. A still WebP under the name "Export WebP" on an animation board is a file that is not what
  the person tapped. Writing an animated WebP by hand (VP8/VP8L bitstreams plus the `ANMF`/`ANIM`
  container chunks) is a different order of work from `GifEncoder` **and there would be no
  third-party decoder anywhere in this repo to oracle it against** — and "at least one assertion made
  by a decoder that did not write the bytes" is the lesson JB-3.06a learned at cost. `GifEncoder`
  has `javax.imageio` standing in for the gallery; a hand-written WebP encoder would have nothing.
  **So: three formats, and this is not a temporary gap waiting for a third-party library.**

## Contract — the plan layer (`:core`, pure, testable today)

```kotlin
package cc.joycreator.joybrush.core.export

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.RectPx

// `Clip`, `PackedSheet` and `SpritePacker` are in THIS package, so they need no import — adding one
// would be noise. `DocException` is named in KDoc only, so it is qualified there rather than imported.

/**
 * WHAT to export, as data. Every encoder below is a consumer of this and adds nothing of its own.
 *
 * The delays come from [cc.joycreator.joybrush.core.doc.AnimOps.frameStartsMs] and
 * [cc.joycreator.joybrush.core.doc.AnimOps.totalDurationMs] and are **never** re-derived by
 * accumulating `holdFrames × 1000 / fps` — the same rule as JB-3.05a and JB-3.03, and for the same
 * reason: a boundary one ulp out is a frame of the wrong length in a file somebody else will play.
 */
data class AnimExportPlan(
    /** Frame ids, in play order. The cells of a sprite sheet, and the frames of a GIF or a sequence. */
    val frameIds: List<String>,
    /** How long each frame is shown, in ms, parallel to [frameIds]. Every entry is at least 1. */
    val delayMs: List<Int>,
    /** The picture size, in pixels. Every frame is this size; a board has one rectangle. */
    val width: Int,
    val height: Int,
    /** The board's own fps, for the one format that wants a cadence rather than a list of delays. */
    val fps: Float,
    /** The file name WITHOUT an extension, already through [AnimExport.safeBaseName]. */
    val baseName: String,
    /**
     * The board's own rectangle, not just its size, and every render is at exactly this rect.
     * A board may sit at a negative origin and the export crops to it (the same crop
     * `RegionRenderer` does and the same one `OraExport` makes).
     */
    val rect: RectPx,
) {
    val totalMs: Int get() = delayMs.sum()
    val frameCount: Int get() = frameIds.size
}

object AnimExport {

    /**
     * The whole board, in play order, at [board]'s own rect.
     *
     * @param baseName the file name without an extension. Sanitised by [safeBaseName] — the caller
     *   passes whatever the board is called and does not pre-clean it.
     * @throws cc.joycreator.joybrush.core.doc.DocException on a board with no frames, a board with
     *   two frames of the same id, a board that is not `BoardKind.ANIMATION`, or an fps outside
     *   1..60. **All four are `DocException`, not `IllegalArgumentException`** — TWO of them are
     *   `AnimOps`' own refusals arriving unchanged (the board kind and the fps; it throws
     *   `DocException`, which extends `Exception`, `DocJson.kt:8`), and the other two are this
     *   object's own sentences in the same type, because a sentence is what the export button shows.
     */
    fun plan(board: Board, baseName: String): AnimExportPlan

    /**
     * A SUB-RANGE of the board: frames [first]..[last] inclusive, in play order.
     *
     * Swapped if given backwards and clamped into the board, because that is exactly what
     * `PlaybackClock`'s constructor does with the same two numbers (`PlaybackClock.kt:52-53`), and
     * "export frames 2..5" and "play frames 2..5" must be the same four frames by construction.
     * See Decision 4 and the test that proves it against the clock's own answers.
     */
    fun planRange(board: Board, first: Int, last: Int, baseName: String): AnimExportPlan

    /**
     * The file name that goes in front of every extension: no path separator, no colon, no control
     * character, trimmed, and never empty. **The algorithm is Decision 5 — there is no judgement left.**
     */
    fun safeBaseName(raw: String): String

    /**
     * Columns for the sprite sheet of [frameCount] frames, with no picker and no caller argument:
     * `ceil(sqrt(frameCount))`, at least 1. See Decision 6.
     */
    fun sheetCols(frameCount: Int): Int

    /**
     * The sidecar's ONE clip for [plan]: every frame of the plan as a cell index, **with each hold
     * folded in as a repeat of that index** (`frames = [0, 1, 1, 2]` for holds `[1, 2, 1]`), [type]
     * verbatim, [fps] = the plan's. See Decision 7.
     */
    fun sheetClip(plan: AnimExportPlan, name: String, type: String): Clip

    /**
     * `frameIndex.withIndex { index, frameId -> index to frameId }` — the sparse `cellNames` map
     * `SpritePacker.pack` already writes, so the sidecar says which cell is which frame.
     */
    fun sheetCellNames(plan: AnimExportPlan): Map<Int, String>
}
```

**The landed code this plan layer calls, pasted so nothing above says "see the other file"** (every
line number checked in the tree on 2026-09-29):

```kotlin
// ---- cc.joycreator.joybrush.core.doc.DocModel.kt --------------------------------------------
const val DOC_FORMAT = "joybrush.document"                                  // :6
@Serializable data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int)   // :36
@Serializable enum class BoardKind { CANVAS, ANIMATION, SPRITE, PUPPET, CHARACTER } // :60
@Serializable data class Frame(val id: String, val holdFrames: Int = 1)       // :62
@Serializable data class Board(                                               // :69
    val id: String,
    val name: String,
    val kind: BoardKind,
    val rect: RectPx,
    val clipToBoard: Boolean = false,
    val fps: Float = 12f,                   // ANIMATION only
    val frames: List<Frame> = emptyList(),  // ANIMATION only, IN PLAY ORDER
    val grid: SpriteGrid? = null,           // SPRITE only
)
@Serializable data class Paper(                                              // :38
    val color: String = "#FFFFFF",          // #RRGGBB
    val textureId: String? = null,
    val textureScale: Float = 1f,
    val includeInExport: Boolean = false,   // the "Include paper" checkbox default
)

// ---- cc.joycreator.joybrush.core.doc.DocJson.kt --------------------------------------------
class DocException(message: String) : Exception(message)                     // :8

// ---- cc.joycreator.joybrush.core.doc.AnimOps.kt -- THE ONLY SOURCE OF A FRAME'S LENGTH -------
private const val MAX_HOLD_FRAMES = 999                                      // :100

/** How long one play of [board] lasts, ms: the sum of `holdFrames × 1000 / fps`. */
fun totalDurationMs(board: Board): Double                                    // :442

/**
 * WHEN each frame STARTS, in play order. **THIS RETURNS THE STARTS OF THE FRAMES, NOT THEIR
 * LENGTHS, AND IT IS NOT THE LENGTH LIST.** It has one entry per frame and **no trailing end**.
 * Refuses everything [playableSchedule] refuses.
 */
fun frameStartsMs(board: Board): List<Double>                                // :461

/** Multiply before dividing, so `holdFrames = 3` at 12 fps is exactly 250. */
private fun durationMs(frame: Frame, fps: Double): Double = frame.holdFrames * 1000.0 / fps  // :475

// ---- cc.joycreator.joybrush.core.anim.PlaybackClock.kt -- THE LANDED DERIVATION ----------------
// This is the code that turns starts into lengths, already written and already reviewed. Read it
// before writing the line in Decision 2; it is the reason the rule below exists.
val boardStarts = AnimOps.frameStartsMs(board)                               // :69
rangeStartMs = boardStarts[lo]                                               // :70
val rangeEndMs = if (hi < frames - 1) boardStarts[hi + 1] else AnimOps.totalDurationMs(board) // :71

// ---- cc.joycreator.joybrush.core.export.SpritePacker.kt -------------------------------------
/** One animation over CELL INDICES. **There is no `id` field here — `id` belongs to `pack`.** */
data class Clip(                                                             // :28
    val name: String,
    val frames: List<Int>,     // cell indices in playing order; a repeat is a repeat, not a mistake
    val type: String = "loop", // one of `loop`, `pingpong`, `once`
    val fps: Float = 0f,       // 0 means "inherit the sheet's fps", and is then OMITTED from the sidecar
)

/** NOT a member of `SpritePacker`. It is a method ON the packed sheet. */
fun PackedSheet.assertEncodedSize(fileName: String, pngWidth: Int, pngHeight: Int)  // :72
// -> IllegalStateException if the encoded PNG is not width x height. Call BEFORE writing either file.

fun SpritePacker.pack(                                                       // :143
    cells: List<ByteArray>,      // straight RGBA8, all cellW x cellH, in reading order
    cellW: Int,
    cellH: Int,
    cols: Int,                   // rows = ceil(cells.size / cols)
    id: String,                  // the sidecar's `id`
    name: String,                // the sidecar's `name`
    sheetFileName: String,       // written verbatim as `sheetUri`, RELATIVE — e.g. "Walk.png"
    fps: Float,                  // the sheet's default cadence, always written
    clips: List<Clip>,           // `presets` is written only when this is non-empty
    cellNames: Map<Int, String> = emptyMap(), // sparse, ascending index order
): PackedSheet
// Refuses (IllegalArgumentException): a cell that is not cellW x cellH RGBA, cols < 1, no cells,
// a cellName or clip frame naming a cell index the sheet does not hold, a clip with no frames or
// an unknown type, a non-finite/negative fps, and a sheet needing more than Int.MAX_VALUE bytes.

// ---- cc.joycreator.joybrush.core.export.GifEncoder.kt ---------------------------------------
class GifEncoder(val width: Int, val height: Int, val loop: Boolean = true)  // :411
fun GifEncoder.addFrame(rgba: ByteArray, delayMs: Int)                       // :436
// -> IllegalArgumentException if `rgba` is not exactly width*height*4. Straight RGBA8, row 0 = TOP.
// `finish(): ByteArray` (:456) is the whole file; IllegalStateException if no frame was added.
// Internally: `delayCentiseconds` (:372) is `(ms + 5) / 10` clamped to 2..65535 cs. **Do not
// second-guess it.** `MAX_SCREEN = 0xFFFF` (:75).

// ---- cc.joycreator.joybrush.core.render.RegionRenderer.kt ------------------------------------
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? } // :24
const val MAX_REGION_PX = 8_388_608L                                          // :89

fun RegionRenderer.render(
    doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?,
): ByteArray                                                                // :196
// -> STRAIGHT (un-premultiplied) RGBA8, `rect.w * rect.h * 4` bytes, row 0 = TOP.
// -> RegionException above MAX_REGION_PX; IllegalArgumentException for a negative side or a
//    `paper` that is neither null nor a `#RRGGBB` string.

// ---- cc.joycreator.joybrush.androidkit.io.JbArchive.kt -- the rules safeBaseName RESTATES ----
private const val MAX_NAME_CHARS = 512                                        // :104
private fun unsafeReason(name: String): String?                              // :514  **PRIVATE**
```

### `frameStartsMs` returns STARTS. Here is the whole derivation, in one line, with its witness.

`delayMs[i]` is a **length**, and `frameStartsMs` hands back a **start**. So:

```kotlin
val starts = AnimOps.frameStartsMs(board)              // one per frame, NO trailing end — already ms
val total  = AnimOps.totalDurationMs(board)            // already ms
delayMs[i] = floor(
    (if (i < starts.lastIndex) starts[i + 1] - starts[i] else total - starts[i]) + 0.5
).toInt().coerceAtLeast(1)
```

**There is no `× 1000` in that line.** `frameStartsMs` and `totalDurationMs` are already in
milliseconds; the `1000 / fps` inside them has been applied. Multiplying a millisecond difference by
1000 is how a 12 fps board exports at 12 000× the right speed, and it is the exact mistake the
`holdFrames × 1000 / fps` prohibition is aimed at wearing a different hat.

The last frame is the one that is easy to get wrong, and it is why `totalDurationMs` is called at
all: there is no `starts[n]` to difference against for the final frame. `PlaybackClock.kt:69-78`
does exactly this and has been reviewed; a builder who finds a neater-looking way to write it is
re-deriving a definition that already exists.

## Contract — the encoder layer (`androidkit`, plain JVM, **no `android.*` import in this file**)

```kotlin
package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.export.AnimExportPlan

// `JbContents`, `JbArchiveException` and `PngWriter` are all in THIS package, so they need no import.

/**
 * The three formats this row exports. **MP4 and WebP are deliberately absent** — MP4 is delivered by
 * "Send to Studio" (JB-3.07, R35) and animated WebP cannot be written by anything in reach (see the
 * note at the top of this spec). Adding either is a different row with its own reasons.
 */
enum class AnimFormat(val label: String, val extension: String, val mime: String) {
    GIF("GIF", "gif", "image/gif"),
    PNG_SEQUENCE("PNG sequence", "png", "image/png"),
    SPRITE_SHEET("Sprite sheet", "png", "image/png"),
}

/**
 * One file an export produced, in memory.
 *
 * **NOT a `data class`, and that is a decision.** A generated `equals` on a `ByteArray` compares
 * arrays by REFERENCE, so two `AnimFile`s holding identical bytes would say they differ — the exact
 * trap `JbContents` (`JbArchive.kt:39-42`) and `OraExport.Thumb` (`OraExport.kt:53-58`) already
 * document and already work around. A plain class has no such `equals` to mislead anyone.
 */
class AnimFile(val name: String, val mime: String, val bytes: ByteArray)

/**
 * Where the bytes go. The **caller owns the folder, the SAF `Uri` and the `cacheDir` staging** —
 * this row has no `android.net.Uri` and no `Context` anywhere in it, exactly as `JbArchive`,
 * `OraExport` and `PngWriter` do not, and for exactly the reason they do not: `android.jar` is
 * `compileOnly`, so an `android.*` type in a signature here would put `NoClassDefFoundError` between
 * this row and every test below it. Staging into `cacheDir` and streaming to the `Uri` is R11 and
 * belongs to the row that owns the screen (see *Questions*).
 */
fun interface AnimFileSink {
    fun write(name: String, mime: String, bytes: ByteArray)
}

object AnimExportRunner {

    /**
     * GIF or the sprite sheet: **every byte, or an exception.** Nothing is written by this call and
     * nothing is written until it has returned, which is R11's rule satisfied by construction
     * rather than by remembering it.
     *
     * @param includePaper `doc.paper.color` as the backdrop, or null. The CALLER defaults this to
     *   `doc.paper.includeInExport`; this row writes nothing back to the document (Decision 11).
     * @param cols the sheet's columns; ignored by `GIF`. `[AnimExport.sheetCols]` is the default.
     * @param onFrame `(done, total)` after each frame is rendered, so a 40-frame export says
     *   "frame 7 of 40" instead of freezing. Never called before the first refusal, which is why
     *   Decision 8 can claim "refused before frame 1" and test it.
     * @return the files written, in the order a reader must receive them.
     * @throws cc.joycreator.joybrush.core.doc.DocException if [plan]'s frame ids are not all frames
     *   of board [boardId] — the one consistency check this call makes, and it names the board and
     *   the frame that is wrong.
     */
    fun encodeOne(
        format: AnimFormat,
        contents: JbContents,
        boardId: String,
        plan: AnimExportPlan,
        includePaper: Boolean,
        cols: Int = AnimExport.sheetCols(plan.frameCount),
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AnimFile>

    /**
     * The PNG sequence: frames in order, then the manifest, into [sink], in that order.
     *
     * The manifest is written LAST and only after the last frame has been accepted by [sink], which
     * is what makes "an incomplete sequence is recognisable by its missing manifest" true instead of
     * aspirational (Decision 12).
     */
    fun writeSequence(
        contents: JbContents,
        boardId: String,
        plan: AnimExportPlan,
        includePaper: Boolean,
        sink: AnimFileSink,
        onFrame: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<String>

    /** The one rule both halves obey, as a function so a test can call it: the file name for [n]. */
    fun sequenceName(n: Int): String   // 1 -> "0001.png", 10000 -> "10000.png"
}
```

## Decisions

1. **One plan, three encoders, and the plan is a `data class` a test can assert field by field.**
   *Why:* the moment each format computes its own delays, two formats drift — and the drift arrives
   as "the GIF is a frame short", which is a bug report with no cause anybody can find.
2. **`delayMs[i]` is frame `i`'s own length in ms, taken from the starts, and it is
   `floor(x + 0.5)` with a floor of 1.** Every delay is positive, so `floor(x + 0.5)` *is* "round half
   up", which on a positive number is the same as "ties away from zero"; write it as `floor` and say
   so in the KDoc, because `kotlin.math.round` is **ties-to-even** and at 16 fps a hold of 1 is
   exactly `62.5` ms, where the two disagree (`62` vs `63`). *Why:* a delay of 0 is a frame nobody
   ever sees — a silent frame loss — so the floor is 1, not 0.
3. **The delays are the only timing source and they come from `AnimOps`, indexed.** No accumulating
   `holdFrames × 1000 / fps` anywhere in this row. *Why:* `AnimOps` is where the 1..60 fps rule and
   the multiply-before-divide order live; a second derivation is a second answer that can differ in
   the last ulp, and the last ulp is a frame boundary in a file somebody else plays.
4. **A sub-range is swapped and clamped exactly as `PlaybackClock`'s range is, and the export sheet
   does not offer a range picker** (R35: no range picker). `planRange` exists because JB-3.05's range
   chip and the tests need it, not because a person picks one. *Why:* an export that disagrees with
   what played is the one bug a person cannot argue with; and two range pickers on one board is one
   too many.
5. **`safeBaseName` sanitises rather than refuses, and falls back to `"Animation"`.** The algorithm,
   in order, is:
   ```
   val s = raw
       .filter { it.code >= 0x20 && it.code != 0x7F }   // 1. drop control chars
       .replace(Regex("[/\\\\:*?\"<>|]"), " ")           // 2. separators + Windows-illegal -> ONE space
       .replace(Regex(" +"), " ")                        // 3. collapse the runs that step 2 made
       .trim()                                            // 4. trim
       .trim('.', ' ')                                   // 5. Windows drops trailing dots and spaces
   val capped = if (s.length > 512) s.take(512).trim('.', ' ') else s   // 6. MAX_NAME_CHARS
   return if (capped.isEmpty()) "Animation" else capped                  // 7. the named fallback
   ```
   `"C:evil"` → `"C evil"`, `"../etc/passwd"` → `"etc passwd"`, `"a\u0000b"` → `"ab"`, `"///"` →
   `"Animation"`. *Why:* a person is not typing this into a field — it is a board's name — so a hard
   failure would be worse than a sensible default, and the fallback is *named* so a test can assert
   it. This is one rule in two places and not a shared constant: `JbArchive.unsafeReason` is
   **private** and lives in `androidkit`, which `commonMain` cannot see at all (the dependency runs
   the other way — `androidkit/build.gradle.kts:45`). The rule it is copied from, for the reader who
   has to keep them in step, is pasted verbatim in *Do not*.
6. **`sheetCols(frameCount) = max(1, ceil(sqrt(frameCount)))` — no picker, no caller argument, no
   scale factor** (R35). 1→1, 4→2, 5→3, 9→3, 10→4. *Why:* a 1×N strip is unreadable in a file
   manager and every reader has to scroll it; a near-square grid is what a sheet is for. And it
   holds **no copy** of `SpritePacker`'s limits — `pack` still refuses what it refuses
   (`SpritePacker.kt:201` for a sheet that will not fit an array), and that refusal is the one the
   user hears, reached by calling it.
7. **The sidecar's clip folds each hold in as a REPEAT of that cell's index**, and that is how a
   hold survives into a format that only has an `fps` and a list of indices: `Clip.frames` is
   "cell indices in playing order; **a repeat is a repeat, not a mistake**" (`SpritePacker.kt:22`),
   which is how a ping-pong animation is written and how a held frame is played. Holds
   `[1,2,1,3]` over four frames become `[0,1,1,2,2,2,3]`. One clip, covering every exported frame,
   `type` written verbatim from the app's own three words, `fps` = the board's.
   *Why:* the alternative is a sidecar that plays a double-hold at single speed, which is the exact
   defect R36 called a MAJOR for the Studio's own sheets — and `Clip` has **no `weights` field today**
   (`SpritePacker.kt:28-33`), so a spec that wrote weights would be writing a field that does not
   exist. If JB-4.03c adds `Clip.weights` later, this row must keep using repeats and **must not
   start writing weights**; see *Questions*.
8. **Everything that can be refused is refused BEFORE the first byte and before the first frame.**
   The board rect is checked against `MAX_REGION_PX` in `Long` arithmetic (`4000 × 4000` is refused;
   `onFrame` has not been called); the fps and the frame ids come from `plan()`, which is where those
   refusals happen at all. *Why:* the renderer's own rule is that every exporter catches
   `RegionException` and says what it wanted in a sentence, and catching it in the plan makes the
   sentence name the board.
9. **Every frame is rendered at the BOARD's rect, at the board's own size.** A board is one
   rectangle; a GIF or a sheet of mixed sizes is a file other apps will not open, and a frame with
   nothing on it is still the size of the board — that is what "the picture at this frame" means and
   `RegionRenderer.render` already answers exactly that question.
10. **No scale factor, no background colour, no transparent-background toggle, no "reverse the
    animation"** (R35 rules the first two out; the blueprint asks for neither of the others).
    `RegionRenderer` renders at 1:1 by definition, and the exported file is exactly what played.
11. **Paper follows `doc.paper.includeInExport` and this row writes NOTHING back.** `includePaper`
    is a parameter of `encodeOne`/`writeSequence`; the caller supplies the document's own setting.
    *Why:* blueprint §2 makes the checkbox a document setting, and persisting it is the save queue's
    job (JB-0.08b's), not an exporter's. A writer that edited the document would need to mark it
    dirty and would be the second thing in the app doing that.
12. **A PNG sequence is `NNNN.png` from `0001`, then `<baseName>.timing.txt` last.** The manifest is
    UTF-8 with LF line endings, one line per frame in play order, `<fileName><TAB><delayMs>`, no
    header, a trailing LF on the last line. `sequenceName` pads to **at least** four digits and does
    not truncate: 10 000 frames give `10000.png`, which is longer and still sorts correctly, and a
    silent truncation would be a file with two frames of the same name. *Why (name):* R35 fixed the
    name — the earlier draft's `NNNN.txt` was a literal typo for the manifest, and the manifest is a
    *document* beside the images, not one of them. *Why (the manifest at all):* the delays are the
    whole content of a sequence and a folder of PNGs has none, so without it the export is a folder;
    with it, it is a document anybody can play at the right speed.
13. **The sprite sheet's two files go out together, or neither does.** `SpritePacker.pack` first,
    then `PackedSheet.assertEncodedSize(sheetName, pngW, pngH)` (it is a method **on the packed
    sheet**), then both writes. *Why:* a `.sprite.json` describing a PNG that is not the right size
    is the pair of files that disagree, and the packer exists to give that check one name and one
    place.
14. **GIF is `GifEncoder` with `loop = true` and the plan's delays**, and the encoder's own
    centisecond rounding (`(ms + 5) / 10`) and 2 cs floor apply untouched. *Why:* the encoder is
    Built and cleared, `GifEncoderTest`/`GifDecodeTest` pin its rounding, and a second rounding here
    would be a second answer. The plan's ms are the input; the encoder owns the unit.
15. **The sidecar's `sheetUri` is the sheet's own file name, written verbatim and relative**, and
    `cellNames` maps every cell index to its frame id. *Why:* `sheetUri` is resolved relative to the
    sidecar (`SpritePacker.kt:130`), so it is a name and never a path; and `cellNames` is the app's
    own sparse key, already written by the packer, so it adds no key the app does not read.
16. **A failure reaches the user as a sentence that says how far it got**, and a sequence that
    failed has no manifest. `"Exported 19 of 40 frames — frame 20 would not fit in memory."`
    *Why:* the house rule from JB-0.08b, and the only thing that makes a 40-frame export on a Note 9
    debuggable.
17. **The test command is two commands, and the first is the whole `:core` suite.** A new `:core` file
    that broke an unrelated test would otherwise be a green row.

## Decision → Test map (every Decision is checkable)

| Decision | Pinned by |
|---|---|
| 1 (one plan, three encoders) | `everyFormatConsumesTheSamePlan` |
| 2 (starts → lengths, half-up, floor 1) | `aTieGoesTo63Not62`, `noDelayIsEverZero` |
| 3 (`AnimOps` is the only timing source) | `theDelaysAreDifferencesOfTheRealStarts` |
| 4 (sub-range = the clock's range; no picker) | `aSubRangeIsSwappedAndClampedLikeTheClock` (against `PlaybackClock`'s own answers) |
| 5 (name algorithm + fallback) | `aNameWithASeparatorIsSanitisedNotHonoured`, `anEmptyNameFallsBackToAnimation` |
| 6 (cols from `sqrt`, no picker, no scale) | `theSheetIsNearSquare` |
| 7 (holds folded as repeats) | `aHoldIsARepeatedCellIndex`, `theSheetCarriesExactlyOneClip` |
| 8 (refused before the first byte) | `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered` |
| 9 (one size, the board's rect) | `everyFrameIsTheBoardsOwnRect` |
| 10 (no scale, no toggles) | review of the enum + `Do not`; a scale factor cannot be added without changing `sheetCols`' signature |
| 11 (paper from the document, nothing written back) | `paperFollowsTheDocumentsOwnSettingAndWritesNothingBack` |
| 12 (0001 + `<name>.timing.txt` last) | `aSequenceIsNumberedFromOneWithFourDigits`, `theManifestIsWrittenLastAndOnlyAfterEveryFrame` |
| 13 (sheet and sidecar agree or neither) | `theEncodedSizeIsCheckedBeforeEitherFileIsWritten` |
| 14 (the encoder owns the cs rounding) | `gifDelaysAreTheEncodersOwnCentiseconds` — **decoded by `javax.imageio`** |
| 15 (relative sheetUri + cellNames) | `theSidecarNamesEveryCellAndPointsAtTheSheetBesideIt` |
| 16 (a sentence that says how far) | `anInterruptedSequenceHasNoManifestAndSaysHowFarItGot` |
| 17 (two commands) | pasted output |

## Tests

### A. `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlanTest.kt`

`commonTest`, not `jvmTest`: this half touches no file and no decoder. Fixture — a 12 fps ANIMATION
board, holds `[1, 2, 1, 3]`, frames `f0..f3`, rect `(0, 0, 64, 48)`; a second board at rect
`(−40, 300, 32, 32)`; a 16 fps board for the tie; a board at 3 fps.

**Every expected delay in this file is written as an expression over `AnimOps.frameStartsMs`, never
as a hand-typed `hold × 1000 / fps`, and the file's KDoc says why** (JB-3.05a rule 1, verbatim). The
only hand-typed numbers in the whole file are the tie cases, and those are exact halves.

1. `theDelaysAreDifferencesOfTheRealStarts`: for the 12 fps fixture, `delayMs == [83, 167, 83, 250]`
   **and** every entry equals `round(starts[i+1] - starts[i])` computed in the test from
   `AnimOps.frameStartsMs` (and the last from `totalDurationMs - starts.last()`). Asserted both ways
   so a builder cannot satisfy it by coincidence.
2. `aTieGoesTo63Not62`: a 16 fps board, one frame, hold 1 → `1000/16 = 62.5` exactly → **63**. The
   test's comment names `kotlin.math.round` (ties-to-even) as the wrong answer, which returns 62.
   A second case: 16 fps, hold 3 → `187.5` → **188**.
3. `noDelayIsEverZero`: fps 1..60 × hold 1..999 — 59 940 cases — **every delay ≥ 1**. (The smallest
   possible is `1000/60 = 16.67 → 17`.)
4. `everyFrameIsTheBoardsOwnRect`: for the board at `(−40, 300, 32, 32)` the plan's `width`/`height`
   are `32`/`32` and its `rect` is `RectPx(−40, 300, 32, 32)` verbatim. The **negative origin is
   irrelevant to the size** — that is the half a builder gets wrong.
5. `aSubRangeIsSwappedAndClampedLikeTheClock`: **not against a second implementation.** Build a real
   `PlaybackClock(board, PlaybackMode.LOOP, firstFrame = first, lastFrame = last)` and enumerate the
   frame indices it plays by walking `nextChangeMs(elapsed)` from 0 to `rangeMs`; the plan's
   `frameIds` must equal `board.frames[thoseIndices]`. Cases: `(3, 1)`, `(0, 99)`, `(−5, −9)`,
   `(2, 2)`, and the whole board.
6. `anEmptyBoardIsRefusedInWords` / `aBoardWithTwoFramesOfTheSameIdIsRefused` / `aBoardAtZeroFpsIs
   RefusedInWords`: all three `DocException`, and each message **names the board** (and the fps, for
   the third — the message comes from `AnimOps.playableFps` and already does).
7. `aNameWithASeparatorIsSanitisedNotHonoured`: `"../etc/passwd"`, `"a/b"`, `"a\\b"`, `"C:evil"`,
   `"a\u0000b"`, `"x:y|z?"`, `"trailing dot . "`. Assert the **properties** of each result — no `/`, no
   `\`, no `:`, no char below `0x20` or equal to `0x7F`, no leading or trailing space or dot, length
   ≤ 512 — and not one expected string, so a builder cannot pass by hard-coding the fixture's
   answers.
8. `anEmptyNameFallsBackToAnimation`: `""`, `"   "`, `"///"`, `"..."`, `"   .  "` → `"Animation"`.
9. `theSheetIsNearSquare`: `sheetCols` for 1, 2, 3, 4, 5, 9, 10, 40 → `1, 2, 2, 2, 3, 3, 4, 7`.
   Assert **both** ends: the exact value, and that `cols × ceil(n/cols)` is within `sqrt(n) + cols` of
   `n` (so it is near-square and not a strip).
10. `aHoldIsARepeatedCellIndex`: for the fixture, `sheetClip(plan, "Walk", "loop").frames ==
    listOf(0, 1, 1, 2, 2, 2, 3)` — 7 entries for 4 frames and holds summing to 7 — with
    `type == "loop"` and `fps == plan.fps`. And `planRange(board, 1, 2, …)` gives `[1, 1, 2]`.
11. `theSheetCarriesExactlyOneClip`: packing with the real `SpritePacker.pack` produces a sidecar
    whose `presets` array has **one** entry, whose `frames` array is that folded list, and whose
    `frames` has no index ≥ the cell count. Asserted by **parsing the JSON string the packer
    returned**, so the file that would be written is what is checked.
12. `theSheetIsAcceptedOrRefusedByThePackerItself`: `sheetCols(40)` = 7, pack 40 cells in 7 columns,
    and assert that whatever `pack` says happens (succeeds, or throws naming the numbers) — the test
    **delegates** rather than predicting a limit this file does not own.
13. `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered`: a rect of `4000 × 4000` (16 M px > `MAX_REGION_PX`
    = 8 388 608) → `RegionException`, and a counter the `onFrame` callback would have incremented is
    **0**. (This half of the test lives in the `androidkit` file below, where the runner is; here it
    is a `plan` that simply cannot be built.)

### B. `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExportTest.kt`

`androidkit`'s test source set, **not `commonTest`**: this half decodes files with
`javax.imageio`, which is JVM-only. `core/src/jvmTest` (`GifDecodeTest`) is the precedent and the
reason the module's other exporter tests live where they do. Fixture: build a `JbContents` the way
`OraExportTest` does — a `JbDocument` with two PAINT layers over one board, and
`tiles = mapOf(Triple(layerId, celId, "tx_ty") to a solid 256×256 tile)` — and a
`TileSource` from it: `TileSource { l, c, tx, ty -> contents.tiles[Triple(l, c, DocOps.key(tx, ty))] }`
(the exact line `OraExport.kt:358-360` uses).

1. `gifDecodesToThePlanAtTheEncodersOwnCentiseconds`: 6 frames, export a GIF, **decode it with
   `javax.imageio`**, and read each frame's Graphic Control Extension delay. The plan's ms →
   `(ms + 5) / 10`, clamped to 2 — written out in the test with the encoder's formula beside it, and
   the total decoded delay compared with `round(plan.totalMs / 10)` to within one centisecond. The
   decoder did not write these bytes; that is the point of this test and it is why it is not an
   assertion about the encoder's own idea of what it wrote.
2. `theLoopExtensionIsPresentAndTheFrameCountMatchesThePlan`: the decoder sees **6** frames, and
   `frames.size == 6`. Asserted on the decoded file.
3. `everyFormatConsumesTheSamePlan`: for one board, the GIF's decoded per-frame delays, the sheet's
   folded `frames` list, and the sequence manifest's delay column are all the **same** list. This is
   the test that makes Decision 1 real rather than asserted.
4. `aSequenceIsNumberedFromOneWithFourDigits`: 12 frames → `0001.png` … `0012.png`, asserted on the
   names the sink recorded, in order. Plus `sequenceName(10_000) == "10000.png"` — longer, never
   truncated, never colliding.
5. `theManifestIsWrittenLastAndOnlyAfterEveryFrame`: a recording sink; assert the recorded order is
   12 PNGs and **then** `<baseName>.timing.txt`, and that the manifest's 12 lines parse to exactly
   the plan's `delayMs`.
6. `anInterruptedSequenceHasNoManifestAndSaysHowFarItGot`: a sink that throws on the 20th call. The
   recorded names are `0001.png` … `0019.png` and **no** `.timing.txt`; the thrown message (or the
   returned warning) contains `19` and `20`.
7. `aFailedSingleFileExportLeavesNothingToWrite`: a sink that throws on the first call for the GIF
   and for the sheet; assert `out.size == 0` because `encodeOne` builds everything **before** the
   sink is ever touched. This is the R11 property, made measurable.
8. `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered`: `4000 × 4000` → `RegionException`, and the
   `onFrame` counter is **0**.
9. `paperFollowsTheDocumentsOwnSettingAndWritesNothingBack`: with `includePaper = true` the decoded
   GIF's corner pixel is `doc.paper.color` where no layer covers; with `false` the same pixel has
   alpha 0. Then: `contents.doc` is **byte-identical** (`assertEquals` on the document, and on every
   tile's `contentEquals`) before and after every export in this file. That assertion is what makes
   "writes nothing back" a fact.
10. `aPaperColourThatIsNotAColourIsRefusedInWords`: `doc.paper.color = "white"` → refused with a
    sentence naming the colour, **before** any render (`onFrame` counter 0).
11. `theEncodedSizeIsCheckedBeforeEitherFileIsWritten`: `encodeOne(SPRITE_SHEET, …)` returns two
    `AnimFile`s, the first `.png` and the second `.sprite.json`; the sidecar's `sheetUri` equals the
    PNG's `name` **verbatim**, and the PNG's bytes, decoded by `javax.imageio`, are exactly
    `cols × width` by `ceil(n / cols) × height`.
12. `theSidecarNamesEveryCellAndPointsAtTheSheetBesideIt`: the parsed `cellNames` has one entry per
    cell index, mapping to the plan's frame ids; `sheetUri` contains no `/` and no `..`.
13. `aPlanForAnotherBoardIsRefusedInWords`: a plan whose `frameIds` include a frame the board does
    not have → `DocException` naming the board and the frame, and the sink sees **zero** calls.
14. `anInvisibleLayerIsStillRendered`: a document with a **locked** layer and a **hidden** layer
    exported — hidden contributes nothing (it is not painted), and the assertion is only that the
    export succeeds and the composite equals `RegionRenderer`'s own bytes for the same document,
    which is the property `RegionRenderer`'s KDoc promises about locks.
15. **Non-vacuity, which the builder must run and paste:** change Decision 2's rounding from
    `floor(x + 0.5)` to `kotlin.math.round` and watch test A2 go red; then change `delayMs` in
    Decision 2 to `starts[i] - (if (i > 0) starts[i-1] else 0.0)` **off by one** (i.e. use
    `starts[i+1] - starts[i+1]`) and watch A1 go red; then make `sheetClip` emit `0 until n` instead
    of folding holds, and watch B3 go red. Those three mutations are the three ways this row can be
    wrong while looking finished.

**Command:** `./gradlew -p joybrush :core:jvmTest` (the plan layer, and the whole core suite — 0
failures) then `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` (0 failures). Both
must be BUILD SUCCESSFUL. **Do not pass `-Pjoybrush.androidJar=<path>`**: it is a *fallback*, not a
requirement (`androidkit/build.gradle.kts:19` tries it first, then `$ANDROID_HOME`,
`$ANDROID_SDK_ROOT`, then `sdk.dir` in the repo's `local.properties`). A machine with an SDK
configured passes nothing at all.

## Do not

- **Do not add an MP4 or an animated WebP to `AnimFormat`.** R35. MP4 is JB-3.07's; WebP cannot be
  written and could not be checked. An `AnimFormat` entry that the runner refuses is a dead control
  (R39: a dead control is worse than a missing one).
- **Do not re-derive a frame's length.** `AnimOps.frameStartsMs` / `totalDurationMs`, indexed. The
  line is in *Do not* because it is the mistake this codebase has already made once.
- **Do not write `holdFrames * 1000.0 / fps` anywhere in this row**, not even "just to check". It is
  a second definition of a number `AnimOps` owns.
- Do not use `kotlin.math.round` for a delay (Decision 2) and do not invent a second copy of
  `GifEncoder`'s centisecond rule, `SpritePacker`'s limits or `MAX_REGION_PX`. Ask, catch, delegate.
- Do not use `ceil(sqrt(n))` as anything other than a column count — **no scale factor** means the
  export is at the board's own pixels, and `RegionRenderer` renders at 1:1 by definition.
- Do not fold a hold any other way than as a repeated cell index, and do not write `Clip.weights`
  (it does not exist; JB-4.03c may add it later, and this row then keeps using repeats).
- Do not put `android.net.Uri`, `android.content.ContentResolver` or any other `android.*` type in
  `AnimExport.kt`. `android.jar` is `compileOnly`; a signature naming one puts
  `NoClassDefFoundError` between this file and every test above, which is why `JbArchive`,
  `OraExport` and `PngWriter` all take an `OutputStream` instead.
- Do not write to a destination incrementally for a single-file format. R11: build it whole, then
  stream. `encodeOne` already gives you that for free — do not undo it to save memory.
- Do not touch `DocModel.kt`, `DocJson.kt`, `JbArchive.kt`, `Blend.kt`, `RegionRenderer.kt`,
  `SpritePacker.kt` or `GifEncoder.kt`. This row adds files; it edits no landed file.
- No new dependencies.
- Do not `git add` anything outside the four owner-area paths.
- **Do not treat `JbArchive.unsafeReason` as a shared constant.** It is **private**
  (`JbArchive.kt:514`) and lives in `androidkit`, which `commonMain` cannot see — so Decision 5's
  rules are a **restatement**, not an import. Put a comment on both sides naming the other. The
  exact set `JbArchive` refuses (`JbArchive.kt:514-538`, pasted so it can be checked against):
  an empty name; a name longer than `MAX_NAME_CHARS` (512); any char with `code < 0x20` or
  `code == 0x7F`; a `\`; a leading `/`; a `:`; a path that is nothing but a separator (after ONE
  trailing `/` is dropped — a single trailing slash is a directory marker and is allowed); an empty
  path segment; a segment that is `.` or `..`; and a segment whose `trimEnd(' ', '.')` is empty, `.`
  or `..` — Windows drops trailing dots and spaces, so `".. "` **is** the parent folder.
  **`..` is not refused by a check for the substring `".."`** — it is refused by the segment rule, so
  a builder who writes only the substring check misses `".. "`, `"foo/./bar"` and `"a/ /../b"`.
  `safeBaseName` does not need the segment rules at all, because it removes every separator in step 2.

## Stop rule

If anything here is ambiguous, or a claim about landed code turns out to be false when you open the
file, **STOP**: write the question in *Questions* under the heading `for the Lead`, set this row
`⛔ Blocked`, commit, push, and take another task. Three things are never a builder's call in this
file: **the encoding of a format** (this row composes landed encoders; it invents none), **where the
files land** (SAF tree, `MediaStore`, `cacheDir` staging — the wiring row's, R11), and **what the
export sheet offers** (the chrome row's, JB-2.01). Never write a file with the right extension and
plausible-looking bytes to get past an unanswered question.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` output pasted, 0 failures
- [ ] the non-vacuity proof pasted (three mutations, three red tests)
- [ ] `git status --short` shows only the four owner-area paths
- [ ] **owner check, Note 9 (📱 the owner, not the builder):** export a 6-frame loop as GIF — open it
      in Gallery: it loops and the timing is right; export the same loop as a PNG sequence — open the
      folder: `0001.png`… and `<name>.timing.txt` are both there; export it as a sheet — open the
      folder in SpriteLab: the cells are in order, **and a frame with a hold of 2 is held for twice
      as long when the sidecar plays it.**
- [ ] committed `JB-3.06b: animation export`; the ROADMAP row is set by the Lead (this spec does not
      touch `ROADMAP.md`)

## Questions

_(Spec writer: `openrouter/stealth/space-bunny-alpha`, 2026-09-29. R35 is applied above; the
questions left are board questions and two provisional calls, not design decisions.)_

### For the Lead — three things, none of which a builder can settle

1. **The export pill is not in this row's owner area any more, and that is a change.** The previous
   draft of this spec had `JoyBrushActivity.kt` in the owner area, which is an app file. R30 item 1
   puts the chrome row (JB-2.01) **before any other row adds a pill**, and R39's JB-2.01 entry lists
   `Export` as part of the cluster the owner will approve — so the export sheet is **JB-2.01's**, and
   this row delivers the three formats plus the exact names/labels/warnings a sheet must show
   (`AnimFormat.label` is in the contract for that reason). **Consequence:** when JB-2.01 lands, it
   wires `AnimFormat` into the cluster. If you want the sheet to land *with* the formats, say so and
   I will split this spec: the plan layer and the runner as JB-3.06b, and a follow-up row that owns
   `JoyBrushActivity.kt` outright — but that row has to be **added to the R30 lock order**, because
   right now it is not in it, which is what made this row undispatchable in the first place.
2. **Where the bytes are written is the wiring row's, and I have not specified it.** `AnimExport.kt`
   has no `Uri` in it, so the SAF/`MediaStore`/`cacheDir` decision is deliberately *not made here*.
   If you want it in this row, the obvious answer is R36's landed pattern for 4.03b — `Documents/
   JoyBrush/<name>/` through `MediaStore` on API 29+, the app's own folder below that — but it is a
   decision that touches the phone, so it is yours. **PROVISIONAL — Claude to confirm** if you would
   rather I fold it in with the R36 pattern.
3. **JB-4.03c and `Clip.weights`.** R36 gives `Clip` a `weights: List<Int>` (a cell held ×3
   currently exports at the wrong speed) via new row **JB-4.03c**. That row has **not** landed: I
   opened `SpritePacker.kt` and `Clip` is still `Clip(name, frames, type, fps)` with no `weights`,
   and `pack` writes no weights key. **This row does not depend on 4.03c** — Decision 7 folds a hold
   into a repeated cell index, which is the app's own vocabulary (`Clip.frames`: *"a repeat is a
   repeat, not a mistake"*) and is exact at a uniform board fps. What I need is a **one-line
   instruction for later**: when 4.03c lands, JB-3.06b must keep using repeats and must not start
   writing `weights` (and must not be re-reviewed for it). If you would rather this row simply wait
   for 4.03c, say so and it goes in `Needs`.

### PROVISIONAL — Claude to confirm

4. **Decision 7 (holds as repeats) instead of waiting for `weights`.** Chosen because it uses only
   what the landed packer writes and makes all three formats carry the timing exactly today.
5. **Decision 12's manifest format** (`<fileName><TAB><delayMs>`, LF, UTF-8, no header). Nobody else's
   app reads it, so nothing can contradict it later.
6. **Decision 6's `ceil(sqrt(n))`.** A near-square grid, no picker (R35).

### Checked against the landed code — true, so nobody re-checks it

`AnimOps.frameStartsMs(board): List<Double>` (`AnimOps.kt:461`) and `totalDurationMs(board): Double`
(`:442`) exist with the shapes Decision 3 assumes; `frameStartsMs` really does return **starts with
no trailing end**, which is why the derivation above is needed and why `totalDurationMs` is called
for the last frame — and `PlaybackClock.kt:69-78` is that same derivation, already landed and
reviewed, so the claim is copied from a reviewer rather than re-derived. `durationMs` multiplies
before dividing (`:475`) and `MAX_HOLD_FRAMES` is 999 (`:100`). `Clip` is
`Clip(name, frames, type, fps)` (`:28`) with **no `id`** — the previous draft of this contract had
`sheetClip(plan, id, name, type)`, and `id` belongs to `pack`, not to `Clip`. `assertEncodedSize` is a
member of `PackedSheet` (`:72`), not a top-level function of `SpritePacker`. `pack`'s parameter list
and its refusals are as pasted (`SpritePacker.kt:143-201`), including the `pixels * 4 <= Int.MAX_VALUE`
check at `:201`. `GifEncoder(width, height, loop = true)` (`:411`), `addFrame` (`:436`),
`delayCentiseconds = (ms + 5) / 10` clamped to 2..65535 (`:372`), `MAX_SCREEN = 0xFFFF` (`:75`).
`RegionRenderer.render` (`:196`) returns straight RGBA8, row 0 = top, and `MAX_REGION_PX = 8_388_608`
(`:89`); `√8 388 608 ≈ 2896 < 65 535`, so the GIF and PNG side caps **cannot** be reached from a plan
and `MAX_REGION_PX` is the only size bound a plan has to check. `DocException : Exception`
(`DocJson.kt:8`) and `AnimOps` throws it, so the fps refusal is a `DocException` — the previous draft
said `IllegalArgumentException`, which was wrong. The fps range `1f..60f` really is in **two**
places (`DocOps.kt:87` in `validate`, `AnimOps.kt:543` in `playableFps`), so "1..60" is `AnimOps`'
own refusal surfacing through, not a third rule written here — Decision 8 names which one.
`JbArchive.unsafeReason` is **private** (`JbArchive.kt:514`) and the module dependency runs
`androidkit → core` (`androidkit/build.gradle.kts:45`), so `commonMain` cannot see it and the name
rules are a restatement; its exact refusals are pasted in *Do not* and were read out of
`JbArchive.kt:514-538`. `Paper.includeInExport` exists (`DocModel.kt:42`). `JbContents` is
`JbContents(doc, tiles, strokes, thumbnailPng)` (`JbArchive.kt:44-51`) and the `TileSource` line
copied from `OraExport.kt:358-360` is the real one. `OraExport`, `PngWriter` and `JbArchive` contain
**no** `android.*` import, which is why this row's encoder layer contains none either.