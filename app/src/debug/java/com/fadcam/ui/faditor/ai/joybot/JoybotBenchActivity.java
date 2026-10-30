package com.fadcam.ui.faditor.ai.joybot;

import android.app.Activity;
import android.os.Bundle;

import com.fadcam.FLog;
import com.fadcam.ui.faditor.ai.AIChatState;
import com.fadcam.ui.faditor.ai.AiKey;
import com.fadcam.ui.faditor.ai.AiService;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.vislab.room.VisLabSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DEBUG BUILDS ONLY: the Joybot benchmark's live runs, on the phone, with the AI key saved in
 * the app (sealed by the phone's keystore, so only the app can use it; it is never written or
 * logged here). The PC pushes the task corpus and starts this; it runs each task with the real
 * agent exactly as the chat does and writes what happened (the document before and after, the
 * reply, undo steps, steps, tokens, time) to results.jsonl. The PC grades those with the same
 * checks as its own runs ({@code tools/jvm-harness/joybot-bench}, {@code --phone}).
 *
 * <pre>
 * adb shell am start -n com.fadcam.beta/com.fadcam.ui.faditor.ai.joybot.JoybotBenchActivity \
 *     --es models "openrouter/free,…:free" [--es tasks studio-,vislab-] [--ei repeat 1] [--ei pause 5]
 * </pre>
 *
 * Studio tasks get a project of their own, deleted afterwards. VisLab tasks change the real looks,
 * so each is undone afterwards and checked to be back as it was ({@code restored}). Only free
 * models run, and only on OpenRouter (the OpenAI accounts on the phone cost money).
 */
public class JoybotBenchActivity extends Activity {

    private static final String TAG = "JoybotBench";
    private static final long WAIT_S = 360;

