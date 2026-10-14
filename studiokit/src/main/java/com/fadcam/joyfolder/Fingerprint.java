package com.fadcam.joyfolder;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A short identity for a file's contents, so a file can be found again after it is moved or
 * renamed (rule 3: moving things never breaks anything).
 *
 * <p>Hashing a 2 GB screen recording in full on every check would take minutes, so the print is
 * the size plus a SHA-256 of three 64 KB samples: start, middle and end. Two different real files
 * of exactly the same size that agree in all three samples do not happen in practice; the size
 * also rules out every candidate of another length without reading a byte.</p>
 */
public final class Fingerprint {

    private Fingerprint() {}

    static final int CHUNK = 64 * 1024;

    /** "v1:<size>:<24 hex>". */
    public static String of(JoyFs fs, String rel) throws IOException {
        JoyFs.Entry e = fs.stat(rel);
        if (e == null || e.dir) throw new IOException("Not a file: " + rel);
        try (InputStream in = fs.open(rel)) {
            return of(in, e.size);
        }
    }

    public static String of(InputStream in, long size) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException(ex);
        }
        byte[] buf = new byte[CHUNK];
        if (size <= 3L * CHUNK) {
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } else {
            long pos = 0;
            long[] starts = {0, size / 2 - CHUNK / 2, size - CHUNK};
            for (long start : starts) {
                skipFully(in, start - pos);
                int got = readFully(in, buf);
                md.update(buf, 0, got);
                pos = start + got;
            }
        }
        return "v1:" + size + ":" + hex(md.digest(), 12);
    }

    /** The size encoded in a fingerprint, or -1. Lets a lookup skip every file of another size. */
    public static long sizeOf(String fingerprint) {
        if (fingerprint == null) return -1;
        String[] p = fingerprint.split(":");
        if (p.length != 3) return -1;
        try {
            return Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new IOException("File ended early");
                s = 1;
            }
            n -= s;
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    private static String hex(byte[] b, int bytes) {
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (int i = 0; i < bytes; i++) sb.append(String.format("%02x", b[i] & 0xff));
        return sb.toString();
    }
}
