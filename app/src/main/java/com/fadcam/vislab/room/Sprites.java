package com.fadcam.vislab.room;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.Studio;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.sprite.SpriteSheetRenderer;
import com.fadcam.ui.type.Type;
import com.fadcam.vislab.gl.SpriteAtlas;
import com.fadcam.vislab.gl.VisRenderer;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprite sheets for Sprite layers. A layer's {@code sheet} is a Sprite Lab sheet in its saved
 * form, its pictures as full links (see {@link SheetShelf}), so the look keeps working outside
 * the project; {@code clip} picks one of its animations. Every frame is drawn through Sprite Lab's
 * own {@link SpriteSheetRenderer} (slicing, background key, each frame's alignment) into an even
 * grid with a clear gutter. No sheet: the built-in one, the Joy record bouncing in 8 frames.
 *
 * <p>The last two grids are kept, so a seek or an undo does not decode the sheet again. Called on
 * the GL thread.</p>
 */
public final class Sprites implements VisRenderer.SpriteSource {

    private static final String TAG = "VisSprites";
    private static final int MAX_SIDE = 2048;     // the whole grid
    private static final int MAX_CELL = 512;      // one frame's longest side
    private static final int GUTTER = 4;          // clear pixels round each frame
    private static final int MAX_FRAMES = 256;
    private static final int MAX_HOLD = 16;       // a frame's weight, as repeats

    private final Context app;
    private final LinkedHashMap<String, SpriteAtlas> kept = new LinkedHashMap<>(4, 0.75f, true);
    @Nullable private SpriteAtlas builtIn;

    public Sprites(@NonNull Context c) {
        app = c.getApplicationContext();
    }

    @Nullable
    @Override
    public synchronized SpriteAtlas atlas(@Nullable JsonObject sheet, @NonNull String clip) {
        if (sheet == null) return builtIn();
        String key = sheet.toString() + '\u0001' + clip;
        SpriteAtlas a = kept.get(key);
        if (a != null && !a.bitmap.isRecycled()) return a;
        try {
            a = build(SpriteSheet.fromJson(sheet), clip);
        } catch (RuntimeException e) {
            Log.w(TAG, "sprite sheet unreadable", e);
            a = null;
        }
        if (a == null) return builtIn();
        kept.put(key, a);
        Iterator<Map.Entry<String, SpriteAtlas>> it = kept.entrySet().iterator();
        while (kept.size() > 2 && it.hasNext()) {
            it.next().getValue().bitmap.recycle();
            it.remove();
        }
        return a;
    }

    @Nullable
    private SpriteAtlas build(@NonNull SpriteSheet s, @NonNull String clip) {
        SpriteSheetRenderer r = SpriteSheetRenderer.load(app, s, MAX_CELL);
        if (r == null) return null;
        try {
            // Play order: the clip's frames, each held for its weight; or every frame not switched off.
            SpriteSheet.Preset p = clip.isEmpty() ? null : s.presetById(clip);
            List<Integer> frames = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            if (p != null && !p.frames.isEmpty()) {
                frames.addAll(p.frames);
                weights.addAll(p.weights);
            } else {
                p = null;
                for (int i = 0; i < s.cellCount(); i++) {
                    SpriteSheet.Cell c = s.cellAt(i);
                    if (c == null || c.enabled) frames.add(i);
                }
            }
            if (frames.isEmpty()) return null;
            Map<Integer, Integer> slotOf = new HashMap<>();
            List<Integer> unique = new ArrayList<>();
            List<Integer> order = new ArrayList<>();
            for (int j = 0; j < frames.size(); j++) {
                int cell = frames.get(j);
                if (cell < 0 || cell >= s.cellCount()) continue;
                Integer slot = slotOf.get(cell);
                if (slot == null) {
                    if (unique.size() >= MAX_FRAMES) continue;
                    slot = unique.size();
                    slotOf.put(cell, slot);
                    unique.add(cell);
                }
                Integer w = j < weights.size() ? weights.get(j) : null;
                int hold = w == null ? 1 : Math.max(1, Math.min(MAX_HOLD, w));
                for (int k = 0; k < hold; k++) order.add(slot);
            }
            if (unique.isEmpty()) return null;
            RectF first = r.fitCell(unique.get(0), new RectF(0, 0, 4096, 4096));
            float cw = Math.max(1f, first.width()), ch = Math.max(1f, first.height());
            int n = unique.size();
            int cols = (int) Math.ceil(Math.sqrt(n));
            int rows = (int) Math.ceil(n / (double) cols);
            // fitCell gave the frame's shape at 4096 on its long side: bring it down to fit.
            float k = Math.min(MAX_CELL / Math.max(cw, ch),
                    Math.min((MAX_SIDE / (float) cols - 2 * GUTTER) / cw, (MAX_SIDE / (float) rows - 2 * GUTTER) / ch));
            int cellW = Math.max(1, Math.round(cw * k)), cellH = Math.max(1, Math.round(ch * k));
            int stepX = cellW + 2 * GUTTER, stepY = cellH + 2 * GUTTER;
            Bitmap grid = Bitmap.createBitmap(cols * stepX, rows * stepY, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(grid);
            for (int i = 0; i < n; i++) {
                float x = (i % cols) * stepX + GUTTER, y = (i / cols) * stepY + GUTTER;
                r.drawCellFitted(canvas, unique.get(i), new RectF(x, y, x + cellW, y + cellH), null);
            }
            int[] play = new int[order.size()];
            for (int i = 0; i < play.length; i++) play[i] = order.get(i);
            float fps = p != null && p.fps > 0 ? p.fps : s.getFps();
            return new SpriteAtlas(grid, cols, rows, cellW, cellH, GUTTER, play, fps,
                    p != null && "pingpong".equals(p.type));
        } finally {
            r.recycle();
        }
    }

    /** The Joy record bouncing: 8 frames of squash and stretch, its shine going round. */
    @NonNull
    private SpriteAtlas builtIn() {
        if (builtIn != null && !builtIn.bitmap.isRecycled()) return builtIn;
        int cell = 256, cols = 4, rows = 2, step = cell + 2 * GUTTER;
        Bitmap b = Bitmap.createBitmap(cols * step, rows * step, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Typeface face = Type.display(app, Type.EXTRA);
        for (int i = 0; i < 8; i++) {
            double ph = i / 8.0 * Math.PI * 2;
            float lift = (float) Math.abs(Math.sin(ph / 2));   // 0 on the floor, 1 at the top
            float sx = 1.1f - 0.16f * lift, sy = 0.88f + 0.18f * lift;
            float ox = (i % cols) * step + GUTTER + cell / 2f;
            float oy = (i / cols) * step + GUTTER + cell / 2f + 34f - 60f * lift;
            float r = 86f;
            c.save();
            c.translate(ox, oy + r * (1 - sy));
            c.scale(sx, sy);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(-r, -r, r, r, Studio.ROOM_VIZ, Studio.ROOM_VIZ_DEEP, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, r, p);
            p.setShader(null);
            p.setColor(0xFF0B0B0E);
            c.drawCircle(0, 0, r * 0.83f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(Color.argb(90, 250, 160, 61));
            for (float g = r * 0.5f; g < r * 0.82f; g += 6f) c.drawCircle(0, 0, g, p);
            // The shine: an arc a quarter of the way round each frame.
            p.setStrokeWidth(5f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.argb(200, 255, 255, 255));
            float sr = r * 0.7f;
            c.drawArc(new RectF(-sr, -sr, sr, sr), i * 45f - 20f, 40f, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Studio.ROOM_VIZ);
            p.setTypeface(face != null ? face : Typeface.DEFAULT_BOLD);
            p.setTextSize(52f);
            p.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText("JOY", 0, -(fm.ascent + fm.descent) / 2f, p);
            c.restore();
        }
        builtIn = new SpriteAtlas(b, cols, rows, cell, cell, GUTTER, new int[]{0, 1, 2, 3, 4, 5, 6, 7}, 8f, false);
        return builtIn;
    }
}
