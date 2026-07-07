package com.fadcam.ui.faditor.player;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.R;

/**
 * G6.3 / G6.4 (contract §5): when the inline preview's slot shrinks below a threshold —
 * because the timeline grab bar was dragged to its top extreme (G6.3) OR the device
 * rotated to landscape / anything else squeezed the column (G6.4) — the preview is
 * PROMOTED to a small draggable PiP window floating over the editor, and the timeline
 * band auto-grows to absorb the freed space (near-fullscreen timeline). When the slot
 * would be comfortable again (grab bar dragged back down, portrait restored, or the
 * PiP's expand button tapped), the preview DEMOTES back inline.
 *
 * <p>One signal drives everything: the PROSPECTIVE inline preview height, computed from
 * {@code editor_root} geometry (root height minus every other visible child). It equals
 * the real preview height while inline and the leftover gap while promoted, so both
 * G6.3 and G6.4 funnel through the same promote/demote hysteresis with no special
 * cases. The whole {@code player_container} (player + canvas + every overlay child) is
 * reparented as one unit — all overlay math is container-relative and the existing
 * {@code reflowPreview()} size-change listener re-fits the canvas automatically. The
 * PlayerView uses a TextureView surface, which survives reparenting.</p>
 */
public class PreviewPipController {

    private static final String TAG = "PreviewPip";

    /** Promote when the inline slot falls below this height. */
    private static final float PROMOTE_BELOW_DP = 110f;
    /** Demote when the slot (gap) grows to at least this height. Hysteresis vs promote. */
    private static final float DEMOTE_ABOVE_DP = 150f;
    /** Auto-fill the band when a smaller-than-demote gap is left behind the promoted preview. */
    private static final float FILL_SLACK_DP = 8f;
    /** PiP width as a fraction of the root width. */
    private static final float PIP_WIDTH_FRACTION = 0.42f;
    /** PiP content height cap as a fraction of the root height (portrait canvases). */
    private static final float PIP_MAX_HEIGHT_FRACTION = 0.40f;
    private static final float CHROME_HEIGHT_DP = 22f;

    /** Host hooks — all cheap, called on the main thread. */
    public interface Host {
        /** Canvas aspect (w/h), or <= 0 when unknown. */
        float canvasAspect();

        /** Current layer-band viewport cap in dp. */
        float getBandDp();

        /** Set the layer-band viewport cap in dp (in-memory; persistence stays with the grab bar). */
        void setBandDp(float dp);

        /** True while the user is actively dragging the timeline grab bar. */
        boolean isGrabBarDragging();
    }

    private final ViewGroup rootFrame;      // the activity's root FrameLayout
    private final LinearLayout editorRoot;  // the vertical column
    private final FrameLayout playerContainer;
    private final Host host;
    private final float density;

    private boolean promoted = false;
    private boolean mutating = false;

    // Saved inline placement for demotion.
    private int savedIndexInRoot = -1;
    @Nullable
    private ViewGroup.LayoutParams savedInlineLp;

    // The floating shell (chrome bar + reparented player container).
    @Nullable
    private LinearLayout pipShell;
    // Remembered PiP translation so re-promotions keep the user's spot.
    private float lastPipTx = 0f;
    private float lastPipTy = 0f;
    /** Gap size (px) at the last band auto-fill attempt. When a fill produced no layout
     *  change (row content shorter than the cap — growing the cap can't grow the view),
     *  the identical gap on the next pass skips the fill, breaking the layout loop. */
    private float lastFillGapPx = -1f;

