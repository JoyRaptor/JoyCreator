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
        sharedGlSource();

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
     * What the picker, the cards and the panel header say.
     *
     * <p>The device tiers this used to assert are gone. They graded phones by {@code
     * RenderEffect} (API 31) and AGSL (API 33), and the project read that as "the Note 9 cannot
     * preview effects" — which was false, and is the belief this whole area was rebuilt to
     * correct. The preview now compiles the export's own GLSL on every device, so a regression
     * here is the one that matters: it puts "export only" back on a card whose effect the editor
     * is, in fact, showing live.</p>
     */
    static void previewTiers() {
        com.fadcam.ui.faditor.fx.FxEffectDef blur =
                com.fadcam.ui.faditor.fx.FxRegistry.require("gaussian_blur");
        com.fadcam.ui.faditor.fx.FxEffectDef invert =
                com.fadcam.ui.faditor.fx.FxRegistry.require("invert");
        check("the GL renderer is the only backend",
                com.fadcam.ui.faditor.fx.FxPreviewTier.usesGl());
        check("a SAMPLER effect previews live — the multi-pass path, not just pointwise",
                com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(blur));
        check("...and so does a pointwise one",
                com.fadcam.ui.faditor.fx.FxPreviewTier.canPreview(invert));
        check("no picker badge on any effect, on any device",
                com.fadcam.ui.faditor.fx.FxPreviewTier.badge(blur).isEmpty()
                && com.fadcam.ui.faditor.fx.FxPreviewTier.badge(invert).isEmpty());
        check("the panel header has nothing to apologise for",
                com.fadcam.ui.faditor.fx.FxPreviewTier.headerNote().isEmpty());
        check("a layer card is clean...",
                com.fadcam.ui.faditor.fx.FxPreviewTier.cardNote(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Subject.LAYER).isEmpty());
        check("...but the per-OBJECT sampler limit still speaks, since that one is real",
                com.fadcam.ui.faditor.fx.FxPreviewTier.cardNote(blur,
                        com.fadcam.ui.faditor.fx.FxPreviewTier.Subject.OBJECT)
                        .contains("adjustment layer"));
    }

    /**
     * Preview and export must compile the SAME text.
     *
     * <p>This is the whole reason {@code FxGlSource} exists. The two renderers each own their
     * pass loop — one drives media3, the other an EGL thread — and that is fine; what cannot
     * differ is the program. If someone re-inlines the composite into either caller, this fails
     * before a device ever sees it.</p>
     */
    static void sharedGlSource() {
        com.fadcam.ui.faditor.fx.FxStack s = new com.fadcam.ui.faditor.fx.FxStack();
        s.add("gaussian_blur");
        com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                com.fadcam.ui.faditor.fx.FxCompiler.plan(s);
        check("a blur stack plans at least one pass", !plan.passes.isEmpty());
        com.fadcam.ui.faditor.fx.FxCompiler.Pass p = plan.passes.get(0);

        String plain = com.fadcam.ui.faditor.fx.FxGlSource.fragment(
                p, com.fadcam.ui.faditor.fx.FxGlSource.KERNEL_HALF, false);
        String composite = com.fadcam.ui.faditor.fx.FxGlSource.fragment(
                p, com.fadcam.ui.faditor.fx.FxGlSource.KERNEL_HALF, true);

        check("both variants are GLSL ES 1.00", plain.startsWith("#version 100")
                && composite.startsWith("#version 100"));
        // The bug the first export A/B found: GLSL ES 1.00 needs declaration before use, so the
        // mask fn and the composite uniforms must land BEFORE main(), not after it.
        check("declarations precede main()",
                composite.indexOf("uniform sampler2D uBaseSampler") < composite.indexOf("void main()")
                && composite.indexOf("fxShapeSd") < composite.indexOf("void main()"));
        check("only the composite variant mixes back over the original",
                composite.contains("uBaseSampler, fxClamp(vFxUv)")
                && composite.contains("mix(base.rgb, c.rgb, amt)")
                && !plain.contains("mix(base.rgb, c.rgb, amt)"));
        check("the composite really replaced the compiler's entry, leaving no stray write",
                !composite.contains("  gl_FragColor = c;\n"));
        check("a sampler pass reads the neighbour offset uniform it was emitted for",
                plain.contains("uTexel"));
        declarationOrder();
    }

    /**
     * {@code fxuvN} → {@code fxRemap} → {@code fxN}, in that order in the SOURCE.
     *
     * <p>GLSL ES 1.00 is declaration-before-use and has no forward declarations in play here, so
     * this ordering is not style — it is whether the program compiles. It did not: {@code
     * fxRemap} was emitted ahead of the {@code fxuvN} bodies it calls, so every Pixelate or
     * Offset stack failed to link with "'fxuv2': no matching overloaded function found". On
     * export that degraded to passthrough with no user-visible sign, which is why it survived a
     * device verification pass that called all twelve effects shipped.</p>
     */
    static void declarationOrder() {
        com.fadcam.ui.faditor.fx.FxStack s = new com.fadcam.ui.faditor.fx.FxStack();
        s.add("pixelate");        // UV_REMAP -> emits fxuvN and fxRemap
        s.add("gaussian_blur");   // SAMPLER  -> its body expands FX_SAMPLE_SRC into fxRemap(...)
        com.fadcam.ui.faditor.fx.FxCompiler.Plan plan =
                com.fadcam.ui.faditor.fx.FxCompiler.plan(s);
        boolean checked = false;
        for (com.fadcam.ui.faditor.fx.FxCompiler.Pass p : plan.passes) {
            String src = com.fadcam.ui.faditor.fx.FxCompiler.emitGlsl(
                    p, com.fadcam.ui.faditor.fx.FxGlSource.KERNEL_HALF);
            int remapDef = src.indexOf("vec2 fxRemap(vec2 uv) {");
            int firstUvDef = src.indexOf("vec2 fxuv");
            if (remapDef < 0 || firstUvDef < 0) continue;
            checked = true;
            check("every fxuvN body is declared before fxRemap calls it",
                    firstUvDef < remapDef);
            // and the call site inside fxRemap must refer to one that now exists above it
            String head = src.substring(0, remapDef);
            int callAt = src.indexOf("uv = fxuv", remapDef);
            if (callAt > 0) {
                String name = src.substring(callAt + 5, src.indexOf('(', callAt));
                check("...specifically " + name,
                        head.contains("vec2 " + name + "(vec2 uv) {"));
            }
        }
        check("a remap pass was actually produced to check", checked);
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
