# JB-8.04b — `KritaZip` inflates with JB-8.02's `inflateRaw`, and so does `zTXt`

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟦 **Ready** — cross-reviewed 2026-09-29. Three defects fixed (below); no Lead ruling is outstanding for the build. Q1's factual half is ruled; the security-bound half stays a non-blocking Lead question. |
| **xr** | xr: openrouter/stealth/space-bunny-alpha 2026-09-29 — **two contract lines would not have compiled**: `inflateMaxOut` takes a `Long` and both zip readers hold `uncompressedSize` as an `Int`, so both call sites need an explicit `.toLong()` (Kotlin does not widen); a Do-not entry added. **Both proved with the Kotlin 2.2.0 compiler, not by eye:** the form as written gives `error: argument type mismatch: actual type is 'Int', but 'Long' was expected.` and the form with `.toLong()` compiles clean. **`readPngCompressedText` had no body** — §D pointed at "Decision 6", which decides where the function lives and why and says nothing about how; the body is now pasted in, every offset a move of `compressedChunk`/`internationalChunk`, with the five refusal sentences marked PROVISIONAL. **The pasted body is compiled, not eyeballed**: it was built against the **real, unmodified** `PngChunks.kt` (only `BrushException`, `inflateRaw`, `inflateMaxOut`, `looksLikeZlib` and the two byte counts stubbed) and came back `exit 0`. **That test also found a fragility worth naming: `walkPngChunks`, `keywordOf`, `latin1` and `indexOfZero` are all `private` *in file*, so this function must physically live inside `PngChunks.kt`** — putting it in a new file in the same package produces a wall of *"it is private in file"* errors, which is the Do-not entry's whole subject one level down. **Q1's factual half ruled against the landed reader, not the spec**: `walkPngChunks` hands its visitor only the *compressed* chunk length (`:210`) and `compressedChunk` keeps no expansion figure (`:286-292`), so there is genuinely no second number for `MAX_INFLATE_RATIO` to divide by — a fact about the format, not a gap. **Every `file:line` in the verification list re-read at the byte level**: `MAX_INFLATE_RATIO`/`MAX_INFLATED_BYTES` and their derivation are at `ProcreateImport.kt:65-76` with `looksLikeZlib` at `:690-701`, both `private`, and grep over all of `joybrush/` finds them in **only** that file and `ProcreateImportTest.kt:676/677/679/683` — so the move breaks no caller and is a move, not a decision. Verified too: `PngTextChunk` is a public three-field data class at `PngChunks.kt:60` with `compressedChunk` returning `("", true)` and keeping no offset, so the `zTXt` inflate genuinely cannot live in `KritaImport`; `MAX_PNG_STRING_BYTES = 8 388 608 < 67 108 864`; `readStored` at `:1388-1435` with the "mostly this message" refusal at `:1402-1408` and callers at `:266`/`:343`/`:890`; the `+ ZLIB_ADLER_BYTES else 0` grouping is right in both the spec and the landed code (a `read`-tool rendering that showed it as `else 0 + …` was checked against the raw bytes and **disproved**); `KritaImportTest` is 1126 lines with no `java.*`, `bundleOf` already takes a per-entry method and writes a stored DEFLATE block; `PngChunksTest` 296 lines with no test needing a change; `InflateTest` 206 lines with the 5 000 ms bound at `:140`; `core/build.gradle.kts:43-51` and `:14`. Two claims corrected: `RealFilesProbeTest.kt` is **46** lines, not 26, and Test 26's "exactly as `RealFilesProbeTest` locates `testdata-local`" described a different anchor than the test uses. |
| **Who** | spec writer: **openrouter/stealth/space-bunny-alpha** 2026-09-29 |
| **Needs** | JB-8.04 (🟧), JB-8.02 (🟧), JB-8.01 (🟧 — it created `ImportSupport.kt`). Nothing else. |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/ImportSupport.kt` · EDIT `.../imports/ProcreateImport.kt` · EDIT `.../imports/Inflate.kt` (**KDoc only — Decision 11**) · EDIT `.../imports/PngChunks.kt` (**additive only — Decision 6**) · EDIT `.../imports/KritaImport.kt` · EDIT `joybrush/core/src/commonTest/.../imports/KritaImportTest.kt` · EDIT `joybrush/core/src/commonTest/.../imports/PngChunksTest.kt` · NEW `joybrush/core/src/commonTest/.../imports/ImportSupportTest.kt` · EDIT `joybrush/core/src/jvmTest/.../imports/ProcreateImportTest.kt` (**four lines, Decision 3**) · NEW `joybrush/core/src/jvmTest/.../imports/KritaBundleInflateTest.kt`<br>**NOT** `core/build.gradle.kts`. **NOT** `joybrush/androidkit/`. **NOT** `Inflate.jvm.kt` — the `actual` is correct as landed and Decision 11 forbids editing it. **NOT** any app file. |
| **Estimated size** | ~90 lines added to `ImportSupport.kt` (mostly KDoc), ~12 lines changed in `ProcreateImport.kt`, ~70 lines added to `PngChunks.kt`, ~140 lines changed in `KritaImport.kt`, ~420 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — see *Command* at the foot, including the R43 worktree rule |

## Why this row exists, in one paragraph

`KritaZip` reads only **STORED** zip entries, and it says so in words: *"Krita zips a bundle's entries with
DEFLATE, so a real `.bundle` is mostly this message."* So as built, `convertBundle` **refuses most real
`.bundle` files** — and a `.bundle` importer that cannot open a bundle is not one. JB-8.04's Decision 5
forbade reaching into JB-8.02's `inflateRaw`, so the JB-8.04 builder **stopped and referred** rather than
deciding, and LEAD_RULINGS **R44 item 2 reversed Decision 5**. This row executes the reversal: one DEFLATE
implementation, one set of bomb caps, no second pair of numbers.

**The two budgets are why this is its own row and not a JB-8.04 edit.** `inflateRaw` takes a `maxOut`, but
the **ratio** budget and the **output** budget that make inflating safe live in the **caller** —
`ProcreateImport.kt:73` and `:76`. Reaching across to the function while re-deriving its caps would be the
worst of both worlds: the *appearance* of sharing with none of the safety. So the caps are **promoted into
`ImportSupport.kt`** and both importers read them, which is R23's "a number written twice is one writer
believing it twice" made into code. `ImportSupport.kt` is in **nobody's** owner area — it was created by
JB-8.01 with a header that says *"JB-8.02 and JB-8.04 read it and do not edit it"* — and that is exactly
why this needs a spec rather than a patch.

**Not a licence question (R8, blueprint §5).** Nothing here extracts, copies or redistributes a
third-party brush. Reading a file the user already owns and holding it in memory for a moment is
importing. What is forbidden is *shipping* someone else's brush, and this row changes no licensing,
attribution or storage rule: `KritaImport`'s Decision 14 (`license` from the bundle's own `meta.xml`,
never `"CC0"`) is untouched and is tested again here against a **DEFLATE** `meta.xml` (Test 19).

## The three questions this spec had to answer before anything else

1. **Where does the inflate call live for a `zTXt`?** Not in `KritaImport`. `PngChunks` already threw the
   payload away: `compressedChunk` (`PngChunks.kt:286-292`) returns `PngTextChunk(keyword, "", true)` and
   keeps no offset, so **`KritaImport` cannot inflate a `zTXt` with what `PngChunks` gives it today.**
   The inflate therefore goes **into `PngChunks`**, additively, and `KritaImport` asks it for the text.
   Reasoning in Decision 6.
2. **Should `zTXt` be inflated on the strength of the flag alone?** **No.** The PNG compression-method byte
   has to be read and has to be `0`. A flag says "this is compressed", not "this is deflate", and this is a
   stranger's file. Decision 7.
3. **What about the un-flagged case?** An `iTXt` whose compression flag is `0` is read as plain text
   **today and stays that way**. It is never speculatively inflated: if its text is not XML, the existing
   *"does not begin with `<`"* refusal fires. Decision 8.

---

# Contract (verbatim)

Everything below is pasted from the landed source. Where a line is being *replaced*, both the old and the
new are given, so nothing here says "see the other file".

## A. `ImportSupport.kt` — the two caps and the zlib wrapper move here

### A.1 The header comment, which currently forbids this row

Today, verbatim (`ImportSupport.kt:1-8`):

```kotlin
package cc.joycreator.joybrush.core.brush.imports

/**
 * What the three Phase 8 importers have in common, in one file so that "the same rule" is a fact
 * about the code rather than a promise made three times in three markdown files.
 *
 * JB-8.01 creates this file (spec Decision 2). JB-8.02 and JB-8.04 **read** it and do not edit it.
 */
```

It becomes (Decision 5):

```kotlin
package cc.joycreator.joybrush.core.brush.imports

/**
 * What the Phase 8 importers have in common, in one file so that "the same rule" is a fact about the
 * code rather than a promise made three times in three markdown files.
 *
 * JB-8.01 created this file (its spec Decision 2). JB-8.02 and JB-8.04 **read** it.
 * **JB-8.04b is the first row to EDIT it**, and it edits it for one reason: the DEFLATE bomb caps had
 * nowhere else to live. `inflateRaw` is one function, but the numbers that make inflating safe were
 * `ProcreateImport`'s, so a second importer either shared the numbers or wrote its own. R23 settles which.
 */
```

### A.2 What is ADDED, verbatim, after `TEXTURE_NOT_DRAWN` (`ImportSupport.kt:51`)

```kotlin
// ---- the DEFLATE bomb caps, promoted from ProcreateImport by JB-8.04b ------------------------------------
//
// **These two numbers are a security bound and this row does not get a vote on them.** They are *moved*,
// not chosen: the landed values, their KDoc and their derivation are in the contract below, and a value
// that changed here would be a decision nobody made. What this row decides is **where they live**.
//
// A zip bomb is refused twice, and the two refusals answer two different questions. The **ratio** answers
// "is this entry trying to be a bomb?", which is arithmetic over two numbers the file states and costs
// nothing to check. The **output ceiling** answers "how much may this build be made to hold?", which is
// the only one of the two that a file cannot talk its way past — the declared size is not believed, and
// `inflateRaw`'s own `maxOut` check fires *before* the bytes are written.

/**
 * How much bigger than its payload an entry may claim to be: 200:1.
 *
 * **Checked before anything is inflated, and that is the whole point of the number.** DEFLATE
 * reaches about 1 038:1 on a run of zeroes, so 200:1 is comfortably above every real brush
 * bitmap and far below what a bomb needs. A `.brush` that declares 100 MiB out of 1 KiB is
 * refused by arithmetic rather than by a memory limit, which costs nothing to refuse.
 *
 * **Where this is checkable, and where it is not:** a zip's central directory states both sizes, so this
 * applies to every method-8 entry. A PNG's `zTXt` states **no** uncompressed size, so for a compressed
 * text chunk this number has nothing to compare and the only bound is [MAX_INFLATED_BYTES], enforced
 * inside the `actual` before a byte is written. That asymmetry is a fact about two file formats, not a
 * gap, and it is why the compressed-chunk path may never ask for more than
 * `PngChunks.MAX_PNG_STRING_BYTES`.
 */
const val MAX_INFLATE_RATIO = 200L

