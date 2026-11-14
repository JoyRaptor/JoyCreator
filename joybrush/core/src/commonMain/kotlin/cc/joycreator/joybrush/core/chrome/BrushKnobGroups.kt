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
}
