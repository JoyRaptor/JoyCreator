import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.WaveformOverlayInstance;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.Map;

/**
 * The optional per-item {@code name} (NAMING_DESIGN_20261014 work item 1), off device.
 *
 * <p>Covers: save/load round trip for every kind (main clip, video layer, text, image, sprite,
 * visualizer); old files with no name load unchanged and re-save WITHOUT a spurious name;
 * {@code name} is independent of {@code Clip.displayName} (the original file name); duplicate
 * and split keep the name; and the name is rendering-neutral (the saved project differs from an
 * unnamed one ONLY by the {@code name} members).
 */
public class ItemNameTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (c) passes++; else fails++;
    }

    static Clip clip(String file, long dur) {
        return new Clip(android.net.Uri.parse("file:///sdcard/Movies/" + file), dur);
    }

    /** A project holding one of every kind; names applied only when {@code named}. */
    static FaditorProject build(boolean named) {
        FaditorProject p = new FaditorProject("Names");
        Timeline tl = p.getTimeline();
        Clip main = clip("IMG_0042.mp4", 10_000);
        main.setDisplayName("IMG_0042.mp4");
        if (named) main.setName("Cat 1");
        tl.addClip(main);
        Clip pip = clip("pip.mp4", 5_000);
        if (named) pip.setName("Cat PiP");
        tl.addOverlayClip(pip);
        TextOverlayItem text = new TextOverlayItem("Hello", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        if (named) text.setName("Title");
        tl.addTextOverlay(text);
        TextOverlayItem img = TextOverlayItem.createImage("file:///sdcard/Pictures/cat.png", 0.5f, 0.5f, 0.3f);
        if (named) img.setName("Cat 2");
        tl.addTextOverlay(img);
        SpriteOverlayItem sp = SpriteOverlayItem.create("sheet-1");
        if (named) sp.setName("Dino 2");
        tl.addSpriteOverlay(sp);
        WaveformOverlayInstance viz = new WaveformOverlayInstance("neon_bars");
        viz.setTimeRange(0, 5_000);
        if (named) viz.setName("Bars");
        tl.addWaveformOverlay(viz);
        return p;
    }

    /** Remove every "name" member anywhere, so named and unnamed saves can be compared. */
    static JsonElement stripNames(JsonElement e) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            o.remove("name");
            for (Map.Entry<String, JsonElement> en : o.entrySet()) stripNames(en.getValue());
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) stripNames(x);
        }
        return e;
    }

    static String withoutClock(String json) {
        return json.replaceAll("\"lastModified\"\\s*:\\s*-?\\d+", "");
    }

    static void diverge(String a, String b) {
        int i = 0;
        while (i < Math.min(a.length(), b.length()) && a.charAt(i) == b.charAt(i)) i++;
        int from = Math.max(0, i - 80);
        System.out.println("      save1: ..." + a.substring(from, Math.min(a.length(), i + 80)).replace("\n", " "));
        System.out.println("      save2: ..." + b.substring(from, Math.min(b.length(), i + 80)).replace("\n", " "));
    }

    public static void main(String[] args) throws Exception {
        final File root = new File(System.getProperty("java.io.tmpdir"), "itemname-test");
        root.mkdirs();
        ProjectStorage ps = new ProjectStorage(new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        });

        // ── 1. Round trip, every kind ────────────────────────────────────────────────────
        FaditorProject p = build(true);
        String save1 = ps.toJson(p);
        FaditorProject q = ps.fromJson(save1);
        check(q != null, "named project reloads");
        if (q == null) { System.out.println("\n1 FAILED (cannot continue)"); System.exit(1); }
        Timeline qt = q.getTimeline();
        check("Cat 1".equals(qt.getClip(0).getName()), "main-track clip name round-trips");
        check("Cat PiP".equals(qt.getOverlayClips().get(0).getName()), "video-layer clip name round-trips");
        check("Title".equals(qt.getTextOverlays().get(0).getName()), "text layer name round-trips");
        check("Cat 2".equals(qt.getTextOverlays().get(1).getName()) && qt.getTextOverlays().get(1).isImage(),
                "image layer name round-trips");
        check("Dino 2".equals(qt.getSpriteOverlays().get(0).getName()), "sprite layer name round-trips");
        check("Bars".equals(qt.getWaveformOverlays().get(0).getName()), "visualizer name round-trips");
        // A first load normalises unrelated things (a video layer gets its lane id), so the
        // stable comparison is load -> save -> load -> save, as in the other round-trip tests.
        String s1 = withoutClock(ps.toJson(q)), s2 = withoutClock(ps.toJson(ps.fromJson(ps.toJson(q))));
        check(s1.equals(s2), "named project: re-save after reload is byte-identical (names stable)");
        if (!s1.equals(s2)) diverge(s1, s2);

        // ── 2. name is NOT displayName ───────────────────────────────────────────────────
        check("IMG_0042.mp4".equals(qt.getClip(0).getDisplayName()),
                "the original file name (displayName) is untouched by naming");
        Clip both = qt.getClip(0);
        both.setDisplayName("other.mp4");
        check("Cat 1".equals(both.getName()), "changing displayName does not change the name");
        both.setName("Cat 9");
        check("other.mp4".equals(both.getDisplayName()), "changing the name does not change displayName");

        // ── 3. Old file: no names anywhere ───────────────────────────────────────────────
        String oldFile = ps.toJson(build(false));
        check(!oldFile.contains("\"name\":\"Cat") && !oldFile.contains("\"name\":\"Title")
                && !oldFile.contains("\"name\":\"Bars") && !oldFile.contains("\"name\":\"Dino"),
                "an unnamed project writes no item names");
        FaditorProject o = ps.fromJson(oldFile);
        check(o != null, "old (no-name) file loads");
        if (o != null) {
            Timeline ot = o.getTimeline();
            check(ot.getClip(0).getName() == null && ot.getOverlayClips().get(0).getName() == null
                            && ot.getTextOverlays().get(0).getName() == null
                            && ot.getTextOverlays().get(1).getName() == null
                            && ot.getSpriteOverlays().get(0).getName() == null
                            && ot.getWaveformOverlays().get(0).getName() == null,
                    "every kind loads with name == null");
            check("IMG_0042.mp4".equals(ot.getClip(0).getDisplayName()), "old file keeps its displayName");
            JsonObject resave = JsonParser.parseString(ps.toJson(o)).getAsJsonObject().getAsJsonObject("timeline");
            check(!resave.getAsJsonArray("clips").get(0).getAsJsonObject().has("name"),
                    "re-saved main clip has no spurious name");
            boolean clean = true;
            for (JsonElement t : resave.getAsJsonArray("textOverlays")) clean &= !t.getAsJsonObject().has("name");
            for (JsonElement t : resave.getAsJsonArray("spriteOverlays")) clean &= !t.getAsJsonObject().has("name");
            for (JsonElement t : resave.getAsJsonArray("waveformOverlays")) clean &= !t.getAsJsonObject().has("name");
            check(clean, "re-saved text / image / sprite / visualizer have no spurious name");
            check(withoutClock(ps.toJson(o)).equals(withoutClock(ps.toJson(ps.fromJson(ps.toJson(o))))),
                    "old file: re-save after reload is byte-identical");
        }

        // ── 4. Hostile values: empty / null / padded name in a file ──────────────────────
        JsonObject hostile = JsonParser.parseString(save1).getAsJsonObject();
        JsonArray clips = hostile.getAsJsonObject("timeline").getAsJsonArray("clips");
        clips.get(0).getAsJsonObject().addProperty("name", "   ");
        hostile.getAsJsonObject("timeline").getAsJsonArray("textOverlays").get(0).getAsJsonObject()
                .add("name", com.google.gson.JsonNull.INSTANCE);
        FaditorProject h = ps.fromJson(hostile.toString());
        check(h != null && h.getTimeline().getClip(0).getName() == null
                        && h.getTimeline().getTextOverlays().get(0).getName() == null,
                "blank or null name in a file loads as no name");
        Clip pad = clip("x.mp4", 1000);
        pad.setName("  Cat 3  ");
        check("Cat 3".equals(pad.getName()), "name is trimmed");
        pad.setName("");
        check(pad.getName() == null, "empty name clears back to the automatic label");

        // ── 5. Duplicate keeps the name, mints a new id ──────────────────────────────────
        Clip src = clip("d.mp4", 4000);
        src.setName("Cat 1");
        Clip dup = new Clip(src);
        check("Cat 1".equals(dup.getName()) && !dup.getId().equals(src.getId()),
                "duplicated clip keeps the name (new id)");
        TextOverlayItem tsrc = TextOverlayItem.createImage("file:///x.png", 0.5f, 0.5f, 0.3f);
        tsrc.setName("Cat 1");
        TextOverlayItem tdup = tsrc.copyWithNewId("new-id");
        check("Cat 1".equals(tdup.getName()) && "new-id".equals(tdup.getId()), "duplicated image/text layer keeps the name");
        SpriteOverlayItem ssrc = SpriteOverlayItem.create("sheet-1");
        ssrc.setName("Dino");
        SpriteOverlayItem sdup = ssrc.copyWithNewId("sprite-copy");
        check("Dino".equals(sdup.getName()), "duplicated sprite keeps the name");
        // The copy is independent: renaming it leaves the original alone.
        sdup.setName("Dino 2");
        check("Dino".equals(ssrc.getName()), "renaming a copy leaves the original's name");

        // ── 6. Split: both halves keep the name ──────────────────────────────────────────
        Timeline st = new Timeline();
        Clip whole = clip("s.mp4", 10_000);
        whole.setName("Cat 1");
        st.addClip(whole);
        int first = st.splitAt(0, 4_000);
        check(first == 0 && st.getClipCount() == 2, "clip splits into two");
        check("Cat 1".equals(st.getClip(0).getName()) && "Cat 1".equals(st.getClip(1).getName()),
                "both split halves keep the name");

        // ── 7. Rendering-neutral: named vs unnamed differ ONLY by the name members ───────
        // (Ids are random, so build one project, save it, then strip names from a reloaded copy.)
        FaditorProject base = build(true);
        String named = ps.toJson(base);
        JsonElement strippedNamed = stripNames(JsonParser.parseString(named));
        for (Clip c : new Clip[]{base.getTimeline().getClip(0), base.getTimeline().getOverlayClips().get(0)}) c.setName(null);
        for (TextOverlayItem t : base.getTimeline().getTextOverlays()) t.setName(null);
        for (SpriteOverlayItem s : base.getTimeline().getSpriteOverlays()) s.setName(null);
        for (WaveformOverlayInstance w : base.getTimeline().getWaveformOverlays()) w.setName(null);
        JsonElement strippedUnnamed = stripNames(JsonParser.parseString(ps.toJson(base)));
        check(strippedNamed.equals(strippedUnnamed),
                "named vs unnamed saves are identical apart from the name members (rendering-neutral)");

        System.out.println("\n" + passes + " passed, " + fails + " failed");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
