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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a Studio effect hears (roadmap R14): the music at a moment of the timeline, read by the
 * same analysis VisLab looks hear in Studio ({@link SoundClock}). The music is a song or sound on
 * an audio track playing then (a music video's song usually sits there), else the video clip's
 * own sound.
 *
 * <p>One clock per {@code owner} and source, because a clock steps forward through its source and
 * the preview and an export walk the timeline separately, on different threads.</p>
 */
public final class StudioEars {

    private static final int MAX_CLOCKS = 8;
    private static final Map<String, SoundClock> clocks = new LinkedHashMap<>(8, 0.75f, true);

    private StudioEars() {}

    /**
     * The music at {@code editorMs}, or null when nothing sounds there (an image clip, a gap).
     * Silence until Studio's sound copy of the source exists ({@link StudioVisExport#warm}).
     *
     * @param owner who walks the timeline: "preview", or one id per export effect
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
        SoundClock k = clocks.get(key);
        if (k == null) {
            k = new SoundClock(c, uri);
            clocks.put(key, k);
            Iterator<Map.Entry<String, SoundClock>> it = clocks.entrySet().iterator();
            while (clocks.size() > MAX_CLOCKS && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        Features f = k.at(sourceMs / 1000.0);
        // Read now: the clock reuses its Features object for the next moment.
        final float level = f.level, bass = f.bass, kick = f.kick, snare = f.snare, hat = f.hat, beat = f.beat;
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

    /** The sources music-linked effects can hear (for the export's warm-up), or empty. */
    @NonNull
    static List<Uri> sources(@NonNull Timeline t) {
        List<Uri> out = new ArrayList<>();
        boolean any = false;
        for (AdjustmentLayer al : t.getAdjustmentLayers()) if (al.getFx().hearsMusic()) any = true;
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
