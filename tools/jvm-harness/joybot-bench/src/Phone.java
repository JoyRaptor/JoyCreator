import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live runs ON THE PHONE ({@code --phone}): the app's own saved key is sealed by the phone's
 * keystore, so the runs happen inside the app (the debug-only JoybotBenchActivity) and come back
 * as raw results that the same {@link Expect} checks grade here. Needs the phone lock
 * ({@code tools/phone.ps1 claim joybot-bench …}) and a debug build with that activity installed.
 */
final class Phone {

    private Phone() {}

    static final String PKG = "com.fadcam.beta";
    static final String DIR = "/sdcard/Android/data/" + PKG + "/files/joybot-bench";

    static void run(Map<String, String> a) throws Exception {
        String holder = sh(List.of("powershell", "-NoProfile", "-File", "tools/phone.ps1", "who")).trim().split("\\R")[0];
        if (!holder.equals("joybot-bench") && !a.containsKey("no-lock-check")) {
            System.out.println("The phone is held by \"" + holder + "\". Claim it first: tools/phone.ps1 claim joybot-bench \"Joybot benchmark\"");
            return;
        }
        List<String> models = new ArrayList<>();
        String list = a.getOrDefault("models", "openrouter/free");
        if (list.equals("auto")) models.addAll(LiveModels.freeToolModels(Integer.parseInt(a.getOrDefault("count", "4"))));
        else for (String m : list.split(",")) if (!m.isBlank()) models.add(m.trim());
        for (String m : models) {
            if (!LiveModels.isFree(m)) { System.out.println("Refusing " + m + ": not a free model."); return; }
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        File runs = new File("tools/jvm-harness/out-joybot-runs/phone-" + stamp);
        runs.mkdirs();

        // ── push the corpus, start the runner ──
        adb("shell", "rm", "-rf", DIR);
        adb("shell", "mkdir", "-p", DIR + "/tasks", DIR + "/fixtures");
        for (File f : files("tasks")) adb("push", f.getPath(), DIR + "/tasks/" + f.getName());
        for (File f : files("fixtures")) adb("push", f.getPath(), DIR + "/fixtures/" + f.getName());
        // NEW_TASK | CLEAR_TASK: always a fresh runner. Without it, a runner screen still open from
        // an earlier run took the start as a new intent and nothing ran (10-27).
        List<String> start = new ArrayList<>(List.of("shell", "am", "start", "-f", "0x10008000", "-n",
                PKG + "/com.fadcam.ui.faditor.ai.joybot.JoybotBenchActivity", "--es", "models", String.join(",", models),
                "--ei", "repeat", a.getOrDefault("repeat", "1"), "--ei", "pause", a.getOrDefault("pause", "5")));
        if (a.containsKey("tasks")) { start.add("--es"); start.add("tasks"); start.add(a.get("tasks")); }
        String started = adb(start.toArray(new String[0])).trim();
        System.out.println(started);
        if (started.contains("Error") || started.contains("not started")) {
            System.out.println("The runner did not start on the phone (is a debug build with JoybotBenchActivity installed?).");
            return;
        }
        System.out.println("Running on the phone with " + models + " → " + runs);

        // ── wait, logging the phone's temperature (owner: probe temps while testing) ──
        List<String> temps = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        String done = "";
        int lastCount = -1;
        long lastChange = t0;
        while (done.isEmpty()) {
            FakeProvider.sleep(30_000);
            done = adb("exec-out", "cat", DIR + "/done.txt").trim();
            if (done.contains("No such file")) done = "";
            int count = 0;
            for (String l : adb("exec-out", "cat", DIR + "/results.jsonl").split("\n")) if (l.startsWith("{")) count++;
            String temp = batteryTemp();
            temps.add(((System.currentTimeMillis() - t0) / 1000) + " s: " + temp + " °C, " + count + " done");
            if (count != lastCount) {
                System.out.println("  " + temps.get(temps.size() - 1));
                lastChange = System.currentTimeMillis();
            }
            lastCount = count;
            if (System.currentTimeMillis() - lastChange > 15 * 60_000L) { done = "no result for 15 minutes (PC gave up)"; break; }
            // Keep the phone lock fresh: it goes stale after 30 minutes and a long run outlasts that.
            sh(List.of("powershell", "-NoProfile", "-File", "tools/phone.ps1", "claim", "joybot-bench",
                    "Joybot benchmark: live run on the phone, " + count + " done"));
        }
        System.out.println("phone says: " + done);
        File raw = new File(runs, "phone-results.jsonl");
        adb("pull", DIR + "/results.jsonl", raw.getPath());
        Files.write(new File(runs, "temperatures.txt").toPath(), String.join("\n", temps).getBytes(StandardCharsets.UTF_8));

        List<JsonObject> results = grade(raw);
        Files.write(new File(runs, "results.json").toPath(), JoybotBench.GSON.toJson(results).getBytes(StandardCharsets.UTF_8));
        String md = Report.markdown(results, true, results.size(), stamp)
                + "\nPhone temperature (battery): " + (temps.isEmpty() ? "—" : temps.get(0) + " … " + temps.get(temps.size() - 1)) + "\n";
        File out = a.containsKey("out") && !a.get("out").isEmpty() ? new File(a.get("out")) : new File(runs, "report.md");
        Files.write(out.toPath(), md.getBytes(StandardCharsets.UTF_8));
        for (JsonObject r : results) System.out.println(Report.line(r));
        System.out.println(Report.summary(results));
        System.out.println("report: " + out);
    }

    /** The phone's raw results, graded by the same checks as the PC runs. */
    static List<JsonObject> grade(File raw) throws Exception {
        Map<String, JsonObject> tasks = new LinkedHashMap<>();
        for (JsonObject t : JoybotBench.tasks()) tasks.put(t.get("id").getAsString(), t);
        List<JsonObject> out = new ArrayList<>();
        for (String line : JoybotBench.read(raw).split("\n")) {
            if (line.isBlank()) continue;
            JsonObject r = JsonParser.parseString(line).getAsJsonObject();
            JsonObject t = tasks.get(r.get("task").getAsString());
            JsonObject res;
            if (t == null || !r.has("before") || !r.has("after")) {
                res = new JsonObject();
                res.addProperty("success", false);
                JsonArray f = new JsonArray();
                f.add(r.has("error") ? "the run failed on the phone: " + r.get("error").getAsString() : "no result for this task");
                res.add("failures", f);
            } else {
                Expect.Outcome o = new Expect.Outcome();
                o.task = t;
                o.reply = r.has("reply") && !r.get("reply").isJsonNull() ? r.get("reply").getAsString() : null;
                o.ok = r.has("ok") ? r.get("ok").getAsBoolean() : null;
                if (r.has("toolLines")) for (JsonElement e : r.getAsJsonArray("toolLines")) o.toolLines.add(e.getAsString());
                o.ended = r.has("ended") && r.get("ended").getAsBoolean();
                res = Expect.check(o, new Doc(r.get("before")), new Doc(r.get("after")), r.has("undoSteps") ? r.get("undoSteps").getAsInt() : -1);
                if (r.has("error")) res.getAsJsonArray("failures").add("error on the phone: " + r.get("error").getAsString());
                if (r.has("error")) res.addProperty("success", false);
            }
            for (String k : new String[]{"model", "rep", "latencyMs", "steps", "tokens", "cost", "restored"}) {
                if (r.has(k)) res.add(k, r.get(k));
            }
            res.addProperty("task", r.get("task").getAsString());
            res.addProperty("title", t != null && t.has("title") ? t.get("title").getAsString() : r.get("task").getAsString());
            out.add(res);
        }
        return out;
    }

    static List<File> files(String sub) {
        File[] f = new File(JoybotBench.HOME, sub).listFiles((d, n) -> n.endsWith(".json"));
        List<File> l = new ArrayList<>();
        if (f != null) for (File x : f) l.add(x);
        return l;
    }

    static String batteryTemp() {
        try {
            for (String l : adb("shell", "dumpsys", "battery").split("\n")) {
                l = l.trim();
                if (l.startsWith("temperature:")) return String.valueOf(Integer.parseInt(l.substring(12).trim()) / 10.0);
            }
        } catch (Exception ignored) { }
        return "?";
    }

    static String adb(String... args) throws Exception {
        String sdk = System.getenv().getOrDefault("ANDROID_HOME", System.getProperty("user.home") + "/AppData/Local/Android/Sdk");
        File exe = new File(sdk, "platform-tools/adb.exe");
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.isFile() ? exe.getPath() : "adb");
        for (String s : args) cmd.add(s);
        return sh(cmd);
    }

    static String sh(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        return new String(out, StandardCharsets.UTF_8).replace("\r", "");
    }
}
