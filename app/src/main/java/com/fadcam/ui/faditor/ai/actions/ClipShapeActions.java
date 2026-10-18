package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The clip-shape family (gap F2, grunt brief ACTION_FAMILY_BRIEF_20261015): what a person does to a
 * main-timeline clip's picture and playback other than trimming and speed. Turn, mirror, uncrop,
 * loop or freeze, and keep the voice's pitch when the speed changes.
 *
 * <p>Each control mirrors the editor code it is named after:</p>
 * <ul>
 *   <li>Rotate: {@code FaditorEditorActivity.rotateNext} (each tap adds 90; here the total turn is set).</li>
 *   <li>Flip: the {@code showFlipPicker} callback, which sets both mirror flags.</li>
 *   <li>Uncrop: the crop tool's reset, {@code crop_btn_reset}, and {@code exitCropMode}'s full-frame
 *       branch. Crop edges themselves are {@code TransformActions} (set_layer_transform).</li>
 *   <li>Loop: {@code FaditorEditorActivity.applyLoopMode} and {@code extendLoop}, including the
 *       structural-edit bracket so a longer clip moves the clips after it.</li>
 *   <li>Keep pitch: the speed sheet's "Maintain pitch" box, {@code onPitchCompensationChanged}.</li>
 * </ul>
 *
 * <p>Not offered: auto-crop of black bars (it needs a decoded frame, {@code detectBlackBarCropBounds},
 * which only the editor can run); aspect-ratio crop presets (the editor sizes them from the video);
 * ping-pong loop (the editor kicks its reverse bake, which a handler cannot do).</p>
 */
final class ClipShapeActions {
    private ClipShapeActions() { }

    private static final String FAMILY = "clip_shape";
    private static final String CLIP_HELP = "Which clip: its id, its name, or its place in the timeline"
            + " (1 = first). Main timeline clips only";

    /** The three loop chips the drawer offers. "LOOP" is the editor's Clip.LOOP_MODE_NORMAL. */
    static final List<String> LOOP = Arrays.asList("OFF", "LOOP", "STILL");