    public PreviewPipController(@NonNull LinearLayout editorRoot,
                                @NonNull FrameLayout playerContainer,
                                @NonNull Host host) {
        this.editorRoot = editorRoot;
        this.playerContainer = playerContainer;
        this.rootFrame = (ViewGroup) editorRoot.getParent();
        this.host = host;
        this.density = editorRoot.getResources().getDisplayMetrics().density;
        editorRoot.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.post(this::evaluate));
    }

    public boolean isPromoted() {
        return promoted;
    }

    /**
     * The largest layer-band cap (dp) the grab bar may grow to right now without pushing
     * the controls below the timeline off-screen: current band + whatever the preview
     * slot / gap can still give up. The preview itself promotes to PiP before it hits zero.
     */
    public float maxBandDpFor(float currentBandDp) {
        return currentBandDp + Math.max(0f, prospectiveSlotPx()) / density;
    }

    // ── Core signal ──────────────────────────────────────────────────

    /**
     * Height (px) the inline preview slot has (inline) or would have (promoted): the
     * editor column's height minus every other visible child. Children as tall as the
     * root itself (match_parent overlays like the remux screen) are skipped so a
     * temporarily-visible overlay can't fake a zero slot. Returns -1 when unlaid-out.
     */
    private float prospectiveSlotPx() {
        int rootH = editorRoot.getHeight();
        if (rootH <= 0) return -1f;
        float others = 0f;
        for (int i = 0; i < editorRoot.getChildCount(); i++) {
            View c = editorRoot.getChildAt(i);
            if (c == playerContainer || c.getVisibility() == View.GONE) continue;
            int h = c.getHeight();
            if (h >= rootH) continue; // full-height overlay child — not part of the column budget
            others += h;
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                others += mlp.topMargin + mlp.bottomMargin;
            }
        }
        return rootH - others;
    }

    private void evaluate() {
        if (mutating) return;
        float slotPx = prospectiveSlotPx();
        if (slotPx < 0) return;
        float slotDp = slotPx / density;
        try {
            if (!promoted && slotDp < PROMOTE_BELOW_DP) {
                promote(slotDp);
            } else if (promoted && slotDp >= DEMOTE_ABOVE_DP) {
                demote();
            } else if (promoted && slotDp > FILL_SLACK_DP && !host.isGrabBarDragging()
                    && Math.abs(slotPx - lastFillGapPx) > 1f) {
                // Fill a leftover sub-demote gap so the timeline really is near-fullscreen
                // (in-memory only — the grab bar owns persistence). Skipped when the last
                // fill left the gap unchanged (viewport already fits all rows).
                lastFillGapPx = slotPx;
                host.setBandDp(host.getBandDp() + slotDp);
            }
        } catch (Exception e) {
            FLog.e(TAG, "evaluate failed (promoted=" + promoted + ")", e);
        }
    }

    // ── Promote / demote ─────────────────────────────────────────────

    private void promote(float slotDp) {
        if (promoted || pipShell != null) return;
        mutating = true;
        try {
            savedIndexInRoot = editorRoot.indexOfChild(playerContainer);
            savedInlineLp = playerContainer.getLayoutParams();
            if (savedIndexInRoot < 0) return;

            Context ctx = editorRoot.getContext();
            int rootW = rootFrame.getWidth();
            int rootH = rootFrame.getHeight();
            int pipW = Math.max((int) (140 * density), (int) (rootW * PIP_WIDTH_FRACTION));
            float aspect = host.canvasAspect();
            if (aspect <= 0f) aspect = 16f / 9f;
            int contentH = (int) (pipW / aspect);
            int maxContentH = (int) (rootH * PIP_MAX_HEIGHT_FRACTION);
            if (contentH > maxContentH) {
                contentH = maxContentH;
                pipW = (int) (contentH * aspect);
            }
            int chromeH = (int) (CHROME_HEIGHT_DP * density);

            LinearLayout shell = new LinearLayout(ctx);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setElevation(12 * density);
            shell.setBackgroundColor(0xFF141414);

            shell.addView(buildChrome(ctx, chromeH, shell));

            editorRoot.removeView(playerContainer);
            shell.addView(playerContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, contentH));

            FrameLayout.LayoutParams shellLp = new FrameLayout.LayoutParams(
                    pipW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            shellLp.topMargin = (int) (72 * density);
            shellLp.setMarginEnd((int) (10 * density));
            // Keep the PiP UNDER the drawers/overlays declared after editor_root in the
            // XML: inserting right after editor_root preserves their stacking.
            rootFrame.addView(shell, rootFrame.indexOfChild(editorRoot) + 1, shellLp);
            shell.setTranslationX(lastPipTx);
            shell.setTranslationY(lastPipTy);
            clampShellIntoRoot(shell);

            pipShell = shell;
            promoted = true;
            lastFillGapPx = -1f;
            // Absorb the freed slot into the timeline band (near-fullscreen timeline).
            if (!host.isGrabBarDragging() && slotDp > FILL_SLACK_DP) {
                host.setBandDp(host.getBandDp() + slotDp);
            }
            FLog.i(TAG, "PROMOTED preview → PiP (" + pipW + "x" + contentH + "px, slot was "
                    + (int) slotDp + "dp)");
        } finally {
            mutating = false;
        }
    }

    private void demote() {
        if (!promoted || pipShell == null) return;
        mutating = true;
        try {
            LinearLayout shell = pipShell;
            lastPipTx = shell.getTranslationX();
            lastPipTy = shell.getTranslationY();
            shell.removeView(playerContainer);
            rootFrame.removeView(shell);
            pipShell = null;

            ViewGroup.LayoutParams lp = savedInlineLp != null ? savedInlineLp
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            int index = Math.min(Math.max(savedIndexInRoot, 0), editorRoot.getChildCount());
            editorRoot.addView(playerContainer, index, lp);
            promoted = false;
            lastFillGapPx = -1f;
            FLog.i(TAG, "DEMOTED PiP → inline preview (index " + index + ")");
        } finally {
            mutating = false;
        }
    }

    /** Expand button: hand the preview a comfortable slot again; demotion follows on layout. */
    private void requestExpand() {
        // Balanced detent — mirrors G6.2's middle snap point.
        host.setBandDp(140f);
        editorRoot.requestLayout();
    }

    // ── Chrome (drag handle + expand button) ─────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    @NonNull
    private View buildChrome(@NonNull Context ctx, int chromeH, @NonNull View shell) {
        FrameLayout chrome = new FrameLayout(ctx);
        chrome.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, chromeH));
        chrome.setBackgroundColor(0xFF232323);

        View grip = new View(ctx);
        grip.setBackgroundColor(0xFF5A5A5A);
        FrameLayout.LayoutParams gripLp = new FrameLayout.LayoutParams(
                (int) (34 * density), (int) (4 * density), Gravity.CENTER);
        grip.setLayoutParams(gripLp);
        chrome.addView(grip);

        TextView expand = new TextView(ctx);
        expand.setText("open_in_full");
        try {
            Typeface icons = androidx.core.content.res.ResourcesCompat.getFont(
                    ctx, R.font.materialicons);
            if (icons != null) expand.setTypeface(icons);
        } catch (Exception ignored) {
            expand.setText("⤢"); // glyph fallback if the icon font is unavailable
        }
        expand.setTextColor(0xFFCCCCCC);
        expand.setTextSize(13);
        expand.setGravity(Gravity.CENTER);
        int pad = (int) (4 * density);
        expand.setPadding(pad, 0, pad, 0);
        FrameLayout.LayoutParams exLp = new FrameLayout.LayoutParams(
                (int) (30 * density), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        expand.setLayoutParams(exLp);
        expand.setOnClickListener(v -> requestExpand());
        chrome.addView(expand);

        chrome.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, baseTx, baseTy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        baseTx = shell.getTranslationX();
                        baseTy = shell.getTranslationY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        shell.setTranslationX(baseTx + (e.getRawX() - downRawX));
                        shell.setTranslationY(baseTy + (e.getRawY() - downRawY));
                        clampShellIntoRoot(shell);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.performClick();
                        return true;
                }
                return false;
            }
        });
        return chrome;
    }

    /** Keep the whole PiP shell inside the root frame. */
    private void clampShellIntoRoot(@NonNull View shell) {
        if (shell.getWidth() <= 0) {
            shell.post(() -> clampShellIntoRoot(shell));
            return;
        }
        float minTx = -(shell.getLeft());
        float maxTx = rootFrame.getWidth() - shell.getWidth() - shell.getLeft();
        float minTy = -(shell.getTop());
        float maxTy = rootFrame.getHeight() - shell.getHeight() - shell.getTop();
        shell.setTranslationX(Math.max(minTx, Math.min(maxTx, shell.getTranslationX())));
        shell.setTranslationY(Math.max(minTy, Math.min(maxTy, shell.getTranslationY())));
    }
}
