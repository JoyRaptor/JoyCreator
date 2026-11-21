package cc.joycreator.joybrush.androidkit.gl.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Codex audit round 4, OIL-STEP: a memo-backed neighbourhood is always the one a full lookup gives, table changes included. */
class NeighbourMemoTest {

    private class Table(seed: Int) {
        val rnd = Random(seed)
        var cells = IntArray(64) { rnd.nextInt(-1, 40) }
        var walls = setOf<Int>()
        fun slot(x: Int, y: Int) = if (x in 0 until 8 && y in 0 until 8) cells[y * 8 + x] else -1
        fun lookup(tx: Int, ty: Int, r: Int, withWalls: Boolean, out: IntArray) {
            var i = 0
            for (dy in -r..r) for (dx in -r..r) {
                val x = tx + dx; val y = ty + dy; val s = slot(x, y)
                when {
                    withWalls && (y * 8 + x) in walls -> { out[i] = -2; out[i + 1] = -2 }
                    s < 0 -> { out[i] = -1; out[i + 1] = -1 }
                    else -> { out[i] = (s % 8) shl 8; out[i + 1] = (s / 8) shl 8 }
                }
                i += 2
            }
        }
    }

    @Test fun everyAnswerEqualsAFreshLookupThroughTableAndWallChanges() {
        val t = Table(7); val memo = NeighbourMemo(); val rnd = Random(11)
        val got = IntArray(50); val want = IntArray(50)
        var hits = 0
        repeat(5000) {
            when (rnd.nextInt(20)) {
                0 -> { t.cells = IntArray(64) { rnd.nextInt(-1, 40) }; memo.changed() }
                1 -> { t.walls = (0 until rnd.nextInt(6)).map { rnd.nextInt(64) }.toSet(); memo.changed() }
            }
            // Mostly the same tile again, as consecutive paste steps are.
            val tx = if (rnd.nextInt(4) == 0) rnd.nextInt(-1, 9) else 3
            val ty = if (rnd.nextInt(4) == 0) rnd.nextInt(-1, 9) else 4
            val r = if (rnd.nextInt(5) == 0) 1 else 2
            val ww = rnd.nextInt(6) == 0
            got.fill(Int.MIN_VALUE)
            if (memo.fill(tx, ty, r, ww, got)) hits++ else { t.lookup(tx, ty, r, ww, got); memo.remember(tx, ty, r, ww, got) }
            t.lookup(tx, ty, r, ww, want)
            val n = (2 * r + 1) * (2 * r + 1) * 2
            assertContentEquals(want.copyOf(n), got.copyOf(n))
        }
        assertTrue(hits > 1000, "the memo serves the repeated steps ($hits)")
    }

    @Test fun aChangedTableOrAnotherQuestionIsNeverServed() {
        val memo = NeighbourMemo(); val out = IntArray(50) { it }
        memo.remember(1, 2, 2, false, out)
        assertTrue(memo.fill(1, 2, 2, false, IntArray(50)))
        assertFalse(memo.fill(1, 2, 1, false, IntArray(50)), "another radius")
        assertFalse(memo.fill(1, 2, 2, true, IntArray(50)), "walls asked")
        assertFalse(memo.fill(2, 2, 2, false, IntArray(50)), "another tile")
        memo.changed()
        assertFalse(memo.fill(1, 2, 2, false, IntArray(50)), "the table changed")
    }
}
