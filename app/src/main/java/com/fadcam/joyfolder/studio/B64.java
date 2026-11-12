package com.fadcam.joyfolder.studio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Base64 in plain Java. {@code java.util.Base64} needs Android 8 and the app still installs on 7,
 * and {@code android.util.Base64} cannot run in the JVM harness; this is thirty lines.
 */
final class B64 {

    private static final char[] DIGITS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private B64() {}

    static String encode(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(DIGITS[b0 >> 2]);
            sb.append(DIGITS[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < in.length ? DIGITS[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < in.length ? DIGITS[b2 & 63] : '=');
        }
        return sb.toString();
    }

    static byte[] decode(String s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() / 4 * 3);
        int acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            int v = c >= 'A' && c <= 'Z' ? c - 'A' : c >= 'a' && c <= 'z' ? c - 'a' + 26
                    : c >= '0' && c <= '9' ? c - '0' + 52 : c == '+' ? 62 : c == '/' ? 63 : -1;
            if (v < 0) throw new IOException("Not base64 text");
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }
}
