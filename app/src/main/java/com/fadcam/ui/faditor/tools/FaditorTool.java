package com.fadcam.ui.faditor.tools;

import androidx.annotation.NonNull;

/**
 * Immutable descriptor for a single tool in the Faditor editor's bottom
 * carousel. The carousel used to be ~25 hardcoded {@code tool_*} LinearLayout
 * blocks in {@code activity_faditor_editor.xml}; it is now data-driven off a
 * list of these descriptors built in {@link FaditorToolRegistry}.
 *
 * <p>Each tool carries:
 * <ul>
 *   <li>{@link #id} — a stable string key (matches the historical
 *       {@code tool_*} view id name, e.g. {@code "mute"} for {@code tool_mute}).
 *       Persisted in prefs for order/pin/recency, so it must never change for
 *       an existing tool.</li>
 *   <li>{@link #viewId} / {@link #iconViewId} / {@link #labelViewId} — the
 *       {@code R.id.*} resource ids assigned to the generated cell + its icon
 *       and label TextViews. These match the OLD inline ids exactly so all of
 *       {@code FaditorEditorActivity}'s cached field references and
 *       {@code findViewById(R.id.tool_*_icon/label)} calls keep resolving.</li>
 *   <li>{@link #label} / {@link #icon} — default label text + materialicons
 *       ligature shown when the activity hasn't overridden them dynamically.
 *       An icon may instead be {@code "text:FX"} — see {@link #TEXT_ICON} — for
 *       a tool whose mark is a word rather than a glyph.</li>
 *   <li>{@link #bindMode} — how clicks/touches are wired. Most tools are a
 *       simple click; {@code mute} and {@code opacity} keep their rich custom
 *       {@code OnTouchListener} (tap/long-press/drag) installed by the
 *       activity.</li>
 *   <li>{@link #alwaysHidden} — {@code trim} and {@code heal} were permanently
 *       {@code GONE} in the old layout; they stay hidden in the carousel but
 *       remain in the registry so their ids exist for the activity.</li>
 * </ul>
 */
public final class FaditorTool {

    /** How the generated cell dispatches user input. */
    public enum BindMode {
        /** Simple click → activity handler. Default for almost every tool. */
        CLICK,
        /** Volume tool: activity installs its custom OnTouchListener. */
        TOUCH_VOLUME,
        /** Opacity tool: activity installs its custom OnTouchListener. */
        TOUCH_OPACITY
    }

    @NonNull public final String id;
    public final int viewId;
    public final int iconViewId;
    public final int labelViewId;
    @NonNull public final String label;
    @NonNull public final String icon;
    @NonNull public final BindMode bindMode;
    public final boolean alwaysHidden;

    /**
     * Prefix marking an {@link #icon} as LITERAL TEXT rather than a materialicons ligature.
     *
     * <p>Some marks are a word. "FX" is one: the icon font has no glyph for the idea, and every
     * near-miss borrowed from it — stacked rhombi, a boolean-union pair of circles — reads as two
     * abstract shapes and tells the user nothing. Rendering the letters is the honest answer.</p>
     *
     * <p>Handled in {@link #applyIcon}, which every binding site calls, so a text icon cannot
     * work in the carousel and come out as the raw string "text:FX" in the overflow drawer.</p>
     */
    public static final String TEXT_ICON = "text:";

    /**
     * Put {@code icon} on {@code view}, choosing the icon font or bold letters as appropriate.
     *
     * <p>Sized down for text: a two-letter mark at the glyph size overflows the 28dp cell that
     * every other tool fits inside.</p>
     */
    public static void applyIcon(@NonNull android.widget.TextView view, @NonNull String icon,
                                 float glyphSp) {
        if (icon.startsWith(TEXT_ICON)) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            view.setText(icon.substring(TEXT_ICON.length()));
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, glyphSp * 0.8f);
            return;
        }
        view.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(
                view.getContext(), com.fadcam.R.font.materialicons));
        view.setText(icon);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, glyphSp);
    }

    public FaditorTool(@NonNull String id,
                       int viewId,
                       int iconViewId,
                       int labelViewId,
                       @NonNull String label,
                       @NonNull String icon,
                       @NonNull BindMode bindMode,
                       boolean alwaysHidden) {
        this.id = id;
        this.viewId = viewId;
        this.iconViewId = iconViewId;
        this.labelViewId = labelViewId;
        this.label = label;
        this.icon = icon;
        this.bindMode = bindMode;
        this.alwaysHidden = alwaysHidden;
    }

    @NonNull
    @Override
    public String toString() {
        return "FaditorTool{" + id + "}";
    }
}
