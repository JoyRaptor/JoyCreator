package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.joyfolder.studio.JoyFolderSetupActivity;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteOverlayView;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.studio.CharacterPosers;
import cc.joycreator.avatar.studio.RestPoser;
import cc.joycreator.avatar.studio.SolverPoser;

/**
 * Send to Studio, end to end, as a person does it (AVA-1 slice 2, item 5), with real taps:
 * <ol>
 *   <li>the Joy Creator folder is chosen through the app's own folder step and the system picker (a folder the workflow
 *   made, {@code JoyTest}), as on a new phone;</li>
 *   <li>the room opens with Topps; red dot, the head pad dragged to one side and held, red dot: a pass that turns his head;</li>
 *   <li>the room's export mark, Send to Studio, Make it, A new project;</li>
 *   <li>Studio opens with Topps on the picture, carrying the pass; its preview at the start of the take and while the head
 *   is turned must differ inside the character's box (the item plays its take).</li>
 * </ol>
 * Screenshots of every step are saved. {@code -e fault rest} plants Studio's old standing-only drawing: the head never
 * turns in Studio and the test must fail.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarSendToStudioTest {

    @After
    public void putBack() {
        CharacterPosers.install(SolverPoser::new);
    }

    @Test
    public void sendToStudioPlaysTheRoomsPass() throws Exception {
        if ("rest".equals(Emu.fault())) CharacterPosers.install(RestPoser::new);
        Context app = Emu.ins().getTargetContext();
        File out = Emu.dir("send");
        StringBuilder log = new StringBuilder("send to studio\n");
        try {
            chooseFolder(app, out, log);

            ActivityScenario<AvatarRoomActivity> room = ActivityScenario.launch(AvatarRoomActivity.class);
            Emu.sleep(4000);
            shot(out, "1-room", log);
            // a pass: red dot, the head pad pushed right and held, red dot
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(600);
            float[] pad = Emu.centreOf("Drag to turn the head", 5000);
            assertNotNull("the room's head pad", pad);
            Emu.drag(pad[0], pad[1], pad[0] + pad[2] * 0.45f, pad[1] - pad[3] * 0.1f, 300, 2500);
            Emu.sleep(300);
            Emu.tapText("Record a pass", 5000);
            Emu.sleep(1000);
            shot(out, "2-room-after-pass", log);

            // the export mark, Send to Studio, Make it, A new project
            Emu.tapText("Export: keep it", 5000);
            Emu.tapText("Send to Studio", 15000);
            Emu.sleep(500);
            shot(out, "3-export-sheet", log);
            Emu.tapText("Make it", 5000);
            Emu.tapText("A new project", 10000);

            // Studio opens and drops Topps with the pass
            SpriteOverlayView v = null;
            SpriteOverlayItem item = null;
            long end = System.currentTimeMillis() + 40000;
            while (System.currentTimeMillis() < end && item == null) {
                Emu.sleep(1000);
                Activity a = Emu.resumed();
                if (a == null || !a.getClass().getName().endsWith("FaditorEditorActivity")) continue;
                for (SpriteOverlayView sv : find(a.getWindow().getDecorView(), SpriteOverlayView.class)) {
                    for (SpriteOverlayItem it : items(sv)) {
                        if (it.isCharacter()) { v = sv; item = it; }
                    }
                }
            }
            shot(out, "4-studio-open", log);
            assertNotNull("Studio opened with the character on the picture", item);
            String take = item.getCharacterTake();
            assertTrue("the Studio item carries the room's pass (a turnX lane)", take != null && new JSONObject(take).toString().contains("turnX"));
            log.append("item ").append(item.getName()).append(", ").append(item.getStartMs()).append("-").append(item.getEndMs())
                .append(" ms, take ").append(take.length()).append(" chars\n");

            // its preview at the take's start and while the head is turned (the pass's middle)
            final SpriteOverlayView fv = v;
            final SpriteOverlayItem fi = item;
            RectF box = box(fv, fi);
            Bitmap still = frameAt(fv, fi.getStartMs() + 33, out, "5-studio-start", log);
            for (long t = 66; t < 1500; t += 33) frameAt(fv, fi.getStartMs() + t, null, null, null);   // play on, as playback does
            Bitmap turned = frameAt(fv, fi.getStartMs() + 1500, out, "6-studio-turned", log);
            // the layer holds only the sprites (the character alone here), so the whole layer is compared
            double drawn = AvatarStudioDrawTest.share(turned, null, 0), moved = AvatarStudioDrawTest.share(turned, still, 32);
            log.append(String.format(Locale.ROOT, "layer %dx%d, character box about %s, drawn %.1f%% of the layer, moved %.2f%% of its drawn pixels\n",
                turned.getWidth(), turned.getHeight(), box.toShortString(), drawn * 100, moved * 100));
            assertTrue("Topps is drawn in Studio's preview", drawn > 0.01);
            assertTrue("Topps plays the room's pass in Studio's preview (head turned), moved " + String.format(Locale.ROOT, "%.2f%%", moved * 100),
                moved > 0.01);
            room.close();
        } finally {
            Log.i(Emu.TAG, log.toString());
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(new File(out, "send.txt"))) { f.write(log.toString().getBytes("UTF-8")); }
        }
    }

    /** The app's own folder step and the system picker: the folder JoyTest the workflow made at the top of storage. */
    private static void chooseFolder(Context app, File out, StringBuilder log) throws Exception {
        if (JoyFolderHome.isReachable(app)) { log.append("folder already chosen\n"); return; }
        try (ActivityScenario<JoyFolderSetupActivity> s = ActivityScenario.launch(JoyFolderSetupActivity.class)) {
            Emu.sleep(1500);
            Emu.tapText("I already have one", 8000);
            Emu.sleep(2500);
            shot(out, "0-picker", log);
            Emu.tapText("JoyTest", 15000);
            Emu.sleep(1500);
            Emu.tapText("Use this folder", 10000);
            Emu.tapText("Allow", 10000);
            long end = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < end && !JoyFolderHome.isReachable(app)) Emu.sleep(500);
            Emu.sleep(3000);   // the folder's first index
            shot(out, "0-folder-chosen", log);
        }
        assertTrue("the Joy Creator folder is chosen", JoyFolderHome.isReachable(app));
        log.append("folder chosen: ").append(JoyFolderHome.treeUri(app)).append('\n');
    }

    /** Studio's preview layer drawn at one time (the playhead put there, as a scrub does). */
    private static Bitmap frameAt(SpriteOverlayView v, long ms, File out, String name, StringBuilder log) {
        Bitmap[] b = new Bitmap[1];
        Emu.ins().runOnMainSync(() -> {
            v.setPlayheadMs(ms);
            b[0] = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
            v.draw(new android.graphics.Canvas(b[0]));
        });
        if (out != null) {
            Emu.save(b[0], new File(out, name + "-layer.png"));
            shot(out, name, log);
        }
        return b[0];
    }

    /** The character's box in the layer (centre and height as Studio places it, the picture's shape). */
    private static RectF box(SpriteOverlayView v, SpriteOverlayItem it) {
        float[] r = new float[4];
        Emu.ins().runOnMainSync(() -> {
            float w = v.getWidth(), h = v.getHeight();
            float bh = it.getSizeFraction() * h, bw = bh;     // Topps is square
            float cx = it.getCenterX() * w, cy = it.getCenterY() * h;
            r[0] = Math.max(0, cx - bw / 2); r[1] = Math.max(0, cy - bh / 2); r[2] = Math.min(w, cx + bw / 2); r[3] = Math.min(h, cy + bh / 2);
        });
        return new RectF(r[0], r[1], r[2], r[3]);
    }

    private static void shot(File out, String name, StringBuilder log) {
        Bitmap s = Emu.screen();
        if (s != null) {
            Emu.save(s, new File(out, name + ".png"));
            s.recycle();
        }
        if (log != null) log.append("screenshot ").append(name).append('\n');
    }

    @SuppressWarnings("unchecked")
    private static List<SpriteOverlayItem> items(SpriteOverlayView v) throws Exception {
        Field f = SpriteOverlayView.class.getDeclaredField("items");
        f.setAccessible(true);
        Object[] o = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try { o[0] = new java.util.ArrayList<>((List<SpriteOverlayItem>) f.get(v)); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return (List<SpriteOverlayItem>) o[0];
    }

    private static <T extends View> List<T> find(View root, Class<T> type) {
        List<T> hits = new java.util.ArrayList<>();
        Emu.ins().runOnMainSync(() -> walk(root, type, hits));
        return hits;
    }

    private static <T extends View> void walk(View v, Class<T> type, List<T> hits) {
        if (type.isInstance(v)) hits.add(type.cast(v));
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) walk(((ViewGroup) v).getChildAt(i), type, hits);
    }
}
