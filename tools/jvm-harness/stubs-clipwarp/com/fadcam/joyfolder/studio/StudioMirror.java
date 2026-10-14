package com.fadcam.joyfolder.studio;

import android.content.Context;

import java.io.File;

/**
 * Harness stand-in for the Joy Creator folder mirror. ProjectStorage calls it after a save, before
 * a load and on delete; the real one reaches Android's folder picker APIs, which these off-device
 * tests don't stub and don't test. Same signatures, no folder.
 */
public final class StudioMirror {

    private StudioMirror() {}

    public static void onProjectSaved(Context context, String projectId) {}

    public static void onProjectDeleted(Context context, String projectId) {}

    public static boolean healMovedFiles(Context context, String projectId, File projectJson) {
        return false;
    }
}
