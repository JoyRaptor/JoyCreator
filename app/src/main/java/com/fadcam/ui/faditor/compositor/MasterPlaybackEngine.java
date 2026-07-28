package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;

/**
 * M-COMP-0 — gapless master playback engine.
 *
 * <p>Plays the whole master track as a single {@link ExoPlayer} <b>playlist</b>: one
 * {@link MediaItem} per clip, each carrying a {@link MediaItem.ClippingConfiguration} from the
 * clip's in/out points, with every source routed through the editor's remux-to-seekable cache
 * (via a {@link SourceResolver} supplied by the caller). ExoPlayer pre-buffers the next item
 * natively, so crossing a plain cut is a <i>warm</i> {@link Player.Listener#onMediaItemTransition}
 * continuation instead of the cold {@code setMediaItem()+prepare()} re-prepare that produces the
 * 100-400ms boundary freeze diagnosed in {@code DIAG_20260701_transition_preview.md}.</p>
 *
 * <p>PROBE PASS (device-verified 2026-07-02, Note 9 serial SANDBOX_SERIAL, sandbox project
 * bdd51919 = 4 clips / 3 seams over genuine fragmented-MP4 FadCam recordings): the clipped playlist
 * built ({@code gapless playlist prepared: 4 clipped items}), rendered its first frame, and played
 * gaplessly through all three auto seams as warm {@code MEDIA_ITEM_TRANSITION_REASON_AUTO}
 * transitions — {@code ffmpeg freezedetect} found ZERO frozen frames at any seam (vs the legacy
 * path's 100-400ms cold-re-prepare stall on the same project), with no ExoPlayer/decoder errors.
 * A per-clip trim rebuilt the playlist and crossed the edited seam freeze-free; scrub across a seam
 * rendered the correct target frame; caption overlays animated continuously across every cut. This
 * validates the ClippingConfiguration-on-remuxed-fMP4 premise this engine rests on.</p>
 *
 * <h3>Eligibility</h3>
 * The engine handles the plain-cut case (PLAN §3.1 / M-COMP-0) PLUS L1: NORMAL-mode ({@code
 * Clip#LOOP_MODE_NORMAL}) loop-extension clips, PLUS L2: PING_PONG ({@code
 * Clip#LOOP_MODE_PING_PONG}) loop-extension clips whose baked-reversed file is already CACHED
 * (via {@link SourceResolver#resolveReversed}). It is eligible when EVERY master clip is a
 * non-image video clip whose loop mode is OFF / NORMAL / (baked) PING_PONG, and there are NO
 * transitions on the timeline. A PING_PONG clip whose reverse bake is missing or guard-too-long,
 * plus STILL clips / transitions / image clips, keep the proven legacy single-clip path (for
 * PING_PONG that means the legacy forward-tail preview until the bake completes and a rebuild
 * re-checks eligibility). Per-clip <b>speed</b> IS supported (applied on every window, including
 * loop reps and reverse legs — reverse legs play the baked file at the clip's speed).
 *
 * <h3>Loop-extension reps (L1)</h3>
 * A NORMAL-loop clip is expanded into MULTIPLE consecutive playlist windows: zero or more
 * "before" reps, the main pass, then zero or more "after" reps — each rep a clipped
 * {@link MediaItem} of the SAME (remux-resolved) source, all sharing one timeline clip index. Rep
 * count and each rep's played duration are computed with the EXACT clamp formula
 * {@code ExportManager.buildLoopExtensionItem} uses (~866-891: {@code reps = ceil(extensionMs /
 * trimmedPlayMs)}, {@code playedMs = min(trimmedPlayMs, extensionMs - repIndex*trimmedPlayMs)},
 * source range {@code [inPointMs, min(outPointMs, inPointMs + playedMs*speed))} — repIndex 0..N-1
 * appended in that order, so the LAST rep appended is the (possibly partial) clamped one) so that
 * preview window boundaries land on the SAME timeline offsets export produces. See
 * {@link #addLoopReps} for the mirrored math and {@link WindowInfo} for the per-window bookkeeping
 * that lets {@link #getCurrentPositionInWindow()} / {@link #getCurrentWindowDuration()} report a
 * single CONTINUOUS visual position/duration across all of a looped clip's reps (matching
 * {@code Clip#getVisualDurationMs()} / the {@code Timeline#getTotalDurationMs()} convention),
 * even though under the hood each rep is a separate ExoPlayer playlist window.
 *
 * <h3>Single-clip illusion</h3>
 * {@link FaditorPlayerManager} delegates its single-clip public API to this engine. To avoid
 * broad changes to {@code FaditorEditorActivity}'s polling loop, the engine preserves single-clip
 * semantics: {@link #getCurrentPositionInWindow()} is 0-based within the current TIMELINE CLIP
 * (continuous across loop reps when looped; ClippingConfiguration-window-local otherwise), and
 * {@link #isAtEndOfTimeline()} reports true only at the END of the whole playlist — never at an
 * internal seam (the engine crosses those itself and fires {@link SeamListener}). Seams BETWEEN
 * reps of the SAME clip are crossed silently (speed re-applied, no {@link SeamListener} callback)
 * so the activity's per-clip UI resync (caption/overlay rebind, trim UI, playhead re-home) never
 * re-fires mid-loop; the listener fires only when the TIMELINE CLIP actually changes. So the
 * activity keeps polling "the current clip" transparently while internal cold cuts AND loop wraps
 * simply vanish.
 */
