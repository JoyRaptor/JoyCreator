package cc.joycreator.avatar.emu;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Shared pieces of the Avatar Studio emulator tests (.github/workflows/avatar-emulator.yml): where pictures go, the
 * planted-fault switch, real taps, screenshots.
 *
 * <p>Pictures land in the app's own files ({@code files/avatar-emu/}); the workflow copies them out with {@code run-as}
 * and uploads them as the run's artifact.</p>
 */
final class Emu {
    static final String TAG = "AvatarEmu";

    private Emu() {}

    static Instrumentation ins() { return InstrumentationRegistry.getInstrumentation(); }

    /** The run's planted fault ({@code am instrument -e fault <name>}), or "" for an honest run. */
    static String fault() {
        return arg("fault", "");
    }

    /** An {@code am instrument -e <name> <value>} argument, or {@code def} when the run gave none. */
    static String arg(String name, String def) {
        Bundle a = InstrumentationRegistry.getArguments();
        String f = a == null ? null : a.getString(name);
        return f == null || f.isEmpty() ? def : f;
    }

    static File dir(String sub) {
        File d = new File(new File(ins().getTargetContext().getFilesDir(), "avatar-emu"), sub);
        d.mkdirs();
        return d;
    }

    static void save(Bitmap b, File f) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        Log.i(TAG, "saved " + f.getName());
    }

    /**
     * Turns the screen the way the run asked ({@code -e orientation portrait|landscape}; none = leave it) and holds it
     * there. A tablet's natural way up is landscape, a phone's upright, so a quarter turn is chosen from the screen's
     * shape. Settings through adb (user_rotation) did not turn the API 30 emulator; UiAutomation does.
     */
    static void turn() {
        String want = arg("orientation", "");
        if (want.isEmpty()) return;
        android.view.Display d = ((android.view.WindowManager) ins().getTargetContext()
            .getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
        android.graphics.Point p = new android.graphics.Point();
        d.getRealSize(p);
        boolean wide = p.x > p.y, wantWide = "landscape".equals(want);
        int rot;
        if (wide == wantWide) rot = android.app.UiAutomation.ROTATION_FREEZE_CURRENT;
        else if (d.getRotation() == android.view.Surface.ROTATION_0) rot = android.app.UiAutomation.ROTATION_FREEZE_90;
        else rot = android.app.UiAutomation.ROTATION_FREEZE_0;
        ins().getUiAutomation().setRotation(rot);
        sleep(2500);
        d.getRealSize(p);
        Log.i(TAG, "asked " + want + ": screen now " + p.x + "x" + p.y);
    }

    /** The whole screen as the person sees it (the GL stage included). */
    static Bitmap screen() { return ins().getUiAutomation().takeScreenshot(); }

    /** A real finger tap at the middle of a view, through the input system (no idle wait: the room animates forever). */
    static void tap(View v) {
        int[] at = new int[2];
        ins().runOnMainSync(() -> v.getLocationOnScreen(at));
        float x = at[0] + v.getWidth() / 2f, y = at[1] + v.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ins().getUiAutomation().injectInputEvent(down, true);
        ins().getUiAutomation().injectInputEvent(up, true);
        down.recycle();
        up.recycle();
    }

    static void sleep(long ms) { SystemClock.sleep(ms); }
}
