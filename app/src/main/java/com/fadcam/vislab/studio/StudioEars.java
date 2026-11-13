package com.fadcam.vislab.studio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.fx.FxMusic;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.vislab.audio.Features;

import java.util.ArrayList;
import java.util.List;

/**
 * What a Studio effect hears (roadmap R14): the music at a moment of the timeline, read by the
 * same analysis VisLab looks hear in Studio ({@link SoundClock}). The music is a song or sound on
 * an audio track playing then (a music video's song usually sits there), else the video clip's
 * own sound.
 *
 * <p>One clock per {@code owner} and source, because a clock steps forward through its source and
 * the preview and each part of an export walk the timeline separately, on different threads. The
 * effects inside one walk share theirs ({@link EarShelf}).</p>
 */
public final class StudioEars {

    private static final int MAX_CLOCKS = 8;
    private static final EarShelf<SoundClock> shelf = new EarShelf<>(MAX_CLOCKS);

    private StudioEars() {}

    /**
     * The music at {@code editorMs}, or null when nothing sounds there (an image clip, a gap).
     * Silence until Studio's sound copy of the source exists ({@link StudioVisExport#warm}).
     *
     * @param owner who walks the timeline: "preview", or one id per export part
     */
    @Nullable
    public static synchronized FxMusic.Ears at(@NonNull Context c, @NonNull Timeline t,
                                               long editorMs, @NonNull String owner) {
        Uri uri = null;
        long sourceMs = -1;
        for (AudioClip a : t.getAudioClips()) {
            if (a.isMuted() || a.getSourceUri() == null) continue;
            if (editorMs >= a.getOffsetMs() && editorMs < a.getEndOnTimelineMs()) {
                uri = a.getSourceUri();
                sourceMs = a.getInPointMs() + (editorMs - a.getOffsetMs());
                break;
            }
        }
        if (uri == null) {
            long cursor = 0;
            for (Clip clip : t.getClips()) {
                long dur = clip.hasLoopExtension() ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs();
                if (editorMs >= cursor && editorMs < cursor + dur) {
                    if (!clip.isImageClip() && clip.getSourceUri() != null) {
                        uri = clip.getSourceUri();
                        sourceMs = clip.mapToSourceMs(editorMs - cursor);
                    }
                    break;
                }
                cursor += dur;
            }
        }
        if (uri == null || sourceMs < 0) return null;
        String key = owner + '|' + uri;
        final Uri source = uri;
        final double sourceSec = sourceMs / 1000.0;
        float[] heard = shelf.heard(key, sourceMs, () -> {
            com.fadcam.FLog.d("StudioEars", "listening for " + owner + " at " + editorMs + " ms");
            return new SoundClock(c, source);
        }, k -> listen(k, sourceSec, owner, editorMs));
        final float level = heard[0], bass = heard[1], kick = heard[2], snare = heard[3], hat = heard[4], beat = heard[5];
        return signal -> {
            switch (signal) {
                case "level": return level;
                case "bass": return bass;
                case "kick": return kick;
                case "snare": return snare;
                case "hat": return hat;
                case "beat": return beat;
                default: return 0f;
            }
        };
    }

    /** The six signals at this moment, in {@link #at}'s order. Remembered once the sound is read. */
    @NonNull
    private static EarShelf.Heard listen(@NonNull SoundClock k, double sourceSec, @NonNull String owner,
                                         long editorMs) {
        Features f = k.at(sourceSec);
        // Read now: the clock reuses its Features object for the next moment.
        float[] heard = {f.level, f.bass, f.kick, f.snare, f.hat, f.beat};
        if (k.firstSound(f.level)) {
            com.fadcam.FLog.d("StudioEars", "first sound for " + owner + " at " + editorMs + " ms (level "
                    + f.level + ", kick " + f.kick + ")");
        }
        return new EarShelf.Heard(heard, k.listening());
    }

    /** The sources music-linked effects can hear (for the export's warm-up), or empty. */
    @NonNull
    static List<Uri> sources(@NonNull Timeline t) {
        List<Uri> out = new ArrayList<>();
        boolean any = false;
        for (AdjustmentLayer al : t.getAdjustmentLayers()) if (al.getFx().hearsMusic()) any = true;
        // A clip's own effects (the Effects drawer of a master clip) can follow the music too.
        for (Clip clip : t.getClips()) if (clip.getFx() != null && clip.getFx().hearsMusic()) any = true;
        if (!any) return out;
        for (AudioClip a : t.getAudioClips()) {
            if (!a.isMuted() && a.getSourceUri() != null && !out.contains(a.getSourceUri())) out.add(a.getSourceUri());
        }
        for (Clip clip : t.getClips()) {
            if (!clip.isImageClip() && clip.getSourceUri() != null && !out.contains(clip.getSourceUri())) {
                out.add(clip.getSourceUri());
            }
        }
        return out;
    }
}
