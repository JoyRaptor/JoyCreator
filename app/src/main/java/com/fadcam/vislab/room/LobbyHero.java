package com.fadcam.vislab.room;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.vislab.model.VisScene;
import com.fadcam.vislab.view.VisTextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * The lobby's Viz Lab hero: a still of the look the person was on when they left the room, with
 * its name, so the front door shows their visualiser instead of "No visualisers yet". A picture,
 * not a live view: the lobby must not keep the graphics chip (and the phone) busy.
 */
public final class LobbyHero {

    private static final String PREFS = "vislab";
    private static final String NAME = "hero_name", FAMILY = "hero_family", IMAGE = "hero_image";

    /** What the hero shows. */
    public static final class Shown {
        @NonNull public final String name, family, image;

        Shown(@NonNull String name, @NonNull String family, @NonNull String image) {
            this.name = name;
            this.family = family;
            this.image = image;
        }
    }

    private LobbyHero() {}

    /** Main thread, as the room closes: grab the frame now, write it on a background thread. */
    static void save(@NonNull Context c, @NonNull VisTextureView view, @Nullable VisScene look) {
        if (look == null || !view.isAvailable() || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        Bitmap b;
        try {
            b = view.getBitmap(Math.max(1, view.getWidth() / 2), Math.max(1, view.getHeight() / 2));
        } catch (RuntimeException e) {
            return;
        }
        if (b == null) return;
        Context app = c.getApplicationContext();
        String name = look.name(), family = look.family();
        new Thread(() -> {
            File dir = new File(app.getFilesDir(), "vislab");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            // A new name each time: the lobby caches pictures by path.
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
            p.edit().putString(NAME, name).putString(FAMILY, family).putString(IMAGE, out.getAbsolutePath()).apply();
            if (old != null && !old.equals(out.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                new File(old).delete();
            }
        }, "vislab-hero").start();
    }

    /** The last look's still and name, or null before the room has been visited. */
    @Nullable
    public static Shown read(@NonNull Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String image = p.getString(IMAGE, null), name = p.getString(NAME, null);
        if (image == null || name == null || !new File(image).exists()) return null;
        return new Shown(name, p.getString(FAMILY, ""), image);
    }
}