/** What one deflate entry may inflate to. 64 MiB, and the declared size is **not** believed. */
const val MAX_INFLATED_BYTES = 64L * 1024 * 1024
```

> **The KDoc above is a copy of the landed KDoc in `ProcreateImport.kt:65-76` and the two declarations are
> byte-for-byte the landed ones.** They move; they do not change. R44 item 5's "`convertBrush` throws on
> brush-level faults" note is unrelated and stays where it is.

### A.3 `inflateMaxOut` — the one formula, and the one zlib-wrapper probe

Also added to `ImportSupport.kt`, verbatim:

```kotlin
/**
 * The `maxOut` to hand [inflateRaw], and **the only place that expression is written.**
 *
 * `minOf(wantsAtMost, MAX_INFLATED_BYTES)`, floored at 0. Two callers, two different reasons to pass
 * something less than the ceiling, and one rule: **you may ask for less when the destination has a
 * smaller cap, and never for more.**
 *
 *  - a **zip entry** passes the uncompressed size the central directory declares, so a file that lies
 *    about its size is bounded by what it claimed, exactly as JB-8.02 decided;
 *  - a **PNG text chunk** passes `PngChunks.MAX_PNG_STRING_BYTES`, because what comes out of a `zTXt` is a
 *    text chunk's text and the string cap is already the number that governs one. A compressed text chunk
 *    therefore cannot ask for more than a *compressed* text chunk is allowed to be, and Test 2 pins that
 *    it never can.
 *
 * This function exists so the *shape* is written once. Two `minOf` expressions in two files is the same
 * hazard as two constants in two files, and it is one that changes only when somebody notices.
 */
internal fun inflateMaxOut(wantsAtMost: Long): Int =
    minOf(wantsAtMost, MAX_INFLATED_BYTES).coerceAtLeast(0L).toInt()

/**
 * Do the two bytes at [at] form a zlib header? `CMF`'s low nibble is the compression method and must
 * be 8, and the pair must be a big-endian multiple of 31.
 *
 * **A test, not a requirement** — see the note in `ProcreateImport.Zip.read`. A false positive costs two
 * bytes of a stream that was never going to decode, so it fails loudly rather than silently, and a true
 * negative on a real zlib-wrapped entry is the case the spec describes.
 */
internal fun looksLikeZlib(data: ByteArray, at: Int): Boolean {
    if (at + 2 > data.size) return false
    val cmf = data[at].toInt() and 0xFF
    val flg = data[at + 1].toInt() and 0xFF
    return cmf and 0x0F == 8 && (cmf * 256 + flg) % 31 == 0
}

/** RFC 1950's two-byte header: `CMF` then `FLG`. */
internal const val ZLIB_HEADER_BYTES = 2

/** RFC 1950's four-byte Adler-32 of the *uncompressed* data. Not DEFLATE, so it comes off too. */
internal const val ZLIB_ADLER_BYTES = 4
```

`looksLikeZlib`, `ZLIB_HEADER_BYTES` and `ZLIB_ADLER_BYTES` are **moved verbatim** from
`ProcreateImport.kt:690-701` (where they are `private`) and deleted there (Decision 4). A **top-level
function** resolves by name *and* signature, so a same-named one in the same package is not the class-level
trap `ProcreateImport.kt:713` warns about — but they are deleted anyway so there is one of each.

## B. `ProcreateImport.kt` — the caps leave; nothing else does

**Deleted** (`ProcreateImport.kt:49`–`76`, only lines 65-76; `MAX_ARCHIVE_BYTES` at `:49` stays):

```kotlin
    /**
     * How much bigger than its payload an entry may claim to be: 200:1.
     *
     * **Checked before anything is inflated, and that is the whole point of the number.** DEFLATE
     * reaches about 1 038:1 on a run of zeroes, so 200:1 is comfortably above every real brush
     * bitmap and far below what a bomb needs. A `.brush` that declares 100 MiB out of 1 KiB is
     * refused by arithmetic rather than by a memory limit, which costs nothing to refuse.
     */
    const val MAX_INFLATE_RATIO = 200L

    /** What one deflate entry may inflate to. 64 MiB, and the declared size is **not** believed. */
    const val MAX_INFLATED_BYTES = 64L * 1024 * 1024
```

**The four call sites**, `ProcreateImport.kt:438`, `:442`, `:445`, `:448`, become `MAX_INFLATE_RATIO` and
`MAX_INFLATED_BYTES` — bare, because `ImportSupport.kt` is top-level in the same package, exactly as
`MAX_EXTENSION_BYTES` and `TEXTURE_NOT_DRAWN` already are. The KDoc links at `:421` and `:424` read
`[MAX_INFLATE_RATIO]` / `[MAX_INFLATED_BYTES]`, and the comment at `:507` reads
"`maxOut` is the declared size capped at `MAX_INFLATED_BYTES`".

**`ProcreateImport.kt:514`** becomes:

```kotlin
        val maxOut = inflateMaxOut(entry.uncompressedSize.toLong())
```

> **The `.toLong()` is load-bearing and it is not optional.** `Zip.Entry.uncompressedSize` is an
> `Int` (`ProcreateImport.kt:398`) and `inflateMaxOut` takes a `Long` (A.3). **Kotlin does not widen
> `Int` to `Long` implicitly**, so `inflateMaxOut(entry.uncompressedSize)` is a compile error
> ("type mismatch: inferred type is `Int` but `Long` was expected"), not a conversion. The landed
> line it replaces spelled the same arithmetic out by hand (`minOf(entry.uncompressedSize.toLong(),
> …)`) and therefore had the conversion in it; the call hides it, so the call has to carry it.

**`ProcreateImport.kt:510-512`** is unchanged in body and now reads the shared constants:

```kotlin
        val wrapped = looksLikeZlib(data, at)
        val from = at + if (wrapped) ZLIB_HEADER_BYTES else 0
        val length = entry.compressedSize - if (wrapped) ZLIB_HEADER_BYTES + ZLIB_ADLER_BYTES else 0
```

**Nothing else in this file changes.** Not the refusals, not their order, not the numbers they name. The
reason is in the Decision: this row is a *move*, and a move that also edits is a copy that thinks it is a
change.

## C. `Inflate.kt` — KDoc only, and the signature is not negotiable

`Inflate.kt:22` is **byte-identical after this row**:

```kotlin
internal expect fun inflateRaw(deflate: ByteArray, offset: Int, length: Int, maxOut: Int): ByteArray
```

Only the KDoc paragraph at `Inflate.kt:6-8`, which makes a claim **the landed code disproves**, is
rewritten:

```kotlin
 * Inflate a **raw DEFLATE stream (RFC 1951)** — the payload of one zip entry, with no zlib and no
 * gzip wrapper around it. **A zip's method 8 is raw DEFLATE, not zlib** (PKWARE APPNOTE 4.4.5), and the
 * spec that first said otherwise was wrong: `ProcreateImport` reads and checks that header itself and
 * hands this function the bytes after it (Decision 5).
```

becomes:

```kotlin
 * Inflate a **raw DEFLATE stream (RFC 1951)** — the payload of one zip entry, with no zlib and no
 * gzip wrapper around it. **A zip's method 8 is raw DEFLATE, not zlib** (PKWARE APPNOTE 4.4.5), which is
 * a correction of an earlier claim in JB-8.02's spec and was found by building a fixture, not by
 * reading. The wrapper is therefore **tolerated, not required**: a caller that sees a zlib header strips
 * both of its ends and hands this function the raw stream. Two callers do this — `ProcreateImport` for a
 * zip entry and `PngChunks` for a `zTXt`, whose PNG specification *does* say zlib — and the probe is
 * `ImportSupport.looksLikeZlib`, one of them.
 *
 * **The wrapper's four-byte Adler-32 trailer is removed by the caller and is NOT verified.** That is the
 * landed `ProcreateImport` decision and this row copies it; see its Questions for the Lead.
```

## D. `PngChunks.kt` — one function added, nothing existing touched

**Everything in `PngChunks.kt` today is unchanged and must stay unchanged.** `PngTextChunk` keeps its
three fields, `readPngTextChunks` keeps returning `compressed = true` with an empty `text`, and **no test
in `PngChunksTest` needs to change.** What is added, verbatim:

```kotlin
/**
 * The **text** of the first chunk whose keyword is [keyword] and which asks to be compressed, inflated and
 * decoded with that chunk type's own encoding — or null when this file has no such chunk.
 *
 * **Why this lives here and not in the importer, and it is not tidiness.** (1) `readPngTextChunks` keeps no
 * offset and no payload for a compressed chunk, so a caller holding only a `PngTextChunk` cannot inflate it
 * — the bytes are simply gone, and no amount of calling discipline gets them back. (2) The **encoding** is
 * a property of the chunk *type*: PNG says a `zTXt` holds Latin-1 and an `iTXt` holds UTF-8, and only this
 * file knows which of the two it is looking at. A caller that decoded it would need the type handed to it
 * as a second return value, and a caller handed the wrong encoding mangles every brush name with an accent
 * in it — silently, and in a way no test that used ASCII would ever catch.
 *
 * **The cap is this file's own `MAX_PNG_STRING_BYTES`, not a number the caller passes.** A `zTXt` states no
 * uncompressed size, so [MAX_INFLATE_RATIO] has nothing to compare and the *only* bomb defence is the
 * output ceiling — which is why the ceiling asked for here is the string cap and is additionally bounded
 * by `ImportSupport.MAX_INFLATED_BYTES` through [inflateMaxOut]. Test 2 in `ImportSupportTest` pins that
 * the string cap is strictly tighter, so a later row cannot quietly make this path looser.
 *
 * **A zlib wrapper is stripped from both ends and not verified** (`ImportSupport.looksLikeZlib`): PNG
 * specifies a `zTXt` payload as a zlib stream, so the wrapper is *expected* to be there, which makes this
 * the opposite of the zip case — there the wrapper is rare and a false positive is 1 in ~496, here it is
 * the normal shape. **The compression method byte is read and must be 0**, and anything else is a refusal
 * naming the method: a flag says "compressed", not "deflate", and this is a stranger's file.
 *
 * @throws BrushException if the chunk is a compressed `zTXt`/compressed `iTXt` whose compression method is
 *   not 0, whose language or translated-keyword field is unterminated, whose payload is not a DEFLATE
 *   stream, or which would expand past the cap. **Never returns a short string**: a truncated text chunk
 *   is a file that is not what it says.
 */
