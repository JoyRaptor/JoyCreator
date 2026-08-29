package com.fadcam.ui.faditor.slides;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Timed slide object — styled cards on the transcript's clock.
 *
 * <p>Route (a) chosen per SPEC §3.1: restricted HTML → styled runs. JoyRaptor's AI-authored HTML
 * drops straight in. Subset documented in {@link SlideHtmlParser}.</p>
 *
 * <p>Pure model: no {@code android.*} beyond annotations, so the JVM harness can exercise it
 * (follows {@code CaptionPhrases}/{@code CaptionFit}).</p>
 *
 * <p>Timing is keyframes: a slide boundary IS a keyframe on the deck object. One window,
 * one predicate, shared by write/indicator/jump — {@link Clip#KEYFRAME_SNAP_MS} and
 * {@link #boundaryIndexAt(long)} / {@link #snapToBoundaryMs(long)}.</p>
 */
public class SlideDeck {

    public enum TimeBase { ABSOLUTE, CLIP }

    @NonNull private final String id;
    @NonNull private String label;
    @NonNull private TimeBase timeBase = TimeBase.ABSOLUTE;
    @NonNull private final List<Slide> slides = new ArrayList<>();

    /** Box geometry in normalised coords, like caption center/size (for renderer). */
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    private float widthFraction = 0.85f;
    private float heightFraction = 0.28f;
    private int backgroundColor = 0xCC000000;
    private int cornerRadiusDp = 12;

    public SlideDeck(@NonNull String label) {
        this.id = UUID.randomUUID().toString();
        this.label = label;
    }

    public SlideDeck(@NonNull String id, @NonNull String label, @NonNull TimeBase base) {
        this.id = id;
        this.label = label;
        this.timeBase = base;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getLabel() { return label; }
    public void setLabel(@NonNull String v) { label = v; }
    @NonNull public TimeBase getTimeBase() { return timeBase; }
    public void setTimeBase(@NonNull TimeBase v) { timeBase = v; }

    public float getCenterX() { return centerX; }
    public float getCenterY() { return centerY; }
    public float getWidthFraction() { return widthFraction; }
    public float getHeightFraction() { return heightFraction; }
    public int getBackgroundColor() { return backgroundColor; }
    public int getCornerRadiusDp() { return cornerRadiusDp; }
    public void setGeometry(float cx, float cy, float wf, float hf) {
        centerX = clamp01(cx); centerY = clamp01(cy);
        widthFraction = clamp01(wf); heightFraction = clamp01(hf);
    }
    public void setBackgroundColor(int c) { backgroundColor = c; }
    public void setCornerRadiusDp(int v) { cornerRadiusDp = v; }

    @NonNull public List<Slide> getSlides() { return slides; }
    public int slideCount() { return slides.size(); }

    /** Which slide is on screen at {@code t} (project timeline ms), or -1 if none/before first. */
    public int cueAtMs(long t) {
        if (slides.isEmpty()) return -1;
        // slides sorted ascending by startMs
        int idx = -1;
        for (int i = 0; i < slides.size(); i++) {
            if (slides.get(i).startMs <= t) idx = i; else break;
        }
        return idx;
    }

    @Nullable public Slide slideAtMs(long t) {
        int i = cueAtMs(t);
        return i >= 0 ? slides.get(i) : null;
    }

    // ── Keyframe boundary helpers — ONE window, ONE predicate ───────────
    // Shared by write (addOrUpdate), indicator (isOnBoundary) and jump.
    // Mirrors Clip.opacityKeyframeIndexAt exactly.

    public static final long BOUNDARY_SNAP_MS = Clip.KEYFRAME_SNAP_MS; // 80L

    /** Index of boundary within snap window, or -1. Nearest wins. */
    public int boundaryIndexAt(long timeMs) {
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < slides.size(); i++) {
            long d = Math.abs(slides.get(i).startMs - timeMs);
            if (d <= BOUNDARY_SNAP_MS && d < bestDelta) { bestDelta = d; best = i; }
        }
        return best;
    }

    public long snapToBoundaryMs(long timeMs) {
        int at = boundaryIndexAt(timeMs);
        return at >= 0 ? slides.get(at).startMs : timeMs;
    }

    public boolean isOnBoundary(long timeMs) { return boundaryIndexAt(timeMs) >= 0; }

    /** Add or nudge a boundary. Returns index of affected slide. Never crosses neighbours. */
    public int addOrUpdateBoundary(long timeMs) {
        timeMs = Math.max(0, timeMs);
        int at = boundaryIndexAt(timeMs);
        if (at >= 0) {
            // Already on a boundary — snap time, no move (idempotent tap)
            return at;
        }
        // Find insertion point
        int pos = 0;
        while (pos < slides.size() && slides.get(pos).startMs < timeMs) pos++;
        // Guard: must not be within snap window of neighbours after insert (would be crossing)
        // Since we already checked whole deck, insertion gap is at least snap+1 from neighbours
        // But also ensure not crossing immediate neighbours (<80ms apart would be considered same)
        // If too close, snap to nearest neighbour instead
        if (pos > 0 && Math.abs(slides.get(pos-1).startMs - timeMs) <= BOUNDARY_SNAP_MS) return pos-1;
        if (pos < slides.size() && Math.abs(slides.get(pos).startMs - timeMs) <= BOUNDARY_SNAP_MS) return pos;
        Slide s = new Slide(timeMs, "<p>Slide " + (pos+1) + "</p>");
        slides.add(pos, s);
        sortSlides();
        return pos;
    }

    public boolean removeBoundaryAt(long timeMs) {
        int at = boundaryIndexAt(timeMs);
        if (at < 0) return false;
        // Keep at least one slide? Spec says nudge/delete; allow deleting even last — deck can be empty
        slides.remove(at);
        return true;
    }

    /** Nudge boundary at {@code fromMs} (must be on a boundary) to {@code toMs}. Returns false if blocked. */
    public boolean nudgeBoundary(long fromMs, long toMs) {
        int at = boundaryIndexAt(fromMs);
        if (at < 0) return false;
        toMs = Math.max(0, toMs);
        // Must not cross neighbours
        long prev = at > 0 ? slides.get(at-1).startMs : Long.MIN_VALUE;
        long next = at + 1 < slides.size() ? slides.get(at+1).startMs : Long.MAX_VALUE;
        if (toMs <= prev + BOUNDARY_SNAP_MS) return false;
        if (toMs >= next - BOUNDARY_SNAP_MS) return false;
        // Also not within snap of other boundaries (other than itself)
        for (int i = 0; i < slides.size(); i++) if (i != at) {
            if (Math.abs(slides.get(i).startMs - toMs) <= BOUNDARY_SNAP_MS) return false;
        }
        slides.get(at).startMs = toMs;
        sortSlides();
        return true;
    }

    // Import timing script: list of startMs values (and optionally html per entry)
    public int importBoundaries(@NonNull List<Long> startMss, @Nullable List<String> htmls) {
        int added = 0;
        for (int i = 0; i < startMss.size(); i++) {
            long t = Math.max(0, startMss.get(i));
            if (boundaryIndexAt(t) >= 0) continue;
            // Not crossing check: find insertion neighbours
            int pos = 0;
            while (pos < slides.size() && slides.get(pos).startMs < t) pos++;
            long prev = pos > 0 ? slides.get(pos-1).startMs : Long.MIN_VALUE;
            long next = pos < slides.size() ? slides.get(pos).startMs : Long.MAX_VALUE;
            if (t <= prev + BOUNDARY_SNAP_MS || t >= next - BOUNDARY_SNAP_MS) continue;
            String html = (htmls != null && i < htmls.size() && htmls.get(i) != null) ? htmls.get(i) : "<p>Slide " + (slides.size()+1) + "</p>";
            Slide s = new Slide(t, html);
            slides.add(pos, s);
            added++;
        }
        sortSlides();
        return added;
    }

    private void sortSlides() {
        Collections.sort(slides, (a,b) -> Long.compare(a.startMs, b.startMs));
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    /** One cue. */
    public static class Slide {
        public long startMs;
        @NonNull public String html;
        public Slide(long startMs, @NonNull String html) { this.startMs = startMs; this.html = html; }
        @NonNull public List<StyledRun> runs() { return SlideHtmlParser.parse(html); }
    }

    /** Styled run produced by restricted HTML parser. */
    public static class StyledRun {
        @NonNull public String text;
        public boolean bold;
        public boolean italic;
        public int color = 0; // 0 = default (white)
        public float fontSizeScale = 1f; // relative
        @Nullable public String fontFamily; // may be file: key
        public boolean isNewline;
        public StyledRun(@NonNull String text) { this.text = text; }
    }
}
