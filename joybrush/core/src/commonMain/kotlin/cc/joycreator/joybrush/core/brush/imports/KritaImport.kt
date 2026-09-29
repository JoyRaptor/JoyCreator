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
 * paint-op settings as XML. A `.bundle` is an ODF-style zip — `mimetype`, `meta.xml`,
 * `META-INF/manifest.xml`, `preview.png`, `brushes/`, `patterns/`, `paintoppresets/`.
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
 * ### No inflater, and that is a decision rather than a module boundary
 *
 * JB-8.02's `inflateRaw` is an `internal expect fun` **in this same module**, so calling it from here would
 * compile. It is not called, and the reason is not "it is private": R4 §B.3 says Krita writes `preset` as
 * an **uncompressed** `tEXt`, so the case should never arise on a real file; and a DEFLATE stream needs a
 * **ratio** budget and an **output** budget, and both of those numbers would then be this file's, next to
 * JB-8.02's. Reusing the function while re-deriving the caps is the worst of the two options — the
 * *appearance* of sharing with none of the safety. So a compressed chunk and a compressed bundle entry are
 * both **refused in words**, and the person sees why. **Consequence, reported not hidden: a real `.bundle`
 * zips its entries with DEFLATE, so `convertBundle` refuses a great many real bundles.** See the Questions.
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

    /** Decision 12: the Krita pattern a texture names, kept whether or not its bytes were reachable. */
    const val TEXTURE_PATTERN_KEY = "krita.texturePattern"

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
        return try {
            val result = KritaBrush(file, null, null).build(name, brushId(idPrefix, name))
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
                val file = readKppFile(zip.readStored(entry))
                brushes += KritaBrush(file, zip, meta).build(name, brushId(idPrefix, name))
            } catch (e: BrushException) {
                refused += RefusedBrush(index, name, e.message ?: "it cannot be read")
            }
        }
        return ImportLibrary(brushes, refused)
    }

    // ---- the file-level half of a `.kpp` ------------------------------------------------------------

    /**
     * The two things [readPngTextChunks] can give this importer and the three it cannot.
     *
     * `PngChunks` **never inflates and never decodes** (Decisions 5 and 6), so a compressed chunk arrives
     * here as `compressed = true` with an **empty** `text` and *this* is the code that refuses it in words.
     * The split is deliberate and is what test 4 of `PngChunksTest` pins from the other side: "the reader
     * tried to decode it" and "the caller forgot to check" are two different failures and must produce two
     * different messages.
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
        for (chunk in chunks) {
            // PNG keywords are 1..79 bytes and case-sensitive ([PngChunks]'s own rule) and Krita writes
            // `preset` in lower case, so this compares exactly rather than guessing at a capitalisation.
            if (chunk.keyword == "preset" && preset == null) preset = chunk
            if (chunk.keyword == "Title" && title == null) title = chunk.text
        }
        val found = preset
            ?: throw BrushException(
                "this file has no \"preset\" text chunk, so it holds no Krita brush settings"
            )

        if (found.compressed) {
            throw BrushException(
                "this file's \"preset\" chunk is compressed (a zTXt, or an iTXt that asks to be), and this " +
                    "build does not inflate a PNG text chunk: Krita writes the chunk uncompressed, so a " +
                    "compressed one means the file was written by something else. Re-save the brush from " +
                    "Krita and it will read"
            )
        }
        val xml = found.text
        if (xml.isBlank() || !xml.trimStart().startsWith("<")) {
            throw BrushException(
                "this file's \"preset\" chunk is ${xml.length} characters and does not begin with \"<\", so " +
                    "it is not the XML a .kpp keeps its paint-op settings in"
            )
        }
        return KritaFile(xml, title?.takeIf { it.isNotBlank() })
    }

    /** One `.kpp`'s settings text, and the one name-ish thing a `.kpp` may carry. */
    private class KritaFile(val presetXml: String, val title: String?)

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

    private fun readBundleMeta(zip: KritaZip): KritaMeta {
        val entry = zip.find(META_NAME) ?: return KritaMeta(null, null)
        val text = try {
            zip.readStored(entry)
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
    ) {

        // The parse happens in the constructor, so a hostile document throws before a single field exists
        // and the caller turns that into a [RefusedBrush] naming the brush.
        private val root = parseKritaXml(file.presetXml, "this .kpp's preset XML")

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
        private var smoothing = DEFAULT_SMOOTHING
        private var scatterAmount = 0f
        private var scatterCount = 1
        private var scatterCountJitter = 0f

        // Decision 4's first case, and the one that is easiest to get wrong in the *other* direction: a
        // brush with an image tip still gets a real `size.base`, so it paints at the right size today.
        private var storedTip: KritaStored? = null

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
            return finish(name, id)
        }

        // ---- Decision 1 and 10: the engine gate, which is the whole row --------------------------------

        /** Decision 10: the id is read from the XML and **nowhere else**, and a missing one refuses. */
        private fun paintopId(): String {
            val id = root.attr("id")?.takeIf { it.isNotBlank() }
                ?: throw BrushException(
                    "its preset XML has no <paintop id=\"…\">, so it does not say which Krita engine this " +
                        "brush is; Joy Brush will not guess one, because a bristle brush read as a round one " +
                        "is a different brush wearing the same name"
                )
            root.claim("id")
            return id
        }

        /**
         * Decision 1: a `when` with **no branch that returns a brush for an unlisted engine**.
         *
         * The named engines each get the same sentence built around the file's own id, because "unsupported"
         * is not a sentence a person can act on. The `else` refuses too and is not a soft landing: it is
         * the same refusal with whatever Krita called it, so a Krita release that adds an engine cannot get
         * in by being new.
         */
        private fun engineOf(paintopId: String): String = when (paintopId) {
            "Pixel" -> "stamp"
            "ColorSmudge" -> {
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

        private fun tip() {
            val definition = root.child("brush_definition")
            if (definition == null) {
                // An absent definition is the spec's first case and is MAPPED, not a fault: a preset that has
                // not chosen a tip yet still has one, and the default tip is a circle. `tip.aspect` stays 0
                // and `tip.corner` is 2, and that is a circle.
                return
            }
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

        private fun curves() {
            val unmappedSensors = LinkedHashSet<String>()
            val unmappedParams = ArrayList<String>()

            // Decision 2b's pre-pass, so the outcome does not depend on which tilt the file happened to
            // write first: both raw values survive either way, and the *same* one is always the ignored one.
            val tiltSensors = HashSet<String>()
            for (node in root.all()) {
                val sensor = node.attr("sensor")?.lowercase() ?: continue
                if (INPUTS[sensor] == BrushInput.tilt && isCurve(node)) tiltSensors += sensor
            }
            val bothTilts = "xtilt" in tiltSensors && "declination" in tiltSensors

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
                val input = INPUTS[sensor.lowercase()]
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
                    if (bothTilts && sensor.equals("declination", ignoreCase = true)) continue
                }
                val target = if (name != null) TARGETS[name.lowercase()] else null
                if (target == null) {
                    unmappedParams += (name ?: node.name)
                    continue
                }
                when (target) {
                    Target.SIZE -> sizeInputs += InputCurve(input, points)
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
         */
        private fun curvePoints(node: KritaNode): List<List<Float>> {
            val where = node.attr("name")?.let { "\"$it\"" } ?: node.name
            val raw = node.attr("commonCurve")?.takeIf { it.isNotBlank() }
                ?: node.attr("points")?.takeIf { it.isNotBlank() }
            node.claim("sensor", "name", "type", "commonCurve", "points", "min", "max", "value", "key")
            if (raw != null) {
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
                return checkPoints(out, where)
            }
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
        }

        /**
         * Decision 4's order for `size.base`, written down: **the size sensor's own base, then the
         * `PixelSize` value, then [DEFAULT_DIAMETER_PX]** — and then, in [finish] and over all three, the
         * embedded tip's own `IHDR` width, because R40 says the stored image never replaces the numbers.
         */
        private fun resolveSize() {
            // The **first** size sensor's curve, not the last: Krita writes pressure first and the pressure
            // sensor is the one a `size` slider is really about. "The first" is a decision rather than an
            // accident — "the last" is also deterministic, and picking one out loud is the whole of it.
            val top = sizeInputs.firstOrNull()?.curve?.maxByOrNull { it[0] }
            var from = pixelSize
            if (top != null) {
                val v = top[1]
                if (!(v > 0f) || v > BrushValidate.MAX_SIZE_PX) {
                    warn(
                        "its size curve tops out at $v px, which is not a diameter Joy Brush can paint (above " +
                            "0 and at most ${BrushValidate.MAX_SIZE_PX}); size.base falls back to " +
                            "$DEFAULT_DIAMETER_PX and the file's own value is kept raw"
                    )
                } else {
                    from = v
                    // The size sensor won, so the `PixelSize` element is kept rather than read-and-dropped:
                    // `extensions` is lossless storage and a value nobody decided to lose is a value kept.
                    pixelSize?.let { extensions["krita.PixelSize"] = it.toString() }
                }
            }
            val v = from
            if (v == null) return
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
            val node = root.child("texture") ?: return
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
            val because = if (grainUnreadable != null) {
                "${grainUnreadable}, so"
            } else {
                "this brush's texture is the Krita pattern \"$name\", which is kept by name and"
            }
            warn(
                "$TEXTURE_NOT_DRAWN; $because it is in extensions[\"$TEXTURE_PATTERN_KEY\"] and is not drawn"
            )
        }

        // ---- everything the mapping did not read -------------------------------------------------------

        /**
         * **Nothing is silent.** Every attribute of every element that no reader above *claimed* is kept
         * under its own path in `extensions` — which is what `extensions` is documented to be, *"lossless
         * storage of imported raw settings"* — and the brush is told once what was kept. The claim set is
         * the mechanism: a reader marks what it used, so a Krita element this build has never heard of is
         * *kept* rather than dropped without anybody deciding to drop it.
         */
        private fun sweep() {
            fun walk(node: KritaNode, path: String) {
                for ((key, value) in node.attributes) {
                    if (key in node.claimedKeys()) continue
                    val at = "$path.$key"
                    if (value.length <= MAX_RAW_VALUE_CHARS) {
                        extensions[at] = value
                    } else {
                        extensions[at] = value.take(MAX_RAW_VALUE_CHARS) + "…(+${value.length} more characters)"
                    }
                    rawKept += at
                }
                for (child in node.children) walk(child, "$path.${child.name}")
            }
            walk(root, "krita.${root.name}")
            if (rawKept.isNotEmpty()) {
                val named = rawKept.distinct().take(8).joinToString(", ")
                val rest = if (rawKept.size > 8) " and ${rawKept.size - 8} more" else ""
                warn("kept raw in extensions: $named$rest")
            }
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
                val b64 = kritaBase64Of(stored.bytes)
                if (extensionChars() + b64.length <= MAX_EXTENSION_BYTES) {
                    extensions[TIP_IMAGE_KEY] = b64
                    tip = tip.copy(source = "image", image = stored.fileName)
                    size = stored.width.toFloat()
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's tip is an embedded ${stored.bytes.size}-byte PNG, " +
                            "stored as ${b64.length} base64 characters in extensions[\"$TIP_IMAGE_KEY\"] for " +
                            "JB-1.05d. size.base is its ${stored.width} px width, so the brush paints at the " +
                            "right size today and the bitmap is extra"
                    )
                } else {
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's tip is an embedded ${stored.bytes.size}-byte PNG, " +
                            "which is ${b64.length} base64 characters, over the $MAX_EXTENSION_BYTES-" +
                            "character extensions cap, so none of it is stored: the tip stays procedural " +
                            "and size.base stays ${sizeBase} px"
                    )
                }
            }

            var paperGrain = GrainSpec(
                enabled = grainEnabled,
                scale = textureScale,
                depth = Param(textureDepth),
            )
            grainStored?.let { stored ->
                val b64 = kritaBase64Of(stored.bytes)
                if (extensionChars() + b64.length <= MAX_EXTENSION_BYTES) {
                    extensions[GRAIN_IMAGE_KEY] = b64
                    paperGrain = paperGrain.copy(enabled = true, source = "image", image = stored.fileName)
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's grain is the bundle's " +
                            "patterns/${extensions[TEXTURE_PATTERN_KEY]}, ${stored.bytes.size} bytes stored " +
                            "as ${b64.length} base64 characters in extensions[\"$GRAIN_IMAGE_KEY\"] for " +
                            "JB-1.05d, and a depth of $textureDepth stands in for it until then"
                )
                } else {
                    // Over the cap the grain is left **off** rather than left on with no picture behind it,
                    // because a grain that claims an image it does not have is the stronger claim.
                    paperGrain = paperGrain.copy(enabled = false)
                    warn(
                        "$TEXTURE_NOT_DRAWN; this brush's grain is the bundle's ${stored.bytes.size}-byte " +
                            "pattern, which is ${b64.length} base64 characters, over the " +
                            "$MAX_EXTENSION_BYTES-character extensions cap, so none of it is stored: " +
                            "paperGrain.source stays cloud and the grain stays off"
                    )
                }
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
                // Decision 14. A `.kpp` on its own carries no licence at all, so this is "unknown" and
                // **never "CC0"**, which is `BrushPreset`'s own default and a claim about somebody else's
                // work that nobody in this file made. A bundle may state one in its `meta.xml` and that is
                // what is used. Where a brush detail sheet shows it is not this row's business (R40).
                license = meta?.license?.takeIf { it.isNotBlank() } ?: "unknown",
                author = meta?.author?.takeIf { it.isNotBlank() } ?: "",
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
    private class KritaXml(private val text: String, private val what: String) {

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
                        text.startsWith("<![CDATA[", at) -> {
                            val end = skipCdata()
                            body.append(text.substring(at + 9, end))
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
                    text.startsWith("<!DOCTYPE", at) || text.startsWith("<!ENTITY", at) -> bad(
                        "holds a DOCTYPE or an ENTITY declaration, which is refused before it is read"
                    )
                    text.startsWith("<!", at) -> bad("holds a <! declaration, which is refused")
                    else -> return
                }
            }
        }

        private fun skipComment() {
            val end = text.indexOf("-->", at + 4)
            if (end < 0) bad("its comment is never closed")
            at = end + 3
        }

        /** Past a `CDATA` section: skips it and returns where its text started, for [KritaNode.text]. */
        private fun skipCdata(): Int {
            val start = at + 9
            val end = text.indexOf("]]>", start)
            if (end < 0) bad("its CDATA section is never closed")
            at = end + 3
            return end
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

    private fun parseKritaXml(text: String, what: String): KritaNode = KritaXml(text, what).document()

    // ---- the zip of a `.bundle` ----------------------------------------------------------------------

    /**
     * Just enough of a zip to walk a `.bundle`. **It reads STORED entries and refuses DEFLATE ones in
     * words** (Decision 5), and it has its own central-directory walk rather than reusing
     * `ProcreateImport`'s because that one is a `private class Zip` in another file and Decision 5 is this
     * row's decision, not a shared mechanism.
     *
     * **Two things this deliberately does not do, both said here rather than discovered later:** it does
     * not verify a CRC (a wrong CRC in a pattern PNG is a pattern nobody will see, and an importer that
     * refuses a whole pack over one entry's CRC is a worse tool), and it does not inflate. Both are in the
     * report's Questions.
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

    /** The two spellings of the Pixel engine's own size setting, in Krita's flat and element forms. */
    private val PIXEL_SIZE_NAMES = setOf("pixelsize", "pixel_size")

    private val LICENSE_TAGS = setOf("rights", "license")
    private val AUTHOR_TAGS = setOf("creator", "contributor", "author")

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
