package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;

import java.util.function.Function;

/**
 * Bake-to-keyframes REPLAY renderer (PLAN_AVATAR_STUDIO §MINED, binding): draws
 * a placed avatar item as a LIVE PUPPET from its recorded
 * {@link AvatarParamTrack} — {@code track.sampleAt(itemLocalMs)} →
 * {@link PuppetPoseResolver#resolve} → {@link PuppetPreviewView} offscreen,
 * exactly the {@link AvatarNeutralBaker} pattern, so the replay is
 * pixel-faithful to the studio, the recorder bubble, and the neutral bake
 * (preview==export by construction; the webcam never re-runs).
 *
 * <p>Determinism rules the doctrine demands, all owned here so BOTH consumers
 * (editor preview {@code SpriteOverlayView} and export
 * {@code CompositeExportOverlay}) inherit them:</p>
 * <ul>
 *   <li>a fresh caller-owned {@link PuppetPoseResolver.DiscreteState}, stepped
 *       strictly forward in item time;</li>
 *   <li>a REWIND (scrub back / new export pass) recreates that state and drops
 *       the view's time-derived bookkeeping ({@code resetReplayState});</li>
 *   <li>the view runs on the item's MEDIA clock ({@code setMediaClockMs}), so
 *       crossfade windows and dangle dt are functions of media time, not the
 *       wall clock.</li>
 * </ul>
 *
 * <p>The offscreen view renders at a fixed {@value #RENDER_SIZE}px square —
 * the same size the neutral bake uses — and maps onto the caller's dest rect,
 * so upgrading an item from the static neutral PNG to a live puppet keeps its
 * placement geometry identical. Callers keep their existing rotate/flip canvas
 * transforms around {@link #draw}; whole-item opacity composites through one
 * saveLayerAlpha (per-part alpha would double-darken overlaps).</p>
 */
public final class AvatarItemPuppet {

    /** Offscreen render square; matches the neutral bake's 1024 so static→live
     *  keeps identical geometry. */
    public static final int RENDER_SIZE = 1024;

    @NonNull private final AvatarRig rig;
    @NonNull private final PuppetPreviewView view;
    @NonNull private PuppetPoseResolver.DiscreteState discrete =
            new PuppetPoseResolver.DiscreteState();
    private long lastLocalMs = Long.MIN_VALUE;
    @Nullable private AvatarParamTrack lastTrack;
    private boolean released;

    public AvatarItemPuppet(@NonNull Context context, @NonNull AvatarRig rig,
                            @NonNull Function<String, SpriteSheetRenderer> rendererLookup,
                            @NonNull Function<String, SpriteSheet> sheetLookup) {
        this.rig = rig;
        view = new PuppetPreviewView(context);
        view.setCleanRender(true);
        view.setBackgroundColor(0x00000000);
        view.bind(rig, rendererLookup);
        view.setSheetLookup(sheetLookup);
        int spec = View.MeasureSpec.makeMeasureSpec(RENDER_SIZE, View.MeasureSpec.EXACTLY);
        view.measure(spec, spec);
        view.layout(0, 0, RENDER_SIZE, RENDER_SIZE);
    }

