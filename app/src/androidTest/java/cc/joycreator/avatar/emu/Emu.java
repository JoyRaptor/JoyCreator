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

    /** One touch event at a screen point, through the input system. */
    static void touch(long downAt, long at, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downAt, at, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ins().getUiAutomation().injectInputEvent(e, true);
        e.recycle();
    }

    /**
     * A finger put down at (x0, y0), moved to (x1, y1) over {@code moveMs}, kept there {@code holdMs}, then lifted: a
     * real drag (the head pad, a hand), in screen pixels.
     */
    static void drag(float x0, float y0, float x1, float y1, long moveMs, long holdMs) {
        long t0 = SystemClock.uptimeMillis();
        touch(t0, t0, MotionEvent.ACTION_DOWN, x0, y0);
        int steps = Math.max(2, (int) (moveMs / 16));
        for (int i = 1; i <= steps; i++) {
            float k = i / (float) steps;
            SystemClock.sleep(moveMs / steps);
            touch(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k);
        }
        long end = SystemClock.uptimeMillis() + holdMs;
        while (SystemClock.uptimeMillis() < end) {     // a held finger keeps reporting where it is
            SystemClock.sleep(50);
            touch(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1, y1);
        }
        touch(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y1);
    }

    /**
     * The first thing on screen, in any window (dialogs and the system's folder picker included), whose words or label
     * start with {@code text} (any case); waits up to {@code timeoutMs}. Null when nothing showed up.
     */
    static android.view.accessibility.AccessibilityNodeInfo find(String text, long timeoutMs) {
        android.app.UiAutomation ui = ins().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = ui.getServiceInfo();
        if (info != null && (info.flags & android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0) {
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            ui.setServiceInfo(info);
        }
        String want = text.toLowerCase(java.util.Locale.ROOT);
        long end = SystemClock.uptimeMillis() + timeoutMs;
        do {
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
            try {
                for (android.view.accessibility.AccessibilityWindowInfo w : ui.getWindows()) {
                    if (w.getRoot() != null) roots.add(w.getRoot());
                }
            } catch (RuntimeException ignored) {
            }
            if (roots.isEmpty() && ui.getRootInActiveWindow() != null) roots.add(ui.getRootInActiveWindow());
            for (android.view.accessibility.AccessibilityNodeInfo r : roots) {
                android.view.accessibility.AccessibilityNodeInfo hit = search(r, want);
                if (hit != null) return hit;
            }
            SystemClock.sleep(250);
        } while (SystemClock.uptimeMillis() < end);
        return null;
    }

    private static android.view.accessibility.AccessibilityNodeInfo search(android.view.accessibility.AccessibilityNodeInfo n, String want) {
        if (n == null) return null;
        CharSequence t = n.getText(), d = n.getContentDescription();
        boolean exact = want.startsWith("=");          // "=Allow": those words exactly (not the dialog's "Allow ... to access?")
        String w = exact ? want.substring(1) : want;
        String ts = t == null ? null : t.toString().toLowerCase(java.util.Locale.ROOT).trim();
        String ds = d == null ? null : d.toString().toLowerCase(java.util.Locale.ROOT).trim();
        if ((ts != null && (exact ? ts.equals(w) : ts.startsWith(w))) || (ds != null && (exact ? ds.equals(w) : ds.startsWith(w)))) {
            android.graphics.Rect b = new android.graphics.Rect();
            n.getBoundsInScreen(b);
            if (b.width() > 0 && b.height() > 0) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo hit = search(n.getChild(i), want);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Taps the thing labelled {@code text} (see {@link #find}); fails the test, naming it, when it never shows. */
    static void tapText(String text, long timeoutMs) {
        android.view.accessibility.AccessibilityNodeInfo n = find(text, timeoutMs);
        if (n == null) throw new AssertionError("nothing on screen says \"" + text + "\"");
        android.graphics.Rect b = new android.graphics.Rect();
        n.getBoundsInScreen(b);
        long t = SystemClock.uptimeMillis();
        touch(t, t, MotionEvent.ACTION_DOWN, b.exactCenterX(), b.exactCenterY());
        touch(t, t + 60, MotionEvent.ACTION_UP, b.exactCenterX(), b.exactCenterY());
        Log.i(TAG, "tapped \"" + text + "\" at " + b.toShortString());
    }

    /**
     * Clicks the thing labelled {@code text} through accessibility (the nearest clickable box around the words), as a
     * screen reader would: for other apps' windows (the system's folder picker), where a label is not itself the button.
     */
    static void clickText(String text, long timeoutMs) {
        android.view.accessibility.AccessibilityNodeInfo n = find(text, timeoutMs);
        if (n == null) throw new AssertionError("nothing on screen says \"" + text + "\"");
        android.view.accessibility.AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "clicked \"" + text + "\"");
            return;
        }
        // the system's picker ignored touches injected from the test: the shell's own tap (what adb scripts use) reaches it
        android.graphics.Rect b = new android.graphics.Rect();
        n.getBoundsInScreen(b);
        shell("input tap " + b.centerX() + " " + b.centerY());
        Log.i(TAG, "shell-tapped \"" + text + "\" at " + b.toShortString());
    }

    /** True when the thing with keyboard focus (any window) holds words starting with {@code text}. */
    static boolean focusedSays(String text) {
        android.app.UiAutomation ui = ins().getUiAutomation();
        String want = text.toLowerCase(java.util.Locale.ROOT);
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : ui.getWindows()) {
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r == null) continue;
                android.view.accessibility.AccessibilityNodeInfo f = r.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null && search(f, want) != null) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** The thing labelled {@code text} and the boxes around it (class, clickable, focusable, where), for a run's log. */
    static String describeAround(String text) {
        android.view.accessibility.AccessibilityNodeInfo n = find(text, 3000);
        StringBuilder sb = new StringBuilder("around \"" + text + "\":\n");
        android.graphics.Rect b = new android.graphics.Rect();
        for (int d = 0; n != null && d < 6; d++, n = n.getParent()) {
            n.getBoundsInScreen(b);
            sb.append("  ").append(n.getClassName()).append(" clickable=").append(n.isClickable()).append(" focusable=")
                .append(n.isFocusable()).append(" enabled=").append(n.isEnabled()).append(' ').append(b.toShortString())
                .append(" id=").append(n.getViewIdResourceName()).append('\n');
        }
        return sb.toString();
    }

    /** Runs a shell command as the shell user (as adb does) and waits for it; returns what it printed. */
    static String shell(String cmd) {
        android.os.ParcelFileDescriptor pfd = ins().getUiAutomation().executeShellCommand(cmd);
        StringBuilder sb = new StringBuilder();
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[4096];
            int k;
            while ((k = in.read(buf)) > 0) sb.append(new String(buf, 0, k, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "shell: " + cmd, e);
        }
        return sb.toString();
    }

    /** Where the thing labelled {@code text} is on screen (its middle), or null. */
    static float[] centreOf(String text, long timeoutMs) {
        android.view.accessibility.AccessibilityNodeInfo n = find(text, timeoutMs);
        if (n == null) return null;
        android.graphics.Rect b = new android.graphics.Rect();
        n.getBoundsInScreen(b);
        return new float[] {b.exactCenterX(), b.exactCenterY(), b.width(), b.height()};
    }

    /**
     * Closes a screen a test opened, waiting at most {@code ms}: Studio's editor can take far longer to finish than a
     * test should wait (a run once sat 25 minutes in ActivityScenario.close), and a test's verdict never depends on it.
     */
    static void closeSoon(androidx.test.core.app.ActivityScenario<?> s, long ms) {
        if (s == null) return;
        Thread t = new Thread(() -> {
            try {
                s.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "closing the screen", e);
            }
        }, "close-screen");
        t.setDaemon(true);
        t.start();
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) Log.w(TAG, "the screen did not close within " + ms + " ms; going on");
    }

    /** The activity on screen now (resumed), or null. */
    static android.app.Activity resumed() {
        android.app.Activity[] a = new android.app.Activity[1];
        ins().runOnMainSync(() -> {
            java.util.Collection<android.app.Activity> r = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED);
            if (!r.isEmpty()) a[0] = r.iterator().next();
        });
        return a[0];
    }

    /**
     * Where a not-a-number in the room's drawing came from (the character vanishing for good, 11-27 and 11-28): the signals that
     * are not numbers, the walker's fields that are not, the stage's view, and how many parts the GPU stage drew without a number
     * (with a few of their ids). An empty string when everything is a number. Reads the room's fields by name.
     */
    @SuppressWarnings("unchecked")
    static String notANumber(android.app.Activity room) {
        StringBuilder b = new StringBuilder();
        ins().runOnMainSync(() -> {
            try {
                Object sig = field(room, "lastSignals");
                if (sig instanceof java.util.Map) for (java.util.Map.Entry<String, Object> e : ((java.util.Map<String, Object>) sig).entrySet())
                    if (e.getValue() instanceof Number && !Double.isFinite(((Number) e.getValue()).doubleValue())) b.append(" signal ").append(e.getKey());
                Object w = field(room, "walker");
                if (w != null) for (java.lang.reflect.Field f : w.getClass().getFields())
                    if (f.getType() == double.class && !Double.isFinite(f.getDouble(w))) b.append(" walker.").append(f.getName());
                Object st = field(room, "stage");
                for (String m : new String[]{"viewScale", "viewX", "viewY"}) {
                    float v = (Float) st.getClass().getMethod(m).invoke(st);
                    if (!Float.isFinite(v)) b.append(" stage.").append(m);
                }
                Object gl = field(room, "glStage");
                java.util.Map<String, double[]> lm = (java.util.Map<String, double[]>) field(gl, "lastM");
                int bad = 0;
                StringBuilder which = new StringBuilder();
                for (java.util.Map.Entry<String, double[]> e : lm.entrySet()) {
                    boolean nan = false;
                    for (double v : e.getValue()) nan |= !Double.isFinite(v);
                    if (nan && bad++ < 5) which.append(' ').append(e.getKey());
                }
                if (bad > 0) b.append("; drawn without a number ").append(bad).append(" of ").append(lm.size()).append(which);
                double[] rm = (double[]) field(gl, "lastRootM");
                if (rm != null) for (double v : rm) if (!Double.isFinite(v)) { b.append("; the root's matrix"); break; }
            } catch (Exception e) {
                b.append(" (could not read: ").append(e).append(')');
            }
        });
        return b.toString().trim();
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * A watchdog for a screen that stops answering (the room froze on the tablet emulator, 11-28): every 10 s it posts a ping to
     * the main thread; when the ping has not run within 10 s it logs where the main thread is, every 10 s until it answers again.
     * A daemon thread: it ends with the test.
     */
    static void watchMainThread() {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final Thread mainThread = android.os.Looper.getMainLooper().getThread();
        Thread w = new Thread(() -> {
            while (true) {
                final boolean[] ran = {false};
                main.post(() -> ran[0] = true);
                SystemClock.sleep(10000);
                if (!ran[0]) {
                    StringBuilder b = new StringBuilder("the main thread has not answered for 10 s; it is at:");
                    for (StackTraceElement e : mainThread.getStackTrace()) b.append("\n    at ").append(e);
                    Log.w(TAG, b.toString());
                }
            }
        }, "main-thread-watchdog");
        w.setDaemon(true);
        w.start();
    }
}