    /** The loop extension the drawer seeds when a loop is first switched on (applyLoopMode). */
    private static final long SEED_EXTENSION_MS = 1000L;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("set_clip_shape", "Turn, mirror or uncrop a clip")
                .help("Turn a clip's picture, mirror it, or show its whole picture again after a crop."
                        + " rotate is the clip's total turn in degrees, not an add: 0, 90, 180 or 270. The clip's"
                        + " info shows its current turn, so to turn it a further 90 send the new total."
                        + " flipH and flipV set the mirror. clearCrop true removes the crop. To crop to set"
                        + " edges, use set_layer_transform.")
                .wing("STUDIO", FAMILY).effect(ActionSpec.Effect.EDIT)
                .where("Studio › tap a clip › Rotate (each tap adds 90°), Flip, or Crop › Reset")
                .id("clip", "clip", true, CLIP_HELP)
                .number("rotate", false, "The clip's total turn: 0, 90, 180 or 270 degrees", 0.0, 270.0)
                .flag("flipH", false, "Mirror left to right")
                .flag("flipV", false, "Mirror top to bottom")
                .flag("clearCrop", false, "true shows the whole picture again, removing its crop")
                .build(), ClipShapeActions::setShape);

        r.add(ActionSpec.of("set_clip_loop", "Loop a clip or freeze its last frame")
                .help("Loop a clip, freeze it on its last frame, or stop looping. count is how many times the clip"
                        + " plays in all, counting its first run: 3 means it plays three times in a row, and 1 means"
                        + " no repeat. mode alone switches the loop on or off and keeps its length. A loop makes the"
                        + " clip longer, so the clips after it move along. Ping-pong (forwards then backwards) is not"
                        + " offered yet.")
                .wing("STUDIO", FAMILY).effect(ActionSpec.Effect.EDIT)
                .where("Studio › tap a clip › Loop › Off, Loop or Still, and the − / + steps")
                .id("clip", "clip", true, CLIP_HELP)
                .choice("mode", false, "OFF stops the loop. LOOP plays the clip again from its start."
                        + " STILL holds its last frame (a freeze) for the extra time", LOOP)
                // Capped although the drawer's + has no top: a model asked to "loop it" must not
                // be able to make an hour-long clip by accident.
                .number("count", false, "How many times the clip plays in all, including its first run. 1 = no repeat",
                        1.0, 100.0)
                .build(), ClipShapeActions::setLoop);

        r.add(ActionSpec.of("set_clip_keep_pitch", "Keep a clip's voice natural when its speed changes")
                .help("Keep a clip's voice at its normal pitch when it is sped up or slowed down (keepPitch true),"
                        + " or let the pitch follow the speed (false: chipmunk when sped up). It only matters when"
                        + " the clip's speed is not 1x.")
                .wing("STUDIO", FAMILY).effect(ActionSpec.Effect.EDIT)
                .where("Studio › tap a clip › Speed › Maintain pitch")
                .id("clip", "clip", true, CLIP_HELP)
                .flag("keepPitch", true, "true keeps the voice natural when the speed changes")
                .build(), ClipShapeActions::setKeepPitch);
    }

    // ── set_clip_shape ───────────────────────────────────────────────────────

    @NonNull
    private static ActionResult setShape(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        boolean turns = a.has("rotate");
        boolean mirrors = a.has("flipH") || a.has("flipV");
        boolean uncrops = Boolean.TRUE.equals(a.bool("clearCrop"));
        if (!turns && !mirrors && !uncrops) {
            throw new ActionError("Say what to change: a turn, a mirror, or the crop removed.");
        }
        // Validate everything before the first write, so a bad value changes nothing.
        long deg = 0;
        if (turns) {
            deg = Math.round(a.num("rotate"));
            if (deg % 90 != 0) {
                throw new ActionError("rotate must be 0, 90, 180 or 270 degrees, not " + a.num("rotate") + ".");
            }
        }
        Clip c = findClip(ctx, a.str("clip"));
        List<String> said = new ArrayList<>();

        // Rotate: rotateNext's write, clip.setRotationDegrees (it snaps to a quarter turn).
        if (turns) {
            c.setRotationDegrees((int) deg);
            said.add("turned " + c.getRotationDegrees() + "°");
        }
        // Flip: the flip picker's callback writes both flags (showFlipPicker).
        if (a.has("flipH")) {
            c.setFlipHorizontal(Boolean.TRUE.equals(a.bool("flipH")));
            said.add(c.isFlipHorizontal() ? "mirrored left to right" : "unmirrored left to right");
        }
        if (a.has("flipV")) {
            c.setFlipVertical(Boolean.TRUE.equals(a.bool("flipV")));
            said.add(c.isFlipVertical() ? "mirrored top to bottom" : "unmirrored top to bottom");
        }
        // Uncrop: the crop tool's reset writes full bounds (crop_btn_reset). setCustomCropBounds also
        // sets the preset to "custom", which is also where the editor's full-frame Done path ends up.
        if (uncrops) {
            c.setCustomCropBounds(0f, 0f, 1f, 1f);
            said.add("showing the whole picture");
        }

        List<String> touched = new ArrayList<>();
        touched.add(c.getId());
        return ActionResult.changed(label(ctx, c) + ": " + join(said, ", ") + ".", touched);
    }

    // ── set_clip_loop ────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult setLoop(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        if (!a.has("mode") && !a.has("count")) {
            throw new ActionError("Say how it should loop: a mode (OFF, LOOP or STILL) or a count of plays.");
        }
        Clip c = findClip(ctx, a.str("clip"));

        // Work the extension out before any write. A count of N adds N-1 repeats of the trimmed clip after
        // its first run (Clip.mapToSourceMs reads each repeat as getTrimmedDurationMs long).
        boolean counted = a.has("count");
        long plays = counted ? Math.max(1L, Math.round(a.num("count"))) : 0L;
        long extension = counted ? (plays - 1) * c.getTrimmedDurationMs() : 0L;

        // Target mode: the chip named, or the current one. A count above 1 on an OFF clip switches the
        // loop on, as extendLoop does.
        int target;
        if (a.has("mode")) {
            target = modeOf(a.choice("mode", LOOP));
        } else {
            target = c.getLoopMode();
            if (counted && target == Clip.LOOP_MODE_OFF && extension > 0) target = Clip.LOOP_MODE_NORMAL;
        }
        // A count of 1 leaves nothing to repeat, so the clip is simply not looping.
        boolean off = target == Clip.LOOP_MODE_OFF || (counted && extension == 0);

        // applyLoopMode / extendLoop: a loop edit is a length change, so bracket it and let the clips
        // after this one move with it.
        Timeline tl = ctx.project.getTimeline();
        Map<String, Long> before = tl.beginStructural();
        if (off) {
            c.setLoopMode(Clip.LOOP_MODE_OFF);
            c.setLoopBeforeMs(0);
            c.setLoopAfterMs(0);
        } else {
            c.setLoopMode(target);
            if (counted) {
                c.setLoopBeforeMs(0);
                c.setLoopAfterMs(extension);
            } else if (c.getLoopBeforeMs() == 0 && c.getLoopAfterMs() == 0) {
                // applyLoopMode seeds a small extension so the loop is live at once.
                c.setLoopAfterMs(SEED_EXTENSION_MS);
            }
        }
        tl.endStructural(before);

        String what;
        if (off) {
            what = "plays once and no longer loops";
        } else if (c.getLoopMode() == Clip.LOOP_MODE_STILL) {
            what = "freezes on its last frame for " + seconds(c.getLoopAfterMs()) + " seconds";
        } else if (counted) {
            what = "plays " + plays + " times in a row";
        } else {
            what = "loops again from its start once it ends";
        }
        List<String> touched = new ArrayList<>();
        touched.add(c.getId());
        return ActionResult.changed(label(ctx, c) + " " + what + ".", touched);
    }

    /** The chip's name as the drawer's mode: LOOP is Clip.LOOP_MODE_NORMAL (applyLoopMode). */
    private static int modeOf(@Nullable String chip) {
        if ("LOOP".equalsIgnoreCase(chip)) return Clip.LOOP_MODE_NORMAL;
        if ("STILL".equalsIgnoreCase(chip)) return Clip.LOOP_MODE_STILL;
        return Clip.LOOP_MODE_OFF;
    }

    // ── set_clip_keep_pitch ──────────────────────────────────────────────────

    @NonNull
    private static ActionResult setKeepPitch(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Clip c = findClip(ctx, a.str("clip"));
        boolean keep = Boolean.TRUE.equals(a.bool("keepPitch"));
        // onPitchCompensationChanged: the speed sheet's Maintain pitch box writes this flag only.
        c.setPitchCompensationEnabled(keep);
        List<String> touched = new ArrayList<>();
        touched.add(c.getId());
        return ActionResult.changed(label(ctx, c) + (keep ? " keeps its voice natural as its speed changes."
                : " lets its pitch follow its speed."), touched);
    }

    // ── clip resolver (private, as the spec asks) ────────────────────────────

    /** An id, then the name, then its place in the timeline (1 = first, "2" or "clip 2"). */
    @NonNull
    private static Clip findClip(@NonNull StudioCtx ctx, @Nullable String ref) throws ActionError {
        List<Clip> all = ctx.project.getTimeline().getClips();
        String r = ref == null ? "" : ref.trim();
        for (Clip c : all) {
            if (r.equals(c.getId())) return c;
        }
        String want = r.toLowerCase(Locale.ROOT);
        List<Clip> exact = new ArrayList<>(), part = new ArrayList<>();
        for (Clip c : all) {
            String n = c.getDisplayName() == null ? "" : c.getDisplayName().trim().toLowerCase(Locale.ROOT);
            if (n.isEmpty()) continue;
            if (n.equals(want)) exact.add(c);
            else if (!want.isEmpty() && n.contains(want)) part.add(c);
        }
        if (exact.size() == 1) return exact.get(0);
        String place = want.startsWith("clip ") ? want.substring(5).trim() : want;
        if (exact.isEmpty() && place.matches("\\d{1,4}")) {
            int n = Integer.parseInt(place);
            if (n >= 1 && n <= all.size()) return all.get(n - 1);
        }
        List<Clip> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.size() > 1) {
            throw new ActionError(hit.size() + " clips match \"" + r + "\": " + list(all, hit)
                    + ". Ask which one, or use its id.");
        }
        throw new ActionError("No clip called \"" + r + "\". The clips are: " + list(all, null) + ".");
    }

    /** "clip 2 \"Beach\" (id a1b2)" for each, so the model can ask the person which. */
    @NonNull
    private static String list(@NonNull List<Clip> all, @Nullable List<Clip> only) {
        List<Clip> show = only != null ? only : all;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Clip c : show) {
            if (n++ == 12) { sb.append(", … ").append(show.size() - 12).append(" more"); break; }
            sb.append(sb.length() > 0 ? ", " : "").append("clip ").append(placeOf(all, c));
            String name = c.getDisplayName() == null ? "" : c.getDisplayName().trim();
            if (!name.isEmpty()) sb.append(" \"").append(name).append('"');
            sb.append(" (id ").append(c.getId()).append(')');
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    // ── small helpers ────────────────────────────────────────────────────────

    /** The person's name for a clip: "Clip 2 \"Beach\"", or "Clip 2" when it was never renamed. */
    @NonNull
    private static String label(@NonNull StudioCtx ctx, @NonNull Clip c) {
        String name = c.getDisplayName() == null ? "" : c.getDisplayName().trim();
        String place = "Clip " + placeOf(ctx.project.getTimeline().getClips(), c);
        return name.isEmpty() ? place : place + " \"" + name + "\"";
    }

    /** The clip's 1-based place in the timeline, or 0 if it is not on it. */
    private static int placeOf(@NonNull List<Clip> all, @NonNull Clip c) {
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == c) return i + 1;
        }
        return 0;
    }

    /** Milliseconds as seconds with one decimal, for the summary. */
    @NonNull
    private static String seconds(long ms) {
        return String.format(Locale.US, "%.1f", ms / 1000.0);
    }

    @NonNull
    private static String join(@NonNull List<String> l, @NonNull String sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? sep : "").append(s);
        return sb.toString();
    }
}
