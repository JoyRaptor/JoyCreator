package cc.joycreator.joybrush.core.brush.imports

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.TipSpec

/**
 * Krita `.kpp` and `.bundle` → Joy Brush presets. JB-8.04. **Pixel and colour smudge engines only.**
 *
 * A `.kpp` is a **PNG** whose text chunks carry the settings: `version` and `preset`, the second being the
 * paint-op settings as XML, and — 61 of the 64 real ones — an `author` in a `zTXt`. A `.bundle` is an
 * ODF-style zip — `mimetype`, `meta.xml`, `META-INF/manifest.xml`, `preview.png`, `brushes/`, `patterns/`,
 * `paintoppresets/` — and **`brushes/` is the tip images, not more `.kpp`**: all 32 of the real pack's named
 * tips resolve to an entry there.
 *
 * **The row exists to get one thing right: what happens to input this reader cannot express.** Krita's own
 * engine list is the cleanest version of that question in Phase 8, because Krita *tells you* what the brush
 * is and most of those things have no analogue in a stamp engine. So every engine is one of three buckets,
 * and the row's headline assertion is that `Hairy`, `Sketch`, `Deform`, `Particle`, `Curve`, `Hatching`,
 * `Grid`, `Shape`, `Filter`, `TangentNormal`, `Quick` and `Spray` are **refused with their own `paintopid`
 * in the sentence** — a refusal you can see beats an approximation you cannot. A missing `paintopid` is
 * refused too and is *never* defaulted to `Pixel` (Decision 10), which would import a bristle brush as a
 * round one.
 *
 * **`ColorSmudge` is legal and is not "unsupported"** (R40). R4 §E.3 maps it to `pickup`, JB-1.06 is
 * `⚪ Outline`, and `"smudge"` is already a word `BrushValidate.ENGINES` accepts — so it imports as
 * `engine = "smudge"` and carries [SMUDGE_NOT_BUILT]. That is LOSSY, not REFUSED: the brush is real, the
 * engine is late.
 *
 * **And R40, uniform across JB-8.01, JB-8.02 and this row: an importer stores image tips and image grain,
 * sets `source = "image"`, and says so out loud.** Nothing in the engine samples an image tip or an image
 * grain today — there is no image reader anywhere in `joybrush/` and `jb_dab.frag` samples no texture —
 * while `BrushValidate` rule 23 checks only that the path is **non-blank**, so a preset can validate clean,
 * name a file nobody has written, and draw as procedural. **[TEXTURE_NOT_DRAWN] by value, never a retyped
 * literal, is the only thing standing between a person and that**, once per brush that carried the bitmap.
 * The condition on removing it is JB-1.05d.
 *
 * ### The real format, measured, and what the spec got wrong about it
 *
 * Everything below about Krita's own grammar is **measured out of a real file** — David Revoy's Brushkit V8.2,
 * `joybrush/testdata-local/krita/deevad-v8-2.bundle`, 3 553 039 bytes, 96 zip entries, 64 `.kpp`, all under
 * `paintoppresets/` and **all 95 other entries DEFLATE** so that the shared inflater is not optional. It is
 * git-ignored and nothing from it is committed (R8); the *structure* is what is taken here, which is what
 * the JB-8.01b `.abr` row did with ag-psd's MIT reference source.
 *
 * | what | the spec said | the real bytes |
 * |---|---|---|
 * | the settings root | `<paintop id="…">` | **`<Preset name="…" paintopid="…">`**, 64 of 64 (Decision 10 is wrong) |
 * | the engine ids | `Pixel`, `ColorSmudge` | `paintbrush` ×54, `colorsmudge` ×7, `deformbrush`, `experimentbrush`, `duplicate` |
 * | a setting | an element with attributes | **`<param type="string\|internal\|bytearray" name="KEY">` and the value is its text** |
 * | a sensor | `<curve sensor= name= commonCurve=>` | **`<param name="SizeSensor">` whose text is `<params id="pressure"><curve>0,0;1,1;</curve></params>`** |
 * | spacing | `<spacing mode="space" value=…/>` | **`spacing="…"` on the `<Brush>` element inside `brush_definition`** |
 * | the tip | `<brush_definition type="auto_mask"><cell value="rx;ry"/>` | **`<Brush type="auto_brush\|gbr_brush\|png_brush">` with `spacing`/`scale`/`filename` attributes and a `<MaskGenerator ratio= diameter=/>` child** |
 * | a texture | `<texture pattern= blendmode= depth=/>` | **`Texture/Pattern/…` parameters, and the pattern's own bytes base64'd TWICE over** |
 * | the licence | `dc:rights` in `meta.xml` | **`<meta:meta-userdefined meta:name="license" meta:value="…"/>`** — no `dc:rights` at all |
 * | the author | nothing in a `.kpp` | **a `zTXt` `author` chunk, in two spellings** |
 *
 * **`sensor=` ×0, `<spacing` ×0, `commonCurve` ×0, `PixelSize` ×0, `<texture` ×0, `<cell` ×0 across all 64.**
 * Every one of the spec's fixture shapes is a shape Krita does not write, and the landed fixtures were built
 * from the spec — so the ~40 mapping tests agreed with the reader and with the spec and with nothing real.
 * That is the same closed loop that killed the `.abr` reader, and both shapes are now read and both are
 * tested: this file takes the **real** grammar as primary and keeps the flat/older grammar as a second
 * reader, because a reader that refuses a stranger's older file is not better than one that reads it.
 *
 * ### Where the bytes are carried, and why that is not a widening of anybody's type
 *
 * Decision 4 as first written told this importer to return the tip's bytes in its result. **`ImportResult`
 * is `data class ImportResult(val preset: BrushPreset, val warnings: List<String>)`** (`MypaintImport.kt:29`)
 * and carries no bytes, and there is no place on [ImportLibrary] to put them — so that was unimplementable
 * as written. It is implemented **by the mechanism, not by widening a type**: the bytes are base64'd into
 * `BrushPreset.extensions`, which `BrushPreset.kt:82` already documents as *"lossless storage of imported
 * raw settings"* and which two other specs read. `tip.image` is then the **file name** those bytes will be
 * written under when JB-1.05d lands, which is why it is `tip.png` and never the `<brush_definition>`
 * attribute's own `name` — that is a Krita resource path, and a brush folder is `<folder>/brush.json`.
 *
 * ### What JB-8.02 already gave this file, and what it did not
 *
 * `PngChunks.kt` (JB-8.02) is **this reader's PNG half, already landed**: the signature, the chunk walk
 * with its `Long` length arithmetic, `tEXt`/`zTXt`/`iTXt`, [readPngHeader] and the shared strict UTF-8
 * decoder. This file therefore declares **no** PNG signature and **no** text-chunk reader, and it *reads*
 * `MAX_PNG_BYTES` / `MAX_PNG_CHUNKS` / `MAX_PNG_CHUNK_BYTES` / `MAX_PNG_STRING_BYTES` and names them as
 * this row's `MAX_FILE_BYTES` / `MAX_CHUNKS` / `MAX_CHUNK_BYTES` / `MAX_STRING_BYTES`, because a second copy
 * of a cap is the drift R23 forbids. The one change made to that file is a single constant, re-pointed from
 * 64 KiB to 8 MiB, and the reason is in that constant's own comment: at 64 KiB this reader would refuse
 * every `.kpp` carrying an embedded base64 tip, which is exactly the case R40 is about.
 *
 * ### One inflater, and that is the point
 *
 * **JB-8.04 refused a DEFLATE bundle entry in words; JB-8.04b reversed that ruling (R44 item 2) and this
 * row's zip reader now inflates.** LEAD_RULINGS R44 item 2 overturned JB-8.04's Decision 5 by name: the
 * two numbers that make inflating safe live in the *caller*, so a second importer had only two choices —
 * share the numbers or write its own — and it now shares them.
 *
 * So there is **one** DEFLATE implementation (`inflateRaw`, JB-8.02's `internal expect` in this same
 * module) and **one** pair of bomb caps, and both live in `ImportSupport.kt` rather than in either
 * importer: `MAX_INFLATE_RATIO`, `MAX_INFLATED_BYTES`, the one `maxOut` formula (`inflateMaxOut`) and the
 * one zlib-wrapper probe (`looksLikeZlib`). R23 is the reason: a number written twice is one writer
 * believing it twice, and re-deriving a security bound in a second file is that plus worse. A compressed
 * PNG text chunk goes through the same probe and the same formula.
 *
 * **The three refusals a bomb gets are copied character for character from `ProcreateImport.Zip.read`,
 * and the order is that reader's**: the entry's own bytes, then the declared expansion against the
 * ratio — **a bomb is refused by arithmetic, before a byte is inflated** — then the declared expansion
 * against the ceiling. What a file declares is never believed; `maxOut` is the declared size *capped* at
 * the ceiling, and a stream that expands past it is refused rather than truncated.
 *
 * **Every class here is prefixed `Krita`, and that is not a style choice.** A *class*'s simple name resolves
 * by name across a package rather than by signature: `AbrImport.kt` has a `private class BrushImport` in
 * this same package, and a same-named class here would be invisible and every use of it would resolve to
 * the other file's. `ProcreateImport.kt:713` records the bug this already paid for once.
 */
object KritaImport {

    // ---- budgets (Decision 7). Every one is a cap *and* a refusal, and every message names it --------

    /**
     * The whole file. **Read from `PngChunks.kt`, not re-declared**: a `.kpp` *is* a PNG, so the cap that
     * bounds the PNG bounds the file, and a second 64 MiB in this file is a number two files would have to
     * agree about. 64 MiB, the same number as `MAX_ENTRY_BYTES` and as `AbrImport`'s, for the same reason.
     */
    internal const val MAX_FILE_BYTES = MAX_PNG_BYTES

    /** Chunks in one `.kpp`. `PngChunks`'s, read for the reason above. */
    internal const val MAX_CHUNKS = MAX_PNG_CHUNKS

    /** One chunk's declared bytes. `PngChunks`'s. On a `.kpp` this is the `preset` chunk's own cap. */
    internal const val MAX_CHUNK_BYTES = MAX_PNG_CHUNK_BYTES

    /**
     * One text chunk's bytes, and on a `.kpp` that is **the whole settings XML**. `PngChunks`'s, which
     * JB-8.04 re-pointed to 8 MiB for this row — the reason is in that file and it is this row's reason.
     */
    internal const val MAX_STRING_BYTES = MAX_PNG_STRING_BYTES

    /** Entries in one `.bundle`. Krita's own default bundle holds about 90. 4 096 is far past it. */
    internal const val MAX_ENTRIES = 4_096

    /**
     * One bundle entry's bytes as they sit in the file. 32 MiB, the same number as [MAX_CHUNK_BYTES]
     * because a `.kpp` **is** the entry's whole content: bounding an entry and bounding a `.kpp` are one
     * bound, and two numbers for one quantity is one more place to be wrong.
     */
    internal const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /**
     * An entry name, in characters. 512, and it is a *name* cap rather than a *file* cap because the name
     * becomes a brush name and a brush name becomes an id, and a stranger's name is the thing that has to be
     * bounded before it is stored, matched or quoted (Decision 13).
     */
    internal const val MAX_ENTRY_NAME_CHARS = 512

    /** XML nesting in a `preset`. Krita's own preset is 4 deep; 64 is unreachable by a real one. */
    internal const val MAX_XML_DEPTH = 64

    /** Elements in one `preset`. A rich Pixel preset is a few hundred. */
    internal const val MAX_XML_NODES = 200_000

    /** Attributes on one element. A real one has four. */
    internal const val MAX_XML_ATTRS_PER_NODE = 256

    /**
     * A base64 payload in the settings, in **characters** — refused *before* base64 decoding is attempted.
     *
     * 8 Mi chars = 8 388 608. **This is not `ImportSupport.MAX_EXTENSION_BYTES` and must never be confused
     * with it**: this is what the reader will refuse to *attempt* to decode, 256 KiB is what one brush may
     * actually *keep*, and over 256 KiB Decision 4 drops the image whole while the brush still converts. A
     * payload can be under this cap and still be over that one, which is exactly what test 14d is.
     */
    internal const val MAX_BASE64_CHARS = 8_388_608

    /** Brushes in one bundle. Krita's default bundle holds about 90; 2 048 is R40's number for an import. */
    internal const val MAX_BRUSHES = 2_048

    /**
     * Decision 3: the curve-point cap, **read** from [BrushValidate] and not re-derived.
     *
     * A second `64` declared here is a second opinion about a number one validator owns, which is the drift
     * R23 forbids and the spec's own Do-not list names ("do not re-derive `MAX_CURVE_POINTS`… a second 64
     * is drift no build can catch"). It is an alias rather than a use site so the test can name the exact
     * number the refusal message must quote.
     */
    internal const val CURVE_POINT_CAP = BrushValidate.MAX_CURVE_POINTS

    // ---- the names other rows and this one grep for --------------------------------------------------

    /**
     * Decision 2b, word for word. A brush that uses **both** `xtilt` and `declination` is two axes of a
     * two-axis sensor folded onto Joy Brush's one `PenSample.tilt` magnitude, so the second is not a rounding
     * error and it is its own sentence, once per brush.
     *
     * **PROVISIONAL — Claude to confirm:** R40 ruled that this warning *exists*; the wording is this spec's
     * and test 9b pins it.
     */
    const val BOTH_TILTS =
        "this brush uses two tilt sensors (xtilt and declination); Joy Brush has one tilt value, " +
            "so the second is ignored"

    /**
     * Decision 11, word for word. `ColorSmudge` is legal, maps to `engine = "smudge"`, and says that the
     * engine which will honour it does not exist yet.
     *
     * **PROVISIONAL — Claude to confirm:** R40 ruled that ColorSmudge imports *with a warning*; the wording
     * is this spec's and test 16 pins it.
     */
    const val SMUDGE_NOT_BUILT =
        "this brush uses Krita's ColorSmudge engine; Joy Brush's smudge engine is not built yet, " +
            "so this brush stamps for now"

    /** Decision 4: where the stored tip bytes go, and the file name that names their encoding. */
    const val TIP_IMAGE_KEY = "krita.tipImage"
    const val TIP_IMAGE_FILE = "tip.png"

    /** Decision 12's second texture branch: the same, for the grain. */
    const val GRAIN_IMAGE_KEY = "krita.grainImage"
    const val GRAIN_IMAGE_FILE = "grain.png"

    /** Decision 4: a `brush_definition` that names a tip the file does not carry. */
    const val TIP_PREDEFINED_KEY = "krita.tipPredefined"

    /** Decision 12: a texture mode this build has no field for. */
    const val TEXTURE_MODE_KEY = "krita.textureMode"

    /** Decision 12: a texture scale outside `GrainSpec`'s own 0..64, kept because the file stated one. */
    const val TEXTURE_SCALE_KEY = "krita.textureScale"

    /**
     * The real parameter a texture's own bytes live in, and the only one whose value the sweep is told not to
     * keep. It is `base64(base64(PNG))` — twice over — in 9 of the 64 real presets of David Revoy's
     * Brushkit V8.2 (`testdata-local/`, git-ignored), and the largest is 466 408 characters.
     */
    private const val TEXTURE_PATTERN_PARAM = "Texture/Pattern/Pattern"

    /** Decision 12: the Krita pattern a texture names, kept whether or not its bytes were reachable. */
    const val TEXTURE_PATTERN_KEY = "krita.texturePattern"

    /**
     * The real preset's `spacing`, kept under a name of its own because the value the brush draws with is a
     * *rounded, range-checked* copy of it and the file's own text is the one worth having back. The key says
     * `tip` because in the real format `spacing` is an attribute of the same `<Brush>` element that holds
     * the tip, so that is where it lives and that is what a reader looking for it will look at.
     */
    const val TIP_SPACING_KEY = "krita.tipSpacing"

    /**
     * Krita's own procedural tip size, **in Krita's unit and unconverted**, and the key says so.
     *
     * The name is the honest one: this build knows the number and does not know the unit, and a key called
     * `krita.tipSize` would be a claim that it does. See [KritaBrush.resolveSize] for the measurement and
     * for why no conversion is made — it is a Question for the Lead, not this row's decision.
     */
    const val TIP_DIAMETER_KEY = "krita.tipDiameterInKritaUnits"

    /**
     * An image tip's own `scale` factor, kept because `size.base` is that factor *applied* to the tip's
     * pixel width and the application is this row's arithmetic while the factor is Krita's number.
     */
    const val TIP_SCALE_KEY = "krita.tipScale"

    // ---- the contract ------------------------------------------------------------------------------

