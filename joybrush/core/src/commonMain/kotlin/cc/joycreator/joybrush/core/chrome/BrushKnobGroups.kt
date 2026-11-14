package cc.joycreator.joybrush.core.chrome

/**
 * BRUSH-UI section K (owner's picks, 11-14): the one brush editor sorts every slider into a small set of groups, each a
 * round button in the editor's top bar, and shows what each end of a slider does with a picture and a word.
 *
 * Pure and keyed on [BrushKnobs.Knob.key], so a new knob lands in a sensible group by its name and every engine's
 * sliders share the same groups and the same words.
 */
object BrushKnobGroups {

    /** In the order the top bar shows them. */
    enum class Group(val label: String) {
        SHAPE("Shape"), INK("Ink"), PRESSURE("Pressure & tilt"), SCATTER("Spacing & scatter"),
        COLOUR("Colour"), WATER("Water"), ENDS("Stroke ends"), PAPER("Paper"), FEEL("Feel"),
    }

    /** What the picture at each end of a slider is: one dab (the tip itself) or a tiny stroke (how it lays). */
    enum class EndPicture { DAB, STROKE }

    /** The two ends of a slider, in words: always shown, beside the picture. */
    data class Ends(val low: String, val high: String, val picture: EndPicture)

    fun groupOf(key: String): Group {
        MEDIA[key]?.let { return it.group }
        val k = key.lowercase()
        return when {
            k.startsWith("response.") -> Group.PRESSURE
            k == "smoothing" || k.contains("stabil") -> Group.FEEL
            has(k, "spacing", "jitter", "scatter", "window") -> Group.SCATTER
            has(k, "grain", "paper") -> Group.PAPER
            has(k, "wet", "water", "thinner", "watercolour", "glazing", "pull", "belly") -> Group.WATER
            has(k, "pickup", "load", "rinf", "colour", "color", "darkness") -> Group.COLOUR
            has(k, "ends", "taper", "turns") -> Group.ENDS
            has(k, "pressure", "press", "tilt", "graze") -> Group.PRESSURE
            has(k, "flow", "opacity") -> Group.INK
            else -> Group.SHAPE
        }
    }

    /** The words and the kind of picture for each end. Dabs for softness, shape and size; strokes for the rest. */
    fun endsOf(key: String): Ends {
        MEDIA[key]?.let { return it.ends }
        val k = key.lowercase()
        fun dab(a: String, b: String) = Ends(a, b, EndPicture.DAB)
        fun stroke(a: String, b: String) = Ends(a, b, EndPicture.STROKE)
        return when {
            has(k, "hardness") -> dab("Soft", "Crisp")
            has(k, "paste.soft") -> stroke("Hard", "Soft")
            has(k, "thinnest") -> dab("Thin", "Full")
            has(k, "tippx") -> dab("Fine", "Broad")
            has(k, "face") -> dab("Edge", "Face")
            has(k, "orient", "edge") -> dab("Across", "Along")
            has(k, "size") && !has(k, "jitter") -> dab("Small", "Large")
            has(k, "sizejitter", "anglejitter", "vary") -> stroke("Even", "Varied")
            has(k, "spacing") -> stroke("Smooth", "Dotted")
            has(k, "scatter") -> stroke("Tight", "Scattered")
            has(k, "opacitypressure") -> stroke("Steady", "Fades")
            has(k, "flow", "opacity") -> stroke("Faint", "Full")
            has(k, "grain", "papercut") -> stroke("Smooth", "Grainy")
            has(k, "pickup") -> stroke("Clean", "Picks up")
            has(k, "load") -> stroke("Smears", "Own colour")
            has(k, "rinf", "darkness") -> stroke("Light", "Dark")
            has(k, "wetness", "water") -> stroke("Dry", "Runny")
            has(k, "thinner") -> stroke("Thick", "Thin")
            has(k, "belly") -> stroke("One colour", "Belly colour")
            has(k, "paste.ends", "taper") -> stroke("Blunt", "Tapered")
            has(k, "pull") -> stroke("Lays", "Pulls")
            has(k, "turns") -> stroke("Round", "Sharp")
            has(k, "press") -> stroke("Light", "Heavy")
            has(k, "graze") -> stroke("Off", "On")
            has(k, "glazing") -> stroke("Mixes", "Glazes")
            k == "smoothing" -> stroke("Raw", "Smooth")
            else -> stroke("Less", "More")
        }
    }

