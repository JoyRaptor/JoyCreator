package com.fadcam.ui.faditor.audio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.util.AudioExtractor;

/**
 * An audio clip's STORED waveform (the saved {@code int[]} the timeline draws while its richer
 * pictures load, and always when zoomed far out) belongs to the file the clip plays. So when a
 * bake, an undo or a revert points the clip at another file, the stored waveform is read again
 * from that file. Before 2026-11-10 nothing did this, and a fixed clip kept the unfixed picture
 * for good (STU-4).
 *
 * <p>Reads are disk-cached per file by {@link AudioExtractor}, so swapping back to a file seen
 * before (undo, redo, a repeated Fix) answers at once. One shared reader thread for the app.
 */
public final class SourceWaveform {

    @Nullable private static AudioExtractor reader;

    private SourceWaveform() {}

    /**
     * Re-read {@code clip}'s stored waveform from its current source. A late answer for a file
     * the clip no longer plays (two swaps in quick succession) is dropped.
     *
     * @param onReady runs on the main thread once the new waveform is stored (repaint here).
     */
    public static void refresh(@NonNull Context context, @NonNull AudioClip clip,
                               @Nullable Runnable onReady) {
        final Uri uri = clip.getSourceUri();
        if (uri == null) return;
        reader(context).generateWaveform(uri, new AudioExtractor.WaveformCallback() {
            @Override
            public void onWaveformReady(@NonNull int[] waveform) {
                if (!uri.equals(clip.getSourceUri())) return;
                clip.setWaveform(waveform);
                if (onReady != null) onReady.run();
            }

            @Override
            public void onError(@NonNull Exception error) {
                // Keep the old picture: a wrong-but-present waveform beats an empty row.
            }
        });
    }

    @NonNull
    private static synchronized AudioExtractor reader(@NonNull Context context) {
        if (reader == null) reader = new AudioExtractor(context.getApplicationContext());
        return reader;
    }
}
