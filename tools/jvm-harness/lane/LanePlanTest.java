import com.fadcam.ui.faditor.compositor.lane.LanePlan;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.TextOverlayItem;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.sprite.SpriteOverlayItem;

import java.util.ArrayList;
import java.util.List;

/**
 * C4a (STU-7): the true-order draw list and the "legacy already looks the same" test, on real
 * Studio timelines.
 *
 * <p>Negative controls (they must fail):
 * <ul>
 *   <li>{@code --grouped}: a plan that draws all PiPs and effect layers first, then the rest,
 *       which is the legacy grouping, must fail the true-order checks.</li>
 *   <li>{@code --rule1-only}: a looks-the-same test that only checks "composited items at the
 *       bottom" must wrongly pass a sprite drawn over a text, and so fail here.</li>
 * </ul>
 */
public final class LanePlanTest {
    private static String mutant = "";
    private static int assertions;

    public static void main(String[] args) {
        mutant = args.length == 0 ? "" : args[0];
        defaultProjectKeepsItsLook();
        textMovedUnderPip();
        effectLayerOverText();
        spriteOverText();
        blendedPictureIsNotPlain();
        hiddenThingsAreSkipped();
        scopeOnlyForEffectLayers();
        System.out.println("LanePlanTest: " + assertions + " assertions");
    }

    /** No lane ever moved by hand: PiP and effect layer at the bottom, sprite under text. */
    static void defaultProjectKeepsItsLook() {
        Timeline t = base();
        List<String> order = kinds(plan(t));
        check(order.indexOf("PIP") < order.indexOf("SPRITE")
                && order.indexOf("SPRITE") < order.indexOf("TEXT"), "default lanes: PiP < sprite < text, got " + order);
        check(looksTheSame(t), "a default project can move to the lane compositor unchanged");
    }

    /** The owner orders a text lane under the PiP lane: true order puts it under the PiP. */
    static void textMovedUnderPip() {
        Timeline t = base();
        t.getOrCreateTrackFlags("video").zIndex = 2;
        t.getOrCreateTrackFlags("text").zIndex = 1;
        t.getOrCreateTrackFlags("sprite").zIndex = 3;
        List<String> order = kinds(plan(t));
        check(order.indexOf("TEXT") < order.indexOf("PIP"), "text ordered under the PiP draws under it, got " + order);
        check(!looksTheSame(t), "text under a PiP keeps the legacy route (conservative)");
    }

    /** An effect layer on the top lane changes the text and sprite below it (true order). */
    static void effectLayerOverText() {
        Timeline t = base();
        t.getOrCreateTrackFlags("adjustment").zIndex = 9;
        LanePlan p = plan(t);
        List<String> order = kinds(p);
        int adj = order.lastIndexOf("ADJUSTMENT");
        check(adj == order.size() - 1, "the top-lane effect layer is drawn last, got " + order);
        List<String> scope = new ArrayList<>();
        for (LanePlan.Entry e : p.scopeBelow(adj)) scope.add(e.kind.name());
        check(scope.contains("TEXT") && scope.contains("SPRITE") && scope.contains("PIP"),
                "an effect layer changes everything below it, got " + scope);
        check(!looksTheSame(t), "legacy never grades text: this project keeps the legacy route");
    }

    /** A sprite lane above the text lane: legacy's fixed surfaces draw sprites under text. */
    static void spriteOverText() {
        Timeline t = base();
        t.getOrCreateTrackFlags("sprite").zIndex = 5;
        t.getOrCreateTrackFlags("text").zIndex = 4;
        List<String> order = kinds(plan(t));
        check(order.indexOf("TEXT") < order.indexOf("SPRITE"), "sprite lane above text draws above it, got " + order);
        check(!looksTheSame(t), "sprite over text differs from legacy's sprite-under-text surfaces");
    }

    static void blendedPictureIsNotPlain() {
        Timeline t = base();
        TextOverlayItem pic = new TextOverlayItem("pic", "", 0xFFFFFFFF, 0.5f, 0.5f, 0.2f, 0f);
        pic.setImageUri("content://test/pic.png");
        pic.setLayerId("text");
        pic.setOverlayBlendMode("SCREEN");
        t.addTextOverlay(pic);
        check(kinds(plan(t)).contains("IMAGE"), "a picture is an IMAGE entry");
        check(!looksTheSame(t), "a blended picture keeps the legacy route");
    }

    static void hiddenThingsAreSkipped() {
        Timeline t = base();
        t.getOrCreateTrackFlags("sprite").hidden = true;
        check(!kinds(plan(t)).contains("SPRITE"), "a hidden lane draws nothing");
        Timeline u = base();
        for (TextOverlayItem o : u.getTextOverlays()) o.setHidden(true);
        check(!kinds(plan(u)).contains("TEXT"), "a hidden object draws nothing");
    }

    static void scopeOnlyForEffectLayers() {
        LanePlan p = plan(base());
        int text = kinds(p).indexOf("TEXT");
        boolean threw = false;
        try { p.scopeBelow(text); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "only an effect layer has a scope");
    }

    // ---- helpers --------------------------------------------------------------------------

    /** Spine-free timeline with one of each: PiP, effect layer, sprite, text, on default lanes. */
    static Timeline base() {
        Timeline t = new Timeline();
        Clip pip = new Clip(null, 5000);
        pip.setLayerId("video");
        t.addOverlayClip(pip);
        AdjustmentLayer a = new AdjustmentLayer();
        a.setStartMs(0);
        a.setLayerId("adjustment");
        t.addAdjustmentLayer(a);
        SpriteOverlayItem s = new SpriteOverlayItem("s1", "sheet1");
        s.setLayerId("sprite");
        t.addSpriteOverlay(s);
        TextOverlayItem txt = new TextOverlayItem("t1", "hello", 0xFFFFFFFF, 0.5f, 0.5f, 0.2f, 0f);
        txt.setLayerId("text");
        t.addTextOverlay(txt);
        return t;
    }

    static LanePlan plan(Timeline t) { return LanePlan.of(t); }

    /** Entry kinds bottom to top; the --grouped mutant regroups them the legacy way. */
    static List<String> kinds(LanePlan p) {
        List<String> composited = new ArrayList<>(), rest = new ArrayList<>(), all = new ArrayList<>();
        for (LanePlan.Entry e : p.entries()) {
            all.add(e.kind.name());
            (e.composited() ? composited : rest).add(e.kind.name());
        }
        if (!mutant.equals("--grouped")) return all;
        composited.addAll(rest);
        return composited;
    }

    static boolean looksTheSame(Timeline t) {
        LanePlan p = plan(t);
        if (!mutant.equals("--rule1-only")) return p.legacyLooksTheSame();
        boolean seenRest = false;
        for (LanePlan.Entry e : p.entries()) {
            if (!e.composited()) seenRest = true;
            else if (seenRest) return false;
        }
        return true;
    }

    static void check(boolean ok, String what) {
        assertions++;
        if (!ok) throw new AssertionError(what);
    }
}
