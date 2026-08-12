import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.AnchorMath;

import java.util.Map;

/**
 * Anchoring against the REAL model classes (addendum §4A), not stubs — the shift only means
 * anything if Timeline's own prefix-sum and clip list are the ones under test.
 *
 * Run: bash tools/jvm-harness/run-anchor.sh
 */
public class AnchorShiftTest {

    static int fails = 0, checks = 0;

    static void check(boolean c, String n) {
        checks++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void eq(long a, long b, String n) {
        check(a == b, n + "  (got " + a + ", want " + b + ")");
    }

    /** A master clip of exactly {@code durMs} on the timeline.
     *  sourceUri stays NULL — android.jar is compile-only, so Uri.parse throws "Stub!" at
     *  runtime. Nothing under test reads the uri (MatteVisibilityTest does the same). */
    static Clip clip(String unusedUri, long durMs) {
        // Source is deliberately much longer than the trimmed span: setOutPointMs CLAMPS to
        // sourceDurationMs, so a fixture built with source == span silently refuses every
        // lengthening trim and the trim tests then "pass" against a no-op. That is exactly how
        // the first version of this file produced two green trims that proved nothing.
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    static TextOverlayItem overlay(long startMs, long endMs) {
        TextOverlayItem o = new TextOverlayItem("t", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        o.setTimeRange(startMs, endMs);
        return o;
    }

    public static void main(String[] a) {
        // Timeline: three clips, 1000 / 2000 / 500  →  starts 0 / 1000 / 3000
        Timeline tl = new Timeline();
        tl.addClip(clip("a", 1000));
        tl.addClip(clip("b", 2000));
        tl.addClip(clip("c", 500));

        eq(tl.getClipStartMs(0), 0, "start of clip 0");
        eq(tl.getClipStartMs(1), 1000, "start of clip 1");
        eq(tl.getClipStartMs(2), 3000, "start of clip 2");

        // ── Attach: half-open, so a seam-exact start binds to the LATER clip ──────────────
        TextOverlayItem onSeam = overlay(1000, 1500);
        tl.addTextOverlay(onSeam);
        String host = tl.attachOverlayToHostUnderStart(onSeam);
        check(host != null && host.equals(tl.getClip(1).getId()),
                "attach: an overlay starting exactly ON the seam binds to the LATER clip");
        eq(onSeam.getHostOffsetMs(), 0, "attach: its offset into that host is 0");

        TextOverlayItem mid = overlay(1500, 2500);
        tl.addTextOverlay(mid);
        tl.attachOverlayToHostUnderStart(mid);
        eq(mid.getHostOffsetMs(), 500, "attach: offset is measured from the HOST's start, not the timeline's");

        // Past the last clip → unanchored (differs from the visualizer rule ON PURPOSE)
        TextOverlayItem far = overlay(9000, 9500);
        tl.addTextOverlay(far);
        check(tl.attachOverlayToHostUnderStart(far) == null,
                "attach: an overlay past the last clip stays UNANCHORED (policy differs from visualizers)");

        // An unanchored overlay. It USED to be the negative control ("never moves"); since ripple
        // covers length changes it moves too, and the never-moves case is pinned in gap mode at the
        // bottom of this file. An anchor and a ripple answer different questions — the anchor
        // survives REORDERING, the ripple covers LENGTH — so both mechanisms stay.
        TextOverlayItem free = overlay(1500, 2500);
        tl.addTextOverlay(free);   // deliberately NOT attached

        // Open-ended rider anchored to clip 1 — the sentinel case
        TextOverlayItem open = overlay(2000, Long.MAX_VALUE);
        tl.addTextOverlay(open);
        tl.attachOverlayToHostUnderStart(open);

        // ── DELETE CLIP 0: everything after it ripples left by 1000 ───────────────────────
        Map<String, Long> before = tl.captureClipStarts();
        tl.removeClip(0);
        Timeline.AnchorShiftResult res = tl.applyAnchorShift(before);

        eq(tl.getClipStartMs(0), 0, "after delete: clip 1 is now at 0");
        eq(onSeam.getStartMs(), 0, "ripple: the seam overlay moved with its host (1000 -> 0)");
        eq(onSeam.getEndMs(), 500, "ripple: and kept its duration (SHIFT_ONLY)");
        eq(mid.getStartMs(), 500, "ripple: the mid overlay moved with its host (1500 -> 500)");
        eq(mid.getEndMs(), 1500, "ripple: duration preserved");
        eq(free.getStartMs(), 500, "ripple: the UNANCHORED overlay moved too (1500 -> 500)");
        eq(free.getEndMs(), 1500, "ripple: and kept its duration");
        eq(far.getStartMs(), 8000,
                "ripple: an overlay past the last clip travels with the last surviving clip");
        eq(open.getStartMs(), 1000, "ripple: the open-ended rider's START moved");
        eq(open.getEndMs(), Long.MAX_VALUE, "ripple: and its OPEN END survived exactly");
        check(res.movedOverlayIds.size() == 5,
                "result reports all 5 riders that moved — 3 anchored, 2 free (got "
                        + res.movedOverlayIds.size() + ")");
        check(res.orphanedOverlayIds.isEmpty(), "result reports no orphans yet");

        // ── DELETE THE HOST: its riders are ORPHANED, reported, and NOT moved ─────────────
        long midStartBefore = mid.getStartMs();
        Map<String, Long> before2 = tl.captureClipStarts();
        tl.removeClip(0);                       // this is mid/onSeam/open's host
        Timeline.AnchorShiftResult res2 = tl.applyAnchorShift(before2);

        check(res2.orphanedOverlayIds.size() == 3,
                "host deleted: all 3 of its riders are reported ORPHANED (got "
                        + res2.orphanedOverlayIds.size() + ")");
        eq(mid.getStartMs(), midStartBefore,
                "host deleted: an orphan is NOT moved — §4A makes that the user's choice");
        // `far` is unanchored and sits past everything, so it ripples with the surviving clip; the
        // orphans do not. Asserting the exact membership, not just a count, is what keeps this from
        // passing if an orphan ever starts moving too.
        check(res2.movedOverlayIds.size() == 1 && res2.movedOverlayIds.contains(far.getId()),
                "host deleted: the only mover is the UNANCHORED overlay, never an orphan");

        // ── TRIM: lengthening an EARLIER clip shifts a later host's riders right ──────────
        Timeline tl2 = new Timeline();
        tl2.addClip(clip("a", 1000));
        tl2.addClip(clip("b", 2000));
        TextOverlayItem r = overlay(1200, 1800);
        tl2.addTextOverlay(r);
        tl2.attachOverlayToHostUnderStart(r);   // hosts on clip 1
        eq(r.getHostOffsetMs(), 200, "trim setup: offset 200 into clip 1");

        Map<String, Long> before3 = tl2.captureClipStarts();
        tl2.getClip(0).setOutPointMs(1500);     // clip 0 grows by 500
        tl2.applyAnchorShift(before3);
        eq(r.getStartMs(), 1700, "trim: lengthening clip 0 pushed clip 1's rider right by 500");
        eq(r.getEndMs(), 2300, "trim: duration unchanged");

        // And the case JoyRaptor asked for explicitly: a rider on the clip being trimmed must NOT move.
        Timeline tl3 = new Timeline();
        tl3.addClip(clip("a", 1000));
        tl3.addClip(clip("b", 2000));
        TextOverlayItem onFirst = overlay(200, 800);
        tl3.addTextOverlay(onFirst);
        tl3.attachOverlayToHostUnderStart(onFirst);   // hosts on clip 0
        Map<String, Long> before4 = tl3.captureClipStarts();
        tl3.getClip(0).setOutPointMs(1500);
        tl3.applyAnchorShift(before4);
        eq(onFirst.getStartMs(), 200,
                "trim: a rider on the TRIMMED clip itself does not move (clip 0's start never changed)");

        // ── SPLIT: both halves get FRESH ids, so anchors must be re-homed in the model ────
        // Without reanchorAfterSplit every rider on the split clip silently orphans.
        Timeline tl4 = new Timeline();
        tl4.addClip(clip("a", 1000));
        tl4.addClip(clip("b", 4000));            // clip 1 spans 1000..5000
        TextOverlayItem early = overlay(1500, 1800);   // falls in the LEFT half
        TextOverlayItem late  = overlay(4000, 4500);   // falls in the RIGHT half
        tl4.addTextOverlay(early);
        tl4.addTextOverlay(late);
        tl4.attachOverlayToHostUnderStart(early);
        tl4.attachOverlayToHostUnderStart(late);
        String preSplitHost = early.getHostClipId();
        check(preSplitHost != null && preSplitHost.equals(late.getHostClipId()),
                "split setup: both riders share one host");

        // Split clip 1 at source 2000 → halves cover timeline 1000..3000 and 3000..5000.
        int a2 = tl4.splitAt(1, 2000);
        check(a2 >= 0, "split: performed (index " + a2 + ")");
        String idA = tl4.getClip(a2).getId(), idB = tl4.getClip(a2 + 1).getId();
        check(!idA.equals(preSplitHost) && !idB.equals(preSplitHost),
                "split: BOTH halves really do get fresh ids (this is why re-homing is needed)");
        eq(early.getStartMs(), 1500, "split: a rider's TIME is untouched by the split");
        check(idA.equals(early.getHostClipId()),
                "split: the rider before the cut re-homed to the LEFT half");
        check(idB.equals(late.getHostClipId()),
                "split: the rider after the cut re-homed to the RIGHT half");
        eq(early.getHostOffsetMs(), 500, "split: left rider's offset recaptured against its half");
        eq(late.getHostOffsetMs(), 1000, "split: right rider's offset recaptured against ITS half");

        // And the re-homing must survive a subsequent ripple — the real point of doing it.
        Map<String, Long> before5 = tl4.captureClipStarts();
        tl4.removeClip(0);                              // drop the 1000ms clip in front
        Timeline.AnchorShiftResult res5 = tl4.applyAnchorShift(before5);
        check(res5.orphanedOverlayIds.isEmpty(),
                "split then ripple: no orphans — re-homing held");
        eq(early.getStartMs(), 500, "split then ripple: left rider moved with its half");
        eq(late.getStartMs(), 3000, "split then ripple: right rider moved with ITS half");

        // ── GAP MODE: the same delete moves NOTHING ──────────────────────────────────────
        // Gap mode is the industry's per-track sync lock expressed project-wide: the edit happens,
        // and every rider keeps its absolute time. Orphan REPORTING still has to work, because a
        // deleted host dangles an anchor whatever the mode and only the user can settle it.
        Timeline tl5 = new Timeline();
        tl5.setRippleMode("gap");
        tl5.addClip(clip("a", 1000));
        tl5.addClip(clip("b", 2000));
        TextOverlayItem gapFree = overlay(1500, 1800);
        TextOverlayItem gapRider = overlay(1200, 1400);
        tl5.addTextOverlay(gapFree);
        tl5.addTextOverlay(gapRider);
        tl5.attachOverlayToHostUnderStart(gapRider);      // hosts on clip 1
        Map<String, Long> before6 = tl5.captureClipStarts();
        tl5.removeClip(0);
        Timeline.AnchorShiftResult res6 = tl5.applyAnchorShift(before6);
        eq(gapFree.getStartMs(), 1500, "gap mode: the unanchored overlay stays put");
        eq(gapRider.getStartMs(), 1200, "gap mode: even an ANCHORED rider stays put");
        check(res6.movedOverlayIds.isEmpty(), "gap mode: nothing is reported as moved");

        Timeline tl6 = new Timeline();
        tl6.setRippleMode("gap");
        tl6.addClip(clip("a", 1000));
        TextOverlayItem gapOrphan = overlay(200, 400);
        tl6.addTextOverlay(gapOrphan);
        tl6.attachOverlayToHostUnderStart(gapOrphan);
        Map<String, Long> before7 = tl6.captureClipStarts();
        tl6.removeClip(0);
        Timeline.AnchorShiftResult res7 = tl6.applyAnchorShift(before7);
        check(res7.orphanedOverlayIds.size() == 1,
                "gap mode: a deleted host still REPORTS its orphan — the mode governs movement, "
                        + "not bookkeeping");

        System.out.println();
        System.out.println(fails == 0 ? ("ALL PASS — " + checks + " checks")
                                      : (fails + " FAILED of " + checks));
        if (fails != 0) System.exit(1);
    }
}
