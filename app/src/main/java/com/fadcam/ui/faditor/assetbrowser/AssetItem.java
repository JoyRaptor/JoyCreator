package com.fadcam.ui.faditor.assetbrowser;

import android.net.Uri;
import androidx.annotation.NonNull;

/**
 * Represents a single media file discovered in the pinned asset directory.
 *
 * <p>Used by {@link AssetBrowserPanel} and {@link AssetBrowserAdapter} to
 * display thumbnails and metadata in the asset grid. The same model is
 * passed to {@link com.fadcam.ui.faditor.FaditorEditorActivity} when the
 * user taps or drags an asset into the timeline.</p>
 *
 * <p><b>Design note for future AI developers:</b> This class is intentionally
 * simple (plain data holder). The {@code isUsed} flag is computed by
 * {@link AssetScanner} by matching URI strings against clips already in the
 * project timeline. When multi-layer tracks are added, extend {@code isUsed}
 * to check overlay layers and audio tracks too.</p>
 */
public class AssetItem {

    public enum Type {
        VIDEO,
        IMAGE,
        AUDIO
    }

    @NonNull
    public final Uri uri;

    @NonNull
    public String displayName;

    @NonNull
    public final Type type;

    /** Duration in milliseconds (0 for images). */
    public long durationMs;

    /** File size in bytes (for display). */
    public long sizeBytes;

    /** True if this file is already used by a clip in the current project. */
    public boolean isUsed;

    /** MIME type from DocumentsContract. */
    @NonNull
    public String mimeType;

    public AssetItem(@NonNull Uri uri, @NonNull String displayName,
                     @NonNull Type type, @NonNull String mimeType) {
        this.uri = uri;
        this.displayName = displayName;
        this.type = type;
        this.mimeType = mimeType;
        this.durationMs = 0;
        this.sizeBytes = 0;
        this.isUsed = false;
    }

    /** Short label for display: name without extension. */
    @NonNull
    public String shortLabel() {
        int dot = displayName.lastIndexOf('.');
        return dot > 0 ? displayName.substring(0, dot) : displayName;
    }

    /** File extension (lowercase, without dot). */
    @NonNull
    public String extension() {
        int dot = displayName.lastIndexOf('.');
        return dot >= 0 ? displayName.substring(dot + 1).toLowerCase() : "";
    }

    @NonNull
    @Override
    public String toString() {
        return "AssetItem{" + type + " " + displayName + " (" + durationMs + "ms)"
                + (isUsed ? " [used]" : "") + "}";
    }
}
