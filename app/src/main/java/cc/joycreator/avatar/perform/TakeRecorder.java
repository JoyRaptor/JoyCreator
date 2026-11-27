package cc.joycreator.avatar.perform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recording and playing a take: a port of the take half of {@code tools/avatarlab/al-inputs.js}.
 *
 * <p>Every signal has an OWNER (head, mouth, mood, blink, each hand, each foot, body, wind, each button, each pinned
 * point). Recording over a take is punch-in, punch-out: an owner is written only while something live is driving it,
 * then handed back to the old take over a quarter of a second. Whatever was not touched is the same afterwards, sample
 * for sample. One "take back" undoes the whole pass.</p>
 *
 * <p>Plain Java and headless: {@link #step} takes the live signals and says which ones the take supplies; the room
 * (and Studio's puppet) apply them to the character. The voice kept with the take is added beside this.</p>
 */
public final class TakeRecorder {
    /** How long an owner is handed back to the old take after the finger lets go, in seconds. */
    public static final double FADE = 0.25;
    /** A take is never longer than this many seconds (a pass stops itself there). */
    public static final double MAX_SECONDS = 120;

    private static final Object NOTHING = new Object();   // "the take has no value here" (not the same as null: not driven)

    private static final Map<String, String> OWN = new HashMap<>();
    private static final Set<String> STEP = new HashSet<>();

    static {
        String[][] o = {
                {"head", "turnX", "turnY", "roll", "lookX", "lookY"}, {"mouth", "mouth", "vis"},
                {"mood", "browL", "browR", "lidUp", "lidLow", "lidTilt"}, {"blink", "blinkL", "blinkR"},
                {"handL", "hLx", "hLy", "hLw"}, {"handR", "hRx", "hRy", "hRw"}, {"footL", "fLx", "fLy", "fLr"}, {"footR", "fRx", "fRy", "fRr"},
                {"body", "spin", "sway", "bounce", "flip", "rootX", "rootY", "rootS", "walk"}, {"wind", "wind"},
                {"shoulders", "shrugL", "shrugR", "shrug"}};
        for (String[] g : o) for (int i = 1; i < g.length; i++) OWN.put(g[i], g[0]);
        STEP.add("vis");
        STEP.add("flip");
        STEP.add("hLw");
        STEP.add("hRw");
    }

    /** Who owns a signal: a fixed group for the body's own signals; a button, pin or face pin for the rest. */
    public static String ownerOf(String name) {
        String g = OWN.get(name);
        if (g != null) return g;
        String p = name.length() >= 3 ? name.substring(0, 3) : name;
        if (p.equals("pt.") || name.startsWith("face.")) return "facepins";
        if (p.equals("dx:") || p.equals("dy:")) return "head";
        if (name.startsWith("p:")) return name.substring(0, name.lastIndexOf(':'));
        return name;
    }

    private Take take;
    private String backup;                  // the take as it was when the pass began, for "take back the last pass"
    private boolean recording, playing, scrub;
    private boolean fresh;
    private double ph;                      // the take's clock, seconds
    private final Map<String, Double> fade = new LinkedHashMap<>();
    private final Set<String> wrote = new HashSet<>();

    public Take take() {
        return take;
    }

    public void setTake(Take t) {
        take = t;
        backup = null;
        recording = false;
        playing = false;
        ph = 0;
    }

    public boolean recording() {
        return recording;
    }

    public boolean playing() {
        return playing;
    }

    public double clock() {
        return ph;
    }

    /** Moves the clock without playing (the take strip is dragged): the character shows that frame, silently. */
    public void scrubTo(double seconds) {
        ph = Math.max(0, seconds);
        if (!recording && !playing) scrub = true;
    }

    public double duration() {
        return take == null ? 0 : take.seconds();
    }

    /** The owners something live wrote during this pass. */
    public Set<String> wroteOwners() {
        return new HashSet<>(wrote);
    }

    /** Begins a pass: remembers the take so the whole pass can be taken back. */
    public void record() {
        if (take == null) take = new Take();
        backup = take.toJsonSafe();
        recording = true;
        playing = true;
        scrub = false;
        ph = 0;
        wrote.clear();
        fade.clear();
        fresh = take.n == 0;
    }

    /** Ends a pass (or stops playing). */
    public void stop() {
        if (recording && take != null) take.n = Math.max(take.n, (int) Math.floor(ph * take.fps));
        recording = false;
        playing = false;
        scrub = false;
    }

    /** Takes the last recording pass back, whole. False when there is no pass to take back. */
    public boolean undoPass() {
        if (backup == null) return false;
        recording = false;
        playing = false;
        Take t = Take.fromJsonSafe(backup);
        backup = null;
        take = t != null && t.n > 0 ? t : null;
        ph = 0;
        return true;
    }

    /** True when the last recording pass can still be taken back. */
    public boolean hasPassToTakeBack() {
        return backup != null;
    }

    public void play() {
        if (take == null || take.n == 0) return;
        playing = true;
        recording = false;
        scrub = false;
        if (ph >= take.n / (double) take.fps - 0.05) ph = 0;
    }

    public void clear() {
        recording = false;
        playing = false;
        take = null;
        backup = null;
        ph = 0;
    }

    /**
     * One frame. {@code live} holds the value of every signal being read from the character now (a Double, a mouth
     * shape's name, or null for "not driven"); {@code names} is every signal the take may hold, {@code own} which
     * owners something live is driving. While recording, only owned (and fading, and on a fresh take, all) signals are
     * written. Returns what the take supplies for the others: apply it to the character.
     *
     * @param dt seconds since the last frame
     */
    public Map<String, Object> step(double dt, Map<String, Object> live, List<String> names, Set<String> own) {
        Map<String, Object> out = new LinkedHashMap<>();
        Take tk = take;
        if (tk == null || !(playing || recording || scrub)) return out;
        int i = (int) Math.floor(ph * tk.fps);
        for (String n : names) {
            String g = ownerOf(n);
            List<Object> arr = tk.ch.get(n);
            Object had = (arr != null && !arr.isEmpty()) ? arr.get(Math.min(i, arr.size() - 1)) : NOTHING;
            boolean mine = own.contains(g);
            double f = fade.containsKey(g) ? fade.get(g) : 0;
            boolean fading = !mine && f > 0;
            if (recording && (fresh || mine || fading)) {
                Object val = live.get(n);
                if (fading) {
                    if (had == NOTHING) continue;
                    boolean keep = STEP.contains(n) || (n.length() > 1 && n.charAt(1) == ':') || had == null || val == null
                            || !(val instanceof Number) || !(had instanceof Number);
                    val = keep ? had : (Object) (((Number) had).doubleValue() + (((Number) val).doubleValue() - ((Number) had).doubleValue()) * (f / FADE));
                }
                if (arr == null) {
                    arr = new ArrayList<>();
                    tk.ch.put(n, arr);
                }
                while (arr.size() < i) arr.add(arr.isEmpty() ? val : arr.get(arr.size() - 1));
                if (arr.size() == i) arr.add(val);
                else arr.set(i, val);
                wrote.add(g);
                if (fading) out.put(n, val);
            } else if (!mine && had != NOTHING) {
                out.put(n, had);
            }
        }
        for (Map.Entry<String, Double> e : new ArrayList<>(fade.entrySet())) if (!own.contains(e.getKey())) fade.put(e.getKey(), Math.max(0, e.getValue() - dt));
        for (String g : own) fade.put(g, FADE);
        if (playing || recording) ph += dt;
        if (recording) {
            tk.n = Math.max(tk.n, i + 1);
            if (ph > MAX_SECONDS) stop();
        } else if (playing && ph >= tk.n / (double) tk.fps) ph = 0;
        return out;
    }
}
