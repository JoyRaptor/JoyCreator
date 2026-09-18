package com.fadcam.ui.faditor.assetbrowser;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.ui.faditor.util.DurableCache;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Video thumbnail loader for the asset browser picker.
 *
 * <p>Implements SPEC_20260829_MEDIA_IMPORT S2.2 (which supersedes QUICK_WINS S2):
 * extracts one frame per video at ~10% in (not 0), via {@code getScaledFrameAtTime}
 * where available, caches to disk via {@link DurableCache#dir(Context, String)}
 * {@code "vidthumb"} keyed on uri+size+thumbPx, on a bounded pool (2 threads),
 * newest-first via cancellation, placeholder while loading and distinct fallback on
 * failure. Never on main thread.
 *
 * <p>Extends -- not duplicates -- the duration-probe pattern in {@link AssetScanner}
 * (its {@code DURATION_PROBE_POOL} + {@code DURATION_CACHE}). This class owns only
 * the bitmap path.
 */
public final class VideoThumbnailCache {

    private static final String TAG = "VideoThumbCache";
    private static final String DIR_NAME = "vidthumb";
    private static final long MAX_BYTES = 50L * 1024L * 1024L; // 50 MB
    private static final int MAX_FILES = 500;
    private static final int CACHE_VERSION = 1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Memory cache: ~1/8 of app heap, keyed on disk-file name (uri+size+px+v)
    private static final android.util.LruCache<String, Bitmap> MEM_CACHE =
            new android.util.LruCache<String, Bitmap>(calcMemCacheKb()) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    private static int calcMemCacheKb() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        return Math.max(1024, maxKb / 8);
    }

    private static final ExecutorService POOL = new ThreadPoolExecutor(
            2, 2,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(@NonNull Runnable r) {
                    Thread t = new Thread(r, "Vidthumb-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });
    static {
        ((ThreadPoolExecutor) POOL).allowCoreThreadTimeOut(true);
    }

    // ImageView -> Future for cancellation when row scrolls away.
    private static final Map<ImageView, Future<?>> IN_FLIGHT = new WeakHashMap<>();

    private VideoThumbnailCache() {}

    /**
     * Load a video thumbnail into {@code target}. Handles mem/disk cache,
     * placeholder, and failure icon. Call from {@code onBindViewHolder}.
     *
     * @param item    video asset (type must be VIDEO)
     * @param target  ImageView to populate
     * @param thumbPx requested square thumb size in px (e.g. 80dp * density)
     */
    public static void load(@NonNull Context context, @NonNull AssetItem item,
                            @NonNull ImageView target, int thumbPx) {
        if (item.type != AssetItem.Type.VIDEO) return;
        Context app = context.getApplicationContext();
        String key = cacheKey(item, thumbPx);
        String filename = fileNameForKey(key);
        // Tag the view to its current key so stale callbacks are dropped.
        target.setTag(R.id.tag_layer_index, key);
        // Cancel any in-flight for this view (newest-first + scroll-away).
        cancel(target);

        // 1. Memory hit
        Bitmap mem = MEM_CACHE.get(key);
        if (mem != null && !mem.isRecycled()) {
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setBackgroundColor(Color.parseColor("#FF1F1F26"));
            target.setImageBitmap(mem);
            return;
        }

        // 2. Disk hit
        File diskFile = new File(DurableCache.dir(app, DIR_NAME), filename);
        if (diskFile.exists() && diskFile.length() > 0) {
            Bitmap bmp = decodeSampled(diskFile, thumbPx, thumbPx);
            if (bmp != null) {
                MEM_CACHE.put(key, bmp);
                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                target.setBackgroundColor(Color.parseColor("#FF1F1F26"));
                target.setImageBitmap(bmp);
                return;
            } else {
                // Corrupt cache entry — delete so we re-extract.
                diskFile.delete();
            }
        }

        // 3. Placeholder while loading
        target.setScaleType(ImageView.ScaleType.CENTER_CROP);
        target.setBackgroundColor(Color.parseColor("#FF1F1F26"));
        target.setImageDrawable(null);

        // 4. Background extraction
        Future<?> f = POOL.submit(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever r = null;
            try {
                r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(app, item.uri);
                } catch (Exception e) {
                    FLog.d(TAG, "setDataSource failed for " + item.uri + ": " + e.getMessage());
                    postFailure(target, key);
                    return;
                }

                long durationMs = item.durationMs;
                if (durationMs <= 0) {
                    try {
                        String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        if (d != null) durationMs = Long.parseLong(d);
                    } catch (Exception ignored) {}
                }
                // ~10% in, not 0: frame zero is often black / lens cap / hand.
                long timeUs;
                if (durationMs > 0) {
                    timeUs = (long) (durationMs * 0.10 * 1000L);
                    // Clamp away from 0 and from end.
                    if (timeUs < 100_000) timeUs = Math.min(500_000, durationMs * 1000);
                } else {
                    timeUs = 500_000; // 0.5s fallback when duration unknown
                }

                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        frame = r.getScaledFrameAtTime(timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST,
                                thumbPx, thumbPx);
                    } catch (Throwable t) {
                        FLog.d(TAG, "getScaledFrameAtTime failed, fallback: " + t.getMessage());
                    }
                }
                if (frame == null) {
                    try {
                        frame = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (frame != null && (frame.getWidth() > thumbPx * 2 || frame.getHeight() > thumbPx * 2)) {
                            frame = Bitmap.createScaledBitmap(frame, thumbPx, thumbPx, true);
                        }
                    } catch (Exception e) {
                        FLog.d(TAG, "getFrameAtTime failed for " + item.uri + ": " + e.getMessage());
                    }
                }
                if (frame == null) {
                    postFailure(target, key);
                    return;
                }

                // Ensure correct thumb size (scaled path may return exact, fallback may be larger)
                if (frame.getWidth() != thumbPx || frame.getHeight() != thumbPx) {
                    // Center-crop scale to square thumbPx
                    frame = scaleCenterCrop(frame, thumbPx, thumbPx);
                }

                // Write to disk (temp then atomic rename so readers never see half-file)
                File dir = DurableCache.dir(app, DIR_NAME);
                File tmp = new File(dir, filename + ".tmp");
                File dst = new File(dir, filename);
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(tmp);
                    frame.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                    fos.flush();
                    fos.close();
                    fos = null;
                    if (dst.exists()) dst.delete();
                    tmp.renameTo(dst);
                    enforceCap(dir);
                } catch (Exception e) {
                    FLog.d(TAG, "disk cache write failed: " + e.getMessage());
                    tmp.delete();
                } finally {
                    if (fos != null) try { fos.close(); } catch (Exception ignored) {}
                }

                MEM_CACHE.put(key, frame);
                postBitmap(target, key, frame);
            } finally {
                if (r != null) try { r.release(); } catch (Exception ignored) {}
            }
        });
        synchronized (IN_FLIGHT) {
            IN_FLIGHT.put(target, f);
        }
    }

    /** Cancel any in-flight extraction for this view (called on rebind / recycle). */
    public static void cancel(@NonNull ImageView target) {
        synchronized (IN_FLIGHT) {
            Future<?> f = IN_FLIGHT.remove(target);
            if (f != null) f.cancel(true);
        }
    }

    private static void postBitmap(@NonNull ImageView target, @NonNull String key, @NonNull Bitmap bmp) {
        MAIN.post(() -> {
            Object tag = target.getTag(R.id.tag_layer_index);
            if (!key.equals(tag)) return; // view recycled to different item
            target.setScaleType(ImageView.ScaleType.CENTER_CROP);
            target.setBackgroundColor(Color.parseColor("#FF1F1F26"));
            target.setImageBitmap(bmp);
        });
    }

    private static void postFailure(@NonNull ImageView target, @NonNull String key) {
        MAIN.post(() -> {
            Object tag = target.getTag(R.id.tag_layer_index);
            if (!key.equals(tag)) return;
            // Distinct generic-video fallback: dark bg, no bitmap (type badge "movie"
            // remains visible in the adapter). A permanent spinner would read as hung.
            target.setScaleType(ImageView.ScaleType.CENTER);
            target.setBackgroundColor(Color.parseColor("#FF16161B"));
            target.setImageDrawable(null);
        });
    }

    @NonNull
    private static String cacheKey(@NonNull AssetItem item, int thumbPx) {
        return item.uri.toString() + "#" + item.sizeBytes + "#" + thumbPx + "#v" + CACHE_VERSION;
    }

    @NonNull
    private static String fileNameForKey(@NonNull String key) {
        // Simple hash filename; collisions negligible for this cache.
        int h = key.hashCode();
        // Use unsigned hex to avoid '-' issues.
        return Integer.toUnsignedString(h, 16) + "_" + Math.abs(key.length() % 1000) + ".jpg";
    }

    @Nullable
    private static Bitmap decodeSampled(@NonNull File f, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = 1;
            while (opts.outWidth / sample > reqW * 2 || opts.outHeight / sample > reqH * 2) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    @NonNull
    private static Bitmap scaleCenterCrop(@NonNull Bitmap src, int dstW, int dstH) {
        float scale = Math.max((float) dstW / src.getWidth(), (float) dstH / src.getHeight());
        int sw = Math.round(src.getWidth() * scale);
        int sh = Math.round(src.getHeight() * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, sw, sh, true);
        int x = (sw - dstW) / 2;
        int y = (sh - dstH) / 2;
        Bitmap out = Bitmap.createBitmap(scaled, x, y, dstW, dstH);
        if (scaled != src) scaled.recycle();
        if (src != out && src != scaled) src.recycle();
        return out;
    }

    /** Enforce ≤MAX_BYTES and ≤MAX_FILES by deleting oldest files. */
    private static void enforceCap(@NonNull File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return;
            // Sort oldest first.
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            long totalBytes = 0;
            for (File f : files) totalBytes += f.length();
            int idx = 0;
            while ((totalBytes > MAX_BYTES || files.length - idx > MAX_FILES) && idx < files.length) {
                File victim = files[idx++];
                totalBytes -= victim.length();
                victim.delete();
            }
        } catch (Exception e) {
            FLog.d(TAG, "enforceCap failed: " + e.getMessage());
        }
    }

    /** For reporting: total bytes and file count after 100 videos. */
    @NonNull
    public static String cacheStats(@NonNull Context context) {
        File dir = DurableCache.dir(context.getApplicationContext(), DIR_NAME);
        File[] files = dir.listFiles();
        if (files == null) return "0 files, 0 bytes";
        long bytes = 0;
        for (File f : files) bytes += f.length();
        return files.length + " files, " + (bytes / 1024) + " KB (" + bytes + " bytes) in " + dir.getAbsolutePath()
                + " cap " + (MAX_BYTES / 1024 / 1024) + "MB / " + MAX_FILES + " files";
    }
}
