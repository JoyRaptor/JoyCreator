import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.AiService;
import com.fadcam.ui.faditor.ai.joybot.HelpBook;
import com.fadcam.ui.faditor.ai.joybot.IdeaEngine;
import com.fadcam.ui.faditor.ai.joybot.JoybotAgent;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.vislab.room.VisLabSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One task, one model, in this (child) process: set up, ask the real agent, check. */
final class Run {

    private Run() {}

    final static class State {
        android.content.Context ctx;
        JsonObject task;
        String pid;
        JoybotAgent.Ask first, second;
        volatile long stopNanos;
        final List<JoybotAgent.Finished> finished = Collections.synchronizedList(new ArrayList<>());
        final List<String> shown = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch over;
        FakeEditor editor;
        final List<String> hookErrors = Collections.synchronizedList(new ArrayList<>());
    }

    /** The editor on screen, for tasks that need one: it takes (or refuses) Joybot's changes. */
    static final class FakeEditor implements AIChatState.LiveEditor {
        final ProjectStorage storage;
        final String pid;
        final boolean refuses;
        final Set<String> requests = new LinkedHashSet<>();
        int adopts;

        FakeEditor(ProjectStorage storage, String pid, boolean refuses) {
            this.storage = storage; this.pid = pid; this.refuses = refuses;
        }

        @Override public String snapshotJson() {
            FaditorProject p = storage.load(pid);
            return p == null ? null : storage.toJson(p);
        }

        @Override public boolean adopt(String json, String label, String request) {
            if (refuses) return false;
            FaditorProject p = storage.fromJson(json);
            if (p == null || !storage.save(p)) return false;
            adopts++;
            requests.add(request);   // same request = merged into one undo step, as the editor does
            return true;
        }
    }

    static void child(Map<String, String> a) throws Exception {
        String id = a.get("one");
        JsonObject task = null;
        for (JsonObject t : JoybotBench.tasks()) if (t.get("id").getAsString().equals(id)) task = t;
        if (task == null) throw new IllegalArgumentException("no task " + id);
        boolean fake = "fake".equals(a.get("mode"));
        String model = a.get("model");
        File dir = new File(a.get("dir"));
        State s = new State();
        s.task = task;
        s.ctx = new android.content.Context(new File(dir, "files"), new File(System.getProperty("bench.assets")));
        String room = str(task, "room", "studio");
        boolean vislab = room.equals("vislab");

        FakeProvider provider = null;
        String apiKey;
        if (fake) {
            List<JsonObject> turns = new ArrayList<>();
            if (task.has("reference")) for (JsonElement e : task.getAsJsonArray("reference")) turns.add(e.getAsJsonObject());
            provider = new FakeProvider(turns, hooks(s));
            AiService.setBase(s.ctx, provider.base());
            apiKey = "bench-fake-key";
            model = "bench/fake";
        } else {
            apiKey = System.getenv("OPENROUTER_API_KEY");
        }

        // ── the starting document ──
        Doc before;
        int undoBefore = 0;
        VisLabSession session = null;
        if (!vislab) {
            ProjectStorage st = new ProjectStorage(s.ctx);
            String json = JoybotBench.read(new File(JoybotBench.HOME, "fixtures/" + str(task, "fixture", "studio-basic") + ".project.json"));
            FaditorProject p = st.fromJson(json);
            if (p == null || !st.save(p)) throw new IllegalStateException("fixture did not load/save");
            s.pid = p.getId();
            String editor = str(task, "editor", "none");
            if (!editor.equals("none")) {
                s.editor = new FakeEditor(st, s.pid, editor.equals("refuses"));
                AIChatState.setLiveEditor(s.pid, s.editor);
            }
            before = new Doc(projectJson(s));
        } else {
            session = VisLabSession.get(s.ctx);
            for (int i = 0; i < 100 && !session.isLoaded(); i++) FakeProvider.sleep(50);
            String look = str(task, "look", null);
            if (look != null && !session.pickLook(look, "bench setup").ok) throw new IllegalStateException("no look " + look);
            clearUndo(session);
            before = new Doc(visDoc(session));
            undoBefore = undoSize(session);
        }

        // ── the person's request ──
        s.first = ask(s, task, model, apiKey);
        int expected = task.has("second") ? 2 : 1;
        s.over = new CountDownLatch(expected);
        JoybotAgent.attach(new JoybotAgent.Listener() {
            @Override public void onText(String shown, boolean newBubble) { }
            @Override public void onWorking(String t, int percent) { }
            @Override public void onChecklist(String[] stages) { }
            @Override public void onToolLine(String line) { }
            @Override public void onStep(AIChatState.Step step) { }
            @Override public boolean onFinished(JoybotAgent.Finished f) {
                s.finished.add(f);
                s.over.countDown();
                return true;
            }
        });
        long t0 = System.nanoTime();
        JoybotAgent.ask(s.ctx, s.first);
        boolean ended = s.over.await(fake ? 60 : 360, TimeUnit.SECONDS);
        long latency = (System.nanoTime() - t0) / 1_000_000L;
        FakeProvider.sleep(fake ? 500 : 200);   // any late request from a stopped job shows up here

        Doc after = vislab ? new Doc(visDoc(session)) : new Doc(projectJson(s));
        int undoSteps = vislab ? undoSize(session) - undoBefore
                : s.editor != null ? s.editor.requests.size() : AIChatState.stepCount(s.pid);

        JsonObject res = Expect.check(s, before, after, undoSteps, provider, ended);
        res.addProperty("task", id);
        res.addProperty("model", fake ? "fake (reference script)" : model);
        res.addProperty("latencyMs", latency);
        JoybotAgent.Finished last = s.finished.isEmpty() ? null : s.finished.get(s.finished.size() - 1);
        res.addProperty("steps", last == null ? -1 : last.steps + 1);
        res.addProperty("tokens", last == null ? -1 : last.tokens);
        res.addProperty("cost", last == null ? -1 : last.cost);
        if (provider != null) {
            res.addProperty("requests", provider.seen.size());
            int chars = 0;
            for (FakeProvider.Seen q : provider.seen) chars += q.chars;
            res.addProperty("sentChars", chars);
            res.addProperty("tokensEstimated", true);
            provider.stop();
        }
        Files.write(new File(a.get("result")).toPath(), JoybotBench.GSON.toJson(res).getBytes(StandardCharsets.UTF_8));
    }

