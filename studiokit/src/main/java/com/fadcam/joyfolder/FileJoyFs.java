package com.fadcam.joyfolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** {@link JoyFs} over plain files under a root directory. */
public final class FileJoyFs implements JoyFs {

    private final File root;

    public FileJoyFs(File root) {
        this.root = root;
    }

    public File root() {
        return root;
    }

    private File f(String rel) {
        return rel == null || rel.isEmpty() ? root : new File(root, rel.replace('/', File.separatorChar));
    }

    @Override
    public List<Entry> list(String relDir) {
        List<Entry> out = new ArrayList<>();
        File[] kids = f(relDir).listFiles();
        if (kids == null) return out;
        for (File k : kids) out.add(entry(k));
        return out;
    }

    @Override
    public Entry stat(String rel) {
        File file = f(rel);
        return file.exists() ? entry(file) : null;
    }

    private static Entry entry(File k) {
        return new Entry(k.getName(), k.isDirectory(), k.isDirectory() ? 0 : k.length(), k.lastModified());
    }

    @Override
    public InputStream open(String rel) throws IOException {
        return new FileInputStream(f(rel));
    }

    @Override
    public OutputStream create(String rel) throws IOException {
        File file = f(rel);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create folder " + parent);
        }
        final FileOutputStream fos = new FileOutputStream(file);
        // Flush to the disk on close, so a rename that follows never points at empty bytes.
        return new java.io.FilterOutputStream(fos) {
            @Override public void write(byte[] b, int off, int len) throws IOException { fos.write(b, off, len); }
            @Override public void close() throws IOException {
                try {
                    fos.flush();
                    fos.getFD().sync();
                } finally {
                    fos.close();
                }
            }
        };
    }

    @Override
    public boolean rename(String rel, String newName) {
        File from = f(rel);
        File to = new File(from.getParentFile(), newName);
        return !to.exists() && from.renameTo(to);
    }

    @Override
    public boolean move(String rel, String newParentRel) throws IOException {
        File from = f(rel);
        File dir = f(newParentRel);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create folder " + dir);
        File to = new File(dir, from.getName());
        return !to.exists() && from.renameTo(to);
    }

    @Override
    public void mkdirs(String relDir) throws IOException {
        File dir = f(relDir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create folder " + dir);
    }

    @Override
    public boolean delete(String rel) {
        return deleteTree(f(rel));
    }

    private static boolean deleteTree(File file) {
        File[] kids = file.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return file.delete();
    }
}
