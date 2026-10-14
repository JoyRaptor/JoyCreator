package com.fadcam.joyfolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * The few file operations the Joy Creator folder needs, over a root the user chose.
 *
 * <p>Paths are relative to that root, separated by {@code /}, with {@code ""} meaning the root
 * itself. Two implementations exist: {@link FileJoyFs} over plain files (tests, and any root the
 * app can reach by path) and {@code SafJoyFs} over a folder picked with Android's system picker.
 * Everything above this interface is plain Java, so the rules can be tested off the phone.</p>
 */
public interface JoyFs {

    /** One file or folder, as listed. */
    final class Entry {
        public final String name;
        public final boolean dir;
        public final long size;
        public final long modified;

        public Entry(String name, boolean dir, long size, long modified) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    /** Children of a folder. Empty, never null, when the folder does not exist. */
    List<Entry> list(String relDir) throws IOException;

    /** The file or folder at {@code rel}, or null when nothing is there. */
    Entry stat(String rel) throws IOException;

    InputStream open(String rel) throws IOException;

    /** Create or truncate a file, making any missing parent folders. */
    OutputStream create(String rel) throws IOException;

    /** Rename in place (same folder). False when the name is taken or the rename failed. */
    boolean rename(String rel, String newName) throws IOException;

    /** Move into another folder, keeping the name. False when the name is taken there. */
    boolean move(String rel, String newParentRel) throws IOException;

    void mkdirs(String relDir) throws IOException;

    /** Delete a file, or a folder and everything in it. */
    boolean delete(String rel) throws IOException;
}
