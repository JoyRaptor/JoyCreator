package com.fadcam.ui.faditor.undo;

import com.fadcam.Log;
import com.fadcam.FLog;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Manages undo/redo history for the Faditor editor.
 *
 * <p>Maintains two stacks: undo and redo. When a new action is performed,
 * it is pushed onto the undo stack and the redo stack is cleared (any
 * previously undone branch is discarded).</p>
 *
 * <p>Supports both action-based undo/redo (in-session, precise) and
 * snapshot-based undo/redo (persistent across sessions). When a project is
 * saved, snapshots of the project state are stored alongside each action
 * description. On reload, snapshot-only entries enable undo/redo without
 * the original {@link EditAction} objects.</p>
 *
 * <p>The history has a configurable maximum size to bound memory usage.</p>
 */
public class UndoManager {

    private static final String TAG = "UndoManager";
    // Each persistent snapshot is a FULL project-JSON string. For a large project
    // (many transcripts) that is multiple MB *each*; keeping 200 of them in memory
    // was hundreds of MB of heap → GC thrashing/OOM. In-session undo uses precise
    // per-action undo (cheap), so this cap only limits how far back undo reaches.
    private static final int DEFAULT_MAX_HISTORY = 50;
    // Minimum spacing between full-project snapshot captures. In-session undo never
    // needs the snapshot (it replays the action); the snapshot only powers
    // cross-session undo. Capturing one on EVERY edit serialized multi-MB JSON on
    // the UI thread per trim/transition/move — the dominant per-edit stall. Spacing
    // captures out keeps cross-session undo functional without the per-edit cost.
    private static final long SNAPSHOT_MIN_INTERVAL_MS = 1500;
    private long lastSnapshotElapsedMs = -SNAPSHOT_MIN_INTERVAL_MS;
    // Total chars of retained project-JSON snapshots across the undo stack.
    // maxHistory alone is not enough of a cap: a large project serializes to
    // multiple MB per snapshot, so 50 snapshots is hundreds of MB of heap and
    // the app OOMs when the history is persisted. Entries past this budget keep
    // their in-session undo (action replay) but lose the snapshot; snapshot-only
    // entries past it are evicted entirely.
    private static final long MAX_SNAPSHOT_CHARS = 24_000_000L;

    @NonNull
    private final Deque<HistoryEntry> undoStack;

    @NonNull
    private final Deque<HistoryEntry> redoStack;

    private final int maxHistory;

    /** Listener for undo/redo state changes (stack empty/non-empty). */
    @Nullable
    private OnStateChangedListener listener;

    /** Provider for capturing & restoring project snapshots (set by editor). */
    @Nullable
    private SnapshotRestorer snapshotRestorer;

    // ── Inner types ──────────────────────────────────────────────────

    /**
     * A single entry in the undo/redo history.
     *
     * <p>In-session entries have both {@code action} and {@code snapshotBefore}.
     * Entries loaded from disk have only {@code description} and {@code snapshotBefore}
     * (action is null). {@code snapshotAfter} is populated lazily during undo
     * to enable redo for snapshot-only entries.</p>
     */
    public static class HistoryEntry {
        /** The reversible action (null for entries loaded from disk). */
        @Nullable
        EditAction action;

        /** Human-readable description (e.g. "Rotated 90°"). */
        @NonNull
        final String description;

        /** Project JSON snapshot captured BEFORE this action was applied. */
        @Nullable
        String snapshotBefore;

        /**
         * Project JSON snapshot captured AFTER this action was applied.
         * Set lazily during undo (captures current state before reverting).
         */
        @Nullable
        String snapshotAfter;

        /**
         * True when this step was made by the AI assistant rather than by the user,
         * so the history list can colour it differently — "I did that, the AI did the
         * other thing" is the thing you want to see while scanning.
         */
        final boolean aiOrigin;

        HistoryEntry(@Nullable EditAction action,
                     @NonNull String description,
                     @Nullable String snapshotBefore) {
            this(action, description, snapshotBefore, false);
        }

