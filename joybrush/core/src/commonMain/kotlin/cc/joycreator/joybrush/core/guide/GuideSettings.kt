package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.shape.Pt

/**
 * What the person has switched on and where it is (JB-2.12). A [Guide] is pure geometry (JB-2.12a); this is the state
 * around it. It is NOT part of the drawing: it is not in `JbDocument`, so it cannot reach `document.json` or any export
 * (blueprint §3: "helpers are overlays, never part of the art"). Lead ruling R49: it IS remembered between visits, in the
 * app's own preferences ([encode]/[decode]), so a three-point perspective setup is not rebuilt every time.
 */
data class GuideSettings(
    val grid: Guide.Grid? = null,
    val isometric: Guide.Isometric? = null,
    val perspective: Guide.Perspective? = null,
    /** Ruler and ellipse tracers, any number. */
    val tracers: List<Guide> = emptyList(),
    /** Strokes are pulled onto the guides. Off keeps the lines and drops the pull (Decision 3). */
    val snapEnabled: Boolean = true,
) {
    /** Everything switched on, in the order they compete: tracers first, then direction guides (JB-2.12a Decision 4). */
    fun all(): List<Guide> {
        val out = ArrayList<Guide>()
        for (t in tracers) if (out.none { it === t }) out.add(t)
        grid?.let { out.add(it) }
        isometric?.let { out.add(it) }
        perspective?.let { out.add(it) }
        return out
    }

    val isEmpty: Boolean get() = grid == null && isometric == null && perspective == null && tracers.isEmpty()

    /** Why these settings may not be used, as a sentence naming the guide, or null. Never a silent repair. */
    fun refusalFor(): String? {
        grid?.let { g ->
            if (!(g.spacing > 0.0) || !g.spacing.isFinite()) return "The grid's spacing has to be more than 0, and it is ${g.spacing}."
            if (!finite(g.origin) || !g.angle.isFinite()) return "The grid's position is not a number."
        }
        isometric?.let { g ->
            if (!(g.spacing > 0.0) || !g.spacing.isFinite()) return "The isometric grid's spacing has to be more than 0, and it is ${g.spacing}."
            if (!finite(g.origin)) return "The isometric grid's position is not a number."
        }
        perspective?.let { p ->
            val n = p.vanishingPoints.size
            if (n !in 1..3) return "A perspective guide has 1, 2 or 3 vanishing points, and this one has $n."
            if (p.vanishingPoints.any { !finite(it) }) return "A vanishing point's position is not a number."
        }
        for (t in tracers) when (t) {
            is Guide.Ruler -> if (!finite(t.a) || !finite(t.b)) return "A ruler's end is not a number."
            is Guide.EllipseTracer -> if (!finite(t.center) || !(t.rx > 0.0) || !(t.ry > 0.0) || !t.rotation.isFinite()) {
                return "An ellipse guide needs a centre and two radii above 0."
            }
            else -> return "Only rulers and ellipses can be tracers."
        }
        return null
    }

    /** One line per guide, for the app's preferences. [decode] reads it back. */
    fun encode(): String = buildString {
        append("snap ").append(if (snapEnabled) 1 else 0)
        grid?.let { append("\ngrid ${it.spacing} ${it.origin.x} ${it.origin.y} ${it.angle}") }
        isometric?.let { append("\niso ${it.spacing} ${it.origin.x} ${it.origin.y}") }
        perspective?.let { p -> append("\npersp"); for (v in p.vanishingPoints) append(" ${v.x} ${v.y}") }
        for (t in tracers) when (t) {
            is Guide.Ruler -> append("\nruler ${t.a.x} ${t.a.y} ${t.b.x} ${t.b.y}")
            is Guide.EllipseTracer -> append("\nellipse ${t.center.x} ${t.center.y} ${t.rx} ${t.ry} ${t.rotation}")
            else -> Unit
        }
    }

    companion object {
        /** A grid a person switches on starts here: 100 document px, at the origin, square to the page. */
        val DEFAULT_GRID = Guide.Grid(100.0)

        /** Nothing on. A new install starts here. */
        val NONE = GuideSettings()

        /** The smallest grid spacing the screen shows, in screen px (GuideLines.MIN_SCREEN_GAP). */
        const val MIN_SCREEN_PX = 8f

        /**
         * [g] with its spacing doubled until its lines are at least [minScreenPx] apart on screen at [zoom] — DOUBLED,
         * never halved, so it is monotone and stops (Decision 8). A zoom that is not a positive number is read as 1, the
         * same guard SizeOpacityDrag and Nudge use.
         */
        fun withMinScreenSpacing(g: Guide.Grid, zoom: Float, minScreenPx: Float = MIN_SCREEN_PX): Guide.Grid {
            val z = if (zoom.isFinite() && zoom > 0f) zoom.toDouble() else 1.0
            if (!(g.spacing > 0.0) || !g.spacing.isFinite()) return g
            var s = g.spacing
            var guard = 0
            // A hair of slack: a zoom like 0.02f is 0.0199999996 as a float, and 400 x that must count as 8 px, not 7.99999.
            val need = minScreenPx - 1e-4
            while (s * z < need && guard < 64) { s *= 2.0; guard++ }
            return g.copy(spacing = s)
        }

        /** The inverse of [encode]. A line that cannot be read is skipped; settings that [refusalFor] refuses are [NONE]. */
        fun decode(text: String?): GuideSettings {
            if (text.isNullOrBlank()) return NONE
            var s = NONE
            val tracers = ArrayList<Guide>()
            for (line in text.lines()) {
                val parts = line.trim().split(' ')
                val nums = parts.drop(1).map { it.toDoubleOrNull() }
                if (nums.any { it == null }) continue
                val n = nums.map { it!! }
                when (parts[0]) {
                    "snap" -> if (n.size == 1) s = s.copy(snapEnabled = n[0] != 0.0)
                    "grid" -> if (n.size == 4) s = s.copy(grid = Guide.Grid(n[0], Pt(n[1], n[2]), n[3]))
                    "iso" -> if (n.size == 3) s = s.copy(isometric = Guide.Isometric(n[0], Pt(n[1], n[2])))
                    "persp" -> if (n.size >= 2 && n.size % 2 == 0) {
                        s = s.copy(perspective = Guide.Perspective((n.indices step 2).map { Pt(n[it], n[it + 1]) }))
                    }
                    "ruler" -> if (n.size == 4) tracers.add(Guide.Ruler(Pt(n[0], n[1]), Pt(n[2], n[3])))
                    "ellipse" -> if (n.size == 5) tracers.add(Guide.EllipseTracer(Pt(n[0], n[1]), n[2], n[3], n[4]))
                }
            }
            s = s.copy(tracers = tracers)
            return if (s.refusalFor() == null) s else NONE
        }

        private fun finite(p: Pt) = p.x.isFinite() && p.y.isFinite()
    }
}
