package cc.joycreator.joybrush.android

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import cc.joycreator.joybrush.core.doc.BoardKind

/**
 * Every colour Joy Brush draws with, read once from `res/values/jb_tokens.xml` (D.01).
 *
 * The rule for Joy Brush code: **no hex literal, ever**. A colour is either a field of
 * [Palette] or one of the two gradients below. Changing `jb_room_start` / `jb_room_end` in
 * that one XML file recolours the room, the Canvas board and everything drawn from them,
 * with no other edit anywhere in the project.
 *
 * The other tokens mirror the app's own `studio_tokens.xml`; they are copied rather than
 * referenced because an Android library cannot see the consuming app's resources, and
 * `tools/check_joybrush_tokens.py` fails the build's review if a copy drifts.
 *
 * The same rule the app follows holds here: a gradient means ACTION or IDENTITY, a state
 * colour is always a RING, and only `stateDestroy` ever fills.
 */
object JbColors {

    private var cached: Palette? = null

    /**
     * The whole token set, loaded once per process. Any thread may call this: a race only
     * ever builds a second, identical [Palette].
     */
    @JvmStatic
    fun palette(context: Context): Palette {
        val have = cached
        if (have != null) return have
        val built = load(context)
        cached = built
        return built
    }

    /**
     * The room gradient, left to right: `jb_room_start` → `jb_room_end`. Identity, so it
     * dresses a room, a hero bar or a board tab — never a button. Primary actions inside
     * Joy Brush stay on the app's own aqua-to-lime action gradient.
     */
    @JvmStatic
    fun roomGradient(context: Context): GradientDrawable {
        val p = palette(context)
        val stops = intArrayOf(p.roomStart, p.roomEnd)
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, stops)
    }

    /**
     * A board's identity gradient, left to right. Canvas wears Joy Brush itself; the
     * animation, sprite, puppet and character boards wear the app each one feeds, so a
     * board is recognisable before you have read its name.
     *
     * [kind] is the document model's own frozen enum rather than a second copy of it: one
     * list of board kinds, so a new board cannot arrive without a colour or a colour
     * without a board.
     */
    @JvmStatic
    fun boardGradient(context: Context, kind: BoardKind): GradientDrawable {
        val p = palette(context)
        val stops: IntArray = when (kind) {
            BoardKind.CANVAS -> intArrayOf(p.boardCanvasStart, p.boardCanvasEnd)
            BoardKind.ANIMATION -> intArrayOf(p.boardAnimationStart, p.boardAnimationEnd)
            BoardKind.SPRITE -> intArrayOf(p.boardSpriteStart, p.boardSpriteEnd)
            BoardKind.PUPPET -> intArrayOf(p.boardPuppetStart, p.boardPuppetEnd)
            BoardKind.CHARACTER -> intArrayOf(p.boardCharacterStart, p.boardCharacterEnd)
        }
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, stops)
    }

    private fun load(context: Context): Palette {
        fun get(resId: Int): Int = ContextCompat.getColor(context, resId)
        return Palette(
            roomStart = get(R.color.jb_room_start),
            roomEnd = get(R.color.jb_room_end),
            boardCanvasStart = get(R.color.jb_board_canvas_start),
            boardCanvasEnd = get(R.color.jb_board_canvas_end),
            boardAnimationStart = get(R.color.jb_board_animation_start),
            boardAnimationEnd = get(R.color.jb_board_animation_end),
            boardSpriteStart = get(R.color.jb_board_sprite_start),
            boardSpriteEnd = get(R.color.jb_board_sprite_end),
            boardPuppetStart = get(R.color.jb_board_puppet_start),
            boardPuppetEnd = get(R.color.jb_board_puppet_end),
            boardCharacterStart = get(R.color.jb_board_character_start),
            boardCharacterEnd = get(R.color.jb_board_character_end),
            stateSelected = get(R.color.jb_state_selected),
            stateLive = get(R.color.jb_state_live),
            stateCareful = get(R.color.jb_state_careful),
            stateDestroy = get(R.color.jb_state_destroy),
            guide = get(R.color.jb_guide),
            ground = get(R.color.jb_ground),
            surface = get(R.color.jb_surface),
            panel = get(R.color.jb_panel),
            sunk = get(R.color.jb_sunk),
            raised = get(R.color.jb_raised),
            line = get(R.color.jb_line),
            ink = get(R.color.jb_ink),
            inkDim = get(R.color.jb_ink_dim),
            inkFaint = get(R.color.jb_ink_faint),
            inkOff = get(R.color.jb_ink_off),
            label = get(R.color.jb_label),
            drawerInk = get(R.color.jb_drawer_ink),
            drawerDim = get(R.color.jb_drawer_dim),
            drawerLabel = get(R.color.jb_drawer_label)
        )
    }
}

/**
 * One flat read of `jb_tokens.xml`, named after the XML tokens it came from. Held as a
 * class rather than as `object` fields so that a screen which forgets to load them cannot
 * quietly draw transparent black.
 */
class Palette internal constructor(
    /** Joy Brush's own gradient, the pair the owner may change. */
    val roomStart: Int,
    val roomEnd: Int,
    val boardCanvasStart: Int,
    val boardCanvasEnd: Int,
    val boardAnimationStart: Int,
    val boardAnimationEnd: Int,
    val boardSpriteStart: Int,
    val boardSpriteEnd: Int,
    val boardPuppetStart: Int,
    val boardPuppetEnd: Int,
    val boardCharacterStart: Int,
    val boardCharacterEnd: Int,
    /** The one thing you are pointing at: a ring, never a fill. */
    val stateSelected: Int,
    /** Recording, playing, the playhead. */
    val stateLive: Int,
    /** Warning, approximate, unsaved. */
    val stateCareful: Int,
    /** Delete, or something being lost. The one state allowed to fill. */
    val stateDestroy: Int,
    /** A guide or snap line (JB-2.12): a hint, drawn as a thin line, never a fill and never a state. */
    val guide: Int,
    val ground: Int,
    val surface: Int,
    val panel: Int,
    /** A well cut into a panel. */
    val sunk: Int,
    val raised: Int,
    val line: Int,
    /** Ink on ordinary surfaces. */
    val ink: Int,
    val inkDim: Int,
    val inkFaint: Int,
    val inkOff: Int,
    val label: Int,
    /** Ink over the picture, on a frosted drawer. Nothing outside one may use these. */
    val drawerInk: Int,
    val drawerDim: Int,
    val drawerLabel: Int
)
