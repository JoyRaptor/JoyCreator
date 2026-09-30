package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.blend.BlendRgb
import cc.joycreator.joybrush.core.doc.BlendMode

/**
 * Which number `jb_composite.frag` is told for each of the 27 [BlendMode]s (JB-2.20b).
 *
 * The 26 modes the Studio has use the STUDIO'S OWN code, through [BlendRgb.codeOf] — the name-keyed
 * table `BlendParityTest` already pins against the Studio's `BlendModes.ALL`. `ordinal` is never used:
 * it is wrong for 22 of the 27 (Joy Brush's enum is in its own frozen order, the Studio's is another).
 * [BlendMode.ERASE_BELOW] has no Studio code — it is destination-out, not a blend term — so it gets
 * [ERASE_CODE], which is outside 0..25 on purpose and which the shader tests for separately.
 */
object BlendCodes {

    /** The shader's "this is an erase, not a blend" value. Never a Studio code. */
    const val ERASE_CODE = -1f

    fun codeOf(mode: BlendMode): Float =
        if (mode == BlendMode.ERASE_BELOW) ERASE_CODE else BlendRgb.codeOf(mode).toFloat()

    /** Every mode the GPU composites. All of them; a mode added to the enum is added here or a test fails. */
    val supported: Set<BlendMode> get() = BlendMode.entries.toSet()
}
