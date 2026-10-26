package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreparedLinePlaneCopyTest {
    private val source = LinePlane("paint", "source")
    private val destination = LinePlane("paint", "copy")
    private fun tile(x: Int) = Tiles.key(x, 0)

    private fun line(id: String = "stroke", seq: Long = 3L, samples: List<PenSample> = listOf(PenSample(2f, 4f, 0.0))) =
        StrokeRecord(id, "pen", 17L, 0f, 1f, samples, seq = seq)

    private fun populated(): NativeLines = NativeLines { _: StrokeRecord, _: BrushPreset? ->
        setOf(tile(0), tile(1))
    }.also { lines ->
        lines.put(source, "stroke", line())
        lines.widen(source, "stroke", setOf(tile(2)))
        lines.setBase(source, tile(0), 10)
        lines.setBase(source, tile(1), 11)
        lines.setSlab(source, 2L, tile(1), 21, "erase")
        lines.setSlab(source, 2L, tile(2), 22, "erase")
    }

    private data class Snapshot(
        val records: List<StrokeRecord>,
        val bases: Map<Long, Int>,
        val slabs: List<Triple<Long, String, Map<Long, Int>>>,
        val footprints: Map<String, Set<Long>>,
        val crossed: Set<Long>,
        val textures: Set<Int>,
    )

    private fun snapshot(lines: NativeLines, plane: LinePlane) = Snapshot(
        records = lines.lines(plane),
        bases = lines.bases(plane),
        slabs = lines.slabSeqs(plane).map { seq ->
            val slab = lines.slab(plane, seq)!!
            Triple(seq, slab.op, slab.tiles)
        },
        footprints = lines.lines(plane).associate { it.id to lines.footprint(plane, it.id) },
        crossed = lines.crossedKeys(plane),
        textures = lines.textures(),
    )

    @Test
    fun captureFreezesTheActualMutableSampleListAndFootprintViewsRejectJvmMutation() {
        val mutableSamples = mutableListOf(PenSample(2f, 4f, 0.0))
        val original = line(samples = mutableSamples)
        val lines = NativeLines { _: StrokeRecord, _: BrushPreset? -> setOf(tile(0)) }
        lines.put(source, original.id, original)
        lines.widen(source, original.id, setOf(tile(3)))
        val prepared = PreparedLinePlaneCopy.capture(lines, source, destination)
        val expectedFootprint = setOf(tile(0), tile(3))

        mutableSamples += PenSample(99f, 100f, 1.0)
        val materialized = prepared.materialize { it + 100 }
        val copiedRecord = materialized.lines.single().after!!.record
        assertEquals(listOf(PenSample(2f, 4f, 0.0)), copiedRecord.samples)
        assertEquals(listOf(PenSample(2f, 4f, 0.0), PenSample(99f, 100f, 1.0)), lines.record(source, original.id)!!.samples)
        @Suppress("UNCHECKED_CAST")
        val sampleMutation = runCatching {
            (copiedRecord.samples as MutableList<PenSample>).add(PenSample(50f, 60f, 2.0))
        }
        assertTrue(sampleMutation.isFailure, "The captured record's sample list must refuse mutation through a JVM cast")
        assertEquals(listOf(PenSample(2f, 4f, 0.0)), copiedRecord.samples)

        val footprintKey = destination to original.id
        assertEquals(expectedFootprint, prepared.footprints.getValue(footprintKey))
        @Suppress("UNCHECKED_CAST")
        val mapMutation = runCatching {
            (prepared.footprints as MutableMap<Pair<LinePlane, String>, Set<Long>>)[footprintKey] = emptySet()
        }
        assertTrue(mapMutation.isFailure, "The footprint map must refuse mutation through a JVM cast")
        @Suppress("UNCHECKED_CAST")
        val setMutation = runCatching {
            (prepared.footprints.getValue(footprintKey) as MutableSet<Long>).add(tile(9))
        }
        assertTrue(setMutation.isFailure, "Each footprint set must refuse mutation through a JVM cast")
        assertEquals(expectedFootprint, prepared.footprints.getValue(footprintKey))
    }

    @Test
    fun materializingTwiceCopiesEveryBaseAndSlabAndKeepsRecordsAndOperators() {
        val lines = populated()
        val before = snapshot(lines, source)
        val prepared = PreparedLinePlaneCopy.capture(lines, source, destination)
        assertEquals(4, prepared.textureCount)

        var nextHandle = 100
        fun materialize() = prepared.materialize { nextHandle++ }
        val first = materialize()
        val second = materialize()
        assertEquals(4, first.slabs.size)
        assertEquals(4, second.slabs.size)
        val copiedHandles = (first.slabs + second.slabs).map { it.after!!.texture }
        assertEquals(8, copiedHandles.toSet().size, "Each base/slab tile gets a fresh handle for each materialization")
        for (changes in listOf(first, second)) {
            assertEquals(listOf("stroke" to 3L), changes.lines.map { it.id to it.after!!.seq })
            assertTrue(changes.lines.all { it.layerId == destination.layerId && it.celId == destination.celId })
            assertEquals(listOf(0L to tile(0), 0L to tile(1), 2L to tile(1), 2L to tile(2)),
                changes.slabs.map { it.seq to it.key })
            assertEquals(listOf("normal", "normal", "erase", "erase"), changes.slabs.map { it.after!!.op })
            assertTrue(changes.slabs.all { it.after != null })
        }
        assertEquals(before, snapshot(lines, source), "Repeated preparation and materialization leave the source plane unchanged")
    }

    @Test
    fun partialTextureCopyFailureLeavesCallerTrackedOutputsCleanAndSourceUntouched() {
        val lines = populated()
        val before = snapshot(lines, source)
        val prepared = PreparedLinePlaneCopy.capture(lines, source, destination)
        val allocatedHandles = mutableListOf<Int>()
        val liveHandles = linkedSetOf<Int>()
        val releasedHandles = mutableListOf<Int>()
        var calls = 0

        val failure = runCatching {
            try {
                prepared.materialize { texture ->
                    calls++
                    if (calls == 2) error("simulated copy failure")
                    (texture + 1000).also { handle ->
                        allocatedHandles += handle
                        liveHandles += handle
                    }
                }
            } catch (failure: Throwable) {
                // The board transaction owns these unpublished allocations and releases them on failure.
                for (handle in liveHandles.toList()) {
                    liveHandles.remove(handle)
                    releasedHandles += handle
                }
                throw failure
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(2, calls)
        assertEquals(1, allocatedHandles.size)
        assertEquals(listOf(allocatedHandles.single()), releasedHandles, "Exactly the first copied handle is released")
        assertTrue(allocatedHandles.single() !in before.textures, "The new allocation is distinct from every source handle")
        assertTrue(releasedHandles.none { it in before.textures }, "Source handles are never released by failed materialization")
        assertTrue(liveHandles.isEmpty(), "No caller-owned output remains live after failure cleanup")
        assertEquals(before, snapshot(lines, source))
    }
}
