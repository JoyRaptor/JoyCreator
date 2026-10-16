package com.fadcam.ui.faditor.ai.actions;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.GeneratedSource;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.slides.SlideCache;
import com.fadcam.ui.faditor.slides.SlideContract;
import com.fadcam.ui.faditor.slides.SlideFiles;
import com.fadcam.ui.faditor.slides.SlideHtmlReader;
import com.fadcam.ui.faditor.slides.SlideRenderer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The slide family (gap M1): "I didn't like this font in this slide". An AI slide IS its HTML file
 * ({@code GeneratedSource.htmlUri}, under the project's slides/ folder). The model reads that code,
 * changes only what the person asked for, and writes the whole file back: no nested model call.
 *
 * <p>set_slide mirrors the editor's code path for in-line slides (FaditorEditorActivity.showSlideCodeSheet
 * and applySlideCodeEdit). On-top slides are created by ADD_GENERATED_SLIDE / applyAddGeneratedOverlay, which
 * writes the HTML the same way and leaves the render cache empty; set_slide does the same for them.</p>
 */
final class SlideActions {
    private SlideActions() { }

    private static final String FAMILY = "slides";
    private static final String SLIDE_HELP = "Which slide: its id, or words that appear on it";
    private static final String WHERE = "Studio › double-tap an in-line slide on the timeline (its code);"
            + " an on-top slide's code is only reachable through Joybot";

    /** The longest code get_slide shows. A longer slide is cut, and set_slide must never get a cut file. */
    static final int MAX_CODE_CHARS = 12000;

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("get_slide", "Read a slide's code")
                .help("Read an AI slide's HTML code: its fonts, colours, words and animation are all in it."
                        + " Also says whether it sits in line with the clips or on top of the video, and how long it"
                        + " lasts. To change a slide's font, colour or words: call this first, edit only the part"
                        + " asked for, then call set_slide with the whole HTML.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where(WHERE)
                .id("slide", "slide", true, SLIDE_HELP)
                .build(), SlideActions::getSlide);