    /**
     * Resolve the track at {@code itemLocalMs} and draw the puppet into
     * {@code dest} on {@code canvas} (which already carries the caller's
     * rotate/flip transforms). Never throws past the caller's per-item guard.
     */
    public void draw(@NonNull Canvas canvas, @NonNull RectF dest,
                     @NonNull AvatarParamTrack track, long itemLocalMs, float alpha) {
        if (itemLocalMs < lastLocalMs) {
            // Rewind: replay state must be a function of (track, time), never
            // of the scrub path taken to get here.
            discrete = new PuppetPoseResolver.DiscreteState();
            view.resetReplayState();
        }
        lastLocalMs = itemLocalMs;
        lastTrack = track;
        view.setMediaClockMs(itemLocalMs);
        java.util.Map<String, Float> params = track.sampleAt(itemLocalMs);
        // Baked pinTarget.* params drive replay FABRIK exactly as live tracking
        // does (the studio/bubble feed the same extract) — without this a take
        // recorded with IK limbs replays rigid, live ≠ replay.
        view.setTrackedPinTargets(TrackingDriverBus.extractPinTargets(params));
        view.setResolved(PuppetPoseResolver.resolve(rig, params, discrete));

        int save = saveCanvas(canvas, dest, alpha);
        try {
            mapToDestination(canvas, dest);
            view.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Fork the current sharp checkpoint before advancing it to the frame centre. The next
     * sharp draw may occur before this exposure's last tap. A shutter that starts before the
     * available checkpoint, or uses cadence-dependent dangle physics, must render sharp.
     * The caller supplies an admitted, break-fenced schedule. Use a fork for one synchronous
     * exposure while the item, take and rig are unchanged.
     */
    @Nullable
    public Exposure beginExposure(@NonNull AvatarParamTrack track, long firstLocalUs) {
        if (released || firstLocalUs < 0 || (lastTrack != null && lastTrack != track)
                || firstLocalUs / 1000L < lastLocalMs
                || rig.getParts().stream().anyMatch(part -> part.dangle)) return null;
        return new Exposure(track, firstLocalUs);
    }

    public final class Exposure {
        private final AvatarParamTrack track;
        private final PuppetPoseResolver.DiscreteState tapDiscrete = discrete.copy();
        private final AvatarReplayState tapReplay = view.copyReplayState();
        private long lastTapUs;

        private Exposure(AvatarParamTrack track, long firstLocalUs) {
            this.track = track;
            this.lastTapUs = firstLocalUs;
        }

        /** Draw an ordered shutter tap on the caller's sample target. */
        public void draw(@NonNull Canvas canvas, @NonNull RectF dest, long localUs, float alpha) {
            if (released || localUs < lastTapUs) {
                throw new IllegalStateException("Exposure taps must advance on an open avatar");
            }
            lastTapUs = localUs;
            var params = track.sampleAtMicros(localUs);
            var targets = TrackingDriverBus.extractPinTargets(params);
            var pose = PuppetPoseResolver.resolve(rig, params, tapDiscrete);
            int save = saveCanvas(canvas, dest, alpha);
            try {
                mapToDestination(canvas, dest);
                view.drawExposureTap(canvas, localUs, tapReplay, targets, pose);
            } finally {
                canvas.restoreToCount(save);
            }
        }
    }

    private static int saveCanvas(Canvas canvas, RectF dest, float alpha) {
        if (alpha >= 0.999f) return canvas.save();
        return canvas.saveLayerAlpha(dest.left, dest.top, dest.right, dest.bottom,
                Math.round(Math.max(0f, alpha) * 255));
    }

    private static void mapToDestination(Canvas canvas, RectF dest) {
        canvas.translate(dest.left, dest.top);
        canvas.scale(dest.width() / RENDER_SIZE, dest.height() / RENDER_SIZE);
    }

    /** Recycle the view's decoded cell-bitmap cache. The puppet is dead after. */
    public void release() {
        released = true;
        view.bind(null, null);
    }

    /**
     * Shared consumer glue: build a puppet for a placed item, or null when the
     * item carries no performance / the rig or its lookup is missing (caller
     * falls back to the static neutral-PNG sprite path — honest, never blank).
     */
    @Nullable
    public static AvatarItemPuppet forItem(
            @NonNull Context context,
            @NonNull com.fadcam.ui.faditor.sprite.SpriteOverlayItem item,
            @Nullable AvatarRig rig,
            @NonNull Function<String, SpriteSheetRenderer> rendererLookup,
            @NonNull Function<String, SpriteSheet> sheetLookup) {
        if (!item.hasAvatarPerformance() || rig == null) return null;
        return new AvatarItemPuppet(context, rig, rendererLookup, sheetLookup);
    }
}
