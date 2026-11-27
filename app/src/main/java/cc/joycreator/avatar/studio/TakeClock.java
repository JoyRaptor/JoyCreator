package cc.joycreator.avatar.studio;

import cc.joycreator.avatar.perform.Take;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A character's take on Studio's clock: which frame an item-local time is, what every lane says there, and the take
 * cut where Studio cuts the item (a split keeps the right half's motion exactly where it was).
 *
 * <p>Plain Java. A lane shorter than the take holds its last value (AVA_FORMAT.md B4); before the first frame the take
 * shows frame 0 and after its end the last frame, as Studio holds a sprite past its end.</p>
 */
public final class TakeClock {
    private TakeClock() {}

    /** The take's length in milliseconds (at least one frame, so a dropped character is never zero long). */
    public static long lengthMs(Take t) {
        if (t == null) return 0;
        int fps = t.fps > 0 ? t.fps : Take.FPS;
        return Math.round(Math.max(1, t.n) * 1000.0 / fps);
    }

    /** The frame showing at {@code ms} into the item: the one whose span holds it, clamped to the take. */
    public static int frameAt(Take t, long ms) {
        if (t == null || t.n <= 0) return 0;
        int fps = t.fps > 0 ? t.fps : Take.FPS;
        long f = (long) Math.floor(ms * (double) fps / 1000.0 + 1e-9);
        return (int) Math.max(0, Math.min(t.n - 1, f));
    }

    /** Every driven lane's value at {@code ms} into the item (a lane that is null there is left out: not driven). */
    public static Map<String, Object> sample(Take t, long ms) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (t == null) return out;
        int f = frameAt(t, ms);
        for (Map.Entry<String, List<Object>> e : t.ch.entrySet()) {
            Object v = valueAt(e.getValue(), f);
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    /** A lane's value at frame {@code f}: its last value when it is shorter, null when it is empty. */
    public static Object valueAt(List<Object> lane, int f) {
        if (lane == null || lane.isEmpty()) return null;
        return lane.get(Math.max(0, Math.min(lane.size() - 1, f)));
    }

    /**
     * The take from {@code ms} on, for the right half of a split: frame for frame what the item showed from there.
     * The left half keeps the whole take (Studio shows it only up to the cut).
     */
    public static Take sliceFrom(Take t, long ms) {
        if (t == null) return null;
        Take o = new Take();
        o.fps = t.fps;
        int from = frameAt(t, ms);
        o.n = Math.max(1, t.n - from);
        for (Map.Entry<String, List<Object>> e : t.ch.entrySet()) {
            List<Object> lane = e.getValue();
            List<Object> cut = new ArrayList<>();
            if (lane.size() > from) cut.addAll(lane.subList(from, lane.size()));
            else if (!lane.isEmpty()) cut.add(lane.get(lane.size() - 1));   // it was holding its last value: keep holding it
            o.ch.put(e.getKey(), cut);
        }
        return o;
    }

    /** Frames for a stretch of item time: how many frames {@code ms} milliseconds take at the take's rate. */
    public static int framesFor(Take t, long ms) {
        int fps = t != null && t.fps > 0 ? t.fps : Take.FPS;
        return (int) Math.max(1, Math.round(ms * fps / 1000.0));
    }
}
