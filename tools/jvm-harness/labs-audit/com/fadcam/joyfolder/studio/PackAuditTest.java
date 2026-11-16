package com.fadcam.joyfolder.studio;

import com.fadcam.joyfolder.FileJoyFs;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.PackPublish;
import com.fadcam.joyfolder.PackShelf;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex audit 11-15, packs (R2-PACK-01 to 03) and the folder's half-written files (LAB-04): the REAL PackPublish,
 * PackSuppliesSync.sync and JoyFolder.addFile on temporary folders, with a folder that fails on cue.
 * Run by tools/jvm-harness/run-labs-audit.sh.
 */
public final class PackAuditTest {

    private static int pass, fail;

    public static void main(String[] a) throws Exception {
        publishIsAllOrNothing();
        publishRecovers();
        cacheFoldersNeverCollide();
        aFailedReadKeepsTheLastGoodCopy();
        aStoppedAddLeavesNoHalfFile();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    // ---- R2-PACK-01 --------------------------------------------------------------------------------

    static void publishIsAllOrNothing() throws IOException {
        Faulty fs = new Faulty(tempFs());
        String dir = "Supplies/Packs/Film titles";
        PackPublish.publish(fs, dir, pack("1", "one-a", "one-b"));
        check("v1 installed", "one-a".equals(read(fs, dir + "/a.txt")) && "one-b".equals(read(fs, dir + "/b/b.txt")));

        fs.failCreateAt = 2;   // the second file of the new version cannot be written
        boolean threw = false;
        try {
            PackPublish.publish(fs, dir, pack("2", "two-a", "two-b"));
        } catch (IOException e) {
            threw = true;
        }
        fs.failCreateAt = -1;
        check("a failed update says so", threw);
        check("a failed update leaves the old pack whole",
                "one-a".equals(read(fs, dir + "/a.txt")) && "one-b".equals(read(fs, dir + "/b/b.txt"))
                        && read(fs, dir + "/joypack.json").contains("\"version\":\"1\""));
        check("no half-made version is left beside it", hidden(fs).isEmpty());
        check("the shelf still sees one pack", PackShelf.installed(fs, "font").size() == 1);

        PackPublish.publish(fs, dir, pack("2", "two-a", "two-b"));
        check("a good update replaces it whole",
                "two-a".equals(read(fs, dir + "/a.txt")) && "two-b".equals(read(fs, dir + "/b/b.txt"))
                        && read(fs, dir + "/joypack.json").contains("\"version\":\"2\""));
        check("and leaves nothing beside it", hidden(fs).isEmpty());
    }

    static void publishRecovers() throws IOException {
        FileJoyFs fs = tempFs();
        String top = "Supplies/Packs";
        // the app ended after setting the old version aside, before the new one was in place
        write(fs, top + "/.Film titles.replaced/joypack.json", manifest("1"));
        write(fs, top + "/.Film titles.replaced/a.txt", "one-a");
        // and another install ended while it was writing
        write(fs, top + "/.Other.installing/joypack.json", manifest("9"));
        check("a set-aside pack is not on the shelf", PackShelf.installed(fs, "font").isEmpty());
        PackPublish.recover(fs, top);
        check("recover puts the old version back", "one-a".equals(read(fs, top + "/Film titles/a.txt")));
        check("and takes the unfinished one away", hidden(fs).isEmpty());
    }

    // ---- R2-PACK-02 --------------------------------------------------------------------------------

    static void cacheFoldersNeverCollide() throws IOException {
        FileJoyFs fs = tempFs();
        File files = Files.createTempDirectory("packfiles").toFile();
        fontPack(fs, "Supplies/Packs/One", "Artist Pack", "AAA");
        fontPack(fs, "Supplies/Packs/Two", "Artist_Pack", "BBB");
        PackSuppliesSync.sync(fs, files);
        List<PackSupplies.Item> items = PackSupplies.all(files);
        check("two packs, two fonts", items.size() == 2);
        if (items.size() == 2) {
            check("in two different files", !items.get(0).file.equals(items.get(1).file));
            String x = text(items.get(0).file), y = text(items.get(1).file);
            check("each with its own pack's bytes", !x.equals(y) && (x + y).contains("AAA") && (x + y).contains("BBB"));
        }
        check("a safe id keeps its old folder", "Artist_Pack".equals(PackSuppliesSync.folderOf("Artist_Pack")));
        check("ids that clean up alike differ", !PackSuppliesSync.folderOf("a/b").equals(PackSuppliesSync.folderOf("a?b")));
        check("a dotted id is not hidden", !PackSuppliesSync.folderOf(".x").startsWith("."));
    }

    // ---- R2-PACK-03 --------------------------------------------------------------------------------

    static void aFailedReadKeepsTheLastGoodCopy() throws IOException {
        Faulty fs = new Faulty(tempFs());
        File files = Files.createTempDirectory("packfiles").toFile();
        fontPack(fs, "Supplies/Packs/One", "one", "AAA");
        PackSuppliesSync.sync(fs, files);
        check("first sync offers the font", PackSupplies.all(files).size() == 1);
        write(fs, "Supplies/Packs/One/F.ttf", "AAAA, newer");   // changed, so the next sync copies it again
        fs.failOpen = "Supplies/Packs/One/F.ttf";
        PackSuppliesSync.sync(fs, files);
        fs.failOpen = null;
        List<PackSupplies.Item> items = PackSupplies.all(files);
        check("a read that fails keeps the font on the shelf", items.size() == 1);
        if (items.size() == 1) {
            check("still offered (its pack is still there)", !items.get(0).gone);
            check("with its last good bytes", "AAA".equals(text(items.get(0).file)));
        }
        PackSuppliesSync.sync(fs, files);
        items = PackSupplies.all(files);
        check("the next good sync takes the new bytes", items.size() == 1 && "AAAA, newer".equals(text(items.get(0).file)));
        fs.delete("Supplies/Packs/One");
        PackSuppliesSync.sync(fs, files);
        items = PackSupplies.all(files);
        check("a removed pack's copy stays, marked gone", items.size() == 1 && items.get(0).gone);
    }

    // ---- LAB-04: a stopped copy into the folder -----------------------------------------------------

    static void aStoppedAddLeavesNoHalfFile() throws IOException {
        FileJoyFs fs = tempFs();
        JoyFolder jf = JoyFolder.open(fs);
        fs.mkdirs("Exports");
        InputStream stopsHalfWay = new InputStream() {
            int n;

            @Override
            public int read() {
                if (n++ > 1000) throw new IllegalStateException("stopped");
                return 'x';
            }
        };
        boolean threw = false;
        try {
            jf.addFile("Exports", "HOT.png", stopsHalfWay, "export");
        } catch (RuntimeException e) {
            threw = true;
        }
        check("the stop reaches the caller", threw);
        check("no half-written file is left", fs.list("Exports").isEmpty());
        jf.addFile("Exports", "HOT.png", new ByteArrayInputStream(new byte[]{1, 2, 3}), "export");
        check("the next export takes the plain name", fs.stat("Exports/HOT.png") != null);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    static Map<String, byte[]> pack(String version, String a, String b) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("joypack.json", bytes(manifest(version)));
        m.put("a.txt", bytes(a));
        m.put("b/b.txt", bytes(b));
        m.put("F.ttf", bytes("font"));
        return m;
    }

    static String manifest(String version) {
        return "{\"id\":\"film\",\"name\":\"Film titles\",\"version\":\"" + version + "\",\"contents\":["
                + "{\"socket\":\"font\",\"file\":\"F.ttf\"}]}";
    }

    static void fontPack(JoyFs fs, String dir, String id, String font) throws IOException {
        write(fs, dir + "/joypack.json", "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"contents\":["
                + "{\"socket\":\"font\",\"file\":\"F.ttf\"}]}");
        write(fs, dir + "/F.ttf", font);
    }

    static List<String> hidden(JoyFs fs) throws IOException {
        List<String> out = new java.util.ArrayList<>();
        if (fs.stat("Supplies/Packs") == null) return out;
        for (JoyFs.Entry e : fs.list("Supplies/Packs")) if (e.name.startsWith(".")) out.add(e.name);
        return out;
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static void write(JoyFs fs, String rel, String s) throws IOException {
        try (OutputStream o = fs.create(rel)) {
            o.write(bytes(s));
        }
    }

    static String read(JoyFs fs, String rel) throws IOException {
        if (fs.stat(rel) == null) return null;
        try (InputStream in = fs.open(rel)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    static FileJoyFs tempFs() throws IOException {
        return new FileJoyFs(Files.createTempDirectory("labsaudit").toFile());
    }

    static void check(String what, boolean ok) {
        if (ok) pass++;
        else fail++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }

    /** A folder that fails on cue: the Nth file created, or every open of one path. */
    static final class Faulty implements JoyFs {
        final JoyFs in;
        int failCreateAt = -1, creates;
        String failOpen;

        Faulty(JoyFs in) {
            this.in = in;
        }

        @Override public List<Entry> list(String relDir) throws IOException { return in.list(relDir); }
        @Override public Entry stat(String rel) throws IOException { return in.stat(rel); }

        @Override
        public InputStream open(String rel) throws IOException {
            if (rel.equals(failOpen)) throw new IOException("unreadable just now");
            return in.open(rel);
        }

        @Override
        public OutputStream create(String rel) throws IOException {
            if (failCreateAt > 0 && ++creates == failCreateAt) throw new IOException("disk said no");
            return in.create(rel);
        }

        @Override public boolean rename(String rel, String newName) throws IOException { return in.rename(rel, newName); }
        @Override public boolean move(String rel, String newParentRel) throws IOException { return in.move(rel, newParentRel); }
        @Override public void mkdirs(String relDir) throws IOException { in.mkdirs(relDir); }
        @Override public boolean delete(String rel) throws IOException { return in.delete(rel); }
    }
}
