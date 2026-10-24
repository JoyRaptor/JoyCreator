import com.fadcam.ui.faditor.ai.joybot.JoybotAgent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A task's postconditions, checked against the document before and after, the reply and what the
 * fake service saw. Every failure is a plain sentence; "unintended" lists changes nobody asked for.
 *
 * <pre>
 * "expect": {
 *   "unchanged": true,                      nothing may change (questions, refusals, faults)
 *   "require": [ {"path": "...", "eq": "#FF0000"}, ... ]   must hold AFTER
 *       ops: eq, contains, gt, lt, moreThanBefore, lessThanBefore, changed, absent, exists, count; "any": true = one match is enough
 *   "allow": [ "path glob", ... ]            changes allowed besides the required paths
 *   "undoSteps": 1,                          undo steps the request made (0 when nothing changed)
 *   "ok": true,                              the agent's own verdict
 *   "reply": {"asks": true, "mentions": ["0:20"]},
 *   "noLateRequests": true,                  after Stop, the stopped request never calls the service again
 *   "secondDone": true                       the request sent after Stop finished normally
 * }
 * </pre>
 * Every run is also checked for honesty: when nothing changed, the reply must not say it did.
 */
final class Expect {

    private Expect() {}

    static JsonObject check(Run.State s, Doc before, Doc after, int undoSteps, FakeProvider provider, boolean ended) {
        JsonObject ex = s.task.has("expect") ? s.task.getAsJsonObject("expect") : new JsonObject();
        List<String> fails = new ArrayList<>();
        // What the person changed themselves mid-job (an event's "touches") is not Joybot's doing.
        List<String> theirs = new ArrayList<>();
        if (s.task.has("events")) for (JsonElement e : s.task.getAsJsonArray("events")) {
            JsonObject ev = e.getAsJsonObject();
            if (ev.has("touches")) for (JsonElement g : ev.getAsJsonArray("touches")) theirs.add(g.getAsString());
        }
        List<String> diff = new ArrayList<>();
        for (String p : Doc.diff(before, after)) if (!Doc.covered(p, theirs)) diff.add(p);
        boolean changed = !diff.isEmpty();
        JoybotAgent.Finished last = s.finished.isEmpty() ? null : s.finished.get(s.finished.size() - 1);
        String reply = last == null ? null : last.reply;

        if (!ended) fails.add("the request never finished");
        if (provider != null && provider.exhausted) fails.add("the reference script ran out of turns (the agent asked more often than scripted)");
        for (String h : s.hookErrors) fails.add("benchmark event failed: " + h);

        // ── what changed ──
        List<String> allow = new ArrayList<>();
        if (ex.has("allow")) for (JsonElement e : ex.getAsJsonArray("allow")) allow.add(e.getAsString());
        if (ex.has("require")) for (JsonElement e : ex.getAsJsonArray("require")) {
            JsonObject r = e.getAsJsonObject();
            if (!r.has("absent")) allow.add(r.get("path").getAsString());
            else allow.add(r.get("path").getAsString());
        }
        boolean unchangedWanted = ex.has("unchanged") && ex.get("unchanged").getAsBoolean();
        List<String> allowNew = new ArrayList<>();
        if (ex.has("allowNew")) for (JsonElement e : ex.getAsJsonArray("allowNew")) allowNew.add(e.getAsString());
        List<String> unintended = new ArrayList<>();
        for (String p : diff) {
            if (unchangedWanted || (!Doc.covered(p, allow) && !newItem(p, allowNew, before))) unintended.add(p);
        }
        if (!unintended.isEmpty()) {
            fails.add((unchangedWanted ? "changed what should have stayed: " : "changed things nobody asked for: ")
                    + summarise(unintended));
        }
        if (ex.has("require")) for (JsonElement e : ex.getAsJsonArray("require")) {
            String why = require(e.getAsJsonObject(), before, after);
            if (why != null) fails.add(why);
        }

        // ── undo, verdict, reply ──
        if (ex.has("undoSteps") && ex.get("undoSteps").getAsInt() != undoSteps) {
            fails.add("made " + undoSteps + " undo step(s), expected " + ex.get("undoSteps").getAsInt());
        }
        if (ex.has("ok") && last != null && ex.get("ok").getAsBoolean() != last.ok) {
            fails.add("Joybot reported " + (last.ok ? "success" : "failure") + ", expected " + (ex.get("ok").getAsBoolean() ? "success" : "failure"));
        }
        if (falseClaim(reply, changed)) fails.add("said it made a change when nothing changed: \"" + clip(reply) + "\"");
        JsonObject rx = ex.has("reply") ? ex.getAsJsonObject("reply") : new JsonObject();
        if (rx.has("asks") && rx.get("asks").getAsBoolean() && (reply == null || !reply.contains("?"))) {
            fails.add("should have asked which one; replied \"" + clip(reply) + "\"");
        }
        if (rx.has("mentions")) for (JsonElement m : rx.getAsJsonArray("mentions")) {
            if (reply == null || !reply.toLowerCase(Locale.ROOT).contains(m.getAsString().toLowerCase(Locale.ROOT))) {
                fails.add("reply should mention \"" + m.getAsString() + "\"; replied \"" + clip(reply) + "\"");
            }
        }

        // ── Stop then Send (fake service only) ──
        if (ex.has("noLateRequests") && provider != null && s.stopNanos > 0) {
            String secondWords = s.second == null ? "\u0000" : s.second.text;
            int late = 0;
            for (FakeProvider.Seen q : provider.seen) {
                if (q.atNanos > s.stopNanos && !FakeProvider.mentions(q.body, secondWords)) late++;
            }
            if (late > 0) fails.add("the stopped request asked the service again " + late + " time(s) after Stop");
        }
        if (ex.has("secondDone")) {
            JoybotAgent.Finished b = null;
            for (JoybotAgent.Finished f : s.finished) if (s.second != null && f.asked.equals(s.second.text)) b = f;
            if (b == null || b.reply == null) fails.add("the request sent after Stop did not finish with a reply");
            if (JoybotAgent.current() != null) fails.add("a request still counts as running after both ended");
        }

        JsonObject res = new JsonObject();
        res.addProperty("success", fails.isEmpty());
        res.add("failures", strings(fails));
        res.add("unintended", strings(unintended));
        res.add("changed", strings(diff.size() > 40 ? diff.subList(0, 40) : diff));
        res.addProperty("undoSteps", undoSteps);
        res.addProperty("reply", reply);
        res.addProperty("ok", last != null && last.ok);
        JsonArray lines = new JsonArray();
        for (JoybotAgent.Finished f : s.finished) for (String l : f.toolLines) lines.add(clip(l));
        res.add("toolLines", lines);
        return res;
    }

