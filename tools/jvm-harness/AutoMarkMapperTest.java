import com.fadcam.ui.faditor.markers.AutoMarkMapper;
import com.fadcam.ui.faditor.markers.AutoMarkMapper.Mark;
import com.fadcam.ui.faditor.markers.AutoMarkMapper.Moment;

import java.util.Arrays;
import java.util.List;

/** FOLD-1 auto-markers: where a Detect moment lands on the timeline. Run: bash tools/jvm-harness/run-automarks.sh */
public class AutoMarkMapperTest {
    static int pass, fail;

    static void eq(String name, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) pass++;
        else { fail++; System.out.println("FAIL  " + name + ": want " + want + " got " + got); }
    }

    static Moment m(long s, long e, String l) { return new Moment(s, e, l); }

    public static void main(String[] a) {
        // plain clip: source 10 s..40 s plays from timeline 5 s at normal speed
        AutoMarkMapper.Result r = AutoMarkMapper.map(Arrays.asList(m(12_000, 13_000, "Person"), m(30_000, 31_000, "Dog")),
                10_000, 40_000, 1f, false, 5_000);
        eq("two marks", 2, r.marks.size());
        eq("first lands at offset + 2 s", 7_000L, r.marks.get(0).timelineMs);
        eq("second lands at offset + 20 s", 25_000L, r.marks.get(1).timelineMs);
        eq("labels kept", "Person", r.marks.get(0).label);

        // outside the trim is counted, not marked
        r = AutoMarkMapper.map(Arrays.asList(m(1_000, 2_000, "Early"), m(50_000, 51_000, "Late"), m(20_000, 21_000, "In")),
                10_000, 40_000, 1f, false, 0);
        eq("only the one inside", 1, r.marks.size());
        eq("two outside counted", 2, r.outside);

        // a moment that began before the trim and is still going is marked at the clip's start
        r = AutoMarkMapper.map(Arrays.asList(m(8_000, 12_000, "Ongoing")), 10_000, 40_000, 1f, false, 3_000);
        eq("began earlier: at the clip start", 3_000L, r.marks.get(0).timelineMs);

        // speed: twice as fast halves the distance from the clip start
        r = AutoMarkMapper.map(Arrays.asList(m(20_000, 21_000, "Fast")), 10_000, 40_000, 2f, false, 0);
        eq("2x speed: 10 s in becomes 5 s", 5_000L, r.marks.get(0).timelineMs);
        r = AutoMarkMapper.map(Arrays.asList(m(20_000, 21_000, "Slow")), 10_000, 40_000, 0.5f, false, 0);
        eq("half speed: 10 s in becomes 20 s", 20_000L, r.marks.get(0).timelineMs);

        // reversed: the clip plays from its out point backwards, so a late moment comes first
        r = AutoMarkMapper.map(Arrays.asList(m(30_000, 31_000, "Late"), m(12_000, 13_000, "Early")), 10_000, 40_000, 1f, true, 0);
        eq("reversed: the late moment is met first (out 40 s - end 31 s)", 9_000L, r.marks.get(0).timelineMs);
        eq("reversed: the early moment comes later (40 - 13)", 27_000L, r.marks.get(1).timelineMs);
        eq("reversed: marks come out in timeline order", "Late", r.marks.get(0).label);

        // marks are always sorted
        r = AutoMarkMapper.map(Arrays.asList(m(35_000, 36_000, "B"), m(11_000, 12_000, "A")), 10_000, 40_000, 1f, false, 0);
        eq("sorted by timeline time", "A", r.marks.get(0).label);

        // the same clip used twice: the caller maps once per use, each with its own start
        AutoMarkMapper.Result u1 = AutoMarkMapper.map(Arrays.asList(m(12_000, 13_000, "P")), 10_000, 20_000, 1f, false, 0);
        AutoMarkMapper.Result u2 = AutoMarkMapper.map(Arrays.asList(m(12_000, 13_000, "P")), 10_000, 20_000, 1f, false, 60_000);
        eq("two uses, two places", "2000/62000", u1.marks.get(0).timelineMs + "/" + u2.marks.get(0).timelineMs);

        // duplicates
        List<Mark> existing = Arrays.asList(new Mark(7_000, "Person"));
        List<Mark> wanted = Arrays.asList(new Mark(7_200, "Person"), new Mark(7_200, "Dog"), new Mark(9_000, "Person"),
                new Mark(9_100, "Person"));
        List<Mark> kept = AutoMarkMapper.withoutDuplicates(wanted, existing, 500);
        eq("near-duplicates dropped (7.2 s Person, 9.1 s Person), the Dog and the 9 s Person kept", 2, kept.size());
        eq("the dog survives", "Dog", kept.get(0).label);
        eq("the first of two near each other survives", 9_000L, kept.get(1).timelineMs);

        // the best N by confidence, kept in time order; confidence, uri and the source moment travel with the mark
        r = AutoMarkMapper.map(Arrays.asList(new Moment(11_000, 12_000, "A", 0.3f, "u"), new Moment(15_000, 16_000, "B", 0.9f, "u"),
                new Moment(20_000, 21_000, "C", 0.6f, "u")), 10_000, 40_000, 1f, false, 0);
        eq("confidence carried", 0.9f, r.marks.get(1).confidence);
        eq("source moment carried", 15_000L, r.marks.get(1).sourceMs);
        eq("uri carried", "u", r.marks.get(1).sourceUri);
        List<Mark> top = AutoMarkMapper.best(r.marks, 2);
        eq("best 2 are B and C", "B,C", top.get(0).label + "," + top.get(1).label);
        eq("fewer than N: all of them", 3, AutoMarkMapper.best(r.marks, 5).size());

        // a zero or bad speed does not divide by zero
        r = AutoMarkMapper.map(Arrays.asList(m(20_000, 21_000, "X")), 10_000, 40_000, 0f, false, 0);
        eq("bad speed treated as 1x", 10_000L, r.marks.get(0).timelineMs);

        System.out.println("AutoMarkMapperTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