internal fun readPngCompressedText(bytes: ByteArray, keyword: String): String?
```

**Its body, verbatim.** Decision 6 decides **where** the function lives and **why**; this is the code,
and every offset in it is a **move** of landed arithmetic rather than a new decision — the two chunk
shapes are read by the landed `compressedChunk` (`PngChunks.kt:286-292`) and `internationalChunk`
(`PngChunks.kt:303-326`), and this re-derives the payload start from the same two expressions. The
only free choices in it are the five refusal **sentences**, and those are marked
**PROVISIONAL — Claude to confirm** (Questions) because wording is reversible and a sentence is what a
person reads.

```kotlin
internal fun readPngCompressedText(bytes: ByteArray, keyword: String): String? {
    var found: String? = null
    walkPngChunks(bytes) { type, at, length ->
        if (type != "zTXt" && type != "iTXt") return@walkPngChunks true
        // The keyword is read and compared FIRST, so a refusal about the shape of *this* chunk is
        // never raised on behalf of a caller that asked about a different one.
        val kw = keywordOf(bytes, at, length, type)
        if (latin1(bytes, at, kw) != keyword) return@walkPngChunks true
        val payloadAt: Int
        val encodingIsUtf8: Boolean
        when (type) {
            "zTXt" -> {
                if (length - kw - 1 < 1) {
                    throw BrushException("its \"$type\" chunk is truncated before its compression method")
                }
                val method = bytes[at + kw + 1].toInt() and 0xFF
                if (method != 0) {
                    throw BrushException(
                        "its \"$type\" chunk is compressed with method $method, and this build reads " +
                            "only 0 (deflate)",
                    )
                }
                payloadAt = at + kw + 2
                encodingIsUtf8 = false
            }
            else -> {
                val flag = at + kw + 1
                if (flag + 2 > at + length) {
                    throw BrushException("its \"$type\" chunk is truncated before its compression flags")
                }
                if (bytes[flag].toInt() and 0xFF == 0) {
                    // Uncompressed: `readPngTextChunks` already returned this one's text, and
                    // Decision 8 says it is never speculatively inflated.
                    return@walkPngChunks true
                }
                val method = bytes[flag + 1].toInt() and 0xFF
                if (method != 0) {
                    throw BrushException(
                        "its \"$type\" chunk is compressed with method $method, and this build reads " +
                            "only 0 (deflate)",
                    )
                }
                // The two NUL-terminated fields in front of the text, skipped exactly as
                // `internationalChunk` skips them: language tag, then translated keyword.
                var cursor = flag + 2
                val language = indexOfZero(bytes, cursor, at + length - cursor) ?: -1
                if (language < 0) throw BrushException("its \"$type\" chunk's language tag is not terminated")
                cursor += language + 1
                val translated = indexOfZero(bytes, cursor, at + length - cursor) ?: -1
                if (translated < 0) {
                    throw BrushException("its \"$type\" chunk's translated keyword is not terminated")
                }
                payloadAt = cursor + translated + 1
                encodingIsUtf8 = true
            }
        }
        val payloadLength = at + length - payloadAt
        if (payloadLength < 1) {
            throw BrushException("its \"$type\" chunk is compressed but carries no text")
        }
        val wrapped = looksLikeZlib(bytes, payloadAt)
        val from = payloadAt + if (wrapped) ZLIB_HEADER_BYTES else 0
        val deflated = payloadLength - if (wrapped) ZLIB_HEADER_BYTES + ZLIB_ADLER_BYTES else 0
        if (deflated < 1) throw BrushException("its \"$type\" chunk holds no DEFLATE data")
        val plain = inflateRaw(bytes, from, deflated, inflateMaxOut(MAX_PNG_STRING_BYTES.toLong()))
        found = if (encodingIsUtf8) {
            decodeUtf8Strict(plain, 0, plain.size, "\"$type\" \"$keyword\"")
        } else {
            latin1(plain, 0, plain.size)
        }
        false   // first match wins, exactly as `KritaImport.kt:300` takes the first `preset`
    }
    return found
}
```

**What is decided here and was not before, and why none of it is a judgement:**

- **The walk.** `walkPngChunks` is `private` in this same file (`PngChunks.kt:166`), so it is
  reachable and reusing it is what keeps the file caps, the chunk-length arithmetic and the
  `Long`-before-add rule from having a second copy. A second hand-rolled walk here would be the
  single worst thing a builder could add to this row.
- **The first match wins.** `KritaImport.kt:300` takes the *first* `preset` chunk and this row must
  hand back that same one, or the importer would inflate a different chunk than the one whose
  `compressed` flag it read. `visit` returning `false` is how the walk stops
  (`PngChunks.kt:210`), and it is the same early exit `readPngHeader` uses (`:133`).
- **The keyword is compared by value, case sensitively, and BEFORE any shape refusal** — the PNG rule,
  and the same rule `KritaImport.kt:298` states for its own comparison. Comparing first is what stops a
  bad compression method on some *other* keyword's chunk from being raised against a caller that
  asked about a different one.
- **An uncompressed `iTXt` is skipped, not refused**, and the keyword comparison has already happened,
  so skipping it is not the same as not finding it.
- **The payload offsets** are `compressedChunk`'s and `internationalChunk`'s arithmetic, moved:
  `zTXt` = keyword, NUL, method, payload at `at + kw + 2`; a compressed `iTXt` = keyword, NUL, flag,
  method, language, NUL, translated, NUL, payload after the second NUL. Nothing here is new.
- **`inflateMaxOut(MAX_PNG_STRING_BYTES.toLong())`** — the cap is this file's own string cap, floored
  by the shared ceiling (A.3). Test 2 pins that the string cap is the tighter of the two, so the
  `.toLong()` here is `Int → Long` on a `const`, the one place where the widening is genuinely a no-op
  and is written only because `inflateMaxOut` takes a `Long`.

**Do not add a parameter for the cap** — see the Do-not list. **Do not widen `PngTextChunk`** to
avoid the re-walk; that is the trap Decision 6 exists to close, and it would put the payload back in
the hands of a caller that has to remember the encoding.

## E. `KritaImport.kt` — the swap

### E.1 `KritaZip.readStored` is replaced by `KritaZip.read`

**Today** (`KritaImport.kt:1388-1435`), verbatim:

```kotlin
        /** One entry's bytes, and the refusal that says why a compressed one is not available. */
        fun readStored(entry: Entry): ByteArray {
            if (entry.name.length > KritaImport.MAX_ENTRY_NAME_CHARS) {
                throw BrushException(
                    "its entry name is ${entry.name.length} characters, at most " +
                        "${KritaImport.MAX_ENTRY_NAME_CHARS}"
                )
            }
            if (entry.compressedSize.toLong() > KritaImport.MAX_ENTRY_BYTES) {
                throw BrushException(
                    "\"${entry.name}\" holds ${entry.compressedSize} bytes, at most " +
                        "${KritaImport.MAX_ENTRY_BYTES}"
                )
            }
            if (entry.method != STORED) {
                throw BrushException(
                    "\"${entry.name}\" uses zip compression method ${entry.method}, and this build has no " +
                        "inflater (Decision 5), so it cannot be read out of the bundle. Krita zips a " +
                        "bundle's entries with DEFLATE, so a real .bundle is mostly this message"
                )
            }
            if (entry.compressedSize != entry.uncompressedSize) {
                throw BrushException(
                    "\"${entry.name}\" is stored but says ${entry.uncompressedSize} bytes out of " +
                        "${entry.compressedSize}, which a stored entry cannot be"
                )
            }
            val local = entry.localOffset
            if (local < 0 || local + LOCAL_HEADER > data.size) {
                throw BrushException("\"${entry.name}\" starts at byte $local, outside the bundle")
            }
            if (KritaImport.le32(data, local, "the local header of \"${entry.name}\"") != LOCAL_SIGNATURE) {
                throw BrushException("\"${entry.name}\" has no local file header at byte $local")
            }
            // The local header repeats the name and the sizes, and the data starts after **its own** name and
            // extra fields. Those lengths are not necessarily the central directory's, and reading from the
            // central directory's is how a reader lands in the middle of a name.
            val localName = KritaImport.le16(data, local + 26, "the name length of \"${entry.name}\"")
            val localExtra = KritaImport.le16(data, local + 28, "the extra length of \"${entry.name}\"")
            val start = local + LOCAL_HEADER + localName + localExtra
            if (start < 0 || start + entry.compressedSize > data.size) {
                throw BrushException(
                    "\"${entry.name}\" claims ${entry.compressedSize} bytes from byte $start, past the end " +
                        "of the bundle"
                )
            }
            return data.copyOfRange(start, start + entry.compressedSize)
        }
```

**After** this row, verbatim:

```kotlin
        /** One entry's bytes, bounded on the way in and on the way out. */
        fun read(entry: Entry): ByteArray {
            if (entry.name.length > KritaImport.MAX_ENTRY_NAME_CHARS) {
                throw BrushException(
                    "its entry name is ${entry.name.length} characters, at most " +
                        "${KritaImport.MAX_ENTRY_NAME_CHARS}"
                )
            }
            if (entry.compressedSize.toLong() > KritaImport.MAX_ENTRY_BYTES) {
                throw BrushException(
                    "\"${entry.name}\" holds ${entry.compressedSize} bytes, at most " +
                        "${KritaImport.MAX_ENTRY_BYTES}"
                )
            }
            if (entry.method == DEFLATED && entry.uncompressedSize > 0 &&
                entry.uncompressedSize > entry.compressedSize * MAX_INFLATE_RATIO
            ) {
                throw BrushException(
                    "\"${entry.name}\" claims ${entry.uncompressedSize} bytes out of ${entry.compressedSize}, " +
                        "over the ${MAX_INFLATE_RATIO}:1 limit this build will expand"
                )
            }
            if (entry.method == DEFLATED && entry.uncompressedSize > MAX_INFLATED_BYTES) {
                throw BrushException(
                    "\"${entry.name}\" inflates to ${entry.uncompressedSize} bytes, " +
                        "at most ${MAX_INFLATED_BYTES}"
                )
            }
            if (entry.method != STORED && entry.method != DEFLATED) {
                throw BrushException(
                    "\"${entry.name}\" uses compression method ${entry.method}; this build reads " +
                        "$STORED (stored) and $DEFLATED (deflate), and will not guess at the rest"
                )
            }
            if (entry.method == STORED && entry.compressedSize != entry.uncompressedSize) {
                throw BrushException(
                    "\"${entry.name}\" is stored but says ${entry.uncompressedSize} bytes out of " +
                        "${entry.compressedSize}, which a stored entry cannot be"
                )
            }
            val local = entry.localOffset
            if (local < 0 || local + LOCAL_HEADER > data.size) {
                throw BrushException("\"${entry.name}\" starts at byte $local, outside the bundle")
            }
            if (KritaImport.le32(data, local, "the local header of \"${entry.name}\"") != LOCAL_SIGNATURE) {
                throw BrushException("\"${entry.name}\" has no local file header at byte $local")
            }
            // The local header repeats the name and the sizes, and the data starts after **its own** name and
            // extra fields. Those lengths are not necessarily the central directory's, and reading from the
            // central directory's is how a reader lands in the middle of a name.
            val localName = KritaImport.le16(data, local + 26, "the name length of \"${entry.name}\"")
            val localExtra = KritaImport.le16(data, local + 28, "the extra length of \"${entry.name}\"")
            val at = local + LOCAL_HEADER + localName + localExtra
            if (at < 0 || at + entry.compressedSize > data.size) {
                throw BrushException(
                    "\"${entry.name}\" claims ${entry.compressedSize} bytes from byte $at, past the end " +
                        "of the bundle"
                )
            }
            if (entry.method == STORED) return data.copyOfRange(at, at + entry.compressedSize)

            val wrapped = looksLikeZlib(data, at)
            val from = at + if (wrapped) ZLIB_HEADER_BYTES else 0
            val length = entry.compressedSize - if (wrapped) ZLIB_HEADER_BYTES + ZLIB_ADLER_BYTES else 0
            if (length < 1) throw BrushException("\"${entry.name}\" holds no DEFLATE data")
            return inflateRaw(data, from, length, inflateMaxOut(entry.uncompressedSize.toLong()))
        }
