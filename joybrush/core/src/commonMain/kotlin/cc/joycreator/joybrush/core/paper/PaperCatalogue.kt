package cc.joycreator.joybrush.core.paper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The format tag in every `assets/paper/catalogue.json`. */
const val PAPER_CATALOGUE_FORMAT = "joybrush-papers"

/** The newest catalogue version this build reads. Bump with any field added below (R30). */
const val PAPER_CATALOGUE_VERSION = 1

/**
 * A paper's **surface** — what the brushes FEEL (JB-9.01's height map). Paired with a [LookEntry],
 * which is what you see.
 *
 * Every range in the comments below is a rule [PaperCatalogues.problems] enforces, because a number a
 * file cannot hold is a number the engine would clamp silently, and a clamped paper tooth is a paper
 * that is quietly not the one the catalogue describes.
 */
@Serializable data class SurfaceEntry(
    val id: String,              // [a-z0-9_]{1,40}, unique among surfaces
    val name: String,            // what the sheet shows, 1..40 chars
    val file: String,            // plain *.png name inside assets/paper/
    val size: Int,               // texels across; the PNG is size×size; power of two 64..2048
    val texelPx: Float,          // doc px per texel, 0.25..16
    val slopeRange: Float,       // as packed (JB-9.01), 0.001..4
    val hexTexels: Float,        // hex cell size in texels, 16..size
    val rotatable: Boolean,      // false for weaves, laid lines, papyrus
    val relief: Float = 1f,      // how strongly the relief is LIT on screen, 0..4
    val heightMean: Float = 0.5f, // mean of the B (height) channel, 0..1: the centre the hex blend preserves contrast around
    // 2026-10-06 (photo papers): `file` may hold the HEIGHT alone (8-bit grey, packed = false); the app derives the
    // slopes and h² at load with SurfaceMaps.pack — the stored RGBA was 4x the size for nothing it could not recompute.
    val packed: Boolean = true,
    // The FLUID map, for the wet and impasto engines: R = absorbency/formation, G,B = fibre direction (double angle,
    // 0.5 + 0.5·(cos2θ, sin2θ)·coherence), A = pore capacity. Its own scale (it holds only broad structure). null = none.
    val fluid: String? = null,
    val fluidTexelPx: Float = 5f,    // doc px per fluid texel, 0.25..16
    val fluidHexTexels: Float = 150f, // hex cell size in fluid texels, 16..2048
    // The paper's physical behaviour, tuned once per brush and varied per paper (all 0..1 except toothDepthMm).
    val toothDepthMm: Float = 0.1f,  // the relief between height 0 and 1, real millimetres (PaperPhysical.DOC_PX_PER_MM), 0..2
    val compliance: Float = 0.3f,    // how much the sheet gives under a pencil: hard board 0 … pillowy pad 1
    val sizing: Float = 0.5f,        // water sits on top (hard-sized, 1) … soaks in at once (rice paper, 0)
    val absorbency: Float = 0.5f,    // base soak rate
    val capacity: Float = 0.5f,      // total water the sheet holds
    val wickSpeed: Float = 0.4f,     // capillary spread speed
    val anisotropy: Float = 0.2f,    // how strongly wicking follows the fibres
)

/** The paper's physical scale (R10 §4: at 1x on the Note 9 one document pixel is about 0.05 mm). */
object PaperPhysical {
    /** Document pixels per real millimetre. A paper's size in mm is texelPx × texels / this. */
    const val DOC_PX_PER_MM = 20f
}

/**
 * A paper's **look** — what you SEE: a flat colour, or a re-tintable colour picture.
 *
 * `mean` is not decoration. Tinting divides the picture by its mean, so a pictured look without one is
 * a tint that divides by nothing — which is why [PaperCatalogues.problems] refuses it, and why the
 * shipped catalogue test asserts every shipped picture carries it.
 */
@Serializable data class LookEntry(
    val id: String,              // [a-z0-9_]{1,40}, unique among looks
    val name: String,
    val base: String,            // "#RRGGBB": the flat colour, and the colour a tint is measured against
    val file: String? = null,    // RGB picture (*.png), or null = flat base colour
    val mean: String? = null,    // "#RRGGBB" mean colour of the picture; REQUIRED when file != null (tinting divides by it)
    val texelPx: Float = 2f,
    val hexTexels: Float = 180f,
    val rotatable: Boolean = true,
    val defaultSurface: String? = null,   // a surface id, or null = smooth
    val lightByDefault: Boolean = true,   // AMOLED black ships false, so black stays black
)

/**
 * `assets/paper/catalogue.json` — the ONE list of the papers that ship. The Paper sheet (JB-9.07), the
 * engine (JB-9.06) and export all read this file, so a paper missing from it does not exist and a
 * paper listed twice is the second one unreachable in the sheet.
 *
 * **It is an asset, not part of a document.** A `.joybrush` references papers by id (JB-9.05), which
 * is what lets a drawing open on a build whose asset list has grown since.
 */
