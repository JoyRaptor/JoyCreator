package com.fadcam.vislab.overlay;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.R;
import com.fadcam.ui.faditor.Studio;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.room.Ui;
import com.fadcam.vislab.room.VisLabSession;
import com.fadcam.vislab.view.VisTextureView;

import java.util.List;

/**
 * VisLab floating over everything: over any app while the person plays music, and over the
 * screen while it is being recorded (the recorder captures the screen, so the visualiser is in
 * the video too). Two ways to show it:
 * <ul>
 *   <li><b>Box</b>: a window you drag and resize, its art faded out at the edges (contained, no
 *       seam). Tap it for the controls.</li>
 *   <li><b>Full screen</b>: see-through over the whole screen, touches passing to the app below;
 *       a small handle stays touchable for the controls.</li>
 * </ul>
 * Listens through {@link OverlaySound}; shows the looks the VisLab room keeps (the person's own
 * included), remembering its own pick.
 */
public class VisOverlayService extends Service implements OverlayControls.Actions, OverlaySound.Listener {

    public static final String ACTION_STOP = "com.fadcam.vislab.OVERLAY_STOP";
    private static final String CHANNEL_ID = "vislab_overlay";
    private static final int NOTIFICATION_ID = 7414;
    private static final String PREFS = "vislab_overlay";
    private static final int MIN_DP = 140;

    /** True while the floating visualiser is showing. */
    public static volatile boolean isRunning;
    @Nullable private static volatile VisOverlayService instance;

