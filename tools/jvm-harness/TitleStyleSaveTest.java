import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;

/**
 * A title's bold, italic, underline, letter case and shadow angle / distance survive a save and reopen (they were
 * never written before 2026-11-12, so they reset on every reopen and after a cross-session undo); a plain title's
 * JSON gains no keys (old files stay byte-identical); an unreadable value loses only that style, never the title;
 * and a title wearing a TextLab look round-trips and stamps the project v14.
 *
 * Drives the REAL ProjectStorage toJson / fromJson. Run: bash tools/jvm-harness/run-titlestyle.sh
 */
public class TitleStyleSaveTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (c) passes++; else fails++;
    }

    static FaditorProject project(TextOverlayItem t) {
        FaditorProject p = new FaditorProject("Styles");
        Timeline tl = p.getTimeline();
        tl.addTextOverlay(t);
        return p;
    }

    static JsonObject firstTitle(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("timeline")
                .getAsJsonArray("textOverlays").get(0).getAsJsonObject();
    }

    public static void main(String[] args) throws Exception {
        final File root = new File(System.getProperty("java.io.tmpdir"), "titlestyle-test");
        root.mkdirs();
        ProjectStorage ps = new ProjectStorage(new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        });

        // 1. every style round-trips
        TextOverlayItem t = new TextOverlayItem("Hello", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        t.setBold(true);
        t.setItalic(true);
        t.setUnderline(true);
        t.setTextCase(TextOverlayItem.CASE_ALL_CAPS);
        t.setShadowAngleDeg(135f);
        t.setShadowDistancePx(12f);
        String saved = ps.toJson(project(t));
        TextOverlayItem r = ps.fromJson(saved).getTimeline().getTextOverlays().get(0);
        check(r.isBold(), "bold round-trips");
        check(r.isItalic(), "italic round-trips");
        check(r.isUnderline(), "underline round-trips");
        check(TextOverlayItem.CASE_ALL_CAPS.equals(r.getTextCase()), "letter case round-trips");
        check(r.getShadowAngleDeg() == 135f, "shadow angle round-trips");
        check(r.getShadowDistancePx() == 12f, "shadow distance round-trips");

        // 2. a plain title writes none of the new keys (old files stay byte-identical)
        TextOverlayItem plain = new TextOverlayItem("Plain", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        JsonObject pj = firstTitle(ps.toJson(project(plain)));
        boolean none = true;
        for (String k : new String[]{"bold", "italic", "underline", "textCase", "shadowAngleDeg", "shadowDistancePx", "textLook"}) {
            if (pj.has(k)) none = false;
        }
        check(none, "a plain title gains no keys");
        int stampPlain = JsonParser.parseString(ps.toJson(project(plain))).getAsJsonObject().get("schemaVersion").getAsInt();
        check(stampPlain < 14, "a plain project keeps its old stamp (" + stampPlain + ")");

        // 3. an old file (no keys) opens with the defaults
        TextOverlayItem old = ps.fromJson(ps.toJson(project(plain))).getTimeline().getTextOverlays().get(0);
        check(!old.isBold() && !old.isItalic() && !old.isUnderline()
                && TextOverlayItem.CASE_NONE.equals(old.getTextCase())
                && old.getShadowAngleDeg() == 0f && old.getShadowDistancePx() == 4f, "an old title opens with the defaults");

        // 4. an unreadable value loses that style, never the title
        JsonObject bad = JsonParser.parseString(saved).getAsJsonObject();
        JsonObject bt = bad.getAsJsonObject("timeline").getAsJsonArray("textOverlays").get(0).getAsJsonObject();
        bt.addProperty("shadowAngleDeg", "not a number");
        FaditorProject bp = ps.fromJson(bad.toString());
        check(bp != null && bp.getTimeline().getTextOverlays().size() == 1, "a bad style value keeps the title");
        TextOverlayItem br = bp.getTimeline().getTextOverlays().get(0);
        check(br.isBold() && TextOverlayItem.CASE_ALL_CAPS.equals(br.getTextCase()), "styles read before the bad one are kept");

        // 5. a title wearing a TextLab look round-trips and stamps the project v14
        TextOverlayItem looked = new TextOverlayItem("Titan", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        JsonObject lookJson = JsonParser.parseString("{\"format\":\"joy.text/1\",\"id\":\"dark-steel\",\"name\":\"Dark Steel\","
                + "\"features\":[],\"p\":{\"bevel\":0.05}}").getAsJsonObject();
        cc.joycreator.textlab.TitleLook tl = cc.joycreator.textlab.TitleLook.fromJson(JsonParser.parseString(
                "{\"key\":\"asset:textlab/looks/dark-steel.text.json\",\"name\":\"Dark Steel\",\"look\":" + lookJson
                        + ",\"light\":\"take\",\"stillX\":0.25}"));
        tl.keys.getOrCreate(cc.joycreator.textlab.TitleLook.LIGHT_X).put(500, 0.4f,
                com.fadcam.ui.faditor.keyframe.Easing.LINEAR);
        looked.setTextLook(tl);
        String ls = ps.toJson(project(looked));
        check(JsonParser.parseString(ls).getAsJsonObject().get("schemaVersion").getAsInt() == 14, "a project with a look is stamped 14");
        TextOverlayItem lr = ps.fromJson(ls).getTimeline().getTextOverlays().get(0);
        check(lr.hasTextLook() && "Dark Steel".equals(lr.getTextLook().name)
                && cc.joycreator.textlab.TitleLook.TAKE.equals(lr.getTextLook().light)
                && lr.getTextLook().stillX == 0.25f
                && lr.getTextLook().keys.valueAt(cc.joycreator.textlab.TitleLook.LIGHT_X, 500, 0f) == 0.4f,
                "a title's look, light and keys round-trip");

        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
