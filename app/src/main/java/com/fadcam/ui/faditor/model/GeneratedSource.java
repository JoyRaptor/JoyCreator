package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Recipe for an AI-authored animated slide (chapter card, stylized title,
 * animated lower-third). Attached to a {@link Clip} (fullscreen mode) or a
 * {@link TextOverlayItem} (overlay mode).
 *
 * <p>The authored HTML ({@link #htmlUri}) is the <b>source of truth</b>. The
 * rendered artifacts ({@link #renderCacheUri} / {@link #renderSequenceDir}) are
 * caches — they may be missing or stale and are always regenerable from the
 * HTML before export, the same rule that applies to remuxed media paths.</p>
 *
 * @see com.fadcam.ui.faditor.slides.SlideCaptureEngine
 */
public class GeneratedSource {

    public static final String KIND_HTML_SLIDE = "html_slide";
    public static final String MODE_FULLSCREEN = "fullscreen";
    public static final String MODE_OVERLAY = "overlay";

    /** Kind discriminator; only {@link #KIND_HTML_SLIDE} for now. */
    @NonNull
    public String kind = KIND_HTML_SLIDE;

    /** {@link #MODE_FULLSCREEN} or {@link #MODE_OVERLAY}. */
    @NonNull
    public String mode = MODE_FULLSCREEN;

    /** {@code file://} URI to the authored HTML. The source of truth. */
    @NonNull
    public String htmlUri;

    /** sha256 of (HTML bytes + width + height + requested durationMs). Cache key. */
    @NonNull
    public String contentHash;

    /** {@code file://} URI to the rendered MP4 (fullscreen). Regenerable / nullable. */
    @Nullable
    public String renderCacheUri;

    /** {@code file://} URI to the rendered PNG sequence dir (overlay). Regenerable / nullable. */
    @Nullable
    public String renderSequenceDir;

    /** Duration the slide's own GSAP timeline was authored for. */
    public long authoredDurationMs;

    /** Pixel width the HTML was authored / hashed against. */
    public int width;

    /** Pixel height the HTML was authored / hashed against. */
    public int height;

    /** Style direction given to the AI, kept for "regenerate" requests. */
    @Nullable
    public String styleHint;

    /** OpenRouter model id that authored this slide, for debugging. */
    @Nullable
    public String sourceModel;

    /**
     * Frozen-start zone (JoyRaptor 2026-07-16): the animation holds its FIRST frame
     * for this many ms after the clip's in-point before it starts playing.
     * Together with {@link #freezeEndMs} this defines the animated window; the
     * animation is time-stretched to exactly fill it (see
     * {@code SlideRenderer.mapSourceToAnimMs}). Default 0 = no frozen start.
     */
    public long freezeStartMs;

    /**
     * Frozen-end zone: the animation reaches its LAST frame this many ms before
     * the clip's out-point and holds it. Default 0 = no frozen end.
     */
    public long freezeEndMs;

    /**
     * Trim-state stamp of the currently cached render ({@code contentHash} +
     * in/out points + freeze zones at render time). When it no longer matches
     * the clip's live state the MP4 is stale and gets re-rendered — the same
     * "cache is never source of truth" rule as {@link #renderCacheUri}.
     */
    @Nullable
    public String renderStateHash;

    public GeneratedSource() { }

    public GeneratedSource(@NonNull String mode, @NonNull String htmlUri,
                           @NonNull String contentHash, long authoredDurationMs,
                           int width, int height) {
        this.mode = mode;
        this.htmlUri = htmlUri;
        this.contentHash = contentHash;
        this.authoredDurationMs = authoredDurationMs;
        this.width = width;
        this.height = height;
    }

    public boolean isOverlay() {
        return MODE_OVERLAY.equals(mode);
    }

    /** Deep copy. */
    @NonNull
    public GeneratedSource copy() {
        GeneratedSource g = new GeneratedSource();
        g.kind = kind;
        g.mode = mode;
        g.htmlUri = htmlUri;
        g.contentHash = contentHash;
        g.renderCacheUri = renderCacheUri;
        g.renderSequenceDir = renderSequenceDir;
        g.authoredDurationMs = authoredDurationMs;
        g.width = width;
        g.height = height;
        g.styleHint = styleHint;
        g.sourceModel = sourceModel;
        g.freezeStartMs = freezeStartMs;
        g.freezeEndMs = freezeEndMs;
        g.renderStateHash = renderStateHash;
        return g;
    }
}
