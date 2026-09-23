package com.fadcam.playback;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.net.Uri;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.IOException;

/**
 * Utility class to remux fragmented MP4 files to add proper seeking support.
 * 
 * Fragmented MP4 files created by Media3's FragmentedMp4Muxer lack sidx (segment index)
 * boxes which ExoPlayer needs for seeking. This utility remuxes the file using FFmpeg
 * with the +faststart flag to move the moov atom to the beginning and enable seeking.
 * 
 * This is a workaround for ExoPlayer's inability to seek in fMP4 without sidx boxes.
 */
public class FragmentedMp4Remuxer {
    private static final String TAG = "FMp4Remuxer";
    
    public interface RemuxCallback {
        void onRemuxComplete(boolean success, String outputPath);
        void onRemuxProgress(int percent);
    }
    
    private final Context context;
    
    public FragmentedMp4Remuxer(Context context) {
        this.context = context.getApplicationContext();
    }
    
    /**
     * Checks if a file is a fragmented MP4 that needs remuxing.
     *
     * <p>Inspects the first 64 KB of the file for the presence of a
     * {@code moof} (movie fragment) box, which is the definitive marker
     * of a fragmented MP4 container. Fragmented MP4 files recorded by
     * FadCam (or any other recorder using Media3's FragmentedMp4Muxer)
     * lack sidx boxes, so ExoPlayer cannot seek within them.</p>
     *
     * @param file The file to check.
     * @return true if the file appears to be a fragmented MP4.
     */
    public boolean needsRemux(File file) {
        if (file == null || !file.exists() || !file.canRead()) return false;
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".mp4")) return false;

        // Scan the first 64 KB of the file for the 'moof' box type.
        // MP4 box structure: [4-byte size][4-byte type] — we look for ASCII "moof" (0x6D6F6F66).
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            int scanLimit = (int) Math.min(raf.length(), 65536);
            byte[] buf = new byte[scanLimit];
            raf.readFully(buf);

            // Search for the 'moof' box type in the buffer
            byte m = (byte) 'm', o = (byte) 'o', f = (byte) 'f';
            for (int i = 0; i <= buf.length - 4; i++) {
                if (buf[i] == m && buf[i + 1] == o && buf[i + 2] == o && buf[i + 3] == f) {
                    // Verify this looks like a valid box: the 4 bytes before 'moof'
                    // should be the box size (> 8). If i >= 4, check it.
                    if (i >= 4) {
                        int boxSize = ((buf[i - 4] & 0xFF) << 24)
                                    | ((buf[i - 3] & 0xFF) << 16)
                                    | ((buf[i - 2] & 0xFF) << 8)
                                    | (buf[i - 1] & 0xFF);
                        if (boxSize >= 8) {
                            FLog.d(TAG, "Detected fragmented MP4 (moof box at offset "
                                    + (i - 4) + ", size=" + boxSize + "): " + file.getName());
                            return true;
                        }
                    } else {
                        // 'moof' at very start — unlikely but still fragmented
                        FLog.d(TAG, "Detected fragmented MP4 (moof at offset 0): " + file.getName());
                        return true;
                    }
                }
            }
            FLog.d(TAG, "Not a fragmented MP4 (no moof in first " + scanLimit + " bytes): " + file.getName());
            return false;
        } catch (IOException e) {
            FLog.w(TAG, "Could not check file for fMP4 structure: " + file.getName(), e);
            // Fallback: assume FadCam-named files are fragmented
            return name.startsWith("fadcam_");
        }
    }
    
    /**
     * Home of the remuxed files. PERSISTENT (files/), not the cache dir.
     *
     * <p>2026-09-21: a 48-minute export died at ~28 min with ENOENT on its 2.2 GB
     * remuxed copy because remuxes lived in {@code getCacheDir()}, which Android may
     * empty under storage pressure — exactly when a GB-scale export is running. A file
     * an hour-long export depends on must survive pressure; it is cleared only on
     * uninstall (acceptable) or by {@link #pruneStaleRemuxedFiles} below.</p>
     */
    public File remuxDir() {
        File dir = new File(context.getFilesDir(), "faditor/remuxed");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        maybeMigrateLegacyCacheDir(dir);
        return dir;
    }

    /** Old, evictable home. Read-only source for the one-time migration; never written. */
    private File legacyCacheDir() {
        // NOTE: getCacheDir() spelled out on purpose — remuxDir() must never route here.
        //noinspection ConstantConditions
        File c = context.getCacheDir();
        return new File(c, "remuxed");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean legacyMigrated =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * One-time, one-direction move of surviving entries from the old cache dir into
     * {@link #remuxDir()} (same partition, so this is a rename, not a 2.2 GB copy).
     * Skips live {@code .part} temp files; failures are left behind and simply re-remux
     * on next use. Runs once per process.
     */
    private void maybeMigrateLegacyCacheDir(File target) {
        if (!legacyMigrated.compareAndSet(false, true)) return;
        try {
            File legacy = legacyCacheDir();
            File[] files = legacy.listFiles();
            if (files == null || files.length == 0) return;
            int moved = 0;
            for (File f : files) {
                if (!f.isFile() || f.getName().endsWith(".part.mp4")) continue;
                File dest = new File(target, f.getName());
                if (dest.exists()) continue; // already migrated by an earlier run
                if (f.renameTo(dest)) moved++;
            }
            if (moved > 0) {
                FLog.i(TAG, "Migrated " + moved + " remuxed file(s) out of the cache dir");
            }
        } catch (Exception e) {
            FLog.w(TAG, "Remux cache migration failed (will re-remux on demand)", e);
        }
    }

    /**
     * Gets the path for the remuxed version of a file.
     * The remuxed file is stored in a cache directory with a clean name.
     *
     * @param originalFile The original file.
     * @return Path to the remuxed file (may not exist yet).
     */
    public File getRemuxedFile(File originalFile) {
        File cacheDir = remuxDir();
        
        String name = originalFile.getName();
        String baseName = name.substring(0, name.lastIndexOf('.'));
        
        // Use a hash of the full path to handle duplicate filenames from different directories
        String hash = String.valueOf(Math.abs(originalFile.getAbsolutePath().hashCode() % 10000));
        return new File(cacheDir, baseName + "-remuxed-" + hash + ".mp4");
    }

    /**
     * Temp path the remux writes to. Atomically renamed onto {@link #getRemuxedFile} only after
     * the output validates, so an app-kill mid-remux can never leave a husk at the final path
     * (2026-07-16: a killed 2.4GB remux left a full-size moov-less file that passed the size
     * check and poisoned the cache entry — every editor open then failed while the raw file
     * played fine). Keeps the .mp4 suffix so ffmpeg still infers the container from it.
     */
    private File getRemuxTempFile(File originalFile) {
        File finalFile = getRemuxedFile(originalFile);
        return new File(finalFile.getParentFile(),
                finalFile.getName().replace(".mp4", ".part.mp4"));
    }

    /**
     * Cheap structural check: walks the top-level boxes and requires a {@code moov} BEFORE any
     * {@code mdat}. Our remux always writes +faststart, so valid output has moov up front; a
     * remux that died mid-write is ftyp(+free)+mdat with no moov anywhere and fails this in a
     * couple of 8-byte reads.
     */
    private static boolean hasLeadingMoov(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long off = 0;
            long len = raf.length();
            byte[] hdr = new byte[16];
            for (int i = 0; i < 16 && off + 8 <= len; i++) {
                raf.seek(off);
                raf.readFully(hdr, 0, 8);
                long size = ((hdr[0] & 0xFFL) << 24) | ((hdr[1] & 0xFFL) << 16)
                        | ((hdr[2] & 0xFFL) << 8) | (hdr[3] & 0xFFL);
                String type = new String(hdr, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if ("moov".equals(type)) return true;
                if ("mdat".equals(type)) return false; // faststart puts moov before mdat
                if (size == 1) { // 64-bit largesize in the next 8 bytes
                    raf.readFully(hdr, 8, 8);
                    size = 0;
                    for (int b = 8; b < 16; b++) size = (size << 8) | (hdr[b] & 0xFFL);
                } else if (size == 0) {
                    return false; // box runs to EOF and isn't moov
                }
                if (size < 8) return false; // malformed header
                off += size;
            }
        } catch (Exception e) {
            FLog.w(TAG, "hasLeadingMoov: validation read failed for " + f.getName(), e);
            return false;
        }
        return false;
    }
    
    /**
     * Checks if a remuxed version of the file already exists.
     *
     * @param originalFile The original file.
     * @return true if a valid remuxed version exists.
     */
    public boolean hasRemuxedVersion(File originalFile) {
        File remuxed = getRemuxedFile(originalFile);
        if (!remuxed.exists()) {
            return false;
        }
        
        // Check if remuxed file is newer than original
        if (remuxed.lastModified() < originalFile.lastModified()) {
            // Original was modified, delete stale remuxed version
            remuxed.delete();
            return false;
        }
        
        // Check if remuxed file has reasonable size
        if (remuxed.length() < originalFile.length() * 0.9) {
            // Remuxed file is too small, probably corrupted
            remuxed.delete();
            return false;
        }

        // Structural check (2026-07-16): an interrupted remux used to leave a FULL-SIZE
        // moov-less husk that passed the size check and permanently poisoned this entry —
        // the editor then failed every open ("Loading finished before preparation is
        // complete") while the raw file played fine. Self-heals old poisoned caches.
        if (!hasLeadingMoov(remuxed)) {
            FLog.w(TAG, "Remuxed file has no leading moov (interrupted remux?) — deleting "
                    + remuxed.getName());
            remuxed.delete();
            return false;
        }

        return true;
    }
    
    /**
     * Remuxes a fragmented MP4 file to add proper seeking support.
     * This is a synchronous operation that blocks until complete.
     *
     * @param inputFile The input fragmented MP4 file.
     * @return The remuxed file, or null if remuxing failed.
     */
    public File remuxSync(File inputFile) {
        File outputFile = getRemuxedFile(inputFile);
        
        // Check if already remuxed
        if (hasRemuxedVersion(inputFile)) {
            FLog.d(TAG, "Using existing remuxed file: " + outputFile.getName());
            return outputFile;
        }
        
        FLog.i(TAG, "Remuxing: " + inputFile.getName() + " -> " + outputFile.getName());

        // Kill-safe: ffmpeg writes to a temp path; the final name only ever appears via an
        // atomic rename AFTER the output validates. An app-kill mid-remux leaves only a
        // .part.mp4 (ignored by getRemuxedFile, cleaned up on the next attempt).
        File tempFile = getRemuxTempFile(inputFile);
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = tempFile.getAbsolutePath();

        // Delete any existing output/temp files
        if (outputFile.exists()) {
            outputFile.delete();
        }
        if (tempFile.exists()) {
            tempFile.delete();
        }

        // FFmpeg command to remux with faststart
        // -i input: input file
        // -c copy: copy streams without re-encoding (fast)
        // -movflags +faststart: move moov atom to beginning for seeking
        // -y: overwrite output
        String ffmpegCmd = String.format(
            "-i \"%s\" -c copy -movflags +faststart -y \"%s\"",
            inputPath, outputPath
        );

        FLog.d(TAG, "FFmpeg command: " + ffmpegCmd);

        try {
            FFmpegSession session = FFmpegKit.execute(ffmpegCmd);

            if (ReturnCode.isSuccess(session.getReturnCode())
                    && hasLeadingMoov(tempFile)
                    && tempFile.renameTo(outputFile)) {
                FLog.i(TAG, "Remux successful: " + outputFile.getName() +
                           " (" + outputFile.length() / 1024 + " KB)");
                return outputFile;
            } else {
                FLog.e(TAG, "Remux failed with code: " + session.getReturnCode()
                        + " (or output failed validation/rename)");
                FLog.e(TAG, "FFmpeg output: " + session.getOutput());

                // Clean up failed output
                if (tempFile.exists()) {
                    tempFile.delete();
                }
                return null;
            }
        } catch (Exception e) {
            FLog.e(TAG, "Remux exception", e);
            if (tempFile.exists()) {
                tempFile.delete();
            }
            return null;
        }
    }
    
    /**
     * Remuxes a fragmented MP4 file asynchronously.
     *
     * @param inputFile The input fragmented MP4 file.
     * @param callback Callback for completion and progress.
     */
    public void remuxAsync(File inputFile, RemuxCallback callback) {
        File outputFile = getRemuxedFile(inputFile);
        
        // Check if already remuxed
        if (hasRemuxedVersion(inputFile)) {
            FLog.d(TAG, "Using existing remuxed file: " + outputFile.getName());
            if (callback != null) {
                callback.onRemuxComplete(true, outputFile.getAbsolutePath());
            }
            return;
        }
        
        FLog.i(TAG, "Async remuxing: " + inputFile.getName());

        // Kill-safe temp-then-rename, same as remuxSync (see there for the 2026-07-16 husk story).
        File tempFile = getRemuxTempFile(inputFile);
        String inputPath = inputFile.getAbsolutePath();
        String outputPath = tempFile.getAbsolutePath();

        // Delete any existing output/temp files
        if (outputFile.exists()) {
            outputFile.delete();
        }
        if (tempFile.exists()) {
            tempFile.delete();
        }

        String ffmpegCmd = String.format(
            "-i \"%s\" -c copy -movflags +faststart -y \"%s\"",
            inputPath, outputPath
        );

        FFmpegKit.executeAsync(ffmpegCmd, session -> {
            boolean success = ReturnCode.isSuccess(session.getReturnCode())
                    && hasLeadingMoov(tempFile)
                    && tempFile.renameTo(outputFile);

            if (success) {
                FLog.i(TAG, "Async remux successful: " + outputFile.getName());
            } else {
                FLog.e(TAG, "Async remux failed: " + session.getReturnCode()
                        + " (or output failed validation/rename)");
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }

            if (callback != null) {
                callback.onRemuxComplete(success, success ? outputFile.getAbsolutePath() : null);
            }
        }, log -> {
            // Log callback - could parse for progress
            FLog.v(TAG, "FFmpeg: " + log.getMessage());
        }, statistics -> {
            // Progress callback
            if (callback != null && statistics.getTime() > 0) {
                // Estimate progress based on time processed
                // This is rough since we don't know total duration here
                callback.onRemuxProgress((int) (statistics.getTime() / 1000));
            }
        });
    }
    
    /**
     * Cleans up cached remuxed files.
     *
     * @param maxAgeDays Maximum age in days for cached files.
     */
    public void cleanupCache(int maxAgeDays) {
        File cacheDir = remuxDir();
        if (!cacheDir.exists()) {
            return;
        }
        
        long maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000;
        long now = System.currentTimeMillis();
        
        File[] files = cacheDir.listFiles();
        if (files == null) return;
        
        int deleted = 0;
        for (File file : files) {
            if (now - file.lastModified() > maxAgeMs) {
                if (file.delete()) {
                    deleted++;
                }
            }
        }
        
        if (deleted > 0) {
            FLog.i(TAG, "Cleaned up " + deleted + " cached remuxed files");
        }
    }
    
    /**
     * Prune stale remuxed files. Safe to call when no export/remux is running (e.g.
     * after an export finishes): deletes interrupted-remux {@code .part} files older
     * than a day (never a live one), finals untouched for {@code maxAgeDays}, then —
     * if the directory still exceeds {@code maxTotalBytes} — oldest first.
     * The file the export just used is hours fresh, so a post-export prune can never
     * take it.
     *
     * @param maxAgeDays   finals untouched this long are deleted.
     * @param maxTotalBytes size cap enforced oldest-first after the age pass.
     * @return number of files deleted.
     */
    public int pruneStaleRemuxedFiles(int maxAgeDays, long maxTotalBytes) {
        File dir = remuxDir();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) return 0;
        long now = System.currentTimeMillis();
        long partTtlMs = 24L * 60 * 60 * 1000;
        long maxAgeMs = maxAgeDays * 24L * 60 * 60 * 1000;
        int deleted = 0;
        java.util.List<File> finals = new java.util.ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) continue;
            if (f.getName().endsWith(".part.mp4")) {
                if (now - f.lastModified() > partTtlMs && f.delete()) deleted++;
                continue;
            }
            if (now - f.lastModified() > maxAgeMs) {
                if (f.delete()) deleted++;
            } else {
                finals.add(f);
            }
        }
        if (maxTotalBytes > 0) {
            long total = 0;
            for (File f : finals) total += f.length();
            if (total > maxTotalBytes) {
                finals.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (File f : finals) {
                    if (total <= maxTotalBytes) break;
                    long len = f.length();
                    if (f.delete()) {
                        deleted++;
                        total -= len;
                    }
                }
            }
        }
        if (deleted > 0) {
            FLog.i(TAG, "Pruned " + deleted + " stale remuxed file(s)");
        }
        return deleted;
    }

    /**
     * Gets the total size of all cached remuxed files in bytes.
     *
     * @return Size in bytes, or 0 if cache directory doesn't exist.
     */
    public long getTotalCacheSize() {
        File cacheDir = remuxDir();
        if (!cacheDir.exists()) return 0;
        
        long totalSize = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }
    
    /**
     * Gets the size of cached files matching a specific filename prefix.
     * Used to calculate cache size for a specific project/video.
     *
     * @param prefix The filename prefix to match (e.g., "clip_001").
     * @return Size in bytes of matching cached files.
     */
    public long getCacheSizeForPrefix(String prefix) {
        File cacheDir = remuxDir();
        if (!cacheDir.exists()) return 0;
        
        long totalSize = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith(prefix)) {
                    totalSize += file.length();
                }
            }
        }
        return totalSize;
    }
    
    /**
     * Delete all cached remuxed files matching a specific filename prefix.
     * Call this when a project or clip is deleted to clean up orphaned cache files.
     *
     * @param prefix The filename prefix to match (e.g., "clip_001").
     * @return Number of files deleted.
     */
    public int deleteCacheForPrefix(String prefix) {
        File cacheDir = remuxDir();
        if (!cacheDir.exists()) return 0;
        
        int deleted = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith(prefix)) {
                    if (file.delete()) {
                        FLog.d(TAG, "Deleted cache file: " + file.getName());
                        deleted++;
                    }
                }
            }
        }
        
        if (deleted > 0) {
            FLog.i(TAG, "Deleted " + deleted + " cached files for prefix: " + prefix);
        }
        
        return deleted;
    }
    
    /**
     * Clear all remuxed cache files.
     *
     * @return Number of files deleted.
     */
    public int clearAllCache() {
        File cacheDir = remuxDir();
        if (!cacheDir.exists()) return 0;
        
        int deleted = 0;
        File[] files = cacheDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.delete()) {
                    deleted++;
                }
            }
        }
        
        // Try to delete the cache directory itself if empty
        if (cacheDir.listFiles() != null && cacheDir.listFiles().length == 0) {
            cacheDir.delete();
        }
        
        FLog.i(TAG, "Cleared cache: deleted " + deleted + " files");
        return deleted;
    }
}
