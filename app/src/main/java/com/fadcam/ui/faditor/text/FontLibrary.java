package com.fadcam.ui.faditor.text;

import android.content.Context;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * The built-in fonts, {key, label}. ONE list, read by the text font picker AND the
     * caption font picker — it used to be typed out inside the text picker's popup, which
     * is how captions ended up with a different, six-font list of their own.
     *
     * <p>Keys are what projects SAVE, so an entry here must never be renamed — only added.
     */
    public static final String[][] TEXT_FONTS = {
            {"popular", "Popular"}, {"popular_italic", "Popular Italic"},
            {"designer", "Designer"}, {"trendy", "Trendy"}, {"light", "Light"},
            {"sans_light", "Airy"}, {"sans_thin", "Thin"}, {"sans_medium", "Medium"},
            {"sans_black", "Heavy"}, {"condensed", "Condensed"},
            {"condensed_bold", "Condensed Bold"}, {"classy", "Classy"},
            {"classy_italic", "Classy Italic"}, {"serif_bold", "Bold Serif"},
            {"serif_italic", "Serif Italic"}, {"country", "Country"},
            {"dramatic", "Dramatic"}, {"mono", "Mono"}, {"mono_bold", "Mono Bold"},
            {"casual", "Casual"}, {"cursive", "Cursive"},
    };

    /** True if {@code key} is one of {@link #TEXT_FONTS}. */
    public static boolean isTextFontKey(@androidx.annotation.Nullable String key) {
        if (key == null) return false;
        for (String[] f : TEXT_FONTS) if (f[0].equals(key)) return true;
        return false;
    }

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

    // ---------------------------------------------------------------------------------------
    // TYPEFACE CACHE
    //
    // Typeface.createFromFile() PARSES THE FONT FILE. It does no caching of its own, and it
    // returns a fresh Typeface identity every call - which also defeats the framework's own
    // styled-variant cache in Typeface.create(base, style), because that cache is keyed on the
    // base identity.
    //
    // Before this cache, CaptionStyle.typeface() called createFromFile() on EVERY measurement.
    // The caption fitter measures every word at up to 18 candidate sizes, so one uncached fit of
    // a 3,400-word transcript issued on the order of 10^5 font parses ON THE MAIN THREAD, inside
    // onDraw. On JoyRaptor's Note 20 that produced 19 "Waited 10001ms for MotionEvent" ANRs in a
    // single day, a 2.1 GB native heap (every parse allocates a native font buffer that only a
    // GC returns), and constant SkStrikeCache purges - the ANR traces bottom out in
    // SkStrikeCache::internalPurge destroying SkTypeface_Stream, which is precisely the
    // stream-backed typeface createFromFile builds.
    //
    // Fonts are immutable once imported: FontLibrary owns the only directory they are written
    // to, so the cache is invalidated explicitly by the importer rather than stat-ed per lookup
    // (a stat per lookup would still be ~10^5 syscalls inside that same loop).
    // ---------------------------------------------------------------------------------------

    private static final Map<String, Typeface> TYPEFACES = new ConcurrentHashMap<>();
    /** Paths that failed to load, remembered so a stale key does not re-parse on every draw. */
    private static final Set<String> UNLOADABLE = ConcurrentHashMap.newKeySet();

    /**
     * The {@link Typeface} for an absolute font path, parsed at most once per process.
     *
     * <p>Returns {@code null} if the file is missing or unreadable - a font deleted since it was
     * chosen - so callers fall back to a built-in rather than crashing a render. That failure is
     * remembered too, for the same reason the successes are.</p>
     *
     * <p>Safe from any thread: preview draws on the UI thread while export rasterises on its
     * own, and both resolve the same fonts.</p>
     */
    @Nullable
    public static Typeface typefaceForFile(@NonNull String absPath) {
        Typeface hit = TYPEFACES.get(absPath);
        if (hit != null) return hit;
        if (UNLOADABLE.contains(absPath)) return null;
        Typeface loaded = null;
        try {
            loaded = Typeface.createFromFile(absPath);
        } catch (Exception e) {
            FLog.w(TAG, "Could not load font " + absPath, e);
        }
        if (loaded == null) {
            UNLOADABLE.add(absPath);
            return null;
        }
        // putIfAbsent, not put: two threads may race here, and the winner's identity must be the
        // one everyone shares or the framework's styled-variant cache misses again.
        Typeface won = TYPEFACES.putIfAbsent(absPath, loaded);
        return won != null ? won : loaded;
    }

    /**
     * Convenience for the stored {@code "file:<abs path>"} key form. Returns {@code null} for a
     * key that is not a file font, so callers keep their own built-in switch.
     */
    @Nullable
    public static Typeface typefaceForKey(@Nullable String fontKey) {
        if (fontKey == null || !fontKey.startsWith("file:")) return null;
        return typefaceForFile(fontKey.substring(5));
    }

    /**
     * Forget every cached font. Call after importing or deleting a font file so a re-imported
     * name picks up the new bytes instead of the old parse.
     */
    public static void invalidateTypefaces() {
        TYPEFACES.clear();
        UNLOADABLE.clear();
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