    private android.widget.TextView status;
    private volatile boolean stop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new android.widget.TextView(this);
        status.setTextColor(0xFFAAAAAA);
        status.setBackgroundColor(0xFF000000);
        status.setPadding(32, 32, 32, 32);
        status.setText("Joybot benchmark starting…");
        setContentView(status);
        final android.content.Intent in = getIntent();
        new Thread(() -> runAll(in), "joybot-bench").start();
    }

    @Override
    protected void onDestroy() {
        stop = true;
        super.onDestroy();
    }

    private void show(String s) {
        FLog.i(TAG, s);
        runOnUiThread(() -> status.setText(s));
    }

    private void runAll(android.content.Intent in) {
        File dir = getExternalFilesDir("joybot-bench");
        File results = new File(dir, "results.jsonl");
        File done = new File(dir, "done.txt");
        results.delete();
        done.delete();
        String outcome = "finished";
        try {
            if (!AiService.chatUrl(this).contains("openrouter.ai")) {
                throw new IllegalStateException("the AI account in use is not OpenRouter; switch to it first");
            }
            String key = AiKey.get(this);
            if (key.isEmpty()) throw new IllegalStateException("no AI key saved in the app");
            List<String> models = split(in.getStringExtra("models"));
            if (models.isEmpty()) models.add("openrouter/free");
            for (String m : models) {
                if (!m.endsWith(":free") && !m.equals("openrouter/free")) {
                    throw new IllegalStateException(m + " is not a free model");
                }
            }
            List<String> only = split(in.getStringExtra("tasks"));
            int repeat = Math.max(1, in.getIntExtra("repeat", 1));
            long pauseMs = Math.max(0, in.getIntExtra("pause", 5)) * 1000L;
            List<JSONObject> tasks = new ArrayList<>();
            File[] files = new File(dir, "tasks").listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    JSONArray a = new JSONArray(read(f));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject t = a.getJSONObject(i);
                        if (!t.optBoolean("live", true)) continue;
                        if (!only.isEmpty() && !startsWithAny(t.getString("id"), only)) continue;
                        tasks.add(t);
                    }
                }
            }
            int total = tasks.size() * models.size() * repeat, n = 0;
            for (String model : models) {
                for (JSONObject t : tasks) {
                    for (int rep = 1; rep <= repeat; rep++) {
                        if (stop) { outcome = "stopped"; return; }
                        n++;
                        show("Joybot benchmark " + n + "/" + total + "\n" + model + "\n" + t.getString("id"));
                        JSONObject raw = new JSONObject().put("task", t.getString("id")).put("model", model).put("rep", rep);
                        try {
                            if ("vislab".equals(t.optString("room", "studio"))) vislab(t, model, key, raw);
                            else studio(t, model, key, dir, raw);
                        } catch (Throwable e) {
                            FLog.e(TAG, "task failed: " + t.optString("id"), e);
                            raw.put("error", String.valueOf(e));
                        }
                        append(results, raw.toString());
                        if (pauseMs > 0 && n < total) Thread.sleep(pauseMs);
                    }
                }
            }
        } catch (Throwable e) {
            FLog.e(TAG, "benchmark stopped", e);
            outcome = "error: " + e.getMessage();
        } finally {
            try {
                append(done, outcome);
            } catch (Exception ignored) { }
            show("Joybot benchmark " + outcome + ". Results: " + results);
            // Close when done: a screen left open swallowed the next run's start (10-27).
            runOnUiThread(this::finish);
        }
    }

    /** A Studio task on a project of its own, deleted afterwards. */
    private void studio(JSONObject t, String model, String key, File dir, JSONObject raw) throws Exception {
        ProjectStorage st = new ProjectStorage(this);
        String pid = "bench-" + t.getString("id") + "-" + System.currentTimeMillis();
        String fixture = read(new File(dir, "fixtures/" + t.optString("fixture", "studio-basic") + ".project.json"));
        FaditorProject p = st.fromJson(fixture.replace("\"bench-proj\"", "\"" + pid + "\""));
        if (p == null || !st.save(p)) throw new IllegalStateException("the fixture would not load or save");
        try {
            raw.put("before", new JSONObject(st.toJson(st.load(pid))));
            JoybotAgent.Ask ask = ask(t, pid, model, key, IdeaEngine.Room.STUDIO);
            run(ask, raw);
            FaditorProject after = st.load(pid);
            raw.put("after", after == null ? new JSONObject() : new JSONObject(st.toJson(after)));
            raw.put("undoSteps", AIChatState.stepCount(pid));
        } finally {
            AIChatState.drainSteps(pid);
            JoybotAgent.history(JoybotAgent.conversationKey(pid, IdeaEngine.Room.STUDIO)).clear();
            JoybotAgent.clearPendingPlan(pid);
            deleteTree(st.projectDir(pid));
        }
    }

    /** A VisLab task on the real looks, undone afterwards (and checked to be back as it was). */
    private void vislab(JSONObject t, String model, String key, JSONObject raw) throws Exception {
        VisLabSession s = VisLabSession.get(this);
        for (int i = 0; i < 100 && !s.isLoaded(); i++) Thread.sleep(50);
        JSONObject original = visDoc(s);
        int u0 = undoSize(s);
        String look = t.optString("look", "");
        if (!look.isEmpty() && !s.pickLook(look, "Joybot benchmark").ok) throw new IllegalStateException("no look " + look);
        int u1 = undoSize(s);
        try {
            raw.put("before", visDoc(s));
            run(ask(t, null, model, key, IdeaEngine.Room.VISLAB), raw);
            raw.put("after", visDoc(s));
            raw.put("undoSteps", undoSize(s) - u1);
        } finally {
            JoybotAgent.history(JoybotAgent.conversationKey(null, IdeaEngine.Room.VISLAB)).clear();
            for (int i = undoSize(s); i > u0; i--) if (!s.undo()) break;
            raw.put("restored", visDoc(s).toString().equals(original.toString()));
        }
    }

    private JoybotAgent.Ask ask(JSONObject t, String pid, String model, String key, IdeaEngine.Room room) {
        JoybotAgent.Situation sit = new JoybotAgent.Situation();
        sit.room = room;
        sit.selectedId = t.has("selected") ? t.optString("selected") : null;
        sit.playheadMs = t.optLong("playheadMs", -1);
        String words = t.optString("words");
        JoybotAgent.Ask ask = new JoybotAgent.Ask(pid, words, model, key,
                "Joybot: “" + JoybotAgent.shortOf(words) + "”", sit);
        ask.question = HelpBook.isQuestion(words);   // as the chat sets it
        return ask;
    }

    /** Ask, wait for the answer, record how it went. */
    private void run(JoybotAgent.Ask ask, JSONObject raw) throws Exception {
        CountDownLatch over = new CountDownLatch(1);
        final JoybotAgent.Finished[] got = new JoybotAgent.Finished[1];
        JoybotAgent.Listener l = new JoybotAgent.Listener() {
            @Override public void onText(String shown, boolean newBubble) { }
            @Override public void onWorking(String task, int percent) { }
            @Override public void onChecklist(String[] stages) { }
            @Override public void onToolLine(String line) { }
            @Override public void onStep(AIChatState.Step step) { }
            @Override public boolean onFinished(JoybotAgent.Finished f) {
                if (!f.asked.equals(ask.text)) return false;
                got[0] = f;
                over.countDown();
                return true;
            }
        };
        JoybotAgent.attach(l);
        long t0 = System.nanoTime();
        try {
            JoybotAgent.ask(this, ask);
            boolean ended = over.await(WAIT_S, TimeUnit.SECONDS);
            if (!ended) JoybotAgent.cancel(ask.key());
            raw.put("ended", ended);
        } finally {
            JoybotAgent.detach(l);
        }
        raw.put("latencyMs", (System.nanoTime() - t0) / 1_000_000L);
        JoybotAgent.Finished f = got[0];
        if (f == null) return;
        raw.put("reply", f.reply == null ? JSONObject.NULL : f.reply);
        raw.put("ok", f.ok);
        raw.put("steps", f.steps + 1);
        raw.put("tokens", f.tokens);
        raw.put("cost", f.cost);
        raw.put("toolLines", new JSONArray(f.toolLines));
    }

    private static JSONObject visDoc(VisLabSession s) throws Exception {
        JSONObject d = new JSONObject();
        String cur = s.currentLookJson();
        d.put("current", cur == null ? "" : new JSONObject(cur).optString("id"));
        String[] brand = s.brand();
        d.put("brand", brand == null ? "" : String.join(",", brand));
        JSONObject looks = new JSONObject();
        for (String id : s.lookIds()) looks.put(id, new JSONObject(s.lookJson(id)));
        d.put("looks", looks);
        return d;
    }

    private static int undoSize(VisLabSession s) throws Exception {
        java.lang.reflect.Field f = VisLabSession.class.getDeclaredField("undo");
        f.setAccessible(true);
        return ((List<?>) f.get(s)).size();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s != null) for (String p : s.split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    private static boolean startsWithAny(String id, List<String> prefixes) {
        for (String p : prefixes) if (id.startsWith(p)) return true;
        return false;
    }

    private static String read(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int k; (k = in.read(buf)) > 0; ) out.write(buf, 0, k);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void append(File f, String line) throws Exception {
        try (FileOutputStream o = new FileOutputStream(f, true)) {
            o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
