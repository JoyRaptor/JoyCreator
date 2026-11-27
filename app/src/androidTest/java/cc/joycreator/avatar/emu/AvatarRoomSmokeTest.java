package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.file.CharacterAssets;

/**
 * The Avatar Studio room on a screen (AVA-1 slice 1): opens with Topps, then real taps on each of the four stages (Set up,
 * Arrange, Motion, Perform), then Pip the same way. After every tap: the room is still there, the stage tapped is the one
 * chosen, the character is drawn on the stage (not an empty stage), and a screenshot is saved for the run's artifact.
 *
 * <p>Reads the room's own fields by name (stageTabs, stageIndex, glStage): no test hooks in the room's code. If one is
 * renamed this test says which.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarRoomSmokeTest {

    @Test
    public void tapsThroughTheFourStagesWithToppsAndPip() throws Exception {
        File out = Emu.dir("room");
        StringBuilder log = new StringBuilder();
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            Emu.sleep(4000);                                   // the stage's first frames (SwiftShader compiles slowly)
            AvatarRoomActivity[] a = new AvatarRoomActivity[1];
            sc.onActivity(x -> a[0] = x);
            shot(a[0], out, "topps-open", log);
            stages(a[0], out, "topps", log);
            sc.onActivity(x -> x.openBuiltIn(CharacterAssets.PIP));
            Emu.sleep(3000);
            shot(a[0], out, "pip-open", log);
            stages(a[0], out, "pip", log);
            assertFalse("the room closed", a[0].isFinishing());
        } finally {
            Log.i(Emu.TAG, "room smoke\n" + log);
        }
    }

    private void stages(AvatarRoomActivity a, File out, String who, StringBuilder log) throws Exception {
        TextView[] tabs = (TextView[]) field(a, "stageTabs");
        assertEquals("four stages", 4, tabs.length);
        for (int i = 0; i < 4; i++) {
            assertNotNull("stage " + (i + 1) + " button", tabs[i]);
            Emu.tap(tabs[i]);
            Emu.sleep(1800);
            int now = (Integer) field(a, "stageIndex");
            assertEquals(who + ": tapping " + tabs[i].getText() + " chose it", i, now);
            shot(a, out, who + "-stage" + (i + 1) + "-" + tabs[i].getText().toString().toLowerCase(Locale.ROOT).replace(' ', '-'), log);
            assertFalse("the room closed after " + tabs[i].getText(), a.isFinishing());
        }
    }

    /** Saves the screen and checks the stage area holds a drawing: enough pixels unlike the stage's own ground colour. */
    private void shot(AvatarRoomActivity a, File out, String name, StringBuilder log) throws Exception {
        Bitmap s = Emu.screen();
        assertNotNull("no screenshot", s);
        Emu.save(s, new File(out, name + ".png"));
        View st = (View) field(a, "glStage");
        int[] at = new int[2];
        Emu.ins().runOnMainSync(() -> st.getLocationOnScreen(at));
        int x0 = Math.max(0, at[0]), y0 = Math.max(0, at[1]);
        int x1 = Math.min(s.getWidth(), at[0] + st.getWidth()), y1 = Math.min(s.getHeight(), at[1] + st.getHeight());
        assertTrue(name + ": the stage has no size on screen", x1 - x0 > 20 && y1 - y0 > 20);
        int w = x1 - x0, h = y1 - y0;
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, x0, y0, w, h);
        Map<Integer, Integer> count = new HashMap<>();
        int ground = 0, most = 0;
        for (int p : px) {
            int c = count.merge(p & 0xf0f0f0, 1, Integer::sum);
            if (c > most) { most = c; ground = p; }
        }
        int drawn = 0;
        for (int p : px) {
            int d = Math.max(Math.abs(((p >> 16) & 255) - ((ground >> 16) & 255)),
                Math.max(Math.abs(((p >> 8) & 255) - ((ground >> 8) & 255)), Math.abs((p & 255) - (ground & 255))));
            if (d > 24) drawn++;
        }
        double share = drawn / (double) px.length;
        log.append(String.format(Locale.ROOT, "%-28s stage %dx%d, %.1f%% drawn\n", name, w, h, share * 100));
        s.recycle();
        assertTrue(name + ": the stage looks empty (" + String.format(Locale.ROOT, "%.2f", share * 100) + "% drawn)", share > 0.01);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f;
        try {
            f = o.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("AvatarRoomActivity has no field '" + name + "' any more; update AvatarRoomSmokeTest", e);
        }
        f.setAccessible(true);
        Object[] v = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try { v[0] = f.get(o); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return v[0];
    }
}
