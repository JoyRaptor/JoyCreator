package com.fadcam.ui.faditor.ai;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a shared "B-roll bucket" — a folder of images and videos that
 * any project (or the AI) can pull from for overlays, b-roll inserts, etc.
 *
 * <p>The bucket defaults to {@code Pictures/FadCam/assets/} on external
 * storage, but can be set per-project or globally via a SAF URI.</p>
 */
public class BRollBucket {

    private static final String TAG = "BRollBucket";
    private static final String PREF_ASSETS_URI = "broll_assets_uri";

    @NonNull private final Context context;

    public BRollBucket(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Get the default assets directory (file-based, always accessible).
     */
    @NonNull
    public File getDefaultAssetsDir() {
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES), "FadCam/assets");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Set a custom assets folder URI (SAF or file://).
     */
    public void setAssetsUri(@Nullable String uri) {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        if (uri == null || uri.isEmpty()) {
            prefs.sharedPreferences.edit().remove(PREF_ASSETS_URI).apply();
        } else {
            prefs.sharedPreferences.edit().putString(PREF_ASSETS_URI, uri).apply();
        }
    }

    @Nullable
    public String getAssetsUri() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        return prefs.sharedPreferences.getString(PREF_ASSETS_URI, null);
    }

    /**
     * List all image/video/font files in the assets bucket.
     * Scans the default file directory AND a custom SAF tree URI if set.
     */
    @NonNull
    public List<AssetEntry> listAssets() {
        List<AssetEntry> result = new ArrayList<>();

        // Scan default directory
        File dir = getDefaultAssetsDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    addIfAsset(result, f.getName(), Uri.fromFile(f).toString(),
                            f.length(), f.getName().toLowerCase());
                }
            }
        }

        // Scan fonts subdirectory
        File fontsDir = getDefaultFontsDir();
        File[] fontFiles = fontsDir.listFiles();
        if (fontFiles != null) {
            for (File f : fontFiles) {
                if (f.isFile()) {
                    addIfAsset(result, f.getName(), Uri.fromFile(f).toString(),
                            f.length(), f.getName().toLowerCase());
                }
            }
        }

        // Scan custom SAF tree URI if set
        String customUri = getAssetsUri();
        if (customUri != null && !customUri.isEmpty() && customUri.startsWith("file://")) {
            File customDir = new File(Uri.parse(customUri).getPath());
            if (customDir.exists() && customDir.isDirectory()) {
                scanDirectoryRecursive(result, customDir, 3);
            }
        }

        return result;
    }

    private void scanDirectoryRecursive(List<AssetEntry> result, File dir, int maxDepth) {
        if (maxDepth <= 0) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                addIfAsset(result, f.getName(), Uri.fromFile(f).toString(),
                        f.length(), f.getName().toLowerCase());
            } else if (f.isDirectory()) {
                scanDirectoryRecursive(result, f, maxDepth - 1);
            }
        }
    }

    private void addIfAsset(List<AssetEntry> result, String name, String uri,
                            long size, String lower) {
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp") || lower.endsWith(".mp4")
                || lower.endsWith(".mov") || lower.endsWith(".gif")
                || lower.endsWith(".ttf") || lower.endsWith(".otf")) {
            boolean isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov");
            boolean isFont = lower.endsWith(".ttf") || lower.endsWith(".otf");
            String type = isVideo ? "video" : (isFont ? "font" : "image");
            result.add(new AssetEntry(name, uri, size, isVideo, isFont, type));
        }
    }

    @NonNull
    public File getDefaultFontsDir() {
        File dir = new File(getDefaultAssetsDir(), "fonts");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Get a text listing of assets for the AI system prompt. */
    @NonNull
    public String getAssetsSummary() {
        List<AssetEntry> assets = listAssets();
        if (assets.isEmpty()) {
            return "No B-roll assets found. Place images/videos in "
                    + getDefaultAssetsDir().getAbsolutePath();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("B-roll bucket (").append(assets.size()).append(" assets in ")
                .append(getDefaultAssetsDir().getName()).append("/):\n");
        for (AssetEntry a : assets) {
            sb.append("  ").append(a.name)
                    .append(" (").append(a.isVideo ? "video" : "image")
                    .append(", ").append(a.sizeBytes / 1024).append("KB)\n");
        }
        return sb.toString();
    }

    /** One asset in the bucket. */
    public static class AssetEntry {
        @NonNull public final String name;
        @NonNull public final String uri;
        public final long sizeBytes;
        public final boolean isVideo;
        public final boolean isFont;
        @NonNull public final String type;

        public AssetEntry(@NonNull String name, @NonNull String uri,
                          long sizeBytes, boolean isVideo) {
            this(name, uri, sizeBytes, isVideo, false, isVideo ? "video" : "image");
        }

        public AssetEntry(@NonNull String name, @NonNull String uri,
                          long sizeBytes, boolean isVideo, boolean isFont,
                          @NonNull String type) {
            this.name = name;
            this.uri = uri;
            this.sizeBytes = sizeBytes;
            this.isVideo = isVideo;
            this.isFont = isFont;
            this.type = type;
        }
    }
}
