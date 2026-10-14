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
        ProjectStorage storage = new ProjectStorage(app);
        boolean online = AiService.ready(app, AiKey.get(app));
        FaditorProject p0 = storage.load(projectId);
        if (p0 == null) { r.error = "I couldn't open this project."; return r; }
        String before = storage.toJson(p0);
        ProjectFacts f0 = ProjectFactsReader.read(app, p0, online);

        AIToolExecutor ex = new AIToolExecutor(app, projectId, progress);
        StringBuilder details = new StringBuilder();
        for (Idea.Step s : steps) {
            if (cancelled != null && cancelled.isCancelled()) { r.error = "Stopped."; break; }
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
            if (!after.equals(before)) {
                AIChatState.pushStep(projectId, undoLabel, before);
                AIChatState.signalModified(projectId, undoLabel);
                r.step = AIChatState.peekStep(projectId);
            }
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
