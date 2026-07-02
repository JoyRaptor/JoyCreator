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

    /** Load a slide's authored HTML file. Relative script srcs resolve to assets. */
    public void loadSlide(@NonNull File htmlFile) {
        ready = false;
        String html = SlideHtmlReader.read(htmlFile);
        if (html == null) return;
        loadDataWithBaseURL(ASSET_BASE, html, "text/html", "utf-8", null);
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
