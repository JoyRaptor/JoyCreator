package com.fadcam.ui.faditor.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SPEC_20260829_AUDIO_SYNC_TRUTH §3.4 — output latency measurement + user calibration.
 *
 * <p>Two sources of truth, in priority order:
 * <ol>
 *   <li>Measured via {@link AudioTrack#getTimestamp(AudioTimestamp)} — median of a few samples
 *       after playback starts, cached per output-device id.</li>
 *   <li>Estimated fallback when getTimestamp returns false: framesPerBuffer / sampleRate + BT surcharge.</li>
 * </ol>
 * Plus a user offset in SharedPreferences {@code audio_sync_offset_ms} (−500..+500, default 0).
 *
 * <p>Usage: {@link #outputLatencyMs(Context)} returns ms to SUBTRACT from decoder position
 * to get the position the user is HEARING. Apply only to the DRAWN playhead during playback.
 */
public final class AudioLatency {

    private static final String TAG = "AudioLatency";
    private static final String PREFS = "audio_sync";
    private static final String KEY_USER_OFFSET = "audio_sync_offset_ms";
    private static final int BT_SURCHARGE_MS = 180;

    private static final Map<String, Integer> measuredCache = new HashMap<>();
    private static String lastDeviceKey = "unknown";
    private static boolean deviceCallbackRegistered = false;

    private AudioLatency() {}

    /** ms to SUBTRACT from decoder position to get what the user hears. 0 when paused. */
    public static int outputLatencyMs(@NonNull Context ctx) {
        int base = measuredLatencyMs(ctx);
        int user = userOffsetMs(ctx);
        int total = base + user;
        // Clamp to sane range so a wild estimate doesn't pull playhead off-screen.
        return Math.max(-500, Math.min(800, total));
    }

    /** Measured or estimated base latency (without user offset). */
    public static int measuredLatencyMs(@NonNull Context ctx) {
        ensureDeviceCallback(ctx);
        String key = currentDeviceKey(ctx);
        Integer cached = measuredCache.get(key);
        if (cached != null) return cached;
        // Try estimate immediately; measured will fill cache async when available.
        int est = estimateLatencyMs(ctx);
        return est;
    }

    /** Source label for UI: "measured" or "estimated". */
    @NonNull
    public static String latencySource(@NonNull Context ctx) {
        String key = currentDeviceKey(ctx);
        return measuredCache.containsKey(key) ? "measured" : "estimated";
    }

    @NonNull
    public static String activeRouteName(@NonNull Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "Unknown";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo d : devices) {
                    // Prefer the most likely active: BT A2DP first, then wired, then speaker
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return "Bluetooth";
                    if (d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || d.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) return "Wired";
                }
            }
            // Fallback via wired headset check
            if (am.isWiredHeadsetOn()) return "Wired";
            if (am.isBluetoothA2dpOn()) return "Bluetooth";
            return "Speaker";
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public static int userOffsetMs(@NonNull Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getInt(KEY_USER_OFFSET, 0);
    }

    public static void setUserOffsetMs(@NonNull Context ctx, int ms) {
        int clamped = Math.max(-500, Math.min(500, ms));
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_USER_OFFSET, clamped).apply();
        FLog.d(TAG, "userOffset set to " + clamped + " ms");
    }

    /** Call when playback starts on an AudioTrack to sample getTimestamp. */
    public static void sampleFromAudioTrack(@NonNull Context ctx, @NonNull AudioTrack track) {
        // Sample 3 times, 80ms apart, take median
        Handler h = new Handler(Looper.getMainLooper());
        List<Long> latencies = new ArrayList<>();
        Runnable sampler = new Runnable() {
            int count = 0;
            @Override public void run() {
                try {
                    AudioTimestamp ts = new AudioTimestamp();
                    if (track.getTimestamp(ts)) {
                        long latencyNs = ts.nanoTime == 0 ? 0 : System.nanoTime() - ts.nanoTime;
                        // framePosition -> convert via sample rate would be more accurate, but
                        // nanoTime delta already is presentation vs now.
                        long latencyMs = latencyNs / 1_000_000L;
                        // Heuristic clamp: 0..800 ms is plausible for output latency
                        if (latencyMs >= 0 && latencyMs <= 800) latencies.add(latencyMs);
                    }
                } catch (Exception ignored) {}
                count++;
                if (count < 3) {
                    h.postDelayed(this, 80);
                } else {
                    if (!latencies.isEmpty()) {
                        Collections.sort(latencies);
                        long median = latencies.get(latencies.size() / 2);
                        String key = currentDeviceKey(ctx);
                        measuredCache.put(key, (int) median);
                        FLog.d(TAG, "measured latency " + median + " ms on " + key + " (" + latencies + ")");
                    }
                }
            }
        };
        h.post(sampler);
    }

    /** Invalidate cache on device change. */
    public static void invalidateCache() {
        measuredCache.clear();
        FLog.d(TAG, "latency cache invalidated (device change)");
    }

    private static int estimateLatencyMs(@NonNull Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return 35;
            String framesStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            String rateStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
            int frames = framesStr != null ? Integer.parseInt(framesStr) : 256;
            int rate = rateStr != null ? Integer.parseInt(rateStr) : 48000;
            int base = rate > 0 ? (int) ((frames * 1000L) / rate) : 0;
            // Small multiplier per spec (x2 as safety)
            base = Math.max(12, base * 2);
            // BT surcharge when BT is active route
            if (isBluetoothRoute(ctx)) base += BT_SURCHARGE_MS;
            return Math.max(0, Math.min(500, base));
        } catch (Exception e) {
            return 35;
        }
    }

    private static boolean isBluetoothRoute(@NonNull Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return true;
                }
            }
            return am.isBluetoothA2dpOn();
        } catch (Exception e) { return false; }
    }

    @NonNull
    private static String currentDeviceKey(@NonNull Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    // Use type+id as key
                    int type = d.getType();
                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        return type + ":" + d.getId();
                    }
                }
            }
            return lastDeviceKey;
        } catch (Exception e) { return lastDeviceKey; }
    }

    private static void ensureDeviceCallback(@NonNull Context ctx) {
        if (deviceCallbackRegistered) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { invalidateCache(); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { invalidateCache(); }
                }, new Handler(Looper.getMainLooper()));
                deviceCallbackRegistered = true;
            }
        } catch (Exception e) {
            FLog.w(TAG, "registerAudioDeviceCallback failed", e);
        }
    }
}
