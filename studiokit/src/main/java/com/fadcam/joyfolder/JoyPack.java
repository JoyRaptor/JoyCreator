package com.fadcam.joyfolder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A Joy pack's manifest ({@code joypack.json}, job PACK-1): one file that brings new things into Joy Creator, from
 * us or from anyone. A pack is a zip of ordinary Joy Creator files plus this manifest; built-in packs use the same
 * manifest in the app's {@code assets/packs/}.
 *
 * <p>Each entry plugs into a SOCKET: the kind of thing it is. A socket this app does not know yet is kept (never
 * dropped) and reported, so a pack made for a later Joy Creator still installs and says what it is waiting for.</p>
 *
 * <p>Pure Java (Gson only), tested on a plain JVM by {@code tools/joyfolder-tests}.</p>
 */
public final class JoyPack {

    /** The pack level this app understands; a pack whose {@code needs} is higher waits for an update. */
    public static final int LEVEL = 1;
    public static final String MANIFEST = "joypack.json";
    public static final String FORMAT = "joy.pack/1";

    /** Sockets this app can use today. Grows as rooms learn to take more kinds of things. */
    public static final Set<String> SOCKETS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "text-look", "vis-look", "vis-part", "brush", "paper", "palette", "font", "grade", "caption-style")));

    /** One thing in the pack: the socket it plugs into and its file, relative to the pack's top. */
    public static final class Entry {
        public final String socket;
        public final String file;

        Entry(String socket, String file) {
            this.socket = socket;
            this.file = file;
        }

        public boolean known() {
            return SOCKETS.contains(socket);
        }
    }

    public final String id, name, author, version, about, cover;
    public final int needs;
    /** A built-in pack kept out of this release (a later wave). */
    public final boolean held;
    public final List<Entry> contents;

    private JoyPack(String id, String name, String author, String version, String about, String cover, int needs,
                    boolean held, List<Entry> contents) {
        this.id = id;
        this.name = name;
        this.author = author;
        this.version = version;
        this.about = about;
        this.cover = cover;
        this.needs = needs;
        this.held = held;
        this.contents = Collections.unmodifiableList(contents);
    }

    /**
     * A manifest from its text. Throws {@link IllegalArgumentException} with a plain reason when it is not one
     * (no id or name, not an object, a file path that climbs out of the pack).
     */
    public static JoyPack parse(String text) {
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("its list of contents could not be read");
        }
        if (!root.isJsonObject()) throw new IllegalArgumentException("its list of contents could not be read");
        JsonObject o = root.getAsJsonObject();
        String id = str(o, "id"), name = str(o, "name");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("it has no id");
        if (name == null || name.isEmpty()) name = id;
        int needs = 1;
        try {
            if (o.has("needs")) needs = Integer.parseInt(o.get("needs").getAsString().trim());
        } catch (RuntimeException e) {
            needs = Integer.MAX_VALUE;   // a level we cannot read is one we do not have
        }
        List<Entry> list = new ArrayList<>();
        JsonElement c = o.get("contents");
        if (c != null && c.isJsonArray()) {
            for (JsonElement e : (JsonArray) c) {
                if (!e.isJsonObject()) continue;
                String socket = str(e.getAsJsonObject(), "socket"), file = str(e.getAsJsonObject(), "file");
                if (socket == null || file == null || file.isEmpty()) continue;
                if (!safePath(file)) throw new IllegalArgumentException("one of its files points outside the pack");
                list.add(new Entry(socket.trim().toLowerCase(Locale.ROOT), file.trim()));
            }
        }
        boolean held = o.has("held") && o.get("held").isJsonPrimitive() && o.get("held").getAsBoolean();
        return new JoyPack(id.trim(), name.trim(), nz(str(o, "author")), nz(str(o, "version")), nz(str(o, "about")),
                str(o, "cover"), needs, held, list);
    }

    /** True when this app can use the pack at all (its level is ours or older). */
    public boolean usable() {
        return needs <= LEVEL;
    }

    /** The entries for one socket. */
    public List<Entry> of(String socket) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : contents) if (e.socket.equals(socket)) out.add(e);
        return out;
    }

    /** Entries waiting for a newer Joy Creator (a socket this app does not know). */
    public List<Entry> waiting() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : contents) if (!e.known()) out.add(e);
        return out;
    }

    /** A path inside the pack: relative, no "..", no drive or leading slash. */
    static boolean safePath(String p) {
        String s = p.replace('\\', '/');
        if (s.startsWith("/") || s.contains(":")) return false;
        for (String part : s.split("/")) if (part.equals("..")) return false;
        return true;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
