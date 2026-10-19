package com.fadcam.vislab.studio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.waveform.PcmSidecar;
import com.fadcam.vislab.audio.FeatureExtractor;
import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.audio.SpectrumAnalyzer;

/**
 * What a VisLab look hears at a moment of a Studio clip: VisLab's own analysis (the one the room
 * and the browser pilot use) run over Studio's PCM copy of the source ({@link PcmSidecar}, 22050
 * Hz mono), on a fixed 60 Hz grid of SOURCE time. Playing or exporting, it steps forward a
 * frame or two at a time; after a jump (a scrub) it starts fresh 2.2 s before the new time and
 * replays up to it, which fills the 64-row history and settles the beat tracking. So a frame
 * looks the same in preview and in export.
 *
 * <p>Not thread-safe: one clock per item, used on the {@link OffscreenGl} thread only.</p>
 */
final class SoundClock {

    private static final int FFT = 2048;
    private static final double STEP = 1.0 / 60.0;
    private static final double PREROLL = 2.2;   // seconds replayed after a jump
    private static final double AHEAD = 0.5;     // further than this ahead = a jump

    private final Context app;
    private final Uri uri;
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer(FFT);
    private final float[] mono = new float[FFT], wave = new float[FFT];
    @Nullable private PcmSidecar.Handle pcm;
    @Nullable private FeatureExtractor extractor;
    private boolean baking;
    private double at = Double.NaN;   // source seconds of the last analysed step
    private final Features last = new Features();

    SoundClock(@NonNull Context context, @NonNull Uri uri) {
        this.app = context.getApplicationContext();
        this.uri = uri;
    }

    /** The features at source time {@code sourceSec}. Silence until Studio's PCM copy exists. */
    @NonNull
    Features at(double sourceSec) {
        PcmSidecar.Handle h = handle();
        if (h == null) return last;
        if (extractor == null || Double.isNaN(at) || sourceSec < at || sourceSec - at > AHEAD) {
            extractor = new FeatureExtractor(PcmSidecar.RATE, FFT);
            at = Math.max(0, sourceSec - PREROLL) - STEP;
        }
        Features f = null;
        while (at + STEP <= sourceSec + 1e-9) {
            at += STEP;
            f = analyse(h, at);
        }
        // The extractor reuses one Features object: copy once, after the last step.
        if (f != null) f.copyTo(last);
        return last;
    }

    @NonNull
    private Features analyse(@NonNull PcmSidecar.Handle h, double t) {
        int end = (int) Math.round(t * PcmSidecar.RATE);
        int start = end - FFT;
        for (int i = 0; i < FFT; i++) {
            int s = start + i;
            mono[i] = s >= 0 && s < h.frames ? h.frameAt(s) : 0f;
            wave[i] = mono[i];   // the spectrum may window its input in place
        }
        float[] db = spectrum.analyze(mono);
        return extractor.update(db, wave, null, STEP);
    }

    @Nullable
    private PcmSidecar.Handle handle() {
        if (pcm != null) return pcm;
        if (PcmSidecar.isBaked(app, uri)) {
            pcm = PcmSidecar.open(app, uri);
            return pcm;
        }
        if (!baking) {
            baking = true;
            PcmSidecar.bakeAsync(app, uri, new PcmSidecar.BakeCallback() {
                @Override public void onBaked(@NonNull java.io.File file) { /* opened on next use */ }
                @Override public void onFailed(@NonNull String message) { /* stays silent */ }
            });
        }
        return null;
    }
}
