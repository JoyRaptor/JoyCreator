package com.fadcam.ui.settingshub;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.fadcam.BuildConfig;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.joyfolder.android.JoyFolderHome;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What some rows can say about themselves right now: where the folder is, how much room is free,
 * the theme in use, whether Motion Lab is on. Read off the main thread (the folder check asks
 * Android about the folder; free space asks the disk), and only ever what is actually stored: a
 * value that cannot be read is left out, never guessed.
 */
final class SettingsStatus {

    enum Kind {
        /** Replaces the row's description line. */
        LINE,
        /** Replaces the description in the careful amber: something needs the user. */
        ATTENTION,
        /** A switch that is on: the solid cyan pill. */
        ON,
        /** A switch that is off: the quiet word. */
        OFF
    }

    static final class Value {
        final Kind kind;
        final String text;

        Value(@NonNull Kind kind, @NonNull String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    interface Listener {
        /** On the main thread. */
        void onStatus(@NonNull Map<SettingsKey, Value> values);
    }

    private static final String TAG = "SettingsStatus";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-status");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;

    SettingsStatus(@NonNull Context context) {
        app = context.getApplicationContext();
    }

    /** Read everything again; an older read still running is dropped when it lands. */
    void load(@NonNull Listener listener) {
        final int gen = ++generation;
        WORK.execute(() -> {
            final Map<SettingsKey, Value> values = read(app);
            main.post(() -> {
                if (gen == generation) listener.onStatus(values);
            });
        });
    }

    /** The screen is going away: deliver nothing more. */
    void cancel() {
        generation++;
    }

    @WorkerThread
    @NonNull
    static Map<SettingsKey, Value> read(@NonNull Context c) {
        Map<SettingsKey, Value> out = new EnumMap<>(SettingsKey.class);
        try {
            readFolder(c, out);
        } catch (RuntimeException e) {
            FLog.w(TAG, "folder state unreadable", e);
        }
        try {
            readPrefs(c, out);
        } catch (RuntimeException e) {
            FLog.w(TAG, "settings unreadable", e);
        }
        try {
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getPath());
            out.put(SettingsKey.SPACE, line(c.getString(R.string.settingshub_status_free,
                    Formatter.formatShortFileSize(c, fs.getAvailableBytes()))));
        } catch (RuntimeException e) {
            FLog.w(TAG, "free space unreadable", e);
        }
        out.put(SettingsKey.ABOUT, line(c.getString(R.string.settingshub_status_version, BuildConfig.VERSION_NAME)));
        return Collections.unmodifiableMap(out);
    }

    private static void readFolder(@NonNull Context c, @NonNull Map<SettingsKey, Value> out) {
        Uri tree = JoyFolderHome.treeUri(c);
        if (tree == null) {
            out.put(SettingsKey.JOY_FOLDER, new Value(Kind.ATTENTION, c.getString(R.string.settingshub_status_folder_none)));
        } else if (!JoyFolderHome.isReachable(c)) {
            out.put(SettingsKey.JOY_FOLDER, new Value(Kind.ATTENTION, c.getString(R.string.settingshub_status_folder_lost)));
        } else {
            String where = JoyFolderHome.describe(tree);
            if (!TextUtils.isEmpty(where)) out.put(SettingsKey.JOY_FOLDER, line(where));
        }
    }

    private static void readPrefs(@NonNull Context c, @NonNull Map<SettingsKey, Value> out) {
        SharedPreferencesManager p = SharedPreferencesManager.getInstance(c);

        String custom = p.getCustomStorageUri();
        boolean customMode = SharedPreferencesManager.STORAGE_MODE_CUSTOM.equals(p.getStorageMode());
        String where = customMode && custom != null ? JoyFolderHome.describe(Uri.parse(custom)) : null;
        out.put(SettingsKey.RECORDING_LOCATION, line(TextUtils.isEmpty(where)
                ? c.getString(R.string.settingshub_status_inside_app) : where));

        // The same two values the Video screen shows for the camera in use.
        Size res = p.getCameraResolution();
        int fps = p.getSpecificVideoFrameRate(p.getCameraSelection());
        if (res != null && res.getWidth() > 0 && fps > 0) {
            out.put(SettingsKey.CAMERA_VIDEO, line(c.getString(R.string.settingshub_status_video,
                    res.getWidth(), res.getHeight(), fps)));
        }

        String theme = p.sharedPreferences.getString(Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if (!TextUtils.isEmpty(theme)) out.put(SettingsKey.APPEARANCE, line(c.getString(R.string.settingshub_status_theme, theme)));

        out.put(SettingsKey.SECURITY, line(c.getString(p.isAppLockEnabled()
                ? R.string.settingshub_status_tab_lock_on : R.string.settingshub_status_tab_lock_off)));

        out.put(SettingsKey.MOTION_LAB, onOff(c, p.isMotionModeEnabled()));
        out.put(SettingsKey.SMART_DETECTION, onOff(c, p.isDigitalForensicsEnabled()));
        out.put(SettingsKey.SHOW_INTRO, onOff(c, p.isShowOnboarding()));
        // The classic row's key and default, exactly (SettingsHomeFragment.refreshAppInlineValues).
        out.put(SettingsKey.UPDATE_CHECK, onOff(c, p.sharedPreferences.getBoolean(SettingsSwitchSheets.KEY_AUTO_UPDATE, true)));
        out.put(SettingsKey.DEBUG_LOG, onOff(c, p.isDebugLoggingEnabled()));
    }

    @NonNull
    private static Value line(@NonNull String text) {
        return new Value(Kind.LINE, text);
    }

    @NonNull
    private static Value onOff(@NonNull Context c, boolean on) {
        return on ? new Value(Kind.ON, c.getString(R.string.settingshub_on))
                : new Value(Kind.OFF, c.getString(R.string.settingshub_off));
    }
}
