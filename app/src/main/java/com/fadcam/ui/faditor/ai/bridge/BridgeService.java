package com.fadcam.ui.faditor.ai.bridge;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the bridge's door open while the person uses it (Android stops plain background servers):
 * a foreground service with a notification that says so, and Stop right on it. Off unless switched
 * on from the pairing screen; a fresh code every time; Stop, or switching the phone off, ends it.
 */
public class BridgeService extends Service {
    private static final String TAG = "BridgeService";
    private static final String CHANNEL = "joy_bridge";
    private static final int NOTE_ID = 4101;
    /** The first port tried; the next few are tried when it's taken. */
    public static final int PORT = 7314;

    public static final String ACTION_START = "com.fadcam.BRIDGE_START";
    public static final String ACTION_STOP = "com.fadcam.BRIDGE_STOP";
    public static final String EXTRA_PROJECT = "project_id";
    public static final String EXTRA_ROOM = "room";

    /** What the pairing screen shows. Read on the main thread. */
    public static final class State {
        public boolean on;
        @NonNull public String code = "";
        public int port;
        @Nullable public String connectedTo;
        @NonNull public final List<String> lines = new ArrayList<>();
        @Nullable public String problem;
    }

    public interface Watcher {
        void changed(@NonNull State s);
    }

    private static final State STATE = new State();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    @Nullable private static Watcher watcher;
    @Nullable private BridgeServer server;

    @NonNull
    public static State state() {
        return STATE;
    }

    public static void watch(@Nullable Watcher w) {
        watcher = w;
        if (w != null) w.changed(STATE);
    }

    public static void start(@NonNull Context c, @Nullable String projectId, @NonNull IdeaEngine.Room room) {
        Intent i = new Intent(c, BridgeService.class).setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT, projectId).putExtra(EXTRA_ROOM, room.name());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(@NonNull Context c) {
        c.startService(new Intent(c, BridgeService.class).setAction(ACTION_STOP));
    }

    /**
     * The pairing code: kept between openings so the computer that was set up once just reconnects
     * (owner: grandma-simple). "New code" forgets it; nothing else ever changes it.
     */
    @NonNull
    static synchronized String code(@NonNull Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("joy_bridge", Context.MODE_PRIVATE);
        String code = p.getString("code", null);
        if (code == null || !code.matches("\\d{6}")) {
            code = BridgeAuth.newCode(new SecureRandom());
            p.edit().putString("code", code).apply();
        }
        return code;
    }

    /** "New code": the next opening gets a fresh one and the old computer setup stops working. */
    public static synchronized void forgetCode(@NonNull Context c) {
        c.getSharedPreferences("joy_bridge", Context.MODE_PRIVATE).edit().remove("code").apply();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action) || intent == null) {
            // Never comes back by itself after the system stopped it: switching on is always the person's.
            shutDown();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            foreground("Starting…");
            if (server == null) open(intent);
        }
        return START_NOT_STICKY;
    }

    private void open(@NonNull Intent intent) {
        IdeaEngine.Room room = IdeaEngine.Room.LOBBY;
        try {
            String r = intent.getStringExtra(EXTRA_ROOM);
            if (r != null) room = IdeaEngine.Room.valueOf(r);
        } catch (IllegalArgumentException ignored) { }
        String code = code(this);
        BridgeAuth auth = new BridgeAuth(code);
        String version = "1";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        for (int port = PORT; port < PORT + 10; port++) {
            BridgeServer s = new BridgeServer(this, port, auth, intent.getStringExtra(EXTRA_PROJECT), room,
                    version == null ? "1" : version, (line, connected) -> MAIN.post(() -> event(line, connected)));
            try {
                s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                server = s;
                MAIN.post(() -> {
                    STATE.on = true;
                    STATE.code = code;
                    STATE.port = s.getListeningPort();
                    STATE.connectedTo = null;
                    STATE.problem = null;
                    STATE.lines.clear();
                    tell();
                });
                foreground("Waiting for your computer · code " + BridgeAuth.spaced(code));
                FLog.i(TAG, "bridge open on port " + s.getListeningPort());
                return;
            } catch (Exception e) {
                FLog.w(TAG, "port " + port + " taken: " + e.getClass().getSimpleName());
            }
        }
        MAIN.post(() -> {
            STATE.problem = "The phone couldn't open the door (every port was taken). Try again in a minute.";
            tell();
        });
        shutDown();
    }

    private void event(@NonNull String line, boolean connected) {
        STATE.lines.add(line);
        while (STATE.lines.size() > 40) STATE.lines.remove(0);
        BridgeServer s = server;
        if (connected && s != null) {
            STATE.connectedTo = s.who();
            foreground("Connected to " + s.who() + " · Stop ends it");
        }
        tell();
    }

    private static void tell() {
        Watcher w = watcher;
        if (w != null) w.changed(STATE);
    }

    private void shutDown() {
        BridgeServer s = server;
        server = null;
        if (s != null) {
            try { s.stop(); } catch (Exception ignored) { }
        }
        MAIN.post(() -> {
            STATE.on = false;
            STATE.connectedTo = null;
            STATE.code = "";
            tell();
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void foreground(@NonNull String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Computer bridge", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while your computer's AI can use Joybot's tools");
            nm.createNotificationChannel(ch);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP), flags);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, BridgeActivity.class), flags);
        android.app.Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("Joybot's tools are open to your computer")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        BridgeServer s = server;
        server = null;
        if (s != null) {
            try { s.stop(); } catch (Exception ignored) { }
        }
        MAIN.post(() -> {
            STATE.on = false;
            STATE.connectedTo = null;
            tell();
        });
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
