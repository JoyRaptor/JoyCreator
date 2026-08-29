package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.audio.ScrubEngine;
import com.fadcam.ui.faditor.waveform.OnsetDetector;
import com.fadcam.ui.faditor.waveform.PcmSidecar;

/**
 * Owns the Word Sync mode lifecycle — the bulk instrument beside the existing
 * surgical word scrubber (`SPEC_20260829_WORD_SYNC`).
 *
 * <p>This is the single place where the three already-built, already-tested pieces
 * meet the editor: {@link WordSyncOnsets} (§3.3), {@link WordSyncRipple} (§3.4)
 * and {@link com.fadcam.ui.faditor.move.TimeShuttleView} (§3.5). The activity
 * only owns the toggle and the lockout flag; every timing decision lives here so
 * the activity stays small (the rule that kept {@code AudioLayerSync}'s defects
 * fixable in one file).</p>
 *
 * <p>Not an Android View — a plain controller that the activity creates once and
 * the transcript view queries while the mode is active. Scrub audio is wired
 * through {@link ScrubEngine} + {@link PcmSidecar}; onset snapping through
 * {@link WordSyncOnsets}/{@link OnsetDetector}; ripple arithmetic through
 * {@link WordSyncRipple}. All three are consumed, not rebuilt.</p>
 */
public final class WordSyncMode {

    /** Host supplies editor-owned state this mode needs but does not own. */
    public interface Host {
        @NonNull Context context();
        @Nullable Uri sourceUri();
        @Nullable Transcript transcript();
        /** Current timeline zoom, as ms per pixel (for snap tolerance). */
        double msPerPixel();
        /** Clip duration fallback for STRETCH when nothing is pinned. */
        long fallbackAnchorMs();
        /** Called after a drag has mutated word timings — host syncs timeline + persists. */
        void onTimingsChanged(@NonNull long[] beforeStarts, @NonNull long[] afterStarts, int draggedIndex);
        /** Request a redraw of the transcript and the tape. */
        void requestRedraw();
    }

    private final ScrubEngine scrubEngine = new ScrubEngine();
    private Host host;
    private boolean active = false;
    private WordSyncRipple.Mode rippleMode = WordSyncRipple.Mode.ONE;
    private boolean snapEnabled = true;

    // Drag state — one gesture, one undo step.
    private int dragIndex = -1;
    private long[] dragBeforeStarts;
    private boolean[] pinned;
    private long dragFallbackAnchor;
    private double dragMsPerPixel;

    public WordSyncMode() {}

    public void setHost(@Nullable Host h) { this.host = h; }

    public boolean isActive() { return active; }

    public WordSyncRipple.Mode getRippleMode() { return rippleMode; }
    public void setRippleMode(@NonNull WordSyncRipple.Mode m) { this.rippleMode = m; }

    public boolean isSnapEnabled() { return snapEnabled; }
    public void setSnapEnabled(boolean v) { this.snapEnabled = v; }

