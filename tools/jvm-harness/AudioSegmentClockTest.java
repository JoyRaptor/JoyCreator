import android.net.Uri;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.transcript.*;
import java.io.File;
import java.util.Collections;

public class AudioSegmentClockTest {
    private static void eq(long value, long wanted) {
        if (value != wanted) throw new AssertionError(value + " != " + wanted);
    }
    public static void main(String[] args) {
        AudioClip c = new AudioClip(Uri.parse("file:///original.wav"), 4000000);
        c.setInPointMs(3600000); c.setOutPointMs(3610000); c.setOffsetMs(4500);
        c.addOrUpdateVolumeKeyframe(700,.5f);
        c.setRemovedSpans(Collections.singletonList(new long[]{3601000,3601800}));
        Transcript words = new Transcript();
        words.words.add(new TranscriptWord("earlier",3000,3500));
        words.words.add(new TranscriptWord("word",3600500,3600900,true,true));
        c.addTranscript(new NamedTranscript("words","test",words));
        AudioClip.BakeState original=c.originalBakeState();
        AudioClip.BakeState local=c.segmentBakeState(new File("segment.m4a"),3600000,3610000);
        c.switchBakeState(local);
        eq(c.getInPointMs(),0); eq(c.getOutPointMs(),10000); eq(c.getSourceDurationMs(),10000);
        eq(c.getOffsetMs(),4500); eq(c.getVolumeKeyframes().get(0).timeMs,700);
        eq(c.getRemovedSpans().get(0)[0],1000); eq(c.getTranscript().words.get(1).startMs,500);
        eq(words.words.get(1).startMs,3600500);
        AudioClip split = new AudioClip(c);
        split.setInPointMs(6000);
        split.switchBakeState(split.originalBakeState());
        eq(split.getInPointMs(),3606000); eq(split.getOutPointMs(),3610000);
        eq(split.getTranscript().words.get(0).startMs,3000); eq(c.getInPointMs(),0);
        c.switchBakeState(original);
        eq(c.getSourceDurationMs(),4000000); eq(c.getRemovedSpans().get(0)[0],3601000);
        c.switchBakeState(local); c.setInPointMs(2000);
        AudioClip.BakeState twice=c.segmentBakeState(new File("fix2.m4a"),2000,10000);
        c.switchBakeState(twice); eq(c.getInPointMs(),0); eq(c.getBakedOriginMs(),3602000);
        c.switchBakeState(c.originalBakeState()); eq(c.getInPointMs(),3602000);
        if (!c.getTranscript().words.get(1).forceLineBreakAfter || !c.getTranscript().words.get(1).struck)
            throw new AssertionError("word edits lost on revert");
        System.out.println("PASS: segment, split, nested bake, undo/redo, original duration, words, cuts, envelope");
    }
}
