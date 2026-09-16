package com.fadcam.ui.faditor.puppet;



/**
 * ONE PIN — everything about a puppet handle that the deformer does not need.
 *
 * <p><b>Why this exists beside {@code PuppetTopology} rather than inside it.</b> The topology
 * holds pin POSITIONS because the solver needs them; it has no business knowing that a pin is
 * called "L.Hand" or that it dangles. Keeping the two apart is what lets the topology stay a
 * neutral {@code MeshTopology} the renderer can consume without ever hearing the word "puppet" —
 * the separation SPEC_20260904_PUPPET_ARCHITECTURE insisted on.
 *
 * <p>The link between them is POSITION IN THE LIST: pin {@code i} here is handle {@code i} there,
 * whose components are {@code 2i} and {@code 2i+1}. {@link PuppetRig} is responsible for keeping
 * the two the same length; nothing here can enforce it alone.
 *
 * <h3>The name is not a nicety</h3>
 * <p>JoyRaptor, 2026-09-15: <i>"with a name an AI LLM can watch what it's doing and understand its
 * context in the larger whole of your project. Same concept as transcribing — it's not just for
 * closed caption, it's for the AI."</i> A pin called "R.Hand" is the difference between the
 * assistant being able to reason about a character and seeing seven anonymous dots. That is why
 * {@link #suggestName} exists: an unnamed rig is the common case, so the rig names itself.
 *
 * <h3>Type carries a colour, but not here</h3>
 * <p>Each type has one hue, and that hue is carried through to the keyframes on the tape — that is
 * how the tape says WHAT KIND of thing you are editing without drawing every pin's keys at once
 * (the ghosted summary of all pins was tried in the design study and rejected: with one
 * live-recorded take it is a thousand marks of grey).
 *
 * <p>The hues live in {@link PuppetPalette}, not in this file, and that is deliberate. A colour is
 * a rendering concern, and keeping it out is what lets this class and {@link PuppetRig} import
 * NOTHING — no android, no androidx, no FadCam class — so the JVM harness can prove the chain walk
 * and the index renumbering without a device. The same discipline the mesh engine already keeps.
 */
public final class PuppetPin {

    /** What a pin does. The whole vocabulary — there is deliberately no fifth. */
    public enum Type {
        /**
         * An anchor. It does not move, and it has NO SETTINGS AT ALL — having none is the
         * feature. It is also the root of any bone chain that reaches it, which is how IK gets
         * set up without the user ever meeting the words "IK chain": you tap the hip first.
         */
        PIN,

        /** Resists deformation around itself — a rigid patch (AE calls this "starch"). */
        STIFF,

        /** Simulated, not keyed. Hair, ears, tails. Driven by {@code DangleSim}. */
        DANGLE,

        /**
         * Moves in any direction, with no anchoring and no simulation.
         *
         * <p>It wore a rotate arc and a scale square until 2026-09-16. Turning and scaling a
         * single pin needs per-pin rotation in the deformer, which the engine does not have —
         * see SPEC_20260915_PUPPET_UI §1 for what it would take.
         */
        FREE
    }

    // ── where it sits ────────────────────────────────────────────────────

    /**
     * REST POSITION in the object's own unit space — origin top-left, 1 at the far edge, the
     * space every mesh handle already lives in.
     *
     * <p>This is the AUTHORED position, not the animated one. The pose track holds the offsets
     * that move a pin over time; this is where it sits when nothing is animating it, and it is
     * what {@code PuppetTopology} is built from. Dragging a pin with no recording in progress
     * moves THIS — which is rigging. Dragging while recording moves the track — which is
     * performing. Two different things, deliberately stored apart.
     */
    public float restX, restY;

    // ── identity ─────────────────────────────────────────────────────────

    public String name;
    public Type type;

    /**
     * True once a human has typed the name. {@link #suggestName} refuses to overwrite one, so
     * moving a pin you have named does not silently rename it — the rig only names what nobody
     * has claimed.
     */
    public boolean nameIsMine;

    /** Set by the user to stop this pin affecting the mesh, without losing its animation. */
    public boolean muted;

    // ── STIFF ────────────────────────────────────────────────────────────

    /** How large the rigid patch is, 0..1 of the object's short side. */
    public float stiffArea = 0.44f;
    /** How hard it resists, 0..1. */
    public float stiffStrength = 0.70f;

    // ── DANGLE ───────────────────────────────────────────────────────────

    public float spring = 0.62f;
    /** Damping. Higher settles sooner. */
    public float settle = 0.38f;
    public float mass = 0.45f;
    /** Cap on stretch, so a tail cannot become spaghetti. 0..1 over rest length. */
    public float maxStretch = 0.25f;

    // ── DEPTH (every type) ───────────────────────────────────────────────

    /**
     * WHICH SIDE OF THE CHARACTER THIS PART IS ON. 0 = behind, 0.5 = flat, 1 = in front.
     *
     * <p>On every type, not just one, because a 3/4 stance needs a shoulder behind the body and a
     * hand in front of it ON THE SAME ARM. The engine blends these into a field across the mesh
     * using the same weights that bend it, so the picture hands over somewhere ALONG the limb
     * rather than at the edge of a piece — which is the thing a per-layer order cannot do.
     *
     * <p>Stored 0..1 like every other slider and mapped to the engine's -1..+1 at the seam. 0.5 is
     * neutral and is what every existing rig has, so nothing already drawn changes.
     */
    public float depth = 0.5f;

