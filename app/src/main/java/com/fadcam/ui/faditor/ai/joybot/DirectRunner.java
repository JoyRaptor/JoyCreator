package com.fadcam.ui.faditor.ai.joybot;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.AIToolExecutor;
import com.fadcam.ui.faditor.ai.AiKey;
import com.fadcam.ui.faditor.ai.AiService;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Runs an idea's tool steps straight through the tool executor — no model, no network unless a
 * step itself needs one — and reports what changed.
 *
 * <p>It is also where "one request, one undo step" is enforced for these: the project is read
 * from disk before the first step, and if anything changed by the end that before-state is parked
 * as one {@link AIChatState.Step}, labelled with the idea's own words.</p>
 */
public final class DirectRunner {

    private DirectRunner() {}

    public static final class Result {
        /** Plain-words "what changed", or null when nothing measurable did. */
        @Nullable public String summary;
        /** The tool's own words, for results that are information rather than a change. */
        @Nullable public String details;
        /** First failure, in the tool's words; null when every step ran. */
        @Nullable public String error;
        /** The undo step this run parked, or null when nothing changed. */
        @Nullable public AIChatState.Step step;
        /** Something changed (even when the step was recorded by the editor on screen). */
        public boolean changed;
        /** The project read back afterwards (for "Show me" and the next ideas). */
        @Nullable public ProjectFacts after;
    }

    public interface Cancelled { boolean isCancelled(); }

    /** Run every step of {@code idea}; stops at the first error or when cancelled between steps. */
    @WorkerThread
    @NonNull
    public static Result run(@NonNull Context context, @NonNull String projectId, @NonNull Idea idea,
                             @Nullable AIToolExecutor.ProgressListener progress,
                             @Nullable Cancelled cancelled) {
        return run(context, projectId, idea.steps, "Joybot: " + idea.undoLabel, progress, cancelled);
    }

    @WorkerThread
    @NonNull
    public static Result run(@NonNull Context context, @NonNull String projectId,
                             @NonNull List<Idea.Step> steps, @NonNull String undoLabel,
                             @Nullable AIToolExecutor.ProgressListener progress,
                             @Nullable Cancelled cancelled) {
        Context app = context.getApplicationContext();
        Result r = new Result();
        ProjectStorage storage = new JoybotStorage(app);
        boolean online = AiService.ready(app, AiKey.get(app));
        FaditorProject p0 = storage.load(projectId);
        if (p0 == null) { r.error = "I couldn't open this project."; return r; }
        String before = storage.toJson(p0);
        ProjectFacts f0 = ProjectFactsReader.read(app, p0, online);

        // Progress says WHICH clip and how far through the list, so "Transcribe all 4" is followable.
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (ProjectFacts.ClipFacts c : f0.spine) names.put(c.id, c.name);
        for (ProjectFacts.ClipFacts c : f0.audio) names.put(c.id, c.name);
        StringBuilder details = new StringBuilder();
        for (int si = 0; si < steps.size(); si++) {
            Idea.Step s = steps.get(si);
            if (cancelled != null && cancelled.isCancelled()) { r.error = "Stopped."; break; }
            final String where = stepWords(s, names, si, steps.size());
            AIToolExecutor ex = new AIToolExecutor(app, projectId, progress == null ? null
                    : (task, percent) -> progress.onProgress(where.isEmpty() ? task : where, percent));
            String out;
            try {
                out = ex.executeTool(s.tool, toJson(s.args));
            } catch (Exception e) {
                out = "Error: " + e.getMessage();
            }
            if (isFailure(out)) { r.error = out; break; }
            if (details.length() > 0) details.append('\n');
            details.append(out);
        }

        FaditorProject p1 = storage.load(projectId);
        if (p1 != null) {
            String after = storage.toJson(p1);
            // With the editor on screen the editor adopted each change as it was saved and recorded
            // the undo step itself; parking another here would make a second one.
            if (!after.equals(before) && !JoybotStorage.isLive(projectId)) {
                AIChatState.pushStep(projectId, undoLabel, before);
                AIChatState.signalModified(projectId, undoLabel);
                r.step = AIChatState.peekStep(projectId);
            }
            r.changed = !after.equals(before);
            r.after = ProjectFactsReader.read(app, p1, online);
            r.summary = Outcome.describe(f0, r.after);
        }
        r.details = details.length() == 0 ? null : details.toString();
        return r;
    }

    /** Undo the newest Joybot step for this project by writing its before-state back. */
    @WorkerThread
    public static boolean undo(@NonNull Context context, @NonNull String projectId) {
        AIChatState.Step s = AIChatState.popStep(projectId);
        if (s == null) return false;
        ProjectStorage storage = new ProjectStorage(context.getApplicationContext());
        FaditorProject restored = storage.fromJson(s.before);
        if (restored == null || !storage.save(restored)) {
            // Put it back: an undo that could not be written must not vanish from the list.
            AIChatState.pushStep(projectId, s.label, s.before);
            return false;
        }
        AIChatState.signalModified(projectId);
        return true;
    }

    /** "Transcribing “Take 2” (2 of 4)", or "" for steps that name no clip. */
    @NonNull
    static String stepWords(@NonNull Idea.Step s, @NonNull java.util.Map<String, String> names, int i, int n) {
        Object id = s.args.get("clipId");
        if (id == null) id = s.args.get("audioClipId");
        String name = id == null ? null : names.get(String.valueOf(id));
        String verb;
        switch (s.tool) {
            case "generate_transcript": verb = "Transcribing"; break;
            case "toggle_captions": verb = "Turning on captions for"; break;
            case "cut_all_fillers": verb = "Cutting the ums in"; break;
            case "detect_silence": verb = "Listening for quiet parts in"; break;
            case "remove_silence": verb = "Cutting silences in"; break;
            default: verb = "Working on"; break;
        }
        if (name == null) return n > 1 ? "Step " + (i + 1) + " of " + n : "";
        return verb + " “" + name + "”" + (n > 1 ? " (" + (i + 1) + " of " + n + ")" : "");
    }

    /** The tool vocabulary for "that didn't work". */
    public static boolean isFailure(@Nullable String out) {
        if (out == null) return true;
        String t = out.trim();
        return t.startsWith("Error") || t.startsWith("Apply failed") || t.startsWith("Validation failed")
                || t.startsWith("Synthesis failed") || t.startsWith("Fix failed");
    }

    @NonNull
    static JSONObject toJson(@NonNull Map<String, Object> args) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Object> e : args.entrySet()) o.put(e.getKey(), wrap(e.getValue()));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Object wrap(Object v) throws Exception {
        if (v instanceof Map) return toJson((Map<String, Object>) v);
        if (v instanceof List) {
            JSONArray a = new JSONArray();
            for (Object x : (List<Object>) v) a.put(wrap(x));
            return a;
        }
        return v == null ? JSONObject.NULL : v;
    }
}
