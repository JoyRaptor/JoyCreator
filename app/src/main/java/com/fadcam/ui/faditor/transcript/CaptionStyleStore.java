package com.fadcam.ui.faditor.transcript;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * App-wide store for user-saved caption styles (JoyRaptor 2026-07-16 captions
 * overhaul), so a creator's personal styles are "always at the ready" in every
 * project.
 *
 * <p>Two kinds of entries share the store:</p>
 * <ul>
 *   <li><b>Named styles</b> ({@code custom_<uuid>}) — saved via the floppy
 *       button, listed as chips in the bottom style ticker, exportable as
 *       text.</li>
 *   <li><b>Working drafts</b> ({@code customdraft_<clipId>}) — the live
 *       per-clip style the granular drawer controls edit. Unlisted; they exist
 *       so tweaking one clip never restyles another.</li>
 * </ul>
 *
 * <p>Backed by SharedPreferences. The {@code :export} process spawns fresh per
 * export and reads the prefs file at init, so styles written in the editor are
 * visible there — call {@link #ensureInit} before resolving styles in any
 * process. Projects persist only the style id; a project opened where the id
 * is unknown falls back to the first preset.</p>
 */
public final class CaptionStyleStore {

    private static final String PREFS = "caption_styles";
    private static final String KEY = "caption_custom_styles_v1";

    @Nullable private static SharedPreferences prefs;
    @Nullable private static List<CaptionStyle> cache;
    /** The app's files folder, where the copies of Joy packs' caption styles are listed (PackSupplies). */
    @Nullable private static java.io.File filesDir;
    /** The pack list the styles below were read from (same object = unchanged). */
    @Nullable private static List<com.fadcam.joyfolder.studio.PackSupplies.Item> packSource;
    private static List<CaptionStyle> packStyles = new ArrayList<>();
    private static java.util.Set<String> packGone = new java.util.HashSet<>();

    private CaptionStyleStore() { }

    public static synchronized void ensureInit(@NonNull Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            cache = null;
        }
        if (filesDir == null) filesDir = context.getApplicationContext().getFilesDir();
    }

    /**
     * Caption styles that came in Joy packs (PACK-1, the {@code caption-style} socket: a {@code .caption.json}
     * as a saved style is written). Their id names the pack and the file, so a project that uses one finds it
     * in the export process too; a removed pack's styles still resolve but are no longer listed.
     */
    @NonNull
    private static synchronized List<CaptionStyle> packStyles() {
        java.io.File dir = filesDir;
        if (dir == null) return packStyles;
        List<com.fadcam.joyfolder.studio.PackSupplies.Item> src = com.fadcam.joyfolder.studio.PackSupplies.all(dir);
        if (src == packSource) return packStyles;
        List<CaptionStyle> out = new ArrayList<>();
        java.util.Set<String> gone = new java.util.HashSet<>();
        for (com.fadcam.joyfolder.studio.PackSupplies.Item i : src) {
            if (!i.socket.equals(com.fadcam.joyfolder.studio.PackSupplies.CAPTION)) continue;
            try {
                JSONObject o = new JSONObject(new String(java.nio.file.Files.readAllBytes(i.file.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                o.put("id", i.id);
                if (o.optString("label", "").trim().isEmpty()) o.put("label", i.label);
                CaptionStyle s = CaptionStyle.fromJson(o);
                if (s == null) continue;
                out.add(s);
                if (i.gone) gone.add(i.id);
            } catch (Exception ignored) {
                // a pack file that is not a caption style: left out
            }
        }
        packSource = src;
        packStyles = out;
        packGone = gone;
        return out;
    }

    /** All custom styles (named + drafts). Empty when not initialised. */
    @NonNull
    public static synchronized List<CaptionStyle> all() {
        if (prefs == null) return new ArrayList<>();
        if (cache == null) cache = load();
        return new ArrayList<>(cache);
    }

    /** Named custom styles only — what the bottom ticker lists after the presets. */
    @NonNull
    public static List<CaptionStyle> listed() {
        List<CaptionStyle> out = new ArrayList<>();
        for (CaptionStyle s : all()) {
            if (s.id.startsWith("custom_")) out.add(s);
        }
        List<CaptionStyle> packs = packStyles();
        synchronized (CaptionStyleStore.class) {
            for (CaptionStyle s : packs) if (!packGone.contains(s.id)) out.add(s);   // then the packs' styles
        }
        return out;
    }

    @Nullable
    public static CaptionStyle find(@NonNull String id) {
        for (CaptionStyle s : all()) {
            if (s.id.equals(id)) return s;
        }
        if (id.startsWith(com.fadcam.joyfolder.studio.PackSupplies.CAPTION + ":")) {
            for (CaptionStyle s : packStyles()) if (s.id.equals(id)) return s;
        }
        return null;
    }

    /** Insert or replace by id. */
    public static synchronized void put(@NonNull CaptionStyle style) {
        if (prefs == null) return;
        List<CaptionStyle> list = all();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(style.id)) {
                list.set(i, style);
                persist(list);
                return;
            }
        }
        list.add(style);
        persist(list);
    }

    public static synchronized void delete(@NonNull String id) {
        if (prefs == null) return;
        List<CaptionStyle> list = all();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) list.remove(i);
        }
        persist(list);
    }

    private static void persist(@NonNull List<CaptionStyle> list) {
        if (prefs == null) return;
        JSONArray arr = new JSONArray();
        for (CaptionStyle s : list) arr.put(s.toJson());
        prefs.edit().putString(KEY, arr.toString()).apply();
        cache = list;
    }

    @NonNull
    private static List<CaptionStyle> load() {
        List<CaptionStyle> out = new ArrayList<>();
        if (prefs == null) return out;
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                CaptionStyle s = CaptionStyle.fromJson(o);
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }
}
