package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.export.CompositeExportOverlay;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.perform.Take;
import cc.joycreator.avatar.studio.CharacterLoader;
import cc.joycreator.avatar.studio.ItemPuppet;
import cc.joycreator.avatar.studio.ItemPuppets;
import cc.joycreator.avatar.studio.StudioCharacter;

/**
 * The X6 audit's findings in the auditor's own code (tasks/audits/claude/AVA_X6_20261128.md), each a check that failed
 * on the code before its fix:
 * <ul>
 *   <li>M4: the same moment drawn again (a paused preview redrawn) is the same picture; before, every redraw stepped the
 *   springs and the paused preview drifted away from the export;</li>
 *   <li>M8: a few ms of time going back (player jitter) does not start the springs again;</li>
 *   <li>M5: a rewind then a replay draws what a fresh start draws (the solver's caches kept, its state not);</li>
 *   <li>M7: the export draws a character whose standing picture is missing, as the preview does;</li>
 *   <li>P13: letting go of everything (and a new character) sends the hands home.</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarX6Test {
    private static final int W = 512, H = 512;

    /** A take that never stops moving: the head turns back and forth, the mouth opens and shuts. */
    static Take busy() {
        Take t = new Take();
        t.n = 90;
        List<Object> turn = new ArrayList<>(), mouth = new ArrayList<>();
        for (int i = 0; i < t.n; i++) {
            turn.add(0.8 * Math.sin(i * 0.35));
            mouth.add(0.5 + 0.5 * Math.sin(i * 0.6));
        }
        t.ch.put("turnX", turn);
        t.ch.put("mouth", mouth);
        return t;
    }

    private static Context app() {
        return Emu.ins().getTargetContext();
    }

    private static SpriteOverlayItem topps(Take take) throws Exception {
        File assets = new File(app().getCacheDir(), "avatar-x6");
        assets.mkdirs();
        StudioCharacter.Prepared p = StudioCharacter.prepareNow(app(), CharacterLoader.assetRef(CharacterAssets.TOPPS), assets);
        SpriteSheet sheet = new SpriteSheet(p.sheetId(), p.name, Uri.fromFile(p.picture).toString());
        sheet.setGrid(1, 1);
        return AvatarStudioDrawTest.item(p, sheet, take);
    }

    /** One puppet's picture at item time {@code ms}. */
    private static Bitmap at(ItemPuppet pp, SpriteOverlayItem item, long ms) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.TRANSPARENT);
        Bitmap[] out = {b};
        Emu.ins().runOnMainSync(() -> pp.draw(new Canvas(out[0]), new RectF(0, 0, W, H), item, ms, 1f));
        return b;
    }

    private static double apart(Bitmap a, Bitmap b) {
        return AvatarStudioDrawTest.share(a, b, 8);
    }

    @Test
    public void m4_aPausedPreviewDoesNotDrift() throws Exception {
        SpriteOverlayItem item = topps(busy());
        ItemPuppet pp = ItemPuppets.forItem(app(), item, null, null, null);
        try {
            Bitmap first = null;
            for (int i = 0; i <= 12; i++) first = at(pp, item, i * 1000L / 30);
            Bitmap again = null;
            for (int k = 0; k < 5; k++) again = at(pp, item, 12 * 1000L / 30);   // the same moment, five redraws
            double d = apart(first, again);
            Log.i(Emu.TAG, String.format(Locale.ROOT, "x6 M4: redraws at one moment differ by %.3f%%", d * 100));
            assertTrue("redrawing a paused moment must not move the character (" + d * 100 + "% differs)", d < 0.0005);
        } finally {
            pp.release();
        }
    }

    @Test
    public void m8_jitterBackDoesNotRestartTheSprings() throws Exception {
        SpriteOverlayItem item = topps(busy());
        ItemPuppet a = ItemPuppets.forItem(app(), item, null, null, null), b = ItemPuppets.forItem(app(), item, null, null, null);
        try {
            Bitmap wobble = null, steady = null;
            for (int i = 0; i <= 20; i++) {
                long ms = i * 1000L / 30;
                at(a, item, ms);
                Bitmap pb = at(b, item, ms);
                if (i == 20) {
                    wobble = at(a, item, ms - 12);                                // the player's position wobbles back 12 ms
                    steady = pb;
                }
            }
            double d = apart(wobble, steady);
            Log.i(Emu.TAG, String.format(Locale.ROOT, "x6 M8: with a 12 ms wobble %.3f%% differs from without", d * 100));
            assertTrue("a 12 ms wobble back must not restart the character (" + d * 100 + "% differs)", d < 0.0005);
        } finally {
            a.release();
            b.release();
        }
    }

    @Test
    public void m5_aRewindThenReplayIsAFreshStart() throws Exception {
        SpriteOverlayItem item = topps(busy());
        ItemPuppet a = ItemPuppets.forItem(app(), item, null, null, null), b = ItemPuppets.forItem(app(), item, null, null, null);
        try {
            for (int i = 0; i <= 40; i++) at(a, item, i * 1000L / 30);           // a plays on, then is scrubbed back to 0
            Bitmap pa = null, pb = null;
            for (int i = 0; i <= 15; i++) {
                pa = at(a, item, i * 1000L / 30);
                pb = at(b, item, i * 1000L / 30);
            }
            double d = apart(pa, pb);
            Log.i(Emu.TAG, String.format(Locale.ROOT, "x6 M5: replay after a rewind vs a fresh start %.3f%%", d * 100));
            assertTrue("a replay after a rewind must draw what a fresh start draws (" + d * 100 + "% differs)", d < 0.0005);
        } finally {
            a.release();
            b.release();
        }
    }

    @Test
    public void m7_theExportDrawsACharacterWhosePictureIsMissing() throws Exception {
        SpriteOverlayItem item = topps(busy());
        SpriteSheet gone = new SpriteSheet(item.getSheetId(), "Topps", Uri.fromFile(new File(app().getCacheDir(), "no-such-picture.png")).toString());
        gone.setGrid(1, 1);
        Clip clip = new Clip(Uri.parse("file:///avatar-emu/none.mp4"), 3000);
        CompositeExportOverlay o = new CompositeExportOverlay(app(), 0, clip, 720, 720, new ArrayList<>(), new ArrayList<>(),
            new ArrayList<>(), Collections.singletonList(item), Collections.singletonList(gone), new ArrayList<>(), 3000, 0, 0, false);
        try {
            o.configure(new Size(720, 720));
            Bitmap last = null;
            for (int i = 0; i < 6; i++) last = o.getBitmap(i * 1000_000L / 30);
            double drawn = AvatarStudioDrawTest.share(last, null, 0);
            Log.i(Emu.TAG, String.format(Locale.ROOT, "x6 M7: export without the picture draws %.1f%%", drawn * 100));
            assertTrue("the export must draw the character as the preview does (drawn " + drawn * 100 + "%)", drawn > 0.02);
        } finally {
            o.release();
        }
    }

    @Test
    public void p13_lettingGoSendsTheHandsHome() {
        Inputs in = new Inputs();
        in.holdHand("L", 120, 200);
        in.letGoHand("L");
        Map<String, Object> f = in.frame(1 / 30.0, 1000);
        assertTrue("the held hand is in the frame first", f.get("hLx") instanceof Number);
        in.letGoOfEverything();
        Map<String, Object> g = in.frame(1 / 30.0, 1100);
        assertNull("after letting go of everything the hand is at rest (hLx " + g.get("hLx") + ")", g.get("hLx"));
    }
}
