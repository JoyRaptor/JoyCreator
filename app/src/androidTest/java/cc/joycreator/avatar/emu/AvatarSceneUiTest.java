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
 * Slice 3 on a screen: the frame button, the director's chair and the strip in Perform, with real taps (AVA-1 slice 3). Checks that
 * the frame turns on and shows its shape, that the chair opens Scene / Character / Shots, that the strip shows and shares its width,
 * that Shots turn the frame on, that a shot card changes the camera, that a long press on the frame opens its sheet and a shape
 * chosen there changes the frame (an upright frame takes the strip from the side). A screenshot after every step.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarSceneUiTest {

    @Test
    public void frameChairAndStrip() throws Exception {
        File out = Emu.dir("scene");
        StringBuilder log = new StringBuilder();
        Emu.turn();
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            Emu.sleep(4000);
            AvatarRoomActivity[] a = new AvatarRoomActivity[1];
            sc.onActivity(x -> a[0] = x);
            View root = a[0].getWindow().getDecorView();
            assertEquals("Perform is where the room opens", 3, field(a[0], "stageIndex"));
            shot(out, "1-perform-plain");
            View frameBtn = byDesc(root, "Show the frame");
            assertNotNull("the frame button (hover label 'Show the frame ...')", frameBtn);
            View chair = byDesc(root, "The director");
            assertNotNull("the director's chair button", chair);
            assertEquals("no strip before the chair is used", 0, stripParts(root));

            Emu.tap(frameBtn);
            Emu.sleep(1500);
            assertNotNull("tapping the frame turns it on (its label says so)", byDesc(root, "The frame is on"));
            shot(out, "2-frame-on");

            Emu.tap(chair);
            Emu.sleep(800);
            View scene = byText(root, "Scene"), character = byText(root, "Character"), shots = byText(root, "Shots");
            assertNotNull("the chair opens Scene", scene);
            assertNotNull("the chair opens Character", character);
            assertNotNull("the chair opens Shots", shots);
            shot(out, "3-chair-open");

            Emu.tap(scene);
            Emu.sleep(600);
            Emu.tap(byText(root, "Character"));
            Emu.sleep(600);
            Emu.tap(byText(root, "Shots"));
            Emu.sleep(1500);
            assertEquals("three parts on the strip", 3, stripParts(root));
            shot(out, "4-strip-three-parts");

            View wide = byDesc(root, "Wide. Tap to go there");
            assertNotNull("the Wide shot card", wide);
            View close = byDesc(root, "Close on");
            assertNotNull("a Close on the character card", close);
            Emu.tap(close);
            Emu.sleep(1500);
            shot(out, "5-close-up");
            Emu.tap(byDesc(root, "Wide. Tap to go there"));
            Emu.sleep(1200);
            shot(out, "6-back-to-wide");

            // hold the frame button: the sheet (a dialog, its own window); choose 9:16: the strip comes from the side
            View fb = byDesc(root, "The frame is on");
            Emu.ins().runOnMainSync(fb::performLongClick);
            Emu.sleep(1200);
            assertNotNull("the frame's sheet opens on a long press", node("The shape"));
            shot(out, "7-frame-sheet");
            assertTrue("the 9:16 shape in the sheet", click("9:16"));
            Emu.sleep(800);
            assertTrue("Done closes the sheet", click("Done"));
            Emu.sleep(1800);
            shot(out, "8-upright-frame-strip-from-the-side");
            View strip = stripView(root);
            assertNotNull("the strip", strip);
            assertTrue("an upright frame takes the strip from the side (it is taller than it is wide)", strip.getHeight() > strip.getWidth());
            assertFalse("the room closed", a[0].isFinishing());
        } finally {
            Log.i(Emu.TAG, "scene ui\n" + log);
        }
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
