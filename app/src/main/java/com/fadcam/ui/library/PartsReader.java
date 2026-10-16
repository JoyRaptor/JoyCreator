package com.fadcam.ui.library;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.joyfolder.FileKind;
import com.fadcam.ui.faditor.avatar.AvatarLibrary;
import com.fadcam.ui.faditor.avatar.AvatarRig;
import com.fadcam.ui.faditor.project.AssetResolver;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprite sheets and characters: one row each (owner ruling a: a sheet is a reusable art asset, so it
 * is listed on its own and opens in Sprite Lab, not hidden inside its project).
 *
 * <p>Both live inside a project's saved file, so this reads only the two arrays it needs
 * ({@code spriteSheets}, {@code avatarRigs}) rather than loading whole projects: a project
 * without either is skipped after a cheap text check. Avatar Studio's own library
 * ({@link AvatarLibrary}) adds the characters saved outside any project.</p>
 */
final class PartsReader {

    private static final String TAG = "LibraryParts";
    /** ProjectStorage's file name inside a project folder (also hard-coded by StudioMirror). */
    private static final String PROJECT_JSON = "project.json";

    private PartsReader() {}

    @NonNull
    static List<LibraryItem> read(@NonNull Context c, @NonNull ProjectStorage storage,
                                  @NonNull List<ProjectStorage.ProjectSummary> projects) {
        List<LibraryItem> out = new ArrayList<>();
        for (ProjectStorage.ProjectSummary p : projects) {
            try {
                readProject(storage, p, out);
            } catch (Throwable t) {
                FLog.w(TAG, "skipping sheets of " + p.id, t);
            }
        }
        readAvatarLibrary(c, out);
        return out;
    }

    private static void readProject(@NonNull ProjectStorage storage,
                                    @NonNull ProjectStorage.ProjectSummary p,
                                    @NonNull List<LibraryItem> out) throws Exception {
        File dir = storage.projectDir(p.id);
        File json = new File(dir, PROJECT_JSON);
        if (!json.isFile()) return;
        String text = readText(json);
        if (!text.contains("\"spriteSheets\"") && !text.contains("\"avatarRigs\"")) return;
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();

        Map<String, String> sheetPictures = new HashMap<>();
        for (JsonElement el : array(root, "spriteSheets")) {
            SpriteSheet s = SpriteSheet.fromJson(el.getAsJsonObject());
            String picture = pictureOf(dir, s);
            sheetPictures.put(s.getId(), picture);
            LibraryItem it = new LibraryItem("sheet:" + p.id + "/" + s.getId(), LibraryKind.SPRITE_SHEETS,
                    s.getName(), modifiedOf(picture, p.lastModified), sizeOf(picture), false);
            it.fileKind = FileKind.SPRITE_SHEET;
            it.projectId = p.id;
            it.parentName = p.name;
            it.partId = s.getId();
            it.thumb = picture;
            it.uri = picture;
            out.add(it);
        }
        for (JsonElement el : array(root, "avatarRigs")) {
            AvatarRig r = AvatarRig.fromJson(el.getAsJsonObject());
            LibraryItem it = new LibraryItem("rig:" + p.id + "/" + r.getId(), LibraryKind.AVATARS,
                    r.getName(), p.lastModified, -1, false);
            it.fileKind = FileKind.AVATAR;
            it.projectId = p.id;
            it.parentName = p.name;
            it.partId = r.getId();
            for (AvatarRig.Part part : r.getParts()) {
                String pic = sheetPictures.get(part.sheetId);
                if (pic != null) { it.thumb = pic; break; }
            }
            out.add(it);
        }
    }

    private static void readAvatarLibrary(@NonNull Context c, @NonNull List<LibraryItem> out) {
        try {
            for (AvatarLibrary.Entry e : AvatarLibrary.list(c)) {
                LibraryItem it = new LibraryItem("avatar:" + e.dir.getName(), LibraryKind.AVATARS,
                        e.rig.getName(), e.dir.lastModified(), -1, false);
                it.fileKind = FileKind.AVATAR;
                it.avatarDir = e.dir.getName();
                if (!e.sheets.isEmpty()) it.thumb = e.sheets.get(0).getSheetUri();
                out.add(it);
            }
        } catch (Throwable t) {
            FLog.w(TAG, "avatar library unreadable", t);
        }
    }

    /** The sheet's picture as a link: its image, or a sequence's first frame. */
    @Nullable
    private static String pictureOf(@NonNull File projectDir, @NonNull SpriteSheet s) {
        String stored = s.getSheetUri();
        if (s.isSequence() && !s.getFrameUris().isEmpty()) stored = s.getFrameUris().get(0);
        if (stored == null || stored.isEmpty()) return null;
        return AssetResolver.resolve(projectDir, stored).toString();
    }

    private static long modifiedOf(@Nullable String picture, long fallback) {
        File f = fileOf(picture);
        return f != null && f.isFile() ? f.lastModified() : fallback;
    }

    private static long sizeOf(@Nullable String picture) {
        File f = fileOf(picture);
        return f != null && f.isFile() ? f.length() : -1;
    }

    @Nullable
    private static File fileOf(@Nullable String uri) {
        if (uri == null || !uri.startsWith("file:")) return null;
        String path = Uri.parse(uri).getPath();
        return path == null ? null : new File(path);
    }

    /** java.nio.file is API 26; this app starts at 24. */
    @NonNull
    private static String readText(@NonNull File f) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @NonNull
    private static JsonArray array(@NonNull JsonObject root, @NonNull String key) {
        JsonElement el = root.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }
}