```

> **The `.toLong()` again, for the same reason** (`KritaZip.Entry.uncompressedSize` is an `Int`,
> `KritaImport.kt:1383`; `inflateMaxOut` takes a `Long`). Two call sites, two chances to write it
> without, and a build that stops at step 8 rather than at step 5.

The three bomb refusals and the method refusal are **copied character for character** from
`ProcreateImport.Zip.read` (`ProcreateImport.kt:431-455`), because "one set of bomb caps" is only true if
the *rules* are the same rules and not merely the same numbers. The **order** is Procreate's and a test
depends on it (Test 12). `readStored`'s three callers inside `KritaImport` — `convertBundle` (`:266`),
`readBundleMeta` (`:344`) and `texture()` (`:890`) — all become `read(entry)`.

### E.2 `readKppFile`'s compressed-chunk refusal becomes an inflate

**Today** (`KritaImport.kt:308-316`), verbatim:

```kotlin
        if (found.compressed) {
            throw BrushException(
                "this file's \"preset\" chunk is compressed (a zTXt, or an iTXt that asks to be), and this " +
                    "build does not inflate a PNG text chunk: Krita writes the chunk uncompressed, so a " +
                    "compressed one means the file was written by something else. Re-save the brush from " +
                    "Krita and it will read"
            )
        }
        val xml = found.text
```

**After**, verbatim:

```kotlin
        val xml = if (found.compressed) {
            readPngCompressedText(bytes, "preset")
                ?: throw BrushException(
                    "its \"preset\" chunk is marked compressed, but walking the file again did not find " +
                        "one: this build's PNG reader and its compressed-chunk reader disagree, so nothing " +
                        "here is guessed at"
                )
        } else {
            found.text
        }
```

The block that follows — the blank / `startsWith("<")` check (`KritaImport.kt:317-322`) — is unchanged and
now runs on the inflated text as well. One addition to it, inside the failure branch, is required: when the
chunk was compressed the message must also say **"after inflating"**, so a person is not sent looking for
corruption in a file that was merely compressed.

### E.3 `texture()`'s method branch is deleted

**Today** (`KritaImport.kt:884-907`), verbatim:

```kotlin
            // Branch two first: does the name resolve to a STORED entry of this bundle?
            val zip = bundle
            val entry = zip?.let { z -> z.find("patterns/$name") ?: z.find(name) }
            if (zip != null && entry != null) {
                if (entry.method == KritaZip.STORED) {
                    val bytes = try {
                        zip.readStored(entry)
                    } catch (e: BrushException) {
                        grainUnreadable = e.message
                        null
                    }
                    if (bytes != null) {
                        grainStored = KritaStored(bytes, GRAIN_IMAGE_FILE, 0)
                        extensions[TEXTURE_PATTERN_KEY] = textureName!!
                        return
                    }
                } else {
                    // Decision 12: a DEFLATE entry needs an inflater this row does not have (Decision 5), so
                    // it falls back to the named case and says why.
                    grainUnreadable = "the pattern \"$name\" is inside this bundle but uses zip compression " +
                        "method ${entry.method}, which this build has no inflater for (Decision 5), so its " +
                        "bytes could not be read out of the bundle"
                }
            }
```

**After**, verbatim:

```kotlin
            // Branch two first: does the name resolve to an entry of this bundle?
            val zip = bundle
            val entry = zip?.let { z -> z.find("patterns/$name") ?: z.find(name) }
            if (zip != null && entry != null) {
                val bytes = try {
                    zip.read(entry)
                } catch (e: BrushException) {
                    // A pattern this build cannot read is **not** a reason to refuse a brush: the name is
                    // kept, the grain stays a cloud, and the reason is carried into the warning. Every
                    // refusal that reaches here is one `read` made — a bomb, a method, a truncated
                    // stream — and all of them are more informative than the single message this replaced.
                    grainUnreadable = e.message
                    null
                }
                if (bytes != null) {
                    grainStored = KritaStored(bytes, GRAIN_IMAGE_FILE, 0)
                    extensions[TEXTURE_PATTERN_KEY] = textureName!!
                    return
                }
            }
```

Note what this **deletes**: the "a DEFLATE entry needs an inflater this row does not have" branch, which
existed only because the inflater was missing. `KritaZip.STORED` becomes referenced only by the copy that
names the methods in the refusal, which is the right use of the constant.

### E.4 The KDoc that must stop saying the opposite

Five comments, all in this row's owner area, all currently false after the change. Each is named so a
reviewer can grep for the phrase and know it is gone (Test 23).

| File:line | Today it says | Becomes |
|---|---|---|
| `KritaImport.kt:64-73` | `### No inflater, and that is a decision rather than a module boundary` … *"**Consequence, reported not hidden: a real `.bundle` zips its entries with DEFLATE, so `convertBundle` refuses a great many real bundles.**"* | `### One inflater, and that is the point` — the shared `inflateRaw`, the shared caps, and R44 item 2 by name. **The sentence saying a real bundle is mostly this message is deleted, not reworded.** |
| `KritaImport.kt:1363-1372` | `KritaZip`'s own KDoc: *"It reads STORED entries and refuses DEFLATE ones in words (Decision 5)"* and the two things it deliberately does not do | Both STORED **and** DEFLATE are read; the *one* thing it still deliberately does not do is **verify a CRC** — keep that, with its reason, because it is still true. |
| `KritaImport.kt:278-285` | `readKppFile`'s KDoc: *"so a compressed chunk arrives here as `compressed = true` with an **empty** `text` and **this** is the code that refuses it in words"* | The arrival is unchanged; what changes is that this code now **asks for the text and inflates it**, and the test 4 pin from the other side is no longer the whole story. |
| `PngChunks.kt:56-58` (`PngTextChunk`) and `:74-78` (`readPngTextChunks`) | *"this reader never inflates (KritaImport Decision 5), so a caller refuses it in words"* | **Still true of `readPngTextChunks`** — that function does not inflate, and its contract is unchanged. Only the attribution changes: it is not a policy of JB-8.04, it is where the boundary sits, and `readPngCompressedText` is the other side of it. Wording is this row's; the **claim is unchanged**. |
| `PngChunks.kt:279-285` (`compressedChunk`) and `:297-302` (`internationalChunk`) | *"no inflater is called and no budget is invented here (Decision 5)"* / *"this build has no inflater (Decision 5)"* | Same: still true of *these two functions*, minus the "no budget is invented here", which is now a promise the file keeps and Test 2 checks. |

---

# Decisions

Each is decided, with the reason it is decided rather than merely stated.

**1. The two caps are MOVED to `ImportSupport.kt`, and their values do not change.**
`MAX_INFLATE_RATIO = 200L` and `MAX_INFLATED_BYTES = 64L * 1024 * 1024` are security bounds, and a writer
does not get to choose one. They are not chosen here either: the landed `ProcreateImport.kt:65-76` already
fixed both, with a derivation (DEFLATE reaches ~1 038:1 on a run of zeroes, so 200:1 is above every real
brush bitmap and far below a bomb) that is a fact about RFC 1951 and not about taste. **Per the rule that
governs this file, this is a move and not a decision at all** — a security bound the landed code already
fixes is a fact to be relocated, and a writer who re-derives it has silently become the author of it. The
only question in scope was *where the number lives*, and the answer is R23's: one number, one home, one
writer. **What breaks if this is not done:** the moment `KritaZip` grows its own `const val MAX_INFLATE_RATIO`,
there are two numbers, one per importer, and nothing in any build can notice when they disagree — which is
the definition of drift. R23's phrasing is that a number written twice is one writer believing it twice;
the promoted constant is the only way to make the second belief impossible rather than merely discouraged.

**2. They are `const val`s in `ImportSupport.kt`, not members of an `object`, and not kept as aliases on
`ProcreateImport`.** Verified against the landed tree: the two constants are referenced **only** inside
`ProcreateImport.kt` and `ProcreateImportTest.kt` — nothing in `androidkit`, nothing in `joybrush-android`,
nothing else in `core` — so moving them breaks no caller. The style matches the file they move into
(`MAX_EXTENSION_BYTES` and `TEXTURE_NOT_DRAWN` are already top-level `const val`s there). **Not aliases:**
`ProcreateImport.MAX_INFLATE_RATIO = MAX_INFLATE_RATIO` would be safe-but-two-names, and inside
`object ProcreateImport` the member would *shadow* the top-level constant at every unqualified use — a
reader would then be unable to tell which one the code means. One name, one thing.

**3. `ProcreateImportTest.kt` is in the owner area for four lines, and nothing else.**
`:676`, `:677`, `:679`, `:683` read `ProcreateImport.MAX_INFLATED_BYTES` / `.MAX_INFLATE_RATIO` and become
the bare `ImportSupport` names. The test's *assertions* do not change: it still asserts against the
constant rather than a retyped literal, and the ratio it computes from the fixture is still asserted to be
inside `1..MAX_INFLATE_RATIO` so the two refusals stay distinguishable (that is what the comment at `:673-674`
is for, and it still holds). Listing a test file in an owner area is not a licence to change a test; the
four lines are the whole of it, and Test 18 exists so that a change to the *behaviour* would turn red.

**4. `looksLikeZlib` and the two RFC 1950 byte counts move to `ImportSupport.kt` too.** Four lines of
arithmetic with a documented false-positive rate, needed identically by a zip reader and by a `zTXt`
reader. Leaving a second copy is the exact drift R23 forbids, and a `zlib`-stripping bug fixed in one copy
and not the other is a bomb gate that is on in one importer and off in the other. The private declarations
at `ProcreateImport.kt:690-701` are **deleted**, not left.

**5. `ImportSupport.kt`'s header comment is rewritten, and it is a requirement, not a nicety.** It currently
says *"JB-8.02 and JB-8.04 **read** it and do not edit it."* This row is the first to edit it, so leaving
the sentence tells the next builder that the file is untouchable — and the next such row will be a bundle
row that copies the caps again for exactly the reason this one exists.

**6. The `zTXt` inflate goes into `PngChunks.kt`, additively, and `PngTextChunk` does not change.**
This is the decision most likely to be got wrong, so here is the reasoning in full. `PngChunks` returns
`PngTextChunk(keyword, "", true)` for a compressed chunk (`PngChunks.kt:286-292`, `:309-311`) and keeps
**no payload and no offset** — the bytes are already gone when the call returns. So an importer that "just
calls `inflateRaw` on the `zTXt`" cannot: there is nothing to call it with. Three shapes were considered.
(a) Widen `PngTextChunk` with a `compressedBytes: ByteArray?` field. Rejected: `PngTextChunk` is public, it
is pinned verbatim in JB-8.04's Contract section, and every reader would then have to remember that its
`text` is empty *while* a byte array sits in the next field. (b) Have `PngChunks` return the decoded `String`
— **chosen**, because the encoding is a property of the chunk *type*, which only `PngChunks` knows: PNG
says `zTXt` text is Latin-1 and `iTXt` text is UTF-8, and a caller decoding it needs the type as a second
return value, and a caller handed the wrong one mangles every non-ASCII brush name silently. (c) Return
`ByteArray` and decode in the importer — same problem as (b), with an extra step.
The function is **additive**: no existing declaration changes, no existing behaviour changes, and therefore
**no test in `PngChunksTest` changes.** That is the property that makes (b) cheap, and it is why
`PngTextChunk`'s contract — "empty text whenever `compressed` is true" — is preserved rather than
reinterpreted. The `KritaImport` KDoc at `:278-285` keeps describing the split correctly, because the split
still exists; only which side of it `KritaImport` sits on has changed.

**7. A `zTXt` is inflated on the flag *and* the compression-method byte — never on the flag alone.**
`compressedChunk` already reads one byte past the keyword's NUL to check the chunk is shaped like a `zTXt`
(`PngChunks.kt:288-290`) and then **discards it**. `readPngCompressedText` must read it, and **a method
byte other than 0 is a refusal naming the method**. "This chunk is compressed" is a claim by the file;
"this chunk is deflate" is a *different* claim, and a reader that assumes the first implies the second is
assuming about a stranger's bytes. The refusal is loud, so the cost of a wrong guess would be high and the
cost of the check is two lines.

