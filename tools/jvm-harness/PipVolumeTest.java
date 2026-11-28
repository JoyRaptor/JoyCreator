import com.fadcam.ui.faditor.model.PipVolume;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STU-F6 (11-27, Note 9): with a picture-in-picture selected, the Volume panel said 100% while the PiP exported silent.
 * Two faults: the Volume controls acted on the MAIN clip under it (getSelectedClip), and a PiP added without its audio
 * included was shown as playing. The panel must show, and change, what the export does.
 */
public class PipVolumeTest {
    static int failed = 0, ran = 0;
    static void check(boolean ok, String what) {
        ran++;
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failed++;
    }

    /** The body of method [name] in [src] (from its opening brace to the matching close), or null. */
    static String body(String src, String name) {
        int at = src.indexOf(" " + name + "(");
        while (at >= 0) {
            int lineStart = src.lastIndexOf('\n', at) + 1;
            String head = src.substring(lineStart, at);
            if (head.contains("private") || head.contains("public")) break;
            at = src.indexOf(" " + name + "(", at + 1);
        }
        if (at < 0) return null;
        int open = src.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        check(PipVolume.shownMuted(true, false, false), "a PiP as added (audio not included) shows as muted");
        check(!PipVolume.shownMuted(true, false, true), "a PiP with its audio included and not muted shows its level");
        check(PipVolume.shownMuted(true, true, true), "a muted PiP shows as muted");
        check(!PipVolume.shownMuted(false, false, false), "a main-track clip ignores the PiP switch");
        check(PipVolume.includedAfter(true, false, false), "unmuting a PiP from the Volume controls includes its audio");
        check(PipVolume.includedAfter(true, true, true), "muting a PiP keeps its audio included (one tap brings it back)");
        check(!PipVolume.includedAfter(false, false, false), "a main-track clip's switch is left alone");

        // The Volume controls act on the selected object, not the main clip under a selected PiP.
        String src = Files.readString(Path.of("app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java"));
        String[] volumeFns = {"showVolumeControlSheet", "volumeDragBaseline", "selectedVolumeState", "applyDraggedVolume",
                "refreshVolumeDrawer", "refreshVolumeDrawerChrome"};
        for (String fn : volumeFns) {
            String b = body(src, fn);
            if (b == null) { check(false, fn + " exists"); continue; }
            check(!b.contains("getSelectedClip()"), fn + " acts on the selected object, not the main clip under it");
        }
        System.out.println("PipVolume: " + (ran - failed) + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
