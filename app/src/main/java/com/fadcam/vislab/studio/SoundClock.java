package com.fadcam.vislab.studio;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.waveform.PcmSidecar;
import com.fadcam.vislab.audio.FeatureExtractor;
import com.fadcam.vislab.audio.Features;
import com.fadcam.vislab.audio.HitCounts;
import com.fadcam.vislab.audio.SpectrumAnalyzer;
import com.fadcam.vislab.gl.VisRenderer;

/**
 * What a VisLab look hears at a moment of a Studio clip: VisLab's own analysis (the one the room
 * and the browser pilot use) run over Studio's PCM copy of the source ({@link PcmSidecar}, 22050
 * Hz mono), on a fixed 60 Hz grid of SOURCE time. Playing or exporting, it steps forward a
 * frame or two at a time; after a jump (a scrub) it starts fresh 2.2 s before the new time and
 * replays up to it, which fills the 64-row history and settles the beat tracking. So a frame
 * looks the same in preview and in export.
 *
 * <p>Not thread-safe: one clock per item on the {@link OffscreenGl} thread, or under
 * {@link StudioEars}' lock.</p>
 */
final class SoundClock implements VisRenderer.HitCounter {

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
    private boolean heard;   // a sound has been reported once (StudioEars' log)
    private double now;      // the moment last asked for

    SoundClock(@NonNull Context context, @NonNull Uri uri) {
        this.app = context.getApplicationContext();
        this.uri = uri;
    }

    /** The features at source time {@code sourceSec}. Silence until Studio's PCM copy exists. */
    @NonNull
    Features at(double sourceSec) {
        now = sourceSec;
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

    /** The source's sound can be read (until then {@link #at} is silence). */
    boolean listening() {
        return handle() != null;
    }

    @Override
    public long beats() {
        HitCounts h = counts();
        return h != null ? h.beatsAt(now) : -1;
    }

    @Override
    public long kicks() {
        HitCounts h = counts();
        return h != null ? h.kicksAt(now) : -1;
    }

    @Nullable
    private HitCounts counts() {
        return handle() != null ? SoundHits.of(app, uri) : null;
    }

    /** True the first time this clock hears anything ({@code level} above silence), once. */
    boolean firstSound(float level) {
        if (heard || level <= 0f) return false;
        heard = true;
        return true;
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
            if (pcm == null) com.fadcam.FLog.w("StudioEars", "sound copy exists but would not open");
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
