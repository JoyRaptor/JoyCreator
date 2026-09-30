# JB-3.06c — the four JB-3.06b audit findings

| | |
|---|---|
| **Tier** | T2 (three small edits to two landed source files and two landed test files; no new file, no app file, no build file, no `document.json` field, nothing on the phone) |
| **Status** | 📝 Draft spec — **one of the four findings is WRONG as reported and one of them is a defect the audit did not name. Both are corrected below, with the code quoted.** Not 🟦 Ready: a cross-reviewer sets that. |
| **Who** | spec writer `openrouter/stealth/space-bunny-alpha` 2026-09-29. Every claim below was opened in the tree on 2026-09-29 and carries its `file:line`; where the audit's wording differs from the code, the code wins and the difference is stated in *Audit verification*. |
| **Needs** | 3.06b (built — the four files below are its output) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt` · EDIT `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlanTest.kt` · EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt` · EDIT `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExportTest.kt`. **Those four paths and nothing else.** `ROADMAP.md`, `LEAD_RULINGS.md`, `tasks/lessons.md` and every other spec are the Lead's and are NOT in this row. |
| **Estimated size** | ~40 lines of Kotlin changed in `:core`, ~55 in `:androidkit`, ~120 lines of test |
| **Command** | `./gradlew -p joybrush :core:jvmTest` then `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` — both BUILD SUCCESSFUL, 0 failures |

## Goal

R44 item 5 bundles four JB-3.06b MINORs into one row: *"the PNG-signature check reads 7 of 8 bytes (a real bug — fix), the stale `Clip has no weights` comment (R36 changed that), unbounded frame retention (encode as you go, do not hold every frame), and `roundHalfUpMs` defined but unused."*

This row is four small fixes and **no behaviour anybody can see changes** — except the one new refusal in Decision 6, which replaces a crash with a sentence. Read *Audit verification* before you read anything else: **two of the four findings are not what R44 says they are**, and a builder who took R44's wording at face value would either delete a live function or promise a refactor this row's owner area cannot deliver.

## Audit verification — every finding, checked against the code, before it became a decision

The lesson this project has paid for twice today is that **a finding repeated in three places is one writer believing it three times**. So each of the four is below, with the code quoted, and a verdict: **exactly right**, **right in substance, imprecise in detail**, or **wrong**.

### Finding 1 — the PNG signature reads 7 of 8 bytes. **EXACTLY RIGHT, and the surrounding lines hold a second defect it did not name.**

`joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt:382-400`, verbatim:

```kotlin
    private fun pngSize(bytes: ByteArray, fileName: String): Pair<Int, Int> {
        for (i in 0 until 7) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw JbArchiveException("$fileName does not start with a PNG signature, so its size cannot be checked")
            }
        }
        if (bytes.size < WIDTH_AT + 8) {
            throw JbArchiveException("$fileName is ${bytes.size} bytes, which is too short to be a PNG")
        }
```

and the array it is compared against, `AnimExport.kt:409`:

```kotlin
    /** 137, PNG, CR LF LF — the eight bytes every PNG starts with. */
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
```

**The 8th byte is `0x0A` (LF).** The loop at `:383` walks `i in 0 until 7`, so it compares indices 0..6 — `89 50 4E 47 0D 0A 1A` — and never looks at `PNG_SIGNATURE[7]`, which is `0x0A`. The array is 8 bytes; the loop is 7. That is the finding, and it is correct.

**Can a 7-byte check reject a valid PNG? No.** It is a strict subset of the real check, so every valid PNG passes it. **Can it accept an invalid one? Yes** — anything whose first seven bytes are the signature and whose eighth is not, which in the wild is a PNG that has been through a transport or filter that rewrote LF (a CRLF mangling turns the eighth byte into `0x0D`), and any hand-made fixture. So the failure mode is **false-accept only**: it can never turn a good PNG away, and it can let a garbled one through a gate whose KDoc says it refuses a file that "is not a PNG" (`AnimExport.kt:250-251`).

**How much does that cost today? Almost nothing, and the spec must say so rather than inflate it.** The only caller of `pngSize` is `AnimExport.kt:273`, and the bytes it is handed come from `PngWriter.encode(...)` on line 272 — the same function, one line earlier, always producing a well-formed PNG. **So no file a person receives is currently wrong.** This is a **hardening fix, not a data-corruption fix**, and Decision 1 fixes it as one.

**The second defect, which the audit did not name and which is the more crash-shaped of the two: the length check is on the WRONG SIDE of the signature loop.** `:388` (`if (bytes.size < WIDTH_AT + 8)`) runs *after* `:383`, and the loop indexes `bytes[i]` with no length guard. A `ByteArray` of 3 bytes therefore throws `ArrayIndexOutOfBoundsException` — an unchecked crash out of the one function every sheet export calls — instead of the sentence four lines below. And the naive fix makes it worse: widening the loop to `0 until 8` turns a 7-byte array from "passes the loop" into "crashes in the loop". **This is why Decision 1 moves the length check ABOVE the loop, and why the order is not optional.**

### Finding 2 — the stale "Clip has no `weights` field today" comment. **EXACTLY RIGHT about the comment, and the audit stopped one line short of a decision that rests on the same false premise.**

Reported at `AnimExportPlan.kt:151-154`; it is really at **`:151-154` and a second copy at `AnimExport.kt:266-268`**, plus a third in the test at `AnimExportPlanTest.kt:455-457`. Verbatim, `AnimExportPlan.kt:151-154`:

```kotlin
     * `Clip` has no `weights` field today, and this row must not start writing one: JB-4.03c may
     * add it later, and when it does this row keeps using repeats. A repeat is a repeat, not a
     * mistake — that is `SpritePacker`'s own KDoc on `Clip.frames`, and it is how a ping-pong is
     * written too.
```

and `AnimExport.kt:266-268`:

```kotlin
            // Decision 7: ONE clip covering every exported frame, with each hold folded in as a
            // repeat of that cell's index. `Clip` has no `weights` field today and this row must
            // not start writing one; a repeat is a repeat, not a mistake.
```

**Both are false.** `Clip` today, `SpritePacker.kt:35-41`:

```kotlin
data class Clip(
    val name: String,
    val frames: List<Int>,
    val type: String = "loop",
    val fps: Float = 0f,
    val weights: List<Int> = emptyList(),
)
```

