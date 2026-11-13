package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Runtime shim of Android's AtomicFile (API 30+ behaviour): a write goes to "&lt;name&gt;.new" and
 * replaces the file only when finished, so a kill mid-write leaves the old file whole. Without it,
 * android.jar's stub threw "Stub!" and every caller's try/catch hid it: Joybot's job checkpoint
 * (JobFile) and Studio's timeline marks (MarkerWorkspaceStore) never wrote anything on the bench
 * (found 11-13, BOT-A16).
 */
public class AtomicFile {
    private final File base;
    private final File next;

    public AtomicFile(File baseName) {
        base = baseName;
        next = new File(baseName.getPath() + ".new");
    }

    public File getBaseFile() { return base; }

    public void delete() {
        base.delete();
        next.delete();
    }

    public FileOutputStream startWrite() throws IOException {
        File dir = next.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("no folder " + dir);
        return new FileOutputStream(next);
    }

    public void finishWrite(FileOutputStream str) {
        if (str == null) return;
        try {
            str.getFD().sync();
            str.close();
            java.nio.file.Files.move(next.toPath(), base.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.e("AtomicFile", "finishWrite failed", e);
        }
    }

    public void failWrite(FileOutputStream str) {
        if (str == null) return;
        try { str.close(); } catch (IOException ignored) { }
        next.delete();
    }

    public FileInputStream openRead() throws FileNotFoundException {
        return new FileInputStream(base);
    }

    public byte[] readFully() throws IOException {
        return java.nio.file.Files.readAllBytes(base.toPath());
    }

    public boolean exists() { return base.exists(); }
}
