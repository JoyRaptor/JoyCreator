package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.ai.actions.ActionSpec.Type;
import com.fadcam.ui.faditor.ai.joybot.Fillers;
import com.fadcam.ui.faditor.ai.joybot.ProjectFactsReader;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.keyframe.KeyframeTrack;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.layers.Track;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;
import com.fadcam.ui.faditor.text.FontLibrary;
import com.fadcam.ui.faditor.transcript.NamedTranscript;
import com.fadcam.ui.faditor.transcript.TranscriptWord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The read side of the Studio (gap F5): Joybot's project summary is capped, so on a long project it
 * cannot see everything. These three look things up on request and change nothing.
 *
 * <ul>
 *   <li>{@code list_items} — every clip and layer, one line each, in time order, paged.</li>
 *   <li>{@code get_item} — every setting a person can change on one item (the drawer's values).</li>
 *   <li>{@code find_words} — where words are said in a transcript, with the time and the sentence.</li>
 * </ul>
 *
 * <p>{@link #onScreenAt} lists the same lines for one moment: the ids beside Joybot's look.</p>
 */
public final class InspectActions {
    private InspectActions() { }

    private static final String FAMILY = "inspect";
    private static final int PAGE_SIZE = 40;      // list_items: items per page
    private static final int HIT_LIMIT = 20;      // find_words: places shown
    private static final int LIST_LIMIT = 12;     // ambiguous names: choices shown

    static final List<String> KINDS = Arrays.asList("clip", "overlay_clip", "text", "image", "slide",
            "sprite", "visualizer", "adjustment", "audio", "all");

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("list_items", "List what is on the timeline")
                .help("Every clip, text, picture, slide, sprite, visualizer, adjustment layer and audio clip, one line"
                        + " each, in time order, with its id, name, start–end, row and flags (hidden, locked, muted,"
                        + " captions on, has transcript, has keyframes). Use it when the project is too long for the"
                        + " summary you were given, or to find an id. Long projects come in pages of 40: the footer"
                        + " says which page you have and how many there are.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › the timeline and its Layers")
                .choice("kind", false, "Only this kind of item (default all)", KINDS)
                .time("fromMs", false, "Only items that overlap this time or later (milliseconds on the timeline)",
                        0.0, null)
                .time("toMs", false, "Only items that start before this time (milliseconds on the timeline)",
                        0.0, null)
                .param("page", Type.INT, false, "Which page, starting at 1 (40 items a page)")
                .build(), InspectActions::listItems);

        r.add(ActionSpec.of("get_item", "Show every setting of one item")
                .help("Every setting a person can change on one clip, text, picture, slide, sprite, visualizer,"
                        + " adjustment layer or audio clip: where it is, its size, colour, font, outline, animation,"
                        + " volume, pan, fades, captions, transcript and which properties are keyframed. Call it before"
                        + " changing an item whose settings you have not seen, or when list_items is not enough.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › tap an item › its settings")
                .id("id", "any", true, "The item's id from list_items, or the words on a text, or its name")
                .build(), InspectActions::getItem);

        r.add(ActionSpec.of("find_words", "Find where words are said")
                .help("Where some words are spoken in the transcripts: the clip, the time on the timeline, and the"
                        + " sentence around them. Use it to find a moment by what someone says before cutting, moving or"
                        + " captioning it. Up to 20 places.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › Transcript › Search transcript")
                .text("text", true, "The words to find, as they are said")
                .build(), InspectActions::findWords);
    }

    // ── list_items ───────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult listItems(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        String kind = a.has("kind") ? a.choice("kind", KINDS) : "all";
        Double fromD = a.num("fromMs"), toD = a.num("toMs");
        long from = fromD == null ? Long.MIN_VALUE : Math.round(fromD);
        long to = toD == null ? Long.MAX_VALUE : Math.round(toD);
        if (fromD != null && toD != null && to <= from) throw new ActionError("toMs must be later than fromMs.");

        List<Item> hits = new ArrayList<>();
        for (Item it : collect(ctx.project)) {
            if (!"all".equals(kind) && !kind.equals(it.kind)) continue;
            if (it.end <= from || it.start >= to) continue;
            hits.add(it);
        }
        Collections.sort(hits, (x, y) -> Long.compare(x.start, y.start));

        int pages = Math.max(1, (hits.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        Long pg = a.whole("page");
        long page = pg == null ? 1 : pg;
        if (page < 1 || page > pages) {
            throw new ActionError(pages == 1 ? "There is only page 1 of the items."
                    : "The pages go from 1 to " + pages + ". Ask for one of those.");
        }
        Map<String, String> rows = rowNames(ctx.project);
        int first = (int) (page - 1) * PAGE_SIZE;
        int last = Math.min(hits.size(), first + PAGE_SIZE);
        StringBuilder sb = new StringBuilder();
        for (Item it : hits.subList(first, last)) sb.append(listLine(it, rows)).append('\n');
        if (hits.isEmpty()) sb.append("Nothing matches.\n");
        sb.append("page ").append(page).append(" of ").append(pages);

        String sum = hits.isEmpty() ? "Nothing on the timeline matches that."
                : "Found " + hits.size() + (hits.size() == 1 ? " item" : " items") + " on the timeline.";
        return ActionResult.unchanged(sum).withDetail(sb.toString());
    }

    @NonNull
    private static String listLine(@NonNull Item it, @NonNull Map<String, String> rows) {
        StringBuilder sb = new StringBuilder(it.kind).append(" | id ").append(it.id)
                .append(" | \"").append(it.name).append("\" | ").append(span(it));
        String row = rows.get(it.id);
        if (row != null) sb.append(" | row \"").append(row).append('"');
        for (String f : flags(it)) sb.append(" | ").append(f);
        return sb.toString();
    }

    /**
     * Everything that can show in the picture at {@code ms} (not audio), one list_items line each:
     * the ids a look's caption carries so the model can act on what it saw. Empty when nothing is
     * there.
     */
    @NonNull
    public static String onScreenAt(@NonNull FaditorProject p, long ms) {
        Map<String, String> rows = rowNames(p);
        StringBuilder sb = new StringBuilder();
        for (Item it : collect(p)) {
            if ("audio".equals(it.kind) || ms < it.start || ms >= it.end) continue;
            sb.append(listLine(it, rows)).append('\n');
        }
        return sb.toString();
    }

    // ── get_item ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult getItem(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Item it = resolve(collect(ctx.project), a.str("id"));
        StringBuilder d = new StringBuilder();
        line(d, "kind", it.kind);
        line(d, "id", it.id);
        if (!"text".equals(it.kind) && !"slide".equals(it.kind)) line(d, "name", it.name);
        line(d, "time", span(it));
        String row = rowNames(ctx.project).get(it.id);
        if (row != null) line(d, "row", row);
        List<String> f = flags(it);
        if (!f.isEmpty()) line(d, "flags", join(f));
        addSettings(d, it, ctx.project);
        return ActionResult.unchanged("Settings for " + kindWord(it.kind) + " \"" + it.name + "\".")
                .withDetail(d.toString().trim());
    }

    private static void addSettings(@NonNull StringBuilder d, @NonNull Item it, @NonNull FaditorProject p) {
        Object o = it.obj;
        if (o instanceof Clip) clipSettings(d, (Clip) o);
        else if (o instanceof AudioClip) audioSettings(d, (AudioClip) o);
        else if (o instanceof TextOverlayItem) textSettings(d, (TextOverlayItem) o);
        else if (o instanceof SpriteOverlayItem) spriteSettings(d, (SpriteOverlayItem) o, p);
        else if (o instanceof WaveformOverlayInstance) visualizerSettings(d, (WaveformOverlayInstance) o);
        else if (o instanceof AdjustmentLayer) adjustmentSettings(d, (AdjustmentLayer) o);
    }

    private static void clipSettings(@NonNull StringBuilder d, @NonNull Clip c) {
        line(d, "trim", "from " + clock(c.getInPointMs()) + " to " + clock(c.getOutPointMs()) + " of the source");
        line(d, "length on timeline", clock(c.getVisualDurationMs()));
        line(d, "speed", dec2(c.getSpeedMultiplier()) + "x");
        line(d, "volume", pct(c.getVolumeLevel()));
        line(d, "muted", yesNo(c.isAudioMuted()));
        line(d, "fades", "in " + clock(c.getMasterFadeInMs()) + ", out " + clock(c.getMasterFadeOutMs()));
        line(d, "captions", c.isCaptionsEnabled() ? "on, style " + c.getCaptionStyleId() : "off");
        line(d, "transcript", transcriptLine(c));
        line(d, "cuts", String.valueOf(c.getRemovedSpans().size()));
        line(d, "rotation", c.getRotationDegrees() + "°");
        line(d, "mirror", flipText(c.isFlipHorizontal(), c.isFlipVertical()));
        line(d, "crop", c.getCropPreset());
        line(d, "volume points", String.valueOf(c.getVolumeKeyframes().size()));
        line(d, "opacity keyframes", yesNo(c.hasOpacityKeyframes()));
        if (c.isOverlayClip()) {
            line(d, "blend mode", c.getOverlayBlendMode());
            line(d, "hidden", yesNo(c.isHiddenObject()));
        }
        line(d, "locked", yesNo(c.isLockedObject()));
    }

    private static void audioSettings(@NonNull StringBuilder d, @NonNull AudioClip ac) {
        line(d, "trim", "from " + clock(ac.getInPointMs()) + " to " + clock(ac.getOutPointMs()) + " of the source");
        line(d, "length", clock(ac.getTrimmedDurationMs()));
        line(d, "volume", pct(ac.getVolumeLevel()));
        line(d, "pan", panText(ac.getPan()));
        line(d, "muted", yesNo(ac.isMuted()));
        line(d, "locked", yesNo(ac.isLocked()));
        line(d, "fades", "in " + clock(ac.getFadeInMs()) + ", out " + clock(ac.getFadeOutMs()));
        line(d, "volume points", String.valueOf(ac.getVolumeKeyframes().size()));
        line(d, "voice clean-up", ac.isVoiceFxEnabled() ? "on" : "off");
        line(d, "captions", ac.isCaptionsEnabled() ? "on, style " + ac.getCaptionStyleId() : "off");
        line(d, "transcript", transcriptLine(ac));
        line(d, "cuts", String.valueOf(ac.getRemovedSpans().size()));
    }

    private static void textSettings(@NonNull StringBuilder d, @NonNull TextOverlayItem t) {
        if (!t.isImage()) {
            line(d, "words", quoteWords(t.getText()));
            String key = t.getFontFamily();
            line(d, "font", fontLabel(key) + (key == null ? "" : " (key " + key + ")"));
            line(d, "colour", hex(t.getColorInt()));
            line(d, "size", dec2(t.getSizeFraction()) + " of the frame height");
            line(d, "style", styleText(t));
            line(d, "alignment", t.getTextAlign());
            line(d, "letter case", t.getTextCase());
            line(d, "outline", t.getStrokeWidthPx() > 0f
                    ? hex(t.getStrokeColorInt()) + ", thickness " + dec1(t.getStrokeWidthPx()) : "off");
            line(d, "shadow", t.getShadowRadiusPx() > 0f
                    ? hex(t.getShadowColorInt()) + ", softness " + dec1(t.getShadowRadiusPx())
                    + ", angle " + dec1(t.getShadowAngleDeg()) + "°, distance "
                    + dec1(t.getShadowDistancePx()) + " px" : "off");
            line(d, "glow", t.getGlowRadiusPx() > 0f
                    ? hex(t.getGlowColorInt()) + ", size " + dec1(t.getGlowRadiusPx()) : "off");
            line(d, "plate", (t.getBackgroundColorInt() >>> 24) == 0 ? "none" : hex(t.getBackgroundColorInt()));
        }
        line(d, "opacity", pct(t.getOpacity()));
        line(d, "position", "centre " + pct(t.getCenterX()) + " across, " + pct(t.getCenterY()) + " down");
        line(d, "scale", dec2(t.getScaleX()) + " x " + dec2(t.getScaleY()));
        line(d, "rotation", dec1(t.getRotationDeg()) + "°");
        line(d, "mirror", flipText(t.isFlipH(), t.isFlipV()));
        line(d, "animation", animationText(t));
        line(d, "keyframed", keyframeNames(t.getKeyframes()));
        if (t.getHostClipId() != null) line(d, "attached to clip", t.getHostClipId());
    }

    private static void spriteSettings(@NonNull StringBuilder d, @NonNull SpriteOverlayItem s,
                                       @NonNull FaditorProject p) {
        SpriteSheet sh = sheetOf(p, s);
        line(d, "sheet", sh == null ? "missing" : sh.getName() + " (" + sh.cellCount()
                + (sh.isSequence() ? " frames)" : " cells)"));
        line(d, "animation", spriteAnimation(s, sh));
        line(d, "starts at frame", String.valueOf(s.getSequenceStartFrame()));
        line(d, "size", pct(s.getSizeFraction()) + " of the frame");
        line(d, "position", "centre " + pct(s.getCenterX()) + " across, " + pct(s.getCenterY()) + " down");
        line(d, "rotation", dec1(s.getRotationDeg()) + "°");
        line(d, "opacity", pct(s.getOpacity()));
        line(d, "mirror", flipText(s.isFlipH(), s.isFlipV()));
        line(d, "fades", "in " + clock(s.getFadeInMs()) + ", out " + clock(s.getFadeOutMs()));
        line(d, "after its end", s.getEndBehavior());
        line(d, "keyframed", yesNo(s.isArmed()));
    }

    private static void visualizerSettings(@NonNull StringBuilder d, @NonNull WaveformOverlayInstance w) {
        line(d, "style", w.getStyleId());
        line(d, "size", pct(w.getWidthFraction()) + " wide, " + pct(w.getHeightFraction()) + " tall");
        line(d, "position", "centre " + pct(w.getCenterX()) + " across, " + pct(w.getCenterY()) + " down");
        line(d, "rotation", dec1(w.getRotationDeg()) + "°");
        line(d, "colour", w.getColorOverride() == null ? "from its style" : w.getColorOverride());
        line(d, "attached to clip", w.getAttachedClipId() == null ? "no" : w.getAttachedClipId());
        line(d, "fades", "in " + clock(w.getFadeInMs()) + ", out " + clock(w.getFadeOutMs()));
        line(d, "keyframed", yesNo(w.isArmed()));
    }

    private static void adjustmentSettings(@NonNull StringBuilder d, @NonNull AdjustmentLayer al) {
        line(d, "blend mode", al.getBlendMode());
        line(d, "keyframed", keyframeNames(al.getTransform()));
    }

    // ── find_words ───────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult findWords(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        String asked = a.str("text") == null ? "" : a.str("text").trim();
        List<String> want = tokens(asked);
        if (want.isEmpty()) throw new ActionError("Give at least one word to look for.");

        List<Hit> hits = new ArrayList<>();
        int searched = 0;
        for (Item it : collect(ctx.project)) {
            if (!(it.obj instanceof Clip) && !(it.obj instanceof AudioClip)) continue;
            NamedTranscript nt = activeNamed(it.obj);
            if (nt == null || nt.transcript == null || nt.transcript.words.isEmpty()) continue;
            searched++;

            // Transcript times are in the source file. The clip shows its trimmed part at its speed,
            // starting where the item starts on the timeline (a looped lead-in shifts it).
            long inP, outP, lead = 0;
            float speed = 1f;
            List<long[]> cuts;
            if (it.obj instanceof Clip) {
                Clip c = (Clip) it.obj;
                inP = c.getInPointMs();
                outP = c.getOutPointMs();
                lead = c.getLoopBeforeMs();
                speed = c.getSpeedMultiplier() > 0f ? c.getSpeedMultiplier() : 1f;
                cuts = c.getRemovedSpans();
            } else {
                AudioClip ac = (AudioClip) it.obj;
                inP = ac.getInPointMs();
                outP = ac.getOutPointMs();
                cuts = ac.getRemovedSpans();
            }
            List<TranscriptWord> ws = new ArrayList<>();
            for (TranscriptWord w : nt.transcript.words) {
                if (w.startMs >= inP && w.startMs < outP) ws.add(w);
            }
            List<Integer> keep = new ArrayList<>();   // words that have letters or digits
            for (int i = 0; i < ws.size(); i++) if (!norm(ws.get(i).text).isEmpty()) keep.add(i);

            for (int k = 0; k + want.size() <= keep.size(); k++) {
                boolean same = true;
                for (int j = 0; j < want.size() && same; j++) {
                    same = want.get(j).equals(norm(ws.get(keep.get(k + j)).text));
                }
                if (!same) continue;
                int first = keep.get(k), last = keep.get(k + want.size() - 1);
                TranscriptWord w0 = ws.get(first);
                long at = it.start + lead + (long) ((w0.startMs - inP) / speed);
                boolean cut = w0.struck || Fillers.isCut(w0.startMs, w0.endMs, cuts);
                hits.add(new Hit(at, "\"" + it.name + "\" (id " + it.id + ") at " + clock(at) + ": \""
                        + sentence(ws, first, last) + "\"" + (cut ? " (cut)" : "")));
            }
        }
        if (hits.isEmpty()) {
            return ActionResult.unchanged(searched == 0 ? "No clip has a transcript to search yet."
                    : "\"" + asked + "\" is not said in any transcript.");
        }
        Collections.sort(hits, (x, y) -> Long.compare(x.at, y.at));
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < hits.size() && i < HIT_LIMIT; i++) d.append(hits.get(i).line).append('\n');
        if (hits.size() > HIT_LIMIT) {
            d.append("Showing the first ").append(HIT_LIMIT).append(" of ").append(hits.size()).append(" places.");
        }
        return ActionResult.unchanged("\"" + asked + "\" is said " + hits.size()
                + (hits.size() == 1 ? " time" : " times") + " in the project.").withDetail(d.toString().trim());
    }

    // ── the item list ────────────────────────────────────────────────────────

    /** One thing on the timeline, whatever its kind. {@code obj} is the model object it came from. */
    private static final class Item {
        @NonNull final String kind, id, name, words;
        final long start, end;
        @NonNull final Object obj;

        Item(@NonNull String kind, @NonNull String id, @NonNull String name, @NonNull String words,
             long start, long end, @NonNull Object obj) {
            this.kind = kind; this.id = id; this.name = name; this.words = words;
            this.start = start; this.end = end; this.obj = obj;
        }
    }

    private static final class Hit {
        final long at;
        @NonNull final String line;
        Hit(long at, @NonNull String line) { this.at = at; this.line = line; }
    }

    @NonNull
    private static List<Item> collect(@NonNull FaditorProject p) {
        Timeline tl = p.getTimeline();
        List<Item> out = new ArrayList<>();
        long at = 0;
        for (int i = 0; i < tl.getClipCount(); i++) {
            Clip c = tl.getClip(i);
            long len = c.getVisualDurationMs();
            out.add(new Item("clip", c.getId(), clipName(c), clipName(c), at, at + len, c));
            at += len;
        }
        for (Clip c : tl.getOverlayClips()) {
            long s = c.getOverlayStartMs();
            out.add(new Item("overlay_clip", c.getId(), clipName(c), clipName(c), s, s + c.getVisualDurationMs(), c));
        }
        for (TextOverlayItem t : tl.getTextOverlays()) {
            String kind = t.isGeneratedSlide() ? "slide" : t.isImage() ? "image" : "text";
            String words = oneLine(t.getText());
            String name;
            if ("image".equals(kind)) name = "Picture";
            else if (words.isEmpty()) name = "slide".equals(kind) ? "Slide" : "Text";
            else name = shorten(words, 40);
            out.add(new Item(kind, t.getId(), name, words.isEmpty() ? name : words,
                    t.getStartMs(), t.getEndMs(), t));
        }
        for (SpriteOverlayItem s : tl.getSpriteOverlays()) {
            SpriteSheet sh = sheetOf(p, s);
            String n = sh == null ? "Sprite" : sh.getName();
            out.add(new Item("sprite", s.getId(), n, n, s.getStartMs(), s.getEndMs(), s));
        }
        for (WaveformOverlayInstance w : tl.getWaveformOverlays()) {
            out.add(new Item("visualizer", w.getId(), "Visualizer", "Visualizer", w.getStartMs(), w.getEndMs(), w));
        }
        for (AdjustmentLayer al : tl.getAdjustmentLayers()) {
            String n = al.getName().trim();
            if (n.isEmpty()) n = "Adjustment";
            out.add(new Item("adjustment", al.getId(), n, n, al.getStartMs(), al.getEndMs(), al));
        }
        for (AudioClip ac : tl.getAudioClips()) {
            String n = ProjectFactsReader.nameOf(ac);
            out.add(new Item("audio", ac.getId(), n, n, ac.getOffsetMs(), ac.getEndOnTimelineMs(), ac));
        }
        return out;
    }

    /** Same name the Joybot context shows for a clip (ProjectFactsReader), with generated slides named. */
    @NonNull
    private static String clipName(@NonNull Clip c) {
        String dn = c.getDisplayName();
        if (c.isGeneratedSlide() && (dn == null || dn.trim().isEmpty())) return "Slide";
        return ProjectFactsReader.nameOf(c);
    }

    /** The row (Layers lane) each item sits on, by item id. Items on no row are absent. */
    @NonNull
    private static Map<String, String> rowNames(@NonNull FaditorProject p) {
        Map<String, String> out = new HashMap<>();
        for (Track t : p.getTimeline().getLayers()) {
            for (TimedItem ti : t.getItems()) out.put(ti.getId(), t.getName());
        }
        return out;
    }

    @NonNull
    private static List<String> flags(@NonNull Item it) {
        List<String> f = new ArrayList<>();
        Object o = it.obj;
        if (o instanceof Clip) {
            Clip c = (Clip) o;
            if (c.isOverlayClip() && c.isHiddenObject()) f.add("hidden");
            if (c.isLockedObject()) f.add("locked");
            if (c.isImageClip()) f.add("picture");
            if (c.isGeneratedSlide()) f.add("slide");
            if (c.isAudioMuted()) f.add("muted");
            if (c.isCaptionsEnabled()) f.add("captions on");
            if (c.hasTranscript()) f.add("has transcript");
            if (c.hasOpacityKeyframes() || c.hasVolumeKeyframes() || c.isSpineTransformArmed()) f.add("has keyframes");
        } else if (o instanceof AudioClip) {
            AudioClip ac = (AudioClip) o;
            if (ac.isLocked()) f.add("locked");
            if (ac.isMuted()) f.add("muted");
            if (ac.isCaptionsEnabled()) f.add("captions on");
            if (ac.hasTranscript()) f.add("has transcript");
            if (ac.hasVolumeKeyframes()) f.add("has volume points");
        } else if (o instanceof TextOverlayItem) {
            TextOverlayItem t = (TextOverlayItem) o;
            if (t.isHidden()) f.add("hidden");
            if (t.isLocked()) f.add("locked");
            if (t.hasTextAnim()) f.add("has animation");
            if (t.isArmed()) f.add("has keyframes");
        } else if (o instanceof SpriteOverlayItem) {
            SpriteOverlayItem s = (SpriteOverlayItem) o;
            if (s.isHidden()) f.add("hidden");
            if (s.isLocked()) f.add("locked");
            if (s.isArmed()) f.add("has keyframes");
        } else if (o instanceof WaveformOverlayInstance) {
            WaveformOverlayInstance w = (WaveformOverlayInstance) o;
            if (w.isHidden()) f.add("hidden");
            if (w.isLocked()) f.add("locked");
            if (w.isArmed()) f.add("has keyframes");
        } else if (o instanceof AdjustmentLayer) {
            AdjustmentLayer al = (AdjustmentLayer) o;
            if (al.isHidden()) f.add("hidden");
            if (al.isLocked()) f.add("locked");
            if (al.getTransform() != null && !al.getTransform().isEmpty()) f.add("has keyframes");
        }
        return f;
    }

    /** The item with this id, or the one whose words or name match (exact first, then contained). */
    @NonNull
    private static Item resolve(@NonNull List<Item> all, @Nullable String ref) throws ActionError {
        String r = ref == null ? "" : ref.trim();
        for (Item it : all) if (r.equals(it.id)) return it;
        String want = squash(r);
        List<Item> exact = new ArrayList<>(), part = new ArrayList<>();
        for (Item it : all) {
            String n = squash(it.words);
            if (n.equals(want)) exact.add(it);
            else if (!want.isEmpty() && n.contains(want)) part.add(it);
        }
        List<Item> hit = exact.isEmpty() ? part : exact;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("Nothing on the timeline is called \"" + r + "\". The items are: " + describe(all) + ".");
        }
        throw new ActionError(hit.size() + " items match \"" + r + "\": " + describe(hit)
                + ". Ask which one, or use its id.");
    }

    @NonNull
    private static String describe(@NonNull List<Item> items) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Item it : items) {
            if (n++ == LIST_LIMIT) {
                sb.append(", … ").append(items.size() - LIST_LIMIT).append(" more");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append('"').append(shorten(it.words, 30)).append("\" (id ").append(it.id)
                    .append(", ").append(span(it)).append(')');
        }
        return sb.toString();
    }

    // ── transcripts ──────────────────────────────────────────────────────────

    /** The active transcript, with the same fallback AIToolExecutor.findActiveTranscript uses. */
    @Nullable
    private static NamedTranscript activeNamed(@NonNull Object o) {
        if (o instanceof Clip) {
            Clip c = (Clip) o;
            NamedTranscript nt = c.getActiveNamedTranscript();
            if (nt == null && !c.getTranscripts().isEmpty()) nt = c.getTranscripts().get(0);
            return nt;
        }
        if (o instanceof AudioClip) {
            AudioClip ac = (AudioClip) o;
            NamedTranscript nt = ac.getActiveNamedTranscript();
            if (nt == null && !ac.getTranscripts().isEmpty()) nt = ac.getTranscripts().get(0);
            return nt;
        }
        return null;
    }

    @NonNull
    private static String transcriptLine(@NonNull Object o) {
        NamedTranscript nt = activeNamed(o);
        if (nt == null || nt.transcript == null) return "none";
        return nt.transcript.words.size() + " words";
    }

    /** The sentence around words first..last: back to the last full stop, on to the next (max 15 words each way). */
    @NonNull
    private static String sentence(@NonNull List<TranscriptWord> ws, int first, int last) {
        int s = first;
        while (s > 0 && first - s < 15 && !endsSentence(ws.get(s - 1).text)) s--;
        int e = last;
        while (e < ws.size() - 1 && e - last < 15 && !endsSentence(ws.get(e).text)) e++;
        StringBuilder sb = new StringBuilder();
        for (int i = s; i <= e; i++) {
            String t = ws.get(i).text == null ? "" : ws.get(i).text.trim();
            if (t.isEmpty()) continue;
            sb.append(sb.length() > 0 ? " " : "").append(t);
        }
        return shorten(sb.toString(), 160);
    }

    private static boolean endsSentence(@Nullable String t) {
        String x = t == null ? "" : t.trim();
        return x.endsWith(".") || x.endsWith("!") || x.endsWith("?");
    }

    @NonNull
    private static List<String> tokens(@NonNull String q) {
        List<String> out = new ArrayList<>();
        for (String part : q.trim().split("\\s+")) {
            String n = norm(part);
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }

    /** Lower case, letters and digits only: "Don't," matches "dont". */
    @NonNull
    private static String norm(@Nullable String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    // ── text, sprites and settings ───────────────────────────────────────────

    @Nullable
    private static SpriteSheet sheetOf(@NonNull FaditorProject p, @NonNull SpriteOverlayItem s) {
        for (SpriteSheet sh : p.getSpriteSheets()) if (sh.getId().equals(s.getSheetId())) return sh;
        return null;
    }

    @NonNull
    private static String spriteAnimation(@NonNull SpriteOverlayItem s, @Nullable SpriteSheet sh) {
        List<FrameTrack.Key> keys = s.getFrameTrack().keys();
        if (keys.isEmpty()) return "still";
        FrameTrack.Key k = keys.get(0);
        if (k.presetId != null) {
            if (sh != null) {
                for (SpriteSheet.Preset pr : sh.getPresets()) if (pr.id.equals(k.presetId)) return pr.name;
            }
            return "animation " + k.presetId;
        }
        String cell = sh == null ? null : sh.getCellNames().get(k.cellIndex);
        return cell != null ? "cell \"" + cell + "\"" : "cell " + k.cellIndex;
    }

    @NonNull
    private static String animationText(@NonNull TextOverlayItem t) {
        String preset = t.getTextAnimPreset();
        if (preset == null || preset.isEmpty() || "NONE".equalsIgnoreCase(preset)) return "none";
        return preset + ", by " + nz(t.getTextAnimGranularity()) + ", arrives " + pct(t.getTextAnimInPct())
                + ", leaves " + pct(t.getTextAnimOutPct());
    }

    @NonNull
    private static String styleText(@NonNull TextOverlayItem t) {
        List<String> on = new ArrayList<>();
        if (t.isBold()) on.add("bold");
        if (t.isItalic()) on.add("italic");
        if (t.isUnderline()) on.add("underline");
        return on.isEmpty() ? "plain" : join(on);
    }

    @NonNull
    private static String fontLabel(@Nullable String key) {
        if (key == null) return "default";
        for (String[] f : FontLibrary.TEXT_FONTS) if (f[0].equals(key)) return f[1];
        for (String[] f : FontLibrary.imported()) if (f[0].equals(key)) return f[1];
        return key;
    }

    /** Keyframed properties by the name a person sees; "none" when there are none. */
    @NonNull
    private static String keyframeNames(@Nullable KeyframeSet ks) {
        if (ks == null) return "none";
        List<String> names = new ArrayList<>();
        for (KeyframeTrack tr : ks.tracks()) if (!tr.isEmpty()) names.add(trackWord(tr.property));
        return names.isEmpty() ? "none" : join(names);
    }

    @NonNull
    private static String trackWord(@NonNull String property) {
        switch (property) {
            case KeyframeSet.X: return "position across";
            case KeyframeSet.Y: return "position down";
            case KeyframeSet.SCALE: return "size";
            case KeyframeSet.SCALE_X: return "width";
            case KeyframeSet.SCALE_Y: return "height";
            case KeyframeSet.OPACITY: return "opacity";
            case KeyframeSet.ROTATION: return "rotation";
            case TextOverlayItem.TRACK_STROKE_WIDTH: return "outline";
            case TextOverlayItem.TRACK_GLOW_RADIUS: return "glow";
            case TextOverlayItem.TRACK_SHADOW_RADIUS: return "shadow softness";
            case TextOverlayItem.TRACK_SHADOW_ANGLE: return "shadow angle";
            case TextOverlayItem.TRACK_SHADOW_DISTANCE: return "shadow distance";
            default: return property;
        }
    }

    // ── small formatting helpers ─────────────────────────────────────────────

    /** Milliseconds as m:ss.cc (centiseconds), e.g. 3725 becomes "0:03.72". Forever becomes "end". */
    @NonNull
    static String clock(long ms) {
        if (ms == Long.MAX_VALUE) return "end";
        if (ms < 0) ms = 0;
        long cs = ms / 10;
        long s = cs / 100;
        return String.format(Locale.US, "%d:%02d.%02d", s / 60, s % 60, cs % 100);
    }

    @NonNull
    private static String span(@NonNull Item it) {
        return clock(it.start) + "–" + clock(it.end);
    }

    @NonNull
    private static String kindWord(@NonNull String kind) {
        switch (kind) {
            case "clip": return "clip";
            case "overlay_clip": return "video layer";
            case "text": return "text layer";
            case "image": return "picture layer";
            case "slide": return "slide";
            case "sprite": return "sprite layer";
            case "visualizer": return "visualizer";
            case "adjustment": return "adjustment layer";
            default: return "audio clip";
        }
    }

    @NonNull
    private static String panText(float pan) {
        if (Math.abs(pan) < 0.005f) return "centre";
        return (pan < 0 ? "left " : "right ") + pct(Math.abs(pan));
    }

    @NonNull
    private static String quoteWords(@Nullable String text) {
        String w = text == null ? "" : text.replace("\n", "\\n");
        return "\"" + shorten(w, 200) + "\"";
    }

    @NonNull
    private static String flipText(boolean h, boolean v) {
        if (h && v) return "both";
        if (h) return "horizontal";
        if (v) return "vertical";
        return "none";
    }

    @NonNull
    private static String hex(int c) {
        return (c >>> 24) == 0xFF ? String.format(Locale.US, "#%06X", c & 0xFFFFFF)
                : String.format(Locale.US, "#%08X", c);
    }

    @NonNull
    private static String pct(float f) {
        return Math.round(f * 100f) + "%";
    }

    @NonNull
    private static String dec1(float f) {
        return String.format(Locale.US, "%.1f", f);
    }

    @NonNull
    private static String dec2(float f) {
        return String.format(Locale.US, "%.2f", f);
    }

    @NonNull
    private static String yesNo(boolean b) {
        return b ? "yes" : "no";
    }

    @NonNull
    private static String nz(@Nullable String s) {
        return s == null || s.isEmpty() ? "none" : s;
    }

    private static void line(@NonNull StringBuilder d, @NonNull String label, @Nullable String value) {
        d.append(label).append(": ").append(value == null ? "none" : value).append('\n');
    }

    @NonNull
    private static String join(@NonNull List<String> l) {
        StringBuilder sb = new StringBuilder();   // String.join is API 26; minSdk is 24
        for (String s : l) sb.append(sb.length() > 0 ? ", " : "").append(s);
        return sb.toString();
    }

    @NonNull
    private static String oneLine(@Nullable String s) {
        return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    @NonNull
    private static String squash(@Nullable String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String shorten(@NonNull String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
