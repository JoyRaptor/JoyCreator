package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionFrame
import cc.joycreator.joybrush.core.doc.RegionPaintPlan
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

class CapturedPaintDomainsTest {
    private fun frame(board: String, cel: String, rect: RectPx, current: String = "current") =
        RegionFrame(board, rect, current, cel)

    private fun planeAtSnapshot(snapshot: CapturedPaintDomains, engineCelId: String) =
        snapshot.planes.single { it.engineCelId == engineCelId }

    @Test fun `domains exactly match immutable plan ownership across negative and tile edges`() {
        val plan = RegionPaintPlan("shared", listOf(
            frame("left", "left-cel", RectPx(-260, -3, 12, 8), "left-current"),
            frame("right", "right-cel", RectPx(250, 250, 12, 12), "right-current"),
        ))
        val snapshot = CapturedPaintDomains.prepare(plan)
        assertEquals(3, snapshot.planes.size)
        assertEquals("shared", snapshot.sharedCelId)
        assertNull(snapshot.planes.first().engineCelId)
        assertEquals(null, snapshot.forCel(null)?.owner?.boardId)
        assertNull(snapshot.forCel("shared"), "The document shared ID is not the engine's canonical null address")
        assertEquals("shared", snapshot.forCel(null)?.owner?.celId)
        assertEquals("left-cel", snapshot.forCel("left-cel")?.engineCelId)
        assertEquals("left-current", snapshot.forCel("left-cel")?.owner?.frameId)
        assertEquals("right", snapshot.forCel("right-cel")?.owner?.boardId)

        val tileKeys = listOf(
            Tiles.key(-2, -1), Tiles.key(-1, -1), Tiles.key(-2, 0), Tiles.key(-1, 0), // negative-x and y-zero crossings
            Tiles.key(0, 0), Tiles.key(1, 0), Tiles.key(0, 1), Tiles.key(1, 1), // both positive tile edges
        )
        val planes = snapshot.planes
        val byOwnerCel = planes.associateBy { it.owner.celId }
        for (key in tileKeys) {
            val tx = Tiles.tx(key)
            val ty = Tiles.ty(key)
            val slices = plan.tileSlices(key)
            for (py in 0 until Tiles.SIZE) for (px in 0 until Tiles.SIZE) {
                val worldX = tx.toLong() * Tiles.SIZE + px
                val worldY = ty.toLong() * Tiles.SIZE + py
                val expected = plan.planeAt(worldX, worldY).celId
                val actual = slices.first { it.rect.contains(px, py) }.plane.celId
                if (actual != expected) fail("slice owner mismatch at $worldX,$worldY")
                if (!byOwnerCel.getValue(expected).source.domain.contains(worldX.toInt(), worldY.toInt())) {
                    fail("capture domain omitted owner pixel $worldX,$worldY")
                }
                for (candidate in planes) {
                    if (candidate.owner.celId != expected &&
                        candidate.source.domain.contains(worldX.toInt(), worldY.toInt())) {
                        fail("capture domain duplicated owner pixel $worldX,$worldY")
                    }
                }
            }
        }
        assertEquals(SourcePlacement(), snapshot.planes.first().source.placement)
    }

    @Test fun `empty plan is unbounded shared plane and omitted held boards stay shared`() {
        val empty = CapturedPaintDomains.prepare(RegionPaintPlan("shared", emptyList()))
        assertEquals(1, empty.planes.size)
        assertNull(empty.planes.single().source.domain.window)
        assertTrue(empty.planes.single().source.domain.excluded.isEmpty())
        assertTrue(empty.planes.single().source.domain.contains(Int.MIN_VALUE, Int.MAX_VALUE))
        assertNull(empty.forCel("unknown-frame-cel"))

        // The authoritative input contains only this board's selected current frame. A missing
        // board is not guessed or backfilled; its pixels belong to the shared plane in this snapshot.
        val currentOnly = CapturedPaintDomains.prepare(RegionPaintPlan("shared", listOf(
            frame("current-board", "current-cel", RectPx(-4, -4, 8, 8), "frame-7"),
        )))
        assertEquals(listOf("shared", "current-cel"), currentOnly.planes.map { it.owner.celId })
        assertNull(currentOnly.forCel("held-board-cel"))
        assertTrue(currentOnly.planes.first().source.domain.contains(20, 20))
        assertFalse(currentOnly.planes.first().source.domain.contains(0, 0))
    }

    @Test fun `input frame list and prepared snapshot are defensively detached`() {
        val input = mutableListOf(
            frame("board", "frame-cel", RectPx(1, 2, 3, 4)),
            frame("other-board", "other-cel", RectPx(20, 30, 2, 2)),
        )
        val plan = RegionPaintPlan("shared", input)
        input[0] = frame("other", "other-cel", RectPx(20, 30, 2, 2))
        val snapshot = CapturedPaintDomains.prepare(plan)
        assertEquals("frame-cel", snapshot.planes[1].owner.celId)
        assertEquals("frame-cel", snapshot.planes[1].engineCelId)

        val previousDomain = snapshot.planes[1].source.domain
        @Suppress("UNCHECKED_CAST")
        val planFrames = plan.frames as MutableList<RegionFrame>
        planFrames[0] = frame("changed", "changed-cel", RectPx(50, 60, 7, 8))
        assertEquals(previousDomain, snapshot.planes[1].source.domain)
        assertEquals("frame-cel", snapshot.planes[1].owner.celId)
        assertFails { (snapshot.planes as MutableList<CapturedPaintDomains.Plane>).clear() }
    }

