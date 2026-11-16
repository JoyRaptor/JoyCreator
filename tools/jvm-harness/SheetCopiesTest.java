package com.fadcam.vislab.room;

import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Codex audit 11-15, XROOM-03: deleting the Studio project a sprite sheet came from must not take
 * the pictures away from a VisLab look that plays it.
 *
 * <p>The oracle is the deletion itself: a "project" folder of pictures, a sheet linking into it,
 * {@link SheetCopies#own}, then the project folder is deleted the way ProjectStorage deletes one
 * (the whole tree). The owned sheet must still find every picture, byte for byte. Control: the
 * sheet as it was saved before the fix reports its pictures missing after the same deletion.</p>
 *
 * <p>In the package of the class it tests (SheetCopies is package-private).
 * Run: {@code bash tools/jvm-harness/run-sheetcopies.sh}</p>
 */
public class SheetCopiesTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("sheetcopies").toFile();
        try {
            survivesProjectDelete(root);
            sequenceSurvivesAndSharesCopies(root);
            leavesNonFileLinksAlone(root);
        } finally {
            deleteTree(root);
        }
        System.out.println("SheetCopiesTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else { failed++; System.out.println("FAIL: " + name); }
    }

    static File picture(File dir, String name, int seed) throws IOException {
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream o = new FileOutputStream(f)) {
            for (int i = 0; i < 3000; i++) o.write((i * 31 + seed) & 0xff);
        }
        return f;
    }

    static String link(File f) {
        return "file://" + f.getAbsoluteFile().toURI().getRawPath();   // the form Uri.fromFile gives
    }

    static boolean sameBytes(File a, File b) throws IOException {
        return Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
    }

    static void survivesProjectDelete(File root) throws IOException {
        File project = new File(root, "projects/p1/assets");
        File home = new File(root, "files/" + SheetCopies.FOLDER);
        File pic = picture(project, "walk sheet.png", 1);
        File keep = new File(root, "reference.png");
        Files.copy(pic.toPath(), keep.toPath());
        SpriteSheet s = new SpriteSheet("sheet1", "Walk", link(pic));
        JsonObject saved = s.toJson();

        JsonObject owned = SheetCopies.own(home, saved);
        String nowLink = SpriteSheet.fromJson(owned).getSheetUri();
        File copy = SheetCopies.fileOf(nowLink);
        check("sheet: the look now links into VisLab's own folder",
                copy != null && copy.getAbsolutePath().startsWith(home.getAbsolutePath()));
        check("sheet: owning twice changes nothing", SheetCopies.own(home, owned) == owned);

        deleteTree(new File(root, "projects/p1"));
        check("control: the old link is missing once the project is deleted", SheetCopies.missing(saved));
        check("sheet: nothing missing after the project is deleted", !SheetCopies.missing(owned));
        check("sheet: the copy is the same picture", copy != null && copy.isFile() && sameBytes(copy, keep));
        check("sheet: the rest of the sheet is unchanged",
                "Walk".equals(SpriteSheet.fromJson(owned).getName())
                        && "sheet1".equals(SpriteSheet.fromJson(owned).getId()));
    }

    static void sequenceSurvivesAndSharesCopies(File root) throws IOException {
        File project = new File(root, "projects/p2/media");
        File home = new File(root, "files/" + SheetCopies.FOLDER);
        List<String> frames = new ArrayList<>();
        for (int i = 0; i < 5; i++) frames.add(link(picture(project, "f_" + i + ".png", 10 + i)));
        frames.add(frames.get(1));   // a frame used twice
        SpriteSheet s = new SpriteSheet("seq", "Run", "");
        s.setSequenceFrames(frames);
        JsonObject owned = SheetCopies.own(home, s.toJson());
        List<String> got = SpriteSheet.fromJson(owned).getFrameUris();
        check("sequence: every frame owned", got.size() == 6);
        check("sequence: a picture used twice is copied once", got.get(1).equals(got.get(5)));
        int before = home.list() == null ? 0 : home.list().length;
        SheetCopies.own(home, s.toJson());
        int after = home.list() == null ? 0 : home.list().length;
        check("sequence: picking the same sheet again makes no new copies", before == after);
        deleteTree(new File(root, "projects/p2"));
        check("sequence: nothing missing after the project is deleted", !SheetCopies.missing(owned));
        boolean noParts = true;
        for (String n : home.list()) if (n.endsWith(".part")) noParts = false;
        check("no half-written copies left behind", noParts);
    }

    static void leavesNonFileLinksAlone(File root) {
        File home = new File(root, "files/" + SheetCopies.FOLDER);
        SpriteSheet s = new SpriteSheet("c", "Picked", "content://media/external/images/1");
        JsonObject j = s.toJson();
        check("content link: left alone", SheetCopies.own(home, j) == j);
        check("content link: not called missing", !SheetCopies.missing(j));
        check("no sheet: not missing", !SheetCopies.missing(null));
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
