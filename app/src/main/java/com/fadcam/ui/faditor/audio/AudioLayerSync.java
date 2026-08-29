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
    /**
     * Last playing state seen by {@link #tick}. Parking is a PAUSED-ONLY operation — it calls
     * setPlayWhenReady(false), so doing it during playback silences the layer outright. The
     * debounced scrub park fires 120ms after a playhead move, and a playhead move happens
     * during playback constantly (seam advance, programmatic seeks), so without this guard a
     * scrub park lands mid-playback and kills the audio. Same class of bug as the late-entry
     * park removed below, reached by a different door.
     */
    private volatile boolean masterPlaying = false;

    private final Runnable scrubParkRunnable = new Runnable() {
        @Override public void run() {
            if (pendingScrubTargetMs < 0) return;
            long target = pendingScrubTargetMs;
            pendingScrubTargetMs = -1L;
            if (masterPlaying) return;   // never park a sounding layer
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
        // A scrub park queued moments ago must not fire after we start — it would park (and
        // therefore silence) the layers we are about to arm. Cancel it and latch playing now
        // rather than waiting for the first tick to set it, which is ~50ms too late.
        handler.removeCallbacks(scrubParkRunnable);
        pendingScrubTargetMs = -1L;
        masterPlaying = true;
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
        masterPlaying = isPlaying;
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
                        // LATE ENTRY DURING PLAYBACK — seek and start, NEVER park.
                        //
                        // This branch used to call parkAt() and start only once isParkedAt()
                        // agreed. That silenced every audio layer in the app (JoyRaptor, Note 20,
                        // 2026-08-29: "I'm not hearing sound anymore"), for two compounding
                        // reasons:
                        //
                        //  1. parkAt() calls setPlayWhenReady(FALSE). So the moment a layer is
                        //     not playing, this branch actively holds it not-playing — and
                        //     isPlaying() is false for a beat after EVERY start() while the
                        //     player buffers. armForPlay would start the layer, the next 50ms
                        //     tick would land inside that window, park it, and it never
                        //     recovered. The log said "armForPlay: started 1 layers (all
                        //     parked)" and the AudioTrack sat at state:idle forever.
                        //  2. isParkedAt() compares against a target recomputed from the
                        //     CURRENT playhead every tick, with a 15ms tolerance. The playhead
                        //     moves ~50ms per tick, so while playing, that test can essentially
                        //     never pass. There was no path out of the parked state.
                        //
                        // The pre-roll belongs to the TRANSPORT ARM (armForPlay) and to the
                        // paused/scrub paths, where the playhead is still and parking is
                        // exactly right. Mid-playback entry is a different problem with a
                        // different answer: get sound out now, let the drift lock below pull it
                        // into place — which is what that lock is FOR. A layer entering a few
                        // tens of ms late and converging is strictly better than a silent one.
                        long seekPos = ac.getInPointMs() + (playheadMs - start);
                        long dur = mp.getDuration();
                        if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
                        mp.seekTo(seekPos);
                        float vol = timeline != null
                                ? LayerPreviewController.effectivePreviewVolume(timeline, ac) : 1f;
                        mp.setVolume(vol, vol);
                        mp.start();
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
