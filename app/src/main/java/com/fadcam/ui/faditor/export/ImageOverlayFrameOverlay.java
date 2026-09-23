package com.fadcam.ui.faditor.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;

import com.fadcam.ui.faditor.model.TextOverlayItem;

/**
 * The frame source feeding {@link ImageBlendGlEffect}: ONE image overlay drawn (positioned,
 * scaled, rotated, faded, wiped) into a frame-sized transparent bitmap per presentation time.
 *
 * <p>Deliberately the same shape as {@link PipFrameOverlay} — positioning stays on the proven
 * Canvas path so the shader can blend with plain full-frame UVs. The picture itself comes from
 * {@link ImageOverlayDraw}, the same call {@link CompositeExportOverlay} makes, so a blended image
 * lands exactly where an unblended one would.</p>
 */
final class ImageOverlayFrameOverlay extends BitmapOverlay {

    private final Context context;
    private final TextOverlayItem item;
    private final long projectDurationMs;
    /** Composition→editor time correction; see {@link PipFrameOverlay}'s note (LEDGER §2d). */
    private final long editorTimeOffsetMs;

    @Nullable private Bitmap bitmap;
    @Nullable private Canvas canvas;
    /** Identity-stable, never mutated — safe for BitmapOverlay's texture cache. */
    @Nullable private Bitmap transparent;
    /**
     * 2026-09-22 static-content fast path. The old code returned a fresh full-frame
     * copy EVERY visible frame (needed: the patched BitmapOverlay re-uploads only on
     * instance change, and Canvas draws must reach the GPU). At 4 GL overlays ×
     * 1088×1920×4B × 33fps that is ~1.1 GB/s of native allocation, and the 12:17
     * trace showed throughput decaying 30x→0.14x with a COOLING SoC — classic
     * allocator/finalizer distress. When the frame's pixels are PROVABLY identical to
     * the last drawn frame (contentStaticAt), the same instance goes back: no alloc,
     * no upload, and correctness is structural (every input below is constant, so the
     * deterministic draw cannot differ) — never a pixels-compare heuristic.
     */
    @Nullable private Bitmap lastDrawnInstance = null;
    /** Static region of the last drawn frame: -1 before all keys, +1 after, 0 animated/none. */
    private int lastStaticSide = 0;
    /** Decoded once for this instance's lifetime. Null AFTER {@link #decoded} means "failed". */
    @Nullable private Bitmap source;
    private boolean decoded;
    private int frameW = 1, frameH = 1;

    ImageOverlayFrameOverlay(@NonNull Context context, @NonNull TextOverlayItem item,
                             long projectDurationMs, long editorTimeOffsetMs) {
        this.context = context.getApplicationContext();
        this.item = item;
        this.projectDurationMs = projectDurationMs;
        this.editorTimeOffsetMs = editorTimeOffsetMs;
    }

    @Override
    public void configure(@NonNull Size videoSize) {
        super.configure(videoSize);
        frameW = Math.max(1, videoSize.getWidth());
        frameH = Math.max(1, videoSize.getHeight());
    }

    @NonNull
    @Override
    public Bitmap getBitmap(long presentationTimeUs) {
        if (bitmap == null || bitmap.getWidth() != frameW || bitmap.getHeight() != frameH) {
            bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            transparent = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888);
            lastDrawnInstance = null;
            lastStaticSide = 0;
        }
        long timelineMs = presentationTimeUs / 1000 + editorTimeOffsetMs;   // →editor time (§2d)
        // Empty frames return the IDENTITY-STABLE transparent bitmap rather than the cleared
        // scratch: BitmapOverlay re-uploads only on instance change, so handing back the mutated
        // scratch would leave the LAST drawn frame on screen past the overlay's window — the
        // exact trap PipFrameOverlay documents.
        if (!item.isVisibleAt(timelineMs)) {
            lastDrawnInstance = null;
            lastStaticSide = 0;
            return transparent;
        }
        if (!decoded) {
            decoded = true;
            source = ImageOverlayDraw.decode(context, item, frameW, frameH);
            if (source == null) {
                com.fadcam.FLog.w("ImageOverlayFrameOverlay", "image overlay " + item.getId()
                        + " decoded to null — its blend will render as nothing");
            }
        }
        if (source == null || source.isRecycled()) {
            lastDrawnInstance = null;
            lastStaticSide = 0;
            return transparent;
        }

