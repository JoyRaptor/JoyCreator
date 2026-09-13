package com.fadcam.ui.faditor.sprite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flatten one or more sheets into a NEW sheet: one image, one grid, alignment baked in.
 *
 * <h3>Why this exists when alignment is already data</h3>
 * <p>It is not needed to move work from the desktop tool to the phone — {@code cellXf} travels
 * in the JSON, and that is the point of it. Baking is for the two things data cannot do:
 * hand a finished sheet to something that is not Joy Creator, and MERGE several sheets into
 * one, which a single {@code sheetUri} cannot express.</p>
 *
 * <h3>The one rule</h3>
 * <p>Every pixel here comes out of {@link SpriteSheetRenderer#drawCell}, the same single blit
 * the preview uses. A baker with its own drawing code is a second renderer, and a second
 * renderer disagrees with the first on the day you need it not to.</p>
 *
 * <p>This class does not modify the renderer or the source sheets. It reads them.</p>
 */
public final class SpriteBaker {

    private SpriteBaker() {}

    /** How a frame is sized into its baked cell. */
    public enum Fit {
        /** Measure the real art across every frame and size the cell to it. No clipping, no
         *  dead margin. The default, and the only one that makes a merged sheet look level. */
        CONTENT,
        /** Keep the source cell size, so the bake drops into an existing pipeline unchanged. */
        CELL
    }

    /** One sheet contributing frames to a bake. */
    public static final class Source {
        @NonNull public final SpriteSheet sheet;
        @NonNull public final SpriteSheetRenderer renderer;
        /** Display slots to take, in order. */
        @NonNull public final List<Integer> cells;

        public Source(@NonNull SpriteSheet sheet, @NonNull SpriteSheetRenderer renderer,
                      @NonNull List<Integer> cells) {
            this.sheet = sheet;
            this.renderer = renderer;
            this.cells = cells;
        }
    }

    public static final class Options {
        public int cols = 8;
        /** Empty pixels left around the art in each baked cell. */
        public int pad = 2;
        @NonNull public Fit fit = Fit.CONTENT;
        /** JPEG has no alpha, so a bake for it is flattened onto white rather than onto black. */
        public boolean jpeg = false;
        public int jpegQuality = 92;
    }

    public static final class Result {
        @NonNull public final Bitmap bitmap;
        public final int cols, rows;
        /** source key ("sheetId#slot") -> slot in the baked sheet. */
        @NonNull public final Map<String, Integer> map;
        /** Pivot of the baked sheet, carried through the crop. */
        public final float pivotX, pivotY;

        Result(@NonNull Bitmap bitmap, int cols, int rows,
               @NonNull Map<String, Integer> map, float pivotX, float pivotY) {
            this.bitmap = bitmap;
            this.cols = cols;
            this.rows = rows;
            this.map = map;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
        }
    }

    @NonNull
    public static String key(@NonNull SpriteSheet sheet, int cell) {
        return sheet.getId() + "#" + cell;
    }

    /**
     * The frames a bake should contain: every distinct cell the sheet's saved animations use,
     * in first-use order, or the whole sheet when it has no animations.
     *
     * <p>First-use order rather than sheet order on purpose — a baked sheet whose slots run in
     * the order the animation plays is one you can read at a glance, which is most of why you
     * would bake at all.</p>
     */
    @NonNull
    public static List<Integer> framesToBake(@NonNull SpriteSheet sheet) {
        List<Integer> out = new ArrayList<>();
        for (SpriteSheet.Preset p : sheet.getPresets()) {
            for (Integer f : p.frames) {
                if (f != null && f >= 0 && f < sheet.cellCount() && !out.contains(f)) out.add(f);
            }
        }
        if (out.isEmpty()) {
            for (int i = 0; i < sheet.cellCount(); i++) {
                SpriteSheet.Cell m = sheet.cellAt(i);
                if (m == null || m.enabled) out.add(i);
            }
        }
        return out;
    }

    /**
     * Draw every frame once into a scratch of cell size and union the ink.
     *
     * <p>Measured AFTER {@code drawCell}, so a nudge, a scale and a rotation are all included.
     * Measuring the raw cell instead would size the baked cell to where the art was before you
     * aligned it, which is the one thing a content fit must not do.</p>
     *
     * @return {left, top, right, bottom} in cell-local pixels, or null when nothing is drawn.
     */
    /**
     * Where a source's cell lands inside the common box.
     *
     * <p>{@code drawCell} stretches its source rect to fill the destination, which is right
     * when every cell is the same shape and wrong the moment you merge a 512x512 sheet with a
     * 512x768 one — the taller character would come out squashed. So a source whose cells are
     * a different shape gets letterboxed into the box rather than stretched across it.</p>
     */
    @NonNull
    private static RectF destFor(@NonNull Source s, int cell, int cw, int ch) {
        Rect r = s.renderer.cellRectBitmap(cell);
        int sw = Math.max(1, r.width()), sh = Math.max(1, r.height());
        float k = Math.min(cw / (float) sw, ch / (float) sh);
        float w = sw * k, h = sh * k;
        float x = (cw - w) / 2f, y = (ch - h) / 2f;
        return new RectF(x, y, x + w, y + h);
    }

    @Nullable
    private static float[] measureInk(@NonNull List<Source> sources, int cw, int ch) {
        if (cw <= 0 || ch <= 0) return null;
        Bitmap scratch = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(scratch);
        int[] row = new int[cw];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;

        for (Source s : sources) {
            for (int cell : s.cells) {
                scratch.eraseColor(0);
                s.renderer.drawCell(c, cell, destFor(s, cell, cw, ch), null);
                for (int y = 0; y < ch; y++) {
                    scratch.getPixels(row, 0, cw, 0, y, cw, 1);
                    for (int x = 0; x < cw; x++) {
                        if (((row[x] >>> 24) & 0xFF) <= 8) continue;
                        any = true;
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }
        scratch.recycle();
        return any ? new float[]{minX, minY, maxX + 1, maxY + 1} : null;
    }

    /**
     * Bake. Returns null when there is nothing to draw.
     *
     * <p>Every source frame is rendered at the FIRST source's cell size, so sheets of different
     * cell sizes merge without stretching: the renderer letterboxes each one into that box.</p>
     */
    @Nullable
    public static Result bake(@NonNull List<Source> sources, @NonNull Options opt) {
        int total = 0;
        for (Source s : sources) total += s.cells.size();
        if (total == 0) return null;

        Source first = sources.get(0);
        Rect c0 = first.renderer.cellRectBitmap(
                first.cells.isEmpty() ? 0 : first.cells.get(0));
        int cw = Math.max(1, c0.width()), ch = Math.max(1, c0.height());

        // Padding is honoured in BOTH fits. The pill is on screen either way, and a control
        // that silently does nothing in one mode is worse than one that is not there.
        float boxL = -opt.pad, boxT = -opt.pad;
        float boxW = cw + opt.pad * 2f, boxH = ch + opt.pad * 2f;
        if (opt.fit == Fit.CONTENT) {
            float[] ink = measureInk(sources, cw, ch);
            if (ink != null) {
                boxL = ink[0] - opt.pad;
                boxT = ink[1] - opt.pad;
                boxW = (ink[2] - ink[0]) + opt.pad * 2f;
                boxH = (ink[3] - ink[1]) + opt.pad * 2f;
            }
        }
        int bw = Math.max(1, Math.round(boxW)), bh = Math.max(1, Math.round(boxH));

        int cols = Math.max(1, Math.min(opt.cols, total));
        int rows = (int) Math.ceil(total / (float) cols);

        Bitmap out = Bitmap.createBitmap(cols * bw, rows * bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (opt.jpeg) canvas.drawColor(0xFFFFFFFF);

        Map<String, Integer> map = new LinkedHashMap<>();
        int i = 0;
        for (Source s : sources) {
            for (int cell : s.cells) {
                int x = (i % cols) * bw, y = (i / cols) * bh;
                int save = canvas.save();
                canvas.clipRect(x, y, x + bw, y + bh);
                canvas.translate(x - boxL, y - boxT);
                s.renderer.drawCell(canvas, cell, destFor(s, cell, cw, ch), null);
                canvas.restoreToCount(save);
                map.put(key(s.sheet, cell), i);
                i++;
            }
        }

        // The pivot is a fraction of a cell, so a crop moves it. Keep it on the same PIXEL, or
        // every sprite already placed against this art jumps the moment you bake.
        float px = (first.sheet.getPivotX() * cw - boxL) / bw;
        float py = (first.sheet.getPivotY() * ch - boxT) / bh;
        return new Result(out, cols, rows, map,
                Math.max(0f, Math.min(1f, px)), Math.max(0f, Math.min(1f, py)));
    }

    /**
     * The sheet that describes a bake: names, visemes and animations carried across, remapped
     * to the new slots.
     *
     * <p>{@code cellXf} is deliberately NOT carried. The alignment is in the pixels now;
     * carrying it as well would apply it twice.</p>
     */
    @NonNull
    public static SpriteSheet describe(@NonNull List<Source> sources, @NonNull Result baked,
                                       @NonNull String name, @NonNull String uri) {
        SpriteSheet s = SpriteSheet.create(name, uri);
        for (Source src : sources) {
            if (!s.getBakedFrom().contains(src.sheet.getName())) {
                s.getBakedFrom().add(src.sheet.getName());
            }
        }
        s.setGrid(baked.cols, baked.rows);
        s.setPivot(baked.pivotX, baked.pivotY);
        s.setFps(sources.get(0).sheet.getFps());

        for (Source src : sources) {
            for (int cell : src.cells) {
                Integer slot = baked.map.get(key(src.sheet, cell));
                if (slot == null) continue;
                String nm = src.sheet.cellName(cell);
                if (nm != null && !nm.isEmpty()) s.setCellName(slot, nm);
                String vis = src.sheet.visemeOfCell(cell);
                // First writer wins: two sheets can both claim "REST", and silently letting
                // the last one through would make the winner depend on merge order.
                if (vis != null && !s.getVisemeMap().containsKey(vis)) s.assignViseme(vis, slot);
            }
            for (SpriteSheet.Preset p : src.sheet.getPresets()) {
                SpriteSheet.Preset np = new SpriteSheet.Preset(
                        java.util.UUID.randomUUID().toString(), p.name);
                np.type = p.type;
                np.fps = p.fps;
                boolean whole = true;
                for (int k = 0; k < p.frames.size(); k++) {
                    Integer from = p.frames.get(k);
                    if (from == null) { whole = false; continue; }
                    Integer slot = baked.map.get(key(src.sheet, from));
                    if (slot == null) { whole = false; continue; }
                    np.frames.add(slot);
                    np.weights.add(SequenceTiming.weightAt(p.weights, k));
                }
                // A half-copied animation plays something nobody authored; drop it and let the
                // count in the report say one went missing.
                if (whole && !np.frames.isEmpty()) s.getPresets().add(np);
            }
        }
        return s;
    }

    /** Write a bitmap, returning the file or null. */
    @Nullable
    public static File write(@NonNull Bitmap bmp, @NonNull File dest, boolean jpeg, int quality) {
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            bmp.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                    jpeg ? quality : 100, fos);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Write one numbered PNG per frame, content-fit to the same box so they can be re-imported
     * as a sequence without every frame jittering.
     *
     * @return how many files were written.
     */
    public static int writeFrames(@NonNull List<Source> sources, @NonNull Options opt,
                                  @NonNull File dir, @NonNull String base) {
        Result baked = bake(sources, opt);
        if (baked == null) return 0;
        int bw = baked.bitmap.getWidth() / Math.max(1, baked.cols);
        int bh = baked.bitmap.getHeight() / Math.max(1, baked.rows);
        int n = 0;
        for (Map.Entry<String, Integer> e : baked.map.entrySet()) {
            int slot = e.getValue();
            int x = (slot % baked.cols) * bw, y = (slot / baked.cols) * bh;
            if (x + bw > baked.bitmap.getWidth() || y + bh > baked.bitmap.getHeight()) continue;
            Bitmap one = Bitmap.createBitmap(baked.bitmap, x, y, bw, bh);
            File f = new File(dir, String.format(java.util.Locale.US, "%s_%03d.png", base, slot));
            if (write(one, f, false, 100) != null) n++;
            one.recycle();
        }
        baked.bitmap.recycle();
        return n;
    }
}
