package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The transform family (gap F2, grunt brief ACTION_FAMILY_BRIEF_20261015): one action that moves,
 * sizes, turns, mirrors and fades a layer, and crops a clip — the things the editor's Transform
 * tab and the crop tool do by hand.
 *
 * <p>Each layer kind is written the way its own editor row writes it:</p>
 * <ul>
 *   <li>Text and pictures ({@code TextOverlayItem}): position via {@code setCenter}, size via
 *       {@code setSizeFraction} (the Scale row when linked), rotation, opacity — as
 *       {@code FaditorEditorActivity.overlayMenuProp}. Mirror only for pictures, unarmed and
 *       unbent, as {@code CornerPinTransformHost.flip}.</li>
 *   <li>Stickers ({@code SpriteOverlayItem}): as {@code FaditorEditorActivity.spriteMenuProp};
 *       mirror as the sprite palette's onFlipH/onFlipV.</li>
 *   <li>Floating videos ({@code Clip} with {@code isOverlayClip()}): as
 *       {@code FaditorEditorActivity.pipMenuProp} — the values live in the clip's overlay
 *       transform track, and a static write collapses each track to one key at 0.</li>
 *   <li>Crop: the main clip's crop tool, {@code setCustomCropBounds} directly.</li>
 * </ul>
 *
 * <p>Ranges are the editor's own slider ranges, per kind. Linked followers and mirrors on text or
 * animated pictures are refused with a plain reason; the editor does those.</p>
 */
final class TransformActions {
    private TransformActions() { }

    private static final String FAMILY = "transform";
    private static final String LAYER_HELP = "Which layer: its id, or the words on a text. Pictures,"
            + " stickers and floating videos work by id";
    private static final String CLIP_HELP = "Which clip to crop: its id";

