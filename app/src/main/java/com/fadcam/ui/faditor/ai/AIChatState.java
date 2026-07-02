package com.fadcam.ui.faditor.ai;

/**
 * Shared state between the editor and the AI chat assistant.
 *
 * <p>This avoids the stale-project bug: the AI modifies the project on disk,
 * but the editor has an in-memory copy. When the editor resumes, it checks
 * {@link #projectModifiedByAI} and reloads from storage.</p>
 */
public class AIChatState {

    /** Set to true when an AI tool modifies the project on disk. */
    public static volatile boolean projectModifiedByAI = false;

    /** Set to the project ID that was modified, so the editor can verify. */
    public static volatile String modifiedProjectId = null;

    /** True while the AI chat activity is running (for the editor to know). */
    public static volatile boolean chatActive = false;

    /** Signal that the project was modified by an AI tool. */
    public static void signalModified(String projectId) {
        projectModifiedByAI = true;
        modifiedProjectId = projectId;
    }

    /** Clear the signal after the editor has reloaded. */
    public static void clearModified() {
        projectModifiedByAI = false;
        modifiedProjectId = null;
    }

    public static String getModifiedProjectId() {
        return modifiedProjectId != null ? modifiedProjectId : "";
    }
}