        HistoryEntry(@Nullable EditAction action,
                     @NonNull String description,
                     @Nullable String snapshotBefore,
                     boolean aiOrigin) {
            this.action = action;
            this.description = description;
            this.snapshotBefore = snapshotBefore;
            this.aiOrigin = aiOrigin;
        }

        public @NonNull String getDescription() { return description; }
        public @Nullable String getSnapshotBefore() { return snapshotBefore; }
        public boolean isAiOrigin() { return aiOrigin; }
    }

    /**
     * Callback to notify when undo/redo availability changes.
     */
    public interface OnStateChangedListener {
        /**
         * Called whenever the undo or redo stack changes.
         *
         * @param canUndo whether there are actions to undo
         * @param canRedo whether there are actions to redo
         */
        void onUndoRedoStateChanged(boolean canUndo, boolean canRedo);
    }

    /**
     * Interface for capturing and restoring project state snapshots.
     * Implemented by the editor activity.
     */
    public interface SnapshotRestorer {
        /**
         * Capture the current project state as a JSON string.
         *
         * @return project JSON, or null if capture is unavailable
         */
        @Nullable
        String captureSnapshot();

        /**
         * Restore the project from a JSON snapshot.
         * Must replace the project object and refresh all UI.
         *
         * @param projectJson the JSON string to restore from
         */
        void restoreFromSnapshot(@NonNull String projectJson);
    }

    // ── Construction ─────────────────────────────────────────────────

    public UndoManager() {
        this(DEFAULT_MAX_HISTORY);
    }

    public UndoManager(int maxHistory) {
        this.maxHistory = maxHistory;
        this.undoStack = new ArrayDeque<>();
        this.redoStack = new ArrayDeque<>();
    }

    // ── Configuration ────────────────────────────────────────────────

    /**
     * Set the listener for state changes.
     */
    public void setOnStateChangedListener(@Nullable OnStateChangedListener listener) {
        this.listener = listener;
    }

    /**
     * Set the snapshot restorer (provided by the editor).
     * Must be set before any undo/redo operations involving snapshots.
     */
    public void setSnapshotRestorer(@Nullable SnapshotRestorer restorer) {
        this.snapshotRestorer = restorer;
    }

    // ── Recording ────────────────────────────────────────────────────

