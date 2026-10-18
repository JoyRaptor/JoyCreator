package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.compositor.OverlayVideoPreviewView;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.Keyframe;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The motion family (gap F2): movement over time on one layer. "Slide the picture in from the
 * left over one second", "zoom the title in slowly", "spin it once", "fade it out at the end",
 * "remove the movement". Written the way the editor's keyframe diamonds and sliders write them
 * (the Transform rows of each layer's drawer), so a motion Joybot makes is one the person could
 * make by hand.
 *
 * <p>Mirrors, by layer kind:</p>
 * <ul>
 *   <li>Text, pictures ({@code TextOverlayItem}): the first key arms the whole pose at the start
 *       ({@code addKeyframeAt}, the diamond's first tap); each value is
 *       {@code addPropertyKeyframeAt} (the slider's armed write); the ease is the outgoing curve of
 *       the key at the start ({@code setSegmentEasingForOverlay}). Clear: {@code clearKeyframes}.</li>
 *   <li>Stickers ({@code SpriteOverlayItem}): as the text row, except that the sprite's own
 *       {@code addPropertyKeyframeAt} rewrites the pose tracks with their STATIC values at that
 *       time, which would undo the other end of a move. The pose tracks are seeded from the
 *       animated values only where they have no key yet — the same rule the text write uses.</li>
 *   <li>Floating videos ({@code Clip} overlay transform): as {@code FaditorEditorActivity.pipMenuProp}
 *       and {@code pipKeyableSpan}. Keys are on the timeline's absolute time, the first arm keys
 *       all five properties, and the default curve is linear, as the editor writes it.</li>
 * </ul>
 *
 * <p>Ranges are the drawers' own slider ranges. Position is -1..2 for text and floating videos (the
 * fixed travel rails of {@code KeyframeSet.POS_MIN/POS_MAX}; large text can travel further in the
 * editor). Stickers stay 0..1, as their slider does. Rotation is in degrees and may wind past one
 * turn, because the typed field stores whole turns ("16x") rather than folding them.</p>
 */
final class MotionActions {
    private MotionActions() { }

    private static final String FAMILY = "motion";
    private static final String LAYER_HELP = "Which layer: its id, or the words on a text. Pictures, stickers and"
            + " floating videos work by id";

    /** The four things a motion can move: the Transform rows' Position, Size, Turn and Opacity. */
    static final List<String> PROPS = Arrays.asList("position", "scale", "rotation", "opacity");

    /** The ease picker's own order (EasePickerPopover.ORDER). */
    static final List<String> EASES = Arrays.asList("LINEAR", "EASE_IN", "EASE_OUT", "EASE_IN_OUT", "HOLD",
            "EASE_IN_EXPO", "EASE_OUT_EXPO", "ANTICIPATE", "OVERSHOOT", "SPRING_SOFT", "SPRING", "SPRING_BOUNCY",
            "BOUNCE", "STAIRS_4");

    /** Ten turns each way: a typed rotation has no fold, so a spin needs more than 180. */
    private static final double ROT_LIMIT = 3600.0;
    private static final long OPEN = Long.MAX_VALUE;
    /** Pose tracks a floating video keys together on its first arm (PipMenu PIP_KEYS). */
    private static final String[] PIP_KEYS = {KeyframeSet.X, KeyframeSet.Y, KeyframeSet.SCALE,
            KeyframeSet.ROTATION, KeyframeSet.OPACITY};
    /** The same five tracks a text or sticker's "clear all keyframes" removes. */
    private static final String[] POSE_KEYS = PIP_KEYS;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("add_motion", "Move, grow, turn or fade a layer over time")
                .help("Animate one thing about a text, picture, sticker or floating video between two moments on the"
                        + " timeline. property: position (across and down), scale (size), rotation (degrees; 360 is one"
                        + " full turn), or opacity (0 invisible, 1 solid). Position: 0 = left or top edge, 1 = right or"
                        + " bottom edge; below 0 or above 1 is off-frame, so a slide-in starts at a negative x. Give"
                        + " fromX/fromY and toX/toY (any left out keeps where it is). Other properties: from and to."
                        + " fromMs and toMs are timeline milliseconds; the movement is kept inside the layer's own time."
                        + " from left out starts from wherever the layer is at fromMs. ease is the curve between the two"
                        + " moments. Use this for slides, zooms, spins and fades. For one still pose use"
                        + " set_layer_transform; to remove movement use clear_motion.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap a text, picture, sticker or floating video › Transform › tap ◆ beside Position,"
                        + " Size, Turn or Opacity, then move its slider; the ease tile beside the row sets the curve")
                .id("layer", "layer", true, LAYER_HELP)
                .choice("property", true, "What moves: position, scale, rotation or opacity", PROPS)
                .time("fromMs", true, "When the movement starts, in timeline milliseconds", 0.0, null)
                .time("toMs", true, "When it arrives, in timeline milliseconds (later than fromMs)", 0.0, null)
                .number("from", false, "Starting value for scale (1 = normal size), rotation (degrees) or opacity"
                        + " (0–1). Left out, it starts where the layer is at fromMs", -ROT_LIMIT, ROT_LIMIT)
                .number("to", false, "Ending value, in the same units as from (scale 0.02–10, degrees, opacity 0–1)",
                        -ROT_LIMIT, ROT_LIMIT)
                .number("fromX", false, "Position only: starting centre across the frame (0 = left edge, 1 = right"
                        + " edge; below 0 or above 1 is off-frame). Left out, keeps where it is", (double) KeyframeSet.POS_MIN,
                        (double) KeyframeSet.POS_MAX)
                .number("fromY", false, "Position only: starting centre down the frame (0 = top, 1 = bottom)",
                        (double) KeyframeSet.POS_MIN, (double) KeyframeSet.POS_MAX)
                .number("toX", false, "Position only: ending centre across the frame (0 = left edge, 1 = right edge)",
                        (double) KeyframeSet.POS_MIN, (double) KeyframeSet.POS_MAX)
                .number("toY", false, "Position only: ending centre down the frame (0 = top, 1 = bottom)",
                        (double) KeyframeSet.POS_MIN, (double) KeyframeSet.POS_MAX)
                .choice("ease", false, "The curve between the two moments. Default: ease in out (linear for floating"
                        + " videos)", EASES)
                .build(), MotionActions::addMotion);

        r.add(ActionSpec.of("clear_motion", "Remove a layer's movement")
                .help("Remove the movement from a layer so it sits still again at the position, size, turn and opacity"
                        + " it had before it started moving. Leave property out to remove all of it, or name one"
                        + " (position, scale, rotation or opacity) to remove only that.")
                .wing("STUDIO", FAMILY).effect(Effect.DESTRUCTIVE)
                .where("Studio › tap a text, picture, sticker or floating video › Transform › Clear all keyframes"
                        + " (or long-press each ◆ on one row to delete it)")
                .id("layer", "layer", true, LAYER_HELP)
                .choice("property", false, "Which movement to remove. Left out removes all of it", PROPS)
                .build(), MotionActions::clearMotion);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult addMotion(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Found f = findLayer(ctx, a.str("layer"));
        Mover m = f.mover;
        if (m.linked()) {
            throw new ActionError(f.name + " follows another layer, so that layer sets where it moves."
                    + " Move it in the editor.");
        }
        String prop = a.choice("property", PROPS);
        boolean pos = "position".equals(prop);
        if (pos && (a.has("from") || a.has("to"))) {
            throw new ActionError("For position, give fromX and toX (across) and fromY and toY (down)."
                    + " from and to are for size, turn and opacity.");
        }
        if (!pos && (a.has("fromX") || a.has("fromY") || a.has("toX") || a.has("toY"))) {
            throw new ActionError("fromX, fromY, toX and toY are for position only. For " + wordFor(prop)
                    + ", give from and to.");
        }
        if (pos && !a.has("toX") && !a.has("toY")) {
            throw new ActionError("Where should it move to? Give toX (across) and/or toY (down).");
        }
        if (!pos && !a.has("to")) throw new ActionError("What value should it end at? Give to.");

        List<String> notes = new ArrayList<>();
        long from = keepInLayer(a.whole("fromMs"), m, notes);
        long to = keepInLayer(a.whole("toMs"), m, notes);
        if (to <= from) {
            throw new ActionError("The movement has to end after it starts. " + f.name + " is on the timeline from "
                    + StudioCtx.clock(m.startMs()) + " to " + (m.endMs() == OPEN ? "the end"
                    : StudioCtx.clock(m.endMs())) + ".");
        }

        List<Leg> legs = new ArrayList<>();
        if (pos) {
            legs.add(leg(m, KeyframeSet.X, a, "fromX", "toX", notes));
            legs.add(leg(m, KeyframeSet.Y, a, "fromY", "toY", notes));
        } else {
            legs.add(leg(m, keyFor(prop), a, "from", "to", notes));
        }
        // Defaults read BEFORE the first key arms the layer: an unarmed layer's animated value is
        // its still pose, so "left out" means exactly where it sits.
        for (Leg l : legs) {
            l.fromV = l.from != null ? l.from : m.now(l.key, from);
            l.toV = l.to != null ? l.to : m.now(l.key, to);
        }
        if (!m.armed()) m.arm(from);

        Easing ease = Easing.valueOf(a.has("ease") ? a.choice("ease", EASES) : m.defaultEase().name());
        for (Leg l : legs) {
            long keyAt = m.write(l.key, from, l.fromV);
            m.write(l.key, to, l.toV);
            setEase(m.keys(true).get(l.key), keyAt, ease);
        }

        String sum = f.name + " " + describe(prop, legs) + " between " + StudioCtx.clock(from) + " and "
                + StudioCtx.clock(to) + ".";
        ActionResult res = ActionResult.changed(sum, Arrays.asList(f.id));
        String detail = "Curve: " + ease.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ".";
        if (!notes.isEmpty()) detail = join(notes, " ") + " " + detail;
        return res.withDetail(detail);
    }

    @NonNull
    private static ActionResult clearMotion(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Found f = findLayer(ctx, a.str("layer"));
        Mover m = f.mover;
        String prop = a.has("property") ? a.choice("property", PROPS) : null;
        KeyframeSet keys = m.keys(false);
        boolean removed = false;
        if (keys != null) {
            if (prop == null) {
                for (String k : POSE_KEYS) removed |= clearTrack(keys, k);
            } else if ("position".equals(prop)) {
                removed |= clearTrack(keys, KeyframeSet.X);
                removed |= clearTrack(keys, KeyframeSet.Y);
            } else {
                removed |= clearTrack(keys, keyFor(prop));
            }
        }
        if (!removed) {
            return ActionResult.unchanged(f.name + " has no movement" + (prop == null ? "" : " in "
                    + wordFor(prop)) + " to remove.");
        }
        String what = prop == null ? "All of its movement" : "Its " + wordFor(prop) + " movement";
        return ActionResult.changed(what + " is removed from " + f.name + ".", Arrays.asList(f.id));
    }

    // ── one leg = one property track; position has two ──────────────────────

    private static final class Leg {
        final String key;
        Float from, to;      // as typed, clamped; null = left out
        float fromV, toV;    // what is written
        Leg(String key) { this.key = key; }
    }

    @NonNull
    private static Leg leg(@NonNull Mover m, @NonNull String key, @NonNull Args a, @NonNull String fromName,
                           @NonNull String toName, @NonNull List<String> notes) {
        Leg l = new Leg(key);
        if (a.has(fromName)) l.from = clampTo(a.num(fromName).floatValue(), m.range(key), notes);
        if (a.has(toName)) l.to = clampTo(a.num(toName).floatValue(), m.range(key), notes);
        return l;
    }

    @NonNull
    private static String keyFor(@NonNull String prop) {
        switch (prop) {
            case "scale": return KeyframeSet.SCALE;
            case "rotation": return KeyframeSet.ROTATION;
            case "opacity": return KeyframeSet.OPACITY;
            default: return KeyframeSet.X;
        }
    }

    @NonNull
    private static String describe(@NonNull String prop, @NonNull List<Leg> legs) {
        switch (prop) {
            case "position":
                return "moves from " + across(legs.get(0).fromV, legs.get(1).fromV) + " to "
                        + across(legs.get(0).toV, legs.get(1).toV);
            case "scale":
                return "changes size from " + pct(legs.get(0).fromV) + " to " + pct(legs.get(0).toV);
            case "rotation":
                return "turns from " + deg(legs.get(0).fromV) + " to " + deg(legs.get(0).toV);
            default:
                return "fades from " + pct(legs.get(0).fromV) + " to " + pct(legs.get(0).toV);
        }
    }

    // ── keyframe writes shared by every layer kind ───────────────────────────

    /** The key at or before {@code t} owns the segment after it (the editor's segmentEasingAt rule). */
    private static void setEase(@Nullable KeyframeTrack tr, long t, @NonNull Easing e) {
        if (tr == null || tr.keyframes.isEmpty()) return;
        Keyframe owner = null;
        for (Keyframe k : tr.keyframes) {
            if (k.timeMs <= t) owner = k; else break;
        }
        if (owner != null) owner.easing = e;
    }

    /** Remove every key on one track, as the diamond's delete does. True if there was any. */
    private static boolean clearTrack(@NonNull KeyframeSet keys, @NonNull String p) {
        boolean any = false;
        KeyframeTrack tr = keys.get(p);
        while (tr != null && !tr.keyframes.isEmpty()) {
            keys.removeKey(p, tr.keyframes.get(0).timeMs);
            any = true;
        }
        return any;
    }

    /** Seed a pose track at {@code t} from its animated value, only where it has no key yet. */
    private static void seedIfMissing(@NonNull KeyframeSet ks, @NonNull String p, float v, long t) {
        KeyframeTrack tr = ks.getOrCreate(p);
        for (Keyframe k : tr.keyframes) {
            if (k.timeMs == t) return;
        }
        tr.put(t, v, Easing.EASE_IN_OUT);
    }

    /** Times are kept inside the layer's own span, and the person is told when they were moved. */
    private static long keepInLayer(long ms, @NonNull Mover m, @NonNull List<String> notes) {
        long lo = m.startMs(), hi = m.endMs();
        long c = ms;
        if (c < lo) c = lo;
        if (hi != OPEN && c > hi) c = hi;
        if (c != ms) {
            addOnce(notes, "Times were kept inside the layer's own time, "
                    + StudioCtx.clock(lo) + " to " + (hi == OPEN ? "its end" : StudioCtx.clock(hi)) + ".");
        }
        return c;
    }

    private static float clampTo(float v, @NonNull float[] r, @NonNull List<String> notes) {
        float c = Math.max(r[0], Math.min(r[1], v));
        if (c != v) addOnce(notes, "A value was kept inside the range the editor allows.");
        return c;
    }

    @NonNull
    private static float[] rangeFor(@NonNull String p, float posLo, float posHi, float scaleLo, float scaleHi) {
        switch (p) {
            case KeyframeSet.X:
            case KeyframeSet.Y:
                return new float[]{posLo, posHi};
            case KeyframeSet.SCALE:
                return new float[]{scaleLo, scaleHi};
            case KeyframeSet.ROTATION:
                return new float[]{(float) -ROT_LIMIT, (float) ROT_LIMIT};
            default:
                return new float[]{0f, 1f};
        }
    }

    // ── the three layer kinds, behind one face ───────────────────────────────

    private interface Mover {
        long startMs();
        /** {@link #OPEN} when the layer has no end. */
        long endMs();
        boolean armed();
        boolean linked();
        float now(@NonNull String prop, long timelineMs);
        @NonNull float[] range(@NonNull String prop);
        @NonNull Easing defaultEase();
        void arm(long timelineMs);
        /** Writes one value on one track; returns the key time it landed on. */
        long write(@NonNull String prop, long timelineMs, float value);
        @Nullable KeyframeSet keys(boolean create);
    }

    private static final class TextMover implements Mover {
        private final TextOverlayItem t;
        TextMover(@NonNull TextOverlayItem t) { this.t = t; }

        @Override public long startMs() { return t.getStartMs(); }
        @Override public long endMs() { return t.getEndMs(); }
        @Override public boolean armed() { return t.isArmed(); }
        @Override public boolean linked() { return t.isLinked(); }

        @Override public float now(@NonNull String p, long ms) {
            switch (p) {
                case KeyframeSet.X: return t.animatedCenterX(ms);
                case KeyframeSet.Y: return t.animatedCenterY(ms);
                case KeyframeSet.SCALE: return t.animatedSizeFraction(ms);
                case KeyframeSet.ROTATION: return t.animatedRotation(ms);
                default: return t.animatedOpacity(ms);
            }
        }

        @NonNull @Override public float[] range(@NonNull String p) {
            return rangeFor(p, KeyframeSet.POS_MIN, KeyframeSet.POS_MAX, 0.02f, 10f);
        }

        @NonNull @Override public Easing defaultEase() { return Easing.EASE_IN_OUT; }

        @Override public void arm(long ms) { t.addKeyframeAt(ms); }

        @Override public long write(@NonNull String p, long ms, float v) {
            t.addPropertyKeyframeAt(p, ms, v);
            return Math.max(0, ms - t.getStartMs());
        }

        @Override public KeyframeSet keys(boolean create) { return t.getKeyframes(); }
    }

    private static final class SpriteMover implements Mover {
        private final SpriteOverlayItem s;
        SpriteMover(@NonNull SpriteOverlayItem s) { this.s = s; }

        @Override public long startMs() { return s.getStartMs(); }
        @Override public long endMs() { return s.getEndMs(); }
        @Override public boolean armed() { return s.isArmed(); }
        @Override public boolean linked() { return s.isLinked(); }

        @Override public float now(@NonNull String p, long ms) {
            switch (p) {
                case KeyframeSet.X: return s.animatedCenterX(ms);
                case KeyframeSet.Y: return s.animatedCenterY(ms);
                case KeyframeSet.SCALE: return s.animatedSizeFraction(ms);
                case KeyframeSet.ROTATION: return s.animatedRotation(ms);
                default: return s.animatedOpacity(ms);
            }
        }

        @NonNull @Override public float[] range(@NonNull String p) {
            // The sticker slider stops at 0..1 for position and 0.01..1 for size.
            return rangeFor(p, 0f, 1f, 0.01f, 1f);
        }

        @NonNull @Override public Easing defaultEase() { return Easing.EASE_IN_OUT; }

        @Override public void arm(long ms) { s.addKeyframeAt(ms); }

        @Override public long write(@NonNull String p, long ms, float v) {
            long k = Math.max(0, ms - s.getStartMs());
            KeyframeSet ks = s.getKeyframes();
            seedIfMissing(ks, KeyframeSet.X, s.animatedCenterX(ms), k);
            seedIfMissing(ks, KeyframeSet.Y, s.animatedCenterY(ms), k);
            seedIfMissing(ks, KeyframeSet.SCALE, s.animatedSizeFraction(ms), k);
            float clamped = v;
            if (KeyframeSet.OPACITY.equals(p)) clamped = Math.max(0f, Math.min(1f, v));
            else if (KeyframeSet.SCALE.equals(p)) clamped = Math.max(0.01f, v);
            ks.getOrCreate(p).put(k, clamped, Easing.EASE_IN_OUT);
            return k;
        }

        @Override public KeyframeSet keys(boolean create) { return s.getKeyframes(); }
    }

    private static final class PipMover implements Mover {
        private final Clip c;
        PipMover(@NonNull Clip c) { this.c = c; }

        @Override public long startMs() { return c.getOverlayStartMs(); }
        @Override public long endMs() { return c.getOverlayStartMs() + c.getVisualDurationMs(); }
        @Override public boolean armed() { return pipArmed(c); }
        @Override public boolean linked() { return false; }

        @Override public float now(@NonNull String p, long ms) {
            KeyframeSet kf = c.getOverlayTransform();
            return kf == null ? pipDefault(p) : kf.valueAt(p, ms, pipDefault(p));
        }

        @NonNull @Override public float[] range(@NonNull String p) {
            return rangeFor(p, KeyframeSet.POS_MIN, KeyframeSet.POS_MAX, 0.05f, 4f);
        }

        @NonNull @Override public Easing defaultEase() { return Easing.LINEAR; }

        @Override public void arm(long ms) {
            KeyframeSet kf = ensure(c);
            for (String k : PIP_KEYS) {
                float v = now(k, ms);
                kf.getOrCreate(k).put(ms, v, Easing.LINEAR);
            }
        }

        @Override public long write(@NonNull String p, long ms, float v) {
            KeyframeSet kf = ensure(c);
            KeyframeTrack tr = kf.getOrCreate(p);
            long t = ms;
            for (Keyframe k : tr.keyframes) {
                if (Math.abs(k.timeMs - ms) <= 66L) { t = k.timeMs; break; }
            }
            tr.put(t, v, Easing.LINEAR);
            return t;
        }

        @Override public KeyframeSet keys(boolean create) {
            return create ? ensure(c) : c.getOverlayTransform();
        }
    }

    /** Same rule as the editor's pipArmed: two keys on a track, or a key past time zero. */
    private static boolean pipArmed(@NonNull Clip c) {
        KeyframeSet kf = c.getOverlayTransform();
        if (kf == null) return false;
        for (String k : PIP_KEYS) {
            KeyframeTrack tr = kf.get(k);
            if (tr == null) continue;
            if (tr.keyframes.size() >= 2) return true;
            for (Keyframe key : tr.keyframes) {
                if (key.timeMs > 0) return true;
            }
        }
        return false;
    }

    @NonNull
    private static KeyframeSet ensure(@NonNull Clip c) {
        KeyframeSet kf = c.getOverlayTransform();
        if (kf == null) {
            kf = new KeyframeSet();
            c.setOverlayTransform(kf);
        }
        return kf;
    }

    /** The editor's per-property defaults for a floating video with no keys yet. */
    private static float pipDefault(@NonNull String p) {
        switch (p) {
            case KeyframeSet.X: return OverlayVideoPreviewView.DEFAULT_X;
            case KeyframeSet.Y: return OverlayVideoPreviewView.DEFAULT_Y;
            case KeyframeSet.SCALE: return OverlayVideoPreviewView.DEFAULT_SCALE;
            case KeyframeSet.OPACITY: return 1f;
            default: return 0f;
        }
    }

    // ── id resolution ────────────────────────────────────────────────────────

    private static final class Found {
        final Mover mover;
        final String id;
        final String name;
        Found(@NonNull Mover mover, @NonNull String id, @NonNull String name) {
            this.mover = mover; this.id = id; this.name = name;
        }
    }

    @NonNull
    private static Found findLayer(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        Timeline tl = ctx.project.getTimeline();
        String r = ref.trim();
        for (TextOverlayItem t : tl.getTextOverlays()) {
            if (r.equals(t.getId())) return foundText(t);
        }
        for (SpriteOverlayItem s : tl.getSpriteOverlays()) {
            if (r.equals(s.getId())) return new Found(new SpriteMover(s), s.getId(), "the sticker");
        }
        for (Clip c : tl.getOverlayClips()) {
            if (r.equals(c.getId())) return new Found(new PipMover(c), c.getId(), "the floating video");
        }
        // By words: text layers only (pictures carry no words).
        String want = norm(r);
        List<TextOverlayItem> exact = new ArrayList<>(), part = new ArrayList<>();
        for (TextOverlayItem t : tl.getTextOverlays()) {
            if (t.isImage()) continue;
            String w = norm(t.getText());
            if (w.equals(want)) exact.add(t);
            else if (!want.isEmpty() && w.contains(want)) part.add(t);
        }
        List<TextOverlayItem> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return foundText(hit.get(0));
        if (hit.size() > 1) {
            throw new ActionError(hit.size() + " text layers say \"" + r + "\": " + listLayers(tl, hit)
                    + ". Ask which one, or use its id.");
        }
        throw new ActionError("No layer called \"" + r + "\". The layers are: " + listLayers(tl, null) + ".");
    }

    @NonNull
    private static Found foundText(@NonNull TextOverlayItem t) {
        String name = t.isImage() ? "the picture" : "\"" + shorten(t.getText()) + "\"";
        return new Found(new TextMover(t), t.getId(), name);
    }

    @NonNull
    private static String listLayers(@NonNull Timeline tl, @Nullable List<TextOverlayItem> only) {
        List<String> out = new ArrayList<>();
        if (only != null) {
            for (TextOverlayItem t : only) out.add("\"" + shorten(t.getText()) + "\" (id " + t.getId() + ")");
        } else {
            for (TextOverlayItem t : tl.getTextOverlays()) {
                String what = t.isImage() ? "picture" : "\"" + shorten(t.getText()) + "\"";
                out.add(what + " (id " + t.getId() + ")");
            }
            for (SpriteOverlayItem s : tl.getSpriteOverlays()) out.add("sticker (id " + s.getId() + ")");
            for (Clip c : tl.getOverlayClips()) out.add("floating video (id " + c.getId() + ")");
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : out) {
            if (n++ == 12) { sb.append(", … ").append(out.size() - 12).append(" more"); break; }
            sb.append(sb.length() > 0 ? ", " : "").append(s);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ── small helpers ────────────────────────────────────────────────────────

    @NonNull
    private static String wordFor(@NonNull String prop) {
        switch (prop) {
            case "position": return "position";
            case "scale": return "size";
            case "rotation": return "turn";
            default: return "fade";
        }
    }

    @NonNull
    private static String across(float x, float y) {
        return pct(x) + " across, " + pct(y) + " down";
    }

    @NonNull
    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
    }

    @NonNull
    private static String deg(float v) {
        return Math.round(v) + "°";
    }

    private static void addOnce(@NonNull List<String> notes, @NonNull String note) {
        if (!notes.contains(note)) notes.add(note);
    }

    @NonNull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String shorten(@Nullable String s) {
        String w = s == null ? "" : s.replace('\n', ' ').trim();
        return w.length() > 30 ? w.substring(0, 30) + "…" : w;
    }

    @NonNull
    private static String join(@NonNull List<String> l, @NonNull String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? sep : "").append(s);
        return sb.toString();
    }
}
