package com.fadcam.ui.faditor.ai.joybot;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Which conversation an interrupted Joybot job belongs to, so "Carry on" works where the job
 * ran (audit BOT-A16, card BOT-3). Until 11-13 a job was checkpointed only when a Studio project
 * was open ({@code if (ask.projectId == null) return;}), so a VisLab, JoyBrush or TextLab request
 * killed mid-way was simply lost. The key is the agent's own conversation key
 * ({@link JoybotAgent#conversationKey}): "project:&lt;id&gt;" or "room:&lt;ROOM&gt;". Pure Java,
 * tested in IdeaEngineTest.
 */
public final class JobKey {
    private JobKey() { }

    /** Rooms whose work lives outside a Studio project, so a job there has no project id. */
    public static boolean roomHasOwnWork(@Nullable String roomName) {
        return "VISLAB".equals(roomName) || "JOYBRUSH".equals(roomName) || "TEXTLAB".equals(roomName);
    }

    /** A job worth carrying on after the app is killed: one with a project, or in a working room. */
    public static boolean resumable(@Nullable String projectId, @Nullable String roomName) {
        return projectId != null || roomHasOwnWork(roomName);
    }

    /** The same spelling as {@link JoybotAgent#conversationKey}. */
    @NonNull
    public static String of(@Nullable String projectId, @NonNull String roomName) {
        return projectId != null ? "project:" + projectId : "room:" + roomName;
    }

    /**
     * The key of a saved job file. Files written before 11-13 have only a project id: they were
     * all Studio jobs. Null when the file names neither (nothing to carry on).
     */
    @Nullable
    public static String fromSaved(@Nullable String key, @Nullable String projectId) {
        if (key != null && (key.startsWith("project:") || key.startsWith("room:")) && key.length() > 5) return key;
        if (projectId != null && !projectId.isEmpty()) return "project:" + projectId;
        return null;
    }

    /** The room a saved key belongs to, by name ("STUDIO" for a project's job). */
    @NonNull
    public static String roomOf(@NonNull String key) {
        return key.startsWith("room:") ? key.substring(5) : "STUDIO";
    }

    /** What "Carry on" tells the model to look at first, so nothing already done is done twice. */
    @NonNull
    public static String checkFirst(@NonNull String roomName) {
        switch (roomName) {
            case "VISLAB": return "Read the look first (vislab tools): some of it is already done, so don't repeat a change.";
            case "JOYBRUSH": return "Describe the drawing first (joybrush tools): some of it is already done, so don't repeat a change.";
            case "TEXTLAB": return "Read the look first (textlab tools): some of it is already done, so don't repeat a change.";
            default: return "Check the project first — some of it is already done.";
        }
    }
}
