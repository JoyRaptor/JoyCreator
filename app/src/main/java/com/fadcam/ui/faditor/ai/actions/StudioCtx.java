package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a Studio action is handed: the project, already loaded (the live editor's copy when it is on
 * screen). Handlers change it in memory; {@link StudioActions#execute} saves once afterwards.
 * Also the one place ids-or-names are resolved, so every action accepts "hello 2" as well as an id.
 */
public final class StudioCtx {
    @NonNull public final Context app;
    @NonNull public final String projectId;
    @NonNull public final FaditorProject project;

    public StudioCtx(@NonNull Context app, @NonNull String projectId, @NonNull FaditorProject project) {
        this.app = app;
        this.projectId = projectId;
        this.project = project;
    }

    /**
     * Text layers matching {@code ref}: an id, "all", the exact words on it, or words it contains —
     * tried in that order. Several matches by words is an error naming each, so the model asks.
     */
    @NonNull
    public List<TextOverlayItem> texts(@NonNull String ref) throws ActionError {
        // Pictures and AI slides are TextOverlayItems too; only real text takes a text look.
        List<TextOverlayItem> all = new ArrayList<>();
        for (TextOverlayItem t : project.getTimeline().getTextOverlays()) {
            if (!t.isImage() && !t.isGeneratedSlide()) all.add(t);
        }
        if (all.isEmpty()) throw new ActionError("This project has no text layers.");
        String r = ref.trim();
        if ("all".equalsIgnoreCase(r)) return new ArrayList<>(all);
        for (TextOverlayItem t : all) if (r.equals(t.getId())) return one(t);
        String want = norm(r);
        List<TextOverlayItem> exact = new ArrayList<>(), part = new ArrayList<>();
        for (TextOverlayItem t : all) {
            String words = norm(t.getText());
            if (words.equals(want)) exact.add(t);
            else if (!want.isEmpty() && words.contains(want)) part.add(t);
        }
        List<TextOverlayItem> hit = !exact.isEmpty() ? exact : part;
        if (hit.size() == 1) return hit;
        if (hit.isEmpty()) {
            throw new ActionError("No text layer is called \"" + r + "\". The text layers are: " + list(all) + ".");
        }
        throw new ActionError(hit.size() + " text layers match \"" + r + "\": " + list(hit)
                + ". Ask which one, or use its id.");
    }

    @NonNull
    private static List<TextOverlayItem> one(@NonNull TextOverlayItem t) {
        List<TextOverlayItem> l = new ArrayList<>();
        l.add(t);
        return l;
    }

    @NonNull
    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** "“Hello” (id a1b2, 0:01–0:04)" for each, so the model can ask the person which. */
    @NonNull
    static String list(@NonNull List<TextOverlayItem> ts) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (TextOverlayItem t : ts) {
            if (n++ == 12) { sb.append(", … ").append(ts.size() - 12).append(" more"); break; }
            if (sb.length() > 0) sb.append(", ");
            String w = t.getText() == null ? "" : t.getText().replace('\n', ' ');
            if (w.length() > 30) w = w.substring(0, 30) + "…";
            sb.append('"').append(w).append("\" (id ").append(t.getId()).append(", ")
                    .append(clock(t.getStartMs())).append('–')
                    .append(t.getEndMs() == Long.MAX_VALUE ? "end" : clock(t.getEndMs())).append(')');
        }
        return sb.toString();
    }

    @NonNull
    static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
