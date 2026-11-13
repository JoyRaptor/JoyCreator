package cc.joycreator.textlab;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.joyfolder.FileKind;
import com.fadcam.joyfolder.Fingerprint;
import com.fadcam.joyfolder.JoyFolder;
import com.fadcam.joyfolder.JoyFs;
import com.fadcam.joyfolder.Placement;
import com.fadcam.joyfolder.ReadableNames;
import com.fadcam.joyfolder.android.JoyFolderHome;
import com.fadcam.vislab.studio.OffscreenGl;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;

/**
 * Look previews (owner, 2026-11-12): every look is picked by its picture and browsed moving.
 *
 * <ul>
 *   <li>{@link #chip}: a built-in look's sheet, made once into the app's cache (remade after an app update).</li>
 *   <li>{@link #save}: a look the person saves goes to {@code Supplies/Looks/} as {@code <Name>.text.json} with
 *       its moving preview {@code <Name>.5x4.sprite.webp} beside it (older ones: {@code .png}); the Library plays it.</li>
 *   <li>{@link #remake}: a look file whose preview is missing or older than the look gets a new one (a look
 *       edited by hand, or copied in without its picture).</li>
 * </ul>
 *
 * <p>Pictures are drawn on a GL thread of their own (VisLab's {@link OffscreenGl}), never the screen's, one at
 * a time; folder work runs on {@link JoyFolderHome#io()}. Callbacks come back on the main thread.</p>
 */
public final class TextLabPreviews {

