package cc.joycreator.joybrush.core.layers

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * The blend modes as a painter meets them (JB-2.04): in the groups every painting app uses — darken, lighten, contrast,
 * difference, colour — with names in the app's own spelling. The GPU composites all 27 (JB-2.20b), so every mode is
 * offered; a test pins that each appears exactly once, so a 28th mode cannot be forgotten by the picker.
 */
object BlendNames {

    val GROUPS: List<Pair<String, List<BlendMode>>> = listOf(
        "Normal" to listOf(BlendMode.NORMAL),
        "Darken" to listOf(BlendMode.DARKEN, BlendMode.MULTIPLY, BlendMode.COLOR_BURN, BlendMode.LINEAR_BURN, BlendMode.DARKER_COLOR),
        "Lighten" to listOf(BlendMode.LIGHTEN, BlendMode.SCREEN, BlendMode.COLOR_DODGE, BlendMode.ADD, BlendMode.LIGHTER_COLOR),
        "Contrast" to listOf(
            BlendMode.OVERLAY, BlendMode.SOFT_LIGHT, BlendMode.HARD_LIGHT, BlendMode.VIVID_LIGHT,
            BlendMode.LINEAR_LIGHT, BlendMode.PIN_LIGHT, BlendMode.HARD_MIX,
        ),
        "Difference" to listOf(BlendMode.DIFFERENCE, BlendMode.EXCLUSION, BlendMode.SUBTRACT, BlendMode.DIVIDE),
        "Colour" to listOf(BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY),
        "Cut" to listOf(BlendMode.ERASE_BELOW),
    )

    /** Every mode, in picker order. */
    val ORDER: List<BlendMode> = GROUPS.flatMap { it.second }

    fun name(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> "Normal"
        BlendMode.MULTIPLY -> "Multiply"
        BlendMode.SCREEN -> "Screen"
        BlendMode.OVERLAY -> "Overlay"
        BlendMode.ADD -> "Add"
        BlendMode.DARKEN -> "Darken"
        BlendMode.LIGHTEN -> "Lighten"
        BlendMode.ERASE_BELOW -> "Erase below"
        BlendMode.DIFFERENCE -> "Difference"
        BlendMode.COLOR -> "Colour"
        BlendMode.COLOR_DODGE -> "Colour dodge"
        BlendMode.COLOR_BURN -> "Colour burn"
        BlendMode.LINEAR_BURN -> "Linear burn"
        BlendMode.HARD_LIGHT -> "Hard light"
        BlendMode.SOFT_LIGHT -> "Soft light"
        BlendMode.VIVID_LIGHT -> "Vivid light"
        BlendMode.LINEAR_LIGHT -> "Linear light"
        BlendMode.PIN_LIGHT -> "Pin light"
        BlendMode.HARD_MIX -> "Hard mix"
        BlendMode.EXCLUSION -> "Exclusion"
        BlendMode.SUBTRACT -> "Subtract"
        BlendMode.DIVIDE -> "Divide"
        BlendMode.DARKER_COLOR -> "Darker colour"
        BlendMode.LIGHTER_COLOR -> "Lighter colour"
        BlendMode.HUE -> "Hue"
        BlendMode.SATURATION -> "Saturation"
        BlendMode.LUMINOSITY -> "Luminosity"
    }

    /** At most 5 letters for the corner of a 52 dp thumbnail: "Mult", "Scrn", "Ovly". */
    fun short(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> ""
        BlendMode.MULTIPLY -> "Mult"
        BlendMode.SCREEN -> "Scrn"
        BlendMode.OVERLAY -> "Ovly"
        BlendMode.ADD -> "Add"
        BlendMode.DARKEN -> "Dark"
        BlendMode.LIGHTEN -> "Light"
        BlendMode.ERASE_BELOW -> "Cut"
        BlendMode.DIFFERENCE -> "Diff"
        BlendMode.COLOR -> "Col"
        BlendMode.COLOR_DODGE -> "Dodge"
        BlendMode.COLOR_BURN -> "Burn"
        BlendMode.LINEAR_BURN -> "LBurn"
        BlendMode.HARD_LIGHT -> "HardL"
        BlendMode.SOFT_LIGHT -> "SoftL"
        BlendMode.VIVID_LIGHT -> "Vivid"
        BlendMode.LINEAR_LIGHT -> "LinL"
        BlendMode.PIN_LIGHT -> "Pin"
        BlendMode.HARD_MIX -> "HMix"
        BlendMode.EXCLUSION -> "Excl"
        BlendMode.SUBTRACT -> "Sub"
        BlendMode.DIVIDE -> "Div"
        BlendMode.DARKER_COLOR -> "DkCol"
        BlendMode.LIGHTER_COLOR -> "LtCol"
        BlendMode.HUE -> "Hue"
        BlendMode.SATURATION -> "Sat"
        BlendMode.LUMINOSITY -> "Lum"
    }
}

/**
 * How many layers this device may hold (JB-2.04, blueprint §2: "a layer budget computed at runtime … so the Note 9 never
 * gets pushed into a crash"). A layer's pixels live in GPU textures, which on a phone come out of the same RAM as
 * everything else, so the budget is a share of the device's RAM divided by what one layer covering the whole page
 * costs. Sparse layers cost less, so this is a floor on what fits, which is the safe way round. Clamped so a tiny phone
 * still gets [MIN] and a huge tablet is not promised [MAX]+ layers it would take minutes to save.
 *
 * Note 9 (6 GB, 1080 × 2220): 6 GiB × 6% ≈ 368.6 MiB; the page is 5 × 9 = 45 tiles × 256 KiB = 11.25 MiB; so 32 layers.
 */
object LayerBudget {
    const val RAM_SHARE = 0.06
    const val MIN = 4
    const val MAX = 64

    fun maxLayers(totalRamBytes: Long, pageW: Int, pageH: Int): Int {
        if (totalRamBytes <= 0L || pageW <= 0 || pageH <= 0) return MIN
        val size = Tiles.SIZE.toLong()
        val tiles = ((pageW + size - 1) / size) * ((pageH + size - 1) / size)
        val layerBytes = tiles * size * size * 4L
        val n = (totalRamBytes * RAM_SHARE / layerBytes).toLong()
        return n.coerceIn(MIN.toLong(), MAX.toLong()).toInt()
    }
}
