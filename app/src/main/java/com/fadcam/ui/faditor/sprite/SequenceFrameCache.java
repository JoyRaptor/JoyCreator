package com.fadcam.ui.faditor.sprite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, LRU-evicting decode cache for a file-backed sequence's frames.
 *
 * <p><b>The rule this exists to enforce</b> (SPEC_IMAGE_SEQUENCE §8 Memory): <i>"A 500-frame 4K
 * sequence must never decode all frames."</i> A grid sheet decodes ONE bitmap and blits sub-rects
 * out of it; a sequence has no such single bitmap, so the equivalent discipline has to be built —
 * downsample against the size the frame will actually be DRAWN at, keep a small number resident,
 * evict the least recently used, and recycle on release.</p>
 *
 * <p>The spec points at the image-overlay export path as the precedent, and this follows its three
 * habits deliberately: bound by the OUTPUT size rather than the source size, cache for the
 * lifetime of the consumer rather than globally, and recycle explicitly rather than leaving it to
 * the collector — a recycled-too-early bitmap throws, but a never-recycled 4K frame is an OOM
 * nobody can reproduce.</p>
 *
 * <p>Not thread-safe by design: each consumer (preview view, export overlay, timeline tape) owns
 * its own cache on its own thread, which is also what keeps the export's aggressive downsample
 * from degrading what the editor shows.</p>
 */
public final class SequenceFrameCache {

    /**
     * RAM the resident frames may occupy, in bytes. The resident COUNT is derived from this and
     * the decode cap, rather than being a fixed number.
     *
     * <p><b>Why a byte budget and not a frame count</b> (found reviewing this class after it
     * shipped): a fixed count means the memory this class exists to bound scales with the decode
     * size, in the wrong direction. The editor decodes at 512px (~1MB a frame) where 8 resident
     * is 8MB and fine; the EXPORT decodes against the output frame, so at 1080x1920 a frame is
     * ~8MB and the same 8 residents would be 64MB — on the export path, which is exactly where
     * this app can least afford it. A byte budget gives the editor a comfortable working set AND
     * keeps the export bounded, from one number.</p>
     */
    private static final long RESIDENT_BUDGET_BYTES = 24L * 1024 * 1024;

    /**
     * Never fewer than this, whatever the size — the frame being drawn plus one to come back to.
     *
     * <p><b>This floor can exceed the budget above, and that is deliberate:</b> at a 4K decode
     * cap even two frames is ~100MB, but a renderer holding zero frames cannot draw. The budget
     * governs where there is a choice; the floor governs where there is not. In practice the
     * export caps at the output frame (1080x1920 ≈ 8MB each, so the floor IS the budget) and the
     * editor at 512px, where the budget gives a comfortable working set.</p>
     */
    private static final int MIN_RESIDENT = 2;

    /**
     * Above this, more residents stop buying anything: playback walks in order, so the useful
     * working set is the frame being shown plus slack for scrubbing back and for a timeline tape
     * drawing several change-points at once.
     */
    private static final int MAX_RESIDENT_CAP = 24;

    /** Residents allowed at {@link #maxDim}, from the budget above. */
    private final int maxResident;

    /** Default longest-edge cap when a consumer does not state a draw size. */
    public static final int DEFAULT_MAX_DIM = 512;

    @NonNull private final Context ctx;
    @NonNull private final SpriteSheet sheet;
    private final int maxDim;

    /** accessOrder=true makes this an LRU: eldest entry is the least recently GOT. */
    @NonNull private final LinkedHashMap<Integer, Bitmap> resident =
            new LinkedHashMap<Integer, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> eldest) {
                    if (size() <= maxResident) return false;
                    Bitmap b = eldest.getValue();
                    if (b != null && !b.isRecycled()) b.recycle();
                    return true;
                }
            };

    /**
     * Frames that could not be decoded. Remembered so a missing or corrupt file is attempted
     * ONCE rather than on every drawn frame — at 30fps a retry loop over an unopenable URI is a
     * stall the user reads as the app hanging, and the MISSING affordance looks identical either
     * way.
     */
    @NonNull private final java.util.Set<Integer> failed = new java.util.HashSet<>();

    public SequenceFrameCache(@NonNull Context ctx, @NonNull SpriteSheet sheet, int maxDim) {
        this.ctx = ctx.getApplicationContext();
        this.sheet = sheet;
        this.maxDim = Math.max(16, maxDim);
        // Worst case per frame: a square image at the cap, ARGB_8888. Real frames are usually
        // smaller, so this errs toward fewer residents — the safe direction.
        long worstFrameBytes = (long) this.maxDim * this.maxDim * 4L;
        long fit = RESIDENT_BUDGET_BYTES / Math.max(1L, worstFrameBytes);
        this.maxResident = (int) Math.max(MIN_RESIDENT, Math.min(MAX_RESIDENT_CAP, fit));
    }

    /** Residents this cache will hold — exposed for diagnostics, not for tuning at runtime. */
    public int residentLimit() { return maxResident; }

    /**
     * Frame {@code index}, decoding it if needed.
     *
     * @return null when the index is out of range or the file cannot be opened/decoded — the
     *         caller's cue to draw the MISSING affordance. Never throws: a sequence breaks the
     *         instant one file is renamed (§8), and that must be a visible placeholder rather
     *         than a crash.
     */
    @Nullable
    public Bitmap frame(int index) {
        String uri = sheet.frameUriAt(index);
        if (uri == null) return null;
        Bitmap cached = resident.get(index);
        if (cached != null && !cached.isRecycled()) return cached;
        if (failed.contains(index)) return null;

        Bitmap bmp = decode(uri);
        if (bmp == null) { failed.add(index); return null; }
        resident.put(index, bmp);
        return bmp;
    }

    @Nullable
    private Bitmap decode(@NonNull String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int inSample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / inSample > maxDim) inSample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = inSample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            // inMutable so the bg color-key can be applied in place, exactly as the grid path
            // does at decode time — keeping "the key is applied ONCE, to the pixels both
            // renderers see" true for sequences too.
            opts.inMutable = true;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp != null && sheet.getBgKeyColor() != 0) {
                SpriteSheetRenderer.applyColorKey(bmp, sheet.getBgKeyColor(),
                        sheet.getKeyTolerance());
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            // OOM is caught deliberately: a single oversized frame must degrade to the MISSING
            // placeholder, not take the editor down with it.
            return null;
        }
    }

    /** True when frame {@code index} is known to be unopenable — drives the MISSING affordance. */
    public boolean isMissing(int index) {
        return sheet.frameUriAt(index) != null && failed.contains(index) && !resident.containsKey(index);
    }

    /**
     * Forget a previous failure so a relinked file is retried. Called by the relink path; without
     * it, fixing a missing file would appear not to work until the editor was reopened.
     */
    public void clearFailures() {
        failed.clear();
    }

    public void recycle() {
        for (Bitmap b : resident.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        resident.clear();
        failed.clear();
    }
}
