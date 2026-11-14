package android.os;

import java.io.File;

/** Harness stub: FontLibrary's one-time move from the old public folder (no such folder in a test). */
public final class Environment {
    public static final String DIRECTORY_PICTURES = "Pictures";
    public static final String DIRECTORY_DOWNLOADS = "Download";
    public static final String DIRECTORY_MOVIES = "Movies";

    public static File getExternalStoragePublicDirectory(String type) {
        return new File(System.getProperty("java.io.tmpdir"), "stub-public/" + type);
    }

    private Environment() {}
}
