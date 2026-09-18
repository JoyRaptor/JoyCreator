package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.model.Clip;

import java.util.ArrayList;
import java.util.List;

/**
 * M-COMP-2 (PLAN_LAYERS_V2 §3.3) — live second-video / PiP preview layer.
 *
 * <p>Sits directly above the master video surfaces and below the visualizer/image/sprite/text
 * overlay views. Owns the overlay {@link ExoPlayer}s and renders the top-most one into a
 * {@link TextureView} that this view positions, scales, rotates and alpha-blends per tick from
 * the clip's {@code overlayTransform} {@link KeyframeSet} — the SAME evaluator
 * ({@code KeyframeSet.valueAt} at the absolute timeline ms) the export path samples, so preview
 * and export cannot diverge on the transform math.</p>
 *
 * <p><b>Decoder policy.</b> The TOP-most visible overlay-video clip at the playhead always gets
 * a live decoder, on the single-player path this view has always had. Clips beneath it get one
 * too — up to {@link FxPreviewTextureView#MAX_LIVE_PIPS} live overlays in total — but ONLY while
 * the FX composite is engaged, because that GL chain is the only tier able to draw more than one
 * live PiP; see {@code syncExtraLive}. Everything past the cap, every device that runs out of
 * hardware decoders, and every project without the composite keeps the cached-still fallback
 * below. Export composites all of them full-quality either way, so the stills remain the one
 * place preview is knowingly poorer than the file.</p>
 *
 * <p>When no overlay clip is visible the players pause; when the bound list empties the top-most
 * player is RELEASED, and the tier below it is released as soon as the project stops having two
 * overlay videos to stack — so scrub-heavy single-track editing and background exports never pay
 * for an idle decoder.</p>
 *
 * <p><b>Transform convention</b> (creation writes these as keyframes at t=0; absent tracks fall
 * back to the same defaults): {@code x},{@code y} = normalised centre in the video content rect;
 * {@code scale} = multiplier over the full-fit size (1.0 = overlay fills the canvas, matching
 * export's {@code ScaleAndRotateTransformation}+{@code Presentation} semantics); {@code rotation}
 * degrees; {@code opacity} 0..1.</p>
 *
 * <p>Gestures mirror {@code SpriteOverlayView}/{@code TextOverlayLayer}: drag moves, pinch
 * scales, empty areas pass through to the player. v1 writes STATIC transforms (replaces the
 * key at t=0); armed auto-keyframing rides with the S5-style lane work later. One undo step per
 * gesture via a whole-{@link KeyframeSet} snapshot.</p>
 */
public class OverlayVideoPreviewView extends FrameLayout {

    /** M-COMP-2 feature flag: gates the AddAssetBottomSheet creation row + this preview. */
    public static final boolean LIVE_PIP = true;

    private static final String TAG = "OverlayVideoPreview";

    /** Creation-time / fallback transform defaults (top-right corner PiP at ~1/3 size). */
    public static final float DEFAULT_X = 0.72f;
    public static final float DEFAULT_Y = 0.22f;
    public static final float DEFAULT_SCALE = 0.35f;

    /** Playback drift beyond this re-syncs the overlay player to the master clock. */
    private static final long DRIFT_RESYNC_MS = 150;
    /** Paused/scrub: re-seek when the shown frame is further off than this. */
    private static final long SCRUB_RESEEK_MS = 40;

    public interface Callback {
        /** Pixel rect of the visible video content inside this layer's bounds. */
        @NonNull RectF getVideoContentRect();
        /** Remux-to-seekable resolver — MUST be the same one the master engine uses. */
        @NonNull Uri resolveSeekable(@NonNull Clip clip);
        /** A transform changed — persist it (autosave). */
        void onOverlayVideoChanged();
        /** A drag/pinch finished; record ONE undo step from the snapshot. */
        void onOverlayVideoManipulated(@NonNull Clip clip, @NonNull KeyframeSet before);

        /**
         * The user TAPPED this PiP on the canvas — select it.
         *
         * <p>Default no-op so existing callers compile, but the editor must implement it: a tap
         * that selects nothing is the difference between a canvas you can work on and one that
         * appears to ignore you.</p>
         */
        default void onOverlayVideoSelected(@NonNull Clip clip) { }
        /**
         * Playback volume for this PiP — the host answers from
         * {@code LayerPreviewController.effectiveOverlayVolume} so preview and export share
         * one authority. 0 for every clip that has not opted into audio (the default), which
         * is exactly the hardcoded silence this view used to apply. See SPEC_PIP_AUDIO.
         */
        float overlayVolumeFor(@NonNull Clip clip);
    }

    private final TextureView textureView;
    /**
     * §3a live key tier — created ONLY when a clip on screen actually keys
     * ({@link com.fadcam.ui.faditor.model.ChromaKey#isActive}). A project that never touched
     * the key never allocates an EGL context, and runs the plain {@link #textureView} path
     * exactly as it always did.
     */
    @Nullable private ChromaKeyTextureView keyedView;
    /** True while the decoder is rendering into {@link #keyedView} rather than the plain one. */
    private boolean keyedRouted;
    /** True while the clip on screen WANTS keying, whether or not the tier is ready yet. */
    private boolean keyedWanted;
    /**
     * The player instance {@link #keyedRouted} actually describes. A decoder error releases the
     * player and a later clip builds a NEW one wired to the plain view; without this, the stale
     * "already routed" flag would match, {@code routeFor} would early-return, and the keyed PiP
     * would render nothing at all. Comparing identity makes the cache self-invalidating.
     */
    @Nullable private ExoPlayer routedPlayer;
    /** The keyed tier's decoder-facing surface, once its GL thread has published one. */
    @Nullable private Surface keyedInputSurface;
    /**
     * KEYDIAG probe. Retained (like SEEKRANGE / ENDEDNET / PHDIAG) rather than deleted after
     * the first bring-up: the live key tier has no other observable, so without this a
     * regression that stops it routing is indistinguishable on screen from a shader bug.
     */
    private static final boolean KEYDIAG = true;
    private final List<Clip> clips = new ArrayList<>();
    @Nullable private Callback callback;
    @Nullable private ExoPlayer player;
    @Nullable private Clip active;          // clip currently owning the live decoder
    private long currentTimeMs;
    private boolean masterPlaying;
    private int videoW, videoH;             // active overlay's decoded size
    private int baseW, baseH;               // TextureView layout box (full-fit in content rect)

    // ── Still-frame fallback (plan §3.3): simultaneously-visible overlay clips
    // BELOW the live top-most render a cached MMR still instead of nothing
    // ("preview shows one live overlay video; export shows all" — the stills
    // close the "nothing" half). Decoded ASYNC on one worker (MMR on the UI
    // thread would jank every tick); refreshed on a coarse time bucket. Drawn
    // in onDraw = behind the TextureView child = correct z (stills are always
    // below the live top-most by definition).
    private static final long STILL_BUCKET_MS = 400;
    private final java.util.Map<String, StillFrame> stills = new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService stillExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.graphics.Paint stillPaint = new android.graphics.Paint(
            android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);

    private static final class StillFrame {
        @Nullable android.graphics.Bitmap bitmap;
        long bucketMs = Long.MIN_VALUE;     // source bucket the bitmap shows
        long pendingBucketMs = Long.MIN_VALUE; // bucket a worker decode is in flight for
    }

    // Gesture state (SpriteOverlayView contract).
    private final ScaleGestureDetector scaleDetector;
    @Nullable private Clip manipulating;
    @Nullable private KeyframeSet beforeGesture;
    private float downRawX, downRawY, startX, startY, startScale;
    private boolean moved;

    public OverlayVideoPreviewView(Context ctx) { this(ctx, null); }

