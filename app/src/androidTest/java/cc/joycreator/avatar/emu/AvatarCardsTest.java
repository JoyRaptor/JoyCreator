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
 * shown over the room with a made-up picture. The room must survive each step and a picture is saved: the pictures are the
 * evidence (pictures/cards/*.png in the run's artifact). Since 11-28 it also checks what the lead found in those pictures: the
 * take bar's words show whole, the head's first drawing tile shows the drawing, and Perform's chips have room.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarCardsTest {
    private static final String TAG = Emu.TAG;
    private File out;
    private AvatarRoomActivity room;
    private final StringBuilder log = new StringBuilder();
    /** What the checks along the way found wrong (all of them run; the test fails at the end if any did). */
    private final StringBuilder problems = new StringBuilder();

    @Test
    public void everyCardOpensAndIsPhotographed() throws Exception {
        out = Emu.dir("cards");
        Emu.watchMainThread();                                       // (a frozen screen says where it is stuck)
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
            scrollTo(0.3f);
            shot("setup-mouth-middle");
            scrollTo(1f);
            shot("setup-mouth-lower");
            scrollTo(0f);
            part("head", "setup-head");
            firstDrawingTileShowsTheDrawing();
            part("browL", "setup-brow");
            part("jaw", "setup-jaw");
            part("nose", "setup-nose");
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
            takeBarWordsShowWhole();
            performChipsHaveRoom();
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
            org.junit.Assert.assertEquals("the cards' checks", "", problems.toString());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The head's first drawing under "Its drawings" shows that drawing, not a blank box (the lead, 11-28: setup-head showed a white
     * box, the drawing's first piece, its upper lid): at most half of what the tile's picture draws is near white.
     */
    private void firstDrawingTileShowsTheDrawing() throws Exception {
        String[] said = {null};
        double[] white = {-1};
        Emu.ins().runOnMainSync(() -> {
            try {
                View parts = (View) field(room, "parts");
                android.widget.ImageView iv = firstTilePicture(parts, room.getString(com.fadcam.R.string.avatar_its_drawings));
                if (iv == null) {
                    said[0] = "no Its drawings row";
                    return;
                }
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (!(d instanceof android.graphics.drawable.BitmapDrawable)) {
                    said[0] = "the first tile has no picture";
                    return;
                }
                Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                int shown = 0, near = 0;
                for (int y = 0; y < b.getHeight(); y++)
                    for (int x = 0; x < b.getWidth(); x++) {
                        int c = b.getPixel(x, y);
                        if ((c >>> 24) < 128) continue;
                        shown++;
                        if (((c >> 16) & 255) > 220 && ((c >> 8) & 255) > 220 && (c & 255) > 220) near++;
                    }
                white[0] = shown == 0 ? 1 : near / (double) shown;
                said[0] = b.getWidth() + " x " + b.getHeight() + ", " + shown + " pixels shown, " + Math.round(white[0] * 100) + "% near white";
            } catch (Exception e) {
                said[0] = "(could not read the tile: " + e + ")";
            }
        });
        log.append("first drawing tile: ").append(said[0]).append('\n');
        if (white[0] < 0 || white[0] > 0.5) problems.append("the head's first drawing shows a blank box: ").append(said[0]).append('\n');
    }

    /**
     * Perform with the joystick open (the lead, 11-28): Mic, Face and the joystick's chip no longer squeeze against the stick pad
     * (none of them reaches within 4 dp of it), and Walk settings sits in their row, not alone on a row of its own.
     */
    private void performChipsHaveRoom() throws Exception {
        StringBuilder wrong = new StringBuilder();
        Emu.ins().runOnMainSync(() -> {
            try {
                Object panel = field(room, "performPanel");
                View mic = (View) field(panel, "micChip"), pad = (View) field(panel, "stickPad");
                if (pad == null) {
                    wrong.append("the stick pad is not open");
                    return;
                }
                android.graphics.Rect pr = onScreen(pad);
                pr.inset(-Math.round(4 * room.getResources().getDisplayMetrics().density), 0);
                ViewGroup chips = (ViewGroup) mic.getParent();
                for (int i = 0; i < chips.getChildCount(); i++) {
                    View ch = chips.getChildAt(i);
                    if (ch.getVisibility() == View.VISIBLE && android.graphics.Rect.intersects(onScreen(ch), pr))
                        wrong.append(" a chip (").append(ch.getContentDescription()).append(") squeezes against the stick pad;");
                }
                TextView walk = withText((View) panel, room.getString(com.fadcam.R.string.avatar_walk_settings));
                if (walk != null && walk.getParent() != chips) wrong.append(" Walk settings is on a row of its own;");
            } catch (Exception e) {
                wrong.append("(could not read Perform: ").append(e).append(')');
            }
        });
        log.append("perform chips: ").append(wrong.length() == 0 ? "room" : wrong).append('\n');
        if (wrong.length() > 0) problems.append("Perform is crowded:").append(wrong).append('\n');
    }

    private static android.graphics.Rect onScreen(View v) {
        int[] at = new int[2];
        v.getLocationOnScreen(at);
        return new android.graphics.Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
    }

    private static TextView withText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) return (TextView) v;
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                TextView t = withText(((ViewGroup) v).getChildAt(i), text);
                if (t != null) return t;
            }
        return null;
    }

    /** The picture of the first tile in the row under the label {@code label}, or null. */
    private static android.widget.ImageView firstTilePicture(View v, String label) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View k = g.getChildAt(i);
                if (k instanceof TextView && label.contentEquals(((TextView) k).getText()) && i + 1 < g.getChildCount()
                        && g.getChildAt(i + 1) instanceof android.widget.HorizontalScrollView) {
                    ViewGroup row = (ViewGroup) ((ViewGroup) g.getChildAt(i + 1)).getChildAt(0);
                    ViewGroup tile = (ViewGroup) row.getChildAt(0);
                    return (android.widget.ImageView) tile.getChildAt(0);
                }
                android.widget.ImageView f = firstTilePicture(k, label);
                if (f != null) return f;
            }
        }
        return null;
    }

    /**
     * The take bar's "No take yet" line shows whole (the lead, 11-28: "No take yet. Tap the red dot, then pe" was cut off on the
     * phone): the lines the strip draws, at its own width and paint, say every word with no ellipsis.
     */
    private void takeBarWordsShowWhole() throws Exception {
        String[] said = new String[1];
        Emu.ins().runOnMainSync(() -> {
            try {
                Object panel = field(room, "performPanel");
                View strip = (View) field(panel, "strip");
                android.graphics.Paint p = (android.graphics.Paint) field(strip, "p");
                float dp = room.getResources().getDisplayMetrics().density;
                Method lines = panel.getClass().getDeclaredMethod("lines", String.class, android.graphics.Paint.class, float.class);
                lines.setAccessible(true);
                String t = room.getString(com.fadcam.R.string.avatar_no_take);
                float width = strip.getWidth() - 20 * dp;
                said[0] = String.join(" ", (String[]) lines.invoke(null, t, p, width));
            } catch (Exception e) {
                said[0] = "(could not read the strip: " + e + ")";
            }
        });
        log.append("take bar: ").append(said[0]).append('\n');
        if (!room.getString(com.fadcam.R.string.avatar_no_take).equals(said[0])) problems.append("the take bar's words are cut off: ").append(said[0]).append('\n');
    }

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
        String nan = Emu.notANumber(room);                             // (the character vanishing for good: say where a NaN is)
        if (!nan.isEmpty()) {
            log.append("      NOT A NUMBER at ").append(name).append(": ").append(nan).append('\n');
            Log.i(Emu.TAG, "NOT A NUMBER at " + name + ": " + nan);
        }
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