    /** TT/Tt/tt transforms on the CURRENT transcript word — pure text, no model change. */
    @NonNull
    public static String transformTT(@NonNull String s) { return s.toUpperCase(java.util.Locale.ROOT); }
    @NonNull
    public static String transformTt(@NonNull String s) {
        if (s.isEmpty()) return s;
        return s.substring(0,1).toUpperCase(java.util.Locale.ROOT) + s.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
    @NonNull
    public static String transformtt(@NonNull String s) { return s.toLowerCase(java.util.Locale.ROOT); }

    /** B/U/I are not live — per-word rich text does not exist yet (SPEC §3.6). */
    public static boolean isRichTextAvailable() { return false; }

    /** Enter the mode. Idempotent. Starts onset computation and prepares scrub audio. */
    public void enter() {
        if (active) return;
        active = true;
        // Kick onset computation (off main thread, cached for session).
        Host h = host;
        if (h != null) {
            Uri uri = h.sourceUri();
            if (uri != null) {
                WordSyncOnsets.get(h.context(), uri, null);
                // Ensure a sidecar bake is in flight so scrub audio is ready on first drag.
                PcmSidecar.bakeAsync(h.context(), uri, new PcmSidecar.BakeCallback() {
                    @Override public void onBaked(@NonNull java.io.File file) {
                        PcmSidecar.Handle handle = PcmSidecar.open(h.context(), uri);
                        if (handle != null) scrubEngine.attach(handle);
                    }
                    @Override public void onFailed(@NonNull String message) {}
                });
                // If already baked, attach synchronously for low latency.
                PcmSidecar.Handle existing = PcmSidecar.open(h.context(), uri);
                if (existing != null) scrubEngine.attach(existing);
            }
        }
    }

    /** Leave the mode. Idempotent. Stops scrub audio and clears drag state. */
    public void exit() {
        if (!active) return;
        scrubEngine.end();
        active = false;
        dragIndex = -1;
        dragBeforeStarts = null;
        pinned = null;
    }

    /** Called when source changes while the mode is active — re-prime cache/sidecar. */
    public void onSourceChanged() {
        if (!active || host == null) return;
        Uri uri = host.sourceUri();
        if (uri == null) { scrubEngine.attach(null); return; }
        WordSyncOnsets.get(host.context(), uri, null);
        PcmSidecar.Handle handle = PcmSidecar.open(host.context(), uri);
        scrubEngine.attach(handle);
        if (handle == null) {
            PcmSidecar.bakeAsync(host.context(), uri, new PcmSidecar.BakeCallback() {
                @Override public void onBaked(@NonNull java.io.File file) {
                    PcmSidecar.Handle h2 = PcmSidecar.open(host.context(), uri);
                    if (h2 != null) scrubEngine.attach(h2);
                }
                @Override public void onFailed(@NonNull String message) {}
            });
        }
    }

    /** Ensure {@code pinned} matches transcript length (false = not pinned). */
    private void ensurePinned(int n) {
        if (pinned == null || pinned.length != n) pinned = new boolean[n];
    }

    public void setPinned(int index, boolean v) {
        if (pinned != null && index >= 0 && index < pinned.length) pinned[index] = v;
    }

    public boolean isPinned(int index) {
        return pinned != null && index >= 0 && index < pinned.length && pinned[index];
    }

    /** Begin a word drag. Snapshots starts for the single undo step; starts scrub audio. */
    public void beginDrag(int index) {
        if (!active || host == null) return;
        Transcript t = host.transcript();
        if (t == null || index < 0 || index >= t.words.size()) return;
        dragIndex = index;
        int n = t.words.size();
        dragBeforeStarts = new long[n];
        for (int i = 0; i < n; i++) dragBeforeStarts[i] = t.words.get(i).startMs;
        ensurePinned(n);
        dragFallbackAnchor = host.fallbackAnchorMs();
        if (dragFallbackAnchor <= 0) {
            // Fallback to last word + 1s or 10s.
            dragFallbackAnchor = n > 0 ? t.words.get(n - 1).startMs + 1000 : 10000;
        }
        dragMsPerPixel = host.msPerPixel();
        scrubEngine.begin();
        // Prime scrub at the word's current position.
        long startMs = t.words.get(index).startMs;
        scrubEngine.seekTo(startMs);
    }

    /**
     * Drag to a desired start time. Snaps to nearest onset within tolerance (if enabled),
     * then applies the selected ripple mode and writes the result into the live transcript.
     * Also drives scrub audio.
     *
     * @param desiredMs raw desired start for the dragged word (before snap)
     * @return the new start array (or null if not dragging)
     */
    @Nullable
    public long[] dragTo(long desiredMs) {
        if (!active || host == null || dragIndex < 0 || dragBeforeStarts == null) return null;
        Transcript t = host.transcript();
        if (t == null) return null;
        Uri uri = host.sourceUri();
        double msPerPixel = dragMsPerPixel;
        if (msPerPixel <= 0) msPerPixel = host.msPerPixel();
        long snapped = desiredMs;
        if (snapEnabled && uri != null) {
            // WordSyncOnsets.snap needs a Context; it returns input unchanged when onsets not ready.
            snapped = WordSyncOnsets.snap(host.context(), uri, desiredMs, msPerPixel, true);
        }
        long anchor = WordSyncRipple.anchorFor(dragBeforeStarts, pinned != null ? pinned : new boolean[dragBeforeStarts.length], dragIndex, dragFallbackAnchor);
        long[] after = WordSyncRipple.apply(dragBeforeStarts, dragIndex, snapped, rippleMode, anchor);
        // Write back to live words (preserving duration).
        for (int i = 0; i < after.length && i < t.words.size(); i++) {
            TranscriptWord w = t.words.get(i);
            long oldStart = w.startMs;
            long oldEnd = w.endMs;
            long dur = Math.max(0, oldEnd - oldStart);
            long ns = Math.max(0, after[i]);
            t.words.set(i, new TranscriptWord(w.text, ns, ns + dur, w.struck, w.forceLineBreakAfter));
        }
        // Preserve chapter/speaker maps via copy? Transcript copy not needed — words list mutated in place.
        // Scrub audio follows the dragged word.
        scrubEngine.seekTo(snapped);
        if (host != null) host.requestRedraw();
        return after;
    }

    /** End the current drag. Stops scrub audio and reports one undo step to the host. */
    public void endDrag() {
        if (!active || dragIndex < 0 || dragBeforeStarts == null || host == null) {
            scrubEngine.end();
            dragIndex = -1;
            dragBeforeStarts = null;
            return;
        }
        Transcript t = host.transcript();
        if (t != null) {
            int n = t.words.size();
            long[] after = new long[n];
            for (int i = 0; i < n; i++) after[i] = t.words.get(i).startMs;
            long[] before = dragBeforeStarts;
            int idx = dragIndex;
            scrubEngine.end();
            dragIndex = -1;
            dragBeforeStarts = null;
            // One undo step for the whole gesture (SPEC §3.4).
            host.onTimingsChanged(before, after, idx);
            host.requestRedraw();
        } else {
            scrubEngine.end();
            dragIndex = -1;
            dragBeforeStarts = null;
        }
    }

    /** Cancel — revert the in-flight timing mutations (e.g. ACTION_CANCEL). */
    public void cancelDrag() {
        if (dragBeforeStarts != null && host != null) {
            Transcript t = host.transcript();
            if (t != null) {
                for (int i = 0; i < dragBeforeStarts.length && i < t.words.size(); i++) {
                    TranscriptWord w = t.words.get(i);
                    long dur = Math.max(0, w.endMs - w.startMs);
                    long ns = Math.max(0, dragBeforeStarts[i]);
                    t.words.set(i, new TranscriptWord(w.text, ns, ns + dur, w.struck, w.forceLineBreakAfter));
                }
                host.requestRedraw();
            }
        }
        scrubEngine.end();
        dragIndex = -1;
        dragBeforeStarts = null;
    }

    public void release() {
        scrubEngine.release();
    }

    /** Snap a playhead position to nearest onset (for shuttle-engaged tap). */
    public long snapToOnset(long ms) {
        if (!active || !snapEnabled || host == null) return ms;
        Uri uri = host.sourceUri();
        if (uri == null) return ms;
        return WordSyncOnsets.snap(host.context(), uri, ms, host.msPerPixel(), true);
    }

    /** Ticks visible while the mode is on (SPEC §3.3). */
    @NonNull
    public long[] ticksInRange(long fromMs, long toMs) {
        if (!active || host == null) return new long[0];
        Uri uri = host.sourceUri();
        if (uri == null) return new long[0];
        long[] onsets = WordSyncOnsets.get(host.context(), uri, null);
        return WordSyncOnsets.inRange(onsets, fromMs, toMs);
    }

    public int getDragIndex() { return dragIndex; }

    public double getMsPerPixel() { return dragMsPerPixel > 0 ? dragMsPerPixel : (host != null ? host.msPerPixel() : 5.0); }
}
