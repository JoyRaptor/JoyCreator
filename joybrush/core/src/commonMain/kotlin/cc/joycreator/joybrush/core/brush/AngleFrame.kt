package cc.joycreator.joybrush.core.brush

/**
 * How ONE symmetry copy sees the brush's angles.
 *
 * A symmetry copy is not a picture of the original stroke: the engine runs the WHOLE brush again
 * on the pen's samples transformed into the copy's frame. So the copy's dabs are made by the same
 * code, from the same file, with the same random stream — and the only thing that makes them a
 * copy rather than a second, unrelated stroke is this frame.
 *
 * ## What the frame does, and what it must NOT do
 *
 * [BrushDabber.look] composes a dab's angle out of three terms:
 *
 *  1. the BASE — the file's `tip.angle`, in degrees, turned to radians,
 *  2. the DIRECTION — the stroke's direction, added only when the file's `tip.followDirection` is on,
 *  3. the JITTER — the signed `angleJitter` draw.
 *
 * The frame owns the base and the jitter. It does NOT own the direction: a copy's lane is fed the
 * samples already transformed into the copy's frame, so the tracker's answer there IS the copy's
 * direction, and turning it again would apply the copy's rotation twice.
 *
 * ## The four cases
 *
 * With `A` the original's composed tip angle, `dir` its stroke direction and `jitter` its signed
 * jitter:
 *
 * | copy                | followDirection | base becomes   | jitter becomes |
 * |---------------------|-----------------|----------------|----------------|
 * | rotation by θ       | on              | `base`         | unchanged      |
 * | rotation by θ       | off             | `base + θ`     | unchanged      |
 * | mirror about axis φ' | on             | `−base`        | `−jitter`      |
 * | mirror about axis φ' | off            | `2φ' − base`   | `−jitter`      |
 *
 * A base that is an OFFSET from the direction is left alone by a rotation (the direction already
 * carries the θ) and has its sign flipped by a reflection (a right-leaning tip mirrors to a
 * left-leaning one). A base that is an ABSOLUTE angle turns by θ under a rotation and is
 * reflected about the axis under a reflection — an angle α reflected about φ' is 2φ' − α.
 *
 * ## The axis is the COPY's own
 *
 * [mirrorAxis] is the axis THE COPY is reflected in, which is not necessarily the guide's line.
 * A kaleidoscope copy is the composition `R(θ) ∘ M(φ)` — a rotation after a reflection — and that
 * composition is ITSELF a reflection, about the axis `φ + θ/2`. Whoever builds a frame from a
 * copy must therefore work out the copy's own axis and pass THAT; passing the guide's line would
 * mirror the copy about the wrong line, which for a kaleidoscope is a whole extra half-turn.
 */
data class AngleFrame(
    /** The copy's rotation, in radians. Unused when [mirrorAxis] is set: a reflection's own axis already carries it. */
    val rotation: Double = 0.0,
    /**
     * The axis THE COPY is reflected in, in radians — the copy's OWN axis, not the guide's line
     * (see the class note). Null for a copy that is only rotated.
     */
    val mirrorAxis: Double? = null,
    /**
     * Whether the brush's tip angle is an OFFSET from the stroke direction (true) or an ABSOLUTE
     * angle (false). This is the FILE's `tip.followDirection`, copied in so the frame says what it
     * means on its own: whoever builds a frame must pass the brush's own setting, because the
     * four-case rule turns on it.
     */
    val followDirection: Boolean = false,
) {

    /** True when this copy is a reflection, and not merely a rotation. */
    val mirrored: Boolean get() = mirrorAxis != null

    /**
     * Whether the scatter's ACROSS component must be negated: a reflection reverses handedness, so
     * an offset rebuilt in the copy's own frame has to go the other way round the stroke or the
     * copy comes out with the original's texture mirrored back into a flip rather than a mirror.
     */
    val flipAcross: Boolean get() = mirrorAxis != null

    /**
     * The tip's base angle for this copy, in radians. [baseRad] is the file's own base, already
     * turned to radians and already guarded against the file's NaN.
     *
     * The four cases, in code:
     *  - a rotation with an offset base leaves it alone,
     *  - a rotation with an absolute base turns it by the rotation,
     *  - a reflection with an offset base flips its sign,
     *  - a reflection with an absolute base reflects it about the copy's own axis.
     */
    fun base(baseRad: Float): Float {
        val axis = mirrorAxis
        return if (axis == null) {
            if (followDirection) {
                baseRad
            } else if (rotation == 0.0) {
                // A rotation of nothing must not touch the value at all — not even the sign of a
                // zero. The identity frame has to be a no-op down to the bit, and `-0.0f + 0.0f` is
                // `+0.0f`.
                baseRad
            } else {
                baseRad + rotation.toFloat()
            }
        } else {
            if (followDirection) -baseRad else (2.0 * axis - baseRad).toFloat()
        }
    }

    /**
     * The signed jitter for this copy, in radians. A reflection flips the sign of every signed
     * offset; a rotation does not.
     */
    fun jitter(jitterRad: Float): Float = if (mirrored) -jitterRad else jitterRad

    companion object {
        /**
         * The frame of a stroke that is not a copy: it changes nothing, down to the bit. This is
         * the default every existing brush draws with.
         */
        val IDENTITY = AngleFrame()
    }
}
