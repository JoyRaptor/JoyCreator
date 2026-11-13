package cc.joycreator.textlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The lobby's TextLab hero: a still of the look the person left the room on, its name and the shelf key to open it
 * again (as VisLab's {@code LobbyHero} does for its looks).
 *
 * <p>Beside the still, once the person leaves the room, its moving preview: the same base name ending in the
 * Library's sheet name ({@code hero-1731500000000.jpg} → {@code hero-1731500000000.5x4.sprite.webp}), which the lobby
 * finds by name and plays ({@code ui/lobby/LobbyHeroMotion}). It is made on leaving, never while the person is still
 * turning sliders, so it never competes with the live room for the graphics chip. Without one the hero is the still.</p>
 */
public final class TlLobbyHero {

    private static final String TAG = "TextLab";
    private static final String PREFS = "textlab";
    private static final String NAME = "hero_name", KEY = "hero_key", IMAGE = "hero_image", LOOK = "hero_look",
            WORDS = "hero_words";
    /** The moving preview is not kept above this (the lobby's limit). */
    private static final long MAX_SHEET = 8L * 1024 * 1024;
    /** True once the person has left the room: a still saved after that gets its moving preview at once. */
    private static volatile boolean left;

    /** What the hero shows. */
    public static final class Shown {
        @NonNull public final String name, key, image;

        Shown(@NonNull String name, @NonNull String key, @NonNull String image) {
            this.name = name;
            this.key = key;
            this.image = image;
        }
    }

    private TlLobbyHero() {}

    /**
     * Any thread: keep this still (it is recycled) as the hero, under a new file name (the lobby caches by path), with
     * the look and words it shows (for its moving preview). The old still and its moving preview are deleted.
     */
    static void save(@NonNull Context c, @NonNull Bitmap b, @NonNull String name, @NonNull String key,
                     @NonNull String lookJson, @NonNull String words) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            File dir = new File(app.getFilesDir(), "textlab");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File out = new File(dir, "hero-" + System.currentTimeMillis() + ".jpg");
            try (OutputStream o = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.JPEG, 88, o);
            } catch (Exception e) {
                return;
            } finally {
                b.recycle();
            }
            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String old = p.getString(IMAGE, null);
            p.edit().putString(NAME, name).putString(KEY, key).putString(IMAGE, out.getAbsolutePath())
                    .putString(LOOK, lookJson).putString(WORDS, words).apply();
            if (old != null && !old.equals(out.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                new File(old).delete();
                for (File s : sheetsBeside(new File(old))) //noinspection ResultOfMethodCallIgnored
                    s.delete();
            }
            if (left) makeMoving(app);
        }, "textlab-hero").start();
    }

    /** The room is in front again: hold the moving preview until the person leaves. */
    static void entered() {
        left = false;
    }

    /** The person left the room: give the hero its moving preview (made once, off the main thread). */
    static void leaving(@NonNull Context c) {
        left = true;
        makeMoving(c.getApplicationContext());
    }

    /** The hero still's moving preview, made from the look it shows and copied beside it, when it is not there yet. */
    private static void makeMoving(@NonNull Context app) {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String image = p.getString(IMAGE, null), look = p.getString(LOOK, null), words = p.getString(WORDS, null);
        if (image == null || look == null || words == null) return;
        File still = new File(image);
        File target = sheetBeside(still);
        if (!still.exists() || target.exists()) return;
        JSONObject lookObj;
        try {
            lookObj = new JSONObject(look);
        } catch (Exception e) {
            return;
        }
        TextLabPreviews.get(app).chip(lookObj, words, sheet -> {
            if (sheet == null) return;
            new Thread(() -> {
                // the hero may have moved on meanwhile: only the still it was made for gets it
                if (!image.equals(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(IMAGE, null))) return;
                if (sheet.length() > MAX_SHEET || target.exists()) return;
                File part = new File(target.getPath() + ".part");
                try (InputStream in = new FileInputStream(sheet); OutputStream o = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
                } catch (Exception e) {
                    Log.w(TAG, "the lobby's moving preview was not kept", e);
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    return;
                }
                if (!part.renameTo(target)) //noinspection ResultOfMethodCallIgnored
                    part.delete();
                if (!still.exists()) //noinspection ResultOfMethodCallIgnored
                    target.delete();   // the still went while copying: its preview goes with it
            }, "textlab-hero-sheet").start();
        });
    }

    /** {@code hero-1.jpg} → {@code hero-1.5x4.sprite.webp}, in the same folder. */
    @NonNull
    static File sheetBeside(@NonNull File still) {
        return new File(still.getParentFile(), LookPreview.sheetName(base(still)));
    }

    @NonNull
    private static File[] sheetsBeside(@NonNull File still) {
        String[] names = LookPreview.sheetNames(base(still));
        File[] out = new File[names.length];
        for (int i = 0; i < names.length; i++) out[i] = new File(still.getParentFile(), names[i]);
        return out;
    }

    @NonNull
    private static String base(@NonNull File still) {
        String n = still.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** The last look's still, name and key, or null before the room has been visited. */
    @Nullable
    public static Shown read(@NonNull Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String image = p.getString(IMAGE, null), name = p.getString(NAME, null);
        if (image == null || name == null || !new File(image).exists()) return null;
        return new Shown(name, p.getString(KEY, ""), image);
    }
}
