package com.fadcam.ui.faditor.util;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;

/**
 * Durable home for derived-data caches that are expensive to rebuild.
 *
 * <p>The waveform caches (legacy bars, HD tiers, quad-band tape) lived under
 * {@code getCacheDir()}, but the OS trims cache dirs under storage pressure — on a
 * device sitting at ~3% free that happens constantly, so every app update / trim event
 * forced a minutes-long audio re-decode of long sources before the bars and tape came
 * back (JoyRaptor, 2026-07-18). These caches are tiny (KB-to-low-MB, content-keyed,
 * format-version-gated) and safe to keep in {@code getFilesDir()}, which the OS never
 * clears. Bulky regenerable data (remuxed copies, filmstrip bitmaps, export temp) stays
 * in the cache dir on purpose — it's the data that SHOULD yield to storage pressure.</p>
 */
public final class DurableCache {

    private DurableCache() {
    }

    /**
     * Returns {@code getFilesDir()/name}, creating it if needed, and migrates any files
     * still sitting in the pre-2026-07-18 {@code getCacheDir()/name} location (renameTo —
     * same filesystem, so the move is free; on collision the durable copy wins).
     */
    @NonNull
    public static File dir(@NonNull Context context, @NonNull String name) {
        File dir = new File(context.getFilesDir(), name);
        if (!dir.exists()) dir.mkdirs();
        File old = new File(context.getCacheDir(), name);
        File[] stale = old.listFiles();
        if (stale != null) {
            for (File f : stale) {
                File dst = new File(dir, f.getName());
                if (dst.exists() || !f.renameTo(dst)) f.delete();
            }
            old.delete();
        }
        return dir;
    }
}
