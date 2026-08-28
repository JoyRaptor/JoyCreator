package com.fadcam.ui.faditor.text;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where imported fonts live, and how everything finds them.
 *
 * <h3>Why this exists</h3>
 * Font import wrote into {@code Pictures/FadCam/fonts} with a plain
 * {@code FileOutputStream}. On JoyRaptor's Note 20 (Android 13) that fails outright:
 *
 * <pre>
 *   Font import failed
 *   java.io.FileNotFoundException: .../TOMMY Black_PERSONAL USE.otf:
 *       open failed: EPERM (Operation not permitted)
 * </pre>
 *
 * Scoped storage does not let an app write arbitrary files into a public media directory,
 * and a {@code .ttf} is not a media file, so MediaStore would not take it either. Both
 * .ttf and .otf failed for JoyRaptor, every time, with the same EPERM. The feature could
 * never have worked on any device newer than Android 10.
 *
 * <p>Fonts now live in the app's own files directory — the same home
 * {@code ensureBlackSpacerUri()} already uses for the generated black spacer. No
 * permission is involved, the write always succeeds, and {@code Typeface.createFromFile}
 * reads it happily, so the stored {@code "file:<abs path>"} key format is unchanged and
 * existing projects keep working.
 *
 * <h3>The trade-off, stated plainly</h3>
 * App-private files are deleted when the app is uninstalled. An imported font therefore
 * does NOT survive a reinstall, and a project that references one will fall back to its
 * built-in font rather than break. The alternative — a public folder — is what just
 * failed with EPERM. The real answer for portability is to bundle fonts INTO the project
 * when project consolidation lands; this class is where that would read them from.
 */
public final class FontLibrary {

    private static final String TAG = "FontLibrary";

    /** Set once from the Application/Activity, because model code has no Context. */
    @Nullable private static volatile File dir;

    private FontLibrary() {}

    /**
     * Point the library at this install's font directory, creating it if needed. Safe to call
     * repeatedly. Also migrates anything left in the old public location, best-effort.
     */
    public static void init(@NonNull Context ctx) {
        if (dir != null) return;
        File d = new File(ctx.getApplicationContext().getFilesDir(), "fonts");
        if (!d.exists() && !d.mkdirs()) {
            FLog.w(TAG, "Could not create " + d);
        }
        dir = d;
        migrateFromPublicFolder(d);
    }

    /** The font directory, or null before {@link #init}. */
    @Nullable
    public static File dir() {
        return dir;
    }

    /** Destination file for an imported font of this display name. */
    @Nullable
    public static File destFor(@NonNull String displayName) {
        File d = dir;
        if (d == null) return null;
        // Strip any path separators a provider might hand back in the display name.
        String safe = displayName.replace('/', '_').replace('\\', '_');
        return new File(d, safe);
    }

    /** True for a name this library will accept. */
    public static boolean isFontName(@Nullable String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".ttf") || n.endsWith(".otf");
    }

    /**
     * Every imported font as {@code {key, label}}, where key is the {@code "file:<path>"} form
     * the text overlays and caption styles store, and label is the bare filename.
     */
    @NonNull
    public static List<String[]> imported() {
        List<String[]> out = new ArrayList<>();
        File d = dir;
        if (d == null) return out;
        File[] files = d.listFiles((f, name) -> isFontName(name));
        if (files == null) return out;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            out.add(new String[]{"file:" + f.getAbsolutePath(), dot > 0 ? n.substring(0, dot) : n});
        }
        return out;
    }

    /**
     * Best-effort rescue of fonts sitting in the old {@code Pictures/FadCam/fonts}. On a device
     * where the original write failed there is nothing there, which is the normal case and not
     * an error; on an older device that DID manage to write, this keeps them working.
     */
    private static void migrateFromPublicFolder(@NonNull File target) {
        try {
            File old = new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES), "FadCam/fonts");
            File[] files = old.listFiles((f, name) -> isFontName(name));
            if (files == null || files.length == 0) return;
            int moved = 0;
            for (File f : files) {
                File dest = new File(target, f.getName());
                if (dest.exists()) continue;
                try (java.io.InputStream in = new java.io.FileInputStream(f);
                     java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    moved++;
                } catch (Exception e) {
                    FLog.w(TAG, "Could not migrate font " + f.getName(), e);
                }
            }
            if (moved > 0) FLog.i(TAG, "Migrated " + moved + " font(s) from the old public folder");
        } catch (Exception ignored) {
            // No access to the old location at all — expected on modern Android.
        }
    }
}
