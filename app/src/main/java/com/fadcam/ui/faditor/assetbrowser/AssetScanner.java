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

            // Enumerate children
            for (DocumentFile child : treeDir.listFiles()) {
                if (child.isFile()) {
                    AssetItem item = createAssetItem(child);
                    if (item != null) {
                        item.isUsed = usedUris.contains(item.uri.toString());
                        items.add(item);
                    }
                }
            }

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

        // Probe duration for video and audio
        if (type == AssetItem.Type.VIDEO || type == AssetItem.Type.AUDIO) {
            item.durationMs = probeDuration(doc.getUri());
        }

        return item;
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
