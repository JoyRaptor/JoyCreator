package com.fadcam.ui.faditor.motion;

import com.fadcam.BuildConfig;

/** One disposable project registered by the paused footage proof APK. */
public final class PausedFootageProof {
    private static volatile String fixtureId;

    private PausedFootageProof() {}

    public static void register(String projectId) {
        if (BuildConfig.DEBUG) fixtureId = projectId;
    }

    public static boolean active(String projectId) {
        return BuildConfig.DEBUG && projectId != null && projectId.equals(fixtureId);
    }
}
