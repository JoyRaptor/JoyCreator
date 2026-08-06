package com.fadcam.ui.faditor.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.sprite.DurationParser;
import com.fadcam.ui.faditor.sprite.OpenEndResolver;
import com.fadcam.ui.faditor.sprite.SequenceTiming;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SPEC_IMAGE_SEQUENCE §7 — the model's read and write access to a sequence's timing.
 *
 * <p><b>Why this is tractable at all:</b> a sequence's entire timing is ONE integer array. §7
 * says so, and §9b kept it that way by refusing per-frame absolute pinning precisely so that
 * every batch pattern here can reason about weights without first asking "is this frame pinned?"
 * Everything below is a transformation of that array, and every one of them is a call into
 * {@link SequenceTiming} — the same functions the dope sheet's buttons call, so the AI cannot
 * produce a timing the UI could not.</p>
 *
 * <h3>§7c — never report success for something it did not do</h3>
 * The spec names the {@code set_clip_duck} incident as the reason that tool was removed
 * entirely. So no operation here composes its own success sentence from its ARGUMENTS. Each one
 * mutates, then READS THE MODEL BACK and reports what it actually finds — frame count, the
 * weight array, the resulting duration. If an edit silently did nothing, the report says the
 * unchanged numbers.
 *
 * <p>Pure model layer (JSON in, JSON out, no Android): the executor owns loading, saving and
 * {@code AIChatState.signalModified}, which is the established one-undo-step granularity for AI
 * edits — the editor reloads the whole project, exactly as apply_narrative_proposal does.</p>
 */
public final class SequenceAiOps {

    private SequenceAiOps() {}

    /** Resolve the placed object the model named, or null. */
    @Nullable
    public static SpriteOverlayItem itemById(@NonNull FaditorProject proj, @NonNull String id) {
        if (proj.getTimeline() == null) return null;
        for (SpriteOverlayItem s : proj.getTimeline().getSpriteOverlays()) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }

