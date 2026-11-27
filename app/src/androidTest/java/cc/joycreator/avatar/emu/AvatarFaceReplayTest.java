package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.R;
import com.fadcam.ui.faditor.avatar.TrackingFrame;
import com.fadcam.ui.faditor.avatar.TrackingSource;
import com.fadcam.ui.faditor.avatar.face.FaceCapture;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.face.DirectionPrompts;
import cc.joycreator.avatar.face.FaceDrive;
import cc.joycreator.avatar.face.FacePins;
import cc.joycreator.avatar.face.FaceWalk;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.ui.StageView;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.ui.RoomEdit;

/**
 * The owner's own face take (tasks/avatar/golden/face-take-20261127.track.json: numbers only, his raw face and 58 face points
 * a frame) played into the REAL room on a screen, where the camera would hand it in: a tracking source on the room's own
 * FaceCapture pipeline (its neutral, smoothing and Mirror setting all run). Topps in Perform, real taps on the room's chips:
 * the Face chip's cycle, Walk with your face, Mirror, the Puff button. A screenshot at each of his moves for the run's artifact.
 * The emulator has no camera worth testing: the live tracker, the mask pass and speed stay on the Note 9.
 *
 * <p>Reads the room's fields by name (as AvatarRoomSmokeTest does): no test hooks in the room. {@code -e fault flip} plays his
 * head turns the other way round, to prove the turn checks read what the room draws from.</p>
 */
@RunWith(AndroidJUnit4.class)
public class AvatarFaceReplayTest {
    private static final String TRACK = "avatar-face/face-take-20261127.track.json";
    private final StringBuilder log = new StringBuilder();

