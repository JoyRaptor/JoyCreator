package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.R;
import com.fadcam.ui.faditor.avatar.face.FaceCapture;
import com.fadcam.ui.faditor.avatar.face.ShoulderStage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.face.Shrugs;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.ui.RoomEdit;

/**
 * SHRUG-1 in the REAL room: shoulders handed in where the camera's body readings arrive (the room's own FaceCapture: its
 * rest, glide and Mirror all run), Topps in Perform and Motion, real taps on the room's controls. Checked against where the
 * room's GPU stage draws his arms and head: the first sighting sets him up to shrug (one undo step), both shoulders lift both
 * arms and sink the head, one shoulder lifts the arm on the side Mirror says, the Shoulders tick box lets the shrug go, and in
 * Motion the Try slider shows a shrug without the camera and Arms lift changes it as one undo step.
 *
 * <p>The emulator has no body worth reading: the body model itself, its speed and a real shrug stay on the Note 9. Reads
 * the room's fields by name (as AvatarRoomSmokeTest does): no test hooks in the room. {@code -e fault flip} hands the body
 * in with its sides swapped, to prove the one-side checks read what the room draws.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarShoulderTest {
    private final StringBuilder log = new StringBuilder();
    private int fails;

    /** The body the camera would see now: the person's right and left shoulder lifted this much of the picture (0: rest). */
    private volatile float liftR, liftL;
    private volatile boolean feeding = true, seen = true;

    @Test
    public void yourShouldersShrugToppsInTheRoom() throws Exception {
        File out = Emu.dir("shoulders");
        boolean flip = "flip".equals(Emu.fault());
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            Emu.sleep(4000);
            AvatarRoomActivity[] ar = new AvatarRoomActivity[1];
            sc.onActivity(x -> ar[0] = x);
            AvatarRoomActivity a = ar[0];
            TextView[] tabs = (TextView[]) field(a, "stageTabs");
            Emu.tap(tabs[3]);                                            // Perform
            Emu.sleep(2000);
            assertEquals("Perform is open", 3, (int) (Integer) field(a, "stageIndex"));
            Inputs in = (Inputs) field(a, "inputs");
            JSONObject ch0 = character(a).json;
            assertTrue("Topps starts with no shrug (the room sets him up)", !Shrugs.has(ch0) && Shrugs.canRig(ch0));
            String armL = role(ch0.getJSONObject("root"), "armL").getString("id"), armR = role(ch0.getJSONObject("root"), "armR").getString("id"),
                    head = role(ch0.getJSONObject("root"), "head").getString("id");

            // still, so only the shrug moves him: breathing and the idle drift off (two steps of their own)
            Emu.ins().runOnMainSync(() -> {
                a.setSetting("breathe", false, "Breathing off");
                a.setSetting("idle", false, "Never quite still off");
            });
            // the face on, as the room's Face chip turns it on minus the camera itself; the body handed in ten times a second
            FaceCapture[] fc = new FaceCapture[1];
            Emu.ins().runOnMainSync(() -> {
                fc[0] = new FaceCapture(a);
                fc[0].setShouldersOn(true);
                setField(a, "faceCapture", fc[0]);
                in.face(true);
            });
            Thread feeder = new Thread(() -> {
                double t = 0;
                while (feeding) {
                    t += 0.1;
                    float r = flip ? liftL : liftR, l = flip ? liftR : liftL;
                    fc[0].onBody(seen ? body(r, l) : null, 0.75f, t);
                    Emu.sleep(100);
                }
            }, "body-readings");
            feeder.start();
            Emu.sleep(2500);
            View tick = find(a, a.getString(R.string.avatar_shoulders));
            check(tick != null, "Perform, with the face on: the Shoulders tick box is there");
            CharacterDoc d1 = character(a);
            String top = undoName(a);
            check(Shrugs.has(d1.json) && Shrugs.amount(d1.json, true) == 32.5 && Shrugs.amount(d1.json, false) == 16
                            && a.getString(R.string.avatar_step_shrugs_set_up).equals(top),
                    "your shoulders first seen: Topps is set up to shrug, as one undo step (arms " + Shrugs.amount(d1.json, true)
                            + " px, head " + Shrugs.amount(d1.json, false) + " px; Undo holds \"" + top + "\")");
            double yL = drawnY(a, armL), yR = drawnY(a, armR), yH = drawnY(a, head);
            shot(out, "01-rest");

            // both shoulders up: both arms lift, the head sinks a little
            liftR = 0.1f;
            liftL = 0.1f;
            Emu.sleep(2000);
            double dL = yL - drawnY(a, armL), dR = yR - drawnY(a, armR), dH = drawnY(a, head) - yH;
            shot(out, "02-both-shoulders");
            check(dL > 25 && dR > 18 && dH > 10, "a full shrug: his left arm up " + f(dL) + " px (of 32.5), his right " + f(dR) + " (of 24.5), the head down " + f(dH) + " (of 16)");
            check(sig(a, "shrug") > 0.9, "...the shrug the room draws from (" + f(sig(a, "shrug")) + ")");

            // one side, Mirror on (the default): your right shoulder lifts Topps's left arm, like a reflection
            liftR = 0.1f;
            liftL = 0;
            Emu.sleep(2000);
            dL = yL - drawnY(a, armL);
            dR = yR - drawnY(a, armR);
            shot(out, "03-right-shoulder-mirror-on");
            check(dL > 25 && dR < 5, "your right shoulder, Mirror on: Topps's left arm lifts (" + f(dL) + " px), his right stays (" + f(dR) + ")");
            tapText(a, a.getString(R.string.avatar_mirror));
            Emu.sleep(2000);
            dL = yL - drawnY(a, armL);
            dR = yR - drawnY(a, armR);
            shot(out, "04-right-shoulder-mirror-off");
            check(dR > 18 && dL < 5, "Mirror off: the same shoulder lifts his right arm, like someone facing you (" + f(dR) + " px; left " + f(dL) + ")");
            tapText(a, a.getString(R.string.avatar_mirror));
            Emu.sleep(500);

            // the Shoulders tick box off by a real tap: the shrug lets go (eases back), and on again
            liftR = 0.1f;
            liftL = 0.1f;
            Emu.sleep(1500);
            tapText(a, a.getString(R.string.avatar_shoulders));
            Emu.sleep(2000);
            dL = yL - drawnY(a, armL);
            boolean offNow = fc[0].shouldersOn();
            shot(out, "05-shoulders-off");
            check(!offNow && Math.abs(dL) < 3, "Shoulders off: the shrug eases away though you still shrug (" + f(dL) + " px)");
            check(find(a, a.getString(R.string.avatar_shoulder_sens)) == null, "...and its sensitivity slider goes with it");
            tapText(a, a.getString(R.string.avatar_shoulders));
            Emu.sleep(2000);
            dL = yL - drawnY(a, armL);
            check(fc[0].shouldersOn() && dL > 25 && find(a, a.getString(R.string.avatar_shoulder_sens)) != null,
                    "Shoulders on again: the shrug is back (" + f(dL) + " px) with its sensitivity slider");

            // out of the picture: the room says how to bring them back, once
            seen = false;
            Emu.sleep(3500);
            check((Boolean) field(a, "shoulderHinted"), "three seconds without your shoulders in the picture: the room says so");
            seen = true;
            liftR = 0;
            liftL = 0;
            Emu.sleep(1500);

            // Motion: Try, Arms lift (one undo step) and Shrugs off
            feeding = false;
            feeder.join(2000);
            Emu.tap(tabs[2]);
            Emu.sleep(2000);
            assertEquals("Motion is open", 2, (int) (Integer) field(a, "stageIndex"));
            Emu.sleep(1000);
            double mL = drawnY(a, armL);
            SeekBar trySb = seekBar(a, a.getString(R.string.avatar_shrug_try));
            assertNotNull("Motion has the Shrug section's Try", trySb);
            float[] tr = across(trySb, 0.02f, 0.95f);
            // (the finger stays at the end three seconds once it gets there; the emulator delivers a drag's moves slowly, so
            // wait for the slider to reach the end before reading the arm)
            Thread finger = new Thread(() -> Emu.drag(tr[0], tr[2], tr[1], tr[2], 400, 3000), "try-finger");
            finger.start();
            int[] prog = {0};
            long until = android.os.SystemClock.uptimeMillis() + 10000;
            while (prog[0] < 18 && android.os.SystemClock.uptimeMillis() < until) {
                Emu.sleep(100);
                Emu.ins().runOnMainSync(() -> prog[0] = trySb.getProgress());
            }
            Emu.sleep(600);
            double tryUp = mL - drawnY(a, armL);
            shot(out, "06-motion-try");
            log.append("      (Try slider at ").append(prog[0]).append(" of 20 when read)\n");
            finger.join(10000);
            Emu.sleep(1500);
            double tryRest = mL - drawnY(a, armL);
            check(tryUp > 25 && Math.abs(tryRest) < 3, "Motion, Try: a finger on it shrugs Topps without the camera (" + f(tryUp) + " px), let go and he rests (" + f(tryRest) + ")");

            SeekBar lift = seekBar(a, a.getString(R.string.avatar_arms_lift));
            assertNotNull("Motion has Arms lift", lift);
            float[] lr = across(lift, 32.5f / 60f, 20f / 60f);
            Emu.drag(lr[0], lr[2], lr[1], lr[2], 400, 300);
            Emu.sleep(1500);
            double now = Shrugs.amount(character(a).json, true);
            String step = undoName(a);
            check(Math.abs(now - 20) <= 3 && a.getString(R.string.avatar_step_arms_lift).equals(step),
                    "Arms lift dragged to about 20 px: both arms' shrug is " + f(now) + " px, one undo step (\"" + step + "\")");
            Emu.tap((View) field(a, "undoButton"));
            Emu.sleep(1500);
            check(Shrugs.amount(character(a).json, true) == 32.5, "one Undo puts Arms lift back (" + Shrugs.amount(character(a).json, true) + " px)");

            tapText(a, a.getString(R.string.avatar_shrug_on));
            Emu.sleep(1500);
            JSONObject noShrug = character(a).json;
            check(!Shrugs.has(noShrug) && noShrug.optBoolean("noShrug") && a.getString(R.string.avatar_step_no_shrugs).equals(undoName(a)),
                    "Shrugs with your shoulders untick: the Links come off and he stays without them (one step)");
            shot(out, "07-motion-no-shrugs");
            Emu.tap((View) field(a, "undoButton"));
            Emu.sleep(1500);
            check(Shrugs.has(character(a).json) && a.getString(R.string.avatar_step_shrugs_set_up).equals(undoName(a)),
                    "one Undo puts the shrugs back; the step under it is his set-up");
            Emu.tap((View) field(a, "undoButton"));
            Emu.sleep(1500);
            check(!Shrugs.has(character(a).json), "and one more Undo takes the set-up away");
            Emu.ins().runOnMainSync(() -> in.face(false));
        } finally {
            feeding = false;
            Log.i(Emu.TAG, "shoulders\n" + log);
        }
        assertTrue("checks failed:\n" + log, fails == 0);
    }

    // ── the body ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * The body the tracker hands in (33 points: x, y, visibility, 0..1 of a 4:3 picture), the person facing an unmirrored
     * front camera: their right shoulder on the picture's left.
     */
    private static float[] body(float liftR, float liftL) {
        float[] p = new float[ShoulderStage.COUNT * 3];
        for (int i = 0; i < ShoulderStage.COUNT; i++) { p[i * 3] = 0.5f; p[i * 3 + 1] = 0.5f; p[i * 3 + 2] = 0.9f; }
        p[ShoulderStage.EAR_L * 3] = 0.55f; p[ShoulderStage.EAR_L * 3 + 1] = 0.30f;
        p[ShoulderStage.EAR_R * 3] = 0.45f; p[ShoulderStage.EAR_R * 3 + 1] = 0.30f;
        p[ShoulderStage.SHOULDER_L * 3] = 0.72f; p[ShoulderStage.SHOULDER_L * 3 + 1] = 0.62f - liftL;
        p[ShoulderStage.SHOULDER_R * 3] = 0.28f; p[ShoulderStage.SHOULDER_R * 3 + 1] = 0.62f - liftR;
        return p;
    }

    // ── checks, pictures, touches ───────────────────────────────────────────────────────────────────

    private void check(boolean c, String what) {
        log.append(c ? "PASS  " : "FAIL  ").append(what).append('\n');
        if (!c) fails++;
    }

    private static String f(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private static void shot(File out, String name) throws Exception {
        Bitmap s = Emu.screen();
        if (s != null) {
            Emu.save(s, new File(out, name + ".png"));
            s.recycle();
        }
    }

    private static CharacterDoc character(AvatarRoomActivity a) throws Exception {
        return (CharacterDoc) field(a, "character");
    }

    private static String undoName(AvatarRoomActivity a) throws Exception {
        RoomEdit ed = (RoomEdit) field(a, "edit");
        String[] top = new String[1];
        Emu.ins().runOnMainSync(() -> top[0] = ed.undoName());
        return top[0];
    }

    /** A signal the room drew its last frame from (0 when absent). */
    @SuppressWarnings("unchecked")
    private static double sig(AvatarRoomActivity a, String k) throws Exception {
        Object m = field(a, "lastSignals");
        Object v = m instanceof java.util.Map ? ((java.util.Map<String, Object>) m).get(k) : null;
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    /** Where a part is drawn now (its matrix's y offset, character pixels), from the room's GPU stage. */
    private static double drawnY(AvatarRoomActivity a, String id) throws Exception {
        Object gl = field(a, "glStage");
        double[][] m = new double[1][];
        Emu.ins().runOnMainSync(() -> {
            try { m[0] = (double[]) gl.getClass().getMethod("matrix", String.class).invoke(gl, id); } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertNotNull("the stage draws " + id, m[0]);
        return m[0][5];
    }

    /** The view showing exactly this text (the first shown), or null. */
    private static View find(AvatarRoomActivity a, String txt) {
        View[] hit = new View[1];
        Emu.ins().runOnMainSync(() -> hit[0] = find(a.getWindow().getDecorView(), txt));
        return hit[0];
    }

    private static View find(View v, String txt) {
        if (v instanceof TextView && txt.contentEquals(((TextView) v).getText()) && v.isShown()) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            View r = find(((ViewGroup) v).getChildAt(i), txt);
            if (r != null) return r;
        }
        return null;
    }

    /** Scrolls the view into sight, then a real tap on it (a row's label: the tap reaches the row). */
    private static void tapText(AvatarRoomActivity a, String txt) {
        View v = find(a, txt);
        assertNotNull("nothing on screen says \"" + txt + "\"", v);
        Emu.ins().runOnMainSync(() -> v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), true));
        Emu.sleep(300);
        Emu.tap(v);
    }

    /** A Motion slider by its label (label, value row, then the bar), scrolled into sight; null when not shown. */
    private static SeekBar seekBar(AvatarRoomActivity a, String label) {
        View l = find(a, label);
        if (l == null || !(l.getParent() instanceof View) || !(((View) l.getParent()).getParent() instanceof ViewGroup)) return null;
        ViewGroup box = (ViewGroup) ((View) l.getParent()).getParent();
        SeekBar[] sb = new SeekBar[1];
        Emu.ins().runOnMainSync(() -> {
            for (int i = 0; i < box.getChildCount(); i++) if (box.getChildAt(i) instanceof SeekBar) sb[0] = (SeekBar) box.getChildAt(i);
            if (sb[0] != null) box.requestRectangleOnScreen(new Rect(0, 0, box.getWidth(), box.getHeight()), true);
        });
        Emu.sleep(400);
        return sb[0];
    }

    /** Screen points on a slider's track: {x at fraction a, x at fraction b, y}. */
    private static float[] across(SeekBar sb, float from, float to) {
        int[] at = new int[2];
        int[] wh = new int[4];
        Emu.ins().runOnMainSync(() -> {
            sb.getLocationOnScreen(at);
            wh[0] = sb.getWidth();
            wh[1] = sb.getHeight();
            wh[2] = sb.getPaddingLeft();
            wh[3] = sb.getPaddingRight();
        });
        float w = wh[0] - wh[2] - wh[3];
        return new float[]{at[0] + wh[2] + w * from, at[0] + wh[2] + w * to, at[1] + wh[1] / 2f};
    }

    private static JSONObject role(JSONObject n, String role) {
        if (role.equals(n.optString("role"))) return n;
        JSONArray k = n.optJSONArray("kids");
        for (int i = 0; k != null && i < k.length(); i++) {
            JSONObject r = role(k.optJSONObject(i), role);
            if (r != null) return r;
        }
        return null;
    }

    private static Object field(Object o, String name) throws Exception {
        Field fl = findField(o.getClass(), name);
        Object[] v = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try { v[0] = fl.get(o); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return v[0];
    }

    private static void setField(Object o, String name, Object val) {
        try {
            findField(o.getClass(), name).set(o, val);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(c.getSimpleName() + " has no field '" + name + "' any more; update AvatarShoulderTest", e);
        }
    }
}
