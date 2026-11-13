package com.fadcam.joyfolder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the installed Joy packs hold, socket by socket (job PACK-1): every room that reads a kind of thing also reads
 * it from {@code Supplies/Packs/<pack>/}. One reader for every wing, so a pack's rules (its level, safe paths, files
 * that are really there) are the same everywhere.
 *
 * <p>Packs are read in folder-name order and entries in manifest order, so a shelf is stable from run to run. A pack
 * whose manifest cannot be read, or that needs a newer Joy Creator, is skipped; an entry whose file is missing is
 * left out. Pure Java over {@link JoyFs}: tested on a plain JVM by {@code tools/joyfolder-tests}.</p>
 */
public final class PackShelf {

    /** Where installed packs live, one folder each. */
    public static final String DIR = "Supplies/Packs";

    private PackShelf() {}

    /** One entry of an installed pack. */
    public static final class Found {
        public final JoyPack pack;
        /** The pack's folder, relative to the Joy Creator folder ("Supplies/Packs/Film titles"). */
        public final String packDir;
        public final JoyPack.Entry entry;
        /** The entry's file, relative to the Joy Creator folder. */
        public final String rel;

        Found(JoyPack pack, String packDir, JoyPack.Entry entry, String rel) {
            this.pack = pack;
            this.packDir = packDir;
            this.entry = entry;
            this.rel = rel;
        }

        /** The file's name without its folders or its ending ("Dark Steel" for "looks/Dark Steel.text.json"). */
        public String label() {
            return FileKind.baseOf(Placement.nameOf(rel));
        }
    }

    /** Every installed, usable pack's entries for one socket. Never throws; empty when there are no packs. */
    public static List<Found> installed(JoyFs fs, String socket) {
        return installed(fs, Collections.singleton(socket));
    }

    /** Every installed, usable pack's entries for any of these sockets. Never throws. */
    public static List<Found> installed(JoyFs fs, Set<String> sockets) {
        List<Found> out = new ArrayList<>();
        try {
            if (fs.stat(DIR) == null) return out;
            List<JoyFs.Entry> dirs = new ArrayList<>(fs.list(DIR));
            dirs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            for (JoyFs.Entry d : dirs) {
                if (!d.dir) continue;
                String top = Placement.join(DIR, d.name);
                JoyPack p = manifest(fs, top);
                if (p == null || !p.usable()) continue;
                Set<String> seen = new HashSet<>();
                for (JoyPack.Entry e : p.contents) {
                    if (!sockets.contains(e.socket)) continue;
                    String rel = Placement.join(top, e.file.replace('\\', '/'));
                    if (!seen.add(rel)) continue;
                    try {
                        JoyFs.Entry f = fs.stat(rel);
                        if (f == null || f.dir) continue;
                    } catch (IOException | RuntimeException ex) {
                        continue;
                    }
                    out.add(new Found(p, top, e, rel));
                }
            }
        } catch (IOException | RuntimeException e) {
            // the packs folder could not be listed: no packs this time
        }
        return out;
    }

    /** The manifest of the pack in {@code packDir}, or null when there is none or it cannot be read. */
    public static JoyPack manifest(JoyFs fs, String packDir) {
        try {
            String m = Placement.join(packDir, JoyPack.MANIFEST);
            if (fs.stat(m) == null) return null;
            return JoyPack.parse(new String(readAll(fs, m), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A file's bytes. */
    public static byte[] readAll(JoyFs fs, String rel) throws IOException {
        try (InputStream in = fs.open(rel)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