    @Test
    public void hisFaceTakeDrivesToppsInTheRoom() throws Exception {
        File out = Emu.dir("face");
        // (played slower than he moved: the emulator draws a few frames a second, and every move must reach the stage)
        Replay replay = new Replay(load(), "flip".equals(Emu.fault()), Double.parseDouble(Emu.arg("faceSpeed", "0.25")));
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

            // his face, where the camera would hand it to the room
            FaceCapture[] fc = new FaceCapture[1];
            Emu.ins().runOnMainSync(() -> {
                fc[0] = new FaceCapture(a);
                setField(a, "faceCapture", fc[0]);
                fc[0].bus().start(replay, 20261127L);
                in.face(true);
            });
            Emu.sleep(600);

            // the Face chip: always, then only while talking, then off
            Object panel = field(a, "performPanel");
            TextView faceChip = (TextView) field(panel, "faceChip");
            assertEquals("the Face chip says it drives always", a.getString(R.string.avatar_face_always), text(faceChip));
            Emu.tap(faceChip);
            Emu.sleep(500);
            assertEquals("second tap: only while talking", a.getString(R.string.avatar_face_talk), text(faceChip));
            assertEquals(FaceDrive.Mode.TALK, in.faceDrive().mode());
            Emu.tap(faceChip);
            Emu.sleep(500);
            assertEquals("third tap: off", a.getString(R.string.avatar_face), text(faceChip));
            assertFalse("the face lets go", in.faceOn());
            Emu.ins().runOnMainSync(() -> in.face(true));                 // (on again: the camera's own start needs a camera)
            Emu.sleep(400);
            assertEquals(FaceDrive.Mode.ALWAYS, in.faceDrive().mode());

            // Walk with your face: its chip, then both tick boxes, by real taps
            tapText(a, a.getString(R.string.avatar_face_walk));
            Emu.sleep(500);
            tapText(a, a.getString(R.string.avatar_face_walk_lr));
            Emu.sleep(300);
            tapText(a, a.getString(R.string.avatar_face_walk_depth));
            Emu.sleep(300);
            FaceWalk fw = (FaceWalk) field(a, "faceWalk");
            assertTrue("Left and right ticked", fw.leftRight());
            assertTrue("Closer and further ticked", fw.closer());
            shot(out, "00-perform-face-on");

            // his take, 0 to 31 s, Mirror on (the default): what the room draws from, and a picture at each move
            assertTrue("the Mirror chip is on by default", (Boolean) a.getClass().getMethod("faceMirror").invoke(a));
            String[][] at = {{"0.9", "01-tilt-right"}, {"2.6", "02-turn-right"}, {"4.3", "03-right-eye"}, {"6.2", "04-left-eye"},
                    {"7.4", "05-tilt-left"}, {"10.2", "06-turn-left"}, {"12.1", "07-lean-left"}, {"13.2", "08-lean-right"},
                    {"13.8", "09-brows-up"}, {"15.0", "10-nod-down"}, {"15.8", "11-mouth-open"}, {"22.1", "12-smile"}, {"29.7", "13-forward"}};
            Trace tr = play(a, replay, 0, 31, at, out);
            check(tr.max("turnX", 2.0, 3.6) > 0.6, "he turns right: Topps turns to the screen's right like his reflection (" + f(tr.max("turnX", 2.0, 3.6)) + ")");
            check(tr.stray(2.0, 3.6) < 15, "...and does not walk (" + f(tr.stray(2.0, 3.6)) + ")");
            check(tr.min("turnX", 9.6, 11.0) < -0.6, "he turns left: Topps turns to the screen's left (" + f(tr.min("turnX", 9.6, 11.0)) + ")");
            check(tr.stray(9.6, 11.0) < 15, "...and does not walk (" + f(tr.stray(9.6, 11.0)) + ")");
            check(tr.max("blinkL", 3.9, 5.0) > 0.8 && tr.max("blinkR", 5.9, 7.0) > 0.8, "his right eye, then his left: Topps's eye on the same side of the screen");
            check(tr.walked(11.5, 12.6) < -30 && tr.walked(12.6, 13.9) > 30, "he leans left, then right: Topps walks that way (" + f(tr.walked(11.5, 12.6)) + ", " + f(tr.walked(12.6, 13.9)) + ")");
            check(tr.max("turnY", 14.8, 15.8) > 0.4, "he nods down: Topps looks down (" + f(tr.max("turnY", 14.8, 15.8)) + ")");
            check(Math.min(tr.max("browL", 13.3, 14.4), tr.max("browR", 13.3, 14.4)) > 0.6, "he raises his brows: Topps's rise");
            check(tr.max("mouth", 15.4, 16.3) > 0.7, "he opens his mouth: Topps's opens");
            check(tr.max("tz", 29.0, 31.0) > 40, "he comes close: Topps walks toward the camera (" + f(tr.max("tz", 29.0, 31.0)) + ")");

            // Mirror off: the same turn the other way round
            TextView mirror = (TextView) field(panel, "mirrorChip");
            Emu.tap(mirror);
            Emu.sleep(400);
            assertFalse("Mirror off", (Boolean) a.getClass().getMethod("faceMirror").invoke(a));
            assertTrue("the engine moves like someone facing you", fc[0].tuning().mirror);
            Trace off = play(a, replay, 1.8, 3.6, new String[][]{{"2.6", "14-turn-right-mirror-off"}}, out);
            check(off.min("turnX", 2.0, 3.6) < -0.6, "Mirror off: his right turn turns Topps to the screen's left (" + f(off.min("turnX", 2.0, 3.6)) + ")");
            Emu.tap(mirror);
            Emu.sleep(400);
            assertTrue("Mirror on again", (Boolean) a.getClass().getMethod("faceMirror").invoke(a));

            // Check directions with his own moves, in its order (turn right, look down, tilt right, lean right, right eye): his
            // camera is not mirrored, so every direction must stay as it was
            tapText(a, a.getString(R.string.avatar_face_dirs));
            Emu.sleep(500);
            DirectionPrompts dp = (DirectionPrompts) field(a, "dirPrompts");
            assertNotNull("Check directions is running", dp);
            shot(out, "16-check-directions");
            double[][] moves = {{1.9, 3.1}, {14.5, 15.3}, {0.5, 1.5}, {12.5, 13.2}, {3.8, 4.7}};
            String[] moveNames = {"turn right", "look down", "tilt right", "lean right", "right eye"};
            StringBuilder dirs = new StringBuilder();
            long t0 = SystemClock.uptimeMillis();
            for (int i = 0; i < moves.length; i++) {
                long w0 = SystemClock.uptimeMillis();
                while (dp.running() && dp.prompt() < i && SystemClock.uptimeMillis() - w0 < 12000) Emu.sleep(50);   // (its prompt showing)
                if (!dp.running()) break;
                int before = dp.answered();
                play(a, replay, moves[i][0], moves[i][1], new String[0][], out);
                long w1 = SystemClock.uptimeMillis();
                while (dp.running() && dp.prompt() == i && dp.answered() == before && SystemClock.uptimeMillis() - w1 < 2500) Emu.sleep(50);
                dirs.append(moveNames[i]).append(dp.answered() > before ? " read" : " NOT read").append(i < moves.length - 1 ? ", " : "");
            }
            long took = SystemClock.uptimeMillis() - t0;
            Emu.sleep(600);
            check(field(a, "dirPrompts") == null && dp.answered() == 5, "Check directions with his own five moves: all read as he made them (" + dp.answered() + " of 5: " + dirs + "; " + took / 1000 + " s)");
            Trace turnAfter = play(a, replay, 1.8, 3.6, new String[][]{{"2.6", "17-turn-right-after-directions"}}, out);
            check(turnAfter.max("turnX", 2.0, 3.6) > 0.6, "...and afterwards his right turn still turns Topps to the screen's right (" + f(turnAfter.max("turnX", 2.0, 3.6)) + ")");
            Trace leanAfter = play(a, replay, 11.5, 13.9, new String[0][], out);
            check(leanAfter.walked(11.5, 12.6) < -30 && leanAfter.walked(12.6, 13.9) > 30, "...and his leans still walk him the right way (" + f(leanAfter.walked(11.5, 12.6)) + ", " + f(leanAfter.walked(12.6, 13.9)) + ")");

            // the Puff button: not there while no part follows the cheek puff; a Link on the head makes it appear and work
            assertNull("no Puff button where nothing follows the cheek puff", find(a, a.getString(R.string.avatar_puff)));
            RoomEdit edit = (RoomEdit) field(a, "edit");
            boolean[] ok = new boolean[1];
            Emu.ins().runOnMainSync(() -> ok[0] = edit.apply("A test link: the head follows the cheek puff", doc -> {
                JSONObject head = role(doc.json.getJSONObject("root"), "head");
                head.put("links", new JSONArray().put(new JSONObject().put("sig", "face.cheekPuff").put("prop", "y").put("amt", 40)));
            }));
            assertTrue("the test link went in", ok[0]);
            Emu.tap(tabs[2]);                                            // Motion, then Perform again: the panel is laid out anew
            Emu.sleep(1200);
            Emu.tap(tabs[3]);
            Emu.sleep(1500);
            View puff = find(a, a.getString(R.string.avatar_puff));
            assertNotNull("the Puff button is there once a part follows the cheek puff", puff);
            Bitmap before = Emu.screen();
            down(puff);
            Emu.sleep(900);
            double held = sig(a, "face.cheekPuff");
            boolean owned = in.owners(System.currentTimeMillis()).contains("puff");
            Bitmap during = Emu.screen();
            Emu.save(during, new File(out, "15-puff-held.png"));
            up(puff);
            Emu.sleep(1200);
            double after = sig(a, "face.cheekPuff");
            double moved = changed(before, during, (View) field(a, "glStage"));
            check(held > 0.9 && owned, "held, Puff puffs: the cheek puff lane " + f(held) + ", the puff's own owner");
            check(moved > 0.002, "...and the part that follows it moves on the stage (" + f(moved * 100) + "% of the stage changed)");
            check(after < 0.1, "let go, it settles (" + f(after) + ")");
            // face pins in Set up, by real taps: the head in hand, Face pins, his right mouth corner, a tap on Topps's mouth
            Emu.tap(tabs[0]);
            Emu.sleep(1500);
            CharacterDoc doc0 = (CharacterDoc) field(a, "character");
            CharacterDoc.Node head = null;
            for (CharacterDoc.Node k : nodes(doc0)) if ("head".equals(k.role)) { head = k; break; }
            assertNotNull("Topps has a head", head);
            final String headId = head.id;
            Emu.ins().runOnMainSync(() -> a.inHand(headId));
            Emu.sleep(1000);
            tapText(a, a.getString(R.string.avatar_facepins));
            Emu.sleep(600);
            tapText(a, FacePins.nameOf(61));
            Emu.sleep(400);
            assertEquals("his right mouth corner picked", 61, a.pendingPoint());
            StageView sv = (StageView) field(a, "stage");
            double[] hb = sv.renderer().restBounds(head);
            float cx = (float) ((hb[0] + hb[2]) / 2), cy = (float) (hb[1] + (hb[3] - hb[1]) * 0.62);     // (the lower face: the mouth)
            int[] sat = new int[2];
            float[] sc = new float[3];
            Emu.ins().runOnMainSync(() -> { sv.getLocationOnScreen(sat); sc[0] = sv.viewScale(); sc[1] = sv.viewX(); sc[2] = sv.viewY(); });
            tapAt(sat[0] + sc[1] + cx * sc[0], sat[1] + sc[2] + cy * sc[0]);
            Emu.sleep(1500);
            JSONArray fpl = FacePins.list(((CharacterDoc) field(a, "character")).json);
            shot(out, "18-face-pin-placed");
            check(fpl.length() == 1 && fpl.getJSONObject(0).getInt("point") == 61, "a face pin made by real taps: his right mouth corner, on Topps's head (" + fpl.length() + " pins)");
            check(a.pendingPoint() < 0, "...and the point is used (a new tap needs a new point)");
            Emu.tap(tabs[3]);                                            // Perform: his smile pulls the pin's point
            Emu.sleep(1500);
            replay.play(21.6, 22.4);
            double pull = 0;
            long until2 = SystemClock.uptimeMillis() + 20000;
            while ((replay.playing || replay.now < 22.3) && SystemClock.uptimeMillis() < until2) {
                pull = Math.max(pull, Math.hypot(sig(a, "pt.61.x"), sig(a, "pt.61.y")));
                Emu.sleep(80);
            }
            check(pull > 0.02, "his smile moves the pinned point: the camera watches it (" + f(pull) + ")");
            Emu.tap(tabs[0]);
            Emu.sleep(1200);
            Emu.tap((View) field(a, "undoButton"));
            Emu.sleep(1200);
            check(FacePins.list(((CharacterDoc) field(a, "character")).json).length() == 0, "one Undo takes the face pin away (one step)");
            Emu.ins().runOnMainSync(() -> fc[0].bus().stop());
            assertFalse("the room closed", a.isFinishing());
        } finally {
            Log.i(Emu.TAG, "face replay\n" + log);
        }
        assertTrue("checks failed:\n" + log, fails == 0);
    }

    // ── his take ─────────────────────────────────────────────────────────────────────────────────────

    static final class Frame {
        double t;
        Map<String, Float> raw;
        float[] xyz;
    }

    private static List<Frame> load() throws Exception {
        InputStream is = Emu.ins().getContext().getAssets().open(TRACK);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        for (int n; (n = is.read(buf)) > 0; ) bo.write(buf, 0, n);
        is.close();
        JSONObject tr = new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        JSONArray keys = tr.getJSONArray("rawKeys"), scale = tr.getJSONArray("rawScale"), keep = tr.getJSONArray("keep"), frames = tr.getJSONArray("frames");
        double fps = tr.getDouble("fps"), ps = tr.getDouble("pointScale");
        List<Frame> out = new ArrayList<>();
        for (int k = 0; k < frames.length(); k++) {
            JSONArray fa = frames.getJSONArray(k);
            Frame f = new Frame();
            f.t = fa.getInt(0) / fps;
            if (!fa.isNull(1)) {
                JSONArray r = fa.getJSONArray(1);
                f.raw = new HashMap<>();
                for (int i = 0; i < keys.length(); i++) f.raw.put(keys.getString(i), (float) (r.getDouble(i) / scale.getDouble(i)));
            }
            if (!fa.isNull(2)) {
                JSONArray p = fa.getJSONArray(2);
                f.xyz = new float[478 * 3];
                for (int i = 0; i < keep.length(); i++) for (int c = 0; c < 3; c++) f.xyz[keep.getInt(i) * 3 + c] = (float) (p.getDouble(i * 3 + c) / ps);
            }
            out.add(f);
        }
        return out;
    }

    /** His take as a camera hands it in: one frame each 1/15 s of a stretch asked for, on its own thread, the clock running on. */
    static final class Replay implements TrackingSource {
        final List<Frame> frames;
        final boolean flip;
        final double speed;
        volatile double from = -1, to = -1, now = -1;
        volatile boolean playing;
        private volatile boolean run;
        private Thread th;
        private final long t0 = System.nanoTime();

        Replay(List<Frame> frames, boolean flip, double speed) {
            this.frames = frames;
            this.flip = flip;
            this.speed = speed;
        }

        void play(double a, double b) {
            from = a;
            to = b;
            playing = true;
        }

        @Override
        public void start(@NonNull FrameListener l) {
            run = true;
            th = new Thread(() -> {
                while (run) {
                    if (!playing) { SystemClock.sleep(20); continue; }
                    for (Frame f : frames) {
                        if (!run) return;
                        if (f.t < from || f.t > to) continue;
                        long due = System.nanoTime() + (long) (66_666_667L / speed);
                        now = f.t;
                        Map<String, Float> raw = f.raw == null ? new HashMap<>() : new HashMap<>(f.raw);
                        if (flip && f.raw != null) {
                            raw.put("headYawDeg", -raw.get("headYawDeg"));
                            raw.put("headRollDeg", -raw.get("headRollDeg"));
                        }
                        double ts = (System.nanoTime() - t0) / 1e9;
                        l.onFrame(new TrackingFrame(ts, raw, Float.NaN, TrackingFrame.NO_VISEME,
                                f.raw == null ? TrackingFrame.FACE_LOST : TrackingFrame.FACE_FOUND, System.nanoTime(), f.xyz));
                        long wait = (due - System.nanoTime()) / 1_000_000L;
                        if (wait > 0) SystemClock.sleep(wait);
                    }
                    playing = false;
                }
            }, "face-take-replay");
            th.start();
        }

        @Override
        public void stop() {
            run = false;
        }
    }

    // ── what the room draws from, while his take plays ───────────────────────────────────────────────

    static final class Trace {
        final List<double[]> rows = new ArrayList<>();     // {take time, turnX, turnY, roll, blinkL, blinkR, browL, browR, mouth, tx, tz}
        static final List<String> COLS = java.util.Arrays.asList("t", "turnX", "turnY", "roll", "blinkL", "blinkR", "browL", "browR", "mouth", "tx", "tz");

        double max(String k, double a, double b) { double m = -1e9; int c = COLS.indexOf(k); for (double[] r : rows) if (r[0] >= a && r[0] <= b) m = Math.max(m, r[c]); return m; }

        double min(String k, double a, double b) { double m = 1e9; int c = COLS.indexOf(k); for (double[] r : rows) if (r[0] >= a && r[0] <= b) m = Math.min(m, r[c]); return m; }

        double before(double a) { double v = Double.NaN; for (double[] r : rows) if (r[0] < a) v = r[9]; return Double.isNaN(v) && !rows.isEmpty() ? rows.get(0)[9] : v; }

        double walked(double a, double b) { double last = Double.NaN; for (double[] r : rows) if (r[0] >= a && r[0] <= b) last = r[9]; return last - before(a); }

        double stray(double a, double b) { double x0 = before(a), m = 0; for (double[] r : rows) if (r[0] >= a && r[0] <= b) m = Math.max(m, Math.abs(r[9] - x0)); return m; }
    }

    private Trace play(AvatarRoomActivity a, Replay rp, double from, double to, String[][] shots, File out) throws Exception {
        Trace tr = new Trace();
        int next = 0;
        rp.play(from, to);
        long until = SystemClock.uptimeMillis() + (long) ((to - from) * 1000 / rp.speed) + 20000;
        Emu.sleep(100);
        Field fSig = findField(a.getClass(), "lastSignals"), fWalker = findField(a.getClass(), "walker");
        while ((rp.playing || rp.now < to - 0.1) && SystemClock.uptimeMillis() < until) {
            double t = rp.now;
            double[] r = new double[11];
            Emu.ins().runOnMainSync(() -> {                     // (one trip to the room a sample: it draws slowly here)
                try {
                    @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) fSig.get(a);
                    Object w = fWalker.get(a);
                    String[] ks = {"turnX", "turnY", "roll", "blinkL", "blinkR", "browL", "browR", "mouth"};
                    r[0] = t;
                    for (int i = 0; i < ks.length; i++) { Object v = m == null ? null : m.get(ks[i]); r[i + 1] = v instanceof Number ? ((Number) v).doubleValue() : 0; }
                    r[9] = w == null ? 0 : w.getClass().getField("tx").getDouble(w);
                    r[10] = tz(w);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            tr.rows.add(r);
            if (next < shots.length && t >= Double.parseDouble(shots[next][0])) shot(out, shots[next++][1]);
            Emu.sleep(50);
            if (!rp.playing && rp.now >= to - 0.1) break;
        }
        log.append(String.format(Locale.ROOT, "played %.1f-%.1f s at %.2fx: %d samples\n", from, to, rp.speed, tr.rows.size()));
        return tr;
    }

    private static double tz(Object w) {
        try {
            Object v = w == null ? null : w.getClass().getField("tz").get(w);
            return v == null ? 0 : ((Number) v).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static double sig(AvatarRoomActivity a, String k) throws Exception {
        Map<String, Object> m = (Map<String, Object>) field(a, "lastSignals");
        Object v = m == null ? null : m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    // ── checks, pictures, touches ───────────────────────────────────────────────────────────────────

    private int fails;

    private void check(boolean c, String what) {
        log.append(c ? "PASS  " : "FAIL  ").append(what).append('\n');
        if (!c) fails++;
    }

    private static String f(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    private void shot(File out, String name) throws Exception {
        Bitmap s = Emu.screen();
        if (s != null) {
            Emu.save(s, new File(out, name + ".png"));
            s.recycle();
        }
    }

    /** The share of the stage's pixels that differ between two screenshots. */
    private static double changed(Bitmap a, Bitmap b, View stage) {
        int[] at = new int[2];
        Emu.ins().runOnMainSync(() -> stage.getLocationOnScreen(at));
        int x0 = Math.max(0, at[0]), y0 = Math.max(0, at[1]), x1 = Math.min(a.getWidth(), at[0] + stage.getWidth()), y1 = Math.min(a.getHeight(), at[1] + stage.getHeight());
        int n = 0, d = 0;
        for (int y = y0; y < y1; y += 2) for (int x = x0; x < x1; x += 2) {
            int p = a.getPixel(x, y), q = b.getPixel(x, y);
            n++;
            if (Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)) + Math.abs((p & 255) - (q & 255)) > 60) d++;
        }
        return n == 0 ? 0 : d / (double) n;
    }

    private static String text(TextView t) {
        String[] s = new String[1];
        Emu.ins().runOnMainSync(() -> s[0] = t.getText().toString());
        return s[0];
    }

    /** The view showing exactly this text (the first found), or null. */
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

    /** A real tap at a screen point. */
    private static void tapAt(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0), u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        Emu.ins().getUiAutomation().injectInputEvent(d, true);
        Emu.ins().getUiAutomation().injectInputEvent(u, true);
        d.recycle();
        u.recycle();
    }

    private static List<CharacterDoc.Node> nodes(CharacterDoc d) {
        List<CharacterDoc.Node> out = new ArrayList<>();
        d.walk((n, depth, parent) -> out.add(n));
        return out;
    }

    private static void down(View v) { touch(v, MotionEvent.ACTION_DOWN); }

    private static void up(View v) { touch(v, MotionEvent.ACTION_UP); }

    private static long downAt;

    private static void touch(View v, int action) {
        int[] at = new int[2];
        Emu.ins().runOnMainSync(() -> v.getLocationOnScreen(at));
        float x = at[0] + v.getWidth() / 2f, y = at[1] + v.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downAt = t;
        MotionEvent e = MotionEvent.obtain(downAt, t, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        Emu.ins().getUiAutomation().injectInputEvent(e, true);
        e.recycle();
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
            throw new AssertionError(c.getSimpleName() + " has no field '" + name + "' any more; update AvatarFaceReplayTest", e);
        }
    }
}