    /**
     * One `.kpp` file's bytes → one brush, or a refusal.
     *
     * **The name of a brush is the one fact a `.kpp` does not carry.** Krita writes `version` and `preset`
     * and nothing else; a brush is *named* by the file it sits in, which is why [convertBundle] gets names
     * for free and this function does not. So this reads a `Title` text chunk when some writer put one
     * there (Krita does not) and otherwise says so in a warning and calls it `"krita brush 1"` — a name
     * that is visibly a placeholder rather than one that quietly looks real.
     *
     * @param idPrefix the caller's library prefix; ids are [brushId] of it and the brush's name.
     * @throws BrushException if the FILE itself cannot be read — not a PNG, no `preset` chunk, a `preset`
     *   chunk that is not XML at all, a chunk length that overruns, a budget. **One bad brush is never a
     *   [BrushException]: it is a [RefusedBrush]**, because a refusal that throws away the file is the one
     *   thing a pack importer must not do.
     */
    fun convertKpp(bytes: ByteArray, idPrefix: String): ImportLibrary {
        val file = readKppFile(bytes)
        val title = file.title
        val name = title ?: PLACEHOLDER_NAME
        // Decision 14, and R44 item 5: a lone `.kpp` states **its own author and nothing else**, so it is read
        // from the file rather than left blank. A brush re-attributed to nobody is a licence defect, not a
        // missing nicety, and 61 of the 64 real `.kpp` in David Revoy's Brushkit V8.2 state theirs.
        val author = file.author
        return try {
            val result = KritaBrush(file, null, null, author).build(name, brushId(idPrefix, name))
            val warnings =
                if (title == null) listOf(PLACEHOLDER_NAME_WARNING) + result.warnings else result.warnings
            ImportLibrary(listOf(result.copy(warnings = warnings)), emptyList())
        } catch (e: BrushException) {
            ImportLibrary(emptyList(), listOf(RefusedBrush(0, name, e.message ?: "it cannot be read")))
        }
    }

    /**
     * A `.bundle` zip → every `.kpp` inside `brushes/` and `paintoppresets/`, in entry-name order, with the
     * bundle's own `patterns/` entries available to Decision 12's texture fork and its `meta.xml` available
     * to Decision 14.
     *
     * "In entry-name order" is a decision, not a convenience: a bundle with `brushes/{a,b}.kpp` and
     * `paintoppresets/{c}.kpp` gives `a, b, c`, so two bundles holding the same file produce the same
     * library in the same order and a diff of two libraries means something.
     *
     * @throws BrushException if the FILE cannot be read: not a zip, needs Zip64, a budget, or Decision 15's
     *   "a `.bundle` with no `brushes/` and no `paintoppresets/` refuses in words rather than importing an
     *   empty library". One bad entry is a [RefusedBrush] and the rest of the bundle still imports.
     */
    fun convertBundle(bytes: ByteArray, idPrefix: String): ImportLibrary {
        val zip = KritaZip.read(bytes)
        val entries = zip.entries
            .filter { isBrushEntry(it.name) }
            .sortedBy { it.name.lowercase() }
        if (entries.isEmpty()) {
            throw BrushException(
                "this .bundle holds no .kpp under brushes/ and none under paintoppresets/, so it holds no " +
                    "brushes; this build will not import an empty library and say nothing about why"
            )
        }
        if (entries.size > MAX_BRUSHES) {
            throw BrushException("this .bundle holds ${entries.size} brushes, at most $MAX_BRUSHES")
        }

        val meta = readBundleMeta(zip)
        val brushes = ArrayList<ImportResult>(entries.size)
        val refused = ArrayList<RefusedBrush>()
        for ((index, entry) in entries.withIndex()) {
            val name = brushNameOf(entry.name)
            // Decision 13: a name is checked before it is stored, matched or printed, and a name that fails
            // is **that entry's** refusal — the rest of a pack is still a pack.
            val unsafe = unsafeReason(entry.name)
            if (unsafe != null) {
                refused += RefusedBrush(index, name, "its entry name is refused: $unsafe")
                continue
            }
            try {
                val file = readKppFile(zip.read(entry))
                brushes += KritaBrush(file, zip, meta, file.author).build(name, brushId(idPrefix, name))
            } catch (e: BrushException) {
                refused += RefusedBrush(index, name, e.message ?: "it cannot be read")
            }
        }
        return ImportLibrary(brushes, refused)
    }

    // ---- the file-level half of a `.kpp` ------------------------------------------------------------

    /**
     * The two things [readPngTextChunks] can give this importer, the one it cannot, and the one it has to ask
     * a second time for.
     *
     * `PngChunks`' **uncompressed** read never inflates (Decisions 5 and 6), so a compressed chunk arrives
     * here as `compressed = true` with an **empty** `text`, and *this* is the code that has to do something
     * about it. Before JB-8.04b it refused it in words; now it **asks for the text** —
     * [readPngCompressedText] inflates and decodes it — and refuses only what that cannot deliver.
     *
     * The split is deliberate and is what test 4 of `PngChunksTest` pins from the other side: "the reader
     * tried to decode it" and "the caller forgot to check" are two different failures and must produce two
     * different messages. Asking a second time is what a caller of a lossy reader has to do, and it is
     * why that second function lives in `PngChunks` and keeps the compressed bytes rather than handing them
     * here.
     *
     * **The author is asked for case-insensitively, and the measurement is why.** Krita writes the keyword
     * in two spellings inside one pack: 35 of the 64 real `.kpp` carry a `zTXt` keyword `author` and 26 carry
     * `Author`, so a case-sensitive comparison — which is the PNG rule, and is what this file does for
     * `preset` — silently re-attributes 26 brushes to nobody. `preset` stays case-sensitive, because the two
     * spellings of *that* keyword have never been seen in one file and a case-insensitive match there would
     * let a file choose which settings document is read. The two spellings are asked for in turn and the
     * first that exists wins, which is the same "first match wins" rule `readPngCompressedText` itself uses.
     */
    private fun readKppFile(bytes: ByteArray): KritaFile {
        if (bytes.size > MAX_FILE_BYTES) {
            throw BrushException("this .kpp is ${bytes.size} bytes, at most $MAX_FILE_BYTES")
        }
        // Everything below this line — the signature, the chunk walk, the `Long` length arithmetic and the
        // file/chunk/string caps — is `PngChunks.kt`, read and not re-derived. A bad signature or a chunk
        // length that overruns arrives here as its [BrushException] and propagates unchanged.
        val chunks = readPngTextChunks(bytes)

        var preset: PngTextChunk? = null
        var title: String? = null
        var author: String? = null
        for (chunk in chunks) {
            // PNG keywords are 1..79 bytes and case-sensitive ([PngChunks]'s own rule) and Krita writes
            // `preset` in lower case, so this compares exactly rather than guessing at a capitalisation.
            if (chunk.keyword == "preset" && preset == null) preset = chunk
            if (chunk.keyword == "Title" && title == null) title = chunk.text
            // The author's own keyword, which is `author` or `Author` in the wild — see the KDoc.
            if (chunk.keyword.equals("author", ignoreCase = true) && author == null) {
                author = if (chunk.compressed) "" else chunk.text
            }
        }
        val found = preset
            ?: throw BrushException(
                "this file has no \"preset\" text chunk, so it holds no Krita brush settings"
            )

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
        if (xml.isBlank() || !xml.trimStart().startsWith("<")) {
            // "after inflating" only when it *was* inflated, so the sentence does not send somebody looking
            // for corruption in a file that was merely compressed.
            val where = if (found.compressed) "after inflating" else "as it stands"
            throw BrushException(
                "this file's \"preset\" chunk is ${xml.length} characters and does not begin with \"<\", " +
                    "$where, so it is not the XML a .kpp keeps its paint-op settings in"
            )
        }
        val authorName: String?
        val authorProblem: String?
        if (author != null && author.isNotBlank()) {
            authorName = author
            authorProblem = null
        } else {
            // Every real author chunk in the pack is compressed, so the uncompressed read hands back an empty
            // string and the text has to be asked for. Both spellings, in turn, and the first that exists.
            var found: String? = null
            var problem: String? = null
            for (keyword in AUTHOR_KEYWORDS) {
                try {
                    found = readPngCompressedText(bytes, keyword)
                } catch (e: BrushException) {
                    // **A malformed author chunk must not cost the brush.** It is metadata about who made the
                    // brush, not the brush: refusing a whole `.kpp` because its `author` chunk is not a DEFLATE
                    // stream would trade a brush for a byline. The reason is carried to the warning instead, so
                    // this is not the swallowed exception test 21 is about — that one loses a *licence*, and
                    // this one loses an *attribution* and says so.
                    problem = e.message
                }
                if (found != null) break
            }
            authorName = found?.trim()?.takeIf { it.isNotBlank() }
            authorProblem = problem
        }
        return KritaFile(
            xml,
            title?.takeIf { it.isNotBlank() },
            authorName,
            authorProblem,
        )
    }

    /**
     * One `.kpp`'s settings text, the one name-ish thing it may carry, and the author it states for itself.
     *
     * [authorProblem] is non-null when the file *tried* to state an author and the chunk could not be read,
     * which is the one case where "this brush has no author" would be a sentence that hides something.
     */
    private class KritaFile(
        val presetXml: String,
        val title: String?,
        val author: String?,
        val authorProblem: String?,
    )

    /**
     * Decision 14, per bundle. **Never `"CC0"`** — that is `BrushPreset`'s own default and a bundle that does
     * not say so is not a claim this file is entitled to make about somebody else's work.
     *
     * **A limit said out loud:** ODF carries **one `meta.xml` per package**, not one per resource, so a
     * mixed bundle — which is what Krita's own default bundle is (R4 §B.9) — simply does not have the
     * per-resource licence this spec asks for. What is recoverable is recovered; what is not stays
     * `"unknown"`, which is the honest word and is what a future detail sheet can filter on.
     */
    private class KritaMeta(val license: String?, val author: String?)

    /**
     * The bundle's own `meta.xml`, and the two strings in it that are a **licence obligation** rather than a
     * nicety (R44 item 5, blueprint §5: CC-BY attribution is required, and the pack the blueprint names as
     * the one to ship — David Revoy's Brushkit, CC-BY — is the case).
     *
     * **The real `meta.xml` has no `dc:rights` at all, so the landed reader found `license = "unknown"` on
     * every brush of a CC-BY pack.** Read verbatim out of the real bundle:
     *
     * ```xml
     * <meta:meta>
     *  <dc:author>Deevad</dc:author>
     *  <meta:meta-userdefined meta:name="license" meta:value="CC-By with custom rules for redistribution"/>
     * </meta:meta>
     * ```
     *
     * Three separate reasons the landed tag table missed it: the root is `<meta:meta>` and not
     * `<office:document-meta><office:meta>`; the licence element is `<meta-userdefined>`, whose name after
     * the last colon is not in [LICENSE_TAGS]; and **the value is in an attribute, `meta:value`**, not in the
     * element's text — and no ODF document anywhere puts a licence in a `text` attribute. So the fix reads
     * **both** places: an element whose local name is `meta-userdefined` and whose `meta:name` is `license`
     * takes its `meta:value`, and the `<dc:rights>`/`<rights>`/`<license>` element text the landed reader
     * already handled is kept, because a bundle written by a different tool may use it.
     *
     * The comparison is case-insensitive because `meta:name="License"` and `meta:name="license"` are the same
     * word and ODF does not promise a capitalisation.
     */
    private fun readBundleMeta(zip: KritaZip): KritaMeta {
        val entry = zip.find(META_NAME) ?: return KritaMeta(null, null)
        val text = try {
            zip.read(entry)
        } catch (e: BrushException) {
            // A `meta.xml` this build cannot read is not a reason to refuse a pack of brushes: the licence
            // and author stay "unknown" and "", which is exactly what they would be without it.
            return KritaMeta(null, null)
        }
        val root = try {
            parseKritaXml(decodeUtf8Strict(text, 0, text.size, "meta.xml"), "this bundle's meta.xml")
        } catch (e: BrushException) {
            return KritaMeta(null, null)
        }
        var license: String? = null
        var author: String? = null
        for (node in root.all()) {
            val tag = node.name.substringAfterLast(':')
            // ODF puts these in the **element's text** — `<dc:rights>CC-BY 4.0</dc:rights>` — not in an
            // attribute, which is why [KritaNode] keeps character data at all.
            val said = node.text.takeIf { it.isNotBlank() } ?: node.attr("text")?.takeIf { it.isNotBlank() }
            if (license == null && tag in LICENSE_TAGS) license = said
            if (author == null && tag in AUTHOR_TAGS) author = said
            // …and the form a real Krita bundle writes, where the value is `meta:value` on a
            // `meta-userdefined` whose `meta:name` says what the value is.
            val key = node.attr(META_NAME_ATTR)?.trim()?.lowercase()
            val value = node.attr(META_VALUE_ATTR)?.trim()?.takeIf { it.isNotBlank() }
            if (value == null) continue
            if (license == null && key == LICENSE_META_NAME) license = value
            if (author == null && key != null && key in AUTHOR_META_NAMES) author = value
        }
        return KritaMeta(license, author)
    }

    // ---- one brush ----------------------------------------------------------------------------------