    /** Every sequence object in the project, as an "id — name" list for the model to pick from. */
    @NonNull
    public static String listSequences(@NonNull FaditorProject proj) {
        StringBuilder sb = new StringBuilder();
        if (proj.getTimeline() != null) {
            for (SpriteOverlayItem s : proj.getTimeline().getSpriteOverlays()) {
                SpriteSheet sheet = proj.spriteSheetById(s.getSheetId());
                if (sheet == null || !sheet.isSequence()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append("objectId=").append(s.getId())
                        .append("  name=").append(sheet.getName())
                        .append("  frames=").append(sheet.cellCount());
            }
        }
        return sb.length() == 0
                ? "This project has no image sequences."
                : "Image sequences in this project:\n" + sb;
    }

    /**
     * §7a — everything the model needs to reason: total frames, the weight array, total
     * duration, loop mode, resize mode.
     */
    @NonNull
    public static String describe(@NonNull FaditorProject proj, @NonNull SpriteOverlayItem item)
            throws Exception {
        SpriteSheet sheet = proj.spriteSheetById(item.getSheetId());
        if (sheet == null) return "Error: this object's sheet is missing.";
        if (!sheet.isSequence()) {
            return "Error: object " + item.getId() + " is a sprite SHEET, not an image sequence. "
                    + "Use describe_sprite_sheet for that.";
        }
        SpriteSheet.Preset p = sheet.sequencePreset();
        int n = sheet.cellCount();
        List<Integer> w = SequenceTiming.fit(p == null ? null : p.weights, n);
        float fps = SequenceTiming.clampFps(p != null && p.fps > 0f ? p.fps : sheet.getFps());

        JSONObject j = new JSONObject();
        j.put("objectId", item.getId());
        j.put("name", sheet.getName());
        j.put("frameCount", n);
        j.put("weights", new JSONArray(w));
        j.put("totalWeight", SequenceTiming.totalWeight(w, n));
        j.put("fps", round2(fps));
        j.put("runLengthMs", SequenceTiming.totalMsForFps(w, n, fps));
        j.put("objectStartMs", item.getStartMs());
        j.put("objectEndMs", item.getEndMs() == Long.MAX_VALUE ? -1 : item.getEndMs());
        j.put("loopMode", p == null ? "once" : p.type);
        j.put("resizeMode", sheet.getResizeMode().name());
        j.put("continuesUntilBlocked", item.isContinuesUntilBlocked());
        j.put("clippedByNeighbour", item.isClippedByNeighbour());
        // The units the spec insists the tools speak in: frame indices and weights.
        j.put("note", "Weights are per-frame holds; frame_duration = total x weight / sum(weights). "
                + "fps = sum(weights) / totalSeconds. Frame indices are 0-based.");
        return j.toString();
    }

    /**
     * §7b — one mutating entry point covering setWeights / applyStride / applyRamp /
     * setTotalDuration / setFrameRate / setLoop / setResizeMode / reorder.
     *
     * <p>Kept as ONE tool with an {@code op} discriminator rather than eight: they share a
     * target, a validation path and — critically — the read-back that §7c requires. Eight copies
     * of "mutate then honestly report" is eight chances for one of them to drift into asserting
     * its arguments instead.</p>
     *
     * @return a human sentence for the model, always derived from the model AFTER the edit
     */
    @NonNull
    public static String edit(@NonNull FaditorProject proj, @NonNull SpriteOverlayItem item,
                              @NonNull JSONObject args) throws Exception {
        SpriteSheet sheet = proj.spriteSheetById(item.getSheetId());
        if (sheet == null) return "Error: this object's sheet is missing.";
        if (!sheet.isSequence()) {
            return "Error: object " + item.getId() + " is not an image sequence.";
        }
        SpriteSheet.Preset p = sheet.ensureSequencePreset();
        final int n = sheet.cellCount();
        if (n <= 0) return "Error: that sequence has no frames.";

        String op = args.optString("op", "").trim();
        List<Integer> before = SequenceTiming.fit(p.weights, n);
        List<Integer> next = null;

        switch (op) {
            case "setWeights": {
                JSONArray arr = args.optJSONArray("weights");
                if (arr == null) return "Error: setWeights needs a \"weights\" array.";
                // Length mismatch is REFUSED rather than padded: a model that emitted 11 numbers
                // for 12 frames has misunderstood the sequence, and silently defaulting the
                // twelfth would hide that behind a plausible-looking success.
                if (arr.length() != n) {
                    return "Error: this sequence has " + n + " frames but you sent "
                            + arr.length() + " weights. Send exactly " + n + ".";
                }
                List<Integer> w = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    w.add(SequenceTiming.clampWeight(arr.optInt(i, 1)));
                }
                next = w;
                break;
            }
            case "applyStride":
                next = SequenceTiming.applyStride(before, n,
                        args.optInt("start", 0), args.optInt("every", 2),
                        args.optInt("weight", 2));
                break;
            case "applyRamp": {
                com.fadcam.ui.faditor.keyframe.Easing ease =
                        com.fadcam.ui.faditor.keyframe.Easing.fromName(
                                args.optString("ease", "LINEAR"));
                next = SequenceTiming.applyRamp(before, n,
                        args.optInt("fromIdx", 0), args.optInt("toIdx", n - 1),
                        args.optInt("w0", 1), args.optInt("w1", 4), ease);
                break;
            }
            case "setTotalDuration": {
                long ms = args.optLong("ms", -1);
                if (ms <= 0) {
                    // Accept the §3c spellings too — the model may well emit "2m30s".
                    ms = DurationParser.parseMs(args.optString("duration", ""),
                            sheet.getFps());
                }
                if (ms <= 0) return "Error: setTotalDuration needs a positive \"ms\".";
                sheet.setFps(SequenceTiming.fpsForTotalMs(before, n, ms));
                item.setTimeRange(item.getStartMs(), item.getStartMs() + ms);
                break;
            }
            case "setFrameRate": {
                double fps = args.optDouble("fps", -1);
                if (fps <= 0) return "Error: setFrameRate needs a positive \"fps\".";
                sheet.setFps(SequenceTiming.clampFps((float) fps));
                item.setTimeRange(item.getStartMs(), item.getStartMs()
                        + SequenceTiming.totalMsForFps(before, n, sheet.getFps()));
                break;
            }
            case "setLoop": {
                String mode = args.optString("mode", "").toUpperCase(Locale.US);
                switch (mode) {
                    case "NONE": p.type = "once"; item.setEndBehavior("hold"); break;
                    case "LOOP": p.type = "loop"; item.setEndBehavior("loop"); break;
                    case "PING_PONG":
                    case "PINGPONG": p.type = "pingpong"; item.setEndBehavior("pingpong"); break;
                    default: return "Error: setLoop mode must be NONE, LOOP or PING_PONG.";
                }
                break;
            }
            case "setResizeMode": {
                String mode = args.optString("mode", "").toUpperCase(Locale.US);
                if (!"RELATIVE".equals(mode) && !"ABSOLUTE".equals(mode)) {
                    return "Error: setResizeMode must be RELATIVE or ABSOLUTE.";
                }
                sheet.setResizeMode(SequenceTiming.ResizeMode.fromName(mode));
                break;
            }
            case "reorder": {
                String rop = args.optString("op2", args.optString("order", "")).toUpperCase(Locale.US);
                List<String> uris = new ArrayList<>(sheet.getFrameUris());
                List<Integer> ws = new ArrayList<>(before);
                if ("REVERSE".equals(rop)) {
                    SequenceTiming.reverse(uris, ws);
                } else if ("SHUFFLE".equals(rop)) {
                    SequenceTiming.shuffle(uris, ws, args.optLong("seed", 12345L));
                } else {
                    return "Error: reorder needs \"order\":\"REVERSE\" or \"SHUFFLE\".";
                }
                sheet.setSequenceFrames(uris);
                sheet.ensureSequencePreset();
                next = ws;
                break;
            }
            default:
                return "Error: unknown op '" + op + "'. Use one of setWeights, applyStride, "
                        + "applyRamp, setTotalDuration, setFrameRate, setLoop, setResizeMode, "
                        + "reorder.";
        }

        if (next != null) {
            SpriteSheet.Preset live = sheet.ensureSequencePreset();
            live.weights.clear();
            live.weights.addAll(SequenceTiming.fit(next, sheet.cellCount()));
            // Adding holds lengthens the object, exactly as the dope sheet's drag does — the two
            // surfaces must not disagree about what a weight change means.
            long span = SequenceTiming.totalMsForFps(live.weights, sheet.cellCount(),
                    live.fps > 0f ? live.fps : sheet.getFps());
            item.setTimeRange(item.getStartMs(), item.getStartMs() + span);
        }

        // §6: any of the above can change what blocks what.
        resolveOpenEndsPerLane(proj);

        return report(proj, item, sheet, before);
    }

