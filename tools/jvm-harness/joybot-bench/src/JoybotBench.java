import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Joybot benchmark. The parent process reads the task corpus and runs each task (per model) in
 * a fresh child JVM, so nothing one run leaves in the agent's statics leaks into the next; then it
 * writes the results table. A child ({@code --one}) sets up the starting project or look, asks the
 * REAL {@code JoybotAgent}, waits for its answer and checks the postconditions ({@link Run}).
 */
public final class JoybotBench {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final File HOME = new File(System.getProperty("bench.home", "tools/jvm-harness/joybot-bench"));

    public static void main(String[] argv) throws Exception {
        Map<String, String> a = args(argv);
        if (a.containsKey("one")) {
            int code = 0;
            try {
                Run.child(a);
            } catch (Throwable t) {
                t.printStackTrace();
                code = 2;
            }
            System.exit(code);
        }
        if (a.containsKey("make-fixtures")) {
            android.content.Context ctx = new android.content.Context(new File("tools/jvm-harness/out-joybot-runs/fixtures/files"),
                    new File(System.getProperty("bench.assets")));
            Fixtures.writeAll(ctx, new File(HOME, "fixtures"));
            System.out.println("fixtures written to " + new File(HOME, "fixtures"));
            System.exit(0);
        }
        if (a.containsKey("phone")) {
            Phone.run(a);
            System.exit(0);
        }
        parent(a);
        System.exit(0);
    }

    /** "--live --models a,b" → {live=, models=a,b}. */
    static Map<String, String> args(String[] argv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            if (!argv[i].startsWith("--")) continue;
            String k = argv[i].substring(2);
            String v = i + 1 < argv.length && !argv[i + 1].startsWith("--") ? argv[++i] : "";
            m.put(k, v);
        }
        return m;
    }

    static List<JsonObject> tasks() throws Exception {
        List<JsonObject> out = new ArrayList<>();
        File[] files = new File(HOME, "tasks").listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            JsonArray arr = JsonParser.parseString(read(f)).getAsJsonArray();
            for (JsonElement e : arr) out.add(e.getAsJsonObject());
        }
        return out;
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    static void parent(Map<String, String> a) throws Exception {
        boolean live = a.containsKey("live");
        List<JsonObject> all = tasks();
        List<JsonObject> picked = new ArrayList<>();
        String only = a.getOrDefault("tasks", "");
        for (JsonObject t : all) {
            String id = t.get("id").getAsString();
            if (!only.isEmpty()) {
                boolean hit = false;
                for (String p : only.split(",")) if (!p.isBlank() && id.startsWith(p.trim())) hit = true;
                if (!hit) continue;
            }
            if (live && t.has("live") && !t.get("live").getAsBoolean()) continue;
            picked.add(t);
        }
        List<String> models = new ArrayList<>();
        if (!live) {
            models.add("fake");
        } else {
            String key = System.getenv("OPENROUTER_API_KEY");
            if (key == null || key.isBlank()) {
                System.out.println("Live runs need OPENROUTER_API_KEY in the environment (it is never printed). Nothing run.");
                return;
            }
            String list = a.getOrDefault("models", "auto");
            if (list.equals("auto") || list.isEmpty()) models.addAll(LiveModels.freeToolModels(Integer.parseInt(a.getOrDefault("count", "4"))));
            else for (String m : list.split(",")) if (!m.isBlank()) models.add(m.trim());
            for (String m : models) {
                if (!LiveModels.isFree(m) && !a.containsKey("allow-paid")) {
                    System.out.println("Refusing " + m + ": not a free model. Paid models need the owner's OK (--allow-paid).");
                    return;
                }
            }
        }
        int repeat = Integer.parseInt(a.getOrDefault("repeat", "1"));
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new java.util.Date());
        File runs = new File("tools/jvm-harness/out-joybot-runs/" + stamp);
        runs.mkdirs();
        List<JsonObject> results = new ArrayList<>();
        System.out.println((live ? "LIVE" : "FAKE") + " run: " + picked.size() + " tasks × " + models.size()
                + " model(s) × " + repeat + " → " + runs);
        for (String model : models) {
            for (JsonObject t : picked) {
                for (int r = 1; r <= repeat; r++) {
                    JsonObject res = runChild(runs, t, model, live, r);
                    results.add(res);
                    System.out.println(Report.line(res));
                    if (live && a.containsKey("pause")) FakeProvider.sleep(Long.parseLong(a.get("pause")) * 1000);
                }
            }
        }
        File json = new File(runs, "results.json");
        Files.write(json.toPath(), GSON.toJson(results).getBytes(StandardCharsets.UTF_8));
        String md = Report.markdown(results, live, picked.size(), stamp);
        String outPath = a.getOrDefault("out", "");
        File out = outPath.isEmpty() ? new File(runs, "report.md") : new File(outPath);
        Files.write(out.toPath(), md.getBytes(StandardCharsets.UTF_8));
        System.out.println();
        System.out.println(Report.summary(results));
        System.out.println("results: " + json + "\nreport:  " + out);
    }

    static JsonObject runChild(File runs, JsonObject t, String model, boolean live, int rep) throws Exception {
        String id = t.get("id").getAsString();
        File dir = new File(runs, safe(model) + "/" + id + (rep > 1 ? "-" + rep : ""));
        dir.mkdirs();
        File result = new File(dir, "result.json");
        List<String> cmd = new ArrayList<>(List.of(
                new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-Djdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir", "C:/Temp"),
                "-Dbench.assets=" + System.getProperty("bench.assets"),
                "-Dbench.home=" + HOME.getPath(),
                "@" + System.getProperty("bench.cpfile"),
                "JoybotBench", "--one", id, "--model", model, "--mode", live ? "live" : "fake",
                "--dir", dir.getPath(), "--result", result.getPath()));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(new File(dir, "log.txt"));
        long t0 = System.nanoTime();
        Process p = pb.start();
        long limit = live ? 420 : 90;
        boolean done = p.waitFor(limit, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        JsonObject res;
        if (result.isFile()) {
            res = JsonParser.parseString(read(result)).getAsJsonObject();
        } else {
            res = new JsonObject();
            res.addProperty("task", id);
            res.addProperty("model", model);
            res.addProperty("success", false);
            JsonArray f = new JsonArray();
            f.add(done ? "the run crashed (see " + new File(dir, "log.txt") + ")" : "timed out after " + limit + " s");
            res.add("failures", f);
            res.addProperty("latencyMs", (System.nanoTime() - t0) / 1_000_000L);
        }
        res.addProperty("rep", rep);
        res.addProperty("wing", t.has("room") ? t.get("room").getAsString() : "studio");
        res.addProperty("kind", id.startsWith("host-") ? "host" : "capability");
        res.addProperty("title", t.has("title") ? t.get("title").getAsString() : id);
        return res;
    }

    static String safe(String s) { return s.replaceAll("[^A-Za-z0-9._-]+", "_"); }

    static String today() { return LocalDate.now().toString(); }
}
