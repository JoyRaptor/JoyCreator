import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The results, as the console line per run and the Markdown tables. */
final class Report {

    private Report() {}

    static String line(JsonObject r) {
        return String.format(Locale.US, "%s %-34s %-28s steps %-3s %6d ms  %s",
                r.get("success").getAsBoolean() ? "PASS" : "FAIL", str(r, "task"), shortModel(str(r, "model")),
                num(r, "steps"), (long) num(r, "latencyMs"), r.get("success").getAsBoolean() ? "" : firstFailure(r));
    }

    static String summary(List<JsonObject> rs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<JsonObject>> e : byModel(rs).entrySet()) {
            int ok = 0;
            for (JsonObject r : e.getValue()) if (r.get("success").getAsBoolean()) ok++;
            sb.append(String.format(Locale.US, "%-40s %d/%d passed (%d%%)%n", e.getKey(), ok, e.getValue().size(),
                    Math.round(100.0 * ok / Math.max(1, e.getValue().size()))));
        }
        return sb.toString().trim();
    }

    static String markdown(List<JsonObject> rs, boolean live, int tasks, String stamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(live ? "Live models" : "Fake model (deterministic)").append(" — run ").append(stamp).append("\n\n");
        sb.append("| Model | Tasks | ✅ Succeeded | ⚠️ Changed things not asked for | 🤥 Claimed a change that didn't happen | Avg steps | ")
                .append(live ? "Avg tokens" : "Avg tokens (est.)").append(" | Median time |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<JsonObject>> e : byModel(rs).entrySet()) {
            List<JsonObject> l = e.getValue();
            int ok = 0, unintended = 0, lies = 0;
            double steps = 0, tokens = 0;
            int tokenRuns = 0;
            List<Long> lat = new ArrayList<>();
            for (JsonObject r : l) {
                if (r.get("success").getAsBoolean()) ok++;
                if (r.has("unintended") && r.getAsJsonArray("unintended").size() > 0) unintended++;
                if (firstFailure(r).startsWith("said it made a change") || r.toString().contains("said it made a change")) lies++;
                steps += Math.max(0, num(r, "steps"));
                if (num(r, "tokens") > 0) { tokens += num(r, "tokens"); tokenRuns++; }
                lat.add((long) num(r, "latencyMs"));
            }
            Collections.sort(lat);
            sb.append(String.format(Locale.US, "| %s | %d | %d (%d%%) | %d | %d | %.1f | %s | %.1f s |%n",
                    e.getKey(), l.size(), ok, Math.round(100.0 * ok / Math.max(1, l.size())), unintended, lies,
                    steps / Math.max(1, l.size()), tokenRuns == 0 ? "—" : String.valueOf(Math.round(tokens / tokenRuns)),
                    lat.isEmpty() ? 0 : lat.get(lat.size() / 2) / 1000.0));
        }
        sb.append("\n| Task | What the person says / what goes wrong | ");
        List<String> models = new ArrayList<>(byModel(rs).keySet());
        for (String m : models) sb.append(shortModel(m)).append(" | ");
        sb.append("\n|---|---|");
        for (int i = 0; i < models.size(); i++) sb.append("---|");
        sb.append('\n');
        Map<String, Map<String, List<JsonObject>>> byTask = new LinkedHashMap<>();
        for (JsonObject r : rs) {
            byTask.computeIfAbsent(str(r, "task"), k -> new LinkedHashMap<>())
                    .computeIfAbsent(str(r, "model"), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, Map<String, List<JsonObject>>> t : byTask.entrySet()) {
            JsonObject any = t.getValue().values().iterator().next().get(0);
            sb.append("| `").append(t.getKey()).append("` | ").append(cell(str(any, "title"))).append(" | ");
            for (String m : models) {
                List<JsonObject> runs = t.getValue().get(m);
                if (runs == null) { sb.append("— | "); continue; }
                int ok = 0;
                String why = "";
                for (JsonObject r : runs) {
                    if (r.get("success").getAsBoolean()) ok++;
                    else if (why.isEmpty()) why = firstFailure(r);
                }
                String mark = ok == runs.size() ? "✅" : ok == 0 ? "❌" : "🟡 " + ok + "/" + runs.size();
                sb.append(mark).append(why.isEmpty() ? "" : " " + cell(why)).append(" | ");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static Map<String, List<JsonObject>> byModel(List<JsonObject> rs) {
        Map<String, List<JsonObject>> m = new LinkedHashMap<>();
        for (JsonObject r : rs) m.computeIfAbsent(str(r, "model"), k -> new ArrayList<>()).add(r);
        return m;
    }

    static String firstFailure(JsonObject r) {
        if (!r.has("failures") || r.getAsJsonArray("failures").size() == 0) return "";
        return r.getAsJsonArray("failures").get(0).getAsString();
    }

    static String cell(String s) {
        String t = s.replace("|", "\\|").replace("\n", " ");
        return t.length() > 140 ? t.substring(0, 137) + "…" : t;
    }

    static String shortModel(String m) { return m.length() > 28 ? m.substring(0, 27) + "…" : m; }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? -1 : e.getAsDouble();
    }
}
