package com.fadcam.vislab.studio;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.compositor.LayerPreviewController;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.waveform.PcmSidecar;
import com.fadcam.vislab.room.VisLabSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Getting an export ready for its VisLab looks, on the export's warm-up thread (ExportService),
 * before the first frame: the person's own looks read from their folder, and the sound of every
 * VisLab visualizer copied to Studio's PCM cache ({@link PcmSidecar}). Without it an export
 * started cold draws its first seconds deaf (the copy is made in the background), or draws the
 * classic style for one of the person's own looks not read yet.
 */
public final class StudioVisExport {

    private static final String TAG = "VisStudio";
    private static final long LOAD_WAIT_MS = 5_000;
    private static final long BAKE_WAIT_MS = 10 * 60_000;

    private StudioVisExport() {}

    /** Main thread: does this timeline need {@link #warm} before it exports? */
    public static boolean needsWarm(@NonNull Context c, @NonNull Timeline t) {
        List<Uri> sounds = sounds(t);
        if (sounds == null) return false;   // no VisLab look on any visualizer
        if (!VisLabSession.get(c).isLoaded()) return true;
        for (Uri u : sounds) if (!PcmSidecar.isBaked(c, u)) return true;
        return false;
    }

    /** Background thread: wait for the person's looks, then copy each sound. Blocks. */
    public static void warm(@NonNull Context c, @NonNull Timeline t) {
        List<Uri> sounds = sounds(t);
        if (sounds == null) return;
        CountDownLatch loaded = new CountDownLatch(1);
        VisLabSession.get(c).whenLoaded(loaded::countDown);
        await(loaded, LOAD_WAIT_MS, "the person's looks");
        for (Uri u : sounds) {
            if (PcmSidecar.isBaked(c, u)) continue;
            CountDownLatch done = new CountDownLatch(1);
            PcmSidecar.bakeAsync(c, u, new PcmSidecar.BakeCallback() {
                @Override
                public void onBaked(@NonNull File file) {
                    done.countDown();
                }

                @Override
                public void onFailed(@NonNull String message) {
                    Log.w(TAG, "No sound copy for " + u + ": " + message + " (its look draws deaf)");
                    done.countDown();
                }
            });
            await(done, BAKE_WAIT_MS, "the sound of " + u.getLastPathSegment());
        }
    }

    /** The source a visualizer hears, by clip id (a video clip or an audio clip), or null. */
    @Nullable
    public static Uri sourceUri(@NonNull Timeline t, @Nullable String clipId) {
        if (clipId == null) return null;
        for (Clip c : t.getClips()) if (clipId.equals(c.getId())) return c.getSourceUri();
        for (AudioClip a : t.getAudioClips()) if (clipId.equals(a.getId())) return a.getSourceUri();
        return null;
    }

    /** The sources VisLab visualizers hear (each once), or null when none has a VisLab look. */
    @Nullable
    private static List<Uri> sounds(@NonNull Timeline t) {
        List<Uri> out = null;
        for (WaveformOverlayInstance o : LayerPreviewController.visibleWaveformOverlays(t)) {
            if (!o.usesVisLab()) continue;
            if (out == null) out = new ArrayList<>();
            Uri u = sourceUri(t, o.getAudioSourceRef());
            if (u != null && !out.contains(u)) out.add(u);
        }
        return out;
    }

    private static void await(@NonNull CountDownLatch l, long ms, @NonNull String what) {
        try {
            if (!l.await(ms, TimeUnit.MILLISECONDS)) Log.w(TAG, "Export went on without " + what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
