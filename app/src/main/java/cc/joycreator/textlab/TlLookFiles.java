package cc.joycreator.textlab;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.Fingerprint;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A person's own looks in {@code Supplies/Looks/} (the owner's supplies rule: the user's copy wins, the factory
 * copy stays in the app, and "back to the original" is one step away):
 *
 * <ul>
 *   <li>{@link #save}: the look as it is now over the one being edited. Their own look is written in place; an edited
 *       built-in becomes their own copy under the same name, marked {@code "replaces": <id>}, which then stands in
 *       the built-in's place on every shelf ({@link #merged}); a pack's look is saved as a new look of their own.</li>
 *   <li>{@link #rename}, {@link #delete} (to the folder's Trash, 30 days), {@link #revert} (their copy of a built-in
 *       to Trash: the original shows again).</li>
 * </ul>
 * Every call is on the folder thread ({@link JoyFolderHome#io()}); each returns a {@link Done} for the person.
 */
public final class TlLookFiles {

    private static final String TAG = "TextLab";
    static final String DIR = "Supplies/" + FileKind.LOOK.suppliesFolder;
    static final String ENDING = ".text.json";
    /** On an own copy of a built-in look: the built-in's id. */
    public static final String REPLACES = "replaces";

    /** What happened, in words for the person; {@code rel} is the look's path when there is one now. */
    public static final class Done {
        @Nullable public final String rel, problem;

        Done(@Nullable String rel, @Nullable String problem) {
            this.rel = rel;
            this.problem = problem;
        }

        public boolean ok() {
            return problem == null;
        }
    }

    private TlLookFiles() {}

    /**
     * The shelf as a person sees it: built-in looks in order, each replaced in place by their own copy of it; then
     * every other look in the folder (packs, their own).
     */
    @NonNull
    public static List<TextLookShelf.Item> merged(@NonNull List<TextLookShelf.Item> builtIn,
                                                  @NonNull List<TextLookShelf.Item> folder) {
        List<TextLookShelf.Item> out = new ArrayList<>();
        List<TextLookShelf.Item> rest = new ArrayList<>(folder);
        for (TextLookShelf.Item b : builtIn) {
            TextLookShelf.Item mine = null;
            for (TextLookShelf.Item f : rest) {
                if (f.from == TextLookShelf.From.MINE && b.look.optString("id").equals(f.look.optString(REPLACES))) {
                    mine = f;
                    break;
                }
            }
            if (mine != null) rest.remove(mine);
            out.add(mine != null ? mine : b);
        }
        out.addAll(rest);
        return out;
    }

    /** True when this is the person's own copy of a built-in look (it can go back to the original). */
    public static boolean isCopyOfBuiltIn(@NonNull TextLookShelf.Item it) {
        return it.from == TextLookShelf.From.MINE && !it.look.optString(REPLACES).isEmpty();
    }

    /** Save {@code look} (with its words as the sample) over {@code editing}. */
    @WorkerThread
    @NonNull
    public static Done save(@NonNull Context c, @NonNull TextLookShelf.Item editing, @NonNull JSONObject look,
                            @NonNull String words) {
        try {
            JoyFolder jf = folder(c);
            JSONObject out = new JSONObject(look.toString());
            out.put("sample", words);
            String rel;
            if (editing.from == TextLookShelf.From.MINE) {
                rel = editing.path;
            } else if (editing.from == TextLookShelf.From.BUILT_IN) {
                out.put(REPLACES, look.optString("id", editing.look.optString("id")));
                out.put("name", editing.name);
                rel = Placement.join(DIR, jf.freeName(prepare(jf), ReadableNames.sanitize(editing.name) + ENDING));
            } else {
                out.put("from", editing.look.optString("id"));
                out.put("name", editing.name + " (mine)");
                rel = Placement.join(DIR, jf.freeName(prepare(jf), ReadableNames.sanitize(editing.name + " (mine)") + ENDING));
            }
            write(jf, rel, out);
            TextLabPreviews.get(c).remake(Collections.singletonList(rel));
            return new Done(rel, null);
        } catch (Exception e) {
            Log.w(TAG, "look not saved", e);
            return new Done(null, "Not saved: " + e.getMessage());
        }
    }

    /** Give an own look a new name: its file and its moving preview follow. */
    @WorkerThread
    @NonNull
    public static Done rename(@NonNull Context c, @NonNull TextLookShelf.Item mine, @NonNull String name) {
        if (mine.from != TextLookShelf.From.MINE) return new Done(null, "Only your own looks can be renamed.");
        String clean = ReadableNames.sanitize(name).trim();
        if (clean.isEmpty()) return new Done(null, "A look needs a name.");
        try {
            JoyFolder jf = folder(c);
            String dir = Placement.parentOf(mine.path);
            String file = jf.freeName(dir, clean + ENDING);
            String rel = Placement.join(dir, file);
            JSONObject look = new JSONObject(mine.look.toString());
            look.put("name", clean);
            write(jf, rel, look);
            String oldSheet = Placement.join(dir, LookPreview.sheetName(FileKind.baseOf(Placement.nameOf(mine.path))));
            String newSheet = LookPreview.sheetName(FileKind.baseOf(file));
            if (jf.fs.stat(oldSheet) != null) {
                if (jf.fs.rename(oldSheet, newSheet)) {
                    jf.index.remove(oldSheet);
                    index(jf, Placement.join(dir, newSheet));
                }
            }
            jf.fs.delete(mine.path);
            jf.index.remove(mine.path);
            jf.saveIndex();
            return new Done(rel, null);
        } catch (Exception e) {
            Log.w(TAG, "look not renamed", e);
            return new Done(null, "Not renamed: " + e.getMessage());
        }
    }

    /** An own look and its preview to the folder's Trash (kept 30 days). */
    @WorkerThread
    @NonNull
    public static Done delete(@NonNull Context c, @NonNull TextLookShelf.Item mine) {
        if (mine.from != TextLookShelf.From.MINE) return new Done(null, "Only your own looks can be deleted.");
        try {
            JoyFolder jf = folder(c);
            long now = System.currentTimeMillis();
            String sheet = Placement.join(Placement.parentOf(mine.path),
                    LookPreview.sheetName(FileKind.baseOf(Placement.nameOf(mine.path))));
            jf.trash(mine.path, now);
            if (jf.fs.stat(sheet) != null) jf.trash(sheet, now);
            jf.saveIndex();
            return new Done(null, null);
        } catch (Exception e) {
            Log.w(TAG, "look not deleted", e);
            return new Done(null, "Not deleted: " + e.getMessage());
        }
    }

    /** Back to the original: the person's copy of a built-in look goes to Trash, so the built-in shows again. */
    @WorkerThread
    @NonNull
    public static Done revert(@NonNull Context c, @NonNull TextLookShelf.Item mine) {
        if (!isCopyOfBuiltIn(mine)) return new Done(null, "This look has no original to go back to.");
        return delete(c, mine);
    }

    // ---- folder -------------------------------------------------------------------------------------

    @NonNull
    private static JoyFolder folder(@NonNull Context c) throws java.io.IOException {
        JoyFolder jf = JoyFolderHome.open(c);
        if (jf == null) throw new java.io.IOException("the Joy Creator folder could not be reached");
        return jf;
    }

    @NonNull
    private static String prepare(@NonNull JoyFolder jf) throws java.io.IOException {
        return jf.prepareDir(Placement.What.SUPPLY, null, null, FileKind.LOOK);
    }

    private static void write(@NonNull JoyFolder jf, @NonNull String rel, @NonNull JSONObject look) throws Exception {
        // readable by hand: org.json writes every slash with a backslash before it, which no person would
        jf.writeAtomic(rel, look.toString(2).replace("\\/", "/").getBytes(StandardCharsets.UTF_8));
        index(jf, rel);
        jf.saveIndex();
    }

    private static void index(@NonNull JoyFolder jf, @NonNull String rel) throws java.io.IOException {
        JoyFs.Entry e = jf.fs.stat(rel);
        if (e != null) jf.index.put(rel, e.size, e.modified, Fingerprint.of(jf.fs, rel), null);
    }
}