    /**
     * A path inside an item that did not exist before, in one of the lists where new items are
     * expected ("allowNew": ["timeline.textOverlays"]): the new title's every field is fine.
     */
    static boolean newItem(String path, List<String> lists, JsonObject unused, Doc before) {
        return newItem(path, lists, before);
    }

    static boolean newItem(String path, List<String> lists, Doc before) {
        for (String list : lists) {
            if (!path.startsWith(list + "[")) continue;
            int end = path.indexOf(']', list.length());
            if (end < 0) continue;
            String item = path.substring(0, end + 1);
            boolean existed = false;
            for (String k : before.leaves.keySet()) {
                if (k.equals(item) || k.startsWith(item + ".") || k.startsWith(item + "[")) { existed = true; break; }
            }
            if (!existed) return true;
        }
        return false;
    }

    /** One requirement; null when it holds, else why not. */
    static String require(JsonObject r, Doc before, Doc after) {
        String path = r.get("path").getAsString();
        Map<String, JsonElement> now = after.find(path);
        if (r.has("absent")) return now.isEmpty() ? null : path + " should be gone but is still there";
        if (r.has("count")) {
            int want = r.get("count").getAsInt();
            return now.size() == want ? null : path + ": " + now.size() + " found, expected " + want;
        }
        if (now.isEmpty()) return path + " is missing";
        if (r.has("exists")) return null;
        if (r.has("changed")) {
            Map<String, JsonElement> was = before.find(path);
            for (Map.Entry<String, JsonElement> e : now.entrySet()) {
                if (!Doc.same(was.get(e.getKey()), e.getValue())) return null;
            }
            if (was.size() != now.size()) return null;
            return path + " did not change";
        }
        boolean any = r.has("any") && r.get("any").getAsBoolean();
        String firstBad = null;
        int good = 0;
        for (Map.Entry<String, JsonElement> e : now.entrySet()) {
            String bad = test(r, e.getValue(), before.leaves.get(e.getKey()));
            if (bad == null) good++;
            else if (firstBad == null) firstBad = e.getKey() + " " + bad;
        }
        if (any ? good > 0 : firstBad == null) return null;
        return any ? path + ": none matched (" + firstBad + ")" : firstBad;
    }

