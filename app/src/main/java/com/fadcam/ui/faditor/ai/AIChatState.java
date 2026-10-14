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

    /**
     * What the AI did, in the user's words — used as the label of the single undo step the
     * editor records for the AI's work. Null/empty falls back to a generic label.
     */
    public static volatile String modifiedDescription = null;

    /** Signal that the project was modified by an AI tool. */
    public static void signalModified(String projectId) {
        signalModified(projectId, null);
    }

    /**
     * @param description short human-readable summary of the change (an edit script's own
     *                    {@code description} is exactly this), shown in the undo history
     */
    public static void signalModified(String projectId, String description) {
        projectModifiedByAI = true;
        modifiedProjectId = projectId;
        if (description != null && !description.trim().isEmpty()) {
            modifiedDescription = description.trim();
        }
    }

    /** Clear the signal after the editor has reloaded. */
    public static void clearModified() {
        projectModifiedByAI = false;
        modifiedProjectId = null;
        modifiedDescription = null;
    }

    public static String getModifiedProjectId() {
        return modifiedProjectId != null ? modifiedProjectId : "";
    }

    // ── Joybot's steps: one undo step per request ────────────────────────────
    //
    // The editor saves before the chat opens and reloads when it comes back, and it used to record
    // everything Joybot did in between as ONE undo step. Owner's rule is one press, one step: each
    // request (a sent message or a tapped idea) is its own step, labelled in plain words. The chat
    // snapshots the project from disk before a request's first change and parks it here; the editor
    // replays the list as that many undo entries. The chat's own Undo button pops the newest.

    /** One Joybot request that changed the project. */
    public static final class Step {
        public final String projectId;
        public final String label;
        /** {@code ProjectStorage.toJson} of the project before this request. */
        public final String before;

        Step(String projectId, String label, String before) {
            this.projectId = projectId;
            this.label = label;
            this.before = before;
        }
    }

    private static final java.util.List<Step> STEPS = new java.util.ArrayList<>();
    /** Project ids whose Joybot steps were all undone in the chat: reload, record nothing. */
    private static final java.util.Set<String> UNDONE_TO_START = new java.util.HashSet<>();

    public static synchronized void pushStep(String projectId, String label, String before) {
        STEPS.add(new Step(projectId, label, before));
        UNDONE_TO_START.remove(projectId);
    }

    /** The newest step for this project, removed; null when there is none. */
    public static synchronized Step popStep(String projectId) {
        for (int i = STEPS.size() - 1; i >= 0; i--) {
            if (STEPS.get(i).projectId.equals(projectId)) {
                Step s = STEPS.remove(i);
                if (stepCount(projectId) == 0) UNDONE_TO_START.add(projectId);
                return s;
            }
        }
        return null;
    }

    /** The newest step for this project, left in place; null when there is none. */
    public static synchronized Step peekStep(String projectId) {
        for (int i = STEPS.size() - 1; i >= 0; i--) {
            if (STEPS.get(i).projectId.equals(projectId)) return STEPS.get(i);
        }
        return null;
    }

    public static synchronized int stepCount(String projectId) {
        int n = 0;
        for (Step s : STEPS) if (s.projectId.equals(projectId)) n++;
        return n;
    }

    /** This project's steps, oldest first, removed from the list (the editor took them). */
    public static synchronized java.util.List<Step> drainSteps(String projectId) {
        java.util.List<Step> out = new java.util.ArrayList<>();
        for (java.util.Iterator<Step> it = STEPS.iterator(); it.hasNext(); ) {
            Step s = it.next();
            if (s.projectId.equals(projectId)) { out.add(s); it.remove(); }
        }
        return out;
    }

    /** True once if every Joybot step for this project was undone in the chat. */
    public static synchronized boolean takeUndoneToStart(String projectId) {
        return UNDONE_TO_START.remove(projectId);
    }

    /** "Show me": the clip the editor should select when it comes back. */
    public static volatile String showClipId = null;
}
