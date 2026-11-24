package cc.joycreator.textlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Looks saved while the Joy Creator folder was out of reach come home (SAFE-1).
 *
 * <p>{@code TextLabPreviews.write} keeps a look in the app's own folder when the Joy Creator folder
 * cannot be opened, tells the person it is saved, and then nothing ever read that folder again:
 * the look never reached the shelf, even once the folder was back, and an uninstall lost it.
 * This moves each such look into the folder the next time it is there, the way VisLab's
 * {@code LookFiles.bringHome} does.</p>
 *
 * <p>Careful about order, because this is somebody's work: a look is deleted from the app's own
 * folder only AFTER the copy in the Joy Creator folder has been read back and found identical (or
 * the folder already holds exactly that look). Any failure leaves the local file where it is, to be
 * tried again next time. The sprite-sheet preview beside a look is the app's own picture of it, made
 * again from the look at any time; it goes with the look it belongs to and is not copied.</p>
 *
 * <p>Pure {@code java.io}, so the JVM harness tests it ({@code run-looks-home.sh}).</p>
 */
public final class LooksComeHome {

    private LooksComeHome() {}

    static final String ENDING = ".text.json";

    /** The Joy Creator folder, as far as this needs it. */
    public interface Folder {
        /** The folder path of a look that already holds exactly these bytes, or null. */
        String holds(byte[] text) throws Exception;

        /** Write a look under a free readable name; returns the folder path written. */
        String put(String base, byte[] text) throws Exception;

        /** Read back a file of the folder. */
        byte[] read(String rel) throws Exception;
    }

    /**
     * @return the folder paths of the looks that are now in the folder because of this call
     *         (not those that were already there).
     */
    public static List<String> run(File dir, Folder folder) {
        List<String> moved = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return moved;
        for (File f : all) {
            String name = f.getName();
            if (!f.isFile() || !name.toLowerCase(Locale.ROOT).endsWith(ENDING)) continue;
            try {
                byte[] text = readFile(f);
                if (text.length == 0) continue;               // never "move" an empty file
                String have = folder.holds(text);
                if (have == null) {
                    String rel = folder.put(name.substring(0, name.length() - ENDING.length()), text);
                    byte[] back = folder.read(rel);
                    if (!Arrays.equals(back, text)) continue;   // not proven: keep the local one
                    moved.add(rel);
                }
                deleteWithPreview(f);
            } catch (Exception e) {
                // Leave it where it is; the next time the folder is there it is tried again.
            }
        }
        return moved;
    }

    private static void deleteWithPreview(File look) {
        String name = look.getName();
        String base = name.substring(0, name.length() - ENDING.length());
        //noinspection ResultOfMethodCallIgnored
        look.delete();
        File[] beside = look.getParentFile().listFiles();
        if (beside == null) return;
        for (File b : beside) {
            String n = b.getName();
            // "<base>.5x4.sprite.webp" and its older ".png": the app's own picture of this look.
            if (n.startsWith(base + ".") && n.toLowerCase(Locale.ROOT).contains(".sprite.")) {
                //noinspection ResultOfMethodCallIgnored
                b.delete();
            }
        }
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
