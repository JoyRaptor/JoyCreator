import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;

/**
 * M12 spine ⇄ layer moves (addendum §3A), against the REAL model classes.
 *
 * The list transfer is the easy half. Every check below that matters is about a field whose
 * MEANING changes across the boundary — the four the 2026-08-03 audit found would break silently.
 *
 * Run: bash tools/jvm-harness/run-promote.sh
 */
public class PromoteDemoteTest {

    static int fails = 0, checks = 0;

    static void check(boolean c, String n) {
        checks++;
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void eq(long a, long b, String n) {
        check(a == b, n + "  (got " + a + ", want " + b + ")");
    }

    static Clip clip(long durMs) {
        Clip c = new Clip(null, 600_000);
        c.setInPointMs(0);
        c.setOutPointMs(durMs);
        return c;
    }

    public static void main(String[] a) {
        // Spine: 1000 / 2000 / 500 → starts 0 / 1000 / 3000
        Timeline tl = new Timeline();
        tl.addClip(clip(1000));
        tl.addClip(clip(2000));
        tl.addClip(clip(500));
        String movedId = tl.getClip(1).getId();

        // ── DEMOTE: spine → layer, keeping absolute position ──────────────────────────────
        Clip demoted = tl.demoteToLayer(1, "video");
        check(demoted != null, "demote: returned the clip");
        eq(tl.getClipCount(), 2, "demote: the spine lost it");
        check(tl.getOverlayClips().contains(demoted), "demote: the layer gained it");
        check(demoted.isOverlayClip(), "demote: it now reads as an overlay (layerId non-null)");
        eq(demoted.getOverlayStartMs(), 1000,
                "demote: it kept its ABSOLUTE timeline position (1000), not a reset to 0");
        eq(tl.getClipStartMs(1), 1000, "demote: the spine ripple-closed behind it");

        // THE SILENT ONE: a PiP is mute unless opted in, so without carrying this the clip's
        // audio would simply disappear the moment it left the spine.
        check(demoted.isOverlayAudioEnabled(),
                "demote: audio is carried — the clip does NOT go silent");

        // ── PROMOTE: layer → spine, inverse ───────────────────────────────────────────────
        demoted.setHiddenObject(true);      // legal on a PiP…
        demoted.setLockedObject(true);
        check(tl.promoteToMaster(demoted, 1), "promote: found and moved");
        eq(tl.getClipCount(), 3, "promote: the spine regained it");
        check(!tl.getOverlayClips().contains(demoted), "promote: the layer lost it");
        check(!demoted.isOverlayClip(),
                "promote: layerId is null again — this IS the master-clip signal");
        check(tl.getClip(1).getId().equals(movedId), "promote: it landed at the requested index");

        // …but hidden/locked are honoured for PiPs ONLY, so leaving them set would make a
        // deliberately hidden object silently reappear as a normal spine clip.
        check(!demoted.isHiddenObject(), "promote: hidden cleared (meaningless on the spine)");
        check(!demoted.isLockedObject(), "promote: locked cleared (meaningless on the spine)");

        // ── ROUND TRIP: position must survive both directions ─────────────────────────────
        Timeline tl2 = new Timeline();
        tl2.addClip(clip(1000));
        tl2.addClip(clip(2000));
        tl2.addClip(clip(500));
        long startBefore = tl2.getClipStartMs(1);
        Clip d2 = tl2.demoteToLayer(1, "video");
        tl2.promoteToMaster(d2, 1);
        eq(tl2.getClipStartMs(1), startBefore,
                "round trip: the clip is back where it started");
        eq(tl2.getClipCount(), 3, "round trip: clip count restored");
        check(tl2.getOverlayClips().isEmpty(), "round trip: no overlay left behind");

        // ── Guards ───────────────────────────────────────────────────────────────────────
        check(tl2.demoteToLayer(99, "video") == null, "demote: out-of-range index refused");
        check(tl2.demoteToLayer(-1, "video") == null, "demote: negative index refused");
        check(!tl2.promoteToMaster(clip(100), 0),
                "promote: a clip that is not an overlay of this timeline is refused");

        // Insert index is clamped, not trusted — a drop past the end must not throw.
        Clip d3 = tl2.demoteToLayer(0, "video");
        check(tl2.promoteToMaster(d3, 999), "promote: an out-of-range insert index is clamped");
        eq(tl2.getClipCount(), 3, "promote: clamped insert still lands exactly one clip");

        System.out.println();
        System.out.println(fails == 0 ? ("ALL PASS — " + checks + " checks")
                                      : (fails + " FAILED of " + checks));
        if (fails != 0) System.exit(1);
    }
}
