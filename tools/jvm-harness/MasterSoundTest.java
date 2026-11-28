import com.fadcam.ui.faditor.export.MasterSoundProbe;

/**
 * STU-C3 (11-27, Note 9): an audio-only export of a project whose main track holds a video with no sound track of its
 * own failed ("The asset loader has no audio or video track to output"): the clip was handed to Media3 as a sound-only
 * item with nothing in it. Such a clip must lay silence of its length, as a picture or a muted clip does.
 */
public class MasterSoundTest {
    static int failed = 0;
    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        check(MasterSoundProbe.laysSilence(false, false, 0L), "a video with no sound track lays silence");
        check(!MasterSoundProbe.laysSilence(false, false, 12_000L), "a video with sound lays its sound");
        check(!MasterSoundProbe.laysSilence(false, false, Long.MAX_VALUE), "a source that could not be read keeps its sound (as before)");
        check(MasterSoundProbe.laysSilence(true, false, 0L), "a picture lays silence");
        check(MasterSoundProbe.laysSilence(false, true, 12_000L), "a muted clip lays silence");
        System.out.println("MasterSound: " + (5 - failed) + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
