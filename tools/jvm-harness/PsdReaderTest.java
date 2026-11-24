import cc.joycreator.avatar.intake.PsdReader;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;

/**
 * The layered-file reader (a port of al-psd.js), on files this test writes by hand: a group with two layers, a layer
 * outside it, raw / RLE / ZIP channel data, a hidden layer, a layer mask, and the plain sentences for files it cannot read.
 * Run: bash tools/jvm-harness/run-avatar-psd.sh
 */
public class PsdReaderTest {
    static int pass, fail;

    static void ok(boolean c, String what) {
        if (c) pass++;
        else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    static class Lay {
        String name;
        int section, top, left, w, h, flags;
        int[] rgba;        // w*h*4 values 0..255
        int comp;          // 0 raw, 1 RLE, 2 zip
        int maskVal = -1;  // when >= 0, a user mask the size of the layer, every pixel this value
    }

    static byte[] zip(byte[] raw) {
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = d.deflate(buf);
        d.end();
        return java.util.Arrays.copyOf(buf, n);
    }

    static byte[] channel(Lay l, byte[] plane) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(o);
        if (l.comp == 0) {
            d.writeShort(0);
            d.write(plane);
        } else if (l.comp == 1) {
            d.writeShort(1);
            for (int y = 0; y < l.h; y++) d.writeShort(1 + l.w);   // each row: one literal run
            for (int y = 0; y < l.h; y++) {
                d.writeByte(l.w - 1);
                d.write(plane, y * l.w, l.w);
            }
        } else {
            d.writeShort(2);
            d.write(zip(plane));
        }
        return o.toByteArray();
    }

    static byte[] psd(List<Lay> fileOrder) throws IOException {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        DataOutputStream di = new DataOutputStream(info);
        di.writeShort(fileOrder.size());
        List<byte[]> data = new ArrayList<>();
        int[] ids = {0, 1, 2, -1, -2};
        for (Lay l : fileOrder) {
            di.writeInt(l.top);
            di.writeInt(l.left);
            di.writeInt(l.top + l.h);
            di.writeInt(l.left + l.w);
            boolean pix = l.section == 0;
            if (pix) {
                int nch = l.maskVal >= 0 ? 5 : 4;
                di.writeShort(nch);
                for (int c = 0; c < nch; c++) {
                    byte[] plane = new byte[l.w * l.h];
                    for (int i = 0; i < plane.length; i++) plane[i] = (byte) (c < 4 ? l.rgba[i * 4 + c] : l.maskVal);
                    byte[] ch = channel(l, plane);
                    data.add(ch);
                    di.writeShort(ids[c]);
                    di.writeInt(ch.length);
                }
            } else {   // group markers carry no pixels: one empty channel of 2 bytes
                di.writeShort(1);
                di.writeShort(0xFFFF);
                di.writeInt(2);
                data.add(new byte[]{0, 0});
            }
            di.writeBytes("8BIM");
            di.writeBytes("norm");
            di.writeByte(255);
            di.writeByte(0);
            di.writeByte(l.flags);
            di.writeByte(0);
            ByteArrayOutputStream ex = new ByteArrayOutputStream();
            DataOutputStream de = new DataOutputStream(ex);
            if (l.maskVal >= 0) {
                de.writeInt(18);
                de.writeInt(l.top);
                de.writeInt(l.left);
                de.writeInt(l.top + l.h);
                de.writeInt(l.left + l.w);
                de.writeByte(0);   // default colour
                de.writeByte(0);   // flags
            } else de.writeInt(0);
            de.writeInt(0);   // blending ranges
            byte[] nm = l.name.getBytes("ISO-8859-1");
            de.writeByte(nm.length);
            de.write(nm);
            int pad = (nm.length + 1) % 4;
            if (pad != 0) de.write(new byte[4 - pad]);
            if (l.section != 0) {
                de.writeBytes("8BIM");
                de.writeBytes("lsct");
                de.writeInt(4);
                de.writeInt(l.section);
            }
            byte[] exb = ex.toByteArray();
            di.writeInt(exb.length);
            di.write(exb);
        }
        for (byte[] c : data) di.write(c);
        byte[] infoB = info.toByteArray();
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(f);
        d.writeBytes("8BPS");
        d.writeShort(1);
        d.write(new byte[6]);
        d.writeShort(4);
        d.writeInt(8);
        d.writeInt(8);
        d.writeShort(8);
        d.writeShort(3);
        d.writeInt(0);
        d.writeInt(0);
        d.writeInt(4 + infoB.length);
        d.writeInt(infoB.length);
        d.write(infoB);
        return f.toByteArray();
    }

    static Lay layer(String name, int top, int left, int w, int h, int r, int g, int b, int a, int comp) {
        Lay l = new Lay();
        l.name = name;
        l.top = top;
        l.left = left;
        l.w = w;
        l.h = h;
        l.comp = comp;
        l.rgba = new int[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            l.rgba[i * 4] = r;
            l.rgba[i * 4 + 1] = g;
            l.rgba[i * 4 + 2] = b;
            l.rgba[i * 4 + 3] = a;
        }
        return l;
    }

