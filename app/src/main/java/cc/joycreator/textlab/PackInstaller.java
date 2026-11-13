package cc.joycreator.textlab;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyPack;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Opens a {@code .joypack} (a zip with {@code joypack.json} at its top, job PACK-1) and installs it as its own folder,
 * {@code Supplies/Packs/<pack name>/}: readable files, one folder to see, copy or delete. Every room reads its sockets
 * from there ({@link TextLookShelf}).
 *
 * <p>Safe by construction: every file stays inside the pack's folder (a path that climbs out refuses the whole
 * pack), the pack is read whole before anything is written, and it is capped in size and count. Nothing in a pack
 * runs as program code. Installing a pack with the same id again replaces that pack's folder; the person's own
 * copies (saved in {@code Supplies/Looks/}) are never touched.</p>
 */
public final class PackInstaller {

    static final long MAX_BYTES = 300L * 1024 * 1024;
    static final int MAX_FILES = 5000;

    private PackInstaller() {}

    /** A pack read from its file, not yet installed. */
    public static final class Read {
        public final JoyPack pack;
        final Map<String, byte[]> files;
        public final long bytes;

        Read(JoyPack pack, Map<String, byte[]> files, long bytes) {
            this.pack = pack;
            this.files = files;
            this.bytes = bytes;
        }

        public int fileCount() {
            return files.size();
        }
    }

    /** Read a pack whole. Throws with a plain reason when it is not one, or is unsafe, or too big. */
    @WorkerThread
    @NonNull
    public static Read read(@NonNull InputStream in) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (!safe(name)) throw new IOException("one of its files points outside the pack");
                if (name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store") || name.equals(".DS_Store")) continue;
                if (files.size() >= MAX_FILES) throw new IOException("it holds too many files");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int r;
                while ((r = z.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_BYTES) throw new IOException("it is larger than 300 MB");
                    bo.write(buf, 0, r);
                }
                files.put(name, bo.toByteArray());
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("it is not a zip file");
        }
        byte[] m = files.get(JoyPack.MANIFEST);
        if (m == null) throw new IOException("it has no " + JoyPack.MANIFEST + " (it may not be a Joy pack)");
        JoyPack pack;
        try {
            pack = JoyPack.parse(new String(m, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage());
        }
        return new Read(pack, files, total);
    }

    /** Install a read pack into the Joy Creator folder. Returns the pack's folder. Folder thread. */
    @WorkerThread
    @NonNull
    public static String install(@NonNull JoyFolder jf, @NonNull Read r) throws IOException {
        String dir = findOrMake(jf, r.pack);
        for (Map.Entry<String, byte[]> f : r.files.entrySet()) {
            String rel = Placement.join(dir, f.getKey());
            int slash = rel.lastIndexOf('/');
            if (slash > 0) jf.fs.mkdirs(rel.substring(0, slash));
            jf.writeAtomic(rel, f.getValue());
        }
        jf.saveIndex();
        return dir;
    }

    /** The folder of an installed pack with this id (updated in place), or a new one named after the pack. */
    private static String findOrMake(@NonNull JoyFolder jf, @NonNull JoyPack p) throws IOException {
        String top = TextLookShelf.PACKS_DIR;
        jf.fs.mkdirs(top);
        for (com.fadcam.joyfolder.JoyFs.Entry d : jf.fs.list(top)) {
            if (!d.dir) continue;
            String m = Placement.join(Placement.join(top, d.name), JoyPack.MANIFEST);
            try {
                if (jf.fs.stat(m) != null && JoyPack.parse(new String(jf.readAll(m), StandardCharsets.UTF_8)).id.equals(p.id)) {
                    return Placement.join(top, d.name);
                }
            } catch (RuntimeException ignored) {
                // another pack's unreadable manifest: not this one
            }
        }
        String base = ReadableNames.sanitize(p.name).trim();
        if (base.isEmpty()) base = "Pack";
        String dir = Placement.join(top, jf.freeName(top, base));
        jf.fs.mkdirs(dir);
        return dir;
    }

    /** A path inside the pack: relative, no "..", no drive. */
    static boolean safe(@NonNull String p) {
        if (p.startsWith("/") || p.contains(":")) return false;
        for (String part : p.split("/")) if (part.equals("..")) return false;
        return true;
    }
}
