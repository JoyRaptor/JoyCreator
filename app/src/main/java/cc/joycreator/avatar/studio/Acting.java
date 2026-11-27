package cc.joycreator.avatar.studio;

import cc.joycreator.avatar.perform.Take;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acting from a video (slice 2b, the owner's idea of 11-23): a character layer in Studio follows what a video layer
 * shows. What it follows is a tick box each; only the ticked ones write into the take, the rest stay as recorded or
 * set by hand. Several videos may drive one character (one the face, another the body), but each thing it follows has
 * one source: ticking it on one video takes it off the others.
 *
 * <p>Plain Java: the plan (which videos, what each drives, the nudge, Bake or Keep as driver) and the step that
 * writes what was read into the take. Reading the video itself is {@code VideoActingReader}'s.</p>
 */
public final class Acting {

    /** What a character can follow, each a tick box in the drawer. */
    public enum Listen {
        LIPS("lips", "mouth", "vis"),
        HEAD("head", "turnX", "turnY", "roll"),
        EYES("eyes", "lookX", "lookY", "blinkL", "blinkR"),
        BROWS("brows", "browL", "browR"),
        SHOULDERS("shoulders", "shrugL", "shrugR", "shrug"),
        LEANING("leaning", "sway");

        public final String key;
        /** The take's lanes it writes (AVA_FORMAT.md B4; leaning is the body's sway). */
        public final String[] lanes;

        Listen(String key, String... lanes) {
            this.key = key;
            this.lanes = lanes;
        }

        public static Listen of(String key) {
            for (Listen l : values()) if (l.key.equals(key)) return l;
            return null;
        }
    }

    /** The face signal each lane is read from (FaceSignals' names, the pilot's al-face.js). */
    static final Map<String, String> FROM = new LinkedHashMap<>();

    static {
        for (String s : new String[]{"turnX", "turnY", "roll", "lookX", "lookY", "blinkL", "blinkR", "browL", "browR",
                "mouth", "vis", "shrugL", "shrugR", "shrug"}) FROM.put(s, s);
        FROM.put("sway", "leanX");
    }

    /** One video driving the character. */
    public static final class Source {
        /** The video's id in the project (a spine clip's or a picture-in-picture clip's). */
        public final String clipId;
        public long nudgeMs;
        public final Set<Listen> listen = EnumSet.noneOf(Listen.class);
        /** Where the video sat when it was last read ({@link #fingerprint}); a change means read it again. */
        public String readAt = "";

        public Source(String clipId) {
            this.clipId = clipId;
        }
    }

    /** The whole plan for one character item. */
    public static final class Plan {
        public final List<Source> sources = new ArrayList<>();
        /** Keep as driver: the videos stay on the timeline, hidden and silent, and are read again when they change. */
        public boolean keepAsDriver;

        public Source source(String clipId) {
            for (Source s : sources) if (s.clipId.equals(clipId)) return s;
            return null;
        }

        /** Tick or untick: on, it is this video's alone (taken off every other). */
        public void tick(String clipId, Listen l, boolean on) {
            Source s = source(clipId);
            if (on) {
                if (s == null) sources.add(s = new Source(clipId));
                for (Source o : sources) if (o != s) o.listen.remove(l);
                s.listen.add(l);
            } else if (s != null) {
                s.listen.remove(l);
            }
        }

        /** A video ticked for nothing drives nothing: it is dropped from the plan (its nudge with it). */
        public void tidy() {
            sources.removeIf(s -> s.listen.isEmpty());
        }

        public boolean isEmpty() {
            for (Source s : sources) if (!s.listen.isEmpty()) return false;
            return true;
        }

        public Plan copy() {
            return fromJson(toJson());
        }

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("keep", keepAsDriver);
                JSONArray a = new JSONArray();
                for (Source s : sources) {
                    JSONArray ls = new JSONArray();
                    for (Listen l : s.listen) ls.put(l.key);
                    a.put(new JSONObject().put("id", s.clipId).put("nudge", s.nudgeMs).put("listen", ls).put("at", s.readAt));
                }
                o.put("src", a);
                return o;
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        public static Plan fromJson(JSONObject o) {
            Plan p = new Plan();
            if (o == null) return p;
            p.keepAsDriver = o.optBoolean("keep", false);
            JSONArray a = o.optJSONArray("src");
            if (a == null) return p;
            for (int i = 0; i < a.length(); i++) {
                JSONObject j = a.optJSONObject(i);
                if (j == null || j.optString("id", "").isEmpty()) continue;
                Source s = new Source(j.optString("id"));
                s.nudgeMs = j.optLong("nudge", 0);
                s.readAt = j.optString("at", "");
                JSONArray ls = j.optJSONArray("listen");
                if (ls != null) for (int k = 0; k < ls.length(); k++) {
                    Listen l = Listen.of(ls.optString(k));
                    if (l != null) s.listen.add(l);
                }
                p.sources.add(s);
            }
            return p;
        }
    }

    private Acting() {}

    /**
     * Where a video sits, as text: its place on the timeline and the stretch of its file it shows. Kept with the plan
     * when the video is read; Keep as driver reads it again when this changes (the video was trimmed or moved).
     */
    public static String fingerprint(long timelineStartMs, long inPointMs, long outPointMs, float speed, long nudgeMs) {
        return timelineStartMs + ":" + inPointMs + ":" + outPointMs + ":" + speed + ":" + nudgeMs;
    }

    /**
     * Writes what was read from a video into the take, one frame each, for the ticked things only.
     *
     * @param base   the take as it is (null: a new one); it is not changed, a new take comes back
     * @param n      how many frames the item has; the take grows to it if shorter
     * @param frames one entry per frame from frame 0: the face signals read there, or null where no face was seen
     *               (the last face seen holds, as a live tracking loss does; before the first face the take keeps
     *               what it had)
     * @param listen what this video drives
     * @param shapes the mouth shapes' names ({@code FaceSignals.SHAPES}): the face's {@code vis} is an index into them
     */
    public static Take write(Take base, int n, List<Map<String, Float>> frames, Set<Listen> listen, String[] shapes) {
        Take t = grown(base, n);
        for (Listen l : listen) {
            if (l == Listen.LIPS && frames == null) continue;
            for (String lane : l.lanes) {
                List<Object> old = t.ch.get(lane);
                List<Object> out = new ArrayList<>(n);
                Object held = null;
                for (int f = 0; f < n; f++) {
                    Map<String, Float> fr = frames != null && f < frames.size() ? frames.get(f) : null;
                    Float v = fr == null ? null : fr.get(FROM.get(lane));
                    if (v != null && !v.isNaN()) held = "vis".equals(lane) ? shape(shapes, v) : (Object) (double) v;
                    out.add(held != null ? held : TakeClock.valueAt(old, f));
                }
                t.ch.put(lane, out);
            }
        }
        return t;
    }

    /**
     * Writes lips from a sound (the video's own, or a sound layer with no picture: "Lips from a sound layer"): the mouth's
     * opening and shape for each frame, over the whole item.
     */
    public static Take writeLips(Take base, int n, float[] open, String[] vis) {
        return writeLips(base, n, 0, open, vis);
    }

    /** As above, from frame {@code from} of the take on (the frames before it keep what they had). */
    public static Take writeLips(Take base, int n, int from, float[] open, String[] vis) {
        Take t = grown(base, n);
        List<Object> oldMo = t.ch.get("mouth"), oldVi = t.ch.get("vis");
        List<Object> mo = new ArrayList<>(n), vi = new ArrayList<>(n);
        for (int f = 0; f < n; f++) {
            int k = f - from;
            if (k < 0) {
                Object om = TakeClock.valueAt(oldMo, f), ov = TakeClock.valueAt(oldVi, f);
                mo.add(om != null ? om : 0.0);
                vi.add(ov != null ? ov : "Neutral");
                continue;
            }
            mo.add(k < open.length ? (Object) (double) open[k] : 0.0);
            vi.add(k < vis.length && vis[k] != null ? vis[k] : "Neutral");
        }
        t.ch.put("mouth", mo);
        t.ch.put("vis", vi);
        return t;
    }

    /**
     * What a video gave at its own sampling times, on the take's frames: frame {@code f} at {@code f / fps} seconds gets
     * the signals between the two samples round it, each number blended by time; the mouth shape ({@code vis}) is never
     * blended (the one before it holds). A frame with no face on either side gets null (a lost face); a frame past the
     * last sample holds it.
     *
     * @param times   each sample's time in item seconds, rising
     * @param samples the signals there (null: no face in that frame)
     */
    public static List<Map<String, Float>> resample(double[] times, List<Map<String, Float>> samples, int fps, int n) {
        List<Map<String, Float>> out = new ArrayList<>(n);
        int j = 0;
        for (int f = 0; f < n; f++) {
            double t = f / (double) fps;
            while (j + 1 < times.length && times[j + 1] <= t + 1e-9) j++;
            if (times.length == 0 || t < times[0] - 1e-9) {
                out.add(null);
                continue;
            }
            Map<String, Float> a = samples.get(j);
            Map<String, Float> b = j + 1 < times.length ? samples.get(j + 1) : null;
            if (a == null) {
                out.add(null);
                continue;
            }
            if (b == null || times[j + 1] - times[j] <= 0) {
                out.add(a);
                continue;
            }
            float k = (float) ((t - times[j]) / (times[j + 1] - times[j]));
            Map<String, Float> m = new LinkedHashMap<>();
            for (Map.Entry<String, Float> e : a.entrySet()) {
                Float bv = b.get(e.getKey());
                Float av = e.getValue();
                m.put(e.getKey(), "vis".equals(e.getKey()) || bv == null || av == null ? av : av + (bv - av) * k);
            }
            out.add(m);
        }
        return out;
    }

    private static Take grown(Take base, int n) {
        Take t = base != null ? base.copy() : new Take();
        t.n = Math.max(t.n, n);
        return t;
    }

    private static String shape(String[] shapes, float v) {
        int i = Math.round(v);
        return shapes != null && i >= 0 && i < shapes.length ? shapes[i] : "Neutral";
    }
}
