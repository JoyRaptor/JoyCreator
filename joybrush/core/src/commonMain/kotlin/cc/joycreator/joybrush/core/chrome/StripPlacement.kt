package cc.joycreator.joybrush.core.chrome

/**
 * Where the tool strip sits (JB-2.01, owner decisions 1 and 5): hugging the LEFT or RIGHT edge, at a
 * fraction [along] of the free height, 0 = as high as it can go and 1 = as low. The strip is dragged
 * by its grip and let go anywhere; [dropAt] decides where it lands. Pure, so "it snaps to the nearer
 * edge and never hangs off the screen" is a test rather than a hope.
 */
data class StripPlacement(val edge: Edge, val along: Float) {

    enum class Edge { LEFT, RIGHT }

    /** One line for the preferences file, e.g. `LEFT 0.3`. [decode] reads it back. */
    fun encode(): String = "${edge.name} $along"

    /** The strip's top, in px, inside a free height of [freeHeight] for a strip [stripHeight] tall. */
    fun topPx(freeHeight: Float, stripHeight: Float): Float = (freeHeight - stripHeight).coerceAtLeast(0f) * along

    companion object {
        /** Left edge, a little above the middle: under the thumb of a person holding the phone in the other hand. */
        val DEFAULT = StripPlacement(Edge.LEFT, 0.3f)

        /**
         * Where a strip let go of with its centre at ([centreX], [centreY]) lands, inside a free area
         * [width] × [height] px, for a strip [stripHeight] tall. The nearer side edge wins (the exact
         * middle goes LEFT, the default side), and the height is clamped so the whole strip stays on
         * screen. Non-finite input keeps [DEFAULT]'s value for whatever cannot be read.
         */
        fun dropAt(centreX: Float, centreY: Float, width: Float, height: Float, stripHeight: Float): StripPlacement {
            val edge = if (!centreX.isFinite() || !width.isFinite() || centreX <= width / 2f) Edge.LEFT else Edge.RIGHT
            val free = height - stripHeight
            if (!centreY.isFinite() || !free.isFinite() || free <= 0f) return StripPlacement(edge, DEFAULT.along)
            val top = centreY - stripHeight / 2f
            return StripPlacement(edge, (top / free).coerceIn(0f, 1f))
        }

        /** The inverse of [encode]. Anything unreadable is [DEFAULT]: a broken preference must not lose the strip. */
        fun decode(text: String?): StripPlacement {
            val parts = text?.trim()?.split(' ') ?: return DEFAULT
            if (parts.size != 2) return DEFAULT
            val edge = Edge.entries.firstOrNull { it.name == parts[0] } ?: return DEFAULT
            val along = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return DEFAULT
            return StripPlacement(edge, along.coerceIn(0f, 1f))
        }
    }
}
