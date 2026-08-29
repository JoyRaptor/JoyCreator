package com.fadcam.ui.faditor.project;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Resolves {@code project://} URIs against a project directory and provides
 * content-hash utilities for bundling / relink auto-match.
 *
 * <p>Extends the {@code project://} scheme already used in
 * {@link ProjectStorage} (b22af2cd) — do not invent a second scheme.</p>
 */
public final class AssetResolver {

    public static final String PROJECT_URI_PREFIX =
            com.fadcam.ui.faditor.model.FaditorProject.PROJECT_URI_SCHEME + "://";

    private AssetResolver() {}

    /** Resolve a stored URI string (may be {@code project://}) to a runtime {@link Uri}. */
    @NonNull
    public static Uri resolve(@NonNull File projectDir, @NonNull String stored) {
        if (stored.startsWith(PROJECT_URI_PREFIX)) {
            String rel = stored.substring(PROJECT_URI_PREFIX.length());
            return Uri.fromFile(new File(projectDir, rel));
        }
        return Uri.parse(stored);
    }

    /** Convert a runtime URI string to its storage form (relativize if inside projectDir). */
    @Nullable
    public static String toStorage(@NonNull File projectDir, @Nullable String uriStr) {
        if (uriStr == null) return null;
        if (uriStr.startsWith("file://")) {
            String path = Uri.parse(uriStr).getPath();
            if (path != null) {
                String base = projectDir.getAbsolutePath();
                if (path.startsWith(base + File.separator)) {
                    String rel = path.substring(base.length() + 1).replace(File.separatorChar, '/');
                    return PROJECT_URI_PREFIX + rel;
                }
            }
        }
        // Also handle "file:" without "//" as used by FontLibrary keys (TextOverlayItem fontFamily).
        if (uriStr.startsWith("file:")) {
            String path = uriStr.substring(5);
            // Uri.parse("file:xxx") may not give path, so use raw substring.
            if (path.startsWith("//")) path = path.substring(2);
            String base = projectDir.getAbsolutePath();
            if (path.startsWith(base + File.separator) || path.startsWith(base + "/")) {
                String rel = path.substring(base.length() + 1).replace(File.separatorChar, '/');
                return PROJECT_URI_PREFIX + rel;
            }
        }
        return uriStr;
    }

    /** True if {@code stored} is already a {@code project://} reference. */
    public static boolean isProjectUri(@Nullable String stored) {
        return stored != null && stored.startsWith(PROJECT_URI_PREFIX);
    }

    /** True if the resolved file lives inside projectDir (already consolidated). */
    public static boolean isInsideProject(@NonNull File projectDir, @NonNull Uri uri) {
        if (!"file".equals(uri.getScheme())) return false;
        String path = uri.getPath();
        if (path == null) return false;
        String base = projectDir.getAbsolutePath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    /** Overload for fontFamily style "file:" strings. */
    public static boolean isInsideProjectFont(@NonNull File projectDir, @NonNull String fileColonPath) {
        if (!fileColonPath.startsWith("file:")) return false;
        String path = fileColonPath.substring(5);
        if (path.startsWith("//")) path = path.substring(2);
        String base = projectDir.getAbsolutePath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    /** Resolve a Uri to a File if possible (file:// or project:// → file). Returns null for content://. */
    @Nullable
    public static File toFile(@NonNull File projectDir, @NonNull Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            return p != null ? new File(p) : null;
        }
        String s = uri.toString();
        if (s.startsWith(PROJECT_URI_PREFIX)) {
            String rel = s.substring(PROJECT_URI_PREFIX.length());
            return new File(projectDir, rel);
        }
        return null;
    }

    /**
     * SHA-256 hex of the bytes reachable via {@code uri} (file:// or content://).
     * Returns null if unreadable. Size-limited by caller.
     */
    @Nullable
    public static String sha256(@NonNull Context ctx, @NonNull Uri uri) {
        try (InputStream in = openInput(ctx, uri)) {
            if (in == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 of a plain File. */
    @Nullable
    public static String sha256(@NonNull File file) {
        if (!file.exists() || !file.canRead()) return null;
        try (InputStream in = new java.io.FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static InputStream openInput(@NonNull Context ctx, @NonNull Uri uri) throws Exception {
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p == null) return null;
            return new java.io.FileInputStream(new File(p));
        }
        // project:// → file inside project: handled by caller via toFile; fallback to file
        String s = uri.toString();
        if (s.startsWith(PROJECT_URI_PREFIX)) return null;
        return ctx.getContentResolver().openInputStream(uri);
    }

    /** Open an InputStream for any uri (file://, content://, or project:// with projectDir). */
    @Nullable
    public static InputStream open(@NonNull Context ctx, @NonNull File projectDir, @NonNull Uri uri) {
        String s = uri.toString();
        if (s.startsWith(PROJECT_URI_PREFIX)) {
            String rel = s.substring(PROJECT_URI_PREFIX.length());
            File f = new File(projectDir, rel);
            try { return new java.io.FileInputStream(f); } catch (Exception e) { return null; }
        }
        try {
            if ("file".equals(uri.getScheme())) {
                String p = uri.getPath();
                if (p == null) return null;
                return new java.io.FileInputStream(new File(p));
            }
            return ctx.getContentResolver().openInputStream(uri);
        } catch (Exception e) { return null; }
    }

    /** Size in bytes, or -1 if unknown. */
    public static long size(@NonNull Context ctx, @NonNull File projectDir, @NonNull Uri uri) {
        String s = uri.toString();
        if (s.startsWith(PROJECT_URI_PREFIX)) {
            String rel = s.substring(PROJECT_URI_PREFIX.length());
            File f = new File(projectDir, rel);
            return f.exists() ? f.length() : -1;
        }
        if ("file".equals(uri.getScheme())) {
            String p = uri.getPath();
            if (p != null) { File f = new File(p); return f.exists() ? f.length() : -1; }
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return -1;
            // For content://, try to query size via ContentResolver if length unknown.
            // Fallback: return -1 and let estimator use 0.
            return -1;
        } catch (Exception e) { return -1; }
    }

    /** Best-effort filename from a Uri. */
    @Nullable
    public static String filename(@NonNull Uri uri) {
        String s = uri.getLastPathSegment();
        if (s == null) s = uri.getPath();
        if (s == null) return null;
        try { s = java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception ignored) {}
        int colon = s.lastIndexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        return s.isEmpty() ? null : s;
    }

    /** Sanitize a filename (strip path separators, control chars). */
    @NonNull
    public static String sanitize(@NonNull String name) {
        String out = name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        if (out.isEmpty()) out = "file";
        // Limit length to avoid filesystem issues.
        if (out.length() > 120) {
            int dot = out.lastIndexOf('.');
            String ext = dot >= 0 ? out.substring(dot) : "";
            out = out.substring(0, 120 - ext.length()) + ext;
        }
        return out;
    }
}
