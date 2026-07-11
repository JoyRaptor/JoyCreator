package com.fadcam.ui.faditor.avatar;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A4 groundwork (PLAN_AVATAR_STUDIO, JoyRaptor go-ahead 2026-07-11): the cross-project
 * avatar LIBRARY — save a rigged character once, then pick it from the screen
 * recorder or any editor project.
 *
 * <p>A library entry is a SELF-CONTAINED BUNDLE directory, because a rig without
 * its sprite sheets is useless outside the project it was authored in (parts
 * reference {@code sheetId}s that only resolve inside that project). Same
 * philosophy as the project bundle ({@code FaditorProject.PROJECT_URI_SCHEME}):
 * relative paths inside a movable folder, never absolute/content URIs.</p>
 *
 * <pre>
 * files/avatar_library/
 *   &lt;safe-name&gt;-&lt;id8&gt;.avatar/
 *     avatar.json      { libSchemaVersion, rig: AvatarRig.toJson(),
 *                        sheets: [ SpriteSheet.toJson() with sheetUri REWRITTEN
 *                                  to the relative "sheets/&lt;sheetId&gt;.png" ] }
 *     sheets/&lt;sheetId&gt;.png
 * </pre>
 *
 * <p>Consumers: Avatar Studio "save to library" (writes), the recorder's avatar
 * picker and the editor's avatar-item insert path (A4, read + import). Import
 * copies the sheets into the target project's assets and re-registers the
 * {@link SpriteSheet}s — that glue lives with the consumers, not here. Pure
 * file IO here; no locked files touched; tolerant reads per house style.</p>
 */
public final class AvatarLibrary {

    public static final int LIB_SCHEMA_VERSION = 1;
    /** Bundle dir suffix; a dir is an entry iff it ends with this and has avatar.json. */
    public static final String ENTRY_SUFFIX = ".avatar";
    private static final String MANIFEST = "avatar.json";
    private static final String SHEETS_DIR = "sheets";

    private AvatarLibrary() {}

    /** One loaded library entry: the rig plus its bundled, resolvable sheets. */
    public static final class Entry {
        @NonNull public final File dir;
        @NonNull public final AvatarRig rig;
        /** Bundled sheet defs with {@code sheetUri} resolved to absolute file:// URIs. */
        @NonNull public final List<SpriteSheet> sheets;

        Entry(@NonNull File dir, @NonNull AvatarRig rig, @NonNull List<SpriteSheet> sheets) {
            this.dir = dir;
            this.rig = rig;
            this.sheets = sheets;
        }
    }