    @Test fun `mutated plan is reconstructed and duplicate or missing identities refuse`() {
        fun mutated(replacement: List<RegionFrame>) = RegionPaintPlan("shared", listOf(
            frame("one", "one-cel", RectPx(0, 0, 2, 2)),
            frame("two", "two-cel", RectPx(4, 0, 2, 2)),
        )).also { plan ->
            @Suppress("UNCHECKED_CAST")
            val frames = plan.frames as MutableList<RegionFrame>
            frames.clear()
            frames.addAll(replacement)
        }

        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(
                frame("same-board", "a", RectPx(0, 0, 1, 1)),
                frame("same-board", "b", RectPx(2, 0, 1, 1)),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(
                frame("a", "same-cel", RectPx(0, 0, 1, 1)),
                frame("b", "same-cel", RectPx(2, 0, 1, 1)),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(frame("", "cel", RectPx(0, 0, 1, 1)))))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(frame("board", "", RectPx(0, 0, 1, 1)))))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(frame("board", "shared", RectPx(0, 0, 1, 1)))))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(frame("board", "cel", RectPx(0, 0, 1, 1), ""))))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(
                frame("a", "a-cel", RectPx(-2, -2, 4, 4)),
                frame("b", "b-cel", RectPx(0, 0, 4, 4)),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(mutated(listOf(frame("board", "cel", RectPx(0, 0, 0, 1)))))
        }
    }

    @Test fun `region budget refuses before domain construction and accepts exactly sixty four`() {
        fun frames(count: Int) = List(count) { i ->
            frame("board-$i", "cel-$i", RectPx(i * 4, 0, 1, 1), "frame-$i")
        }
        val accepted = CapturedPaintDomains.prepare(RegionPaintPlan("shared", frames(CapturedPaintDomains.MAX_REGIONS)))
        assertEquals(CapturedPaintDomains.MAX_REGIONS + 1, accepted.planes.size)
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(RegionPaintPlan("shared", frames(CapturedPaintDomains.MAX_REGIONS + 1)))
        }
    }

    @Test fun `component fragments capture one exact domain per physical cel`() {
        val root = frame("clip", "composite", RectPx(-10, -2, 30, 6))
        val plan = RegionPaintPlan("shared", listOf(
            root.copy(celId = "drawing", rect = RectPx(-10, -2, 10, 6)),
            root.copy(celId = "composite", rect = RectPx(0, -2, 10, 6)),
            root.copy(celId = "drawing", rect = RectPx(10, -2, 10, 6)),
        ), listOf(root))
        val captured = CapturedPaintDomains.prepare(plan)
        assertEquals(listOf("shared", "drawing", "composite"), captured.planes.map { it.owner.celId })
        for (y in -3..4) for (x in -11..20) {
            val owner = plan.planeAt(x.toLong(), y.toLong()).celId
            assertEquals(listOf(owner), captured.planes.filter { it.source.domain.contains(x, y) }.map { it.owner.celId })
        }
        val drawing = captured.forCel("drawing")!!.source.domain
        assertTrue(drawing.contains(-5, 0)); assertTrue(drawing.contains(15, 0))
        assertFalse(drawing.contains(5, 0))
    }

    @Test fun `explicit logical roots reject missing coverage and unrelated fragments`() {
        val root = frame("clip", "composite", RectPx(0, 0, 20, 10))
        assertFailsWith<IllegalArgumentException> {
            RegionPaintPlan("shared", listOf(root.copy(celId = "part", rect = RectPx(0, 0, 10, 10))), listOf(root))
        }
        assertFailsWith<IllegalArgumentException> {
            RegionPaintPlan("shared", listOf(root.copy(boardId = "other")), listOf(root))
        }
        assertFailsWith<IllegalArgumentException> {
            RegionPaintPlan("shared", listOf(root.copy(frameId = "other")), listOf(root))
        }
        assertFailsWith<IllegalArgumentException> {
            RegionPaintPlan("shared", listOf(root.copy(rect = RectPx(-1, 0, 20, 10))), listOf(root))
        }
    }

    @Test fun `region rectangles preserve Int edge pixels and refuse unaddressable exclusive ends`() {
        val valid = CapturedPaintDomains.prepare(RegionPaintPlan("shared", listOf(
            frame("edge", "edge-cel", RectPx(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1)),
            frame("low", "low-cel", RectPx(Int.MIN_VALUE, Int.MIN_VALUE, 1, 1)),
        )))
        val upper = planeAtSnapshot(valid, "edge-cel").source.domain.window!!
        assertEquals(Int.MAX_VALUE + 1L, upper.right)
        assertEquals(Int.MAX_VALUE + 1L, upper.bottom)
        assertTrue(upper.contains(Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(planeAtSnapshot(valid, "low-cel").source.domain.window!!.contains(Int.MIN_VALUE, Int.MIN_VALUE))

        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(RegionPaintPlan("shared", listOf(
                frame("overflow", "overflow-cel", RectPx(Int.MAX_VALUE, 0, 2, 1)),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            CapturedPaintDomains.prepare(RegionPaintPlan("shared", listOf(
                frame("overflow", "overflow-cel", RectPx(0, Int.MAX_VALUE, 1, 2)),
            )))
        }
    }
}
