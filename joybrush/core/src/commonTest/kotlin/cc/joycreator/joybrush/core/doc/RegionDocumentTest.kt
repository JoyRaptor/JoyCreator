package cc.joycreator.joybrush.core.doc

import kotlin.test.*

class RegionDocumentTest {
    private var serial = 0
    private fun ids(): String = "region-id-${serial++}"
    private fun base() = JbDocument(id="drawing",name="test",boards=listOf(Board("image","Image",BoardKind.CANVAS,RectPx(0,0,1024,1024))),
        layers=listOf(Layer("layer","Paint",LayerKind.PAINT,cels=listOf(Cel("base")))))
    private fun create(doc:JbDocument=base(),rect:RectPx=RectPx(17,23,101,93)) = RegionDocumentOps.create(doc,"Animation",rect,::ids)

    @Test fun `new board animates all paint layers and moves the art under it into frame 1`() {
        val original=base().copy(layers=base().layers+base().layers.single().copy(id="second",cels=listOf(Cel("second-base"))))
        val change=create(original)
        // The art under the rectangle MOVES into frame 1: a transfer into the new cel and a clear of the shared one.
        assertTrue(change.copies.isEmpty())
        assertEquals(2,change.transfers.size)
        assertEquals(change.transfers.map { RegionClear(it.layerId, it.fromCelId, it.sourceRect) }, change.clears)
        assertEquals(listOf("base","second-base"),change.doc.layers.map{it.sharedCelId})
        assertEquals(original.layers.map{it.cels.first()},change.doc.layers.map{it.cels.first()})
        assertTrue(DocOps.validate(change.doc).isEmpty())
        assertEquals(DOC_VERSION,change.doc.version)
        assertEquals(change.doc.boards.last().frames.first().id,change.doc.boards.last().currentFrameId)
        assertEquals(RectPx(17,23,101,93),change.transfers.first().sourceRect)
        assertEquals(change.transfers.first().sourceRect,change.transfers.first().destinationRect)
        assertEquals(change.doc.layers.first().regions.single().frameCel.values.single(),change.transfers.first().toCelId)
    }

    @Test fun `blank changes inside ownership only`() {
        val first=create().doc; val board=first.boards.last()
        val next=RegionDocumentOps.addFrame(first,board.id,NewFrame.BLANK,::ids)
        val a=RegionDocumentOps.paintPlan(first,"layer");val b=RegionDocumentOps.paintPlan(next.doc,"layer")
        assertTrue(next.copies.isEmpty())
        assertEquals(a.shared,b.shared)
        assertEquals(a.planeAt(16,23),b.planeAt(16,23))
        assertNotEquals(a.planeAt(17,23).celId,b.planeAt(17,23).celId)
        assertTrue(next.doc.layers.single().cels.last().tiles.isEmpty())
    }

    @Test fun `duplicate copies just board bounds and link aliases same cel`() {
        val first=create().doc;val board=first.boards.last()
        val copy=RegionDocumentOps.addFrame(first,board.id,NewFrame.DUPLICATE,::ids)
        assertEquals(board.rect,copy.copies.single().rect)
        assertNotEquals(copy.copies.single().fromCelId,copy.copies.single().toCelId)
        val linked=RegionDocumentOps.addFrame(copy.doc,board.id,NewFrame.LINK,::ids)
        assertTrue(linked.copies.isEmpty())
        assertEquals(copy.doc.layers.single().cels,linked.doc.layers.single().cels)
        assertEquals(copy.doc.layers.single().regions.single().frameCel.values.last(),linked.doc.layers.single().regions.single().frameCel.values.last())
    }

    @Test fun `current frame persists independently for two boards`() {
        val one=create().doc
        val two=create(one,RectPx(-73,-81,19,21)).doc
        val b=two.boards.last();val a=two.boards[two.boards.lastIndex-1]
        val next=RegionDocumentOps.addFrame(two,a.id,NewFrame.BLANK,::ids).doc
        val decoded=DocJson.decode(DocJson.encode(next))
        assertEquals(next,decoded)
        assertEquals(b.currentFrameId,decoded.boards.last().currentFrameId)
        assertNotEquals(a.currentFrameId,decoded.boards[decoded.boards.lastIndex-1].currentFrameId)
        val plan=RegionDocumentOps.paintPlan(decoded,"layer")
        assertEquals(2,plan.frames.size)
        assertEquals("base",plan.planeAt(0,0).celId)
    }

