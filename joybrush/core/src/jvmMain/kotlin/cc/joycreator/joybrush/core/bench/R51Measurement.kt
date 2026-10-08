package cc.joycreator.joybrush.core.bench

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.fill.FillOptions
import cc.joycreator.joybrush.core.fill.FillTrace
import cc.joycreator.joybrush.core.fill.FloodFill
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkTiles
import java.io.File
import java.util.Locale

/** Explicit opt-in desktop runner; no benchmark is part of normal CI or a phone budget gate. */
object R51Measurement {
    const val WARMUP = 1
    const val REPEATS = 3

    fun renderFrame(prepared: InkTiles.Prepared): List<ByteArray> = buildList {
        check(prepared.refusals.isEmpty()) { prepared.refusals.joinToString() }
        for (ty in 0..1) for (tx in 0..1) {
            val result = InkTiles.render(prepared, tx, ty)
            check(result.refusals.isEmpty()) { result.refusals.joinToString() }
            result.pixels?.let { add(it) }
        }
    }

    private fun tilesChecksum(tiles: List<ByteArray>): Long =
        tiles.fold(tiles.size.toLong()) { a, b -> a * 31 + R51JvmMetrics.checksum(b) }

    private fun recordsChecksum(records: List<StrokeRecord>): Long {
        var value = records.size.toLong()
        for (r in records) for (s in r.samples) {
            value = value * 31 + s.x.toRawBits()
            value = value * 31 + s.y.toRawBits()
        }
        return value
    }

    /** Appends rows as jobs finish so a slow reference run remains observable. */
    fun run(output: File) {
        output.parentFile?.mkdirs()
        output.writeText("# R51 raw desktop measurement output\n\n" +
            "JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}\n" +
            "OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}\n" +
            "Processors visible to JVM: ${Runtime.getRuntime().availableProcessors()}; max heap bytes: ${Runtime.getRuntime().maxMemory()}\n" +
            "Warmups: $WARMUP; measured repeats: $REPEATS. Fixture construction outside brackets.\n" +
            "Three repeats explicitly limit runtime of the per-mark CPU reference; p95 is the maximum of three, not a robust tail estimate. Output hashing is included. No explicit GC.\n\n")
        fun line(s: String) { output.appendText(s + "\n"); println(s) }
        fun timed(name: String, job: () -> Long) {
            line("Starting: $name")
            val t = R51JvmMetrics.time(WARMUP, REPEATS, job)
            val fmt = { v: Double -> String.format(Locale.ROOT, "%.3f", v) }
            line("| $name | ${fmt(t.medianMs)} | ${fmt(t.p95Ms)} | ${t.samplesMs.joinToString { fmt(it) }} | ${t.checksum} |")
        }
        line("| Case | Median ms | p95 ms | all samples ms | checksum |")
        line("|---|---:|---:|---|---:|")
        for (brush in listOf(R51Fixtures.pen, R51Fixtures.pencil)) for (n in listOf(50, 500, 2000)) {
            val records = R51Fixtures.lines(n, brush)
            val lookup: (String) -> BrushPreset? = { if (it == brush.id) brush else null }
            val prepared = InkTiles.prepare(records, lookup)
            check(prepared.refusals.isEmpty())
            line("Fixture ${brush.id}/$n: samples=${records.sumOf { it.samples.size }}, dabs=${prepared.marks.sumOf { it.dabs.size }}")
            timed("${brush.id}/$n prepare") {
                val p = InkTiles.prepare(records, lookup)
                check(p.refusals.isEmpty())
                p.marks.fold(p.marks.size.toLong()) { a, m -> a * 31 + m.dabs.size }
            }
            timed("${brush.id}/$n prepared full 512px frame") { tilesChecksum(renderFrame(prepared)) }
            timed("${brush.id}/$n prepare + full 512px frame") {
                tilesChecksum(renderFrame(InkTiles.prepare(records, lookup)))
            }
            if (brush == R51Fixtures.pen && n == 500) {
                val storage = R51JvmMetrics.storage(records, renderFrame(prepared))
                line("Storage line-art500: $storage")
            }
        }
        val paint = R51Fixtures.painted(500)
        val paintPrepared = InkTiles.prepare(paint) { if (it == R51Fixtures.paint.id) R51Fixtures.paint else null }
        line("Starting: storage painted500")
        line("Storage painted500: ${R51JvmMetrics.storage(paint, renderFrame(paintPrepared))}")

        val ref = R51Fixtures.denseLineArt()
        val options = FillOptions(tolerance = 0f, gapClosePx = 1, grow = 0)
        val region = FloodFill.fill(2048, 2048, ref, 8, 8, options)
        val pixels = region.count { it != 0.toByte() }
        check(pixels > 2048 * 2048 / 2) { "dense fill too small: $pixels" }
        line("Fill reference2048: selected=$pixels/4194304, maskChecksum=${R51JvmMetrics.checksum(region)}; gapClose=1, grow=0, seed=8,8")
        timed("FloodFill2048 only") {
            R51JvmMetrics.checksum(FloodFill.fill(2048, 2048, ref, 8, 8, options))
        }
        timed("FillTrace2048 only (0.75px growth included)") {
            recordsChecksum(FillTrace.trace(2048, 2048, region, "r51-fill", "r51-fill-brush", 0xff397db7.toInt()))
        }
        val shapes = FillTrace.trace(2048, 2048, region, "r51-fill", "r51-fill-brush", 0xff397db7.toInt())
        check(shapes.isNotEmpty())
        line("Fill output: records=${shapes.size}, samples=${shapes.sumOf { it.samples.size }}, checksum=${recordsChecksum(shapes)}")
        line("COMPLETE")
    }
}
