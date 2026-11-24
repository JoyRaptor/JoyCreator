import com.fadcam.ui.faditor.tools.SnapAuthority;

import java.util.Arrays;
import java.util.Random;

/**
 * STU-5, one snapping authority: every snap a surface used to compute for itself now comes from
 * SnapAuthority, and NOTHING A PERSON RELIES ON MOVES. Each block holds a frozen copy of the code a
 * surface ran before (named legacy*), and compares it with the authority on many random inputs,
 * including the ties and the exact edge of the reach where rules differ. Plus hand-worked pins.
 *
 * <p>The one deliberate change, pinned at the end: a text overlay's drop-time snap now takes the
 * nearest edge (it took the last edge within reach, a bug; the sprite layer's twin was right).</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-snap.sh}</p>
 */
public class SnapAuthorityTest {

    static int passed, failed;

    static void check(String what, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL " + what); }
    }

    // ── frozen copies of the code each surface ran before 2026-11-24 ─────────

    /** BeatDetector.snap, as it was. */
    static long legacyBeat(long[] b, long t, long tol) {
        if (b.length == 0 || tol <= 0) return t;
        int lo = 0, hi = b.length - 1, best = 0;
        long bestDist = Long.MAX_VALUE;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long d = Math.abs(b[mid] - t);
            if (d < bestDist) { bestDist = d; best = mid; }
            if (b[mid] < t) lo = mid + 1; else hi = mid - 1;
        }
        return bestDist <= tol ? b[best] : t;
    }

    /** OnsetDetector.snap, as it was. */
    static long legacyOnset(long[] o, long ms, long tol) {
        if (o.length == 0 || tol <= 0) return ms;
        int lo = 0, hi = o.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (o[mid] < ms) lo = mid + 1; else hi = mid;
        }
        long best = o[lo];
        long bestDist = Math.abs(best - ms);
        if (lo > 0) {
            long d = Math.abs(o[lo - 1] - ms);
            if (d < bestDist) { best = o[lo - 1]; bestDist = d; }
        }
        return bestDist <= tol ? best : ms;
    }

    /** FaditorEditorActivity.updateWordSyncMagnet's own "is any onset within reach" search, as it was. */
    static boolean legacyMagnet(long[] onsets, long desired, long tol) {
        if (tol <= 0 || onsets.length == 0) return false;
        int lo = 0, hi = onsets.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = onsets[mid];
            if (v < desired - tol) lo = mid + 1;
            else if (v > desired + tol) hi = mid - 1;
            else return true;
        }
        return false;
    }

    /** TransformOverlayView.snapAxis, as it was. */
    static float[] legacyAxis(float lo, float hi, float fLo, float fHi, float thr) {
        float[][] pairs = {{(lo + hi) / 2f, (fLo + fHi) / 2f}, {lo, fLo}, {hi, fHi}};
        float[] best = null;
        float bestD = thr;
        for (float[] p : pairs) {
            float d = Math.abs(p[1] - p[0]);
            if (d <= bestD) { bestD = d; best = new float[]{p[1] - p[0], p[1]}; }
        }
        return best;
    }

    /** TransformOverlayView.snapAxisTo, as it was. */
    static float[] legacyAxisTo(float lo, float hi, float oLo, float oHi, float thr) {
        float[] best = legacyAxis(lo, hi, oLo, oHi, thr);
        float bestD = best == null ? thr : Math.abs(best[0]);
        float[][] flush = {{lo, oHi}, {hi, oLo}};
        for (float[] p : flush) {
            float d = Math.abs(p[1] - p[0]);
            if (d <= bestD) { bestD = d; best = new float[]{p[1] - p[0], p[1]}; }
        }
        return best;
    }

    /** TransformOverlayView.snapRotation's arithmetic once its reach is known, as it was. */
    static float legacyRotation(float deg, int step, float reach) {
        if (reach <= 0f) return deg;
        float nearest = Math.round(deg / step) * (float) step;
        return Math.abs(deg - nearest) <= 4f * reach ? nearest : deg;
    }

    /** PreviewHandlesOverlay.snapRotation, as it was (5 degree reach). */
    static float legacyCardinal(float deg) {
        float norm = ((deg % 360f) + 360f) % 360f;
        for (float c : new float[]{0f, 90f, 180f, 270f, 360f}) {
            if (Math.abs(norm - c) < 5f) return deg + (c - norm);
        }
        return deg;
    }

    /** TextOverlayLayer.snapX / SpriteOverlayView.snap: 0.5 first, then the others; strict, ties first. */
    static float legacyCentre(float v, float[] others, float thr) {
        float best = v;
        float bestD = thr;
        float d = Math.abs(v - 0.5f);
        if (d < bestD) { bestD = d; best = 0.5f; }
        for (float o : others) {
            d = Math.abs(v - o);
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    /** SpriteOverlayView.snapTimeMs (and what TextOverlayLayer meant): nearest edge, strict, ties first. */
    static long legacySpriteTime(long t, long[] edges, long reach) {
        long best = t;
        long bestD = reach;
        for (long e : edges) {
            long d = Math.abs(t - e);
            if (d < bestD) { bestD = d; best = e; }
        }
        return best;
    }

    /** TextOverlayLayer.snapTimeMs as it really ran: the best distance was never updated. */
    static long legacyTextTimeBuggy(long t, long[] edges, long reach) {
        long best = t;
        for (long e : edges) {
            long d = Math.abs(t - e);
            if (d < reach) best = e;
        }
        return best;
    }

    /** Clip.opacityKeyframeIndexAt, as it was (80 ms, inclusive, ties first). */
    static int legacyKeyframe(long[] keys, long t, long reach) {
        int best = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < keys.length; i++) {
            long d = Math.abs(keys[i] - t);
            if (d <= reach && d < bestDelta) { bestDelta = d; best = i; }
        }
        return best;
    }

    /** The timeline's edge magnets (butt, align, fade knob) as they ran: best = thr + 1, strictly nearer wins. */
    static long legacyEdges(long t, long[] cands, long thr) {
        long best = Long.MIN_VALUE, bestDist = thr + 1;
        for (long c : cands) {
            long d = Math.abs(t - c);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        return best != Long.MIN_VALUE && bestDist <= thr ? best : Long.MIN_VALUE;
    }

    static long[] sortedRandom(Random r, int n, int span) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = r.nextInt(span);
        Arrays.sort(a);
        return a;
    }

    static boolean same(float[] a, float[] b) {
        if (a == null || b == null) return a == b;
        return a[0] == b[0] && a[1] == b[1];
    }

    public static void main(String[] args) {
        Random r = new Random(20261124L);
        int beatBad = 0, onsetBad = 0, magnetBad = 0, axisBad = 0, flushBad = 0, rotBad = 0,
                cardBad = 0, centreBad = 0, timeBad = 0, keyBad = 0, edgeBad = 0, textFixedDiffers = 0;
        for (int i = 0; i < 40000; i++) {
            // Small spans on purpose: duplicates, exact ties and hits on the reach edge happen often.
            long[] beats = sortedRandom(r, r.nextInt(8), 200);
            long t = r.nextInt(240) - 20;
            long tol = r.nextInt(30) - 2;
            if (SnapAuthority.sortedBisect(beats, t, tol) != legacyBeat(beats, t, tol)) beatBad++;
            if (SnapAuthority.sorted(beats, t, tol) != legacyOnset(beats, t, tol)) onsetBad++;
            if ((SnapAuthority.sortedIndex(beats, t, tol) >= 0) != legacyMagnet(beats, t, tol)) magnetBad++;

            float lo = r.nextInt(100), hi = lo + r.nextInt(60), fLo = r.nextInt(100), fHi = fLo + r.nextInt(80);
            float thr = r.nextInt(12);
            if (!same(SnapAuthority.axis(lo, hi, fLo, fHi, thr), legacyAxis(lo, hi, fLo, fHi, thr))) axisBad++;
            if (!same(SnapAuthority.axisFlush(lo, hi, fLo, fHi, thr), legacyAxisTo(lo, hi, fLo, fHi, thr))) flushBad++;

            float deg = (r.nextInt(14400) - 7200) / 10f;
            int step = new int[]{5, 15, 45, 90}[r.nextInt(4)];
            float reach = new float[]{0f, 0.5f, 1f, 2f}[r.nextInt(4)];
            if (SnapAuthority.angleStep(deg, step, 4f * reach) != legacyRotation(deg, step, reach)) rotBad++;
            if (SnapAuthority.cardinal(deg, 5f) != legacyCardinal(deg)) cardBad++;

            float v = r.nextInt(1000) / 1000f;
            int no = r.nextInt(5);
            float[] others = new float[no];
            for (int k = 0; k < no; k++) others[k] = r.nextInt(1000) / 1000f;
            float[] cands = new float[no + 1];
            cands[0] = 0.5f;
            System.arraycopy(others, 0, cands, 1, no);
            if (SnapAuthority.snap(v, cands, cands.length, 0.045f, false) != legacyCentre(v, others, 0.045f)) centreBad++;

            long[] edges = new long[r.nextInt(6)];
            for (int k = 0; k < edges.length; k++) edges[k] = r.nextInt(2000);
            long dt = r.nextInt(2200);
            long got = SnapAuthority.snap(dt, edges, edges.length, 250L, false);
            if (got != legacySpriteTime(dt, edges, 250L)) timeBad++;
            if (got != legacyTextTimeBuggy(dt, edges, 250L)) textFixedDiffers++;

            long thrE = r.nextInt(120);
            int hitE = SnapAuthority.nearestIndex(dt, edges, edges.length, thrE, true);
            if ((hitE < 0 ? Long.MIN_VALUE : edges[hitE]) != legacyEdges(dt, edges, thrE)) edgeBad++;

            long[] keys = new long[r.nextInt(5)];
            for (int k = 0; k < keys.length; k++) keys[k] = r.nextInt(500);
            long kt = r.nextInt(600);
            if (SnapAuthority.nearestIndex(kt, keys, keys.length, 80L, true) != legacyKeyframe(keys, kt, 80L)) keyBad++;
        }
        check("beats: the beat snap lands where it always did (" + beatBad + " of 40000 differ)", beatBad == 0);
        check("word onsets: the word snap lands where it always did (" + onsetBad + " differ)", onsetBad == 0);
        check("word sync magnet lights exactly when it used to (" + magnetBad + " differ)", magnetBad == 0);
        check("canvas: a box against the frame snaps as before (" + axisBad + " differ)", axisBad == 0);
        check("canvas: a box against another box (flush too) snaps as before (" + flushBad + " differ)", flushBad == 0);
        check("rotation steps snap as before at every strength (" + rotBad + " differ)", rotBad == 0);
        check("rotation cardinals snap as before (" + cardBad + " differ)", cardBad == 0);
        check("overlay and sprite centres snap as before (" + centreBad + " differ)", centreBad == 0);
        check("sprite drop times snap as before (" + timeBad + " differ)", timeBad == 0);
        check("opacity keyframes are found as before (" + keyBad + " differ)", keyBad == 0);
        check("timeline edge magnets (butt, align, fade) pick as before (" + edgeBad + " differ)", edgeBad == 0);
        check("the random set does exercise the text-time fix (" + textFixedDiffers + " cases now nearest)",
                textFixedDiffers > 0);

        // ── hand-worked pins ──
        long[] b = {1000, 2000, 3000};
        check("pin: 1950 with 60 ms reach lands on the 2000 beat", SnapAuthority.sortedBisect(b, 1950, 60) == 2000);
        check("pin: 1900 with 60 ms reach stays (a miss returns the input)", SnapAuthority.sortedBisect(b, 1900, 60) == 1900);
        check("pin: an exact reach counts for onsets", SnapAuthority.sorted(b, 1940, 60) == 2000);
        check("pin: an onset tie (1500 between 1000 and 2000) goes to the later", SnapAuthority.sorted(b, 1500, 500) == 2000);
        check("pin: nothing within a zero reach", SnapAuthority.sorted(b, 2000, 0) == 2000
                && SnapAuthority.sortedIndex(b, 2000, 0) == -1);
        float[] ax = SnapAuthority.axis(10, 50, 0, 100, 3);
        check("pin: a box 10 px from every frame line, with a 3 px reach, does not snap", ax == null);
        ax = SnapAuthority.axis(48, 52, 0, 100, 3);
        check("pin: a box centred 0 px off the frame centre snaps to it (guide at 50)", ax != null && ax[0] == 0f && ax[1] == 50f);
        float[] fl = SnapAuthority.axisFlush(101, 141, 0, 100, 2);
        check("pin: a box 1 px right of another sits flush against it", fl != null && fl[0] == -1f && fl[1] == 100f);
        check("pin: 47 degrees snaps to 45 on a 15-degree step at Normal", SnapAuthority.angleStep(47f, 15, 4f) == 45f);
        check("pin: 50 degrees stays at Normal (5 away)", SnapAuthority.angleStep(50f, 15, 4f) == 50f);
        check("pin: a small twist below level lands on level, never on minus a full turn",
                SnapAuthority.cardinal(-3f, 5f) == 0f && SnapAuthority.cardinal(357f, 5f) == 360f);
        check("pin: a centre 0.02 off the middle snaps to 0.5", SnapAuthority.snap(0.52f, new float[]{0.5f}, 1, 0.045f, false) == 0.5f);
        check("pin: exactly the reach does not count for a strict snap", SnapAuthority.nearestIndex(0L, new long[]{250L}, 1, 250L, false) == -1);
        check("pin: exactly the reach counts for an inclusive snap", SnapAuthority.nearestIndex(0L, new long[]{80L}, 1, 80L, true) == 0);
        check("pin: a tie goes to the first listed", SnapAuthority.nearestIndex(100L, new long[]{80L, 120L}, 2, 80L, true) == 0);

        // ── the one deliberate change ──
        long[] edgesT = {1000L, 1100L};   // dropped at 1080: 1100 is 20 away, 1000 is 80 away
        check("text drop time: the nearest edge now wins (1100), as on sprites",
                SnapAuthority.snap(1080L, new long[]{1100L, 1000L}, 2, 250L, false) == 1100L
                        && SnapAuthority.snap(1080L, edgesT, 2, 250L, false) == 1100L);
        check("text drop time: the old code took the last edge within reach instead (1000 here)",
                legacyTextTimeBuggy(1080L, new long[]{1100L, 1000L}, 250L) == 1000L);

        // ── every surface asks the authority (a private copy creeping back in fails here) ──
        String[][] sites = {
                {"app/src/main/java/com/fadcam/ui/faditor/waveform/BeatDetector.java", "SnapAuthority.sortedBisect("},
                {"app/src/main/java/com/fadcam/ui/faditor/waveform/OnsetDetector.java", "SnapAuthority.sorted("},
                {"app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java", "SnapAuthority.sortedIndex("},
                {"studiokit/src/main/java/com/fadcam/ui/faditor/transform/TransformOverlayView.java", "SnapAuthority.angleStep("},
                {"studiokit/src/main/java/com/fadcam/ui/faditor/transform/TransformOverlayView.java", "SnapAuthority.axisFlush("},
                {"app/src/main/java/com/fadcam/ui/faditor/overlay/PreviewHandlesOverlay.java", "SnapAuthority.cardinal("},
                {"app/src/main/java/com/fadcam/ui/faditor/overlay/TextOverlayLayer.java", "SnapAuthority.snap("},
                {"app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayView.java", "SnapAuthority.snap("},
                {"app/src/main/java/com/fadcam/ui/faditor/model/Clip.java", "SnapAuthority.nearestIndex("},
                {"app/src/main/java/com/fadcam/ui/faditor/layers/LayerGestureController.java", "SnapAuthority.nearestIndex("},
        };
        for (String[] site : sites) {
            String src;
            try {
                src = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(site[0])),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                src = "";
            }
            check("routes through the authority: " + site[0].substring(site[0].lastIndexOf('/') + 1) + " " + site[1],
                    src.contains(site[1]));
        }

        System.out.println("SnapAuthorityTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
