package com.fadcam.ui.faditor.export;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import com.fadcam.FLog;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GL errors are sticky: one left pending by any step of the export's effect chain is reported
 * by the NEXT {@code GlUtil.checkGlError()} anywhere on the thread, which then fails the whole
 * export in someone else's name (Note 9, 2026-09-24: "invalid operation" thrown inside
 * BlendModeGlEffect's uniform bind, 4 s into the export, from an error an earlier step left).
 * Each custom step drains at its entry, so a stale error neither kills the export nor gets
 * blamed on the wrong step, and the first occurrence per site is logged so its origin can be
 * found and fixed. A step's OWN errors still fail loudly through its own checkGlError.
 */
final class GlErrors {

    private GlErrors() { }

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** Clear pending GL errors; logs the first per site. Returns the first error, or 0. */
    static int drain(@NonNull String where) {
        int first = GLES20.GL_NO_ERROR;
        for (int i = 0; i < 16; i++) {
            int e = GLES20.glGetError();
            if (e == GLES20.GL_NO_ERROR) break;
            if (first == GLES20.GL_NO_ERROR) first = e;
        }
        if (first != GLES20.GL_NO_ERROR && LOGGED.add(where + first)) {
            FLog.w("GlErrors", "GL error 0x" + Integer.toHexString(first) + " " + where
                    + " (drained; logged once per site)");
        }
        return first;
    }
}
