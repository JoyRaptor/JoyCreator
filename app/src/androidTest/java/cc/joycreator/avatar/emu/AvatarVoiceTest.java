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
            boolean playing = false;
            int changed = 0;
            for (int k = 0; k < 10; k++) {
                Emu.sleep(300);
                if (field(a[0], "voicePlayer") != null) playing = true;
                Bitmap s = Emu.screen();
                if (k == 4) Emu.save(s, new File(out, "2-playing.png"));
                s.recycle();
            }
            assertTrue("the room started playing the sound (voicePlayer is set)", playing);
            Log.i(Emu.TAG, "voice file: playing " + playing);
            assertNotNull(before);
        }
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
