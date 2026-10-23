package com.fadcam.vislab.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.SharedPreferencesManager;
import com.fadcam.vislab.audio.LiveAnalyzer;
import com.fadcam.vislab.audio.MicSource;
import com.fadcam.vislab.audio.PcmRing;
import com.fadcam.vislab.audio.RecordingTap;
import com.fadcam.vislab.audio.SoundSource;

/**
 * What the floating visualiser listens to, chosen by itself once a second:
 * <ul>
 *   <li>While a screen recording is running: exactly what it records (the phone's audio, the
 *       microphone, or whichever the recording is set to), handed over by the recorder.</li>
 *   <li>Otherwise, once the person let it hear the phone (Android's screen-capture question): the
 *       phone's own sound, music, games, video ({@link com.fadcam.vislab.audio.PhoneAudioSource}).</li>
 *   <li>Otherwise: the microphone.</li>
 * </ul>
 * The microphone is NEVER opened while a recording runs, paused included: on Android 10 the
 * newest listener takes the microphone, which would leave the recording silent.
 */
final class OverlaySound {

    static final int NONE = 0, RECORDING = 1, MIC = 2, PHONE = 3;

    interface Listener {
        void onSource(int kind);
    }

    private final Context app;
    private final PcmRing ring = new PcmRing(96000, 48000);
    final LiveAnalyzer analyzer = new LiveAnalyzer(ring);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    @Nullable private SoundSource source;
    /** The person's yes to hearing the phone; null until then, or after they take it back. */
    @Nullable private android.media.projection.MediaProjection projection;
    private int kind = -1;
    private boolean running;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            choose();
            main.postDelayed(this, 1000);
        }
    };

    OverlaySound(@NonNull Context c, @NonNull Listener l) {
        app = c.getApplicationContext();
        listener = l;
    }

    void start() {
        running = true;
        main.removeCallbacks(check);
        check.run();
    }

    void stop() {
        running = false;
        main.removeCallbacks(check);
        if (source != null) source.stop();
        source = null;
        kind = -1;
        usePhone(null);
    }

    /** Hear the phone through this projection (null: stop, back to the microphone). */
    void usePhone(@Nullable android.media.projection.MediaProjection p) {
        if (projection == p) return;
        android.media.projection.MediaProjection old = projection;
        projection = p;
        if (kind == PHONE && source != null) {   // drop the old capture before its projection goes
            source.stop();
            source = null;
            kind = -1;
        }
        if (old != null) old.stop();
        if (running) choose();
    }

    boolean hearsPhone() {
        return projection != null;
    }

    int kind() {
        return kind;
    }

    /** A screen recording is running, paused or starting (its sound is what we hear then). */
    boolean recordingRuns() {
        String s = SharedPreferencesManager.getInstance(app).getScreenRecordingState();
        return RecordingTap.isLive() || (s != null && !"NONE".equals(s));
    }

    private void choose() {
        boolean phone = projection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q;
        int want = recordingRuns() ? RECORDING : phone && MicSource.hasPermission(app) ? PHONE
                : (MicSource.hasPermission(app) ? MIC : NONE);
        if (want == kind) return;
        if (source != null) source.stop();
        source = null;
        SoundSource s = want == RECORDING ? new RecordingTap(ring)
                : want == PHONE ? new com.fadcam.vislab.audio.PhoneAudioSource(ring, projection)
                : want == MIC ? new MicSource(ring) : null;
        if (s != null && !s.start(app)) {
            s = null;
            want = NONE;
        }
        source = s;
        kind = want;
        analyzer.setSource(s);
        listener.onSource(want);
    }
}
