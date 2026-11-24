package com.fadcam.ui.faditor.motion.flow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Persistent analysed-flow chunks (STU-7 C6, spec section 6).
 *
 * <p>The cache is deliberately separate from project JSON. A chunk owns the forward and backward
 * field for one decoded PTS pair. Vectors are stored at 1/16-pixel precision, confidence at 8-bit
 * precision, then deflated. A digest of every analysis input is both the filename and part of the
 * checked header, so colour and shutter changes can reuse flow while crop, source, estimator or
 * detail changes cannot.
 *
 * <p>Writes fsync a sibling temporary file and use the same-filesystem rename used by Android's
 * Linux filesystems for atomic replacement. If replacement fails, the previous complete chunk
 * remains readable.
 * Corrupt or incomplete chunks are cache misses. The disk quota is enforced least-recently-viewed
 * using the successful read time; analysis can regenerate an evicted chunk while LiveFlow covers
 * the gap.
 */
public final class FlowCache {
    public static final long DEFAULT_MAX_BYTES = 256L * 1024L * 1024L;

    private static final int MAGIC = 0x4a464c57; // JFLW
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_RAW_BYTES = 256 * 1024 * 1024;
    private static final float VECTOR_SCALE = 16f;

    private final File directory;
    private final long maxBytes;
    private final CommitHook commitHook;

    public FlowCache(File directory) throws IOException {
        this(directory, DEFAULT_MAX_BYTES);
    }

    public FlowCache(File directory, long maxBytes) throws IOException {
        this(directory, maxBytes, null);
    }