        r.add(ActionSpec.of("set_slide", "Rewrite a slide's code")
                .help("Replace a slide's whole HTML with the new HTML. To change a slide's font, colour or words:"
                        + " get_slide first, edit the HTML minimally (change only what was asked), then send the"
                        + " entire file here, never a part. The file must keep the slide rules (one"
                        + " Faditor.register(...) call, GSAP timelines, nothing from the network); if not, the app says"
                        + " what to fix. Works for slides in line with the clips and slides on top of the video. The"
                        + " slide keeps its place and length on the timeline. One Undo puts the old code back.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where(WHERE)
                .id("slide", "slide", true, SLIDE_HELP)
                .text("html", true, "The slide's whole HTML file, as it should be saved (no code fences)")
                .build(), SlideActions::setSlide);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult getSlide(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Found f = resolve(ctx, a.str("slide"));
        String html = readHtml(f.gs);
        if (html == null) throw new ActionError("The code file for that slide is missing, so it can't be read.");
        boolean cut = html.length() > MAX_CODE_CHARS;
        StringBuilder d = new StringBuilder("Slide id: ").append(f.id).append(".\n");
        if (cut) {
            d.append("Only the first ").append(MAX_CODE_CHARS).append(" of ").append(html.length())
                    .append(" characters are shown. set_slide replaces the whole file, so do NOT send it a partial one.\n");
        }
        d.append("Code:\n").append(cut ? html.substring(0, MAX_CODE_CHARS) : html);
        String w = shorten(visibleWords(html));
        String name = w.isEmpty() ? "Slide" : "Slide \"" + w + "\"";
        return ActionResult.unchanged(name + ", " + placeWords(f) + ".").withDetail(d.toString());
    }

    @NonNull
    private static ActionResult setSlide(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Found f = resolve(ctx, a.str("slide"));
        String html = SlideContract.stripFences(a.str("html"));
        if (html.isEmpty()) throw new ActionError("The new code is empty. Send the slide's whole HTML.");
        // Same gates as applySlideCodeEdit: the validator first, then the contract version.
        String reason = SlideContract.validate(html);
        if (reason != null) {
            throw new ActionError("That code breaks a slide rule: " + reason + ". Fix it and send the whole HTML again.");
        }
        int version = SlideContract.extractContractVersion(html);
        if (version > SlideContract.CONTRACT_VERSION) {
            throw new ActionError("That slide needs a newer version of this app (slide contract " + version + ").");
        }
        String old = readHtml(f.gs);
        if (old != null && html.trim().equals(old.trim())) {
            return ActionResult.unchanged("That is already the slide's code, so nothing changed.");
        }

        GeneratedSource gs = f.gs;
        // Same duration rule as the code editor: the authored length, clamped to 0.8–20 s.
        long authored = SlideContract.extractAuthoredDurationMs(html,
                gs.authoredDurationMs > 0 ? gs.authoredDurationMs : 3000);
        authored = Math.max(800, Math.min(20000, authored));
        int[] dims = SlideFiles.dimensionsFor(ctx.project);
        String hash = SlideFiles.contentHash(html, dims[0], dims[1], authored);

        // The NEW code goes to a NEW file name. project.json (what Undo restores) still points at the old
        // HTML, and the old file stays on disk, so one Undo brings the old code back. The editor overwrites
        // in place with its own undo closure; this host can only undo through project.json.
        File projectDir = new ProjectStorage(ctx.app).projectDir(ctx.projectId);
        File file;
        try {
            file = SlideFiles.writeHtml(projectDir, f.id + "-" + shortHash(hash), html);
        } catch (Exception e) {
            throw new ActionError("Couldn't save the slide's new code: " + e.getMessage());
        }

        // Stamp the recipe the way applySlideCodeEdit does, keeping the clip's own trim and freeze zones.
        gs.htmlUri = Uri.fromFile(file).toString();
        gs.contentHash = hash;
        gs.authoredDurationMs = authored;
        gs.width = dims[0];
        gs.height = dims[1];
        gs.sourceModel = "user-edit";
        gs.renderStateHash = null;   // stale on purpose: the next render makes a fresh MP4 for the new code
        if (f.clip != null) {
            Uri mp4 = Uri.fromFile(new SlideCache(projectDir)
                    .mp4ForState(hash, SlideRenderer.renderStateHash(f.clip, gs)));
            gs.renderCacheUri = mp4.toString();
            f.clip.repointGeneratedSlideSource(mp4);
        } else {
            // On-top slide: creation leaves renderSequenceDir empty. The export pre-pass and the background
            // render see the null stamp as stale and rebuild the PNG frames for the new code.
            gs.renderSequenceDir = null;
        }

        String w = shorten(visibleWords(html));
        String name = w.isEmpty() ? "The slide" : "The slide \"" + w + "\"";
        return ActionResult.changed(name + " has the new code. Its place and length on the timeline stay the same.",
                        Arrays.asList(f.id))
                .withDetail("It is drawn again from the new code the next time it is needed.");
    }

    // ── finding slides ───────────────────────────────────────────────────────

    /** One AI slide: an in-line clip, or a layer on top of the video. Exactly one of clip/overlay is set. */
    private static final class Found {
        final String id;
        @Nullable final Clip clip;
        @Nullable final TextOverlayItem overlay;
        @NonNull final GeneratedSource gs;

        Found(@NonNull String id, @Nullable Clip clip, @Nullable TextOverlayItem overlay,
              @NonNull GeneratedSource gs) {
            this.id = id;
            this.clip = clip;
            this.overlay = overlay;
            this.gs = gs;
        }
    }

    @NonNull
    private static List<Found> slides(@NonNull StudioCtx ctx) {
        List<Found> out = new ArrayList<>();
        for (Clip c : ctx.project.getTimeline().getClips()) {
            GeneratedSource gs = c.getGeneratedSource();
            if (gs != null) out.add(new Found(c.getId(), c, null, gs));
        }
        for (TextOverlayItem o : ctx.project.getTimeline().getTextOverlays()) {
            GeneratedSource gs = o.getGeneratedSource();
            if (gs != null) out.add(new Found(o.getId(), null, o, gs));
        }
        return out;
    }

    /** The slide matching {@code ref}: an id, then the words on it (exact, then contained). */
    @NonNull
    private static Found resolve(@NonNull StudioCtx ctx, @NonNull String ref) throws ActionError {
        List<Found> all = slides(ctx);
        if (all.isEmpty()) throw new ActionError("This project has no AI slides.");
        String r = ref.trim();
        for (Found f : all) if (r.equals(f.id)) return f;
        String want = norm(r);
        List<Found> exact = new ArrayList<>(), part = new ArrayList<>();
        for (Found f : all) {
            if (want.isEmpty()) break;
            String w = norm(words(f));
            if (w.equals(want)) exact.add(f);
            else if (w.contains(want)) part.add(f);
        }
        List<Found> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit.get(0);
        if (hit.isEmpty()) {
            throw new ActionError("No slide shows \"" + r + "\". The slides are: " + list(all) + ".");
        }
        throw new ActionError(hit.size() + " slides match \"" + r + "\": " + list(hit) + ". Ask which one, or use its id.");
    }

    @NonNull
    private static String list(@NonNull List<Found> fs) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Found f : fs) {
            if (n++ == 12) { sb.append(", … ").append(fs.size() - 12).append(" more"); break; }
            if (sb.length() > 0) sb.append(", ");
            String w = shorten(words(f));
            sb.append('"').append(w.isEmpty() ? "no words" : w).append("\" (id ").append(f.id).append(", ")
                    .append(placeWords(f)).append(')');
        }
        return sb.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** "on top of the video, 0:01–0:04" or "in line with the clips, 3.0 s long". */
    @NonNull
    private static String placeWords(@NonNull Found f) {
        long d = durationMs(f);
        if (f.overlay != null) {
            long start = f.overlay.getStartMs();
            return "on top of the video, " + StudioCtx.clock(start) + "–" + StudioCtx.clock(start + d);
        }
        return "in line with the clips, " + String.format(Locale.US, "%.1f s", d / 1000.0) + " long";
    }

    private static long durationMs(@NonNull Found f) {
        if (f.overlay == null) {
            return f.clip == null ? 0 : Math.max(0, f.clip.getOutPointMs() - f.clip.getInPointMs());
        }
        TextOverlayItem o = f.overlay;
        if (o.getEndMs() == Long.MAX_VALUE || o.getEndMs() <= o.getStartMs()) return f.gs.authoredDurationMs;
        return o.getEndMs() - o.getStartMs();
    }

    /** The slide's HTML file as text, or null when it is missing. */
    @Nullable
    private static String readHtml(@NonNull GeneratedSource gs) {
        String p = gs.htmlUri.startsWith("file:") ? Uri.parse(gs.htmlUri).getPath() : gs.htmlUri;
        if (p == null || p.isEmpty()) return null;
        return SlideHtmlReader.read(new File(p));
    }

    /** The words a viewer sees: script and style blocks and tags removed. */
    @NonNull
    private static String words(@NonNull Found f) {
        String html = readHtml(f.gs);
        return html == null ? "" : visibleWords(html);
    }

    @NonNull
    static String visibleWords(@NonNull String html) {
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
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
    private static String shortHash(@NonNull String hash) {
        return hash.length() > 10 ? hash.substring(0, 10) : hash;
    }
}
