package cc.joycreator.joybrush.android

import android.content.Context
import android.content.Intent
import cc.joycreator.joybrush.android.library.DrawingLibrary

/**
 * Opening JoyBrush with a board ready (BOT-7, owner 11-15: Joybot's create tree, "new animation" and the like).
 *
 * The one contract: call [newDrawing] off the main thread, start the Intent it returns. JoyBrush opens a NEW Library
 * drawing with the [Preset]'s board already made in the middle of the screen (no rectangle to drag) and its guide on,
 * as one Undo step for the board. Every field is optional. A field JoyBrush cannot use (an unknown kind, a ratio that
 * is not "w:h", a number out of range) is left out quietly, so the worst case is a plain new drawing.
 *
 * The same thing as plain extras, for a caller that cannot link this class: [EXTRA_BOARD] and the rest (or the `jb_*`
 * names Joybot first asked for) on `DrawingLibrary.openIntent(context, DrawingLibrary.create(context))`.
 */
object JoyBrushLaunch {
    /** "image", "animation", "sprite" or "tile" (a tile is an image board with tiling shown). Alias: jb_new_kind. */
    const val EXTRA_BOARD = "cc.joycreator.joybrush.extra.BOARD"
    /** The board's shape as "w:h", e.g. "16:9", "3:1", "1:1". Alias: jb_ratio. */
    const val EXTRA_ASPECT = "cc.joycreator.joybrush.extra.ASPECT"
    /** Animation only: frames per second, 1 to 60 (default 12). Int. Alias: jb_fps. */
    const val EXTRA_FPS = "cc.joycreator.joybrush.extra.FPS"
    /** Sprite only: the grid of cells (default 4 x 1). Ints. Alias: jb_grid = "4x4". */
    const val EXTRA_COLUMNS = "cc.joycreator.joybrush.extra.COLUMNS"
    const val EXTRA_ROWS = "cc.joycreator.joybrush.extra.ROWS"
    /** Sprite only: each cell's side in px, 8 to 1024. Int. Alias: jb_cell_px. */
    const val EXTRA_CELL_PX = "cc.joycreator.joybrush.extra.CELL_PX"
    /** Tile only: the tile's side in px, 16 to 4096. Int. Alias: jb_tile_px. */
    const val EXTRA_TILE_PX = "cc.joycreator.joybrush.extra.TILE_PX"
    /** "grid", "isometric", "perspective-1/2/3", "ruler", "symmetry" or "radial". Alias: jb_perspective = 1..3. */
    const val EXTRA_GUIDE = "cc.joycreator.joybrush.extra.GUIDE"
    /** The board's name (default: "Image", "Animation", "Sprite", "Tile"). */
    const val EXTRA_NAME = "cc.joycreator.joybrush.extra.NAME"

    val BOARDS = listOf("image", "animation", "sprite", "tile")
    val GUIDES = listOf("grid", "isometric", "perspective-1", "perspective-2", "perspective-3", "ruler", "symmetry", "radial")
    private val ALL_EXTRAS = listOf(EXTRA_BOARD, EXTRA_ASPECT, EXTRA_FPS, EXTRA_COLUMNS, EXTRA_ROWS, EXTRA_CELL_PX,
        EXTRA_TILE_PX, EXTRA_GUIDE, EXTRA_NAME, "jb_new_kind", "jb_ratio", "jb_fps", "jb_tile_px", "jb_grid", "jb_cell_px", "jb_perspective")

