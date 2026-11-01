import android.net.Uri;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.SpineLookup;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * The clip under a timeline time, with the real Clip and Timeline: the Codex S3 picture (a 2 s image
 * looped out to 10 s, then a video) must stay on the image at 8 s, as the timeline draws it.
 */
public class SpineLookupTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static Clip clip(String name, long durationMs) {
        return new Clip(Uri.parse("file:///" + name), durationMs);
    }

    static Clip looped(String name, long baseMs, long afterMs, int mode) {
        Clip c = clip(name, baseMs);
        c.setLoopMode(mode);
        c.setLoopAfterMs(afterMs);
        return c;
    }

    public static void main(String[] args) {
        // Image to video: inside the extended image, not in the video the trimmed sum would pick.
        Timeline tl = new Timeline();
        Clip image = looped("image.png", 2_000, 8_000, Clip.LOOP_MODE_STILL);
        image.setImageClip(true);
        Clip video = clip("video.mp4", 5_000);
        tl.addClip(image);
        tl.addClip(video);
        SpineLookup at8 = SpineLookup.at(tl, 8_000);
        check(at8.clip == image && at8.index == 0, "8 s is still the extended image");
        check(at8.visualMs == 8_000, "local time runs past the base length");
        SpineLookup at10 = SpineLookup.at(tl, 10_000);
        check(at10.clip == video && at10.startMs == 10_000 && at10.visualMs == 0, "the video starts at 10 s");
        check(SpineLookup.at(tl, 9_999).clip == image, "one ms before it is still the image");
        check(SpineLookup.at(tl, 14_999).clip == video, "the last ms is the video");
        check(SpineLookup.at(tl, 99_000).clip == video && SpineLookup.at(tl, 99_000).visualMs == 4_999,
                "past the end parks on the last frame of the last clip");
        check(SpineLookup.at(tl, -5).clip == image && SpineLookup.at(tl, -5).visualMs == 0, "before the start is 0");

        // Video to looped video: the repeat maps to the right place in the source.
        Timeline tl2 = new Timeline();
        Clip first = clip("a.mp4", 3_000);
        Clip loop = looped("b.mp4", 2_000, 4_000, Clip.LOOP_MODE_NORMAL);
        tl2.addClip(first);
        tl2.addClip(loop);
        SpineLookup rep = SpineLookup.at(tl2, 3_000 + 2_500);
        check(rep.clip == loop && rep.startMs == 3_000, "the second clip starts after the first");
        check(rep.visualMs == 2_500 && rep.sourceOffsetMs() == 500, "half a second into the first repeat");

        // Speed: a timeline second at 2x is two source seconds.
        Timeline tl3 = new Timeline();
        Clip fast = clip("c.mp4", 8_000);
        fast.setSpeedMultiplier(2f);
        Clip after = clip("d.mp4", 1_000);
        tl3.addClip(fast);
        tl3.addClip(after);
        check(SpineLookup.spanMs(fast) == 4_000, "2x halves the span");
        check(SpineLookup.at(tl3, 3_000).clip == fast && SpineLookup.at(tl3, 3_000).sourceOffsetMs() == 6_000,
                "3 s on the timeline is 6 s into the source");
        check(SpineLookup.at(tl3, 4_000).clip == after, "the next clip starts at the sped span");

        // A trim is counted from the In point.
        Timeline tl4 = new Timeline();
        Clip trimmed = clip("e.mp4", 10_000);
        trimmed.setInPointMs(4_000);
        trimmed.setOutPointMs(6_000);
        tl4.addClip(trimmed);
        check(SpineLookup.at(tl4, 500).sourceOffsetMs() == 500, "the offset is from the In point");

        check(SpineLookup.at(new Timeline(), 500) == null, "an empty timeline has no clip");

        // Every position agrees with the project's total, the sum the timeline and export use.
        long total = tl.getTotalDurationMs();
        check(SpineLookup.at(tl, total - 1).clip == video && SpineLookup.at(tl, total - 1).visualMs == 4_999,
                "the lookup's last ms is the project's last ms");

        System.out.println("SpineLookupTest: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