    private static final String TAG = "TextLab";
    private static final String TEXT_ENDING = ".text.json";
    private static TextLabPreviews instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private OffscreenGl gl;
    private TextLabRenderer renderer;
    /** Looks whose preview is being made now: a Library read meanwhile does not ask twice. */
    private final java.util.Set<String> making = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private TextLabPreviews(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    @NonNull
    public static synchronized TextLabPreviews get(@NonNull Context c) {
        if (instance == null) instance = new TextLabPreviews(c);
        return instance;
    }

    private synchronized Handler glThread() {
        if (gl == null) gl = new OffscreenGl();
        return gl.handler();
    }

    /** GL thread: the sheet, or null when this phone cannot draw it. */
    @Nullable
    private Bitmap draw(@NonNull JSONObject look, @NonNull String words) {
        try {
            if (!gl.ready()) {
                Log.w(TAG, "preview not made: the GL thread could not start");
                return null;
            }
            if (renderer == null) renderer = new TextLabRenderer(TextLabRenderer.Assets.load(app.getAssets()), app);
            long t0 = System.nanoTime();
            Bitmap b = LookPreview.make(renderer, look, words);
            Log.i(TAG, "preview " + look.optString("id") + " made in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            return b;
        } catch (Exception | Error e) {
            Log.w(TAG, "preview failed for " + look.optString("id"), e);
            return null;
        }
    }

    // ---- chips: the built-in looks, cached ------------------------------------------------------

    /** A built-in look's sheet in the cache (made now if it is not there yet); null if it could not be drawn. */
    public void chip(@NonNull JSONObject look, @NonNull String words, @NonNull Consumer<File> done) {
        File dir = new File(app.getCacheDir(), "textlab/previews");
        String key = look.optString("id", "look") + "." + hash(look.toString() + "|" + words + "|" + engineStamp());
        File f = new File(dir, LookPreview.sheetName(key));
        if (f.exists()) {
            done.accept(f);
            return;
        }
        glThread().post(() -> {
            Bitmap b = draw(look, words);
            File out = null;
            if (b != null) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File tmp = new File(dir, f.getName() + ".part");
                try (FileOutputStream os = new FileOutputStream(tmp)) {
                    os.write(LookPreview.webp(b));
                    os.close();
                    if (tmp.renameTo(f)) out = f;
                } catch (Exception e) {
                    Log.w(TAG, "preview not cached", e);
                }
                b.recycle();
            }
            File result = out;
            main.post(() -> done.accept(result));
        });
    }

    private String engineStamp;

    /**
     * Changes only when what draws a look changes (the shader, knob and tube files): an app update that
     * leaves them alone keeps every chip picture, one that changes them remakes them.
     */
    private synchronized String engineStamp() {
        if (engineStamp != null) return engineStamp;
        StringBuilder all = new StringBuilder();
        try {
            String[] names = app.getAssets().list("textlab/shaders");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names) all.append(n).append(com.fadcam.vislab.gl.ShaderLib.readAll(app.getAssets().open("textlab/shaders/" + n)));
            }
            all.append(com.fadcam.vislab.gl.ShaderLib.readAll(app.getAssets().open("textlab/parts.json")));
            all.append(com.fadcam.vislab.gl.ShaderLib.readAll(app.getAssets().open("textlab/tubefont.json")));
        } catch (Exception e) {
            Log.w(TAG, "engine files not read for the preview key", e);
        }
        engineStamp = hash(all.toString());
        return engineStamp;
    }

    // ---- saving a look ----------------------------------------------------------------------------

    /** What a save did: where the look went, or why it could not. */
    public static final class Saved {
        @Nullable public final String where;
        @Nullable public final String problem;

        Saved(@Nullable String where, @Nullable String problem) {
            this.where = where;
            this.problem = problem;
        }
    }

    /**
     * Save {@code look} as the person's own: {@code <name>.text.json} (the words kept as its sample) and its
     * moving preview beside it, in the Joy Creator folder's {@code Supplies/Looks/}. A name already taken gets
     * " 2", " 3" (the folder's own rule). Without a reachable folder it goes to the app's own folder.
     */
    public void save(@NonNull JSONObject look, @NonNull String words, @NonNull String name,
                     @NonNull Consumer<Saved> done) {
        final JSONObject mine;
        try {
            mine = new JSONObject(look.toString());
            mine.put("from", look.optString("id"));
            mine.put("name", name);
            mine.put("sample", words);
        } catch (Exception e) {
            done.accept(new Saved(null, e.toString()));
            return;
        }
        // ahead of any chip pictures still being made (the person is waiting on this one), but never ahead
        // of the GL thread's own start-up: a save on a fresh start once ran before it and lost its picture
        Handler h = glThread();
        h.postAtFrontOfQueue(() -> {
            if (!gl.ready()) {
                h.post(() -> saveNow(mine, words, done));
                return;
            }
            saveNow(mine, words, done);
        });
    }

    /** GL thread: draw the sheet, then write both files on the folder thread. */
    private void saveNow(@NonNull JSONObject mine, @NonNull String words, @NonNull Consumer<Saved> done) {
        String name = mine.optString("name");
        Bitmap b = draw(mine, words);
        JoyFolderHome.io().execute(() -> {
            Saved s;
            try {
                s = new Saved(write(mine, name, b), null);
                Log.i(TAG, "saved " + s.where + (b == null ? " (no picture yet: the Library will make it)" : " with its picture"));
            } catch (Exception e) {
                Log.w(TAG, "look not saved", e);
                s = new Saved(null, e.getMessage());
            }
            if (b != null) b.recycle();
            Saved r = s;
            main.post(() -> done.accept(r));
        });
    }

    /** io thread: write the look and its sheet. Returns where the look is, for the person. */
    @NonNull
    private String write(@NonNull JSONObject look, @NonNull String name, @Nullable Bitmap sheet) throws Exception {
        String base = ReadableNames.sanitize(name).trim();
        if (base.isEmpty()) base = "Text look";
        // readable by hand: org.json writes every slash with a backslash before it, which no person would
        byte[] text = look.toString(2).replace("\\/", "/").getBytes(StandardCharsets.UTF_8);
        byte[] sheetBytes = sheet != null ? LookPreview.webp(sheet) : null;
        JoyFolder jf = JoyFolderHome.open(app);
        if (jf == null) {
            File dir = new File(app.getFilesDir(), "textlab/looks");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = freeFile(dir, base);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(text);
            }
            if (sheetBytes != null) {
                try (FileOutputStream os = new FileOutputStream(new File(dir, LookPreview.sheetName(FileKind.baseOf(f.getName()))))) {
                    os.write(sheetBytes);
                }
            }
            Log.w(TAG, "Joy Creator folder unreachable; look kept in the app's own folder: " + f);
            return app.getString(com.fadcam.R.string.textlab_looks_own_folder);
        }
        String dir = jf.prepareDir(Placement.What.SUPPLY, null, null, FileKind.LOOK);
        String file = jf.freeName(dir, base + TEXT_ENDING);
        String rel = Placement.join(dir, file);
        put(jf, rel, text);
        if (sheetBytes != null) putSheet(jf, dir, FileKind.baseOf(file), sheetBytes);
        jf.saveIndex();
        return rel;
    }

    private static void put(@NonNull JoyFolder jf, @NonNull String rel, @NonNull byte[] data) throws Exception {
        jf.writeAtomic(rel, data);
        JoyFs.Entry e = jf.fs.stat(rel);
        if (e != null) jf.index.put(rel, e.size, e.modified, Fingerprint.of(jf.fs, rel), null);
    }

    /**
     * A look's moving preview, written as WebP; an older PNG preview of the same look beside it is then removed
     * (it is the app's own picture of the look, made again from the look at any time, never the person's work).
     */
    private static void putSheet(@NonNull JoyFolder jf, @NonNull String dir, @NonNull String base, @NonNull byte[] webp)
            throws Exception {
        String[] names = LookPreview.sheetNames(base);
        put(jf, Placement.join(dir, names[0]), webp);
        for (int i = 1; i < names.length; i++) {
            String old = Placement.join(dir, names[i]);
            if (jf.fs.stat(old) != null && jf.fs.delete(old)) jf.index.remove(old);
        }
    }

    @NonNull
    private static File freeFile(@NonNull File dir, @NonNull String base) {
        File f = new File(dir, base + TEXT_ENDING);
        for (int n = 2; f.exists(); n++) f = new File(dir, base + " " + n + TEXT_ENDING);
        return f;
    }

    // ---- previews for looks already in the folder ------------------------------------------------

    /**
     * io thread or any: give each of these looks (folder paths of {@code .text.json} files) a fresh moving
     * preview beside it. One at a time, in the background; the Library shows them the next time it reads.
     */
    public void remake(@NonNull List<String> lookRels) {
        if (lookRels.isEmpty()) return;
        JoyFolderHome.io().execute(() -> {
            JoyFolder jf = JoyFolderHome.open(app);
            if (jf == null) return;
            for (String rel : lookRels) {
                if (!making.add(rel)) continue;
                try {
                    JSONObject look = new JSONObject(new String(jf.readAll(rel), StandardCharsets.UTF_8));
                    String words = look.optString("sample", FileKind.baseOf(Placement.nameOf(rel)));
                    glThread().post(() -> {
                        Bitmap b = draw(look, words);
                        if (b == null) {
                            making.remove(rel);
                            return;
                        }
                        JoyFolderHome.io().execute(() -> {
                            try {
                                String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
                                putSheet(jf, dir, FileKind.baseOf(Placement.nameOf(rel)), LookPreview.webp(b));
                                jf.saveIndex();
                            } catch (Exception e) {
                                Log.w(TAG, "preview not written for " + rel, e);
                            }
                            b.recycle();
                            making.remove(rel);
                        });
                    });
                } catch (Exception e) {
                    making.remove(rel);
                    Log.w(TAG, "look not read for its preview: " + rel, e);
                }
            }
        });
    }

    @NonNull
    private static String hash(@NonNull String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
