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
        // The fixed-pixel stage is scaled to fit by fitStage(), NOT by page zoom. Overview mode
        // used to do it, but WebView will not zoom out past 25%, and a 1080-wide stage in a
        // ~390px preview needs ~18%: the preview showed the left ~70% of every slide, the title
        // pushed off the right edge (Note 9, 2026-10-15). The capture activity renders 1:1.
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);

        setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (READY_TITLE.equals(title)) {
                    ready = true;
                    fitStage();
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

    /**
     * Scale the page so its #stage exactly fills this view — the whole slide, at any preview size.
     * A CSS transform has no minimum, unlike page zoom.
     */
    private void fitStage() {
        evaluateJavascript(FIT_JS, null);
    }

    /**
     * Installs {@code __jbFit} once and runs it. It scales the #stage element (the body when a
     * page has none) — not the root element, whose transform Chromium applied twice (the slide
     * shrank into the top-left corner, Note 9, 2026-10-15). It is re-run on every seek as well as
     * on resize: the view's size can land before or after the page reports ready, and a fit made
     * once raced it (measured: scaled for a 788-wide viewport inside a 197-wide view).
     */
    private static final String FIT_JS = "(function(){if(!window.__jbFit){window.__jbFit=function(){"
            + "var s=document.getElementById('stage')||document.body;if(!s)return;"
            + "var w=s.offsetWidth,h=s.offsetHeight;if(!w||!h||!innerWidth)return;"
            + "var t='scale('+Math.min(innerWidth/w,innerHeight/h)+')';"
            + "if(s.style.transform===t)return;"
            + "s.style.transformOrigin='0 0';s.style.transform=t;"
            + "document.documentElement.style.overflow='hidden';document.body.style.overflow='hidden';};"
            + "addEventListener('resize',window.__jbFit);}window.__jbFit();})();";

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // innerWidth follows the view; refit once the page has caught up with the new size.
        if (ready) post(this::fitStage);
    }

    /** Seek the slide's GSAP timeline to the given local time (ms). */
    public void seekTo(long ms) {
        if (!ready) {
            pendingSeekMs = ms;
            return;
        }
        evaluateJavascript("Faditor.seek(" + Math.max(0, ms) + ");window.__jbFit&&__jbFit();", null);
    }

    public boolean isReady() {
        return ready;
    }
}
