package cc.joycreator.joybrush.android

import android.content.Context
import android.content.Intent
import cc.joycreator.joybrush.android.library.DrawingLibrary

/**
 * Opening JoyBrush with a board ready (BOT-7, owner 11-15: Joybot's create tree, "new animation" and the like).
 *
 * The one contract: call [newDrawing] off the main thread, start the Intent it returns. JoyBrush opens a NEW Library
 * drawing with the [Preset]'s board already made in the middle of the screen (no rectangle to drag) and its guide on,
 * as one Undo step for the board. Every field is optional; a preset with nothing set opens a plain new drawing.
 *
 * The same thing as plain extras, for a caller that cannot link this class: [EXTRA_BOARD] and the rest, on
 * `DrawingLibrary.openIntent(context, DrawingLibrary.create(context))`.
 */
object JoyBrushLaunch {
    /** "image", "animation", "sprite" or "tile" (a tile is an image board with tiling shown). */
    const val EXTRA_BOARD = "cc.joycreator.joybrush.extra.BOARD"
    /** The board's shape as "w:h", e.g. "16:9", "1:1", "9:16". Default 1:1 for tile and sprite cells, 4:3 otherwise. */
    const val EXTRA_ASPECT = "cc.joycreator.joybrush.extra.ASPECT"
    /** Animation only: frames per second, 1 to 60 (default 12). */
    const val EXTRA_FPS = "cc.joycreator.joybrush.extra.FPS"
    /** Sprite only: the grid of cells (default 4 x 1). The aspect is then each cell's shape. */
    const val EXTRA_COLUMNS = "cc.joycreator.joybrush.extra.COLUMNS"
    const val EXTRA_ROWS = "cc.joycreator.joybrush.extra.ROWS"
    /** "grid", "isometric", "perspective-1", "perspective-2", "perspective-3", "ruler", "symmetry" or "radial". */
    const val EXTRA_GUIDE = "cc.joycreator.joybrush.extra.GUIDE"
    /** The board's name (default: "Image", "Animation", "Sprite", "Tile"). */
    const val EXTRA_NAME = "cc.joycreator.joybrush.extra.NAME"

    val BOARDS = listOf("image", "animation", "sprite", "tile")
    val GUIDES = listOf("grid", "isometric", "perspective-1", "perspective-2", "perspective-3", "ruler", "symmetry", "radial")

    data class Preset(
        val board: String? = null,
        val aspect: String? = null,
        val fps: Int? = null,
        val columns: Int? = null,
        val rows: Int? = null,
        val guide: String? = null,
        val name: String? = null,
    ) {
        /** Width and height of the aspect, or the board's default; null when the text is not "w:h" with w, h >= 1. */
        fun aspectParts(): Pair<Int, Int>? {
            val text = aspect ?: return if (board == "tile" || board == "sprite") 1 to 1 else 4 to 3
            val parts = text.split(':', 'x', '/').map { it.trim().toIntOrNull() }
            val w = parts.getOrNull(0); val h = parts.getOrNull(1)
            return if (parts.size == 2 && w != null && h != null && w in 1..100 && h in 1..100) w to h else null
        }

        /** What is wrong with it, in words, or null when JoyBrush can open it. */
        fun problem(): String? = when {
            board != null && board !in BOARDS -> "There is no \"$board\" board; choose image, animation, sprite or tile"
            guide != null && guide !in GUIDES -> "There is no \"$guide\" guide"
            aspectParts() == null -> "The shape \"$aspect\" is not like 16:9"
            fps != null && fps !in 1..60 -> "Frames per second go from 1 to 60"
            (columns ?: 1) !in 1..64 || (rows ?: 1) !in 1..64 -> "A sprite grid goes up to 64 x 64"
            else -> null
        }

        fun isEmpty() = board == null && guide == null
    }

    /**
     * Makes a new Library drawing and returns the Intent that opens it with [preset]. Null when no drawing can be made
     * (no drawing folder). Throws IllegalArgumentException with [Preset.problem] for a preset JoyBrush cannot open.
     * Not on the main thread (it writes the Library's list).
     */
    @JvmStatic
    fun newDrawing(context: Context, preset: Preset): Intent? {
        preset.problem()?.let { throw IllegalArgumentException(it) }
        val file = DrawingLibrary.create(context) ?: return null
        return put(DrawingLibrary.openIntent(context, file), preset)
    }

    fun put(intent: Intent, p: Preset): Intent = intent.apply {
        p.board?.let { putExtra(EXTRA_BOARD, it) }
        p.aspect?.let { putExtra(EXTRA_ASPECT, it) }
        p.fps?.let { putExtra(EXTRA_FPS, it) }
        p.columns?.let { putExtra(EXTRA_COLUMNS, it) }
        p.rows?.let { putExtra(EXTRA_ROWS, it) }
        p.guide?.let { putExtra(EXTRA_GUIDE, it) }
        p.name?.let { putExtra(EXTRA_NAME, it) }
    }

    /** The preset an Intent carries, or null when it carries none. */
    fun presetOf(intent: Intent?): Preset? {
        if (intent == null || (!intent.hasExtra(EXTRA_BOARD) && !intent.hasExtra(EXTRA_GUIDE))) return null
        fun int(key: String) = if (intent.hasExtra(key)) intent.getIntExtra(key, 0) else null
        return Preset(intent.getStringExtra(EXTRA_BOARD), intent.getStringExtra(EXTRA_ASPECT), int(EXTRA_FPS),
            int(EXTRA_COLUMNS), int(EXTRA_ROWS), intent.getStringExtra(EXTRA_GUIDE), intent.getStringExtra(EXTRA_NAME))
    }

    /** Taken once: a screen rebuilt after rotation or recovery does not make the board twice. */
    fun clear(intent: Intent) {
        for (k in listOf(EXTRA_BOARD, EXTRA_ASPECT, EXTRA_FPS, EXTRA_COLUMNS, EXTRA_ROWS, EXTRA_GUIDE, EXTRA_NAME)) intent.removeExtra(k)
    }
}