    public OverlayVideoPreviewView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        setWillNotDraw(false); // stills draw in onDraw (behind the TextureView child)
        textureView = new TextureView(ctx);
        textureView.setVisibility(GONE);
        addView(textureView, new LayoutParams(1, 1, Gravity.CENTER));
        scaleDetector = new ScaleGestureDetector(ctx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        if (manipulating != null) {
                            // 4.0 matches the Scale slider's max exactly (user, 2026-08-05).
                            // They used to disagree — 3.0 here against 1.5 there — so a pinch
                            // could reach a size the slider could not display or restore.
                            float s = Math.max(0.05f, Math.min(4f,
                                    readValue(manipulating, KeyframeSet.SCALE, DEFAULT_SCALE)
                                            * d.getScaleFactor()));
                            putStatic(manipulating, KeyframeSet.SCALE, s);
                            applyTransform(manipulating);
                        }
                        return true;
                    }
                });
    }

    /** Bind the z-ordered (bottom→top) visible overlay-video clips. Empty releases the decoder. */
    public void setClips(@NonNull List<Clip> visible, @NonNull Callback cb) {
        this.clips.clear();
        this.clips.addAll(visible);
        this.callback = cb;
        if (clips.isEmpty()) {
            releasePlayer();
        }
        syncToTime();
    }

    /** Drive time-ranges, playback sync, and keyframed transforms — one call per playhead tick. */
    public void setPlayheadMs(long timelineMs, boolean masterIsPlaying) {
        this.currentTimeMs = timelineMs;
        this.masterPlaying = masterIsPlaying;
        syncToTime();
    }

    /** Pause the overlay decoder (activity onPause). */
    public void pausePlayback() {
        if (player != null) player.pause();
        ExtraLive[] pool = extraLive;
        if (pool == null) return;
        for (ExtraLive el : pool) {
            if (el != null && el.player != null) el.player.pause();
        }
    }

    // ── §3a live chroma key ──────────────────────────────────────────────────────────────

    /**
     * The view the decoder is currently painting into — the plain {@link TextureView}, or the
     * keyed one while a key is live. Everything that positions, scales, clips or hides the PiP
     * asks THIS rather than naming a field, so the two tiers cannot end up transformed
     * differently. Getting that wrong would look exactly like "the key moves my PiP".
     */
    @NonNull
    private TextureView videoHost() {
        return (keyedRouted && keyedView != null) ? keyedView : textureView;
    }

    // ── FX-graded tier: hand the PiP decoder to FxPreviewTextureView ─────────────────────────

    /**
     * When non-null, the PiP's frames go HERE instead of to a TextureView of this view's own, so
     * that {@link FxPreviewTextureView} can composite the PiP into its chain and let an
     * adjustment layer grade it — which is what the export does and what a View sibling can never
     * reproduce.
     *
     * <p>This view keeps doing everything else: decoder policy, gestures, hit-testing, ghost
     * outlines and the transform evaluation. Only the PIXELS move. Its own hosts are made
     * transparent rather than GONE, because a GONE TextureView is never laid out and
     * {@code getVideoContentRect}-relative gesture maths depends on the layout being real.</p>
     */
    @Nullable private Surface fxSurface;
    private boolean fxRouted;
    /**
     * The FX view's GL-thread trash for retired still bitmaps, wired alongside the composite
     * surface offer. Non-null while the FX composite may be uploading our stills; the gate on
     * {@link #recycleStill} additionally requires fxSurface != null, so deferral stops the
     * moment the chain disengages (queued bitmaps are drained by the next GL draw / release).
     */
    @Nullable private java.util.concurrent.ConcurrentLinkedQueue<android.graphics.Bitmap>
            fxStillTrash;
    /**
     * The player {@link #fxRouted} describes. A decoder error releases the player and a later
     * clip builds a NEW one wired to this view's own TextureView; without the identity check the
     * stale flag matched, routeToFxIfWanted early-returned, and the new decoder painted into a
     * view still held at alpha 0 -- the PiP disappeared for the rest of the session with nothing
     * on screen to say why. The keyed tier learned this already; see routedPlayer.
     */
    @Nullable private ExoPlayer fxRoutedPlayer;

    // ── The live tier BELOW the top-most PiP ─────────────────────────────────────────────────

    /**
     * One decoder for an overlay clip that is visible but is NOT the top-most one.
     *
     * <p>Deliberately thinner than the top-most tier: no keyed view, no TextureView, no
     * gestures. Its pixels only ever go to a composite input, which is the one place more than
     * one live PiP can be shown at all.</p>
     */
    private static final class ExtraLive {
        final int slot;
        @Nullable ExoPlayer player;
        @Nullable Clip clip;
        @Nullable Surface surface;      // the composite input this player is attached to
        int videoW, videoH;             // its decoded size, once the decoder reports one
        float volume = -1f;             // last pushed, so a tick is not an audio-renderer message
        ExtraLive(int slot) { this.slot = slot; }
    }

    /**
     * The pool, indexed by composite slot ({@code [0]} is unused — slot 0 is {@link #player}).
     *
     * <p><b>Null, not empty, until a project actually stacks overlay videos while the composite
     * is engaged.</b> One PiP is the overwhelmingly common project and it must allocate exactly
     * what it always did; everything in this tier is behind that gate.</p>
     *
     * <p>A pool rather than a per-tick build: creating and releasing an ExoPlayer while the user
     * drags the playhead is the expensive thing, not holding a paused one. Players are keyed to
     * a clip, reused as clips come in and out of visibility, and released outright only when the
     * project stops being stacked.</p>
     */
    @Nullable private ExtraLive[] extraLive;
    /** Composite inputs for slots 1.., indexed by slot; null where the GL side has none yet. */
    @Nullable private Surface[] fxExtraSurfaces;
    /**
     * Latched for the session when an extra decoder fails to start.
     *
     * <p>Exceeding a phone's concurrent hardware-decoder count fails at codec-init, and retrying
     * it every tick would turn a degraded preview into a stuttering one. Once this is set the
     * whole tier stands down and every clip below the top-most goes back to the still-frame
     * path — the picture this view has always shown, which is worse than live video and much
     * better than a black PiP.</p>
     */
    private boolean extraLiveFailed;

    /** Route the PiP into {@code s}, or pass null to hand it back to this view's own surface. */
    public void setFxCompositeSurface(@Nullable Surface s) {
        if (s == fxSurface) return;
        fxSurface = s;
        if (s == null) releaseExtraLive();   // the tier exists only inside the composite
        if (s == null && fxRouted) {
            fxRouted = false;
            textureView.setAlpha(1f);
            if (keyedView != null) keyedView.setAlpha(1f);
            if (player != null) player.setVideoTextureView(textureView);
            keyedRouted = false;
        }
        routeToFxIfWanted();
    }

    /**
     * Wire the composite's inputs for live slots 1.., offered alongside slot 0's.
     *
     * <p>The array is indexed by slot and may hold nulls: the GL side builds each input lazily
     * and publishes it when it exists, so an unbuilt slot simply means "that clip keeps its
     * still for now". Passing null is the host saying this project does not stack overlay
     * videos at all, which is when the pooled decoders are given back — an idle decoder a
     * project can never use is exactly the cost this tier is not allowed to have.</p>
     */
    public void setFxExtraSurfaces(@Nullable Surface[] slots) {
        fxExtraSurfaces = slots;
        if (slots == null) releaseExtraLive();
    }

    /** Wire the FX view's GL-thread still-bitmap trash; called with the composite surface offer. */
    public void setFxStillTrash(
            @Nullable java.util.concurrent.ConcurrentLinkedQueue<android.graphics.Bitmap> q) {
        fxStillTrash = q;
    }

    /**
     * Recycle a still bitmap, deferring to the FX chain's GL thread while the chain may be
     * uploading it. {@code texImage2D} reads the pixel memory; freeing that memory mid-upload is
     * a native crash no try/catch reaches, so while the FX composite owns the stills the actual
     * free happens on the one thread that touches them. When no chain is engaged, recycle
     * immediately, exactly as before.
     */
    private void recycleStill(@Nullable android.graphics.Bitmap b) {
        if (b == null || b.isRecycled()) return;
        java.util.concurrent.ConcurrentLinkedQueue<android.graphics.Bitmap> trash =
                fxSurface != null ? fxStillTrash : null;
        if (trash != null) trash.offer(b); else b.recycle();
    }

    /**
     * Whether the FX tier currently owns the PiP pixels. The host asks so it knows whether the
     * GL composite will actually show something, rather than assuming the routing took.
     */
    public boolean isFxRouted() {
        return fxRouted;
    }

    /**
     * The composite descriptor the FX chain needs for ONE visible clip, or null when this view
     * cannot supply its pixels: a clip that is not on screen at the playhead, a matte peer
     * (never in this view's list), a keyed clip (its own live tier owns the pixels), the live
     * clip before its routing holds, or a still clip whose frame has not decoded yet. The FX
     * walk simply draws nothing for that rung — the same "absent until ready" behaviour the
     * sibling stills have always had.
     *
     * <p>The live decoder's clip carries no bitmap — its pixels arrive on the FX surface. Every
     * other visible clip carries its cached still, which the chain uploads to a 2D texture and
     * grades in z-order, so an adjustment layer grades EVERY PiP beneath it — not just the one
     * that happens to hold the decoder (the multi-PiP jank this replaces).</p>
     */
    @Nullable
    public FxPreviewTextureView.Pip fxPipFor(@NonNull Clip clip) {
        if (callback == null || !clips.contains(clip)) return null;
        long start = clip.getOverlayStartMs();
        long end = start + Math.max(0, clip.getTrimmedDurationMs());
        if (currentTimeMs < start || currentTimeMs > end) return null;
        if (clip == active) {
            // The live decoder's clip: its pixels come through the FX surface — but only while
            // that routing actually holds; a keyed PiP keeps its own tier and must NOT also be
            // drawn here.
            if (!fxRouted || videoW <= 0 || videoH <= 0) return null;
            return pipFor(clip, videoW, videoH, null, 0);
        }
        // A clip in the tier below the top-most: its own decoder, on its own composite input.
        ExtraLive el = extraFor(clip);
        if (el != null) {
            if (el.videoW <= 0 || el.videoH <= 0) return null;   // absent until the size lands
            return pipFor(clip, el.videoW, el.videoH, null, el.slot);
        }
        StillFrame sf = stills.get(clip.getId());
        android.graphics.Bitmap b = sf == null ? null : sf.bitmap;
        if (b == null || b.isRecycled()) {
            FLog.d("FxMultiPip", "fxPipFor still " + clip.getId() + " -> no bitmap"
                    + " (stills has " + stills.size() + ")");
            return null;
        }
        return pipFor(clip, b.getWidth(), b.getHeight(), b, 0);
    }

    /**
     * Still-frame matte for live preview: same placement math as {@link #pipFor} but always
     * via the cached still, never a live decoder. This is the decoder-budget fallback
     * (FEEDBACK_20260702 §B3): master + overlay + matte =3 decoders exceeds Note 9's ~2,
     * so a STILL-frame matte in preview is the correct result, not a dropped frame.
     * Returns null when the matte peer is not visible at the playhead, has no decoded
     * frame yet, or is dangling — the recipient then degrades to unmatted (same as export).
     */
    @Nullable
    public FxPreviewTextureView.Pip mattePipFor(@NonNull Clip mattePeer) {
        if (callback == null || !clips.contains(mattePeer)) return null;
        long start = mattePeer.getOverlayStartMs();
        long end = start + Math.max(0, mattePeer.getTrimmedDurationMs());
        if (currentTimeMs < start || currentTimeMs > end) return null;
        StillFrame sf = stills.get(mattePeer.getId());
        android.graphics.Bitmap b = sf == null ? null : sf.bitmap;
        if (b == null || b.isRecycled()) {
            return null;
        }
        return pipFor(mattePeer, b.getWidth(), b.getHeight(), b, 0);
    }

    /**
     * One PiP's placement on the master frame, normalised — the maths {@link #applyTransform}
     * feeds View properties, re-expressed for the GL composite. {@code srcW}×{@code srcH} is the
     * clip's own decoded size (the live decoder's, or the still bitmap's): the fit box of a 16:9
     * PiP on a 9:16 canvas is letterboxed, and guessing it would move the PiP. Half-extents are
     * in master-frame units, which is why they divide by the content rect: the shader works in
     * the master video's normalised space.
     */
    @Nullable
    private FxPreviewTextureView.Pip pipFor(@NonNull Clip clip, int srcW, int srcH,
                                            @Nullable android.graphics.Bitmap still,
                                            int liveSlot) {
        if (callback == null || srcW <= 0 || srcH <= 0) return null;
        RectF r = canvasRect();
        if (r == null) return null;
        float fit = Math.min(r.width() / srcW, r.height() / srcH);
        float baseW = srcW * fit, baseH = srcH * fit;
        float x = readValue(clip, KeyframeSet.X, DEFAULT_X);
        float y = readValue(clip, KeyframeSet.Y, DEFAULT_Y);
        float scale = readValue(clip, KeyframeSet.SCALE, DEFAULT_SCALE);
        float rot = readValue(clip, KeyframeSet.ROTATION, 0f);
        float alpha = Math.max(0f, Math.min(1f, readValue(clip, KeyframeSet.OPACITY, 1f)));
        // Y AND ROTATION ARE FLIPPED into GL's frame. The transform above is in VIEW space,
        // whose origin is top-left and whose positive rotation is clockwise on screen; the
        // shader works in vFxUv, which the vertex stage builds bottom-up. Passing y straight
        // through put the PiP as far below centre as it should have been above it.
        //
        // The object's OWN effect stack rides along, so a PiP with effects on it finally shows
        // them in the editor instead of only in the export.
        return FxPreviewTextureView.Pip.of(
                x, 1f - y,
                (baseW * scale) / r.width() * 0.5f,
                (baseH * scale) / r.height() * 0.5f,
                -rot, alpha,
                clip.getFx(), currentTimeMs,
                clip.getCompositing(),
                com.fadcam.ui.faditor.model.BlendModes.modeCode(clip.getOverlayBlendMode()),
                // THE MASTER FRAME, not this PiP's own source size. A mask's cx/cy/w/h are
                // CANVAS fractions and the composite shader evaluates them against the master
                // frame ({@code 1.0 / uPipTexel}); packing the feather in this clip's pixels
                // made a soft edge scale with whatever resolution the overlay clip happened to
                // be. Falls back to the source size before the controller has published a
                // frame, which is what it always used.
                compositeFrameW > 0 ? compositeFrameW : Math.max(1, srcW),
                compositeFrameH > 0 ? compositeFrameH : Math.max(1, srcH),
                clip.getId(), still, liveSlot);
    }

    /**
     * The OUTPUT CANVAS rect in this view's pixel space, or null before it is measured.
     *
     * <p>The callback's {@code getVideoContentRect} is the host's {@code computeCanvasRect()} —
     * the PlayerView's bounds when a canvas aspect is resolvable, the video content rect when it
     * is not. Exposed so {@link FxLivePreviewController} can size the GL composite to the same
     * box every overlay is normalised against, rather than the host growing a second accessor
     * that could answer differently.</p>
     */
    @Nullable
    public RectF canvasRect() {
        if (callback == null) return null;
        RectF r = callback.getVideoContentRect();
        return (r.width() > 0 && r.height() > 0) ? r : null;
    }

    /**
     * The size the GL composite is running at, published each sync by
     * {@link FxLivePreviewController}. Used only to pack mask geometry into the space the
     * composite shader evaluates it in.
     */
    public void setCompositeFrameSize(int w, int h) {
        compositeFrameW = w;
        compositeFrameH = h;
    }

    private int compositeFrameW, compositeFrameH;

    /**
     * The PiP's drawn box in THIS view's pixel space, for the preview manipulation handles.
     *
     * <p>Derived from the same full-fit base box {@link #updateBaseLayout} sizes the host with,
     * NOT from the content rect scaled directly: a 16:9 PiP on a 9:16 canvas is letterboxed
     * inside its base box, so {@code contentRect * scale} would draw a handle frame far taller
     * than the video and the user would grab empty space.</p>
     *
     * <p>Null when the clip is not the one currently decoding — only the active clip's source
     * dimensions are known, and guessing a box would put the handles somewhere the PiP is not.</p>
     */
    @Nullable
    public RectF drawnRectFor(@NonNull Clip clip) {
        if (callback == null) return null;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return null;
        // SOURCE SIZE FOR ANY VISIBLE CLIP, not only the decoding one. Returning null for the
        // rest meant a PiP that was not topmost had no box at all: no handles, and — once the
        // preview's hit-testing started asking this — no way to tap or drag it either. That is
        // the "one layer I can't move by hand" report. The still-frame cache already holds the
        // decoded bitmap onDraw paints for exactly those clips, so its dimensions are the same
        // ones the picture on screen was built from.
        int srcW = videoW, srcH = videoH;
        if (clip != active) {
            // Its own decoder's size when it has one, else its still's. Without the first
            // branch a PiP promoted into the live tier would lose its handles and its
            // hit-testing the moment it stopped being a still.
            ExtraLive el = extraFor(clip);
            if (el != null && el.videoW > 0 && el.videoH > 0) {
                srcW = el.videoW;
                srcH = el.videoH;
            } else {
                StillFrame sf = stills.get(clip.getId());
                if (sf == null || sf.bitmap == null || sf.bitmap.isRecycled()) return null;
                srcW = sf.bitmap.getWidth();
                srcH = sf.bitmap.getHeight();
            }
        }
        if (srcW <= 0 || srcH <= 0) return null;
        final int videoW = srcW, videoH = srcH;
        float fit = Math.min(r.width() / videoW, r.height() / videoH);
        float scale = readValue(clip, KeyframeSet.SCALE, DEFAULT_SCALE);
        float w = videoW * fit * scale, h = videoH * fit * scale;
        float cx = r.left + readValue(clip, KeyframeSet.X, DEFAULT_X) * r.width();
        float cy = r.top + readValue(clip, KeyframeSet.Y, DEFAULT_Y) * r.height();
        return new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    /** Attach the decoder to the FX surface once both it and a player exist. */
    private void routeToFxIfWanted() {
        Surface s = fxSurface;
        if (s == null || player == null) return;
        if (fxRouted && player == fxRoutedPlayer) return;
        // A keyed PiP keeps its own live tier: ChromaKeyTextureView produces per-pixel alpha
        // that this composite has no equivalent for, and a key that stopped working because a
        // grade was added would be a far worse trade than an ungraded PiP.
        if (keyedWanted) return;
        player.setVideoSurface(s);
        fxRouted = true;
        fxRoutedPlayer = player;
        textureView.setAlpha(0f);
        FLog.i(TAG, "PiP routed into the FX composite");
    }

    /**
     * Decide which tier renders {@code clip} and move the decoder if that changed.
     *
     * <p>Switching is DEFERRED until the keyed tier has published a surface: the GL thread and
     * its EGL context come up asynchronously, and pointing the player at a surface that does
     * not exist yet is a black frame at best. Until it is ready the plain path keeps running,
     * so the worst case of a device that cannot make an EGL context is the OLD behaviour —
     * unkeyed preview — rather than a broken one.</p>
     */
    private void routeFor(@NonNull Clip clip) {
        boolean wantKeyed = com.fadcam.ui.faditor.model.ChromaKey.isActive(clip.getCompositing());
        keyedWanted = wantKeyed;
        // KEYDIAG: the live tier is invisible to every other instrument — a keyed PiP that
        // looks unkeyed could be a missing spec, a tier that never routed, or a shader that
        // ran and did nothing, and those need different fixes. One line separates them.
        if (KEYDIAG) {
            com.fadcam.ui.faditor.model.CompositingSpec cs = clip.getCompositing();
            android.util.Log.d("KEYDIAG", "clip=" + clip.getId().substring(0, 8)
                    + " spec=" + (cs == null ? "null"
                            : "on=" + cs.keyEnabled + " tol=" + cs.keyTolerance
                              + " col=" + Integer.toHexString(cs.keyColor))
                    + " wantKeyed=" + wantKeyed
                    + " surfaceReady=" + (keyedInputSurface != null)
                    + " routed=" + keyedRouted);
        }
        if (wantKeyed && keyedView == null) {
            ChromaKeyTextureView kv = new ChromaKeyTextureView(getContext());
            // VISIBLE from birth, and this is load-bearing. A GONE TextureView is never laid
            // out, so it never receives onSurfaceTextureAvailable and never publishes a
            // decoder surface — while the routing that would make it visible waits for exactly
            // that surface. Created GONE, the two conditions deadlock and the key silently
            // never engages (KEYDIAG: wantKeyed=true surfaceReady=false, forever).
            // Showing it early costs nothing visually: it is transparent until it has frames,
            // and the plain tier stays visible underneath until the switch actually happens.
            kv.setVisibility(VISIBLE);
            kv.setSurfaceListener(new ChromaKeyTextureView.SurfaceListener() {
                @Override public void onKeyedInputSurfaceReady(@NonNull Surface s) {
                    keyedInputSurface = s;
                    // Re-run the decision now that the surface exists — this is the moment the
                    // deferred switch above actually happens.
                    if (active != null) routeFor(active);
                    requestLayout();
                }
                @Override public void onKeyedInputSurfaceLost() {
                    keyedInputSurface = null;
                    if (keyedRouted) {
                        keyedRouted = false;
                        if (player != null) player.setVideoTextureView(textureView);
                        applyHostVisibility(true);
                    }
                }
            });
            // Match the plain view's layout slot so updateBaseLayout can size either one.
            addView(kv, new LayoutParams(Math.max(1, baseW), Math.max(1, baseH), Gravity.CENTER));
            keyedView = kv;
        }
        if (keyedView != null) {
            // Push the authored key every sync, not only on change: a slider drag must show up
            // live, and this is cheap (two array reads).
            keyedView.setSpec(clip.getCompositing());
        }
        // The FX composite owns the pixels while it is routed, and only an actual KEY takes them
        // back — otherwise this method's per-tick "reattach the plain TextureView" would undo the
        // routing on the next frame and the PiP would vanish from the graded chain.
        if (fxSurface != null && !wantKeyed) {
            routeToFxIfWanted();
            if (fxRouted) return;
        } else if (fxRouted) {
            fxRouted = false;
            textureView.setAlpha(1f);
        }
        boolean canKey = wantKeyed && keyedInputSurface != null;
        if (canKey == keyedRouted && routedPlayer == player) return;
        keyedRouted = canKey;
        routedPlayer = player;
        if (player != null) {
            if (canKey) {
                player.setVideoSurface(keyedInputSurface);
            } else {
                player.setVideoTextureView(textureView);
            }
            // A newly attached surface gets NO frame until something makes the renderer produce
            // one, and while PAUSED nothing does — so turning the key on from the panel (which
            // is always done paused) left the tier black and the PiP vanished. It reads exactly
            // like "the key deleted my video", and the eyedropper faithfully sampled the black.
            // A zero-distance seek forces the frame at the current position to be re-rendered
            // without moving the playhead. Only on a real switch: this sits after the
            // already-routed early return, so it costs nothing per tick.
            player.seekTo(player.getCurrentPosition());
        }
        // The tier that is not rendering must be hidden, or the stale one sits on top showing
        // the last frame it ever drew.
        applyHostVisibility(true);
        updateBaseLayout();
        applyTransform(clip);
    }

    /**
     * The compositing spec changed under the panel — re-push it and repaint. Separate from a
     * plain {@code invalidate()} because turning the key ON has to be able to CREATE the GL
     * tier: an invalidate alone would repaint the unkeyed view forever and the panel's sliders
     * would appear to do nothing, which is the exact "looks trustworthy, is not" failure the
     * key's UI was withheld for.
     */
    public void refreshCompositing() {
        if (active != null) routeFor(active);
        invalidate();
    }

    /**
     * Sample the raw colour under a point in THIS view's coordinates — the eyedropper.
     *
     * <p><b>Called by the activity's {@code dispatchTouchEvent}, not by this view's own
     * {@code onTouchEvent}, and that is not incidental.</b> Five sibling layers sit ABOVE this
     * one in {@code activity_faditor_editor.xml} — waveform, layer image, sprite, text and
     * caption overlays — and the text layer in particular consumes taps in the preview. An
     * eyedropper armed inside this view therefore never receives the tap at all: it is not that
     * the guards reject it, it is that {@code onTouchEvent} is never called. Interception has
     * to happen above every sibling, which means the activity.</p>
     *
     * <p>Reported as a FAILURE (null) rather than silently ignored when there is no keyed tier
     * or the point missed the PiP — the panel toasts on null, so the user is told why instead
     * of concluding the dropper is broken.</p>
     */
    public void sampleAt(float x, float y, @NonNull ChromaKeyTextureView.ColorSink sink) {
        Clip top = hitTest(x, y);
        ChromaKeyTextureView kv = keyedView;
        if (KEYDIAG) {
            android.util.Log.d("KEYDIAG", "dropper tap=(" + x + "," + y + ")"
                    + " hit=" + (top != null) + " kv=" + (kv != null)
                    + " routed=" + keyedRouted + " baseW=" + baseW + " baseH=" + baseH);
        }
        if (top == null || kv == null || !keyedRouted) {
            sink.onColorSampled(null);
            return;
        }
        // View coords → the PiP's own normalised surface coords. The inverse of applyTransform:
        // undo the centre translation, then the scale. Rotation is deliberately NOT undone —
        // sampleRawColor reads the unrotated decoder frame, and the PiP's rotation is a VIEW
        // property applied after it, so a rotated PiP would sample the wrong pixel. Guarded
        // below rather than silently returning a wrong colour.
        float scale = Math.max(0.0001f, readValue(top, KeyframeSet.SCALE, DEFAULT_SCALE));
        float rot = readValue(top, KeyframeSet.ROTATION, 0f);
        if (Math.abs(rot) > 0.5f) { sink.onColorSampled(null); return; }
        float cx = getWidth() / 2f + videoHost().getTranslationX();
        float cy = getHeight() / 2f + videoHost().getTranslationY();
        float u = (x - cx) / (baseW * scale) + 0.5f;
        float v = (y - cy) / (baseH * scale) + 0.5f;
        if (KEYDIAG) {
            android.util.Log.d("KEYDIAG", "dropper uv=(" + u + "," + v + ") rot=" + rot
                    + " scale=" + scale);
        }
        if (u < 0f || u > 1f || v < 0f || v > 1f) { sink.onColorSampled(null); return; }
        kv.sampleRawColor(u, v, sink);
    }

    /**
     * Show the routed tier and hide the other; {@code false} hides both.
     *
     * <p>The keyed tier stays laid out whenever keying is WANTED, not merely once it is routed —
     * see the note in {@code routeFor}: hiding it before its surface exists is what deadlocks
     * the handover. During that gap both are visible; the keyed one is transparent until it has
     * frames, so the plain one shows through and there is no flicker at the switch.</p>
     */
    private void applyHostVisibility(boolean visible) {
        if (keyedView != null) {
            keyedView.setVisibility(visible && (keyedRouted || keyedWanted) ? VISIBLE : GONE);
        }
        textureView.setVisibility(visible && !keyedRouted ? VISIBLE : GONE);
    }

    /** Release the overlay decoder entirely (activity onDestroy / export start). */
    public void releasePlayer() {
        // The tier below goes with it: every caller here (export start, onDestroy, a decoder
        // error) is a reason to be holding no overlay codecs at all.
        releaseExtraLive();
        if (player != null) {
            try { player.release(); } catch (RuntimeException ignored) { }
            player = null;
        }
        active = null;
        videoW = 0;
        videoH = 0;
        keyedRouted = false;
        routedPlayer = null;
        // The FX tier too. Leaving fxRouted set here is what stranded the PiP: the next player
        // was built wired to textureView, routeToFxIfWanted saw "already routed" and skipped,
        // and applyTransform kept the view at alpha 0 forever.
        fxRouted = false;
        fxRoutedPlayer = null;
        textureView.setAlpha(1f);
        applyHostVisibility(false);
    }

    public boolean isEmpty() { return clips.isEmpty(); }

    // ── Core sync ─────────────────────────────────────────────────────────────

    private void syncToTime() {
        if (callback == null) return;
        Clip top = topVisibleAt(currentTimeMs);
        syncExtraLive(top);
        refreshStills(top);
        if (top == null) {
            applyHostVisibility(false);
            if (player != null) player.pause();
            return;
        }
        ensureActive(top);
        if (player == null) return;
        // Route BEFORE showing: routeFor decides which tier is the host, and applyHostVisibility
        // reads that decision.
        routeFor(top);
        applyHostVisibility(true);
        applyTransform(top);
        syncPlayerTo(player, top);
    }

    /**
     * Hold {@code p} on the frame {@code clip} should be showing at the playhead: in step with
     * the master while it plays, on the scrubbed frame while it does not.
     *
     * <p>Shared by the top-most PiP and by the tier below it so a stack cannot drift apart from
     * itself. N players is not N times the disk churn during a drag: every seek here is
     * {@code CLOSEST_SYNC} — a keyframe fetch, not a decode-to-exact walk — and media3 drops a
     * pending seek when a newer one supersedes it, so a fast drag costs each player roughly one
     * fetch as it settles rather than one per tick.</p>
     */
    private void syncPlayerTo(@NonNull ExoPlayer p, @NonNull Clip clip) {
        long want = clip.getInPointMs() + Math.max(0, currentTimeMs - clip.getOverlayStartMs());
        long pos = p.getCurrentPosition();
        if (masterPlaying) {
            if (!p.isPlaying()) {
                p.seekTo(want);
                p.play();
            } else if (Math.abs(pos - want) > DRIFT_RESYNC_MS) {
                p.seekTo(want);
            }
        } else {
            if (p.isPlaying()) p.pause();
            if (Math.abs(pos - want) > SCRUB_RESEEK_MS) {
                p.seekTo(want);
            }
        }
    }

    // ── The live tier below the top-most PiP ─────────────────────────────────────────────────

    /**
     * Give the visible overlay clips BELOW the top-most one their own decoders, up to
     * {@link FxPreviewTextureView#MAX_LIVE_PIPS}, so a stack of PiPs actually plays instead of
     * flicking through 400ms stills.
     *
     * <p><b>The gate is the composite.</b> This tier renders only through
     * {@link FxPreviewTextureView}, because that is the only place more than one live PiP can be
     * drawn at all — the sibling-View path has exactly one TextureView.
     * {@code FxLivePreviewController} engages the chain for a stacked project for this reason.
     * With no composite, or with only one overlay video on screen, this returns having allocated
     * nothing, which is what keeps the one-PiP project identical to what it was.</p>
     *
     * <p>Clips are taken from the TOP down: the PiPs nearest the front are the ones being looked
     * at, so they get the decoders and the deepest ones keep their stills. Slots are assigned
     * positionally, which is stable by construction at a cap of two — raising the cap should
     * first keep a clip in the slot it already holds, or one clip leaving visibility would
     * shuffle the others and re-prepare players that were already on the right frame.</p>
     */
    private void syncExtraLive(@Nullable Clip top) {
        Surface[] slots = fxExtraSurfaces;
        if (top == null || fxSurface == null || slots == null || extraLiveFailed
                || FxPreviewTextureView.MAX_LIVE_PIPS < 2) {
            idleExtraLive();
            return;
        }
        ExtraLive[] pool = extraLive;
        int cap = Math.min(FxPreviewTextureView.MAX_LIVE_PIPS, slots.length);
        int next = 1;
        for (int i = clips.size() - 1; i >= 0 && next < cap; i--) {
            Clip c = clips.get(i);
            if (c == top || c.isImageClip() || c.isHiddenObject()) continue;
            long start = c.getOverlayStartMs();
            if (currentTimeMs < start || currentTimeMs > start + Math.max(0,
                    c.getTrimmedDurationMs())) {
                continue;
            }
            if (slots[next] == null) break;      // that input is not built yet — keep the still
            if (pool == null) {
                pool = new ExtraLive[FxPreviewTextureView.MAX_LIVE_PIPS];
                extraLive = pool;
            }
            if (pool[next] == null) pool[next] = new ExtraLive(next);
            bindExtra(pool[next], c, slots[next]);
            next++;
        }
        // Slots that found no clip this tick keep their player, paused. Releasing on every gap
        // between stacked clips would be exactly the create/release churn a pool exists to
        // avoid; the whole tier is released when the project stops being stacked.
        if (pool != null) {
            for (int s = next; s < pool.length; s++) {
                if (pool[s] != null) bindExtra(pool[s], null, null);
            }
        }
    }

    /** Bind (or idle) one pooled decoder. Reuses the player across clips; never rebuilds it. */
    private void bindExtra(@NonNull ExtraLive el, @Nullable Clip clip, @Nullable Surface surface) {
        if (clip == null || surface == null || callback == null) {
            el.clip = null;
            if (el.player != null && el.player.isPlaying()) el.player.pause();
            return;
        }
        if (el.player == null) {
            try {
                ExoPlayer p = new ExoPlayer.Builder(getContext()).build();
                p.setVolume(0f);   // silent until the clip's own opt-in says otherwise
                p.setSeekParameters(SeekParameters.CLOSEST_SYNC);
                p.addListener(new Player.Listener() {
                    @Override public void onVideoSizeChanged(@NonNull VideoSize size) {
                        if (size.width > 0 && size.height > 0) {
                            el.videoW = size.width;
                            el.videoH = size.height;
                            invalidate();
                        }
                    }

                    @Override public void onPlayerError(@NonNull PlaybackException error) {
                        // This is what running out of hardware decoders looks like. Stand the
                        // whole tier down and let every clip below the top-most go back to its
                        // still, rather than leaving a black PiP where a video should be.
                        FLog.w(TAG, "extra overlay decoder failed — falling back to stills",
                                error);
                        extraLiveFailed = true;
                        // POSTED: this is one of this player's own listener callbacks, and
                        // releasing a player from inside its own dispatch is asking for
                        // trouble. The flag above already stops the next tick rebinding it.
                        post(OverlayVideoPreviewView.this::releaseExtraLive);
                    }
                });
                el.player = p;
            } catch (RuntimeException e) {
                FLog.w(TAG, "could not build an extra overlay decoder — stills it is", e);
                extraLiveFailed = true;
                releaseExtraLive();
                return;
            }
        }
        ExoPlayer p = el.player;
        if (el.surface != surface) {
            p.setVideoSurface(surface);
            el.surface = surface;
        }
        if (el.clip != clip) {
            el.clip = clip;
            el.videoW = 0;
            el.videoH = 0;
            p.setMediaItem(MediaItem.fromUri(callback.resolveSeekable(clip)));
            p.prepare();
            FLog.i(TAG, "extra overlay decoder " + el.slot + " bound to layer="
                    + clip.getLayerId() + " start=" + clip.getOverlayStartMs() + "ms");
        }
        if (el.player != p) return;   // a prepare-time error tore the tier down under us
        // The SAME opt-in authority the top-most PiP uses, so stacking cannot make a project
        // suddenly audible: every clip that has not opted into overlay audio answers 0, which is
        // every clip by default. Pushed on change only — per-tick setVolume is a message to the
        // audio renderer for nothing.
        float v = Math.max(0f, callback.overlayVolumeFor(clip));
        if (v != el.volume) {
            el.volume = v;
            p.setVolume(v);
        }
        syncPlayerTo(p, clip);
    }

    /** The pooled decoder bound to {@code clip}, or null when it is not in the live tier. */
    @Nullable
    private ExtraLive extraFor(@NonNull Clip clip) {
        ExtraLive[] pool = extraLive;
        if (pool == null) return null;
        for (ExtraLive el : pool) {
            if (el != null && el.clip == clip && el.player != null) return el;
        }
        return null;
    }

    /** Pause every pooled decoder and unbind its clip, keeping the players for the next stack. */
    private void idleExtraLive() {
        ExtraLive[] pool = extraLive;
        if (pool == null) return;
        for (ExtraLive el : pool) {
            if (el != null) bindExtra(el, null, null);
        }
    }

    /** Release the whole tier. Idempotent, and a no-op on every project that never stacked. */
    private void releaseExtraLive() {
        ExtraLive[] pool = extraLive;
        if (pool == null) return;
        extraLive = null;
        for (ExtraLive el : pool) {
            if (el == null || el.player == null) continue;
            try { el.player.release(); } catch (RuntimeException ignored) { }
            el.player = null;
            el.clip = null;
            el.surface = null;
        }
    }

    /**
     * Still-frame upkeep for every visible clip EXCEPT the live top-most: kick an
     * async decode when the clip's 400ms source bucket moved, drop stills for
     * clips no longer visible (bounded memory), and redraw when anything changed.
     */
    private void refreshStills(@Nullable Clip top) {
        boolean changed = false;
        java.util.Set<String> visibleIds = new java.util.HashSet<>();
        for (Clip c : clips) {
            // A clip with its own live decoder needs no still — and must not keep one, or the
            // MMR worker would decode frames nothing draws.
            if (c == top || extraFor(c) != null) continue;
            long start = c.getOverlayStartMs();
            long end = start + Math.max(0, c.getTrimmedDurationMs());
            if (currentTimeMs < start || currentTimeMs > end) continue;
            visibleIds.add(c.getId());
            long sourceMs = c.getInPointMs() + (currentTimeMs - start);
            long bucket = (sourceMs / STILL_BUCKET_MS) * STILL_BUCKET_MS;
            StillFrame sf = stills.get(c.getId());
            if (sf == null) {
                sf = new StillFrame();
                stills.put(c.getId(), sf);
            }
            if (sf.bucketMs != bucket && sf.pendingBucketMs != bucket) {
                sf.pendingBucketMs = bucket;
                decodeStillAsync(c, bucket);
            }
        }
        java.util.Iterator<java.util.Map.Entry<String, StillFrame>> it =
                stills.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, StillFrame> e = it.next();
            if (!visibleIds.contains(e.getKey())) {
                if (e.getValue().bitmap != null) recycleStill(e.getValue().bitmap);
                it.remove();
                changed = true;
            }
        }
        if (changed) invalidate();
    }

    private void decodeStillAsync(@NonNull Clip clip, long bucketMs) {
        final Uri uri = callback != null ? callback.resolveSeekable(clip) : clip.getSourceUri();
        final String id = clip.getId();
        final Runnable task = () -> {
            android.graphics.Bitmap frame = null;
            android.media.MediaMetadataRetriever r = null;
            try {
                r = new android.media.MediaMetadataRetriever();
                r.setDataSource(getContext(), uri);
                frame = r.getFrameAtTime(bucketMs * 1000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                FLog.w(TAG, "still decode failed for " + uri.getLastPathSegment(), e);
            } finally {
                if (r != null) { try { r.release(); } catch (Exception ignored) { } }
            }
            final android.graphics.Bitmap result = frame;
            post(() -> {
                StillFrame sf = stills.get(id);
                if (sf == null) { // clip left visibility while we decoded
                    if (result != null) recycleStill(result);
                    return;
                }
                if (result != null) {
                    if (sf.bitmap != null) recycleStill(sf.bitmap);
                    sf.bitmap = result;
                    sf.bucketMs = bucketMs;
                }
                if (sf.pendingBucketMs == bucketMs) sf.pendingBucketMs = Long.MIN_VALUE;
                invalidate();
            });
        };
        try {
            stillExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Detached (executor shut down) — stills simply stop refreshing.
        }
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        if (callback == null || stills.isEmpty()) return;
        // While the FX composite owns the pixels, every still is graded INSIDE its chain in
        // z-order; drawing them here too would double every PiP AND hoist them ABOVE the
        // adjustment layers meant to grade them — the exact z-inversion the routing exists
        // to fix.
        if (fxSurface != null) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        Clip top = topVisibleAt(currentTimeMs);
        // List order = z bottom→top; stills are all below the live TextureView child.
        for (Clip c : clips) {
            if (c == top) continue;
            StillFrame sf = stills.get(c.getId());
            if (sf == null || sf.bitmap == null || sf.bitmap.isRecycled()) continue;
            KeyframeSet kf = c.getOverlayTransform();
            long t = currentTimeMs;
            float x = kf == null ? DEFAULT_X : kf.valueAt(KeyframeSet.X, t, DEFAULT_X);
            float y = kf == null ? DEFAULT_Y : kf.valueAt(KeyframeSet.Y, t, DEFAULT_Y);
            float scale = kf == null ? DEFAULT_SCALE : kf.valueAt(KeyframeSet.SCALE, t, DEFAULT_SCALE);
            float rot = kf == null ? 0f : kf.valueAt(KeyframeSet.ROTATION, t, 0f);
            float alpha = kf == null ? 1f : Math.max(0f, Math.min(1f,
                    kf.valueAt(KeyframeSet.OPACITY, t, 1f)));
            float fit = Math.min(r.width() / sf.bitmap.getWidth(),
                    r.height() / sf.bitmap.getHeight());
            float w = sf.bitmap.getWidth() * fit * scale;
            float h = sf.bitmap.getHeight() * fit * scale;
            float cx = r.left + x * r.width();
            float cy = r.top + y * r.height();
            stillPaint.setAlpha(Math.round(alpha * 255));
            // Compositing masks (§C): same shapes, same authority as export —
            // masked in content-rect space BEFORE the rotate (a hole stays put
            // over the frame while the PiP moves under it, mirroring
            // PipFrameOverlay's order exactly).
            com.fadcam.ui.faditor.model.CompositingSpec cspec = c.getCompositing();
            com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope ms =
                    com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                            canvas, cspec, kf, t, r.width(), r.height(), r.left, r.top);
            if (rot != 0f) canvas.rotate(rot, cx, cy);
            canvas.drawBitmap(sf.bitmap, null, new RectF(
                    cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), stillPaint);
            com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(canvas, ms);
        }
    }

    /**
     * Compositing masks on the LIVE overlay (§C): the TextureView child clips
     * through the SAME MaskPathBuilder authority as the export, in parent
     * space (the child's translate/scale/rotate applies inside drawChild, so
     * the mask stays fixed over the content rect while the PiP moves — the
     * export-order parity). TextureView renders through the view-hierarchy
     * canvas (unlike SurfaceView), so canvas clipping genuinely applies.
     */
    @Override
    protected boolean drawChild(@NonNull android.graphics.Canvas canvas,
                                @NonNull android.view.View child, long drawingTime) {
        // videoHost(), not textureView: with a key live the decoder paints into the keyed tier,
        // and a mask that only clipped the plain view would silently stop masking the moment
        // the key was switched on — two features that must compose, not cancel.
        if (child == videoHost() && active != null && callback != null) {
            com.fadcam.ui.faditor.model.CompositingSpec cs = active.getCompositing();
            if (cs != null && cs.hasMasks()) {
                RectF r = callback.getVideoContentRect();
                if (r.width() > 0 && r.height() > 0) {
                    com.fadcam.ui.faditor.model.MaskPathBuilder.MaskScope ms =
                            com.fadcam.ui.faditor.model.MaskPathBuilder.beginMask(
                                    canvas, cs, active.getOverlayTransform(), currentTimeMs,
                                    r.width(), r.height(), r.left, r.top);
                    boolean res = super.drawChild(canvas, child, drawingTime);
                    com.fadcam.ui.faditor.model.MaskPathBuilder.endMask(canvas, ms);
                    return res;
                }
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    /** Top-most visible overlay clip (list order = z, bottom→top; end-INCLUSIVE like sprites). */
    @Nullable
    // ── Off-stage ghosts ──────────────────────────────────────────────────────

    /**
     * Outline colours. Green = the object, yellow = its mask — JoyRaptor's own pairing,
     * 2026-08-06, and worth keeping distinct because the two travel independently once a mask
     * is unlinked.
     */
    private static final int GHOST_OBJECT = 0xFF35F6BF;
    private static final int GHOST_MASK = 0xFFFBBF24;

    private final android.graphics.Paint ghostPaint = new android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path ghostPath = new android.graphics.Path();

    /**
     * Draw a dashed outline wherever a PiP or one of its mask shapes has left the visible
     * frame, so an object panned off-stage can still be found.
     *
     * <p>Positions may now travel a full frame beyond each edge ({@code KeyframeSet.POS_MIN}),
     * which is exactly what makes an object easy to lose — "oh no, where did my asset go"
     * (user, 2026-08-06). The ghost is the answer to that, so it arrived in the same breath as
     * the range that needs it.</p>
     *
     * <p><b>Drawn only when something is ACTUALLY off-stage.</b> An outline around every object
     * all the time is chrome; one that appears exactly when a thing becomes hard to see is
     * information.</p>
     *
     * <p>Two tiers. If any part of the outline is inside this view it is drawn in place, in the
     * letterbox beside the frame. If the object has gone past the view as well — reachable,
     * since the range is a whole frame — the outline would be invisible, so an EDGE TICK is
     * drawn instead, on the side the object left by, at its centre's height. Without that
     * second tier the feature would quietly stop working at exactly the distances that need it
     * most.</p>
     */
    private void drawOffStageGhosts(@NonNull android.graphics.Canvas canvas) {
        if (callback == null || clips.isEmpty()) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        ghostPaint.setStyle(android.graphics.Paint.Style.STROKE);
        ghostPaint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 1.5f));
        ghostPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{9f, 7f}, 0f));

        for (Clip c : clips) {
            long start = c.getOverlayStartMs();
            long end = start + Math.max(0, c.getTrimmedDurationMs());
            if (currentTimeMs < start || currentTimeMs > end) continue;
            if (c.isHiddenObject()) continue;

            KeyframeSet kf = c.getOverlayTransform();
            long t = currentTimeMs;
            float x = kf == null ? DEFAULT_X : kf.valueAt(KeyframeSet.X, t, DEFAULT_X);
            float y = kf == null ? DEFAULT_Y : kf.valueAt(KeyframeSet.Y, t, DEFAULT_Y);
            float scale = kf == null ? DEFAULT_SCALE
                    : kf.valueAt(KeyframeSet.SCALE, t, DEFAULT_SCALE);
            float rot = kf == null ? 0f : kf.valueAt(KeyframeSet.ROTATION, t, 0f);

            // The still's own fit when there is one, else the routed host's base box. Using
            // baseW/baseH for every clip would draw a ghost the size of the ACTIVE video.
            StillFrame sf = stills.get(c.getId());
            ExtraLive el = extraFor(c);
            float bw, bh;
            if (el != null && el.videoW > 0 && el.videoH > 0) {
                float fit = Math.min(r.width() / el.videoW, r.height() / el.videoH);
                bw = el.videoW * fit;
                bh = el.videoH * fit;
            } else if (sf != null && sf.bitmap != null && !sf.bitmap.isRecycled()) {
                float fit = Math.min(r.width() / sf.bitmap.getWidth(),
                        r.height() / sf.bitmap.getHeight());
                bw = sf.bitmap.getWidth() * fit;
                bh = sf.bitmap.getHeight() * fit;
            } else {
                bw = baseW; bh = baseH;
            }
            if (bw <= 0 || bh <= 0) continue;
            bw *= scale; bh *= scale;

            float cx = r.left + x * r.width();
            float cy = r.top + y * r.height();
            RectF box = new RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f);
            if (r.contains(box)) continue;   // fully on stage — nothing to find

            ghostPaint.setColor(GHOST_OBJECT);
            drawGhostBox(canvas, r, box, rot, cx, cy);

            // Mask shapes travel independently of the object once unlinked, so they get their
            // own ghost rather than being assumed to sit inside the object's.
            com.fadcam.ui.faditor.model.CompositingSpec cs =
                    com.fadcam.ui.faditor.model.MaskAnimator.resolve(
                            c.getCompositing(), kf, t, r.width(), r.height());
            if (cs == null) continue;
            ghostPaint.setColor(GHOST_MASK);
            for (com.fadcam.ui.faditor.model.CompositingSpec.MaskShape m : cs.masks) {
                float mw = m.w * r.width(), mh = m.h * r.height();
                float mcx = r.left + m.cx * r.width(), mcy = r.top + m.cy * r.height();
                RectF mb = new RectF(mcx - mw / 2f, mcy - mh / 2f, mcx + mw / 2f, mcy + mh / 2f);
                if (r.contains(mb)) continue;
                drawGhostBox(canvas, r, mb, m.rotationDeg, mcx, mcy);
            }
        }
        ghostPaint.setPathEffect(null);
    }

    /**
     * One ghost outline, or an edge tick when the thing has left the FRAME entirely.
     *
     * <p><b>Everything here is measured against the content rect, not this view's bounds.</b>
     * The view is wider than the frame — there is letterbox either side — but something above
     * it paints that letterbox, so anything drawn out there is invisible. Verified on device:
     * an outline at view-x 744..1060 rendered only as far as the content rect's right edge at
     * 782. So the frame, not the view, is the usable canvas, and a tick placed at the view
     * edge would be a locator nobody can see.</p>
     */
    private void drawGhostBox(@NonNull android.graphics.Canvas canvas, @NonNull RectF frame,
                              @NonNull RectF box, float rotDeg, float cx, float cy) {
        boolean offFrame = box.right < frame.left || box.left > frame.right
                || box.bottom < frame.top || box.top > frame.bottom;
        if (!offFrame) {
            // Partly in view: draw it where it really is and let the frame clip it. The edge
            // that is still inside points straight at the rest.
            canvas.save();
            if (rotDeg != 0f) canvas.rotate(rotDeg, cx, cy);
            ghostPath.reset();
            ghostPath.addRect(box, android.graphics.Path.Direction.CW);
            canvas.drawPath(ghostPath, ghostPaint);
            canvas.restore();
            return;
        }
        // Wholly outside: a tick on the frame edge it left by, at its own centre's height, so
        // the direction it went is still readable. Without this the feature would quietly stop
        // working at exactly the distances that need it most.
        float d = getResources().getDisplayMetrics().density;
        float inset = 3f * d, len = 18f * d;
        float ty = Math.max(frame.top + len, Math.min(frame.bottom - len, cy));
        float tx = Math.max(frame.left + len, Math.min(frame.right - len, cx));
        android.graphics.PathEffect dashes = ghostPaint.getPathEffect();
        ghostPaint.setPathEffect(null);      // an 18dp tick has no room for dashes
        if (box.right < frame.left) {
            canvas.drawLine(frame.left + inset, ty - len / 2f,
                    frame.left + inset, ty + len / 2f, ghostPaint);
        } else if (box.left > frame.right) {
            canvas.drawLine(frame.right - inset, ty - len / 2f,
                    frame.right - inset, ty + len / 2f, ghostPaint);
        } else if (box.bottom < frame.top) {
            canvas.drawLine(tx - len / 2f, frame.top + inset,
                    tx + len / 2f, frame.top + inset, ghostPaint);
        } else {
            canvas.drawLine(tx - len / 2f, frame.bottom - inset,
                    tx + len / 2f, frame.bottom - inset, ghostPaint);
        }
        ghostPaint.setPathEffect(dashes);
    }

    /**
     * Ghosts draw AFTER the children, unlike the stills in {@link #onDraw}. An off-stage object
     * lies in the letterbox where the TextureView may still be sitting, and a ghost drawn
     * behind it would be hidden by the very object it exists to locate.
     */
    @Override
    protected void dispatchDraw(@NonNull android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        try {
            drawOffStageGhosts(canvas);
        } catch (RuntimeException e) {
            // A locator is never worth taking the preview down for.
            com.fadcam.FLog.w("OverlayPreview", "ghost draw failed: " + e);
        }
    }

    private Clip topVisibleAt(long t) {
        for (int i = clips.size() - 1; i >= 0; i--) {
            Clip c = clips.get(i);
            long start = c.getOverlayStartMs();
            long end = start + Math.max(0, c.getTrimmedDurationMs());
            if (t >= start && t <= end) return c;
        }
        return null;
    }

    private void ensureActive(@NonNull Clip clip) {
        if (clip == active && player != null) return;
        if (player == null) {
            player = new ExoPlayer.Builder(getContext()).build();
            player.setVideoTextureView(textureView);
            player.setVolume(0f); // silent until a clip opts in — applyActiveVolume() below
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(@NonNull VideoSize size) {
                    if (size.width > 0 && size.height > 0) {
                        videoW = size.width;
                        videoH = size.height;
                        updateBaseLayout();
                        if (active != null) applyTransform(active);
                    }
                }

                @Override public void onPlayerError(@NonNull PlaybackException error) {
                    // Never black the editor over a PiP: drop the live overlay, keep master.
                    FLog.e(TAG, "overlay decoder error — hiding PiP for this session", error);
                    releasePlayer();
                }
            });
        }
        Uri uri = callback.resolveSeekable(clip);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        active = clip;
        applyActiveVolume();
        videoW = 0;
        videoH = 0;
        FLog.i(TAG, "overlay decoder bound to clip layer=" + clip.getLayerId()
                + " start=" + clip.getOverlayStartMs() + "ms uri=" + uri.getLastPathSegment());
    }

    /**
     * Push the bound clip's effective volume onto the player. Called whenever the decoder is
     * (re)bound, and by {@link #refreshVolume()} when the host changes a mute/volume/lane
     * flag. Silent when nothing is bound or the host says 0 — the default for every PiP that
     * has not opted into audio, which reproduces this view's historical hardcoded silence.
     */
    private void applyActiveVolume() {
        if (player == null || active == null || callback == null) return;
        float v = callback.overlayVolumeFor(active);
        player.setVolume(Math.max(0f, v));
    }

    /** Host hook: re-read the bound PiP's volume after a mute/volume/lane-mute change. */
    public void refreshVolume() {
        applyActiveVolume();
        ExtraLive[] pool = extraLive;
        if (pool == null) return;
        for (ExtraLive el : pool) {
            if (el == null || el.player == null || el.clip == null || callback == null) continue;
            float v = Math.max(0f, callback.overlayVolumeFor(el.clip));
            if (v == el.volume) continue;
            el.volume = v;
            el.player.setVolume(v);
        }
    }

    // ── Transform ─────────────────────────────────────────────────────────────

    /** Full-fit box of the overlay video inside the content rect = scale 1.0 reference. */
    private void updateBaseLayout() {
        if (callback == null || videoW <= 0 || videoH <= 0) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        float fit = Math.min(r.width() / videoW, r.height() / videoH);
        int w = Math.max(1, Math.round(videoW * fit));
        int h = Math.max(1, Math.round(videoH * fit));
        if (w == baseW && h == baseH) return;
        baseW = w;
        baseH = h;
        // BOTH tiers are sized, not just the routed one: the keyed view can be created while a
        // size is already settled, and a switch must not have to wait for the next size change
        // to stop being 1x1.
        sizeHost(textureView, w, h);
        if (keyedView != null) sizeHost(keyedView, w, h);
    }

    private void sizeHost(@NonNull TextureView v, int w, int h) {
        LayoutParams lp = (LayoutParams) v.getLayoutParams();
        if (lp == null) return;
        lp.width = w;
        lp.height = h;
        lp.gravity = Gravity.CENTER;
        v.setLayoutParams(lp);
    }

    private void applyTransform(@NonNull Clip clip) {
        if (callback == null) return;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return;
        updateBaseLayout();
        float x = readValue(clip, KeyframeSet.X, DEFAULT_X);
        float y = readValue(clip, KeyframeSet.Y, DEFAULT_Y);
        float scale = readValue(clip, KeyframeSet.SCALE, DEFAULT_SCALE);
        float rot = readValue(clip, KeyframeSet.ROTATION, 0f);
        float alpha = Math.max(0f, Math.min(1f,
                readValue(clip, KeyframeSet.OPACITY, 1f)));
        // Base box is centered in this layer; content rect is centered too (resize_mode=fit),
        // so translation maps the normalized centre into content-rect pixels directly.
        // Applied to the ROUTED host — a keyed PiP is positioned by exactly the same maths as
        // an unkeyed one, because turning the key on must not move anything.
        TextureView host = videoHost();
        host.setScaleX(scale);
        host.setScaleY(scale);
        host.setRotation(rot);
        // Zero while the FX composite owns the pixels. This runs EVERY tick, so writing the
        // clip's own opacity here would undo the hide a frame after routing and leave the
        // ungraded PiP sitting on top of the graded one — which is exactly what it did.
        host.setAlpha(fxRouted ? 0f : alpha);
        host.setTranslationX((x - 0.5f) * r.width());
        host.setTranslationY((y - 0.5f) * r.height());
        // Export clips the PiP at the canvas; the preview must not show pixels
        // the export can't have (M-EXPORT-2 review note: preview-honesty clamp).
        setClipBounds(new android.graphics.Rect(
                Math.round(r.left), Math.round(r.top),
                Math.round(r.right), Math.round(r.bottom)));
    }

    private float readValue(@NonNull Clip clip, @NonNull String property, float fallback) {
        KeyframeSet kf = clip.getOverlayTransform();
        return kf == null ? fallback : kf.valueAt(property, currentTimeMs, fallback);
    }

    /**
     * Write a transform value — collapsing to one key at t=0 ONLY when the track is not animated.
     *
     * <p><b>An animated track is updated in place, never cleared.</b> This used to clear
     * unconditionally, so keying a PiP's X at 0s and Y at 3s to fly it across frame and then
     * dragging its BODY on the canvas replaced the whole curve with a single static pose — and a
     * pinch did the same to SCALE. Dragging is the most casual way to touch a PiP; it is the
     * last thing that should be able to destroy an animation. The corner handles were taught
     * this; the body drag and the pinch, which come through here, had been left behind.</p>
     *
     * <p>"Animated" is decided from the track itself (more than one key) rather than from the
     * editor's ARMED flag, because this view has no access to that flag and the property it
     * really needs to preserve is the curve, not the mode that produced it.</p>
     */
    private void putStatic(@NonNull Clip clip, @NonNull String property, float value) {
        KeyframeSet kf = clip.getOverlayTransform();
        if (kf == null) {
            kf = new KeyframeSet();
            clip.setOverlayTransform(kf);
        }
        com.fadcam.ui.faditor.keyframe.KeyframeTrack track = kf.getOrCreate(property);
        if (track.keyframes.size() > 1) {
            // Move the key at (or nearest within a frame of) the playhead, so a drag nudges the
            // pose at this moment instead of stacking a new key every touch event.
            long at = currentTimeMs;
            for (com.fadcam.ui.faditor.keyframe.Keyframe k : track.keyframes) {
                if (Math.abs(k.timeMs - currentTimeMs) <= 66L) { at = k.timeMs; break; }
            }
            track.put(at, value, Easing.LINEAR);
            return;
        }
        track.keyframes.clear();
        track.put(0L, value, Easing.LINEAR);
    }

    // ── Gestures (SpriteOverlayView contract) ─────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (callback == null) return false;
        if (manipulating != null) scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                Clip hit = hitTest(e.getX(), e.getY());
                if (hit == null) return false; // pass through to the player
                manipulating = hit;
                KeyframeSet kf = hit.getOverlayTransform();
                beforeGesture = kf != null ? kf.copy() : new KeyframeSet();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = readValue(hit, KeyframeSet.X, DEFAULT_X);
                startY = readValue(hit, KeyframeSet.Y, DEFAULT_Y);
                startScale = readValue(hit, KeyframeSet.SCALE, DEFAULT_SCALE);
                moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (manipulating == null) return false;
                if (scaleDetector.isInProgress()) { moved = true; return true; }
                RectF r = callback.getVideoContentRect();
                if (r.width() <= 0 || r.height() <= 0) return true;
                // Raw deltas are SCREEN pixels; r is in this view's own pixels. They agree only
                // while nothing above is scaled, and the editor shrinks player_container to
                // clear an open drawer — at 0.6 the PiP moved 60% of the finger's travel and
                // slid out from under it. See UiScale.
                float ui = com.fadcam.ui.faditor.overlay.UiScale.of(this);
                float dx = (e.getRawX() - downRawX) / ui / r.width();
                float dy = (e.getRawY() - downRawY) / ui / r.height();
                if (Math.abs(e.getRawX() - downRawX) > 8
                        || Math.abs(e.getRawY() - downRawY) > 8) {
                    moved = true;
                }
                // clampPos, NOT clamp01: dragging must be able to take an object off-stage,
                // the same reach the Pos X / Pos Y sliders now have. Clamping the finger to
                // the visible frame while the slider could leave it would be two different
                // answers to one question.
                putStatic(manipulating, KeyframeSet.X, KeyframeSet.clampPos(startX + dx));
                putStatic(manipulating, KeyframeSet.Y, KeyframeSet.clampPos(startY + dy));
                applyTransform(manipulating);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                Clip c = manipulating;
                KeyframeSet before = beforeGesture;
                manipulating = null;
                beforeGesture = null;
                if (c != null && moved) {
                    if (e.getActionMasked() == MotionEvent.ACTION_CANCEL && before != null) {
                        // Interrupted gesture reverts — never commits (fcc0bd5 rule).
                        KeyframeSet kf = c.getOverlayTransform();
                        if (kf != null) kf.copyFrom(before);
                        applyTransform(c);
                    } else {
                        if (before != null) callback.onOverlayVideoManipulated(c, before);
                        callback.onOverlayVideoChanged();
                    }
                } else if (c != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    // A TAP SELECTS. This branch did not exist: a tap that moved nothing fell
                    // straight out of the gesture and fired no callback at all, so tapping a
                    // PiP on the canvas was a literal no-op — which is why the preview felt
                    // like it was eating touches. Selecting is what every other surface does
                    // with a tap, and it is what raises the row highlight and the trash badge.
                    callback.onOverlayVideoSelected(c);
                }
                return c != null;
            }
        }
        return false;
    }

    /** Hit = inside the active PiP's current (unrotated) drawn bounds; only the live one is grabbable. */
    @Nullable
    private Clip hitTest(float x, float y) {
        Clip top = topVisibleAt(currentTimeMs);
        // PASS-THROUGH: the object is on screen and editable from its row, but it is not
        // catching taps meant for what is behind it. Only the TOP PiP is hit-testable in this
        // tier, so a pass-through top means the tap continues to the master video — the deeper
        // "fall through to the PiP below" needs multi-PiP hit-testing, which this view does not
        // have yet (it drives one live decoder).
        if (top != null && top.isPassThrough()) return null;
        // The ROUTED host again: hit-testing the plain view while the keyed one is on screen
        // would make a keyed PiP ungrabbable — the drag would silently do nothing.
        TextureView host = videoHost();
        if (top == null || host.getVisibility() != VISIBLE || baseW <= 0) return null;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return null;
        float cx = getWidth() / 2f + host.getTranslationX();
        float cy = getHeight() / 2f + host.getTranslationY();
        float scale = readValue(top, KeyframeSet.SCALE, DEFAULT_SCALE);
        float minHalf = 24f * getResources().getDisplayMetrics().density / 2f;
        float hw = Math.max(baseW * scale / 2f, minHalf);
        float hh = Math.max(baseH * scale / 2f, minHalf);
        if (x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh) return top;
        return null;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releasePlayer();
        for (StillFrame sf : stills.values()) {
            if (sf.bitmap != null && !sf.bitmap.isRecycled()) recycleStill(sf.bitmap);
        }
        stills.clear();
        stillExecutor.shutdownNow();
    }
}
