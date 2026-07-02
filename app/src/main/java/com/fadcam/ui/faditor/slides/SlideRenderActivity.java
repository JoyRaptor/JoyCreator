package com.fadcam.ui.faditor.slides;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Headless activity that hosts a real WebView, drives a GSAP timeline frame by
 * frame via {@code Faditor.seek(ms)}, and captures each frame to a PNG sequence.
 *
 * <p>Android WebViews render unreliably when never attached to a real window, so
 * this hosts the WebView in a real (invisible) window and finishes itself when
 * capture completes — mirroring the headless-activity pattern used elsewhere in
 * this codebase. Communication with the caller is one-directional (Java drives
 * JS); completion is reported back through {@link SlideCaptureEngine}.</p>
 *
 * <p>It is also directly ADB-triggerable for Phase 0 capture-pipeline testing:</p>
 * <pre>
 * adb shell am start \
 *   -n com.fadcam.beta/com.fadcam.ui.faditor.slides.SlideRenderActivity \
 *   --es out_dir /sdcard/Download/slide_frames \
 *   --ei width 1080 --ei height 1920 --ei fps 30 --el duration_ms 2900
 * </pre>
 */
public class SlideRenderActivity extends Activity {

    private static final String TAG = "SlideRender";

    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_HTML_PATH = "html_path";
    public static final String EXTRA_OUT_DIR = "out_dir";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    /** When true (or no html_path given), renders the bundled sample slide. */
    public static final String EXTRA_USE_SAMPLE = "use_sample";

    /** Base URL so relative <script src="gsap.min.js"> resolves against assets. */
    private static final String ASSET_BASE = "file:///android_asset/faditor/";
    private static final String READY_TITLE = "FADITOR_READY";

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable private String requestId;
    private File outDir;
    private int width;
    private int height;
    private int fps;
    private long durationMs;

    private int frameCount;
    private int currentFrame;
    private boolean started;
    private boolean finishedReported;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        makeWindowInvisible();

        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        width = Math.max(2, getIntent().getIntExtra(EXTRA_WIDTH, 1080));
        height = Math.max(2, getIntent().getIntExtra(EXTRA_HEIGHT, 1920));
        fps = Math.max(1, getIntent().getIntExtra(EXTRA_FPS, 30));
        durationMs = Math.max(100, getIntent().getLongExtra(EXTRA_DURATION_MS, 2900));

        String outPath = getIntent().getStringExtra(EXTRA_OUT_DIR);
        if (outPath == null || outPath.isEmpty()) {
            outDir = new File(getExternalFilesDir(null), "slide_frames");
        } else {
            outDir = new File(outPath);
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            failAndFinish("Could not create out dir: " + outDir);
            return;
        }

        String html = loadHtml();
        if (html == null) {
            failAndFinish("No HTML to render");
            return;
        }

        // Total number of frames including the final frame at durationMs.
        frameCount = (int) Math.max(1, Math.round((durationMs / 1000.0) * fps));

        setupWebView(html);
    }

    /**
     * Shrink the window to a single off-screen pixel and make it non-interactive
     * so the headless render never visibly flashes over the editor. The WebView is
     * still measured/laid out to the full capture size manually (see
     * {@link #forceLayout()}), so frame capture is unaffected.
     */
    private void makeWindowInvisible() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.width = 1;
            lp.height = 1;
            lp.gravity = Gravity.START | Gravity.TOP;
            lp.x = 0;
            lp.y = 0;
            lp.dimAmount = 0f;
            lp.alpha = 0f;
            getWindow().setAttributes(lp);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        } catch (Exception e) {
            FLog.w(TAG, "Could not shrink render window", e);
        }
    }

    @Nullable
    private String loadHtml() {
        boolean useSample = getIntent().getBooleanExtra(EXTRA_USE_SAMPLE, false);
        String htmlPath = getIntent().getStringExtra(EXTRA_HTML_PATH);
        if (!useSample && htmlPath != null && !htmlPath.isEmpty()) {
            try (InputStream is = new FileInputStream(new File(htmlPath))) {
                return readStream(is);
            } catch (Exception e) {
                FLog.e(TAG, "Failed to read html_path: " + htmlPath, e);
                return null;
            }
        }
        // Fallback: bundled sample slide (Phase 0 / hard fallback).
        try (InputStream is = getAssets().open("faditor/sample_slide.html")) {
            return readStream(is);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to read bundled sample slide", e);
            return null;
        }
    }

    @NonNull
    private static String readStream(@NonNull InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    @SuppressWarnings({"SetJavaScriptEnabled"})
    private void setupWebView(@NonNull String html) {
        webView = new WebView(this);
        // Hardware-accelerated WebViews don't reliably hand pixels to draw().
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        webView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // Map CSS px 1:1 to layout px regardless of device density.
        webView.setInitialScale(100);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (READY_TITLE.equals(title) && !started) {
                    started = true;
                    handler.post(SlideRenderActivity.this::beginCapture);
                }
            }
        });

        FrameLayout root = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        root.addView(webView, lp);
        setContentView(root,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        webView.loadDataWithBaseURL(ASSET_BASE, html, "text/html", "utf-8", null);

        // Safety: if the page never signals ready, bail after a timeout.
        handler.postDelayed(() -> {
            if (!started) failAndFinish("Timed out waiting for FADITOR_READY");
        }, 15000);
    }

    private void forceLayout() {
        webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        webView.layout(0, 0, width, height);
    }

    private void beginCapture() {
        currentFrame = 0;
        forceLayout();
        captureNextFrame();
    }

    private void captureNextFrame() {
        if (currentFrame >= frameCount) {
            successAndFinish();
            return;
        }
        long ms = Math.round((currentFrame * 1000.0) / fps);
        if (ms > durationMs) ms = durationMs;
        final long seekMs = ms;
        webView.evaluateJavascript("Faditor.seek(" + seekMs + ");", value -> {
            // Don't trust the eval callback alone for paint. Post twice so the
            // WebView has actually drawn the new state before we read pixels.
            handler.post(() -> handler.post(this::captureCurrentFrameAndAdvance));
        });
    }

    private void captureCurrentFrameAndAdvance() {
        try {
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(bmp);
            webView.draw(canvas);

            File out = new File(outDir, String.format(java.util.Locale.US,
                    "frame%04d.png", currentFrame));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
        } catch (Throwable t) {
            FLog.e(TAG, "Frame capture failed at " + currentFrame, t);
            failAndFinish("Frame capture failed: " + t.getMessage());
            return;
        }
        currentFrame++;
        captureNextFrame();
    }

    private void successAndFinish() {
        if (finishedReported) return;
        finishedReported = true;
        FLog.i(TAG, "Captured " + frameCount + " frames to " + outDir);
        SlideCaptureEngine.publishResult(requestId, true, null, frameCount);
        cleanupAndFinish();
    }

    private void failAndFinish(@NonNull String message) {
        if (finishedReported) return;
        finishedReported = true;
        FLog.e(TAG, "SlideRender failed: " + message);
        SlideCaptureEngine.publishResult(requestId, false, message, currentFrame);
        cleanupAndFinish();
    }

    private void cleanupAndFinish() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.destroy();
            } catch (Exception ignored) { }
            webView = null;
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (!finishedReported && requestId != null) {
            SlideCaptureEngine.publishResult(requestId, false, "Activity destroyed", currentFrame);
            finishedReported = true;
        }
        super.onDestroy();
    }
}
