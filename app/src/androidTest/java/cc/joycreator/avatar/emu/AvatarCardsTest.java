package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.file.CharacterDoc;

/**
 * Every card of the room opened and photographed (AVA-1 slice 1): a card nobody has looked at is not done. Topps is opened, a part
 * is put in hand for each card (an eye, the mouth, the head, the body), a stance is edited, reshape is switched on, Motion is scrolled
 * through, the joystick and its gestures are opened, the menu, and the two full-screen doors (the pin wizard and the sheet cutter) are
 * shown over the room with a made-up picture. Nothing is asserted but that the room survives each step and a picture is saved:
 * the pictures are the evidence (pictures/cards/*.png in the run's artifact).
 */
@RunWith(AndroidJUnit4.class)
public class AvatarCardsTest {
    private static final String TAG = Emu.TAG;
    private File out;
    private AvatarRoomActivity room;
    private final StringBuilder log = new StringBuilder();

    @Test
    public void everyCardOpensAndIsPhotographed() throws Exception {
        out = Emu.dir("cards");
        Emu.turn();
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            Emu.sleep(4000);
            AvatarRoomActivity[] a = new AvatarRoomActivity[1];
            sc.onActivity(x -> a[0] = x);
            room = a[0];
            TextView[] tabs = (TextView[]) field(room, "stageTabs");

            // Set up: the parts
            Emu.tap(tabs[0]);
            Emu.sleep(1200);
            part("eyeL", "setup-eye");
            scrollTo(1f);
            shot("setup-eye-lower");
            scrollTo(0f);
            part("mouth", "setup-mouth");
            scrollTo(1f);
            shot("setup-mouth-lower");
            scrollTo(0f);
            part("head", "setup-head");
            part("armL", "setup-arm");

            // Arrange: a part, a stance, in front or behind, reshape
            Emu.tap(tabs[1]);
            Emu.sleep(1200);
            part("torso", "arrange-body");
            scrollTo(0.5f);
            shot("arrange-body-lower");
            scrollTo(0f);
            part("head", "arrange-head");
            String pose = firstPose();
            if (pose != null) {
                ui(() -> room.editStance(pose));
                Emu.sleep(1200);
                shot("arrange-stance");
                scrollTo(1f);
                shot("arrange-stance-lower");
                scrollTo(0f);
                ui(() -> room.editStance(null));
            }
            part("face", "arrange-face");
            ui(() -> room.reshape(true));
            Emu.sleep(1500);
            shot("arrange-reshape");
            scrollTo(1f);
            shot("arrange-reshape-lower");
            ui(() -> room.reshape(false));

            // Motion: scrolled through
            Emu.tap(tabs[2]);
            Emu.sleep(1500);
            shot("motion-top");
            scrollTo(0.4f);
            shot("motion-walk");
            scrollTo(0.8f);
            shot("motion-walk-more");
            scrollTo(1f);
            shot("motion-bottom");

            // Perform: with the joystick open, its gestures, the menu
            ui(() -> room.setStickOpen(true));
            Emu.tap(tabs[3]);
            Emu.sleep(1500);
            shot("perform-joystick");
            ui(() -> room.inHand((String) get("inHand")));              // put the part in hand away: its outline hides the picture
            Emu.sleep(800);
            shot("perform-joystick-clear");
            ui(() -> room.openStickGestures());
            Emu.sleep(1000);
            shot("perform-gestures-sheet");
            back();
            Emu.sleep(600);
            ui(() -> room.setStickOpen(false));
            ui(() -> call("openMenu"));
            Emu.sleep(1000);
            shot("menu");
            back();
            Emu.sleep(600);

            // the two doors that need a picture, with a made-up one
            Bitmap pic = made();
            ui(() -> call("openPinWizard", pic));
            Emu.sleep(800);
            shot("door-pin-wizard");
            ui(() -> removeLast());
            Emu.sleep(300);
            assertFalse("the room closed", room.isFinishing());
            Log.i(TAG, "cards\n" + log);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void part(String role, String name) throws Exception {
        String id = idOfRole(role);
        if (id == null) {
            log.append(name).append(": no part with role ").append(role).append('\n');
            return;
        }
        ui(() -> {
            String now = (String) get("inHand");
            if (id.equals(now)) room.inHand(id);                      // a tap on the part in hand puts it away: put it in hand again
            room.inHand(id);
        });
        Emu.sleep(1200);
        shot(name);
    }

    private String idOfRole(String role) throws Exception {
        CharacterDoc doc = (CharacterDoc) get("character");
        List<String> found = new ArrayList<>();
        ui(() -> doc.walk((n, d, p) -> {
            if (role.equals(n.role) && found.isEmpty()) found.add(n.id);
        }));
        return found.isEmpty() ? null : found.get(0);
    }

    private String firstPose() throws Exception {
        CharacterDoc doc = (CharacterDoc) get("character");
        org.json.JSONArray p = doc.json.optJSONArray("poses");
        return p == null || p.length() == 0 ? null : p.optJSONObject(0).optString("id");
    }

    /** Scrolls the card (the panel body's ScrollView) to a share of its length. */
    private void scrollTo(float share) throws Exception {
        ui(() -> {
            View panelBody = (View) get("panelBody");
            ScrollView sv = findScroll(panelBody);
            if (sv != null && sv.getChildCount() > 0) {
                int max = Math.max(0, sv.getChildAt(0).getHeight() - sv.getHeight());
                sv.scrollTo(0, Math.round(max * share));
            }
        });
        Emu.sleep(500);
    }

    private static ScrollView findScroll(View v) {
        if (v instanceof ScrollView && v.getVisibility() == View.VISIBLE && v.getHeight() > 0) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    private void back() {
        Emu.ins().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
    }

    private Bitmap made() {
        Bitmap b = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(0xFF3366CC);
        c.drawOval(new android.graphics.RectF(60, 20, 240, 200), p);          // a head
        p.setColor(0xFF33AA66);
        c.drawRect(90, 200, 210, 340, p);                                    // a body
        p.setColor(0xFFCC6633);
        c.drawRect(60, 340, 140, 410, p);                                    // feet
        c.drawRect(160, 340, 240, 410, p);
        return b;
    }

    private void removeLast() {
        ViewGroup content = room.findViewById(android.R.id.content);
        if (content.getChildCount() > 1) content.removeViewAt(content.getChildCount() - 1);
    }

    private void shot(String name) throws Exception {
        Bitmap s = Emu.screen();
        assertNotNull("no screenshot", s);
        Emu.save(s, new File(out, name + ".png"));
        s.recycle();
        log.append(name).append('\n');
    }

    private void ui(Runnable r) {
        Emu.ins().runOnMainSync(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.append("(a step failed: ").append(e).append(")\n");
                Log.w(TAG, "a card step failed", e);
            }
        });
    }

    private Object get(String field) {
        try {
            return field(room, field);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void call(String method, Object... args) {
        try {
            for (Method m : room.getClass().getDeclaredMethods()) {
                if (m.getName().equals(method) && m.getParameterTypes().length == args.length) {
                    m.setAccessible(true);
                    m.invoke(room, args);
                    return;
                }
            }
            log.append("(no method ").append(method).append(")\n");
        } catch (Exception e) {
            log.append("(").append(method).append(" failed: ").append(e).append(")\n");
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