    static Lay marker(String name, int section) {
        Lay l = new Lay();
        l.name = name;
        l.section = section;
        return l;
    }

    public static void main(String[] args) throws Exception {
        // file order is bottom to top: the layer outside the group, the group's end marker, its two layers, its header
        Lay bottom = layer("Backdrop", 0, 0, 4, 4, 10, 20, 30, 255, 0);
        Lay hid = layer("Hidden eye", 1, 1, 2, 2, 1, 2, 3, 255, 1);
        hid.flags = 2;   // bit 1 = hidden
        Lay masked = layer("Nose", 0, 0, 3, 2, 200, 100, 50, 255, 2);
        masked.maskVal = 0;
        for (int i = 0; i < 4; i++) {   // varied pixels, so a wrong row offset shows
            hid.rgba[i * 4] = 10 * i + 1;
            hid.rgba[i * 4 + 1] = 10 * i + 2;
            hid.rgba[i * 4 + 2] = 10 * i + 3;
        }
        Lay tip = layer("Tip", 2, 2, 2, 2, 7, 8, 9, 255, 0);
        List<Lay> order = new ArrayList<>();
        order.add(bottom);
        order.add(marker("</Layer group>", 3));
        order.add(tip);
        order.add(marker("Tail", 2));   // a group that was closed in Photoshop (type 2)
        order.add(marker("</Layer group>", 3));
        order.add(hid);
        order.add(masked);
        order.add(marker("Head", 1));
        byte[] bytes = psd(order);

        PsdReader r = PsdReader.parse(bytes);
        ok(r.width == 8 && r.height == 8, "the canvas size is read");
        ok(r.tree.size() == 3, "three things at the top: two groups and the backdrop (" + r.tree.size() + ")");
        ok(r.tree.get(1).group && "Tail".equals(r.tree.get(1).name) && r.tree.get(1).children.size() == 1, "a group closed in Photoshop is a group too");
        PsdReader.Node head = r.tree.get(0);
        ok(head.group && "Head".equals(head.name) && head.children.size() == 2, "the group is read with its two layers");
        ok("Nose".equals(head.children.get(0).name) && "Hidden eye".equals(head.children.get(1).name), "a group lists its layers top first");
        ok("Head/Nose".equals(head.children.get(0).path), "a layer knows its path through its groups");
        ok(!r.tree.get(2).group && "Backdrop".equals(r.tree.get(2).name), "a layer outside the group is read");
        ok(!head.children.get(1).visible && head.children.get(0).visible, "a hidden layer is read as hidden");

        PsdReader.Image back = r.decode(r.tree.get(2));
        ok(back.w == 4 && back.h == 4 && (back.rgba[0] & 255) == 10 && (back.rgba[1] & 255) == 20 && (back.rgba[2] & 255) == 30 && (back.rgba[3] & 255) == 255, "raw pixels decode");
        PsdReader.Image eye = r.decode(head.children.get(1));
        boolean rle = eye.w == 2;
        for (int i = 0; i < 4; i++) rle &= (eye.rgba[i * 4] & 255) == 10 * i + 1 && (eye.rgba[i * 4 + 1] & 255) == 10 * i + 2 && (eye.rgba[i * 4 + 2] & 255) == 10 * i + 3;
        ok(rle, "RLE pixels decode, row by row");
        PsdReader.Image nose = r.decode(head.children.get(0));
        ok(nose.w == 3 && (nose.rgba[0] & 255) == 200 && (nose.rgba[1] & 255) == 100 && (nose.rgba[2] & 255) == 50, "ZIP pixels decode");
        ok((nose.rgba[3] & 255) == 0, "a layer mask of 0 makes the pixel see-through");

        List<PsdReader.Leaf> leaves = new ArrayList<>();
        for (PsdReader.Node n : r.tree) PsdReader.leaves(n, leaves, true, 1);
        ok(leaves.size() == 4 && !leaves.get(1).visible, "the leaves come out top first with their inherited visibility");

        // the plain sentences
        try {
            PsdReader.parse("not a photoshop file at all".getBytes());
            ok(false, "a file that is not Photoshop is refused");
        } catch (IOException e) {
            ok("This is not a Photoshop file.".equals(e.getMessage()), "a file that is not Photoshop says so in one sentence");
        }
        byte[] cut = java.util.Arrays.copyOf(bytes, 60);
        try {
            PsdReader.parse(cut);
            ok(false, "a cut-short file is refused");
        } catch (IOException e) {
            ok(e.getMessage().contains("damaged"), "a cut-short file says it is damaged, not an exception name");
        }
        byte[] sixteen = bytes.clone();
        sixteen[23] = 16;   // depth: the 16-bit field at offset 22
        try {
            PsdReader.parse(sixteen);
            ok(false, "a 16-bit file is refused");
        } catch (IOException e) {
            ok(e.getMessage().contains("8-bit"), "a 16-bit file says how to save it as 8-bit");
        }
        System.out.println((fail > 0 ? "FAILED " : "PASSED ") + pass + " checks, " + fail + " failures");
        System.exit(fail > 0 ? 1 : 0);
    }
}
