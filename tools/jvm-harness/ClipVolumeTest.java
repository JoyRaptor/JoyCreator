import com.fadcam.ui.faditor.model.Clip;

/**
 * Video-clip volume envelope, off device.
 *
 * <p><b>Why this exists.</b> run-envelope.sh covered AudioClip and nothing else, so when A7's
 * carrier refactor introduced field shadowing in BOTH VolumeKeyframe subclasses, the harness
 * caught the AudioClip half instantly and was blind to the Clip half. That half shipped to a
 * real device, where the symptom was: add a volume keyframe to a video clip and its sound goes
 * to zero permanently — raising the slider does nothing, un-muting does nothing. Reported as
 * "I could not get my voice back".</p>
 *
 * <p>A video clip carries the speaker's own voice. Silently zeroing it is about the worst
 * thing this editor can do to a recording, so it gets its own checks.</p>
 */
public class ClipVolumeTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }
    static boolean near(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    static Clip clip() {
        // Uri is stored, never dereferenced, so null is safe here (same assumption
        // run-envelope.sh's header records for AudioClip).
        Clip c = new Clip((android.net.Uri) null, 10_000L);
        c.setOutPointMs(10_000);
        return c;
    }

    public static void main(String[] args) {
        // ── 1. No envelope: volumeAt is simply the flat level ────────────────────────
        Clip c = clip();
        c.setVolumeLevel(1.0f);
        check(near(c.volumeAt(0), 1.0f), "no keyframes -> flat level 1.0");
        c.setVolumeLevel(0.5f);
        check(near(c.volumeAt(5_000), 0.5f), "no keyframes -> flat level follows the slider");

        // ── 2. THE SHADOWING BUG. One keyframe at full gain must stay at full gain ───
        // With shadowed fields the stored volume read 0, so this returned 0 and the clip
        // was silent forever. This single check is the one that would have caught it.
        Clip k = clip();
        k.setVolumeLevel(1.0f);
        k.addOrUpdateVolumeKeyframe(0, 1.0f);
        check(near(k.volumeAt(0), 1.0f),
                "a keyframe at full gain plays at full gain (not silence)");
        check(k.getVolumeKeyframes().get(0).volume > 0f,
                "the stored keyframe's volume field is actually readable (shadowing guard)");
        check(k.getVolumeKeyframes().get(0).timeMs == 0L,
                "the stored keyframe's timeMs field is actually readable (shadowing guard)");

        // ── 3. Raising the slider after keyframing must still raise the sound ────────
        // The device symptom was that nothing the user did could recover the audio.
        Clip r = clip();
        r.setVolumeLevel(0.5f);
        r.addOrUpdateVolumeKeyframe(0, 1.0f);
        float atHalf = r.volumeAt(0);
        r.setVolumeLevel(1.0f);
        float atFull = r.volumeAt(0);
        check(atFull > atHalf, "raising the level after keyframing raises the gain ("
                + atHalf + " -> " + atFull + ")");

        // ── 4. Interpolation between two keys ───────────────────────────────────────
        Clip i = clip();
        i.setVolumeLevel(1.0f);
        i.addOrUpdateVolumeKeyframe(0, 0f);
        i.addOrUpdateVolumeKeyframe(1_000, 1f);
        float mid = i.volumeAt(500);
        check(mid > 0.3f && mid < 0.7f, "ramps between two keys (mid = " + mid + ")");

        // ── 5. NEGATIVE CONTROLS — these must FAIL on a broken build ────────────────
        // A zero keyframe really is silence: check 2 above is meaningful only if a genuine
        // zero still reads as zero, otherwise it would pass on a build that ignores
        // keyframes entirely.
        Clip z = clip();
        z.setVolumeLevel(1.0f);
        z.addOrUpdateVolumeKeyframe(0, 0f);
        check(near(z.volumeAt(0), 0f),
                "NEGCTRL: a genuine zero keyframe IS silent (so check 2 is not vacuous)");
        // And the level must genuinely scale, or check 3 would pass on a stuck build.
        Clip s = clip();
        s.setVolumeLevel(0f);
        check(near(s.volumeAt(0), 0f), "NEGCTRL: level 0 with no keyframes is silent");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
