package com.fadcam.vislab.room;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.export.SequentialFrameReader;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Moving pictures for a look's picture layers (roadmap R12): a video's frame at a moment, from
 * Studio's own forward decoder ({@link SequentialFrameReader}: one linear decode per video, no
 * seek per frame). A jump back (a loop, a scrub) starts that video's decode again.
 *
 * <p>Not thread-safe on its own: one per renderer, used on its GL thread (or under the caller's
 * lock). Frames are BORROWED: valid until the next call for the same video.</p>
 */
public final class Videos {

    private static final String TAG = "VisVideos";
    /**
     * The longest side a frame is converted at: a layer is rarely drawn larger than this, and the
     * conversion runs on the drawing thread (about 8 ms a frame at 720 on the Note 9).
     */
    private static final int MAX = 540;
    /** Readers not asked for this long are let go. */
    private static final long IDLE_MS = 10_000;

    private final Context app;
    private final Map<String, Entry> videos = new HashMap<>();
    private final Map<String, Boolean> kinds = new HashMap<>();

    private static final class Entry {
        @Nullable SequentialFrameReader reader;
        long durationUs = -1;
        long lastUs = Long.MIN_VALUE;
        long usedAt;
        int fails;
    }

    public Videos(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    /** True when {@code ref} names a video (asked of the phone once per reference). */
    public boolean isVideo(@Nullable String ref) {
        if (ref == null || ref.isEmpty()) return false;
        Boolean k = kinds.get(ref);
        if (k != null) return k;
        boolean video;
        try {
            String type = app.getContentResolver().getType(Uri.parse(ref));
            video = type != null ? type.startsWith("video/") : looksLikeVideo(ref);
        } catch (RuntimeException e) {
            video = looksLikeVideo(ref);
        }
        kinds.put(ref, video);
        return video;
    }

    private static boolean looksLikeVideo(@NonNull String ref) {
        String r = ref.toLowerCase(java.util.Locale.ROOT);
        return r.endsWith(".mp4") || r.endsWith(".webm") || r.endsWith(".mkv") || r.endsWith(".3gp")
                || r.endsWith(".mov") || r.endsWith(".m4v");
    }

    /**
     * The frame of video {@code ref} at {@code seconds}, or null when it cannot be read.
     *
     * @param loop true: play round and round over the video's length (the room's clock runs
     *             on past it); false: hold the last frame past the end (a Studio clip, whose time
     *             is already its own)
     */
    @Nullable
    public Bitmap frame(@NonNull String ref, double seconds, boolean loop) {
        Entry e = videos.get(ref);
        if (e == null) {
            e = new Entry();
            videos.put(ref, e);
        }
        e.usedAt = android.os.SystemClock.uptimeMillis();
        if (e.fails > 3) return null;   // a video that will not decode stops costing time
        if (e.durationUs < 0) e.durationUs = durationUs(ref);
        long us = Math.max(0, Math.round(seconds * 1_000_000.0));
        if (e.durationUs > 0) {
            us = loop ? us % e.durationUs : Math.min(us, Math.max(0, e.durationUs - 1000));
        }
        if (e.reader != null && e.lastUs != Long.MIN_VALUE && us < e.lastUs) {
            // Back to the start (a loop) or a jump back: a fresh forward decode.
            e.reader.release();
            e.reader = null;
        }
        if (e.reader == null) e.reader = new SequentialFrameReader(app, Uri.parse(ref));
        e.lastUs = us;
        Bitmap b = e.reader.frameAt(us, MAX, MAX);
        if (b == null) {
            e.fails++;
            e.reader.release();
            e.reader = null;
            Log.w(TAG, "no frame at " + us + " us of " + ref);
        }
        letGoOfIdle(e);
        return b;
    }

    private long durationUs(@NonNull String ref) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(app, Uri.parse(ref));
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) * 1000L : 0;
        } catch (RuntimeException ex) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void letGoOfIdle(@NonNull Entry keep) {
        long now = android.os.SystemClock.uptimeMillis();
        Iterator<Map.Entry<String, Entry>> i = videos.entrySet().iterator();
        while (i.hasNext()) {
            Entry e = i.next().getValue();
            if (e == keep || now - e.usedAt < IDLE_MS) continue;
            if (e.reader != null) e.reader.release();
            i.remove();
        }
    }

    /** Let go of every decoder. */
    public void release() {
        for (Entry e : videos.values()) if (e.reader != null) e.reader.release();
        videos.clear();
    }
}
