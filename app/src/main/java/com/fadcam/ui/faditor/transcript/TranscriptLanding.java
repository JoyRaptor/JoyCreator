package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.List;

/**
 * Puts a finished transcription into a project that was loaded AFTER the run started.
 *
 * <p>A long run outlives the editor that started it. That editor's copy of the project is then
 * old: the person may have reopened the project and kept working. Saving the old copy to deliver
 * the words would put every later edit back to how it was when the editor closed (work-loss
 * audit, 2026-11-10). So the words go into a copy read fresh from where the project lives now,
 * and nothing else in it is touched.</p>
 */
public final class TranscriptLanding {

    private TranscriptLanding() {}

    /**
     * @param versionId the transcript version the run was filling
     * @param label     the version's label and
     * @param engine    its engine, used to put the version back if the fresh copy no longer has it
     * @return false when the clip is not in the project any more (it was deleted meanwhile):
     *         nothing was changed, and there is nowhere for the words to go
     */
    public static boolean land(@NonNull FaditorProject project, @NonNull String clipId,
                               @NonNull String versionId, @NonNull String label,
                               @NonNull String engine, @NonNull Transcript words) {
        Timeline tl = project.getTimeline();
        if (tl == null) return false;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip clip = tl.getClip(i);
            if (!clipId.equals(clip.getId())) continue;
            int at = put(clip.getTranscripts(), versionId, words);
            if (at < 0) {
                clip.addTranscript(new NamedTranscript(versionId, label, engine, words));
                at = clip.getTranscripts().size() - 1;
            }
            clip.setActiveTranscriptIndex(at);
            return true;
        }
        for (AudioClip clip : tl.getAudioClips()) {
            if (!clipId.equals(clip.getId())) continue;
            int at = put(clip.getTranscripts(), versionId, words);
            if (at < 0) {
                clip.addTranscript(new NamedTranscript(versionId, label, engine, words));
                at = clip.getTranscripts().size() - 1;
            }
            clip.setActiveTranscriptIndex(at);
            return true;
        }
        return false;
    }

    private static int put(@NonNull List<NamedTranscript> versions, @NonNull String versionId,
                           @NonNull Transcript words) {
        for (int i = 0; i < versions.size(); i++) {
            if (versionId.equals(versions.get(i).id)) {
                versions.get(i).transcript = words;
                return i;
            }
        }
        return -1;
    }
}