    data class Preset(
        val board: String? = null,
        val aspect: String? = null,
        val fps: Int? = null,
        val columns: Int? = null,
        val rows: Int? = null,
        val guide: String? = null,
        val name: String? = null,
        /** Sprite: each cell's side in px. Then the board is exactly columns x rows cells of this size. */
        val cellPx: Int? = null,
        /** Tile: the tile's side in px. Then the board is exactly this square. */
        val tilePx: Int? = null,
    ) {
        /** Width and height of the aspect, or the board's default; null when the text is not "w:h" with w, h >= 1. */
        fun aspectParts(): Pair<Int, Int>? {
            val text = aspect ?: return if (board == "tile" || board == "sprite") 1 to 1 else 4 to 3
            val parts = text.split(':', 'x', 'X', '/').map { it.trim().toIntOrNull() }
            val w = parts.getOrNull(0); val h = parts.getOrNull(1)
            return if (parts.size == 2 && w != null && h != null && w in 1..100 && h in 1..100) w to h else null
        }

        /** What is wrong with it, in words, or null when every field can be used as given. */
        fun problem(): String? = when {
            board != null && board !in BOARDS -> "There is no \"$board\" board; choose image, animation, sprite or tile"
            guide != null && guide !in GUIDES -> "There is no \"$guide\" guide"
            aspectParts() == null -> "The shape \"$aspect\" is not like 16:9"
            fps != null && fps !in 1..60 -> "Frames per second go from 1 to 60"
            (columns ?: 1) !in 1..64 || (rows ?: 1) !in 1..64 -> "A sprite grid goes up to 64 x 64"
            cellPx != null && cellPx !in 8..1024 -> "A sprite cell is 8 to 1024 px"
            tilePx != null && tilePx !in 16..4096 -> "A tile is 16 to 4096 px"
            else -> null
        }

        /** Every field JoyBrush can use, the rest dropped: what is actually opened. */
        fun usable(): Preset = Preset(
            board = board?.lowercase()?.takeIf { it in BOARDS },
            aspect = aspect?.takeIf { copy(aspect = it).aspectParts() != null },
            fps = fps?.takeIf { it in 1..60 },
            columns = columns?.takeIf { it in 1..64 },
            rows = rows?.takeIf { it in 1..64 },
            guide = guide?.lowercase()?.takeIf { it in GUIDES },
            name = name?.trim()?.take(60)?.takeIf { it.isNotEmpty() },
            cellPx = cellPx?.takeIf { it in 8..1024 },
            tilePx = tilePx?.takeIf { it in 16..4096 },
        )

        fun isEmpty() = board == null && guide == null
    }

    /**
     * Makes a new Library drawing and returns the Intent that opens it with [preset] (its usable fields). Null when no
     * drawing can be made (no drawing folder). Not on the main thread (it writes the Library's list).
     */
    @JvmStatic
    fun newDrawing(context: Context, preset: Preset): Intent? {
        val file = DrawingLibrary.create(context) ?: return null
        return put(DrawingLibrary.openIntent(context, file), preset.usable())
    }

    fun put(intent: Intent, p: Preset): Intent = intent.apply {
        p.board?.let { putExtra(EXTRA_BOARD, it) }
        p.aspect?.let { putExtra(EXTRA_ASPECT, it) }
        p.fps?.let { putExtra(EXTRA_FPS, it) }
        p.columns?.let { putExtra(EXTRA_COLUMNS, it) }
        p.rows?.let { putExtra(EXTRA_ROWS, it) }
        p.guide?.let { putExtra(EXTRA_GUIDE, it) }
        p.name?.let { putExtra(EXTRA_NAME, it) }
        p.cellPx?.let { putExtra(EXTRA_CELL_PX, it) }
        p.tilePx?.let { putExtra(EXTRA_TILE_PX, it) }
    }

    /** The preset an Intent carries (its usable fields; the `jb_*` names too), or null when it carries none. */
    fun presetOf(intent: Intent?): Preset? {
        if (intent == null || ALL_EXTRAS.none { intent.hasExtra(it) }) return null
        // Numbers may come as Int, Long or text; anything else is left out.
        fun int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { k ->
            if (!intent.hasExtra(k)) null else when (val v = intent.extras?.get(k)) {
                is Int -> v; is Long -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null
            }
        }
        fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> intent.extras?.get(k)?.toString() }
        val grid = text("jb_grid")?.split('x', 'X', '*')?.map { it.trim().toIntOrNull() }
        val perspective = int("jb_perspective")?.takeIf { it in 1..3 }?.let { "perspective-$it" }
        return Preset(
            board = text(EXTRA_BOARD, "jb_new_kind"),
            aspect = text(EXTRA_ASPECT, "jb_ratio"),
            fps = int(EXTRA_FPS, "jb_fps"),
            columns = int(EXTRA_COLUMNS) ?: grid?.getOrNull(0),
            rows = int(EXTRA_ROWS) ?: grid?.getOrNull(1),
            guide = text(EXTRA_GUIDE) ?: perspective,
            name = text(EXTRA_NAME),
            cellPx = int(EXTRA_CELL_PX, "jb_cell_px"),
            tilePx = int(EXTRA_TILE_PX, "jb_tile_px"),
        ).usable()
    }

    /** Taken once: a screen rebuilt after rotation or recovery does not make the board twice. */
    fun clear(intent: Intent) { for (k in ALL_EXTRAS) intent.removeExtra(k) }
}
