package com.fadcam.ui.faditor.undo;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.transcript.NamedTranscript;

import java.util.ArrayList;
import java.util.List;

/**
 * A clip's transcript versions and caption tracks as they stand, ready to be put back. It is
 * the before or after of ONE undo step for the edits that change them: a version deleted,
 * imported, renamed or switched to, or a caption track added, renamed, deleted, shown, hidden
 * or laid out (owner rule: one press, one step; before 11-24 these had no undo step at all).
 *
 * <p>Restores by value onto the SAME objects: the transcript and binding objects are put back
 * in the lists, and each binding's fields are set from a copy. Other undo steps (caption size,
 * position, style) hold bindings by reference, and swapping in fresh copies would leave them
 * editing objects that are no longer in the project.</p>
 *
 * <p>Pure Java over the two clip models, so it is tested on a plain JVM.</p>
 */
public final class CaptionTrackState {

    private final Object owner;
    private final List<NamedTranscript> transcripts;
    private final List<String> labels = new ArrayList<>();
    private final int activeTranscript;
    private final List<Object> bindings;
    private final List<Object> fields = new ArrayList<>();

    private CaptionTrackState(@NonNull Object owner, @NonNull List<NamedTranscript> transcripts,
                              int activeTranscript, @NonNull List<?> bindings) {
        this.owner = owner;
        this.transcripts = new ArrayList<>(transcripts);
        for (NamedTranscript t : transcripts) labels.add(t.label);
        this.activeTranscript = activeTranscript;
        this.bindings = new ArrayList<>(bindings);
        for (Object b : bindings) {
            fields.add(b instanceof Clip.CaptionBinding ? ((Clip.CaptionBinding) b).copy()
                    : ((AudioClip.CaptionBinding) b).copy());
        }
    }

    @NonNull
    public static CaptionTrackState of(@NonNull Clip c) {
        return new CaptionTrackState(c, c.getTranscripts(), c.getActiveTranscriptIndex(),
                c.getCaptionBindings());
    }

    @NonNull
    public static CaptionTrackState of(@NonNull AudioClip a) {
        return new CaptionTrackState(a, a.getTranscripts(), a.getActiveTranscriptIndex(),
                a.getCaptionBindings());
    }

    /** The clip or audio clip this state belongs to. */
    @NonNull
    public Object owner() {
        return owner;
    }

    /** Put the clip's versions and tracks back exactly as they were when this was taken. */
    public void restore() {
        if (owner instanceof Clip) {
            Clip c = (Clip) owner;
            putBack(c.getTranscripts());
            List<Clip.CaptionBinding> live = c.getCaptionBindings();
            live.clear();
            for (int i = 0; i < bindings.size(); i++) {
                Clip.CaptionBinding b = (Clip.CaptionBinding) bindings.get(i);
                b.setFrom((Clip.CaptionBinding) fields.get(i));
                live.add(b);
            }
            c.setActiveTranscriptIndex(activeTranscript);
            c.syncLegacyFromBindings();
        } else {
            AudioClip a = (AudioClip) owner;
            putBack(a.getTranscripts());
            List<AudioClip.CaptionBinding> live = a.getCaptionBindings();
            live.clear();
            for (int i = 0; i < bindings.size(); i++) {
                AudioClip.CaptionBinding b = (AudioClip.CaptionBinding) bindings.get(i);
                b.setFrom((AudioClip.CaptionBinding) fields.get(i));
                live.add(b);
            }
            a.setActiveTranscriptIndex(activeTranscript);
            a.syncLegacyFromBindings();
        }
    }

    private void putBack(@NonNull List<NamedTranscript> live) {
        live.clear();
        for (int i = 0; i < transcripts.size(); i++) {
            NamedTranscript t = transcripts.get(i);
            t.label = labels.get(i);
            live.add(t);
        }
    }

    /** True when {@code o} holds the same versions and tracks, field for field. */
    public boolean sameAs(@NonNull CaptionTrackState o) {
        if (owner != o.owner || activeTranscript != o.activeTranscript
                || !transcripts.equals(o.transcripts) || !labels.equals(o.labels)
                || !bindings.equals(o.bindings)) {
            return false;
        }
        for (int i = 0; i < fields.size(); i++) {
            if (!key(fields.get(i)).equals(key(o.fields.get(i)))) return false;
        }
        return true;
    }

    @NonNull
    private static String key(@NonNull Object b) {
        if (b instanceof Clip.CaptionBinding) {
            Clip.CaptionBinding c = (Clip.CaptionBinding) b;
            return c.transcriptId + '\u0001' + c.styleId + '\u0001' + c.enabled + '\u0001' + c.centerX
                    + '\u0001' + c.centerY + '\u0001' + c.sizeFraction + '\u0001' + c.label + '\u0001'
                    + c.fadeInMs + '\u0001' + c.fadeOutMs + '\u0001' + c.boxWidthFraction + '\u0001'
                    + c.anchor + '\u0001' + c.justify;
        }
        AudioClip.CaptionBinding c = (AudioClip.CaptionBinding) b;
        return c.transcriptId + '\u0001' + c.styleId + '\u0001' + c.enabled + '\u0001' + c.centerX
                + '\u0001' + c.centerY + '\u0001' + c.sizeFraction + '\u0001' + c.label + '\u0001'
                + c.fadeInMs + '\u0001' + c.fadeOutMs + '\u0001' + c.boxWidthFraction + '\u0001'
                + c.anchor + '\u0001' + c.justify;
    }
}
