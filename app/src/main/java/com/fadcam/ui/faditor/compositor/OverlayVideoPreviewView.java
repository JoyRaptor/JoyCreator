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
 * overlay views. Owns ONE overlay {@link ExoPlayer} (the Part-10 probe measured the Note 9
 * comfortably running 3 simultaneous 1080p HEVC decoders, so master + this one live overlay is
 * well inside budget) rendering into a {@link TextureView} that this view positions, scales,
 * rotates and alpha-blends per tick from the clip's {@code overlayTransform} {@link KeyframeSet}
 * — the SAME evaluator ({@code KeyframeSet.valueAt} at the absolute timeline ms) the export path
 * samples, so preview and export cannot diverge on the transform math.</p>
 *
 * <p><b>Decoder policy (plan §3.3):</b> only the TOP-most visible overlay-video clip at the
 * playhead gets the live decoder. Other simultaneously-visible overlay videos are not drawn in
 * preview v1 (a cached-still fallback is a documented follow-up); export composites all of them
 * full-quality. When no overlay clip is visible the player pauses; when the bound list empties
 * the player is RELEASED so scrub-heavy single-track editing and background exports never pay
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
     * The player {@link #fxRouted} describes. A decoder error releases the player and a later
     * clip builds a NEW one wired to this view's own TextureView; without the identity check the
     * stale flag matched, routeToFxIfWanted early-returned, and the new decoder painted into a
     * view still held at alpha 0 -- the PiP disappeared for the rest of the session with nothing
     * on screen to say why. The keyed tier learned this already; see routedPlayer.
     */
    @Nullable private ExoPlayer fxRoutedPlayer;

    /** Route the PiP into {@code s}, or pass null to hand it back to this view's own surface. */
    public void setFxCompositeSurface(@Nullable Surface s) {
        if (s == fxSurface) return;
        fxSurface = s;
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
     * Whether the FX tier currently owns the PiP pixels. The host asks so it knows whether the
     * GL composite will actually show something, rather than assuming the routing took.
     */
    public boolean isFxRouted() {
        return fxRouted;
    }

    /**
     * The PiP's placement on the master frame, normalised, or null when nothing is on screen.
     *
     * <p>Read from the SAME {@code KeyframeSet} evaluation {@link #applyTransform} feeds the View
     * properties, so the GL composite lands exactly where the gesture layer thinks the PiP is.
     * Half-extents are in master-frame units, which is why they divide by the content rect: the
     * shader works in the master video's normalised space.</p>
     */
    @Nullable
    public FxPreviewTextureView.Pip fxPipGeometry() {
        if (callback == null || active == null || videoW <= 0 || videoH <= 0) return null;
        RectF r = callback.getVideoContentRect();
        if (r.width() <= 0 || r.height() <= 0) return null;
        float fit = Math.min(r.width() / videoW, r.height() / videoH);
        float baseW = videoW * fit, baseH = videoH * fit;
        float x = readValue(active, KeyframeSet.X, DEFAULT_X);
        float y = readValue(active, KeyframeSet.Y, DEFAULT_Y);
        float scale = readValue(active, KeyframeSet.SCALE, DEFAULT_SCALE);
        float rot = readValue(active, KeyframeSet.ROTATION, 0f);
        float alpha = Math.max(0f, Math.min(1f, readValue(active, KeyframeSet.OPACITY, 1f)));
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
                active.getFx(), currentTimeMs,
                active.getCompositing(),
                com.fadcam.ui.faditor.model.BlendModes.modeCode(active.getOverlayBlendMode()),
                Math.max(1, videoW), Math.max(1, videoH));
    }

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
            StillFrame sf = stills.get(clip.getId());
            if (sf == null || sf.bitmap == null || sf.bitmap.isRecycled()) return null;
            srcW = sf.bitmap.getWidth();
            srcH = sf.bitmap.getHeight();
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

        long want = top.getInPointMs() + Math.max(0, currentTimeMs - top.getOverlayStartMs());
        long pos = player.getCurrentPosition();
        if (masterPlaying) {
            if (!player.isPlaying()) {
                player.seekTo(want);
                player.play();
            } else if (Math.abs(pos - want) > DRIFT_RESYNC_MS) {
                player.seekTo(want);
            }
        } else {
            if (player.isPlaying()) player.pause();
            if (Math.abs(pos - want) > SCRUB_RESEEK_MS) {
                player.seekTo(want);
            }
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
            if (c == top) continue;
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
                if (e.getValue().bitmap != null) e.getValue().bitmap.recycle();
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
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) {
                    if (sf.bitmap != null) sf.bitmap.recycle();
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
    private static final int GHOST_OBJECT = 0xFF4CD964;
    private static final int GHOST_MASK = 0xFFFFD426;

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
            float bw, bh;
            if (sf != null && sf.bitmap != null && !sf.bitmap.isRecycled()) {
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
            if (sf.bitmap != null && !sf.bitmap.isRecycled()) sf.bitmap.recycle();
        }
        stills.clear();
        stillExecutor.shutdownNow();
    }
}
