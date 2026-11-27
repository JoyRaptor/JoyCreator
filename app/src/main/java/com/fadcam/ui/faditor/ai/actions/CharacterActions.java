package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.AudioClip;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.FrameTrack;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.fadcam.ui.faditor.sprite.SpriteSheet;

import cc.joycreator.avatar.studio.Acting;
import cc.joycreator.avatar.studio.ActingRun;
import cc.joycreator.avatar.studio.ActFromVideoSheet;
import cc.joycreator.avatar.studio.StudioCharacter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Avatar Studio's characters inside Studio (slice 2 and 2b), as Joybot's tools. Each mirrors the editor's own write
 * (the Add sheet's character drop, Act from a video's Read plan, Bake), on the project in memory; the editor adopts the
 * request as one undo step. Reading a video takes a while, so {@code character_act_from} sets what the character
 * follows and the open editor reads it straight after, folded into the same step.
 */
final class CharacterActions {
    private CharacterActions() { }

    private static final String FAMILY = "avatar";
    private static final List<String> LISTEN = Arrays.asList("lips", "head", "eyes", "brows", "shoulders", "leaning");
    private static final String CHAR_HELP = "Which character on the video: its name, or its id";

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("studio_add_character", "Put an Avatar Studio character on the video")
                .help("Drops a character from Avatar Studio (Topps, Pip, or one of the person's own by name) on its own"
                        + " lane at atMs, as long as its recorded performance, with its voice as a sound at the same"
                        + " start, as the Add sheet's Character row does.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › Add › Character (Avatar Studio)")
                .text("name", true, "The character's name (Topps, Pip, or one the person made)")
                .time("atMs", false, "Where it starts, in milliseconds on the timeline (default 0)", 0.0, null)
                .build(), CharacterActions::add);

        r.add(ActionSpec.of("character_act_from", "Make a character act from a video")
                .help("Points a character at one of the project's videos (or a sound, for lips only) and says what it"
                        + " follows from it: lips, head, eyes, brows, leaning (shoulders wait for the body tracker)."
                        + " Each thing follows one video. The open editor reads the video straight after; keep true"
                        + " leaves the video hidden and silent as a driver, read again when it moves.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap the character › Act from a video")
                .text("character", true, CHAR_HELP)
                .text("video", true, "\"main\" for the main video, or a picture-in-picture video's or sound's name")
                .param("listen", ActionSpec.Type.STRING_LIST, true, "What it follows: lips, head, eyes, brows, leaning")
                .number("nudgeMs", false, "Slide the acting later (positive) or earlier (negative), in milliseconds", -10000.0, 10000.0)
                .flag("keep", false, "true: keep the video as a hidden, silent driver")
                .build(), CharacterActions::actFrom);

        r.add(ActionSpec.of("character_bake_acting", "Bake a character's acting")
                .help("Keeps the keyframes a character read from its videos and lets the videos go (picture-in-picture"
                        + " videos are removed; the main video stays), as the Bake button does.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › tap the character › Act from a video › Bake")
                .text("character", true, CHAR_HELP)
                .build(), CharacterActions::bake);
    }

    @NonNull
    private static ActionResult add(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        String want = a.str("name").trim();
        List<StudioCharacter.Choice> shelf = StudioCharacter.shelfNow(ctx.app);
        StudioCharacter.Choice hit = null;
        for (StudioCharacter.Choice ch : shelf) if (ch.name.equalsIgnoreCase(want)) hit = ch;
        if (hit == null) {
            StringBuilder names = new StringBuilder();
            for (StudioCharacter.Choice ch : shelf) names.append(names.length() == 0 ? "" : ", ").append(ch.name);
            throw new ActionError("No character is called \"" + want + "\". The characters are: " + names + ".");
        }
        Long at = a.whole("atMs");
        long start = at == null ? 0L : at;
        StudioCharacter.Prepared p;
        try {
            p = StudioCharacter.prepareNow(ctx.app,
                    hit.ref, new File(new com.fadcam.ui.faditor.project.ProjectStorage(ctx.app).projectDir(ctx.projectId), "assets"));
        } catch (Exception e) {
            p = null;
        }
        if (p == null) throw new ActionError("That character could not be opened just now. Open it in Avatar Studio first.");
        Timeline tl = ctx.project.getTimeline();
        SpriteSheet sheet = ctx.project.spriteSheetById(p.sheetId());
        if (sheet == null) {
            sheet = new SpriteSheet(p.sheetId(), p.name, android.net.Uri.fromFile(p.picture).toString());
            sheet.setGrid(1, 1);
            ctx.project.getSpriteSheets().add(sheet);
        } else {
            sheet.setSheetUri(android.net.Uri.fromFile(p.picture).toString());
        }
        SpriteOverlayItem item = SpriteOverlayItem.create(sheet.getId());
        item.setLayerId(Timeline.spriteLayerIdFor(item));
        item.getFrameTrack().put(FrameTrack.Key.ofCell(0, 0));
        item.setTimeRange(start, start + p.lengthMs);
        item.setName(p.name);
        item.setSizeFraction(0.6f);
        item.setCharacterRef(p.ref);
        item.setCharacterUid(p.uid);
        item.setCharacterTake(p.takeJson);
        tl.addSpriteOverlay(item);
        List<String> touched = new ArrayList<>();
        touched.add(item.getId());
        String voice = "";
        if (p.voice != null) {
            AudioClip ac = new AudioClip(android.net.Uri.fromFile(p.voice), p.lengthMs);
            ac.setLabel(p.name + "'s voice");
            ac.setOffsetMs(start);
            tl.addAudioClip(ac);
            touched.add(ac.getId());
            voice = ", with its voice";
        }
        return ActionResult.changed("Put " + p.name + " on the video from " + StudioCtx.clock(start) + " for "
                + StudioCtx.clock(p.lengthMs) + voice + ".", touched).withDetail("Character id " + item.getId() + ".");
    }

    @NonNull
    private static ActionResult actFrom(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        SpriteOverlayItem item = character(ctx, a.str("character"));
        String video = a.str("video").trim();
        List<ActingRun.Choice> choices = ActingRun.choices(ctx.project.getTimeline(), "main");
        ActingRun.Choice src = null;
        for (ActingRun.Choice ch : choices) {
            if (ch.id.equals(video) || ch.name.equalsIgnoreCase(video)) src = ch;
        }
        if (src == null) {
            StringBuilder names = new StringBuilder();
            for (ActingRun.Choice ch : choices) names.append(names.length() == 0 ? "" : ", ").append(ch.name);
            throw new ActionError(choices.isEmpty() ? "This project has no video or sound to act from."
                    : "No video or sound is called \"" + video + "\". There are: " + names + ".");
        }
        List<Acting.Listen> ls = new ArrayList<>();
        for (String w : a.list("listen")) {
            Acting.Listen l = Acting.Listen.of(w.trim().toLowerCase(Locale.ROOT));
            if (l == null) throw new ActionError("\"" + w + "\" is not something a character follows: " + LISTEN + ".");
            if (l == Acting.Listen.SHOULDERS) throw new ActionError("Shoulders need the body tracker, which is not in this build yet.");
            if (src.sound && l != Acting.Listen.LIPS) throw new ActionError("A sound gives lips only.");
            ls.add(l);
        }
        if (ls.isEmpty()) throw new ActionError("Say what it follows: " + LISTEN + ".");
        Acting.Plan plan = ActFromVideoSheet.planOf(item);
        for (Acting.Listen l : ls) plan.tick(src.id, l, true);
        Double nudge = a.num("nudgeMs");
        if (nudge != null) plan.source(src.id).nudgeMs = Math.round(nudge);
        Boolean keep = a.bool("keep");
        if (keep != null) plan.keepAsDriver = keep;
        plan.source(src.id).readAt = "";   // not read yet: the open editor reads it
        plan.tidy();
        item.setCharacterActing(plan.toJson().toString());
        return ActionResult.changed(name(item) + " follows the " + ls.toString().toLowerCase(Locale.ROOT).replaceAll("[\\[\\]]", "")
                + " from " + src.name + ". The editor reads it now; it takes a moment.", Collections.singletonList(item.getId()));
    }

    @NonNull
    private static ActionResult bake(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        SpriteOverlayItem item = character(ctx, a.str("character"));
        Acting.Plan plan = ActFromVideoSheet.planOf(item);
        if (plan.isEmpty()) throw new ActionError(name(item) + " is not acting from a video: nothing to bake.");
        Timeline tl = ctx.project.getTimeline();
        List<String> touched = new ArrayList<>();
        touched.add(item.getId());
        int gone = 0;
        for (Acting.Source s : plan.sources) {
            for (Clip k : new ArrayList<>(tl.getOverlayClips())) {
                if (k.getId().equals(s.clipId)) {
                    tl.removeOverlayClip(k);
                    gone++;
                }
            }
        }
        item.setCharacterActing(null);
        return ActionResult.changed("Baked " + name(item) + "'s acting: the keyframes stay" + (gone > 0
                ? " and " + gone + " video" + (gone == 1 ? " was" : "s were") + " taken off the timeline." : "."), touched);
    }

    @NonNull
    private static SpriteOverlayItem character(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        List<SpriteOverlayItem> all = new ArrayList<>();
        for (SpriteOverlayItem s : ctx.project.getTimeline().getSpriteOverlays()) if (s.isCharacter()) all.add(s);
        if (all.isEmpty()) throw new ActionError("This project has no Avatar Studio character. studio_add_character puts one on.");
        String r = ref.trim();
        List<SpriteOverlayItem> hit = new ArrayList<>();
        for (SpriteOverlayItem s : all) if (r.equals(s.getId())) return s;
        for (SpriteOverlayItem s : all) if (s.getName() != null && s.getName().equalsIgnoreCase(r)) hit.add(s);
        if (hit.size() == 1) return hit.get(0);
        StringBuilder names = new StringBuilder();
        for (SpriteOverlayItem s : all) names.append(names.length() == 0 ? "" : ", ").append(name(s))
                .append(" (id ").append(s.getId(), 0, Math.min(6, s.getId().length())).append(", ").append(StudioCtx.clock(s.getStartMs())).append(')');
        throw new ActionError((hit.isEmpty() ? "No character is called \"" + r + "\"." : hit.size() + " characters are called \"" + r + "\".")
                + " The characters are: " + names + ". Use its id.");
    }

    private static String name(SpriteOverlayItem s) {
        return s.getName() == null || s.getName().isEmpty() ? "The character" : s.getName();
    }
}
