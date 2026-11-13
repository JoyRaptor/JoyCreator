package com.fadcam.joyfolder.studio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Studio's share of the installed Joy packs (job PACK-1): their fonts ({@code font}), colour grades ({@code grade},
 * a {@code .cube}) and caption styles ({@code caption-style}, a {@code .caption.json}).
 *
 * <p>Studio draws and exports from plain files it can open by path (and its export runs in a process of its own),
 * so {@link PackSuppliesSync} copies each of these from {@code Supplies/Packs/<pack>/} into the app's
 * {@code files/packs/<pack id>/}, and a small list, {@code files/packs/shelf.json}, says what each is and where it
 * came from. This class reads that list; nothing is read from the Joy Creator folder while drawing.</p>
 *
 * <p>A pack that is removed leaves its copies in place, marked gone: a project that uses its font or grade keeps
 * looking the same, but the pickers no longer offer them.</p>
 *
 * <p>Plain Java (Gson): Studio's model code reaches it through the font and caption lists.</p>
 */
public final class PackSupplies {

    private static final String TAG = "PackSupplies";
    public static final String FONT = "font", GRADE = "grade", CAPTION = "caption-style";
    static final String SHELF = "shelf.json";

    /** One thing from a pack, as Studio uses it. */
    public static final class Item {
        public final String socket;
        /** Stable across runs and phones: the socket, the pack's id and the file's place in it. */
        public final String id;
        /** What the person sees: the file's name without its ending. */
        public final String label;
        /** The pack's name. */
        public final String pack;
        /** The app's own copy. */
        public final File file;
        /** The pack has been removed: kept so work that uses it still draws, no longer offered. */
        public final boolean gone;

        Item(String socket, String id, String label, String pack, File file, boolean gone) {
            this.socket = socket;
            this.id = id;
            this.label = label;
            this.pack = pack;
            this.file = file;
            this.gone = gone;
        }
    }

    private static final Object LOCK = new Object();
    @Nullable private static List<Item> cache;
    private static long cacheStamp = -1;

    private PackSupplies() {}

    /** The app's copies of pack supplies: {@code <files>/packs}. */
    @NonNull
    public static File root(@NonNull File filesDir) {
        return new File(filesDir, "packs");
    }

    /** Everything Studio has from packs for one socket, offered ones only (removed packs left out). Any thread. */
    @NonNull
    public static List<Item> offered(@NonNull File filesDir, @NonNull String socket) {
        List<Item> out = new ArrayList<>();
        for (Item i : all(filesDir)) if (!i.gone && i.socket.equals(socket)) out.add(i);
        return out;
    }

    /** An item by its id, offered or gone (a project that uses it still finds it), or null. Any thread. */
    @Nullable
    public static Item find(@NonNull File filesDir, @NonNull String id) {
        for (Item i : all(filesDir)) if (i.id.equals(id)) return i;
        return null;
    }

    /** Everything on the list, offered and gone. The same list object until the list changes. Any thread. */
    @NonNull
    public static List<Item> all(@NonNull File filesDir) {
        File f = new File(root(filesDir), SHELF);
        synchronized (LOCK) {
            long stamp = f.isFile() ? f.lastModified() ^ (f.length() << 20) : 0;
            if (cache != null && stamp == cacheStamp) return cache;
            List<Item> out = new ArrayList<>();
            if (f.isFile()) {
                try {
                    JsonElement items = JsonParser.parseString(new String(Files.readAllBytes(f.toPath()),
                            StandardCharsets.UTF_8)).getAsJsonObject().get("items");
                    if (items != null && items.isJsonArray()) {
                        for (JsonElement e : items.getAsJsonArray()) {
                            if (!e.isJsonObject()) continue;
                            JsonObject o = e.getAsJsonObject();
                            out.add(new Item(str(o, "socket"), str(o, "id"), str(o, "label"), str(o, "pack"),
                                    new File(str(o, "file")), o.has("gone") && o.get("gone").getAsBoolean()));
                        }
                    }
                } catch (Exception e) {
                    FLog.w(TAG, "Pack supplies list not read", e);
                }
            }
            cache = Collections.unmodifiableList(out);
            cacheStamp = stamp;
            return cache;
        }
    }

    /** Replace the list (the sync, on the folder thread). */
    static void write(@NonNull File filesDir, @NonNull List<Item> items) throws IOException {
        JsonArray a = new JsonArray();
        for (Item i : items) {
            JsonObject o = new JsonObject();
            o.addProperty("socket", i.socket);
            o.addProperty("id", i.id);
            o.addProperty("label", i.label);
            o.addProperty("pack", i.pack);
            o.addProperty("file", i.file.getAbsolutePath());
            if (i.gone) o.addProperty("gone", true);
            a.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("items", a);
        File dir = root(filesDir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, SHELF + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        File to = new File(dir, SHELF);
        if (!tmp.renameTo(to)) {
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            if (!tmp.renameTo(to)) throw new IOException("Cannot save " + to);
        }
        synchronized (LOCK) {
            cache = null;
        }
    }

    @NonNull
    private static String str(@NonNull JsonObject o, @NonNull String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }
}