    /**
     * Record a new action. The action should already have been applied
     * to the model; this method just records it for undo.
     *
     * <p>Automatically captures a project snapshot (if a restorer is set)
     * to enable persistent undo across sessions.</p>
     *
     * <p>Clears the redo stack (new action forks a new branch).</p>
     *
     * @param action the action that was just performed
     */
    public void recordAction(@NonNull EditAction action) {
        // Capture a full-project snapshot only if enough time has passed since the
        // last one (see SNAPSHOT_MIN_INTERVAL_MS). Skipping it just means this
        // particular step won't be undoable after an app restart; in-session undo
        // still works precisely via action.undo(). This avoids serializing the
        // entire (possibly multi-MB) project on the UI thread for every edit.
        String snapshot = null;
        if (snapshotRestorer != null) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastSnapshotElapsedMs >= SNAPSHOT_MIN_INTERVAL_MS) {
                snapshot = snapshotRestorer.captureSnapshot();
                lastSnapshotElapsedMs = now;
            }
        }

        HistoryEntry entry = new HistoryEntry(action, action.getDescription(), snapshot);
        undoStack.push(entry);
        redoStack.clear();

        // Trim history if exceeding max
        while (undoStack.size() > maxHistory) {
            ((ArrayDeque<HistoryEntry>) undoStack).removeLast();
        }
        enforceSnapshotBudget();

        FLog.d(TAG, "Recorded: " + action.getDescription()
                + " (undo=" + undoStack.size() + ", redo=0"
                + ", snapshot=" + (snapshot != null) + ")");
        notifyListener();
    }

    /**
     * Walk the undo stack newest-first and enforce {@link #MAX_SNAPSHOT_CHARS}
     * across retained snapshots. Over-budget in-session entries just drop their
     * snapshot (action replay still undoes them); over-budget snapshot-only
     * entries (loaded from disk) are removed — without a snapshot they are
     * un-undoable dead weight.
     */
    private void enforceSnapshotBudget() {
        long total = 0;
        // ArrayDeque push() = addFirst(), so iteration order is newest → oldest.
        java.util.Iterator<HistoryEntry> it = undoStack.iterator();
        while (it.hasNext()) {
            HistoryEntry e = it.next();
            long len = (e.snapshotBefore != null ? e.snapshotBefore.length() : 0)
                    + (e.snapshotAfter != null ? e.snapshotAfter.length() : 0);
            if (len == 0) continue;
            if (total + len > MAX_SNAPSHOT_CHARS && total > 0) {
                if (e.action != null) {
                    e.snapshotBefore = null;
                    e.snapshotAfter = null;
                } else {
                    it.remove();
                }
            } else {
                total += len;
            }
        }
    }

    /**
     * Free the in-memory full-project JSON snapshots held by entries that can be
     * undone via their {@link EditAction} anyway (in-session entries). Call this
     * before a memory-heavy operation (e.g. export) to reclaim potentially
     * hundreds of MB of heap. In-session undo/redo is unaffected (it replays the
     * action); only this app session's cross-session-undo persistence is reduced.
     * Snapshot-only entries loaded from disk keep their snapshots.
     */
    public void releaseSnapshotMemory() {
        int freed = 0;
        for (HistoryEntry e : undoStack) {
            if (e.action != null) {
                if (e.snapshotBefore != null) { e.snapshotBefore = null; freed++; }
                if (e.snapshotAfter != null) { e.snapshotAfter = null; freed++; }
            }
        }
        for (HistoryEntry e : redoStack) {
            if (e.action != null) {
                if (e.snapshotBefore != null) { e.snapshotBefore = null; freed++; }
                if (e.snapshotAfter != null) { e.snapshotAfter = null; freed++; }
            }
        }
        FLog.d(TAG, "releaseSnapshotMemory: cleared " + freed + " in-session snapshots");
    }

    // ── Undo / Redo ──────────────────────────────────────────────────

    /**
     * Undo the most recent action.
     *
     * <p>For in-session entries (action != null), calls {@link EditAction#undo()}.
     * For snapshot-only entries (loaded from disk), restores via
     * {@link SnapshotRestorer#restoreFromSnapshot(String)}.</p>
     *
     * @return true if an action was undone, false if nothing to undo
     */
    public boolean undo() {
        if (undoStack.isEmpty()) {
            FLog.d(TAG, "Nothing to undo");
            return false;
        }

        HistoryEntry entry = undoStack.pop();

        // Capture current state as "after" (needed for redo) ONLY for snapshot-only
        // entries — in-session entries redo via action.execute(), so serializing the
        // whole project here would be a pointless multi-MB UI-thread stall.
        if (entry.action == null && snapshotRestorer != null) {
            entry.snapshotAfter = snapshotRestorer.captureSnapshot();
        }

        if (entry.action != null) {
            // In-session: precise action-based undo
            entry.action.undo();
            FLog.d(TAG, "Undone (action): " + entry.description);
        } else if (entry.snapshotBefore != null && snapshotRestorer != null) {
            // Loaded from disk: snapshot-based undo
            snapshotRestorer.restoreFromSnapshot(entry.snapshotBefore);
            // After snapshot restore, invalidate action refs on redo stack
            invalidateRedoActions();
            FLog.d(TAG, "Undone (snapshot): " + entry.description);
        }

        redoStack.push(entry);

        FLog.d(TAG, "(undo=" + undoStack.size() + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return true;
    }

    /**
     * Redo the most recently undone action.
     *
     * <p>For in-session entries, calls {@link EditAction#execute()}.
     * For snapshot-only entries, restores the "after" snapshot.</p>
     *
     * @return true if an action was redone, false if nothing to redo
     */
    public boolean redo() {
        if (redoStack.isEmpty()) {
            FLog.d(TAG, "Nothing to redo");
            return false;
        }

        HistoryEntry entry = redoStack.pop();

        if (entry.action != null) {
            // In-session: precise action-based redo
            entry.action.execute();
            FLog.d(TAG, "Redone (action): " + entry.description);
        } else if (entry.snapshotAfter != null && snapshotRestorer != null) {
            // Loaded from disk: snapshot-based redo
            snapshotRestorer.restoreFromSnapshot(entry.snapshotAfter);
            FLog.d(TAG, "Redone (snapshot): " + entry.description);
        }

        undoStack.push(entry);

        FLog.d(TAG, "(undo=" + undoStack.size() + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return true;
    }

    // ── Queries ──────────────────────────────────────────────────────

    /** Whether there are actions to undo. */
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    /** Whether there are actions to redo. */
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** Number of actions that can be undone. */
    public int getUndoCount() {
        return undoStack.size();
    }

    /** Number of actions that can be redone. */
    public int getRedoCount() {
        return redoStack.size();
    }

    /** Get the description of the action that would be undone. */
    @Nullable
    public String peekUndoDescription() {
        return undoStack.isEmpty() ? null : undoStack.peek().description;
    }

    /** Get the description of the action that would be redone. */
    @Nullable
    public String peekRedoDescription() {
        return redoStack.isEmpty() ? null : redoStack.peek().description;
    }

    /** Clear all history. */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        FLog.d(TAG, "History cleared");
        notifyListener();
    }

    /** Total number of recorded entries (undo + redo). */
    public int size() {
        return undoStack.size() + redoStack.size();
    }

    // ── Persistence ──────────────────────────────────────────────────

    /**
     * Get the undo history as a serializable list (bottom-to-top order).
     * Used by {@code ProjectStorage} to persist undo history alongside
     * the project file.
     *
     * @return list of history entries (oldest first)
     */
    @NonNull
    public List<HistoryEntry> getUndoHistory() {
        // ArrayDeque iteration is top-to-bottom (LIFO); reverse for persistence
        List<HistoryEntry> list = new ArrayList<>(undoStack);
        // Reverse so oldest is first
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * Get the redo history, ordered so that index 0 is the entry that would be
     * redone NEXT (i.e. top of the redo stack), index 1 the one after that, etc.
     * This is the reverse chronological order used by the history popup (nearest
     * redo first), as opposed to {@link #getUndoHistory()} which is oldest-first
     * for persistence.
     *
     * @return list of redo entries, nearest-redo-first
     */
    @NonNull
    public List<HistoryEntry> getRedoHistory() {
        // ArrayDeque iteration is top-to-bottom (LIFO), i.e. the next redo first.
        // That's exactly the order we want here, so no reversal needed.
        return new ArrayList<>(redoStack);
    }

    /**
     * Load undo history from persisted data (oldest first).
     * Creates snapshot-only entries (no {@link EditAction}) that enable
     * undo via snapshot restoration.
     *
     * @param descriptions ordered list of action descriptions (oldest first)
     * @param snapshots    ordered list of project JSON snapshots (oldest first)
     */
    public void loadHistory(@NonNull List<String> descriptions,
                            @NonNull List<String> snapshots) {
        loadHistory(descriptions, snapshots, java.util.Collections.emptyList());
    }

    /**
     * @param aiFlags parallel to the other two (may be shorter/empty — missing entries read
     *                as user-authored, which is what pre-existing sidecars contain)
     */
    public void loadHistory(@NonNull List<String> descriptions,
                            @NonNull List<String> snapshots,
                            @NonNull List<Boolean> aiFlags) {
        clear();
        int count = Math.min(descriptions.size(), snapshots.size());
        // Oldest first, pushed with push() — i.e. addFirst, the SAME end recordAction uses
        // and the end undo() pops from — so the newest edit ends up on top, which is what
        // this loop always intended.
        //
        // It used to addLast() here, which is the opposite end, so a reloaded history came
        // back INVERTED: after a restart the nearest undo was the OLDEST edit and the
        // history popup listed everything upside down. Measured on the Note 9 — a trim
        // followed by an AI step reloaded as "-1 Trim, -2 AI" when the AI step was the
        // most recent thing that happened.
        for (int i = 0; i < count; i++) {
            boolean ai = i < aiFlags.size() && Boolean.TRUE.equals(aiFlags.get(i));
            HistoryEntry entry = new HistoryEntry(null, descriptions.get(i), snapshots.get(i), ai);
            undoStack.push(entry);
        }
        FLog.d(TAG, "Loaded " + count + " history entries from disk");
        notifyListener();
    }

    /**
     * Record the AI assistant's work as ONE undoable step, captured from the state the
     * project is in RIGHT NOW — so this must be called BEFORE the editor swaps in the
     * reloaded project.
     *
     * <p>Snapshot-based on purpose: the AI edits a separate copy of the project on disk, so
     * there is no {@link EditAction} that could describe the change against the live object
     * graph. Restoring the pre-AI snapshot is the only faithful inverse.</p>
     *
     * @param description what the AI did, for the history row
     * @return true if the step was recorded; false when no snapshot could be taken (in which
     *         case there is nothing honest to offer and no row is added)
     */
    public boolean recordAiCheckpoint(@NonNull String description) {
        if (snapshotRestorer == null) return false;
        String snapshot;
        try {
            snapshot = snapshotRestorer.captureSnapshot();
        } catch (Exception e) {
            FLog.e(TAG, "AI checkpoint: snapshot capture failed", e);
            return false;
        }
        if (snapshot == null) return false;

        undoStack.push(new HistoryEntry(null, description, snapshot, true));
        redoStack.clear();
        while (undoStack.size() > maxHistory) {
            ((ArrayDeque<HistoryEntry>) undoStack).removeLast();
        }
        enforceSnapshotBudget();
        FLog.i(TAG, "Recorded AI checkpoint: " + description
                + " (undo=" + undoStack.size() + ", redo=0)");
        notifyListener();
        return true;
    }

    /**
     * The editor is about to replace the whole project object (an AI edit landed on disk).
     * Every {@link EditAction} on both stacks closes over the OUTGOING model objects, so
     * replaying one would mutate an orphan: the undo would report success and change
     * nothing. Drop those references and keep only what can still be honoured — the
     * snapshots, which restore by value and do not care about object identity.
     *
     * <p>Entries with neither an action nor a snapshot are REMOVED rather than kept as dead
     * rows: {@code recordAction} skips the snapshot when one was taken too recently, so such
     * entries exist and would otherwise sit in the history doing nothing when tapped.</p>
     *
     * @return the number of entries dropped as unhonourable
     */
    public int invalidateActionsForProjectSwap() {
        int dropped = replaceWithSnapshotOnly(undoStack) + replaceWithSnapshotOnly(redoStack);
        FLog.i(TAG, "Project swapped under the undo stack: actions invalidated, "
                + dropped + " unrestorable entr(ies) dropped (undo=" + undoStack.size()
                + ", redo=" + redoStack.size() + ")");
        notifyListener();
        return dropped;
    }

    /** Null every action in {@code stack} and drop entries that have no snapshot to fall back on. */
    private int replaceWithSnapshotOnly(@NonNull Deque<HistoryEntry> stack) {
        List<HistoryEntry> kept = new ArrayList<>(stack.size());
        int dropped = 0;
        for (HistoryEntry entry : stack) { // iteration order is top-to-bottom
            if (entry.snapshotBefore == null) {
                dropped++;
                continue;
            }
            entry.action = null;
            kept.add(entry);
        }
        stack.clear();
        // kept is top-to-bottom; addLast preserves that order.
        for (HistoryEntry entry : kept) ((ArrayDeque<HistoryEntry>) stack).addLast(entry);
        return dropped;
    }

    // ── Internal ─────────────────────────────────────────────────────

    /**
     * After a snapshot-based project restore, action references on the
     * redo stack point to stale model objects. Null them out so redo
     * falls back to snapshot restoration.
     */
    private void invalidateRedoActions() {
        for (HistoryEntry entry : redoStack) {
            if (entry.action != null) {
                entry.action = null;
            }
        }
        FLog.d(TAG, "Invalidated action refs on redo stack (" + redoStack.size() + " entries)");
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onUndoRedoStateChanged(canUndo(), canRedo());
        }
    }
}
