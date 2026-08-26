package com.fadcam.ui.faditor.slides;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Thin {@link WebView} wrapper that gives a live, scrubbable preview of an
 * AI-authored slide inside the editor — the same HTML that gets rasterized at
 * export, driven by the playhead instead of frame-stepped.
 *
 * <p>Load the slide HTML once via {@link #loadSlide}, then call {@link #seekTo}
 * as the playhead moves. Readiness fires once the page calls
 * {@code Faditor.register(...)} (surfaced as the {@code FADITOR_READY} title).</p>
 *
 * <p>Communication is one-directional (Java → JS via {@code evaluateJavascript});
 * there is no JS-to-Java bridge to secure.</p>
 */
public class GeneratedSlideView extends WebView {

    private static final String ASSET_BASE = "file:///android_asset/faditor/";
    private static final String READY_TITLE = "FADITOR_READY";

    private boolean ready;
    @Nullable private Runnable onReadyListener;
    @Nullable private Runnable onDoubleTapListener;
    @Nullable private android.view.GestureDetector gestureDetector;
    private long pendingSeekMs = -1;

    public GeneratedSlideView(@NonNull Context context) {
        super(context);
        init();
    }

    public GeneratedSlideView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void init() {
        setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        // Scale the fixed-pixel slide stage down to fit the preview area (unlike
        // the capture activity, which renders 1:1 for full-resolution frames).
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (READY_TITLE.equals(title)) {
                    ready = true;
                    // Fit before the first seek so the very first frame the user sees is
                    // already at the right scale, not corrected a beat later.
                    fitStageToView();
                    if (pendingSeekMs >= 0) {
                        seekTo(pendingSeekMs);
                        pendingSeekMs = -1;
                    }
                    if (onReadyListener != null) onReadyListener.run();
                }
            }
        });
    }

    public void setOnReadyListener(@Nullable Runnable listener) {
        this.onReadyListener = listener;
    }

    /**
     * Re-fit the fixed-pixel slide stage to whatever size this view is NOW.
     *
     * <p>{@code setLoadWithOverviewMode(true)} fits the page to the view exactly once, when it
     * loads. Every other preview layer recomputes itself from the video content rect when the
     * container is reparented — into the floating PiP, or back inline — but a WebView lays its
     * content out at a CSS viewport size and does not re-lay-out just because its View got
     * smaller. So the slide kept rendering at load-time scale inside a shrunken PiP: JoyRaptor,
     * 2026-08-26, "the html clip stays at unrotated scale so in the popout it is not ground
     * truth. seems to be the outlier."
     *
     * <p>The authored HTML declares NO viewport meta (see {@code SlideContract}), so WebView
     * falls back to its default 980px layout width while {@code #stage} is a fixed pixel size
     * of its own — the two never agreed even before a resize. Injecting a viewport that IS the
     * stage width puts WebView's own fit machinery on the right number, and a fixed-width
     * viewport is re-scaled by the engine when the view resizes, which is exactly the behaviour
     * that was missing. Nothing here scales anything by hand, so there is no second transform
     * to fight the engine's.</p>
     *
     * <p>Idempotent and safe to call at any time; a no-op until the stage exists.</p>
     */
    public void fitStageToView() {
        if (!ready) return;
        evaluateJavascript(
                "(function(){var s=document.getElementById('stage');if(!s)return;"
                        + "var w=s.offsetWidth||0;if(!w)return;"
                        + "var m=document.querySelector('meta[name=\"viewport\"]');"
                        + "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');"
                        + "(document.head||document.documentElement).appendChild(m);}"
                        + "var c='width='+w;if(m.getAttribute('content')!==c)"
                        + "m.setAttribute('content',c);"
                        + "document.documentElement.style.margin='0';"
                        + "if(document.body)document.body.style.margin='0';})();",
                null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The PiP promote/demote reparent is exactly this: a size change with no reload.
        if (w > 0 && h > 0 && (w != oldw || h != oldh)) fitStageToView();
    }

    /** Double-tap on the slide preview = the slide's advanced menu (code editor). */
    public void setOnDoubleTapListener(@Nullable Runnable listener) {
        this.onDoubleTapListener = listener;
        if (listener != null && gestureDetector == null) {
            gestureDetector = new android.view.GestureDetector(getContext(),
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(@NonNull android.view.MotionEvent e) {
                            if (onDoubleTapListener != null) {
                                onDoubleTapListener.run();
                                return true;
                            }
                            return false;
                        }
                    });
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (gestureDetector != null) gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    /**
     * Load a slide's authored HTML file from its OWN directory, so relative
     * refs (the runtime scripts, and any images the user drops next to the
     * HTML) resolve there. The runtime scripts are copied in first.
     */
    public void loadSlide(@NonNull File htmlFile) {
        ready = false;
        File dir = htmlFile.getParentFile();
        if (dir != null) SlideFiles.ensureRuntimeIn(getContext(), dir);
        loadUrl(android.net.Uri.fromFile(htmlFile).toString());
    }

    /** Seek the slide's GSAP timeline to the given local time (ms). */
    public void seekTo(long ms) {
        if (!ready) {
            pendingSeekMs = ms;
            return;
        }
        evaluateJavascript("Faditor.seek(" + Math.max(0, ms) + ");", null);
    }

    public boolean isReady() {
        return ready;
    }
}
