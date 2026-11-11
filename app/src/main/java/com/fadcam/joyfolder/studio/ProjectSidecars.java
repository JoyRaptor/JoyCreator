package com.fadcam.joyfolder.studio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The small files a Studio project keeps BESIDE its project.json that are the person's own work:
 * timeline marks and regions ({@code timeline-marks.json}) and the older bookmark list
 * ({@code bookmarks.json}). The copy of a project in the Joy Creator folder is one file, so these
 * travel inside it and are written back out when the project is restored (work-loss audit
 * 2026-11-10: before this a restored project had lost every mark).
 *
 * <p>Not here on purpose: undo history (large, and tied to one sitting), caches, and the app's own
 * backups. Pure {@code java.io} and Gson, so the JVM harness tests it
 * ({@code tools/jvm-harness/run-sidecars.sh}).</p>
 */
public final class ProjectSidecars {

    /** File names carried, as they are in the project folder. A name is never a path. */
    static final String[] NAMES = {"timeline-marks.json", "bookmarks.json"};
    /** A marks file is a few kilobytes; anything this big is not one, and is left out. */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private ProjectSidecars() {}

    /** The project's sidecars as {@code {"timeline-marks.json": {...}, ...}}, or null when it has none. */
    public static JsonObject read(File projectDir) {
        JsonObject out = new JsonObject();
        for (String name : NAMES) {
            File f = source(projectDir, name);
            if (f == null || f.length() > MAX_BYTES) continue;
            try {
                JsonElement el = JsonParser.parseString(readText(f));
                if (el.isJsonObject()) out.add(name, el);
            } catch (Exception ignored) {
                // Unreadable right now: leave it out of this copy. The next save tries again.
            }
        }
        return out.size() == 0 ? null : out;
    }

    /**
     * The file to read. {@code timeline-marks.json} is written the way Android's AtomicFile writes:
     * while a write is in flight (or after one was cut off) the good copy is the {@code .bak}.
     */
    private static File source(File projectDir, String name) {
        File bak = new File(projectDir, name + ".bak");
        if (bak.isFile()) return bak;
        File f = new File(projectDir, name);
        return f.isFile() ? f : null;
    }

    /**
     * Write the carried sidecars into a project folder being restored. A file that is already
     * there is never replaced: what is on the phone is at least as new as the folder copy.
     */
    public static void restore(File projectDir, JsonObject sidecars) {
        if (sidecars == null) return;
        for (Map.Entry<String, JsonElement> e : sidecars.entrySet()) {
            String name = e.getKey();
            if (!known(name) || !e.getValue().isJsonObject()) continue;
            File target = new File(projectDir, name);
            if (target.exists() || new File(projectDir, name + ".bak").exists()) continue;
            File tmp = new File(projectDir, name + ".restore.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(e.getValue().toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException ex) {
                tmp.delete();
                continue;
            }
            if (!tmp.renameTo(target)) tmp.delete();
        }
    }

    private static boolean known(String name) {
        for (String n : NAMES) if (n.equals(name)) return true;
        return false;
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
