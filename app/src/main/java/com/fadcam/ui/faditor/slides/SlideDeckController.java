package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller for slide deck keyframe gestures — one window, one predicate, one undo step.
 *
 * <p>Mirrors the opacity keyframe helper pattern: advance (add), prev/next (jump), delete.
 * Uses {@link SlideDeck#BOUNDARY_SNAP_MS} everywhere.</p>
 */
public class SlideDeckController {

    public interface Host {
        void saveAndSignal(@NonNull String description);
        long getPlayheadMs();
        void seekTo(long ms);
        void invalidatePreview();
    }

    @NonNull private final FaditorProject project;
    @NonNull private final ProjectStorage storage;
    @NonNull private final SlideDeck deck;
    @NonNull private final Host host;

    public SlideDeckController(@NonNull FaditorProject project, @NonNull ProjectStorage storage,
                               @NonNull SlideDeck deck, @NonNull Host host) {
        this.project = project; this.storage = storage; this.deck = deck; this.host = host;
    }

    /** Advance next slide — drops a boundary at playhead (live retiming gesture). */
    public void advance() {
        long t = host.getPlayheadMs();
        int existing = deck.boundaryIndexAt(t);
        if (existing >= 0) return; // already on boundary
        deck.addOrUpdateBoundary(t);
        host.saveAndSignal("Advance slide");
        host.invalidatePreview();
    }

    public boolean deleteAtPlayhead() {
        long t = host.getPlayheadMs();
        boolean removed = deck.removeBoundaryAt(t);
        if (removed) { host.saveAndSignal("Delete slide boundary"); host.invalidatePreview(); }
        return removed;
    }

    @Nullable
    public Long prevBoundary() {
        long t = host.getPlayheadMs();
        Long best = null;
        for (SlideDeck.Slide s : deck.getSlides()) {
            if (s.startMs < t - SlideDeck.BOUNDARY_SNAP_MS) {
                if (best == null || s.startMs > best) best = s.startMs;
            }
        }
        return best;
    }

    @Nullable
    public Long nextBoundary() {
        long t = host.getPlayheadMs();
        Long best = null;
        for (SlideDeck.Slide s : deck.getSlides()) {
            if (s.startMs > t + SlideDeck.BOUNDARY_SNAP_MS) {
                if (best == null || s.startMs < best) best = s.startMs;
            }
        }
        return best;
    }

    public void jumpPrev() {
        Long p = prevBoundary();
        if (p != null) host.seekTo(p);
    }

    public void jumpNext() {
        Long n = nextBoundary();
        if (n != null) host.seekTo(n);
    }

    public boolean nudgeCurrent(long deltaMs) {
        long t = host.getPlayheadMs();
        int idx = deck.boundaryIndexAt(t);
        if (idx < 0) return false;
        long from = deck.getSlides().get(idx).startMs;
        long to = from + deltaMs;
        boolean ok = deck.nudgeBoundary(from, to);
        if (ok) {
            host.saveAndSignal("Nudge slide boundary");
            host.seekTo(to);
            host.invalidatePreview();
        }
        return ok;
    }

    public int importTimings(@NonNull List<Long> times, @Nullable List<String> htmls) {
        int added = deck.importBoundaries(times, htmls);
        if (added > 0) { host.saveAndSignal("Import slide timings ("+added+")"); host.invalidatePreview(); }
        return added;
    }

    public boolean isOnBoundary() { return deck.isOnBoundary(host.getPlayheadMs()); }
}
