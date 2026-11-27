package cc.joycreator.avatar.scene;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One clock for the scene: passes, the camera lane and shots (a port of the pilot's {@code al-scene-take.js}).
 *
 * <p>One performer, several characters: you have one pair of hands and one voice, so a scene is performed in PASSES. The character
 * in hand records; everyone else plays back what they already did, in step; anyone with nothing recorded stays alive by themselves.
 * One clock runs them all ({@link #tick}).</p>
 *
 * <p>The camera works in SHOTS: {@code wide} (the whole frame), {@code two} (everyone, framed together), {@code close:<id>} (found
 * from the head, and it follows them), a shot you kept (its id), or {@code free} (framed by hand). While recording, each change is
 * written on the camera lane, and from the first change in a pass the camera is yours from there on (older cuts after it are
 * replaced), like any other pass. Phone and pilot give the same numbers (tools/jvm-harness SceneClockTest).</p>
 */
public final class SceneClock {

    /** What the clock needs to know about the performers and what they fill on the stage. Implemented by the room. */
    public interface Cast {
        /** Ids of the cast items that have a performer. */
        List<String> ids();

        double duration(String id);

        boolean hasTake(String id);

        boolean recording(String id);

        boolean playing(String id);

        boolean scrubbing(String id);

        double phase(String id);

        /** Play {@code id}'s take at this moment of the scene (no running of its own). */
        void follow(String id, double ph);

        /** Let {@code id} go back to being alive by itself. */
        void unfollow(String id);

        /** The scene's length, so a take can run as long as the longest one in it. */
        void sceneLength(String id, double len);

        /** What a performer fills on the stage now, {x0, y0, x1, y1} in stage units, or null. */
        double[] box(String id);

        /** The head of it, or null (then the top of its box is used). */
        double[] headBox(String id);
    }

    /** The camera: where it looks (stage units) and how far in (1 = the whole frame). */
    public static final class Cam {
        public double x = SceneGeometry.W / 2.0, y = SceneGeometry.H / 2.0, z = 1;

        public Cam() {
        }

        public Cam(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Cam copy() {
            return new Cam(x, y, z);
        }
    }

    private final Scene scene;
    public String shot = "wide";
    /** The view framed by hand (key {@code free}), or null. */
    public Cam free;
    public final Cam cam = new Cam();
    /** The clock: seconds into the scene, whether it is running the others, and whether a play of everyone was asked for. */
    public double ph;
    public boolean on;
    public boolean runPlay;
    /** The scene's time in seconds (the camera's ease runs on it). */
    public double t;
    private Cam easeFrom;
    private double easeT0, easeDur;
    private boolean cutNow;
    private JSONObject camAt;
    private boolean camPass;
    private final Set<String> following = new HashSet<>();

    public SceneClock(Scene scene) {
        this.scene = scene;
    }

    // ── time ──
    /** How long the scene runs: the longest take in it, or the last camera change and a breath. */
    public double length(Cast c) {
        double len = 0;
        for (String id : c.ids()) len = Math.max(len, c.duration(id));
        JSONArray lane = scene.cam();
        if (lane.length() > 0) len = Math.max(len, lane.optJSONObject(lane.length() - 1).optDouble("t", 0) + 1.5);
        return len;
    }

    /** "rec", "play" or null: what the one in hand, or the scene, is doing. */
    public String running(Cast c, String focus) {
        if (focus != null && c.ids().contains(focus) && c.recording(focus)) return "rec";
        if ((focus != null && c.ids().contains(focus) && c.playing(focus)) || runPlay) return "play";
        return null;
    }

    /** A pass begins or the scene is played again: the camera lane is the lane again, not yet the one being written. */
    public void forgetCamPass() {
        camPass = false;
        camAt = null;
        runPlay = false;
    }

    /** Play the whole scene from the start (no performer leads: the clock does). */
    public void playAll(Cast c) {
        if (length(c) <= 0) return;
        runPlay = true;
        on = true;
        ph = 0;
        camAt = null;
        camPass = false;
    }

    public void stop(Cast c, String focus) {
        runPlay = false;
        on = false;
        for (String id : new ArrayList<>(following)) {
            c.unfollow(id);
            following.remove(id);
        }
        // the one in hand is stopped by its own engine; the room calls it
    }

    /**
     * Once a frame, before anyone moves: who leads the clock, and everyone else in step with it. {@code focus} is the one in hand.
     * Also moves the camera along the lane when the clock is running.
     */
    public void tick(Cast c, String focus, double dt) {
        t += dt;
        double len = length(c), was = ph;
        boolean hand = focus != null && c.ids().contains(focus);
        boolean lead = hand && (c.hasTake(focus) || c.recording(focus)) && (c.recording(focus) || c.playing(focus) || c.scrubbing(focus));
        if (hand) c.sceneLength(focus, len);
        if (lead) {
            ph = c.phase(focus);
            on = true;
            runPlay = false;
        } else if (runPlay) {
            if (len <= 0) {
                runPlay = false;
                on = false;
            } else {
                ph += dt;
                if (ph >= len) ph = 0;
                on = true;
            }
        } else on = false;
        if (on && ph < was - 0.2) camAt = null;                         // it came round again
        for (String id : c.ids()) {
            if (lead && id.equals(focus)) continue;
            if (on && c.hasTake(id)) {
                c.follow(id, Math.min(ph, c.duration(id)));
                following.add(id);
            } else if (following.remove(id)) c.unfollow(id);
        }
        JSONArray lane = scene.cam();
        if (!on || lane.length() == 0 || camPass) return;
        JSONObject e = null;
        for (int i = 0; i < lane.length(); i++) {
            JSONObject q = lane.optJSONObject(i);
            if (q.optDouble("t", 0) <= ph + 1e-4) e = q;
            else break;
        }
        if (e != null && e != camAt) {
            camAt = e;
            if (shotOk(c, e.optString("shot"))) setShot(e.optString("shot"), e.optDouble("ease", 0));
        }
    }

    // ── shots ──
    public static final class ShotInfo {
        public final String key, name, kind;

        ShotInfo(String key, String name, String kind) {
            this.key = key;
            this.name = name;
            this.kind = kind;
        }
    }

    /** The shots on offer right now, in the order the strip shows them. */
    public List<ShotInfo> shots(Cast c) {
        List<ShotInfo> l = new ArrayList<>();
        l.add(new ShotInfo("wide", "Wide", "wide"));
        List<String> ids = c.ids();
        if (ids.size() > 1) l.add(new ShotInfo("two", ids.size() == 2 ? "Two-shot" : "Everyone", "two"));
        for (JSONObject it : scene.cast()) if (ids.contains(it.optString("id"))) l.add(new ShotInfo("close:" + it.optString("id"), it.optString("name"), "close"));
        JSONArray own = scene.shots();
        for (int i = 0; i < own.length(); i++) l.add(new ShotInfo(own.optJSONObject(i).optString("id"), own.optJSONObject(i).optString("name"), "own"));
        return l;
    }

    public boolean shotOk(Cast c, String key) {
        for (ShotInfo s : shots(c)) if (s.key.equals(key)) return true;
        return false;
    }

    public String shotName(Cast c, String key) {
        if ("free".equals(key)) return "By hand";
        for (ShotInfo s : shots(c)) if (s.key.equals(key)) return "close".equals(s.kind) ? "Close on " + s.name : s.name;
        return "Wide";
    }

    /** Where the camera should be for a shot: its middle (stage units) and how far in (1 = the whole frame). {@code fr} = {x, y, w, h}. */
    public Cam target(Cast c, String key, double[] fr) {
        Cam wide = new Cam();
        if ("free".equals(key) && free != null) return free;
        if ("two".equals(key)) {
            double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
            for (String id : c.ids()) {
                double[] q = c.box(id);
                if (q == null) continue;
                x0 = Math.min(x0, q[0]);
                x1 = Math.max(x1, q[2]);
                y0 = Math.min(y0, q[1]);
                y1 = Math.max(y1, q[3]);
            }
            if (x0 > x1) return wide;
            return new Cam((x0 + x1) / 2, (y0 + y1) / 2, SceneGeometry.clamp(Math.min(fr[2] / ((x1 - x0) * 1.3), fr[3] / ((y1 - y0) * 1.25)), 1, 4));
        }
        if (key.startsWith("close:")) {
            String id = key.substring(6);
            double[] b = c.headBox(id);
            if (b == null) {
                double[] q = c.box(id);
                if (q == null) return wide;
                b = new double[]{q[0], q[1], q[2], q[1] + (q[3] - q[1]) * 0.45};      // no head named: the top of it
            }
            double hh = Math.max(30, b[3] - b[1]);
            return new Cam((b[0] + b[2]) / 2, (b[1] + b[3]) / 2 + hh * 0.22, SceneGeometry.clamp(Math.min(fr[3] / (hh * 2.3), fr[2] / ((b[2] - b[0]) * 1.5)), 1.15, 7));
        }
        JSONArray own = scene.shots();
        for (int i = 0; i < own.length(); i++) {
            JSONObject s = own.optJSONObject(i);
            if (key.equals(s.optString("id"))) return new Cam(s.optDouble("x"), s.optDouble("y"), s.optDouble("z", 1));
        }
        return wide;
    }

    /** The frame never looks off the edge of the stage. */
    public static Cam clampCam(Cam c, double[] fr) {
        double z = SceneGeometry.clamp(c.z, 1, 8), hw = fr[2] / (2 * z), hh = fr[3] / (2 * z), W = SceneGeometry.W, H = SceneGeometry.H;
        return new Cam(hw >= W / 2 ? W / 2 : SceneGeometry.clamp(c.x, hw, W - hw), hh >= H / 2 ? H / 2 : SceneGeometry.clamp(c.y, hh, H - hh), z);
    }

    private void setShot(String key, double ease) {
        shot = key;
        if (ease > 0) {
            easeFrom = cam.copy();
            easeT0 = t;
            easeDur = ease;
        } else {
            easeFrom = null;
            cutNow = true;
        }
    }

    /** The camera this frame. A cut lands at once; an ease glides; a shot that follows someone keeps up with them softly. */
    public Cam camNow(Cast c, double[] fr, double dt) {
        Cam tg = clampCam(target(c, shot, fr), fr), cur = cam;
        if (easeFrom != null) {
            double u = (t - easeT0) / easeDur;
            if (u >= 1) {
                easeFrom = null;
                cur = tg;
            } else {
                double s = u * u * (3 - 2 * u);
                Cam f0 = easeFrom;
                cur = new Cam(f0.x + (tg.x - f0.x) * s, f0.y + (tg.y - f0.y) * s, Math.exp(Math.log(f0.z) + (Math.log(tg.z) - Math.log(f0.z)) * s));
            }
        } else if (cutNow) {
            cur = tg;
            cutNow = false;
        } else {
            double k = 1 - Math.exp(-5 * dt);
            cur = new Cam(cur.x + (tg.x - cur.x) * k, cur.y + (tg.y - cur.y) * k, cur.z + (tg.z - cur.z) * k);
        }
        cam.x = cur.x;
        cam.y = cur.y;
        cam.z = cur.z;
        return cam;
    }

    /**
     * Go to a shot. {@code ease} = seconds to glide there (0 = cut). While the one in hand records (at {@code recPhase}, else NaN) it is
     * written on the camera lane. Returns false for a shot that is not on offer.
     */
    public boolean goShot(Cast c, String key, double ease, double recPhase) {
        if (!"free".equals(key) && !shotOk(c, key)) return false;
        String before = shot;
        if (!Double.isNaN(recPhase)) {
            double tt = Math.round(recPhase * 100) / 100.0;
            JSONArray lane = scene.cam();
            try {
                if (!camPass) {
                    camPass = true;
                    JSONArray n = new JSONArray();
                    for (int i = 0; i < lane.length(); i++) if (lane.getJSONObject(i).optDouble("t", 0) < tt) n.put(lane.get(i));
                    if (n.length() == 0 && tt > 0.05 && !"free".equals(before)) n.put(new JSONObject().put("t", 0).put("shot", before).put("ease", 0));
                    lane = n;
                }
                lane.put(new JSONObject().put("t", tt).put("shot", key).put("ease", ease));
                scene.doc.put("cam", lane);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        setShot(key, ease);
        return true;
    }

    /** Take the camera by hand (drag the empty stage, pinch): {@code fn} changes the view. */
    public void freeCam(double[] fr, double dx, double dy, double zoom) {
        if (!"free".equals(shot) || free == null) free = cam.copy();
        free.x += dx;
        free.y += dy;
        free.z *= zoom;
        free = clampCam(free, fr);
        shot = "free";
        easeFrom = null;
        cutNow = true;
    }

    /** Keep the view as it is now as a shot of this scene. */
    public JSONObject keepShot(String name) {
        JSONObject s = scene.keepShot(name, cam.x, cam.y, cam.z);
        shot = s.optString("id");
        free = null;
        return s;
    }

    public void dropShot(String id) {
        scene.dropShot(id);
        if (id.equals(shot)) shot = "wide";
    }

    /** A thing was taken out of the scene: a camera on its close-up goes back to Wide. */
    public void itemRemoved(String id) {
        if (("close:" + id).equals(shot)) shot = "wide";
        following.remove(id);
    }

    /** The camera goes back to the whole frame at once (the frame went off, or the scene went to Arrange). */
    public void toWide() {
        shot = "wide";
        free = null;
        easeFrom = null;
        cutNow = true;
    }

    public void clearCam() {
        scene.clearCam();
        camAt = null;
    }
}
