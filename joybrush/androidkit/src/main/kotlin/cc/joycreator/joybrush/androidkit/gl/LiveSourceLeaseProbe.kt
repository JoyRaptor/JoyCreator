package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * JB-5.20d LIVE-SOURCE, the lease on the REAL GPU (debug bench only; reviews/LIVE_SOURCE_LEASE_SCOUT_20261027.md §4).
 * The same live stroke goes into two fresh probe-owned engines: one ends it natively ([GlPaintEngine.endStroke], the
 * reference), the other admits a live source ticket after the begin and before the first dab, paints the same dabs and
 * finishes the ticket into a lease. Each leased tile must be the reference's committed tile byte for byte (the stroke lands
 * on an empty layer, so the commit is the stroke itself), and the lease must not publish: the leased engine's history
 * cursor stays put while the lease is held, there is no joint publication, and its layer is still empty after the stroke is
 * cancelled. Closing the lease and the engines leaves GL error 0. Cases vary opacity, accumulation and blank-crossing tiles.
 *
 * GL thread. The user's canvas is not touched.
 */
internal object LiveSourceLeaseProbe {
    private const val LAYER = "paint"

    private class Case(val name: String, val argb: Int, val opacity: Float, val accumulate: Accumulate, val radius: Float)

    private val CASES = listOf(
        Case("wash 0.65", 0xFF123456.toInt(), 0.65f, Accumulate.WASH, 14f),
        Case("build-up 0.4", 0xFFB8461A.toInt(), 0.4f, Accumulate.BUILD_UP, 22f),
        Case("opaque wide", 0xFF2F7D3A.toInt(), 1f, Accumulate.WASH, 48f),
    )

    fun probe(paper: Paper): String {
        val report = JSONObject().put("probe", "liveSourceLease").put("renderer", GLES30.glGetString(GLES30.GL_RENDERER))
        val cases = JSONArray()
        var pass = true
        for (c in CASES) {
            val o = try { runCase(paper, c) } catch (e: Throwable) {
                JSONObject().put("case", c.name).put("pass", false).put("error", e.toString())
                    .put("at", e.stackTrace.take(8).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" })
            }
            if (!o.optBoolean("pass")) pass = false
            cases.put(o)
        }
        val glError = GLES30.glGetError()
        return report.put("cases", cases).put("glError", glError).put("pass", pass && glError == GLES30.GL_NO_ERROR).toString()
    }

    private fun runCase(paper: Paper, c: Case): JSONObject {
        val o = JSONObject().put("case", c.name)
        val dabs = dabs(c.radius)
        // Reference: the same stroke ended natively.
        val reference = engine(paper)
        val expected = try {
            begin(reference, c)
            for (chunk in dabs.chunked(6)) reference.addDabs(chunk)
            reference.endStroke()
            reference.tileKeys(LAYER).associateWith { k -> checkNotNull(reference.readTile(LAYER, k)) { "reference tile $k unreadable" } }
        } finally { reference.release() }
        // Leased: admitted after the begin, before the first dab; finished, never ended.
        val leased = engine(paper)
        try {
            begin(leased, c)
            val cursor = leased.liveSourceHistoryCursor()
            val ticket = leased.admitLiveRecordedSource(LAYER)
            for (chunk in dabs.chunked(6)) leased.addDabs(chunk)
            val lease = ticket.finish()
            val got = try {
                o.put("cursorUnchanged", leased.liveSourceHistoryCursor() === cursor)
                    .put("jointPublication", leased.hasJointPaintPublication)
                lease.unit.tiles.mapValues { (k, tex) -> checkNotNull(leased.readLiveSourceTile(tex)) { "leased tile $k unreadable" } }
            } finally {
                leased.cancelStroke()
                lease.close()
            }
            val layerAfter = leased.tileKeys(LAYER).size
            var differing = 0; var worst = 0; var firstDiff = ""
            for (k in expected.keys + got.keys) {
                val a = expected[k]; val b = got[k]
                if (a == null || b == null) { differing++; if (firstDiff.isEmpty()) firstDiff = "$k only ${if (a == null) "leased" else "native"}"; continue }
                if (a.contentEquals(b)) continue
                differing++
                var d = 0; for (i in a.indices) d = maxOf(d, abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)))
                worst = maxOf(worst, d)
                if (firstDiff.isEmpty()) firstDiff = "$k max delta $d"
            }
            val inked = expected.values.sumOf { t -> (3 until t.size step 4).count { t[it].toInt() != 0 } }
            o.put("tiles", expected.size).put("leasedTiles", got.size).put("inkedPixels", inked)
                .put("differingTiles", differing).put("maxDelta", worst).put("firstDiff", firstDiff)
                .put("layerTilesAfterCancel", layerAfter)
                .put("digest", digest(expected))
            o.put("pass", differing == 0 && expected.size >= 4 && inked > 1000 && o.getBoolean("cursorUnchanged") &&
                !o.getBoolean("jointPublication") && layerAfter == 0)
        } finally { leased.release() }
        return o
    }

    private fun engine(paper: Paper) = GlPaintEngine().also { it.init(); it.setDocumentPaper(paper); it.addLayer(LAYER) }

    private fun begin(e: GlPaintEngine, c: Case) = e.beginStroke(LAYER, c.argb, c.opacity, c.accumulate, StrokeBlend.NORMAL, TipShape())

    /** A wavy line across several tiles, negative coordinates included, pressure and flow varying. */
    private fun dabs(radius: Float): List<Dab> {
        val n = 240
        return List(n) { i ->
            val t = i / (n - 1f)
            val p = 0.35f + 0.6f * abs(sin(3 * PI * t)).toFloat()
            Dab(-300f + 900f * t, -120f + 500f * t + 90f * sin(2 * PI * t * 2).toFloat(), radius * (0.5f + 0.5f * p), flow = p, pressure = p)
        }
    }

    private fun digest(tiles: Map<Long, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (k in tiles.keys.sorted()) { md.update(k.toString().toByteArray()); md.update(tiles.getValue(k)) }
        return md.digest().take(8).joinToString("") { "%02x".format(it) }
    }
}
