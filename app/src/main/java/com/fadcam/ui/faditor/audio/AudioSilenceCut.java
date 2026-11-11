package com.fadcam.ui.faditor.audio;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;

import java.util.ArrayList;
import java.util.List;

/**
 * Clean (cut the silences) for an AUDIO clip: the clip becomes one piece per kept range, laid
 * back to back from where the clip started, so the gaps close up inside the clip's own span.
 * The video version splits a film clip the same way ({@code applySilenceCuts}); audio pieces
 * keep every setting of the original (volume, envelope, fades, bake) because each is a copy.
 *
 * <p>Pure: no timeline, no undo. The editor swaps the original for these pieces as one step.
 */
public final class AudioSilenceCut {

    private AudioSilenceCut() {}

    /**
     * @param keepRanges source-time ranges to keep, in order (SilenceDetector's answer).
     * @return the pieces, or an empty list when there is nothing to cut (one range or none).
     */
    @NonNull
    public static List<AudioClip> pieces(@NonNull AudioClip original,
                                         @NonNull List<long[]> keepRanges) {
        List<AudioClip> out = new ArrayList<>();
        if (keepRanges.size() <= 1) return out;
        long at = original.getOffsetMs();
        for (long[] r : keepRanges) {
            long in = Math.max(r[0], original.getInPointMs());
            long end = Math.min(r[1], original.getOutPointMs());
            if (end <= in) continue;
            AudioClip piece = new AudioClip(original);
            piece.setInPointMs(in);
            piece.setOutPointMs(end);
            piece.setOffsetMs(at);
            at += piece.getTrimmedDurationMs();
            out.add(piece);
        }
        if (out.size() <= 1) out.clear();
        return out;
    }
}