public class MasterPlaybackEngine {

    private static final String TAG = "MasterPlayEngine";

    /** Resolves a clip's raw source URI to a SEEKABLE URI (remuxed faststart copy for fMP4). */
    public interface SourceResolver {
        @NonNull
        Uri resolveSeekable(@NonNull Clip clip);

        /**
         * L2: resolve the CACHED baked-reversed file for a PING_PONG clip's trimmed sub-range, or
         * {@code null} if the clip is not PING_PONG, its span is too long to bake, or the bake has
         * not completed yet. When non-null, the engine builds this clip's ping-pong reverse legs
         * from the returned file (TRUE reverse). When null for a PING_PONG clip, the clip (and thus
         * the whole timeline) is INELIGIBLE and playback falls to the legacy forward-tail path until
         * the bake finishes and a rebuild re-evaluates eligibility.
         */
        @Nullable
        default Uri resolveReversed(@NonNull Clip clip) {
            return null;
        }
    }

    /**
     * Fired (on the app main thread) when the player crosses into a different playlist window
     * belonging to a DIFFERENT TIMELINE CLIP than before. Window-to-window transitions BETWEEN
     * reps of the SAME looped clip (L1) do NOT fire this — they are crossed silently by the
     * engine (speed re-applied internally) so the activity's per-clip UI resync (caption/overlay
     * rebind, trim UI, playhead re-home) doesn't re-run on every loop wrap. See
     * {@link #internalListener}.
     */
    public interface SeamListener {
        /**
         * @param newClipIndex   the TIMELINE clip index now current (not the raw playlist window
         *                       index — for a looped clip several consecutive windows/reps share
         *                       one {@code newClipIndex})
         * @param autoAdvance    true when playback PLAYED THROUGH a plain cut
         *                       ({@code MEDIA_ITEM_TRANSITION_REASON_AUTO}); false when the window
         *                       change was caused by a user-initiated cross-item
         *                       {@code seekTo(window, pos)} ({@code REASON_SEEK}). Callers that
         *                       re-home the playhead to the new clip's start must do so only when
         *                       {@code autoAdvance} is true — on a seek the caller has already set
         *                       the authoritative (tapped/scrubbed) position.
         */
        void onSeam(int newClipIndex, boolean autoAdvance);
    }

    /**
     * Rank-1 resilience hook. Fired (on the app main thread, from the player's onPlayerError) when
     * the shared gapless player errors on a window that plays a baked REVERSED file (a PING_PONG
     * reverse leg). The listener MUST: (1) poison that clip's reversed URI in a per-session set the
     * {@link SourceResolver#resolveReversed} consults (so it returns null → that clip degrades to
     * forward reps ONLY, scoped per-clip), then (2) rebuild the gapless playlist and reseek to the
     * pre-error visual position. Because the failing window degrades to a forward-source rep with
     * the SAME clamp math, the black-out spread is contained to zero clips — playback resumes.
     *
     * <p>If the failing window is NOT a reverse leg (a forward source itself failing) the engine
     * cannot recover by poisoning a reversed URI; it reports {@code reversed == null} and the
     * listener falls back to whatever coarser handling it wants (today: log only, legacy behaviour).
     */
    public interface ErrorRecoveryListener {
        /**
         * @param clipId    the timeline clip id whose window failed, or null if unmappable
         * @param reversed  the poisoned reversed URI when the failing window was a reverse leg
         *                  (non-null ⇒ recoverable by degrading that clip to forward), else null
         * @param resumeClipId  clip id to reseek to after the rebuild (the pre-error visual clip)
         * @param resumeVisualPosMs  visual position within {@code resumeClipId} to reseek to
         */
        void onReverseWindowFailed(@Nullable String clipId, @Nullable Uri reversed,
                                   @Nullable String resumeClipId, long resumeVisualPosMs);
    }

    @NonNull
    private final Context context;
    @NonNull
    private final SourceResolver resolver;
    @NonNull
    private final SeamListener seamListener;
    @Nullable
    private ErrorRecoveryListener errorRecoveryListener;

    /** Debug-only: attach a media3 {@link androidx.media3.exoplayer.util.EventLogger} to every
     *  built player so the seam's decoder init/format-change/error signatures land in logcat. */
    private boolean eventLoggingEnabled = false;
    @Nullable
    private androidx.media3.exoplayer.util.EventLogger eventLogger;

