import com.fadcam.ui.faditor.layers.ObjectPalette;
import com.fadcam.ui.faditor.layers.TimedItem;
import com.fadcam.ui.faditor.layers.TrackKind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proves F-COLOR's table: objects are coloured BY TYPE, from ONE place.
 *
 * <p>The bug this guards is that a hue used to be written out twice — LayerRowRenderer's
 * COLOR_ITEM_* and EditorTimelineView's COLOR_PH_* — and the two copies had already drifted
 * (neither had an IMAGE case, so images silently took VIDEO blue through a default: branch).
 * Every check below is paired with a positive control, so a table that quietly collapsed to
 * one colour, or a lookup that ignored its argument, would fail rather than pass vacuously.</p>
 *
 * <p>Section 7 proves the part that actually matters — an object keeps ITS OWN colour on a
 * foreign lane — using a stub TimedItem that carries only the six payload accessors
 * {@code payloadKindOf} consults. What is still NOT claimed here: the render call sites need a
 * Canvas, so "the row body actually paints this colour" is compile-checked and reviewed only.</p>
 *
 * Run:
 *   javac -nowarn -d tools/jvm-harness/out6 \
 *     tools/jvm-harness/stubs/androidx/annotation/NonNull.java \
 *     $(find tools/jvm-harness/stubs-palette -name '*.java') \
 *     app/src/main/java/com/fadcam/ui/faditor/layers/TrackKind.java \
 *     app/src/main/java/com/fadcam/ui/faditor/layers/ObjectPalette.java \
 *     tools/jvm-harness/ObjectPaletteTest.java
 *   java -cp tools/jvm-harness/out6 ObjectPaletteTest
 */
