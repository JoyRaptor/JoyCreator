package com.fadcam.joyfolder.studio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * One avatar (a saved character) as ONE readable file: {@code <Name>.avatar.json}, the form the
 * Joy Creator folder keeps. The app's own copy is a bundle folder ({@code avatar.json} plus
 * {@code sheets/<id>.png}); this packs it into a single JSON file, with the pictures written as
 * text, so the character is one file the person can see, copy and keep, and unpacks it again after
 * a reinstall (work-loss audit 2026-11-10, F6).
 *
 * <pre>
 * { "joyAvatar": 1, "id": "...", "name": "My Dino", "libraryDir": "My_Dino-3fa4a1b2.avatar",
 *   "manifest": { ...the bundle's avatar.json... },
 *   "sheets": { "sheets/&lt;id&gt;.png": "&lt;base64&gt;" } }
 * </pre>
 *
 * <p>The first four fields are written first so a reader can learn which character a file is from
 * its first few hundred bytes ({@link #headOf(InputStream)}). Plain {@code java.io} and Gson, so the
 * JVM harness tests it ({@code tools/jvm-harness/run-avatarfont-copy.sh}).</p>
 */
public final class AvatarBundleFile {

    public static final String ENDING = ".avatar.json";
    static final int VERSION = 1;
    private static final String LIB_ENDING = ".avatar";
    private static final String MANIFEST = "avatar.json";
    private static final String SHEETS = "sheets";

    private AvatarBundleFile() {}

    /** Which character a file or bundle is. */
    public static final class Head {
        public final String id;
        public final String name;
        /** The bundle folder's name in the app's library, e.g. {@code My_Dino-3fa4a1b2.avatar}. */
        public final String libraryDir;

        Head(String id, String name, String libraryDir) {
            this.id = id;
            this.name = name;
            this.libraryDir = libraryDir;
        }

        /** The 8 characters the app's library folder name ends with. */
        public String shortId() {
            return AvatarBundleFile.shortId(id);
        }
    }

    public static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** The character a bundle folder holds, or null when it is not a readable bundle. */
    public static Head headOfBundle(File entryDir) {
        try {
            JsonObject rig = manifestOf(entryDir).getAsJsonObject("rig");
            return new Head(rig.get("id").getAsString(), rig.get("name").getAsString(), entryDir.getName());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static JsonObject manifestOf(File entryDir) throws IOException {
        try (InputStream in = new FileInputStream(new File(entryDir, MANIFEST));
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /** Write the bundle in {@code entryDir} as one file. Throws if it cannot be read in full. */
    public static void write(File entryDir, OutputStream out) throws IOException {
        JsonObject manifest = manifestOf(entryDir);
        Head head = headOfBundle(entryDir);
        if (head == null) throw new IOException("Not a character: " + entryDir);
        JsonWriter w = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        w.setIndent("  ");
        w.beginObject();
        w.name("joyAvatar").value(VERSION);
        w.name("id").value(head.id);
        w.name("name").value(head.name);
        w.name("libraryDir").value(head.libraryDir);
        w.name("manifest");
        new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(manifest, w);
        w.name("sheets").beginObject();
        File[] pics = new File(entryDir, SHEETS).listFiles();
        if (pics != null) {
            java.util.Arrays.sort(pics);
            for (File p : pics) {
                if (!p.isFile()) continue;
                w.name(SHEETS + "/" + p.getName()).value(B64.encode(readAll(p)));
            }
        }
        w.endObject();
        w.endObject();
        w.flush();
    }

    /** Which character a file is, from its first fields; null when it is not one of ours. */
    public static Head headOf(InputStream in) {
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String id = null, name = null, dir = null;
            r.beginObject();
            boolean ours = false;
            while (r.hasNext()) {
                String key = r.nextName();
                if (key.equals("joyAvatar")) { r.skipValue(); ours = true; }
                else if (key.equals("id") && r.peek() == JsonToken.STRING) id = r.nextString();
                else if (key.equals("name") && r.peek() == JsonToken.STRING) name = r.nextString();
                else if (key.equals("libraryDir") && r.peek() == JsonToken.STRING) dir = r.nextString();
                else break;   // the big parts follow: the head is complete
                if (id != null && name != null && dir != null) break;
            }
            return ours && id != null && name != null ? new Head(id, name, dir) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Unpack a file into the library as a bundle folder. Returns the new folder, or null when the
     * library already holds that character (a character in the app is never written over) or the
     * file is not a readable character. Built in a temporary folder and moved in, so a cut-off
     * leaves no half bundle in the list.
     */
    public static File unpack(InputStream in, File library) throws IOException {
        File tmp = null;
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String id = null, name = null, dir = null;
            JsonObject manifest = null;
            r.beginObject();
            while (r.hasNext()) {
                String key = r.nextName();
                switch (key) {
                    case "id": id = r.nextString(); break;
                    case "name": name = r.nextString(); break;
                    case "libraryDir": dir = r.nextString(); break;
                    case "manifest": {
                        JsonElement m = JsonParser.parseReader(r);
                        manifest = m.isJsonObject() ? m.getAsJsonObject() : null;
                        break;
                    }
                    case "sheets": {
                        String target = targetName(dir, id, name);
                        if (target == null || existing(library, id) != null) return null;
                        if (tmp == null) {
                            tmp = new File(library, target + ".tmp");
                            deleteRecursive(tmp);
                            if (!new File(tmp, SHEETS).mkdirs()) throw new IOException("Cannot make " + tmp);
                        }
                        r.beginObject();
                        while (r.hasNext()) {
                            String rel = r.nextName();
                            byte[] png = B64.decode(r.nextString());
                            if (!safeSheetName(rel)) continue;
                            write(new File(tmp, rel), png);
                        }
                        r.endObject();
                        break;
                    }
                    default: r.skipValue();
                }
            }
            if (id == null || manifest == null) { deleteRecursive(tmp); return null; }
            String target = targetName(dir, id, name);
            if (target == null || existing(library, id) != null) { deleteRecursive(tmp); return null; }
            if (tmp == null) {
                tmp = new File(library, target + ".tmp");
                deleteRecursive(tmp);
                if (!new File(tmp, SHEETS).mkdirs()) throw new IOException("Cannot make " + tmp);
            }
            write(new File(tmp, MANIFEST), manifest.toString().getBytes(StandardCharsets.UTF_8));
            File dest = new File(library, target);
            if (dest.exists() || !tmp.renameTo(dest)) { deleteRecursive(tmp); return null; }
            return dest;
        } catch (IOException | RuntimeException e) {
            deleteRecursive(tmp);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Not a readable character file", e);
        }
    }

    /** The folder in {@code library} that already holds the character with this id, or null. */
    public static File existing(File library, String id) {
        if (id == null) return null;
        String tail = "-" + shortId(id) + LIB_ENDING;
        File[] all = library.listFiles();
        if (all == null) return null;
        for (File d : all) if (d.isDirectory() && d.getName().endsWith(tail)) return d;
        return null;
    }

    /** The bundle folder name to unpack into: the one the file names, if it is a plain name for this id. */
    private static String targetName(String dir, String id, String name) {
        if (id == null) return null;
        String tail = "-" + shortId(id) + LIB_ENDING;
        if (dir != null && dir.endsWith(tail) && dir.length() > tail.length()
                && dir.indexOf('/') < 0 && dir.indexOf('\\') < 0 && !dir.contains("..")) return dir;
        String safe = (name == null ? "" : name).trim().replaceAll("[^A-Za-z0-9-_ ]", "").replace(' ', '_');
        return (safe.isEmpty() ? "avatar" : safe) + tail;
    }

    private static boolean safeSheetName(String rel) {
        return rel.startsWith(SHEETS + "/") && rel.indexOf('\\') < 0 && !rel.contains("..")
                && rel.indexOf('/', SHEETS.length() + 1) < 0 && rel.length() > SHEETS.length() + 1;
    }

    private static void write(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length() + 16, 64L * 1024 * 1024));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
