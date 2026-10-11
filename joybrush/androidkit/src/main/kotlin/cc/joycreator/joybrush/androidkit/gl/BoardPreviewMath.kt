package cc.joycreator.joybrush.androidkit.gl

import kotlin.math.floor

/**
 * The few numbers [GlBoardPreview] needs that do not depend on a GL context, so they are unit-tested on the JVM.
 *
 * WHY THE TEXTURE IS BIGGER THAN THE BOARD. Dragging a tile board's edge resizes the crop every frame. Allocating
 * an exact w x h texture each time would delete and make a texture (and a framebuffer) per drag step. Instead the
 * texture is rounded up to a multiple of [STEP] and kept; the crop is drawn into its lower-left w x h corner and
 * the shader reads only that corner, so a 1-pixel drag costs no allocation at all.
 */
internal object BoardPreviewMath {
    /** Allocation granularity in pixels. Small enough to waste little, large enough that a drag rarely crosses one. */
    const val STEP = 256

    /** The side of the texture that holds [used] pixels: the next multiple of [STEP], never above [maxTexture]. */
    fun allocation(used: Int, maxTexture: Int): Int {
        require(used in 1..maxTexture) { "This board is too large to preview on this phone" }
        val rounded = (used.toLong() + STEP - 1) / STEP * STEP
        return minOf(rounded, maxTexture.toLong()).toInt()
    }

    /**
     * The texel the shader reads for a board coordinate [uv] (board widths from the board's origin; any real number,
     * so a neighbour copy is -1.25 or 3.75). It mirrors `texelFetch(u_crop, ivec2(min(floor(fract(uv) * used), used - 1)), 0)`
     * in the fragment shader exactly: the repeat is exact for the used w x h whatever the texture size, and the
     * clamp keeps a rounding-up fract() from ever reading the stale texels beyond [used].
     */
    fun texel(uv: Double, used: Int): Int {
        require(used >= 1)
        val fract = uv - floor(uv)
        return minOf(floor(fract * used).toInt(), used - 1)
    }
}
