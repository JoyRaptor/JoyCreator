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

    /**
     * Ticks of raw offset collected before a player's baseline is fixed. 12 ticks is ~600ms —
     * long enough for a median to reject one stalled tick, short enough that correction starts
     * almost immediately.
     */
    private static final int BASELINE_SAMPLES = 12;

    /**
     * Minimum gap between start attempts for one player. The late-entry branch fires whenever a
     * layer that SHOULD be sounding reports !isPlaying(), and that condition can persist —
     * a player parked at the end of its media never becomes "playing" however often it is
     * asked. Without this the branch retries every 50ms tick and each attempt allocates a
     * fresh AudioTrack: ~20 in one second were observed on the Note 9 around a transport
     * change. A start that has not taken effect in 300ms is not going to take effect because
     * it was asked a sixth time.
     */
    private static final long START_RETRY_MS = 300L;
    private final java.util.Map<AudioClipPreviewPlayer, Long> lastStartAttemptMs =
            new java.util.HashMap<>();

    /**
     * Consecutive out-of-band ticks required before the desync branch is allowed to REPARK.
     *
     * <p>A repark is the most violent thing this class can do: it pauses the layer, re-seeks it and
     * restarts it, and the late-entry branch is rate-limited to {@link #START_RETRY_MS}, so the
     * music is SILENT for a few hundred ms every time. Firing that on a SINGLE tick means any
     * one-off excursion — a decoder stall at a clip seam, a GC pause, one late playhead sample —
     * costs the user an audible drop-out and fade back in. That is exactly the "stammering /
     * staggering of the music, it drops out and fades in" report.
     *
     * <p>Genuine desync persists; a transient does not. At ~56ms per tick (measured on the Note 20)
     * five ticks is ~280ms of SUSTAINED error before the layer is touched — still far below what a
     * listener would call "out of sync", and it makes single-tick noise free.</p>
     */
    private static final int DESYNC_STREAK_TICKS = 5;

    /** Consecutive ticks each player has been outside TRIM_MS. Reset the moment it is back in band. */
    private final java.util.Map<AudioClipPreviewPlayer, Integer> desyncStreak = new java.util.HashMap<>();

    /** The constant pipeline offset per player, once measured. See the drift block in tick(). */
    private final java.util.Map<AudioClipPreviewPlayer, Long> baseline = new java.util.HashMap<>();
    private final java.util.Map<AudioClipPreviewPlayer, java.util.List<Long>> baselineSamples =
            new java.util.HashMap<>();

    /**
     * Forget a player's baseline. MUST be called every time it is (re)started: the offset is
     * only meaningful for one continuous run, and carrying a stale one across a start would
     * bake a real misalignment in as "zero".
     */
    private void resetBaseline(@Nullable AudioClipPreviewPlayer mp) {
        if (mp == null) return;
        baseline.remove(mp);
        baselineSamples.remove(mp);
        desyncStreak.remove(mp);
    }

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
                            resetBaseline(mp);
                            lastStartAttemptMs.remove(mp);
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
        baseline.clear();
        baselineSamples.clear();
        desyncStreak.clear();
        lastStartAttemptMs.clear();
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
                        // Rate-limit: see START_RETRY_MS. Also refuse to restart a player that
                        // has run off the end of its media — it can never report playing, so
                        // retrying is a guaranteed loop rather than a recoverable hiccup.
                        long nowMs = SystemClock.elapsedRealtime();
                        Long lastTry = lastStartAttemptMs.get(mp);
                        if (lastTry != null && nowMs - lastTry < START_RETRY_MS) continue;
                        long durMs = mp.getDuration();
                        if (durMs > 0 && seekPos >= durMs - 50) continue;
                        lastStartAttemptMs.put(mp, nowMs);

                        mp.seekTo(seekPos);
                        float vol = timeline != null
                                ? LayerPreviewController.effectivePreviewVolume(timeline, ac) : 1f;
                        mp.setVolume(vol, vol);
                        resetBaseline(mp);
                        mp.start();
                    } else {
                        // DRIFT CORRECTION, MEASURED AGAINST A CALIBRATED BASELINE.
                        //
                        // The raw difference between a layer's position and the master
                        // playhead is NOT drift. The two numbers come from different clocks:
                        // the master playhead is driven by the gapless engine's (video-side)
                        // clock, while an ExoPlayer's getCurrentPosition() reports where its
                        // AUDIO has actually reached — already behind by the output pipeline's
                        // buffering. On the Note 9 that constant is ~125ms.
                        //
                        // Treating it as error was catastrophic: it exceeds TRIM_MS, so every
                        // single tick took the "genuine desync" branch and re-parked, forever.
                        // That is what JoyRaptor heard as "stuttering... volume fading in and out...
                        // doesn't handle clip scenes well" (2026-08-29). The layer was being
                        // paused and restarted ~20 times a second.
                        //
                        // Crucially the constant is HARMLESS: the master's own audio goes
                        // through the same output path with the same latency, so a layer
                        // sitting one pipeline-length behind the video clock is correctly
                        // aligned with what you hear. "Fixing" it would have pushed the layer
                        // 125ms AHEAD of the master's audio.
                        //
                        // So: measure the offset once per start — right after the coalesced
                        // start, where every layer is parked at the same source position and
                        // alignment is correct BY CONSTRUCTION — take the median of the first
                        // few ticks, and call that zero. Real drift then shows up as movement
                        // away from it, which is the only thing worth correcting. Self-
                        // calibrating per device and per player; nothing is hardcoded.
                        long expected = ac.getInPointMs() + (playheadMs - start);
                        long actual = mp.getCurrentPosition();
                        long raw = actual - expected;

                        Long base = baseline.get(mp);
                        if (base == null) {
                            java.util.List<Long> s = baselineSamples.get(mp);
                            if (s == null) { s = new java.util.ArrayList<>(); baselineSamples.put(mp, s); }
                            s.add(raw);
                            if (s.size() >= BASELINE_SAMPLES) {
                                java.util.Collections.sort(s);   // median: one stalled tick
                                base = s.get(s.size() / 2);      // must not skew the baseline
                                baseline.put(mp, base);
                                baselineSamples.remove(mp);
                                FLog.d(TAG, "drift baseline [" + i + "] = " + base
                                        + "ms (pipeline offset, treated as zero)");
                            } else {
                                continue;   // still calibrating — correcting now would chase
                                            // the very constant we are trying to measure
                            }
                        }

                        long error = raw - base;
                        long absErr = Math.abs(error);
                        boolean isTrimming = Boolean.TRUE.equals(trimming.get(mp));
                        if (absErr <= LOCK_MS) {
                            desyncStreak.remove(mp);
                            if (isTrimming) {
                                mp.setPlaybackSpeed(1f);
                                trimming.put(mp, false);
                                FLog.d(TAG, "drift lock [" + i + "] err=" + error + " -> 1.0");
                            }
                        } else if (absErr <= TRIM_MS) {
                            desyncStreak.remove(mp);
                            float speed = 1f - clamp(error / 1000f, -0.002f, 0.002f);
                            mp.setPlaybackSpeed(speed);
                            trimming.put(mp, true);
                            // Throttle log: only every 20 ticks effectively (caller logs anyway)
                        } else {
                            // Out of band. NOT yet a repark — see DESYNC_STREAK_TICKS: a repark
                            // silences the layer for a few hundred ms, so it has to be earned by a
                            // SUSTAINED error, not by one late tick at a clip seam.
                            Integer prior = desyncStreak.get(mp);
                            int streak = (prior == null ? 0 : prior) + 1;
                            desyncStreak.put(mp, streak);
                            if (streak < DESYNC_STREAK_TICKS) continue;
                            desyncStreak.remove(mp);
                            // Genuine desync: re-park and restart
                            FLog.w(TAG, "drift desync [" + i + "] err=" + error
                                    + " (sustained " + streak + " ticks) -> repark");
                            mp.pause();
                            mp.setPlaybackSpeed(1f);
                            trimming.put(mp, false);
                            long seekPos = ac.getInPointMs() + (playheadMs - start);
                            long dur = mp.getDuration();
                            if (dur > 0 && seekPos >= dur) seekPos = Math.max(0, dur - 100);
                            resetBaseline(mp);
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
