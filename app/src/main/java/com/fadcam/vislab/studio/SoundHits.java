package com.fadcam.vislab.studio;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.waveform.PcmSidecar;
import com.fadcam.vislab.audio.HitCounts;
import com.fadcam.vislab.gl.SpriteClock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A source's {@link HitCounts}, found once on a background thread and kept for the session. Only
 * looks with a sprite playing a frame per beat or per kick ask, because hearing a whole song
 * takes seconds. Until they are in, a sprite counts as it plays.
 */
final class SoundHits {

    private static final String TAG = "VisStudio";
    private static final int FFT = 2048;
    private static final double STEP = 1.0 / 60.0;
    private static final int KEEP = 8;

    private static final Map<String, HitCounts> known = new LinkedHashMap<>(8, 0.75f, true);
    private static final Set<String> asked = new HashSet<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vislab-sound-hits");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private SoundHits() {}

    /** The counts of {@code uri}'s sound, or null while they are being found. */
    @Nullable
    static HitCounts of(@NonNull Context c, @NonNull Uri uri) {
        String key = uri.toString();
        synchronized (known) {
            HitCounts h = known.get(key);
            if (h != null || !asked.add(key)) return h;
        }
        Context app = c.getApplicationContext();
        worker.execute(() -> find(app, uri, key));
        return null;
    }

    /** Waits for {@code uri}'s counts (the export's warm-up), up to {@code ms}. */
    static void await(@NonNull Context c, @NonNull Uri uri, long ms) {
        long until = SystemClock.uptimeMillis() + ms;
        while (of(c, uri) == null && SystemClock.uptimeMillis() < until) {
            synchronized (known) {
                if (!asked.contains(uri.toString())) return;   // gave up: no sound copy
            }
            SystemClock.sleep(50);
        }
    }

    static boolean ready(@NonNull Uri uri) {
        synchronized (known) {
            return known.containsKey(uri.toString());
        }
    }

    private static void find(@NonNull Context app, @NonNull Uri uri, @NonNull String key) {
        HitCounts h = null;
        try {
            PcmSidecar.Handle pcm = PcmSidecar.open(app, uri);
            if (pcm != null) {
                long t0 = SystemClock.uptimeMillis();
                h = HitCounts.scan(pcm::frameAt, pcm.frames, PcmSidecar.RATE, FFT, STEP, () -> false);
                com.fadcam.FLog.d("StudioEars", "heard " + pcm.durationMs() + " ms in "
                        + (SystemClock.uptimeMillis() - t0) + " ms");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not count the beats of " + uri, e);
        }
        synchronized (known) {
            asked.remove(key);
            if (h == null) return;
            known.put(key, h);
            while (known.size() > KEEP) {
                java.util.Iterator<String> i = known.keySet().iterator();
                i.next();
                i.remove();
            }
        }
    }

    /** True when this look has a sprite playing a frame per beat or per kick. */
    static boolean wantedBy(@Nullable String lookJson) {
        if (lookJson == null) return false;
        try {
            JsonElement layers = JsonParser.parseString(lookJson).getAsJsonObject().get("layers");
            if (layers == null || !layers.isJsonArray()) return false;
            for (JsonElement e : layers.getAsJsonArray()) {
                JsonObject l = e.getAsJsonObject();
                if (l.has("type") && "sprite".equals(l.get("type").getAsString())
                        && l.has("p") && l.getAsJsonObject("p").has("play")) {
                    int play = Math.round(l.getAsJsonObject("p").get("play").getAsFloat());
                    if (play == SpriteClock.PER_BEAT || play == SpriteClock.PER_KICK) return true;
                }
            }
        } catch (RuntimeException e) {
            return false;   // a look that will not read has no sprites to keep in step
        }
        return false;
    }
}