    static JoybotAgent.Ask ask(State s, JsonObject task, String model, String apiKey) {
        return ask(s, task, str(task, "words", ""), model, apiKey);
    }

    static JoybotAgent.Ask ask(State s, JsonObject task, String words, String model, String apiKey) {
        JoybotAgent.Situation sit = new JoybotAgent.Situation();
        sit.room = str(task, "room", "studio").equals("vislab") ? IdeaEngine.Room.VISLAB : IdeaEngine.Room.STUDIO;
        sit.selectedId = str(task, "selected", null);
        sit.playheadMs = task.has("playheadMs") ? task.get("playheadMs").getAsLong() : -1;
        JoybotAgent.Ask ask = new JoybotAgent.Ask(s.pid, words, model, apiKey,
                "Joybot: “" + JoybotAgent.shortOf(words) + "”", sit);
        ask.question = HelpBook.isQuestion(words);   // as the chat sets it
        return ask;
    }

    /** What the scripted events do, at the moments the fake provider reports. */
    static FakeProvider.Hooks hooks(State s) {
        return new FakeProvider.Hooks() {
            @Override public void beforeAnswer(int n, JsonObject body) { fire("before", n, -1); }
            @Override public void duringAnswer(int n, int chunk) { fire("during", n, chunk); }
            @Override public void afterAnswer(int n) { fire("after", n, -1); }

            void fire(String when, int n, int chunk) {
                if (!s.task.has("events")) return;
                for (JsonElement e : s.task.getAsJsonArray("events")) {
                    JsonObject ev = e.getAsJsonObject();
                    if (!when.equals(str(ev, "when", "")) || ev.get("request").getAsInt() != n) continue;
                    if (chunk >= 0 && ev.has("chunk") && ev.get("chunk").getAsInt() != chunk) continue;
                    if (chunk >= 0 && !ev.has("chunk") && chunk != 1) continue;
                    try {
                        act(s, ev);
                    } catch (Exception ex) {
                        s.hookErrors.add(str(ev, "do", "?") + ": " + ex);
                    }
                }
            }
        };
    }

