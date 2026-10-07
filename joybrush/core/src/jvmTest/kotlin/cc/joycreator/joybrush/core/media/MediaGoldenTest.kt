package cc.joycreator.joybrush.core.media

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The port against the lab, number for number. golden.json is the lab's own output for fixed inputs
 * (node joybrush/tools/media_golden.mjs). A failure here means the phone would draw differently from what the
 * owner approved in the lab: fix the port (or change the lab first and regenerate), never loosen the tolerance.
 */
class MediaGoldenTest {
    private val g: JsonObject = Json.parseToJsonElement(
        MediaGoldenTest::class.java.getResourceAsStream("/media/golden.json")!!.bufferedReader().readText(),
    ).jsonObject

    private fun JsonElement.d() = jsonPrimitive.double
    private fun JsonElement.ds() = jsonArray.map { it.d() }.toDoubleArray()
    private fun obj(k: String) = g[k]!!.jsonObject

    /** Golden numbers are rounded to 7 significant digits; dab outputs are Float32 on both sides. */
    private fun near(expected: Double, actual: Double, what: String) {
        val tol = 2e-6 + 3e-6 * max(abs(expected), abs(actual))
        if (abs(expected - actual) > tol) fail("$what: lab $expected, port $actual")
    }

    private fun nearAll(expected: DoubleArray, actual: DoubleArray, what: String) {
        assertEquals(expected.size, actual.size, "$what: length")
        for (i in expected.indices) near(expected[i], actual[i], "$what[$i]")
    }

    private val paper: MediaPaper by lazy {
        val p = obj("paper")
        MediaPaper(p["hist"]!!.ds(), p["toothMm"]!!.d(), p["compliance"]!!.d(), heightMeanOverride = 0.5)
    }

    private fun samples(key: String): List<MediaSample> = obj("inputs")[key]!!.jsonArray.map {
        val o = it.jsonObject
        MediaSample(o["x"]!!.d(), o["y"]!!.d(), o["p"]!!.d(), o["tilt"]!!.d(), o["az"]!!.d(), o["t"]!!.d())
    }

    private fun dabsOf(batches: List<DabBatch>): List<DoubleArray> = batches.flatMap { b ->
        (0 until b.count).map { i -> DoubleArray(DabBatch.FLOATS) { k -> b.dabs[i * DabBatch.FLOATS + k].toDouble() } }
    }

    private fun compareDabs(expected: JsonElement, actual: List<DoubleArray>, what: String) {
        val e = expected.jsonArray
        assertEquals(e.size, actual.size, "$what: dab count")
        e.forEachIndexed { i, row -> nearAll(row.ds(), actual[i], "$what dab $i") }
    }

    @Test fun paperTables() {
        nearAll(g["phi"]!!.ds(), paper.phi, "phi")
        nearAll(g["psi"]!!.ds(), paper.psi, "psi")
        val lut = paper.contactLut()
        nearAll(obj("lut")["a"]!!.ds(), lut.a.map { it.toDouble() }.toDoubleArray(), "lut.a")
        nearAll(obj("lut")["p"]!!.ds(), lut.p, "lut.p")
    }

