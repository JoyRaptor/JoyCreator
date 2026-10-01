package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable

/** The tuft engine (brush version 4): a 2-D sable brush — tip, belly, trailing bristles, ink load, splay (R9 §3B). */
const val ENGINE_TUFT = "tuft"

/** The brush version that introduced `engine: "tuft"` and the `tuft` section. */
const val VERSION_TUFT = 4

/**
 * How a tuft brush behaves (R9 §3A, the owner's rulings O1–O13). Every number except [tipPx] is a 0..1 slider the owner
 * tunes on the phone ("Tune this brush…"); the brush's belly is the file's `size.base`, its colour and opacity are the
 * stroke's own. Read only when `engine == "tuft"`.
 *
 * The defaults are the Sable brush's starting point. They are NOT physics constants: they are where the owner's sliders
 * start, and he moves them.
 */
@Serializable data class TuftSpec(
    /** The needle point, as a DIAMETER in document px. Absolute: it does not grow when Size goes up (R9 §3.2). */
    val tipPx: Float = 1f,
    /** How long the lightest pressure stays a hairline before the belly opens (R9 §3.1, the "shelf"). */
    val shelf: Float = 0.6f,
    /** The brush's own steadying: hand jitter across the stroke becomes a calm drift of the whole line (O7). */
    val steady: Float = 0.6f,
    /** How long the contact grows when moving with some pressure — thin but long, calligraphic (O8). */
    val trail: Float = 0.5f,
    /** How quickly the bristles swing round to a new direction. High = stiff sable, low = floppy (O9). */
    val snap: Float = 0.6f,
    /** The thick spot and broken bristles at a sharp turn while the bristles re-settle (O9). */
    val corner: Float = 0.5f,
    /** Slow lines thicken a little and stay solid as the ink settles (O6). */
    val settle: Float = 0.5f,
    /** How much a fast stroke thins (R9 §3.5). */
    val speedThin: Float = 0.3f,
    /** How much ink the brush holds: high = long strokes before it runs dry (O3). */
    val ink: Float = 0.6f,
    /** How readily fast, heavy strokes break into dry-brush streaks (O3, M5). */
    val dry: Float = 0.5f,
    /** Fast curves: solid on the inside, dry and broken on the outside (O10). */
    val sweep: Float = 0.5f,
    /** Bristles spreading apart on jolts and quick lifts — split, rough ends (O11). */
    val splay: Float = 0.5f,
    /** How fine the dry-brush streaks are: low = a few coarse clumps, high = many fine bristles. */
    val bristles: Float = 0.5f,
    /** How much the paper's tooth breaks up the dry parts. */
    val tooth: Float = 0.5f,
    /** Ink thrown off by quick flicks, sudden presses and anything jolty (O1). */
    val spatter: Float = 0.3f,
    /** A broken hair or two, only when painting with the belly: a thin, on-and-off line beside the stroke (O4). */
    val strays: Float = 0.4f,
    /** Laying the pen over spreads the belly wider and stretches it on the diagonal (pens that report tilt only). */
    val tilt: Float = 0.5f,
    /**
     * Pressing the brush flat for shadows: past the line-weight range the belly spreads to the widest the bristles go
     * (owner, 2026-09-30: "three modes — detail, line weight, shadows"). 0 = no press-flat zone … 1 = five times wider.
     */
    val flatten: Float = 0.5f,
    /** Bristle marks even in a loaded brush: broken edges and a streaky light side. */
    val action: Float = 0.4f,
    /**
     * A laid-over brush pressed lightly: how wispy and scratchy the far end of its body is, where only a few bristles
     * graze the tooth (owner, 2026-09-30: "shading, even though it's a pen"). Pressed hard it is black whatever this says.
     */
    val graze: Float = 0.6f,
    /**
     * Which end of a laid-over brush grazes: false = the far, belly end (solid at the point on the pen); true = the point
     * end (solid out along the lean). The owner's preference (2026-10-01: "a checkbox to flip this").
     */
    val grazeAtPoint: Boolean = false,
)
