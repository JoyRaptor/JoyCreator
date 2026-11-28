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
public class AvatarScenePictureTest {

    /** Pictures from files become a backdrop and an object in the scene (the picker's answer is handed to the room as the system would). */
    @Test
    public void picturesFromFilesJoinTheScene() throws Exception {
        File out = Emu.dir("scenepics");
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
            Emu.tap(byText(root, "Scene"));
            Emu.sleep(1200);
            Emu.tap(byDesc(root, "Make a new scene on a set"));
            Emu.sleep(900);
            assertTrue("Room", click("Room"));
            Emu.sleep(2500);
            Emu.tap(byText(root, "Arrange"));
            Emu.sleep(1000);
            assertNotNull("the + Backdrop chip", byText(root, "+ Backdrop"));
            assertNotNull("the + Object chip", byText(root, "+ Object"));
            assertNotNull("the + Foreground chip", byText(root, "+ Foreground"));
            File dir = a[0].getCacheDir();
            File red = png(new File(dir, "red.png"), 0xFFCC2222, 600, 900);
            File sky = png(new File(dir, "sky.png"), 0xFF3377DD, 1600, 900);
            int before = items(a[0]);
            give(a[0], "back", sky);
            Emu.sleep(2500);
            give(a[0], "obj", red);
            Emu.sleep(2500);
            assertEquals("two things came in", before + 2, items(a[0]));
            shot(out, "1-backdrop-and-object");
            assertNotNull("the new object is in hand with its size slider", byText(root, "Flip"));
        }
    }

    private static File png(File f, int color, int w, int h) throws Exception {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(color);
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
        }
        return f;
    }

    private static void give(AvatarRoomActivity a, String kind, File f) throws Exception {
        Object r = field(a, "sceneRoom");
        Emu.ins().runOnMainSync(() -> {
            try {
                java.util.List<android.net.Uri> l = new ArrayList<>();
                l.add(android.net.Uri.fromFile(f));
                r.getClass().getMethod("picturesChosen", String.class, java.util.List.class).invoke(r, kind, l);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static int items(AvatarRoomActivity a) throws Exception {
        Object r = field(a, "sceneRoom");
        int[] v = new int[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                Object sc = r.getClass().getMethod("scene").invoke(r);
                v[0] = ((org.json.JSONArray) sc.getClass().getMethod("items").invoke(sc)).length();
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
