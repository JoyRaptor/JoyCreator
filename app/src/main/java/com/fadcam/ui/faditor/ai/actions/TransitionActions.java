package com.fadcam.ui.faditor.ai.actions;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.ai.actions.ActionSpec.Effect;
import com.fadcam.ui.faditor.gltransitions.GLTransitionCatalog;
import com.fadcam.ui.faditor.gltransitions.GlExternalTransitions;
import com.fadcam.ui.faditor.model.Clip;
import com.fadcam.ui.faditor.model.Timeline;
import com.fadcam.ui.faditor.model.Transition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The transitions family (gap F2): the effects a person picks in the Studio's Transitions drawer and
 * puts on a cut between two clips. Shows exactly the drawer's cards: the nine basic cards, the
 * catalogue's GL effects ("More effects"), and any .glsl files in the project's folder.
 *
 * <p>Cut numbers are 1-based for people and Joybot ("1" = between clip 1 and clip 2); the model stores
 * the 0-based left clip index, as the editor does. Duration rules mirror the editor: a new transition
 * on a cut is 600 ms (insertTransitionAtSeam / insertTransitionAtPlayhead), the stored length is kept
 * in the model's 50 ms – 10 s range, and it is shortened to fit the shorter of the two clips at that
 * cut the way the drag handle does (EditorTimelineView.transitionDurationFromDragX).</p>
 */
final class TransitionActions {
    private TransitionActions() { }

    private static final String FAMILY = "transitions";

    /** The editor's default length for a transition put on a cut. */
    private static final long DEFAULT_MS = 600;
    /** Floor of the drag handle and of Transition.clampDurationMs. */
    private static final long MIN_MS = 50;

    /** One card in the Transitions drawer: what it stores and what it is called. */
    private static final class Choice {
        final String key;       // Transition.Type name for basic cards, the GL shader id otherwise
        final String label;     // the card's words
        final Transition.Type type;
        final String glId;      // null for basic cards
        final boolean external; // a .glsl file from the project's folder

        Choice(String key, String label, Transition.Type type, String glId, boolean external) {
            this.key = key; this.label = label; this.type = type; this.glId = glId; this.external = external;
        }
    }

    static void register(@NonNull ActionRegistry<StudioCtx> r) {
        r.add(ActionSpec.of("list_transitions", "List the transitions")
                .help("Every transition a person can put on a cut between two clips, with the name they see and"
                        + " the key to use in set_transition. Call this when they name an effect you don't"
                        + " recognise, or ask what transitions there are.")
                .wing("STUDIO", FAMILY).effect(Effect.READ)
                .where("Studio › Transitions")
                .build(), TransitionActions::listTransitions);

        r.add(ActionSpec.of("set_transition", "Put a transition on a cut")
                .help("Put a transition on the cut between two clips, replacing whatever was on that cut. seam is"
                        + " the cut's number: 1 is between clip 1 and clip 2, 2 is between clips 2 and 3, and so on;"
                        + " \"all\" puts it on every cut. type is a name or key from list_transitions. durationMs is"
                        + " 50 to 10000 ms (600 if not given); it is shortened where a clip at the cut is shorter.")
                .wing("STUDIO", FAMILY).effect(Effect.EDIT)
                .where("Studio › Transitions › tap an effect, or drag it onto a cut")
                .text("seam", true, "Which cut: its number (1 = between clip 1 and clip 2) or \"all\"")
                .text("type", true, "The transition: its name or key from list_transitions (e.g. \"Cross Zoom\" or \"CrossZoom\")")
                .param("durationMs", ActionSpec.Type.TIME_MS, false,
                        "How long the transition lasts, in milliseconds (50 to 10000; 600 if not given)")
                .build(), TransitionActions::setTransition);
    }

    // ── handlers ─────────────────────────────────────────────────────────────