    FlowCache(File directory, long maxBytes, CommitHook commitHook) throws IOException {
        if (directory == null || maxBytes < 0) throw new IllegalArgumentException("invalid cache");
        this.directory = directory;
        this.maxBytes = maxBytes;
        this.commitHook = commitHook;
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("could not create flow cache " + directory);
        }
        enforceQuota();
    }

    /** A complete bidirectional estimate for one neighbouring source-frame pair. */
    public static final class Pair {
        public final FlowField forward;
        public final FlowField backward;

        public Pair(FlowField forward, FlowField backward) {
            if (forward == null || backward == null
                    || forward.width != backward.width || forward.height != backward.height
                    || forward.fromPtsUs != backward.toPtsUs
                    || forward.toPtsUs != backward.fromPtsUs) {
                throw new IllegalArgumentException("flow pair mismatch");
            }
            this.forward = forward;
            this.backward = backward;
        }
    }

    /** Every value that can make an analysed field geometrically or algorithmically different. */
    public static final class Key {
        public final String sourceFingerprint;
        public final int orientationDegrees;
        public final int cropLeft;
        public final int cropTop;
        public final int cropRight;
        public final int cropBottom;
        public final long fromPtsUs;
        public final long toPtsUs;
        public final String estimatorRevision;
        public final String detail;
        public final int width;
        public final int height;

        public Key(String sourceFingerprint, int orientationDegrees,
                   int cropLeft, int cropTop, int cropRight, int cropBottom,
                   long fromPtsUs, long toPtsUs, String estimatorRevision, String detail,
                   int width, int height) {
            if (sourceFingerprint == null || estimatorRevision == null || detail == null
                    || width <= 0 || height <= 0 || fromPtsUs == toPtsUs) {
                throw new IllegalArgumentException("invalid flow cache key");
            }
            this.sourceFingerprint = sourceFingerprint;
            this.orientationDegrees = orientationDegrees;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropRight = cropRight;
            this.cropBottom = cropBottom;
            this.fromPtsUs = fromPtsUs;
            this.toPtsUs = toPtsUs;
            this.estimatorRevision = estimatorRevision;
            this.detail = detail;
            this.width = width;
            this.height = height;
        }

        byte[] digest() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeUTF(sourceFingerprint);
                out.writeInt(orientationDegrees);
                out.writeInt(cropLeft);
                out.writeInt(cropTop);
                out.writeInt(cropRight);
                out.writeInt(cropBottom);
                out.writeLong(fromPtsUs);
                out.writeLong(toPtsUs);
                out.writeUTF(estimatorRevision);
                out.writeUTF(detail);
                out.writeInt(width);
                out.writeInt(height);
                out.close();
                return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            } catch (NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }

    public Pair get(Key key) {
        byte[] digest = key.digest();
        File file = fileFor(digest);
        if (!file.isFile()) return null;
        try {
            Pair pair = read(file, digest);
            if (pair.forward.width != key.width || pair.forward.height != key.height
                    || pair.forward.fromPtsUs != key.fromPtsUs
                    || pair.forward.toPtsUs != key.toPtsUs) return null;
            file.setLastModified(System.currentTimeMillis());
            return pair;
        } catch (IOException | RuntimeException badChunk) {
            return null;
        }
    }

    public void put(Key key, Pair pair) throws IOException {
        if (pair.forward.width != key.width || pair.forward.height != key.height
                || pair.forward.fromPtsUs != key.fromPtsUs
                || pair.forward.toPtsUs != key.toPtsUs) {
            throw new IllegalArgumentException("pair does not match key");
        }
        byte[] digest = key.digest();
        byte[] raw = encode(pair);
        CRC32 crc = new CRC32();
        crc.update(raw);
        byte[] compressed = deflate(raw);

        File target = fileFor(digest);
        File temp = File.createTempFile(target.getName() + ".", ".tmp", directory);
        boolean moved = false;
        try {
            try (FileOutputStream stream = new FileOutputStream(temp);
                 DataOutputStream out = new DataOutputStream(stream)) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.write(digest);
                out.writeInt(raw.length);
                out.writeInt((int) crc.getValue());
                out.writeInt(compressed.length);
                out.write(compressed);
                out.flush();
                stream.getFD().sync();
            }
            if (commitHook != null) commitHook.beforeMove(temp, target);
            if (!temp.renameTo(target)) throw new IOException("could not atomically replace flow chunk");
            moved = true;
        } finally {
            if (!moved) temp.delete();
        }
        target.setLastModified(System.currentTimeMillis());
        enforceQuota();
    }

    private Pair read(File file, byte[] expectedDigest) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(file));
        try {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("header");
            byte[] digest = new byte[DIGEST_BYTES];
            in.readFully(digest);
            if (!Arrays.equals(digest, expectedDigest)) throw new IOException("key");
            int rawLength = in.readInt();
            long expectedCrc = in.readInt() & 0xffffffffL;
            int compressedLength = in.readInt();
            if (rawLength <= 0 || rawLength > MAX_RAW_BYTES
                    || compressedLength <= 0 || compressedLength > file.length()) {
                throw new IOException("length");
            }
            byte[] compressed = new byte[compressedLength];
            in.readFully(compressed);
            if (in.read() != -1) throw new IOException("trailing bytes");
            byte[] raw = inflate(compressed, rawLength);
            CRC32 crc = new CRC32();
            crc.update(raw);
            if (crc.getValue() != expectedCrc) throw new IOException("crc");
            return decode(raw);
        } finally {
            in.close();
        }
    }

    private static byte[] encode(Pair pair) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeField(out, pair.forward);
        writeField(out, pair.backward);
        out.close();
        return bytes.toByteArray();
    }

    private static void writeField(DataOutputStream out, FlowField field) throws IOException {
        out.writeInt(field.width);
        out.writeInt(field.height);
        out.writeLong(field.fromPtsUs);
        out.writeLong(field.toPtsUs);
        out.writeBoolean(field.hasConfidence());
        for (int y = 0; y < field.height; y++) {
            for (int x = 0; x < field.width; x++) {
                out.writeShort(quantizeVector(field.u(x, y)));
                out.writeShort(quantizeVector(field.v(x, y)));
                if (field.hasConfidence()) out.writeByte(quantizeConfidence(field.confidence(x, y)));
            }
        }
    }

    private static Pair decode(byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        FlowField forward = readField(in);
        FlowField backward = readField(in);
        if (in.read() != -1) throw new IOException("raw trailing bytes");
        return new Pair(forward, backward);
    }

    private static FlowField readField(DataInputStream in) throws IOException {
        int width = in.readInt();
        int height = in.readInt();
        long fromPtsUs = in.readLong();
        long toPtsUs = in.readLong();
        boolean hasConfidence = in.readBoolean();
        long count = (long) width * height;
        if (width <= 0 || height <= 0 || count > MAX_RAW_BYTES / 4) throw new IOException("field size");
        float[] u = new float[(int) count];
        float[] v = new float[(int) count];
        float[] confidence = hasConfidence ? new float[(int) count] : null;
        try {
            for (int i = 0; i < count; i++) {
                u[i] = in.readShort() / VECTOR_SCALE;
                v[i] = in.readShort() / VECTOR_SCALE;
                if (confidence != null) confidence[i] = in.readUnsignedByte() / 255f;
            }
        } catch (EOFException shortChunk) {
            throw new IOException("short field", shortChunk);
        }
        return new FlowField(width, height, u, v, confidence, fromPtsUs, toPtsUs);
    }

    private static int quantizeVector(float value) {
        if (!Float.isFinite(value)) value = 0f;
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * VECTOR_SCALE)));
    }

    private static int quantizeConfidence(float value) {
        if (!Float.isFinite(value)) value = 0f;
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DeflaterOutputStream out = new DeflaterOutputStream(bytes);
        out.write(raw);
        out.close();
        return bytes.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, int rawLength) throws IOException {
        byte[] raw = new byte[rawLength];
        InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed));
        int offset = 0;
        while (offset < raw.length) {
            int count = in.read(raw, offset, raw.length - offset);
            if (count < 0) throw new IOException("short inflated chunk");
            offset += count;
        }
        if (in.read() != -1) throw new IOException("inflated length");
        in.close();
        return raw;
    }

    private File fileFor(byte[] digest) {
        StringBuilder name = new StringBuilder(DIGEST_BYTES * 2 + 5);
        for (byte b : digest) name.append(String.format("%02x", b & 0xff));
        return new File(directory, name.append(".flow").toString());
    }

    private void enforceQuota() {
        File[] listed = directory.listFiles((dir, name) -> name.endsWith(".flow"));
        if (listed == null) return;
        List<File> chunks = new ArrayList<>(Arrays.asList(listed));
        chunks.sort(Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        long total = 0;
        for (File chunk : chunks) total += chunk.length();
        for (File chunk : chunks) {
            if (total <= maxBytes) break;
            long length = chunk.length();
            if (chunk.delete()) total -= length;
        }
    }

    interface CommitHook {
        void beforeMove(File temporary, File target) throws IOException;
    }
}
