package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelLayout
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.LineRules
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

class LineDocumentTest {
    private fun doc(cel: Cel = Cel("cel")) = JbDocument(id = "doc", name = "Drawing",
        boards = listOf(Board("board", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
        layers = listOf(Layer("layer", "Lines and paint", LayerKind.PAINT, cels = listOf(cel))))
    private fun record(id: String = "line", seq: Long = 1) = StrokeRecord(id, "brush", 19, 0.3f, 2f, emptyList(), seq = seq)

    @Test fun pixelOnlyJsonAddsOnlyTheVersionNumber() {
        val base = doc(Cel("cel", tiles = listOf("0_0"), floatTiles = listOf("0_0")))
        val old = DocJson.encode(base.copy(version = 9))
        val current = DocJson.encode(base)
        assertEquals(old.replace("\"version\": 9", "\"version\": 10"), current)
        for (word in listOf("slabs", "looks", "brushes")) assertFalse(current.contains("\"$word\""), word)
        assertEquals(base, DocJson.decode(current))
    }

    @Test fun genuineOldInkJsonBecomesPaintAndKeepsFrameIdentity() {
        // Literal historical JSON: it contains none of the v10 fields and is not produced by today's writer.
        val text = """{"format":"joybrush.document","version":9,"id":"old","name":"Old ink",
          "boards":[{"id":"b","name":"Animation","kind":"ANIMATION","rect":{"x":0,"y":0,"w":64,"h":64},"frames":[{"id":"f1"},{"id":"f2"}]}],
          "layers":[{"id":"l","name":"Ink","kind":"INK","animatedIn":"b","cels":[{"id":"c","strokesFile":"layers/l/c/strokes.jbs"}],"frameCel":{"f1":"c","f2":"c"}}]}"""
        val read = DocJson.decode(text)
        assertEquals(10, read.version)
        assertEquals(LayerKind.PAINT, read.layers.single().kind)
        assertEquals(mapOf("f1" to "c", "f2" to "c"), read.layers.single().frameCel)
        assertEquals("layers/l/c/strokes.jbs", read.layers.single().cels.single().strokesFile)
        assertTrue(read.layers.single().cels.single().tiles.isEmpty())
        assertTrue(DocOps.validate(read).isEmpty(), DocOps.validate(read).toString())
        assertTrue(DocOps.validate(read.copy(layers = read.layers.map { it.copy(kind = LayerKind.INK) })).any { "INK" in it })
    }

    @Test fun mixedCelAndBrushSetsSerializeDeterministically() {
        val refs = listOf(SlabRef("0_0", 8, "multiply"), SlabRef("0_0", 0), SlabRef("-1_0", 4, "erase"))
        val base = doc(Cel("cel", tiles = listOf("0_0", "-1_0"), strokesFile = "layers/layer/cel/strokes.jbs",
            slabs = refs, looks = listOf("0_0"))).copy(brushes = listOf("z", "a"))
        assertEquals(DocJson.encode(base), DocJson.encode(base.copy(brushes = base.brushes.reversed(),
            layers = base.layers.map { it.copy(cels = it.cels.map { c -> c.copy(slabs = refs.reversed()) }) })))
        assertTrue(DocOps.validate(base).isEmpty(), DocOps.validate(base).toString())
        val decoded = DocJson.decode(DocJson.encode(base))
        assertEquals(refs.toSet(), decoded.layers.single().cels.single().slabs.toSet())
        assertEquals(base.layers.single().cels.single().looks, decoded.layers.single().cels.single().looks)
    }

    @Test fun structuralFailuresAreReportedTogether() {
        val c = Cel("cel", tiles = listOf("0_0"), slabs = listOf(SlabRef("0_0", -1, "unknown"),
            SlabRef("0_0", -1), SlabRef("1_0", 5), SlabRef("banana", 8)), looks = listOf("0_0", "0_0", "broken"))
        val errors = DocOps.validate(doc(c).copy(brushes = listOf("", "brush", "brush")))
        for (word in listOf("seq", "unknown", "does not list", "banana", "twice", "no lines", "no id", "two frozen"))
            assertTrue(errors.any { word in it }, "$word: $errors")
        val old = DocOps.validate(doc(c).copy(version = 9, brushes = listOf("brush")))
        assertTrue(old.any { "version 10" in it })
        val masked = doc().let { it.copy(layers = it.layers.map { l -> l.copy(mask = c) }) }
        assertTrue(DocOps.validate(masked).any { "mask" in it && "slabs" in it })
    }

    @Test fun recordsValidateIdentitySequenceGroupsAndFrozenBrushResolution() {
        val cel = Cel("cel", tiles = listOf("0_0"), strokesFile = "layers/layer/cel/strokes.jbs", slabs = listOf(SlabRef("0_0", 2)))
        assertTrue(LineRules.problems("layer", cel, listOf(record()), setOf("brush")).isEmpty())
        val records = listOf(record(seq = 2).copy(groupId = " "), record(seq = 2), record("zero", 0).copy(brushId = "missing"))
        val errors = LineRules.problems("layer", cel, records, setOf("brush"))
        for (word in listOf("two lines", "all have seq", "slab", "group", "missing", "seq 0"))
            assertTrue(errors.any { word in it }, "$word: $errors")
        assertTrue(LineRules.problems("layer", cel.copy(strokesFile = null), listOf(record()), setOf("brush")).isNotEmpty())
    }

    @Test fun layoutKeepsBottomBytesAndEveryOperatorInSequenceOrder() {
        val c = Cel("cel", tiles = listOf("0_0", "-1_0"), slabs = listOf(SlabRef("0_0", 8, "multiply"), SlabRef("0_0", 2, "behind")))
        assertEquals(listOf(SlabRef("-1_0", 0)), CelLayout.stacks(c).getValue("-1_0"))
        val bytes = ByteArray(Tiles.SIZE * Tiles.SIZE * 4).also { it[3] = -1 }
        val requests = mutableListOf<Triple<String, Long, Boolean>>()
        val items = CelLayout.items(c, emptyList()) { key, ref, bottom -> requests += Triple(key, ref.seq, bottom); bytes }
        assertEquals(listOf(2L, 8L), items.slabs.getValue(Tiles.key(0, 0)).map { it.seq })
        assertEquals(listOf("behind", "multiply"), items.slabs.getValue(Tiles.key(0, 0)).map { it.op })
        assertTrue(requests.contains(Triple("0_0", 2L, true)))
        assertTrue(requests.contains(Triple("0_0", 8L, false)))
        val described = CelLayout.describe(CelComposer.draw(items) { null })
        assertEquals(c.tiles.toSet(), described.first.toSet())
        assertEquals(c.slabs.toSet(), described.second.toSet())
        val plain = CelComposer.Items(emptyList(), mapOf(Tiles.key(0, 0) to listOf(CelComposer.Slab(50, bytes))))
        assertTrue(CelLayout.describe(CelComposer.draw(plain) { null }).second.isEmpty())
    }

    @Test fun layoutRefusesMissingImplicitBottomAndDeclaredBottomOrUpperSlabs() {
        val bytes = ByteArray(Tiles.SIZE * Tiles.SIZE * 4)
        val plain = Cel("cel", tiles = listOf("-1_0"))
        val implicit = assertFailsWith<IllegalArgumentException> {
            CelLayout.items(plain, emptyList()) { _, _, _ -> null }
        }.message.orEmpty()
        for (word in listOf("cel", "bottom", "-1_0", "seq 0")) assertTrue(word in implicit, implicit)

        val stacked = Cel("cel", tiles = listOf("0_0"),
            slabs = listOf(SlabRef("0_0", 2, "behind"), SlabRef("0_0", 8, "multiply")))
        for ((missingSeq, position) in listOf(2L to "bottom", 8L to "upper")) {
            val error = assertFailsWith<IllegalArgumentException> {
                CelLayout.items(stacked, emptyList()) { _, ref, _ -> if (ref.seq == missingSeq) null else bytes }
            }.message.orEmpty()
            for (word in listOf("cel", position, "0_0", "seq $missingSeq")) assertTrue(word in error, error)
        }
        // An empty cel declares no slab, so it legitimately needs no bytes and never calls the provider.
        val empty = CelLayout.items(Cel("empty"), emptyList()) { _, _, _ -> error("no declared slab") }
        assertTrue(empty.slabs.isEmpty())
    }
}