@Serializable data class PaperCatalogue(
    val format: String = PAPER_CATALOGUE_FORMAT,
    val version: Int = PAPER_CATALOGUE_VERSION,
    val surfaces: List<SurfaceEntry>,
    val looks: List<LookEntry>,
)

/**
 * Reads the paper catalogue and says, in words, everything wrong with one.
 *
 * **Reading is strict** — an unknown key is a parse error, like the brush codec (JB-0.02d). The
 * catalogue is an asset in the app rather than a file off a hot-reload, but the argument is the same
 * and stronger here: a key this build does not know is a build and an asset that disagree, and
 * reading it anyway means the sheet offers a paper whose settings are not the ones on disk.
 *
 * **Parsing never validates, and validating never parses.** [parse] refuses only what the file itself
 * cannot say; [problems] says everything else. That split is what lets a catalogue from a **newer**
 * Joy Brush still be read, so the version problem can be reported in words — the trade JB-0.02d ruled
 * on, and the same answer here. Refusing it at the door would replace `made by a newer Joy Brush` with
 * a library's `Encountered an unknown key`, which sends a person looking for a bug.
 *
 * **One message per rule, naming the entry's id and the field.** A person fixing a catalogue needs to
 * know WHICH paper and WHICH number, and `PaperCatalogueTest` holds every rule to exactly one problem
 * per bad field so these sentences cannot quietly grow into paragraphs.
 */
object PaperCatalogues {

    /**
     * Strict, unlike `BrushJson`: `ignoreUnknownKeys` is left at its default `false` on purpose, and
     * this is the only reason the flag exists in this file.
     */
    private val FORMAT = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Read a catalogue. Throws the serialization library's own error on a broken document or an
     * unknown key — this row's contract gives [PaperCatalogues] no exception type of its own, so it
     * invents none. **A newer version still parses**: it is [problems] that refuses it, in words.
     */
    fun parse(json: String): PaperCatalogue = FORMAT.decodeFromString(PaperCatalogue.serializer(), json)

