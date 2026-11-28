package cc.joycreator.avatar.scene;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * THE EMPTY SCENE'S SHOTS (the pilot's {@code al-frame.js}, "plain shots"). A scene has a camera; the plain stage has its view, so a
 * shot here moves that view: Wide (the character fitted in the frame), Close on the character (found from its head), and views
 * framed by hand and kept. Kept shots are stored in character pixels relative to the wide view, so they survive a change of frame.
 * A view is {scale, ox, oy}: stage pixel = character pixel * scale + (ox, oy).
 */
public final class EmptyShots {
    private EmptyShots() {
    }

    /** The character's box {x0, y0, x1, y1} fitted into the frame rectangle {x, y, w, h} (pixels): the Wide view. */
    public static double[] wide(double[] frame, double[] box, double pad) {
        double w = Math.max(8, box[2] - box[0]), h = Math.max(8, box[3] - box[1]);
        double s = Math.min((frame[2] - pad * 2) / w, (frame[3] - pad * 1.4) / h);
        return new double[]{s, frame[0] + (frame[2] - w * s) / 2 - box[0] * s, frame[1] + (frame[3] - h * s) / 2 - box[1] * s};
    }

    /** Close on the character: found from its head box, a little above its middle, never farther out than Wide x 1.15. */
    public static double[] close(double[] frame, double[] wide, double[] head) {
        double hh = Math.max(20, head[3] - head[1]);
        double s = Math.max(wide[0] * 1.15, Math.min(frame[3] / (hh * 2.3), frame[2] / ((head[2] - head[0]) * 1.5)));
        double cx = (head[0] + head[2]) / 2, cy = (head[1] + head[3]) / 2 + hh * 0.22;
        return new double[]{s, frame[0] + frame[2] / 2 - cx * s, frame[1] + frame[3] / 2 - cy * s};
    }

    /** A kept shot: {@code cx, cy} the character pixel in the middle of the frame, {@code k} how far in relative to Wide. */
    public static double[] kept(double[] frame, double[] wide, JSONObject shot) {
        double s = wide[0] * shot.optDouble("k", 1), cx = shot.optDouble("cx"), cy = shot.optDouble("cy");
        return new double[]{s, frame[0] + frame[2] / 2 - cx * s, frame[1] + frame[3] / 2 - cy * s};
    }

    /** Keep the view as it is now as a shot. */
    public static JSONObject keep(String id, String name, double[] frame, double[] wide, double[] view) throws JSONException {
        return new JSONObject().put("id", id).put("name", name)
                .put("cx", Math.round((frame[0] + frame[2] / 2 - view[1]) / view[0] * 10) / 10.0)
                .put("cy", Math.round((frame[1] + frame[3] / 2 - view[2]) / view[0] * 10) / 10.0)
                .put("k", Math.round(view[0] / wide[0] * 1000) / 1000.0);
    }

    /** The shots on offer: Wide, Close on the character (when a head is known), then the kept ones. {@code kind} is wide, close or own. */
    public static String[][] list(String characterName, boolean hasHead, JSONArray mine) {
        java.util.List<String[]> l = new java.util.ArrayList<>();
        l.add(new String[]{"wide", "Wide", "wide"});
        if (hasHead) l.add(new String[]{"close", characterName, "close"});
        for (int i = 0; mine != null && i < mine.length(); i++) {
            JSONObject s = mine.optJSONObject(i);
            if (s != null) l.add(new String[]{s.optString("id"), s.optString("name"), "own"});
        }
        return l.toArray(new String[0][]);
    }
}
