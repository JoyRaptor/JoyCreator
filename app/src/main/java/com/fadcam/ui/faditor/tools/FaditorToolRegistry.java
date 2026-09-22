package com.fadcam.ui.faditor.tools;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Canonical list of Faditor bottom-carousel tools, in the SAME order they
 * appeared as hardcoded {@code tool_*} blocks in the old
 * {@code activity_faditor_editor.xml}. This is the single source of truth the
 * data-driven carousel (and, later, the swipe-up drawer) builds from.
 *
 * <p>To ADD a new tool in a future build: append one {@code add(...)} call
 * here with a NEW unique {@code id} string and a NEW {@code R.id.*} for the
 * cell/icon/label (declare them in {@code res/values/ids.xml}), then wire its
 * click handler in {@code FaditorEditorActivity.bindToolHandlers()}. Persisted
 * user ordering treats unknown ids as "new" and appends them at the end, so
 * added tools never vanish and never break existing saved orders.</p>
 */
public final class FaditorToolRegistry {

    private FaditorToolRegistry() {}

    /**
     * Builds the default (canonical) ordered tool list. Order here mirrors the
     * historical XML exactly so the default carousel is byte-for-byte the same
     * left-to-right sequence users had before the refactor.
     */
    @NonNull
    public static List<FaditorTool> defaultTools(@NonNull Context ctx) {
        List<FaditorTool> t = new ArrayList<>();

        add(t, "speed", R.id.tool_speed, R.id.tool_speed_icon, R.id.tool_speed_label,
                ctx.getString(R.string.faditor_tool_speed), "speed",
                FaditorTool.BindMode.CLICK, false);
        add(t, "mute", R.id.tool_mute, R.id.tool_mute_icon, R.id.tool_mute_label,
                ctx.getString(R.string.faditor_tool_sound), "volume_up",
                FaditorTool.BindMode.TOUCH_VOLUME, false);
        add(t, "opacity", R.id.tool_opacity, R.id.tool_opacity_icon, R.id.tool_opacity_label,
                ctx.getString(R.string.faditor_tool_opacity), "opacity",
                FaditorTool.BindMode.TOUCH_OPACITY, false);
        add(t, "rotate", R.id.tool_rotate, R.id.tool_rotate_icon, R.id.tool_rotate_label,
                ctx.getString(R.string.faditor_tool_rotate), "rotate_right",
                FaditorTool.BindMode.CLICK, false);
        add(t, "flip", R.id.tool_flip, R.id.tool_flip_icon, R.id.tool_flip_label,
                ctx.getString(R.string.faditor_tool_flip), "flip",
                FaditorTool.BindMode.CLICK, false);
        add(t, "crop", R.id.tool_crop, R.id.tool_crop_icon, R.id.tool_crop_label,
                ctx.getString(R.string.faditor_tool_crop), "crop",
                FaditorTool.BindMode.CLICK, false);
        add(t, "canvas", R.id.tool_canvas, R.id.tool_canvas_icon, R.id.tool_canvas_label,
                ctx.getString(R.string.faditor_tool_canvas), "aspect_ratio",
                FaditorTool.BindMode.CLICK, false);
        add(t, "audio", R.id.tool_audio, R.id.tool_audio_icon, R.id.tool_audio_label,
                ctx.getString(R.string.faditor_tool_audio), "call_split",
                FaditorTool.BindMode.CLICK, false);
        add(t, "split", R.id.tool_split, R.id.tool_split_icon, R.id.tool_split_label,
                ctx.getString(R.string.faditor_tool_split), "content_cut",
                FaditorTool.BindMode.CLICK, false);
        add(t, "delete", R.id.tool_delete, R.id.tool_delete_icon, R.id.tool_delete_label,
                ctx.getString(R.string.faditor_tool_delete), "delete",
                FaditorTool.BindMode.CLICK, false);
        add(t, "duplicate", R.id.tool_duplicate, R.id.tool_duplicate_icon, R.id.tool_duplicate_label,
                ctx.getString(R.string.faditor_tool_duplicate), "content_copy",
                FaditorTool.BindMode.CLICK, false);
        add(t, "add_asset", R.id.tool_add_asset, R.id.tool_add_asset_icon, R.id.tool_add_asset_label,
                ctx.getString(R.string.faditor_tool_add), "add_circle",
                FaditorTool.BindMode.CLICK, false);
        // SPEC_20260829_QUICK_WINS S1: Image (overlay) is the common documentary action;
        // image-as-clip (spine segment) is rare. Promote overlay to sit beside Add so it
        // is reachable in <=2 taps, beside the other primary Add/Transition/Captions/Visualizer
        // entry points. The clip path is demoted to Add's secondary section / long-press.
        add(t, "sticker", R.id.tool_sticker, R.id.tool_sticker_icon, R.id.tool_sticker_label,
                ctx.getString(R.string.faditor_tool_sticker), "image",
                FaditorTool.BindMode.CLICK, false);
        add(t, "text", R.id.tool_text, R.id.tool_text_icon, R.id.tool_text_label,
                ctx.getString(R.string.faditor_tool_text), "text_fields",
                FaditorTool.BindMode.CLICK, false);
        add(t, "visualizer", R.id.tool_visualizer, R.id.tool_visualizer_icon, R.id.tool_visualizer_label,
                // graphic_eq, not music_note: Beats already wears the note, and the owner tells
                // tools apart by their icon. Two tools, one glyph, is one tool too few to find.
                "Visualizer", "graphic_eq",
                FaditorTool.BindMode.CLICK, false);
        // "closed_caption" is the CC badge, which is literally what captions ARE. It was on
        // Transcript, where it meant nothing, while Captions wore "subtitles" — a stack of
        // little text blocks that describes a transcript. The two were simply swapped.
        add(t, "captions", R.id.tool_captions, R.id.tool_captions_icon, R.id.tool_captions_label,
                ctx.getString(R.string.faditor_captions), "closed_caption",
                FaditorTool.BindMode.CLICK, false);
        add(t, "filter", R.id.tool_filter, R.id.tool_filter_icon, R.id.tool_filter_label,
                ctx.getString(R.string.faditor_tool_filter), "tune",
                FaditorTool.BindMode.CLICK, false);
        add(t, "move", R.id.tool_move, R.id.tool_move_icon, R.id.tool_move_label,
                ctx.getString(R.string.faditor_tool_move), "open_with",
                FaditorTool.BindMode.CLICK, false);
        add(t, "transitions", R.id.tool_transitions, R.id.tool_transitions_icon, R.id.tool_transitions_label,
                ctx.getString(R.string.faditor_tool_transitions), "auto_awesome_motion",
                FaditorTool.BindMode.CLICK, false);
        add(t, "transcript", R.id.tool_transcript, R.id.tool_transcript_icon, R.id.tool_transcript_label,
                ctx.getString(R.string.faditor_transcript), "subtitles",
                FaditorTool.BindMode.CLICK, false);
        add(t, "silence", R.id.tool_silence, R.id.tool_silence_icon, R.id.tool_silence_label,
                // cleaning_services, not auto_fix_high: that is Fix audio's wand. Same reason.
                ctx.getString(R.string.faditor_tool_silence), "cleaning_services",
                FaditorTool.BindMode.CLICK, false);
        add(t, "fix_audio", R.id.tool_fix_audio, R.id.tool_fix_audio_icon, R.id.tool_fix_audio_label,
                "Fix audio", "auto_fix_high",
                FaditorTool.BindMode.CLICK, false);
        add(t, "beats", R.id.tool_beats, R.id.tool_beats_icon, R.id.tool_beats_label,
                "Beats", "music_note",
                FaditorTool.BindMode.CLICK, false);
        add(t, "align", R.id.tool_align, R.id.tool_align_icon, R.id.tool_align_label,
                "Align", "compare_arrows",
                FaditorTool.BindMode.CLICK, false);
        add(t, "loop", R.id.tool_loop, R.id.tool_loop_icon, R.id.tool_loop_label,
                ctx.getString(R.string.faditor_tool_loop), "loop",
                FaditorTool.BindMode.CLICK, false);
        // A running figure, not the overlapping-circles "animation" mark: JoyRaptor could not
        // tell what that tool was from its icon, and sprite sheets are how a character MOVES.
        add(t, "sprites", R.id.tool_sprites, R.id.tool_sprites_icon, R.id.tool_sprites_label,
                ctx.getString(R.string.faditor_tool_sprites), "directions_run",
                FaditorTool.BindMode.CLICK, false);
        // Adjust — open the effect stack for whatever is selected. The mark is the WORD "FX",
        // not a glyph: the icon font has nothing for the idea, and every near-miss borrowed
        // from it (stacked rhombi, a boolean-union pair of circles) reads as two abstract
        // shapes. See FaditorTool.TEXT_ICON. This tool no longer creates a layer either —
        // that lives in Add, where the user went looking for it.
        add(t, "adjustment", R.id.tool_adjustment, R.id.tool_adjustment_icon,
                R.id.tool_adjustment_label, "Adjust", FaditorTool.TEXT_ICON + "FX",
                FaditorTool.BindMode.CLICK, false);
        // Slice F: compact lanes — drop every overlay into the fewest no-overlap lanes.
        // "Consolidate layers" over "Compact": longer, but it names the OBJECT it acts on.
        // "Compact" alone could mean compact the timeline, the view, the file, or the UI.
        add(t, "compact", R.id.tool_compact, R.id.tool_compact_icon, R.id.tool_compact_label,
                // The ROW label, not the feature's name. "Consolidate layers" is 18 characters and
                // clipped to "onsolidate la" in a 68dp cell even at the 7sp autosize floor -
                // and a centre-clipped label loses BOTH ends, which is worse than a short one.
                // The full name survives everywhere it has room.
                "Consolidate", "compress",
                FaditorTool.BindMode.CLICK, false);
        // G8: marquee multi-select mode toggle (off / inclusive-crossing / exclusive-window).
        add(t, "select", R.id.tool_select, R.id.tool_select_icon, R.id.tool_select_label,
                "Select", "highlight_alt",
                FaditorTool.BindMode.CLICK, false);
        add(t, "settings", R.id.tool_settings, R.id.tool_settings_icon, R.id.tool_settings_label,
                ctx.getString(R.string.faditor_tool_settings), "settings",
                FaditorTool.BindMode.CLICK, false);

        return t;
    }

    private static void add(@NonNull List<FaditorTool> list, @NonNull String id,
                            int viewId, int iconViewId, int labelViewId,
                            @NonNull String label, @NonNull String icon,
                            @NonNull FaditorTool.BindMode mode, boolean alwaysHidden) {
        list.add(new FaditorTool(id, viewId, iconViewId, labelViewId, label, icon, mode, alwaysHidden));
    }
}
