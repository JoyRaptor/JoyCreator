package com.fadcam.vislab.room;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.fadcam.FLog;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.PackShelf;
import com.fadcam.vislab.model.LookValidator;
import com.fadcam.vislab.model.VisPart;
import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.model.VisTypes;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * VisLab looks and parts that came in Joy packs (job PACK-1, sockets {@code vis-look} and {@code vis-part}), read
 * from {@code Supplies/Packs/<pack>/}. A pack's look is offered like a built-in one: it is never copied out, the
 * person's copy is written only once they change it (and wins from then on), and "Revert to default" puts the
 * pack's own version back. A pack's parts sit on the parts shelf under the pack's name.
 *
 * <p>Blocking: call on {@code JoyFolderHome.io()} only.</p>
 */
final class PackLooks {

    private static final String TAG = "VisPackLooks";

    /** A part and the pack it came in. */
    static final class Part {
        @NonNull final String pack;
        @NonNull final VisPart part;

        Part(@NonNull String pack, @NonNull VisPart part) {
            this.pack = pack;
            this.part = part;
        }
    }

    /** What the installed packs hold for VisLab. */
    static final class Read {
        /** Look id -> the look, in pack order. */
        final Map<String, VisScene> looks = new LinkedHashMap<>();
        /** Look id -> the name of the pack it came in. */
        final Map<String, String> packOf = new LinkedHashMap<>();
        final List<Part> parts = new ArrayList<>();
    }

    private PackLooks() {}

    /**
     * Every pack look and part. A look whose id is taken (a built-in look's, or another pack's) gets the pack's
     * name in front, the same way every time, so the person's copy of it is found again next time.
     */
    @WorkerThread
    @NonNull
    static Read read(@NonNull JoyFs fs, @NonNull Set<String> taken, @NonNull VisTypes types) {
        Read r = new Read();
        for (PackShelf.Found f : PackShelf.installed(fs, "vis-look")) {
            try {
                JsonObject o = JsonParser.parseString(text(fs, f.rel)).getAsJsonObject();
                String id = o.has("id") && o.get("id").isJsonPrimitive() ? o.get("id").getAsString() : slug(f.label());
                if (taken.contains(id) || r.looks.containsKey(id)) id = slug(f.pack.name) + "-" + id;
                String base = id;
                for (int n = 2; taken.contains(id) || r.looks.containsKey(id); n++) id = base + "-" + n;
                o.addProperty("id", id);
                if (!o.has("name") || !o.get("name").isJsonPrimitive()) o.addProperty("name", f.label());
                List<String> notes = LookValidator.fix(o, types);
                if (!notes.isEmpty()) FLog.w(TAG, f.rel + ": " + String.join("; ", notes));
                r.looks.put(id, new VisScene(o));
                r.packOf.put(id, f.pack.name);
            } catch (Exception e) {
                FLog.w(TAG, "Skipped " + f.rel + ": not a readable look");
            }
        }
        for (PackShelf.Found f : PackShelf.installed(fs, "vis-part")) {
            try {
                VisPart p = VisPart.parse(text(fs, f.rel), f.label());
                if (p != null) r.parts.add(new Part(f.pack.name, p));
                else FLog.w(TAG, "Skipped " + f.rel + ": not a readable part");
            } catch (Exception e) {
                FLog.w(TAG, "Skipped " + f.rel + ": not readable");
            }
        }
        return r;
    }

    /** An empty read (no folder). */
    @NonNull
    static Read none() {
        return new Read();
    }

    @NonNull
    static List<Part> unmodifiable(@NonNull List<Part> parts) {
        return Collections.unmodifiableList(new ArrayList<>(parts));
    }

    @NonNull
    private static String text(@NonNull JoyFs fs, @NonNull String rel) throws java.io.IOException {
        return new String(PackShelf.readAll(fs, rel), StandardCharsets.UTF_8);
    }

    @NonNull
    private static String slug(@NonNull String s) {
        String b = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return b.isEmpty() ? "pack" : b;
    }
}
