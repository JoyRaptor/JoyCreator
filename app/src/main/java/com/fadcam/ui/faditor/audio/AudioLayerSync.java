package com.fadcam.ui.faditor.audio;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.compositor.AudioClipPreviewPlayer;
import com.fadcam.ui.faditor.compositor.LayerPreviewController;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.List;

/**
 * SPEC_20260829_AUDIO_SYNC_TRUTH §3.1–3.3 — owns audio pre-roll, coalesced start, drift lock.
 *
 * <p>All timing logic lives here. {@code FaditorEditorActivity} delegates via 4 tiny sites.
 */
public final class AudioLayerSync {

    private static final String TAG = "AudioLayerSync";

    public static final long LOCK_MS = 8L;
    public static final long TRIM_MS = 120L;
    public static final long PARK_WAIT_TIMEOUT_MS = 400L;
    private static final long SCRUB_DEBOUNCE_MS = 120L;
    private static final long AUDIO_STOP_DEBOUNCE_MS = 400L;

    private final Handler handler;
    @Nullable private Timeline timeline;
    @Nullable private List<AudioClip> clipsRef;
    @Nullable private List<AudioClipPreviewPlayer> playersRef;

    private long audioStoppedSinceMs = 0L;
    private long pendingScrubTargetMs = -1L;
    private final Runnable scrubParkRunnable = new Runnable() {
        @Override public void run() {
            if (pendingScrubTargetMs < 0) return;
            long target = pendingScrubTargetMs;
            pendingScrubTargetMs = -1L;
            parkAllAt(target);
        }
    };

    /** Per-player trimming state: true while we hold a micro-speed correction. */
    private final java.util.Map<AudioClipPreviewPlayer, Boolean> trimming = new java.util.HashMap<>();

    public AudioLayerSync(@NonNull Handler playheadHandler) {
        this.handler = playheadHandler;
    }

    public AudioLayerSync() {
        this(new Handler(Looper.getMainLooper()));
    }

    /** Bind the live lists (called from prepareAudioPlayer / after timeline changes). */
    public void bind(@Nullable Timeline tl,
                     @Nullable List<AudioClip> clips,
                     @Nullable List<AudioClipPreviewPlayer> players) {
        this.timeline = tl;
        this.clipsRef = clips;
        this.playersRef = players;
    }

    // ── Public entry points called from activity ───────────────────────

    /** Transport arm: park every sounding layer, then start them together. */
    public void armForPlay(final long playheadMs, @NonNull final Runnable startMaster) {
        if (clipsRef == null || playersRef == null) {
            startMaster.run();
            return;
        }
        // Compute targets for sounding layers
        final java.util.List<Integer> sounding = soundingIndices(playheadMs);
        if (sounding.isEmpty()) {
            startMaster.run();
            return;
        }
        for (int idx : sounding) {
            AudioClip ac = clipsRef.get(idx);
            AudioClipPreviewPlayer mp = safePlayer(idx);
            if (ac == null || mp == null) continue;
            long seekPos = ac.getInPointMs() + (playheadMs - ac.getOffsetMs());
            long dur = mp.getDuration();
            if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
            mp.parkAt(seekPos);
            // Ensure volume/effects are current
            if (timeline != null) {
                float vol = LayerPreviewController.effectivePreviewVolume(timeline, ac);
                mp.setVolume(vol, vol);
            }
        }
        final long start = SystemClock.elapsedRealtime();
        final Handler h = handler;
        Runnable waiter = new Runnable() {
            @Override public void run() {
                boolean allParked = true;
                for (int idx : sounding) {
                    AudioClip ac = clipsRef != null && idx < clipsRef.size() ? clipsRef.get(idx) : null;
                    AudioClipPreviewPlayer mp = safePlayer(idx);
                    if (ac == null || mp == null) continue;
                    long seekPos = ac.getInPointMs() + (playheadMs - ac.getOffsetMs());
                    long dur = mp.getDuration();
                    if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
                    if (!mp.isParkedAt(seekPos)) { allParked = false; break; }
                }
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (allParked || elapsed >= PARK_WAIT_TIMEOUT_MS) {
                    // Coalesced start: one pass
                    for (int idx : sounding) {
                        AudioClipPreviewPlayer mp = safePlayer(idx);
                        if (mp != null) {
                            try { mp.start(); } catch (Exception e) { FLog.w(TAG, "arm start failed", e); }
                        }
                    }
                    startMaster.run();
                    FLog.d(TAG, "armForPlay: started " + sounding.size() + " layers after " + elapsed + "ms"
                            + (allParked ? " (all parked)" : " (timeout)"));
                } else {
                    h.postDelayed(this, 20);
                }
            }
        };
        h.post(waiter);
    }

    /** Pause path: ensure parked at current pos (for instant resume). */
    public void parkOnPause(long playheadMs) {
        // Cancel any pending scrub park
        handler.removeCallbacks(scrubParkRunnable);
        pendingScrubTargetMs = -1L;
        parkAllAt(playheadMs);
        // Pause all players (activity also pauses, but we ensure)
        if (playersRef != null) {
            for (AudioClipPreviewPlayer mp : playersRef) {
                if (mp != null && mp.isPlaying()) {
                    try { mp.pause(); } catch (Exception ignored) {}
                }
                // Reset micro-speed to 1f exactly
                if (mp != null) mp.setPlaybackSpeed(1f);
            }
        }
        trimming.clear();
    }

    /** Call when playhead moves while paused — debounced park. */
    public void onPlayheadScrubbed(long playheadMs) {
        pendingScrubTargetMs = playheadMs;
        handler.removeCallbacks(scrubParkRunnable);
        handler.postDelayed(scrubParkRunnable, SCRUB_DEBOUNCE_MS);
    }