    @NonNull
    private static ActionResult listTransitions(@NonNull StudioCtx ctx, @NonNull Args a) {
        List<Choice> all = catalogue(ctx);
        StringBuilder basic = new StringBuilder(), more = new StringBuilder(), mine = new StringBuilder();
        int nBasic = 0, nMore = 0, nMine = 0;
        for (Choice c : all) {
            StringBuilder b;
            if (c.glId == null) { b = basic; nBasic++; }
            else if (c.external) { b = mine; nMine++; }
            else { b = more; nMore++; }
            if (b.length() > 0) b.append(", ");
            b.append(c.label).append(" (").append(c.key).append(')');
        }
        String summary = "You can put " + all.size() + " transitions on a cut: " + nBasic + " basic and "
                + (nMore + nMine) + " more effects.";
        String detail = "Basic: " + basic + ".\n"
                + "More effects: " + more + ".\n"
                + (nMine == 0
                    ? "Your own effects (.glsl files in the project folder): none."
                    : "Your own effects (.glsl files in the project folder): " + mine + ".");
        return ActionResult.unchanged(summary).withDetail(detail);
    }

    @NonNull
    private static ActionResult setTransition(@NonNull StudioCtx ctx, @NonNull Args a) throws ActionError {
        Timeline tl = ctx.project.getTimeline();
        List<Integer> seams = seams(tl, a.str("seam"));
        Choice pick = resolve(catalogue(ctx), a.str("type"));
        Long asked = a.whole("durationMs");
        long wanted = Transition.clampDurationMs(asked == null ? DEFAULT_MS : asked);

        List<Clip> clips = tl.getClips();
        Set<String> touched = new LinkedHashSet<>();
        List<Long> lengths = new ArrayList<>();
        boolean shortened = false;
        for (int seam : seams) {
            Clip left = clips.get(seam), right = clips.get(seam + 1);
            // EditorTimelineView.transitionDurationFromDragX: span = shorter neighbouring clip, floor 50 ms.
            long span = Math.max(MIN_MS, Math.min(mainMs(left), mainMs(right)));
            long dur = Math.min(wanted, span);
            if (dur < wanted) shortened = true;
            // FaditorEditorActivity.insertTransitionAtSeam / insertTransitionAtPlayhead: old one on the cut goes, new one added.
            tl.removeTransitionAtSeam(seam);
            Transition t = new Transition(pick.type, dur, seam);
            if (pick.glId != null) t.glTransitionId = pick.glId;
            tl.addTransition(t);
            touched.add(left.getId());
            touched.add(right.getId());
            lengths.add(dur);
        }

        String what = seams.size() == 1
                ? "the cut between clip " + (seams.get(0) + 1) + " and clip " + (seams.get(0) + 2)
                : "all " + seams.size() + " cuts";
        String length = allEqual(lengths)
                ? ms(lengths.get(0))
                : "up to " + ms(Collections.max(lengths)) + ", shorter where a clip is short";
        String summary = "Put " + pick.label + " on " + what + " (" + length + ")."
                + (shortened ? " It was shortened to fit the clips at that cut." : "");
        return ActionResult.changed(summary, new ArrayList<>(touched))
                .withDetail("Key: " + pick.key + ". Cuts: " + seamNumbers(seams) + ".");
    }

    // ── catalogue and name resolution ────────────────────────────────────────

    /** The drawer's cards in the order the picker shows them: basics, then GL effects, then the user's. */
    @NonNull
    private static List<Choice> catalogue(@NonNull StudioCtx ctx) {
        List<Choice> out = new ArrayList<>();
        out.add(basic(Transition.Type.CROSS_DISSOLVE, "Crossfade"));
        out.add(basic(Transition.Type.FADE_OUT_TO_BLACK, "Fade Black"));
        out.add(basic(Transition.Type.FADE_OUT_TO_WHITE, "Fade White"));
        out.add(basic(Transition.Type.WIPE_LEFT, "Wipe"));
        out.add(basic(Transition.Type.RADIAL, "Radial"));
        out.add(basic(Transition.Type.LINEAR_MIRROR_WIPE, "Mirror Wipe"));
        out.add(basic(Transition.Type.PUSH_LEFT, "Push"));
        out.add(basic(Transition.Type.GLITCH, "Glitch"));
        out.add(basic(Transition.Type.TV_CHANNEL, "TV Channel"));
        for (GLTransitionCatalog.Entry e : GLTransitionCatalog.entries()) {
            out.add(new Choice(e.id, e.displayName, Transition.Type.GL_SHADER, e.id, false));
        }
        // The drawer registers the folder's .glsl shaders when it opens; do the same so their keys resolve
        // and they render. Invisible when the project has no folder or the folder has no shaders.
        String dir = ctx.project.getPinnedAssetDir();
        if (dir != null) {
            for (GlExternalTransitions.Item it
                    : GlExternalTransitions.scanAndRegister(ctx.app, Uri.parse(dir))) {
                out.add(new Choice(it.id, it.displayName, Transition.Type.GL_SHADER, it.id, true));
            }
        }
        return out;
    }

