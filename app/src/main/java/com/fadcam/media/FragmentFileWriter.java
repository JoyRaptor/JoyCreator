package com.fadcam.media;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Writes the finished pieces (fragments) of a recording to its file, and keeps the file playable
 * when a write fails (work-loss audit F5).
 *
 * <p>Before this, a failed write (disk full, storage taken out) was logged and the recording went
 * on: the next piece was written after a half-written one and the file could not be opened. Now
 * the first failure cuts the file back to the end of the last piece that went in whole, so what is
 * there is a complete recording up to that point, writes nothing more, and tells whoever asked to
 * hear about it, once. Plain {@code java.io}; the JVM harness tests it with a failing target
 * ({@code tools/jvm-harness/run-recording-room.sh}).</p>
 */
public final class FragmentFileWriter {

    /** Where the bytes go. Split out so a test can fail it on purpose. */
    public interface Target {
        void write(byte[] data) throws IOException;

        void flush() throws IOException;

        /** Cut the file to this many bytes. */
        void truncate(long size) throws IOException;
    }

    private final Target target;
    private final Runnable onFailure;
    private long good;
    private boolean failed;

    /** @param onFailure told once, from the thread that wrote, after the file was cut back; may be null */
    public FragmentFileWriter(Target target, Runnable onFailure) {
        this.target = target;
        this.onFailure = onFailure;
    }

    /** A target that writes to a plain file stream. */
    public static Target forStream(final FileOutputStream out) {
        return new Target() {
            @Override public void write(byte[] data) throws IOException { out.write(data); }
            @Override public void flush() throws IOException { out.flush(); }
            @Override public void truncate(long size) throws IOException { out.getChannel().truncate(size); }
        };
    }

    /**
     * Write one whole piece. Returns false (and writes nothing) once an earlier write has failed,
     * or when this one does.
     */
    public synchronized boolean write(byte[] data) {
        if (failed) return false;
        try {
            target.write(data);
            target.flush();
            good += data.length;
            return true;
        } catch (IOException | RuntimeException e) {
            failed = true;
            try {
                target.truncate(good);   // back to the end of the last whole piece
            } catch (IOException | RuntimeException ignored) {
                // Nothing more can be done for the tail; the pieces before it are whole.
            }
            if (onFailure != null) {
                try {
                    onFailure.run();
                } catch (RuntimeException ignored) {
                    // The listener must never break the muxer.
                }
            }
            return false;
        }
    }

    public synchronized boolean hasFailed() {
        return failed;
    }

    /** Bytes in the file as of the last whole piece. */
    public synchronized long goodBytes() {
        return good;
    }
}
