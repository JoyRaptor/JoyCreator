package com.fadcam.ui.faditor.assetbrowser;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans a SAF (Storage Access Framework) tree URI for media files and
 * builds a list of {@link AssetItem}s, marking which ones are already
 * in use by the current project.
 *
 * <p><b>For future AI developers:</b> This scanner runs on a background
 * thread (called from {@link AssetBrowserPanel}). It uses
 * {@link DocumentFile#fromTreeUri} + {@code listFiles()} which is the
 * standard SAF traversal pattern. Duration probing for video/audio files
 * uses {@link android.media.MediaMetadataRetriever} — this is slow for
 * large directories, so consider caching results or making it lazy.</p>
 */
public class AssetScanner {

    private static final String TAG = "AssetScanner";

    // File extensions to recognize (lowercase)
    private static final String[] VIDEO_EXTS = {
            "mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v", "flv"
    };
    private static final String[] IMAGE_EXTS = {
            "jpg", "jpeg", "png", "webp", "bmp", "gif"
    };
    private static final String[] AUDIO_EXTS = {
            "mp3", "wav", "aac", "m4a", "ogg", "flac", "wma", "opus"
    };

    /**
     * Small fixed-size pool for MediaMetadataRetriever duration probes. Directory scans can
     * contain dozens of video/audio files; probing them one-at-a-time on the scanner thread
     * (the previous behavior) serializes what is mostly I/O + native decode wait, so a handful
     * of worker threads lets probes overlap. Kept small (4) since each MMR instance holds a
     * native codec/extractor resource. Daemon threads so the pool never blocks app shutdown.
     */
    private static final int DURATION_PROBE_THREADS = 3;

    /**
     * Cache of previously-probed durations, keyed by "uri#size" so a changed/replaced file
     * (different size) is re-probed. Static + process-lifetime: the asset browser is opened
     * repeatedly while editing a project, and re-opening the same folder should not re-run
     * MediaMetadataRetriever against files we already probed. Unbounded growth is not a
     * practical concern here (asset folders are user-curated, at most low hundreds of files).
     */
    private static final ConcurrentHashMap<String, Long> DURATION_CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService DURATION_PROBE_POOL = new ThreadPoolExecutor(
            DURATION_PROBE_THREADS, DURATION_PROBE_THREADS,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(1);
                @Override
                public Thread newThread(@NonNull Runnable r) {
                    Thread t = new Thread(r, "AssetScanner-MMR-" + count.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });
    static {
        ((ThreadPoolExecutor) DURATION_PROBE_POOL).allowCoreThreadTimeOut(true);
    }

    @NonNull
    private final Context context;

    public AssetScanner(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Scan the given tree URI for media files.
     *
     * @param treeUriStr  SAF tree URI as string (content://com.android.externalstorage.documents/tree/...)
     * @param timeline    Current project timeline (for marking used files). May be null.
     * @return Sorted list of media items (videos first, then images, then audio).
     */
    @NonNull
    public List<AssetItem> scan(@NonNull String treeUriStr, @Nullable Timeline timeline) {
        List<AssetItem> items = new ArrayList<>();
        try {
            Uri treeUri = Uri.parse(treeUriStr);
            DocumentFile treeDir = DocumentFile.fromTreeUri(context, treeUri);
            if (treeDir == null || !treeDir.isDirectory()) {
                FLog.w(TAG, "Tree directory not accessible: " + treeUriStr);
                return items;
            }

            // Build set of used URIs from the project
            java.util.Set<String> usedUris = new java.util.HashSet<>();
            if (timeline != null) {
                for (Clip c : timeline.getClips()) {
                    usedUris.add(c.getSourceUri().toString());
                }
                for (AudioClip ac : timeline.getAudioClips()) {
                    usedUris.add(ac.getSourceUri().toString());
                }
            }

            // Enumerate children (fast metadata only — no MMR probe here)
            for (DocumentFile child : treeDir.listFiles()) {
                if (child.isFile()) {
                    AssetItem item = createAssetItem(child);
                    if (item != null) {
                        item.isUsed = usedUris.contains(item.uri.toString());
                        items.add(item);
                    }
                }
            }

            // Probe durations for video/audio items in parallel on the small MMR pool instead
            // of serially inline — this is the slow part of a scan (native retriever setup per
            // file); overlapping them cuts wall time roughly by the pool width for large folders.
            probeDurationsInParallel(items);

            // Sort: videos first, then images, then audio, then by name
            items.sort((a, b) -> {
                int typeCmp = Integer.compare(a.type.ordinal(), b.type.ordinal());
                if (typeCmp != 0) return typeCmp;
                return a.displayName.compareToIgnoreCase(b.displayName);
            });

        } catch (Exception e) {
            FLog.e(TAG, "Failed to scan directory: " + treeUriStr, e);
        }
        return items;
    }

    @Nullable
    private AssetItem createAssetItem(@NonNull DocumentFile doc) {
        String name = doc.getName();
        if (name == null) return null;

        String ext = getExtension(name);
        AssetItem.Type type = classifyByExtension(ext);
        if (type == null) {
            // Try MIME type as fallback
            String mime = doc.getType();
            if (mime != null) {
                if (mime.startsWith("video/")) type = AssetItem.Type.VIDEO;
                else if (mime.startsWith("image/")) type = AssetItem.Type.IMAGE;
                else if (mime.startsWith("audio/")) type = AssetItem.Type.AUDIO;
            }
        }
        if (type == null) return null; // Not a media file

        String mime = doc.getType();
        if (mime == null) mime = type == AssetItem.Type.VIDEO ? "video/*"
                : type == AssetItem.Type.IMAGE ? "image/*" : "audio/*";

        AssetItem item = new AssetItem(doc.getUri(), name, type, mime);
        item.sizeBytes = doc.length();
        // Duration (video/audio only) is probed afterward in parallel — see probeDurationsInParallel.

        return item;
    }

    /**
     * Probes durations for all video/audio items using the shared MMR pool, waiting for all
     * probes to finish before returning (keeps {@link #scan} synchronous — callers already run
     * it off the UI thread and expect a fully-populated list back).
     */
    private void probeDurationsInParallel(@NonNull List<AssetItem> items) {
        List<AssetItem> needsDuration = new ArrayList<>();
        for (AssetItem item : items) {
            if (item.type != AssetItem.Type.VIDEO && item.type != AssetItem.Type.AUDIO) continue;
            Long cached = DURATION_CACHE.get(cacheKey(item));
            if (cached != null) {
                // Already probed this exact (uri, size) pair in a prior scan — skip MMR entirely.
                item.durationMs = cached;
            } else {
                needsDuration.add(item);
            }
        }
        if (needsDuration.isEmpty()) return;

        CountDownLatch latch = new CountDownLatch(needsDuration.size());
        for (AssetItem item : needsDuration) {
            DURATION_PROBE_POOL.execute(() -> {
                try {
                    long duration = probeDuration(item.uri);
                    item.durationMs = duration;
                    if (duration > 0) {
                        DURATION_CACHE.put(cacheKey(item), duration);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            // Generous cap so a stuck retriever on one file can't hang the whole scan forever.
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Cache key that changes if the underlying file is replaced with a different-sized one. */
    @NonNull
    private static String cacheKey(@NonNull AssetItem item) {
        return item.uri.toString() + "#" + item.sizeBytes;
    }

    @NonNull
    private String getExtension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    @Nullable
    private AssetItem.Type classifyByExtension(@NonNull String ext) {
        for (String e : VIDEO_EXTS) if (e.equals(ext)) return AssetItem.Type.VIDEO;
        for (String e : IMAGE_EXTS) if (e.equals(ext)) return AssetItem.Type.IMAGE;
        for (String e : AUDIO_EXTS) if (e.equals(ext)) return AssetItem.Type.AUDIO;
        return null;
    }

    /**
     * Probe media duration using MediaMetadataRetriever.
     * Returns 0 on failure.
     */
    private long probeDuration(@NonNull Uri uri) {
        android.media.MediaMetadataRetriever r = null;
        try {
            r = new android.media.MediaMetadataRetriever();
            r.setDataSource(context, uri);
            String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) return Long.parseLong(d);
        } catch (Exception e) {
            FLog.d(TAG, "Duration probe failed for " + uri + ": " + e.getMessage());
        } finally {
            if (r != null) {
                try { r.release(); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    /**
     * Get a human-readable path for display from a tree URI string.
     * Extracts the folder name from the SAF URI path.
     */
    @NonNull
    public static String getDisplayPath(@NonNull String treeUriStr) {
        try {
            Uri uri = Uri.parse(treeUriStr);
            String docId = DocumentsContract.getTreeDocumentId(uri);
            // docId format: "primary:Path/To/Folder" or "ABCD-1234:Path/To/Folder"
            int colon = docId.indexOf(':');
            if (colon >= 0) {
                String path = docId.substring(colon + 1);
                if (path.isEmpty()) {
                    String storageId = docId.substring(0, colon);
                    return "primary".equals(storageId) ? "Internal Storage" : storageId;
                }
                return "/" + path;
            }
            return docId;
        } catch (Exception e) {
            return treeUriStr;
        }
    }
}