    @Test fun stickTablesAndMaterials() {
        val sticks = obj("sticks")
        assertEquals(sticks.keys.toList(), Sticks.ALL.keys.toList())
        for ((name, s) in Sticks.ALL) {
            val o = sticks[name]!!.jsonObject
            nearAll(doubleArrayOf(o["tipR"]!!.d(), o["tipMax"]!!.d(), o["side1"]!!.d(), o["side2"]!!.d(), o["face"]!!.d(), o["soft"]!!.d(), o["rInf"]!!.d()),
                doubleArrayOf(s.tipR, s.tipMax, s.side1, s.side2, s.face, s.soft, s.rInf), "stick $name")
            val m = obj("materials")[name]!!.jsonObject
            val k = StickMaterial.of(s)
            nearAll(doubleArrayOf(m["capMm"]!!.d(), m["flakeR"]!!.d(), m["abrasion"]!!.d(), m["transferExp"]!!.d(), m["leadSoft"]!!.d(),
                m["plateau"]!!.d(), m["crushRate"]!!.d(), m["crushStart"]!!.d(), m["crushMax"]!!.d(), m["smear"]!!.d(), m["smearMm"]!!.d(),
                m["dustRate"]!!.d(), m["clump"]!!.d(), m["pileRange"]!!.jsonArray[0].d(), m["pileRange"]!!.jsonArray[1].d(),
                m["dirStrength"]!!.d(), m["conform"]!!.d(), m["sheen"]!!.d()),
                doubleArrayOf(k.capMm, k.flakeR, k.abrasion, k.transferExp, k.leadSoft, k.plateau, k.crushRate, k.crushStart, k.crushMax,
                    k.smear, k.smearMm, k.dustRate, k.clump, k.pileRangeLo, k.pileRangeHi, k.dirStrength, k.conform, k.sheen), "material $name")
        }
    }

    @Test fun stickContacts() {
        for (row in g["contacts"]!!.jsonArray) {
            val o = row.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val t = o["t"]!!.d()
            val p = o["P"]!!.d()
            val c = stickContact(Sticks.ALL[name]!!, t * Math.PI / 2, p, paper.toothMm)
            val e = o["c"]
            if (e == null || e is JsonNull) { assertNull(c); continue }
            val eo = e.jsonObject
            assertTrue(c != null)
            nearAll(listOf("D", "R0", "L1", "L2", "H", "w0", "w1", "w2", "even", "xMin", "xMax", "yMax").map { eo[it]!!.d() }.toDoubleArray(),
                doubleArrayOf(c.d, c.r0, c.l1, c.l2, c.h, c.w0, c.w1, c.w2, c.even, c.xMin, c.xMax, c.yMax), "contact $name t=$t P=$p")
        }
        val c = stickContact(Sticks.ALL["Proto"]!!, 0.85 * Math.PI / 2, 0.6, paper.toothMm)!!
        for (row in g["squeeze"]!!.jsonArray) {
            val o = row.jsonObject
            near(o["v"]!!.d(), stickSqueeze(o["x"]!!.d(), o["y"]!!.d(), c), "squeeze ${o["x"]} ${o["y"]}")
        }
    }

    private fun runDry(name: String, input: String): List<DoubleArray> {
        val ds = DryStroke(Sticks.ALL[name]!!, paper.toothMm, 20.0)
        val out = ArrayList<DabBatch>()
        for (s in samples(input)) { ds.add(s); out.add(ds.take()) }
        return dabsOf(out)
    }

    @Test fun dryStrokes() {
        compareDabs(obj("dry")["Proto"]!!, runDry("Proto", "s40"), "dry Proto")
        compareDabs(obj("dry")["HB"]!!, runDry("HB", "s30flat"), "dry HB")
        compareDabs(obj("dry")["dab"]!!, runDry("Proto", "dab20"), "dry dab")
    }

