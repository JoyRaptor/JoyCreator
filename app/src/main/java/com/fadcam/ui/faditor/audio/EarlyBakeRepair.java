package com.fadcam.ui.faditor.audio;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AudioClip;

import java.io.File;

/**
 * Repairs a clip that plays an OLD-STYLE bake (made before 2026-11-10, see {@link BakedAudioCache}
 * "Timing"). Those files started at 0 instead of keeping the source's clock, so on a trimmed or
 * split clip the processed sound played early by the clip's in-point (STU-B4).
 *
 * <p>Detected by length, not by name: an aligned bake runs to the clip's out-point, an old one
 * only for the span. Clips whose in-point is (near) 0 are untouched, since an old bake of those is
 * timed right. The repair points the clip back at its original (correct timing at once, sound not
 * yet processed), bakes the same chain again in the new style, and points the clip at that. No
 * undo step: nothing the person chose changes, only the timing is put right.
 */
public final class EarlyBakeRepair {

    private static final String TAG = "EarlyBakeRepair";
    /** Below this in-point an old bake is timed right anyway (AAC priming is ~20 ms). */
    private static final long IN_POINT_SLACK_MS = 60;

    private EarlyBakeRepair() {}

    /** Called on the main thread once per clip as each step lands. */
    public interface Listener {
        void onClipChanged(@NonNull AudioClip clip);
    }

    /**
     * Whether {@code clip} plays an early (old-style) bake. Reads the file's length, so call it
     * off the main thread.
     */
    public static boolean isEarly(@NonNull AudioClip clip) {
        if (!clip.isBakedSource() || clip.getBakedFromFile() == null) return false;
        if (clip.getInPointMs() <= IN_POINT_SLACK_MS) return false;
        long dur = durationMs(new File(clip.getBakedFromFile()));
        // An aligned bake reaches the out-point; an old one stops at (out - in).
        return dur > 0 && dur < clip.getOutPointMs() - IN_POINT_SLACK_MS;
    }

    /** The chain a bake was made with, from its file name ("fix-...", "afftdn-..."), or null. */
    @Nullable
    static String chainOf(@NonNull File bake) {
        String n = bake.getName();
        for (String c : new String[]{BakedAudioCache.CHAIN_FIX, BakedAudioCache.CHAIN_DENOISE,
                BakedAudioCache.CHAIN_LOUDNORM}) {
            if (n.startsWith(c + "-")) return c;
        }
        return null;
    }

    /**
     * Repair one clip found {@link #isEarly}. Runs the bake on ffmpeg-kit's worker; {@code listener}
     * hears each change on the main thread (the swap back to the original, then the new bake).
     */
    public static void repair(@NonNull Context context, @NonNull File projectDir,
                              @NonNull AudioClip clip, @NonNull Listener listener) {
        final String original = clip.getBakedFromUri();
        final String oldBake = clip.getBakedFromFile();
        if (original == null || oldBake == null) return;
        final String chain = chainOf(new File(oldBake));
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            if (!oldBake.equals(clip.getBakedFromFile())) return; // changed meanwhile
            clip.setSourceUri(Uri.parse(original));
            clip.setBakedFrom(null, null);
            FLog.i(TAG, "Early bake on clip " + clip.getId() + ": original restored ("
                    + new File(oldBake).getName() + ")");
            listener.onClipChanged(clip);
            if (chain == null) return; // unknown recipe: the original, correctly timed, stays
            BakedAudioCache.Request req;
            try {
                req = new BakedAudioCache.Request(Uri.parse(original),
                        clip.getInPointMs(), clip.getOutPointMs(), chain);
            } catch (IllegalArgumentException e) {
                return;
            }
            new BakedAudioCache(context).bakeAsync(projectDir, req, null, (result, error) ->
                    main.post(() -> {
                        if (result == null) {
                            FLog.w(TAG, "Re-bake failed, original kept: " + error);
                            return;
                        }
                        // Only if nothing else touched the clip while it baked.
                        if (clip.isBakedSource() || !original.equals(clip.getSourceUri().toString())) return;
                        clip.setSourceUri(Uri.fromFile(result.bakedFile));
                        clip.setBakedFrom(original, result.bakedFile.getAbsolutePath());
                        FLog.i(TAG, "Re-baked in time: " + result.bakedFile.getName());
                        listener.onClipChanged(clip);
                    }));
        });
    }

    private static long durationMs(@NonNull File f) {
        if (!f.isFile()) return -1;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }
}
