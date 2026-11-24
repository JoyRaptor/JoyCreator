import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.Transcript;
import com.fadcam.ui.faditor.undo.CaptionTrackState;
import com.fadcam.ui.faditor.undo.TextBoxLook;

/**
 * The 11-24 undo sweep (owner rule: one press, one step): the caption-track and transcript-version
 * edits that had no undo step now record a CaptionTrackState before and after. These checks use the
 * REAL Clip and AudioClip models and prove that putting a state back undoes each edit exactly, and
 * that it keeps the SAME binding objects (other undo steps, such as caption size, hold them).
 *
 * Usage:  bash tools/jvm-harness/run-undosteps.sh
 */
public class UndoStepsTest {

    static int pass, fail;

    public static void main(String[] a) {
        deleteAVersionComesBack();
        trackEditsComeBack();
        audioClipToo();
        nothingChangedIsSame();
        textBoxLookComesBack();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static Clip clipWithTwoVersions() {
        Clip c = new Clip(null, 60_000);
        c.addTranscript(new NamedTranscript("t1", "Whisper", "whisper", new Transcript()));
        c.addTranscript(new NamedTranscript("t2", "Imported 1", "import", new Transcript()));
        c.addCaptionBinding(new Clip.CaptionBinding("t1", "pop", true, 0.5f, 0.8f, 0.06f, "Track 1"));
        c.addCaptionBinding(new Clip.CaptionBinding("t2", "pop", true, 0.5f, 0.6f, 0.06f, "Track 2"));
        c.setActiveTranscriptIndex(1);
        return c;
    }

    static void deleteAVersionComesBack() {
        Clip c = clipWithTwoVersions();
        NamedTranscript second = c.getTranscripts().get(1);
        Clip.CaptionBinding track2 = c.getCaptionBindings().get(1);
        CaptionTrackState before = CaptionTrackState.of(c);
        c.removeTranscript(1);                         // also drops Track 2, which used it
        check("deleting a version drops its track (the edit being undone)",
                c.getTranscripts().size() == 1 && c.getCaptionBindings().size() == 1);
        CaptionTrackState after = CaptionTrackState.of(c);
        check("the edit is seen as a change", !after.sameAs(before));
        before.restore();
        check("Undo brings the version back in its place",
                c.getTranscripts().size() == 2 && c.getTranscripts().get(1) == second);
        check("Undo brings its caption track back, the same object",
                c.getCaptionBindings().size() == 2 && c.getCaptionBindings().get(1) == track2);
        check("Undo puts the chosen version back", c.getActiveTranscriptIndex() == 1);
        after.restore();
        check("Redo deletes it again", c.getTranscripts().size() == 1 && c.getCaptionBindings().size() == 1);
    }

    static void trackEditsComeBack() {
        Clip c = clipWithTwoVersions();
        Clip.CaptionBinding track1 = c.getCaptionBindings().get(0);
        CaptionTrackState before = CaptionTrackState.of(c);
        track1.label = "Lyrics";
        track1.enabled = false;
        c.getTranscripts().get(0).label = "Renamed";
        c.addCaptionBinding(new Clip.CaptionBinding("t1", "pop", true, 0.5f, 0.4f, 0.06f, "Track 3"));
        before.restore();
        check("Undo puts a track's name back", "Track 1".equals(track1.label));
        check("Undo shows a hidden track again", track1.enabled);
        check("Undo puts a version's name back", "Whisper".equals(c.getTranscripts().get(0).label));
        check("Undo removes an added track", c.getCaptionBindings().size() == 2);
        check("the binding kept is the very same object", c.getCaptionBindings().get(0) == track1);
        check("the legacy mirror follows binding 0 again", c.isCaptionsEnabled());
    }

    static void audioClipToo() {
        AudioClip ac = new AudioClip(null, 30_000L);
        ac.addTranscript(new NamedTranscript("a1", "Whisper", "whisper", new Transcript()));
        ac.addCaptionBinding(new AudioClip.CaptionBinding("a1", "pop", true, 0.5f, 0.7f, 0.06f, "Track 1"));
        AudioClip.CaptionBinding b = ac.getCaptionBindings().get(0);
        CaptionTrackState before = CaptionTrackState.of(ac);
        b.anchor = 2;
        b.justify = 1;
        ac.removeTranscript(0);
        before.restore();
        check("an audio clip's version comes back", ac.getTranscripts().size() == 1);
        check("an audio clip's track comes back with its layout",
                ac.getCaptionBindings().size() == 1 && ac.getCaptionBindings().get(0) == b
                        && b.anchor == 0 && b.justify == 0);
    }

    static void nothingChangedIsSame() {
        Clip c = clipWithTwoVersions();
        check("a state is the same as itself taken again (no empty Undo step)",
                CaptionTrackState.of(c).sameAs(CaptionTrackState.of(c)));
        CaptionTrackState s = CaptionTrackState.of(c);
        c.getCaptionBindings().get(1).sizeFraction = 0.2f;
        check("any field of any track counts as a change", !CaptionTrackState.of(c).sameAs(s));
    }

    static void textBoxLookComesBack() {
        TextOverlayItem o = new TextOverlayItem("Hello", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        TextBoxLook before = TextBoxLook.of(o);
        check("a text box's look is the same as itself (no empty step)", TextBoxLook.of(o).sameAs(before));
        o.setColorInt(0xFFFF0000);
        o.setFontFamily("serif");
        o.setStrokeColorInt(0xFF000000);
        o.setStrokeWidthPx(25f);
        o.setGlowRadiusPx(40f);
        o.setShadowAngleDeg(90f);
        o.setBackgroundColorInt(0x80000000);
        o.setWrapEm(10f);
        o.setFitWidth(true);
        o.setTextCase(TextOverlayItem.CASE_ALL_CAPS);
        o.setTextAlign("left");
        TextBoxLook after = TextBoxLook.of(o);
        check("a drawer visit that changed the box is seen as a change", !after.sameAs(before));
        before.applyTo(o);
        check("Undo puts the whole-box look back", TextBoxLook.of(o).sameAs(before)
                && o.getColorInt() == 0xFFFFFFFF && "default".equals(o.getFontFamily())
                && o.getStrokeWidthPx() == 0f && !o.fitWidthSetting());
        after.applyTo(o);
        check("Redo puts the new look back", TextBoxLook.of(o).sameAs(after) && o.isFitWidth());
    }

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }
}
