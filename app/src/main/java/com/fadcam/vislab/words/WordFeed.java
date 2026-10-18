package com.fadcam.vislab.words;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.type.Type;
import com.fadcam.vislab.gl.ShaderLib;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The timed words a {@code words} layer turns into art (a transcript's words, the demo song's
 * sample lyrics) and the glyph atlas the shader reads. The twin of the web pilot's
 * {@code vislab-words.js}; the contract with {@code words.frag}:
 * <ul>
 *   <li>{@code uWords}: 2048 x 2048, 16 slots of 2048 x 128 stacked top to bottom, white ink, the
 *       word drawn from x = 0 with padding both sides;</li>
 *   <li>{@code uWord[i]} = (slot, start - now, end - now, width / height) in seconds;</li>
 *   <li>{@code uWordB[i]} = (flow - flow now, order, hash, duration): flow is the words laid end
 *       to end in text heights, so a scene can scroll text along a path and have each word
 *       arrive at the anchor exactly when it starts.</li>
 * </ul>
 * {@link #setWords} from any thread; everything else on the GL thread.
 */
public final class WordFeed {

    private static final String TAG = "VisWords";
    public static final int SLOTS = 16;
    static final int SW = 2048, SH = 128;
    static final float GAP = 0.6f, PAD = 0.25f;   // text heights
    static final float TEXT_PX = 96f;

    /** One timed word. */
    public static final class Word {
        public final String text;
        public final double start, end;

        public Word(@NonNull String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = Math.max(end, start + 0.05);
        }
    }

    /** An immutable set of words with their widths and flow positions (swapped in whole). */
    private static final class Track {
        final Word[] words;
        final float[] aspect, flow;
        final double period;     // > 0: repeats (a looping song)
        final float flowPeriod;

        Track(Word[] words, float[] aspect, float[] flow, double period, float flowPeriod) {
            this.words = words;
            this.aspect = aspect;
            this.flow = flow;
            this.period = period;
            this.flowPeriod = flowPeriod;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile Track track = new Track(new Word[0], new float[0], new float[0], 0, 0);

    // GL thread
    private final String[] slotKey = new String[SLOTS];
    private final String[] slotText = new String[SLOTS];
    private final boolean[] dirty = new boolean[SLOTS];
    private boolean anyDirty;
    private int tex;
    @Nullable private Bitmap slotBitmap;
    @Nullable private Canvas slotCanvas;
    private Track drawnFor;

    /** Filled by {@link #update}: what the shader gets. */
    public final float[] u = new float[SLOTS * 4], ub = new float[SLOTS * 4];
    public int count;
    /** How far the words have flowed so far, text heights (a scene can travel with them). */
    public float flowNow;

    public WordFeed(@NonNull Context c) {
        Typeface t = Type.display(c.getApplicationContext(), Type.EXTRA);
        paint.setTypeface(t != null ? t : Typeface.DEFAULT_BOLD);
        paint.setTextSize(TEXT_PX);
        paint.setColor(Color.WHITE);
    }

    /** The demo song's sample words (assets/vislab/words/demo-lyrics.json), or none. */
    @NonNull
    public static WordFeed withDemo(@NonNull Context c) {
        WordFeed f = new WordFeed(c);
        try {
            JsonObject o = JsonParser.parseString(ShaderLib.readAll(c.getAssets().open("vislab/words/demo-lyrics.json")))
                    .getAsJsonObject();
            List<Word> list = new ArrayList<>();
            JsonArray a = o.getAsJsonArray("words");
            for (JsonElement e : a) {
                JsonObject w = e.getAsJsonObject();
                list.add(new Word(w.get("text").getAsString(), w.get("start").getAsDouble(), w.get("end").getAsDouble()));
            }
            f.setWords(list, o.has("period") ? o.get("period").getAsDouble() : 0);
        } catch (Exception e) {
            Log.w(TAG, "demo words unavailable", e);
        }
        return f;
    }

    /** Timed words; {@code period} > 0 repeats them every {@code period} seconds. Any thread. */
    public void setWords(@NonNull List<Word> list, double period) {
        Word[] w = list.toArray(new Word[0]);
        java.util.Arrays.sort(w, (a, b) -> Double.compare(a.start, b.start));
        float[] aspect = new float[w.length], flow = new float[w.length];
        float f = 0;
        synchronized (paint) {
            for (int i = 0; i < w.length; i++) {
                aspect[i] = paint.measureText(w[i].text) / SH + PAD * 2;
                flow[i] = f;
                f += aspect[i] + GAP;
            }
        }
        track = new Track(w, aspect, flow, period, f);
    }

    /** Flow position at time t (inside one period): words arrive at the anchor as they start. */
    static float flowAt(@NonNull Track k, double t) {
        Word[] w = k.words;
        if (w.length == 0) return 0;
        int last = w.length - 1;
        double P = k.period;
        if (t < w[0].start) {
            return P > 0 ? lerp(t, w[last].start - P, k.flow[last] - k.flowPeriod, w[0].start, k.flow[0])
                    : (float) (k.flow[0] - (w[0].start - t) * 2);
        }
        for (int i = 0; i < last; i++) {
            if (t < w[i + 1].start) return lerp(t, w[i].start, k.flow[i], w[i + 1].start, k.flow[i + 1]);
        }
        return P > 0 ? lerp(t, w[last].start, k.flow[last], P + w[0].start, k.flowPeriod + k.flow[0])
                : (float) (k.flow[last] + (t - w[last].start) * 2);
    }

    private static float lerp(double t, double t0, float f0, double t1, float f1) {
        double x = Math.max(0, Math.min(1, (t - t0) / Math.max(t1 - t0, 1e-3)));
        return (float) (f0 + (f1 - f0) * x);
    }

    private static final class Pick {
        final int i, period;
        final double s, e;
        final float flow;
        final String key;

        Pick(int i, int period, double s, double e, float flow) {
            this.i = i;
            this.period = period;
            this.s = s;
            this.e = e;
            this.flow = flow;
            this.key = period + ":" + i;
        }
    }

    /** Choose the words around {@code now} (up to 16, nearest first) and fill {@link #u} / {@link #ub}. */
    public void update(double now, double lead, double back) {
        Track k = track;
        if (k != drawnFor) {   // new words: every slot is stale
            java.util.Arrays.fill(slotKey, null);
            drawnFor = k;
        }
        java.util.Arrays.fill(u, 0);
        java.util.Arrays.fill(ub, 0);
        count = 0;
        Word[] w = k.words;
        if (w.length == 0) return;
        double P = k.period;
        int k0 = P > 0 ? (int) Math.floor((now - back) / P) : 0;
        int k1 = P > 0 ? (int) Math.floor((now + lead) / P) : 0;
        List<Pick> pick = new ArrayList<>();
        for (int c = k0; c <= k1; c++) {
            double base = c * P;
            float flowBase = c * k.flowPeriod;
            for (int i = 0; i < w.length; i++) {
                double s = w[i].start + base, e = w[i].end + base;
                if (s - now > lead || now - e > back) continue;
                pick.add(new Pick(i, c, s, e, k.flow[i] + flowBase));
            }
        }
        Collections.sort(pick, (a, b) -> Double.compare(Math.abs(a.s - now), Math.abs(b.s - now)));
        if (pick.size() > SLOTS) pick = pick.subList(0, SLOTS);
        double inPeriod = P > 0 ? ((now % P) + P) % P : now;
        float flowNow = flowAt(k, inPeriod) + (P > 0 ? (float) (Math.floor(now / P) * k.flowPeriod) : 0f);
        this.flowNow = flowNow;
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : pick) keep.add(p.key);
        for (Pick p : pick) {
            int slot = -1;
            for (int s = 0; s < SLOTS; s++) if (p.key.equals(slotKey[s])) { slot = s; break; }
            if (slot < 0) {
                for (int s = 0; s < SLOTS; s++) if (slotKey[s] == null || !keep.contains(slotKey[s])) { slot = s; break; }
                if (slot < 0) continue;
                slotKey[slot] = p.key;
                slotText[slot] = w[p.i].text;
                dirty[slot] = true;
                anyDirty = true;
            }
            int j = count * 4;
            int order = p.i + p.period * w.length;
            u[j] = slot;
            u[j + 1] = (float) (p.s - now);
            u[j + 2] = (float) (p.e - now);
            u[j + 3] = k.aspect[p.i];
            ub[j] = p.flow - flowNow;
            ub[j + 1] = order;
            double h = Math.sin(order * 12.9898) * 43758.5453;
            ub[j + 2] = (float) (h - Math.floor(h));
            ub[j + 3] = (float) (p.e - p.s);
            count++;
        }
    }

    /** The atlas texture, with any newly placed words drawn in. GL thread, context current. */
    public int texture() {
        if (tex == 0) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            tex = ids[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, SW, SW, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            // Start clear: a slot nobody wrote must not show garbage.
            for (int s = 0; s < SLOTS; s++) if (slotKey[s] == null) { slotText[s] = ""; dirty[s] = true; }
            anyDirty = true;
        }
        if (anyDirty) {
            if (slotBitmap == null) {
                slotBitmap = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888);
                slotCanvas = new Canvas(slotBitmap);
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            Paint.FontMetrics fm = paint.getFontMetrics();
            float y = SH * 0.54f - (fm.ascent + fm.descent) / 2f;
            for (int s = 0; s < SLOTS; s++) {
                if (!dirty[s]) continue;
                slotCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
                String text = slotText[s];
                if (text != null && !text.isEmpty()) {
                    synchronized (paint) {
                        slotCanvas.drawText(text, PAD * SH, y, paint);
                    }
                }
                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, s * SH, slotBitmap);
                dirty[s] = false;
            }
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            anyDirty = false;
        }
        return tex;
    }

    /** Free the texture. GL thread, context current. */
    public void release() {
        if (tex != 0) GLES30.glDeleteTextures(1, new int[]{tex}, 0);
        contextLost();
    }

    /** The context is gone: forget the texture without deleting it; every slot redraws. */
    public void contextLost() {
        tex = 0;
        java.util.Arrays.fill(slotKey, null);
        drawnFor = null;
        if (slotBitmap != null) slotBitmap.recycle();
        slotBitmap = null;
        slotCanvas = null;
    }
}
