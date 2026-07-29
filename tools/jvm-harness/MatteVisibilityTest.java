import com.fadcam.ui.faditor.compositor.LayerPreviewController;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.Timeline;

import java.util.List;
import java.util.Set;

/**
 * §3a track-matte visibility: the clip serving as another clip's luma matte must be hidden
 * from EVERY consumer that renders a PiP as itself — the preview surface and the export's
 * overlay AUDIO sequence — while staying present in the list the export VIDEO path resolves
 * peers out of. Two shipped divergences motivated this: the preview drew the stencil clip as
 * a normal PiP, and a matte peer with overlayAudioEnabled still contributed sound to the
 * exported file while its picture was hidden.
 *
 * <p>Runs on a plain JVM against the REAL model classes (no stubs) — sourceUri stays null,
 * which is safe because nothing on these paths dereferences it.</p>
 */
public class MatteVisibilityTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static boolean has(List<Clip> clips, String id) {
        for (Clip c : clips) if (c.getId().equals(id)) return true;
        return false;
    }

    /** A floating overlay clip on lane {@code laneId} — layerId != null IS what makes it one. */
    static Clip overlay(String laneId) {
        Clip c = new Clip(null, 5000);
        c.setLayerId(laneId);
        return c;
    }

    public static void main(String[] args) {
        // ── 1. No matte anywhere: renderable == visible, and nothing is filtered ──────
        {
            Timeline t = new Timeline();
            Clip a = overlay("lane-a");
            Clip b = overlay("lane-b");
            t.addOverlayClip(a);
            t.addOverlayClip(b);

            List<Clip> visible = LayerPreviewController.visibleOverlayVideoClips(t);
            List<Clip> renderable = LayerPreviewController.renderableOverlayVideoClips(t);
            check(visible.size() == 2, "plain project: both PiPs are visible");
            check(renderable.size() == 2, "plain project: both PiPs are renderable");
            check(LayerPreviewController.servingMatteClipIds(t).isEmpty(),
                    "plain project: nothing is serving as a matte");
            check(renderable.equals(visible),
                    "plain project: renderable is the visible list, same clips in the same z-order");
        }

        // ── 2. B serves as A's matte: B is visible but NOT renderable ─────────────────
        {
            Timeline t = new Timeline();
            Clip recipient = overlay("lane-a");
            Clip peer = overlay("lane-b");
            CompositingSpec cs = new CompositingSpec();
            cs.mattePeerId = peer.getId();
            recipient.setCompositing(cs);
            t.addOverlayClip(recipient);
            t.addOverlayClip(peer);

            Set<String> serving = LayerPreviewController.servingMatteClipIds(t);
            check(serving.size() == 1 && serving.contains(peer.getId()),
                    "the peer, and only the peer, is reported as serving");

            List<Clip> visible = LayerPreviewController.visibleOverlayVideoClips(t);
            check(has(visible, peer.getId()),
                    "the peer stays VISIBLE — the export video path resolves it out of this list");

            List<Clip> renderable = LayerPreviewController.renderableOverlayVideoClips(t);
            check(renderable.size() == 1, "exactly one clip renders as a PiP");
            check(has(renderable, recipient.getId()), "the recipient renders");
            check(!has(renderable, peer.getId()),
                    "the peer does NOT render — this is the preview divergence");
        }

        // ── 3. The AUDIO defect: an opted-in, unmuted matte peer is still excluded ────
        //    buildOverlayAudioSequence iterates renderableOverlayVideoClips and asks
        //    effectiveOverlayVolume; the peer must never reach that question at all.
        {
            Timeline t = new Timeline();
            Clip recipient = overlay("lane-a");
            Clip peer = overlay("lane-b");
            peer.setOverlayAudioEnabled(true);
            peer.setVolumeLevel(1.0f);
            CompositingSpec cs = new CompositingSpec();
            cs.mattePeerId = peer.getId();
            recipient.setCompositing(cs);
            t.addOverlayClip(recipient);
            t.addOverlayClip(peer);

            check(LayerPreviewController.effectiveOverlayVolume(t, peer) > 0f,
                    "the peer WOULD be audible on its own terms (opted in, unmuted)");
            check(!has(LayerPreviewController.renderableOverlayVideoClips(t), peer.getId()),
                    "the audible peer is still excluded — its sound cannot reach the export");
        }

        // ── 4. A DANGLING peerId degrades to unmatted, it does not hide anything ──────
        {
            Timeline t = new Timeline();
            Clip recipient = overlay("lane-a");
            Clip other = overlay("lane-b");
            CompositingSpec cs = new CompositingSpec();
            cs.mattePeerId = "a-clip-that-was-deleted";
            recipient.setCompositing(cs);
            t.addOverlayClip(recipient);
            t.addOverlayClip(other);

            List<Clip> renderable = LayerPreviewController.renderableOverlayVideoClips(t);
            check(renderable.size() == 2, "a dangling peerId hides nothing");
        }

        // ── 5. Two recipients, one shared peer: the peer is hidden once, both render ──
        {
            Timeline t = new Timeline();
            Clip r1 = overlay("lane-a");
            Clip r2 = overlay("lane-b");
            Clip peer = overlay("lane-c");
            CompositingSpec c1 = new CompositingSpec();
            c1.mattePeerId = peer.getId();
            r1.setCompositing(c1);
            CompositingSpec c2 = new CompositingSpec();
            c2.mattePeerId = peer.getId();
            r2.setCompositing(c2);
            t.addOverlayClip(r1);
            t.addOverlayClip(r2);
            t.addOverlayClip(peer);

            List<Clip> renderable = LayerPreviewController.renderableOverlayVideoClips(t);
            check(renderable.size() == 2 && !has(renderable, peer.getId()),
                    "one peer serving two recipients: hidden once, both recipients render");
        }

        // ── 6. The list overload agrees with the timeline overload ───────────────────
        //    The export video path calls the list overload on a list it already holds; if the
        //    two disagreed, the peer could be hidden from pixels but not from audio.
        {
            Timeline t = new Timeline();
            Clip recipient = overlay("lane-a");
            Clip peer = overlay("lane-b");
            CompositingSpec cs = new CompositingSpec();
            cs.mattePeerId = peer.getId();
            recipient.setCompositing(cs);
            t.addOverlayClip(recipient);
            t.addOverlayClip(peer);

            Set<String> fromTimeline = LayerPreviewController.servingMatteClipIds(t);
            Set<String> fromList = LayerPreviewController.servingMatteClipIds(
                    LayerPreviewController.visibleOverlayVideoClips(t));
            check(fromTimeline.equals(fromList),
                    "both servingMatteClipIds overloads name the same set");
        }

        System.out.println();
        System.out.println((fails == 0 ? "ALL PASS" : fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
