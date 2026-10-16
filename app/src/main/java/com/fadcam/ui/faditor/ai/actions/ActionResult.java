package com.fadcam.ui.faditor.ai.actions;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an action did. {@link #summary} is for the person ("The title now uses Serif bold."),
 * {@link #detail} is extra for the model, {@link #touchedIds} feeds Show me and Director-mode review.
 * A handler that changed nothing returns {@link #unchanged} so the host does not save.
 */
public final class ActionResult {
    public final boolean changed;
    @NonNull public final String summary;
    @NonNull public final String detail;
    @NonNull public final List<String> touchedIds;

    private ActionResult(boolean changed, @NonNull String summary, @NonNull String detail,
                         @NonNull List<String> touchedIds) {
        this.changed = changed;
        this.summary = summary;
        this.detail = detail;
        this.touchedIds = Collections.unmodifiableList(new ArrayList<>(touchedIds));
    }

    @NonNull
    public static ActionResult changed(@NonNull String summary, @NonNull List<String> touchedIds) {
        return new ActionResult(true, summary, "", touchedIds);
    }

    @NonNull
    public static ActionResult unchanged(@NonNull String summary) {
        return new ActionResult(false, summary, "", Collections.<String>emptyList());
    }

    /** The same result with extra words for the model. */
    @NonNull
    public ActionResult withDetail(@NonNull String d) {
        return new ActionResult(changed, summary, d, touchedIds);
    }

    /** One string, the shape every caller of AIToolExecutor.executeTool already reads. */
    @NonNull
    public String toToolText() {
        return detail.isEmpty() ? summary : summary + "\n" + detail;
    }
}
