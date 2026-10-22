package cc.joycreator.joybrush.core.brush

/** DRAFT: pure bounded editing, no rendering/serialization/Android ownership. Not compiled/tested. */
data class FalloffPointEdit(val profile: TipProfileSpec, val selected: Int)
data class FalloffEditorPosition(val x: Float, val y: Float)

object FalloffCurveEditing {
    /** Follow the authoritative current validator, never truncate a saved curve to a UI limit. */
    val maxPoints: Int get() = minOf(256, BrushValidate.MAX_CURVE_POINTS)

    fun checked(profile: TipProfileSpec): TipProfileSpec {
        if (profile.kind != TipProfile.custom) throw BrushException("Choose a custom falloff to edit its points")
        val errors = BrushValidate.validate(BrushPreset(
            id = "falloff-editor-validation", name = "Falloff", size = Param(1f),
            tip = TipSpec(profile = profile),
        ))
        if (errors.isNotEmpty()) throw BrushException(errors.joinToString("; "))
        if (profile.curve.size > maxPoints) throw BrushException("This editor supports at most $maxPoints falloff points")
        return snapshot(profile)
    }

    fun snapshot(profile: TipProfileSpec): TipProfileSpec = profile.copy(curve = profile.curve.map { it.toList() })

    /** Requires a previously checked profile. Only one point changes; no resampling/sorting. */
    fun move(profile: TipProfileSpec, index: Int, radius: Float, coverage: Float): TipProfileSpec {
        require(index in profile.curve.indices)
        if (!radius.isFinite() || !coverage.isFinite()) throw BrushException("Falloff coordinates must be finite")
        if (index == profile.curve.lastIndex) return profile // Exact [1, 0] rim is fixed.
        val old = profile.curve[index]
        val x = if (index == 0) old[0] else {
            val low = nextRadius(profile.curve[index - 1][0])
            val high = previousRadius(profile.curve[index + 1][0])
            // A valid existing interior point guarantees at least its own representable radius.
            if (low > high) throw BrushException("There is no representable radius between these neighbors")
            radius.coerceIn(low, high)
        }
        val y = coverage.coerceIn(0f, 1f)
        if (old[0].toBits() == x.toBits() && old[1].toBits() == y.toBits()) return profile
        return profile.copy(curve = profile.curve.mapIndexed { i, point -> if (i == index) listOf(x, y) else point })
    }

    /** Insert halfway through the selected segment; at the rim, use the preceding segment. */
    fun addAfter(profile: TipProfileSpec, selected: Int): FalloffPointEdit {
        require(selected in profile.curve.indices)
        if (profile.curve.size >= maxPoints) throw BrushException("A falloff can have at most $maxPoints points")
        val segment = minOf(selected, profile.curve.lastIndex - 1)
        val a = profile.curve[segment]; val b = profile.curve[segment + 1]
        val low = nextRadius(a[0]); val high = previousRadius(b[0])
        if (low > high) throw BrushException("These neighboring radii are too close to add a point")
        val middle = ((a[0].toDouble() + b[0].toDouble()) * .5).toFloat().coerceIn(low, high)
        val t = (middle - a[0]) / (b[0] - a[0])
        val coverage = (a[1] + (b[1] - a[1]) * t).coerceIn(0f, 1f)
        val points = profile.curve.toMutableList().apply { add(segment + 1, listOf(middle, coverage)) }
        return FalloffPointEdit(profile.copy(curve = points.toList()), segment + 1)
    }

    fun remove(profile: TipProfileSpec, selected: Int): FalloffPointEdit {
        require(selected in profile.curve.indices)
        if (selected == 0 || selected == profile.curve.lastIndex) throw BrushException("The two falloff endpoints stay in the curve")
        val points = profile.curve.filterIndexed { index, _ -> index != selected }
        return FalloffPointEdit(profile.copy(curve = points), minOf(selected, points.lastIndex))
    }

    // Radius is nonnegative. Adjacent Float values preserve strict ordering even for microscopic
    // imported intervals; a fixed UI epsilon would incorrectly erase/merge such points.
    private fun nextRadius(value: Float): Float = if (value == 0f) Float.MIN_VALUE else Float.fromBits(value.toBits() + 1)
    private fun previousRadius(value: Float): Float = Float.fromBits(value.toBits() - 1)
}

data class FalloffEditorGeometry(val left: Float, val top: Float, val width: Float, val height: Float) {
    init { require(listOf(left, top, width, height).all { it.isFinite() } && width > 0f && height > 0f) }

    fun position(point: List<Float>) = FalloffEditorPosition(left + point[0] * width, top + (1f - point[1]) * height)
    fun radius(x: Float): Float = ((x - left) / width).coerceIn(0f, 1f)
    fun coverage(y: Float): Float = (1f - (y - top) / height).coerceIn(0f, 1f)

    /** Selected wins an exact overlapping hit; otherwise nearest, then earlier stored index. */
    fun nearest(profile: TipProfileSpec, x: Float, y: Float, reach: Float, selected: Int): Int {
        if (!x.isFinite() || !y.isFinite() || !reach.isFinite() || reach <= 0f) return -1
        var chosen = -1; var best = Double.POSITIVE_INFINITY
        for (index in profile.curve.indices) {
            val p = position(profile.curve[index])
            val distance = kotlin.math.hypot(x.toDouble() - p.x, y.toDouble() - p.y)
            if (distance <= reach && (distance < best || (distance == best && index == selected))) {
                chosen = index; best = distance
            }
        }
        return chosen
    }

    companion object {
        fun fit(width: Float, height: Float, handleRadius: Float): FalloffEditorGeometry? {
            if (listOf(width, height, handleRadius).any { !it.isFinite() } || handleRadius < 0f ||
                width <= 2f * handleRadius || height <= 2f * handleRadius) return null
            return FalloffEditorGeometry(handleRadius, handleRadius, width - 2f * handleRadius, height - 2f * handleRadius)
        }
    }
}
