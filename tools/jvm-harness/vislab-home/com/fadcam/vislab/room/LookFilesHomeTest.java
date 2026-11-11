package com.fadcam.vislab.room;

import com.fadcam.joyfolder.FileJoyFs;
import com.fadcam.joyfolder.JoyFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Work-loss audit 2026-11-11: looks made on a day the Joy Creator folder was out of reach come
 * home to the folder the next time it is there (LookFiles.bringHome).
 */
public class LookFilesHomeTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static void write(File f, String text) throws Exception {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        // The app's own folder, as a day without the Joy Creator folder left it.
        File appFiles = Files.createTempDirectory("vislab-app").toFile();
        File away = new File(appFiles, LookFiles.FALLBACK_DIR);
        String builtIn = "{\"id\":\"outrun\",\"name\":\"Outrun\"}\n";
        String mine = "{\"id\":\"calm-ocean\",\"name\":\"Calm Ocean\",\"made\":\"that day\"}\n";
        String edited = "{\"id\":\"outrun\",\"name\":\"Outrun\",\"glow\":0.2}\n";
        String part = "{\"name\":\"Fire Aurora\",\"layer\":{}}";
        write(new File(away, "Outrun.vis.json"), builtIn);            // an untouched built-in copy
        write(new File(away, "Calm Ocean.vis.json"), mine);           // a look the person made
        write(new File(away, "Outrun edit.vis.json"), edited);        // a built-in they changed
        write(new File(away, "Fire Aurora.vispart.json"), part);      // a part they saved
        LookFiles.Home fallback = new LookFiles.Home(new FileJoyFs(appFiles), null, LookFiles.FALLBACK_DIR);

        // The Joy Creator folder, which already has a look called Calm Ocean (another one).
        File folderDir = Files.createTempDirectory("vislab-folder").toFile();
        JoyFolder jf = JoyFolder.open(new FileJoyFs(folderDir));
        // Where looks live in the folder: the same rule LookFiles.home() uses.
        String looksDir = com.fadcam.joyfolder.Placement.dirFor(com.fadcam.joyfolder.Placement.What.SUPPLY,
                null, null, com.fadcam.joyfolder.FileKind.LOOK, jf.roles).dir;
        LookFiles.Home probe = new LookFiles.Home(jf.fs, jf, looksDir);
        String older = "{\"id\":\"calm-ocean\",\"name\":\"Calm Ocean\",\"made\":\"last week\"}\n";
        write(new File(folderDir, looksDir + "/Calm Ocean.vis.json"), older);

        final Set<String> pristine = new HashSet<>();
        pristine.add(builtIn);
        int came = LookFiles.bringHome(fallback, probe, f -> pristine.contains(f.text));
        check(came == 3, "the person's two looks and their part come home, was " + came);

        File looks = new File(folderDir, looksDir);
        Set<String> texts = new HashSet<>();
        String[] names = looks.list();
        for (String n : names == null ? new String[0] : names) texts.add(read(new File(looks, n)));
        check(texts.contains(mine), "the look made that day is in the folder");
        check(texts.contains(edited), "the changed built-in is in the folder");
        check(texts.contains(part), "the part is in the folder");
        check(texts.contains(older), "the look already in the folder was not written over");
        check(!texts.contains(builtIn), "an untouched built-in copy is not brought in");
        check(read(new File(looks, "Calm Ocean.vis.json")).equals(older), "the name already taken keeps its file");
        check(new File(looks, "Calm Ocean 2.vis.json").isFile(), "the newcomer gets a free name");

        String[] left = away.list();
        check(left == null || left.length == 0, "nothing is left in the app's own folder");

        // A second run finds nothing to do and duplicates nothing.
        int again = LookFiles.bringHome(fallback, probe, f -> false);
        String[] after = looks.list();
        check(again == 0, "a second run brings nothing");
        check(after != null && names != null && after.length == names.length, "and adds no files");

        // A look whose exact text the folder already holds is not copied a second time.
        write(new File(away, "Copy.vis.json"), mine);
        int dup = LookFiles.bringHome(fallback, probe, f -> false);
        check(dup == 0, "the same look is not brought in twice");
        check(!new File(away, "Copy.vis.json").exists(), "and its stray copy is cleared");

        // With no Joy Creator folder there is nowhere to bring them: nothing is touched.
        write(new File(away, "Later.vis.json"), "{\"id\":\"later\"}\n");
        int none = LookFiles.bringHome(fallback, fallback, f -> false);
        check(none == 0 && new File(away, "Later.vis.json").isFile(), "no folder: the look stays where it is");

        System.out.println("LookFilesHomeTest: " + passes + " passed, " + fails + " failed");
        System.exit(fails > 0 ? 1 : 0);
    }
}