    /** Edges of the frame a centre may sit at (the PiP / text travel limits, KeyframeSet.POS_MIN/MAX). */
    private static final float POS_MIN = -1f, POS_MAX = 2f;
    private static final float TEXT_SCALE_MIN = 0.02f, TEXT_SCALE_MAX = 10f;
    private static final float SPRITE_SCALE_MIN = 0.01f, SPRITE_SCALE_MAX = 1f;
    private static final float PIP_SCALE_MIN = 0.05f, PIP_SCALE_MAX = 4f;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("set_layer_transform", "Move, size, turn, mirror or fade a layer")
                .help("Move a text, picture, sticker or floating video, or crop a clip. x and y put its centre"
                        + " (0 = left or top edge, 0.5 = middle, 1 = right or bottom edge). scale is its size."
                        + " rotation is in degrees. flipH and flipV mirror a picture or sticker. opacity 0 is"
                        + " invisible, 1 solid. Send only what should change. To crop, give clip and the crop"
                        + " edges (0 = the frame's edge, 1 = the far edge).")
                .wing("STUDIO", FAMILY)
                .where("Studio › tap a text, picture or sticker › Transform (or a floating video › Transform)"
                        + "; a clip › Crop")
                .id("layer", "layer", false, LAYER_HELP)
                .number("x", false, "Where its centre sits across the frame: 0 = left edge, 1 = right edge", (double) POS_MIN, (double) POS_MAX)
                .number("y", false, "Where its centre sits down the frame: 0 = top edge, 1 = bottom edge", (double) POS_MIN, (double) POS_MAX)
                .number("scale", false, "Its size. Text and pictures 0.02–10, stickers 0.01–1, floating videos 0.05–4", (double) SPRITE_SCALE_MIN, (double) TEXT_SCALE_MAX)
                .number("rotation", false, "Turn in degrees", -180.0, 180.0)
                .flag("flipH", false, "Mirror left to right (pictures and stickers only)")
                .flag("flipV", false, "Mirror top to bottom (pictures and stickers only)")
                .number("opacity", false, "0 invisible … 1 solid", 0.0, 1.0)
                .id("clip", "clip", false, CLIP_HELP)
                .number("cropLeft", false, "Crop edge: left. 0 = the frame's left edge", 0.0, 1.0)
                .number("cropTop", false, "Crop edge: top. 0 = the frame's top edge", 0.0, 1.0)
                .number("cropRight", false, "Crop edge: right. 1 = the frame's right edge", 0.0, 1.0)
                .number("cropBottom", false, "Crop edge: bottom. 1 = the frame's bottom edge", 0.0, 1.0)
                .build(), TransformActions::setTransform);
    }

    // ── handler ──────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult setTransform(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        boolean moves = a.has("x") || a.has("y") || a.has("scale") || a.has("rotation")
                || a.has("flipH") || a.has("flipV") || a.has("opacity");
        boolean crops = a.has("cropLeft") || a.has("cropTop") || a.has("cropRight") || a.has("cropBottom");
        if (!moves && !crops) {
            throw new ActionError("Nothing to change. Give a position, size, turn, mirror, opacity or crop edge.");
        }
        if (moves && !a.has("layer")) {
            throw new ActionError("Which layer should move? Give its id or the words on it.");
        }
        if (crops && !a.has("clip")) {
            throw new ActionError("Which clip should be cropped? Give its id.");
        }
        if (a.has("layer") && !moves) {
            throw new ActionError("Nothing to change on that layer. Give a position, size, turn, mirror or opacity.");
        }
        if (a.has("clip") && !crops) {
            throw new ActionError("Nothing to change on that clip. Give a crop edge.");
        }

        List<String> touched = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> parts = new ArrayList<>();

        if (a.has("layer")) {
            Found f = findLayer(ctx, a.str("layer"));
            List<String> said = new ArrayList<>();
            String name;
            if (f.text != null) {
                name = wordsOf(f.text);
                moveText(f.text, a, said, notes);
            } else if (f.sprite != null) {
                name = "the sticker";
                moveSprite(f.sprite, a, said, notes);
            } else {
                name = "the floating video";
                movePip(f.pip, a, said, notes);
            }
            touched.add(f.id);
            parts.add(name + ": " + joinWith(said, ", "));
        }

        if (crops) {
            Clip c = findClip(ctx, a.str("clip"));
            cropClip(c, a);
            touched.add(c.getId());
            parts.add("cropped the clip");
        }

        String sum = "Changed " + joinWith(parts, "; ") + ".";
        ActionResult res = ActionResult.changed(sum, touched);
        return notes.isEmpty() ? res : res.withDetail(joinWith(notes, " "));
    }

    // ── text and pictures ────────────────────────────────────────────────────

    private static void moveText(@NonNull TextOverlayItem t, @NonNull Args a,
                                 @NonNull List<String> said, @NonNull List<String> notes) throws ActionError {
        boolean geometry = a.has("x") || a.has("y") || a.has("scale") || a.has("rotation");
        boolean mirror = a.has("flipH") || a.has("flipV");
        boolean armed = t.isArmed();
        if (geometry && t.isLinked()) {
            throw new ActionError(wordsOf(t) + " follows another layer, so that layer sets where it is."
                    + " Move it in the editor.");
        }
        if (mirror && !t.isImage()) {
            throw new ActionError("Only pictures can be mirrored. Text can't be flipped.");
        }
        if (mirror && (armed || t.hasMesh())) {
            throw new ActionError("This picture is animated or bent, so it can't be mirrored from here."
                    + " Mirror it in the editor.");
        }
        if ((geometry || a.has("opacity")) && armed) {
            notes.add("This layer is animated, so the change is its starting pose. Keys at the playhead were"
                    + " not added; key it in the editor to animate the change.");
        }

        // Centre: the editor's X/Y rows call setCenter, which keeps the centre inside its travel limit.
        if (a.has("x") || a.has("y")) {
            float nx = a.has("x") ? a.num("x").floatValue() : t.getCenterX();
            float ny = a.has("y") ? a.num("y").floatValue() : t.getCenterY();
            t.setCenter(nx, ny);
            if (Math.abs(t.getCenterX() - nx) > 1e-4f || Math.abs(t.getCenterY() - ny) > 1e-4f) {
                addOnce(notes, "A position was kept inside the edge the editor allows.");
            }
            said.add("moved");
        }
        // Scale: the editor's Scale row (linked) writes sizeFraction, clamped 0.02..10.
        if (a.has("scale")) {
            float s = clamp(a.num("scale").floatValue(), TEXT_SCALE_MIN, TEXT_SCALE_MAX, notes);
            t.setSizeFraction(s);
            said.add("size " + pct(s));
        }
        if (a.has("rotation")) {
            float d = a.num("rotation").floatValue();
            t.setRotationDeg(d);
            said.add("turned " + Math.round(d) + "°");
        }
        if (a.has("opacity")) {
            float o = clamp(a.num("opacity").floatValue(), 0f, 1f, notes);
            t.setOpacity(o);
            said.add("opacity " + pct(o));
        }
        // Mirror: CornerPinTransformHost.flip, unarmed and unbent, sets only the flag.
        if (a.has("flipH")) {
            t.setFlipH(Boolean.TRUE.equals(a.bool("flipH")));
            said.add(t.isFlipH() ? "mirrored left to right" : "unmirrored left to right");
        }
        if (a.has("flipV")) {
            t.setFlipV(Boolean.TRUE.equals(a.bool("flipV")));
            said.add(t.isFlipV() ? "mirrored top to bottom" : "unmirrored top to bottom");
        }
    }

    // ── stickers ─────────────────────────────────────────────────────────────

    private static void moveSprite(@NonNull SpriteOverlayItem s, @NonNull Args a,
                                   @NonNull List<String> said, @NonNull List<String> notes) throws ActionError {
        boolean geometry = a.has("x") || a.has("y") || a.has("scale") || a.has("rotation");
        boolean armed = s.isArmed();
        if (geometry && s.isLinked()) {
            throw new ActionError("This sticker follows another layer, so that layer sets where it is."
                    + " Move it in the editor.");
        }
        if ((geometry || a.has("opacity")) && armed) {
            notes.add("This layer is animated, so the change is its starting pose. Keys at the playhead were"
                    + " not added; key it in the editor to animate the change.");
        }
        if (a.has("x") || a.has("y")) {
            float nx = a.has("x") ? clamp(a.num("x").floatValue(), 0f, 1f, notes) : s.getCenterX();
            float ny = a.has("y") ? clamp(a.num("y").floatValue(), 0f, 1f, notes) : s.getCenterY();
            s.setCenter(nx, ny);
            said.add("moved");
        }
        if (a.has("scale")) {
            float v = clamp(a.num("scale").floatValue(), SPRITE_SCALE_MIN, SPRITE_SCALE_MAX, notes);
            s.setSizeFraction(v);
            said.add("size " + pct(v));
        }
        if (a.has("rotation")) {
            float d = a.num("rotation").floatValue();
            s.setRotationDeg(d);
            said.add("turned " + Math.round(d) + "°");
        }
        if (a.has("opacity")) {
            float o = clamp(a.num("opacity").floatValue(), 0f, 1f, notes);
            s.setOpacity(o);
            said.add("opacity " + pct(o));
        }
        // The sprite palette's onFlipH / onFlipV: toggle a static flag.
        if (a.has("flipH")) {
            s.setFlipH(Boolean.TRUE.equals(a.bool("flipH")));
            said.add(s.isFlipH() ? "mirrored left to right" : "unmirrored left to right");
        }
        if (a.has("flipV")) {
            s.setFlipV(Boolean.TRUE.equals(a.bool("flipV")));
            said.add(s.isFlipV() ? "mirrored top to bottom" : "unmirrored top to bottom");
        }
    }

    // ── floating videos (PiP overlay clips) ──────────────────────────────────

    private static void movePip(@NonNull Clip c, @NonNull Args a,
                                @NonNull List<String> said, @NonNull List<String> notes) throws ActionError {
        if (a.has("flipH") || a.has("flipV")) {
            throw new ActionError("Floating videos can't be mirrored in this app.");
        }
        boolean armed = pipArmed(c);
        if (armed) {
            notes.add("This floating video is animated, so the change is its starting pose. Keys at the playhead"
                    + " were not added; key it in the editor to animate the change.");
        }
        KeyframeSet kf = c.getOverlayTransform();
        if (kf == null) {
            kf = new KeyframeSet();
            c.setOverlayTransform(kf);
        }
        if (a.has("x")) pipKey(kf, KeyframeSet.X, clamp(a.num("x").floatValue(), POS_MIN, POS_MAX, notes), armed);
        if (a.has("y")) pipKey(kf, KeyframeSet.Y, clamp(a.num("y").floatValue(), POS_MIN, POS_MAX, notes), armed);
        if (a.has("x") || a.has("y")) said.add("moved");
        if (a.has("scale")) {
            float v = clamp(a.num("scale").floatValue(), PIP_SCALE_MIN, PIP_SCALE_MAX, notes);
            pipKey(kf, KeyframeSet.SCALE, v, armed);
            said.add("size " + pct(v));
        }
        if (a.has("rotation")) {
            float d = a.num("rotation").floatValue();
            pipKey(kf, KeyframeSet.ROTATION, d, armed);
            said.add("turned " + Math.round(d) + "°");
        }
        if (a.has("opacity")) {
            float o = clamp(a.num("opacity").floatValue(), 0f, 1f, notes);
            pipKey(kf, KeyframeSet.OPACITY, o, armed);
            said.add("opacity " + pct(o));
        }
    }

    /**
     * The PiP editor's pipMenuProp write. Unarmed: the track collapses to one key at 0 (the static pose).
     * Armed: the editor keys the playhead, which this action has no time for, so the starting key is set.
     */
    private static void pipKey(@NonNull KeyframeSet kf, @NonNull String key, float v, boolean armed) {
        KeyframeTrack tr = kf.getOrCreate(key);
        if (!armed) tr.keyframes.clear();
        tr.put(0L, v, Easing.LINEAR);
    }

    /** Same rule as the PiP editor's pipArmed: any track with two keys, or a key past t=0. */
    private static boolean pipArmed(@NonNull Clip c) {
        KeyframeSet kf = c.getOverlayTransform();
        if (kf == null) return false;
        String[] keys = {KeyframeSet.X, KeyframeSet.Y, KeyframeSet.SCALE, KeyframeSet.ROTATION, KeyframeSet.OPACITY};
        for (String k : keys) {
            KeyframeTrack tr = kf.get(k);
            if (tr == null) continue;
            if (tr.keyframes.size() >= 2) return true;
            for (com.fadcam.ui.faditor.keyframe.Keyframe key : tr.keyframes) {
                if (key.timeMs > 0) return true;
            }
        }
        return false;
    }

    // ── crop (the crop tool's custom bounds) ─────────────────────────────────

    private static void cropClip(@NonNull Clip c, @NonNull Args a) throws ActionError {
        float l = a.has("cropLeft") ? a.num("cropLeft").floatValue() : c.getCropLeft();
        float t = a.has("cropTop") ? a.num("cropTop").floatValue() : c.getCropTop();
        float r = a.has("cropRight") ? a.num("cropRight").floatValue() : c.getCropRight();
        float b = a.has("cropBottom") ? a.num("cropBottom").floatValue() : c.getCropBottom();
        if (l >= r) throw new ActionError("The crop's left edge must be left of its right edge.");
        if (t >= b) throw new ActionError("The crop's top edge must be above its bottom edge.");
        c.setCustomCropBounds(l, t, r, b);
    }

    // ── id resolution ────────────────────────────────────────────────────────

    /** The one layer a ref names, in whichever list it lives in. */
    private static final class Found {
        @Nullable TextOverlayItem text;
        @Nullable SpriteOverlayItem sprite;
        @Nullable Clip pip;
        @NonNull String id = "";
    }

    @NonNull
    private static Found findLayer(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        Timeline tl = ctx.project.getTimeline();
        String r = ref.trim();
        for (TextOverlayItem t : tl.getTextOverlays()) {
            if (r.equals(t.getId())) return foundText(t);
        }
        for (SpriteOverlayItem s : tl.getSpriteOverlays()) {
            if (r.equals(s.getId())) return foundSprite(s);
        }
        for (Clip c : tl.getOverlayClips()) {
            if (r.equals(c.getId())) return foundPip(c);
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
            throw new ActionError(hit.size() + " text layers say \"" + r + "\": " + listLayers(ctx, hit)
                    + ". Ask which one, or use its id.");
        }
        throw new ActionError("No layer called \"" + r + "\". The layers are: " + listLayers(ctx, null) + ".");
    }

    @NonNull
    private static Found foundText(@NonNull TextOverlayItem t) {
        Found f = new Found();
        f.text = t;
        f.id = t.getId();
        return f;
    }

    @NonNull
    private static Found foundSprite(@NonNull SpriteOverlayItem s) {
        Found f = new Found();
        f.sprite = s;
        f.id = s.getId();
        return f;
    }

    @NonNull
    private static Found foundPip(@NonNull Clip c) {
        Found f = new Found();
        f.pip = c;
        f.id = c.getId();
        return f;
    }

    @NonNull
    private static Clip findClip(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        String r = ref.trim();
        List<Clip> all = ctx.project.getTimeline().getClips();
        for (Clip c : all) {
            if (r.equals(c.getId())) return c;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Clip c : all) {
            if (n++ == 12) { sb.append(", …"); break; }
            sb.append(sb.length() > 0 ? ", " : "").append("id ").append(c.getId());
        }
        throw new ActionError("No clip with id \"" + r + "\". The clips are: " + sb + ".");
    }

    /** Every layer the person could mean, with its id, for the error that asks which one. */
    @NonNull
    private static String listLayers(@NonNull StudioCtx ctx, @Nullable List<TextOverlayItem> only) {
        Timeline tl = ctx.project.getTimeline();
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

    /** Clamp into the editor's range, noting it so the summary can say so. */
    private static float clamp(float v, float lo, float hi, @NonNull List<String> notes) {
        float c = Math.max(lo, Math.min(hi, v));
        if (c != v) addOnce(notes, "A value was kept inside the range the editor allows.");
        return c;
    }

    private static void addOnce(@NonNull List<String> notes, @NonNull String note) {
        if (!notes.contains(note)) notes.add(note);
    }

    @NonNull
    private static String wordsOf(@NonNull TextOverlayItem t) {
        return t.isImage() ? "the picture" : "\"" + shorten(t.getText()) + "\"";
    }

    @NonNull
    private static String pct(float v) {
        return Math.round(v * 100f) + "%";
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
    private static String joinWith(@NonNull List<String> l, @NonNull String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? sep : "").append(s);
        return sb.toString();
    }
}
