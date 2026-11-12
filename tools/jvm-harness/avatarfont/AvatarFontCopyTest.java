import com.fadcam.joyfolder.FileJoyFs;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.studio.AvatarBundleFile;
import com.fadcam.joyfolder.studio.AvatarFolderCopy;
import com.fadcam.joyfolder.studio.FontFolderCopy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

/**
 * Work-loss audit F6 (2026-11-12): saved characters and imported fonts get their copy in the Joy
 * Creator folder, a character older than this change is back-filled, and both come back after a
 * reinstall (an empty app pointed at the same folder). Nothing is written over.
 */
public class AvatarFontCopyTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static void write(File f, byte[] data) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
    }

    static byte[] bytes(int seed, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (i * 31 + seed);
        return b;
    }

    /** A bundle folder as AvatarLibrary.save leaves it. */
    static File bundle(File library, String name, String id, byte[]... pngs) throws Exception {
        String dirName = name.replace(' ', '_') + "-" + id.substring(0, 8) + ".avatar";
        File dir = new File(library, dirName);
        String manifest = "{\"libSchemaVersion\":1,\"rig\":{\"id\":\"" + id + "\",\"name\":\"" + name
                + "\",\"parts\":[]},\"sheets\":[]}";
        write(new File(dir, "avatar.json"), manifest.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < pngs.length; i++) write(new File(dir, "sheets/sheet" + i + ".png"), pngs[i]);
        return dir;
    }

    static File temp(String p) throws Exception { return Files.createTempDirectory(p).toFile(); }

    public static void main(String[] args) throws Exception {
        // ---- the one-file form
        File lib1 = temp("av-lib1");
        byte[] pngA = bytes(1, 5000), pngB = bytes(2, 7);
        File dino = bundle(lib1, "My Dino", "3fa4a1b2-0000-4000-8000-000000000001", pngA, pngB);
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        AvatarBundleFile.write(dino, packed);
        AvatarBundleFile.Head head = AvatarBundleFile.headOf(new ByteArrayInputStream(packed.toByteArray()));
        check(head != null && head.name.equals("My Dino") && head.shortId().equals("3fa4a1b2"), "the head names the character");
        check(AvatarBundleFile.headOf(new ByteArrayInputStream("{\"a\":1}".getBytes())) == null, "another json file is not a character");
        File lib2 = temp("av-lib2");
        File un = AvatarBundleFile.unpack(new ByteArrayInputStream(packed.toByteArray()), lib2);
        check(un != null && un.getName().equals(dino.getName()), "unpacks under the same folder name");
        check(un != null && Arrays.equals(Files.readAllBytes(new File(un, "sheets/sheet0.png").toPath()), pngA)
                && Arrays.equals(Files.readAllBytes(new File(un, "sheets/sheet1.png").toPath()), pngB), "the pictures come back exactly");
        check(un != null && new String(Files.readAllBytes(new File(un, "avatar.json").toPath()), StandardCharsets.UTF_8).contains("\"My Dino\""), "and the rig");
        check(AvatarBundleFile.unpack(new ByteArrayInputStream(packed.toByteArray()), lib2) == null, "a character already in the app is not written over");
        String[] left = lib2.list();
        check(left != null && left.length == 1, "and no half bundle is left behind");
        // a hostile picture name is not followed out of the folder
        String evil = new String(packed.toByteArray(), StandardCharsets.UTF_8).replace("sheets/sheet1.png", "sheets/../../x.png");
        File lib3 = temp("av-lib3");
        AvatarBundleFile.unpack(new ByteArrayInputStream(evil.getBytes(StandardCharsets.UTF_8)), lib3);
        check(!new File(lib3.getParentFile(), "x.png").exists() && !new File(lib3, "x.png").exists(), "a name with .. is dropped");

        // ---- into the folder, and back after a reinstall
        File folderDir = temp("av-folder");
        JoyFolder jf = JoyFolder.open(new FileJoyFs(folderDir));
        File lib = temp("av-app");
        File dino2 = bundle(lib, "My Dino", "3fa4a1b2-0000-4000-8000-000000000001", pngA);   // made before this change
        File robot = bundle(lib, "Robot", "9c0de111-0000-4000-8000-000000000002", pngB);
        jf.index.scan(jf.fs);
        int wrote = AvatarFolderCopy.backfill(jf, lib);
        check(wrote == 2, "the back-fill copies both older characters, was " + wrote);
        jf.index.scan(jf.fs);
        Map<String, String> copies = AvatarFolderCopy.copiesByShortId(jf);
        check(copies.size() == 2 && copies.get("3fa4a1b2") != null && copies.get("3fa4a1b2").endsWith("My Dino.avatar.json"),
                "each has a readable file: " + copies);
        check(AvatarFolderCopy.backfill(jf, lib) == 0, "a second back-fill writes nothing");
        // a save later: the same file is refreshed, not a second one
        write(new File(dino2, "sheets/sheet0.png"), bytes(9, 100));
        File[] before = new File(folderDir, new File(copies.get("3fa4a1b2")).getParent() == null ? "" : new File(copies.get("3fa4a1b2")).getParent()).listFiles();
        String rel = AvatarFolderCopy.copyOne(jf, dino2, copies);
        check(rel != null && rel.equals(copies.get("3fa4a1b2")), "a later save rewrites the same file");
        File[] after = new File(folderDir, new File(rel).getParent() == null ? "" : new File(rel).getParent()).listFiles();
        check(before != null && after != null && before.length == after.length, "and adds no second file");
        // the person renamed the file in a file manager: still found by the character's id
        File renamed = new File(folderDir, rel.replace("My Dino", "Dino renamed"));
        check(new File(folderDir, rel).renameTo(renamed), "renamed by hand");
        jf.index.scan(jf.fs);
        Map<String, String> c2 = AvatarFolderCopy.copiesByShortId(jf);
        check(c2.get("3fa4a1b2") != null && c2.get("3fa4a1b2").endsWith("Dino renamed.avatar.json"), "a renamed copy is still that character");
        check(AvatarFolderCopy.copyOne(jf, dino2, c2).endsWith("Dino renamed.avatar.json"), "and is the one refreshed");

        // reinstall: an empty app library, the same folder
        File freshApp = temp("av-reinstall");
        JoyFolder jf2 = JoyFolder.open(new FileJoyFs(folderDir));
        jf2.index.scan(jf2.fs);
        int back = AvatarFolderCopy.restore(jf2, freshApp);
        check(back == 2, "both characters come back after a reinstall, was " + back);
        File d = new File(freshApp, dino.getName());
        check(d.isDirectory() && Arrays.equals(Files.readAllBytes(new File(d, "sheets/sheet0.png").toPath()), bytes(9, 100)),
                "with the picture as last saved");
        check(AvatarFolderCopy.restore(jf2, freshApp) == 0, "a second restore brings nothing");
        // never over what the app has
        write(new File(d, "sheets/sheet0.png"), bytes(5, 10));
        AvatarFolderCopy.restore(jf2, freshApp);
        check(Arrays.equals(Files.readAllBytes(new File(d, "sheets/sheet0.png").toPath()), bytes(5, 10)), "a character in the app is never overwritten");

        // ---- fonts
        File fonts = temp("fonts-app");
        write(new File(fonts, "Tommy Black.otf"), bytes(3, 40000));
        write(new File(fonts, "Quiet.ttf"), bytes(4, 3000));
        write(new File(fonts, "notes.txt"), bytes(5, 10));
        File folder2 = temp("fonts-folder");
        JoyFolder jf3 = JoyFolder.open(new FileJoyFs(folder2));
        jf3.index.scan(jf3.fs);
        check(FontFolderCopy.copyAll(jf3, fonts) == 2, "both fonts are copied, not the text file");
        File there = new File(folder2, "Supplies/Fonts");
        String[] names = there.list();
        check(names != null && names.length == 2 && new File(there, "Tommy Black.otf").isFile(), "into Supplies/Fonts under their own names");
        check(FontFolderCopy.copyAll(jf3, fonts) == 0, "a second copy writes nothing");
        // a different font with a name already used is kept beside it, never written over
        write(new File(fonts, "Quiet.ttf"), bytes(6, 3100));
        check(FontFolderCopy.copyAll(jf3, fonts) == 1, "a changed font is a new copy");
        check(new File(there, "Quiet.ttf").length() == 3000 && new File(there, "Quiet 2.ttf").length() == 3100, "the first copy is untouched");
        // reinstall
        File freshFonts = temp("fonts-reinstall");
        JoyFolder jf4 = JoyFolder.open(new FileJoyFs(folder2));
        jf4.index.scan(jf4.fs);
        int fb = FontFolderCopy.restore(jf4, freshFonts);
        check(fb == 3, "the fonts come back after a reinstall, was " + fb);
        check(Arrays.equals(Files.readAllBytes(new File(freshFonts, "Tommy Black.otf").toPath()), bytes(3, 40000)), "byte for byte, same name");
        check(FontFolderCopy.restore(jf4, freshFonts) == 0, "a second restore brings nothing");
        write(new File(freshFonts, "Tommy Black.otf"), bytes(7, 12));
        FontFolderCopy.restore(jf4, freshFonts);
        check(new File(freshFonts, "Tommy Black.otf").length() == 12, "a font in the app is never overwritten");

        System.out.println("AvatarFontCopyTest: " + passes + " passed, " + fails + " failed");
        System.exit(fails > 0 ? 1 : 0);
    }
}
