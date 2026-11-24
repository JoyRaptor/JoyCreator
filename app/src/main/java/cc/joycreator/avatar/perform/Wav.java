package cc.joycreator.avatar.perform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Mono 16-bit WAV, the form a take's voice is kept in (AVA_FORMAT.md B4): 44 bytes of header, then the samples. */
public final class Wav {

    /** Samples as floats in -1..1 and the rate they were made at. */
    public static final class Pcm {
        public final int rate;
        public final float[] d;

        public Pcm(int rate, float[] d) {
            this.rate = rate;
            this.d = d;
        }

        public double seconds() {
            return d.length / (double) rate;
        }

        /** The sound at a moment, between two samples (0 outside). */
        public double at(double t) {
            double x = t * rate;
            int i = (int) Math.floor(x);
            double f = x - i;
            if (i < 0) return 0;
            return i + 1 < d.length ? d[i] * (1 - f) + d[i + 1] * f : i < d.length ? d[i] : 0;
        }
    }

    private Wav() {}

    public static byte[] encode(float[] f, int rate) {
        int n = f.length;
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + n * 2).put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            float s = f[i] < -1 ? -1 : f[i] > 1 ? 1 : f[i];
            b.putShort((short) Math.round(s < 0 ? s * 32768 : s * 32767));
        }
        return b.array();
    }

    /** A WAV read back (its first channel; 16-bit PCM), or null when it is not one this reads. */
    public static Pcm decode(byte[] bytes) {
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int o = 12, rate = 0, chans = 1, bits = 16;
            while (o + 8 <= bytes.length) {
                String id = new String(bytes, o, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                long sz = b.getInt(o + 4) & 0xFFFFFFFFL;
                if (id.equals("fmt ")) {
                    chans = b.getShort(o + 10) & 0xFFFF;
                    rate = b.getInt(o + 12);
                    bits = b.getShort(o + 22) & 0xFFFF;
                } else if (id.equals("data")) {
                    if (bits != 16 || rate <= 0 || chans < 1) return null;
                    int step = 2 * chans;
                    int n = (int) (Math.min(sz, bytes.length - o - 8) / step);
                    float[] d = new float[n];
                    for (int i = 0; i < n; i++) d[i] = b.getShort(o + 8 + i * step) / 32768f;
                    return new Pcm(rate, d);
                }
                o += 8 + (int) sz + (int) (sz & 1);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }
}