    private fun has(k: String, vararg parts: String) = parts.any { k.contains(it) }

    private class Media(val group: Group, val ends: Ends)

    /** Pencil (stick), watercolour (wet) and oil/knife (paste) settings, by their exact keys in [BrushKnobs]. */
    private val MEDIA: Map<String, Media> = buildMap {
        fun dab(key: String, g: Group, a: String, b: String) = put(key, Media(g, Ends(a, b, EndPicture.DAB)))
        fun stroke(key: String, g: Group, a: String, b: String) = put(key, Media(g, Ends(a, b, EndPicture.STROKE)))
        val st = "media.stick."; val w = "media.wet."; val pa = "media.paste."
        dab(st + "tipR", Group.SHAPE, "Sharp", "Blunt")
        dab(st + "tipMax", Group.PRESSURE, "Narrow", "Wide")
        stroke(st + "side1", Group.SHAPE, "Short", "Long")
        stroke(st + "side2", Group.SHAPE, "Short", "Long")
        dab(st + "face", Group.SHAPE, "Narrow", "Wide")
        dab(st + "soft", Group.SHAPE, "Crisp", "Soft")
        stroke(st + "blend", Group.COLOUR, "Separate", "Blended")
        stroke(st + "build", Group.INK, "Even", "Builds up")
        dab(w + "bellyMm", Group.SHAPE, "Small", "Large")
        dab(w + "tipMm", Group.SHAPE, "Fine", "Broad")
        stroke(w + "capacityMm3", Group.WATER, "Little", "Lots")
        stroke(w + "dwellMmPerS", Group.WATER, "Flows", "Pools")
        stroke(w + "gran", Group.PAPER, "Smooth", "Grainy")
        stroke(w + "liftMm", Group.WATER, "None", "Lifts")
        stroke(w + "load", Group.INK, "Pale", "Strong")
        stroke(w + "stain", Group.COLOUR, "Lifts off", "Stains")
        stroke(w + "waterPerMm", Group.WATER, "Dry", "Wet")
        stroke(w + "beadMm", Group.WATER, "Small", "Big")
        dab(pa + "acrossMaxMm", Group.SHAPE, "Narrow", "Wide")
        stroke(pa + "bead", Group.INK, "Flat", "Beaded")
        stroke(pa + "bladeHmax", Group.SHAPE, "Short", "Far")
        dab(pa + "bladeLenMm", Group.SHAPE, "Short", "Long")
        stroke(pa + "bow", Group.SHAPE, "Straight", "Bent")
        stroke(pa + "carry", Group.COLOUR, "Drops", "Carries")
        stroke(pa + "cover", Group.INK, "Thin", "Covers")
        stroke(pa + "edgeMinMm", Group.PRESSURE, "Flat", "Upright")
        stroke(pa + "force", Group.PRESSURE, "Upright", "Leans")
        stroke(pa + "hairDepth", Group.INK, "Smooth", "Marked")
        stroke(pa + "lenMm", Group.SHAPE, "Short", "Long")
        stroke(pa + "loadLenMm", Group.INK, "Short", "Long")
        stroke(pa + "lump", Group.INK, "Smooth", "Lumpy")
        stroke(pa + "mix", Group.COLOUR, "Pure", "Mixes")
        stroke(pa + "ridge", Group.INK, "Flat", "Ridged")
        stroke(pa + "riseMaxMm", Group.ENDS, "Blunt", "Tapered")
        stroke(pa + "scrape", Group.COLOUR, "Lays", "Scrapes")
        stroke(pa + "thickMm", Group.INK, "Thin", "Thick")
        dab(pa + "widthMm", Group.SHAPE, "Narrow", "Wide")
    }
}