    @Test fun `held region uses shared canvas and retains frame addresses`() {
        val doc=create().doc;val layer=doc.layers.single()
        val held=doc.copy(layers=listOf(layer.copy(regions=layer.regions.map{it.copy(held=true)})))
        assertEquals("base",RegionDocumentOps.paintPlan(held,"layer").planeAt(17,23).celId)
        assertEquals(layer.regions.single().frameCel,held.layers.single().regions.single().frameCel)
        assertTrue(DocOps.validate(held).isEmpty())
    }

    @Test fun `current frame and mappings must be real`() {
        val doc=create().doc;val layer=doc.layers.single()
        val invalid=listOf(doc.copy(boards=doc.boards.map{if(it.kind==BoardKind.ANIMATION)it.copy(currentFrameId="missing")else it}),
            doc.copy(layers=listOf(layer.copy(sharedCelId="missing"))),
            doc.copy(layers=listOf(layer.copy(regions=layer.regions.map{it.copy(frameCel=emptyMap())}))),
            doc.copy(layers=listOf(layer.copy(regions=layer.regions.map{it.copy(frameCel=it.frameCel.mapValues{"base"})}))),
            doc.copy(version=4))
        for (bad in invalid) {
            assertTrue(DocOps.validate(bad).isNotEmpty())
            assertFailsWith<DocException>{RegionDocumentOps.paintPlan(bad,"layer")}
        }
    }

    @Test fun `overlapping boards and repeated IDs are refused before mutation`() {
        val first=create().doc
        assertFailsWith<DocException>{create(first)}
        assertFailsWith<DocException>{RegionDocumentOps.create(base(),"Animation",RectPx(0,0,1,1)){"base"}}
        assertFailsWith<DocException>{RegionDocumentOps.create(base(),"Animation",RectPx(0,0,1,1)){""}}
        assertEquals(1,base().layers.single().cels.size)
    }

    @Test fun `wrong board and frame requests are refused`() {
        val doc=create().doc
        assertFailsWith<DocException>{RegionDocumentOps.selectFrame(doc,"image","none")}
        assertFailsWith<DocException>{RegionDocumentOps.selectFrame(doc,doc.boards.last().id,"none")}
        assertFailsWith<DocException>{RegionDocumentOps.addFrame(doc,"image",NewFrame.BLANK,::ids)}
        assertFailsWith<DocException>{create(base().copy(layers=emptyList()))}
        assertFailsWith<DocException>{DocOps.celFor(doc.layers.single(),doc.boards.last().currentFrameId)}
    }

    @Test fun `frame maps serialize canonically and mask remains its own store`() {
        val first=create().doc;val b=first.boards.last()
        val doc=RegionDocumentOps.addFrame(first,b.id,NewFrame.BLANK,::ids).doc
        val l=doc.layers.single();val alternate=l.copy(regions=l.regions.map{it.copy(frameCel=it.frameCel.entries.reversed().associate{it.key to it.value})})
        assertEquals(DocJson.encode(doc),DocJson.encode(doc.copy(layers=listOf(alternate))))
        val masked=l.copy(mask=Cel("mask"))
        assertEquals(l.cels+Cel("mask"),DocOps.storedCels(masked))
        assertTrue(DocOps.validate(doc.copy(layers=listOf(masked))).isEmpty())
    }

    @Test fun `old whole-layer test drawings are not converted`() {
        val doc=create().doc;val b=doc.boards.last()
        val old=doc.copy(layers=listOf(Layer("layer","Paint",LayerKind.PAINT,animatedIn=b.id,
            cels=listOf(Cel("legacy")),frameCel=mapOf(b.currentFrameId!! to "legacy"))))
        assertFailsWith<DocException>{create(old,RectPx(-100,0,10,10))}
    }

    @Test fun `board lock and tiled flag survive saving`() {
        val doc=base().copy(boards=base().boards.map{it.copy(locked=true,tiled=true)})
        assertEquals(doc,DocJson.decode(DocJson.encode(doc)))
    }

    @Test fun `unknown region words are refused rather than dropped`() {
        val json=DocJson.encode(create().doc).replaceFirst("\"held\":", "\"unknownRegionWord\": true, \"held\":")
        assertFailsWith<DocException>{DocJson.decode(json)}
    }
}