    /**
     * Show look {@code id} (a VisLab look file's id) in the floating visualiser: at once if it is
     * showing, otherwise the next time it starts. An id that no longer exists falls back to the
     * first look. Any thread.
     */
    public static void setLook(@NonNull Context c, @NonNull String id) {
        c.getApplicationContext().getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("look", id).apply();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            VisOverlayService s = instance;
            if (s == null || s.view == null) return;
            if (s.session.lookIds().contains(id)) {
                s.lookId = id;
                s.showLook();
            }
        });
    }

    private WindowManager wm;
    private SharedPreferences prefs;
    private FrameLayout box;
    private VisTextureView view;
    private OverlayControls controls;
    private TextView resize;
    @Nullable private FrameLayout handle;
    private WindowManager.LayoutParams boxLp, handleLp;
    private boolean full;
    private OverlaySound sound;
    private VisLabSession session;
    private String lookId;

    /** Turn the floating visualiser on or off (from the recording toolbar). */
    public static void toggle(@NonNull Context c) {
        Intent i = new Intent(c, VisOverlayService.class);
        if (isRunning) {
            c.stopService(i);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        startInForeground();   // first, always: a foreground start that never calls it crashes the app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            // Samsung titles the same switch "Appear on top": name both so it can be found.
            android.widget.Toast.makeText(this, "To float the visualiser over other apps, allow Joy Creator to "
                    + "\"Display over other apps\" (Samsung: \"Appear on top\") in Android's settings.",
                    android.widget.Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        session = VisLabSession.get(this);
        buildBox();
        sound = new OverlaySound(this, this);
        view.setFeatureSource(dt -> sound.analyzer.step(dt));
        sound.start();
        List<String> ids = session.lookIds();
        String want = prefs.getString("look", "halo-drop");
        lookId = ids.contains(want) ? want : (ids.isEmpty() ? null : ids.get(0));
        showLook();
        setFull(prefs.getBoolean("full", false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (instance == this) instance = null;
        if (sound != null) sound.stop();
        removeQuietly(handle);
        handle = null;
        removeQuietly(box);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ window

    @SuppressLint("ClickableViewAccessibility")
    private void buildBox() {
        box = new FrameLayout(this);
        view = new VisTextureView(this);
        view.renderer().setTransparent(true);   // see-through over whatever is below
        box.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controls = new OverlayControls(this, this);
        controls.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cl.bottomMargin = Ui.dp(this, 10);
        box.addView(controls, cl);
        resize = Ui.icon(this, "open_in_full", 18, Studio.INK);
        resize.setGravity(Gravity.CENTER);
        resize.setBackground(Ui.round(Ui.DRAWER, Ui.dp(this, 999)));
        resize.setRotation(90);
        Ui.label(resize, "Drag to resize");
        resize.setVisibility(View.GONE);
        int rs = Ui.dp(this, 34);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(rs, rs, Gravity.TOP | Gravity.END);
        rl.topMargin = rl.rightMargin = Ui.dp(this, 6);
        box.addView(resize, rl);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        boxLp = new WindowManager.LayoutParams(
                prefs.getInt("w", Math.round(dm.widthPixels * 0.9f)),
                prefs.getInt("h", Math.round(dm.heightPixels * 0.3f)),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        boxLp.gravity = Gravity.TOP | Gravity.START;
        boxLp.x = prefs.getInt("x", Math.round(dm.widthPixels * 0.05f));
        boxLp.y = prefs.getInt("y", Math.round(dm.heightPixels * 0.62f));
        wm.addView(box, boxLp);
        box.setOnTouchListener(new Drag(boxLp, box, true));
        resize.setOnTouchListener(new Resize());
    }

    private void setFull(boolean on) {
        full = on;
        prefs.edit().putBoolean("full", on).apply();
        controls.setFull(on);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        if (on) {
            boxLp.width = WindowManager.LayoutParams.MATCH_PARENT;
            boxLp.height = WindowManager.LayoutParams.MATCH_PARENT;
            boxLp.x = boxLp.y = 0;
            boxLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            view.renderer().setForceFrame(-1);   // the look's own edges: bleed, as it was made
            moveControls(true);
        } else {
            boxLp.width = Math.max(Ui.dp(this, MIN_DP), prefs.getInt("w", Math.round(dm.widthPixels * 0.9f)));
            boxLp.height = Math.max(Ui.dp(this, MIN_DP), prefs.getInt("h", Math.round(dm.heightPixels * 0.3f)));
            boxLp.x = prefs.getInt("x", Math.round(dm.widthPixels * 0.05f));
            boxLp.y = prefs.getInt("y", Math.round(dm.heightPixels * 0.62f));
            boxLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            view.renderer().setForceFrame(1);    // contained: soft box, never a hard seam
            moveControls(false);
        }
        wm.updateViewLayout(box, boxLp);
    }

    /** Full screen keeps the controls in a small touchable handle; the box keeps them inside. */
    @SuppressLint("ClickableViewAccessibility")
    private void moveControls(boolean toHandle) {
        if (controls.getParent() instanceof ViewGroup) ((ViewGroup) controls.getParent()).removeView(controls);
        if (toHandle) {
            if (handle == null) {
                handle = new FrameLayout(this);
                handleLp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType(),
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                handleLp.gravity = Gravity.TOP | Gravity.START;
                handleLp.x = prefs.getInt("hx", Ui.dp(this, 12));
                handleLp.y = prefs.getInt("hy", Ui.dp(this, 96));
                wm.addView(handle, handleLp);
                handle.setOnTouchListener(new Drag(handleLp, handle, false));
            }
            handle.addView(controls);
            controls.setVisibility(View.VISIBLE);
        } else {
            removeQuietly(handle);
            handle = null;
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            cl.bottomMargin = Ui.dp(this, 10);
            box.addView(controls, cl);
            controls.setVisibility(View.GONE);
        }
    }

    private void toggleBoxControls() {
        boolean show = controls.getVisibility() != View.VISIBLE;
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        resize.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void removeQuietly(@Nullable View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    /** Drag a window; a short tap (box only) shows or hides the controls. */
    private final class Drag implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private final View win;
        private final boolean isBox;
        private int x0, y0;
        private float tx, ty;
        private long down;

        Drag(WindowManager.LayoutParams lp, View win, boolean isBox) {
            this.lp = lp;
            this.win = win;
            this.isBox = isBox;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = System.currentTimeMillis();
                    x0 = lp.x;
                    y0 = lp.y;
                    tx = e.getRawX();
                    ty = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (isBox && full) return true;
                    lp.x = x0 + Math.round(e.getRawX() - tx);
                    lp.y = y0 + Math.round(e.getRawY() - ty);
                    wm.updateViewLayout(win, lp);
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean tap = System.currentTimeMillis() - down < 250
                            && Math.abs(e.getRawX() - tx) < 12 && Math.abs(e.getRawY() - ty) < 12;
                    if (tap && isBox) toggleBoxControls();
                    else if (!tap) prefs.edit().putInt(isBox ? "x" : "hx", lp.x).putInt(isBox ? "y" : "hy", lp.y).apply();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** The corner handle: free resize, never smaller than the controls need. */
    private final class Resize implements View.OnTouchListener {
        private int w0, h0, x0;
        private float tx, ty;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    w0 = boxLp.width;
                    h0 = boxLp.height;
                    x0 = boxLp.x;
                    tx = e.getRawX();
                    ty = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    // The handle sits top-right: right edge follows the finger, top edge too.
                    int dx = Math.round(e.getRawX() - tx);
                    int dy = Math.round(e.getRawY() - ty);
                    int w = Math.max(Ui.dp(VisOverlayService.this, MIN_DP), Math.min(dm.widthPixels, w0 + dx));
                    int h = Math.max(Ui.dp(VisOverlayService.this, MIN_DP), Math.min(dm.heightPixels, h0 - dy));
                    boxLp.y += boxLp.height - h;
                    boxLp.width = w;
                    boxLp.height = h;
                    boxLp.x = x0;
                    wm.updateViewLayout(box, boxLp);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    prefs.edit().putInt("w", boxLp.width).putInt("h", boxLp.height)
                            .putInt("x", boxLp.x).putInt("y", boxLp.y).apply();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ looks + sound

    private void showLook() {
        if (lookId == null) return;
        String json = session.lookJson(lookId);
        if (json == null) return;
        VisScene s = VisScene.parse(json);
        view.setScene(s);
        view.renderer().setBrandPalette(session.brand());
        controls.setLook(s.name());
        prefs.edit().putString("look", lookId).apply();
    }

    @Override
    public void step(int dir) {
        List<String> ids = session.lookIds();
        if (ids.isEmpty()) return;
        int i = lookId == null ? 0 : Math.max(0, ids.indexOf(lookId));
        lookId = ids.get(((i + dir) % ids.size() + ids.size()) % ids.size());
        showLook();
    }

    @Override
    public void toggleFull() {
        setFull(!full);
    }

    @Override
    public void close() {
        stopSelf();
    }

    @Override
    public void onSource(int kind) {
        controls.setSource(kind);
    }

    // ------------------------------------------------------------------ foreground

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Visualiser on screen",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, VisOverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Visualiser on screen")
                .setContentText("Moving with what you hear")
                .setSmallIcon(R.drawable.ic_waveform_bars)
                .setOngoing(true)
                .addAction(0, "Turn off", stop)
                .build();
        // Android 11+ only lets a foreground service hear the microphone when it says so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int type = com.fadcam.vislab.audio.MicSource.hasPermission(this)
                    ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
            if (Build.VERSION.SDK_INT >= 34) type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            try {
                startForeground(NOTIFICATION_ID, n, type);
                return;
            } catch (RuntimeException e) {
                // fall through: without the microphone it still shows a recording's sound
            }
        }
        startForeground(NOTIFICATION_ID, n);
    }
}