    /**
     * One preset being built, field by field.
     *
     * A class rather than one very long function because the spec's own Step 5 warns that the R40 store is
     * "the row a builder will think is finished". Here the tip is *decided* while the tip is in front of you
     * ([tip]) and the grain while the grain is ([texture]), and the bytes are written at [finish], where the
     * `extensions` budget is already known — because Decision 4's over-cap rule needs the budget and
     * Decision 4's warning needs both facts at once.
     */
    private class KritaBrush(
        private val file: KritaFile,
        private val bundle: KritaZip?,
        private val meta: KritaMeta?,
        /**
         * The author **this one `.kpp` states in its own `author`/`Author` chunk**, which is more specific
         * than a bundle's `meta.xml` and therefore wins: in David Revoy's real pack the file says
         * `David Revoy ( aka Deevad )` where the bundle says `Deevad`, and the fuller string is the better
         * attribution. Null when the file states none, and then the bundle's own word is used.
         */
        private val ownAuthor: String?,
    ) {

        // The parse happens in the constructor, so a hostile document throws before a single field exists
        // and the caller turns that into a [RefusedBrush] naming the brush.
        private val root = parseKritaXml(file.presetXml, "this .kpp's preset XML")

        /**
         * Krita's own settings form, as the real bytes write it — and this is the fact the whole row was
         * getting wrong. **Every setting is a `<param type="string|internal|bytearray" name="KEY">`, and the
         * value is the element's *text*, inside a `CDATA` section for the two types that hold text.**
         *
         * Measured over the 64 real presets of David Revoy's Brushkit V8.2 (`testdata-local/`, git-ignored):
         * 7 256 `<param>` elements, `type="string"` 4 973, `type="internal"` 2 265, `type="bytearray"` 18,
         * and **247 distinct `name`s**. In all 64 presets **no `name` appears twice**, so a map keyed by the
         * name loses nothing on any of them; a second `<param>` with a name already present wins, which is the
         * only rule a duplicate needs and the only one a real file has never required.
         *
         * The `internal` type writes its value as bare text with no `CDATA` and the other two inside one, and
         * **both are read the same way**, because a reader that insisted on the `CDATA` would discard 2 265
         * of the 7 256 settings in the real pack — including every `EraserMode` and every `PressureSize`.
         */
        private val settings: Map<String, KritaValue> = readSettings()

        private val warnings = ArrayList<String>()
        private val extensions = LinkedHashMap<String, String>()
        private val rawKept = ArrayList<String>()

        private var engine = "stamp"
        private var aspect = 0f
        private var tipHardnessBase = DEFAULT_HARDNESS
        private val angleInputs = ArrayList<InputCurve>()
        private val tipHardnessInputs = ArrayList<InputCurve>()
        private val sizeInputs = ArrayList<InputCurve>()
        private val opacityInputs = ArrayList<InputCurve>()
        private val flowInputs = ArrayList<InputCurve>()
        private val scatterInputs = ArrayList<InputCurve>()
        private var sizeBase = DEFAULT_DIAMETER_PX
        private var pixelSize: Float? = null
        private var spacing = DEFAULT_SPACING

        /**
         * The **flat** shape's size curve top, read as a px diameter because that is what the spec's Decision
         * 4 and its test 11 say it is.
         *
         * **And only the flat shape's.** In the real shape a size curve is a **multiplier on the diameter**,
         * which is why every one of the 64 real `SizeSensor` curves in David Revoy's Brushkit V8.2 tops out
         * at *exactly* 1.0 — a brush whose size curve ends at 1.0 is a brush whose pressure does not change
         * its size, which is the common case and not a one-pixel brush. Reading that 1.0 as a diameter is
         * what the landed `resolveSize` does, and it would have made **all 64 real presets paint one pixel
         * wide**. The real form's size therefore goes where it belongs: `size.base` is the diameter (a
         * number this row keeps in Krita's own unit and warns about) and the curve is an **input**, which is
         * exactly `Param(base, inputs)` and exactly Krita's own `diameter × curve(sensor)`.
         */
        private var flatSizeTop: Float? = null
        private var smoothing = DEFAULT_SMOOTHING
        private var scatterAmount = 0f
        private var scatterCount = 1
        private var scatterCountJitter = 0f

        // Decision 4's first case, and the one that is easiest to get wrong in the *other* direction: a
        // brush with an image tip still gets a real `size.base`, so it paints at the right size today.
        private var storedTip: KritaStored? = null

        /**
         * The image brush's own `scale` attribute, which is the factor its pixel width is multiplied by.
         * 1 when the file does not state one, so an unstated scale is the identity rather than a guess.
         */
        private var tipScale = 1f

        /**
         * Krita's procedural mask's `diameter`, **kept in Krita's own unit and not converted**, with
         * [TIP_DIAMETER_KEY] naming that unit in the key. See [resolveSize] for why there is no conversion
         * and what the person is told instead.
         */
        private var tipDiameter: String? = null

        /** The real `spacing` attribute of the `<Brush>` element, in Krita's own fraction of the diameter. */
        private var realSpacing: Float? = null

        /** Krita's `useAutoSpacing`, which means Krita *computed* the spacing and this build did not. */
        private var realAutoSpacing = false

        // Decision 12's two branches, kept apart because they end in different `paperGrain` fields.
        private var textureName: String? = null
        private var textureMode: String? = null
        private var textureDepth = 1f
        private var textureScale = 1f
        private var grainStored: KritaStored? = null
        private var grainUnreadable: String? = null
        private var grainEnabled = false

        /**
         * The tip's bytes, its file name, and its own width.
         *
         * [width] is the tip's `IHDR` width, read at a fixed offset and **not decoded** (Decision 6): R40
         * says the stored image never replaces the numbers, so a brush with an image tip still gets a real
         * `size.base` — the diameter in px — and the bitmap is *additional*. A grain has no width, hence 0.
         */
        private class KritaStored(val bytes: ByteArray, val fileName: String, val width: Int)

        fun build(name: String, id: String): ImportResult {
            engine = engineOf(paintopId())
            tip()
            settings()
            curves()
            resolveSize()
            texture()
            sweep()
            // Said here rather than nowhere, and once per brush: the file tried to name its author and the
            // chunk could not be read, so the brush has no author **and the person is told why**.
            file.authorProblem?.let { why ->
                warn(
                    "this .kpp's own author chunk could not be read, so the brush is imported with no " +
                        "author rather than not at all: $why"
                )
            }
            return finish(name, id)
        }

        // ---- Decision 1 and 10: the engine gate, which is the whole row --------------------------------

        /**
         * Decision 10: the id is read from the XML and **nowhere else**, and a missing one refuses.
         *
         * **The attribute is `paintopid` on a `<Preset>` root, and the spec's Decision 10 was wrong about
         * it.** The spec says the settings are `<paintop id="…">`; the reader obeyed; the fixtures obeyed;
         * and the three agreed with each other and with nothing Krita has ever written. Measured over the 64
         * real presets of David Revoy's Brushkit V8.2 (`testdata-local/`, git-ignored): the root is
         * **`<Preset name="…" paintopid="…">` in 64 of 64**, and the values are `paintbrush` ×54,
         * `colorsmudge` ×7, `deformbrush`, `experimentbrush` and `duplicate` ×1 each. The string `id=` never
         * appears on a root. So the reader refused 61 of 61 mappable brushes with a sentence about a
         * `<paintop id="…">` that no such file has, and the row's headline claim — that a `deformbrush` is
         * refused by name — was untested against anything real.
         *
         * There is a **second copy** of the same fact in every real preset, `<param type="string"
         * name="paintop"><![CDATA[paintbrush]]></param>` (64 of 64, and it always agrees with the
         * attribute). **It is not read as a fallback.** The attribute is in 64 of 64, a fallback would be a
         * second place a stranger's file gets to choose an engine, and Decision 10's whole point is that the
         * engine is read from one place or not at all. The redundant copy is left in the sweep, which keeps
         * it, so nothing is lost by not reading it.
         */
        private fun paintopId(): String {
            val id = root.attr("paintopid")?.takeIf { it.isNotBlank() }
                ?: throw BrushException(
                    "its preset XML is a <${root.name}> with no paintopid=\"…\", so it does not say which " +
                        "Krita engine this brush is; Joy Brush will not guess one, because a bristle brush " +
                        "read as a round one is a different brush wearing the same name"
                )
            root.claim("paintopid")
            return id
        }

        /**
         * Decision 1: a `when` with **no branch that returns a brush for an unlisted engine**.
         *
         * **Two spellings of each accepted engine, because the two the spec invented and the two the real
         * files write are both real.** `paintbrush` is what 54 of 64 real presets carry and `Pixel` is what
         * Krita's own brush editor calls that engine — the class name rather than the registered name — and a
         * reader that accepts only one of them refuses half of every pack it has ever seen. So both are
         * accepted for both engines and **nothing else is**, which keeps Decision 1's actual claim (a
         * `when` with no soft landing) and the thirteen refused-id cases it is pinned by.
         *
         * The refused sentences are built around the file's **own** id, because "unsupported" is not a
         * sentence a person can act on and the id is what they will see in Krita. The `else` refuses too and
         * is not a soft landing: it is the same refusal with whatever Krita called it — which is what
         * `deformbrush`, `experimentbrush` and `duplicate` get, three of the 64 real presets, because none of
         * them is a dab model Joy Brush has and approximating one is the thing this row exists to refuse.
         */
        private fun engineOf(paintopId: String): String = when (paintopId) {
            "paintbrush", "Pixel" -> "stamp"
            "colorsmudge", "ColorSmudge" -> {
                warn(SMUDGE_NOT_BUILT)
                "smudge"
            }
            "Hairy", "Bristle", "Sketch", "Deform", "Particle", "Curve", "Hatching", "Grid",
            "Shape", "Filter", "TangentNormal", "Quick", "Spray" -> throw BrushException(engineRefusal(paintopId))
            else -> throw BrushException(engineRefusal(paintopId))
        }

        private fun engineRefusal(id: String) =
            "its paintopid is \"$id\", which is a Krita engine with no stamp equivalent in Joy Brush; a " +
                "different dab model wearing the same name is a different brush, so this one is dropped " +
                "rather than approximated"

        // ---- Decision 4: the tip, and the R40 store it may end in -------------------------------------

        /**
         * The tip, in whichever of the two shapes the file uses.
         *
         * **The real shape is a *value*, not an element.** `brush_definition` is a
         * `<param type="string" name="brush_definition">` whose text is a nested document,
         * `<![CDATA[<Brush type="auto_brush" spacing="0.1" …><MaskGenerator …/></Brush>]]>`, and 62 of the
         * 64 real presets carry it that way. The older element form, `<brush_definition type="auto_mask"
         * name="start"><cell row="0" col="0" value="1.0;0.6"/></brush_definition>`, is still read by
         * [elementTip] — the landed tests pin it, it is a shape Krita's own older writers produced, and a
         * reader that drops it would refuse a preset rather than approximate it.
         */
        private fun tip() {
            val element = root.child("brush_definition")
            if (element != null) {
                elementTip(element)
                return
            }
            val value = settings["brush_definition"]
            if (value == null) {
                // An absent definition is the spec's first case and is MAPPED, not a fault: a preset that has
                // not chosen a tip yet still has one, and the default tip is a circle. `tip.aspect` stays 0
                // and `tip.corner` is 2, and that is a circle.
                return
            }
            realTip(value)
        }

        /**
         * The real shape, and the three things it turns out to carry.
         *
         * - **an `auto_brush`** — a procedural ellipse. `MaskGenerator@ratio` is the aspect and
         *   `MaskGenerator@diameter` is the size in Krita's own unit. 30 of the 64 real presets.
         * - **a `png_brush` or `gbr_brush` with a `filename`** — a tip the file *names*. **All 32 of the
         *   real pack's named tips resolve to an entry of the same bundle**, in `brushes/`, which is a third
         *   case the spec did not have: the bytes are reachable, so a `.png` one is stored under R40 and a
         *   `.gbr`/`.gih` one is kept by name with a warning, exactly as a texture this build cannot decode
         *   is. **Not refused** — see the class KDoc on [KritaStored] and the report; Decision 4's REFUSED
         *   bullet is scoped to a *base64-embedded* GBR, which no real file has.
         * - **a name that resolves to nothing** — the spec's *predefined* case, unchanged.
         */
        private fun realTip(value: KritaValue) {
            val where = "its \"brush_definition\""
            val node = parseKritaXml(value.text, where, bareDoctype = true)
            node.claim(
                "type", "filename", "scale", "spacing", "useAutoSpacing", "autoSpacingCoeff",
                "randomness", "density", "angle", "BrushVersion", "ColorAsMask",
            )
            node.attr("scale")?.trim()?.toFloatOrNull()?.let { if (it > 0f) tipScale = it }
            node.attr("scale")?.let { extensions[TIP_SCALE_KEY] = it.trim() }
            // The real `spacing` is read here, where the element is already parsed, and applied by
            // [applyRealSpacing]. Its raw copy is kept so the value is in `extensions` under a name of its own.
            node.attr("spacing")?.let { extensions[TIP_SPACING_KEY] = it.trim() }
            realSpacing = node.attr("spacing")?.trim()?.toFloatOrNull()
            realAutoSpacing = node.attr("useAutoSpacing")?.trim() == "1"

            val kind = node.attr("type")?.trim()?.lowercase() ?: ""
            if (kind == "auto_brush" || kind.isEmpty()) {
                aspectOfMask(node)
                return
            }
            val filename = node.attr("filename")?.trim()?.takeIf { it.isNotBlank() }
            if (filename == null) {
                // A tip of a kind this build does not recognise and with no name to look up: there is nothing
                // to fetch and nothing to keep by name, so it is the spec's first case — a round procedural
                // dab — and the kind is named so the person can see what this build met.
                val said = node.attr("type")?.trim()?.takeIf { it.isNotBlank() } ?: kind
                warn(
                    "this brush's tip is a \"$said\" with no file name, which this build does not read, so " +
                        "the tip stays a procedural round dab"
                )
                return
            }
            val base = filename.substringAfterLast('/')
            val zip = bundle
            val entry = zip?.let { z -> z.find("brushes/$base") ?: z.find(base) }
            if (zip == null || entry == null) {
                // The spec's *predefined* case: a Krita resource path this file does not carry. LOSSY, not
                // refused — there is nothing to refuse *on*, the file simply does not carry the picture.
                extensions[TIP_PREDEFINED_KEY] = filename
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's tip is the Krita resource \"$filename\", which this " +
                        "file and its bundle do not carry, so the tip stays a procedural round dab and the " +
                        "name is kept in extensions[\"$TIP_PREDEFINED_KEY\"]"
                )
                return
            }
            val bytes = try {
                zip.read(entry)
            } catch (e: BrushException) {
                // A tip this build cannot read out of the bundle is not a reason to drop the brush: the
                // name is kept, the tip stays procedural, and the reason is carried into the warning. The
                // same rule the texture branch uses, and for the same reason — an importer that refuses a
                // whole pack because one entry is a bomb is a worse tool than one that says so.
                extensions[TIP_PREDEFINED_KEY] = filename
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's tip is \"brushes/$base\", which this bundle holds but " +
                        "this build could not read (${
                            e.message ?: "the entry could not be read"
                        }), so the tip stays a procedural round dab and the name is kept in " +
                        "extensions[\"$TIP_PREDEFINED_KEY\"]"
                )
                return
            }
            if (!isPngSignature(bytes, 0)) {
                // GIMP's `.gbr` / `.gih` and Adobe's `.abr` are three more binary formats and this row's
                // budget is one XML reader and one PNG chunk reader. **The tip is kept by name and warned
                // about, and the brush is kept**, which is Decision 12's rule for a pattern this build
                // cannot decode and R40's own words: *"refusing the brush instead refuses most of Procreate
                // and much of Revoy's Krita pack."* 20 of the 64 real presets are in this case.
                extensions[TIP_PREDEFINED_KEY] = filename
                val format = base.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() }
                    ?: "a binary brush format"
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's tip is \"brushes/$base\", which this bundle does carry, " +
                        "but it is a $format file and this build reads no GIMP or Adobe brush binary, so the " +
                        "tip stays a procedural round dab and the name is kept in " +
                        "extensions[\"$TIP_PREDEFINED_KEY\"]"
                )
                return
            }
            val header = readPngHeader(bytes)
                ?: throw BrushException("its tip \"brushes/$base\" starts with the PNG signature but carries no IHDR")
            if (header.width <= 0) {
                throw BrushException("its tip \"brushes/$base\" has an IHDR width of ${header.width} px")
            }
            storedTip = KritaStored(bytes, TIP_IMAGE_FILE, header.width)
        }

        /**
         * The procedural mask's own two numbers, and only those two.
         *
         * `ratio` is the aspect (see [aspectOfRatio]) and `diameter` is the size (see [resolveSize]); the
         * other eight attributes a real `MaskGenerator` carries — `type`, `id`, `vfade`, `hfade`, `spikes`,
         * `antialiasEdges`, `softness_curve` — are Joy Brush tip features this row does not have, so they
         * are left to the sweep, which keeps them and the brush is told they were kept.
         */
        private fun aspectOfMask(node: KritaNode) {
            val mask = node.childrenNamed("MaskGenerator").firstOrNull() ?: return
            mask.claim("type", "id", "diameter", "ratio", "vfade", "hfade", "spikes", "antialiasEdges")
            aspect = aspectOfRatio(mask.attr("ratio"))
            tipDiameter = mask.attr("diameter")?.trim()?.takeIf { it.isNotBlank() }
        }

        /**
         * Krita's own ellipse ratio (`MaskGenerator@ratio`, width over height) onto `TipSpec.aspect`, which
         * `BrushValidate` rule 6 holds to −1..1 with 0 a circle.
         *
         * **`(r − 1) / (r + 1)`.** Chosen because it is one expression with no branch that cannot get a
         * ratio's sign wrong: `r < 1` (taller than wide) → negative, `r == 1` → **exactly** 0, `r > 1` →
         * positive, and it is monotone in `r`, so no value rounds to the wrong side. The real pack's four
         * values are `1` ×27, `0.52`, `0.39` and `0.17`, and the three non-circular ones give −0.3158,
         * −0.4388 and −0.7094 — every one of them a real ellipse, which the landed `aspectOf` could not have
         * produced because **a real preset has no `<cell>`: `<cell>` appears 0 times in all 64.**
         *
         * A `ratio` that is not a number, or is zero or negative, leaves `aspect` at 0 and says so, because
         * 0 is a circle and a circle is the one shape this build can draw without a curve.
         */
        private fun aspectOfRatio(raw: String?): Float {
            val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return 0f
            val r = text.toFloatOrNull()
            if (r == null || !r.isFinite() || !(r > 0f)) {
                warn("this brush's tip ellipse ratio is \"$text\", which is not a positive number, so " +
                    "tip.aspect stays 0 and a circle is drawn")
                return 0f
            }
            return ((r - 1f) / (r + 1f)).coerceIn(-1f, 1f)
        }

        /**
         * The older element shape, unchanged: an absent definition, an `auto_mask` ellipse from a `<cell>`, a
         * base64-embedded tip (PNG stored, GBR/GIH/ABR **refused by name**), or a name the file does not
         * carry. Decision 4's REFUSED bullet is scoped to *embedded* and stays exactly that way here, because
         * [realTip]'s named case is a different thing and the report says which.
         */
        private fun elementTip(definition: KritaNode) {
            definition.claim("type", "name", "active", "mask_color_palette")
            val type = definition.attr("type")?.lowercase() ?: ""

            if (type == "auto_mask" || type.isEmpty()) {
                // The auto mask is an ellipse: `<cell row="0" col="0" value="1.0;0.6"/>` is `rx;ry`, and
                // `tip.aspect` is a **ratio in −1..1** (rule 6), so `rx − ry` is the only reading of "wider
                // than it is tall" that lands in the field's own range. A circle is 0.
                aspect = aspectOf(definition)
                return
            }

            val embedded = base64Of(definition)
            if (embedded == null) {
                // A **predefined tip by name**: a resource path Krita expects to find on disk, which is not
                // in this file. LOSSY, not REFUSED — there is nothing to refuse *on*, the file simply does
                // not carry the picture. The texture is kept by name and not drawn, so it carries the R40
                // sentence like everywhere else.
                val named = definition.attr("name")?.takeIf { it.isNotBlank() } ?: "(unnamed)"
                extensions[TIP_PREDEFINED_KEY] = named
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's tip is the Krita resource \"$named\", which this file " +
                        "does not carry, so the tip stays a procedural round dab and the name is kept in " +
                        "extensions[\"$TIP_PREDEFINED_KEY\"]"
                )
                return
            }

            if (!isPngSignature(embedded, 0)) {
                // A base64-embedded **GBR / GIH / ABR**. REFUSED for this row, by name, and the reason is
                // stated so it is not a bare "no": three more binary readers (GIMP ×2, Adobe) against this
                // row's budget of one XML reader and one PNG chunk reader.
                val declared = definition.attr("name")?.substringAfterLast('.', "")?.uppercase()
                val named = if (!declared.isNullOrEmpty()) {
                    declared
                } else {
                    "GBR, GIH, ABR or another binary brush format"
                }
                throw BrushException(
                    "its tip is embedded as $named, which this build does not read: a GIMP .gbr and a GIMP " +
                        ".gih and an Adobe .abr are three more binary formats, and this importer reads the " +
                        "PNG one. The whole brush is dropped rather than replaced by a tip that is not it"
                )
            }
            val header = readPngHeader(embedded)
                ?: throw BrushException(
                    "its embedded tip starts with the PNG signature but carries no IHDR, so it is not a PNG"
                )
            if (header.width <= 0) {
                throw BrushException("its embedded tip's IHDR says its width is ${header.width} px")
            }
            storedTip = KritaStored(embedded, TIP_IMAGE_FILE, header.width)
        }

        private fun aspectOf(definition: KritaNode): Float {
            val cell = definition.child("cell") ?: return 0f
            val raw = cell.attr("value")?.takeIf { it.isNotBlank() } ?: return 0f
            cell.claim("row", "col", "value")
            val bits = raw.split(';')
            if (bits.size != 2) {
                warn("this brush's auto mask is \"$raw\", which is not an rx;ry pair, so tip.aspect stays 0")
                return 0f
            }
            val rx = bits[0].trim().toFloatOrNull()
            val ry = bits[1].trim().toFloatOrNull()
            if (rx == null || ry == null || !rx.isFinite() || !ry.isFinite()) {
                warn("this brush's auto mask is \"$raw\", which is not two numbers, so tip.aspect stays 0")
                return 0f
            }
            return (rx - ry).coerceIn(-1f, 1f)
        }

        /** The `base64` payload of a `<brush_definition>`, decoded, or null when there is not one. */
        private fun base64Of(definition: KritaNode): ByteArray? {
            for (node in definition.all()) {
                if (!node.attr("type").equals("base64", ignoreCase = true)) continue
                val text = node.attr("value")?.takeIf { it.isNotBlank() } ?: continue
                node.claim("type", "name", "value")
                // Refused **before** base64 decoding is attempted: 8 Mi characters is what this row will not
                // attempt, and it is not the 256 KiB that one brush may keep.
                if (text.length > MAX_BASE64_CHARS) {
                    throw BrushException(
                        "its embedded tip is ${text.length} base64 characters, and this build will not " +
                            "attempt more than $MAX_BASE64_CHARS"
                    )
                }
                return kritaBase64Decode(text, "its embedded tip")
            }
            return null
        }

        // ---- Decisions 2, 2b and 3: sensors, the two tilts, and curves ---------------------------------

        /**
         * One curve found in the file, in **either** shape, reduced to the four facts the mapping needs.
         *
         * Collecting first and applying second is not tidiness, it is Decision 2b. The two-tilt warning
         * depends on which tilt sensors the file carries *anywhere*, so a reader that decided each curve as
         * it walked would have to know the whole file before it could map the first one — and the landed
         * version's two-pass pre-pass was a workaround for exactly that. One list, applied once, cannot
         * depend on the order the file happened to be written in.
         *
         * [target] is the Joy Brush setting this curve drives, or null when the format does not say — and in
         * the real format it **does** say, through the parameter's own name (see [SENSOR_TARGETS]).
         * [label] is what a warning and a refusal name, and it is the format's own name for the thing.
         */
        private class KritaCurve(
            val sensor: String,
            val target: Target?,
            val label: String,
            val points: List<List<Float>>,
            /**
             * Did this curve come from the **flat** shape (a `sensor` attribute) or from the **real** shape (a
             * `…Sensor` param's nested document)? It decides one thing only, and that one thing is a px
             * diameter: see [resolveSize] and [flatSizeTop].
             */
            val fromFlatForm: Boolean,
        )

        private fun curves() {
            val found = ArrayList<KritaCurve>()
            collectFlatCurves(found)
            collectRealCurves(found)
            applyCurves(found)
        }

        /**
         * Sensors a real parameter names that carry **no curve** and that Joy Brush has no `BrushInput` for.
         * They contribute nothing, but they are still named, because "Joy Brush has no input for this sensor"
         * is true whether or not the file happened to give it a curve.
         */
        private val unmappedCurveLess = LinkedHashSet<String>()

        /**
         * The shape the landed reader understood, kept whole: any element carrying a `sensor` and a **curve**
         * is a curve whatever Krita called it, and its `name` says which setting it drives.
         *
         * **Krita writes this shape 0 times in the 64 real presets** — `sensor=` appears nowhere, and so do
         * `commonCurve`, `<curve …>` as an attribute-carrying element, and `<point>`. It is kept because
         * these are the landed tests' fixtures and because Krita's older preset writers produced a flat form
         * this build has no reason to refuse, not because any file it will see uses it.
         */
        private fun collectFlatCurves(found: MutableList<KritaCurve>) {
            for (node in root.all()) {
                val sensor = node.attr("sensor")?.takeIf { it.isNotBlank() } ?: continue
                // Any element carrying a `sensor` and a **curve** is a curve, whatever Krita called it:
                // `<curve …/>` in Krita 4 and `<param type="curve" … sensor="…"/>` in the flat form.
                // Keying on the shape rather than on the element name is what lets one reader take both, and
                // a `<param type="range" sensor="…">` is not mistaken for a curve with no points in it.
                if (!isCurve(node)) continue
                val name = node.attr("name")?.takeIf { it.isNotBlank() }
                // **Every curve in the file is parsed and checked, mapped or not.** JB-8.03's finding was that
                // checking only the mapped ones made a file's legality depend on which setting you looked at,
                // and nothing here is allowed to depend on that.
                val points = curvePoints(node)
                found += KritaCurve(
                    sensor.lowercase(),
                    name?.let { TARGETS[it.lowercase()] },
                    name ?: node.name,
                    points,
                    fromFlatForm = true,
                )
            }
        }

        /**
         * The shape Krita actually writes, and it is a **value** rather than an element: every input hangs off
         * a `<param name="…Sensor">` whose text is a nested document.
         *
         *  - **one sensor** — `<params id="pressure"><curve>0,0;1,1;</curve></params>`, 902 nested documents
         *    begin with `<!DOCTYPE params>` and this is the shape;
         *  - **two or more** — `<params id="sensorslist">` with one `<ChildSensor id="…">` each carrying its
         *    own `<curve>`, which is how Krita combines two axes onto one setting.
         *
         * **Both places a curve can sit are read, and each is read for a measured reason.** Eight of the nine
         * real `sensorslist` values put their curves on the children, and the ninth (`deevad 6q bokeh`) puts
         * one on the `<params>` itself *beside* its children; a reader that took only the children would lose
         * that brush's own curve, and one that took only the direct `<curve>` would lose the other eight. A
         * direct `<curve>` beside children is given to the **first** child in document order, which is a
         * choice and is stated as one: the file does not say whose curve it is, and the first child is the
         * only defensible answer, while the raw text is kept either way.
         *
         * **A sensor with no curve contributes nothing at all** — no input, and no warning either, because a
         * warning that fires on almost every brush is a warning nobody reads. The real pack has 118 sensor
         * parameters of this shape (`<params id="pressure"/>` with no `<curve>`), and a sensor with no curve
         * says nothing about the stroke. A sensor with no curve **and no `BrushInput`** is still named,
         * because that one is a fact about Joy Brush rather than about the file.
         */
        private fun collectRealCurves(found: MutableList<KritaCurve>) {
            for ((paramName, value) in settings) {
                if (!paramName.endsWith(SENSOR_SUFFIX)) continue
                val body = value.text
                // A `…Sensor` param whose value is not a document is not a sensor. `startsWith("<")` is the
                // test, and it is the same one `readKppFile` uses on a whole `preset` chunk.
                if (!body.startsWith("<")) continue
                val params = parseKritaXml(body, "its \"$paramName\"", bareDoctype = true)
                params.claim("id", "rotationModeEnabled", "type", "softness_threshold", "isCircular")
                val children = params.childrenNamed("ChildSensor")
                for (child in children) {
                    child.claim("id", "rotationModeEnabled", "type", "softness_threshold", "isCircular")
                }
                val direct = params.childrenNamed("curve").firstOrNull()
                if (direct != null) direct.claim("sensor", "name", "type", "commonCurve", "points", "min", "max")
                val target = SENSOR_TARGETS[paramName.lowercase()]

                val rows = ArrayList<Pair<String, KritaNode?>>()
                val rootId = params.attr("id")?.trim()?.lowercase()
                if (rootId != null && rootId != SENSORS_LIST) {
                    rows += rootId to direct
                }
                for (child in children) {
                    val id = child.attr("id")?.trim()?.lowercase()
                    if (id.isNullOrEmpty()) continue
                    rows += id to child.childrenNamed("curve").firstOrNull()
                }
                if (rootId == SENSORS_LIST && direct != null && rows.isNotEmpty()) {
                    // A `<curve>` on the `sensorslist` itself: the first child's, as the KDoc says.
                    val first = rows[0]
                    if (first.second == null) rows[0] = first.first to direct
                }
                for ((sensor, curve) in rows) {
                    // A sensor with **no curve** contributes nothing at all: no input, and no warning
                    // unless Joy Brush has no input for the sensor either, which is a fact about Joy Brush
                    // rather than about the file. 118 of the 1 020 real sensor parameters are this shape
                    // (`<params id="pressure"/>`), and inventing an identity curve for them would be
                    // inventing a setting — and, through [resolveSize], a one-pixel brush.
                    if (curve == null) {
                        if (INPUTS[sensor] == null) unmappedCurveLess += sensor
                        continue
                    }
                    found += KritaCurve(
                        sensor,
                        target,
                        paramName,
                        pointsOf(curve, paramName),
                        fromFlatForm = false,
                    )
                }
            }
        }

        /**
         * Apply every curve the two collectors found, and say the two things that are not obvious.
         *
         * Decision 2b's outcome is decided from the whole list, so it does not depend on which tilt the file
         * happened to write first: both raw values survive either way and the **same** one is always the
         * ignored one.
         */
        private fun applyCurves(found: List<KritaCurve>) {
            val unmappedSensors = LinkedHashSet(unmappedCurveLess)
            val unmappedParams = ArrayList<String>()

            // Decision 4's order, and the **flat form's** reading of a size curve as a px diameter. See
            // [flatSizeTop]: in the real shape the top of a size curve is a **multiplier**, and all 64 real
            // presets' top out at exactly 1.0, so reading it as a diameter would make every one of them a
            // one-pixel brush.
            val tiltSensors = HashSet<String>()
            for (curve in found) {
                if (INPUTS[curve.sensor] == BrushInput.tilt) tiltSensors += curve.sensor
            }
            val bothTilts = "xtilt" in tiltSensors && "declination" in tiltSensors

            for (curve in found) {
                val sensor = curve.sensor
                val points = curve.points
                val input = INPUTS[sensor]
                if (input == null) {
                    // Decision 2: the rest have no `BrushInput` and are kept raw and named. **No new
                    // `BrushInput` constant here** — R3 makes that a `BRUSH_VERSION` bump and a validator
                    // change, and Joy Brush's inputs are the engine's business, not Krita's.
                    unmappedSensors += sensor
                    extensions["krita.$sensor"] = points.asCommonCurve()
                    continue
                }
                if (input == BrushInput.tilt) {
                    // Decision 2b: **both** raw values stay in `extensions`, whether or not the brush also
                    // triggered the general per-sensor warning — so this is written for a single tilt too, and
                    // the one that is *ignored* when there are two is the one whose curve is not applied.
                    extensions["krita.$sensor"] = points.asCommonCurve()
                    if (bothTilts && sensor == "declination") continue
                }
                val target = curve.target
                if (target == null) {
                    unmappedParams += curve.label
                    continue
                }
                when (target) {
                    Target.SIZE -> {
                        sizeInputs += InputCurve(input, points)
                        // Only the **flat** shape's size curve is a diameter in px. See [flatSizeTop].
                        if (curve.fromFlatForm && flatSizeTop == null) {
                            flatSizeTop = points.maxByOrNull { it[0] }?.get(1)
                        }
                    }
                    Target.OPACITY -> opacityInputs += InputCurve(input, points)
                    Target.FLOW -> flowInputs += InputCurve(input, points)
                    Target.TIP_ANGLE -> angleInputs += InputCurve(input, points)
                    Target.SCATTER -> {
                        scatterInputs += InputCurve(input, points)
                        // The scatter *amount* is the curve's own top: at full sensor value that is all the
                        // scatter this brush asks for.
                        scatterAmount = points.last()[1]
                    }
                    Target.HARDNESS -> {
                        tipHardnessInputs += InputCurve(input, points)
                        tipHardnessBase = points.last()[1]
                    }
                }
            }

            if (bothTilts) warn(BOTH_TILTS)
            if (unmappedSensors.isNotEmpty()) {
                warn(
                    "Krita sensors this build has no Joy Brush input for: " +
                        "${unmappedSensors.joinToString(", ")}; their curves are kept raw in extensions and " +
                        "are not drawn"
                )
            }
            if (unmappedParams.isNotEmpty()) {
                warn(
                    "these Krita parameters have no Joy Brush setting: " +
                        "${unmappedParams.distinct().take(8).joinToString(", ")}; their curves are kept raw " +
                        "in extensions and are not drawn"
                )
            }
        }

        /**
         * A `<curve>` element's text as Krita spells a curve — `0,0;1,1;` — obeying Decision 3's caps. The
         * real form puts the points in the element's **text** and the flat form in an attribute, and both go
         * through here so both obey the same 64-point cap and the same 0..1 x rule.
         */
        private fun pointsOf(curve: KritaNode, label: String): List<List<Float>> {
            val raw = curve.text.trim().takeIf { it.isNotBlank() } ?: ""
            return checkPoints(parsePairs(raw, "\"$label\" curve"), "\"$label\"")
        }

        /**
         * Is this element a **curve** rather than one of the other things Krita hangs a `sensor` on?
         *
         * Decided on the shape — a `type="curve"`, a `commonCurve`/`points` attribute, or `<point>` children —
         * and not on the element's name, because Krita 4 writes `<curve sensor= name=>` and the flat form
         * writes `<param type="curve" name= sensor=>`, and one reader should take both. A
         * `<param type="range" sensor="pressure" value="0.5"/>` is therefore **not** a curve with no points
         * in it, which is a fault this importer would otherwise refuse a real preset for.
         */
        private fun isCurve(node: KritaNode): Boolean =
            node.name == "curve" ||
                node.attr("type").equals("curve", ignoreCase = true) ||
                node.attr("commonCurve") != null ||
                node.attr("points") != null ||
                node.childrenNamed("point").isNotEmpty()

        /**
         * Decision 3: a curve parses to `[[x, y], …]` or **the brush is refused — never trimmed**.
         *
         * Both of Krita's shapes are read, because Krita has both: a `commonCurve="x,y;x,y;"` attribute
         * (the flat form, and the one Decision 3 names) and `<point x= y=/>` children (what
         * `QDomElement::toXML` writes). Every refusal below is a *shape or number* problem and each has its
         * own sentence, because "65 points", "an x outside 0..1" and "not two numbers" are three different
         * bugs in a person's file.
         *
         * The real preset's third shape — the points in a `<curve>` element's **text**, `0,0;1,1;` — goes
         * through [parsePairs], the very same parse the `commonCurve` attribute goes through, so the 64-point
         * cap and the 0..1 x rule are the same rules for all three and not three similar ones. **It is the
         * shape 718 of the 902 real curves are written in.**
         */
        private fun curvePoints(node: KritaNode): List<List<Float>> {
            val where = node.attr("name")?.let { "\"$it\"" } ?: node.name
            val raw = node.attr("commonCurve")?.takeIf { it.isNotBlank() }
                ?: node.attr("points")?.takeIf { it.isNotBlank() }
            node.claim("sensor", "name", "type", "commonCurve", "points", "min", "max", "value", "key")
            if (raw != null) return checkPoints(parsePairs(raw, where), where)
            val out = ArrayList<List<Float>>()
            for (point in node.childrenNamed("point")) {
                point.claim("x", "y")
                val x = point.attr("x")?.let { number(it, where, "x") }
                val y = point.attr("y")?.let { number(it, where, "y") }
                if (x == null || y == null) {
                    throw BrushException("its $where curve has a <point> with no x and y both")
                }
                out += listOf(x, y)
            }
            return checkPoints(out, where)
        }

        /**
         * Krita's own spelling of a curve — `0,0;0.5,0.8;1,1;` — as `[[x, y], …]`, and **never trimmed**: a
         * point that is not exactly two numbers, or an x outside 0..1, or a non-finite y, each refuses the
         * brush with its own sentence. One parse for the `commonCurve` attribute and for the `<curve>` element
         * text a real preset writes, so the rules are the same rules.
         */
        private fun parsePairs(raw: String, where: String): List<List<Float>> {
            val out = ArrayList<List<Float>>()
            for (pair in raw.trim().trimEnd(';').split(';')) {
                val body = pair.trim()
                if (body.isEmpty()) continue
                val bits = body.split(',')
                if (bits.size != 2) {
                    throw BrushException("its $where curve point \"$body\" is ${bits.size} numbers, not 2")
                }
                out += listOf(number(bits[0].trim(), where, "x"), number(bits[1].trim(), where, "y"))
            }
            return out
        }

        private fun checkPoints(points: List<List<Float>>, where: String): List<List<Float>> {
            if (points.isEmpty()) throw BrushException("its $where curve has no points")
            if (points.size > BrushValidate.MAX_CURVE_POINTS) {
                throw BrushException(
                    "its $where curve has ${points.size} points, at most ${BrushValidate.MAX_CURVE_POINTS}"
                )
            }
            for ((i, p) in points.withIndex()) {
                if (p[0] !in 0f..1f) {
                    throw BrushException("its $where curve point ${i + 1} has x = ${p[0]}, and x is 0..1")
                }
                if (!p[1].isFinite()) {
                    throw BrushException(
                        "its $where curve point ${i + 1} has y = ${p[1]}, which is not a number"
                    )
                }
            }
            return points
        }

        private fun number(text: String, where: String, which: String): Float {
            val v = text.toFloatOrNull()
            if (v == null || !v.isFinite()) {
                throw BrushException("its $where curve point has $which = \"$text\", which is not a number")
            }
            return v
        }

        /** A curve back in Krita's own spelling, which is what makes `extensions` lossless enough to be useful. */
        private fun List<List<Float>>.asCommonCurve(): String = joinToString(";") { "${it[0]},${it[1]}" }

        // ---- the settings that are not curves ---------------------------------------------------------

        /**
         * Every `<param>` of the document, keyed by its own `name`.
         *
         * **Why a map and not a walk.** The real format puts every setting in one element whose value is its
         * text, and the readers below ask for settings *by name* — `SizeSensor`, `brush_definition`,
         * `Texture/Pattern/Name` — so a map is the shape the format is, not a convenience. A `<param>` with
         * no `name` is skipped and left to the sweep, which keeps it under its own position.
         */
        private fun readSettings(): Map<String, KritaValue> {
            val out = LinkedHashMap<String, KritaValue>()
            for (node in root.childrenNamed("param")) {
                val name = node.attr("name")?.takeIf { it.isNotBlank() } ?: continue
                // Read now, so the sweep knows not to keep this element's character data a second time under
                // a key of its own. Its `type` and `name` attributes are read too, for the same reason.
                node.claim("type", "name")
                // **The one parameter whose value the sweep must not keep**, and the measurement is why: the
                // real `Texture/Pattern/Pattern` runs to 466 408 characters, which is 1.8 times the whole
                // 262 144-character extensions budget, so keeping it as a raw string would make a real brush
                // **unimportable** over a value [realPatternBytes] has already either stored or refused with a
                // reason. Its `type="bytearray"` is read here and it is base64 of base64 of a PNG.
                if (name == TEXTURE_PATTERN_PARAM) node.textClaimed = true
                out[name] = KritaValue(node.attr("type")?.lowercase() ?: "", node.text.trim())
            }
            return out
        }

        /** One `<param>`: the `type` Krita wrote and the value it wrote as the element's text. */
        private class KritaValue(val type: String, val text: String)

        private fun settings() {
            for (node in root.all()) {
                when (node.name) {
                    "spacing" -> {
                        val v = node.attr("value")?.toFloatOrNull()
                        node.claim("value", "mode", "type", "name")
                        if (v != null) {
                            // Krita's spacing is a fraction of the diameter and both spellings are in the
                            // wild: a `0.3` and a `30`. A value above 1 can only be a percentage — a spacing
                            // over one diameter is a dotted line, which no Krita preset is — and the
                            // interpretation is said rather than applied in silence.
                            val fraction = if (v > 1f) v / 100f else v
                            if (fraction < SPACING_MIN || fraction > SPACING_MAX) {
                                warn(
                                    "its spacing is $v, which reads as $fraction of the diameter and is " +
                                        "outside $SPACING_MIN..$SPACING_MAX; spacing falls back to " +
                                        "$DEFAULT_SPACING and the file's own value is kept raw"
                                )
                            } else {
                                spacing = fraction
                            }
                        }
                    }
                    "smoothing" -> {
                        val v = node.attr("value")?.toFloatOrNull()
                        node.claim("value", "mode", "type", "name")
                        if (v != null) smoothing = v.coerceIn(0f, 1f)
                    }
                    // `<param type="range" name="PixelSize" value="…"/>`, or the older
                    // `<pixel_size value="…"/>`. Read as **px**, because `KoPixelPaintOp::setPixelSize`
                    // takes px and the engine's Size slider runs 1..200. Both shapes are accepted because
                    // Krita writes both and they are the same setting.
                    "pixelsize", "pixel_size" -> {
                        val v = node.attr("value")?.toFloatOrNull()
                        node.claim("value", "type", "name", "min", "max", "mode")
                        if (v != null) pixelSize = v
                    }
                }
                if (node.name == "param" && node.attr("name")?.lowercase() in PIXEL_SIZE_NAMES) {
                    val v = node.attr("value")?.toFloatOrNull()
                    node.claim("value", "type", "name", "min", "max", "mode")
                    if (v != null) pixelSize = v
                }
            }
            applyRealSpacing()
        }

        /**
         * The real shape's one setting that is not a curve: **`spacing`**, which a real preset writes as an
         * **attribute of the `<Brush>` element inside `brush_definition`** and not as a `<spacing>` element.
         * `spacing="0.1"`, in 62 of the 64 real presets (the other two are the `deformbrush` and the
         * `experimentbrush`, which carry no `<Brush>` at all). `<spacing` as an element appears **0 times** in
         * the real pack, which is exactly why the landed reader found no spacing on any real brush and left
         * all 61 of them at `DEFAULT_SPACING`.
         *
         * **The value is read in [realTip], where the element is already parsed**, and this only decides what
         * to do with it. Re-parsing the fragment here to fish one attribute out of it would be a second walk
         * of a nested document *and* a regex over a stranger's bytes, and Decision 9's "no regex" is a rule
         * about exactly that.
         *
         * **`useAutoSpacing` is said about rather than reproduced.** 57 of the 62 carry it and 9 of those are
         * `1`, and when it is on Krita *computes* the spacing from `autoSpacingCoeff` and the brush's own
         * density. This build has not read that formula out of Krita's source, so the file's own `spacing`
         * is used — a real number, not a default — and the fact that Krita would have computed a different
         * one is said out loud. Nine of 61 real brushes, and a computed spacing is the difference between a
         * line and a dotted one.
         */
        private fun applyRealSpacing() {
            val v = realSpacing ?: return
            val fraction = if (v > 1f) v / 100f else v
            if (fraction < SPACING_MIN || fraction > SPACING_MAX) {
                warn(
                    "its spacing is $v, which reads as $fraction of the diameter and is outside " +
                        "$SPACING_MIN..$SPACING_MAX; spacing falls back to $DEFAULT_SPACING and the file's " +
                        "own value is kept in extensions[\"$TIP_SPACING_KEY\"]"
                )
                return
            }
            spacing = fraction
            if (realAutoSpacing) {
                warn(
                    "this brush has Krita's automatic spacing on, which Krita computes from a formula this " +
                        "build has not read; spacing is the $fraction the file itself states, not the value " +
                        "Krita would compute"
                )
            }
        }

        /**
         * Decision 4's order for `size.base`, written down: **the size sensor's own base, then the
         * `PixelSize` value, then [DEFAULT_DIAMETER_PX]** — and then, in [finish] and over all three, the
         * stored tip's own `IHDR` width, because R40 says the stored image never replaces the numbers.
         *
         * **Krita's own procedural size is kept in Krita's own unit and is NOT converted, and this is the one
         * mapping this row deliberately does not make.** `MaskGenerator@diameter` is 30 of the 64 real presets'
         * tip size and its values run `1`, `3`, `5`, `8`, `10`, `15`, `26.17`, `30`, `32`, `46`, `50`, `55`,
         * `56`, `60`, `84`, `101`, `102.38`, `600` — a brush Krita calls `deevad 5b pixel` states `1`, which
         * reads as one pixel; a 6-inch airbrush reads as `600` only if the unit is 1/100 inch, which would
         * make the same `1` a quarter of a millimetre. **The two readings differ by 96:1 and the bytes do not
         * say which is right**, and a `.kpp` states no document resolution either, so a px number would have
         * to come from a DPI this file would have to invent. Inventing it is the thing this row exists to
         * refuse, so the number is kept, its unit is named in the key, and the person is told what it is and
         * what it was not used for. It is a Question for the Lead, not a decision this row may take.
         *
         * **An image tip's own size is different and is used**: a tip's `IHDR` width is in pixels and
         * `<Brush scale=>` is a plain multiplier, so `width × scale` needs no unit and no resolution.
         */
        private fun resolveSize() {
            // The **first** size sensor's curve, not the last, and only in the flat shape: see [flatSizeTop]
            // for the measurement that makes the real shape's curve a multiplier rather than a diameter.
            val top = flatSizeTop
            var from = pixelSize
            if (top != null) {
                if (!(top > 0f) || top > BrushValidate.MAX_SIZE_PX) {
                    warn(
                        "its size curve tops out at $top px, which is not a diameter Joy Brush can paint " +
                            "(above 0 and at most ${BrushValidate.MAX_SIZE_PX}); size.base falls back to " +
                            "$DEFAULT_DIAMETER_PX and the file's own value is kept raw"
                    )
                } else {
                    from = top
                    // The size sensor won, so the `PixelSize` element is kept rather than read-and-dropped:
                    // `extensions` is lossless storage and a value nobody decided to lose is a value kept.
                    pixelSize?.let { extensions["krita.PixelSize"] = it.toString() }
                }
            }
            val v = from
            if (v == null) {
                // Krita's own size is said about even when nothing else set `size.base`, because it is the
                // one number a person notices first and the reason it was not used is a decision, not an
                // oversight. 30 of the 61 real brushes take this path.
                tipDiameter?.let { diameter ->
                    extensions[TIP_DIAMETER_KEY] = diameter
                    warn(
                        "this brush's own tip size is $diameter in Krita's unit, which a .kpp does not " +
                            "resolve to pixels and which this build will not guess at, so size.base is " +
                            "$sizeBase px and Krita's number is in extensions[\"$TIP_DIAMETER_KEY\"]"
                    )
                }
                return
            }
            tipDiameter?.let { diameter ->
                extensions[TIP_DIAMETER_KEY] = diameter
                warn(
                    "this brush's own tip size is $diameter in Krita's unit, which a .kpp does not resolve " +
                        "to pixels and which this build will not guess at; size is the ${v} px derived from " +
                        "its size curve and Krita's own number is in extensions[\"$TIP_DIAMETER_KEY\"]"
                )
            }
            if (!(v > 0f) || v > BrushValidate.MAX_SIZE_PX) {
                warn(
                    "its PixelSize is $v px, which is not a diameter Joy Brush can paint (above 0 and at " +
                        "most ${BrushValidate.MAX_SIZE_PX}); size.base falls back to $DEFAULT_DIAMETER_PX and " +
                        "the file's own value is kept raw"
                )
                return
            }
            sizeBase = v
        }

        // ---- Decision 12: the texture fork ------------------------------------------------------------

        /**
         * Krita's texture maps onto [BrushPreset.paperGrain], and there are exactly **two** cases, because a
         * `.kpp` on its own and a `.bundle` carry the texture differently. The branch is chosen by whether
         * the name resolves to an entry of *this* bundle, and both branches are right for their own input.
         */
        private fun texture() {
            val node = root.child("texture")
            if (node == null) {
                // A real preset has **no `<texture>` element at all** — `<texture ` appears 0 times in the
                // 64 real presets of David Revoy's Brushkit V8.2 — and hangs the texture off a dozen
                // `Texture/Pattern/…` parameters instead, 9 of which carry the pattern's own bytes.
                realTexture()
                return
            }
            // A texture the file has **switched off** is not a texture this brush carries: no name, no
            // grain, no R40 warning, because there is nothing kept and nothing left not drawn.
            if (node.attr("enabled")?.equals("false", ignoreCase = true) == true) {
                node.claim("enabled", "pattern", "blendmode", "mode", "depth", "scale", "type", "name",
                    "brushmodel", "patternFile", "invert", "index")
                return
            }
            val name = node.attr("pattern")?.takeIf { it.isNotBlank() }
                ?: node.attr("name")?.takeIf { it.isNotBlank() }
            val mode = node.attr("blendmode")?.takeIf { it.isNotBlank() }
                ?: node.attr("mode")?.takeIf { it.isNotBlank() }
            node.claim("enabled", "pattern", "name", "blendmode", "mode", "depth", "scale", "type",
                "brushmodel", "patternFile", "invert", "index")

            if (name == null) {
                // A texture with no pattern is a pattern this build has no name for. Keep whatever the file
                // did say, warn, and **leave `paperGrain.source` at "cloud"** — the spec's stop rule: do not
                // invent an image.
                if (mode != null) {
                    textureMode = mode
                    extensions[TEXTURE_MODE_KEY] = mode
                    warn(
                        "this brush names a texture mode ($mode) and no pattern, so there is nothing to " +
                            "keep; the mode is in extensions[\"$TEXTURE_MODE_KEY\"] and paperGrain stays a cloud"
                    )
                } else {
                    warn(
                        "this brush carries a <texture> with no pattern and no mode, so this build cannot " +
                            "say what it is; paperGrain is left off rather than given a made-up pattern"
                    )
                }
                return
            }
            textureName = name.substringAfterLast('/')
            textureMode = mode
            node.attr("depth")?.toFloatOrNull()?.let { textureDepth = it }
            node.attr("scale")?.toFloatOrNull()?.let { if (it > 0f && it <= MAX_GRAIN_SCALE) textureScale = it }

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

            // Branch one: the texture is kept **by name** and not drawn.
            extensions[TEXTURE_PATTERN_KEY] = textureName!!
            grainEnabled = mode != null && mode.lowercase() in PS_MODES
            // `paperGrain` has no mode field today, so the four Photoshop-compatible modes — the ones with
            // published formulas (R4 §A.5, from Krita's own `KisMaskingBrushCompositeOp.h`) — map to
            // `enabled` + `depth`, and every other mode is kept raw and named. **No `GrainSpec` field is
            // added here**: R31 makes a new serialised field a version bump and a validator change, and
            // grain belongs to JB-1.05c.
            //
            // **The mode is named in a warning either way**, which is Decision 12's "one naming the mode";
            // `extensions["krita.textureMode"]` is written **only** when the mode is not one of the four, so
            // a mode Joy Brush actually has a field for does not also end up as a raw setting.
            if (mode != null) {
                if (grainEnabled) {
                    warn(
                        "this brush's texture is a $mode pattern; Joy Brush's grain has no such mode, so a " +
                            "grain of depth $textureDepth stands in for it and the pattern is not drawn"
                    )
                } else {
                    extensions[TEXTURE_MODE_KEY] = mode
                    warn(
                        "this brush's texture is a $mode pattern, and Joy Brush's grain has no such mode; the " +
                            "mode is in extensions[\"$TEXTURE_MODE_KEY\"] and the grain is not drawn"
                    )
                }
            }
            // The reason a stored or deflated entry could not be read, phrased as what happened to **this
            // brush**: the entry itself was fine, and the bytes are the part that is missing. Every refusal
            // `KritaZip.read` raises names its own cause, so the cause is kept and the consequence is said
            // once, here.
            val because = if (grainUnreadable != null) {
                "$grainUnreadable, so its bytes could not be read out of the bundle, and"
            } else {
                "this brush's texture is the Krita pattern \"$name\", which is kept by name and"
            }
            warn(
                "$TEXTURE_NOT_DRAWN; $because it is in extensions[\"$TEXTURE_PATTERN_KEY\"] and is not drawn"
            )
        }

        /**
         * The real shape, and the three things it says that the `<texture>` element never did.
         *
         * **The pattern's own bytes are in the file**, in `<param type="bytearray"
         * name="Texture/Pattern/Pattern">` — 9 of the 64 real presets — so this is Decision 12's second branch
         * reached without a bundle at all. R40 therefore applies to a lone `.kpp` and not only to a
         * `.bundle`, which the spec did not anticipate: Decision 12's "a `.kpp` on its own, and most
         * `.bundle` files" says the opposite for a `.kpp`, and the bytes say otherwise.
         *
         * **The payload is base64 twice, and that is a measurement, not a guess.** Decoding the parameter once
         * yields 90–349 804 bytes of *base64 text*; decoding that again yields the PNG. All 9 of 9 need the
         * second decode and none of the 9 works with one. So the test is a **content test** — decode once, and
         * if what came out is not a PNG but *is* base64 that decodes to one, it was written twice — and a
         * payload that is neither is refused in words and kept by name, because a file called `grain.png` has
         * to hold a PNG.
         *
         * **The blend mode is a number, and this build does not know what the numbers mean.** The real
         * `Texture/Pattern/TexturingMode` is `0` or `1` (and absent for 2 of the 9), never a name. Joy Brush's
         * `GrainSpec` has no mode field and R31 makes adding one a version bump and a validator change, so the
         * number is kept under [TEXTURE_MODE_KEY] and **named in a warning** — which is Decision 12's rule for
         * a mode this build has no field for, arrived at by the same road. Mapping `0` and `1` to names would
         * be a claim about Krita's enum that this build has not read out of Krita's source, and it is a
         * Question for the Lead rather than a decision here.
         *
         * **`Enabled` is absent for 2 of the 9** — Krita simply does not write it when the texture is on — so
         * an absent `Enabled` is **on** and only an explicit `false` is off. Getting that backwards would drop
         * a real brush's paper.
         */
        private fun realTexture() {
            val name = settings["Texture/Pattern/Name"]?.text?.takeIf { it.isNotBlank() }
            val payload = settings["Texture/Pattern/Pattern"]?.text?.takeIf { it.isNotBlank() }
            if (name == null && payload == null) return
            if (settings["Texture/Pattern/Enabled"]?.text?.trim()?.equals("false", ignoreCase = true) == true) {
                // Switched off: no name kept, no grain, no R40 sentence, because nothing is kept and nothing
                // is left not drawn. The parameters are still swept, so the file's own values are not lost.
                return
            }
            if (name != null) {
                textureName = name.substringAfterLast('/')
                extensions[TEXTURE_PATTERN_KEY] = textureName!!
            }
            settings["Texture/Pattern/Scale"]?.text?.trim()?.toFloatOrNull()?.let {
                if (it > 0f && it <= MAX_GRAIN_SCALE) {
                    textureScale = it
                } else {
                    // The real `deevad 5c screentones` states `9999999`, which is 155 612 times `GrainSpec`'s
                    // own maximum of 64, so the guard fires on a real value and the file's number is kept.
                    extensions[TEXTURE_SCALE_KEY] = it.toString()
                    warn(
                        "this brush's texture scale is $it, which is outside 0..$MAX_GRAIN_SCALE, so the " +
                            "grain keeps scale $textureScale and the file's own number is in " +
                            "extensions[\"$TEXTURE_SCALE_KEY\"]"
                    )
                }
            }
            settings["Texture/Pattern/Strength"]?.text?.trim()?.toFloatOrNull()?.let {
                if (it > 0f) textureDepth = it.coerceAtMost(1f)
            }
            val mode = settings["Texture/Pattern/TexturingMode"]?.text?.trim()?.takeIf { it.isNotBlank() }
            if (mode != null) {
                extensions[TEXTURE_MODE_KEY] = mode
                warn(
                    "this brush's texture blend mode is Krita's number $mode, which this build has no name " +
                        "for and will not guess at; it is in extensions[\"$TEXTURE_MODE_KEY\"] and the grain " +
                        "is not drawn"
                )
            }

            if (payload != null) {
                val bytes = realPatternBytes(payload)
                if (bytes != null) {
                    // R40: the bytes are the grain, the name says the encoding, and the sentence is the one
                    // every importer says. `grainEnabled` is true because the file really does carry a texture,
                    // whatever the blend mode's number means.
                    grainStored = KritaStored(bytes, GRAIN_IMAGE_FILE, 0)
                    grainEnabled = true
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's texture is the Krita pattern " +
                            "\"${textureName ?: "(unnamed)"}\", ${bytes.size} bytes carried in the file itself " +
                            "and stored base64 in extensions[\"$GRAIN_IMAGE_KEY\"] for JB-1.05d, and a depth " +
                            "of $textureDepth stands in for it until then"
                    )
                    return
                }
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's texture is the Krita pattern " +
                        "\"${textureName ?: "(unnamed)"}\", which this file carries but which is not a PNG " +
                        "after one or two base64 decodes, so none of it is stored, the name is in " +
                        "extensions[\"$TEXTURE_PATTERN_KEY\"] and the grain is not drawn"
                )
                return
            }
            // A name and no bytes: Decision 12's first branch, reached from the real shape.
            warn(
                "$TEXTURE_NOT_DRAWN; this brush's texture is the Krita pattern " +
                    "\"${textureName ?: "(unnamed)"}\", which this file does not carry and which is kept by " +
                    "name in extensions[\"$TEXTURE_PATTERN_KEY\"] rather than drawn"
            )
        }

        /**
         * The pattern payload as PNG bytes, or null when it is not a PNG under one or two base64 decodes.
         *
         * **Each cap is checked before the decode that would build the string**, which is the whole of the
         * order: a 466 408-character payload is refused by arithmetic on its length rather than by allocating
         * its expansion and comparing afterwards.
         */
        private fun realPatternBytes(payload: String): ByteArray? {
            var text = payload
            for (attempt in 0..1) {
                if (text.length > MAX_BASE64_CHARS) {
                    warn(
                        "its texture pattern is ${text.length} base64 characters, and this build will not " +
                            "attempt more than $MAX_BASE64_CHARS, so the pattern is kept by name only"
                    )
                    return null
                }
                val bytes = try {
                    kritaBase64Decode(text, "its texture pattern")
                } catch (e: BrushException) {
                    return null
                }
                if (isPngSignature(bytes, 0)) return bytes
                if (attempt == 1) return null
                text = bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
            }
            return null
        }

        // ---- everything the mapping did not read -------------------------------------------------------

        /**
         * **Nothing is silent.** Every attribute **and every value** of every element that no reader above
         * *claimed* is kept under its own path in `extensions` — which is what `extensions` is documented to
         * be, *"lossless storage of imported raw settings"* — and the brush is told once what was kept and
         * what was not.
         *
         * **The value half is not optional, and it is the half that was missing.** The landed version walked
         * `node.attributes` and never read `KritaNode.text`, so for a real preset — where 7 256 of the
         * settings are character data inside `CDATA` sections — it kept the *shape* of the settings and threw
         * the settings away, while its own KDoc promised that every attribute of every element would be kept
         * and the warning it emitted said "kept raw in extensions". **`deevad 4a airbrush`'s 8 678-character
         * settings produced a 20 px brush with no curve, no spacing and no texture, and a warning that
         * claimed otherwise.** The warning text is fixed with it, below.
         *
         * **The key has to identify the element, or the map is a shredder.** `<param name=…>` repeats 155
         * times in one real preset, so a key of the element's name alone keeps exactly one of them and
         * silently loses 154. A node's path segment is therefore its element name, and its `name` attribute in
         * brackets when it has one: `krita.Preset.param[Texture/Pattern/Name]`. **Measured: the 64 real
         * presets carry 32 to 155 distinct parameter names and no name twice in any one of them**, so this
         * loses nothing on any of them.
         *
         * **Bounded three ways, and every bound says so rather than truncating silently.** A value over
         * [MAX_RAW_VALUE_CHARS] is shortened with its full length written into the value; a sweep that would
         * pass the shared extensions cap stops keeping and says how many values it did not keep; and the
         * whole-brush refusal at [finish] then cannot be reached by a sweep of its own. **The measurement that
         * makes the second bound necessary rather than theoretical: the largest real preset's kept values come
         * to 3 872 characters against the 262 144 shared cap, a factor of 68 of headroom, and the one value
         * that would have blown it — the 466 408-character texture pattern — is claimed by
         * [realPatternBytes] instead.** The bound is checked *before* each value is added, so a hostile
         * document cannot make this loop allocate its way past the cap.
         */
        private fun sweep() {
            // What the readers above have already put in `extensions`, in the same characters `finish` counts.
            var total = extensionChars()
            var dropped = 0
            fun keep(at: String, value: String): Boolean {
                val stored = if (value.length <= MAX_RAW_VALUE_CHARS) {
                    value
                } else {
                    value.take(MAX_RAW_VALUE_CHARS) + "…(+${value.length} more characters)"
                }
                if (total + stored.length > MAX_EXTENSION_BYTES) {
                    dropped++
                    return false
                }
                total += stored.length
                extensions[at] = stored
                rawKept += at
                return true
            }
            fun walk(node: KritaNode, path: String) {
                val own = "$path.${node.segment()}"
                for ((key, value) in node.attributes) {
                    if (key in node.claimedKeys()) continue
                    keep("$own.$key", value)
                }
                // The value, which is the half the real format puts every setting in.
                val text = node.text.trim()
                if (text.isNotEmpty() && !node.textClaimed) keep(own, text)
                for (child in node.children) walk(child, own)
            }
            walk(root, "krita.${root.name}")
            if (rawKept.isEmpty()) return
            // The one sweep-wide statement, and every clause of it is a fact about what the walk above did.
            val named = rawKept.distinct().take(8).joinToString(", ")
            val rest = if (rawKept.size > 8) " and ${rawKept.size - 8} more" else ""
            val shortened = rawKept.count { extensions[it]?.contains("more characters") == true }
            warn(
                "kept raw in extensions: $named$rest — every Krita setting this build has no Joy Brush " +
                    "setting for, by attribute name and by value, under each element's own path" +
                    if (shortened > 0) {
                        "; $shortened of them is over $MAX_RAW_VALUE_CHARS characters and is shortened, with " +
                            "its full length in the value"
                    } else {
                        ""
                    } +
                    if (dropped > 0) {
                        ", and $dropped more were not kept at all because keeping them would have passed " +
                            "the $MAX_EXTENSION_BYTES-character extensions cap"
                    } else {
                        ""
                    }
            )
        }

        /**
         * One node's path segment: its element name, plus its own `name` attribute in brackets when it has
         * one, so that 155 `<param>` elements become 155 keys rather than one.
         */
        private fun KritaNode.segment(): String {
            val named = attr("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return name
            return "$name[$named]"
        }

        // ---- finish -------------------------------------------------------------------------------------

        private fun finish(name: String, id: String): ImportResult {
            var tip = TipSpec(
                aspect = aspect,
                corner = 2f,
                angle = Param(0f, angleInputs),
                hardness = Param(tipHardnessBase, tipHardnessInputs),
            )
            // R40's store, written now that the extensions budget is known. Over the cap the image is dropped
            // **whole**: a prefix of an image is a file that is not the thing it says it is, so `tip.source`
            // stays `"procedural"` and nothing downstream believes there is a picture.
            var size = sizeBase
            storedTip?.let { stored ->
                // **The cap is checked before the encoder runs, and that order is the fix.** The landed code
                // built the whole base64 string and only then compared its length, so a tip this build is
                // going to refuse — `MAX_ENTRY_BYTES` permits a 32 MiB entry, whose base64 is 42.7 Mi
                // characters — cost a StringBuilder and a String of that size to be thrown away. The
                // **length of the base64 is arithmetic on the input** (`ceil(n / 3) × 4`), so the question
                // "would this fit?" is answerable without building it, and is now.
                val chars = base64LengthOf(stored.bytes.size)
                if (extensionChars() + chars > MAX_EXTENSION_BYTES) {
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's tip is a ${stored.bytes.size}-byte PNG, which is " +
                            "$chars base64 characters, over the $MAX_EXTENSION_BYTES-character extensions " +
                            "cap, so none of it is stored: the tip stays procedural and size.base stays " +
                            "${sizeBase} px"
                    )
                    return@let
                }
                val b64 = kritaBase64Of(stored.bytes)
                extensions[TIP_IMAGE_KEY] = b64
                tip = tip.copy(source = "image", image = stored.fileName)
                // R40's "the stored image never replaces the numbers", with the real format's own factor: a
                // `png_brush`'s painted size is its `IHDR` width times the `<Brush scale=…>` it states, and
                // both are in the same unit — pixels and a plain multiplier — so this needs no resolution and
                // invents nothing. The 12 real image tips scale to 11.06, 30.72, 40, 62.76, 112, 150, 3754.07
                // and 454 px, all of them inside `BrushValidate.MAX_SIZE_PX`.
                val scaled = stored.width * tipScale
                if (scaled > 0f && scaled <= BrushValidate.MAX_SIZE_PX) {
                    size = scaled
                } else {
                    size = stored.width.toFloat()
                    warn(
                        "this brush's tip is ${stored.width} px wide and states a scale of $tipScale, which " +
                            "is $scaled px — not a diameter Joy Brush can paint (above 0 and at most " +
                            "${BrushValidate.MAX_SIZE_PX}) — so size.base is ${stored.width} px, the tip's " +
                            "own width, and the scale is in extensions[\"$TIP_SCALE_KEY\"]"
                    )
                }
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's tip is a ${stored.bytes.size}-byte PNG, stored as " +
                        "${b64.length} base64 characters in extensions[\"$TIP_IMAGE_KEY\"] for JB-1.05d. " +
                        "size.base is $size px, the tip's own ${stored.width} px width at the scale the file " +
                        "states, so the brush paints at the right size today and the bitmap is extra"
                )
            }

            var paperGrain = GrainSpec(
                enabled = grainEnabled,
                scale = textureScale,
                depth = Param(textureDepth),
            )
            grainStored?.let { stored ->
                // The same order as the tip: **arithmetic first, encoder second.** See the tip branch for why,
                // and it matters more here — a real texture pattern reaches 262 353 decoded bytes, whose base64
                // is 349 804 characters, so this is a case that really is over the cap and not a hypothetical.
                val chars = base64LengthOf(stored.bytes.size)
                if (extensionChars() + chars > MAX_EXTENSION_BYTES) {
                    // Over the cap the grain is left **off** rather than left on with no picture behind it,
                    // because a grain that claims an image it does not have is the stronger claim.
                    paperGrain = paperGrain.copy(enabled = false)
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's grain is the ${stored.bytes.size}-byte pattern " +
                            "\"${extensions[TEXTURE_PATTERN_KEY]}\", which is $chars base64 characters, over " +
                            "the $MAX_EXTENSION_BYTES-character extensions cap, so none of it is stored: " +
                            "paperGrain.source stays cloud and the grain stays off"
                    )
                    return@let
                }
                val b64 = kritaBase64Of(stored.bytes)
                extensions[GRAIN_IMAGE_KEY] = b64
                paperGrain = paperGrain.copy(enabled = true, source = "image", image = stored.fileName)
                warn(
                    "$TEXTURE_NOT_DRAWN; this brush's grain is the Krita pattern " +
                        "\"${extensions[TEXTURE_PATTERN_KEY]}\", ${stored.bytes.size} bytes stored as " +
                        "${b64.length} base64 characters in extensions[\"$GRAIN_IMAGE_KEY\"] for JB-1.05d, " +
                        "and a depth of $textureDepth stands in for it until then"
                )
            }

            val characters = extensionChars()
            if (characters > MAX_EXTENSION_BYTES) {
                throw BrushException(
                    "its settings come to $characters characters of extensions, at most " +
                        "$MAX_EXTENSION_BYTES; the whole brush is dropped rather than half-kept"
                )
            }

            val preset = BrushPreset(
                id = id,
                name = name,
                engine = engine,
                tip = tip,
                size = Param(size, sizeInputs),
                opacity = Param(1f, opacityInputs),
                flow = Param(1f, flowInputs),
                spacing = spacing,
                paperGrain = paperGrain,
                scatter = ScatterSpec(Param(scatterAmount, scatterInputs), scatterCount, scatterCountJitter),
                smoothing = smoothing,
                // Decision 14, R44 item 5, blueprint §5. **The licence is only ever what the file states** —
                // a lone `.kpp` states none, so that is `"unknown"`, and a bundle's `meta.xml` is the one
                // place one can come from. **Never `"CC0"`**, which is `BrushPreset`'s own default and a claim
                // about somebody else's work that nobody in this file made; the real pack is
                // `CC-By with custom rules for redistribution`, which *requires* attribution, so "unknown"
                // on it was a legal defect and not a cosmetic one. **The author is the file's own first** and
                // the bundle's second, because the file's is per-brush and the bundle's is per-package.
                // Where a brush detail sheet shows either is not this row's business (R40).
                license = meta?.license?.takeIf { it.isNotBlank() } ?: "unknown",
                author = ownAuthor?.takeIf { it.isNotBlank() }
                    ?: meta?.author?.takeIf { it.isNotBlank() }
                    ?: "",
                sourceFormat = "kpp",
                extensions = extensions,
            )
            // Everything above has already been clamped or refused, so this should always be empty. If it is
            // not, the *mapping* is wrong, and the house rule is to refuse in words rather than ship a brush
            // the validator will reject the moment it is saved.
            val problems = BrushValidate.validate(preset)
            if (problems.isNotEmpty()) {
                throw BrushException("it would not be a legal brush: " + problems.joinToString("; "))
            }
            return ImportResult(preset, warnings)
        }

        // ---- small helpers -----------------------------------------------------------------------------

        private fun extensionChars(): Int {
            var total = 0
            for (v in extensions.values) total += v.length
            return total
        }

        private fun warn(message: String) {
            warnings += message
        }
    }

    // ---- the XML (Decision 9) -----------------------------------------------------------------------

    /**
     * One element of a document, and **the claim set** that keeps [KritaBrush]'s sweep from re-keeping what
     * the mapping already read.
     *
     * A claim is a *key*, not a node, so a reader claims the attributes it used and everything else on the
     * same element is still kept. The alternative — listing the attributes this importer *knows* about —
     * means every Krita version that adds one silently loses it, which is the failure this row exists to
     * prevent, in miniature.
     */
    private class KritaNode(
        val name: String,
        val attributes: LinkedHashMap<String, String>,
        val children: List<KritaNode>,
        /** Character data and `CDATA` directly inside this element, entities already resolved. */
        val text: String = "",
    ) {
        private val claimed = HashSet<String>()

        /**
         * Set when a reader has used this element's **character data**, so the sweep does not keep it a
         * second time under a key of its own.
         *
         * **A claim is on an attribute's *name* and this one is on the text**, because a claim cannot be on a
         * thing that is not a map entry, and the real format puts its settings in the text. It exists for one
         * measured reason: the real `Texture/Pattern/Pattern` parameter runs to 466 408 characters, which is
         * 1.8 times the whole 262 144-character extensions budget, so a sweep that kept it would make a real
         * brush **unimportable** over a value the reader had already either stored or refused with a reason.
         */
        var textClaimed: Boolean = false

        fun attr(key: String): String? = attributes[key]

        fun claim(vararg keys: String) {
            claimed += keys
        }

        fun claimedKeys(): Set<String> = claimed

        fun child(lower: String): KritaNode? = children.firstOrNull { it.name == lower }

        fun childrenNamed(lower: String): List<KritaNode> = children.filter { it.name == lower }

        fun all(): List<KritaNode> = listOf(this) + children.flatMap { it.all() }

        override fun toString(): String = "<$name>"
    }

    /**
     * A hand-written XML reader. **Decision 9, and every word of it is load-bearing:** a `.kpp` is a
     * stranger's file, and XXE is the first thing that comes to mind.
     *
     * So: **no DTD, no external entity, no network, no regex.** A `<!DOCTYPE` or `<!ENTITY` is refused
     * **before a single character of it is looked at**, and so is any other `<!` that is not a comment or a
     * `CDATA`. Entities are exactly `&amp; &lt; &gt; &quot; &apos; &#NNN;` / `&#xHH;`; **anything else is
     * left as text**, which is the spec's rule and the safe one — a file that writes `&nbsp;` gets a literal
     * `&nbsp;` in a brush name, and a file that writes an external entity gets a literal one too.
     *
     * A depth, a node count and an attribute count are all budgets, and all three are checked on the way in
     * rather than after the tree exists.
     */
    private class KritaXml(
        private val text: String,
        private val what: String,
        /**
         * May this document carry a **bare** `<!DOCTYPE name>`? **True only for a fragment that came out of
         * a `CDATA` section of a document this reader has already parsed**, and never for a file's own root.
         *
         * **It has to be allowed at all, and this is the measurement that says so:** every one of the 902
         * nested documents in the 64 real presets of David Revoy's Brushkit V8.2 — every `<params>` sensor
         * and every `<Brush>` tip definition — begins with exactly `<!DOCTYPE params>`. A reader that refuses
         * every DOCTYPE refuses every sensor and every tip in the pack.
         *
         * **And what is *not* allowed is what makes this safe rather than a hole.** [skipBareDoctype]
         * accepts one name and a `>`, and refuses an internal subset (`[`, which is where an entity
         * definition lives) and an external id (`SYSTEM`, `PUBLIC`) **before reading a character of them**.
         * Decision 9's promise — a DOCTYPE is refused before it is looked at — therefore still holds
         * **outright for a stranger's own file**, which is parsed with this false, and the only relaxation
         * is in a place where the bytes have already survived the outer parse. There is no DTD subset to
         * resolve and no system id to fetch, so there is nothing to resolve and nothing to fetch.
         */
        private val bareDoctype: Boolean = false,
    ) {

        private var at = 0
        private var nodes = 0
        private var depth = 0

        fun document(): KritaNode {
            skipMisc()
            if (at >= text.length) bad("is empty")
            val root = element()
            skipMisc()
            if (at < text.length) bad("has content after its root element <${root.name}>")
            return root
        }

        private fun element(): KritaNode {
            if (at >= text.length || text[at] != '<') bad("expected an element")
            at++
            val name = readName("an element")
            if (++nodes > MAX_XML_NODES) {
                throw BrushException("$what holds at most $MAX_XML_NODES XML nodes, and this one has more")
            }
            if (++depth > MAX_XML_DEPTH) {
                throw BrushException(
                    "$what nests elements $MAX_XML_DEPTH deep at most, and this one goes deeper at <$name>"
                )
            }
            val attributes = LinkedHashMap<String, String>()
            try {
                skipSpace()
                while (at < text.length && text[at] != '>' && !text.startsWith("/>", at)) {
                    val key = readName("an attribute of <$name>")
                    skipSpace()
                    if (at >= text.length || text[at] != '=') bad("<$name>'s \"$key\" has no \"=\"")
                    at++
                    skipSpace()
                    if (attributes.size + 1 > MAX_XML_ATTRS_PER_NODE) {
                        throw BrushException(
                            "<$name> carries at most $MAX_XML_ATTRS_PER_NODE attributes, and this one has more"
                        )
                    }
                    val value = quoted(key)
                    if (attributes.put(key, value) != null) bad("<$name>'s \"$key\" appears twice")
                    skipSpace()
                }
                if (text.startsWith("/>", at)) {
                    at += 2
                    return KritaNode(name, attributes, emptyList())
                }
                if (at >= text.length || text[at] != '>') bad("<$name> is never closed")
                at++

                val children = ArrayList<KritaNode>()
                // `body` and not `text`: the document is the field `text`, and shadowing it here would be a
                // bug the compiler cannot see and the next reader would spend an hour on.
                val body = StringBuilder()
                while (true) {
                    if (at >= text.length) bad("<$name> is never closed")
                    if (text[at] != '<') {
                        val start = at
                        while (at < text.length && text[at] != '<') at++
                        body.append(unescape(text.substring(start, at)))
                        continue
                    }
                    when {
                        text.startsWith("</", at) -> {
                            closeTag(name)
                            return KritaNode(name, attributes, children, body.toString())
                        }
                        text.startsWith("<!--", at) -> skipComment()
                        text.startsWith(CDATA_OPEN, at) -> {
                            val from = skipCdata()
                            // `at` is now past the closing `]]>`, so the text ends `CDATA_CLOSE` back. Both
                            // offsets come out of [skipCdata] together precisely so they cannot be swapped.
                            body.append(text.substring(from, at - CDATA_CLOSE))
                        }
                        text.startsWith("<!", at) -> bad(
                            "holds a <! declaration, which is refused before it is read: a DOCTYPE or an " +
                                "entity definition in a stranger's file is the first thing that comes to mind"
                        )
                        text.startsWith("<?", at) -> bad("holds a processing instruction inside <$name>")
                        else -> children += element()
                    }
                }
            } finally {
                depth--
            }
        }

        private fun closeTag(expected: String) {
            at += 2
            val name = readName("a closing tag")
            skipSpace()
            if (at >= text.length || text[at] != '>') bad("</$name> is never closed")
            at++
            if (name != expected) bad("</$name> closes <$expected>")
        }

        private fun quoted(key: String): String {
            if (at >= text.length) bad("\"$key\" has no value")
            val q = text[at]
            if (q != '"' && q != '\'') bad("\"$key\" is not quoted; XML requires \" or '")
            at++
            val start = at
            while (at < text.length && text[at] != q) {
                if (text[at] == '<') bad("\"$key\" holds a \"<\"")
                at++
            }
            if (at >= text.length) bad("\"$key\" is never closed")
            val raw = text.substring(start, at)
            at++
            return unescape(raw)
        }

        private fun skipMisc() {
            while (at < text.length) {
                when {
                    text[at].isWhitespace() -> at++
                    text.startsWith("<?", at) -> {
                        val end = text.indexOf("?>", at)
                        if (end < 0) bad("its \"<?\" declaration is never closed")
                        at = end + 2
                    }
                    text.startsWith("<!--", at) -> skipComment()
                    text.startsWith("<!DOCTYPE", at) || text.startsWith("<!ENTITY", at) -> {
                        if (!bareDoctype) {
                            bad("holds a DOCTYPE or an ENTITY declaration, which is refused before it is read")
                        }
                        if (text.startsWith("<!ENTITY", at)) {
                            bad("holds an ENTITY declaration, which is refused before it is read")
                        }
                        skipBareDoctype()
                    }
                    text.startsWith("<!", at) -> bad("holds a <! declaration, which is refused")
                    else -> return
                }
            }
        }

        /**
         * Past a `<!DOCTYPE name>` and nothing else — the exact form all 902 nested fragments of the real
         * pack use — with **a subset or an external id refused before a character of it is read**.
         *
         * The order is the safe one: the name is read (a name is a name, and it can only be used in a refusal
         * sentence), and then the *next* thing is examined before anything is skipped past. A document that
         * wrote `<!DOCTYPE x [ <!ENTITY y SYSTEM "file:///etc/passwd"> ]>` stops here, at the `[`, having read
         * no entity and fetched nothing.
         */
        private fun skipBareDoctype() {
            at += CDATA_DOCTYPE.length          // "<!DOCTYPE"
            skipSpace()
            val name = readName("the DOCTYPE's own name")
            skipSpace()
            if (at < text.length && text[at] == '[') {
                bad("holds the DOCTYPE <!DOCTYPE $name [ … ]>, whose internal subset is refused before it is read")
            }
            if (text.startsWith("SYSTEM", at) || text.startsWith("PUBLIC", at)) {
                bad("holds the external DOCTYPE <!DOCTYPE $name ${
                    text.substring(at, minOf(text.length, at + 6))
                }…>, which is refused before it is read")
            }
            if (at >= text.length || text[at] != '>') bad("its <!DOCTYPE $name> is never closed")
            at++
        }

        private fun skipComment() {
            val end = text.indexOf("-->", at + 4)
            if (end < 0) bad("its comment is never closed")
            at = end + 3
        }

        /**
         * Past a `CDATA` section, **returning the offset its text started at**, for [KritaNode.text].
         *
         * The two go out together and the caller reads `text.substring(start, at - 3)`, which cannot get the
         * ends the wrong way round. **That is the whole fix, and the bug it replaces was total:** this
         * function used to return the *closing* `]]>` offset while leaving `at` three further on, so the
         * caller evaluated `text.substring(at + 9, end)` = `text.substring(end + 12, end)` — a begin past its
         * own end — and **every** `CDATA` section raised `StringIndexOutOfBoundsException` out of
         * [convertKpp] and [convertBundle], whose KDoc promises a [BrushException]. 61 of the 61 real presets
         * in David Revoy's Brushkit V8.2 (`testdata-local/`, git-ignored) contain `CDATA`, so this was every
         * real `.kpp` and every real `.bundle`: the row converted nothing because it could not finish.
         *
         * **An unclosed section was never the problem and still refuses in words** ([bad] below), which is
         * the other half of the contract and is pinned by its own test.
         */
        private fun skipCdata(): Int {
            val from = at + CDATA_OPEN.length
            val end = text.indexOf(CDATA_CLOSE_TEXT, from)
            if (end < 0) bad("its CDATA section is never closed")
            at = end + CDATA_CLOSE
            return from
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun readName(what: String): String {
            val start = at
            if (at < text.length && (text[at].isLetter() || text[at] == '_' || text[at] == ':')) at++
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] in "_.:-")) at++
            if (at == start) bad("$what has no name")
            return text.substring(start, at)
        }

        private fun bad(detail: String): Nothing = throw BrushException("$what $detail")

        /** The five named entities and a numeric one. **Anything else is left as text**, by Decision 9. */
        private fun unescape(raw: String): String {
            if ('&' !in raw) return raw
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '&') {
                    sb.append(c)
                    i++
                    continue
                }
                val semi = raw.indexOf(';', i + 1)
                if (semi < 0) {
                    sb.append(c)
                    i++
                    continue
                }
                val entity = raw.substring(i + 1, semi)
                val replacement = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#") -> numericEntity(entity)
                    else -> null
                }
                if (replacement == null) {
                    sb.append(c)
                    i++
                    continue
                }
                sb.append(replacement)
                i = semi + 1
            }
            return sb.toString()
        }

        /**
         * `&#NNN;` or `&#xHH;`, and **only** a code point XML can carry: not NUL, not a lone surrogate, not
         * above `0x10FFFF`. Anything else returns null, which leaves the reference as literal text — the
         * same treatment an unknown entity name gets, and for the same reason: a stranger's file does not get
         * to inject a character Joy Brush's own strings cannot hold.
         */
        private fun numericEntity(entity: String): String? {
            val body = entity.substring(1)
            val code = if (body.startsWith("x") || body.startsWith("X")) {
                body.substring(1).toIntOrNull(16)
            } else {
                body.toIntOrNull()
            } ?: return null
            if (code <= 0 || code in 0xD800..0xDFFF || code > 0x10FFFF) return null
            return code.toChar().toString()
        }
    }

    /**
     * The settings document is parsed with `bareDoctype = false` — a stranger's file may not declare
     * anything — and a **nested** value with it true, because a nested value is character data that has
     * already been inside a parsed document. See [KritaXml]'s own KDoc for the measurement.
     */
    private fun parseKritaXml(text: String, what: String, bareDoctype: Boolean = false): KritaNode =
        KritaXml(text, what, bareDoctype).document()

    // ---- the zip of a `.bundle` ----------------------------------------------------------------------

    /**
     * Just enough of a zip to walk a `.bundle`. **It reads both STORED and DEFLATE entries** — R44 item 2
     * reversed JB-8.04's Decision 5, and a `.bundle` is unreadable without this — and it inflates through
     * the *shared* `inflateRaw`, under the *shared* caps, which is the whole point of the reversal. It has
     * its own central-directory walk rather than reusing `ProcreateImport`'s because that one is a
     * `private class Zip` in another file and this row's budgets are this reader's.
     *
     * **One thing this deliberately does not do, said here rather than discovered later: it does not verify
     * a CRC** (a wrong CRC in a pattern PNG is a pattern nobody will see, and an importer that refuses a
     * whole pack over one entry's CRC is a worse tool). That decision is inherited, not revisited, and
     * whether a CRC is now *checkable* — a DEFLATE entry's bytes are in memory and its CRC is in the
     * central directory — is in the report's Questions.
     */
    private class KritaZip private constructor(
        private val data: ByteArray,
        val entries: List<Entry>,
    ) {
        class Entry(
            val name: String,
            val method: Int,
            val compressedSize: Int,
            val uncompressedSize: Int,
            val localOffset: Int,
        )

        fun find(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

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

        companion object {
            const val STORED = 0
            const val DEFLATED = 8
            private const val LOCAL_HEADER = 30
            private const val LOCAL_SIGNATURE = 0x04034b50L
            private const val CENTRAL_SIGNATURE = 0x02014b50L
            private const val END_SIGNATURE = 0x06054b50L
            private const val END_SIZE = 22
            private const val CENTRAL_SIZE = 46
            private const val ZIP64 = 0xFFFFFFFFL

            fun read(bytes: ByteArray): KritaZip {
                if (bytes.size > KritaImport.MAX_FILE_BYTES) {
                    throw BrushException(
                        "this .bundle is ${bytes.size} bytes, at most ${KritaImport.MAX_FILE_BYTES}"
                    )
                }
                if (bytes.size < END_SIZE) {
                    throw BrushException(
                        "this file is ${bytes.size} bytes, too short to be a zip: a zip ends with a $END_SIZE-" +
                            "byte directory record"
                    )
                }
                // The end record is at the very end unless the archive has a comment, which is 0..65535
                // bytes, so the search is the last 65 557 bytes backwards and stops at the first hit.
                val earliest = maxOf(0, bytes.size - (END_SIZE + 0xFFFF))
                var end = -1
                for (i in bytes.size - END_SIZE downTo earliest) {
                    if (KritaImport.le32(bytes, i, "the end of the zip directory") == END_SIGNATURE) {
                        end = i
                        break
                    }
                }
                if (end < 0) {
                    throw BrushException(
                        "this file is not a zip: it has no end-of-directory record, so it is not a .bundle " +
                            "this build can open"
                    )
                }
                val total = KritaImport.le16(bytes, end + 10, "the entry count")
                val directoryAt = KritaImport.le32(bytes, end + 16, "the directory offset")
                if (total == 0xFFFF || directoryAt == ZIP64) {
                    throw BrushException("this .bundle needs the Zip64 extensions, which this build does not read")
                }
                if (total > KritaImport.MAX_ENTRIES) {
                    throw BrushException(
                        "this .bundle holds $total entries, at most ${KritaImport.MAX_ENTRIES}"
                    )
                }
                if (directoryAt > Int.MAX_VALUE || directoryAt + CENTRAL_SIZE > bytes.size) {
                    throw BrushException("this .bundle's directory starts at $directoryAt, outside the file")
                }
                val entries = ArrayList<Entry>(total)
                var at = directoryAt.toInt()
                for (n in 0 until total) {
                    if (KritaImport.le32(bytes, at, "directory entry ${n + 1}") != CENTRAL_SIGNATURE) {
                        throw BrushException(
                            "this .bundle's directory entry ${n + 1} has no directory signature"
                        )
                    }
                    val method = KritaImport.le16(bytes, at + 10, "the method of entry ${n + 1}")
                    val compressed = KritaImport.le32(bytes, at + 20, "the stored size of entry ${n + 1}")
                    val uncompressed = KritaImport.le32(bytes, at + 24, "the expanded size of entry ${n + 1}")
                    val nameLength = KritaImport.le16(bytes, at + 28, "the name length of entry ${n + 1}")
                    val extraLength = KritaImport.le16(bytes, at + 30, "the extra length of entry ${n + 1}")
                    val commentLength = KritaImport.le16(bytes, at + 32, "the comment length of entry ${n + 1}")
                    val local = KritaImport.le32(bytes, at + 42, "the offset of entry ${n + 1}")
                    if (compressed == ZIP64 || uncompressed == ZIP64 || local == ZIP64) {
                        throw BrushException(
                            "this .bundle needs the Zip64 extensions, which this build does not read"
                        )
                    }
                    if (compressed > Int.MAX_VALUE || uncompressed > Int.MAX_VALUE || local > Int.MAX_VALUE) {
                        throw BrushException("this .bundle's entry ${n + 1} is larger than this build can hold")
                    }
                    val nameAt = at + CENTRAL_SIZE
                    if (nameAt + nameLength > bytes.size) {
                        throw BrushException("this .bundle's entry ${n + 1} runs past the end of the file")
                    }
                    entries += Entry(
                        KritaImport.kritaLatin1(bytes, nameAt, nameLength),
                        method,
                        compressed.toInt(),
                        uncompressed.toInt(),
                        local.toInt(),
                    )
                    at = nameAt + nameLength + extraLength + commentLength
                }
                return KritaZip(bytes, entries)
            }
        }
    }

    private fun le16(bytes: ByteArray, at: Int, what: String): Int {
        if (at < 0 || at + 2 > bytes.size) {
            throw BrushException("this .bundle is truncated: $what needs 2 bytes at $at")
        }
        return (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(bytes: ByteArray, at: Int, what: String): Long {
        if (at < 0 || at + 4 > bytes.size) {
            throw BrushException("this .bundle is truncated: $what needs 4 bytes at $at")
        }
        var v = 0L
        for (i in 0 until 4) v = v or ((bytes[at + i].toInt() and 0xFF).toLong() shl (8 * i))
        return v
    }

    /**
     * Decision 13's segment rule. **A restatement, not a copy:** `JbArchive.unsafeReason` is `private` and
     * lives in `androidkit`, and `ProcreateImport`'s is `private` in that file, so neither is reachable from
     * `commonMain`. The comment naming the other two copies is what makes this honest, and all three have to
     * change together.
     *
     * It is a **segment** rule and not a substring test, because a substring test for `".."` catches none of
     * the three cases that actually climb out: `".. "`, `"a/./b"` and `"a/ /../b"`. Each is caught here
     * because the test is on each segment after `trimEnd(' ', '.')`.
     */
    private fun unsafeReason(name: String): String? {
        if (name.isEmpty()) return "its name is empty"
        if (name.length > MAX_ENTRY_NAME_CHARS) {
            return "its name is ${name.length} characters, at most $MAX_ENTRY_NAME_CHARS"
        }
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7F) return "its name holds a control character"
        }
        if (name.contains('\\')) return "its name holds a backslash"
        if (name.startsWith("/")) return "its name starts with a slash"
        if (name.contains(':')) return "its name holds a colon"
        for (segment in name.split('/')) {
            if (segment.isEmpty()) return "its name holds an empty path segment"
            val trimmed = segment.trimEnd(' ', '.')
            if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
                return "its path segment \"$segment\" climbs out of the archive"
            }
        }
        return null
    }

    // ---- base64 (RFC 4648 §4), and this row needs a *pair* ------------------------------------------------

    /**
     * The standard alphabet `A–Z a–z 0–9 + /` with `=` padding. Derived, not recalled: three input bytes are
     * 24 bits and each of the four output characters takes six, so the four indices are `(n >> 18) & 63`,
     * `(n >> 12) & 63`, `(n >> 6) & 63` and `n & 63` for `n` the three bytes big-endian. A trailing group of
     * one byte pads `==` and of two bytes pads `=`.
     *
     * `AbrImport.base64Of` and `ProcreateImport.procreateBase64Of` are the same nine lines twice already,
     * and R23 says a number written three times is one writer believing it three times. **This is the third
     * copy** and it should not be: the three are all `private` in their own files, so the honest fix is one
     * `internal fun` beside the shared UTF-8 decoder in `PngChunks.kt` — and that file is not in this row's
     * owner area. It is in the report's Questions.
     */
    /**
     * **How many characters [kritaBase64Of] will produce for [bytes] bytes, without producing them.**
     *
     * RFC 4648 §4: three input bytes are 24 bits and the four output characters take six bits each, so a
     * group of three always costs four and the remainder costs `ceil(remainder / 3) × 4` — which is the same
     * answer as `ceil(bytes / 3) × 4`. A group of one byte pads `==` and of two pads `=`, both already paid
     * for by the ceil.
     *
     * **This is the whole of the "check the cap before encoding" fix, and the order is the point.** The
     * question "would this image fit in `extensions`?" is arithmetic on the *input* size, so it is answered
     * before the encoder allocates. Building 42.7 Mi characters of `String` in order to compare its length
     * against a 256 KiB cap and then throw it away is the transient the audit measured at ~340 MB on ART.
     */
    private fun base64LengthOf(bytes: Int): Int = (bytes + 2) / 3 * 4

    private fun kritaBase64Of(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(KRITA_BASE64[(n ushr 18) and 63])
            sb.append(KRITA_BASE64[(n ushr 12) and 63])
            sb.append(KRITA_BASE64[(n ushr 6) and 63])
            sb.append(KRITA_BASE64[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(KRITA_BASE64[(n ushr 18) and 63])
                sb.append(KRITA_BASE64[(n ushr 12) and 63])
                sb.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(KRITA_BASE64[(n ushr 18) and 63])
                sb.append(KRITA_BASE64[(n ushr 12) and 63])
                sb.append(KRITA_BASE64[(n ushr 6) and 63])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /**
     * The inverse, and this row needs one where the other two did not: **the tip's bytes have to be read** to
     * tell a PNG from a `.gbr` (Decision 4), and R40's `size.base` is the PNG's own `IHDR` width.
     *
     * A `tEXt` chunk is Latin-1, so base64 text is ASCII by construction and this is exact. A character
     * outside the alphabet is a **refusal in words** naming [what], because a silently dropped character
     * would be a silently changed image.
     */
    private fun kritaBase64Decode(text: String, what: String): ByteArray {
        var end = text.length
        var padding = 0
        while (end > 0 && text[end - 1] == '=') {
            end--
            padding++
        }
        if (padding > 2) throw BrushException("$what is not base64: it has $padding padding characters")
        val out = ByteArray(end / 4 * 3 + when (end % 4) { 1 -> 0; 2 -> 1; 3 -> 2; else -> 0 })
        var written = 0
        var acc = 0
        var bits = 0
        for (i in 0 until end) {
            val v = KRITA_BASE64.indexOf(text[i])
            if (v < 0) {
                throw BrushException(
                    "$what is not base64: character ${i + 1} of $end is '${text[i]}', which is not in the " +
                        "RFC 4648 alphabet"
                )
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                if (written < out.size) out[written++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    // ---- the small tables ----------------------------------------------------------------------------

    /** Where a Krita curve's `name` puts it. Every value is a name Krita's own `KoInput*` classes write. */
    private enum class Target { SIZE, OPACITY, FLOW, TIP_ANGLE, SCATTER, HARDNESS }

    private val TARGETS: Map<String, Target> = mapOf(
        // KoInputSize writes "sizes". The flat form's own names, from Decision 2's preamble.
        "sizes" to Target.SIZE, "size" to Target.SIZE,
        "pressuresize" to Target.SIZE, "sizesensor" to Target.SIZE,
        "opacity" to Target.OPACITY, "pressureopacity" to Target.OPACITY,
        "flow" to Target.FLOW, "pressureflow" to Target.FLOW,
        // KoInputRotation writes "angle".
        "angle" to Target.TIP_ANGLE, "rotation" to Target.TIP_ANGLE, "anglejitter" to Target.TIP_ANGLE,
        "scattering" to Target.SCATTER, "scatter" to Target.SCATTER,
        "hardness" to Target.HARDNESS, "pressurehardness" to Target.HARDNESS,
    )

    /**
     * Decision 2's seven, and **only** these. `xtilt` and `declination` are both "how far from upright", so
     * both are [BrushInput.tilt]; the rest of Krita's sensor list has no `BrushInput` at all and is kept raw
     * with a warning naming it.
     *
     * **Deliberately not here: Krita's own derived `tilt` sensor** (`KoInputTilt`, which is what Krita uses
     * when the artist picks "Tilt" rather than "Tilt X"). Decision 2 says "there are exactly these" and this
     * row does not add one. The cost is that the most common real Krita tilt setup produces a "no Joy Brush
     * input" warning instead of a curve. It is in the report as a Question for the Lead, because adding
     * `tilt` to *this table* is not R3 — no new `BrushInput`, no version bump, no validator change — and the
     * spec's silence may be an oversight rather than a decision.
     */
    private val INPUTS: Map<String, BrushInput> = mapOf(
        "pressure" to BrushInput.pressure,
        "xtilt" to BrushInput.tilt,
        "declination" to BrushInput.tilt,
        "ascension" to BrushInput.lean,
        "rotation" to BrushInput.barrel,
        "speed" to BrushInput.speed,
        "drawingangle" to BrushInput.direction,
        "distance" to BrushInput.distance,
    )

    /** Every real input parameter's name ends with this, and the ones that do are sensors. */
    private const val SENSOR_SUFFIX = "Sensor"

    /** The `id` a real nested sensor document carries when it is a *list* of two or more sensors. */
    private const val SENSORS_LIST = "sensorslist"

    /**
     * Which Joy Brush setting each of Krita's own input parameters drives, keyed by the parameter's own
     * lower-cased name.
     *
     * **The real format says which setting a sensor drives only through its parameter name**, so this table
     * *is* the mapping and every entry in it is a name measured out of the 64 real presets of David Revoy's
     * Brushkit V8.2 (`testdata-local/`, git-ignored) rather than out of the spec — the spec's `TARGETS` is
     * keyed by the *flat* form's `name` attribute, which Krita writes **0 times**.
     *
     * **Six names map and the rest do not, and the fourteen that do not are named in a warning**, which is
     * Decision 2's rule — "a sensor Joy Brush has no `BrushInput` for goes to `extensions` with a warning
     * naming it" — applied to the *setting* rather than to the sensor, because the sensor (`pressure` in
     * `SizeSensor` and in `SpacingSensor` alike) is the same one and it is the target that differs.
     * `SpacingSensor` is the clearest case: its sensor is `pressure` in 61 of 62 real presets and Joy Brush
     * has no spacing curve, so the setting is named and the curve is kept raw.
     *
     * **`SoftnessSensor` and `SharpnessSensor` are in the "no" list deliberately.** Krita's softness runs
     * the opposite way from `TipSpec.hardness` and **this build has not read that polarity out of Krita's
     * source**, so mapping either would be a guess about somebody else's numbers: an inverted hardness
     * curve is a different brush wearing the same name, which is the sentence `engineRefusal` uses and the
     * sentence this whole row is built on. It is a Question for the Lead.
     */
    private val SENSOR_TARGETS: Map<String, Target> = mapOf(
        "sizesensor" to Target.SIZE,
        "opacitysensor" to Target.OPACITY,
        "flowsensor" to Target.FLOW,
        "rotationsensor" to Target.TIP_ANGLE,
        "scattersensor" to Target.SCATTER,
    )

    /**
     * Decision 12's Photoshop-compatible modes, matched case-insensitively. These are the ones R4 §A.5 has
     * published formulas for (from Krita's `KisMaskingBrushCompositeOp.h`) and the only ones that become
     * `enabled = true` with a `depth`; every other mode is kept raw and named.
     */
    private val PS_MODES = setOf(
        "subtract", "height", "height(ps)", "height (ps)", "linear height(ps)", "linear height (ps)",
        "hardmix", "hardmix(ps)", "hard mix(ps)", "hard mix (ps)",
    )

    private const val META_NAME = "meta.xml"

    /**
     * The two spellings Krita writes an author's own keyword in, in the order they are asked for.
     *
     * **35 of the 64 real `.kpp` in David Revoy's Brushkit V8.2 use `author` and 26 use `Author`**, so a
     * case-sensitive match re-attributes 26 brushes to nobody. `preset` is *not* matched this way and the
     * asymmetry is deliberate — see [readKppFile].
     */
    private val AUTHOR_KEYWORDS = listOf("author", "Author")

    /** The two spellings of the Pixel engine's own size setting, in Krita's flat and element forms. */
    private val PIXEL_SIZE_NAMES = setOf("pixelsize", "pixel_size")

    private val LICENSE_TAGS = setOf("rights", "license")
    private val AUTHOR_TAGS = setOf("creator", "contributor", "author")

    /** ODF's own attribute names on a `<meta:meta-userdefined>`: what the value is, and what the value is. */
    private const val META_NAME_ATTR = "meta:name"
    private const val META_VALUE_ATTR = "meta:value"

    /** The `meta:name` a Krita bundle writes its licence under. Lower-cased, because the comparison is. */
    private const val LICENSE_META_NAME = "license"

    /** …and the two it has been seen to write an author under, lower-cased. */
    private val AUTHOR_META_NAMES = setOf("author", "creator")

    private const val DEFAULT_DIAMETER_PX = 20f
    private const val DEFAULT_SPACING = 0.04f
    private const val DEFAULT_SMOOTHING = 0.3f
    /** `TipSpec.hardness`'s own default (`BrushPreset.kt:41`), restated so the number is stated once here. */
    private const val DEFAULT_HARDNESS = 0.9f
    private const val SPACING_MIN = 0.005f
    private const val SPACING_MAX = 5f
    private const val MAX_GRAIN_SCALE = 64f

    /** One unclaimed attribute's worth of `extensions`. A setting nobody mapped is not worth more than this. */
    private const val MAX_RAW_VALUE_CHARS = 4_096

    private const val PLACEHOLDER_NAME = "krita brush 1"

    private val PLACEHOLDER_NAME_WARNING =
        "this .kpp carries no name — Krita names a brush by the file it sits in, and a lone file has no " +
            "folder around it — so it is called \"$PLACEHOLDER_NAME\" and its id is built from that"

    private fun isBrushEntry(name: String): Boolean {
        if (!name.endsWith(".kpp", ignoreCase = true)) return false
        val lower = name.lowercase()
        return lower.startsWith("brushes/") || lower.startsWith("paintoppresets/")
    }

    private fun brushNameOf(entry: String): String {
        val base = entry.substringAfterLast('/')
        val withoutExtension =
            if (base.endsWith(".kpp", ignoreCase = true)) base.dropLast(4) else base
        return withoutExtension.ifBlank { entry }
    }

    private fun isPngSignature(bytes: ByteArray, at: Int): Boolean {
        if (at + PNG_SIGNATURE_BYTES > bytes.size) return false
        for (i in 0 until PNG_SIGNATURE_BYTES) {
            if (bytes[at + i] != PNG_SIGNATURE[i]) return false
        }
        return true
    }

    /** 8 of signature + 4 of length + 4 of type is 16, so a header must reach byte 24 to hold a width. */
    private const val PNG_SIGNATURE_BYTES = 8

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun kritaLatin1(bytes: ByteArray, at: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) sb.append((bytes[at + i].toInt() and 0xFF).toChar())
        return sb.toString()
    }
}

private const val KRITA_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** `<![CDATA[`: the nine characters that open a CDATA section, so the text inside starts nine further on. */
private const val CDATA_OPEN = "<![CDATA["

/** `]]>`: the three that close it. `at` is left three past the end, so the text ends three back. */
private const val CDATA_CLOSE = 3

private const val CDATA_CLOSE_TEXT = "]]>"

/** `<!DOCTYPE`, which a nested Krita value opens with and a stranger's file may not carry. */
private const val CDATA_DOCTYPE = "<!DOCTYPE"
