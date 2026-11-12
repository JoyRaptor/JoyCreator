package cc.joycreator.textlab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A debug screen for TextLab's phone engine: draws one look on real words, saves the still the look
 * book is compared against, then plays the look live with its frame time on screen. Tap to hold or
 * play. Started by adb:
 *
 * <pre>am start -n &lt;package&gt;/cc.joycreator.textlab.TextLabDebugActivity
 *     --es look dark-steel --es text TITAN --ef t 3 --ei w 1280 --ei h 720</pre>
 *
 * The still lands in the app's own files folder, {@code textlab/<look>_t<t>.png}.
 */
public final class TextLabDebugActivity extends Activity {

    private static final String TAG = "TextLab";

    private GLSurfaceView view;
    private TextView label;
    private volatile boolean live = true;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String lookId = in.hasExtra("look") ? in.getStringExtra("look") : "dark-steel";
        String text = in.getStringExtra("text");
        float t = in.getFloatExtra("t", 3f);
        int w = in.getIntExtra("w", 1280), h = in.getIntExtra("h", 720);
        float stage = in.getFloatExtra("stage", Float.NaN);
        float tx = in.getFloatExtra("tx", 0f), ty = in.getFloatExtra("ty", 0f);
        live = in.getBooleanExtra("live", true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(3);
        view.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        label = new TextView(this);
        label.setTextColor(0xffd4d4d8);
        label.setBackgroundColor(0x99000000);
        label.setTextSize(12);
        label.setPadding(16, 8, 16, 8);
        label.setText("TextLab: loading " + lookId);
        root.addView(view);
        root.addView(label, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        root.setOnClickListener(v -> live = !live);
        view.setOnClickListener(v -> live = !live);
        setContentView(root);

        view.setRenderer(new GLSurfaceView.Renderer() {
            TextLabRenderer r;
            String error;
            int sw, sh;
            double clock0 = -1;
            double sumMs;
            int frames;
            long lastLabel;
            String stillNote = "";

            @Override
            public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                try {
                    long t0 = SystemClock.elapsedRealtimeNanos();
                    TextLabRenderer.Assets assets = TextLabRenderer.Assets.load(getAssets());
                    JSONObject look = TextLabRenderer.Assets.look(getAssets(), lookId);
                    r = new TextLabRenderer(assets, TextLabDebugActivity.this);
                    r.setLook(look);
                    r.setText(text != null ? text.replace("\\n", "\n") : look.optString("sample", "TEXT"));
                    r.setSize(w, h);
                    if (!Float.isNaN(stage)) r.stage = stage;
                    r.tilt = new double[]{tx, ty};
                    long t1 = SystemClock.elapsedRealtimeNanos();
                    r.still(t);
                    GLES30.glFinish();
                    long t2 = SystemClock.elapsedRealtimeNanos();
                    Bitmap bmp = r.readBitmap();
                    File dir = new File(getExternalFilesDir(null), "textlab");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, lookId + "_t" + fmt(t) + ".png");
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    stillNote = String.format(Locale.ROOT, "still %dx%d at t=%s: setup %.0f ms, first still %.0f ms (compiles shaders)",
                            r.width(), r.height(), fmt(t), (t1 - t0) / 1e6, (t2 - t1) / 1e6);
                    Log.i(TAG, stillNote + " -> " + f.getAbsolutePath());
                    Log.i(TAG, "layout " + r.layoutReport());
                } catch (Exception | Error e) {
                    error = e.toString();
                    Log.e(TAG, "TextLab debug failed", e);
                    runOnUiThread(() -> label.setText("TextLab: " + error));
                }
            }

            @Override
            public void onSurfaceChanged(GL10 gl, int width, int height) {
                sw = width;
                sh = height;
            }

            @Override
            public void onDrawFrame(GL10 gl) {
                if (r == null || error != null) {
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                    GLES30.glClearColor(0, 0, 0, 1);
                    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                    return;
                }
                double ms = -1;
                if (live) {
                    double now = SystemClock.elapsedRealtimeNanos() / 1e9;
                    if (clock0 < 0) clock0 = now;
                    long a = SystemClock.elapsedRealtimeNanos();
                    try {
                        // the still left the look's clock at 1000 = moment t: play on from there
                        r.frame(1000 + (now - clock0));
                    } catch (RuntimeException e) {
                        error = e.toString();
                        Log.e(TAG, "TextLab frame failed", e);
                        runOnUiThread(() -> label.setText("TextLab: " + error));
                        return;
                    }
                    GLES30.glFinish();
                    ms = (SystemClock.elapsedRealtimeNanos() - a) / 1e6;
                    sumMs += ms;
                    frames++;
                }
                show();
                long nowMs = SystemClock.uptimeMillis();
                if (nowMs - lastLabel > 500) {
                    lastLabel = nowMs;
                    String s = live && frames > 0
                            ? String.format(Locale.ROOT, "%s  live: %.1f ms a frame (avg of %d), look time %.1f s",
                            lookId, sumMs / frames, frames, r.lookTime())
                            : lookId + "  held (tap to play)";
                    if (frames > 0 && live) Log.i(TAG, s);
                    sumMs = 0;
                    frames = 0;
                    final String text = s + "\n" + stillNote;
                    runOnUiThread(() -> label.setText(text));
                }
            }

            /** The finished picture onto the screen, whole, centred. */
            void show() {
                int pw = r.width(), ph = r.height();
                double k = Math.min((double) sw / pw, (double) sh / ph);
                int dw = (int) Math.round(pw * k), dh = (int) Math.round(ph * k);
                int x0 = (sw - dw) / 2, y0 = (sh - dh) / 2;
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0);
                GLES30.glViewport(0, 0, sw, sh);
                GLES30.glClearColor(0, 0, 0, 1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, r.outputFramebuffer());
                GLES30.glBlitFramebuffer(0, 0, pw, ph, x0, y0, x0 + dw, y0 + dh, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            }
        });
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    private static String fmt(float t) {
        return t == Math.rint(t) ? String.valueOf((int) t) : String.valueOf(t);
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.onResume();
    }
}
