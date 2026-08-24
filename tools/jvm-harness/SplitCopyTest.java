import com.fadcam.ui.faditor.model.Clip;

/**
 * SPLIT field carriage, off device.
 *
 * <p><b>Why this exists.</b> The copy constructor {@code Clip(Clip other)} is what SPLIT
 * uses to build both halves (FaditorEditorActivity.splitAt / splitLinkedPartnerAndRecord),
 * and three fields went missing from it unnoticed because nothing executed it: pan, label,
 * hidden were found by copy_lint.py structurally; fx was found the same way and fixed once
 * someone noticed {@code FxStack.copy()} already existed. There was no BEHAVIOURAL test —
 * this is it, for the fields whose carriage was ruled on 2026-08-24:</p>
 *
 * <ul>
 *   <li><b>passThrough</b> — COPIED. Its readers (hitsInPreview,
 *       OverlayVideoPreviewView touch interception, the LayerRowRenderer badge) make it
 *       "stop catching taps aimed at what is behind me" — a property of the object, not of
 *       its trim window. Both halves of a cut keep the premise, so both keep the flag.</li>
 *   <li><b>linkedClipId</b> — NOT copied (ruled): a fresh-id child stays unlinked until an
 *       operation re-links it (splitLinkedPartnerAndRecord calls Timeline.linkClips
 *       pairwise). Copying would leave two clips pointing at one partner while the partner
 *       points at the dead original id.</li>
 *   <li><b>offsetMs</b> — DELETED from the model (ruled): master positions are derived by
 *       summation in Timeline.getMasterTrack, overlay positions live in overlayStartMs,
 *       and no reader ever consulted the stored value. getOffsetMs() is AudioParams
 *       conformance and returns 0.</li>
 * </ul>
 *
 * <p>The negative controls are what give the positive checks meaning: one proves the
 * harness really can see a field NOT travel (locked, exempted), so the passThrough check
 * is not vacuous.</p>
 */
public class SplitCopyTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static Clip clip() {
        // Uri is stored, never dereferenced, so null is safe here (same assumption
        // run-envelope.sh's header records for AudioClip / ClipVolumeTest).
        return new Clip((android.net.Uri) null, 10_000L);
    }

    public static void main(String[] args) {
        // ── 1. passThrough survives a split ──────────────────────────────────────────
        Clip p = clip();
        p.setPassThrough(true);
        Clip left = new Clip(p, "left-id");
        Clip right = new Clip(p, "right-id");
        check(left.isPassThrough(), "split LEFT half keeps pass-through");
        check(right.isPassThrough(), "split RIGHT half keeps pass-through");

        // Value semantics: flipping the ORIGINAL afterwards must not leak into the halves.
        p.setPassThrough(false);
        check(left.isPassThrough(), "original's later flip does not rewrite the left half");

        // ── 2. linkedClipId deliberately does NOT travel (the 2026-08-24 ruling) ────
        Clip host = clip();
        host.setLinkedClipId("partner-id");
        Clip half = new Clip(host, "half-id");
        check(!half.isLinked(),
                "a fresh-id split half is UNLINKED (independent until re-linked)");
        check(host.isLinked(), "the original keeps its own link");

        // ── 3. offsetMs is derived, not stored (the 2026-08-24 ruling) ──────────────
        Clip d = clip();
        d.setOffsetMs(12_345);          // must be absorbed silently
        check(d.getOffsetMs() == 0L, "setOffsetMs on a Clip is a no-op (position is derived)");
        Clip dHalf = new Clip(d, "dhalf-id");
        check(dHalf.getOffsetMs() == 0L, "and the copy carries no phantom offset either");

        // ── 4. Regression guards: fields fixed earlier stay fixed ───────────────────
        Clip r = clip();
        r.setLabel("keeper");
        r.setHiddenObject(true);
        Clip rLeft = new Clip(r, "rl-id");
        check("keeper".equals(rLeft.getLabel()), "label still travels with a copy");
        check(rLeft.isHiddenObject(), "hidden still travels with a copy");

        // ── 5. NEGATIVE CONTROLS — prove the checks above can fail ──────────────────
        // `locked` is EXEMPTED in copy_lint (workspace state, not clip content). If this
        // check ever fails, the harness above is broken — e.g. someone made the copy ctor
        // blanket-copy every field, at which point "passThrough survives" proves nothing.
        Clip l = clip();
        l.setLockedObject(true);
        Clip lHalf = new Clip(l, "lh-id");
        check(!lHalf.isLockedObject(), "NEGCTRL: an exempted field (locked) really does NOT travel");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
