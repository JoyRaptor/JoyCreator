package com.fadcam.vislab.room;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.FLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Words wearing a TextLab look, in a VisLab picture layer (TEXTLAB_ROADMAP T15): chrome, fire, neon... on the
 * person's own words, moving with the look (its fire, its particles, its light). TextLab draws them on its own
 * thread ({@code cc.joycreator.textlab.StudioTextLab}, as for Studio's titles in VisLab), so the look is exactly the
 * one the TextLab room and Studio show.
 *
 * <p>The layer keeps the whole look with the words in its picture ({@code "image": "textlab:{...}"}), so a VisLab
 * look carries it to Studio, to an export and to another phone, and a later change to the look's file never changes
 * a VisLab look that wears it. When a phone cannot draw the looks the words show plainly, never an error.</p>
 *
 * <p>Frames are made at 30 a second of the look's clock (a newer frame is never asked for twice), the same in the
 * room and in an export. GL thread.</p>
 */
public final class WordsPicture {

    private static final String TAG = "VisWords";
    public static final String PREFIX = "textlab:";
    /** The letters are drawn this many pixels to the em: sharp in a full-screen layer, a small texture. */
    private static final double EM = 160;
    private static final double FPS = 30;

    @NonNull private final Context app;
    private final Map<String, Parsed> parsed = new HashMap<>();
    private final Map<String, Long> lastStep = new HashMap<>();
    private final Map<String, Bitmap> shown = new HashMap<>();

    WordsPicture(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    /** The words and look a layer's picture names. */
    public static final class Parsed {
        @NonNull public final String words;
        @NonNull public final cc.joycreator.textlab.TitleLook look;

        Parsed(@NonNull String words, @NonNull cc.joycreator.textlab.TitleLook look) {
            this.words = words;
            this.look = look;
        }
    }

    public static boolean is(@Nullable String ref) {
        return ref != null && ref.startsWith(PREFIX);
    }

    /** A layer's picture for these words in this look. */
    @NonNull
    public static String ref(@NonNull String words, @NonNull cc.joycreator.textlab.TitleLook look) {
        JsonObject o = new JsonObject();
        o.addProperty("words", words);
        o.add("look", look.toJson());
        return PREFIX + o;
    }

    /** What a layer's picture holds, or null when it is not words in a look (or cannot be read). */
    @Nullable
    public static Parsed parse(@Nullable String ref) {
        if (!is(ref)) return null;
        try {
            JsonObject o = JsonParser.parseString(ref.substring(PREFIX.length())).getAsJsonObject();
            JsonElement w = o.get("words");
            cc.joycreator.textlab.TitleLook t = cc.joycreator.textlab.TitleLook.fromJson(o.get("look"));
            if (t == null) return null;
            String words = w != null && w.isJsonPrimitive() ? w.getAsString() : "";
            return new Parsed(words.trim().isEmpty() ? t.sample() : words, t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A still of the words (the look settled, three seconds in): a new picture the caller owns. */
    @WorkerThread
    @Nullable
    Bitmap still(@NonNull String ref) {
        Bitmap b = draw(ref, 3000);
        return b;
    }

    /**
     * The words at {@code seconds} of the look's clock, BORROWED until the next call; null when nothing could be
     * drawn. Within the same thirtieth of a second the frame already made is given again (two layers wearing the
     * same words both get it).
     */
    @WorkerThread
    @Nullable
    Bitmap frame(@NonNull String ref, double seconds) {
        long step = (long) Math.floor(Math.max(0, seconds) * FPS);
        Long last = lastStep.get(ref);
        Bitmap had = shown.get(ref);
        if (last != null && last == step && had != null && !had.isRecycled()) return had;
        Bitmap b = draw(ref, Math.round(step * 1000 / FPS));
        if (b == null) return null;
        lastStep.put(ref, step);
        Bitmap old = shown.put(ref, b);
        if (old != null && old != b && !old.isRecycled()) old.recycle();   // the renderer let it go at this call
        return b;
    }

    /** Let go of every frame (the renderer is being let go). */
    void release() {
        for (Bitmap b : shown.values()) if (b != null && !b.isRecycled()) b.recycle();
        shown.clear();
        lastStep.clear();
    }

    @Nullable
    private Bitmap draw(@NonNull String ref, long localMs) {
        Parsed p = parsed.get(ref);
        if (p == null && !parsed.containsKey(ref)) {
            p = parse(ref);
            parsed.put(ref, p);
        }
        if (p == null) return null;
        try {
            cc.joycreator.textlab.StudioTextLab.Frame f = cc.joycreator.textlab.StudioTextLab.get(app).renderBlocking(
                    "vislab-words:" + Integer.toHexString(ref.hashCode()), p.look, p.words, localMs, EM);
            if (f != null) return f.bitmap;
        } catch (RuntimeException | Error e) {
            FLog.w(TAG, "TextLab could not draw these words; showing them plainly", e);
        }
        return plain(p.words);
    }

    /** The words in plain white letters, when this phone cannot draw the look. */
    @NonNull
    private static Bitmap plain(@NonNull String words) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize((float) EM);
        String[] lines = words.split("\n");
        float w = 1;
        for (String l : lines) w = Math.max(w, paint.measureText(l));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float lh = fm.descent - fm.ascent;
        int pad = (int) (EM / 4);
        Bitmap b = Bitmap.createBitmap(Math.min(2048, (int) w + 2 * pad), Math.min(2048, (int) (lh * lines.length) + 2 * pad),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        for (int i = 0; i < lines.length; i++) {
            Rect r = new Rect();
            paint.getTextBounds(lines[i], 0, lines[i].length(), r);
            c.drawText(lines[i], (b.getWidth() - paint.measureText(lines[i])) / 2f, pad - fm.ascent + i * lh, paint);
        }
        return b;
    }
}
