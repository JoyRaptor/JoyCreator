import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.ClipClock;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Job STU-5, the editor side of the timeline clock (code review 2026-11-11, section A).
 *
 * D9: one rounding rule; a speeded clip split again and again keeps its length.
 * D7: the later clip owns a cut point.
 * D2: caption-style keys are on the timeline ruler; an old file (keys in source ms) converts once.
 *
 * Usage:  bash tools/jvm-harness/run-editorclock.sh
 */
public class EditorClockTest {

    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static void eq(long got, long want, String what) {
        check(got == want, what + " (got " + got + ", want " + want + ")");
    }

    static File root;

    static ProjectStorage storage() {
        return new ProjectStorage(new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        });
    }

    static File projectFile(String id) {
        return new File(root, "faditor/projects/" + id + "/project.json");
    }

    // ── D9 ─────────────────────────────────────────────────────────────────────────────────

    static void rounding() {
        eq(ClipClock.sourceToLocalMs(1000, 1.5f), 667, "1000 source ms at 1.5x rounds to 667");
        eq(ClipClock.sourceToLocalMs(1001, 2f), 501, "1001 at 2x rounds half up");
        eq(ClipClock.sourceToLocalMs(1234, 1f), 1234, "normal speed is the identity");
        eq(ClipClock.localToSourceMs(667, 1.5f), 1001, "667 timeline ms at 1.5x -> 1001 (1000.5 rounds up)");
        eq(ClipClock.sourceToLocalMs(500, 0f), 500, "a zero speed leaves the time alone");

        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(1000);
        c.setSpeedMultiplier(1.5f);
        eq(c.getTrimmedDurationMs(), 667, "a clip's length rounds like TimeMap.clipLocalMsAt");
        eq(c.getTrimmedDurationMs(),
                com.fadcam.ui.faditor.model.TimeMap.clipLocalMsAt(c, 1000),
                "same answer as TimeMap");
    }

    /** Split the LAST piece over and over; the pieces must always add up to the whole. */
    static void splitsKeepTheLength(float speed, long outMs, long step) {
        Timeline tl = new Timeline();
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(outMs);
        c.setSpeedMultiplier(speed);
        tl.addClip(c);
        long whole = c.getTrimmedDurationMs();
        int splits = 0;
        // odd steps on purpose: a cut that is not a whole timeline ms is where a ms used to go
        for (int guard = 0; guard < 400; guard++) {
            int last = tl.getClipCount() - 1;
            Clip tail = tl.getClip(last);
            long at = tail.getInPointMs() + step + (guard % 3);
            int r = tl.splitAt(last, at);
            if (r < 0) break;
            splits++;
            long sum = 0;
            for (int i = 0; i < tl.getClipCount(); i++) sum += tl.getClip(i).getTrimmedDurationMs();
            if (sum != whole) {
                check(false, "speed " + speed + " after " + splits + " splits the pieces add to "
                        + sum + ", the clip was " + whole);
                return;
            }
        }
        check(splits >= 20, "speed " + speed + " really split " + splits + " times");
        check(true, "speed " + speed + " kept " + whole + " ms over " + splits + " splits");
    }

    static void normalSpeedCutNeverMoves() {
        for (long w = 200; w < 5000; w += 37) {
            eq(ClipClock.snapCut(0, 10_000, 1f, w), w, "normal speed: the cut at " + w + " stays");
        }
    }

    // ── D7 ─────────────────────────────────────────────────────────────────────────────────

    static void laterClipOwnsTheCut() {
        long[] lens = {1000, 2000, 500};
        eq(ClipClock.spanIndexAt(lens, 0), 0, "start belongs to the first clip");
        eq(ClipClock.spanIndexAt(lens, 999), 0, "just before a cut: the earlier clip");
        eq(ClipClock.spanIndexAt(lens, 1000), 1, "exactly on a cut: the LATER clip");
        eq(ClipClock.spanIndexAt(lens, 3000), 2, "the second cut: the later clip");
        eq(ClipClock.spanIndexAt(lens, 3500), 2, "the very end belongs to the last clip");
        eq(ClipClock.spanIndexAt(lens, 99999), 2, "past the end: the last clip");
        eq(ClipClock.spanIndexAt(new long[0], 5), -1, "no clips: no answer");
    }

    // ── D2 ─────────────────────────────────────────────────────────────────────────────────

    /** Visit every JSON object that has a captionStyleKeyframes array. */
    interface Visit { void on(JsonObject clipJson); }

    static void walk(JsonElement e, Visit v) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("captionStyleKeyframes")) v.on(o);
            for (java.util.Map.Entry<String, JsonElement> en : o.entrySet()) walk(en.getValue(), v);
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) walk(x, v);
        }
    }

    static JsonElement readJson(File f) throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    static void writeJson(File f, JsonElement e) throws Exception {
        Files.write(f.toPath(), e.toString().getBytes(StandardCharsets.UTF_8));
    }

    static long firstKeyTime(Clip c, int i) { return c.getCaptionStyleKeyframes().get(i).timeMs; }

    static Clip onlyClip(FaditorProject p) { return p.getTimeline().getClip(0); }

    static FaditorProject withKeys(float speed) {
        FaditorProject p = new FaditorProject("keys");
        Clip c = new Clip(android.net.Uri.parse("file:///sdcard/Movies/a.mp4"), 60_000L);
        c.setInPointMs(0);
        c.setOutPointMs(40_000L);
        c.setSpeedMultiplier(speed);
        c.addOrUpdateCaptionStyleKeyframe(0, "pop");
        c.addOrUpdateCaptionStyleKeyframe(3000, "zoom");
        p.getTimeline().addClip(c);
        return p;
    }

    /** Make the saved file look like one an older build wrote: no clock tag, times in source ms. */
    static void makeOld(File f, long[] sourceMs) throws Exception {
        JsonElement root = readJson(f);
        final int[] seen = {0};
        walk(root, clip -> {
            clip.remove("captionStyleKeyClock");
            JsonArray a = clip.getAsJsonArray("captionStyleKeyframes");
            for (int i = 0; i < a.size(); i++) {
                a.get(i).getAsJsonObject().addProperty("t", sourceMs[i]);
            }
            seen[0]++;
        });
        check(seen[0] == 1, "found the clip's key array to age (" + seen[0] + ")");
        writeJson(f, root);
    }

    static void keysFollowTheRuler() throws Exception {
        root = Files.createTempDirectory("editorclock").toFile();

        // A file written by this build: times are timeline ms, loading never converts them.
        {
            FaditorProject p = withKeys(2f);
            check(storage().save(p), "new file saved");
            String json = new String(Files.readAllBytes(projectFile(p.getId()).toPath()), StandardCharsets.UTF_8);
            check(json.replace(" ", "").contains("\"captionStyleKeyClock\":\"timeline\""), "new file carries the clock tag");
            Clip back = onlyClip(storage().load(p.getId()));
            eq(firstKeyTime(back, 1), 3000, "new file, 2x: the key is read as written");
        }

        // An OLD file at 2x: source ms 6000 is timeline ms 3000. Converts once.
        {
            FaditorProject p = withKeys(2f);
            check(storage().save(p), "old-file test: saved");
            makeOld(projectFile(p.getId()), new long[]{0, 6000});
            FaditorProject loaded = storage().load(p.getId());
            Clip c = onlyClip(loaded);
            eq(firstKeyTime(c, 0), 0, "old file, 2x: first key stays at 0");
            eq(firstKeyTime(c, 1), 3000, "old file, 2x: 6000 source ms becomes 3000 on the ruler");
            // The style shown at a given timeline moment is the same as the old build showed at
            // the matching SOURCE moment.
            check("zoom".equals(c.captionStyleAtClipMs(3000)), "old file, 2x: zoom from timeline 3000");
            check("pop".equals(c.captionStyleAtClipMs(2999)), "old file, 2x: still pop just before");

            // Save, load again: not converted a second time.
            check(storage().save(loaded), "old file: saved again");
            String json = new String(Files.readAllBytes(projectFile(p.getId()).toPath()), StandardCharsets.UTF_8);
            check(json.replace(" ", "").contains("\"captionStyleKeyClock\":\"timeline\""), "re-saved file carries the tag");
            FaditorProject againP = storage().load(p.getId());
            eq(firstKeyTime(onlyClip(againP), 1), 3000, "reload after save: still 3000, not 1500");
            check(storage().save(againP), "third save");
            eq(firstKeyTime(onlyClip(storage().load(p.getId())), 1), 3000, "and once more");
        }

        // An old file at 1.5x rounds the way the rest of the clock does.
        {
            FaditorProject p = withKeys(1.5f);
            check(storage().save(p), "1.5x: saved");
            makeOld(projectFile(p.getId()), new long[]{0, 1000});
            eq(firstKeyTime(onlyClip(storage().load(p.getId())), 1), 667, "old file, 1.5x: 1000 -> 667");
        }

        // An old file at normal speed: byte for byte the same times.
        {
            FaditorProject p = withKeys(1f);
            check(storage().save(p), "1x: saved");
            makeOld(projectFile(p.getId()), new long[]{0, 3000});
            Clip c = onlyClip(storage().load(p.getId()));
            eq(firstKeyTime(c, 0), 0, "old file, normal speed: key 0 unchanged");
            eq(firstKeyTime(c, 1), 3000, "old file, normal speed: key 1 unchanged");
        }
    }

    public static void main(String[] args) throws Exception {
        rounding();
        splitsKeepTheLength(2f, 60_001, 1500);
        splitsKeepTheLength(1.5f, 60_000, 1301);
        splitsKeepTheLength(1.5f, 59_999, 777);
        splitsKeepTheLength(0.5f, 30_001, 901);
        splitsKeepTheLength(1.37f, 60_000, 1111);
        normalSpeedCutNeverMoves();
        laterClipOwnsTheCut();
        keysFollowTheRuler();
        System.out.println(fails == 0 ? "ALL GREEN (" + passes + "/" + (passes + fails) + ")"
                : "FAILURES: " + fails + " (passed " + passes + ")");
        if (fails > 0) System.exit(1);
    }
}