    static void act(State s, JsonObject ev) throws Exception {
        switch (str(ev, "do", "")) {
            case "stopAndSend": {
                // The person taps Stop, then sends their next request straight away (audit J3).
                s.stopNanos = System.nanoTime();
                JoybotAgent.cancel(s.first.key());
                JsonObject second = s.task.getAsJsonObject("second");
                s.second = ask(s, s.task, str(second, "words", ""), s.first.model, s.first.apiKey);
                JoybotAgent.ask(s.ctx, s.second);
                break;
            }
            case "deleteItem":
            case "userEdit": {
                // The person changes the project in Studio while Joybot works.
                ProjectStorage st = new ProjectStorage(s.ctx);
                JsonObject p = JsonParser.parseString(st.toJson(st.load(s.pid))).getAsJsonObject();
                if (str(ev, "do", "").equals("deleteItem")) {
                    if (!removeById(p, str(ev, "id", ""))) throw new IllegalStateException("no item " + str(ev, "id", ""));
                } else {
                    JsonObject item = findById(p, str(ev, "id", ""));
                    if (item == null) throw new IllegalStateException("no item " + str(ev, "id", ""));
                    item.add(str(ev, "field", ""), ev.get("value"));
                }
                if (!st.save(st.fromJson(p.toString()))) throw new IllegalStateException("save failed");
                break;
            }
            default:
                throw new IllegalArgumentException("unknown event " + str(ev, "do", ""));
        }
    }

    static boolean removeById(JsonElement e, String id) {
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) if (removeById(m.getValue(), id)) return true;
        } else if (e.isJsonArray()) {
            JsonArray arr = e.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement it = arr.get(i);
                if (it.isJsonObject() && it.getAsJsonObject().has("id") && id.equals(it.getAsJsonObject().get("id").getAsString())) {
                    arr.remove(i);
                    return true;
                }
                if (removeById(it, id)) return true;
            }
        }
        return false;
    }

    static JsonObject findById(JsonElement e, String id) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("id") && o.get("id").isJsonPrimitive() && id.equals(o.get("id").getAsString())) return o;
            for (Map.Entry<String, JsonElement> m : o.entrySet()) {
                JsonObject f = findById(m.getValue(), id);
                if (f != null) return f;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement it : e.getAsJsonArray()) {
                JsonObject f = findById(it, id);
                if (f != null) return f;
            }
        }
        return null;
    }

    static JsonElement projectJson(State s) {
        ProjectStorage st = new ProjectStorage(s.ctx);
        FaditorProject p = st.load(s.pid);
        return p == null ? new JsonObject() : JsonParser.parseString(st.toJson(p));
    }

    /** VisLab as one document: which look is on screen, the brand colours, and every look. */
    static JsonElement visDoc(VisLabSession session) {
        JsonObject d = new JsonObject();
        String cur = session.currentLookJson();
        d.addProperty("current", cur == null ? "" : JsonParser.parseString(cur).getAsJsonObject().get("id").getAsString());
        String[] brand = session.brand();
        d.addProperty("brand", brand == null ? "" : String.join(",", brand));
        JsonObject looks = new JsonObject();
        for (String id : session.lookIds()) looks.add(id, JsonParser.parseString(session.lookJson(id)));
        d.add("looks", looks);
        return d;
    }

    static int undoSize(VisLabSession session) throws Exception {
        java.lang.reflect.Field f = VisLabSession.class.getDeclaredField("undo");
        f.setAccessible(true);
        return ((List<?>) f.get(session)).size();
    }

    static void clearUndo(VisLabSession session) throws Exception {
        java.lang.reflect.Field f = VisLabSession.class.getDeclaredField("undo");
        f.setAccessible(true);
        ((List<?>) f.get(session)).clear();
    }

    static String str(JsonObject o, String k, String def) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }
}
