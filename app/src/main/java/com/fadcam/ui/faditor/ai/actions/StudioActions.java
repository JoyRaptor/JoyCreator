package com.fadcam.ui.faditor.ai.actions;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.joybot.JoybotStorage;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.BookmarkStore;
import com.fadcam.ui.faditor.project.ProjectStorage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Studio's actions (gap F2) and the host that runs them. {@link com.fadcam.ui.faditor.ai.AIToolExecutor}
 * asks here first, so Joybot's model, idea buttons and DirectRunner all reach a registered action
 * through the one door they already use; anything not registered yet falls through to the old
 * switch. Families register in {@link #build}; each new family is one line there.
 */
public final class StudioActions {
    private StudioActions() { }

    @Nullable private static volatile ActionRegistry<StudioCtx> REG;

    /** What the last action on this thread touched, for Show me / Director review. */
    private static final ThreadLocal<List<String>> TOUCHED = new ThreadLocal<>();

    /** The ids the last {@link #execute} on this thread touched (empty if none); clears it. */
    @NonNull
    public static List<String> takeTouched() {
        List<String> t = TOUCHED.get();
        TOUCHED.remove();
        return t == null ? new ArrayList<String>() : t;
    }

    @NonNull
    public static ActionRegistry<StudioCtx> registry() {
        ActionRegistry<StudioCtx> r = REG;
        if (r == null) {
            synchronized (StudioActions.class) {
                if ((r = REG) == null) REG = r = build();
            }
        }
        return r;
    }

    private interface Family { void register(@NonNull ActionRegistry<StudioCtx> r); }

    @NonNull
    private static ActionRegistry<StudioCtx> build() {
        ActionRegistry<StudioCtx> r = new ActionRegistry<>();
        add(r, "text look", TextLookActions::register);
        add(r, "transitions", TransitionActions::register);
        add(r, "audio", AudioMixActions::register);
        add(r, "captions", CaptionActions::register);
        add(r, "transform", TransformActions::register);
        add(r, "organise", OrganiseActions::register);
        add(r, "slides", SlideActions::register);
        add(r, "looks", LooksActions::register);
        add(r, "inspect", InspectActions::register);
        add(r, "export", ExportActions::register);
        add(r, "project care", ProjectCareActions::register);
        add(r, "sources", SourcesActions::register);
        return r;
    }

    /**
     * One family that fails to declare itself (a bad spec, a class that won't load) must cost only
     * its own actions: every Joybot tool goes through this registry, so a throw here would take
     * all of them down, the old ones included.
     */
    private static void add(@NonNull ActionRegistry<StudioCtx> r, @NonNull String name, @NonNull Family f) {
        try {
            f.register(r);
        } catch (Throwable t) {
            com.fadcam.FLog.e("StudioActions", "action family \"" + name + "\" left out", t);
        }
    }

    /**
     * Loads the project once, runs the action on it, saves once if it changed and tells the editor
     * (which adopts it as one undo step per request). Returns the text callers of executeTool read:
     * "Error: …" for anything the model must fix.
     */
    @NonNull
    public static String execute(@NonNull Context app, @NonNull ProjectStorage storage,
                                 @NonNull String projectId, @NonNull String tool, @NonNull JSONObject args) {
        FaditorProject proj = storage.load(projectId);
        if (proj == null) return "Error: project not found";
        // Markers are not in project.json: they live in the bookmarks.json sidecar. Load them so
        // list_markers and add_marker see the real list, and remember it to tell if the action moved them.
        File dir = storage.projectDir(projectId);
        List<Long> marksBefore = BookmarkStore.load(dir);
        proj.setBookmarksMs(marksBefore);
        try {
            ActionResult res = registry().run(new StudioCtx(app, projectId, proj), tool, toMap(args));
            TOUCHED.set(new ArrayList<>(res.touchedIds));
            if (res.changed) {
                boolean marksChanged = !proj.getBookmarksMs().equals(marksBefore);
                // A live editor takes its markers from the sidecar inside save() (swapInProject), so
                // write the sidecar first then. A file save writes it only once the save has succeeded.
                boolean live = JoybotStorage.isLive(projectId);
                if (marksChanged && live) BookmarkStore.save(dir, proj.getBookmarksMs());
                boolean saved = storage.save(proj);
                if (marksChanged && !live && saved) BookmarkStore.save(dir, proj.getBookmarksMs());
                AIChatState.signalModified(projectId, res.summary);
            }
            return res.toToolText();
        } catch (ActionError e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + tool + " failed: " + e.getMessage();
        }
    }

    /** A JSON object as plain maps and lists (what {@link Args} and Idea.Step hold). */
    @NonNull
    public static Map<String, Object> toMap(@NonNull JSONObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = o.opt(k);
            if (v == JSONObject.NULL) continue;
            m.put(k, plain(v));
        }
        return m;
    }

    @Nullable
    private static Object plain(@Nullable Object v) {
        if (v instanceof JSONObject) return toMap((JSONObject) v);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(plain(a.opt(i)));
            return l;
        }
        return v;
    }
}
