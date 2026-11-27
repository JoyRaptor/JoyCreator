package cc.joycreator.avatar.solve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One frame of a character: what to draw, in order, and where things are (the pilot's {@code out}). */
public final class SolveOut {
    public final List<Item> items = new ArrayList<>();
    /** each set's chosen drawing */
    public final Map<String, String> picks = new HashMap<>();
    /** each part's matrix as drawn (the transform box works in {@link #before}) */
    public final Map<String, double[]> m = new HashMap<>(), before = new HashMap<>();
    /** handles for the room: a limb's shoulder, elbow and hand; a mesh's pins; a chain's joints (stage space) */
    public final Map<String, double[][]> pins = new HashMap<>();
    /** where each limb or swinging part ends and which way it points there (riders hang on it) */
    final Map<String, Tip> tips = new HashMap<>();
    final Map<String, Item> first = new HashMap<>(), last = new HashMap<>();
    public double[] rootM;
    public boolean flipped;
    /** colour sets in play: up to 8 stops, each { from, to, sat, val, width, level, soft, blotch }, the box the wave crosses, its angle */
    public List<double[]> toneStops;
    public double[] toneBox;
    public double toneDir;

    static final class Tip {
        double[] p, restP;
        double ang, rest;
        String under;
        Item.Z zo;
    }
}
