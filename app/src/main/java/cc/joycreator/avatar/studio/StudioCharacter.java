package cc.joycreator.avatar.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import cc.joycreator.avatar.file.CharacterAssets;
import cc.joycreator.avatar.file.CharacterDoc;
import cc.joycreator.avatar.file.CharacterStore;
import cc.joycreator.avatar.perform.Take;

import com.fadcam.joyfolder.android.JoyFolderHome;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Getting a character into a Studio project (slice 2, item 3): the shelf (the built-in characters, then the ones in the
 * Joy Creator folder), and everything a drop needs made off the main thread: the character's standing picture as the
 * item's sprite, its take, and its voice as a WAV for an audio clip at the same start.
 */
public final class StudioCharacter {
    /** A character with no take of its own is dropped this long (Studio's still-picture length). */
    public static final int EMPTY_TAKE_FRAMES = 5 * Take.FPS;
    /** The standing picture's long side, the old avatar's bake size. */
    public static final int PICTURE_SIDE = 1024;

    private StudioCharacter() {}

    /** One character on the shelf. */
    public static final class Choice {
        public final String name, ref;

        public Choice(String name, String ref) {
            this.name = name;
            this.ref = ref;
        }
    }

    /** Everything a drop needs, made ready. */
    public static final class Prepared {
        public final String ref, uid, name;
        public final File picture;
        public final int pictureW, pictureH;
        public final String takeJson;
        public final long lengthMs;
        /** The take's voice as a file, or null when the take has none. */
        public final File voice;

        Prepared(String ref, String uid, String name, File picture, int w, int h, String takeJson, long lengthMs, File voice) {
            this.ref = ref;
            this.uid = uid;
            this.name = name;
            this.picture = picture;
            this.pictureW = w;
            this.pictureH = h;
            this.takeJson = takeJson;
            this.lengthMs = lengthMs;
            this.voice = voice;
        }

        /** The sprite sheet's id: one per character, so dropping it twice shares the picture. */
        public String sheetId() {
            return "character-" + uid;
        }
    }

    /** The shelf, on the main thread: Topps and Pip first, then the folder's characters, the newest first. */
    public static void shelf(Context c, Consumer<List<Choice>> done) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        JoyFolderHome.io().execute(() -> {
            List<Choice> out = new ArrayList<>();
            out.add(new Choice("Topps", CharacterLoader.assetRef(CharacterAssets.TOPPS)));
            out.add(new Choice("Pip", CharacterLoader.assetRef(CharacterAssets.PIP)));
            try {
                for (CharacterStore.Entry e : CharacterStore.list(app)) out.add(new Choice(e.name, e.path));
            } catch (RuntimeException ignored) {
                // the folder could not be reached: the built-in ones are still there
            }
            main.post(() -> done.accept(out));
        });
    }

    /**
     * Makes a drop ready, off the main thread; {@code done} gets null (on the main thread) when the character could not
     * be read. {@code assetsDir} is the project's own assets folder (the picture lives with the project).
     */
    public static void prepare(Context c, String ref, File assetsDir, Consumer<Prepared> done) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        JoyFolderHome.io().execute(() -> {
            Prepared p;
            try {
                p = prepareNow(app, ref, assetsDir);
            } catch (Exception | OutOfMemoryError e) {
                p = null;
            }
            final Prepared fp = p;
            main.post(() -> done.accept(fp));
        });
    }

    static Prepared prepareNow(Context c, String ref, File assetsDir) throws Exception {
        CharacterLoader.forget(ref);   // the room may have changed it since
        CharacterLoader.Loaded l = CharacterLoader.load(c, ref);
        if (l == null || l.doc.w <= 0 || l.doc.h <= 0) return null;
        CharacterDoc doc = l.doc;
        String uid = doc.json.optString("uid", "");
        if (uid.isEmpty()) uid = safeId(doc.name.isEmpty() ? "character" : doc.name);

        // the take (its voice goes beside it, as an audio clip)
        JSONObject tj = doc.json.optJSONObject("take");
        Take take = tj != null ? Take.fromJson(tj) : null;
        if (take == null || take.n <= 0) {
            take = new Take();
            take.n = EMPTY_TAKE_FRAMES;
        }
        File voice = tj != null ? writeVoice(c, tj.optJSONObject("voice"), uid) : null;

        // the standing picture: the take's first frame, posed by whatever poser is installed
        float s = PICTURE_SIDE / (float) Math.max(doc.w, doc.h);
        int w = Math.max(1, Math.round(doc.w * s)), h = Math.max(1, Math.round(doc.h * s));
        if (!assetsDir.exists() && !assetsDir.mkdirs()) return null;
        File png = new File(assetsDir, "character-" + uid + ".png");
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        CharacterPoser poser = CharacterPosers.get().forDoc(doc, new CharacterPictures(doc, l.beside));
        try {
            Canvas cv = new Canvas(bmp);
            cv.scale(s, s);
            poser.draw(cv, TakeClock.sample(take, 0), 0);
            try (FileOutputStream out = new FileOutputStream(png)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } finally {
            poser.release();
            bmp.recycle();
        }
        return new Prepared(ref, uid, doc.name.isEmpty() ? "Character" : doc.name, png, w, h,
                take.toJson().toString(), TakeClock.lengthMs(take), voice);
    }

    /** The take's voice ({@code data:audio/wav;base64,...}) as a WAV file in Studio's audio folder, or null. */
    static File writeVoice(Context c, JSONObject voice, String uid) {
        if (voice == null) return null;
        String wav = voice.optString("wav", "");
        int comma = wav.indexOf(',');
        if (!wav.startsWith("data:") || comma < 0) return null;
        try {
            byte[] raw = Base64.decode(wav.substring(comma + 1), Base64.DEFAULT);
            if (raw.length <= 44) return null;
            File dir = new File(c.getFilesDir(), "faditor_audio");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, "character_voice_" + uid + "_" + System.currentTimeMillis() + ".wav");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(raw);
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** A small square picture of the character standing, for the shelf (off the main thread); null when it has none. */
    public static Bitmap thumb(Context c, String ref, int side) {
        CharacterLoader.Loaded l = CharacterLoader.load(c, ref);
        if (l == null || l.doc.w <= 0 || l.doc.h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        CharacterPoser poser = CharacterPosers.get().forDoc(l.doc, new CharacterPictures(l.doc, l.beside));
        try {
            float[] box = CharacterItemPuppet.fit(l.doc.w, l.doc.h, side, side);
            Canvas cv = new Canvas(bmp);
            cv.translate(box[1], box[2]);
            cv.scale(box[0], box[0]);
            poser.draw(cv, java.util.Collections.emptyMap(), 0);
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            bmp.recycle();
            return null;
        } finally {
            poser.release();
        }
    }

    static String safeId(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.isEmpty() ? "character" : s;
    }

    /** The item's take, or a new empty one as long as the item. */
    public static Take takeOf(com.fadcam.ui.faditor.sprite.SpriteOverlayItem item) {
        String t = item.getCharacterTake();
        if (t != null) {
            try {
                return Take.fromJson(new JSONObject(t));
            } catch (Exception ignored) {
            }
        }
        return new Take();
    }

    public static String json(Take t) {
        try {
            return t.toJson().toString();
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
