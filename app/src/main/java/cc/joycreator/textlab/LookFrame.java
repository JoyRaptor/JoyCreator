package cc.joycreator.textlab;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A look fitted to a frame of a given shape (TextLab's quick export): the look's own picture is only as wide
 * as its words and margins, so a 16:9, square or tall frame gets more margin round the words, split evenly, and
 * the look's stage then fills the whole frame. The words stay the size they would be in that frame.
 */
final class LookFrame {

    /** The renderer's margin when a look names none: left, top, right, bottom, em. */
    private static final double DEFAULT_MARGIN = 0.7;

    private LookFrame() {}

    /**
     * {@code look} with margins widened so its field ({@code fieldEm}: width, height, em, as the renderer laid it
     * out) has the shape {@code aspect} (width / height). The look itself is not changed.
     *
     * <p>The floor line is a share of the field's height ({@code floorLine}, the look's value now): with room
     * added above and below, it is moved so the floor stays the same distance under the words.</p>
     */
    @NonNull
    static JSONObject framed(@NonNull JSONObject look, @NonNull double[] fieldEm, double aspect, double floorLine)
            throws Exception {
        double fw = fieldEm[0], fh = fieldEm[1];
        JSONObject out = new JSONObject(look.toString());
        if (!(fw > 0) || !(fh > 0) || !(aspect > 0)) return out;
        double[] m = margin(look);
        if (fw / fh < aspect) {
            double more = (fh * aspect - fw) / 2;
            m[0] += more;
            m[2] += more;
        } else {
            double more = (fw / aspect - fh) / 2;
            m[1] += more;
            m[3] += more;
            JSONObject knobs = out.optJSONObject("p");
            if (knobs == null) out.put("p", knobs = new JSONObject());
            knobs.put("floorLine", (floorLine * fh + more) / (fh + 2 * more));
        }
        JSONArray a = new JSONArray();
        for (double v : m) a.put(v);
        out.put("margin", a);
        return out;
    }

    @NonNull
    private static double[] margin(@NonNull JSONObject look) {
        double[] m = {DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN, DEFAULT_MARGIN};
        JSONArray a = look.optJSONArray("margin");
        if (a != null && a.length() == 4) for (int i = 0; i < 4; i++) m[i] = a.optDouble(i, DEFAULT_MARGIN);
        return m;
    }
}