    /**
     * Every problem, in words, each naming the entry id and the field. Empty = valid.
     *
     * Ranges are written `!in lo..hi`, which fails NaN and ±Infinity as well as the numbers outside the
     * range — a surface whose `texelPx` is `1e999` would otherwise reach the sampler as an infinite
     * repeat distance and vanish.
     */
    fun problems(c: PaperCatalogue): List<String> {
        val out = ArrayList<String>()

        // 1 — this build can read this file. One message, and only the first: a file from the future
        // and a file of the wrong format are both mistakes, and until the first is fixed the second
        // is noise.
        if (c.version > PAPER_CATALOGUE_VERSION) {
            out += "catalogue was made by a newer Joy Brush (version ${c.version}, this build reads $PAPER_CATALOGUE_VERSION)"
        } else if (c.version < 1) {
            out += "unknown paper catalogue version ${c.version}"
        } else if (c.format != PAPER_CATALOGUE_FORMAT) {
            out += "format is \"${c.format}\", expected \"$PAPER_CATALOGUE_FORMAT\""
        }

        // 2 — the surfaces. Every rule is one message naming the id and the field, so a person can find
        // the line in the file without counting entries. A **shared id is spoken ONCE**, not once per
        // entry that carries it: two entries claiming `pulp_artisan` is one mistake with two victims, and
        // the same number is printed twice in a problem list.
        val surfaceIds = c.surfaces.map { it.id }
        for (id in surfaceIds.distinct()) {
            if (!ID.matches(id)) out += "surface \"$id\": id must be 1 to 40 characters of a-z, 0-9 and _"
            val claimants = surfaceIds.count { it == id }
            if (claimants > 1) {
                out += "surface \"$id\": id is claimed by $claimants surfaces, and all but the first are unreachable in the sheet"
            }
        }
        for (s in c.surfaces) {
            val at = "surface \"${s.id}\""
            if (s.name.length !in 1..40) out += "$at: name is ${s.name.length} characters, not 1 to 40"
            pngProblem(s.file)?.let { out += "$at: $it" }
            if (s.size < 64 || s.size > 2048) {
                out += "$at: size ${s.size} is outside 64 to 2048"
            } else if (s.size and (s.size - 1) != 0) {
                // Not a power of two. A tiled surface whose size is not a power of two does not wrap:
                // the modulo in the sampler turns into a visible seam every repeat, which is the one
                // thing a paper must never do (R10 P2).
                out += "$at: size ${s.size} is not a power of two"
            }
            if (s.texelPx !in 0.25f..16f) out += "$at: texelPx ${s.texelPx} is outside 0.25 to 16"
            if (s.slopeRange !in 0.001f..4f) out += "$at: slopeRange ${s.slopeRange} is outside 0.001 to 4"
            if (s.heightMean !in 0f..1f) out += "$at: heightMean ${s.heightMean} is outside 0 to 1"
            s.fluid?.let { f -> pngProblem(f)?.let { out += "$at: fluid $it" } }
            if (s.fluidTexelPx !in 0.25f..16f) out += "$at: fluidTexelPx ${s.fluidTexelPx} is outside 0.25 to 16"
            if (s.fluidHexTexels !in 16f..2048f) out += "$at: fluidHexTexels ${s.fluidHexTexels} is outside 16 to 2048"
            if (s.toothDepthMm !in 0f..2f) out += "$at: toothDepthMm ${s.toothDepthMm} is outside 0 to 2"
            for ((n, v) in listOf("compliance" to s.compliance, "sizing" to s.sizing, "absorbency" to s.absorbency,
                    "capacity" to s.capacity, "wickSpeed" to s.wickSpeed, "anisotropy" to s.anisotropy)) {
                if (v !in 0f..1f) out += "$at: $n $v is outside 0 to 1"
            }
            // The upper bound is the surface's OWN size, so the rule reads `!in 16f..s.size`: a hex cell
            // wider than its picture is a cell that can never be assembled.
            if (s.hexTexels !in 16f..s.size.toFloat()) {
                out += "$at: hexTexels ${s.hexTexels} is outside 16 to ${s.size}"
            }
            if (s.relief !in 0f..4f) out += "$at: relief ${s.relief} is outside 0 to 4"
        }

        // 3 — the looks, and the one cross-list rule: a look may name the surface it comes with (owner
        // P6, a chalkboard comes with its chalk), so `defaultSurface` is checked against THIS
        // catalogue's surfaces rather than against a constant.
        val lookIds = c.looks.map { it.id }
        for (id in lookIds.distinct()) {
            if (!ID.matches(id)) out += "look \"$id\": id must be 1 to 40 characters of a-z, 0-9 and _"
            val claimants = lookIds.count { it == id }
            if (claimants > 1) {
                out += "look \"$id\": id is claimed by $claimants looks, and all but the first are unreachable in the sheet"
            }
        }
        for (l in c.looks) {
            val at = "look \"${l.id}\""
            if (l.name.length !in 1..40) out += "$at: name is ${l.name.length} characters, not 1 to 40"
            if (!COLOUR.matches(l.base)) out += "$at: base \"${l.base}\" is not #RRGGBB"
            if (l.file == null) {
                // A flat look has no picture, so a mean on it is a number nothing reads.
                if (l.mean != null) out += "$at: has no picture, so a mean on it is a number nobody reads"
            } else {
                imageProblem(l.file)?.let { out += "$at: $it" }
                if (l.mean == null) {
                    out += "$at: has a picture and no mean, and a tint divides by the mean"
                } else if (!COLOUR.matches(l.mean)) {
                    out += "$at: mean \"${l.mean}\" is not #RRGGBB"
                }
            }
            val d = l.defaultSurface
            if (d != null && c.surfaces.none { it.id == d }) {
                out += "$at: defaultSurface \"$d\" is not a surface in this catalogue"
            }
        }

        return out
    }

    /** A surface by the id a document carries, or null. A miss is a miss, not a throw. */
    fun surface(c: PaperCatalogue, id: String): SurfaceEntry? = c.surfaces.firstOrNull { it.id == id }

    /** A look by the id a document carries, or null. */
    fun look(c: PaperCatalogue, id: String): LookEntry? = c.looks.firstOrNull { it.id == id }

    /**
     * A `file` is a PLAIN name inside `assets/paper/`. A path in it is either a typo or a reach
     * outside the folder, and either way it is not a name the asset loader can find — so it is refused
     * by name here rather than becoming a `FileNotFoundException` on a phone.
     */
    private fun pngProblem(file: String): String? = when {
        file.isBlank() -> "file is empty"
        '/' in file || '\\' in file -> "file \"$file\" must be a plain name inside assets/paper/, not a path"
        !file.endsWith(".png") -> "file \"$file\" does not end in .png"
        else -> null
    }

    /** A look is a photograph: PNG or JPEG (2026-10-06; a JPEG of paper grain is a fifth of the PNG's size). */
    private fun imageProblem(file: String): String? = when {
        file.isBlank() -> "file is empty"
        '/' in file || '\\' in file -> "file \"$file\" must be a plain name inside assets/paper/, not a path"
        !(file.endsWith(".png") || file.endsWith(".jpg")) -> "file \"$file\" does not end in .png or .jpg"
        else -> null
    }

    /** Ids are what a document stores, so they are held to a shape a filename can never have. */
    private val ID = Regex("[a-z0-9_]{1,40}")

    /** `#RRGGBB`, the only colour spelling the tint maths has a parser for. */
    private val COLOUR = Regex("#[0-9A-Fa-f]{6}")
}