    @Nullable
    private ExoPlayer player;
    @Nullable
    private PlayerView boundView;

    /**
     * Which "part" of a looped clip a window plays. MAIN windows exist for every clip (looped or
     * not) and carry the clip's full {@code [inPointMs, outPointMs)} range; BEFORE/AFTER windows
     * exist only for NORMAL-loop clips with a non-zero extension on that side.
     */
    private enum RepKind { MAIN, BEFORE, AFTER }

    /**
     * Per-playlist-window bookkeeping (L1). One entry per {@link MediaItem} actually added to the
     * ExoPlayer playlist — for a looped clip that is MULTIPLE consecutive entries (before-reps +
     * main + after-reps) all sharing {@link #clipIndex}/{@link #clipId}.
     */
    private static final class WindowInfo {
        final int clipIndex;      // index into Timeline.getClip(i) — SHARED across a clip's reps
        @NonNull final String clipId;
        @NonNull final RepKind kind;
        final long visualStartMs; // this window's offset within the clip's VISUAL duration (0-based)
        final long visualLenMs;   // this window's contribution to the clip's visual duration
        final float speed;
        /** True iff this window plays the baked REVERSED file (a PING_PONG reverse leg). Used by
         *  {@link #internalListener}'s onPlayerError to decide whether a failing window can be
         *  recovered by poisoning the reversed URI + degrading this clip to forward reps. */
        final boolean reverse;

        WindowInfo(int clipIndex, @NonNull String clipId, @NonNull RepKind kind,
                   long visualStartMs, long visualLenMs, float speed, boolean reverse) {
            this.clipIndex = clipIndex;
            this.clipId = clipId;
            this.kind = kind;
            this.visualStartMs = visualStartMs;
            this.visualLenMs = visualLenMs;
            this.speed = speed;
            this.reverse = reverse;
        }
    }

    /** One entry per playlist window, in playlist order (index == ExoPlayer media item index). */
    @NonNull
    private final List<WindowInfo> windows = new ArrayList<>();

    private int currentWindow = 0;
    /** Timeline clip index of {@link #currentWindow}, tracked separately so seam suppression
     *  (same-clip rep transitions) can detect "did the TIMELINE CLIP change" cheaply. */
    private int currentClipIndex = -1;

    private final Player.Listener internalListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            if (player == null) return;
            int idx = player.getCurrentMediaItemIndex();
            if (idx == currentWindow) return;
            currentWindow = idx;
            applyWindowSpeed(idx);
            int newClipIndex = idx >= 0 && idx < windows.size() ? windows.get(idx).clipIndex : -1;
            boolean clipChanged = newClipIndex != currentClipIndex;
            currentClipIndex = newClipIndex;
            // AUTO (played through) or SEEK across a boundary both need the activity's
            // per-clip UI sync. PLAYLIST_CHANGED (initial set) is skipped — the activity
            // sets up clip 0 itself on load. Seams BETWEEN REPS OF THE SAME LOOPED CLIP
            // (clipChanged == false) are crossed SILENTLY — no callback — so caption/overlay
            // rebind and playhead re-homing don't re-fire on every loop wrap (L1 seam
            // suppression; see SeamListener doc).
            if ((reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
                    && clipChanged && newClipIndex >= 0) {
                FLog.d(TAG, "seam -> window " + idx + " clip=" + newClipIndex + " reason=" + reason);
                seamListener.onSeam(newClipIndex, reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO);
            } else if (!clipChanged) {
                FLog.d(TAG, "loop rep wrap (suppressed) -> window " + idx
                        + " clip=" + newClipIndex + " reason=" + reason);
            }
        }

