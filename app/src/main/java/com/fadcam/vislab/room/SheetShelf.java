package com.fadcam.vislab.room;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.avatar.AvatarLibrary;
import com.fadcam.ui.faditor.project.AssetResolver;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Every Sprite Lab sheet on the phone, for a Sprite layer to play: the ones saved in Studio
 * projects and in the character library. Each comes out in its saved form with its pictures as
 * full links (a project keeps them relative to its own folder), so a look that carries one keeps
 * working outside that project. Reads project files: call it off the main thread.
 */
final class SheetShelf {

    private static final String TAG = "VisSheetShelf";

    /** One sheet: its name, where it lives, and the sheet itself as a Sprite layer keeps it. */
    static final class Entry {
        @NonNull final String name;
        @NonNull final String where;
        @NonNull final JsonObject sheet;

        Entry(@NonNull String name, @NonNull String where, @NonNull JsonObject sheet) {
            this.name = name;
            this.where = where;
            this.sheet = sheet;
        }
    }

    private SheetShelf() {}

    @NonNull
    static List<Entry> list(@NonNull Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            ProjectStorage storage = new ProjectStorage(c);
            for (ProjectStorage.ProjectSummary p : storage.listProjects()) {
                try {
                    File dir = storage.projectDir(p.id);
                    File json = new File(dir, "project.json");
                    if (!json.isFile()) continue;
                    String text = read(json);
                    if (!text.contains("\"spriteSheets\"")) continue;
                    JsonElement arr = JsonParser.parseString(text).getAsJsonObject().get("spriteSheets");
                    if (arr == null || !arr.isJsonArray()) continue;
                    for (JsonElement el : arr.getAsJsonArray()) {
                        SpriteSheet s = SpriteSheet.fromJson(el.getAsJsonObject());
                        out.add(new Entry(s.getName(), p.name, linked(s, dir).toJson()));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "skipping the sheets of " + p.id, e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "projects unreadable", e);
        }
        try {
            for (AvatarLibrary.Entry e : AvatarLibrary.list(c)) {
                for (SpriteSheet s : e.sheets) out.add(new Entry(s.getName(), e.rig.getName(), linked(s, e.dir).toJson()));
            }
        } catch (Exception e) {
            Log.w(TAG, "character library unreadable", e);
        }
        return out;
    }

    /** The sheet's animations (Sprite Lab presets), id to name, in the sheet's order. */
    @NonNull
    static LinkedHashMap<String, String> clips(@Nullable JsonObject sheet) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (sheet == null) return out;
        try {
            SpriteSheet s = SpriteSheet.fromJson(sheet);
            for (SpriteSheet.Preset p : s.getPresets()) if (!p.frames.isEmpty()) out.put(p.id, p.name);
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    /** The sheet's name as a Sprite layer shows it. */
    @NonNull
    static String nameOf(@Nullable JsonObject sheet) {
        if (sheet == null) return "Joy record";
        JsonElement n = sheet.get("name");
        return n != null && n.isJsonPrimitive() ? n.getAsString() : "Sprite sheet";
    }

    /** Its picture links made full (relative ones are relative to {@code dir}). */
    @NonNull
    private static SpriteSheet linked(@NonNull SpriteSheet s, @NonNull File dir) {
        if (!s.getSheetUri().isEmpty()) s.setSheetUri(AssetResolver.resolve(dir, s.getSheetUri()).toString());
        List<String> frames = s.getFrameUris();
        for (int i = 0; i < frames.size(); i++) frames.set(i, AssetResolver.resolve(dir, frames.get(i)).toString());
        return s;
    }

    @NonNull
    private static String read(@NonNull File f) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
