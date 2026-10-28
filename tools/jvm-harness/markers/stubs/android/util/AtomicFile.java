package android.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Minimal filesystem AtomicFile stand-in for focused local marker store checks. */
public final class AtomicFile {
    private final File base;
    private final File backup;
    public static boolean failNextFinishWrite;

    public AtomicFile(File base) {
        this.base = base;
        this.backup = new File(base.getPath() + ".bak");
    }

    public FileInputStream openRead() throws IOException {
        if (!base.exists() && backup.exists()) {
            Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new FileInputStream(base);
    }

    public FileOutputStream startWrite() throws IOException {
        if (base.exists()) Files.move(base.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return new FileOutputStream(base);
    }

    public void finishWrite(FileOutputStream stream) throws IOException {
        stream.flush();
        stream.getFD().sync();
        if (failNextFinishWrite) {
            failNextFinishWrite = false;
            throw new IOException("injected commit failure");
        }
        stream.close();
        Files.deleteIfExists(backup.toPath());
    }

    public void failWrite(FileOutputStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(base.toPath()); } catch (IOException ignored) { }
        if (backup.exists()) {
            try { Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
        }
    }
}
