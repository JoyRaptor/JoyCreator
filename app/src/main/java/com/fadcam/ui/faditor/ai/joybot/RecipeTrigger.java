package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Set;

/**
 * "Every new recording: transcribe, cut the ums, captions on" (audit BOT-A33). A recipe marked
 * "offer for new recordings" is OFFERED, never run by itself (the owner's rule: an automatic guess
 * keeps a way back): when a Studio project holds a recording this recipe has not been offered for,
 * Joybot asks once, "Run it on this new recording?". Run or Not this time both count as answered,
 * so it is never asked twice for the same recording in the same project. Pure Java, tested in
 * IdeaEngineTest.
 */
public final class RecipeTrigger {
    private RecipeTrigger() { }

    /** What to offer: the recipe's file and the recording's address. */
    public static final class Offer {
        @NonNull public final String recipeFile;
        @NonNull public final String recordingUri;

        Offer(@NonNull String recipeFile, @NonNull String recordingUri) {
            this.recipeFile = recipeFile;
            this.recordingUri = recordingUri;
        }
    }

    /** The key remembering one answered offer. */
    @NonNull
    public static String key(@NonNull String recipeFile, @NonNull String projectId, @NonNull String recordingUri) {
        return recipeFile + "|" + projectId + "|" + recordingUri;
    }

    /**
     * The offer to make now, or null. {@code flagged}: recipe files marked for new recordings, in the
     * order they are listed; {@code recordings}: this project's clips that are recordings, oldest
     * first (the newest is offered first); {@code answered}: keys already answered.
     */
    @Nullable
    public static Offer next(@NonNull List<String> flagged, @NonNull List<String> recordings,
                             @NonNull Set<String> answered, @Nullable String projectId) {
        if (projectId == null || flagged.isEmpty() || recordings.isEmpty()) return null;
        for (int i = recordings.size() - 1; i >= 0; i--) {
            String uri = recordings.get(i);
            for (String file : flagged) {
                if (!answered.contains(key(file, projectId, uri))) return new Offer(file, uri);
            }
        }
        return null;
    }
}
