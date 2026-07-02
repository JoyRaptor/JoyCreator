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
 *       ligature shown when the activity hasn't overridden them dynamically.</li>
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
