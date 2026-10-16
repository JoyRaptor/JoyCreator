package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.model.ExportSettings;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * The export family (gap F2): "export the video". Joybot never starts an export itself. This handler
 * checks the settings and hands back an Export card; the person taps "Export now" in the chat
 * (ChatAssistantActivity, the "export" proposal card), and only then does the Studio's own export start.
 *
 * <p>Choices are the export sheet's (FaditorEditorActivity.showExportConfirmation): the resolution row
 * Original / 1080p / 720p / 480p, the quality row High / Medium / Low, and the range row. The range rules
 * are the sheet's: the start must be before the end and inside the video; the end is clamped to the video.
 * With no settings given, the card shows the project's own saved settings, as the sheet does.</p>
 */
final class ExportActions {
    private ExportActions() { }

    private static final String FAMILY = "export";

    /** The export sheet's resolution row, in its order (Original, 1080p, 720p, 480p). */
    static final List<String> RESOLUTIONS = Arrays.asList("Original", "1080p", "720p", "480p");
    /** The export sheet's quality row (High, Medium, Low). */
    static final List<String> QUALITIES = Arrays.asList("HIGH", "MEDIUM", "LOW");

    /** Tool results carry this sentinel; the chat draws the card from the JSON after it. */
    static final String PROPOSAL = "@@PROPOSAL:export@@";

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("export_video", "Export the video")
                .help("Shows the person an Export card with the settings; it starts only when they tap Export now."
                        + " Use this one, not export_project, when they ask to export, render, save or share the"
                        + " video. resolution: Original, 1080p, 720p or 480p. quality: HIGH, MEDIUM or LOW."
                        + " For a part of the video, give fromMs and toMs (ms from the start); leave both out"
                        + " for the whole video.")
                .wing("STUDIO", FAMILY).effect(Effect.NAVIGATE)
                .where("Studio › Export (the green upload button)")
                .choice("resolution", false, "Frame size of the video", RESOLUTIONS)
                .choice("quality", false, "Quality of the video", QUALITIES)
                .time("fromMs", false, "Start of the part to export, in ms (0 = the start)", 0.0, null)
                .time("toMs", false, "End of the part to export, in ms (leave out for the end)", 0.0, null)
                .build(), ExportActions::exportVideo);
    }

    // ── handler ──────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult exportVideo(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        FaditorProject p = ctx.project;
        Timeline tl = p.getTimeline();
        if (tl.getClipCount() == 0) {
            throw new ActionError("There is no video to export yet. Add a clip first.");
        }
        long total = tl.getTotalDurationMs();

        // The sheet pre-selects the project's own saved settings; so does this card unless the model asks otherwise.
        ExportSettings es = p.getExportSettings();
        String resolution = a.choice("resolution", RESOLUTIONS);
        if (resolution == null) resolution = resolutionLabel(es == null ? null : es.getResolution());
        String quality = a.choice("quality", QUALITIES);
        if (quality == null) quality = es == null || es.getQuality() == null ? "HIGH" : es.getQuality().name();

        // Range: the sheet's rules. A missing start is the beginning, a missing end is the end of the video.
        Long from = a.whole("fromMs");
        Long to = a.whole("toMs");
        long start = 0L, end = total;
        boolean ranged = false;
        if (from != null || to != null) {
            start = from != null ? from : 0L;
            end = to != null ? to : total;
            if (start < 0 || end <= start || start >= total) {
                throw new ActionError("That part isn't in the video. The video runs 0:00 to "
                        + StudioCtx.clock(total) + ", and the part must end after it starts.");
            }
            end = Math.min(end, total);
            // The whole video is not a part: export it as a whole.
            ranged = start > 0 || end < total;
        }

        try {
            JSONObject out = new JSONObject();
            out.put("projectId", ctx.projectId);
            out.put("resolution", resolution);
            out.put("quality", quality);
            if (ranged) {
                out.put("fromMs", start);
                out.put("toMs", end);
            }
            return ActionResult.unchanged(PROPOSAL + out.toString());
        } catch (Exception e) {
            throw new ActionError("Could not prepare the export: " + e.getMessage());
        }
    }

    /** The sheet's label for a stored resolution. */
    @NonNull
    private static String resolutionLabel(ExportSettings.Resolution r) {
        if (r == null) return "Original";
        switch (r) {
            case FHD_1080P: return "1080p";
            case HD_720P: return "720p";
            case SD_480P: return "480p";
            default: return "Original";
        }
    }
}
