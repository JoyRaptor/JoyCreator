import android.net.Uri;

import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.FaditorProject;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.motion.MotionBlurItem;
import com.fadcam.ui.faditor.motion.MotionBlurModes;
import com.fadcam.ui.faditor.motion.rest.RestFrameRule;
import com.fadcam.ui.faditor.project.ProjectStorage;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;

/**
 * STU-7 §K.3: a Motion Blur card's Off and Export only states.
 *
 * <ul>
 *   <li>the three-flag state a tap changes (and its undo restores) keeps Off = not drawn;</li>
 *   <li>Export only stays enabled for export and leaves the editor's preview;</li>
 *   <li>both flags ride along wherever an object is copied;</li>
 *   <li>both round-trip through the REAL ProjectStorage, written only when true, so a project
 *       without them (and every old file) is byte-identical and opens as before;</li>
 *   <li>the picture-at-rest rule: which resting playheads owe the exact frame.</li>
 * </ul>
 *
 * Run: bash tools/jvm-harness/run-motionmodes.sh (MOTIONMODES_FAULT=... plants a fault that must fail it).
 */
public class MotionBlurModesTest {
    static int fails = 0, passes = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (c) passes++; else fails++;
    }

    static JsonObject timelineJson(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("timeline");
    }

    public static void main(String[] args) throws Exception {
        final File root = new File(System.getProperty("java.io.tmpdir"), "motionmodes-test");
        root.mkdirs();
        ProjectStorage ps = new ProjectStorage(new android.content.Context() {
            @Override public java.io.File getFilesDir() { return root; }
        });

        // 1. the state one tap changes
        MotionBlurModes live = MotionBlurModes.LIVE;
        MotionBlurModes off = live.switched(false);
        check(!off.enabled() && off.off() && off.hasCard() && !off.inPreview(), "a tap turns it off and keeps the card");
        check(off.switched(true).equals(live), "a second tap turns it back on, exactly as it was");
        check(!new MotionBlurModes(true, true, false).enabled(), "off always means not drawn");
        MotionBlurModes exportOnly = live.withExportOnly(true);
        check(exportOnly.enabled() && !exportOnly.inPreview() && exportOnly.hasCard(), "export only: exported, not previewed");
        check(exportOnly.switched(false).switched(true).exportOnly(), "off and on again keeps Export only");
        check(!MotionBlurModes.NONE.hasCard(), "no card before Add and after the cross");

        // 2. the model reads the same way
        SpriteOverlayItem sprite = new SpriteOverlayItem("sp1", "sheet");
        check(!sprite.hasMotionBlurCard() && !sprite.isMotionBlurInPreview(), "a new sprite has no card");
        live.applyTo(sprite);
        check(sprite.isRigidMotionBlurEnabled() && sprite.isMotionBlurInPreview(), "on: drawn in preview and export");
        exportOnly.applyTo(sprite);
        check(sprite.isRigidMotionBlurEnabled() && !sprite.isMotionBlurInPreview(), "export only: export reads enabled, preview does not");
        off.applyTo(sprite);
        check(!sprite.isRigidMotionBlurEnabled() && sprite.hasMotionBlurCard() && sprite.isMotionBlurOff(), "off: nothing drawn, card kept");
        check(MotionBlurModes.of(sprite).equals(off), "the state reads back from the object");

        // 3. copies keep both flags
        SpriteOverlayItem spCopy = sprite.copyWithNewId("sp2");
        check(spCopy.isMotionBlurOff() && !spCopy.isRigidMotionBlurEnabled(), "a sprite copy stays off");
        TextOverlayItem title = new TextOverlayItem("Hi", 0xFFFFFFFF, 0.5f, 0.5f, 0.1f, 0f);
        exportOnly.applyTo(title);
        check(title.copyWithNewId("t2").isMotionBlurExportOnly(), "a title copy stays export only");
        Clip clip = new Clip(Uri.parse("file:///clip.mp4"), 4000);
        off.applyTo(clip);
        check(new Clip(clip).isMotionBlurOff(), "a clip copy stays off");
        Clip pip = new Clip(Uri.parse("file:///pip.mp4"), 4000);
        exportOnly.applyTo(pip);
        check(new Clip(pip, "pip2").isMotionBlurExportOnly(), "a clip copy with a new id stays export only");

        // 4. saved, written only when true
        FaditorProject p = new FaditorProject("Modes");
        Timeline tl = p.getTimeline();
        tl.addClip(clip);
        tl.addTextOverlay(title);
        tl.addSpriteOverlay(sprite);
        String saved = ps.toJson(p);
        Timeline back = ps.fromJson(saved).getTimeline();
        Clip clipBack = back.getClip(0);
        check(clipBack.isMotionBlurOff() && !clipBack.isRigidMotionBlurEnabled(), "a clip's Off round-trips");
        TextOverlayItem titleBack = back.getTextOverlays().get(0);
        check(titleBack.isMotionBlurExportOnly() && titleBack.isRigidMotionBlurEnabled(), "a title's Export only round-trips");
        SpriteOverlayItem spriteBack = back.getSpriteOverlays().get(0);
        check(spriteBack.isMotionBlurOff() && spriteBack.hasMotionBlurCard(), "a sprite's Off round-trips");
        JsonObject tj = timelineJson(saved);
        JsonObject sj = tj.getAsJsonArray("spriteOverlays").get(0).getAsJsonObject();
        check(sj.has("motionBlurOff") && !sj.has("motionBlurExportOnly"), "only the true flag is written");

        // 5. a project without them gains no keys; an old file opens as before
        FaditorProject plain = new FaditorProject("Plain");
        SpriteOverlayItem plainSprite = new SpriteOverlayItem("sp3", "sheet");
        live.applyTo(plainSprite);
        plain.getTimeline().addSpriteOverlay(plainSprite);
        String plainJson = ps.toJson(plain);
        check(!plainJson.contains("motionBlurOff") && !plainJson.contains("motionBlurExportOnly"), "an ordinary card writes no new keys");
        SpriteOverlayItem old = ps.fromJson(plainJson).getTimeline().getSpriteOverlays().get(0);
        check(old.isRigidMotionBlurEnabled() && !old.isMotionBlurOff() && !old.isMotionBlurExportOnly(), "an old file opens on, live");

        // 6. a file saying both "on" and "off" opens off; junk values are ignored
        JsonObject both = JsonParser.parseString(plainJson).getAsJsonObject();
        JsonObject bs = timelineJson(both.toString()).getAsJsonArray("spriteOverlays").get(0).getAsJsonObject();
        bs.addProperty("motionBlurOff", true);
        bs.addProperty("motionBlurExportOnly", "yes");
        JsonObject bothTl = both.getAsJsonObject("timeline");
        bothTl.getAsJsonArray("spriteOverlays").set(0, bs);
        SpriteOverlayItem bothBack = ps.fromJson(both.toString()).getTimeline().getSpriteOverlays().get(0);
        check(bothBack.isMotionBlurOff() && !bothBack.isRigidMotionBlurEnabled(), "Off wins over an old on");
        check(!bothBack.isMotionBlurExportOnly(), "a junk Export only value reads as off");

        // 7. the picture at rest
        FaditorProject rest = new FaditorProject("Rest");
        SpriteOverlayItem mover = new SpriteOverlayItem("mover", "sheet");
        mover.setTimeRange(0, 2000);
        live.applyTo(mover);
        rest.getTimeline().addSpriteOverlay(mover);
        Timeline rt = rest.getTimeline();
        check(RestFrameRule.needsFrame(rt, 1000, i -> false), "a live blur the preview did not draw gets the exact frame");
        check(!RestFrameRule.needsFrame(rt, 1000, i -> i == mover), "skipped when the live path drew it");
        check(!RestFrameRule.needsFrame(rt, 5000, i -> false), "skipped when it is not on screen");
        exportOnly.applyTo(mover);
        check(!RestFrameRule.needsFrame(rt, 1000, i -> false), "skipped for export only");
        off.applyTo(mover);
        check(!RestFrameRule.needsFrame(rt, 1000, i -> false), "skipped when off");
        live.applyTo(mover);
        mover.setRigidMotionShutter(mover.getRigidMotionShutter().withMix(0));
        check(!RestFrameRule.needsFrame(rt, 1000, i -> false), "skipped when the blur changes nothing (Mix 0)");

        // 8. the editor's view of a copy: export only drawn sharp; the held object drawn blurred
        SpriteOverlayItem heavy = new SpriteOverlayItem("heavy", "sheet");
        exportOnly.applyTo(heavy);
        SpriteOverlayItem held = new SpriteOverlayItem("held", "sheet");
        off.applyTo(held);
        FaditorProject view = new FaditorProject("View");
        view.getTimeline().addSpriteOverlay(heavy);
        view.getTimeline().addSpriteOverlay(held);
        RestFrameRule.forEditor(view.getTimeline(), "held");
        check(!heavy.isRigidMotionBlurEnabled(), "an export only object is sharp in the editor's frame");
        check(held.isRigidMotionBlurEnabled() && !held.isMotionBlurOff(), "the held Off object is drawn with its blur");

        System.out.println(passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
