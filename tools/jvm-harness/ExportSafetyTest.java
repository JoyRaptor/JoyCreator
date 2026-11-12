package com.fadcam.ui.faditor.export;

import java.io.File;
import java.nio.file.Files;

/**
 * Export-safety audit 2026-11-12 (E15, E13), off device: the free-space estimate and refusal rule,
 * and the render-cache key reacting to a changed gallery (content://) source. Lives in the export
 * package because both classes are package-private.
 */
public class ExportSafetyTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) { passed++; } else { failed++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] a) throws Exception {
        // ── E15: the estimate ────────────────────────────────────────────────────────────────
        long mb = 1024L * 1024;
        long tenMinHigh = ExportSpace.estimateBytes(600_000L, "HIGH", false, false);
        // 600 s * (20M + 0.32M) bits / 8 = ~1.52 GB, plus a quarter = ~1.9 GB
        check("10 min high is 1.5-2.5 GB", tenMinHigh > 1500 * mb && tenMinHigh < 2500 * mb);
        long fifteenMinHigh = ExportSpace.estimateBytes(900_000L, "HIGH", false, false);
        long fourteenFiftyNine = ExportSpace.estimateBytes(719_000L, "HIGH", false, false);
        check("long export (parts + join) needs about twice the single-pass size",
                fifteenMinHigh > ExportSpace.estimateBytes(900_000L - 1L, "HIGH", false, false) - 1
                        && fifteenMinHigh > fourteenFiftyNine * 2L * 0.95);
        check("12 min exactly counts as long",
                ExportSpace.estimateBytes(720_000L, "HIGH", false, false)
                        > 1.9 * ExportSpace.estimateBytes(719_999L, "HIGH", false, false));
        check("low is smaller than medium is smaller than high",
                ExportSpace.estimateBytes(300_000L, "LOW", false, false)
                        < ExportSpace.estimateBytes(300_000L, "MEDIUM", false, false)
                        && ExportSpace.estimateBytes(300_000L, "MEDIUM", false, false)
                        < ExportSpace.estimateBytes(300_000L, "HIGH", false, false));
        check("audio only is small and never doubled",
                ExportSpace.estimateBytes(3_600_000L, "HIGH", true, false) < 200 * mb);
        check("one picture is the floor", ExportSpace.estimateBytes(0, "HIGH", false, true)
                == ExportSpace.FLOOR_BYTES);
        check("zero length is the floor", ExportSpace.estimateBytes(0, "HIGH", false, false)
                == ExportSpace.FLOOR_BYTES);
        check("unknown quality counts as high",
                ExportSpace.estimateBytes(60_000L, "???", false, false)
                        == ExportSpace.estimateBytes(60_000L, "HIGH", false, false));
        check("negative length is safe", ExportSpace.estimateBytes(-5, "HIGH", false, false)
                == ExportSpace.FLOOR_BYTES);

        // ── E15: the refusal rule ───────────────────────────────────────────────────────────
        long need = 1000 * mb;
        check("plenty of room is not refused", !ExportSpace.shortOfSpace(need, 5000 * mb));
        check("less than needed is refused", ExportSpace.shortOfSpace(need, 900 * mb));
        check("exactly needed without the reserve is refused", ExportSpace.shortOfSpace(need, need));
        check("needed plus reserve is allowed",
                !ExportSpace.shortOfSpace(need, need + ExportSpace.RESERVE_BYTES));
        check("unknown free space lets the export try", !ExportSpace.shortOfSpace(need, -1L));
        check("missing bytes is the gap incl. reserve",
                ExportSpace.missingBytes(need, 400 * mb) == 600 * mb + ExportSpace.RESERVE_BYTES);
        check("missing bytes is never negative", ExportSpace.missingBytes(need, 9000 * mb) == 0L);

        // ── E13: a changed gallery source changes the key ───────────────────────────────────
        String json = "{\"timeline\":{\"clips\":[{\"id\":\"c1\",\"sourceUri\":\"content://media/external/video/media/42\","
                + "\"inPointMs\":0,\"outPointMs\":5000}],\"textOverlays\":[],\"audioClips\":[]}}";
        final String[] stamp = {"1000/50"};
        RenderCacheKeys.UriStamper st = uri -> uri.endsWith("/42") ? stamp[0] : null;
        String k1 = RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x", st);
        String k1b = RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x", st);
        stamp[0] = "1000/51";   // same size, touched later
        String k2 = RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x", st);
        stamp[0] = "2048/50";   // replaced by a different-size file
        String k3 = RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x", st);
        check("keys exist", k1 != null && k2 != null && k3 != null);
        check("unchanged source gives the same key", k1.equals(k1b));
        check("source touched later changes the key", !k1.equals(k2));
        check("source with another size changes the key", !k1.equals(k3));
        String old1 = RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x");
        String old2 = RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x", null);
        check("no stamper keeps the old behaviour (overload equals null)", old1.equals(old2));
        RenderCacheKeys.UriStamper silent = uri -> null;
        check("a provider that says nothing adds nothing (same as the old key)",
                old1.equals(RenderCacheKeys.partKey(json, 0, 1, 0, 5000, 5000, true, "x", silent)));
        String a1 = RenderCacheKeys.audioKey(json, "x", st);
        stamp[0] = "1000/50";
        String a2 = RenderCacheKeys.audioKey(json, "x", st);
        check("the sound key follows a changed source too", a1 != null && !a1.equals(a2));

        // A second clip's changed source must not invalidate a part that does not show it.
        String two = "{\"timeline\":{\"clips\":[{\"id\":\"c1\",\"sourceUri\":\"content://media/external/video/media/42\","
                + "\"inPointMs\":0,\"outPointMs\":5000},{\"id\":\"c2\",\"sourceUri\":\"content://media/external/video/media/43\","
                + "\"inPointMs\":0,\"outPointMs\":5000}],\"textOverlays\":[],\"audioClips\":[]}}";
        final String[] stamp43 = {"7/7"};
        RenderCacheKeys.UriStamper st2 = uri -> uri.endsWith("/43") ? stamp43[0] : "1/1";
        String p0a = RenderCacheKeys.partKey(two, 0, 1, 0, 5000, 10000, false, "x", st2);
        stamp43[0] = "8/8";
        String p0b = RenderCacheKeys.partKey(two, 0, 1, 0, 5000, 10000, false, "x", st2);
        String p1b = RenderCacheKeys.partKey(two, 1, 2, 5000, 10000, 10000, true, "x", st2);
        stamp43[0] = "7/7";
        String p1a = RenderCacheKeys.partKey(two, 1, 2, 5000, 10000, 10000, true, "x", st2);
        check("part 1 is reused when only clip 2's source changed", p0a.equals(p0b));
        check("part 2 re-renders when its own source changed", !p1a.equals(p1b));

        System.out.println("ExportSafetyTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