    /**
     * Periodic tick called from playheadUpdater (replaces old syncAudioPlayerWithPlayhead body).
     * @param playheadMs current absolute timeline playhead
     * @param isPlaying true if master is playing (or audioTail/image active)
     */
    public void tick(long playheadMs, boolean isPlaying) {
        if (clipsRef == null || playersRef == null) return;
        if (timeline != null && !timeline.hasAudioClips()) return;
        if (!isPlaying) {
            long now = SystemClock.elapsedRealtime();
            if (audioStoppedSinceMs == 0L) audioStoppedSinceMs = now;
            if (now - audioStoppedSinceMs > AUDIO_STOP_DEBOUNCE_MS) {
                // Pause all
                for (AudioClipPreviewPlayer mp : playersRef) {
                    if (mp != null && mp.isPlaying()) {
                        try { mp.pause(); mp.setPlaybackSpeed(1f); } catch (Exception ignored) {}
                    }
                }
                trimming.clear();
            }
            return;
        }
        audioStoppedSinceMs = 0L;

        // For each clip, handle enter/exit + drift correction
        for (int i = 0; i < clipsRef.size() && i < playersRef.size(); i++) {
            AudioClip ac = clipsRef.get(i);
            AudioClipPreviewPlayer mp = safePlayer(i);
            if (ac == null || mp == null) continue;
            long start = ac.getOffsetMs();
            long end = ac.getEndOnTimelineMs();
            boolean shouldSound = playheadMs >= start && playheadMs < end;
            try {
                if (shouldSound) {
                    if (!mp.isPlaying()) {
                        // Late entry during playback: park then start immediately (will be corrected by drift if needed)
                        long seekPos = ac.getInPointMs() + (playheadMs - start);
                        long dur = mp.getDuration();
                        if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
                        mp.parkAt(seekPos);
                        // If already parked, start now; otherwise drift handler will catch up.
                        // Do immediate start if parked, else wait one tick is okay.
                        if (mp.isParkedAt(seekPos)) {
                            float vol = timeline != null ? LayerPreviewController.effectivePreviewVolume(timeline, ac) : 1f;
                            mp.setVolume(vol, vol);
                            mp.start();
                        } else {
                            // Parked async; attempt start anyway next tick. For now park.
                        }
                    } else {
                        // Drift correction
                        long expected = ac.getInPointMs() + (playheadMs - start);
                        long actual = mp.getCurrentPosition();
                        long error = actual - expected;
                        long absErr = Math.abs(error);
                        boolean isTrimming = Boolean.TRUE.equals(trimming.get(mp));
                        if (absErr <= LOCK_MS) {
                            if (isTrimming) {
                                mp.setPlaybackSpeed(1f);
                                trimming.put(mp, false);
                                FLog.d(TAG, "drift lock [" + i + "] err=" + error + " -> 1.0");
                            }
                        } else if (absErr <= TRIM_MS) {
                            float speed = 1f - clamp(error / 1000f, -0.002f, 0.002f);
                            mp.setPlaybackSpeed(speed);
                            trimming.put(mp, true);
                            // Throttle log: only every 20 ticks effectively (caller logs anyway)
                        } else {
                            // Genuine desync: re-park and restart
                            FLog.w(TAG, "drift desync [" + i + "] err=" + error + " -> repark");
                            mp.pause();
                            mp.setPlaybackSpeed(1f);
                            trimming.put(mp, false);
                            long seekPos = ac.getInPointMs() + (playheadMs - start);
                            long dur = mp.getDuration();
                            if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
                            mp.parkAt(seekPos);
                            // Will start next tick when parked
                        }
                    }
                } else {
                    if (mp.isPlaying()) {
                        mp.pause();
                        mp.setPlaybackSpeed(1f);
                        trimming.remove(mp);
                    } else {
                        // Ensure speed is 1f when not sounding
                        if (Boolean.TRUE.equals(trimming.get(mp))) {
                            mp.setPlaybackSpeed(1f);
                            trimming.remove(mp);
                        }
                    }
                }
            } catch (Exception e) {
                FLog.e(TAG, "tick error [" + i + "]", e);
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void parkAllAt(long playheadMs) {
        if (clipsRef == null || playersRef == null) return;
        for (int i = 0; i < clipsRef.size() && i < playersRef.size(); i++) {
            AudioClip ac = clipsRef.get(i);
            AudioClipPreviewPlayer mp = safePlayer(i);
            if (ac == null || mp == null) continue;
            long start = ac.getOffsetMs();
            long end = ac.getEndOnTimelineMs();
            if (playheadMs >= start && playheadMs < end) {
                long seekPos = ac.getInPointMs() + (playheadMs - start);
                long dur = mp.getDuration();
                if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
                mp.parkAt(seekPos);
            }
        }
    }

    @NonNull
    private java.util.List<Integer> soundingIndices(long playheadMs) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        if (clipsRef == null) return out;
        for (int i = 0; i < clipsRef.size(); i++) {
            AudioClip ac = clipsRef.get(i);
            if (ac == null) continue;
            if (playheadMs >= ac.getOffsetMs() && playheadMs < ac.getEndOnTimelineMs()) out.add(i);
        }
        return out;
    }

    @Nullable
    private AudioClipPreviewPlayer safePlayer(int idx) {
        if (playersRef == null || idx < 0 || idx >= playersRef.size()) return null;
        AudioClipPreviewPlayer p = playersRef.get(idx);
        if (p != null && p.isReady()) return p;
        // Allow park even when not yet ready? Need to expose. For now allow non-ready for park.
        if (p != null) return p;
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