and `SpritePacker.kt:285-288` writes the key when a weight is not 1. JB-4.03c has landed (commit `5fd26419`), so `weights` is a field, `pack` validates it (`SpritePacker.kt:220-232`, the app's own 1..9999) and writes it.

**The comment is one problem. The decision underneath it is the second one, and the audit did not report it.** The decision is *"fold a hold in as a REPEAT of that cell's index, never as a `weights` entry"* — and the reason recorded for it, at `AnimExportPlan.kt:151-152` and in the JB-3.06b spec at `:408-411` and `:618-619`, is *"a spec that wrote weights would be writing a field that does not exist."* **That reason is gone.** The decision now stands or falls on something else, and nobody has said what.

So the spec has to say what is actually true, and here is the part that matters: **the repeats are not a bug, and switching them to `weights` would not be a bug fix.** Both spellings say the same thing to a reader — play cell 1 three times at 12 fps is hold 1 for 250 ms — and the landed tests prove the repeat form is exact (`noHoldIsLostOrGainedByTheRoundTrip`, `AnimExportPlanTest.kt:181-201`, walks all 59 940 rate/hold pairs through the real `AnimOps`). The GIF and the manifest carry the same timing in milliseconds either way, and `everyFormatConsumesTheSamePlan` (`AnimExportTest.kt:300-343`) checks all three against each other. **Which spelling goes into a `.sprite.json` is a file-format decision, and file formats are the Lead's** — so Decision 2 fixes the four false sentences, pins today's behaviour with a real assertion and a positive control, and puts the switch to the Lead as Question 1. What this row must not do is leave a comment in the code asserting a falsehood about the world it is compiled into.

### Finding 3 — unbounded frame retention. **RIGHT that retention is unbounded, and the audit's remedy is not achievable inside this row's owner area.** The number is derived below, not chosen.

**What is actually retained today.** `AnimExport.kt:128-132`, verbatim:

```kotlin
        val cells = ArrayList<ByteArray>(plan.frameCount)
        plan.frameIds.forEachIndexed { i, frameId ->
            cells.add(RegionRenderer.render(doc, tileSource(contents), plan.rect, frameId, paper))
            onFrame(i + 1, plan.frameCount)
        }
```

Every frame's RGBA is kept until the encoder has finished, and **nothing in the tree caps how many frames a board may have.** `AnimOps` caps a single *hold* at `MAX_HOLD_FRAMES = 999` (`AnimOps.kt:100`) and the *rate* at 1..60 (`AnimOps.kt:543`) — **the frame COUNT is uncapped**; `DocOps.validate` checks duplicate frame ids (`DocOps.kt:120-121`) and nothing else. The only bound is `JbArchive.MAX_DOCUMENT_BYTES = 32 * 1024 * 1024` (`JbArchive.kt:119`), which at roughly thirty bytes of JSON per frame is on the order of a million frames. So a pathological board is reachable from a real file, not a thought experiment.

**The 4096×4096 case the brief asks about, and why it is not the worst one.** 4096×4096 = 16 777 216 px; ×4 B = 67 108 864 B = **64 MiB per frame**; ×600 frames = 40 265 318 400 B = **37.5 GiB**. But that board is refused before a single frame exists: `checkRegionFits` (`AnimExport.kt:334-343`) compares 16 777 216 against `MAX_REGION_PX = 8_388_608` (`RegionRenderer.kt:89`) and throws. The largest cell this row will actually render is therefore `⌊√8 388 608⌋ = 2896`, i.e. 2896×2896 = 8 385 856 px = **32 MiB per frame**, and **a 600-frame GIF at that size retains 600 × 33 554 432 = 20 132 659 200 B = 18.75 GiB.** That is the real number, and it is the one the ceiling has to forbid.

**Why "encode as you go" cannot be done here, and this is the finding's real content.** R44's remedy assumes the retention is `AnimExport`'s choice. It is not:

- **The GIF cannot stream.** `GifEncoder` is built to retain: `private class PendingFrame(val rgba: ByteArray, val delayMs: Int)` and `private val frames = ArrayList<PendingFrame>()` (`GifEncoder.kt:413-415`), `frames.add(PendingFrame(rgba, delayMs))` (`:443`), all read back in `finish()` (`:456`). Every frame's array is alive for the whole call **by the encoder's own design**, and `GifEncoder.kt` is a JB-3.06a file this row may not touch. Deleting the `cells` list at `AnimExport.kt:128` would free **references only** — the same arrays are still held by the encoder — so it would be a refactor that buys nothing and would be sold as a memory fix. **Do not do it.**
- **The sheet cannot stream.** `SpritePacker.pack(cells: List<ByteArray>, …)` is handed the whole list and allocates the whole sheet on top of it (`SpritePacker.kt:248`), and `SpritePacker.kt` is a reviewed landed file.
- **The PNG sequence already streams** and needs no fix: `writeSequence` renders, encodes and writes inside the loop (`AnimExport.kt:179-200`) and keeps one frame at a time. `:154-155` says so in the code already.

So there is exactly one fix available inside four files, and it is the one this row ships: **a budget, checked before the first frame, that turns the crash into a sentence naming the numbers** — plus the comment that tells the next reader why the list is there and why it cannot go. Decision 6.

### Finding 4 — `roundHalfUpMs` is defined but never used. **WRONG. It is used, at `AnimExportPlan.kt:255`, and the real defect is the mirror image of the one reported.**

`AnimExportPlan.kt:240` and its one call site, `:254-255`, verbatim:

```kotlin
    private fun roundHalfUpMs(x: Double): Int = floor(x + 0.5).toInt().coerceAtLeast(1)

    private fun ticksOf(plan: AnimExportPlan, i: Int): Int =
        roundHalfUpMs(plan.delayMs[i].toDouble() * plan.fps.toDouble() / 1000.0)
```

`ticksOf` is called by `cellIndices` (`:261`), which is called by `sheetClip` (`:159`), which `AnimExport.kt:269` calls. **The call chain is live and its result is what the sidecar says.** So the audit's premise is false and **deleting `roundHalfUpMs` would delete the hold arithmetic** — the sidecar would lose every hold. Decision 4 does not touch this function's existence; Decision 5 does something else with it.

**The real drift is that the same expression appears twice in the same file, and the second copy is the one that matters.** `AnimExportPlan.kt:218`, inside `build`:

```kotlin
            delays.add(floor(end - starts[i] + 0.5).toInt().coerceAtLeast(1))
```

That is `roundHalfUpMs`'s body, inlined, character for character. So the rounding rule JB-3.06b's Decision 2 is *about* — `floor(x + 0.5)`, never `kotlin.math.round` — is written down twice, and the copy that computes **every exported delay** is the un-named one. If somebody "fixed" the tie behaviour in one place and not the other, the GIF and the sidecar would disagree about 62.5 ms and nothing in the test suite would notice. **This is the JB-1.08a lesson exactly** — *a number that appears twice is a drift waiting to happen* — and here the thing that appears twice is a whole rounding rule, not a `const`. Decision 5 routes `build` through the function that already exists, which is a **behaviour-preserving** change: identical expression, identical results, and the existing 59 940-case sweep plus the tie cases are the proof, run unchanged.

## The rule the four fixes share, and why it is a rule

Two of these findings (1 and 2) are the same mistake wearing different clothes: **a number that is written once as prose and once as code, and the prose is what drifts.** `PNG_SIGNATURE` is 8 bytes in an array and 7 in a loop bound; `Clip.weights` does not exist in a comment and does exist in a data class. So the four fixes are four instances of one house rule, and Decision 1 is the clearest case:

- the loop bound is **`PNG_SIGNATURE.indices`**, not `7` and not `8` — so the bound *is* the array, and the number exists once, in the array;
- the length guard reuses **`PNG_SIGNATURE.size`**, not a typed `8`;
- the `WIDTH_AT` comment keeps its prose, because prose explaining an offset is worth having; what must not happen is a second **executable** copy of a count.

That is also the shape two other PNG readers in this tree already got right, and one of them is pasted below because copying a worked example beats describing one. `PngChunks.kt:172-179`:

```kotlin
    if (bytes.size < PNG_SIGNATURE.size) {
        throw BrushException("this PNG is ${bytes.size} bytes, too short to hold the 8-byte signature")
    }
    for (i in PNG_SIGNATURE.indices) {
        if (bytes[i] != PNG_SIGNATURE[i]) {
            throw BrushException("this file does not start with the 8-byte PNG signature")
        }
    }
```

Length first, then `PNG_SIGNATURE.indices`. `KritaImport.kt:1750-1758` does the same with a named `PNG_SIGNATURE_BYTES = 8`; **this row copies `PngChunks` and not `KritaImport`**, because `.indices` cannot drift from the array at all, while a `const` and an array are two places to update. `PNG_SIGNATURE` here is `private` in `:core` (`PngChunks.kt:148`) and `:core` is a dependency of `:androidkit`, so it is not importable — this is a **restatement**, exactly as `safeBaseName` restates `JbArchive.unsafeReason`, and the comment on both sides names the other (Decision 2's precedent, applied to finding 1).

## Contracts — the four sites, before and after, pasted

### Site 1 — `AnimExport.kt:382-400` → the signature gate (Decision 1)

**Now** (the loop bound is 7, the length guard is below it):

```kotlin
    private fun pngSize(bytes: ByteArray, fileName: String): Pair<Int, Int> {
        for (i in 0 until 7) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw JbArchiveException("$fileName does not start with a PNG signature, so its size cannot be checked")
            }
        }
        if (bytes.size < WIDTH_AT + 8) {
            throw JbArchiveException("$fileName is ${bytes.size} bytes, which is too short to be a PNG")
        }
```

**After** — the length guard first, the loop bound *is* the array, `internal` so a test can reach it, and the existing two sentences and both refusals unchanged:

```kotlin
    /**
     * The width and height a PNG says about ITSELF, out of its IHDR.
     *
     * … (the existing KDoc, kept, with the sentence below added)
     *
     * **THE SIGNATURE IS CHECKED IN FULL, AND THE LENGTH IS CHECKED FIRST.** `PNG_SIGNATURE` is
     * eight bytes and the loop walks `PNG_SIGNATURE.indices`, so the bound cannot drift from the
     * array the way a typed `7` did — that was the bug this row fixes. The length guard is ABOVE the
     * loop because the loop indexes the array: a short file must arrive as the sentence below and
     * never as an `ArrayIndexOutOfBoundsException`.
     *
     * **A RESTATEMENT OF `PngChunks.PNG_SIGNATURE`, NOT AN IMPORT OF IT.** That array is `private`
     * in `:core` (`PngChunks.kt:148`) and the module dependency runs `androidkit -> core`
     * (`androidkit/build.gradle.kts:45`), so this file cannot see it. `PngChunks.kt:172-179` does
     * this same check in this same order; keep them in step, and put a comment on both sides naming
     * the other. The difference is deliberate: `PngChunks` refuses a file it is *reading*,
     * `pngSize` refuses one it has just *written*, which is a backstop against a writer that lies.
     */
    internal fun pngSize(bytes: ByteArray, fileName: String): Pair<Int, Int> {
        if (bytes.size < WIDTH_AT + 8) {
            throw JbArchiveException("$fileName is ${bytes.size} bytes, which is too short to be a PNG")
        }
        for (i in PNG_SIGNATURE.indices) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw JbArchiveException("$fileName does not start with a PNG signature, so its size cannot be checked")
            }
        }
```

`WIDTH_AT = 16` (`:412`) is **not** renamed and **not** re-derived: it is the sum 8 + 4 + 4 and the comment above it says so. `internal` is visible from this module's own test source set, and the precedent is in the tree — `PngWriter.encode`'s five-argument `internal` overload (`PngWriter.kt:98`) is called from `PngWriterTest.kt:280` in `src/test`. **The one call site, `AnimExport.kt:273`, does not change.**

### Site 2 — the four false sentences (Decision 2)

`AnimExportPlan.kt:151-154` **now**:

```kotlin
     * `Clip` has no `weights` field today, and this row must not start writing one: JB-4.03c may
     * add it later, and when it does this row keeps using repeats. A repeat is a repeat, not a
     * mistake — that is `SpritePacker`'s own KDoc on `Clip.frames`, and it is how a ping-pong is
     * written too.
```

**becomes** — same shape, same length of argument, and true:

```kotlin
     * **`Clip` HAS a `weights` field** (`SpritePacker.kt:40`, added by JB-4.03c), and this row
     * still folds each hold in as a REPEAT of that cell's index rather than writing a weight. A
     * repeat is a repeat, not a mistake — that is `SpritePacker`'s own KDoc on `Clip.frames`, and
     * it is how a ping-pong is written too. The two spellings say the same thing to a reader, and
     * which one goes into an exported `.sprite.json` is a file-format question this row does not
     * decide: see the JB-3.06c spec, Question 1. Until the Lead rules, the repeats stay, and
     * `AnimExportPlanTest.theFoldedClipCarriesNoWeightsKeyAndThePackerWouldWriteOne` is what makes
     * that a fact rather than an intention.
```

`AnimExport.kt:266-268` **now**:

```kotlin
            // Decision 7: ONE clip covering every exported frame, with each hold folded in as a
            // repeat of that cell's index. `Clip` has no `weights` field today and this row must
            // not start writing one; a repeat is a repeat, not a mistake.
```

**becomes**:

```kotlin
            // Decision 7: ONE clip covering every exported frame, with each hold folded in as a
            // repeat of that cell's index. `Clip` HAS a `weights` field now (JB-4.03c) and this row
            // does not write one — that is Question 1 of the JB-3.06c spec, not a fact about the
            // field. A repeat is a repeat, not a mistake, and it is what the app's own KDoc means.
```

`AnimExportPlanTest.kt:455-457` **now**:

```kotlin
        // No `weights` key is invented, because `Clip` has no such field. If JB-4.03c adds one,
        // this row must keep folding repeats — see the spec's Question 3.
        assertEquals("Clip", Clip::class.simpleName, "the landed Clip")
```

**becomes** — the vacuous assertion (`assertEquals("Clip", Clip::class.simpleName)` asserts that a
class is called `Clip`, which no edit to this row can make false) is **deleted**, and the sentence
it belongs to moves to the new test in *Tests*:

```kotlin
        // `weights` is a field of `Clip` now (JB-4.03c) and this row does not write one; what the
        // exported sidecar actually carries is asserted in
        // theFoldedClipCarriesNoWeightsKeyAndThePackerWouldWriteOne, against the packer's own JSON.
```

### Site 3 — the decoded-pixel budget (Decision 6)

**Now**, `AnimExport.kt:334-343`, the only size check there is:

```kotlin
    private fun checkRegionFits(boardId: String, width: Int, height: Int) {
        val px = width.toLong() * height.toLong()
        if (px > MAX_REGION_PX) {
            throw RegionException(
                "board \"$boardId\" is $width by $height, which is $px pixels, and the most one " +
                    "export will render is $MAX_REGION_PX. Export a smaller board, or a piece of it " +
                    "at a time.",
            )
        }
    }
```

**After** — this function **unchanged**, and three new private/internal neighbours beside it:

```kotlin
    /**
     * The most DECODED picture one export may hold alive at once, in pixels.
     *
     * **DERIVED, NOT CHOSEN — and written as `MAX_REGION_PX * 2` so the derivation cannot rot.**
     * 1. `MAX_REGION_PX` (`RegionRenderer.kt:89`) is this tree's one-render budget, and it is
     *    documented as a MEMORY budget, not a range check: `RegionRenderer.kt:153` and `:345-350`
     *    both say so, and `BYTES_PER_PX = 20` (`:92`, four result bytes plus sixteen of float
     *    scratch) puts one render's live footprint at `MAX_REGION_PX * 20` = 160 MiB.
     * 2. This codebase's own ceiling for ONE decoded blob is 64 MiB, and it says so three times
     *    with the same number: `ProcreateImport.MAX_INFLATED_BYTES = 64L * 1024 * 1024`
     *    (`ProcreateImport.kt:76`, "What one deflate entry may inflate to. 64 MiB, and the declared
     *    size is not believed"), `KritaImport.MAX_FILE_BYTES` (`KritaImport.kt:89`) and
     *    `AbrImport.MAX_FILE_BYTES = 64L * 1024 * 1024` (`AbrImport.kt:39`) — and
     *    `KritaImport.kt:87` names the agreement in prose, which is where the number comes from.
     * 3. 64 MiB of straight RGBA8 is 64 * 1024 * 1024 / 4 = 16 777 216 pixels, and
     *    16 777 216 = 2 * 8 388 608. **So the multiplier is 2 because the tree's blob ceiling
     *    happens to be exactly twice the renderer's pixel budget, not because two felt right.**
     *
     * The consequence is the sentence a person gets instead of an `OutOfMemoryError`, and the
     * number moves on its own if the Lead rules on the renderer's own pending question
     * (`RegionRenderer.kt:85-86`: "LEAD RULING PENDING … the implementer picked it, not the Lead").
     */
    internal const val MAX_EXPORT_PX = MAX_REGION_PX * 2L

    /**
     * How many decoded RGBA pixels [format] holds alive at once. **ONE TABLE, THREE ANSWERS, and
     * each answer is a line of landed code rather than a preference:**
     *
     * - `GIF`: `frames * cellW * cellH`, because `GifEncoder` retains every frame's array by
     *   design — `PendingFrame` / `frames` (`GifEncoder.kt:413-415`), `frames.add(…)` (`:443`),
     *   read back in `finish()` (`:456`) — and `GifEncoder.kt` is not this row's to change.
     * - `PNG_SEQUENCE`: ONE cell, because `writeSequence` renders, encodes and writes inside its
     *   loop (`AnimExport.kt:179-200`) and nothing accumulates. **This is why the count is not
     *   `frames * …` for every format: a 600-frame sequence runs in constant memory, and a rule
     *   that refused it would be refusing an export that works.**
     * - `SPRITE_SHEET`: `frames * cellW * cellH` PLUS the sheet itself, because `encodeOne` holds
     *   the cells list (`AnimExport.kt:128-132`) and `pack` allocates the whole sheet on top of it
     *   (`SpritePacker.kt:248`) — the one place two full copies of the same picture are alive.
     *
     * `cols` is ignored for the two formats that have no grid, and the test that proves it is
     * `theBudgetIsTheSameForEveryColumnCountOnATwoDimensionalFormat`.
     */
    internal fun decodedPixels(format: AnimFormat, plan: AnimExportPlan, cols: Int): Long {
        val cell = plan.width.toLong() * plan.height.toLong()
        val frames = cell * plan.frameCount.toLong()
        if (format != AnimFormat.SPRITE_SHEET) return frames
        val rows = (frames + cols - 1) / cols
        return frames + (cols.toLong() * plan.width) * (rows * plan.height.toLong())
    }

    /**
     * The refusal, in a sentence that names the board and both numbers.
     *
     * `RegionException`, not `DocException`: the refusal three lines above throws `RegionException`
     * for the same reason — this export will not fit in memory — and one type means a caller wraps
     * `encodeOne` in one `catch`. The sentence names what the export WOULD need, what the most IS,
     * and one remedy. **It does not name a control** (see *Do not*, R35).
     */
    private fun checkExportFits(boardId: String, format: AnimFormat, plan: AnimExportPlan, cols: Int) {
        val px = decodedPixels(format, plan, cols)
        if (px > MAX_EXPORT_PX) {
            throw RegionException(
                "board \"$boardId\" would hold $px pixels of picture at once for this " +
                    "${format.label} — ${plan.frameCount} frames of ${plan.width} by " +
                    "${plan.height} — and the most one export will hold is $MAX_EXPORT_PX. " +
                    "Export a shorter range of it, or a smaller board.",
            )
        }
    }
```

**Where it is called — two lines each, after `checkRegionFits` and before any render:**

```kotlin
        checkPlanFitsBoard(boardId, board.frames.map { it.id }, plan.frameIds)
        checkRegionFits(boardId, plan.width, plan.height)
        checkExportFits(boardId, format, plan, cols)      // <— encodeOne only
        val paper = if (includePaper) checkedPaper(doc.paper.color) else null
```

and in `writeSequence`, with `AnimFormat.PNG_SEQUENCE` in place of `format` and no `cols`
argument. **It is placed after `checkRegionFits`, not before**, because a board that cannot be
rendered at all has no business being told how many frames it holds; `checkRegionFits` is the older
sentence and stays the first one a person meets.

### Site 4 — the rounding rule, once (Decision 5)

`AnimExportPlan.kt:218` **now**:

```kotlin
        for (i in lo..hi) {
            val end = if (i < last) starts[i + 1] else total
            delays.add(floor(end - starts[i] + 0.5).toInt().coerceAtLeast(1))
        }
```

**becomes** — one line changed, one identifier shorter, same result:

```kotlin
        for (i in lo..hi) {
            val end = if (i < last) starts[i + 1] else total
            delays.add(roundHalfUpMs(end - starts[i]))
        }
```

`roundHalfUpMs` (`AnimExportPlan.kt:240`) is **not renamed, moved, deleted, or given a new body**,
and its KDoc is not edited except for one added sentence naming `build` as its other caller — the
sentence exists so the next reader knows there are two call sites and one rule. `ticksOf` (`:254-255`)
and `cellIndices` (`:258-264`) are **untouched**. `floor` stays imported (`:7`) — it is still used
inside `roundHalfUpMs`.

## The four decisions, and the reasoning

1. **The signature loop is `PNG_SIGNATURE.indices` and the length guard moves above it; `pngSize`
   becomes `internal`.** *Why:* the loop bound is the bug, and binding it to the array removes the
   class of bug rather than this instance — a typed `8` would be a second place to update, and the
   tree has already paid for that lesson (`KritaImport.kt:1758` keeps `PNG_SIGNATURE_BYTES = 8`
   *and* an eight-element array, which works, and is one edit more than this needs). *Why the order
   is part of the fix and not a tidy-up:* the guard below the loop is what turns a 3-byte array into
   an `ArrayIndexOutOfBoundsException`, and widening the loop to 8 without moving the guard converts
   a 7-byte array from "quietly accepted" to "crashes". A signature check that can crash is worse
   than the one that is short. *Why `internal` and not a public function:* the check is a private
   backstop inside a writer, and a test needs to reach it; `internal` is module-scoped, is already
   proven visible from this test source set (`PngWriterTest.kt:280` calls `PngWriter`'s `internal`
   five-argument `encode`), and adds nothing to any other module's vocabulary.

2. **The four false sentences are corrected to the truth, and today's behaviour is pinned with a
   real assertion plus a positive control — the weights/no-weights choice is not decided here.**
   *Why:* a comment that says a field does not exist, in a file that compiles against a class where
   it does, is worse than no comment: it is a false statement with a line number, and the next
   reader trusts it. *Why the behaviour is pinned rather than changed:* whether an exported
   `.sprite.json` carries `weights` is a **file format** decision, and file formats are the Lead's
   (R36 changed this packer's key set once already, and `SpritePacker.kt:94-95` says so: "the key
   set, the key ORDER and the sparse-write rule … follow `SpriteSheet.toJson()` on the Android
   side, because that file is what parses it"). *Why the positive control matters:* an assertion that
   no `weights` key appears proves nothing on its own — the packer might simply not write one, ever.
   Showing the same test pack a clip that DOES carry weights, and getting a `weights` key back, is
   what turns "this row does not write weights" from an absence into a choice.

3. **`encodeOne` keeps its `cells` list, and a comment says why it cannot go.** *Why:* deleting it
   frees references, not bytes — `GifEncoder` holds the same arrays (`GifEncoder.kt:443`) — and for
   the sheet `pack` needs the list anyway. A refactor that does not move a byte and is described as
   a memory fix is the exact thing this row exists to stop. *Why the comment matters:* the next
   person to read `AnimExport.kt:128` will see a list of every frame and think it is an oversight,
   and "fixing" it will either re-add the list or split the encode into a second packer, which is
   the two-implementations hazard R38 item 2 rejected for JB-2.20b.

4. **The PNG sequence keeps streaming, and Decision 3's comment is its documentation.** *Why:* it is
   already correct (`:179-200`), and the budget's per-format table (Site 3) depends on that being
   true — a sequence's count is one cell, not `n` cells. A test pins it.

5. **`build` calls `roundHalfUpMs`, and nothing else about the function changes.** *Why:* the
   rounding rule is the one number in this row that decides a frame boundary in a file somebody else
   plays (JB-3.06b Decision 2), and it is currently written twice in one file with the *load-bearing*
   copy unnamed. Routing `build` through the function is behaviour-preserving by construction — the
   expression is character-for-character the function's body, including `coerceAtLeast(1)` — so this
   is the cheapest possible fix for the most expensive possible drift. *Why not delete it, as the
   finding says:* it is the only thing computing a hold from a duration (`ticksOf`, `:254-255`), and
   `noHoldIsLostOrGainedByTheRoundTrip` proves that arithmetic over all 59 940 rate/hold pairs.
   Deleting it deletes the holds. *Why `build` is the caller to change and not `ticksOf`:* `build` is
   the copy with no name, and the copy with a name is the one worth keeping.

6. **`MAX_EXPORT_PX = MAX_REGION_PX * 2L`, checked before the first frame, in `Long`, refusing with
   a `RegionException` that names the board, the need and the cap — and counted PER FORMAT.**
   *Why derived:* the derivation is in Site 3 and every step is a landed constant; the multiplier
   falls out of two real numbers agreeing. *Why written as `MAX_REGION_PX * 2L`:* a typed
   `16_777_216` would be a second copy of a number the Lead has an open question about
   (`RegionRenderer.kt:85-86`), and JB-1.08a's lesson is precisely that a number written twice is a
   drift waiting to happen. *Why per format:* the three formats retain different things, and a
   single `n × cell` rule would refuse a 600-frame PNG sequence that runs in constant memory — a
   refusal for a reason the export does not have. *Why `RegionException`:* it is the type
   `checkRegionFits` throws for the same "will not fit in memory" reason three lines away, so a
   caller catches one type. *Why before the first frame:* Decision 8 of JB-3.06b already promised
   that everything refusable is refused before `onFrame` is called, and this is now something
   refusable. *Why a sentence and not a smaller cap:* a cap low enough to make the crash impossible
   for every board would refuse boards that export perfectly today; the budget is set at the size
   the tree already accepts elsewhere, so the only exports it stops are the ones that were going to
   throw `OutOfMemoryError` — and `OutOfMemoryError` is an `Error`, which no `catch (e: Exception)`
   in this file catches, so today it is an unhandled crash on a phone.

   *Marked **PROVISIONAL — Claude to confirm** (Decision 6 only).* It is low-risk, it is reversible
   (one constant), and it is derived rather than chosen — but it caps what a person can export, so
   the Lead may want a different number. **What the number costs, stated plainly so the choice is
   informed:** with `MAX_REGION_PX = 8 388 608`, a 1024×1024 board exports at most **16 frames as a
   GIF** and **7 as a sprite sheet**; a 512×512 board at most 64 / 30; a 2896×2896 board at most
   2 / 1. If the Lead wants more, the constant is the only thing to change, and the arithmetic moves
   with it.

## Decision → Test map

| Decision | Pinned by |
|---|---|
| 1 (full signature, length first, `internal`) | `aFileWithAWrongEighthByteIsNotAPng`, `aFileTooShortToHoldASignatureIsRefusedInWords`, `theSignatureIsEightBytesAndTheLoopWalksAllOfIt` |
| 2 (the four sentences, behaviour pinned) | `theFoldedClipCarriesNoWeightsKeyAndThePackerWouldWriteOne` |
| 3 (the cells list stays, and says why) | review + the Do-not entry; a test cannot pin a comment's existence, and the *behaviour* it documents is pinned by `theSheetIsAcceptedOrRefusedByThePackerItself` and `noHoldIsLostOrGainedByTheRoundTrip` |
| 4 (the sequence keeps streaming) | `aLongSequenceIsAcceptedWhateverItsFrameCount`, which fails if the sequence's count ever becomes `n × cell` |
| 5 (`build` calls `roundHalfUpMs`) | `theDelaysAreDifferencesOfTheRealStarts` and `aTieGoesTo63Not62`, **run unchanged**, plus the mutation drill |
| 6 (`MAX_EXPORT_PX`, per format, before frame 1) | `theBudgetIsTwiceTheRenderersPixelBudget`, `theBudgetAcceptsExactlyTheCapAndRefusesOnePixelOver` (a GIF **and** a sheet, each hitting the cap exactly), `theBudgetIsTheSameForEveryColumnCountOnATwoDimensionalFormat`, `anExportOverThePixelBudgetIsRefusedBeforeAnyFrameIsRendered` |

## Tests

### A. `AnimExportPlanTest.kt` (`commonTest`, `:core`) — one new test

The class KDoc, the fixtures and the sweeps stay. **No existing test in this file may be edited**
except the one line quoted in Site 2 — the file is the witness for Decision 5, and a witness that
moves is not a witness. `git diff` on this file must show the new test and the Site 2 comment, and
nothing else; **paste that diff in the report.**

1. `theFoldedClipCarriesNoWeightsKeyAndThePackerWouldWriteOne`: pack the fixture with the real
   `SpritePacker.pack` and parse the returned `sidecarJson`, as `theSheetCarriesExactlyOneClip`
   (`:469-516`) already does. Assert, on the **clip object** inside `presets`:
   - `"weights" !in clip` — the exported clip carries no weights key;
   - `clip["frames"]` is the folded list, unchanged from today;
   - **the positive control:** re-pack with `sheetClip(...).copy(weights = List(size) { if (it == 0) 3 else 1 })`
     and assert `"weights" in clip` and that the array is the one handed in, and that `pack`
     **accepted** it. The comment must say why the control is here: without it, the first
     assertion proves nothing, because a packer that never wrote `weights` would also pass it.
   - A third case with **mismatched** lengths (`weights = listOf(3)` against a 7-entry `frames`)
     must be refused by `pack` naming both numbers — that is `SpritePacker.kt:221-225` and it is
     what makes "weights is parallel to frames" a fact this row knows rather than assumes.

### B. `AnimExportTest.kt` (`androidkit`'s test source set) — four new tests, one new fixture block

The existing fixture, the `RecordingSink`, the `DecodedGifFrame` helpers and every decoding helper
stay exactly as they are. **The oversized-board test (`:461-496`) is the template for the new
refusal test and must not be edited** — it is the proof that the new check is *added* and did not
replace the old one. `pngSize` is reachable because Decision 1 makes it `internal`; if the compiler
says otherwise, **STOP** (see *Stop rule*).

1. `aFileWithAWrongEighthByteIsNotAPng`: `PNG_SIGNATURE` is `private`, so the fixture **builds the
   eight bytes itself** — `byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x41)` —
   24 bytes long so the size guard passes, then `pngSize(bytes, "Board.png")` must throw
   `JbArchiveException` whose message contains `Board.png` and `signature`. **This is the non-vacuity
   proof for finding 1: it throws nothing today**, because the eighth byte is never read.
2. `aFileTooShortToHoldASignatureIsRefusedInWords`: `pngSize(ByteArray(3), "Board.png")` must throw
   `JbArchiveException` containing `3` and `too short`. **Today it throws
   `ArrayIndexOutOfBoundsException`**, so this test is red before the fix and green after — and it
   is the reason the guard moved above the loop.
3. `theSignatureIsEightBytesAndTheLoopWalksAllOfIt`: a 24-byte array whose bytes 0..7 are the real
   signature and whose IHDR width/height are the ones a 4×2 sheet would have; `pngSize` returns
   exactly those two numbers. Plus `assertEquals(8, <the array>.size)` read off the *same* fixture —
   so the count of the signature is asserted as a number in a test and not only in a comment.
4. `theBudgetIsTwiceTheRenderersPixelBudget`: `assertEquals(MAX_REGION_PX * 2L, MAX_EXPORT_PX)`
   and `assertEquals(16_777_216L, MAX_EXPORT_PX)` — both are in the tree, and the second is the
   arithmetic the derivation claims. This test is what stops the constant from being quietly retyped
   as a literal later.
5. `theBudgetAcceptsExactlyTheCapAndRefusesOnePixelOver`: a 256-frame board at 256×256, **no tiles
   at all**
   (an empty tile map costs nothing and this test never renders). For a GIF the count is
   `256 * 65536 = 16_777_216` = `MAX_EXPORT_PX` **exactly**, so it must be **accepted** (`<=`, not
   `<`); the same board with 257 frames is `16_842_752` and must be **refused** with a sentence
   containing the board id, `16842752` and `16777216`. Asserted through `decodedPixels` and through
   `encodeOne` with an `onFrame` counter that must be **0** for the refusal and equal to `frameCount`
   for the acceptance — the acceptance is the half that proves the comparison operator, and a builder
   who writes `>=` fails it. **The same at the exact boundary for the sheet, which is the only
   format with a second term:** a **7**-frame board at 1024×1024 gives
   `7 * 1_048_576 + (3 * 1024) * (3 * 1024) = 7_340_032 + 9_437_184 = 16_777_216` — the cap, exactly,
   and `cols = ceil(sqrt(7)) = 3` with `rows = 3`, so the sheet's 9 cells are what makes it land
   there. It must be **accepted**; **8** frames gives `17_825_792` and must be refused. Both
   expectations are written as expressions over `MAX_EXPORT_PX` and the frame count, and the test
   asserts they are equal to the cap rather than trusting this paragraph's arithmetic.
6. `theBudgetIsTheSameForEveryColumnCountOnATwoDimensionalFormat`: the same 12-frame plan yields the
   same `decodedPixels` for `PNG_SEQUENCE` at `cols = 1` and `cols = 99`, and the same for `GIF`;
   and for `SPRITE_SHEET` it does **not** (so the test also proves the sheet's term is real, and
   that a two-dimensional format is not accidentally costed as a stream).
7. `aLongSequenceIsAcceptedWhateverItsFrameCount`: 400 frames at 128×128 through `writeSequence`
   with a recording sink. 400 × 16 384 = 6 553 600 px, **under** the budget, and the export must
   complete with 400 PNGs and the manifest. A `decodedPixels` that ever became `n × cell` plus a
   sheet for this format would still pass here, so the test that actually pins Decision 4 is the
   **counter-example** the builder must run: temporarily make `decodedPixels` add a sheet term for
   `PNG_SEQUENCE`, lower `MAX_EXPORT_PX` until 400 × 16 384 is over it, and confirm this test goes
   red. Paste that. (400 frames at 128×128 is 6.5 MB of pixels in the test JVM — cheap.)
8. `anExportOverThePixelBudgetIsRefusedBeforeAnyFrameIsRendered`: the template is
   `anOversizedBoardIsRefusedBeforeAnyFrameIsRendered` (`:461-496`) — **`tiles = emptyMap()`**, a
   40-frame board at 1024×1024, so `40 * 1_048_576 = 41_943_040` px is over the budget. For each of
   `GIF` and `SPRITE_SHEET`: `RegionException`, the message names the board, `41943040` and
   `16777216`, and the `onFrame` counter is **0**. Then the **sequence** at 400 frames of 1024×1024
   (400 × 1 048 576 = 419 430 400 px) must still be **accepted** and write 400 PNGs and the
   manifest — that is the assertion that stops a builder from making the budget a flat `n × cell`
   rule and refusing the one format that streams.
9. **Mutation drill, all four, pasted:** (a) put the loop back to `0 until 7` → tests 1 red;
   (b) move the length guard back below the loop → test 2 red; (c) change `build`'s call back to the
   inlined `floor(…)` **and** change `roundHalfUpMs` to `kotlin.math.round` →
   `aTieGoesTo63Not62` red **while `theDelaysAreDifferencesOfTheRealStarts` stays green**, which is
   exactly the drift the two copies allowed; (d) make `decodedPixels` return `frames` for
   `SPRITE_SHEET` too → test 8's sequence half or test 6 red.

**Command:** `./gradlew -p joybrush :core:jvmTest` (the whole `:core` suite, 0 failures) then
`./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` (0 failures). Both BUILD
SUCCESSFUL. **Do not pass `--rerun-tasks`** (R44 item 1): `brushes/`, `assets/` and `shaders/` are
now declared `jvmTest` inputs, so an edited preset re-runs the tests by itself, and the workaround
hides the real answer. **Do not pass `-Pjoybrush.androidJar=<path>`**: it is a fallback, not a
requirement (`androidkit/build.gradle.kts:19`). If you build from a worktree, `cd` into it first
(R44 item 6) or `-p joybrush` resolves to the main folder and you will read a red that is not red.

## Do not

- **Do not delete `roundHalfUpMs`.** It is live (`AnimExportPlan.kt:255` via `ticksOf` → `cellIndices`
  → `sheetClip`), and deleting it deletes every hold from every exported sidecar. The audit's finding
  4 is wrong; this is the sentence that keeps a future agent from acting on it.
- **Do not change `roundHalfUpMs`'s body, name, or KDoc beyond the one added sentence.** A
  round-half-up where a truncation is today is a behaviour change; this row makes none, and the proof
  is that `theDelaysAreDifferencesOfTheRealStarts` and `aTieGoesTo63Not62` are **not edited**.
- **Do not "de-duplicate" the rounding expression out of the TEST file.** `AnimExportPlanTest.kt:88`
  and `:108` spell `floor(end - starts[i] + 0.5)` **on purpose**: they are the independent witness
  that the object's own arithmetic is the real one. Route the object through the function; leave the
  witness alone.
- **Do not remove the `cells` list from `encodeOne`, and do not add a second packer.** Decision 3.
  It frees references, not bytes, and `SpritePacker` is a reviewed landed file.
- **Do not touch `GifEncoder.kt`, `SpritePacker.kt`, `RegionRenderer.kt`, `PngWriter.kt`, `AnimOps.kt`,
  `DocModel.kt`, `DocJson.kt` or `DocOps.kt`.** This row edits two files and two tests. `AnimExport`'s
  KDoc calls itself "three encoders, and nothing else" (`AnimExport.kt:70-71`) and that is still true.
- **Do not put `MAX_EXPORT_PX` in `:core`.** There is a landed test that forbids it:
  `thePlanLayerHoldsNoSizeBudgetAndTheRunnerHoldsTheOnlyOne` (`AnimExportPlanTest.kt:558-569`) asserts
  that the plan layer builds a plan for a 4000×4000 board and that the RUNNER is what refuses. A
  constant in `AnimExportPlan.kt` turns that test red and is a refusal without a board id to name.
- **Do not type the pixel count anywhere.** `16_777_216`, `8_388_608` and `2` as an executable literal
  are all drift; `MAX_REGION_PX * 2L` is the rule. Same for the signature: no `7`, no `8` in a bound.
- **Do not write a sentence that tells the person to find a control that does not exist.** R35: no
  range picker. "Export a shorter range" is a sentence about what to export; the remedy's UI is
  JB-3.05's range chip and this row does not add a control, an `AnimFormat` entry, or a label
  (R39: a dead control is worse than a missing one).
- **Do not catch or wrap `OutOfMemoryError`.** Decision 6 exists so nothing reaches it. A
  `catch (t: Throwable)` around the encode would turn a refusal into a silent empty export, which is
  the failure mode Decision 16 of JB-3.06b exists to prevent.
- **Do not change `writeSequence`'s loop, `timingText`, `sequenceName`, `checkedPaper`,
  `checkPlanFitsBoard`, `checkRegionFits`'s message, or any `AnimFormat` entry.** They are
  JB-3.06b's, they are reviewed, and none of the four findings is in them.
- **Do not treat `PngChunks.PNG_SIGNATURE` as importable.** It is `private` (`PngChunks.kt:148`) in
  `:core`, and the dependency runs `androidkit -> core`. Decision 1 is a restatement; put a comment
  on both sides naming the other file, which is the pattern `safeBaseName` already uses for
  `JbArchive.unsafeReason` (`AnimExportPlan.kt:110-117`).
- No new dependencies, no new files, no `git add` outside the four owner-area paths.

## Stop rule

**STOP, write the question in *Questions* under `for the Lead`, set this row `⛔ Blocked`, commit,
push, and take another task** if any of these is true, rather than deciding it:

- **`internal` is not visible from `androidkit`'s test source set** and the four PNG tests cannot
  reach `pngSize`. (The tree says it is — `PngWriterTest.kt:280` — but a builder who cannot compile
  must not invent a public API or a second entry point to work around it.)
- **A clip with `weights` parallel to the FOLDED frames is refused by `pack`** in a way this spec
  did not predict, or `pack`'s weight rules turn out to reject a weight this row's `ticksOf`
  produces. That is a packer/app-format question.
- **A real board in the tree exceeds `MAX_EXPORT_PX` at its own size** — i.e. the shipped presets, or
  anything `joybrush/assets` or `joybrush/brushes` contains, would be refused by Decision 6. Check
  before you build (see *Definition of done*) and report the numbers; do not raise the constant to
  make it go away.

Three things are never a builder's call in this file, and they are the same three JB-3.06b named:
**the encoding of a format**, **what an exported `.sprite.json` contains** (Question 1), and
**where the files land**. Never write a plausible-looking file to get past an unanswered question.

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` output pasted, 0 failures
- [ ] `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test` output pasted, 0 failures
- [ ] the four-test mutation drill pasted (four reds, in the order above)
- [ ] `git diff` on `AnimExportPlanTest.kt` pasted, showing only the new test and the Site 2 comment
- [ ] `git status --short` shows only the four owner-area paths
- [ ] **the Decision 6 pre-check, pasted:** a one-line sweep that computes
      `decodedPixels` for every animation board in `joybrush/assets` and `joybrush/brushes` (or,
      if no such fixture exists, the statement that there is none) and prints `board → pixels`, so
      the Lead can see what the new ceiling refuses before anybody runs it on a phone
- [ ] **owner check, Note 9 (📱 the owner, not the builder):** export the same 4-frame board as a
      GIF and as a sprite sheet — both open, and **a frame held for 2 ticks is held twice as long in
      both**. Then export a 600-frame 512×512 board: you must get **the sentence**, not a crash, and
      the sentence must name the board and both numbers
- [ ] committed `JB-3.06c: audit fixes`; **the ROADMAP row is set by the Lead** (this spec does not
      touch `ROADMAP.md`)

## Questions

_(Spec writer: `openrouter/stealth/space-bunny-alpha`, 2026-09-29. Findings 1 and 2 are confirmed in
the code; finding 3's remedy is not available inside this row's owner area and finding 4 is wrong.
One question is a format question and is the Lead's; the rest are decisions I made and marked.)_

### For the Lead

1. **Does an exported sprite sheet carry `weights`, or repeated cell indices? — a FILE FORMAT
   question, and the only thing in this row I will not decide.** `Clip.weights` exists
   (`SpritePacker.kt:40`) and `pack` writes it (`:285-288`). JB-3.06b chose repeats and gave the
   reason *"a spec that wrote weights would be writing a field that does not exist"*
   (`JB-3.06b_anim_export.md:408-411`) — a reason that stopped being true when JB-4.03c landed
   (`5fd26419`). **What I have done meanwhile:** corrected the four false sentences, kept the
   repeats, and pinned that choice with `theFoldedClipCarriesNoWeightsKeyAndThePackerWouldWriteOne`
   so it is a fact and not an accident. **What I need from you:** either (a) "repeats stand, the
   comment now says so and the Lead has ruled" — this row closes as written; or (b) "write weights",
   which is a small change to `cellIndices` (`AnimExportPlan.kt:258-264`) plus the inverse
   arithmetic in `ticksOf`, and it changes the bytes of every exported `.sprite.json`. Note that
   writing weights is **not** a bug fix: the repeats are exact today, and
   `noHoldIsLostOrGainedByTheRoundTrip` proves it over all 59 940 rate/hold pairs. It is a
   preference about which of the app's two own vocabularies an exporter should use. **If you want
   it, it belongs in its own row** — it is a format change to a reviewed packer's output, not an
   audit MINOR.
2. **The GIF cannot be made to stream without editing `GifEncoder.kt`, which is JB-3.06a's cleared
   file.** `GifEncoder` retains every frame by design (`GifEncoder.kt:413-415`, `:443`, read back in
   `finish()`), and `SpritePacker.pack` takes the whole list (`:143`, allocating at `:248`), so
   "encode as you go; do not hold every frame in memory" is not reachable in four files. I have
   shipped the reachable half: a budget that refuses in a sentence before frame 1, plus comments
   saying why the list is there. **Do you want a streaming entry point on `GifEncoder` (and one on
   `SpritePacker`)?** If yes, that is a new T2 row against two Built/cleared files, and it is the
   only way R44's remedy can actually be had. My recommendation is **no**: the budget makes the
   crash unreachable, and a streaming `SpritePacker` would have to decide what a partial sheet means.
3. **`MAX_REGION_PX` is still yours, and Decision 6 inherits it.** `RegionRenderer.kt:85-86` reads:
   *"LEAD RULING PENDING (see JB-2.13a `## Questions`): the number is defensible against a Note 9,
   but the implementer picked it, not the Lead, and it is a product decision in disguise."* My budget
   is `MAX_REGION_PX * 2L`, so **your ruling on that number moves this one automatically** and there
   is no second number to rule on. What I need is only whether 2× is the right multiple once you
   have ruled on the base.

### PROVISIONAL — Claude to confirm

4. **Decision 6's multiple: 2.** Derived (64 MiB ÷ 4 B/px = 16 777 216 = 2 × `MAX_REGION_PX`), not
   chosen — but the cost is a product decision and it is in Decision 6 with the arithmetic:
   1024×1024 → 16 frames as a GIF, 7 as a sheet; 512×512 → 64 / 30; 2896×2896 → 2 / 1.
5. **Decision 6's exception type: `RegionException`, not `DocException`.** One type for both memory
   refusals, so a caller wraps `encodeOne` in one `catch`. Reversible, and it names no contract.
6. **Decision 1's `internal` over `private`.** The precedent is in the tree
   (`PngWriterTest.kt:280` → `PngWriter.kt:98`) and the alternative — a public function, or a test
   that cannot reach the check — is worse.
7. **Decision 2's split: fix the sentences, do not switch the format.** See Question 1. If the
   answer is (b), this row's comment work stands and a new row does the switch.
