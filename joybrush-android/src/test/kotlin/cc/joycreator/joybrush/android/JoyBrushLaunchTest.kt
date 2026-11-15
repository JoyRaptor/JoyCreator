package cc.joycreator.joybrush.android

import android.content.Intent
import cc.joycreator.joybrush.android.board.BoardPresetRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BOT-7: the board preset Joybot opens JoyBrush with, and where the board goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class JoyBrushLaunchTest {
    @Test fun aPresetGoesThroughAnIntentAndIsTakenOnce() {
        val p = JoyBrushLaunch.Preset(board = "animation", aspect = "16:9", fps = 8, guide = "perspective-2", name = "Walk")
        val intent = JoyBrushLaunch.put(Intent(), p)
        assertEquals(p, JoyBrushLaunch.presetOf(intent))
        JoyBrushLaunch.clear(intent)
        assertNull(JoyBrushLaunch.presetOf(intent))
        assertNull(JoyBrushLaunch.presetOf(Intent()))
    }

    @Test fun badPresetsSayWhatIsWrongInWords() {
        assertNull(JoyBrushLaunch.Preset(board = "sprite", columns = 8, rows = 2).problem())
        assertNotNull(JoyBrushLaunch.Preset(board = "comic").problem())
        assertNotNull(JoyBrushLaunch.Preset(board = "image", aspect = "wide").problem())
        assertNotNull(JoyBrushLaunch.Preset(board = "animation", fps = 120).problem())
        assertNotNull(JoyBrushLaunch.Preset(guide = "spiral").problem())
        assertEquals(4 to 3, JoyBrushLaunch.Preset(board = "image").aspectParts())
        assertEquals(1 to 1, JoyBrushLaunch.Preset(board = "tile").aspectParts())
        assertEquals(9 to 16, JoyBrushLaunch.Preset(board = "image", aspect = "9:16").aspectParts())
    }

    /** Joybot's own extra names work, and anything JoyBrush cannot use is left out quietly (a plain drawing at worst). */
    @Test fun joybotsNamesWorkAndBadValuesAreDropped() {
        val i = Intent().putExtra("jb_new_kind", "sprite").putExtra("jb_grid", "8x2").putExtra("jb_cell_px", 64)
            .putExtra("jb_perspective", 2).putExtra("jb_fps", "12")
        val p = JoyBrushLaunch.presetOf(i)!!
        assertEquals("sprite", p.board); assertEquals(8, p.columns); assertEquals(2, p.rows); assertEquals(64, p.cellPx)
        assertEquals("perspective-2", p.guide); assertEquals(12, p.fps)
        val bad = JoyBrushLaunch.presetOf(Intent().putExtra("jb_new_kind", "comic").putExtra("jb_ratio", "wide")
            .putExtra("jb_tile_px", 99999).putExtra("jb_fps", 500))!!
        assertNull(bad.board); assertNull(bad.aspect); assertNull(bad.tilePx); assertNull(bad.fps)
        assertTrue(bad.isEmpty())
        assertEquals(512, JoyBrushLaunch.presetOf(Intent().putExtra("jb_new_kind", "tile").putExtra("jb_tile_px", 512))!!.tilePx)
        assertEquals(BoardPresetRect.centred(0f, 0f, 1000f, 1000f, 512, 512), cc.joycreator.joybrush.core.doc.RectPx(244, 244, 512, 512))
    }

    @Test fun theBoardIsCentredAtItsShapeAndASpriteIsAWholeGrid() {
        val r = BoardPresetRect.fit(0f, 0f, 1000f, 800f, 16, 9)
        assertEquals(16.0 / 9.0, r.w.toDouble() / r.h, 0.02)
        assertTrue(r.w <= 700 && r.h <= 560)
        assertEquals(500f, r.x + r.w / 2f, 1f); assertEquals(400f, r.y + r.h / 2f, 1f)
        val s = BoardPresetRect.fit(100f, 100f, 1100f, 900f, 1, 1, columns = 8, rows = 2)
        assertEquals(0, s.w % 8); assertEquals(0, s.h % 2)
        assertEquals(s.w / 8, s.h / 2)
        // Zoomed far in, a board still has room to draw on.
        val tiny = BoardPresetRect.fit(0f, 0f, 20f, 20f, 1, 1)
        assertTrue(tiny.w >= BoardPresetRect.MIN_SIDE)
    }
}
