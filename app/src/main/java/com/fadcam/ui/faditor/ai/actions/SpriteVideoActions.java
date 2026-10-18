package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SequenceTiming;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The sprite-on-video family (gap F2): putting an animated sprite from a sprite sheet on the video,
 * and choosing what it plays. Authoring sheets (cells, presets, grids) is the Sprite Lab's job and
 * lives in the legacy AIToolExecutor tools; this family only places and plays what already exists.
 *
 * <p>Each call mirrors the editor's own model writes:</p>
 * <ul>
 *   <li>Placing: {@code FaditorEditorActivity#placeSpriteOnVideo} — own lane
 *       ({@code Timeline.spriteLayerIdFor}), a first-frame key (or the sequence's own preset key with
 *       its natural length), the item's time range.</li>
 *   <li>Dropping an animation: {@code FaditorEditorActivity.openSpritePalette} →
 *       {@code onPresetChipTapped}: a preset key at the item-local time, replacing a key at that exact
 *       time, nothing else removed.</li>
 *   <li>Loop: {@code onEndBehaviorCycled} for a single-frame or saved-animation sprite;
 *       {@code cycleSequenceLoopMode} for an image sequence (sheet preset type and end behaviour
 *       written together, copy-on-write when the sheet is shared).</li>
 *   <li>Speed: {@code spriteFpsProvider.setFps} (copy-on-write), the sheet's fps; range is the Sprite
 *       Lab's fps pill.</li>
 * </ul>
 *
 * <p>Position, size, mirroring and opacity of a sprite already on the video are
 * {@code set_layer_transform} (TransformActions); this family only sets them at placement, with the
 * same {@code setCenter} / {@code setSizeFraction} calls the drawer's unarmed rows make.</p>
 */
final class SpriteVideoActions {
    private SpriteVideoActions() { }

    private static final String FAMILY = "sprite_video";
    private static final String SHEET_HELP = "Which sprite sheet: its id, or its name";
    private static final String SPRITE_HELP = "Which sprite on the video: its id, or the sheet's name when only one sprite uses it";

    /** The palette's end-behaviour chip; for an image sequence the same three wraps as its loop chip. */
    static final List<String> LOOP = Arrays.asList("HOLD", "LOOP", "PINGPONG");

    /** The Sprite Lab's fps pill range. */
    private static final double FPS_MIN = 0.5, FPS_MAX = 60;
    /** The drawer's Scale row for stickers. */
    private static final double SIZE_MIN = 0.01, SIZE_MAX = 1;
    private static final double POS_MIN = 0, POS_MAX = 1;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("place_sprite", "Put a sprite on the video")
                .help("Put an animated sprite from one of the sprite sheets on the video. startMs is when it"
                        + " appears (default: the start of the video); endMs is when it goes (default: to the end,"
                        + " or an image sequence's own length). animation plays one of the sheet's saved animations"
                        + " by name; leave it out for the first frame, and an image sequence plays by itself."
                        + " x and y put its centre (0 = left or top edge, 1 = right or bottom edge, so x 0.85 and"
                        + " y 0.85 is bottom right). scale is its size as a fraction of the frame height, 0.01 to 1;"
                        + " the default is 0.25, and about 0.12 is small. loop sets how it runs on.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › Sprites › tap a sprite sheet › Place")
                .id("sheet", "sprite sheet", true, SHEET_HELP)
                .text("animation", false, "A saved animation on that sheet, by name (from Sprite Lab)")
                .time("startMs", false, "When it appears, in milliseconds on the timeline", 0.0, null)
                .time("endMs", false, "When it goes, in milliseconds on the timeline", 0.0, null)
                .number("x", false, "Centre across the frame: 0 = left edge, 1 = right edge", POS_MIN, POS_MAX)
                .number("y", false, "Centre down the frame: 0 = top edge, 1 = bottom edge", POS_MIN, POS_MAX)
                .number("scale", false, "Size as a fraction of the frame height: 0.01 tiny … 1 full height", SIZE_MIN, SIZE_MAX)
                .choice("loop", false, "How it runs after its last frame: HOLD keeps the animation's own way of"
                        + " running (an image sequence plays once and stops on its last frame); LOOP repeats;"
                        + " PINGPONG runs back and forth", LOOP)
                .build(), SpriteVideoActions::place);

        r.add(ActionSpec.of("set_sprite_playback", "Change what a sprite on the video plays")
                .help("Change which saved animation a sprite on the video plays, how it runs on, or its speed."
                        + " animation drops that animation into the sprite at atMs (default: the sprite's start);"
                        + " later steps the sprite already has still play after it, and the reply says so."
                        + " loop: HOLD, LOOP or PINGPONG, as in place_sprite. fps is the sprite sheet's speed in"
                        + " frames a second. To move, resize, mirror or fade the sprite use set_layer_transform.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap a sprite › Sprite palette (animation chips, loop chip)")
                .id("sprite", "sprite", true, SPRITE_HELP)
                .text("animation", false, "A saved animation on that sprite's sheet, by name")
                .time("atMs", false, "When the animation starts, in milliseconds on the timeline (needs animation)", 0.0, null)
                .choice("loop", false, "How it runs after its last frame: HOLD, LOOP or PINGPONG", LOOP)
                .number("fps", false, "Speed in frames a second for the sprite sheet", FPS_MIN, FPS_MAX)
                .build(), SpriteVideoActions::setPlayback);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult place(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        FaditorProject project = ctx.project;
        SpriteSheet sheet = sheetRef(ctx, a.str("sheet"));
        Long startArg = a.whole("startMs");
        long start = startArg == null ? 0L : startArg;
        Long endArg = a.whole("endMs");
        if (endArg != null && endArg <= start) {
            throw new ActionError("It has to end after it starts (" + StudioCtx.clock(start) + ").");
        }
        SpriteSheet.Preset anim = a.has("animation") ? presetRef(sheet, a.str("animation")) : null;
        Double x = a.num("x"), y = a.num("y"), size = a.num("scale");
        String loop = a.choice("loop", LOOP);

        // Everything checked; from here the project is changed. Mirrors placeSpriteOnVideo.
        SpriteOverlayItem item = SpriteOverlayItem.create(sheet.getId());
        item.setLayerId(Timeline.spriteLayerIdFor(item));
        long end = Long.MAX_VALUE;
        if (sheet.isSequence()) {
            // A sequence must animate: its own preset key, with the length the import path uses.
            sheet.ensureSequencePreset();
            if (anim != null) {
                item.getFrameTrack().put(FrameTrack.Key.ofPreset(0, anim.id));
            } else {
                item.getFrameTrack().put(FrameTrack.Key.ofPreset(0, SpriteSheet.SEQUENCE_PRESET_ID));
                SpriteSheet.Preset seq = sheet.sequencePreset();
                end = start + SequenceTiming.totalMsForFps(seq == null ? null : seq.weights,
                        sheet.cellCount(), seq != null && seq.fps > 0f ? seq.fps : sheet.getFps());
            }
        } else if (anim != null) {
            item.getFrameTrack().put(FrameTrack.Key.ofPreset(0, anim.id));
        } else {
            // Single frame: the first enabled cell, from the item's start.
            item.getFrameTrack().put(FrameTrack.Key.ofCell(0, firstEnabledCell(sheet)));
        }
        item.setTimeRange(start, endArg != null ? endArg : end);
        project.getTimeline().addSpriteOverlay(item);

        // The drawer's X/Y and Scale rows on an unarmed sprite: setCenter / setSizeFraction.
        if (x != null || y != null) {
            item.setCenter(x != null ? x.floatValue() : item.getCenterX(),
                    y != null ? y.floatValue() : item.getCenterY());
        }
        if (size != null) item.setSizeFraction(size.floatValue());
        applyLoop(ctx, item, loop);

        StringBuilder sum = new StringBuilder("Put \"").append(sheet.getName()).append("\" on the video from ")
                .append(StudioCtx.clock(item.getStartMs()));
        sum.append(item.getEndMs() == Long.MAX_VALUE ? " to the end" : " to " + StudioCtx.clock(item.getEndMs()));
        if (x != null || y != null) sum.append(", ").append(where(item.getCenterX(), item.getCenterY()));
        if (size != null) sum.append(", ").append(sizeWords(item.getSizeFraction()));
        if (anim != null) sum.append(", playing \"").append(anim.name).append('"');
        else if (sheet.isSequence()) sum.append(", playing through its frames");
        if (loop != null) sum.append(", ").append(loopWords(loop));
        sum.append('.');
        return ActionResult.changed(sum.toString(), ids(item))
                .withDetail("Sprite id " + item.getId() + ".");
    }

    @NonNull
    private static ActionResult setPlayback(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        SpriteOverlayItem item = spriteRef(ctx, a.str("sprite"));
        SpriteSheet sheet = ctx.project.spriteSheetById(item.getSheetId());
        if (sheet == null) {
            throw new ActionError("The sprite sheet this sprite uses is missing from the project. Relink it first.");
        }
        // Check everything before touching the project: an error must leave it unchanged.
        SpriteSheet.Preset anim = a.has("animation") ? presetRef(sheet, a.str("animation")) : null;
        if (a.has("atMs") && anim == null) {
            throw new ActionError("atMs says when an animation starts, so it goes with animation.");
        }
        long local = 0L;
        if (anim != null && a.has("atMs")) {
            long at = a.whole("atMs");
            if (at < item.getStartMs() || at > item.getEndMs()) {
                throw new ActionError("That time is outside this sprite, which is on the video from "
                        + StudioCtx.clock(item.getStartMs()) + " to "
                        + (item.getEndMs() == Long.MAX_VALUE ? "the end" : StudioCtx.clock(item.getEndMs())) + ".");
            }
            local = at - item.getStartMs();
        }
        String loop = a.choice("loop", LOOP);
        Double fps = a.num("fps");
        if (anim == null && loop == null && fps == null) {
            return ActionResult.unchanged("Nothing to change on \"" + sheet.getName() + "\".");
        }

        List<String> said = new ArrayList<>();
        String detail = "";
        if (anim != null) {
            // Mirrors onPresetChipTapped: one key at the item-local time, an exact-time match replaced.
            item.getFrameTrack().put(FrameTrack.Key.ofPreset(local, anim.id));
            FrameTrack.Key later = item.getFrameTrack().after(local);
            said.add("plays \"" + anim.name + "\"");
            if (later != null) {
                detail = "Later steps on this sprite still run after it, starting at "
                        + StudioCtx.clock(item.getStartMs() + later.timeMs)
                        + ". Remove them in the editor if this animation should run alone.";
            }
        }
        if (loop != null) {
            applyLoop(ctx, item, loop);
            said.add(loopWords(loop));
        }
        if (fps != null) {
            // Copy-on-write, as spriteFpsProvider.setFps: other placements of this sheet keep their speed.
            SpriteSheet own = sheetForExclusiveEdit(ctx, item);
            if (own != null) own.setFps(fps.floatValue());
            said.add("runs at " + Math.round(fps) + " frames a second");
        }
        String summary = "\"" + sheet.getName() + "\" on the video: " + join(said) + ".";
        return ActionResult.changed(summary, ids(item)).withDetail(
                ("Sprite id " + item.getId() + ". " + detail).trim());
    }

    // ── mirrored model writes ────────────────────────────────────────────────

    /** Mirrors cycleSequenceLoopMode (image sequences) and onEndBehaviorCycled (everything else). */
    private static void applyLoop(@NonNull StudioCtx ctx, @NonNull SpriteOverlayItem item, @Nullable String loop) {
        if (loop == null) return;
        SpriteSheet sheet = ctx.project.spriteSheetById(item.getSheetId());
        if (sheet != null && sheet.isSequence()) {
            SpriteSheet own = sheetForExclusiveEdit(ctx, item);
            SpriteSheet.Preset p = own == null ? null : own.sequencePreset();
            if (p == null) return;
            String type = "HOLD".equals(loop) ? "once" : "LOOP".equals(loop) ? "loop" : "pingpong";
            p.type = type;
            // The same pairing cycleSequenceLoopMode writes: a "once" wrap holds its last frame.
            item.setEndBehavior("once".equals(type) ? "hold" : type);
        } else {
            item.setEndBehavior("HOLD".equals(loop) ? "hold" : "LOOP".equals(loop) ? "loop" : "pingpong");
        }
    }

    /** Mirrors FaditorEditorActivity.sheetForExclusiveEdit: a shared sheet is copied for this one sprite. */
    @Nullable
    private static SpriteSheet sheetForExclusiveEdit(@NonNull StudioCtx ctx, @NonNull SpriteOverlayItem item) {
        FaditorProject project = ctx.project;
        SpriteSheet sheet = project.spriteSheetById(item.getSheetId());
        if (sheet == null) return null;
        int users = 0;
        for (SpriteOverlayItem s : project.getTimeline().getSpriteOverlays()) {
            if (sheet.getId().equals(s.getSheetId())) users++;
        }
        if (users <= 1) return sheet;
        SpriteSheet clone = sheet.copyAsNew(uniqueSheetName(project, sheet.getName()));
        project.getSpriteSheets().add(clone);
        item.setSheetId(clone.getId());
        return clone;
    }

    /** Mirrors FaditorEditorActivity.uniqueSheetCopyName: "frames" → "frames 2", "frames 3" … */
    @NonNull
    private static String uniqueSheetName(@NonNull FaditorProject project, @NonNull String base) {
        String stem = base.replaceAll("\\s+\\d+$", "");
        for (int n = 2; n < 999; n++) {
            String candidate = stem + " " + n;
            boolean taken = false;
            for (SpriteSheet s : project.getSpriteSheets()) {
                if (s.getName().equals(candidate)) { taken = true; break; }
            }
            if (!taken) return candidate;
        }
        return stem + " copy";
    }

    /** Mirrors FaditorEditorActivity.firstEnabledCell. */
    private static int firstEnabledCell(@NonNull SpriteSheet sheet) {
        for (int i = 0; i < sheet.cellCount(); i++) {
            SpriteSheet.Cell meta = sheet.cellAt(i);
            if (meta == null || meta.enabled) return i;
        }
        return 0;
    }

    // ── id and name resolution (errors list the valid choices) ───────────────

    @NonNull
    private static SpriteSheet sheetRef(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        List<SpriteSheet> all = ctx.project.getSpriteSheets();
        if (all.isEmpty()) {
            throw new ActionError("This project has no sprite sheets yet. Add one with \"+ Load sprite sheet\" in the Sprites tool.");
        }
        String r = ref.trim();
        for (SpriteSheet s : all) if (r.equals(s.getId())) return s;
        String want = norm(r);
        List<SpriteSheet> exact = new ArrayList<>(), part = new ArrayList<>();
        for (SpriteSheet s : all) {
            String n = norm(s.getName());
            if (n.equals(want)) exact.add(s);
            else if (!want.isEmpty() && n.contains(want)) part.add(s);
        }
        List<SpriteSheet> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("No sprite sheet is called \"" + r + "\". The sprite sheets are: " + sheetList(all) + ".");
        }
        throw new ActionError(hit.size() + " sprite sheets match \"" + r + "\": " + sheetList(hit)
                + ". Ask which one, or use its id.");
    }

    @NonNull
    private static SpriteSheet.Preset presetRef(@NonNull SpriteSheet sheet, @NonNull String ref) throws ActionError {
        List<SpriteSheet.Preset> all = sheet.getPresets();
        if (all.isEmpty()) {
            throw new ActionError("\"" + sheet.getName() + "\" has no saved animations yet. Make one in Sprite Lab,"
                    + " or leave the animation out to show its first frame.");
        }
        String r = ref.trim();
        for (SpriteSheet.Preset p : all) if (r.equals(p.id)) return p;
        String want = norm(r);
        List<SpriteSheet.Preset> exact = new ArrayList<>(), part = new ArrayList<>();
        for (SpriteSheet.Preset p : all) {
            String n = norm(p.name);
            if (n.equals(want)) exact.add(p);
            else if (!want.isEmpty() && n.contains(want)) part.add(p);
        }
        List<SpriteSheet.Preset> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("\"" + sheet.getName() + "\" has no animation called \"" + r
                    + "\". Its animations are: " + presetNames(all) + ".");
        }
        throw new ActionError(hit.size() + " animations match \"" + r + "\": " + presetNames(hit) + ". Use the exact name.");
    }

    @NonNull
    private static SpriteOverlayItem spriteRef(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        List<SpriteOverlayItem> all = ctx.project.getTimeline().getSpriteOverlays();
        if (all.isEmpty()) {
            throw new ActionError("There are no sprites on the video. Put one on with place_sprite first.");
        }
        String r = ref.trim();
        for (SpriteOverlayItem s : all) if (r.equals(s.getId())) return s;
        String want = norm(r);
        List<SpriteOverlayItem> hit = new ArrayList<>();
        for (SpriteOverlayItem s : all) {
            SpriteSheet sh = ctx.project.spriteSheetById(s.getSheetId());
            if (sh != null && norm(sh.getName()).equals(want)) hit.add(s);
        }
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("No sprite on the video is called \"" + r + "\". The sprites are: "
                    + spriteList(ctx, all) + ".");
        }
        throw new ActionError(hit.size() + " sprites are \"" + r + "\": " + spriteList(ctx, hit)
                + ". Ask which one, or use its id.");
    }

    // ── small helpers ────────────────────────────────────────────────────────

    @NonNull
    private static String sheetList(@NonNull List<SpriteSheet> l) {
        StringBuilder sb = new StringBuilder();
        for (SpriteSheet s : l) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(s.getName()).append("\" (id ").append(s.getId()).append(", ")
                    .append(s.cellCount()).append(s.isSequence() ? " frames)" : " cells)");
        }
        return sb.toString();
    }

    @NonNull
    private static String presetNames(@NonNull List<SpriteSheet.Preset> l) {
        List<String> names = new ArrayList<>();
        for (SpriteSheet.Preset p : l) names.add("\"" + p.name + "\"");
        return join(names);
    }

    @NonNull
    private static String spriteList(@NonNull StudioCtx ctx, @NonNull List<SpriteOverlayItem> l) {
        StringBuilder sb = new StringBuilder();
        for (SpriteOverlayItem s : l) {
            SpriteSheet sh = ctx.project.spriteSheetById(s.getSheetId());
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(sh == null ? "Sprite" : sh.getName()).append("\" (id ").append(s.getId())
                    .append(", ").append(StudioCtx.clock(s.getStartMs())).append('–')
                    .append(s.getEndMs() == Long.MAX_VALUE ? "end" : StudioCtx.clock(s.getEndMs())).append(')');
        }
        return sb.toString();
    }

    @NonNull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static List<String> ids(@NonNull SpriteOverlayItem s) {
        return Collections.singletonList(s.getId());
    }

    /** "bottom right", "in the middle", "top" … from the centre, so the summary reads as a person would say it. */
    @NonNull
    private static String where(float x, float y) {
        String v = y < 0.34f ? "top" : y > 0.66f ? "bottom" : "";
        String h = x < 0.34f ? "left" : x > 0.66f ? "right" : "";
        if (v.isEmpty() && h.isEmpty()) return "in the middle";
        if (v.isEmpty()) return h;
        if (h.isEmpty()) return v;
        return v + " " + h;
    }

    @NonNull
    private static String sizeWords(float s) {
        return s < 0.15f ? "small" : s < 0.4f ? "medium-sized" : "large";
    }

    @NonNull
    private static String loopWords(@NonNull String loop) {
        if ("LOOP".equals(loop)) return "it loops";
        if ("PINGPONG".equals(loop)) return "it runs back and forth";
        return "it runs as it is set up";
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }
}
