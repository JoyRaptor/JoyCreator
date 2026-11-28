package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.R;
import com.fadcam.ui.faditor.avatar.TrackerCameras;
import com.fadcam.ui.faditor.avatar.face.CameraPlan;
import com.fadcam.ui.faditor.avatar.face.FaceCapture;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.perform.Inputs;
import cc.joycreator.avatar.ui.RoomEdit;

/**
 * Cameras per tracker (the owner, 11-27) on the emulator's own camera system (a front and a back camera). First the cameras
 * themselves, without any face or body model: the choices are exactly the cameras it has, each choice opens that camera and
 * its pictures arrive, and two cameras open together only where the camera system says that pair can. Then the room: the
 * face row's camera chips by real taps, one undo step each, the body's row with Shoulders, and (where the pair cannot open)
 * the plain line and One camera for both. Reads the room's fields by name: no test hooks in the room.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarCameraTest {
    private final StringBuilder log = new StringBuilder();
    private int fails;

    /** The test's own lifecycle for the cameras it opens itself. */
    static final class Owner implements LifecycleOwner {
        final LifecycleRegistry reg = new LifecycleRegistry(this);

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return reg;
        }
    }

    @Test
    public void eachTrackerReadsTheCameraChosen() throws Exception {
        File out = Emu.dir("cameras");
        String pkg = Emu.ins().getTargetContext().getPackageName();
        Emu.ins().getUiAutomation().grantRuntimePermission(pkg, Manifest.permission.CAMERA);
        try {
            // ── the cameras themselves ──
            ProcessCameraProvider p = ProcessCameraProvider.getInstance(Emu.ins().getTargetContext()).get();
            List<CameraPlan.Lens>[] lensesBox = new List[1];
            List<Set<String>>[] pairsBox = new List[1];
            Emu.ins().runOnMainSync(() -> {
                lensesBox[0] = TrackerCameras.lenses(p);
                pairsBox[0] = TrackerCameras.pairs(p);
            });
            List<CameraPlan.Choice> choices = CameraPlan.choices(lensesBox[0]);
            StringBuilder keys = new StringBuilder();
            for (CameraPlan.Choice c : choices) keys.append(keys.length() == 0 ? "" : ", ").append(c.key);
            log.append("      cameras: ").append(lensesBox[0].size()).append(", pairs that open together: ").append(pairsBox[0]).append('\n');
            boolean hasFront = has(choices, "front"), hasBack = has(choices, "back");
            check(hasFront && hasBack && choices.size() == lensesBox[0].size(),
                    "the choices are the cameras it has: " + keys + " (" + lensesBox[0].size() + " cameras)");
            for (CameraPlan.Choice c : choices) {
                int[] facing = {-1};
                int frames = open(p, CameraPlan.plan(c.key, null, false, choices, pairsBox[0]), facing, null);
                int want = c.lens.front ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
                check(frames > 3 && facing[0] == want, c.key + " opens the " + (c.lens.front ? "front" : "back") + " camera and its pictures arrive (" + frames + " in 2 s)");
            }
            CameraPlan.Plan two = CameraPlan.plan("front", "back", true, choices, pairsBox[0]);
            boolean canPair = false;
            for (Set<String> s : pairsBox[0]) if (s.contains(find(choices, "front").lens.id) && s.contains(find(choices, "back").lens.id)) canPair = true;
            if (canPair) {
                int[] facing = {-1};
                AtomicInteger bodyFrames = new AtomicInteger();
                int faceFrames = open(p, two, facing, bodyFrames);
                check(two.two && faceFrames > 3 && bodyFrames.get() > 3,
                        "face Front and body Back where the pair opens together: both cameras deliver (" + faceFrames + " and " + bodyFrames.get() + ")");
            } else {
                check(!two.two && two.refused && two.body == two.face,
                        "face Front and body Back where the camera system cannot open that pair: refused, the face's camera feeds both");
            }

            // ── the room ──
            try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
                Emu.sleep(4000);
                AvatarRoomActivity[] ar = new AvatarRoomActivity[1];
                sc.onActivity(x -> ar[0] = x);
                AvatarRoomActivity a = ar[0];
                TextView[] tabs = (TextView[]) field(a, "stageTabs");
                Emu.tap(tabs[3]);
                Emu.sleep(2000);
                Inputs in = (Inputs) field(a, "inputs");
                // the face on as the Face chip does, minus the camera's own start (the trackers' models are not built for this
                // emulator's processor): the room's own face capture knows the cameras and plans them
                Emu.ins().runOnMainSync(() -> in.face(true));
                Emu.sleep(1000);
                openFaceSheet(a);                                            // (the cameras live in the face sheet; it makes the room's face capture)
                Emu.sleep(2500);                                             // (the camera list arrives, the sheet is laid out again)
                FaceCapture fc = (FaceCapture) field(a, "faceCapture");
                assertNotNull("the room has its face capture", fc);
                if (fc.shouldersOn()) {                                      // Shoulders off by a real tap (it is on as built)
                    tapText(a, a.getString(R.string.avatar_shoulders));
                    Emu.sleep(1200);
                }
                check(fc.cameraChoices() != null && fc.cameraChoices().contains("front") && fc.cameraChoices().contains("back")
                                && !fc.cameraChoices().contains("back-wide"),
                        "the room knows this phone's cameras: " + fc.cameraChoices());
                check(find(a, a.getString(R.string.avatar_face_camera)) != null && find(a, a.getString(R.string.avatar_cam_back_wide)) == null,
                        "the face row offers Face camera, with no Back wide on a phone without one");
                check(find(a, a.getString(R.string.avatar_body_camera)) == null, "Shoulders off: no Body camera row");
                shot(out, "01-face-camera-row");

                tapText(a, a.getString(R.string.avatar_cam_back));
                Emu.sleep(1500);
                String undo = undoName(a);
                CameraPlan.Plan plan = fc.cameraPlan();
                check("back".equals(settings(a).optString("faceCamera")) && plan != null && plan.face != null && "back".equals(plan.face.key)
                                && a.getString(R.string.avatar_step_face_camera, a.getString(R.string.avatar_cam_back)).equals(undo),
                        "Face camera, Back by a real tap: saved with the character, the face reads the back camera, one undo step (\"" + undo + "\")");
                shot(out, "02-face-on-back");

                tapText(a, a.getString(R.string.avatar_shoulders));
                Emu.sleep(1500);
                check(find(a, a.getString(R.string.avatar_body_camera)) != null, "Shoulders on: the Body camera row shows");
                plan = fc.cameraPlan();
                check(plan != null && plan.body != null && "back".equals(plan.body.key) && !plan.two && !plan.refused,
                        "the body follows the face's camera until chosen: one camera (Back) for both");
                // the body on the front camera: the second row's Front chip (the first Front is the face's)
                View bodyFront = second(a, a.getString(R.string.avatar_cam_front));
                assertNotNull("the Body camera row has Front", bodyFront);
                Emu.ins().runOnMainSync(() -> bodyFront.requestRectangleOnScreen(new Rect(0, 0, bodyFront.getWidth(), bodyFront.getHeight()), true));
                Emu.sleep(300);
                Emu.tap(bodyFront);
                Emu.sleep(1500);
                plan = fc.cameraPlan();
                undo = undoName(a);
                check("front".equals(settings(a).optString("bodyCamera")) && a.getString(R.string.avatar_step_body_camera, a.getString(R.string.avatar_cam_front)).equals(undo),
                        "Body camera, Front by a real tap: saved, one undo step (\"" + undo + "\")");
                shot(out, "03-body-on-front");
                String where = "this phone cannot open both";
                if (canPair) {
                    check(plan.two && find(a, a.getString(R.string.avatar_cams_refused)) == null,
                            "this phone opens Front and Back together: the face on the back camera, the body on the front, nothing refused");
                    // and a phone that cannot open that pair: stood in for by telling the room's face capture that no two cameras
                    // open together, then laying the panel out again as the room does after a step
                    Emu.ins().runOnMainSync(() -> setField(fc, "pairs", CameraPlan.none()));
                    rebind(a);
                    plan = fc.cameraPlan();
                    where = "a phone that cannot open both (stood in for)";
                }
                check(plan.refused && find(a, a.getString(R.string.avatar_cams_refused)) != null && find(a, a.getString(R.string.avatar_cams_one)) != null,
                        where + ": one plain line says so, and One camera for both is offered");
                check(onScreen(find(a, a.getString(R.string.avatar_cams_refused))) && onScreen(find(a, a.getString(R.string.avatar_cams_one))),
                        "...where it is seen: the line and One camera for both are on screen, not scrolled below the chips just tapped");
                shot(out, "04-refused");
                tapText(a, a.getString(R.string.avatar_cams_one));
                Emu.sleep(1500);
                plan = fc.cameraPlan();
                check("back".equals(settings(a).optString("bodyCamera")) && !plan.refused && find(a, a.getString(R.string.avatar_cams_refused)) == null,
                        "One camera for both by a real tap: the body reads the face's camera (Back), the line goes, one undo step (\"" + undoName(a) + "\")");
                shot(out, "05-one-camera-for-both");
                closeFaceSheet(a);                                           // (a sheet takes a touch outside it: Undo needs it closed)
                Emu.tap((View) field(a, "undoButton"));
                Emu.sleep(1500);
                check("front".equals(settings(a).optString("bodyCamera")) && fc.cameraPlan().refused, "one Undo: the body back on Front, refused again");
                Emu.tap((View) field(a, "undoButton"));
                Emu.sleep(1500);
                Emu.tap((View) field(a, "undoButton"));
                Emu.sleep(1500);
                plan = fc.cameraPlan();
                check(!settings(a).has("faceCamera") && !settings(a).has("bodyCamera") && plan.face != null && "front".equals(plan.face.key) && plan.body == plan.face,
                        "Undo all the way: Front for both again, nothing saved");
                Emu.ins().runOnMainSync(() -> in.face(false));
            }
        } finally {
            Log.i(Emu.TAG, "cameras\n" + log);
        }
        assertTrue("checks failed:\n" + log, fails == 0);
    }

    /**
     * Opens what the plan says through {@link TrackerCameras} with picture counters for two seconds; the face camera's
     * facing into {@code facing}; the body's pictures into {@code bodyFrames} (two cameras only). Returns the face's count.
     */
    private static int open(ProcessCameraProvider p, CameraPlan.Plan plan, int[] facing, AtomicInteger bodyFrames) {
        Owner owner = new Owner();
        AtomicInteger faceFrames = new AtomicInteger();
        java.util.concurrent.ExecutorService fx = Executors.newSingleThreadExecutor(), bx = Executors.newSingleThreadExecutor();
        ImageAnalysis face = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        ImageAnalysis body = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        face.setAnalyzer(fx, img -> { faceFrames.incrementAndGet(); img.close(); });
        body.setAnalyzer(bx, img -> { if (bodyFrames != null) bodyFrames.incrementAndGet(); img.close(); });
        String what = plan.face.key + (plan.two ? " + " + plan.body.key : "");
        long t0 = android.os.SystemClock.uptimeMillis();
        Log.i(Emu.TAG, "cameras: opening " + what);
        Emu.ins().runOnMainSync(() -> {
            owner.reg.setCurrentState(Lifecycle.State.RESUMED);
            TrackerCameras.Opened o = TrackerCameras.open(p, owner, plan, face, body);
            facing[0] = o.face.getCameraInfo().getLensFacing();
        });
        Log.i(Emu.TAG, "cameras: " + what + " bound in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
        // pictures for two seconds once the first arrives (the emulator's cameras can take a while to start), at most 20 s
        long until = android.os.SystemClock.uptimeMillis() + 20000;
        while (faceFrames.get() == 0 && android.os.SystemClock.uptimeMillis() < until) Emu.sleep(100);
        int first = faceFrames.get();
        Emu.sleep(2000);
        int frames = faceFrames.get() - first + (first > 0 ? 1 : 0);
        Log.i(Emu.TAG, "cameras: " + what + " first picture after " + (android.os.SystemClock.uptimeMillis() - t0 - 2000) + " ms; " + frames + " in 2 s");
        long t1 = android.os.SystemClock.uptimeMillis();
        Emu.ins().runOnMainSync(() -> {
            owner.reg.setCurrentState(Lifecycle.State.DESTROYED);
            p.unbindAll();
        });
        fx.shutdown();
        bx.shutdown();
        Emu.sleep(1500);                                                     // (let the camera close before the next opens)
        Log.i(Emu.TAG, "cameras: " + what + " closed in " + (android.os.SystemClock.uptimeMillis() - t1) + " ms");
        return frames;
    }

    // ── small pieces ────────────────────────────────────────────────────────────────────────────────

    private void check(boolean c, String what) {
        log.append(c ? "PASS  " : "FAIL  ").append(what).append('\n');
        Log.i(Emu.TAG, (c ? "PASS  " : "FAIL  ") + what);                  // (as it happens: a stalled run shows where)
        if (!c) fails++;
    }

    private static boolean has(List<CameraPlan.Choice> cs, String key) {
        return find(cs, key) != null;
    }

    private static CameraPlan.Choice find(List<CameraPlan.Choice> cs, String key) {
        for (CameraPlan.Choice c : cs) if (c.key.equals(key)) return c;
        return null;
    }

    private static JSONObject settings(AvatarRoomActivity a) throws Exception {
        JSONObject st = ((CharacterDoc) field(a, "character")).json.optJSONObject("settings");
        return st == null ? new JSONObject() : st;
    }

    private static String undoName(AvatarRoomActivity a) throws Exception {
        RoomEdit ed = (RoomEdit) field(a, "edit");
        String[] top = new String[1];
        Emu.ins().runOnMainSync(() -> top[0] = ed.undoName());
        return top[0];
    }

    private static void shot(File out, String name) throws Exception {
        Bitmap s = Emu.screen();
        if (s != null) {
            Emu.save(s, new File(out, name + ".png"));
            s.recycle();
        }
    }

    /** The first view showing exactly this text, or null. */
    private static View find(AvatarRoomActivity a, String txt) {
        View[][] hit = {new View[2]};
        int[] n = {0};
        View sheet = sheetRoot(a);                                       // (the face sheet first, while it is open)
        Emu.ins().runOnMainSync(() -> {
            if (sheet != null) collect(sheet, txt, hit[0], n);
            collect(a.getWindow().getDecorView(), txt, hit[0], n);
        });
        return hit[0][0];
    }

    /** The second view showing exactly this text (the body's row after the face's), or null. */
    private static View second(AvatarRoomActivity a, String txt) {
        View[][] hit = {new View[2]};
        int[] n = {0};
        View sheet = sheetRoot(a);
        Emu.ins().runOnMainSync(() -> {
            if (sheet != null) collect(sheet, txt, hit[0], n);
            collect(a.getWindow().getDecorView(), txt, hit[0], n);
        });
        return hit[0][1];
    }

    private static void collect(View v, String txt, View[] hits, int[] n) {
        if (n[0] >= hits.length) return;
        if (v instanceof TextView && txt.contentEquals(((TextView) v).getText()) && v.isShown()) hits[n[0]++] = v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), txt, hits, n);
    }

    /** The face sheet's window while it is open (the face's settings live there), or null. */
    private static View sheetRoot(AvatarRoomActivity a) {
        try {
            Object sheet = field(field(a, "performPanel"), "faceSheet");
            Object d = sheet == null ? null : field(sheet, "dialog");
            return d == null ? null : ((android.app.Dialog) d).getWindow().getDecorView();
        } catch (Exception e) {
            return null;
        }
    }

    /** Face settings by a real tap: the sheet behind the Face chip. */
    private static void openFaceSheet(AvatarRoomActivity a) {
        if (sheetRoot(a) != null) return;
        View chip = find(a, a.getString(R.string.avatar_face_settings));
        org.junit.Assert.assertNotNull("the Face settings chip shows while the face is on", chip);
        Emu.tap(chip);
        Emu.sleep(1200);
    }

    /** The sheet closed with the Back key (a sheet takes a touch outside it, so the stage tabs need it gone). */
    private static void closeFaceSheet(AvatarRoomActivity a) {
        if (sheetRoot(a) == null) return;
        Emu.ins().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        Emu.sleep(800);
    }

    /** Scrolls the view into sight, then a real tap on it. */
    private static void tapText(AvatarRoomActivity a, String txt) {
        View v = find(a, txt);
        assertNotNull("nothing on screen says \"" + txt + "\"", v);
        Emu.ins().runOnMainSync(() -> v.requestRectangleOnScreen(new Rect(0, 0, v.getWidth(), v.getHeight()), true));
        Emu.sleep(300);
        Emu.tap(v);
    }

    /** All of the view is on the screen now (not scrolled out of the panel). */
    private static boolean onScreen(View v) {
        if (v == null) return false;
        boolean[] all = new boolean[1];
        Emu.ins().runOnMainSync(() -> {
            Rect r = new Rect();
            all[0] = v.getGlobalVisibleRect(r) && r.height() >= v.getHeight() - 1 && r.width() >= v.getWidth() - 1;
        });
        return all[0];
    }

    /** The Perform panel laid out again for the character (what the room does after a step). */
    private static void rebind(AvatarRoomActivity a) throws Exception {
        Object panel = field(a, "performPanel");
        CharacterDoc d = (CharacterDoc) field(a, "character");
        Emu.ins().runOnMainSync(() -> {
            try {
                panel.getClass().getMethod("bind", CharacterDoc.class, boolean.class).invoke(panel, d, true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Emu.sleep(800);
    }

    private static void setField(Object o, String name, Object val) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, val);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Field fl = o.getClass().getDeclaredField(name);
        fl.setAccessible(true);
        Object[] v = new Object[1];
        Emu.ins().runOnMainSync(() -> {
            try { v[0] = fl.get(o); } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        });
        return v[0];
    }
}
