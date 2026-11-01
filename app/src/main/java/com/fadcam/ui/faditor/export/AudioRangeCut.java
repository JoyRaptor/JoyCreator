package com.fadcam.ui.faditor.export;

import java.util.Locale;

/**
 * An audio-only range export is the project's finished sound, cut. The sound pass already holds the
 * mix with every clip's speed, trim, transition and envelope applied, on the composition's clock, so
 * the only sums left are where the range falls on that clock and the cut itself. Pure, so the
 * harness can run it.
 */
final class AudioRangeCut {
    private AudioRangeCut() {}

    /**
     * Composition time of editor time {@code t}: linear inside the part holding it. Past the last
     * part (a song running on after the picture) the sound carries on at one to one.
     */
    static long compOf(long t, long[] compBases, long[] compDurs, long[] editorBases, long[] editorDurs) {
        int last = compBases.length - 1;
        for (int i = 0; i <= last; i++) {
            if (t < editorBases[i] + editorDurs[i] || i == last) {
                long local = Math.max(0L, t - editorBases[i]);
                if (i == last && local > editorDurs[i]) return compBases[i] + compDurs[i] + local - editorDurs[i];
                return compBases[i] + Math.min(local, compDurs[i]);
            }
        }
        return t;
    }

    /** Stream copy into an M4A: no re-encode, the same flags the older extraction step used. */
    static String command(String soundPath, long startMs, long endMs, String outputPath) {
        return String.format(Locale.US, "-ss %.3f -t %.3f ", startMs / 1000.0, (endMs - startMs) / 1000.0)
                + "-i \"" + soundPath + "\" -map 0:a:0 -vn -c:a copy -movflags +faststart"
                + " -f ipod -y \"" + outputPath + "\"";
    }
}
