import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CutJumps;
import com.fadcam.ui.faditor.model.CutSpanMerge;
import com.fadcam.ui.faditor.model.TimeMap;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CutJumps + TimeMap (job STU-B3, owner ruling 2026-11-15): struck words and cut silences stay on
 * the editor timeline, and playback and export JUMP over them. Pins: where each jump sits on the
 * editor clock (speed, loop extensions, later clips, sound lanes), that no jump lies under a
 * transition, that the file is the timeline less the jumps (the owner's Note 9 case: three
 * struck words and one Clean gap), that the map never goes backwards, and that striking a word
 * and bringing it back returns the timeline to exactly the uncut map.
 *
 * Usage:  bash tools/jvm-harness/run-cutjumps.sh
 */
public class CutJumpsTest {
    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; } else { fail++; System.out.println("FAIL " + name); }
    }

    static void eq(long got, long want, String name) {
        check(got == want, name + "  (got " + got + ", want " + want + ")");
    }

    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    static List<long[]> spans(long[]... s) {
        List<long[]> out = new ArrayList<>();
        for (long[] x : s) out.add(new long[]{x[0], x[1]});
        return out;
    }

    public static void main(String[] args) {
        // ── Nothing struck: no jumps, the map is the old one ──────────────────────────────
        Timeline plain = new Timeline();
        plain.addClip(clip(10_000));
        CutJumps none = CutJumps.of(plain);
        check(none.isEmpty(), "no spans: no jumps");
        eq(TimeMap.of(plain).editorToFileMs(10_000), 10_000, "no spans: file = timeline");
        eq(none.landingMs(4_000), 4_000, "no spans: no landing");

        // ── The Note 9 case: a 10 s talking clip, three struck words and one Clean gap ────
        Timeline talk = new Timeline();
        Clip t = clip(10_000);
        t.setRemovedSpans(spans(new long[]{2_000, 2_800}, new long[]{5_000, 5_500},
                new long[]{7_000, 7_300}, new long[]{8_000, 9_000}));
        talk.addClip(t);
        CutJumps j = CutJumps.of(talk);
        eq(j.count(), 4, "talk: four jumps");
        eq(j.totalMs(), 2_600, "talk: 2.6 s jumped");
        TimeMap m = TimeMap.of(talk);
        eq(m.editorToFileMs(talk.getTotalDurationMs()), 7_400, "talk: the file runs 7.4 s");
        eq(m.editorToFileMs(1_999), 1_999, "talk: before the first word, unmoved");
        eq(m.editorToFileMs(2_400), 2_000, "talk: inside a struck word folds onto the jump");
        eq(m.editorToFileMs(2_800), 2_000, "talk: the jump's end is where the file goes on");
        eq(m.editorToFileMs(6_000), 4_700, "talk: after two words, less both");
        eq(j.landingMs(5_200), 5_500, "talk: playback lands at the word's end");
        eq(j.landingMs(5_500), 5_500, "talk: at the end, no second jump");
        eq(j.indexAt(1_000), -1, "talk: no jump at 1 s");
        eq(j.cutBeforeMs(10_000), 2_600, "talk: cut before the end");
        List<long[]> kept = j.keptRanges(0, 10_000);
        eq(kept.size(), 5, "talk: five kept pieces");
        long keptSum = 0;
        for (long[] k : kept) keptSum += k[1] - k[0];
        eq(keptSum, 7_400, "talk: kept pieces add up to the file");
        eq(kept.get(1)[0], 2_800, "talk: second piece starts after the first word");
        eq(kept.get(4)[1], 10_000, "talk: last piece runs to the end");

        // Bring one word back: the jump goes, the file grows by it; all back = the uncut map.
        t.setRemovedSpans(spans(new long[]{2_000, 2_800}, new long[]{7_000, 7_300},
                new long[]{8_000, 9_000}));
        eq(TimeMap.of(talk).editorToFileMs(10_000), 7_900, "restore one word: +0.5 s");
        t.setRemovedSpans(new ArrayList<>());
        eq(TimeMap.of(talk).editorToFileMs(10_000), 10_000, "restore all: the uncut length");
        check(CutJumps.of(talk).isEmpty(), "restore all: no jumps");

        // ── Trim, speed, later clips ──────────────────────────────────────────────────────
        Timeline sp = new Timeline();
        Clip a = clip(4_000);
        Clip b = clip(10_000);
        b.setInPointMs(1_000);
        b.setSpeedMultiplier(2f);          // 9 s of source plays in 4.5 s
        b.setRemovedSpans(spans(new long[]{3_000, 4_000}, new long[]{0, 1_500}));
        sp.addClip(a);
        sp.addClip(b);
        CutJumps js = CutJumps.of(sp);
        eq(js.count(), 2, "speed: two jumps (the one before the in-point is clamped to it)");
        eq(js.startMs(0), 4_000, "speed: the clamped span starts at the clip");
        eq(js.endMs(0), 4_250, "speed: 0.5 s of source at 2x is 0.25 s");
        eq(js.startMs(1), 5_000, "speed: source 3 s lands at 4 s + 1 s");
        eq(js.endMs(1), 5_500, "speed: and runs 0.5 s");
        eq(TimeMap.of(sp).editorToFileMs(sp.getTotalDurationMs()), 8_500 - 750,
                "speed: file = timeline less the jumps");

        // A span outside the trim is not a jump at all.
        Clip out = clip(5_000);
        out.setRemovedSpans(spans(new long[]{6_000, 7_000}));
        Timeline ot = new Timeline();
        ot.addClip(out);
        check(CutJumps.of(ot).isEmpty(), "trim: a span past the out-point jumps nothing");

        // A sliver from rounding is not worth a seek.
        Clip sl = clip(5_000);
        sl.setRemovedSpans(spans(new long[]{1_000, 1_010}));
        Timeline st = new Timeline();
        st.addClip(sl);
        check(CutJumps.of(st).isEmpty(), "sliver: a 10 ms span is dropped");

        // ── Loop extension: the main pass starts after loop-before ──────────────────────
        Clip lp = clip(4_000);
        lp.setLoopMode(Clip.LOOP_MODE_NORMAL);
        lp.setLoopBeforeMs(1_000);
        lp.setRemovedSpans(spans(new long[]{500, 900}));
        Timeline lt = new Timeline();
        lt.addClip(lp);
        CutJumps jl = CutJumps.of(lt);
        eq(jl.startMs(0), 1_500, "loop: the jump is in the main pass, after loop-before");
        eq(jl.endMs(0), 1_900, "loop: its end");

        // ── Sound lanes: a struck word on an audio clip jumps the program ──────────────────
        Timeline au = new Timeline();
        au.addClip(clip(10_000));
        AudioClip ac = new AudioClip(null, 60_000);
        ac.setInPointMs(500);
        ac.setOutPointMs(8_500);
        ac.setOffsetMs(3_000);
        ac.setRemovedSpans(spans(new long[]{1_000, 1_500}, new long[]{0, 700}));
        au.addAudioClip(ac);
        CutJumps ja = CutJumps.of(au);
        eq(ja.count(), 2, "lane: two jumps");
        eq(ja.startMs(0), 3_000, "lane: clamped to the in-point, at the clip's start");
        eq(ja.endMs(0), 3_200, "lane: 0.2 s of it is inside the trim");
        eq(ja.startMs(1), 3_500, "lane: source 1 s is editor 3.5 s");
        eq(ja.endMs(1), 4_000, "lane: its end");

        // Audio-only project: no film at all.
        Timeline ao = new Timeline();
        AudioClip solo = new AudioClip(null, 60_000);
        solo.setInPointMs(0);
        solo.setOutPointMs(20_000);
        solo.setRemovedSpans(spans(new long[]{5_000, 6_000}));
        ao.addAudioClip(solo);
        CutJumps jo = CutJumps.of(ao);
        eq(jo.count(), 1, "audio-only: one jump");
        eq(jo.landingMs(5_400), 6_000, "audio-only: lands at the word's end");
        eq(TimeMap.of(ao).editorToFileMs(20_000), 19_000, "audio-only: the file is 1 s shorter");

        // A film span and a lane span that overlap merge into one jump.
        Timeline mg = new Timeline();
        Clip mc = clip(10_000);
        mc.setRemovedSpans(spans(new long[]{2_000, 3_000}));
        mg.addClip(mc);
        AudioClip ma = new AudioClip(null, 60_000);
        ma.setInPointMs(0);
        ma.setOutPointMs(10_000);
        ma.setRemovedSpans(spans(new long[]{2_500, 3_500}));
        mg.addAudioClip(ma);
        CutJumps jm = CutJumps.of(mg);
        eq(jm.count(), 1, "merge: one jump");
        eq(jm.totalMs(), 1_500, "merge: 2 s to 3.5 s");

        // ── Never under a transition ──────────────────────────────────────────────────────
        Timeline tr = new Timeline();
        Clip t0 = clip(5_000);
        t0.setRemovedSpans(spans(new long[]{4_000, 5_000}));
        Clip t1 = clip(5_000);
        t1.setRemovedSpans(spans(new long[]{0, 1_000}));
        tr.addClip(t0);
        tr.addClip(t1);
        tr.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE, 600, 0));
        CutJumps jt = CutJumps.of(tr);
        eq(jt.count(), 2, "transition: both jumps kept, trimmed");
        eq(jt.endMs(0), 4_400, "transition: the outgoing tail under the blend is not cut");
        eq(jt.startMs(1), 5_600, "transition: the incoming head under the blend is not cut");
        eq(TimeMap.of(tr).editorToFileMs(10_000), 10_000 - 600 - 400 - 400,
                "transition: file = timeline less the blend and both jumps");

        // ── Random timelines: the map never goes backwards and ends at total - all ────────
        Random rnd = new Random(1115);
        for (int run = 0; run < 300; run++) {
            Timeline rt = new Timeline();
            int n = 1 + rnd.nextInt(4);
            for (int i = 0; i < n; i++) {
                Clip c = clip(1_000 + rnd.nextInt(8_000));
                if (rnd.nextBoolean()) c.setSpeedMultiplier(0.5f + rnd.nextInt(4) * 0.5f);
                List<long[]> s = new ArrayList<>();
                int k = rnd.nextInt(4);
                for (int q = 0; q < k; q++) {
                    long st0 = rnd.nextInt((int) c.getOutPointMs());
                    s.add(new long[]{st0, st0 + 50 + rnd.nextInt(1_500)});
                }
                c.setRemovedSpans(s);
                rt.addClip(c);
            }
            for (int sIdx = 0; sIdx + 1 < n; sIdx++) {
                if (rnd.nextBoolean()) {
                    rt.addTransition(new Transition(Transition.Type.CROSS_DISSOLVE,
                            100 + rnd.nextInt(900), sIdx));
                }
            }
            TimeMap rm = TimeMap.of(rt);
            long total = rt.getTotalDurationMs();
            long prev = -1;
            boolean mono = true;
            for (long e = 0; e <= total; e += 7) {
                long f = rm.editorToFileMs(e);
                if (f < prev) { mono = false; break; }
                prev = f;
            }
            check(mono, "random " + run + ": the map never goes backwards");
            eq(rm.editorToFileMs(total), total - rm.totalSwallowedMs() - rm.cuts().totalMs(),
                    "random " + run + ": file = timeline less blends and jumps");
            CutJumps rj = rm.cuts();
            for (int q = 0; q + 1 < rj.count(); q++) {
                check(rj.endMs(q) < rj.startMs(q + 1), "random " + run + ": jumps sorted, apart");
            }
        }

        // ── Striking a word keeps the Clean cuts (found on the Note 9, 11-15) ─────────────
        // Words (padded): one 930-1750, two 2430-3250, three 3930-4750. A Clean gap 1750-2430.
        List<long[]> words = spans(new long[]{930, 1_750}, new long[]{2_430, 3_250},
                new long[]{3_930, 4_750});
        List<long[]> afterStrike = CutSpanMerge.rebuild(spans(new long[]{1_760, 2_420}),
                spans(new long[]{2_430, 3_250}), words);
        eq(afterStrike.size(), 2, "merge: the Clean gap survives a strike");
        eq(afterStrike.get(0)[0], 1_760, "merge: gap kept as cut");
        eq(afterStrike.get(1)[0], 2_430, "merge: struck word added");
        List<long[]> afterRestore = CutSpanMerge.rebuild(afterStrike, new ArrayList<>(), words);
        eq(afterRestore.size(), 1, "merge: restoring the word drops only the word");
        eq(afterRestore.get(0)[1], 2_420, "merge: the gap is still there");
        // A gap that touches its words by a little is still a gap.
        List<long[]> touching = CutSpanMerge.rebuild(spans(new long[]{1_600, 2_600}),
                new ArrayList<>(), words);
        eq(touching.size(), 1, "merge: a gap overlapping its words' edges is kept");
        // A gap cut next to a struck word merges into one span.
        List<long[]> joined = CutSpanMerge.rebuild(spans(new long[]{1_750, 2_430}),
                spans(new long[]{2_430, 3_250}), words);
        eq(joined.size(), 1, "merge: gap + struck word touching = one span");
        eq(joined.get(0)[1], 3_250, "merge: to the word's end");

        System.out.println("CutJumpsTest: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