public class ObjectPaletteTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("PASS  " + what + (detail.isEmpty() ? "" : " -> " + detail)); }
        else { fail++; System.out.println("FAIL  " + what + "  " + detail); }
    }

    static void eqColor(String what, int want, int got) {
        check(what, want == got, String.format("want=#%08X got=#%08X", want, got));
    }

    public static void main(String[] args) {

        // ── 1. Each kind maps to its F-COLOR hue ──────────────────────────────────────────
        eqColor("1a TEXT is purple",        ObjectPalette.TEXT,       ObjectPalette.forKind(TrackKind.TEXT));
        eqColor("1b STICKER rides TEXT",    ObjectPalette.TEXT,       ObjectPalette.forKind(TrackKind.STICKER));
        eqColor("1c VISUALIZER is pink",    ObjectPalette.VISUALIZER, ObjectPalette.forKind(TrackKind.VISUALIZER));
        eqColor("1d SPRITE is amber",       ObjectPalette.SPRITE,     ObjectPalette.forKind(TrackKind.SPRITE));
        eqColor("1e IMAGE is teal",         ObjectPalette.IMAGE,      ObjectPalette.forKind(TrackKind.IMAGE));
        eqColor("1f AUDIO is green",        ObjectPalette.AUDIO,      ObjectPalette.forKind(TrackKind.AUDIO));
        eqColor("1g CAPTION is gold",       ObjectPalette.CAPTION,    ObjectPalette.forKind(TrackKind.CAPTION));
        eqColor("1h VIDEO is blue",         ObjectPalette.VIDEO,      ObjectPalette.forKind(TrackKind.VIDEO));
        eqColor("1i MASTER reads as video", ObjectPalette.VIDEO,      ObjectPalette.forKind(TrackKind.MASTER));

        // ── 2. THE REGRESSION: IMAGE must no longer alias VIDEO ───────────────────────────
        // Both old tables lacked an IMAGE case, so an image took blue via default:.
        check("2a IMAGE is NOT the VIDEO hue any more",
                ObjectPalette.forKind(TrackKind.IMAGE) != ObjectPalette.forKind(TrackKind.VIDEO),
                String.format("image=#%08X video=#%08X",
                        ObjectPalette.forKind(TrackKind.IMAGE), ObjectPalette.forKind(TrackKind.VIDEO)));
        // Positive control for that assertion: a kind that IS meant to share a hue still does,
        // so 2a is detecting a real distinction rather than "all kinds happen to differ".
        check("2b control: STICKER still SHARES the TEXT hue (sharing is possible)",
                ObjectPalette.forKind(TrackKind.STICKER) == ObjectPalette.forKind(TrackKind.TEXT), "");

        // ── 3. The neutral lane must not colour its contents ──────────────────────────────
        // A LAYER lane holds mixed payloads; its own kind may not stand in for an object's.
        eqColor("3a LAYER lane falls back to VIDEO (it describes nothing)",
                ObjectPalette.VIDEO, ObjectPalette.forKind(TrackKind.LAYER));
        check("3b control: that fallback is NOT the text hue — a text object on a LAYER lane "
                        + "would be visibly miscoloured if resolved from the lane",
                ObjectPalette.forKind(TrackKind.LAYER) != ObjectPalette.TEXT, "");

        // ── 4. The distinct hues really are distinct (no silent collapse) ─────────────────
        Map<String, Integer> hues = new LinkedHashMap<>();
        hues.put("TEXT", ObjectPalette.TEXT);
        hues.put("VIDEO", ObjectPalette.VIDEO);
        hues.put("IMAGE", ObjectPalette.IMAGE);
        hues.put("AUDIO", ObjectPalette.AUDIO);
        hues.put("SPRITE", ObjectPalette.SPRITE);
        hues.put("CAPTION", ObjectPalette.CAPTION);
        hues.put("VISUALIZER", ObjectPalette.VISUALIZER);
        int collisions = 0;
        String[] names = hues.keySet().toArray(new String[0]);
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                if (hues.get(names[i]).equals(hues.get(names[j]))) {
                    collisions++;
                    System.out.println("      collision: " + names[i] + " == " + names[j]);
                }
            }
        }
        check("4a all seven object hues are distinct", collisions == 0, "collisions=" + collisions);

        // ── 5. Every kind is opaque, and body() only changes alpha ────────────────────────
        boolean allOpaque = true;
        for (TrackKind k : TrackKind.values()) {
            if ((ObjectPalette.forKind(k) >>> 24) != 0xFF) { allOpaque = false; break; }
        }
        check("5a every kind's hue is fully opaque", allOpaque, "");

        int text = ObjectPalette.TEXT;
        int textBody = ObjectPalette.body(text);
        check("5b body() preserves RGB",
                (textBody & 0x00FFFFFF) == (text & 0x00FFFFFF),
                String.format("#%08X -> #%08X", text, textBody));
        check("5c body() lowers alpha below opaque",
                (textBody >>> 24) < 0xFF, String.format("alpha=0x%02X", textBody >>> 24));
        // Positive control: withAlpha is actually doing something, not returning its input.
        eqColor("5d control: withAlpha(0x80) sets exactly that alpha",
                (text & 0x00FFFFFF) | 0x80000000, ObjectPalette.withAlpha(text, 0x80));

        // ── 6. Total coverage: no kind falls through to a null/zero colour ────────────────
        int zeroes = 0;
        for (TrackKind k : TrackKind.values()) if (ObjectPalette.forKind(k) == 0) zeroes++;
        check("6a every TrackKind resolves to a real colour", zeroes == 0,
                "kinds=" + TrackKind.values().length + " zeroes=" + zeroes);

        // ── 7. THE ACTUAL BUG: an object keeps its own colour on a FOREIGN lane ───────────
        // The renderer used to hoist baseColorFor(row.getKind()) out of the item loop, so every
        // object wore its LANE's colour. Since the neutral substrate, a lane describes nothing.
        {
            // A text object sitting on each of the lanes it can legally be dropped on.
            TrackKind[] foreignLanes = {
                    TrackKind.LAYER, TrackKind.VIDEO, TrackKind.IMAGE, TrackKind.SPRITE,
            };
            boolean allPurple = true;
            for (TrackKind lane : foreignLanes) {
                if (ObjectPalette.forItem(TimedItem.ofText(), lane) != ObjectPalette.TEXT) {
                    allPurple = false;
                    System.out.println("      text went wrong colour on lane " + lane);
                }
            }
            check("7a a TEXT object stays purple on every foreign lane", allPurple, "");
            // Positive control: resolving the SAME lanes by KIND really does give other colours,
            // so 7a is not passing because every lane happens to be purple anyway.
            boolean lanesDiffer = false;
            for (TrackKind lane : foreignLanes) {
                if (ObjectPalette.forKind(lane) != ObjectPalette.TEXT) { lanesDiffer = true; break; }
            }
            check("7b control: those lanes' OWN colours are not purple — resolving by lane "
                    + "would visibly miscolour the object", lanesDiffer, "");

            eqColor("7c sprite on a TEXT lane is amber, not purple",
                    ObjectPalette.SPRITE, ObjectPalette.forItem(TimedItem.ofSprite(), TrackKind.TEXT));
            eqColor("7d visualizer on a LAYER lane is pink",
                    ObjectPalette.VISUALIZER, ObjectPalette.forItem(TimedItem.ofWaveform(), TrackKind.LAYER));
            eqColor("7e audio item on a VIDEO lane is green",
                    ObjectPalette.AUDIO, ObjectPalette.forItem(TimedItem.ofAudio(), TrackKind.VIDEO));
            eqColor("7f caption on a LAYER lane is gold",
                    ObjectPalette.CAPTION, ObjectPalette.forItem(TimedItem.ofCaption(), TrackKind.LAYER));
            eqColor("7g an image-backed overlay is teal, not text purple",
                    ObjectPalette.IMAGE, ObjectPalette.forItem(TimedItem.ofImage(), TrackKind.TEXT));
            eqColor("7h a PiP (overlay clip) on a LAYER lane is blue",
                    ObjectPalette.VIDEO, ObjectPalette.forItem(TimedItem.ofOverlayClip(), TrackKind.LAYER));

            // Fallback: a payload with no identity of its own DOES defer to the row.
            eqColor("7i a payload-less item falls back to the row kind",
                    ObjectPalette.SPRITE, ObjectPalette.forItem(TimedItem.empty(), TrackKind.SPRITE));
            eqColor("7j a MASTER-spine clip (not an overlay) also defers to the row",
                    ObjectPalette.CAPTION, ObjectPalette.forItem(TimedItem.ofMasterClip(), TrackKind.CAPTION));
            // Control for the fallback pair: the fallback is reading the ARGUMENT, not a constant.
            check("7k control: the same payload-less item yields a DIFFERENT colour on a "
                            + "different row (fallback is live, not hard-coded)",
                    ObjectPalette.forItem(TimedItem.empty(), TrackKind.SPRITE)
                            != ObjectPalette.forItem(TimedItem.empty(), TrackKind.AUDIO), "");
        }

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