    @Test fun wetTablesAndPaint() {
        val wb = obj("wet")["WET_BRUSHES"]!!.jsonObject
        assertEquals(wb.keys.toList(), WetBrushes.ALL.keys.toList())
        for ((name, b) in WetBrushes.ALL) {
            val o = wb[name]!!.jsonObject
            nearAll(listOf("bellyMm", "tipMm", "waterPerMm", "capacityMm3", "load", "gran", "stain", "beadMm", "liftMm", "dwellMmPerS").map { o[it]!!.d() }.toDoubleArray(),
                doubleArrayOf(b.bellyMm, b.tipMm, b.waterPerMm, b.capacityMm3, b.load, b.gran, b.stain, b.beadMm, b.liftMm, b.dwellMmPerS), "wet brush $name")
            assertEquals((o["allround"] as? JsonPrimitive)?.boolean ?: false, b.allround, "$name allround")
            assertEquals((o["clear"] as? JsonPrimitive)?.boolean ?: false, b.clear, "$name clear")
        }
        val wn = obj("wet")["WETNESS"]!!.jsonArray
        assertEquals(wn.size, WETNESS.size)
        wn.forEachIndexed { i, e -> near(e.jsonObject["load"]!!.d(), WETNESS[i].load, "wetness $i"); near(e.jsonObject["flow"]!!.d(), WETNESS[i].flow, "wetness $i flow") }
        for (row in g["paintFromColor"]!!.jsonArray) {
            val o = row.jsonObject
            val c = o["c"]!!.ds()
            for ((key, strength) in listOf("p" to 1.0, "half" to 0.5)) {
                val e = o[key]!!.jsonObject
                val p = paintFromColor(c, strength)
                nearAll(e["K"]!!.ds(), p.k, "paint $key K")
                near(e["S"]!!.d(), p.s, "paint $key S")
            }
        }
        for (row in g["tiltSlope"]!!.jsonArray) {
            val o = row.jsonObject
            val dir = o["dir"]!!.ds()
            nearAll(o["s"]!!.ds(), tiltSlope(o["d"]!!.d(), dir[0], dir[1]), "tiltSlope")
        }
        val u = wetUniforms(paper)
        for ((k, v) in obj("wetUniforms")) near(v.d(), u[k] ?: fail("wetUniforms lacks $k"), k)
    }

    private fun runWet(ws: WetStroke, input: String): List<DoubleArray> {
        val smp = samples(input)
        val out = ArrayList<DabBatch>()
        smp.forEachIndexed { i, s -> ws.add(s); if (i == smp.size - 1) ws.finish(); out.add(ws.take()) }
        return dabsOf(out)
    }

    @Test fun wetStrokes() {
        val c = doubleArrayOf(0.62, 0.22, 0.18)
        val w = obj("wetStrokes")
        compareDabs(w["allroundLoaded"]!!, runWet(WetStroke(WetBrushes.ALL["All-round"]!!, paintFromColor(c), 20.0, 3, WETNESS[3]), "s40"), "allround loaded")
        compareDabs(w["roundOwnLoad"]!!, runWet(WetStroke(WetBrushes.ALL["Round"]!!, paintFromColor(c), 20.0, 5, null), "s30"), "round")
        compareDabs(w["dryBrush"]!!, runWet(WetStroke(WetBrushes.ALL["Dry brush"]!!, paintFromColor(c), 20.0, 7, WETNESS[0]), "s30"), "dry brush")
        compareDabs(w["waterDab"]!!, runWet(WetStroke(WetBrushes.ALL["Water"]!!, paintFromColor(c), 20.0, 9, WETNESS[4]), "dab20"), "water dab")
        compareDabs(w["thinner"]!!, runWet(thinnerStroke(PasteBrushes.ALL["All-round"]!!, c, 20.0, 11, 3)!!, "s30"), "thinner")
    }

    @Test fun pasteTablesAndPaint() {
        val pb = obj("paste")["PASTE_BRUSHES"]!!.jsonObject
        assertEquals(pb.keys.toList(), PasteBrushes.ALL.keys.toList())
        for ((name, cap) in obj("cellCap")) near(cap.d(), cellCap(PasteBrushes.ALL[name]!!), "cellCap $name")
        for (row in g["opaquePaint"]!!.jsonArray) {
            val o = row.jsonObject
            val p = opaquePaint(o["c"]!!.ds())
            nearAll(o["p"]!!.jsonObject["K"]!!.ds(), p.k, "opaque K")
            near(o["p"]!!.jsonObject["S"]!!.d(), p.s, "opaque S")
        }
        for (row in g["bladeTan"]!!.jsonArray) near(row.jsonArray[1].d(), bladeTan(row.jsonArray[0].d()), "bladeTan")
    }

    private fun runPaste(stroke: PasteStroke, input: String): List<PasteStep> {
        val out = ArrayList<PasteStep>()
        for (s in samples(input)) { stroke.add(s); out.addAll(stroke.take()) }
        return out
    }

