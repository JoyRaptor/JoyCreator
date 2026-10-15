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
        // A change the live editor already adopted (and recorded as an undo step) must not make
        // it reload from disk on its next resume and record a second, empty step.
        if (liveEditorFor(projectId) != null) return;
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

    // ── The live editor: where Joybot writes while the editor is on screen ──────
    //
    // Joybot's long jobs (transcribing four clips) now outlive the chat. If the editor is on screen
    // when one finishes, writing the project FILE would be overwritten by the editor's next save,
    // and reading the file at the start of a long job would undo whatever the person edited
    // meanwhile. So while the editor is resumed it registers here, and Joybot reads from and writes
    // into the editor's open project instead (JoybotStorage). Main thread only for the calls.

    public interface LiveEditor {
        /** {@code ProjectStorage.toJson} of the open project. Called on the main thread. */
        @androidx.annotation.Nullable String snapshotJson();

        /**
         * Take this project as the new state: one undo step labelled {@code label}; a second adopt
         * carrying the same {@code request} merges into that step (one press, one step).
         * Called on the main thread. True when adopted.
         */
        boolean adopt(@androidx.annotation.NonNull String json, @androidx.annotation.NonNull String label,
                      @androidx.annotation.NonNull String request);
    }

    private static volatile String liveProjectId;
    private static volatile LiveEditor liveEditor;

    public static void setLiveEditor(String projectId, LiveEditor editor) {
        liveProjectId = projectId;
        liveEditor = editor;
    }

    public static void clearLiveEditor(LiveEditor editor) {
        if (liveEditor == editor) { liveEditor = null; liveProjectId = null; }
    }

    /** The editor that has this project open on screen right now, or null. */
    public static LiveEditor liveEditorFor(String projectId) {
        LiveEditor e = liveEditor;
        return e != null && projectId != null && projectId.equals(liveProjectId) ? e : null;
    }

    // ── Pictures to put ON TOP as a new layer (the editor's "Image as new layer" path) ──
    private static final java.util.Map<String, java.util.List<android.net.Uri>> LAYER_INSERTS =
            new java.util.HashMap<>();

    public static synchronized void putLayerInsert(String projectId, android.net.Uri uri) {
        java.util.List<android.net.Uri> l = LAYER_INSERTS.get(projectId);
        if (l == null) LAYER_INSERTS.put(projectId, l = new java.util.ArrayList<>());
        l.add(uri);
    }

    public static synchronized java.util.List<android.net.Uri> takeLayerInserts(String projectId) {
        java.util.List<android.net.Uri> l = LAYER_INSERTS.remove(projectId);
        return l == null ? new java.util.ArrayList<>() : l;
    }

    /** Label + request token for writes made while the editor is live (set by the job runner). */
    public static volatile String liveWriteLabel = "Joybot";
    public static volatile String liveWriteRequest = "";
}
