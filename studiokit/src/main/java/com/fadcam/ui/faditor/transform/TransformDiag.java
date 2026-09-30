package com.fadcam.ui.faditor.transform;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * SPEC K — the churn-proof flight recorder for the transform surface.
 *
 * <p>Logcat on JoyRaptor's phone churns through megabytes in minutes (social apps +
 * system chatter), so gesture forensics pulled minutes later finds nothing. This
 * keeps the last ~200 lines of transform decisions (ring taps, flips, folds,
 * commit bakes, walk-aways, refusals) in {@code files/faditor/transform-diag.log},
 * readable any time via {@code run-as} with no time pressure. Discrete gestures
 * only — never per drag frame — so it costs nothing and cannot spam.
 */
public final class TransformDiag {

    private TransformDiag() {}

    private static final Object LOCK = new Object();
    private static final long CAP_BYTES = 256L * 1024L;

    @Nullable private static volatile java.io.File file;

    /** Call once from the editor's {@code onCreate} with an app-private dir. */
    public static void init(@Nullable java.io.File dir) {
        if (dir == null) return;
        try {
            java.io.File d = new java.io.File(dir, "faditor");
            // mkdirs true/false both fine — file creation below is the real test.
            d.mkdirs();
            java.io.File f = new java.io.File(d, "transform-diag.log");
            if (!f.exists()) {
                try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                    w.write("");
                }
            }
            file = f;
        } catch (Exception ignored) {
            file = null;   // diagnostics must never break the editor
        }
    }

    /** Append one line (timestamped). Never throws. */
    public static void log(@NonNull String line) {
        java.io.File f = file;
        if (f == null) return;
        synchronized (LOCK) {
            try {
                if (f.length() > CAP_BYTES) trimLocked(f);
                try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                    w.write(System.currentTimeMillis() + " " + line + "\n");
                }
            } catch (Exception ignored) { }
        }
    }

    /** Keep roughly the second half; crude but bounded and allocation-light. */
    private static void trimLocked(@NonNull java.io.File f) throws Exception {
        byte[] all = new byte[(int) Math.min(f.length(), 1L << 20)];
        int n;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            n = in.read(all);
        }
        if (n <= 0) return;
        int keepFrom = n / 2;
        while (keepFrom < n && all[keepFrom] != '\n') keepFrom++;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) {
            out.write(all, keepFrom, n - keepFrom);
        }
    }
}