    private fun compareSteps(expected: JsonElement, actual: List<PasteStep>, what: String) {
        val e = expected.jsonArray
        assertEquals(e.size, actual.size, "$what: step count")
        e.forEachIndexed { i, row ->
            val o = row.jsonObject
            val a = actual[i]
            val w = "$what step $i"
            nearAll(doubleArrayOf(o["x"]!!.d(), o["y"]!!.d(), o["wDir"]!!.jsonArray[0].d(), o["wDir"]!!.jsonArray[1].d(),
                o["lDir"]!!.jsonArray[0].d(), o["lDir"]!!.jsonArray[1].d(), o["halfW"]!!.d(), o["len"]!!.d(), o["lenMax"]!!.d(),
                o["pressure"]!!.d(), o["slideMm"]!!.d()),
                doubleArrayOf(a.x, a.y, a.wDirX, a.wDirY, a.lDirX, a.lDirY, a.halfW, a.len, a.lenMax, a.pressure, a.slideMm), w)
            fun opt(k: String, v: Double?) {
                val ev = o[k]
                if (ev == null) assertNull(v, "$w $k should be unset") else near(ev.d(), v ?: fail("$w $k unset"), "$w $k")
            }
            opt("fingers", a.fingers); opt("thick", a.thick); opt("bladeLen", a.bladeLen); opt("bladeHalf", a.bladeHalf)
            opt("bladeH0", a.bladeH0); opt("bladeTan", a.bladeTan); opt("pressK", a.pressK)
            o["bladeDir"]?.let { nearAll(it.ds(), doubleArrayOf(a.bladeDirX, a.bladeDirY), "$w bladeDir") }
            o["travel"]?.let { nearAll(it.ds(), doubleArrayOf(a.travelX, a.travelY), "$w travel") }
            o["loadMode"]?.let { assertEquals(it.jsonPrimitive.boolean, a.loadMode, "$w loadMode") }
        }
    }

    @Test fun pasteStrokes() {
        val ps = obj("pasteStrokes")
        for (name in PasteBrushes.ALL.keys) compareSteps(ps[name]!!, runPaste(PasteStroke(PasteBrushes.ALL[name]!!, 20.0), "s30"), name)
        val k2 = PasteBrushes.ALL["Palette knife 2"]!!
        for (edge in EdgeMode.entries) for (press in PressMode.entries) for (face in FaceMode.entries)
            compareSteps(ps["Palette knife 2|${edge.id}|${press.id}|${face.id}"]!!,
                runPaste(PasteStroke(k2, 20.0, edge = edge, press = press, face = face), "s20"), "knife 2 $edge $press $face")
        for (edge in EdgeMode.entries) compareSteps(ps["Scraper|${edge.id}"]!!, runPaste(PasteStroke(PasteBrushes.ALL["Scraper"]!!, 20.0, edge = edge), "s20"), "scraper $edge")
        compareSteps(ps["Oil round|dab"]!!, runPaste(PasteStroke(PasteBrushes.ALL["Oil round"]!!, 20.0), "dab20"), "oil round dab")
        compareSteps(ps["All-round|thin"]!!, runPaste(PasteStroke(PasteBrushes.ALL["All-round"]!!, 20.0, body = 0.4), "s30"), "all-round thinned")
    }

    @Test fun spline() {
        val sp = obj("spline")
        val sparse = sp["sparse"]!!.jsonArray.map {
            val o = it.jsonObject
            MediaSample(o["x"]!!.d(), o["y"]!!.d(), o["p"]!!.d(), o["tilt"]!!.d(), o["az"]!!.d(), o["t"]!!.d())
        }
        val dense = ArrayList<MediaSample>()
        val f = MediaSpline { dense.add(it) }
        sparse.forEach(f::add)
        f.finish()
        val e = sp["dense"]!!.jsonArray
        assertEquals(e.size, dense.size, "spline points")
        e.forEachIndexed { i, row ->
            val o = row.jsonObject
            val a = dense[i]
            nearAll(listOf("x", "y", "p", "tilt", "az", "t").map { o[it]!!.d() }.toDoubleArray(), doubleArrayOf(a.x, a.y, a.p, a.tilt, a.az, a.t), "spline $i")
        }
    }
}
