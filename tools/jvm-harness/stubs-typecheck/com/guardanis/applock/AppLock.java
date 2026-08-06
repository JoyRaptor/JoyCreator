package com.guardanis.applock;

import android.content.Context;

/**
 * COMPILE-ONLY STUB for the vendored AppLockLibrary, used exclusively by
 * {@code tools/jvm-harness/typecheck.sh}.
 *
 * <p>The library lives at {@code app/libs/AppLockLibrary} as a Gradle subproject whose sources
 * reference their OWN generated {@code R}, so they cannot be fed to a bare javac the way the rest
 * of the app can. Three unrelated screens (Records / Security / Trash) import it, and without
 * this stub those imports fail and take the whole type-check down with them.</p>
 *
 * <p><b>Consequence, stated so nobody is surprised by it later:</b> the type-check cannot catch a
 * misuse of THIS API. It is a fixed third-party surface nothing in the editor touches, which is
 * why the trade is worth making — but if applock usage is ever changed, that change is only
 * proven by a real Gradle build.</p>
 */
public class AppLock {
    public static boolean isEnrolled(Context context) { return false; }
    public static AppLock getInstance(Context context) { return null; }
    public void invalidateEnrollments() {}
}