**8. The un-flagged case: an `iTXt` with flag `0` is read as text and is NEVER inflated, and the existing
"does not begin with `<`" refusal is what a lying one gets.** A file that writes an uncompressed `iTXt` whose
text is a DEFLATE stream is a file whose text is a DEFLATE stream; the importer's job is to say so, and it
already has a sentence for it that names the character count. **Speculatively inflating it would be the
worst outcome available**: it converts a loud, correct, one-line refusal into a silent guess, and the
question that makes it tempting — "what if a real `.kpp` ever does that?" — is answered by R44 item 4, which
is that **no real file of any of these three formats has ever been read in this project.** A precaution
aimed at a file nobody has seen, which converts a refusal into a guess, is not a precaution.

**9. `convertBundle` gains nothing and loses nothing.** The per-entry `try`/`catch` that turns one bad
brush into a `RefusedBrush` (`KritaImport.kt:265-270`) already contains the change's whole safety story:
a bomb in entry 7 of 90 costs entry 7, not the pack. `MAX_ENTRIES`, `MAX_BRUSHES`, the name order, the
`unsafeReason` refusal, and Decision 15's "a bundle with no `brushes/` refuses in words" are untouched.
**The first test in this row is a bundle that works**, because the row's whole existence is a row whose
first test was "it refuses in words".

**10. The two zip readers are NOT merged.** `KritaZip` and `ProcreateImport.Zip` are near-identical and
that is a real duplication, but merging them is a **different, larger row** with its own risks (Procreate's
refuses an unsafe entry name at directory-read time, `KritaImport`'s does not; they have different file
caps and different string caps), and R44 item 2 did not ask for it. This row's *do not* names it so a
builder who reads R23 and gets enthusiastic does not do it at 6pm on a Friday. It goes in Questions.