    /** The shared on-device library root (created on demand). */
    @NonNull
    public static File libraryDir(@NonNull Context ctx) {
        File dir = new File(ctx.getFilesDir(), "avatar_library");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Save a rig + the sheets its parts reference as a self-contained bundle.
     * Sheet bytes are copied from each sheet's CURRENT absolute URI (file:// or
     * content://) into the bundle; the embedded defs get relative sheetUris so
     * the bundle survives moves/reinstalls. Overwrites an existing entry for the
     * same rig id (save-again = update).
     *
     * @return the entry directory, or null on failure (nothing half-written:
     *         a failed save deletes its partial dir).
     */
    @Nullable
    public static File save(@NonNull Context ctx, @NonNull AvatarRig rig,
                            @NonNull List<SpriteSheet> sheets) {
        File dir = new File(libraryDir(ctx), entryDirName(rig));
        // Build in a temp dir, then swap. CRITICAL for standalone-studio re-saves:
        // a loaded library entry's sheet uris point INTO the existing bundle, so
        // deleting it first would destroy the very bytes we're about to copy
        // (data loss on every save-over). Temp-then-rename keeps the old bundle
        // intact until the new one is complete.
        File tmp = new File(libraryDir(ctx), entryDirName(rig) + ".tmp");
        try {
            deleteRecursive(tmp);
            File sheetsDir = new File(tmp, SHEETS_DIR);
            if (!sheetsDir.mkdirs()) return null;

            JsonArray sheetArr = new JsonArray();
            for (SpriteSheet sheet : sheets) {
                String rel = SHEETS_DIR + "/" + sheet.getId() + ".png";
                copyUriToFile(ctx, sheet.getSheetUri(), new File(tmp, rel));
                // Rewrite the embedded def's uri WITHOUT mutating the live sheet.
                JsonObject sj = sheet.toJson();
                sj.addProperty("sheetUri", rel);
                sheetArr.add(sj);
            }

            JsonObject manifest = new JsonObject();
            manifest.addProperty("libSchemaVersion", LIB_SCHEMA_VERSION);
            manifest.add("rig", rig.toJson());
            manifest.add("sheets", sheetArr);
            try (OutputStream os = new FileOutputStream(new File(tmp, MANIFEST))) {
                os.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            }
            deleteRecursive(dir);
            if (!tmp.renameTo(dir)) return null;
            return dir;
        } catch (Exception e) {
            deleteRecursive(tmp);
            return null;
        }
    }

    /** All entries in the library, tolerant of unreadable strays (skipped). */
    @NonNull
    public static List<Entry> list(@NonNull Context ctx) {
        List<Entry> out = new ArrayList<>();
        File[] dirs = libraryDir(ctx).listFiles();
        if (dirs == null) return out;
        for (File d : dirs) {
            if (!d.isDirectory() || !d.getName().endsWith(ENTRY_SUFFIX)) continue;
            Entry e = load(d);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Load one bundle dir; null if missing/corrupt (caller skips, never crashes). */
    @Nullable
    public static Entry load(@NonNull File dir) {
        File mf = new File(dir, MANIFEST);
        if (!mf.isFile()) return null;
        try (InputStream is = new java.io.FileInputStream(mf)) {
            byte[] buf = new byte[(int) mf.length()];
            int n = is.read(buf);
            JsonObject manifest = JsonParser
                    .parseString(new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            AvatarRig rig = AvatarRig.fromJson(manifest.getAsJsonObject("rig"));
            List<SpriteSheet> sheets = new ArrayList<>();
            if (manifest.has("sheets")) {
                JsonArray arr = manifest.getAsJsonArray("sheets");
                for (int i = 0; i < arr.size(); i++) {
                    SpriteSheet s = SpriteSheet.fromJson(arr.get(i).getAsJsonObject());
                    // Relative bundle path → absolute file:// so any consumer can
                    // decode it directly; absolute uris (legacy) pass through.
                    if (!s.getSheetUri().contains("://")) {
                        s.setSheetUri(Uri.fromFile(new File(dir, s.getSheetUri())).toString());
                    }
                    sheets.add(s);
                }
            }
            return new Entry(dir, rig, sheets);
        } catch (Exception e) {
            return null;
        }
    }

    /** Delete an entry (returns false if it wasn't there). */
    public static boolean delete(@NonNull Context ctx, @NonNull String rigId) {
        File[] dirs = libraryDir(ctx).listFiles();
        if (dirs == null) return false;
        for (File d : dirs) {
            if (d.isDirectory() && d.getName().endsWith("-" + shortId(rigId) + ENTRY_SUFFIX)) {
                deleteRecursive(d);
                return true;
            }
        }
        return false;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** "My Dino" + id "3fa4…" → "My_Dino-3fa4a1b2.avatar" (stable per rig id). */
    @NonNull
    private static String entryDirName(@NonNull AvatarRig rig) {
        String safe = rig.getName().trim().replaceAll("[^A-Za-z0-9-_ ]", "")
                .replace(' ', '_');
        if (safe.isEmpty()) safe = "avatar";
        return safe + "-" + shortId(rig.getId()) + ENTRY_SUFFIX;
    }

    @NonNull
    private static String shortId(@NonNull String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static void copyUriToFile(@NonNull Context ctx, @NonNull String uri,
                                      @NonNull File dest) throws Exception {
        try (InputStream is = ctx.getContentResolver().openInputStream(Uri.parse(uri));
             OutputStream os = new FileOutputStream(dest)) {
            if (is == null) throw new IllegalStateException("unresolvable sheet uri " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(@NonNull File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
