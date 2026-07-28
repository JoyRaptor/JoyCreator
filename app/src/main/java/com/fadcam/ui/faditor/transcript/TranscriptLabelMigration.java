package com.fadcam.ui.faditor.transcript;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;

import java.util.HashMap;
import java.util.Map;

/**
 * Renames transcript labels that were baked in before the trade-off naming (2026-07-28).
 *
 * <p>{@code NamedTranscript.label} is PERSISTED, not a display string derived from the model —
 * so renaming {@code ModelType}'s labels only affects future runs and would otherwise leave a
 * project showing "Fast" beside "Fast timing" for the same engine forever. Worse, dedup keys on
 * engine+label, so an old and a new run of the same model would stop collapsing into one another
 * and would stack instead.</p>
 *
 * <p>Deliberately narrow: it renames ONLY the three labels this app itself generated, and only
 * when the engine matches, so a user-authored track that happens to be called "Fast" is left
 * alone. Custom labels ("Imported", "Best", anything hand-named) are never touched.</p>
 */
public final class TranscriptLabelMigration {

    private TranscriptLabelMigration() {}

    /** old label → new label, applied only when {@code engine} also matches. */
    private static final Map<String, String[]> RENAMES = new HashMap<>();

    static {
        //                     old label          new label        required engine
        RENAMES.put("fast",           new String[]{"Fast timing",  "vosk"});
        RENAMES.put("accurate",       new String[]{"Balanced",     "vosk"});
        RENAMES.put("high accuracy",  new String[]{"Best wording", "whisper"});
    }

    /**
     * Apply to every transcript in the project. Returns how many labels changed, so the caller
     * can decide whether the project is dirty and needs saving — renaming nothing must not mark
     * an untouched project as modified.
     */
    public static int migrate(@NonNull FaditorProject project) {
        int n = 0;
        for (Clip c : project.getTimeline().getClips()) {
            for (NamedTranscript nt : c.getTranscripts()) n += rename(nt);
        }
        for (AudioClip ac : project.getTimeline().getAudioClips()) {
            for (NamedTranscript nt : ac.getTranscripts()) n += rename(nt);
        }
        return n;
    }

    private static int rename(@NonNull NamedTranscript nt) {
        String[] to = RENAMES.get(nt.label.trim().toLowerCase());
        if (to == null) return 0;
        if (!to[1].equalsIgnoreCase(nt.engine)) return 0;   // same word, different engine: leave it
        if (to[0].equals(nt.label)) return 0;
        nt.label = to[0];
        return 1;
    }
}
