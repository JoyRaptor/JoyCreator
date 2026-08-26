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
    /** H2: release the drag this close to a root edge and the shell docks to that edge. */
    private static final float DOCK_SNAP_DP = 56f;

    /** Host hooks — all cheap, called on the main thread. */
    public interface Host {
        /** Canvas aspect (w/h), or <= 0 when unknown. */
        float canvasAspect();

        /** Current layer-band viewport cap in dp. */
        float getBandDp();

        /** Set the layer-band viewport cap in dp (in-memory; persistence stays with the grab bar). */
        void setBandDp(float dp);

        /**
         * Same, for the AUTOMATIC gap-absorb after a promote — which may exceed the rows'
         * own height, because a near-fullscreen timeline is mostly empty space below the last
         * row. A user drag must NOT: clamping it is what keeps the grab bar free of dead
         * travel. Defaults to the clamped setter so a host that does not distinguish them
         * behaves exactly as before.
         */
        default void setBandDpForFill(float dp) { setBandDp(dp); }

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
    /**
     * H2 (SPEC_20260824_HORIZONTAL_REFLOW): which edge the shell is parked at —
     * -1 left, 0 nowhere (free position), +1 right. Remembered across promote/demote
     * within the session: a re-promotion re-docks at that edge, vertically centred,
     * instead of restoring lastPipTx/lastPipTy (a docked spot is derived from CURRENT
     * geometry; a remembered translation is only right for the geometry it was made in).
     */
    private int dockedEdge = 0;
    /** Gap size (px) at the last band auto-fill attempt. When a fill produced no layout
     *  change (row content shorter than the cap — growing the cap can't grow the view),
     *  the identical gap on the next pass skips the fill, breaking the layout loop. */
    private float lastFillGapPx = -1f;
    /** Last slot written to the log, so the trace only fires on real movement. */
    private float lastLoggedSlotDp = Float.NaN;

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
        FLog.i(TAG, "attached (playerContainer is child #"
                + editorRoot.indexOfChild(playerContainer) + " of editor_root)");
    }

    public boolean isPromoted() {
        return promoted;
    }

    /** Reasons already reported, so a per-frame guard says its piece once and then shuts up. */
    private final java.util.Set<String> whyNotSeen = new java.util.HashSet<>();

    /** Report a refusal ONCE per distinct reason. See the note in {@link #promote}. */
    private void whyNot(@NonNull String reason) {
        if (whyNotSeen.add(reason)) FLog.w(TAG, "promote SKIPPED - " + reason);
    }

    /**
     * The largest band cap that can ever do anything: the whole editor column's height.
     *
     * <p>THE BAND FILL IS ADDITIVE AND WAS UNBOUNDED. Both fill sites do
     * {@code setBandDp(getBandDp() + slotDp)}, and the only brake was
     * {@link #lastFillGapPx} skipping a repeat when the gap landed within 1px of the last
     * one. Any wobble in the measured gap defeats that, so the cap ratchets upward; the
     * grab bar's ACTION_UP then persists whatever it has grown to, and the inflation
     * survives restarts. Found on JoyRaptor's Note 9 at <b>820dp</b> on a 1128dp-tall screen.
     *
     * <p>Past the content height the cap stops being the binding constraint, so dragging
     * up changes a number nothing can render and dragging down does nothing until it has
     * travelled all the way back — the grab bar reads as completely dead in both
     * directions ("i couldent grab the bar either"). Clamping here is what makes the
     * runaway impossible; {@code sanitizeBandDp} on the restore path is what rescues
     * installs that already ran away.</p>
     */
    public float maxUsefulBandDp() {
        int h = editorRoot.getHeight();
        if (h <= 0) h = editorRoot.getResources().getDisplayMetrics().heightPixels;
        return h / density;
    }

    /** Additive band fill, clamped so it can never ratchet past what the column can show. */
    private void fillBandBy(float slotDp) {
        host.setBandDpForFill(Math.min(host.getBandDp() + slotDp, maxUsefulBandDp()));
    }

    /**
     * The largest layer-band cap (dp) the grab bar may grow to right now without pushing
     * the controls below the timeline off-screen: current band + whatever the preview
     * slot / gap can still give up. The preview itself promotes to PiP before it hits zero.
     */
    public float maxBandDpFor(float currentBandDp) {
        float slot = prospectiveSlotPx();
        // UNKNOWN IS NOT ZERO. prospectiveSlotPx returns NaN before the column has been laid
        // out, and clamping to "current + 0" on that answer pins the band at exactly its
        // present height — the timeline then refuses to grow and the grab bar feels dead,
        // which is indistinguishable from it not being draggable at all. No measurement means
        // no ceiling; the next MOVE event will have one.
        //
        // BOTH READERS OF prospectiveSlotPx MUST AGREE ON THE SENTINEL. It used to be -1, and
        // this guard caught it via `slot < 0f`. When it became NaN, evaluate() was updated and
        // THIS WAS NOT — and NaN fails `< 0f`, so an unmeasured root fell through to
        // `currentBandDp + NaN` = NaN. The caller does Math.min(targetDp, thatNaN), and
        // Math.min returns NaN if EITHER argument is NaN, so the band cap became NaN and the
        // grab bar died. A rotation re-measures the root, which is why it presented as
        // "landscape lost the PiP and the drawer won't reach fullscreen" — one stale sentinel
        // check, both symptoms. If the sentinel ever changes again, grep every caller.
        if (Float.isNaN(slot) || slot < 0f) return Float.MAX_VALUE;
        return currentBandDp + slot / density;
    }

    // ── Core signal ──────────────────────────────────────────────────

    /**
     * Height (px) the inline preview slot has (inline) or would have (promoted): the
     * editor column's height minus every other visible child. Children as tall as the
     * root itself (match_parent overlays like the remux screen) are skipped so a
     * temporarily-visible overlay can't fake a zero slot. Returns -1 when unlaid-out.
     */
    /**
     * The height the inline preview WOULD get: the root minus every other visible child.
     *
     * <p>Returns {@link Float#NaN} — not a negative number — when the root has not been measured
     * yet. A NEGATIVE result is a real, meaningful answer: the column's other children already
     * overflow the root, so the preview has less than zero space. Landscape produces exactly
     * that, and it is the strongest possible case for promoting.</p>
     */
    private float prospectiveSlotPx() {
        int rootH = editorRoot.getHeight();
        if (rootH <= 0) return Float.NaN;
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

    /**
     * Re-run the promote/demote/fill decision after the grab bar is released.
     *
     * <p>Both places that absorb the freed preview slot into the timeline band are gated on
     * {@code !host.isGrabBarDragging()} — promote()'s band-fill and evaluate()'s fill branch —
     * so a promotion that happens DURING a drag deliberately leaves the gap alone. Something
     * has to close it once the finger is up, and the layout listener cannot be relied on: if
     * the release lands far from a detent, snapTimelineBandToDetent changes nothing, no layout
     * pass runs, and evaluate() never fires again. The freed height then just sits there as
     * dead black space under the timeline (JoyRaptor, 2026-08-24: "the bottom of the app seems
     * raised up almost two centimeters ... that's just wasted space").
     *
     * <p>Safe to call at any time: evaluate() is idempotent and guarded by {@code mutating}.</p>
     */
    public void onGrabBarReleased() {
        editorRoot.post(this::evaluate);
    }

    private void evaluate() {
        if (mutating) return;
        float slotPx = prospectiveSlotPx();
        // NaN is "not measured yet". A negative slot is NOT: it means the other children already
        // overflow the root and the preview has less than zero room.
        //
        // This used to read `if (slotPx < 0) return;`, which conflated the two and is why the
        // landscape PiP disappeared. Rotating produced slotPx = -51 on a 1440px root (children
        // summed to 1491), so the one case that most needed promoting was the one case that
        // returned early. The timeline still grew, because the band-fill runs from the grab bar
        // as well, which is why the symptom read as "the window is gone" rather than "landscape
        // is broken" (JoyRaptor, 2026-08-21: "Where is that feature now? Because it seems to have
        // gone").
        //
        // Everything downstream is already safe for a negative slot: promote()'s band-fill and
        // the fill branch below are both gated on slotDp > FILL_SLACK_DP, so a negative value
        // promotes without ever adding negative height to the timeline band.
        if (Float.isNaN(slotPx)) return;
        float slotDp = slotPx / density;
        // Trace the ONE signal everything keys off, but only when it actually moves
        // -- enough to explain a missing promote without spamming every layout pass.
        if (Math.abs(slotDp - lastLoggedSlotDp) > 20f) {
            lastLoggedSlotDp = slotDp;
            FLog.i(TAG, "slot " + (int) slotDp + "dp (promote<" + (int) PROMOTE_BELOW_DP
                    + ", demote>=" + (int) DEMOTE_ABOVE_DP + ", promoted=" + promoted + ")");
        }
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
                fillBandBy(slotDp);
            }
        } catch (Exception e) {
            FLog.e(TAG, "evaluate failed (promoted=" + promoted + ")", e);
        }
    }

    // ── Promote / demote ─────────────────────────────────────────────

    private void promote(float slotDp) {
        // WHY-NOT DIAGNOSTICS. On JoyRaptor's Note 9 the PiP never appears — not in landscape, not
        // at the grab bar's top extreme — and logcat carries NO PreviewPip lines at all: no
        // PROMOTED, no DEMOTED, no "setup failed". That silence is the symptom. Every guard
        // below could return without a word, so a working controller and a controller that
        // bails on its first guard looked identical from the outside. Each now says so once
        // per reason (whyNotLogged), which is enough to name the cause in one session without
        // spamming a per-frame path.
        if (promoted || pipShell != null) {
            whyNot("already promoted (promoted=" + promoted + " shell=" + (pipShell != null) + ")");
            return;
        }
        mutating = true;
        try {
            savedIndexInRoot = editorRoot.indexOfChild(playerContainer);
            savedInlineLp = playerContainer.getLayoutParams();
            if (savedIndexInRoot < 0) {
                // A SILENT RETURN HERE LOOKS EXACTLY LIKE THE FEATURE NOT EXISTING: no
                // PiP, no log, nothing to grep. Say so instead.
                FLog.w(TAG, "promote SKIPPED - player_container is not a child of editor_root");
                return;
            }

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
            // Elevation 8dp: ABOVE all zero-elevation editor content, but BELOW the
            // transcript panel's 12dp (activity_faditor_editor.xml) so a docked PiP is a
            // layout citizen — the transcript drawer slides OVER it, per spec H2. The
            // shell also sits after editor_root in rootFrame, so equal-Z order alone would
            // already put it above the whole editor column; elevation only has to lose to
            // the one overlay that must win (the panel).
            shell.setElevation(8 * density);
            shell.setBackgroundColor(0xFF141414);

            shell.addView(buildChrome(ctx, chromeH, shell));

            editorRoot.removeView(playerContainer);
            // DROP THE DRAWER REFLOW'S TRANSFORM. The editor scales and lowers this very
            // container to clear an open FX drawer; reparenting it carried that transform into
            // the floating PiP, so promoting with a drawer open rendered the preview at 60% and
            // shoved a couple of hundred pixels out of its own chrome box. Only the drawer's
            // height listener ever reset it, and the drawer is no longer above this view.
            playerContainer.setScaleX(1f);
            playerContainer.setScaleY(1f);
            playerContainer.setTranslationY(0f);
            // H1 (SPEC_20260824_HORIZONTAL_REFLOW): same for the horizontal axis — a stale
            // transcript reflow shift must not ride into the popped-out shell.
            playerContainer.setTranslationX(0f);
            // The transcript panel and its reopen tab are CHILDREN of this container and
            // hold station against the shift with their own +shift translation; that offset
            // meant something under the old parent, so zero it too or the open drawer lands
            // shifted inside its new shell.
            resetTranscriptStation();
            shell.addView(playerContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, contentH));

            FrameLayout.LayoutParams shellLp = new FrameLayout.LayoutParams(
                    pipW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            shellLp.topMargin = (int) (72 * density);
            shellLp.setMarginEnd((int) (10 * density));
            // Keep the PiP UNDER the drawers/overlays declared after editor_root in the
            // XML: inserting right after editor_root preserves their stacking.
            rootFrame.addView(shell, rootFrame.indexOfChild(editorRoot) + 1, shellLp);
            if (dockedEdge != 0) {
                // H2: re-dock at the remembered edge rather than restoring a translation
                // that was computed against the previous promotion's geometry. The shell
                // starts INVISIBLE so the one frame at its gravity-END spawn spot (before
                // layout lets dockTo compute the real position) is never seen.
                shell.setVisibility(View.INVISIBLE);
                shell.post(() -> {
                    if (pipShell != shell) return;
                    dockTo(shell, dockedEdge, false);
                    shell.setVisibility(View.VISIBLE);
                });
            } else {
                shell.setTranslationX(lastPipTx);
                shell.setTranslationY(lastPipTy);
            }
            clampShellIntoRoot(shell);

            pipShell = shell;
            promoted = true;
            lastFillGapPx = -1f;
            // Absorb the freed slot into the timeline band (near-fullscreen timeline).
            if (!host.isGrabBarDragging() && slotDp > FILL_SLACK_DP) {
                fillBandBy(slotDp);
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
            // Back inline at identity. Any reflow the drawer wants is re-applied by its own
            // height listener on the next layout; inheriting the promoted shell's state here
            // would be inheriting a transform that meant something in a different parent.
            playerContainer.setScaleX(1f);
            playerContainer.setScaleY(1f);
            playerContainer.setTranslationY(0f);
            // H1: horizontal axis too — never inherit a stale shift into the inline slot.
            playerContainer.setTranslationX(0f);
            resetTranscriptStation();
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
            float movedSquared;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        baseTx = shell.getTranslationX();
                        baseTy = shell.getTranslationY();
                        movedSquared = 0f;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        movedSquared = Math.max(movedSquared,
                                (e.getRawX() - downRawX) * (e.getRawX() - downRawX)
                                        + (e.getRawY() - downRawY) * (e.getRawY() - downRawY));
                        shell.setTranslationX(baseTx + (e.getRawX() - downRawX));
                        shell.setTranslationY(baseTy + (e.getRawY() - downRawY));
                        clampShellIntoRoot(shell);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.performClick();
                        // H2: released near a left/right edge → dock there, vertically
                        // centred. A mere TAP never re-docks: the default spawn spot sits
                        // inside the snap window, so an accidental tap would otherwise
                        // yank the PiP to the edge (audit 084b1bc5 #5).
                        if (movedSquared > Math.pow(2 * android.view.ViewConfiguration
                                .get(v.getContext()).getScaledTouchSlop(), 2)) {
                            dockedEdge = maybeDock(shell, true);
                        }
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

    // ── H2 · PiP edge parking ────────────────────────────────────────

    /**
     * If the shell was released within {@link #DOCK_SNAP_DP} of the root's left or right
     * edge, park it flush at THAT edge, vertically centred, and return the edge (-1/+1).
     * Otherwise leave it alone and return 0. When both edges qualify (a shell wider than
     * the snap window can straddle), the nearer edge wins.
     *
     * @param animate true for the release snap (150ms decelerate); false when re-applying
     *                a remembered dock on re-promotion, where the position should simply
     *                be correct at first layout.
     */
    private int maybeDock(@NonNull View shell, boolean animate) {
        int rootW = rootFrame.getWidth();
        if (rootW <= 0 || shell.getWidth() <= 0) {
            // Not measured yet — defer exactly like clampShellIntoRoot does.
            final View s = shell;
            s.post(() -> { if (pipShell == s) dockedEdge = maybeDock(s, animate); });
            return dockedEdge;
        }
        float snapPx = DOCK_SNAP_DP * density;
        float screenLeft = shell.getLeft() + shell.getTranslationX();
        float distL = screenLeft;
        float distR = rootW - (screenLeft + shell.getWidth());
        if (distL > snapPx && distR > snapPx) return 0;
        int edge = distL <= distR ? -1 : 1;
        dockTo(shell, edge, animate);
        return edge;
    }

    /**
     * Park the shell flush at {@code edge}, vertically centred in the root. Docking is a
     * LAYOUT citizen's position: flush to the edge and centred, so a drawer sliding in
     * from that side lands over the parked PiP rather than shoving it.
     */
    private void dockTo(@NonNull View shell, int edge, boolean animate) {
        if (shell.getWidth() <= 0 || rootFrame.getWidth() <= 0) {
            final View s = shell;
            final int e = edge;
            final boolean a = animate;
            s.post(() -> { if (pipShell == s) dockTo(s, e, a); });
            return;
        }
        float tx = edge < 0 ? -shell.getLeft()
                : rootFrame.getWidth() - shell.getWidth() - shell.getLeft();
        float ty = Math.max(0f,
                (rootFrame.getHeight() - shell.getHeight()) / 2f) - shell.getTop();
        if (animate) {
            shell.animate().translationX(tx).translationY(ty).setDuration(150)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            shell.animate().cancel();
            shell.setTranslationX(tx);
            shell.setTranslationY(ty);
        }
    }

    /**
     * H1 support: zero the transcript panel / reopen tab station-holding translations.
     * They are children of {@code playerContainer}; their +shift offset is meaningful only
     * while that container sits inline with an active reflow, so promote and demote both
     * clear it alongside the container's own transform.
     */
    private void resetTranscriptStation() {
        View tp = playerContainer.findViewById(R.id.transcript_panel);
        if (tp != null) tp.setTranslationX(0f);
        View tab = playerContainer.findViewById(R.id.transcript_reopen_tab);
        if (tab != null) tab.setTranslationX(0f);
    }
}

