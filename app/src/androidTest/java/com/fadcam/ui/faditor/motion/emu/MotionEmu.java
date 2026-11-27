package com.fadcam.ui.faditor.motion.emu;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Shared pieces of the STU-7 motion blur emulator tests (.github/workflows/stu7-motion-emulator.yml).
 * Pictures land in the app's own files ({@code files/motion-emu/}); the workflow copies them out with
 * {@code run-as} and uploads them as the run's artifact.
 */
final class MotionEmu {
    static final String TAG = "MotionEmu";

    private MotionEmu() {}

    static Instrumentation ins() { return InstrumentationRegistry.getInstrumentation(); }

    /** The run's planted fault ({@code am instrument -e fault <name>}), or "" for an honest run. */
    static String fault() {
        Bundle a = InstrumentationRegistry.getArguments();
        String f = a == null ? null : a.getString("fault");
        return f == null ? "" : f;
    }

    static File dir(String sub) {
        File d = new File(new File(ins().getTargetContext().getFilesDir(), "motion-emu"), sub);
        d.mkdirs();
        return d;
    }

    static void save(Bitmap b, File f) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        Log.i(TAG, "saved " + f.getName());
    }

    static void log(String line) { Log.i(TAG, line); }
}
