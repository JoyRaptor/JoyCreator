package cc.joycreator.textlab;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.JoyPack;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.vislab.gl.ShaderLib;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Every text look a room may offer, in one list (job PACK-1): the built-in packs ({@code assets/packs/}, minus any
 * held back for a later wave), the packs the person installed ({@code Supplies/Packs/<pack>/}), and the person's
 * own looks ({@code Supplies/Looks/*.text.json}). The TextLab screen's chips and Studio's carousel read it.
 *
 * <p>The TextLab lead's rulings (11-12): only {@code looks} from {@code index.json}, never drafts; a look a pack
 * does not list still appears (under "TextLab"), so a new look is never lost. A look is opaque data: rooms keep it
 * whole and set knobs by name.</p>
 */
public final class TextLookShelf {

    private static final String TAG = "TextLab";
    public static final String PACKS_DIR = "Supplies/Packs";

    /** Where a look on the shelf came from. */
    public enum From { BUILT_IN, PACK, MINE }

    /** One look on the shelf. */
    public static final class Item {
        public final String key;          // stable: "asset:<path>" or "folder:<path>"
        public final String name;
        public final String pack;         // the pack's name ("Film titles"), or "My looks"
        public final From from;
        public final JSONObject look;
        /** Its path: inside the app's assets, or inside the Joy Creator folder. */
        public final String path;

        public Item(String key, String name, String pack, From from, JSONObject look, String path) {
            this.key = key;
            this.name = name;
            this.pack = pack;
            this.from = from;
            this.look = look;
            this.path = path;
        }
    }

    private TextLookShelf() {}

    /** The built-in looks, pack by pack. Fast (small files in the app): any thread. */
    @NonNull
    public static List<Item> builtIn(@NonNull Context c) {
        List<Item> out = new ArrayList<>();
        Set<String> finished = new LinkedHashSet<>();
        try {
            JSONObject index = new JSONObject(read(c, "textlab/looks/index.json"));
            JSONArray l = index.optJSONArray("looks");   // drafts are never offered
            for (int i = 0; l != null && i < l.length(); i++) finished.add("textlab/looks/" + l.optString(i));
        } catch (Exception e) {
            Log.w(TAG, "look index not read", e);
            return out;
        }
        Set<String> listed = new HashSet<>();
        try {
            String[] names = c.getAssets().list("packs");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) {
                    if (!n.endsWith(".joypack.json")) continue;
                    JoyPack p;
                    try {
                        p = JoyPack.parse(read(c, "packs/" + n));
                    } catch (Exception e) {
                        Log.w(TAG, "built-in pack not read: " + n, e);
                        continue;
                    }
                    for (JoyPack.Entry e : p.of("text-look")) listed.add(e.file);   // held packs still claim their looks
                    if (p.held || !p.usable()) continue;
                    for (JoyPack.Entry e : p.of("text-look")) {
                        if (!finished.contains(e.file)) continue;
                        Item it = assetItem(c, e.file, p.name);
                        if (it != null) out.add(it);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "built-in packs not read", e);
        }
        for (String f : finished) {
            if (listed.contains(f)) continue;
            Item it = assetItem(c, f, "TextLab");
            if (it != null) out.add(it);
        }
        return out;
    }

    @Nullable
    private static Item assetItem(@NonNull Context c, @NonNull String path, @NonNull String pack) {
        try {
            JSONObject look = new JSONObject(read(c, path));
            return new Item("asset:" + path, look.optString("name", look.optString("id")), pack, From.BUILT_IN, look, path);
        } catch (Exception e) {
            Log.w(TAG, "look not read: " + path, e);
            return null;
        }
    }

    /**
     * The looks in the Joy Creator folder: installed packs, then the person's own. Folder thread
     * ({@link JoyFolderHome#io()}); an empty list when the folder is out of reach.
     */
    @WorkerThread
    @NonNull
    public static List<Item> inFolder(@NonNull Context c) {
        List<Item> out = new ArrayList<>();
        JoyFolder jf = JoyFolderHome.open(c);
        if (jf == null) return out;
        try {
            if (jf.fs.stat(PACKS_DIR) != null) {
                List<JoyFs.Entry> packs = jf.fs.list(PACKS_DIR);
                packs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                for (JoyFs.Entry d : packs) {
                    if (!d.dir) continue;
                    String top = Placement.join(PACKS_DIR, d.name);
                    try {
                        JoyPack p = JoyPack.parse(text(jf, Placement.join(top, JoyPack.MANIFEST)));
                        if (!p.usable()) continue;
                        for (JoyPack.Entry e : p.of("text-look")) {
                            String rel = Placement.join(top, e.file);
                            try {
                                JSONObject look = new JSONObject(text(jf, rel));
                                out.add(new Item("folder:" + rel, look.optString("name", FileKind.baseOf(Placement.nameOf(rel))),
                                        p.name, From.PACK, look, rel));
                            } catch (Exception ex) {
                                Log.w(TAG, "pack look not read: " + rel, ex);
                            }
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "pack not read: " + top, ex);
                    }
                }
            }
            String mine = "Supplies/" + FileKind.LOOK.suppliesFolder;
            if (jf.fs.stat(mine) != null) {
                List<JoyFs.Entry> files = jf.fs.list(mine);
                files.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                for (JoyFs.Entry f : files) {
                    if (f.dir || !f.name.toLowerCase(Locale.ROOT).endsWith(".text.json")) continue;
                    String rel = Placement.join(mine, f.name);
                    try {
                        JSONObject look = new JSONObject(text(jf, rel));
                        out.add(new Item("folder:" + rel, FileKind.baseOf(f.name), "My looks", From.MINE, look, rel));
                    } catch (Exception ex) {
                        Log.w(TAG, "own look not read: " + rel, ex);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "folder looks not read", e);
        }
        return out;
    }

    /** A look by its key (asset or folder), or null. Folder ones: folder thread. */
    @WorkerThread
    @Nullable
    public static JSONObject load(@NonNull Context c, @NonNull String key) {
        try {
            if (key.startsWith("asset:")) return new JSONObject(read(c, key.substring(6)));
            if (key.startsWith("folder:")) {
                JoyFolder jf = JoyFolderHome.open(c);
                return jf == null ? null : new JSONObject(text(jf, key.substring(7)));
            }
        } catch (Exception e) {
            Log.w(TAG, "look not loaded: " + key, e);
        }
        return null;
    }

    private static String read(@NonNull Context c, @NonNull String asset) throws java.io.IOException {
        return ShaderLib.readAll(c.getAssets().open(asset));
    }

    private static String text(@NonNull JoyFolder jf, @NonNull String rel) throws java.io.IOException {
        return new String(jf.readAll(rel), StandardCharsets.UTF_8);
    }
}
