package cc.joycreator.avatar.emu;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import cc.joycreator.avatar.AvatarRoomActivity;
import cc.joycreator.avatar.perform.Wav;

/**
 * Voice file in the room (parity row 34): a made-up sound of speech-like bursts is handed to the room; it must start playing and the mouth
 * must be driven by it (the room's player is set and the screen changes while it plays). Pictures: pictures/voice/*.png.
 */
@RunWith(AndroidJUnit4.class)
public class AvatarVoiceTest {

    @Test
    public void aSoundIsPlayedAndTheMouthTalksToIt() throws Exception {
        File out = Emu.dir("voice");
        // 4 seconds: a loud tone in bursts of 0.35 s with gaps, so the lip-sync finds a mouth opening and shutting
        int rate = 22050;
        float[] f = new float[rate * 4];
        for (int i = 0; i < f.length; i++) {
            double t = i / (double) rate;
            boolean burst = (t % 0.7) < 0.35;
            f[i] = burst ? (float) (0.6 * Math.sin(2 * Math.PI * 220 * t) + 0.25 * Math.sin(2 * Math.PI * 660 * t)) : 0f;
        }
        File wav = new File(Emu.ins().getTargetContext().getCacheDir(), "voice-test.wav");
        try (FileOutputStream o = new FileOutputStream(wav)) {
            o.write(Wav.encode(f, rate));
        }
        try (ActivityScenario<AvatarRoomActivity> sc = ActivityScenario.launch(AvatarRoomActivity.class)) {
            Emu.sleep(4000);
            AvatarRoomActivity[] a = new AvatarRoomActivity[1];
            sc.onActivity(x -> a[0] = x);
            android.widget.TextView[] tabs = (android.widget.TextView[]) field(a[0], "stageTabs");
            Emu.tap(tabs[3]);
            Emu.sleep(1500);
            Bitmap before = Emu.screen();
            Emu.save(before, new File(out, "1-before.png"));
            Method m = AvatarRoomActivity.class.getDeclaredMethod("startVoice", Uri.class);
            m.setAccessible(true);
            Emu.ins().runOnMainSync(() -> {
                try {
                    m.invoke(a[0], Uri.fromFile(wav));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // the planted fault (-e fault rest): the sound is not wired to the mouth (every frame's opening is zero)
            if ("rest".equals(Emu.fault())) {
                Field vo = AvatarRoomActivity.class.getDeclaredField("voiceOpen");
                vo.setAccessible(true);
                Emu.ins().runOnMainSync(() -> {
                    try {
                        float[] z = (float[]) vo.get(a[0]);
                        if (z != null) java.util.Arrays.fill(z, 0f);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            boolean playing = false;
            double lo = 9, hi = -9;
            int opened = 0, shut = 0;
            for (int k = 0; k < 80; k++) {                                 // 4 s of the room's own mouth signal, every 50 ms
                Emu.sleep(50);
                if (field(a[0], "voicePlayer") != null) playing = true;
                Object sig = field(a[0], "lastSignals");
                Object mv = sig instanceof java.util.Map ? ((java.util.Map<?, ?>) sig).get("mouth") : null;
                if (mv instanceof Number && field(a[0], "voicePlayer") != null) {
                    double v = ((Number) mv).doubleValue();
                    lo = Math.min(lo, v);
                    hi = Math.max(hi, v);
                    if (v > 0.2) opened++;
                    if (v < 0.05) shut++;
                }
                if (k == 40) {
                    Bitmap shot = Emu.screen();
                    Emu.save(shot, new File(out, "2-playing.png"));
                    shot.recycle();
                }
            }
            Log.i(Emu.TAG, "voice file: playing " + playing + ", mouth signal from " + lo + " to " + hi + ", open in " + opened + " samples, shut in " + shut);
            assertTrue("the room started playing the sound (voicePlayer is set)", playing);
            assertTrue("the mouth opens on the speech parts (peak " + hi + ", open in " + opened + " samples)", hi > 0.3 && opened >= 5);
            assertTrue("and closes in the gaps (low " + lo + ", shut in " + shut + " samples)", lo < 0.05 && shut >= 5);
            assertNotNull(before);
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
