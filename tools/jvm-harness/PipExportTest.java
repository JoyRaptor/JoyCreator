import com.fadcam.ui.faditor.export.DecodedFrameCursor;
import com.fadcam.ui.faditor.model.PipPlacement;
import com.fadcam.ui.faditor.model.TimeMap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Picture-in-picture export: size and shape, frame pacing, and the effect clock
 * (Codex round 2: R2-FX-01, R2-FX-02, R2-FX-04).
 *
 * <p>The GPU drawing itself needs a phone (the STATE row has that check). What is checked here is
 * the arithmetic it now runs on: where the four corners land, which decoded frame answers which
 * output frame, and which clock the effect keys are read on. Each part carries a NEGCTRL: the old
 * rule, put through the same check, must fail it, or the check proves nothing.</p>
 */
public class PipExportTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }
    static boolean close(float a, float b) { return Math.abs(a - b) < 0.6f; }

    /** The drawn rect matches the editor's: size, shape, centre. */
    static boolean matchesPreview(float[] wh, float boxW, float boxH, float srcW, float srcH, float scale) {
        float fit = Math.min(boxW / srcW, boxH / srcH);          // OverlayVideoPreviewView's own rule
        return close(wh[0], srcW * fit * scale) && close(wh[1], srcH * fit * scale);
    }

    public static void main(String[] a) throws Exception {
        // ── R2-FX-01: size and shape ─────────────────────────────────────────
        // A portrait phone clip (1080x1920 as shown) over a 1920x1080 project at scale 0.35.
        float[] p = PipPlacement.fitSize(1920, 1080, 1080, 1920, 0.35f);
        check(close(p[1], 1080 * 0.35f), "portrait over landscape: 35% of the frame's height tall (" + p[1] + ")");
        check(Math.abs(p[0] / p[1] - 1080f / 1920f) < 1e-3, "portrait over landscape: keeps its 9:16 shape");
        check(matchesPreview(p, 1920, 1080, 1080, 1920, 0.35f), "portrait over landscape: same as the editor");
        float[] l = PipPlacement.fitSize(1080, 1920, 1920, 1080, 0.5f);
        check(close(l[0], 540) && close(l[1], 303.75f), "landscape over portrait at 0.5: half the width, 16:9");
        float[] sq = PipPlacement.fitSize(1920, 1080, 1000, 1000, 1f);
        check(close(sq[0], 1080) && close(sq[1], 1080), "square at scale 1 fills the height, stays square");
        float[] same = PipPlacement.fitSize(1920, 1080, 1280, 720, 0.35f);
        check(close(same[0], 672) && close(same[1], 378), "matching shape at 0.35: 35% of the width (not 10.5%)");
        float[] unknown = PipPlacement.fitSize(1920, 1080, 0, 0, 0.5f);
        check(close(unknown[0], 960) && close(unknown[1], 540), "unknown source size: the frame's shape, never nothing");
        // NEGCTRL: the old GPU placeholder (30% of the frame on each side, whatever the clip).
        float[] old = {1920 * 0.3f * 0.35f, 1080 * 0.3f * 0.35f};
        check(!matchesPreview(old, 1920, 1080, 1080, 1920, 0.35f), "NEGCTRL: the old 30% placeholder fails this check");

        // Corners: unrotated, the picture's TOP-left corner (quad +y edge, -x) lands top-left of the
        // rect, in a y-down store, and the rect is centred where the editor puts it.
        int fw = 1920, fh = 1080;
        float cx = 0.72f * fw, cy = 0.22f * fh;                  // the editor's default spot
        float[] m = PipPlacement.quadMatrix(fw, fh, cx, cy, p[0], p[1], 0f);
        float[] tl = PipPlacement.cornerPixel(m, fw, fh, -1f, 1f);
        float[] br = PipPlacement.cornerPixel(m, fw, fh, 1f, -1f);
        check(close(tl[0], cx - p[0] / 2) && close(tl[1], cy - p[1] / 2), "picture's top-left lands top-left of the rect");
        check(close(br[0], cx + p[0] / 2) && close(br[1], cy + p[1] / 2), "picture's bottom-right lands bottom-right");
        // NEGCTRL: the old matrix put the centre at NDC y = 1 - 2cy/H, the mirror of where it belongs.
        // Read back the way the compositor reads the store: row 0 is the top.
        float oldCentreRowFromTop = ((1f - 2f * cy / fh) + 1f) * 0.5f * fh;
        check(!close(oldCentreRowFromTop, cy), "NEGCTRL: the old centre comes back mirrored (" + oldCentreRowFromTop + " vs " + cy + ")");

        // Rotation: clockwise on screen like Canvas.rotate, and a rotated PiP keeps its shape.
        float[] r90 = PipPlacement.quadMatrix(fw, fh, cx, cy, p[0], p[1], 90f);
        float[] rtl = PipPlacement.cornerPixel(r90, fw, fh, -1f, 1f);
        float[] rtr = PipPlacement.cornerPixel(r90, fw, fh, 1f, 1f);
        float[] rbl = PipPlacement.cornerPixel(r90, fw, fh, -1f, -1f);
        // Canvas.rotate(90) about the centre takes the top-left corner (-w/2, -h/2) to (+h/2, -w/2).
        check(close(rtl[0], cx + p[1] / 2) && close(rtl[1], cy - p[0] / 2), "90 degrees turns clockwise, as Canvas.rotate");
        float top = (float) Math.hypot(rtr[0] - rtl[0], rtr[1] - rtl[1]);
        float side = (float) Math.hypot(rbl[0] - rtl[0], rbl[1] - rtl[1]);
        check(close(top, p[0]) && close(side, p[1]), "rotated 90: the edges keep their lengths (no skew)");
        float[] r30 = PipPlacement.quadMatrix(fw, fh, cx, cy, p[0], p[1], 30f);
        float[] a0 = PipPlacement.cornerPixel(r30, fw, fh, -1f, 1f), a1 = PipPlacement.cornerPixel(r30, fw, fh, 1f, 1f),
                a2 = PipPlacement.cornerPixel(r30, fw, fh, -1f, -1f);
        float dot = (a1[0] - a0[0]) * (a2[0] - a0[0]) + (a1[1] - a0[1]) * (a2[1] - a0[1]);
        check(Math.abs(dot) < 1f, "rotated 30 on a wide frame: corners stay square (dot " + dot + ")");
        // NEGCTRL: rotating in NDC after a non-uniform scale (the old order) skews on a 16:9 frame.
        double t = Math.toRadians(30), sx = p[0] / fw, sy = p[1] / fh;   // NDC half-extents
        double ux = Math.cos(t) * sx * fw / 2, uy = Math.sin(t) * sy * fh / 2;      // +x edge, pixels
        double vx = -Math.sin(t) * sx * fw / 2, vy = Math.cos(t) * sy * fh / 2;     // +y edge, pixels
        check(Math.abs(ux * vx + uy * vy) > 1, "NEGCTRL: the old NDC rotation is not square on 16:9");

        // ── R2-FX-02: a 24 fps clip in a 30 fps export ────────────────────────
        int[] used = new int[1];
        long[] shown = pace(24, 30, 30, true, used);
        check(used[0] <= 25, "24 into 30 fps: one second of output uses about 24 source frames (" + used[0] + "), not 30");
        boolean firstAtOrAfter = true;
        for (int i = 0; i < 30; i++) {
            long target = i * 1_000_000L / 30;
            long want = (long) Math.ceil(target * 24 / 1_000_000.0) * 1_000_000L / 24;
            firstAtOrAfter &= Math.abs(shown[i] - want) <= 1;
        }
        check(firstAtOrAfter, "each output frame shows the first source frame at or after its time");
        check(maxLead(shown, 30) < 1_000_000L / 24, "never more than one source frame ahead of its time (" + maxLead(shown, 30) + " us)");
        int[] slowUsed = new int[1];
        pace(30, 30, 30, true, slowUsed);
        check(slowUsed[0] == 30, "30 into 30 fps: one frame each, unchanged");
        // NEGCTRL: pulling a frame on every output frame (the old GPU reader) runs ahead.
        int[] oldUsed = new int[1];
        long[] oldShown = pace(24, 30, 30, false, oldUsed);
        check(oldUsed[0] == 30 && maxLead(oldShown, 30) > 1_000_000L / 24,
                "NEGCTRL: the old reader uses 30 frames and runs " + maxLead(oldShown, 30) + " us ahead");
        check(!DecodedFrameCursor.holds(Long.MIN_VALUE, 0L), "nothing held answers nothing");

        // ── R2-FX-04: the effect clock ───────────────────────────────────────
        check(TimeMap.chunkToEditorMs(2_500_000L, 12_000L) == 14_500L, "piece clock 2.5 s + piece start 12 s = editor 14.5 s");
        check(TimeMap.chunkToEditorMs(2_500_999L, 0L) == 2_500L, "first piece: the presentation time itself");
        for (String f : new String[]{"BlendModeGlEffect", "ImageBlendGlEffect"}) {
            String src = new String(Files.readAllBytes(Paths.get(
                    "app/src/main/java/com/fadcam/ui/faditor/export/" + f + ".java")), StandardCharsets.UTF_8);
            int at = -1, n = 0;
            boolean ok = true;
            while ((at = src.indexOf(".resolveAt(", at + 1)) >= 0) {
                n++;
                String before = src.substring(Math.max(0, at - 400), at + 160);
                ok &= before.contains("chunkToEditorMs(");
            }
            check(n > 0 && ok, f + ": its effect keys are read on the editor's clock (" + n + " resolve)");
        }

        if (fails > 0) { System.out.println(fails + " FAILED"); System.exit(1); }
        System.out.println("PipExportTest: all passed");
    }

    /**
     * A forward decoder of a {@code srcFps} clip read at {@code outFps} for {@code frames} output
     * frames, the way both PiP readers loop: keep the held frame if {@code reuse} and it answers,
     * else pull frames until one is at or past the target. Returns the pts shown per output frame.
     */
    static long maxLead(long[] shown, int outFps) {
        long lead = 0;
        for (int i = 0; i < shown.length; i++) lead = Math.max(lead, shown[i] - i * 1_000_000L / outFps);
        return lead;
    }

    static long[] pace(int srcFps, int outFps, int frames, boolean reuse, int[] used) {
        long[] shown = new long[frames];
        long held = Long.MIN_VALUE;
        int next = 0;
        for (int i = 0; i < frames; i++) {
            long target = i * 1_000_000L / outFps;
            if (!(reuse && DecodedFrameCursor.holds(held, target))) {
                do { held = next++ * 1_000_000L / srcFps; used[0]++; } while (held < target);
            }
            shown[i] = held;
        }
        return shown;
    }
}
