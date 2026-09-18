package com.fadcam.ui.faditor.player;

import com.fadcam.ui.faditor.Studio;

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
    /**
     * How close to a wall counts as "pushed against it", and therefore as DOCKING.
     *
     * <p>Deliberately tiny. Floating and docked are different states, and only docking
     * reflows the editor, so the gesture that triggers it has to be one the user meant.
     * JoyRaptor: "the snapping to edge would be a very small snap. Not very powerful at all
     * because I want people to have control of where they place it so they can move it
     * around as they work."
     *
     * <p>At 56dp the window was yanked to an edge — and, once docking reflowed the column,
     * would have rearranged the whole editor — from a third of a centimetre away, which made
     * free placement anywhere near an edge impossible. {@link #clampShellIntoRoot} already
     * stops the shell at the wall, so a deliberate shove lands at distance ~0 and this
     * threshold only needs to forgive the last pixel or two of travel.</p>
     */
    private static final float DOCK_SNAP_DP = 12f;

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

        /**
         * The preview container has just been re-parented or re-stationed (promote, demote,
         * dock, undock). Anything the host holds against the container's station - the
         * transcript reflow's counter-translations, above all - must be re-applied now.
         *
         * <p>{@link #resetTranscriptStation()} zeroes those translations because a station
         * that meant something inline is meaningless in a popped-out shell. Nothing put them
         * back: the container kept its reflow shift while the transcript panel lost its
         * counter-shift, so the panel slid 208px left of where it belonged and its right edge
         * ran off under the clip. Rotation is the common trigger, which is what made it look
         * intermittent - "did a screen rotate and came back and the black is back".
         */
        void onPreviewStationChanged();
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
        host.setBandDp(Math.min(host.getBandDp() + slotDp, maxUsefulBandDp()));
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
    /** Root height at the last column dump, so a rotation triggers exactly one. */
    private int lastDumpedRootH = -1;

    /**
     * Print the column's ACTUAL arithmetic, once per root-height change.
     *
     * <p>Five theories about this subsystem have now died against JoyRaptor's device, the last
     * two after being shipped: a band-cap split that regressed landscape promote, and a slide
     * refit that changed the project's aspect ratio. Every one of them was an argument about
     * what the geometry must be doing. Nobody had ever looked at the geometry.
     *
     * <p>This prints each child's height and margins against the root, so "the drawer will not
     * reach the top" and "rotating with a tall timeline pushes the toolbox off the bottom"
     * become arithmetic instead of hypotheses. A root-height change IS a rotation, which is
     * the exact moment both symptoms appear, and gating on it keeps this off the per-frame
     * path entirely.</p>
     */
    private void dumpColumn(int rootH) {
        if (rootH == lastDumpedRootH) return;
        lastDumpedRootH = rootH;
        StringBuilder sb = new StringBuilder("COLUMN rootH=").append(rootH)
                .append("px (").append((int) (rootH / density)).append("dp) promoted=")
                .append(promoted);
        int total = 0;
        for (int i = 0; i < editorRoot.getChildCount(); i++) {
            View c = editorRoot.getChildAt(i);
            String name;
            try {
                name = c.getId() == View.NO_ID ? "(no-id)"
                        : c.getResources().getResourceEntryName(c.getId());
            } catch (Exception e) {
                name = "(id?)";
            }
            int h = c.getHeight(), mt = 0, mb = 0;
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                mt = ((ViewGroup.MarginLayoutParams) lp).topMargin;
                mb = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
            }
            boolean isPlayer = c == playerContainer;
            boolean gone = c.getVisibility() == View.GONE;
            boolean skippedAsOverlay =
                    lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT;
            if (!isPlayer && !gone && !skippedAsOverlay) total += h + mt + mb;
            sb.append("\n    [").append(i).append("] ").append(name)
                    .append(" h=").append(h).append(" m=").append(mt).append('/').append(mb)
                    .append(isPlayer ? "  <- PLAYER (excluded)" : "")
                    .append(gone ? "  <- GONE" : "")
                    .append(skippedAsOverlay && !isPlayer ? "  <- match_parent, overlay" : "");
        }
        sb.append("\n    others=").append(total).append("px  slot=").append(rootH - total)
                .append("px (").append((int) ((rootH - total) / density)).append("dp)");
        // ONE LEVEL DEEPER for controls_section. The column dump says it fills the root,
        // which is true but not actionable: the tool row lives INSIDE it as a wrap_content
        // child (faditor_tools_overlay), so when its siblings there eat the space it is
        // squeezed to ZERO height and vanishes from the view tree altogether — not pushed
        // off-screen, ABSENT, which is exactly what the uiautomator dump showed. Shrinking
        // the timeline band by the column's overflow did not bring it back, so the band is
        // not the only thing competing in there. This names what is.
        for (int i = 0; i < editorRoot.getChildCount(); i++) {
            View c = editorRoot.getChildAt(i);
            if (!(c instanceof ViewGroup) || c.getVisibility() == View.GONE) continue;
            String nm;
            try {
                nm = c.getId() == View.NO_ID ? ""
                        : c.getResources().getResourceEntryName(c.getId());
            } catch (Exception e) {
                nm = "";
            }
            if (!"controls_section".equals(nm)) continue;
            ViewGroup g = (ViewGroup) c;
            sb.append("\n  controls_section h=").append(g.getHeight()).append("px:");
            int inner = 0;
            for (int j = 0; j < g.getChildCount(); j++) {
                View k = g.getChildAt(j);
                String kn;
                try {
                    kn = k.getId() == View.NO_ID ? "(no-id)"
                            : k.getResources().getResourceEntryName(k.getId());
                } catch (Exception e) {
                    kn = "(id?)";
                }
                boolean kgone = k.getVisibility() == View.GONE;
                if (!kgone) inner += k.getHeight();
                sb.append("\n      [").append(j).append("] ").append(kn)
                        .append(" h=").append(k.getHeight())
                        .append(kgone ? "  <- GONE" : "")
                        .append(k.getHeight() == 0 && !kgone ? "  <- SQUEEZED TO ZERO" : "");
            }
            sb.append("\n      children total=").append(inner).append("px");
        }
        FLog.i(TAG, sb.toString());
        reclampBandToColumn(rootH, total);
    }

    /**
     * A band sized for portrait does not fit landscape, and nothing was shrinking it.
     *
     * <p>The grab bar clamps every drag through {@code maxBandDpFor}, so a band the user
     * sets by hand always fits the column it was set in. Rotation changes the column without
     * touching the band: on JoyRaptor's Note 9 a 495dp band sized against portrait's 1127dp
     * column survived into landscape's 548dp one, where the timeline alone then rendered at
     * the full 1080px root height.
     *
     * <pre>
     *   rootH=1080px   editor_top_bar 110 + timeline_grab_bar 55 + controls_section 1080
     *   others=1245px  -- 165px MORE than the column holds
     * </pre>
     *
     * <p>LinearLayout does not shrink a wrap_content child to fit, so the 165px come off the
     * bottom and take the tool row with them — "a tall timeline in portrait rotates to a
     * landscape that does not show the toolbox, but a short timeline in portrait rotates to
     * show the toolbox". Short timelines were fine because they never overflowed.
     *
     * <p>Shrinking by exactly the overflow is the same arithmetic the drag clamp already
     * does, applied at the one moment nothing was doing it. Only ever REDUCES: growing the
     * band here would fight the user's own setting, and the portrait value is restored by
     * the next drag rather than remembered, which is the existing behaviour for every other
     * orientation-dependent size.</p>
     */
    private void reclampBandToColumn(int rootH, int others) {
        if (mutating || host.isGrabBarDragging()) return;
        int overflowPx = others - rootH;
        if (overflowPx <= 0) return;
        float cur = host.getBandDp();
        float target = cur - (overflowPx / density);
        if (!(target > 0f) || target >= cur) return;
        FLog.i(TAG, "column overflows by " + overflowPx + "px — band " + (int) cur
                + "dp -> " + (int) target + "dp so the rows below stay on screen");
        host.setBandDp(target);
    }

    private float prospectiveSlotPx() {
        int rootH = editorRoot.getHeight();
        if (rootH > 0) dumpColumn(rootH);
        if (rootH <= 0) return Float.NaN;
        float others = 0f;
        for (int i = 0; i < editorRoot.getChildCount(); i++) {
            View c = editorRoot.getChildAt(i);
            if (c == playerContainer || c.getVisibility() == View.GONE) continue;
            int h = c.getHeight();
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            // AN OVERLAY IS DECLARED, NOT MEASURED. This used to skip any child as tall as the
            // root — meaning to skip match_parent overlays like remux_progress_overlay, which
            // sit on top of the column rather than inside its budget.
            //
            // But controls_section is layout_height=wrap_content and in LANDSCAPE it grows to
            // exactly the column height, so the measured test called the TIMELINE an overlay
            // and dropped it from the budget. Measured on JoyRaptor's Note 9: rootH=1080,
            // controls_section h=1080, others collapses to 165px and the slot reads 915px
            // (464dp) — nowhere near the 110dp trigger, so landscape could not promote.
            //
            // It is also why growing the band broke landscape: a taller timeline reaches root
            // height sooner, trips this guard, and the slot jumps from negative to 464dp. The
            // band change was not wrong on its own terms; it walked the column into this test.
            //
            // layout_height tells the two apart with no ambiguity: MATCH_PARENT is an overlay,
            // anything else is in the flow however tall it happens to render.
            if (lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) continue;
            others += h;
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
            shell.setBackgroundColor(Studio.PANEL);

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
        host.onPreviewStationChanged();
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
            applyParkInset(0, 0);   // no shell, nothing parked: give the column its full width
            removeDockResizeHandle();
            editorRoot.addView(playerContainer, index, lp);
            promoted = false;
            lastFillGapPx = -1f;
            FLog.i(TAG, "DEMOTED PiP → inline preview (index " + index + ")");
        } finally {
            mutating = false;
        }
        host.onPreviewStationChanged();
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
        chrome.setBackgroundColor(Studio.RAISED);

        View grip = new View(ctx);
        grip.setBackgroundColor(Studio.INK_OFF);
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
        expand.setTextColor(Studio.INK_DIM);
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

        // UNDOCK: back to a free-floating window without leaving PiP.
        // JoyRaptor: "perhaps at the top there should be a pop-out button to bring it back into
        // pop-out mode. Because sometimes a little tiny thing is all you need to see when you
        // really have a big project with a lot of different lanes." Docked and floating are
        // both useful states, so there has to be a way back from the one that reflows the
        // editor -- otherwise docking is a trapdoor.
        TextView undock = new TextView(ctx);
        undock.setText("picture_in_picture");
        try {
            Typeface icons = androidx.core.content.res.ResourcesCompat.getFont(
                    ctx, R.font.materialicons);
            if (icons != null) undock.setTypeface(icons);
        } catch (Exception ignored) {
            undock.setText("⇲");
        }
        undock.setTextColor(Studio.INK_DIM);
        undock.setTextSize(13);
        undock.setGravity(Gravity.CENTER);
        undock.setPadding(pad, 0, pad, 0);
        undock.setLayoutParams(new FrameLayout.LayoutParams(
                (int) (30 * density), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        undock.setOnClickListener(v -> undock());
        undock.setVisibility(dockedEdge == 0 ? View.GONE : View.VISIBLE);
        chrome.addView(undock);
        undockButton = undock;

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
                        showDockHint(edgeUnderShell(shell));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.performClick();
                        showDockHint(0);   // the preview band never outlives the drag
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
    /** Docked pane width as a fraction of the root, dragged by the resize handle. */
    private float dockWidthFraction = PIP_WIDTH_FRACTION;
    /** The vertical grip on a docked pane's inner edge. Null while floating. */
    @Nullable private View dockResizeHandle;

    /** Narrowest and widest a docked preview may be dragged, as a fraction of the root. */
    private static final float DOCK_MIN_FRACTION = 0.18f;
    private static final float DOCK_MAX_FRACTION = 0.60f;

    /**
     * The docked pane's own grab bar — the landscape twin of the portrait one.
     *
     * <p>JoyRaptor: "there should be a resize handle so you can change how much of ratio preview
     * to timeline the screen real-estate, just like the portrait mode." Portrait splits the
     * screen vertically and lets the grab bar move the divide; a docked pane splits it
     * horizontally and had no equivalent, so the preview/timeline balance was whatever the
     * PiP happened to be. Same idea, rotated ninety degrees.</p>
     *
     * <p>Lives on the pane's INNER edge — the one facing the editor — because that is the
     * divider being moved. Dragging it resizes the shell and re-insets the column in the same
     * frame, so the two never disagree about where the boundary is.</p>
     */
    @SuppressLint("ClickableViewAccessibility")
    private void installDockResizeHandle(@NonNull LinearLayout shell, int edge) {
        removeDockResizeHandle();
        View grip = new View(shell.getContext());
        grip.setBackgroundColor(0x66FFFFFF);
        int gripW = (int) (6 * density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                gripW, ViewGroup.LayoutParams.MATCH_PARENT,
                edge < 0 ? Gravity.END : Gravity.START);
        rootFrame.addView(grip, lp);
        grip.setTranslationX(edge < 0
                ? shell.getWidth() - gripW
                : rootFrame.getWidth() - shell.getWidth());
        grip.setOnTouchListener(new View.OnTouchListener() {
            float downX, baseW;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        baseW = shell.getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float delta = e.getRawX() - downX;
                        float w = baseW + (edge < 0 ? delta : -delta);
                        float rootW = Math.max(1, rootFrame.getWidth());
                        dockWidthFraction = Math.max(DOCK_MIN_FRACTION,
                                Math.min(DOCK_MAX_FRACTION, w / rootW));
                        applyDockWidth(shell, edge);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.performClick();
                        return true;
                }
                return false;
            }
        });
        dockResizeHandle = grip;
    }

    private void removeDockResizeHandle() {
        if (dockResizeHandle != null) {
            rootFrame.removeView(dockResizeHandle);
            dockResizeHandle = null;
        }
    }

    /** Resize the docked pane and re-inset the column together, so they cannot disagree. */
    private void applyDockWidth(@NonNull View shell, int edge) {
        int w = Math.round(dockWidthFraction * rootFrame.getWidth());
        ViewGroup.LayoutParams lp = shell.getLayoutParams();
        if (lp != null && lp.width != w) {
            lp.width = w;
            shell.setLayoutParams(lp);
        }
        applyParkInset(edge, w);
        if (dockResizeHandle != null) {
            int gripW = dockResizeHandle.getWidth() > 0 ? dockResizeHandle.getWidth() : (int) (6 * density);
            dockResizeHandle.setTranslationX(edge < 0 ? w - gripW : rootFrame.getWidth() - w);
        }
        shell.post(() -> dockTo(shell, edge, false));
    }

    /** The band shown under the shell while a drag hovers a dock edge. Null when not showing. */
    @Nullable private View dockHint;
    /** Chrome's undock control — only meaningful while docked. */
    @Nullable private View undockButton;

    /** Show the undock control exactly while there is something to undock FROM. */
    private void updateUndockButton() {
        if (undockButton != null) {
            undockButton.setVisibility(dockedEdge == 0 ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Leave the docked slot and float again, without demoting back inline.
     *
     * <p>Releases the column inset first so the editor is already whole by the time the shell
     * animates off the edge — the reverse order leaves a frame where the window has moved but
     * the editor is still narrowed, which reads as a glitch.</p>
     */
    private void undock() {
        if (pipShell == null) return;
        dockedEdge = 0;
        applyParkInset(0, 0);
        removeDockResizeHandle();
        updateUndockButton();
        float inset = 24 * density;
        pipShell.animate()
                .translationX(pipShell.getTranslationX() > 0 ? -inset : inset)
                .setDuration(150)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> { if (pipShell != null) clampShellIntoRoot(pipShell); })
                .start();
        FLog.i(TAG, "UNDOCKED — floating again, editor width restored");
    }

    /**
     * Which edge this shell would dock to if released now — 0 for none.
     *
     * <p>Split out of {@link #maybeDock} so the drag can ASK without committing. Docking and
     * previewing a dock have to agree exactly, or the band promises a landing the release does
     * not honour.</p>
     */
    private int edgeUnderShell(@NonNull View shell) {
        int rootW = rootFrame.getWidth();
        if (rootW <= 0 || shell.getWidth() <= 0) return 0;
        float snapPx = DOCK_SNAP_DP * density;
        float screenLeft = shell.getLeft() + shell.getTranslationX();
        float distL = screenLeft;
        float distR = rootW - (screenLeft + shell.getWidth());
        if (distL > snapPx && distR > snapPx) return 0;
        return distL <= distR ? -1 : 1;
    }

    /**
     * Preview where a release would land, the way every dockable UI does.
     *
     * <p>JoyRaptor drew the distinction that this exists for: a window RESTING near an edge and a
     * window DOCKED to it are different states, and only the second reflows the editor —
     * "there might be a blue line or a green line or something, previewing where it would be.
     * And then if you let go while it's in there, then it would dock to that side."
     *
     * <p>Without it, docking is a hidden gesture with a large consequence: the whole editor
     * shifts, and nothing warned that it would. The band is the promise; {@link #maybeDock}
     * keeps it, because both ask {@link #edgeUnderShell} rather than each deciding for
     * itself.</p>
     */
    private void showDockHint(int edge) {
        if (edge == 0) {
            if (dockHint != null) {
                rootFrame.removeView(dockHint);
                dockHint = null;
            }
            return;
        }
        int w = pipShell != null && pipShell.getWidth() > 0
                ? pipShell.getWidth() : (int) (PIP_WIDTH_FRACTION * rootFrame.getWidth());
        if (dockHint == null) {
            dockHint = new View(editorRoot.getContext());
            // Below the shell being dragged, above the editor, so the band reads as a slot the
            // window is about to occupy rather than an overlay on top of it.
            rootFrame.addView(dockHint, rootFrame.indexOfChild(editorRoot) + 1);
        }
        dockHint.setBackgroundColor(0x334ADE80);            // the cyan already used for selection
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w, ViewGroup.LayoutParams.MATCH_PARENT,
                edge < 0 ? Gravity.START : Gravity.END);
        dockHint.setLayoutParams(lp);
        dockHint.setVisibility(View.VISIBLE);
    }

    private int maybeDock(@NonNull View shell, boolean animate) {
        int rootW = rootFrame.getWidth();
        if (rootW <= 0 || shell.getWidth() <= 0) {
            // Not measured yet — defer exactly like clampShellIntoRoot does.
            final View s = shell;
            s.post(() -> { if (pipShell == s) dockedEdge = maybeDock(s, animate); });
            return dockedEdge;
        }
        int edge = edgeUnderShell(shell);
        if (edge == 0) {
            applyParkInset(0, 0);   // dragged back into the middle — the editor takes its width back
            removeDockResizeHandle();
            updateUndockButton();
            return 0;
        }
        dockTo(shell, edge, animate);
        applyDockWidth(shell, edge);          // honour any width the user already dragged
        installDockResizeHandle(pipShell, edge);
        updateUndockButton();
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
        applyParkInset(edge, shell.getWidth());
    }

    /**
     * PARKED means the editor gets out of the way, not that the window merely sits on top.
     *
     * <p>Docking to an edge already worked — verified on the Note 9, the shell snaps flush —
     * but the timeline kept running underneath it, so a parked preview covered the very rows
     * the user was trying to reach. JoyRaptor's whole ask is one continuous experience:
     * "should be a smooth experience going from portrait to landscape to popout to parked",
     * with the parked window a companion to the editor rather than a lid on it — "you could
     * have it parked on the left hand side or the right hand side, and the drawers would come
     * in just over that."
     *
     * <p>Insetting the COLUMN rather than moving each row keeps every child's own layout
     * untouched: the tool row, the timeline and the top bar all simply have less width, which
     * is what they already handle on a narrower device. It costs nothing when nothing is
     * parked, and it cannot disturb the slot arithmetic, which is entirely about height.</p>
     *
     * @param edge -1 left, +1 right, 0 not parked (clears the inset)
     */
    private void applyParkInset(int edge, int shellWidth) {
        int inset = (edge == 0 || shellWidth <= 0) ? 0 : shellWidth;
        int left = edge < 0 ? inset : 0;
        int right = edge > 0 ? inset : 0;
        if (editorRoot.getPaddingLeft() == left && editorRoot.getPaddingRight() == right) return;
        editorRoot.setPadding(left, editorRoot.getPaddingTop(),
                right, editorRoot.getPaddingBottom());
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

