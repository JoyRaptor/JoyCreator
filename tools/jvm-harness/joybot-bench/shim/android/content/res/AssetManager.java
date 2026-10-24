package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Runtime shim: the app's assets folder, read straight from the source tree. */
public final class AssetManager implements AutoCloseable {
    private final File root;

    public AssetManager(File root) { this.root = root; }

    public InputStream open(String fileName) throws IOException { return new FileInputStream(new File(root, fileName)); }
    public InputStream open(String fileName, int accessMode) throws IOException { return open(fileName); }

    public String[] list(String path) {
        String[] names = new File(root, path).list();
        if (names == null) return new String[0];
        java.util.Arrays.sort(names);
        return names;
    }

    @Override public void close() { }
}
