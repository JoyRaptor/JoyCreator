package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One-time migration for the removal of FadCam's cloud streaming mode (job S19).
 *
 * Older installs may have "streaming_mode" = 1 (cloud) in the "FadCamCloudPrefs" file. Cloud mode
 * is gone, and the value used to mean "do not start the local HTTP server", which would silently
 * kill LAN Remote. This rewrites it to 0 (local). The pref file and key names stay unchanged.
 */
public final class StreamingModeMigration {

    private static final String PREFS_NAME = "FadCamCloudPrefs";
    private static final String KEY_STREAMING_MODE = "streaming_mode";

    private StreamingModeMigration() {}

    /** Safe to call repeatedly: does nothing once the value is no longer 1. */
    public static void run(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getInt(KEY_STREAMING_MODE, 0) == 1) {
                prefs.edit().putInt(KEY_STREAMING_MODE, 0).commit();
            }
        } catch (Exception ignored) {
            // A wrongly-typed value cannot be 1 as an int, so there is nothing to migrate.
        }
    }
}
