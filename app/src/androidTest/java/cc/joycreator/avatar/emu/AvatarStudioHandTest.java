package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.ui.faditor.FaditorEditorActivity;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteOverlayView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.studio.CharacterLoader;
import cc.joycreator.avatar.studio.CharacterPosers;
import cc.joycreator.avatar.studio.RestPoser;
import cc.joycreator.avatar.studio.RoomExportSheet;
import cc.joycreator.avatar.studio.SolvedStageRenderer;
import cc.joycreator.avatar.studio.SolverPoser;
import cc.joycreator.avatar.ui.StageView;

/**
 * Driving a character in Studio by a finger on a hand (AVA-1 slice 2, item 4), with real touches:
 * <ol>
 *   <li>Studio opens on a new project with Topps dropped in (the intent the room's Send to Studio sends);</li>
 *   <li>his performing pop-up opens (what the Perform chip does), drawn by the solver;</li>
 *   <li>red dot; a finger lands on his left hand on the pop-up's stage, lifts it up and holds; red dot;</li>
 *   <li>the item's take now has the hand's lanes (hLx, hLy) and Studio's preview, during the pass, shows the hand moved
 *   (a different picture from the take's first frame).</li>
 * </ol>
 * {@code -e fault rest} plants Studio's old standing-only drawing: the hand never moves in Studio's preview, the test fails.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarStudioHandTest {

    @After
    public void putBack() {
        CharacterPosers.install(SolverPoser::new);
    }

    @Test
    public void aFingerOnAHandDrivesTheCharacterInStudio() throws Exception {
        if ("rest".equals(Emu.fault())) CharacterPosers.install(RestPoser::new);
        File out = Emu.dir("hand");
        StringBuilder log = new StringBuilder("studio hand\n");
        Intent it = new Intent(Emu.ins().getTargetContext(), FaditorEditorActivity.class)
            .putExtra(RoomExportSheet.EXTRA_DROP_CHARACTER, CharacterLoader.assetRef(CharacterAssets.TOPPS))
            .putExtra(RoomExportSheet.EXTRA_DROP_KEY_COLOUR, 0)
            .putExtra(RoomExportSheet.EXTRA_OPEN_EXPORT, false);
        ActivityScenario<FaditorEditorActivity> sc = ActivityScenario.launch(it);
        try {
            // 1. Studio with Topps on the picture
            SpriteOverlayView layer = null;
            SpriteOverlayItem item = null;
            long end = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < end && item == null) {
                Emu.sleep(1000);
                Activity a = Emu.resumed();
                if (a == null) continue;
                for (SpriteOverlayView sv : find(a.getWindow().getDecorView(), SpriteOverlayView.class)) {
                    for (SpriteOverlayItem i : items(sv)) if (i.isCharacter()) { layer = sv; item = i; }
                }
            }
            shot(out, "1-studio", log);
            assertNotNull("Studio opened with Topps on the picture", item);

            // 2. his performing pop-up (the Perform chip's own call)
            final SpriteOverlayItem fItem = item;
            sc.onActivity(a -> {
                try {
                    Method m = FaditorEditorActivity.class.getDeclaredMethod("openCharacterPerform", SpriteOverlayItem.class);
                    m.setAccessible(true);
                    m.invoke(a, fItem);
                } catch (Exception e) {
                    throw new AssertionError("FaditorEditorActivity.openCharacterPerform(item) is gone; update this test", e);
                }
            });
            Emu.sleep(3000);
            StageView stage = null;
            for (View root : windowRoots()) for (StageView s : find(root, StageView.class)) stage = s;
            assertNotNull("the performing pop-up's stage", stage);
            shot(out, "2-popup", log);
            final StageView st = stage;
            Object[] r = new Object[1];
            Emu.ins().runOnMainSync(() -> r[0] = st.renderer());
            assertTrue("the pop-up's stage is drawn by the solver", r[0] instanceof SolvedStageRenderer);
            SolvedStageRenderer solved = (SolvedStageRenderer) r[0];

            // 3. red dot, a finger lifts his left hand and holds it, red dot
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(700);
            Map<String, double[]> tips = solved.tips();
            double[] hand = tips.get("L") != null ? tips.get("L") : tips.get("R");
            String side = tips.get("L") != null ? "L" : "R";
            assertNotNull("the solver says where a hand is (tips " + tips.keySet() + ")", hand);
            float[] at = new float[3];
            Emu.ins().runOnMainSync(() -> {
                int[] loc = new int[2];
                st.getLocationOnScreen(loc);
                at[0] = loc[0] + st.viewX() + (float) hand[0] * st.viewScale();
                at[1] = loc[1] + st.viewY() + (float) hand[1] * st.viewScale();
                at[2] = st.viewScale();
            });
            log.append(String.format(Locale.ROOT, "hand %s at %.0f, %.0f (character px), on screen %.0f, %.0f, stage scale %.3f\n",
                side, hand[0], hand[1], at[0], at[1], at[2]));
            Emu.drag(at[0], at[1], at[0] + 25 * at[2], at[1] - 150 * at[2], 400, 2200);
            Emu.sleep(300);
            shot(out, "3-popup-after-drag", log);
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(1500);

            // 4. the take has the hand, and Studio's preview shows it moved
            String take = fItem.getCharacterTake();
            assertNotNull("the item has a take", take);
            JSONObject ch = new JSONObject(take).optJSONObject("ch");
            JSONArray hx = ch == null ? null : ch.optJSONArray("h" + side + "x");
            int held = 0, firstHeld = -1;
            for (int i = 0; hx != null && i < hx.length(); i++) if (!hx.isNull(i)) { held++; if (firstHeld < 0) firstHeld = i; }
            log.append("take: h").append(side).append("x has ").append(held).append(" frames with the hand placed, first at frame ").append(firstHeld).append('\n');
            assertTrue("the pass wrote the hand's lanes (h" + side + "x)", held > 10);
            final SpriteOverlayView fLayer = layer;
            long start = fItem.getStartMs();   // (the take starts with the item: no offset on a dropped character)
            Bitmap before = frameAt(fLayer, start + 33);
            for (long t = 66; t < (firstHeld + 30) * 1000L / 30; t += 33) frameAt(fLayer, start + t);   // play on, as playback does
            Bitmap during = frameAt(fLayer, start + (firstHeld + 30) * 1000L / 30);
            Emu.save(before, new File(out, "4-preview-before.png"));
            Emu.save(during, new File(out, "5-preview-hand-up.png"));
            double moved = AvatarStudioDrawTest.share(during, before, 32);
            log.append(String.format(Locale.ROOT, "studio preview: %.2f%% of the character changed while the hand is up\n", moved * 100));
            assertTrue("Studio's preview plays the hand the finger moved (" + String.format(Locale.ROOT, "%.2f%%", moved * 100) + " changed)",
                moved > 0.005);
        } finally {
            if (Emu.find("Done", 1000) != null) Emu.tapText("Done", 500);   // the pop-up closes first
            Emu.closeSoon(sc, 15000);
            Log.i(Emu.TAG, log.toString());
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(out, "hand.txt"))) { f.write(log.toString().getBytes("UTF-8")); }
        }
    }

    private static Bitmap frameAt(SpriteOverlayView v, long ms) {
        Bitmap[] b = new Bitmap[1];
        Emu.ins().runOnMainSync(() -> {
            v.setPlayheadMs(ms);
            b[0] = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(b[0]));
        });
        return b[0];
    }

    private static void shot(File out, String name, StringBuilder log) {
        Bitmap s = Emu.screen();
        if (s != null) {
            try { Emu.save(s, new File(out, name + ".png")); } catch (java.io.IOException e) { Log.w(Emu.TAG, "picture not saved", e); }
            s.recycle();
        }
        log.append("screenshot ").append(name).append('\n');
    }

    /** Every window's root view in this process (the pop-up is a window of its own). */
    @SuppressWarnings("unchecked")
    private static List<View> windowRoots() {
        List<View> roots = new ArrayList<>();
        Emu.ins().runOnMainSync(() -> {
            try {
                Class<?> g = Class.forName("android.view.WindowManagerGlobal");
                Object inst = g.getMethod("getInstance").invoke(null);
                Field f = g.getDeclaredField("mViews");
                f.setAccessible(true);
                roots.addAll((List<View>) f.get(inst));
            } catch (Exception e) {
                throw new AssertionError("cannot list the windows", e);
            }
        });
        return roots;
    }

    @SuppressWarnings("unchecked")
    private static List<SpriteOverlayItem> items(SpriteOverlayView v) throws Exception {
        Field f = SpriteOverlayView.class.getDeclaredField("items");
        f.setAccessible(true);
        Object[] o = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try { o[0] = new ArrayList<>((List<SpriteOverlayItem>) f.get(v)); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return (List<SpriteOverlayItem>) o[0];
    }

    private static <T extends View> List<T> find(View root, Class<T> type) {
        List<T> hits = new ArrayList<>();
        Emu.ins().runOnMainSync(() -> walk(root, type, hits));
        return hits;
    }

    private static <T extends View> void walk(View v, Class<T> type, List<T> hits) {
        if (type.isInstance(v)) hits.add(type.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) walk(((ViewGroup) v).getChildAt(i), type, hits);
    }
}