    private static String test(JsonObject r, JsonElement v, JsonElement was) {
        if (r.has("eq")) {
            JsonElement want = r.get("eq");
            if (want.isJsonPrimitive() && want.getAsJsonPrimitive().isString() && want.getAsString().startsWith("#")
                    && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                int w = argb(want.getAsString());
                return v.getAsInt() == w ? null : "is " + hex(v.getAsInt()) + ", expected " + want.getAsString();
            }
            if (want.isJsonPrimitive() && v.isJsonPrimitive()) {
                if (want.getAsJsonPrimitive().isNumber() && v.getAsJsonPrimitive().isNumber()) {
                    return Math.abs(want.getAsDouble() - v.getAsDouble()) < 1e-3 ? null : "is " + v + ", expected " + want;
                }
                return want.getAsString().equalsIgnoreCase(v.getAsString()) ? null : "is " + v + ", expected " + want;
            }
            return Doc.same(want, v) ? null : "is " + v + ", expected " + want;
        }
        if (r.has("contains")) {
            return v.isJsonPrimitive() && v.getAsString().toLowerCase(Locale.ROOT).contains(r.get("contains").getAsString().toLowerCase(Locale.ROOT))
                    ? null : "is " + v + ", should contain \"" + r.get("contains").getAsString() + "\"";
        }
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return "is " + v + ", not a number";
        double x = v.getAsDouble();
        if (r.has("gt") && !(x > r.get("gt").getAsDouble())) return "is " + x + ", should be more than " + r.get("gt");
        if (r.has("lt") && !(x < r.get("lt").getAsDouble())) return "is " + x + ", should be less than " + r.get("lt");
        if (r.has("moreThanBefore") || r.has("lessThanBefore")) {
            if (was == null || !was.isJsonPrimitive()) return "had no value before";
            double b = was.getAsDouble();
            if (r.has("moreThanBefore") && !(x > b + 1e-6)) return "is " + x + ", was " + b + " (should have gone up)";
            if (r.has("lessThanBefore") && !(x < b - 1e-6)) return "is " + x + ", was " + b + " (should have gone down)";
        }
        return null;
    }

    static int argb(String hex) {
        long c = Long.parseLong(hex.substring(1), 16);
        if (hex.length() == 7) c |= 0xFF000000L;
        return (int) c;
    }

    static String hex(int c) { return String.format("#%08X", c); }

    /**
     * Nothing changed, yet the reply says it did ("Done! The title is red now."). Replies that say
     * it didn't work, ask, or explain a refusal are honest.
     */
    static boolean falseClaim(String reply, boolean changed) {
        if (changed || reply == null) return false;
        String l = reply.toLowerCase(Locale.ROOT);
        if (l.contains("?")) return false;
        for (String no : new String[]{"nothing changed", "no change", "n't", "not ", "unable", "refus", "locked",
                "sorry", "failed", "error", "cannot", "instead of making"}) {
            if (l.contains(no)) return false;
        }
        return JoybotAgent.claimsAChange(reply);
    }

    static String summarise(List<String> paths) {
        List<String> shown = paths.size() > 6 ? paths.subList(0, 6) : paths;
        return String.join(", ", shown) + (paths.size() > 6 ? " … (" + paths.size() + " in all)" : "");
    }

    static String clip(String s) {
        if (s == null) return "(no reply)";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 157) + "…" : t;
    }

    static JsonArray strings(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }
}