        // Static fast path: provably-identical pixels → same instance back. The side
        // (before-first-key vs after-last-key) is part of the key: the two static
        // regions hold DIFFERENT clamped values, so crossing from one to the other
        // must redraw.
        int side = staticSideAt(timelineMs);
        if (side != 0 && side == lastStaticSide
                && lastDrawnInstance != null && !lastDrawnInstance.isRecycled()) {
            return lastDrawnInstance;
        }

        canvas.drawColor(0, PorterDuff.Mode.CLEAR);
        if (!ImageOverlayDraw.draw(canvas, source, item, timelineMs, projectDurationMs,
                frameW, frameH, 1f)) {
            lastDrawnInstance = null;
            lastStaticSide = 0;
            return transparent;
        }
        // NEW instance per frame, matching PipFrameOverlay. The patched BitmapOverlay also
        // compares getGenerationId(), so reusing the scratch would in fact re-upload — but the
        // in-repo precedent copies, the failure mode if that reading is wrong is a frozen first
        // frame for the whole clip, and this is not the change to find that out on. If the copy
        // is ever removed, remove it from BOTH classes in one commit and prove it on a device.
        Bitmap out = Bitmap.createBitmap(bitmap);
        lastDrawnInstance = out;
        lastStaticSide = side;
        return out;
    }

    /**
     * Static region of this frame: -1 before all keys, +1 after, 0 animating (or any
     * time-driven feature present). The caller reuses the last drawn instance only when
     * the side matches, because the two static regions hold DIFFERENT clamped values.
     * Checked inputs (every one ImageOverlayDraw consumes that can move with time):
     * preset motion (NONE + zero zones ⇒ identity Transform, proven at the call site),
     * masks, corner pins, mesh, image preset, fade handles, and ALL keyframe tracks.
     * Anything animated — fades, moves, wipes — returns 0 and takes the full
     * draw+copy path, so animation can never freeze.
     */
    private int staticSideAt(long timelineMs) {
        try {
            com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset preset =
                    com.fadcam.ui.faditor.transcript.CaptionAnimator.parsePreset(
                            item.getTextAnimPreset());
            if (preset
                    != com.fadcam.ui.faditor.transcript.CaptionAnimator.Preset.NONE) return 0;
            if (item.getTextAnimInPct() != 0f || item.getTextAnimOutPct() != 0f) return 0;
            com.fadcam.ui.faditor.model.CompositingSpec cs = item.getCompositing();
            if (cs != null && !cs.masks.isEmpty()) return 0;
            if (item.hasCornerPin() || item.hasMesh()) return 0;
            if (item.getImageAnimPreset() != null && item.hasActiveImagePreset()) return 0;
            if (item.getImageFadeInMs() != 0L || item.getImageFadeOutMs() != 0L) return 0;
            long local = timelineMs - item.getStartMs();
            boolean anyKeys = false;
            long minFirst = Long.MAX_VALUE;
            long maxLast = Long.MIN_VALUE;
            for (com.fadcam.ui.faditor.keyframe.KeyframeTrack track
                    : item.getKeyframes().tracks()) {
                if (track.keyframes.isEmpty()) continue;
                anyKeys = true;
                long first = Long.MAX_VALUE;
                long last = Long.MIN_VALUE;
                for (com.fadcam.ui.faditor.keyframe.Keyframe k : track.keyframes) {
                    if (k.timeMs < first) first = k.timeMs;
                    if (k.timeMs > last) last = k.timeMs;
                }
                if (first < minFirst) minFirst = first;
                if (last > maxLast) maxLast = last;
            }
            if (!anyKeys) return 1;
            if (local < minFirst) return -1;
            if (local > maxLast) return 1;
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void release() throws androidx.media3.common.VideoFrameProcessingException {
        super.release();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        bitmap = null;
        canvas = null;
        if (transparent != null && !transparent.isRecycled()) transparent.recycle();
        transparent = null;
        if (source != null && !source.isRecycled()) source.recycle();
        source = null;
        if (lastDrawnInstance != null && !lastDrawnInstance.isRecycled()) {
            lastDrawnInstance.recycle();
        }
        lastDrawnInstance = null;
        lastStaticSide = 0;
    }
}