    /**
     * §6 open-end resolution, PER LANE — the same grouping the editor uses.
     *
     * <p>Resolving the flat sprite list as if it were one lane makes an object on a DIFFERENT
     * lane count as a blocker: the AI would truncate a sequence, honestly report the truncated
     * length, and honestly add "cut short by the next object in its lane" — about an object that
     * is not in its lane. The editor would then re-resolve it back on the next sync, so the
     * number the model reported and the number on screen would disagree. Two implementations of
     * one rule is the thing §0 exists to prevent, so there is one: this walks the same lanes.</p>
     */
    public static void resolveOpenEndsPerLane(@NonNull FaditorProject proj) {
        if (proj.getTimeline() == null) return;
        java.util.Map<String, List<SpriteOverlayItem>> byLane = new java.util.LinkedHashMap<>();
        for (SpriteOverlayItem s : proj.getTimeline().getSpriteOverlays()) {
            String lane = s.getLayerId() == null ? "sprite" : s.getLayerId();
            byLane.computeIfAbsent(lane, k -> new ArrayList<>()).add(s);
        }
        long end = Math.max(1, proj.getTimeline().getTotalDurationMs());
        for (List<SpriteOverlayItem> lane : byLane.values()) {
            OpenEndResolver.resolve(lane, proj::spriteSheetById, end);
        }
    }

    /**
     * §7c's honesty rule made mechanical: read the model back and say what is THERE, including
     * "nothing changed" when nothing did.
     */
    @NonNull
    private static String report(@NonNull FaditorProject proj, @NonNull SpriteOverlayItem item,
                                 @NonNull SpriteSheet sheet, @NonNull List<Integer> before) {
        SpriteSheet.Preset p = sheet.sequencePreset();
        int n = sheet.cellCount();
        List<Integer> after = SequenceTiming.fit(p == null ? null : p.weights, n);
        float fps = SequenceTiming.clampFps(p != null && p.fps > 0f ? p.fps : sheet.getFps());
        long span = Math.max(0, item.getEndMs() == Long.MAX_VALUE
                ? SequenceTiming.totalMsForFps(after, n, fps)
                : item.getEndMs() - item.getStartMs());
        StringBuilder sb = new StringBuilder();
        sb.append(after.equals(before) ? "Weights unchanged. " : "Weights updated. ");
        sb.append(String.format(Locale.US,
                "%d frames · holds %s · %.2f fps · object is %s (%s).",
                n, summarise(after), fps, DurationParser.formatMs(span),
                p == null ? "once" : p.type));
        if (item.isClippedByNeighbour()) {
            sb.append(" NOTE: this object is being cut short by the next object in its lane.");
        }
        return sb.toString();
    }

    /** Compact weight summary — the full array for short runs, a histogram for long ones. */
    @NonNull
    private static String summarise(@NonNull List<Integer> w) {
        if (w.size() <= 24) return w.toString();
        java.util.TreeMap<Integer, Integer> counts = new java.util.TreeMap<>();
        for (Integer v : w) counts.merge(v == null ? 1 : v, 1, Integer::sum);
        StringBuilder sb = new StringBuilder("{");
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (sb.length() > 1) sb.append(", ");
            sb.append("x").append(e.getKey()).append(":").append(e.getValue()).append(" frames");
        }
        return sb.append("}").toString();
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
