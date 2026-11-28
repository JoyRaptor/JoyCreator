package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import cc.joycreator.avatar.AvatarRoomActivity;

/**
 * A scene in hand on the four stages, with real taps (AVA-1 slice 3, row 3): new scene on a set, Set up, Arrange (a character comes in), Motion, Perform with the frame, leave. A screenshot after every step. Old header: 
 * the frame turns on and shows its shape, that the chair opens Scene / Character / Shots, that the strip shows and shares its width,
 * that Shots turn the frame on, that a shot card changes the camera, that a long press on the frame opens its sheet and a shape
 * chosen there changes the frame (an upright frame takes the strip from the side). A screenshot after every step.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarSceneTakeTest {

    /** Two characters in a scene each record a take, then play together on one clock. */
    @Test
    public void twoCharactersRecordAndPlayTogether() throws Exception {
        File out = Emu.dir("scenetakes");
        Emu.turn();
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            AvatarRoomActivity[] a = new AvatarRoomActivity[1];
            sc.onActivity(x -> a[0] = x);
            View root = a[0].getWindow().getDecorView();
            View chair = null;
            for (int i = 0; i < 60 && chair == null; i++) {
                chair = byDesc(root, "The director");
                if (chair == null) Emu.sleep(1000);
            }
            assertNotNull("the director's chair button", chair);
            Emu.tap(chair);
            Emu.sleep(600);
            Emu.tap(mustText(root, "Scene"));
            Emu.sleep(600);
            Emu.tap(mustText(root, "Character"));
            Emu.sleep(1000);
            Emu.tap(must(root, "Make a new scene on a set"));
            Emu.sleep(900);
            assertTrue("Meadow", click("Meadow"));
            Emu.sleep(2500);
            Emu.tap(mustText(root, "Arrange"));
            Emu.sleep(1000);
            for (String who : new String[]{"Topps", "Pip"}) {
                Emu.tap(mustText(root, "+ Character"));
                Emu.sleep(1500);
                assertTrue(who, click(who));
                Emu.sleep(3000);
            }
            shot(out, "1-two-characters");
            Emu.tap(mustText(root, "Perform"));
            Emu.sleep(2000);
            shot(out, "2-perform-in-the-scene");
            // the first one in hand records a pass with the room's own Record button
            View rec = byDesc(root, "Record a pass");
            assertNotNull("the room's Record button works in a scene (Perform's panel)", rec);
            Emu.tap(rec);
            Emu.sleep(3000);
            Emu.tap(must(root, "Record a pass"));
            Emu.sleep(1500);
            assertEquals("one take in the scene", 1, takes(a[0]));
            // the second takes the hand and records
            View pip = byDesc(root, "Take Pip in hand");
            assertNotNull("Pip's card on the Character part", pip);
            Emu.tap(pip);
            Emu.sleep(1500);
            Emu.tap(must(root, "Record a pass"));
            Emu.sleep(3000);
            Emu.tap(must(root, "Record a pass"));
            Emu.sleep(1500);
            assertEquals("two takes in the scene", 2, takes(a[0]));
            shot(out, "3-two-takes");
            View play = byDesc(root, "Play the whole scene");
            assertNotNull("the strip's Play the whole scene", play);
            Emu.tap(play);
            Emu.sleep(1500);
            shot(out, "4-playing-together");
            assertTrue("the scene is playing", call(a[0], "playingAll"));
            Emu.tap(must(root, "Stop the scene"));
            Emu.sleep(1000);
            assertFalse("the scene stopped", call(a[0], "playingAll"));
        }
    }

    private static View must(View root, String desc) {
        View v = byDesc(root, desc);
        assertNotNull("no shown view described \"" + desc + "\"", v);
        return v;
    }

    private static View mustText(View root, String text) {
        View v = byText(root, text);
        assertNotNull("no tappable text \"" + text + "\"", v);
        return v;
    }

    private static int takes(AvatarRoomActivity a) throws Exception {
        Object r = field(a, "sceneRoom");
        int[] v = new int[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                Field f = r.getClass().getDeclaredField("takes");
                f.setAccessible(true);
                v[0] = ((java.util.Map<?, ?>) f.get(r)).size();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return v[0];
    }

    private static boolean call(AvatarRoomActivity a, String method) throws Exception {
        Object r = field(a, "sceneRoom");
        boolean[] v = new boolean[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                v[0] = (Boolean) r.getClass().getMethod(method).invoke(r);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return v[0];
    }

    private void shot(File out, String name) throws Exception {
        Bitmap s = Emu.screen();
        assertNotNull("no screenshot", s);
        Emu.save(s, new File(out, name + ".png"));
        s.recycle();
    }

    private static List<View> all(View v) {
        List<View> l = new ArrayList<>();
        l.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) l.addAll(all(((ViewGroup) v).getChildAt(i)));
        return l;
    }

    private static View byDesc(View root, String prefix) {
        for (View v : all(root)) {
            CharSequence d = v.getContentDescription();
            if (d != null && d.toString().startsWith(prefix) && v.isShown()) return v;
        }
        return null;
    }

    /** A tappable thing with this text (the chair's chips, the sheet's buttons), not a label that only says it. */
    private static View byText(View root, String text) {
        for (View v : all(root)) if (v instanceof TextView && text.contentEquals(((TextView) v).getText()) && v.isShown() && v.isClickable()) return v;
        return null;
    }

    private static boolean hasText(View root, String text) {
        for (View v : all(root)) if (v instanceof TextView && text.contentEquals(((TextView) v).getText()) && v.isShown()) return true;
        return false;
    }

    /** The strip is the layout the scene classes tagged. */
    private static View stripView(View root) {
        for (View v : all(root)) if ("scene-strip".equals(v.getTag()) && v.isShown()) return v;
        return null;
    }

    private static int stripParts(View root) {
        View s = stripView(root);
        return s instanceof ViewGroup ? ((ViewGroup) s).getChildCount() : 0;
    }

    /** A node of whatever window is in front (a dialog is its own window, not part of the room's views). */
    private static android.view.accessibility.AccessibilityNodeInfo node(String text) {
        android.view.accessibility.AccessibilityNodeInfo r = Emu.ins().getUiAutomation().getRootInActiveWindow();
        if (r == null) return null;
        List<android.view.accessibility.AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByText(text);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private static boolean click(String text) {
        android.view.accessibility.AccessibilityNodeInfo n = node(text);
        while (n != null && !n.isClickable()) n = n.getParent();
        return n != null && n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        Object[] v = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                v[0] = f.get(o);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
        return v[0];
    }
}
