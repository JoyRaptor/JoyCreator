package com.fadcam.vislab.room;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * A look OWNS the pictures of the sprite sheets it plays (Codex audit 11-15, XROOM-03).
 *
 * <p>A sheet picked from a Studio project or the character library used to keep links into that
 * project's folder, so deleting the project (which removes its whole folder) left the look
 * pointing at nothing, and the layer quietly played the built-in Joy record instead. Now the
 * pictures are copied into VisLab's own folder when the sheet is picked, and the look links to
 * the copies. Same picture picked twice: one copy (named by where it came from, its size and its
 * time). A picture that cannot be copied keeps its old link, so nothing is worse than before.</p>
 *
 * <p>Plain Java on files, no Android, so the JVM harness tests it. Call it off the main thread:
 * a long image sequence is many files.</p>
 */
final class SheetCopies {

    /** Under the app's files folder, beside the projects. */
    static final String FOLDER = "vislab/sheets";

    private SheetCopies() {}

    /**
     * The sheet with every picture that lives outside {@code home} copied into it, and its links
     * changed to the copies. Links that are not files (a picture the person picked from the phone
     * keeps its own permission) and pictures already in {@code home} stay as they are.
     */
    @NonNull
    static JsonObject own(@NonNull File home, @NonNull JsonObject sheetJson) {
        SpriteSheet s;
        try {
            s = SpriteSheet.fromJson(sheetJson);
        } catch (RuntimeException e) {
            return sheetJson;
        }
        boolean changed = false;
        String u = s.getSheetUri();
        if (!u.isEmpty()) {
            String o = ownLink(home, u);
            if (!o.equals(u)) { s.setSheetUri(o); changed = true; }
        }
        List<String> frames = s.getFrameUris();
        for (int i = 0; i < frames.size(); i++) {
            String f = frames.get(i);
            String o = ownLink(home, f);
            if (!o.equals(f)) { frames.set(i, o); changed = true; }
        }
        return changed ? s.toJson() : sheetJson;
    }

    /**
     * True when the sheet links to a picture FILE that is no longer there: the layer then says so
     * and offers to choose the sheet again, instead of playing something else.
     */
    static boolean missing(@Nullable JsonObject sheetJson) {
        if (sheetJson == null) return false;
        try {
            SpriteSheet s = SpriteSheet.fromJson(sheetJson);
            if (!s.getSheetUri().isEmpty() && gone(s.getSheetUri())) return true;
            for (String f : s.getFrameUris()) if (gone(f)) return true;
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private static boolean gone(@NonNull String link) {
        File f = fileOf(link);
        return f != null && !f.isFile();
    }

    @NonNull
    private static String ownLink(@NonNull File home, @NonNull String link) {
        File src = fileOf(link);
        if (src == null || !src.isFile() || inside(home, src)) return link;
        try {
            File dest = new File(home, fingerprint(src) + extension(src.getName()));
            if (!dest.isFile() || dest.length() != src.length()) copy(src, dest);
            return "file://" + dest.getAbsoluteFile().toURI().getRawPath();
        } catch (IOException | RuntimeException e) {
            return link;   // keep the old link: never worse than before
        }
    }

    /** The file a link names, or null when it is not a file link (content://, android.resource). */
    @Nullable
    static File fileOf(@NonNull String link) {
        try {
            if (link.startsWith("file:")) return new File(new URI(link));
            if (link.startsWith("/")) return new File(link);
        } catch (Exception ignored) {
            // not a link we can read as a file
        }
        return null;
    }

    private static boolean inside(@NonNull File home, @NonNull File f) {
        String h = home.getAbsolutePath() + File.separator;
        return f.getAbsolutePath().startsWith(h);
    }

    @NonNull
    private static String fingerprint(@NonNull File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            String id = f.getAbsolutePath() + '|' + f.length() + '|' + f.lastModified();
            byte[] d = md.digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    @NonNull
    private static String extension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 6) return "";
        String ext = name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        for (int i = 1; i < ext.length(); i++) if (!Character.isLetterOrDigit(ext.charAt(i))) return "";
        return ext;
    }

    /** Written beside, then renamed: a half-written copy never stands in for the picture. */
    private static void copy(@NonNull File src, @NonNull File dest) throws IOException {
        File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder " + dir);
        File tmp = new File(dir, dest.getName() + ".part");
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (dest.exists() && !dest.delete()) { tmp.delete(); throw new IOException("busy " + dest); }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("rename " + dest); }
    }
}
