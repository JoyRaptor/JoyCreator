package cc.joycreator.textlab;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * The lobby's TextLab hero: a still of the look the person left the room on, its name and the shelf key to open it
 * again (as VisLab's {@code LobbyHero} does for its looks).
 */
public final class TlLobbyHero {

    private static final String PREFS = "textlab";
    private static final String NAME = "hero_name", KEY = "hero_key", IMAGE = "hero_image";

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

    /** Any thread: keep this still (it is recycled) as the hero, under a new file name (the lobby caches by path). */
    static void save(@NonNull Context c, @NonNull Bitmap b, @NonNull String name, @NonNull String key) {
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
            p.edit().putString(NAME, name).putString(KEY, key).putString(IMAGE, out.getAbsolutePath()).apply();
            if (old != null && !old.equals(out.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                new File(old).delete();
            }
        }, "textlab-hero").start();
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
