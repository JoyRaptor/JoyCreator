import com.fadcam.ui.faditor.model.AudioClip;

/**
 * B1.Q — the volume envelope became a MULTIPLIER over volumeLevel, off device.
 *
 * <p>The property under test is JoyRaptor's ruling in listener terms: "a fade from A to B goes
 * A's level to B's level — fades stack". Every check below is something an EAR would notice,
 * plus the one-shot disk migration that keeps yesterday's project sounding identical.</p>
 */
public class AudioClipEnvelopeTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }
    static boolean close(float a, float b) { return Math.abs(a - b) < 1e-4f; }

    /** sourceUri is only ever STORED by these paths (android.jar's Uri is a stub), so null. */
    private static AudioClip clip(float level) {
        AudioClip ac = new AudioClip(null, 10_000L);
        ac.setVolumeLevel(level);
        return ac;
    }

    public static void main(String[] args) {
        // ── 1. JoyRaptor's exact scenario: fades stack with the slider ────────────────────
        AudioClip b = clip(0.5f);
        b.addOrUpdateVolumeKeyframe(0, 0f);
        b.addOrUpdateVolumeKeyframe(1000, 1f);   // a fade's stored full-scale IS 1f
        check(close(b.gainAtClipMs(500), 0.25f), "50% clip, mid-fade -> 25% (fade stacks)");
        check(close(b.gainAtClipMs(1000), 0.5f), "fade end lands ON the slider level");
        check(close(b.gainAtClipMs(-5), 0.0f),   "fade start is silence at any level");
        // Moving the slider AFTER drawing rescales instead of stranding the peak.
        b.setVolumeLevel(2.0f);
        check(close(b.gainAtClipMs(500), 1.0f),  "slider moved to 200% -> whole fade rescales");

        // ── 2. The B1.Q bug is actually gone: boosted clip keeps its boost ────────────
        AudioClip boosted = clip(1.5f);
        boosted.setFadeInMs(1000);
        check(boosted.getFadeInMs() == 1000,     "fade accessor round-trips");
        check(close(boosted.gainAtClipMs(0), 0.0f), "boosted clip fades FROM silence");
        check(close(boosted.gainAtClipMs(1500), 1.5f),
                "boosted clip fades TO its 150%, not 100% (the reported bug)");

        // ── 3. Migration: a pre-B1.Q project sounds IDENTICAL after load ──────────────
        AudioClip legacy = clip(1.5f);
        legacy.setEnvelopeMultiplier(false);      // as ProjectStorage marks old projects
        legacy.getVolumeKeyframes().add(new AudioClip.VolumeKeyframe(0, 0f));
        legacy.getVolumeKeyframes().add(new AudioClip.VolumeKeyframe(1000, 1.5f)); // ABSOLUTE
        float beforeMid = 0.75f;                  // what the OLD code played at t=500ms
        legacy.migrateLegacyAbsoluteEnvelope();
        check(legacy.isEnvelopeMultiplier(),      "migration flips the semantics flag");
        check(close(legacy.getVolumeKeyframes().get(1).volume, 1.0f),
                "absolute 1.5 over a 150% level -> multiplier 1.0");
        check(close(legacy.gainAtClipMs(500), beforeMid),
                "post-migration playback matches pre-B1.Q byte-for-byte loudness");
        // Double-load must not double-divide (save writes envMul:true; next load skips).
        legacy.migrateLegacyAbsoluteEnvelope();
        check(close(legacy.getVolumeKeyframes().get(1).volume, 1.0f),
                "second migration is a no-op (no compounding quietness)");
        // Re-saving + re-loading via the flag path leaves everything alone.
        legacy.setEnvelopeMultiplier(true);
        float g = legacy.gainAtClipMs(500);
        check(close(g, beforeMid), "envMul:true reload plays the same gain");

        // ── 4. Migration guard: silent base cannot divide ─────────────────────────────
        AudioClip silent = clip(0.0f);
        silent.setEnvelopeMultiplier(false);
        silent.getVolumeKeyframes().add(new AudioClip.VolumeKeyframe(0, 1.0f));
        silent.migrateLegacyAbsoluteEnvelope();
        check(close(silent.getVolumeKeyframes().get(0).volume, 1.0f),
                "volumeLevel==0 skips the divide (any multiplier x 0 is silence anyway)");
        check(close(silent.gainAtClipMs(0), 0.0f), "and it is still silent");

        // ── 5. Hand-drawn envelopes survive the fade setters untouched ────────────────
        AudioClip drawn = clip(1.0f);
        drawn.addOrUpdateVolumeKeyframe(3000, 0.8f);
        drawn.setFadeInMs(0);                     // reports 0 (not a fade) and must no-op
        check(drawn.getFadeInMs() == 0,           "hand-drawn curve does not read as a fade");
        check(drawn.getVolumeKeyframes().size() == 1
                && close(drawn.getVolumeKeyframes().get(0).volume, 0.8f),
                "setFadeInMs(0) left the hand-drawn key alone");

        // ── 6. Copying carries the flag both ways ─────────────────────────────────────
        AudioClip src = clip(2.0f);
        src.addOrUpdateVolumeKeyframe(0, 0f);
        src.addOrUpdateVolumeKeyframe(500, 1f);
        AudioClip copy = new AudioClip(src);
        check(copy.isEnvelopeMultiplier(),        "copy of a modern clip stays modern");
        check(close(copy.gainAtClipMs(250), 1.0f), "copy plays identical final gains");

        // ── G1/G2: shrinking a fade must not strew orphan keyframes ──────────────────
        // JoyRaptor, device 2026-08-23: dragging a fade DOWN left "a whole bunch of round blue
        // keyframe looking things", and dragging it to zero left the line behind. A slider
        // drag calls setFadeInMs once per step, so this simulates the drag rather than
        // jumping straight to the final value — the bug only appears across steps.
        AudioClip gg = clip(1.0f);
        for (long f = 3000; f >= 500; f -= 250) gg.setFadeInMs(f);
        check(gg.getVolumeKeyframes().size() == 2,
                "G1 fade-in shrunk across many steps leaves exactly 2 keyframes (was "
                        + gg.getVolumeKeyframes().size() + ")");
        check(gg.getFadeInMs() == 500, "G1 the surviving fade-in is the last one set");

        AudioClip gg2 = clip(1.0f);
        for (long f = 3000; f >= 500; f -= 250) gg2.setFadeOutMs(f);
        check(gg2.getVolumeKeyframes().size() == 2,
                "G1 fade-out shrunk across many steps leaves exactly 2 keyframes (was "
                        + gg2.getVolumeKeyframes().size() + ")");
        check(gg2.getFadeOutMs() == 500, "G1 the surviving fade-out is the last one set");

        // Dragging all the way to zero must leave NOTHING, even after a messy drag.
        AudioClip gg3 = clip(1.0f);
        for (long f = 3000; f >= 0; f -= 500) gg3.setFadeInMs(f);
        check(gg3.getVolumeKeyframes().isEmpty(),
                "G2 fade-in dragged to zero removes the envelope entirely (was "
                        + gg3.getVolumeKeyframes().size() + ")");
        check(gg3.getFadeInMs() == 0, "G2 fade-in reads back as zero");

        AudioClip gg4 = clip(1.0f);
        for (long f = 3000; f >= 0; f -= 500) gg4.setFadeOutMs(f);
        check(gg4.getVolumeKeyframes().isEmpty(),
                "G2 fade-out dragged to zero removes the envelope entirely (was "
                        + gg4.getVolumeKeyframes().size() + ")");

        // A hand-drawn envelope in the middle must SURVIVE a fade being set and cleared —
        // the union-clear must not become a licence to wipe the user's own keyframes.
        AudioClip gg5 = clip(1.0f);
        gg5.addOrUpdateVolumeKeyframe(5000, 0.4f);
        gg5.setFadeInMs(1000);
        gg5.setFadeInMs(0);
        boolean midSurvived = false;
        for (AudioClip.VolumeKeyframe kf : gg5.getVolumeKeyframes()) {
            if (kf.timeMs == 5000) midSurvived = true;
        }
        check(midSurvived, "G1 a hand-drawn keyframe outside the fade region is not swept away");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
