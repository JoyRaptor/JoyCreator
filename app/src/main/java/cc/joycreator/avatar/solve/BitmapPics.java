package cc.joycreator.avatar.solve;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A character's pictures on the phone: each one decoded once into a Bitmap (the drawing step uploads these as textures), with its
 * working size and alpha plane for the solver. A picture is either inside the file ({@code data:image/...;base64,}) or beside it
 * ({@code Name.pictures/id.png}, read from {@code dir}); AVA_FORMAT.md rule 4. A picture painted finer than the character
 * ({@code fine: { id: [w, h] }}) is kept at full size for drawing, and measured at its working size, as in the pilot.
 *
 * <p>Pictures that cannot be read are left out: the solver then skips those drawings, as the pilot does.</p>
 */
public final class BitmapPics implements Pics {
    private final Map<String, Bitmap> full = new HashMap<>();
    private final Map<String, int[]> size = new HashMap<>();
    private final Map<String, byte[]> alpha = new HashMap<>();
    private final Map<String, Integer> ver = new HashMap<>();

    public BitmapPics(JSONObject character, File dir) {
        this((id, src) -> decode(src, dir), character);
    }

    /** Reads one picture: its id and its source as the file names it ({@code data:...} or a path beside the file). */
    public interface Reader {
        Bitmap read(String id, String src);
    }

    /** Pictures read by the caller (Studio reads them beside a character kept in the person's folder). */
    public static BitmapPics read(JSONObject character, Reader reader) {
        return new BitmapPics(reader, character);
    }

    private BitmapPics(Reader reader, JSONObject character) {
        JSONObject pics = character.optJSONObject("pictures"), fine = character.optJSONObject("fine");
        if (pics == null) return;
        for (Iterator<String> it = pics.keys(); it.hasNext(); ) {
            String id = it.next();
            Bitmap b;
            try {
                b = reader.read(id, pics.optString(id, ""));
            } catch (RuntimeException | OutOfMemoryError e) {
                b = null;
            }
            if (b == null) continue;
            JSONArray f = fine == null ? null : fine.optJSONArray(id);
            int w = b.getWidth(), h = b.getHeight();
            if (f != null && f.optInt(0, 0) > 0 && f.optInt(1, 0) > 0 && f.optInt(0) < w) { w = f.optInt(0); h = f.optInt(1); }
            full.put(id, b);
            size.put(id, new int[] {w, h});
            ver.put(id, 1);
        }
    }

    private static Bitmap decode(String src, File dir) {
        try {
            if (src.startsWith("data:")) {
                byte[] bytes = Base64.decode(src.substring(src.indexOf(',') + 1), Base64.DEFAULT);
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            }
            if (dir == null || src.isEmpty() || src.contains("..")) return null;
            return BitmapFactory.decodeFile(new File(dir, src).getPath());
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The picture as painted (for drawing); null when it could not be read. */
    public Bitmap bitmap(String img) { return full.get(img); }

    @Override public boolean has(String img) { return size.containsKey(img); }

    @Override public int w(String img) { return size.get(img)[0]; }

    @Override public int h(String img) { return size.get(img)[1]; }

    @Override public int ver(String img) { Integer v = ver.get(img); return v == null ? 0 : v; }

    /** A picture was replaced (Update the art): meshes and distances made from the old one are made again. */
    public void replace(String img, Bitmap b) {
        if (b == null) return;
        full.put(img, b);
        int[] s = size.get(img);
        if (s == null) size.put(img, new int[] {b.getWidth(), b.getHeight()});
        alpha.remove(img);
        ver.put(img, ver(img) + 1);
    }

    @Override public byte[] alpha(String img) {
        byte[] a = alpha.get(img);
        if (a != null) return a;
        Bitmap b = full.get(img);
        int[] s = size.get(img);
        if (b == null || s == null) return new byte[0];
        Bitmap m = (b.getWidth() == s[0] && b.getHeight() == s[1]) ? b : Bitmap.createScaledBitmap(b, s[0], s[1], true);
        int[] px = new int[s[0] * s[1]];
        m.getPixels(px, 0, s[0], 0, 0, s[0], s[1]);
        if (m != b) m.recycle();
        a = new byte[px.length];
        for (int i = 0; i < px.length; i++) a[i] = (byte) (px[i] >>> 24);
        alpha.put(img, a);
        return a;
    }
}