    @NonNull
    private static Choice basic(@NonNull Transition.Type type, @NonNull String label) {
        return new Choice(type.name(), label, type, null, false);
    }

    /**
     * The one transition a name or key means. Exact key or name first; then letters-and-digits only
     * ("push left" is "PushLeft"). Two or more matches is an error naming each, so the model asks.
     */
    @NonNull
    private static Choice resolve(@NonNull List<Choice> all, @NonNull String raw) throws ActionError {
        String q = raw.trim();
        List<Choice> hits = new ArrayList<>();
        for (Choice c : all) {
            if (c.key.equals(q) || c.label.equalsIgnoreCase(q)) hits.add(c);
        }
        if (hits.isEmpty()) {
            String n = squash(q);
            if (!n.isEmpty()) {
                for (Choice c : all) {
                    if (squash(c.key).equals(n) || squash(c.label).equals(n)) hits.add(c);
                }
            }
        }
        if (hits.size() == 1) return hits.get(0);
        if (hits.isEmpty()) {
            throw new ActionError("There's no transition called \"" + q + "\". The transitions are: "
                    + names(all) + ".");
        }
        throw new ActionError("\"" + q + "\" could be " + names(hits) + ". Say which one, by its key.");
    }

    // ── cuts and lengths ─────────────────────────────────────────────────────

    /** The 0-based left-clip index of each cut asked for: a cut number 1..N-1, or every cut for "all". */
    @NonNull
    private static List<Integer> seams(@NonNull Timeline tl, @NonNull String raw) throws ActionError {
        int clips = tl.getClipCount();
        if (clips < 2) {
            throw new ActionError("A transition needs two clips side by side. This project has " + clips
                    + " clip" + (clips == 1 ? "" : "s") + " on the main timeline.");
        }
        String s = raw.trim();
        List<Integer> out = new ArrayList<>();
        if ("all".equalsIgnoreCase(s)) {
            for (int i = 0; i < clips - 1; i++) out.add(i);
            return out;
        }
        Double d = ActionSpec.asNumber(s);
        if (d == null || d != Math.rint(d)) {
            throw new ActionError("seam must be a cut number like 1 (the cut between clip 1 and clip 2), or \"all\".");
        }
        int cut = d.intValue();
        if (cut < 1 || cut > clips - 1) {
            throw new ActionError("There's no cut " + cut + ". This timeline has " + clips + " clips, so the cuts"
                    + " are 1 to " + (clips - 1) + ".");
        }
        out.add(cut - 1);
        return out;
    }

    /** How long a clip plays on the main timeline, as the drag handle measures it (segment effectiveMs). */
    private static long mainMs(@NonNull Clip c) {
        long ms = c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs();
        return Math.max(1, ms);
    }

    // ── small helpers ────────────────────────────────────────────────────────

    @NonNull
    private static String names(@NonNull List<Choice> cs) {
        StringBuilder sb = new StringBuilder();
        for (Choice c : cs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.label).append(" (").append(c.key).append(')');
        }
        return sb.toString();
    }

    @NonNull
    private static String squash(@NonNull String s) {
        return s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    @NonNull
    private static String ms(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(Locale.US, "%.1f s", ms / 1000f);
    }

    private static boolean allEqual(@NonNull List<Long> l) {
        for (Long v : l) if (!v.equals(l.get(0))) return false;
        return true;
    }

    @NonNull
    private static String seamNumbers(@NonNull List<Integer> seams) {
        StringBuilder sb = new StringBuilder();
        for (int s : seams) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s + 1);
        }
        return sb.toString();
    }
}
