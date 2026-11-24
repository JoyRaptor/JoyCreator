package cc.joycreator.avatar.intake;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A small Photoshop (.psd / .psb) reader: the layer tree (groups, names, visibility, opacity, blend, clipping, bounds)
 * and any layer's pixels as straight RGBA on demand. 8-bit RGB or grey files; raw, RLE and ZIP channel data; user masks.
 * A port of {@code tools/avatarlab/al-psd.js}; the phone's own door (phone-first).
 *
 * <p>Plain Java, no Android classes, so the JVM harness tests it. A problem is an {@link IOException} whose message is
 * one plain sentence the person can read (Photoshop's own words for the fix).</p>
 */
public final class PsdReader {

    /** One entry of the layer tree, top to bottom as Photoshop's panel shows it. */
    public static final class Node {
        public String name = "", blend = "";
        public boolean group, visible = true, clip;
        public double opacity = 1;
        public int top, left, bottom, right, w, h;
        public final List<Node> children = new ArrayList<>();
        public String path = "";
        // reading details
        int section;
        final List<Chan> ch = new ArrayList<>();
        Mask mask;
    }

    static final class Chan {
        int id;
        long len, off;
    }

    static final class Mask {
        int top, left, w, h, def;
        boolean off;
    }

    /** A layer's pixels: {@code rgba} is w*h*4 bytes, straight (not premultiplied), layer opacity not baked in. */
    public static final class Image {
        public final int w, h;
        public final byte[] rgba;

        Image(int w, int h, byte[] rgba) {
            this.w = w;
            this.h = h;
            this.rgba = rgba;
        }
    }

    public int width, height, channels, depth, mode;
    public boolean big;
    public final List<Node> tree = new ArrayList<>();
    private final List<Node> flat = new ArrayList<>();
    private final ByteBuffer buf;
    private int p;

    private PsdReader(byte[] bytes) {
        buf = ByteBuffer.wrap(bytes);   // big-endian, as the format is
    }

    private static final String BIG_KEYS = "LMsk Lr16 Lr32 Layr Mt16 Mt32 Mtrn Alph FMsk lnk2 FEid FXid PxSD ";

    private static boolean isBigKey(String key) {
        return BIG_KEYS.contains(key + " ");
    }

    public static PsdReader parse(byte[] bytes) throws IOException {
        PsdReader r = new PsdReader(bytes);
        try {
            r.read();
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("This Photoshop file is damaged or cut short.");
        }
        return r;
    }

    private void read() throws IOException {
        if (!"8BPS".equals(str(4))) throw new IOException("This is not a Photoshop file.");
        int version = u16();
        big = version == 2;
        p += 6;
        channels = u16();
        height = (int) u32();
        width = (int) u32();
        depth = u16();
        mode = u16();
        if (depth != 8) throw new IOException("This file is " + depth + "-bit. Save it as 8-bit (Image > Mode > 8 Bits/Channel) and try again.");
        if (mode != 3 && mode != 1) throw new IOException("This file is not RGB. Change it to RGB Color (Image > Mode) and try again.");
        long cm = u32();
        p += (int) cm;                               // colour mode data
        long res = u32();
        p += (int) res;                              // image resources
        long lmLen = big ? u64() : u32();
        long lmEnd = p + lmLen;
        if (lmLen > 0) {
            long liLen = big ? u64() : u32();
            if (liLen > 0) {
                readLayerInfo();
            } else {
                // 16/32-bit style: layers hide in a tagged block after the global mask. Kept for odd 8-bit writers.
                long gm = u32();
                p += (int) gm;
                while (p + 12 <= lmEnd) {
                    String sig = str(4), key = str(4);
                    long len = ("8B64".equals(sig) || (big && isBigKey(key))) ? u64() : u32();
                    long end = p + len;
                    if ("Layr".equals(key) || "Lr16".equals(key)) readLayerInfo();
                    p = (int) (end + (len % 4 != 0 ? 4 - (len % 4) : 0));
                }
            }
        }
        buildTree();
    }

    private void readLayerInfo() throws IOException {
        int count = Math.abs((int) i16());
        List<Node> recs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Node L = new Node();
            L.top = i32();
            L.left = i32();
            L.bottom = i32();
            L.right = i32();
            L.w = Math.max(0, L.right - L.left);
            L.h = Math.max(0, L.bottom - L.top);
            int nch = u16();
            for (int c = 0; c < nch; c++) {
                Chan ch = new Chan();
                ch.id = i16();
                ch.len = big ? u64() : u32();
                L.ch.add(ch);
            }
            p += 4;                                  // '8BIM'
            L.blend = str(4).trim();
            L.opacity = u8() / 255.0;
            L.clip = u8() == 1;
            int flags = u8();
            p += 1;
            L.visible = (flags & 2) == 0;
            long extraLen = u32();
            long extraEnd = p + extraLen;
            long maskLen = u32();
            long maskEnd = p + maskLen;
            if (maskLen >= 18) {
                int mt = i32(), ml = i32(), mb = i32(), mr = i32();
                int mdef = u8(), mflags = u8();
                Mask m = new Mask();
                m.top = mt;
                m.left = ml;
                m.w = Math.max(0, mr - ml);
                m.h = Math.max(0, mb - mt);
                m.def = mdef;
                m.off = (mflags & 2) != 0;
                L.mask = m;
            }
            p = (int) maskEnd;
            long brLen = u32();
            p += (int) brLen;                        // blending ranges
            int nlen = u8();
            L.name = str(nlen);
            int pad = (nlen + 1) % 4;
            if (pad != 0) p += 4 - pad;
            L.section = 0;
            while (p + 12 <= extraEnd) {
                String sig = str(4), key = str(4);
                long len = ("8B64".equals(sig) || (big && isBigKey(key))) ? u64() : u32();
                long end = p + len;
                if ("luni".equals(key)) {
                    long n = u32();
                    StringBuilder s = new StringBuilder();
                    for (long k = 0; k < n; k++) {
                        int cc = u16();
                        if (cc != 0) s.append((char) cc);
                    }
                    L.name = s.toString();
                } else if ("lsct".equals(key) || "lsdk".equals(key)) {
                    L.section = (int) u32();
                    if (len >= 12) {
                        p += 4;
                        String bm = str(4).trim();
                        if (!bm.isEmpty()) L.blend = bm;
                    }
                }
                p = (int) (end + (len & 1));
            }
            p = (int) extraEnd;
            recs.add(L);
        }
        for (Node L : recs) for (Chan c : L.ch) {
            c.off = p;
            p += (int) c.len;
        }
        flat.clear();
        flat.addAll(recs);
    }

    /** File order is bottom-to-top with a hidden "end of group" marker under each group. The tree is top-to-bottom. */
    private void buildTree() {
        List<List<Node>> stack = new ArrayList<>();
        stack.add(tree);
        List<String> path = new ArrayList<>();
        for (int i = flat.size() - 1; i >= 0; i--) {
            Node L = flat.get(i);
            if (L.section == 1 || L.section == 2) {
                L.group = true;
                L.path = join(path, L.name);
                stack.get(stack.size() - 1).add(L);
                stack.add(L.children);
                path.add(L.name);
            } else if (L.section == 3) {
                if (stack.size() > 1) {
                    stack.remove(stack.size() - 1);
                    path.remove(path.size() - 1);
                }
            } else {
                L.path = join(path, L.name);
                stack.get(stack.size() - 1).add(L);
            }
        }
    }

    private static String join(List<String> path, String name) {
        StringBuilder sb = new StringBuilder();
        for (String s : path) sb.append(s).append('/');
        return sb.append(name).toString();
    }

    private static void unpackBits(byte[] src, int[] pp, int end, byte[] out, int o, int n) {
        int p = pp[0], stop = o + n;
        while (o < stop && p < end) {
            int c = src[p++] & 255;
            if (c < 128) {
                c += 1;
                for (int i = 0; i < c && o < stop; i++) out[o++] = src[p++];
            } else if (c > 128) {
                c = 257 - c;
                byte v = src[p++];
                for (int j = 0; j < c && o < stop; j++) out[o++] = v;
            }
        }
        pp[0] = p;
    }

    /** One channel plane (w*h bytes): raw, RLE or ZIP (with or without prediction). */
    private byte[] readPlane(Chan ch, int w, int h) throws IOException {
        byte[] out = new byte[w * h];
        if (w == 0 || h == 0 || ch.len < 2) return out;
        byte[] a = buf.array();
        int q = (int) ch.off;
        int comp = ((a[q] & 255) << 8) | (a[q + 1] & 255);
        q += 2;
        int end = (int) (ch.off + ch.len);
        if (comp == 0) {
            System.arraycopy(a, q, out, 0, Math.min(w * h, end - q));
            return out;
        }
        if (comp == 1) {
            q += h * (big ? 4 : 2);
            int[] pp = {q};
            for (int y = 0; y < h; y++) unpackBits(a, pp, end, out, y * w, w);
            return out;
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(a, q, end - q);
            int got = 0;
            while (got < out.length && !inf.finished()) {
                int n = inf.inflate(out, got, out.length - got);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                got += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("This Photoshop file has a layer that could not be opened.");
        } finally {
            inf.end();
        }
        if (comp == 3) {
            for (int y = 0; y < h; y++) for (int x = 1, o = y * w + 1; x < w; x++, o++) out[o] = (byte) (out[o] + out[o - 1]);
        }
        return out;
    }

    /** Straight RGBA for one layer; the layer's opacity is NOT baked in. */
    public Image decode(Node L) throws IOException {
        int w = L.w, h = L.h;
        Chan cr = null, cg = null, cb = null, ca = null, cm = null;
        for (Chan c : L.ch) {
            if (c.id == 0) cr = c;
            else if (c.id == 1) cg = c;
            else if (c.id == 2) cb = c;
            else if (c.id == -1) ca = c;
            else if (c.id == -2) cm = c;
        }
        byte[] R = cr == null ? null : readPlane(cr, w, h);
        byte[] G = cg == null ? R : readPlane(cg, w, h);
        byte[] B = cb == null ? R : readPlane(cb, w, h);
        byte[] A = ca == null ? null : readPlane(ca, w, h);
        byte[] M = (L.mask != null && !L.mask.off && cm != null) ? readPlane(cm, L.mask.w, L.mask.h) : null;
        byte[] data = new byte[w * h * 4];
        for (int i = 0, o = 0, n = w * h; i < n; i++, o += 4) {
            data[o] = R != null ? R[i] : 0;
            data[o + 1] = G != null ? G[i] : 0;
            data[o + 2] = B != null ? B[i] : 0;
            data[o + 3] = A != null ? A[i] : (byte) 255;
        }
        if (M != null) {
            Mask m = L.mask;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int mx = x + L.left - m.left, my = y + L.top - m.top;
                    int mv = (mx >= 0 && my >= 0 && mx < m.w && my < m.h) ? (M[my * m.w + mx] & 255) : m.def;
                    if (mv != 255) {
                        int k = (y * w + x) * 4 + 3;
                        data[k] = (byte) ((int) ((data[k] & 255) * mv / 255.0));
                    }
                }
        }
        return new Image(w, h, data);
    }

    /** A leaf with the visibility and opacity it inherits from its groups. */
    public static final class Leaf {
        public final Node layer;
        public final boolean visible;
        public final double opacity;

        Leaf(Node l, boolean v, double o) {
            layer = l;
            visible = v;
            opacity = o;
        }
    }

    /** Every leaf under a node, top first. */
    public static void leaves(Node node, List<Leaf> out, boolean vis, double op) {
        boolean v = vis && node.visible;
        double o = op * node.opacity;
        if (node.group) for (Node c : node.children) leaves(c, out, v, o);
        else if (node.w > 0 && node.h > 0) out.add(new Leaf(node, v, o));
    }

    // ── reading ─────────────────────────────────────────────────────────────────────────────────────

    private int u8() {
        return buf.get(p++) & 255;
    }

    private int u16() {
        int v = buf.getShort(p) & 0xFFFF;
        p += 2;
        return v;
    }

    private int i16() {
        int v = buf.getShort(p);
        p += 2;
        return v;
    }

    private long u32() {
        long v = buf.getInt(p) & 0xFFFFFFFFL;
        p += 4;
        return v;
    }

    private int i32() {
        int v = buf.getInt(p);
        p += 4;
        return v;
    }

    private long u64() {
        long hi = u32(), lo = u32();
        return hi * 4294967296L + lo;
    }

    private String str(int n) {
        String s = new String(buf.array(), p, n, StandardCharsets.ISO_8859_1);
        p += n;
        return s;
    }
}
