package cc.joycreator.avatar.studio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import cc.joycreator.avatar.perform.Take;

import com.fadcam.ui.faditor.waveform.PcmSidecar;

import java.io.File;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

/**
 * Lips from a sound (slice 2b): a video's own sound, or a sound layer with no picture (a voiceover), to a talking
 * mouth on every frame of the character's take, through the pilot's voice-to-mouth step ({@link VoiceMouth}).
 *
 * <p>The sound is read from Studio's flat copy of it ({@link PcmSidecar}: mono, 22 050 a second), made once and kept,
 * the same copy scrubbing uses.</p>
 */
public final class SoundLips {
    private SoundLips() {}

    /**
     * @param itemToSourceMs where in the sound file each moment of the item is (-1 where the sound does not play)
     * @param done           on the main thread: {open[], vis[]} for {@code n} frames, or null when the sound has none
     *                       to give (no sound in the file, or it could not be read)
     */
    public static void read(@NonNull Context c, @NonNull Uri uri, @NonNull LongUnaryOperator itemToSourceMs, int n,
                            @NonNull Consumer<Object[]> done) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        PcmSidecar.bakeAsync(app, uri, new PcmSidecar.BakeCallback() {
            @Override
            public void onBaked(@NonNull File file) {
                Object[] out = null;
                try {
                    PcmSidecar.Handle h = PcmSidecar.open(app, uri);
                    if (h != null && h.frames > 0) out = frames(h, itemToSourceMs, n);
                } catch (RuntimeException e) {
                    out = null;
                }
                final Object[] fo = out;
                main.post(() -> done.accept(fo));
            }

            @Override
            public void onFailed(@NonNull String message) {
                main.post(() -> done.accept(null));
            }
        });
    }

    static Object[] frames(PcmSidecar.Handle h, LongUnaryOperator map, int n) {
        VoiceMouth vm = new VoiceMouth(PcmSidecar.RATE);
        VoiceMouth.Samples s = new VoiceMouth.Samples() {
            @Override public int count() { return h.frames; }
            @Override public float at(int i) { return h.frameAt(i); }
        };
        float[] open = new float[n];
        String[] vis = new String[n];
        boolean any = false;
        for (int f = 0; f < n; f++) {
            long src = map.applyAsLong(Math.round(f * 1000.0 / Take.FPS));
            if (src < 0) {
                open[f] = 0f;
                vis[f] = "Neutral";
                vm.reset();
                continue;
            }
            VoiceMouth.Mouth m = vm.at(s, (int) Math.round(src / 1000.0 * PcmSidecar.RATE));
            open[f] = m.open < 0.015f ? 0f : m.open;
            vis[f] = m.vis;
            if (open[f] > 0f) any = true;
        }
        return any ? new Object[]{open, vis} : null;
    }
}