        @Override
        public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
            // RANK-1 RESILIENCE. With no handler here, ANY single-item decode failure left the one
            // shared player permanently errored → black spread across every clip (the ffcdc86
            // blackout). Map the failing window; if it is a baked REVERSED leg, hand it to the
            // recovery listener to POISON that reversed URI + rebuild (that clip degrades to forward
            // reps ONLY — scoped, not project-wide) + reseek to the pre-error visual position.
            int idx = player != null ? player.getCurrentMediaItemIndex() : -1;
            WindowInfo w = (idx >= 0 && idx < windows.size()) ? windows.get(idx) : null;
            String failClipId = w != null ? w.clipId : null;
            boolean isReverse = w != null && w.reverse;
            // Pre-error visual position for the reseek: the current window's visual start + local pos
            // (best-effort; if the player is already torn down we fall to the window's visual start).
            long resumeVisualPos = w != null ? w.visualStartMs : 0L;
            try {
                if (player != null && idx == currentWindow) {
                    long local = Math.max(0L, player.getCurrentPosition());
                    resumeVisualPos = (w != null ? w.visualStartMs : 0L) + local;
                }
            } catch (Exception ignored) { /* player may be in error state */ }
            FLog.e(TAG, "onPlayerError code=" + error.errorCode + " (" + error.getErrorCodeName()
                    + ") window=" + idx + " clip=" + failClipId + " reverseLeg=" + isReverse
                    + " msg=" + error.getMessage());
            if (errorRecoveryListener != null) {
                Uri reversedUri = null;
                if (isReverse && failClipId != null) {
                    // The reverse leg's own baked URI (the one to poison). All this clip's reverse
                    // windows share it; grab from the failing MediaItem if available, else null and
                    // let the listener resolve it from the clip id.
                    androidx.media3.common.MediaItem mi =
                            player != null ? player.getCurrentMediaItem() : null;
                    if (mi != null && mi.localConfiguration != null) {
                        reversedUri = mi.localConfiguration.uri;
                    }
                }
                errorRecoveryListener.onReverseWindowFailed(failClipId, reversedUri,
                        failClipId, resumeVisualPos);
            }
        }
    };

    public MasterPlaybackEngine(@NonNull Context context,
                                @NonNull SourceResolver resolver,
                                @NonNull SeamListener seamListener) {
        this.context = context.getApplicationContext();
        this.resolver = resolver;
        this.seamListener = seamListener;
    }

    /** Rank-1: register the reverse-leg failure recovery hook (see {@link ErrorRecoveryListener}). */
    public void setErrorRecoveryListener(@Nullable ErrorRecoveryListener listener) {
        this.errorRecoveryListener = listener;
    }

    /** Debug-flag-gated: when enabled, a media3 {@link androidx.media3.exoplayer.util.EventLogger}
     *  is attached to every built player (decoder init / input-format change / videoDisabled /
     *  onPlayerError → logcat under the "EventLogger" tag). Call BEFORE {@link #prepareTimeline}. */
    public void setEventLoggingEnabled(boolean enabled) {
        this.eventLoggingEnabled = enabled;
    }

    // ── Eligibility ──────────────────────────────────────────────────────

    /**
     * Resolver-unaware eligibility (legacy callers). Treats PING_PONG clips as INELIGIBLE because
     * without a resolver it can't know whether their reversed file is baked. Prefer
     * {@link #isEligible(Timeline, SourceResolver)}.
     */
    public static boolean isEligible(@Nullable Timeline timeline) {
        return isEligible(timeline, null);
    }

    /**
     * Whether the timeline is a single track the gapless playlist can serve: plain cuts, L1
     * NORMAL-loop clips, (L2) PING_PONG-loop clips whose baked-reversed file is already CACHED
     * ({@code resolver.resolveReversed(clip) != null}), and IMAGE clips (played as native media3
     * image windows via {@code MediaItem.Builder#setImageDurationMs} — the same pipeline export
     * uses — so a freeze-frame/photo insert no longer punts the WHOLE project back to the legacy
     * cold-re-prepare path; see road_map 🔴 P0 2026-07-07). Requires ≥2 clips and no transitions.
     * A PING_PONG clip whose bake is missing/too-long makes the whole timeline ineligible (legacy
     * forward-tail path handles it until the bake completes and a rebuild re-checks). STILL clips
     * remain legacy (L3).
     */
    public static boolean isEligible(@Nullable Timeline timeline, @Nullable SourceResolver resolver) {
        if (timeline == null) return false;
        int count = timeline.getClipCount();
        if (count < 2) return false;
        if (timeline.getTransitions() != null && !timeline.getTransitions().isEmpty()) return false;
        for (int i = 0; i < count; i++) {
            Clip c = timeline.getClip(i);
            if (c == null) return false;
            if (c.isImageClip()) {
                // A still frame is loop-mode-agnostic (looping/reversing a still is the still),
                // so ANY image clip is served as one image window of its full visual duration.
                if (c.getSourceUri() == null) return false;
                continue;
            }
            // SHORT-SPEED-CLIP FREEZE — FIXED AT THE ROOT (2026-07-07). The f855e51 eligibility
            // guard that used to bail short speed≠1 clips to the legacy path is REMOVED: the
            // freeze was a cross-renderer deadlock in media3 (a first window whose post-Sonic
            // audio undershoots the AudioTrack start threshold never starts the position clock;
            // video then can't drain, the reading period can't advance, and no more audio ever
            // arrives). The patched DefaultAudioSink now detects the never-started-track wedge
            // and forces playout (media3-patched "short-first-window deadlock" patch) — REQUIRES
            // the media3-exoplayer source substitution in settings.gradle.kts. Device-proven on
            // SM-N960U: "bisect A 2x clip" (250ms@2x, froze permanently) now plays end-to-end
            // with one ~400ms recovery hiccup; long-2x / image controls play with zero kicks.
            int loopMode = c.getLoopMode();
            if (loopMode == Clip.LOOP_MODE_OFF || loopMode == Clip.LOOP_MODE_NORMAL) continue;
            if (loopMode == Clip.LOOP_MODE_PING_PONG) {
                // Eligible ONLY when the reverse leg's baked file is cached. Not-yet-baked or
                // guard-too-long → ineligible → legacy forward-tail until a rebuild re-checks.
                if (resolver != null && resolver.resolveReversed(c) != null) continue;
                return false;
            }
            return false; // STILL (L3) or anything else → legacy
        }
        return true;
    }

    // ── Build / lifecycle ────────────────────────────────────────────────

    /**
     * Build the clipped playlist for {@code timeline} and prepare the player. Returns false if the
     * timeline is not eligible (caller must fall back to the legacy path).
     */
    public boolean prepareTimeline(@NonNull Timeline timeline, @NonNull PlayerView view) {
        if (!isEligible(timeline, resolver)) return false;
        releasePlayer();
        this.boundView = view;

        List<MediaItem> items = new ArrayList<>();
        windows.clear();
        int count = timeline.getClipCount();
        for (int i = 0; i < count; i++) {
            Clip clip = timeline.getClip(i);
            if (clip.isImageClip()) {
                // Image clip: one native image window for the clip's whole visual duration
                // (loop extensions on a still are just more of the same frame). The RAW source
                // URI is used — the remux-to-seekable resolver is for fMP4 video only.
                addImageWindow(i, clip, items);
                continue;
            }
            Uri seekable = resolver.resolveSeekable(clip);
            int loopMode = clip.getLoopMode();
            if (loopMode == Clip.LOOP_MODE_PING_PONG && clip.hasLoopExtension()) {
                // L2: reversed file is guaranteed cached here (isEligible gated on it).
                Uri reversed = resolver.resolveReversed(clip);
                buildLoopedClipWindows(i, clip, seekable, reversed, items);
            } else if (loopMode == Clip.LOOP_MODE_NORMAL && clip.hasLoopExtension()) {
                buildLoopedClipWindows(i, clip, seekable, null, items);
            } else {
                addMainWindow(i, clip, seekable, items, 0L, clip.getTrimmedDurationMs());
            }
        }

        ExoPlayer p = new ExoPlayer.Builder(context).build();
        p.setRepeatMode(Player.REPEAT_MODE_OFF);
        // NOTE (2026-07-28): playlist preloading was TRIED HERE AND DID NOT HELP — do not
        // re-add it without new evidence. media3's PreloadConfiguration.DEFAULT disables
        // preloading, so setting a 2s target looked like the obvious fix for the per-seam
        // stall. Measured on the Note 9 (project 74e36000, EventLogger): baseline seam cost
        // 250/271/330ms vs 219/236/398ms with preloading — the same mean, and the event
        // sequence was byte-for-byte the same shape (videoDisabled -> videoEnabled ->
        // downstreamFormat -> renderedFirstFrame, the next window's format still arriving only
        // AFTER the transition). It costs an extra buffered period for no measured gain.
        // The renderer teardown at each item transition is the thing to attack; see
        // handoff §0z 2026-07-28 ~06:15.
        p.addListener(internalListener);
        if (eventLoggingEnabled) {
            try {
                eventLogger = new androidx.media3.exoplayer.util.EventLogger();
                p.addAnalyticsListener(eventLogger);
                FLog.d(TAG, "EventLogger attached to gapless player (debug)");
            } catch (Throwable t) {
                FLog.w(TAG, "EventLogger attach failed (non-fatal): " + t.getMessage());
            }
        }
        p.setMediaItems(items);
        p.prepare();
        currentWindow = 0;
        currentClipIndex = windows.isEmpty() ? -1 : windows.get(0).clipIndex;
        applyWindowSpeed(0);
        this.player = p;
        view.setPlayer(p);
        FLog.d(TAG, "gapless playlist prepared: " + items.size() + " clipped items ("
                + count + " timeline clips)");
        return true;
    }

    /**
     * L1: expand a NORMAL-loop clip into before-reps + main pass + after-reps, each a separate
     * playlist window sharing {@code clipIndex}. Rep count/duration/source-range math MIRRORS
     * {@code ExportManager.buildLoopExtensionItem} (~866-891) EXACTLY — same {@code ceil} rep
     * count, same clamp formula, same source-range formula, same rep-append order (repIndex
     * 0..N-1, so the LAST rep appended — closest to the main pass for "before", furthest from it
     * for "after" — is the one that absorbs the clamp/partial duration) — so preview window
     * boundaries land on the same timeline offsets export produces (preview time == export time).
     */
    private void buildLoopedClipWindows(int clipIndex, @NonNull Clip clip, @NonNull Uri seekable,
                                         @Nullable Uri reversed, @NonNull List<MediaItem> items) {
        long trimmedPlayMs = clip.getTrimmedDurationMs();
        long loopBeforeMs = clip.getLoopBeforeMs();
        long loopAfterMs = clip.getLoopAfterMs();
        long visualCursorMs = 0L;

        if (loopBeforeMs > 0 && trimmedPlayMs > 0) {
            visualCursorMs = addLoopReps(clipIndex, clip, seekable, reversed, items, RepKind.BEFORE,
                    loopBeforeMs, trimmedPlayMs, visualCursorMs);
        }
        addMainWindow(clipIndex, clip, seekable, items, visualCursorMs, trimmedPlayMs);
        visualCursorMs += trimmedPlayMs;
        if (loopAfterMs > 0 && trimmedPlayMs > 0) {
            addLoopReps(clipIndex, clip, seekable, reversed, items, RepKind.AFTER,
                    loopAfterMs, trimmedPlayMs, visualCursorMs);
        }
    }

    /**
     * Append the before/after loop-extension rep windows for one side of a looped clip. Returns
     * the visual cursor AFTER the appended reps (== {@code startVisualMs + extensionMs}).
     *
     * <p>L2: when {@code reversedUri != null} this is a PING_PONG clip — alternate reps play the
     * baked REVERSED file (true reverse). The reverse decision mirrors
     * {@code ExportManager.buildLoopExtensionItem}: {@code reverse = (isBefore ? reps-1-r : r) % 2
     * == 1}. For a forward rep the window clips the (forward) source {@code [inPoint, inPoint+span)}.
     * For a reverse rep the window clips the reversed file to {@code [outPoint-endMs, outPoint-startMs)}
     * where {@code [startMs,endMs]} is that forward source range — i.e. reversed-file coords
     * {@code [(outPoint-inPoint) - span, (outPoint-inPoint))} = the LAST {@code span} ms of the
     * reversed file = the clip's head rolling backward. This is the SAME mapping export uses, so
     * preview reverse legs land on identical frames to export reverse legs.</p>
     */
    private long addLoopReps(int clipIndex, @NonNull Clip clip, @NonNull Uri seekable,
                              @Nullable Uri reversedUri, @NonNull List<MediaItem> items,
                              @NonNull RepKind kind,
                              long extensionMs, long trimmedPlayMs, long startVisualMs) {
        int reps = (int) Math.ceil(extensionMs / (double) trimmedPlayMs);
        float speed = clip.getSpeedMultiplier();
        long inPointMs = clip.getInPointMs();
        long outPointMs = clip.getOutPointMs();
        boolean isBefore = kind == RepKind.BEFORE;
        long visualCursorMs = startVisualMs;
        for (int r = 0; r < reps; r++) {
            // Same clamp as ExportManager.buildLoopExtensionItem: only the last rep in append
            // order can be partial; every earlier rep gets the full trimmedPlayMs.
            long playedMs = Math.min(trimmedPlayMs, extensionMs - (long) r * trimmedPlayMs);
            if (playedMs <= 0) continue;
            long sourceSpanMs = Math.max(1L, (long) (playedMs * speed));

            boolean reverse = reversedUri != null
                    && ((isBefore ? reps - 1 - r : r) % 2 == 1);

            Uri uri;
            long startMs;
            long endMs;
            if (reverse) {
                // Forward source range for this rep is [inPoint, inPoint+span]; map into the
                // reversed file (baked-time t ↔ source outPoint-t) → [outPoint-(inPoint+span),
                // outPoint-inPoint] == [(out-in)-span, (out-in)].
                uri = reversedUri;
                long revLen = outPointMs - inPointMs; // reversed file's own duration
                startMs = Math.max(0L, revLen - sourceSpanMs);
                endMs = revLen;
            } else {
                uri = seekable;
                startMs = inPointMs;
                endMs = Math.min(outPointMs, inPointMs + sourceSpanMs);
            }
            MediaItem item = new MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(startMs)
                                    .setEndPositionMs(endMs)
                                    .build())
                    .build();
            items.add(item);
            windows.add(new WindowInfo(clipIndex, clip.getId(), kind,
                    visualCursorMs, playedMs, speed, reverse));
            visualCursorMs += playedMs;
        }
        return visualCursorMs;
    }

    /** Append the (single) main-pass window for a clip — the un-looped full [inPoint,outPoint). */
    private void addMainWindow(int clipIndex, @NonNull Clip clip, @NonNull Uri seekable,
                                @NonNull List<MediaItem> items,
                                long visualStartMs, long visualLenMs) {
        long inMs = clip.getInPointMs();
        long outMs = clip.getOutPointMs();
        MediaItem item = new MediaItem.Builder()
                .setUri(seekable)
                .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(inMs)
                                .setEndPositionMs(outMs)
                                .build())
                .build();
        items.add(item);
        windows.add(new WindowInfo(clipIndex, clip.getId(), RepKind.MAIN,
                visualStartMs, visualLenMs, clip.getSpeedMultiplier(), /* reverse = */ false));
    }

    /**
     * Append the single window for an IMAGE clip: a media3 image {@link MediaItem}
     * ({@code setImageDurationMs}) played by the default {@code ImageRenderer} and displayed by
     * {@code PlayerView}'s image output — the SAME image pipeline {@code ExportManager}
     * ({@code buildVideoMediaItem}) uses, so preview and export agree on image handling. The
     * window spans the clip's whole VISUAL duration (trimmed + any loop extension: repeating or
     * reversing a still frame is identical to showing it longer), at speed 1 (speed is meaningless
     * for a still; the clip's duration IS the duration). No ClippingConfiguration — the image has
     * no source timeline to clip.
     */
    private void addImageWindow(int clipIndex, @NonNull Clip clip, @NonNull List<MediaItem> items) {
        long visualLenMs = Math.max(1L, clip.hasLoopExtension()
                ? clip.getVisualDurationMs() : clip.getTrimmedDurationMs());
        MediaItem item = new MediaItem.Builder()
                .setUri(clip.getSourceUri())
                .setImageDurationMs(visualLenMs)
                .build();
        items.add(item);
        windows.add(new WindowInfo(clipIndex, clip.getId(), RepKind.MAIN,
                /* visualStartMs = */ 0L, visualLenMs, /* speed = */ 1f, /* reverse = */ false));
    }

    private void applyWindowSpeed(int window) {
        if (player == null || window < 0 || window >= windows.size()) return;
        float speed = windows.get(window).speed;
        player.setPlaybackParameters(new PlaybackParameters(speed));
    }

    public void releasePlayer() {
        if (player != null) {
            player.removeListener(internalListener);
            if (eventLogger != null) {
                try { player.removeAnalyticsListener(eventLogger); } catch (Exception ignored) {}
                eventLogger = null;
            }
            player.release();
            player = null;
        }
    }

    public boolean isPrepared() {
        return player != null;
    }

    @Nullable
    public ExoPlayer getPlayer() {
        return player;
    }

    // ── Window <-> clip mapping ──────────────────────────────────────────

    public int getCurrentWindow() {
        return currentWindow;
    }

    /**
     * Clip id of the TIMELINE CLIP the current window belongs to, or null if not prepared. Unlike
     * {@link #getCurrentWindow()} (a raw playlist window index — MEANINGLESS as a clip index once
     * a looped clip occupies more than one window), this is stable across a clip's reps and is
     * what callers should use to resolve "which clip is playing right now" (e.g. resuming after a
     * playlist rebuild — see {@code FaditorPlayerManager#rebuildGaplessTimeline}).
     */
    @Nullable
    public String getCurrentClipId() {
        if (currentWindow < 0 || currentWindow >= windows.size()) return null;
        return windows.get(currentWindow).clipId;
    }

    /**
     * FIRST playlist window index for a clip id (the before-extension start when looped,
     * otherwise the main-pass window), or -1 if the clip is not in this playlist. Looped clips
     * occupy a CONTIGUOUS run of windows [firstWindowForClipId(id), lastWindowForClipId(id)] — see
     * {@link #visualPositionToWindow}.
     */
    public int windowForClipId(@Nullable String clipId) {
        if (clipId == null) return -1;
        for (int i = 0; i < windows.size(); i++) {
            if (windows.get(i).clipId.equals(clipId)) return i;
        }
        return -1;
    }

    /** LAST playlist window index for a clip id (its final loop rep, or its main pass if not
     *  looped), or -1 if not found. */
    private int lastWindowForClipId(@Nullable String clipId) {
        if (clipId == null) return -1;
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).clipId.equals(clipId)) return i;
        }
        return -1;
    }

    /**
     * Resolve a VISUAL position (0-based within the clip, across all its reps — the
     * {@code Clip#getVisualDurationMs()} convention) to the playlist window that contains it and
     * the window-LOCAL position within that window. Clamps to [0, clip's total visual length].
     * {@code fromWindow} is any window belonging to the target clip (used to scan forward/back to
     * that clip's contiguous window run without a clip-id string compare per window).
     */
    @NonNull
    private int[] visualPositionToWindow(int fromWindow, long visualPositionMs) {
        long clampedInput = Math.max(0L, Math.min(visualPositionMs, Integer.MAX_VALUE));
        if (fromWindow < 0 || fromWindow >= windows.size()) {
            return new int[]{Math.max(0, fromWindow), (int) clampedInput};
        }
        String clipId = windows.get(fromWindow).clipId;
        int first = fromWindow;
        while (first > 0 && windows.get(first - 1).clipId.equals(clipId)) first--;
        int last = fromWindow;
        while (last < windows.size() - 1 && windows.get(last + 1).clipId.equals(clipId)) last++;
        long clamped = Math.max(0L, visualPositionMs);
        for (int i = first; i <= last; i++) {
            WindowInfo w = windows.get(i);
            if (clamped < w.visualStartMs + w.visualLenMs || i == last) {
                long local = clamped - w.visualStartMs;
                local = Math.max(0L, Math.min(local, w.visualLenMs));
                return new int[]{i, (int) local};
            }
        }
        // Unreachable (loop always returns at i==last), but keep the compiler happy.
        WindowInfo w = windows.get(last);
        return new int[]{last, (int) Math.max(0L, Math.min(clamped - w.visualStartMs, w.visualLenMs))};
    }

    // ── Position / transport (all single-clip-relative; CONTINUOUS/visual across loop reps) ──

    /**
     * Seek to {@code visualPositionMs} — 0-based within the clip's VISUAL duration (across all
     * loop reps when looped, same as the trimmed region when not). Switches to whichever playlist
     * window contains that position (may differ from the clip's first window for a looped clip).
     */
    public void seekInClip(@NonNull String clipId, long visualPositionMs) {
        if (player == null) return;
        int window = windowForClipId(clipId);
        if (window < 0) return;
        int[] resolved = visualPositionToWindow(window, visualPositionMs);
        player.seekTo(resolved[0], resolved[1]);
    }

    /** Seek within the CURRENT clip, 0-based across its VISUAL duration (all its loop reps). */
    public void seekInCurrentWindow(long visualPositionMs) {
        if (player == null) return;
        int[] resolved = visualPositionToWindow(currentWindow, visualPositionMs);
        player.seekTo(resolved[0], resolved[1]);
    }

    /**
     * Current position, 0-based within the CURRENT CLIP's visual duration — CONTINUOUS across
     * loop reps (rep N's window-local position is offset by its {@code visualStartMs}), so the
     * playhead advances smoothly through an extension instead of resetting to 0 at every rep seam.
     * For a non-looped clip this is exactly the window-local (ClippingConfiguration-local)
     * position, unchanged from pre-L1 behavior.
     */
    public long getCurrentPositionInWindow() {
        if (player == null) return 0L;
        long local = Math.max(0L, player.getCurrentPosition());
        if (currentWindow < 0 || currentWindow >= windows.size()) return local;
        return windows.get(currentWindow).visualStartMs + local;
    }

    /**
     * Total VISUAL duration (ms) of the CURRENT CLIP — the sum of all its rep windows (== {@code
     * Clip#getVisualDurationMs()} when looped, == the trimmed duration otherwise). Pairs with
     * {@link #getCurrentPositionInWindow()} so position/duration both read "continuous across the
     * whole clip," matching the {@code Timeline#getTotalDurationMs()} / activity convention of
     * using visual duration for looped clips.
     */
    public long getCurrentWindowDuration() {
        if (player == null) return 0L;
        if (currentWindow < 0 || currentWindow >= windows.size()) {
            long d = player.getDuration();
            return d == androidx.media3.common.C.TIME_UNSET ? 0L : Math.max(0L, d);
        }
        int last = lastWindowForClipId(windows.get(currentWindow).clipId);
        if (last < 0) last = currentWindow;
        WindowInfo lastWindow = windows.get(last);
        return lastWindow.visualStartMs + lastWindow.visualLenMs;
    }

    /** True only when the WHOLE playlist has ended (never at an internal seam, including loop-rep
     *  seams). */
    public boolean isAtEndOfTimeline() {
        if (player == null) return false;
        if (player.getPlaybackState() == Player.STATE_ENDED) return true;
        // At the LAST playlist window (the last rep of the last clip, or its main pass if not
        // looped), near its end, treat as end-of-timeline so the activity's stop logic runs
        // (mirrors FaditorPlayerManager.isAtTrimEnd's 150ms grace).
        if (currentWindow == windows.size() - 1) {
            long d = player.getDuration();
            long dur = d == androidx.media3.common.C.TIME_UNSET ? 0L : Math.max(0L, d);
            long pos = Math.max(0L, player.getCurrentPosition());
            return dur > 0 && pos >= dur - 150L;
        }
        return false;
    }

    public void play() {
        if (player != null) player.play();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean getPlayWhenReady() {
        return player != null && player.getPlayWhenReady();
    }

    public boolean isReady() {
        return player != null && player.getPlaybackState() == Player.STATE_READY;
    }

    public boolean isEnded() {
        return player != null && player.getPlaybackState() == Player.STATE_ENDED;
    }

    public void setPlaybackSpeed(float speed, boolean pitchCompensation) {
        if (player != null) {
            float pitch = pitchCompensation ? 1.0f : speed;
            player.setPlaybackParameters(new PlaybackParameters(speed, pitch));
        }
    }

    public void setExactSeek(boolean exact) {
        if (player != null) {
            player.setSeekParameters(exact
                    ? androidx.media3.exoplayer.SeekParameters.EXACT
                    : androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        }
    }

    public void setVolume(float v) {
        if (player != null) player.setVolume(Math.max(0f, Math.min(1f, v)));
    }
}
