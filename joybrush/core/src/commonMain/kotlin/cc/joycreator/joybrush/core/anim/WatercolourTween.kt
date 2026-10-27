package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame

/** Frozen-brush-aware replay admission and transport; this does not render or mutate a cel. */
object WatercolourTween {
    /**
     * [frozenBrushLookup] must resolve the immutable drawing/snapshot brush closure, never the
     * mutable brush library. Both endpoint streams are checked. Ordinary same-engine Ink keeps
     * [Tween.between]'s semantics, including the source brush when the target has another preset.
     *
     * A WATERCOLOUR pair with different wash frames is admitted only when it is exactly the
     * existing LineTransform KEEP translation of the source, apart from colour, width, lock and
     * group metadata. Both frames must have literal affine bottom row (0,0,1) and the same linear
     * part. Rotation/scale/shear/projective/nonrigid or recipe-changing transport is refused.
     * This envelope is deliberate; interpolating matrix coefficients can collapse a 180° turn.
     *
     * Consumers must use this entry when replaying frozen brush records. The generic Tween math
     * alone does not transport a changed watercolour wash frame. Pixel/GPU parity is a separate
     * acceptance check, not established by this metadata operation.
     */
    fun betweenForReplay(
        a: List<StrokeRecord>,
        b: List<StrokeRecord>,
        t: Float,
        frozenBrushLookup: (String) -> BrushPreset?,
    ): List<StrokeRecord> {
        CapturedStrokeRules.requireOrdinary(a)
        CapturedStrokeRules.requireOrdinary(b)
        if (!t.isFinite() || t !in 0f..1f) throw DocException("A tween replay fraction must be between zero and one")
        val brushes = linkedMapOf<String, BrushPreset>()
        for (record in a.asSequence() + b.asSequence()) {
            val preset = frozenBrushLookup(record.brushId)
                ?: throw DocException("Tween replay is missing frozen brush \"${record.brushId}\"")
            if (preset.id != record.brushId) throw DocException("Tween replay resolved the wrong frozen brush")
            val known = brushes[record.brushId]
            if (known != null && known != preset) throw DocException("Tween replay has inconsistent frozen brush definitions")
            if (known == null) {
                val problems = BrushValidate.validate(preset)
                if (problems.isNotEmpty()) throw DocException("Tween replay has an invalid frozen brush: ${problems.joinToString("; ")}")
                brushes[record.brushId] = preset
            }
        }
        val targets = b.distinctBy { it.id }.associateBy { it.id }
        val sources = a.distinctBy { it.id }.associateBy { it.id }
        val translations = hashMapOf<String, Pair<Double, Double>>()
        for ((id, source) in sources) {
            val target = targets[id] ?: continue
            val preset = brushes.getValue(source.brushId)
            val targetPreset = brushes.getValue(target.brushId)
            if (preset.engine != targetPreset.engine) throw DocException("Tween replay cannot change brush engines within a matched line")
            if (preset.engine != ENGINE_WATERCOLOUR) continue
            val from = source.washFrame ?: StrokeWashFrame.IDENTITY
            val to = target.washFrame ?: StrokeWashFrame.IDENTITY
            if (from == to) continue
            if (preset != targetPreset || source.brushId != target.brushId) unsupported()
            translations[id] = translation(source, target, from, to)
        }

        val ordinary = Tween.between(a, b, t)
        return ordinary.mapIndexed { index, result ->
            val source = a[index]
            // Tween only matches the first occurrence of an ID; never apply one pair to duplicates.
            if (sources[source.id] !== source || brushes.getValue(source.brushId).engine != ENGINE_WATERCOLOUR) return@mapIndexed result
            val target = targets[source.id] ?: return@mapIndexed result
            val delta = translations[source.id] ?: return@mapIndexed result.copy(
                washFrame = if (t == 1f) target.washFrame else source.washFrame,
            )
            if (t == 0f) return@mapIndexed source
            if (t == 1f) return@mapIndexed target
            val transported = LineTransform.apply(source, Homography.translate(delta.first * t, delta.second * t), LineTransform.Weight.KEEP)
                ?: unsupported()
            transported.copy(colorArgb = result.colorArgb, widthScale = result.widthScale)
        }
    }

    private fun translation(a: StrokeRecord, b: StrokeRecord, from: StrokeWashFrame, to: StrokeWashFrame): Pair<Double, Double> {
        fun affine(frame: StrokeWashFrame) = frame.m20 == 0.0 && frame.m21 == 0.0 && frame.m22 == 1.0
        if (!affine(from) || !affine(to) || from.m00 != to.m00 || from.m01 != to.m01 ||
            from.m10 != to.m10 || from.m11 != to.m11) unsupported()
        val dx = to.m02 - from.m02
        val dy = to.m12 - from.m12
        if (!dx.isFinite() || !dy.isFinite()) unsupported()
        val expected = LineTransform.apply(a, Homography.translate(dx, dy), LineTransform.Weight.KEEP) ?: unsupported()
        if (expected.smoothing.toRawBits() != b.smoothing.toRawBits() ||
            expected.screenPerDoc.toRawBits() != b.screenPerDoc.toRawBits() ||
            !sameSamples(expected, b) ||
            expected.copy(samples = b.samples, colorArgb = b.colorArgb, widthScale = b.widthScale,
                locked = b.locked, groupId = b.groupId) != b) unsupported()
        return dx to dy
    }

    // Kotlin data equality canonicalizes NaN payloads. Raw sensor/time bits are part of the
    // frozen translation recipe, so an unknown channel must not silently be replaced or repaired.
    private fun sameSamples(a: StrokeRecord, b: StrokeRecord): Boolean =
        a.samples.size == b.samples.size && a.samples.indices.all { i ->
            val x = a.samples[i]
            val y = b.samples[i]
            x.x.toRawBits() == y.x.toRawBits() && x.y.toRawBits() == y.y.toRawBits() &&
                x.timeMs.toRawBits() == y.timeMs.toRawBits() && x.pressure.toRawBits() == y.pressure.toRawBits() &&
                x.tilt.toRawBits() == y.tilt.toRawBits() && x.azimuth.toRawBits() == y.azimuth.toRawBits() &&
                x.barrel.toRawBits() == y.barrel.toRawBits() && x.tool == y.tool && x.predicted == y.predicted
        }

    private fun unsupported(): Nothing = throw DocException(
        "This watercolour tween needs matching frozen recipes and an exact translation; changed wash rotation, scale, perspective or shape is not supported yet",
    )
}
