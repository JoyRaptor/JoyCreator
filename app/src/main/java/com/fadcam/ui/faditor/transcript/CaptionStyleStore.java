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

    private CaptionStyleStore() { }

    public static synchronized void ensureInit(@NonNull Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            cache = null;
        }
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
        return out;
    }

    @Nullable
    public static CaptionStyle find(@NonNull String id) {
        for (CaptionStyle s : all()) {
            if (s.id.equals(id)) return s;
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
