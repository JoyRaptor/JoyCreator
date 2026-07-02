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

        // trim — was android:visibility="gone" (kept hidden, id preserved).
        add(t, "trim", R.id.tool_trim, R.id.tool_trim_icon, R.id.tool_trim_label,
                ctx.getString(R.string.faditor_tool_trim), "content_cut",
                FaditorTool.BindMode.CLICK, true);
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
                ctx.getString(R.string.faditor_tool_audio), "graphic_eq",
                FaditorTool.BindMode.CLICK, false);
        add(t, "split", R.id.tool_split, R.id.tool_split_icon, R.id.tool_split_label,
                ctx.getString(R.string.faditor_tool_split), "carpenter",
                FaditorTool.BindMode.CLICK, false);
        // heal — was android:visibility="gone" (kept hidden, id preserved).
        add(t, "heal", R.id.tool_heal, R.id.tool_heal_icon, R.id.tool_heal_label,
                ctx.getString(R.string.faditor_tool_heal), "auto_fix_high",
                FaditorTool.BindMode.CLICK, true);
        add(t, "delete", R.id.tool_delete, R.id.tool_delete_icon, R.id.tool_delete_label,
                ctx.getString(R.string.faditor_tool_delete), "delete",
                FaditorTool.BindMode.CLICK, false);
        add(t, "duplicate", R.id.tool_duplicate, R.id.tool_duplicate_icon, R.id.tool_duplicate_label,
                ctx.getString(R.string.faditor_tool_duplicate), "content_copy",
                FaditorTool.BindMode.CLICK, false);
        add(t, "add_asset", R.id.tool_add_asset, R.id.tool_add_asset_icon, R.id.tool_add_asset_label,
                ctx.getString(R.string.faditor_tool_add), "add_circle",
                FaditorTool.BindMode.CLICK, false);
        add(t, "text", R.id.tool_text, R.id.tool_text_icon, R.id.tool_text_label,
                ctx.getString(R.string.faditor_tool_text), "text_fields",
                FaditorTool.BindMode.CLICK, false);
        add(t, "visualizer", R.id.tool_visualizer, R.id.tool_visualizer_icon, R.id.tool_visualizer_label,
                "Visualizer", "graphic_eq",
                FaditorTool.BindMode.CLICK, false);
        add(t, "sticker", R.id.tool_sticker, R.id.tool_sticker_icon, R.id.tool_sticker_label,
                ctx.getString(R.string.faditor_tool_sticker), "image",
                FaditorTool.BindMode.CLICK, false);
        add(t, "captions", R.id.tool_captions, R.id.tool_captions_icon, R.id.tool_captions_label,
                ctx.getString(R.string.faditor_captions), "subtitles",
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
                ctx.getString(R.string.faditor_transcript), "closed_caption",
                FaditorTool.BindMode.CLICK, false);
        add(t, "silence", R.id.tool_silence, R.id.tool_silence_icon, R.id.tool_silence_label,
                ctx.getString(R.string.faditor_tool_silence), "auto_fix_high",
                FaditorTool.BindMode.CLICK, false);
        add(t, "loop", R.id.tool_loop, R.id.tool_loop_icon, R.id.tool_loop_label,
                ctx.getString(R.string.faditor_tool_loop), "loop",
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