    // ── FREE ─────────────────────────────────────────────────────────────

    // A `scale` field lived here, with a slider on the Free pin’s settings and a scale square
    // on the preview. All three are gone as of 2026-09-16, and the reason is one line of the
    // engine: PuppetTopology.handleComponents() returns 2. A pin is an x and a y. There is
    // nothing for a per-pin scale to drive, so the value was authored, saved, reloaded and
    // ignored — and the user was left believing the feature was broken rather than absent.
    //
    // Keeping the field "for later" would have been the same lie one level down: the next person
    // to add a scale control would have found somewhere to put the number and shipped the same
    // dead knob again. SPEC_20260915_PUPPET_UI §1 states exactly what the engine would have to
    // grow for this to come back, which is where it belongs until it does.

    // ── weighting ────────────────────────────────────────────────────────

    /**
     * Manual override of this pin's influence, or {@link #WEIGHT_AUTO}.
     *
     * <p>Defaults to automatic and should almost always stay there. The deformer weights every
     * pin by {@code 1 / distance²} measured ACROSS THE BODY and then normalises across all pins,
     * so adding a pin beside another already splits their influence with nothing to set. The
     * slider exists for the rare case, not for the common one — which is why it is not in the
     * drawer's default rows.
     */
    public float weight = WEIGHT_AUTO;

    public static final float WEIGHT_AUTO = -1f;

    public PuppetPin(String name, Type type) {
        this.name = name;
        this.type = type;
    }

    public boolean weightIsAuto() { return weight < 0f; }

    /** Deep copy — used by the undo snapshot, which must not alias live pins. */
    
    public PuppetPin copy() {
        PuppetPin p = new PuppetPin(name, type);
        p.restX = restX;
        p.restY = restY;
        p.nameIsMine = nameIsMine;
        p.muted = muted;
        p.depth = depth;
        p.stiffArea = stiffArea;
        p.stiffStrength = stiffStrength;
        p.spring = spring;
        p.settle = settle;
        p.mass = mass;
        p.maxStretch = maxStretch;
        p.weight = weight;
        return p;
    }

    // ── smart default names ──────────────────────────────────────────────

    /**
     * A name for a pin dropped at {@code (ux, uy)} in the object's own unit space — origin at the
     * top-left, 1 at the far edge, which is the space every mesh handle already lives in.
     *
     * <p>It reads the picture as a standing figure, because that is what people rig. It will be
     * wrong for a tree or a car, and that is fine: a wrong-but-specific name ("L.Hand") is still
     * a better starting point than "Pin 4", it is one tap to fix, and the user renaming it is
     * exactly the signal {@link #nameIsMine} records.
     *
     * <p>Callers pass {@code taken} so a second pin in the same region becomes "Head 2" rather
     * than colliding — the assistant cannot tell two identically named pins apart either.
     */
    
    public static String suggestName(float ux, float uy, java.util.Collection<String> taken) {
        String base = region(ux, uy);
        if (!taken.contains(base)) return base;
        for (int n = 2; n < 100; n++) {
            String tryName = base + " " + n;
            if (!taken.contains(tryName)) return tryName;
        }
        return base;
    }

    
    private static String region(float ux, float uy) {
        // The side bands are WIDE on purpose. At 0.38/0.62 a shoulder dropped at x=0.62 — a
        // perfectly ordinary place to put one — came back "Neck", because the centre band was
        // swallowing the tops of the arms. Caught by PuppetRigTest, which is the whole reason
        // the naming heuristic has a test at all.
        boolean left = ux < 0.42f;
        boolean right = ux > 0.58f;

        // SIX bands, not five. With five, a rig of an ARM — the commonest thing anyone builds —
        // produced "R.Shoulder", "R.Hand" and "R.Hand 2", because the elbow had nowhere of its
        // own to land. A joint named after the wrong joint is worse than no suggestion at all:
        // the name is what the assistant reads, so a wrong one actively misleads it.
        // Caught by PuppetRigTest, which is why the naming has a test.
        if (uy < 0.20f) return "Head";
        if (uy < 0.34f) {
            if (left) return "L.Shoulder";
            if (right) return "R.Shoulder";
            return "Neck";
        }
        if (uy < 0.47f) {
            if (left) return "L.Elbow";
            if (right) return "R.Elbow";
            return "Chest";
        }
        if (uy < 0.60f) {
            if (left) return "L.Hand";
            if (right) return "R.Hand";
            return "Waist";
        }
        if (uy < 0.76f) {
            if (left) return "L.Knee";
            if (right) return "R.Knee";
            return "Hip";
        }
        if (left) return "L.Foot";
        if (right) return "R.Foot";
        return "Base";
    }

    /** The label shown beside the name in the drawer. */
    
    public String typeLabel() {
        switch (type) {
            case PIN: return "Pin";
            case STIFF: return "Stiff";
            case DANGLE: return "Dangle";
            default: return "Free";
        }
    }

    /**
     * True when this pin's motion is SIMULATED rather than keyed. The tape draws no track for
     * one of these — it says so instead, because an empty track and a track that cannot have
     * keys look identical and mean opposite things.
     */
    public boolean isSimulated() { return type == Type.DANGLE; }


    @Override
    public String toString() { return name + " (" + typeLabel() + ")"; }
}
