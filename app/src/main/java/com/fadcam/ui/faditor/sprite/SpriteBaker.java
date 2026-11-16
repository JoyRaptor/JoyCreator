package com.fadcam.ui.faditor.sprite;

import com.fadcam.ui.faditor.Studio;

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
        CELL,
        /**
         * Fill the cell edge to edge, cropping whatever will not fit.
         *
         * <p>The dangerous one, and specified as such: "fill crops, and cropping a character
         * costs it a head" (SPEC_20260910_SPRITELAB_MODEL §6). It exists because matching an
         * existing sheet's framing exactly is sometimes worth a crop. It is never the default
         * and the UI says what it will do before it does it.</p>
         *
         * <p>Safe by construction here: {@link #bake} clips each cell to its own box before
         * drawing, so an overflowing frame can never bleed into its neighbour.</p>
         */
        FILL
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
    private static RectF destFor(@NonNull Source s, int cell, int cw, int ch, boolean fill) {
        Rect r = s.renderer.cellRectBitmap(cell);
        int sw = Math.max(1, r.width()), sh = Math.max(1, r.height());
        // min letterboxes and never crops; max fills and crops the overflow. Same arithmetic,
        // one operator, which is why they cannot drift apart.
        float k = fill ? Math.max(cw / (float) sw, ch / (float) sh)
                       : Math.min(cw / (float) sw, ch / (float) sh);
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
                // Measuring is the CONTENT path by definition, so it measures the
                // letterboxed art. FILL sizes its cell from the source, not from the ink.
                s.renderer.drawCell(c, cell, destFor(s, cell, cw, ch, false), null);
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
        Box b = box(sources, opt);
        if (b == null) return null;
        int total = b.total, cw = b.cw, ch = b.ch, bw = b.bw, bh = b.bh;
        float boxL = b.l, boxT = b.t;

        int cols = Math.max(1, Math.min(opt.cols, total));
        int rows = (int) Math.ceil(total / (float) cols);

        Bitmap out = Bitmap.createBitmap(cols * bw, rows * bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (opt.jpeg) canvas.drawColor(Studio.INK);

        Map<String, Integer> map = new LinkedHashMap<>();
        int i = 0;
        for (Source s : sources) {
            for (int cell : s.cells) {
                drawFrame(canvas, s, cell, b, opt, (i % cols) * bw, (i / cols) * bh);
                map.put(key(s.sheet, cell), i);
                i++;
            }
        }

        // The pivot is a fraction of a cell, so a crop moves it. Keep it on the same PIXEL, or
        // every sprite already placed against this art jumps the moment you bake.
        Source first = sources.get(0);
        float px = (first.sheet.getPivotX() * cw - boxL) / bw;
        float py = (first.sheet.getPivotY() * ch - boxT) / bh;
        return new Result(out, cols, rows, map,
                Math.max(0f, Math.min(1f, px)), Math.max(0f, Math.min(1f, py)));
    }

    /** The frame box every baked frame shares, measured once for a sheet or for numbered files. */
    private static final class Box {
        int total, cw, ch, bw, bh;
        float l, t;
    }

    @Nullable
    private static Box box(@NonNull List<Source> sources, @NonNull Options opt) {
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
        Box b = new Box();
        b.total = total;
        b.cw = cw;
        b.ch = ch;
        b.l = boxL;
        b.t = boxT;
        b.bw = Math.max(1, Math.round(boxW));
        b.bh = Math.max(1, Math.round(boxH));
        return b;
    }

    /** One frame into its box at (x, y), clipped to it: the one drawing both outputs share. */
    private static void drawFrame(@NonNull Canvas canvas, @NonNull Source s, int cell,
                                  @NonNull Box b, @NonNull Options opt, int x, int y) {
        int save = canvas.save();
        canvas.clipRect(x, y, x + b.bw, y + b.bh);
        canvas.translate(x - b.l, y - b.t);
        s.renderer.drawCell(canvas, cell, destFor(s, cell, b.cw, b.ch, opt.fit == Fit.FILL), null);
        canvas.restoreToCount(save);
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
        java.util.Set<String> seenSources = new java.util.LinkedHashSet<>();
        for (Source src : sources) {
            // By ID, not by name: two sheets can share a name (copyAsNew and relink both make
            // near-duplicates), and dropping one from the record defeats the point of it.
            if (seenSources.add(src.sheet.getId())) s.getBakedFrom().add(src.sheet.getName());
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

    /** What a numbered-frame export did. */
    public static final class Frames {
        /** The new folder holding the frames, or null when nothing was published. */
        @Nullable public final File dir;
        public final int written;
        /** True when a frame (or the folder) could not be written: then nothing was published. */
        public final boolean failed;

        Frames(@Nullable File dir, int written, boolean failed) {
            this.dir = dir;
            this.written = written;
            this.failed = failed;
        }
    }

    /**
     * Write one numbered PNG per frame, content-fit to the same box so they can be re-imported
     * as a sequence without every frame jittering.
     *
     * <p>One frame is in memory at a time, in one reused bitmap: the whole sheet used to be baked
     * first, hundreds of MB for a long sequence, before the first file was written (audit SP-05).
     * The frames go to a staging folder that becomes a NEW folder ({@code <base>_frames}, then
     * {@code <base>_frames-2} ...) only once every frame is written: an earlier export is never
     * overwritten or mixed with this one, and a failure halfway leaves nothing half-done (SP-04).</p>
     */
    @NonNull
    public static Frames writeFrames(@NonNull List<Source> sources, @NonNull Options opt,
                                     @NonNull File parent, @NonNull String base) {
        Box b = box(sources, opt);
        if (b == null) return new Frames(null, 0, false);
        File stage = new File(parent, base + "_frames.part");
        deleteTree(stage);   // our own leftover from an export that was cut off
        if (!stage.mkdirs()) return new Frames(null, 0, true);
        Bitmap one = Bitmap.createBitmap(b.bw, b.bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(one);
        int n = 0;
        boolean failed = false;
        try {
            outer:
            for (Source s : sources) {
                for (int cell : s.cells) {
                    one.eraseColor(0);
                    drawFrame(canvas, s, cell, b, opt, 0, 0);
                    File f = new File(stage, String.format(java.util.Locale.US, "%s_%03d.png", base, n));
                    if (write(one, f, false, 100) == null) { failed = true; break outer; }
                    n++;
                }
            }
        } finally {
            one.recycle();
        }
        File dest = failed ? null : freshDir(parent, base + "_frames");
        if (dest == null || !stage.renameTo(dest)) {
            deleteTree(stage);
            return new Frames(null, n, true);
        }
        return new Frames(dest, n, false);
    }

    /** {@code name}, or {@code name-2}, {@code name-3} ...: the first that does not exist yet. */
    @NonNull
    static File freshDir(@NonNull File parent, @NonNull String name) {
        File d = new File(parent, name);
        for (int k = 2; d.exists(); k++) d = new File(parent, name + "-" + k);
        return d;
    }

    private static void deleteTree(@NonNull File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
