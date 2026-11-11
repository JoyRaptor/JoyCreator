package com.fadcam.joyfolder.studio;

import com.fadcam.joyfolder.JoyFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Files that live INSIDE a project's own folder (sprite sheet pictures, a board sent from Joy
 * Brush, the code of an AI slide), across a round trip through the Joy Creator folder.
 *
 * <p>The folder copy of a project holds such a file beside it and refers to it by its folder path.
 * A restore used to leave it there and point the project at the folder file. That is right for a
 * video the person can see and move; for a file the app made for itself it is wrong twice: the
 * project stops being self-contained, and some readers only understand a path on the phone (a
 * slide's code was read as a file path, so a restored slide showed nothing). Work-loss audit
 * 2026-11-11: remember where inside the project each such file was, and put it back there.</p>
 *
 * <p>No Android: tested by {@code tools/jvm-harness/run-sidecars.sh}.</p>
 */
public final class ProjectLocals {

    /** Kinds of reference a project can hold to a file inside its own folder. */
    private static final String AS_PROJECT = "p:";   // "project://assets/x.png"
    private static final String AS_FILE = "f:";      // "file:///…/projects/<id>/slides/x.html"
    private static final String PROJECT_SCHEME = "project://";

    private ProjectLocals() {}

    /**
     * Where inside the project a reference points, tagged with the form the reference had; null
     * when it does not point inside {@code projectDir}.
     *
     * @param filePath for a {@code file://} reference or a plain path, the path it names; else null
     */
    public static String placeOf(File projectDir, String value, String filePath) {
        if (value.startsWith(PROJECT_SCHEME)) {
            String rel = value.substring(PROJECT_SCHEME.length());
            return safe(rel) ? AS_PROJECT + rel : null;
        }
        if (filePath == null) return null;
        String base = canonical(projectDir);
        String path = canonical(new File(filePath));
        if (!path.startsWith(base + File.separator)) return null;
        String rel = path.substring(base.length() + 1).replace(File.separatorChar, '/');
        return safe(rel) ? AS_FILE + rel : null;
    }

    /**
     * Copy a folder file back to its place inside a project being restored.
     *
     * @param place what {@link #placeOf} returned when the folder copy was written
     * @return the reference the project should hold again, or null when it could not be put back
     *         (the caller then points at the folder file, as before)
     */
    public static String putBack(JoyFolder jf, String folderRel, File projectDir, String place) {
        if (place == null || place.length() < 3) return null;
        String rel = place.substring(2);
        if (!safe(rel)) return null;
        boolean asProject = place.startsWith(AS_PROJECT);
        if (!asProject && !place.startsWith(AS_FILE)) return null;
        File target = new File(projectDir, rel);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return null;
        // What is already on the phone is at least as new as the folder copy.
        if (!target.isFile()) {
            File tmp = new File(parent, target.getName() + ".restore.tmp");
            try (InputStream in = jf.fs.open(folderRel); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.getFD().sync();
            } catch (IOException e) {
                tmp.delete();
                return null;
            }
            if (tmp.length() == 0 || !tmp.renameTo(target)) {
                tmp.delete();
                return null;
            }
        }
        return asProject ? PROJECT_SCHEME + rel : fileUri(target);
    }

    /** A path inside the project and nowhere else: not empty, not absolute, no ".." part. */
    private static boolean safe(String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("\\")) return false;
        for (String part : rel.split("/")) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) return false;
        }
        return true;
    }

    /** "file:///data/…/x.html", the form {@code Uri.fromFile} writes for a plain path. */
    private static String fileUri(File f) {
        String path = f.getAbsolutePath().replace(File.separatorChar, '/');
        StringBuilder sb = new StringBuilder("file://");
        if (!path.startsWith("/")) sb.append('/');
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '-' || c == '_' || c == '.' || c == '~' || c == '!' || c == '*'
                    || c == '\'' || c == '(' || c == ')' || c == ':';
            if (plain) {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    sb.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                            .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
            }
        }
        return sb.toString();
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }
}