**11. `Inflate.jvm.kt` is not touched, and `Inflate.kt`'s signature is not touched.** The `actual` is
`java.util.zip.Inflater` and it is right: the range check in `Long`, the `n == 0`-but-`finished()` case, the
cap checked **before** `out.write`, the `remaining != 0` tail check. The bomb story this row depends on —
"a bomb allocates at most one 64 KiB chunk past the cap" — lives in *that* file, and rewriting it would
mean hand-writing DEFLATE again, which R40 forbade. `Inflate.kt` is in the owner area **for its KDoc only**,
because the sentence at `:6-8` ("a `.brush` entry carries the zlib two-byte header; `ProcreateImport` reads
and checks that header") is a claim the landed code disproved, and a builder who reads it and believes it
will write a check that refuses every real `.brush`. If you are about to change anything else in that file,
that is the stop rule.

**12. CRC verification stays off, and that is a move, not a decision.** `KritaZip`'s landed KDoc already
declines to verify a CRC with a reason ("a wrong CRC in a pattern PNG is a pattern nobody will see, and an
importer that refuses a whole pack over one entry's CRC is a worse tool"). This row does not revisit it —
**and note the asymmetry it creates**: after this row a DEFLATE entry's bytes *are* in memory and its CRC
*is* in the central directory, so a check is now possible where it was not. That possibility is in
Questions, non-blocking, because the landed reasoning is about a `.kpp` entry nobody sees and still holds.

**13. Prose that contradicts the code is fixed in the same commit, and a test greps for it.** Five named
comments (E.4) currently tell the next reader the opposite of what the code will do. A stale KDoc is not
cosmetic here: JB-8.04's Decision 5 exists *because* a reader would otherwise have concluded no inflater was
reachable. Test 23 greps the two source files for the phrases that carry the old claim, so the sentence
cannot come back.

---

# Steps

Written in this order, because each step is a precondition for the next one compiling.

1. **Work in your own worktree** (R43, item 3 and item 6): `git worktree add --detach "$TEMP/jb-8.04b" HEAD`,
   copy `local.properties`, and **`cd` into the worktree before running Gradle**. `& "$TEMP\jb-8.04b\gradlew.bat" -p joybrush`
   runs the worktree's wrapper and leaves the working directory in the **main** folder, so `-p joybrush`
   resolves to the main copy and produces a plausible red that is not red. If a run reports errors in files
   you have not opened, suspect this before you suspect the code.
2. **Confirm the tree you are reading is the landed one.** `ProcreateImport.kt:73` and `:76` must hold
   `MAX_INFLATE_RATIO` and `MAX_INFLATED_BYTES`; `KritaImport.kt:1402-1408` must still hold the *"a real
   `.bundle` is mostly this message"* refusal. If either has moved, **stop** and write the question.
3. **Tests first**, in this order: `ImportSupportTest` (pure, 4 tests), then the `PngChunksTest` additions,
   then the `KritaImportTest` additions, then the new `jvmTest` file. Write them before the code; three of
   the `KritaImportTest` cases **replace** existing assertions and Test 20 is that replacement.
4. **`ImportSupport.kt`** — Decision 1, 2, 3, 4, 5: rewrite the header, add `MAX_INFLATE_RATIO`,
   `MAX_INFLATED_BYTES`, `inflateMaxOut`, `looksLikeZlib`, `ZLIB_HEADER_BYTES`, `ZLIB_ADLER_BYTES`.
5. **`ProcreateImport.kt`** — Decisions 1–4: delete the two constants and the three private helpers, repoint
   five call sites, use `inflateMaxOut`, **stop**. Nothing else in this file.
6. **`ProcreateImportTest.kt`** — Decision 3: four lines. Run `:core:jvmTest` and confirm it is green before
   touching anything else. If it is not, the move is wrong and you should find out here.
7. **`PngChunks.kt`** — Decisions 6, 7, 8: add `readPngCompressedText` and its KDoc, change nothing else.
8. **`KritaImport.kt`** — Decisions 1, 9, 10, 12, 13: `readStored` → `read` (E.1), three call sites,
   `readKppFile` (E.2), `texture()` (E.3), the five comments (E.4).
9. **`Inflate.kt`** — Decision 11: the KDoc paragraph only.
10. **Run the whole suite.** `./gradlew -p joybrush :core:jvmTest` from inside the worktree. Passing is
    `BUILD SUCCESSFUL` and **0 failures** in `joybrush/core/build/test-results/jvmTest/`, and the total
    count **not lower** than it was before you started — a suite that lost tests is a green build that
    tested less, which is R44 item 1's shape.

---

# Tests

**Fixtures.** The zip is still written by hand in `KritaImportTest` — `java.util.zip` may not appear in
`commonTest`, and a `ZipOutputStream` would be a second implementation of the thing under test. The
existing `bundleOf` already writes a method-8 entry as a **genuine stored DEFLATE block**
(`KritaImportTest.kt:1054-1063`: `BFINAL = 1`, `BTYPE = 00`, `LEN`, `~LEN`, the bytes), which is a real
RFC 1951 stream and is what the structural tests below use. **A stored block never exercises Huffman**, so
the `jvmTest` file builds real compressed fixtures with `java.util.zip.Deflater` and a real `.bundle` with
`java.util.zip.ZipOutputStream` — an independent writer, which is the only oracle worth having.

## `commonTest/.../ImportSupportTest.kt` (NEW) — no `java.*`

**1. `theInflateBudgetsAreTheOnesThatMoved`** — asserts `MAX_INFLATE_RATIO == 200L` and
`MAX_INFLATED_BYTES == 67_108_864`. This is the one test in the row that retypes a number on purpose: its
whole job is to catch a value that changed during the move, so its KDoc writes the derivation
(`64L * 1024 * 1024 = 67 108 864`) and names R9's rule — an expected value changes only with its
derivation written down. **`200` is derived, not remembered:** DEFLATE reaches ~1 038:1 on a run of zeros,
so 200:1 sits above any real brush bitmap and far below a bomb. **Non-vacuity:** the assertion is on the
value, not on the identifier, so a copy that kept the old declaration would pass this and fail
`ProcreateImportTest`'s compile — which is why Test 18 exists too.

**2. `aCompressedTextChunkCanNeverAskForMoreThanAnUncompressedOneMayBe`** — asserts
`MAX_PNG_STRING_BYTES < MAX_INFLATED_BYTES` (8 388 608 < 67 108 864) **and**
`inflateMaxOut(MAX_PNG_STRING_BYTES) == MAX_PNG_STRING_BYTES`. This is what keeps a later row from quietly
making the `zTXt` path looser than the uncompressed path: if someone raised the string cap past the
ceiling, or lowered the ceiling under it, this goes red.

**3. `theMaxOutFormulaNeverAsksForMoreThanTheCeilingAndNeverGoesNegative`** —
`inflateMaxOut(0) == 0`; `inflateMaxOut(MAX_INFLATED_BYTES) == MAX_INFLATED_BYTES`; and
`inflateMaxOut(Long.MAX_VALUE) == 67_108_864`. The last one is the arithmetic that a file controls: a
hostile central directory may state a 32-bit size, but a *computed* budget must not wrap, and `Long` is
what stops it. **`Long.MAX_VALUE` to `Int` would be `-1`**, and a `maxOut` of `-1` makes `Inflate.jvm.kt:28`
throw "maxOut -1 is negative" — a refusal that reads like a bug. Non-vacuity: it asserts the *clamped*
value, not that no exception was thrown.

**4. `aZlibHeaderIsRecognisedAndOtherThingsAreNot`** — `looksLikeZlib(byteArrayOf(0x78, 0x9C), 0)` is true
(`CMF & 0x0F == 8`, and `0x78 * 256 + 0x9C = 30 876 = 31 × 996`); `byteArrayOf(0x78, 0x9D)` is **false**
(30 877 leaves remainder 1 against 31); a one-byte array is false; a deflate stored block's first byte
`0x01` is false (`0x01 & 0x0F == 1`, not 8). Every expected value carries its arithmetic in the comment.

## `commonTest/.../PngChunksTest.kt` (EDIT — additive; nothing existing changes)

**5. `aCompressedTextChunkIsInflatedByASeparateCallAndTheOldContractIsUnchanged`** — a `zTXt` `preset`
whose payload is a stored DEFLATE block of a real XML string. **Two assertions, and the second is the
point:** `readPngTextChunks` still returns `compressed = true` with `text == ""` (the old contract, intact),
**and** `readPngCompressedText(bytes, "preset")` returns the XML exactly. A builder who quietly changed
`readPngTextChunks` to inflate would satisfy the first and fail the second, and the second is what keeps
JB-8.04's pinned contract honest.

**6. `aCompressedTextIsDecodedWithItsOwnChunksEncodingAndNotWithTheOthers`** — the test that catches the
trap Decision 6 exists to prevent. Build two chunks carrying the **same** bytes, whose Latin-1 reading is
`Café` (`0x43 0x61 0x66 0xE9`): one as a `zTXt` (Latin-1 by the PNG specification) and one as a compressed
`iTXt` (UTF-8 by the same document). `readPngCompressedText` on the `zTXt` returns the **one** character
`é` (U+00E9) and on the compressed `iTXt` returns **two** (`é`, U+00E9 then U+0301) or refuses the
unrepresentable sequence — and either way the two are **not** the same string. *How to satisfy both halves
without arguing about combining characters:* assert that the two results are **not equal**, and that the
`zTXt` result has the same `length` as the input byte count. Those two assertions are what fail if both
paths decode as UTF-8, which is the only mistake this test exists to catch.

**7. `aCompressedKeywordThatIsNotThereIsNullAndAPayloadThatIsNotDeflateRefuses`** — a file with no
compressed chunk of that keyword returns `null` (not an exception, not an empty string); a `zTXt` whose
payload is four junk bytes raises `BrushException` with a non-blank sentence; and a `zTXt` whose
compression-method byte is **1** raises rather than inflating anything. *A `null` and an empty string are
different answers and a caller cannot tell them apart, which is exactly the failure the landed
`PngTextChunk` KDoc was written to prevent.*

## `commonTest/.../KritaImportTest.kt` (EDIT — one test is **replaced**, and that is R44's authority)

**8. `aWholeBundleConvertsAndEveryPresetIsALegalBrush` — the FIRST test, and it is the one this row
exists for.** The existing STORED-only version at `KritaImportTest.kt:43` is **re-run with every entry
method 8** as well, by giving `bundleOf` a per-entry method that is already a parameter
(`ZipEntry(name, method, data)` at `:829`) and pointing it at the existing `ZIP_DEFLATED` constant. The
bundle is `brushes/Ink Default.kpp`, `brushes/Ink Splatter.kpp`, `paintoppresets/Flat Blitter.kpp` — the
same three, the same names, the same order assertion (`"Ink Default", "Ink Splatter", "Flat Blitter"`), the
same `"unknown"` licence, the same `brushId`, the same empty `BrushValidate.validate`. **Non-vacuity:**
assert the count is **3** and assert the three Krita names, exactly as today — "more than zero" would pass
against a reader that imported one and dropped two.

**9. `theSameBundleStoredAndDeflatedProduceTheSameLibrary`** — the twin test, and the strongest assertion
in the row. Build the bundle twice from the **same three `.kpp` byte arrays**, once with `ZIP_STORED` and
once with `ZIP_DEFLATED`; assert the two `library.brushes.map { it.preset }` lists are **equal** and the
two `refused` lists are both empty. *Same bytes in, same brushes out, only the zip method differs* — which
is the entire claim of the row, stated as an equality rather than as a green tick. This test cannot exist
before this row, because before this row it was false.

**10. `aBundleEntryThatIsADeflateBombIsRefusedByArithmeticAndTheRestOfThePackStillImports`** — a bundle
of three STORED `.kpp` plus one method-8 entry whose central directory declares `compressedSize = 1 000`
and `uncompressedSize = 100 000 000`. Arithmetic, in the comment: `100 000 000 / 1 000 = 100 000:1`, far
over `MAX_INFLATE_RATIO = 200`, and `1 000` is far under `MAX_ENTRY_BYTES = 33 554 432` so the entry-cap
check does not fire first. Assert: that entry is a `RefusedBrush` whose reason contains **"200"** and
**"limit this build will expand"**; the **other three brushes still convert** (assert the count is 3 and
the names); and the refusal happened **without allocating** — measure `System.nanoTime()` around the
`convertBundle` call and assert it is under a generous 5 000 ms, exactly as the landed
`InflateTest.theCapIsEnforcedInsideTheActual` does. *A bomb refused by arithmetic is the entire reason the
ratio exists; a test that only asserted the message would pass against a reader that allocated first.*

**11. `aBundleEntryOverTheInflatedCeilingIsRefusedByTheCeilingAndNotByTheRatio`** — a method-8 entry
declaring `compressedSize = 400 000`, `uncompressedSize = 70 000 000`. Arithmetic, in the comment:
`400 000 × 200 = 80 000 000`, and `70 000 000 < 80 000 000` so **the ratio check passes on purpose**;
`70 000 000 > 67 108 864` so the ceiling is what refuses. Assert the reason contains **`67108864`** and
**does not** contain `"limit this build will expand"`. *Choosing the numbers independently is the point: a
fixture that tripped both refusals could not tell them apart, which is the observation the landed
`ProcreateImportTest` already made and this test repeats on the other side of the swap.*

**12. `aBundleEntryWithAMethodThisBuildDoesNotReadIsRefusedByName`** — methods **12** (bzip2) and **99**
(a number no writer has ever used) each produce a `RefusedBrush` whose reason names the method **and**
contains `0 (stored)` and `8 (deflate)`. *The landed Krita message said "this build has no inflater" and
named no methods; the new one has to be actionable, because a bundle that used bzip2 is a real possibility
and "we will not guess at the rest" is the reason a person can act on.*

**13. `aStoredEntryIsStillCopiedAndAStoredEntryThatLiesAboutItsSizeStillRefuses`** — the STORED path is
untouched: an entry with `compressedSize != uncompressedSize` and method 0 still refuses, and a plain
STORED `.kpp` still converts. *Without this, "read both methods" could be implemented by deleting the
STORED branch.*

**14. `aZlibWrappedEntryStillReadsAndTheTrailerIsNotVerified`** — a method-8 entry whose payload is
`0x78 0x9C` + a stored DEFLATE block + **four arbitrary bytes**. It converts. The four bytes are
deliberately **not** a real Adler-32, and the comment says why: the wrapper is *tolerated*, the trailer is
*removed*, and neither is verified — the landed `ProcreateImport` decision this row copies. *A builder who
"improved" this by verifying the Adler would refuse this fixture, which is the correct outcome of a change
nobody asked for.*

**15. `aDeflatedPatternBecomesTheGrain`** — **this is the replacement for the second half of the existing
`aBundleStoredPatternBecomesTheGrainAndADeflatedOneFallsBackToItsName` (`:553-592`)**, which asserted
today's refusal and is now false. A bundle with a STORED `brushes/grain.kpp` naming a texture and a
**method-8** `patterns/grain.png` produces `paperGrain.source == "image"`, `paperGrain.image == "grain.png"`,
`paperGrain.enabled`, `extensions["krita.grainImage"]` round-tripping to the entry's bytes **exactly**
(decoded by the test's own independent base64, `:1101`), and a warning carrying `TEXTURE_NOT_DRAWN` by
value. The STORED half of the old test is **kept unchanged** — both branches must work.

**16. `aPatternThatCannotBeReadFallsBackToItsNameAndSaysWhy`** — the other replacement, and the fallback
**survives**: a method-8 `patterns/grain.png` that is a bomb (Test 10's numbers) leaves
`paperGrain.source == "cloud"`, **no** `GRAIN_IMAGE_KEY` in `extensions` (assert *absent*, not empty), the
name still in `extensions[TEXTURE_PATTERN_KEY]`, and a warning containing **"could not be read out of the
bundle"** and `TEXTURE_NOT_DRAWN`. *Decision 12's fallback was never about DEFLATE; it was about a pattern
this build cannot read, and a bomb is now one of those. Deleting the fallback because "DEFLATE works" would
turn a loud refusal into a silent procedural grain.*

**17. `aCompressedPresetChunkIsInflatedAndTheBrushConverts`** — **the replacement for the existing
`aCompressedPresetChunkIsRefusedByTheCallerInWords` (`:813-825`)**, which asserted `why.contains("does not
inflate")` and is now false by R44's reversal. A `zTXt` `preset` carrying a stored DEFLATE block of a real
`<paintop id="Pixel">…` now **converts**, with the same assertions as any other `Pixel` fixture
(`engine == "stamp"`, `BrushValidate.validate` empty). **This is the row's `zTXt` headline and it is a
changed expectation**, with the reason written in: R44 item 2 says `zTXt` "may be inflated the same way".

**18. `aCompressedPresetChunkThatIsNotDeflateIsRefusedInWordsAndACompressedOneOfProseIsRefusedAfterInflating`** —
two refusals, both required, neither silent. (a) A `zTXt` `preset` whose payload is four junk bytes →
`BrushException` with a non-blank sentence (this is `inflateRaw`'s, propagated). (b) A `zTXt` `preset`
carrying a stored DEFLATE block of **`this is not a preset`** → the existing *"does not begin with `<`"*
refusal, **plus the words "after inflating"** (Decision on E.2). *(b) is the test that a builder who
swapped the order — inflating and *then* skipping the XML check — would fail, and it is the only assertion
in the row that a plausible wrong implementation cannot pass.*

**19. `aDeflatedMetaXmlStillSuppliesTheLicenceAndTheAuthor`** — a bundle whose `meta.xml` is **method 8**
(built by `zip.read` on a DEFLATE entry, so this is Decision 14 on a compressed entry) and whose `.kpp` is
STORED: `license` and `author` are the ones the `meta.xml` states, never `"CC0"`. *`readBundleMeta`
already caught its own `BrushException` and fell back to unknown, so before this row a deflated `meta.xml`
produced `license = "unknown"` and no warning at all — the one place where a swallowed failure is invisible.
R8 makes CC-BY attribution an obligation, so this test is the one that proves it survives the swap.*

**20. `anEntryOverTheEntryByteCapRefusesAndNamesItsCap`** — a method-8 entry declaring
`compressedSize = MAX_ENTRY_BYTES + 1 = 33 554 433` refuses, and the reason contains `33554433`. The
existing suite has "one test at cap + 1" for the file, chunk, chunk-count, string, XML depth, XML nodes,
XML attrs, base64 and brush caps; this is the one that was missing, and it is now reachable through a
DEFLATE entry as well as a STORED one.

**21. `theOnlyIntegersAFileCanStateAreNotTheBudgets`** — a method-8 entry declaring a **negative**-looking
size through the `Long`-read path (`0xFFFFFFFF`, the Zip64 marker) refuses with the *Zip64* sentence, not
with a budget's, and a `compressedSize` of 2 000 000 000 (under `Int.MAX_VALUE`, over every cap) refuses
naming `MAX_ENTRY_BYTES`. *`KritaImport.le32` returns `Long` precisely so `0xFFFFFFFF` is 4 294 967 295 and
not `-1` (`KritaImport.kt:1537-1545`); a rewrite in `Int` would produce a negative size and a slice that
runs backwards. This test is the guard on that rewrite.*

## `jvmTest/.../KritaBundleInflateTest.kt` (NEW) — real DEFLATE, real zip, a second implementation

**22. `aBundleTheJdkItselfWroteConvertsWholeAndItsMetaXmlStillSuppliesTheLicence`** — the test that closes
this row. Build a `.bundle` with **`java.util.zip.ZipOutputStream`** — an implementation this file did not
write, compressing three real `.kpp` files and a `meta.xml` with `setLevel(Deflater.BEST_COMPRESSION)`, so
every entry is a **real Huffman-coded** method-8 entry. `convertBundle` returns all three brushes with the
right names, in name order, all `BrushValidate.validate` clean, and the licence from the compressed
`meta.xml`. *Two independent implementations — the JDK's writer and the JDK's reader, one level up through
`inflateRaw` — agreeing on a file the JDK wrote is the strongest evidence available without a real
`.bundle`, and it is the fixture the hand-written `storedDeflateBlock` can never provide.*

**23. `aRealHuffmanDeflatedPatternBecomesTheGrainByteForByte`** — a `patterns/<name>.png` compressed by
`java.util.zip.Deflater` and read back through `zip.read`; the bytes in `extensions["krita.grainImage"]`
are the entry's bytes exactly. *The stored-block fixtures prove the plumbing; only a Huffman stream proves
the stream.*

**24. `aRealCompressedPresetTextChunkInflatesAndALatin1NameSurvivesIt`** — a `zTXt` `preset` built with
`java.util.zip.Deflater(6, /* nowrap = */ false)`, i.e. a **real zlib stream**, which is what the PNG
specification says a `zTXt` holds and what the wrapper tolerance exists for. The XML inside carries a
`Title` text chunk with an accented name. Assert the brush imports and the name is byte-exact. *This is the
fixture that would catch a `zTXt` path that forgot the wrapper tolerance entirely, which is a different bug
from the zip one and a builder will not think of it from the zip case.*

**25. `aFalseZlibHeaderOnRealDeflateDataIsARefusalAndNotAShortRead`** — search a genuine
`Deflater(6, true)` stream for a first two bytes that `looksLikeZlib` accepts (it exists at about 1 in
496; the search is a 256-iteration loop, not a fixture hunt). Then feed that stream as a method-8 entry and
assert the result is **either** the correct bytes **or** a `BrushException` with a sentence — and explicitly
**not** a short array. *The landed code's own note says a false positive "costs four bytes of the stream,
so the inflater then reports 'ends early' rather than returning something short". This test is what makes
that sentence true rather than plausible.*

**26. `noSourceFileStillSaysThisBuildHasNoInflater`** — read `KritaImport.kt` and `PngChunks.kt` from disk
(locate the module root the way `RealFilesProbeTest` locates its corpus, by walking up from
`File("").absoluteFile` for the first directory that contains `src/commonMain`; that is the same
`generateSequence(…).firstOrNull { … }` idiom pointed at the module root rather than at
`testdata-local`) and assert **none** of them
contains the strings `"no inflater"`, `"does not inflate a PNG text chunk"` or `"readStored"`. *Three
needles, each chosen for a reason: the first two are the prose claim this row reverses, and the third is
the old function name, so a call site left behind by a partial rename is caught by a test rather than by a
reviewer noticing a compile error it has not built yet. This is the technique D.02a used
(`noCallSiteInTheAppChanged`).*

---

**Command**

```powershell
cd $TEMP\jb-8.04b          # R43 item 6: cd FIRST. `-p joybrush` from the main folder resolves to the main copy.
.\gradlew.bat -p joybrush :core:jvmTest
```

Passing = `BUILD SUCCESSFUL` and **0 failures** in `joybrush/core/build/test-results/jvmTest/`, **and the
total test count not lower than it was before you started**. Report both numbers.

- **Never `--rerun-tasks`** (R44 item 1). `brushes/`, `assets/`, `shaders/` and the real-file corpus are
  declared `jvmTest` inputs (`core/build.gradle.kts:43-51`), so a re-run happens by itself when its inputs
  change. `--rerun-tasks` is not a workaround; it is how you lose the ability to tell what was tested.
- If a run reports errors in files you have not opened, **suspect the working directory before the code**
  (R43 item 6). This has happened twice on this project.

---

# Do not

- **Do not choose a bomb ratio, or any other number in this row.** Every value here is moved or copied from
  a landed declaration, and the spec says which. If a number is not in the contract above, it is a
  **question**, and a builder who invents one has quietly become the author of a security bound.
- **Do not put `readPngCompressedText` in a new file.** It reuses `walkPngChunks`, `keywordOf`,
  `latin1`, `indexOfZero` and `decodeUtf8Strict`, and the first four are **`private` in file**
  (`PngChunks.kt:166`, `:240`, `:254`, `:329`). In this package that is a wall of
  *"cannot access … it is private in file"*, not a one-line fix, and the answer is never to make them
  `internal` — the cap-ownership comment at `PngChunks.kt:5-17` explains why this file's helpers are
  file-private. **Add the function to `PngChunks.kt`.** The owner area already says EDIT, not NEW.
- **Do not compare the keyword after reading the shape.** A `zTXt` carrying some *other* keyword with a
  bad compression method must not raise against a caller that asked about a different one. The pasted
  body compares the keyword first, and that is the order.
- **Do not write a second `minOf` in a second file.** `inflateMaxOut` is the formula (Decision 2, A.3). A
  `minOf(declared, MAX_INFLATED_BYTES)` typed into `KritaImport.kt` is the same drift as a second constant.
- **Do not pass an `Int` to `inflateMaxOut`.** It takes a `Long` and Kotlin does not widen. Both zip
  readers hold `uncompressedSize` as an `Int` (`ProcreateImport.kt:398`, `KritaImport.kt:1383`), so
  **both** call sites need the explicit `.toLong()`. This is the one line in the row a build stops on,
  and it is easy to write correctly in the first file and wrongly in the second.
- **Do not add a `maxOut` parameter to `readPngCompressedText`.** The cap is `PngChunks`' own
  `MAX_PNG_STRING_BYTES` because the caller does not get a vote in it; a parameter invites a caller to pass
  `MAX_INFLATED_BYTES` and quietly raise this path's ceiling from 8 MiB to 64 MiB.
- **Do not widen `PngTextChunk`.** Its three fields and its "empty text whenever `compressed` is true"
  contract are pinned in JB-8.04's Contract section, are read by `KritaImport`, and are what five
  existing `PngChunksTest` assertions depend on. The new function is *alongside* it (Decision 6).
- **Do not change `readPngTextChunks`, `readPngHeader`, `decodeUtf8Strict`, `flatChunk`,
  `internationalChunk` or `compressedChunk` beyond the wording in E.4.** They work, they are tested, and
  every one of them is a decision another row reviewed.
- **Do not decode a `zTXt` as UTF-8, or an `iTXt` as Latin-1.** That is Test 6, and the failure is silent:
  every ASCII brush name passes either way.
- **Do not inflate an `iTXt` whose compression flag is `0`.** Decision 8. The existing
  *"does not begin with `<`"* refusal is the right answer for a lying one, and converting a refusal into a
  guess is the one outcome this project keeps forbidding.
- **Do not believe a declared uncompressed size.** `maxOut` is `inflateMaxOut(declared)`; the declared
  size is a *hint* that is used to lower the ceiling, never to raise it, and a stream that expands past
  `maxOut` is **refused, never truncated** (that is `Inflate.jvm.kt:51` and it is not this row's to change).
- **Do not write `entry.compressedSize * MAX_INFLATE_RATIO` in `Int`.** `compressedSize` is an `Int` and
  `MAX_INFLATE_RATIO` is a `Long`; the product must stay `Long` or a large entry overflows to a negative
  and the ratio check silently passes everything. The contract's line is correct — copy it.
- **Do not re-order the five refusals in `KritaZip.read`.** Entry name, entry bytes, ratio, ceiling,
  method. Test 11 depends on the ratio being checked before the ceiling, and Test 12 on the method being
  refused by name rather than by a slice that runs off the end.
- **Do not verify a CRC.** Decision 12 — the landed decision, the landed reason, and a change nobody asked
  for. If you think it should be verified, that is a question, not an edit.
- **Do not merge `KritaZip` into `ProcreateImport.Zip` or the other way round.** Decision 10. They are two
  readers with two file caps and two different name rules; merging them is its own row and it is in
  Questions.
- **Do not delete the `grainUnreadable` fallback in `texture()`.** Test 16. A DEFLATE pattern now reads; a
  *bomb* pattern does not, and the fallback is what makes that difference loud instead of silent.
- **Do not touch `Inflate.jvm.kt`, the `expect` signature, `core/build.gradle.kts`, anything in
  `androidkit`, or any app file.** Decision 11. If you believe the `actual` is wrong, that is a stop.
- **Do not use `java.util.zip` in a `commonTest` file**, and do not build a `.bundle` fixture with
  `ZipOutputStream` outside the `jvmTest` file. The hand-written zip is the point: a fixture written by the
  same ecosystem that reads it can only prove they agree with each other.
- **Do not delete a failing test to make the suite green.** Three expectations in this row **legitimately
  change** and are named above (Tests 15, 16, 17); nothing else may.
- **Do not run this row beside JB-8.01, JB-8.02 or JB-8.04.** They share `ImportSupport.kt` and
  `KritaImport.kt`, and two rows in one file is how one of them loses an edit.

---

# Stop rule

If anything here is ambiguous, or a claim about the `.kpp`/`.bundle`/`zTXt` formats turns out to be false
when you build the fixture, **STOP**: write the question in *Questions* under a heading `for the Lead`, set
this row `⛔ Blocked`, commit, push, and take another task. Never guess.

Four things are never a builder's call in this file, and each of them is a **security bound or a contract**:

1. **The DEFLATE ratio** (`MAX_INFLATE_RATIO`) — a security bound. It is *moved*, and the landed
   declaration is the authority. If you believe 200:1 is wrong, that is a question, and the correct
   action is to leave the number exactly where the landed code put it.
2. **The output ceiling** (`MAX_INFLATED_BYTES`) — same, same answer.
3. **The meaning of `inflateRaw`** — its signature, its `actual`, and its four checks. If a `.bundle` entry
   that should inflate does not, the **stop** is to report the bytes, not to change `Inflate.jvm.kt`.
4. **Whether the two zip readers become one.** Decision 10 says not in this row.

And one that is the same trap this row was created by: **if `PngChunks` turns out not to give you what this
spec says it gives you, do not work around it by widening `PngTextChunk`.** Report it. The whole point of
R44 item 2 is that a builder who stopped and referred was right, and a builder who patches around the
thing they were unsure of is how the next `.bundle` importer gets refused in words again.

---

# Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` (run from **inside** the worktree) — `BUILD SUCCESSFUL`,
      **0 failures**, and the **total count not lower** than before. Both numbers pasted into the report.
- [ ] Tests 1–26 all present and named as above, in the four files listed in the owner area.
- [ ] `git status --short` pasted, and **only** owner-area files are in it.
- [ ] The five E.4 comments rewritten, and Test 26 green (which is what proves it).
- [ ] `ProcreateImportTest.kt` changed by **four lines** and nothing else.
- [ ] `Inflate.kt` changed by **KDoc only** — diff it and confirm the `expect` line is byte-identical.
- [ ] `PngChunks.kt`: the diff is **additive**. `git diff --stat` shows one new function and the E.4
      wording; every existing declaration is untouched.
- [ ] The `MAX_INFLATE_RATIO`/`MAX_INFLATED_BYTES` values are `200L` / `64L * 1024 * 1024`, unchanged, and
      `grep -rn "200L\|64L \* 1024 \* 1024" joybrush/core/src` shows **one** declaration of each.
- [ ] Committed as `JB-8.04b: Krita bundle inflate`; pushed.
- [ ] ROADMAP row → `🟧 Built` *(there is no `INDEX.md`; it is a stub pointing at `ROADMAP.md`)*.
- [ ] **The row is a real-file check away from 🟩, and that is owed, not claimed.** R44 item 4: no real
      `.bundle` has ever been read. `RealFilesProbeTest` already exists and will exercise this code the
      moment the owner drops a real bundle into `joybrush/testdata-local/`; this row does not sign that
      off, and saying it does would be the one claim in this spec that no test backs.

---

# Questions

## PROVISIONAL — Claude to confirm

Both are wording, both are reversible in one edit, and neither is a contract, a file format or a number.

0. **RULED BY THE CROSS-REVIEWER, and it closes the factual half of Q1: the `zTXt` really does state
   no uncompressed size, so `MAX_INFLATE_RATIO` genuinely has nothing to compare there — and that is a
   fact about the format, not a gap in this row.** Checked against the landed reader rather than against
   the PNG specification, because the specification is not in this tree: `Zip.read` applies the ratio as
   `entry.uncompressedSize > entry.compressedSize * MAX_INFLATE_RATIO` (`ProcreateImport.kt:437-439`),
   where both numbers come out of the zip's **central directory**, and a central directory states
   `uncompressedSize` as its own field. A PNG chunk header states one length and it is the length of
   the **compressed** payload — `walkPngChunks` hands its visitor exactly that (`PngChunks.kt:210`:
   `visit(type, dataAt, dataLength)`, and `dataLength` is the chunk's declared length), and
   `compressedChunk` reads keyword, one method byte and returns, keeping no expansion figure at all
   (`PngChunks.kt:286-292`). **So there is no second number anywhere in this codebase for the ratio to
   divide by, and the claim holds.** What is left binding is `MAX_PNG_STRING_BYTES = 8 MiB`, capped
   inside the `actual` at `Inflate.jvm.kt:51` **before** `out.write` at `:52` — so the worst a `zTXt`
   bomb costs is 8 MiB plus one 64 KiB chunk, which is the same worst case the zip path has when the
   ratio check is the one that fires. **On "is a row that half-works a problem": no, and the reason
   is that this row's load-bearing half is complete.** The zip-bomb ratio now applies to `KritaZip`,
   which is what makes real `.bundle` files open at all; the compressed-chunk path is bounded, loudly,
   and the asymmetry is a difference between two file formats rather than an omission. Test 2 pins
   that the string cap is the tighter of the two so a later row cannot quietly widen it, and the
   constant's own KDoc (A.2) states the asymmetry where the next reader will meet it.

1. **The words of the "one inflater" KDoc** (E.4, `KritaImport.kt:64-73`). The paragraph has to change
   because it asserts the opposite of what the code does; the wording is this spec's. *What is not
   provisional: the sentence "a real `.bundle` is mostly this message" must be **deleted**, not softened,
   because a softened version is a claim nobody has checked.*
2. **The words of `readPngCompressedText`'s KDoc** (A/D). It must name the ratio asymmetry for `zTXt`
   (no declared size ⇒ the ceiling is the only bound) and the encoding asymmetry (`zTXt` Latin-1, `iTXt`
   UTF-8). Both facts are from the PNG specification, both are load-bearing for Decisions 6 and 7, and
   neither is verifiable inside this repository — the same class of fact as JB-8.04's R4 citations.
3. **The five refusal sentences inside `readPngCompressedText`'s body** (D): *"compressed with method
   $method, and this build reads only 0 (deflate)"*, *"is truncated before its compression method"*, the
   two `iTXt` unterminated-field sentences, and *"is compressed but carries no text"*. They are
   **wording**, and the spec supplies them so no builder invents one; three of the five are reuses of
   sentences `PngChunks` already ships (`PngChunks.kt:289`, `:307`, `:314`, `:317`). Change any of them
   freely — Tests 7 and 18 only assert that a sentence is raised and names the method, never the
   wording.

## for the Lead

**Q1. Is a `zTXt` bomb bounded only by the output ceiling, and is that the answer you want?** **The
factual half is RULED (see PROVISIONAL 0): yes, a `zTXt` states no uncompressed size, and that is
checked against the landed reader.** What remains is the design choice the ruling deliberately does not
make. The alternative to the ceiling-only bound is to read the DEFLATE stream's own trailing `ISIZE`
(RFC 1951) and apply the ratio as a *pre-check*: a hostile file's claim about itself, used only to
**refuse** rather than to accept, so a false claim can cause a false refusal and never a false
acceptance. **My recommendation: do not add it to this row.** It is a security-bound design decision,
it is not needed for 8 MiB, and it is the kind of thing that should be ruled once rather than
discovered. **Not blocking** — the default is safe, the spec says so in the constant's own KDoc, and
this row does not stop the builder on it.

**Q2. The two zip readers are now near-identical, and R23 says "share, don't copy".** `KritaZip` and
`ProcreateImport.Zip` are two hand-written central-directory walks with two sets of file caps and **two
different entry-name rules** (`ProcreateImport` refuses an unsafe name at directory-read time;
`KritaImport` refuses it per entry in `convertBundle`, which is why one bad *name* costs one brush rather
than the pack). Merging them means deciding which name rule wins, and that is a real decision about a real
failure mode, not a refactor. **Recommendation: a separate small row, "one shared zip reader", needs
nothing, and it should be written by whoever has budget rather than smuggled in here.** Decision 10 keeps
this row out of it. Not blocking.

**Q3. A CRC is now *checkable* where it was not, and this row keeps the refusal to check.** A DEFLATE
entry's bytes are in memory and its CRC is in the central directory, so verification is possible for the
first time. The landed reason for not checking — "an importer that refuses a whole pack over one entry's
CRC is a worse tool" — was written when a DEFLATE entry could not be read at all, and it still reads well.
But it is a policy about *corrupt third-party files*, not a security bound, and it is worth a ruling rather
than an inheritance. **My recommendation: keep it off** (Decision 12, and Test 14's dummy Adler-32 makes the
choice visible). Not blocking.

**Q4. `KritaImport`'s `MAX_ENTRY_BYTES` is 32 MiB and `ProcreateImport`'s is 64 MiB, and the shared
`MAX_INFLATED_BYTES` is 64 MiB.** So a Krita entry is capped at 32 MiB in the file and may expand to
64 MiB. That is deliberate, not drift — a `.kpp` **is** its entry's whole content, so bounding the entry
and bounding the file are one bound (that is why `KritaImport.MAX_FILE_BYTES = MAX_PNG_BYTES`), whereas a
`.brush` entry is one file of a pack. **Flagging it only so nobody "aligns" the two later** as though it
were the same quantity. A question only because R44 item 5 is exactly this kind of finding and this row is
the one that makes the two numbers visible side by side.

---

**Checked against the landed source on 2026-09-29.** Every claim above with a `file:line` was read in the
working tree, not recalled:

- `inflateRaw` is `internal expect fun inflateRaw(deflate: ByteArray, offset: Int, length: Int, maxOut: Int): ByteArray`
  — **`Inflate.kt:22`, verbatim** — and the JVM `actual` is `Inflate.jvm.kt:24`, with the cap checked
  *before* `out.write` at `:51` and the trailing-bytes check at `:54-56`. **Confirmed: the signature is
  `maxOut` as a fourth `Int` parameter, and the budget arguments are *not* in it** — the ratio and the
  output ceiling are in the caller, exactly as the row's premise says. They are `ProcreateImport.kt:73`
  (`MAX_INFLATE_RATIO = 200L`) and `:76` (`MAX_INFLATED_BYTES = 64L * 1024 * 1024`), both `const val` on
  the public `object ProcreateImport`, and grep over all of `joybrush/` finds them in **only** that file
  and `ProcreateImportTest.kt` (`:676, :677, :679, :683`).
- `looksLikeZlib` is `ProcreateImport.kt:690-695` and the two byte counts are `:698` and `:701`, all
  `private`. The zlib tolerance is applied at `:510-512` with the correction comment at `:487-509`, which
  says in its own words that the JB-8.02 spec's claim about the zlib header was **false** and that a real
  `.brush` is raw DEFLATE. This spec copies that, and does not re-derive it.
- `readPngTextChunks` returns `PngTextChunk(keyword, "", true)` for a `zTXt` at `PngChunks.kt:286-292` and
  for a compressed `iTXt` at `:309-311` — **no payload and no offset is retained**, which is why the
  `zTXt` inflate cannot live in `KritaImport` (Decision 6). `PngTextChunk` is a public three-field data
  class at `:60`, and `readPngHeader`/`decodeUtf8Strict` are `internal` at `:117`/`:349`.
- `MAX_PNG_STRING_BYTES = 8 * 1024 * 1024` is `PngChunks.kt:50`, with a KDoc that says it is JB-8.04's
  Decision 7 and that only JB-8.04 is affected. 8 388 608 < 67 108 864, so Test 2 is true as written.
- `KritaZip.readStored` is `KritaImport.kt:1388-1435` and the "a real `.bundle` is mostly this message"
  refusal is `:1402-1408`, verbatim. Its three callers are `convertBundle` (`:266`), `readBundleMeta`
  (`:343`) and `texture()` (`:890`), and the `KritaZip.STORED` test is at `:888`. All four are named.
- `texture()`'s DEFLATE-fallback branch is `KritaImport.kt:900-906`; `readKppFile`'s compressed refusal is
  `:308-315` and the non-XML check that follows is `:317-322`. All verbatim above.
- `ImportSupport.kt` is 71 lines: `ImportLibrary`/`summary()` (`:11-30`), `RefusedBrush` (`:32`),
  `MAX_EXTENSION_BYTES` (`:42`), `TEXTURE_NOT_DRAWN` (`:51`), `brushId` (`:63-71`). The header comment at
  `:1-8` is verbatim above and does say *"JB-8.02 and JB-8.04 **read** it and do not edit it"*.
- `KritaImportTest` is 1 126 lines in `commonTest` with **no `java.*`**, and its own header says so
  (`:29`). `bundleOf` (`:988-1052`) already takes a per-entry `method` and already writes a genuine stored
  DEFLATE block for method 8 (`:1054-1063`), so Test 8 needs no new fixture machinery. The two tests this
  row replaces are `aBundleStoredPatternBecomesTheGrainAndADeflatedOneFallsBackToItsName` (`:553`) and
  `aCompressedPresetChunkIsRefusedByTheCallerInWords` (`:813`), and `aBundleStoredPatternBecomesTheGrain…`
  is the **only** place in the file that uses `ZIP_DEFLATED` (`:581`).
- `PngChunksTest` is 296 lines in `commonTest` and **no test in it needs to change** —
  `aCompressedChunkIsReturnedAsCompressedWithNoTextAndTheReaderKeepsGoing` (`:107`) and
  `anUncompressedInternationalChunkIsReadAndACompressedFlagIsNot` (`:133`) both assert the *unchanged*
  `PngTextChunk` contract, which is the evidence for Decision 6's "additive".
- `InflateTest` (206 lines, `jvmTest`) already oracles `inflateRaw` against `java.util.zip.Deflater` and
  already asserts the bomb cap **with a 5 000 ms time bound** (`:140`) — Test 10 copies that idiom, so
  the time bound is this repo's, not this spec's invention.
- `ProcreateImportTest`'s two-refusal fixture is at `:673-684` and its comment already says the point:
  "Choosing the two numbers independently is the point: a fixture that only ever produced a high-ratio bomb
  could not tell the two refusals apart." Test 11 is the same observation on the other side of the swap.
- `core/build.gradle.kts:43-51` declares `brushes/`, `assets/`, `shaders/` and the real-file corpus as
  `jvmTest` inputs (R44 item 1), and `:14` enables `jvm()` as the only target — so `expect`/`actual` needs
  no build-file change and this row edits none.
- `RealFilesProbeTest.kt` (46 lines, `jvmTest`) already calls `KritaImport.convertBundle` and
  `convertKpp` over a git-ignored corpus, so **this row's code is already wired into the probe** and
  JB-8.05 owes the real-file verdict, not this row.

**Not verified, and said so rather than assumed:** that a real `.bundle` from Krita uses method 8 for its
`.kpp` entries (JB-8.04's builder's claim, and the basis of R44 item 2 — I found no `.bundle` in the tree
to check, and no real file of any of these three formats has ever been read, per R44 item 4). That Krita
writes a `zTXt` at all in practice, which is why the compressed path "never fires on a real file" — the
spec implements it anyway, and the reason it does is Decision 7/8's second point rather than a claim about
Krita. That PNG specifies `zTXt` text as Latin-1 and `iTXt` text as UTF-8, which is the load-bearing fact
under Decisions 6 and 7 and is from the PNG specification, not from a file in this repository. And that
`zTXt` payloads are zlib-wrapped rather than raw DEFLATE, which Test 24 exercises with a real
`Deflater(6, nowrap = false)` stream precisely so the claim is checked rather than trusted.
