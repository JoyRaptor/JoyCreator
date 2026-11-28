package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;

import cc.joycreator.avatar.AvatarRoomActivity;

/**
 * The character stays on the stage while the room is left in Perform (the lead, 11-28: Topps was missing from the stage in Motion
 * and Perform in a cards run whose room had sat in Perform about seven minutes before its first step; runs that sat one to three
 * minutes kept him). The room opens in Perform and is left alone for {@code -e idleMinutes} (default 9); once a minute the stage
 * is photographed, how much of it the character covers is counted, and the walker's numbers and the drawn root are logged. The
 * character must still cover at least half of what it covered in the first minute.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarIdleTest {
    private AvatarRoomActivity room;

    @Test
    public void theCharacterStaysOnTheStageInPerform() throws Exception {
        File out = Emu.dir("idle");
        Emu.watchMainThread();
        Emu.turn();
        int minutes = Integer.parseInt(Emu.arg("idleMinutes", "9"));
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            AvatarRoomActivity[] a = new AvatarRoomActivity[1];
            sc.onActivity(x -> a[0] = x);
            room = a[0];
            Emu.sleep(5000);
            int first = -1, least = Integer.MAX_VALUE;
            StringBuilder log = new StringBuilder();
            for (int m = 0; m <= minutes; m++) {
                if (m > 0) Emu.sleep(60_000);
                Bitmap s = Emu.screen();
                assertNotNull("no screenshot", s);
                int[] r = stageRect();
                int drawn = drawn(s, r);
                Emu.save(s, new File(out, String.format(Locale.US, "perform-%02d-min.png", m)));
                s.recycle();
                String line = String.format(Locale.US, "minute %d: %d pixels drawn; %s; %s", m, drawn, walker(), Emu.notANumber(room));
                Log.i(Emu.TAG, "IDLE " + line);
                log.append(line).append('\n');
                if (first < 0) first = drawn;
                least = Math.min(least, drawn);
            }
            Log.i(Emu.TAG, "idle\n" + log);
            assertTrue("nothing was drawn at the start: " + first, first > 1000);
            assertTrue("the character left the stage while the room sat in Perform:\n" + log, least >= first / 2);
        }
    }

    /** The GPU stage's place on the screen: left, top, right, bottom. */
    private int[] stageRect() throws Exception {
        int[] r = new int[4];
        Emu.ins().runOnMainSync(() -> {
            try {
                View gl = (View) field(room, "glStage");
                int[] at = new int[2];
                gl.getLocationOnScreen(at);
                r[0] = at[0];
                r[1] = at[1];
                r[2] = at[0] + gl.getWidth();
                r[3] = at[1] + gl.getHeight();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return r;
    }

    /** How many pixels of the stage (every fourth on a side) are not the stage's near-black ground. */
    private static int drawn(Bitmap s, int[] r) {
        int n = 0;
        for (int y = Math.max(0, r[1]); y < Math.min(s.getHeight(), r[3]); y += 4)
            for (int x = Math.max(0, r[0]); x < Math.min(s.getWidth(), r[2]); x += 4) {
                int c = s.getPixel(x, y);
                if (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255) > 90) n++;
            }
        return n;
    }

    /** The walker's place and target, its ground line and depth, and the signals' root. */
    private String walker() {
        StringBuilder b = new StringBuilder();
        Emu.ins().runOnMainSync(() -> {
            try {
                Object w = field(room, "walker");
                if (w == null) {
                    b.append("no walker");
                } else {
                    for (String f : new String[]{"x", "y", "tx", "ty", "baseY", "vx", "idle"})
                        b.append(f).append('=').append(String.format(Locale.US, "%.1f", w.getClass().getField(f).getDouble(w))).append(' ');
                    b.append("tz=").append(w.getClass().getField("tz").get(w)).append(' ');
                    b.append("held=").append(w.getClass().getField("held").get(w)).append(' ');
                }
                Object sig = field(room, "lastSignals");
                if (sig instanceof java.util.Map) {
                    java.util.Map<?, ?> m = (java.util.Map<?, ?>) sig;
                    b.append("turnX=").append(m.get("turnX")).append(" turnY=").append(m.get("turnY"));
                }
                b.append(" stage=").append(field(room, "stageIndex"));
            } catch (Exception e) {
                b.append("(could not read: ").append(e).append(')');
            }
        });
        return b.toString();
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
