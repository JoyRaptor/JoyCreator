import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;
import com.fadcam.ui.faditor.layers.TrackKind;
import com.fadcam.ui.faditor.model.AdjustmentLayer;
import com.fadcam.ui.faditor.model.CompositingSpec;

/**
 * JVM harness for the adjustment-layer model (SPEC_ADJUSTMENT_LAYERS_FX M3).
 *
 * <p><b>The most important assertion here is a single integer.</b>
 * {@code TrackKind.ADJUSTMENT.minSchemaVersion() == 13} is what makes an older build REFUSE a
 * project containing one, instead of mapping the unknown kind to VIDEO and autosaving that
 * coercion back — which permanently changes which lane paints over which, with no error and no
 * way back. That exact failure already shipped once, for {@code LAYER}. The mechanism is
 * reproduced end to end in {@code tasks/schema_adjust_stamp.py}; this pins the constant it all
 * rests on.</p>
 *
 * <p><b>Model only.</b> Lane emission and the shared z seam are NOT tested here and cannot be:
 * {@code Timeline} reaches {@code Clip}, which reaches media3, so it will not load on this
 * harness's deliberately gson-only classpath. Those assertions live in
 * {@code AdjustmentLaneTest} under {@code run-matte.sh}, which already pays for a real Android
 * classpath. Keeping this one pure is what makes it seconds to run.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-adjust.sh}</p>
 */
