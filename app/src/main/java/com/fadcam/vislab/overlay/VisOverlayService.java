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
    private static final String ACTION_PHONE = "com.fadcam.vislab.OVERLAY_PHONE_AUDIO";
    private static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final String CHANNEL_ID = "vislab_overlay";
    private static final int NOTIFICATION_ID = 7414;
    private static final String PREFS = "vislab_overlay";
    private static final int MIN_DP = 140;
    /**
     * Controls float over any app, so the text underneath is unknown: they get a denser fill than
     * the room's 55% drawers, or they cannot be read (seen on the Note 9 over the recorder page).
     */
    static final int LEGIBLE = (0xE0 << 24) | (Studio.PANEL & 0xFFFFFF);

    /** True while the floating visualiser is showing. */
    public static volatile boolean isRunning;
    /** Told (main thread) whenever the visualiser starts or stops, by any route: its own close,
     *  the notification, the recording toolbar, Joybot (audit 10-18 R3: the toolbar label went stale). */
    private static final java.util.List<Runnable> stateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addStateListener(@NonNull Runnable r) {
        stateListeners.add(r);
    }

    public static void removeStateListener(@NonNull Runnable r) {
        stateListeners.remove(r);
    }

    private static void announceState() {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        for (Runnable r : stateListeners) h.post(r);
    }
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
    /** Full screen: the controls tuck into this small round button (it is in the recording too). */
    @Nullable private TextView dot;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tuck = this::tuck;
    /** Long enough to read and choose (4 s was too short in the 10-17 phone test). */
    private static final long TUCK_MS = 6000;
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
        announceState();
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
        showLook(false);   // a stand-in until the folder is read must not replace the person's choice
        // Once the folder is read: the look the person chose (it may be their own), as saved in
        // their folder (a customised built-in included). Audit 10-18 V9.
        session.whenLoaded(() -> {
            if (instance != this) return;
            if (session.lookIds().contains(want)) lookId = want;
            showLook(true);
        });
        setFull(prefs.getBoolean("full", false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        if (intent != null && ACTION_PHONE.equals(intent.getAction())) takePhoneAudio(intent);
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ the phone's own sound

    /** From {@link PhoneAudioConsent}: the person said yes. */
    static void phoneAudioAllowed(@NonNull Context c, int code, @NonNull Intent data) {
        if (!isRunning) return;
        c.startService(new Intent(c, VisOverlayService.class).setAction(ACTION_PHONE)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data));
    }

    /** From {@link PhoneAudioConsent}: no (or back). Nothing changes. */
    static void phoneAudioRefused(@NonNull Context c) {
        android.widget.Toast.makeText(c.getApplicationContext(), "The visualiser keeps listening to the microphone",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void togglePhoneAudio() {
        showControls();
        if (sound.hearsPhone()) {
            sound.usePhone(null);
            startInForeground();   // no longer a screen-capture service
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            android.widget.Toast.makeText(this, "Hearing the phone's own sound needs Android 10 or newer",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        // Audit 10-18 R1: while a recording runs it already hears the phone through the recorder, and
        // a second screen-capture yes would make Android end that recording.
        if (sound.recordingRuns()) {
            android.widget.Toast.makeText(this, "While you record, the visualiser hears the recording",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!com.fadcam.vislab.audio.MicSource.hasPermission(this)) {
            android.widget.Toast.makeText(this, "Allow the microphone for Joy Creator first: Android counts the phone's sound as recording",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        // An app showing a window over others may open its own screen from here.
        startActivity(new Intent(this, PhoneAudioConsent.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void takePhoneAudio(@NonNull Intent intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || sound == null) return;
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        if (data == null) return;
        // A recording started while Android was asking: drop this yes unused (using it would end
        // the recording; the recording's sound is what we hear now anyway).
        if (sound.recordingRuns()) return;
        // Android only hands out the projection to a service that runs as a screen-capture one.
        startInForeground(true);
        try {
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            android.media.projection.MediaProjection p = mpm.getMediaProjection(code, data);
            if (p == null) return;
            p.registerCallback(new android.media.projection.MediaProjection.Callback() {
                @Override
                public void onStop() {   // the person stopped it from Android's own controls
                    ui.post(() -> {
                        if (sound != null && sound.hearsPhone()) {
                            sound.usePhone(null);
                            startInForeground();
                        }
                    });
                }
            }, ui);
            sound.usePhone(p);
        } catch (RuntimeException e) {
            android.util.Log.w("VisOverlay", "phone audio refused by the system", e);
            startInForeground();
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        announceState();
        if (instance == this) instance = null;
        ui.removeCallbacks(tuck);
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
        view.renderer().setWords(null);         // no sample lyrics over someone's screen
        // The look's pictures (the Joy emblem, a picked logo), as in the room (audit 10-18 V6).
        view.renderer().setImageSource(new com.fadcam.vislab.room.Pictures(this));
        view.renderer().setSpriteSource(new com.fadcam.vislab.room.Sprites(this));
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
        boxLp.y = prefs.getInt("y", defaultY(dm));
        wm.addView(box, boxLp);
        box.setOnTouchListener(new Drag(boxLp, box, true));
        resize.setOnTouchListener(new Resize());
    }

    /** The most opaque a pass-through window may be and still let touches reach the app below. */
    private float passThroughAlpha() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f;
        try {
            android.hardware.input.InputManager im = getSystemService(android.hardware.input.InputManager.class);
            float max = im != null ? im.getMaximumObscuringOpacityForTouch() : 0.8f;
            return Math.max(0.1f, max - 0.01f);   // just under: equal can still count as obscuring
        } catch (RuntimeException e) {
            return 0.79f;
        }
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
            // Audit 10-18 R2: Android 12+ blocks touches that pass through another app's window
            // more opaque than its limit (0.8 by default), so full screen sits just under it.
            boxLp.alpha = passThroughAlpha();
            view.renderer().setForceFrame(-1);   // the look's own edges: bleed, as it was made
            resize.setVisibility(View.GONE);
            moveControls(true);
        } else {
            boxLp.width = Math.max(Ui.dp(this, MIN_DP), prefs.getInt("w", Math.round(dm.widthPixels * 0.9f)));
            boxLp.height = Math.max(Ui.dp(this, MIN_DP), prefs.getInt("h", Math.round(dm.heightPixels * 0.3f)));
            boxLp.x = prefs.getInt("x", Math.round(dm.widthPixels * 0.05f));
            boxLp.y = prefs.getInt("y", defaultY(dm));
            boxLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            boxLp.alpha = 1f;
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
                dot = Ui.icon(this, "graphic_eq", 22, Ui.VIZ_A);
                dot.setGravity(Gravity.CENTER);
                dot.setBackground(Ui.round(LEGIBLE, Ui.dp(this, 999)));
                Ui.label(dot, "Visualiser: tap for its controls, drag to move");
                int d = Ui.dp(this, 44);
                handle.addView(dot, new FrameLayout.LayoutParams(d, d));
                wm.addView(handle, handleLp);
                handle.setOnTouchListener(new Drag(handleLp, handle, false));
                // A view with a hover label takes its own touches, so the dot needs the drag/tap too.
                dot.setOnTouchListener(new Drag(handleLp, handle, false));
            }
            handle.addView(controls);
            showControls();
        } else {
            removeQuietly(handle);
            handle = null;
            dot = null;
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            cl.bottomMargin = Ui.dp(this, 10);
            box.addView(controls, cl);
            controls.setVisibility(View.GONE);
        }
    }

    private void toggleBoxControls() {
        if (controls.getVisibility() == View.VISIBLE) tuck();
        else showControls();
    }

    /** Show the controls; they tuck away again after a few seconds untouched. */
    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        if (dot != null) dot.setVisibility(View.GONE);
        if (!full) resize.setVisibility(View.VISIBLE);
        ui.removeCallbacks(tuck);
        ui.postDelayed(tuck, controls.pickerOpen() ? TUCK_MS * 5 / 2 : TUCK_MS);   // browsing the list takes longer
    }

    /** Out of the way (and out of the recording): nothing in the box, a small dot in full screen. */
    private void tuck() {
        ui.removeCallbacks(tuck);
        if (controls == null) return;
        controls.closePicker();
        controls.setVisibility(View.GONE);
        resize.setVisibility(View.GONE);
        if (full && dot != null) dot.setVisibility(View.VISIBLE);
    }

    /**
     * First placement: near the top, clear of the bottom of the screen where apps keep their main
     * buttons (on the recorder page the box sat over Start Screen Recording and took its taps).
     */
    private static int defaultY(@NonNull DisplayMetrics dm) {
        return Math.round(dm.heightPixels * 0.14f);
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
                    else if (tap) showControls();
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
        showLook(true);
    }

    private void showLook(boolean remember) {
        if (lookId == null) return;
        String json = session.lookJson(lookId);
        if (json == null) return;
        VisScene s = VisScene.parse(json);
        view.setScene(s);
        view.renderer().setBrandPalette(session.brand());
        controls.setLook(s.name());
        if (remember) prefs.edit().putString("look", lookId).apply();
    }

    @Override
    public void step(int dir) {
        List<String> ids = session.lookIds();
        if (ids.isEmpty()) return;
        int i = lookId == null ? 0 : Math.max(0, ids.indexOf(lookId));
        lookId = ids.get(((i + dir) % ids.size() + ids.size()) % ids.size());
        showLook();
        showControls();
    }

    @Override
    public void toggleFull() {
        setFull(!full);
    }

    @NonNull
    @Override
    public List<String> lookIds() {
        return session.lookIds();
    }

    @NonNull
    @Override
    public List<String> lookNames() {
        return session.lookNames();
    }

    @Nullable
    @Override
    public String currentLook() {
        return lookId;
    }

    @Override
    public void pickLook(@NonNull String id) {
        lookId = id;
        showLook();
        controls.closePicker();
        showControls();
    }

    @Override
    public void touched() {
        showControls();
    }

    @Override
    public int roomForList() {
        // Full screen: the handle window grows. A box: what is left above the row inside it.
        if (full) return Math.round(getResources().getDisplayMetrics().heightPixels * 0.45f);
        return boxLp.height - Ui.dp(this, 52 + 10 + 6 + 8);
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
        startInForeground(false);
    }

    /** {@code projection}: also a screen-capture service (needed to hear the phone's sound). */
    private void startInForeground(boolean projection) {
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
        // Android 10 needs the screen-capture type to hand out the projection; Android 11+ also
        // only lets a foreground service hear the microphone when it says so.
        if (projection && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                return;
            } catch (RuntimeException e) {
                // fall through
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int type = com.fadcam.vislab.audio.MicSource.hasPermission(this)
                    ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
            if (projection) type |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
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