public class AdjustmentLayerTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        theStampFloor();
        laneMembership();
        timeGating();
        rendersAnything();
        serializationRoundTrip();
        tolerantRead();
        copyIsDeep();
        previewTiers();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── The trap ────────────────────────────────────────────────────────────

    static void theStampFloor() {
        check("ADJUSTMENT declares a schema floor of 13",
                TrackKind.ADJUSTMENT.minSchemaVersion() == 13);
        // A LITERAL, not the running SCHEMA_VERSION: it names the version that first understood
        // the kind, so a later unrelated bump must not drag it along and lock old builds out of
        // files they could read perfectly well.
        check("...and LAYER's floor is untouched by it",
                TrackKind.LAYER.minSchemaVersion() == 11);
        check("...as is every older kind", TrackKind.VIDEO.minSchemaVersion() == 7);
        check("an unknown persisted kind still degrades to VIDEO rather than crashing",
                TrackKind.fromName("NOT_A_KIND") == TrackKind.VIDEO);
        check("ADJUSTMENT parses back by name",
                TrackKind.fromName("ADJUSTMENT") == TrackKind.ADJUSTMENT);
    }

    static void laneMembership() {
        check("an adjustment lane IS a lane — it owns items by layerId",
                TrackKind.ADJUSTMENT.isLane());
        // The whitelist rule: a kind that cannot own a dropped item must stay out of it.
        check("...while a read-only view kind still is not", !TrackKind.CAPTION.isLane());
    }

    // ── Time ────────────────────────────────────────────────────────────────

    static void timeGating() {
        AdjustmentLayer a = layer(1000, 2000);
        check("before the span: inactive", !a.activeAt(999));
        check("at the start: active", a.activeAt(1000));
        check("inside: active", a.activeAt(2500));
        // END IS EXCLUSIVE. Two layers meeting edge to edge must not both be live on the
        // shared frame, or the join would double-apply for exactly one frame.
        check("at the exact end: INACTIVE (the end is exclusive)", !a.activeAt(3000));
        check("after: inactive", !a.activeAt(5000));

        a.setHidden(true);
        check("a hidden layer is never active, even mid-span", !a.activeAt(2500));
    }

    static void rendersAnything() {
        AdjustmentLayer a = layer(0, 1000);
        check("an EMPTY stack renders nothing — it must cost no pass", !a.rendersAnything());
        a.getFx().add("invert");
        check("one card and it renders", a.rendersAnything());

        FxInstance card = a.getFx().cards().get(0);
        card.enabled = false;
        check("a disabled-only stack renders nothing again", !a.rendersAnything());
        card.enabled = true;
        a.setHidden(true);
        check("hidden beats a full stack", !a.rendersAnything());
    }

    // ── Serialization ───────────────────────────────────────────────────────

    static void serializationRoundTrip() {
        AdjustmentLayer a = layer(500, 1500);
        a.setName("Grade everything");
        a.setLayerId("adjustment-1");
        a.getFx().add("gaussian_blur");
        a.getFx().add("levels");
        CompositingSpec.MaskShape m = a.getCompositing().addShape();
        m.cx = 0.3f;
        m.mode = CompositingSpec.MODE_INTERSECT;
        KeyframeSet kf = new KeyframeSet();
        kf.getOrCreate(KeyframeSet.OPACITY).put(500, 0f, Easing.LINEAR);
        kf.getOrCreate(KeyframeSet.OPACITY).put(1500, 1f, Easing.LINEAR);
        a.setTransform(kf);

        AdjustmentLayer r = AdjustmentLayer.fromJson(a.toJson());
        check("round-trip survives at all", r != null);
        if (r == null) return;
        check("round-trip keeps id and lane",
                r.getId().equals(a.getId()) && r.getLayerId().equals("adjustment-1"));
        check("round-trip keeps the span", r.getStartMs() == 500 && r.getDurationMs() == 1500);
        check("round-trip keeps the name", r.getName().equals("Grade everything"));
        check("round-trip keeps the FX stack", r.getFx().size() == 2);
        check("round-trip keeps the mask, mode and all",
                r.getCompositing().masks.size() == 1
                        && r.getCompositing().masks.get(0).isIntersect());
        check("round-trip keeps the opacity animation",
                Math.abs(r.opacityAt(1000) - 0.5f) < 0.01f);
        check("a second trip is stable", r.toJson().toString().equals(a.toJson().toString()));

        // A bare layer is a small object — nothing is written at its default.
        String bare = layer(0, 100).toJson().toString();
        check("a bare layer writes no flags, no compositing and no fx",
                !bare.contains("\"hidden\"") && !bare.contains("\"locked\"")
                        && !bare.contains("\"compositing\"") && !bare.contains("\"fx\""));
    }

    static void tolerantRead() {
        check("an object with no id is rejected rather than half-built",
                AdjustmentLayer.fromJson(com.google.gson.JsonParser
                        .parseString("{\"startMs\":5}").getAsJsonObject()) == null);
        AdjustmentLayer min = AdjustmentLayer.fromJson(com.google.gson.JsonParser
                .parseString("{\"id\":\"x\"}").getAsJsonObject());
        check("an id alone is enough to load", min != null);
        if (min == null) return;
        check("...and everything else takes its default",
                min.getStartMs() == 0 && min.getFx().isEmpty() && !min.isHidden());
        check("opacity with no transform is fully on", min.opacityAt(0) == 1f);
    }

    static void copyIsDeep() {
        AdjustmentLayer a = layer(0, 1000);
        a.getFx().add("invert");
        a.getCompositing().addShape();
        AdjustmentLayer c = a.copy();
        c.getFx().add("levels");
        c.getCompositing().addShape();
        c.setName("changed");
        check("copy does not alias the FX stack", a.getFx().size() == 1);
        check("copy does not alias the compositing spec", a.getCompositing().masks.size() == 1);
        check("copy does not alias scalars", !a.getName().equals("changed"));
    }

    // ── Preview tiers ───────────────────────────────────────────────────────

    /**
     * The tier table, pinned here because it CANNOT be verified on the sandbox phone: the
     * Note 9 is API 29, below RenderEffect's floor of 31, so it is permanently EXPORT_ONLY and
     * no amount of device testing there can exercise the other two branches.
     */
    static void previewTiers() {
        check("API 33+ is the full AGSL chain",
                com.fadcam.ui.faditor.fx.FxPreviewTier.of(33)
                        == com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL);
        check("API 31-32 is partial", com.fadcam.ui.faditor.fx.FxPreviewTier.of(31)
                == com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.PARTIAL
                && com.fadcam.ui.faditor.fx.FxPreviewTier.of(32)
                == com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.PARTIAL);
        check("API 30 and below cannot preview at all",
                com.fadcam.ui.faditor.fx.FxPreviewTier.of(30)
                        == com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.EXPORT_ONLY);
        check("the sandbox phone (API 29) is EXPORT_ONLY — so M5 is unverifiable on it",
                com.fadcam.ui.faditor.fx.FxPreviewTier.of(29)
                        == com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.EXPORT_ONLY);
        check("minSdk (24) does not crash the table",
                com.fadcam.ui.faditor.fx.FxPreviewTier.of(24)
                        == com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.EXPORT_ONLY);

        com.fadcam.ui.faditor.fx.FxEffectDef blur =
                com.fadcam.ui.faditor.fx.FxRegistry.require("gaussian_blur");
        com.fadcam.ui.faditor.fx.FxEffectDef invert =
                com.fadcam.ui.faditor.fx.FxRegistry.require("invert");

        check("tier A previews everything",
                com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL)
                && com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(invert,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL));
        // Deliberately narrow: a "nearly right" grade is the thing nobody notices is wrong
        // until export, so tier B approximates blur and skips the rest rather than faking it.
        check("tier B previews blur only",
                com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.PARTIAL)
                && !com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(invert,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.PARTIAL));
        check("tier C previews nothing",
                !com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.EXPORT_ONLY));

        check("tier A shows NO badge — a badge on every entry is noise",
                com.fadcam.ui.faditor.fx.FxPreviewTier.badge(invert,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL).isEmpty());
        check("tier B badges an unpreviewable effect 'export only'",
                "export only".equals(com.fadcam.ui.faditor.fx.FxPreviewTier.badge(invert,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.PARTIAL)));
        check("tier B badges blur 'approx', because it genuinely is",
                "approx".equals(com.fadcam.ui.faditor.fx.FxPreviewTier.badge(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.PARTIAL)));
        check("tier A has no header note", com.fadcam.ui.faditor.fx.FxPreviewTier
                .headerNote(com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL).isEmpty());
        check("the other tiers explain themselves", !com.fadcam.ui.faditor.fx.FxPreviewTier
                .headerNote(com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.EXPORT_ONLY).isEmpty());

        // EXPORT capability is a different question from preview tier, and nothing to do with
        // the device: a SAMPLER card needs multi-pass FBOs the export renderer does not have.
        check("a SAMPLER effect cannot be exported yet",
                !com.fadcam.ui.faditor.fx.FxPreviewTier.canExport(blur));
        check("a pointwise effect can", com.fadcam.ui.faditor.fx.FxPreviewTier.canExport(invert));
        check("the card says so, even on the best hardware",
                com.fadcam.ui.faditor.fx.FxPreviewTier.cardNote(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL).contains("not rendered"));
        check("...and says nothing when an effect works everywhere",
                com.fadcam.ui.faditor.fx.FxPreviewTier.cardNote(invert,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Tier.FULL).isEmpty());
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static AdjustmentLayer layer(long start, long dur) {
        AdjustmentLayer a = new AdjustmentLayer();
        a.setStartMs(start);
        a.setDurationMs(dur);
        a.setLayerId("adjustment");
        return a;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
