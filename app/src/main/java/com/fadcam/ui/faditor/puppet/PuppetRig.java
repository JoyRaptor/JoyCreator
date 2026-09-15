package com.fadcam.ui.faditor.puppet;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * THE RIG — the pins, the bones, and the settings that belong to the whole character.
 *
 * <p>Everything the deformer does not need, in one object, so that
 * {@code PuppetTopology} can stay a neutral mesh the renderer consumes without knowing what a
 * puppet is. Pin {@code i} here is handle {@code i} there; {@link #pinCount} and the topology's
 * {@code handleCount()} must agree, and {@link #matchesTopology} is how a caller checks rather
 * than assumes.
 *
 * <h3>Why a bone is in here and not in the pose track</h3>
 * <p>JoyRaptor, 2026-09-15: <i>"grabbing a bone and moving it, you're pretty much just moving two
 * pins, keeping them relative to each other."</i> That is the whole model. A bone is a RULE about
 * two pins, not a thing that animates:
 *
 * <ul>
 *   <li>Rotating a bone orbits its tip pin about its root pin — and writes to the TIP PIN's keys.</li>
 *   <li>Moving a bone moves both its pins — and writes to BOTH pins' keys.</li>
 *   <li>Dragging a far pin solves the chain back toward its root — and writes to every pin on the
 *       way.</li>
 * </ul>
 *
 * <p>So the timeline never learns a new kind of object, the tape stays "select a thing, see its
 * keys", and any given movement is stored in exactly one place. Two sources of truth for one
 * motion is the bug class this repo has already paid for twice.
 *
 * <h3>There is no "create an IK chain" step</h3>
 * <p>A chain of bones IS an IK chain. The root is whichever pin the chain reaches that has no
 * parent bone — and if that pin is a {@link PuppetPin.Type#PIN}, it is anchored, which is the
 * entire reason the Pin type exists. The user taps the hip first and gets IK without ever meeting
 * the term. See SPEC_20260915_PUPPET_UI §3.
 */
public final class PuppetRig {

    /**
     * ONE BONE: a rule joining two pins.
     *
     * <p>Carries no keyframes and no track — see the class doc. Everything here is a property of
     * the RIG, set once, not a value that changes over time.
     */
    public static final class Bone {

        /** Index into {@link #pins}. The end nearer the root of the chain. */
        public int rootPin;
        /** Index into {@link #pins}. The end that moves. */
        public int tipPin;

        public String name;

        /**
         * Rest length in unit space, captured when the bone is made. With {@link #stretchy} off,
         * the solver holds this and the limb keeps its length.
         */
        public float restLength;

        /**
         * Lets the bone lengthen so an out-of-reach target stays under the finger.
         *
         * <p>This is not a stylistic toggle, it prevents a specific ugly failure: with rigid
         * bones, dragging a hand past the arm's reach makes the arm stop dead and the shoulder
         * snap. Cartoon rigs want this ON; realistic ones want it off.
         */
        public boolean stretchy;

        /** Cap on stretch as a fraction of {@link #restLength}. Ignored when not stretchy. */
        public float maxStretch = 0.35f;

        /** Off by default — most cartoon rigs never want a knee that refuses to bend. */
        public boolean jointLimits;
        public float minAngleDeg = -170f;
        public float maxAngleDeg = 170f;

        /**
         * Which way this joint prefers to fold: +1 or -1.
         *
         * <p>Desktop packages solve this with a pole-vector target — a third object the user has
         * to place and keep out of the way. On a phone that is unacceptable, so the REST POSE
         * decides it: the sign is captured from how the artwork is already bent when the bone is
         * made. If it guesses wrong, one toggle flips it. One bit instead of an object.
         */
        public int bendSign = 1;

        Bone(int rootPin, int tipPin, String name, float restLength) {
            this.rootPin = rootPin;
            this.tipPin = tipPin;
            this.name = name;
            this.restLength = restLength;
        }

        
        Bone copy() {
            Bone b = new Bone(rootPin, tipPin, name, restLength);
            b.stretchy = stretchy;
            b.maxStretch = maxStretch;
            b.jointLimits = jointLimits;
            b.minAngleDeg = minAngleDeg;
            b.maxAngleDeg = maxAngleDeg;
            b.bendSign = bendSign;
            return b;
        }
    }

    // ── contents ─────────────────────────────────────────────────────────

    private final List<PuppetPin> pins = new ArrayList<>();
    private final List<Bone> bones = new ArrayList<>();

    // ── character-wide settings ──────────────────────────────────────────

    /**
     * The falloff exponent, remapped to 0..1 for the UI. The one knob that genuinely matters:
     * it decides whether the whole character is floppy or crisp.
     */
    public float softness = 0.5f;

    /** Triangle density. Costs frame time, so it is a real trade and not a free dial. */
    public float meshDetail = 0.55f;

    /** Drives every {@link PuppetPin.Type#DANGLE} pin at once. */
    public float gravity = 0.60f;
    public float wind = 0f;
    public float windDirDeg = 0f;

    /** How transparent counts as "outside the character", 0..1 of full alpha. */
    public float edgeThreshold = 0.12f;
    /** How far past the outline the mesh reaches, in unit space, so edges do not shear. */
    public float edgeExpansion = 0.02f;

    public boolean showPins = true;
    public boolean showBones = true;
    public boolean showMesh = false;

    /**
     * THE GATE. When true every pin greys and stops answering to touch.
     *
     * <p>It lives on the RIG, not in the drawer and not in a preference, because it is a property
     * of this character on this clip — and because the accident it prevents (tapping a sprite that
     * happens to have pins and knocking one) happens while the drawer is SHUT, which is exactly
     * when a drawer setting cannot help you. The control is the puppet badge in the corner of the
     * preview: seeing the pins IS the gate, so there is no second piece of state to track.
     */
    public boolean locked;

    // ── recording settings ───────────────────────────────────────────────

    /**
     * Touching a pin during playback records. On by default.
     *
     * <p>Off is a real need, not a safety net: pressing play and then finding the pin takes long
     * enough that the first moment is lost, so someone rehearsing wants to grab it first. It is
     * NOT an arming step — with it on there is still no extra tap before you perform.
     */
    public boolean recordOnTouch = true;

    /**
     * How hard a recorded move is thinned, 0 = keep every sample. High by default.
     *
     * <p>This is load-bearing, not polish. A live take drops a key per frame; without thinning,
     * the surviving keys cannot be drawn inside the bar because there is no room, and cannot be
     * grabbed because no finger can hit one.
     */
    public float detail = 0.78f;

    /**
     * How long a punch-in takes to rejoin the motion it replaced.
     *
     * <p>Only the OUT point needs this. At the in point your finger takes the pin from wherever
     * the earlier recording already had it, so there is nothing to jump from. Anchor in, blend out.
     */
    public int blendOutMs = 200;

    /** Snap the playhead to keys while editing. See INBOX 2026-09-15 on app-wide snapping. */
    public boolean snapToKeys = true;

    /**
     * What the current mesh was built from — see {@code PuppetMeshBuilder.signatureOf}.
     *
     * <p>NOT saved. Every knob below shapes the WEIGHT TABLE, not just the triangles: softness,
     * a pin's stiff area, a pin's mute. Comparing pin COUNT alone therefore misses the case that
     * matters most — the same pins with a different feel — and the picture goes on bending the
     * old way while the slider says otherwise. Left at 0 after a load so the first change
     * rebuilds, which is cheap and always correct.
     */
    public long meshSignature;

    // ── pins ─────────────────────────────────────────────────────────────

    public int pinCount() { return pins.size(); }

    
    public PuppetPin pin(int i) { return pins.get(i); }

    
    public List<PuppetPin> pins() { return pins; }

    /**
     * Add a pin, naming it for where it landed unless the caller has a better idea.
     *
     * @param ux,uy the drop point in the object's own unit space
     * @return the new pin's index, which is also its handle index in the topology
     */
    public int addPin(PuppetPin.Type type, float ux, float uy) {
        PuppetPin p = new PuppetPin(PuppetPin.suggestName(ux, uy, takenNames()), type);
        p.restX = ux;
        p.restY = uy;
        pins.add(p);
        return pins.size() - 1;
    }

    /**
     * Remove a pin AND every bone that touched it, renumbering the bones that survive.
     *
     * <p>The renumbering is the whole reason this is a method rather than a {@code List.remove}:
     * bones address pins by index, so deleting pin 2 silently re-points every bone that referred
     * to pins 3 and up. That is a corruption with no error message.
     */
    public void removePin(int index) {
        if (index < 0 || index >= pins.size()) return;
        pins.remove(index);
        for (int i = bones.size() - 1; i >= 0; i--) {
            Bone b = bones.get(i);
            if (b.rootPin == index || b.tipPin == index) { bones.remove(i); continue; }
            if (b.rootPin > index) b.rootPin--;
            if (b.tipPin > index) b.tipPin--;
        }
    }

    
    private Set<String> takenNames() {
        Set<String> s = new HashSet<>();
        for (PuppetPin p : pins) s.add(p.name);
        for (Bone b : bones) s.add(b.name);
        return s;
    }

    /** Rename, and record that a human chose it so {@link PuppetPin#suggestName} leaves it alone. */
    public void renamePin(int index, String name) {
        if (index < 0 || index >= pins.size()) return;
        String trimmed = name.trim();
        PuppetPin p = pins.get(index);
        if (trimmed.isEmpty()) { p.name = p.typeLabel(); p.nameIsMine = false; return; }
        p.name = trimmed;
        p.nameIsMine = true;
    }

    // ── bones ────────────────────────────────────────────────────────────

    public int boneCount() { return bones.size(); }

    
    public Bone bone(int i) { return bones.get(i); }

    
    public List<Bone> bones() { return bones; }

    /**
     * Join two pins. Refuses a bone to itself, a duplicate, and anything that would make a cycle —
     * the chain walk in {@link #chainToRoot} terminates only because of that last check.
     *
     * @return the new bone's index, or -1 if refused
     */
    public int addBone(int rootPin, int tipPin, float restLength) {
        if (rootPin == tipPin) return -1;
        if (rootPin < 0 || tipPin < 0 || rootPin >= pins.size() || tipPin >= pins.size()) return -1;
        if (parentBoneOf(tipPin) >= 0) return -1;          // a pin has at most one parent
        if (wouldCycle(rootPin, tipPin)) return -1;
        String name = pins.get(rootPin).name + "→" + pins.get(tipPin).name;
        bones.add(new Bone(rootPin, tipPin, name, restLength));
        return bones.size() - 1;
    }

    public void removeBone(int index) {
        if (index >= 0 && index < bones.size()) bones.remove(index);
    }

    /** The bone whose TIP is this pin — the one that parents it — or -1. */
    public int parentBoneOf(int pinIndex) {
        for (int i = 0; i < bones.size(); i++) if (bones.get(i).tipPin == pinIndex) return i;
        return -1;
    }

    /** True if joining these would close a loop, which the chain walk could not survive. */
    private boolean wouldCycle(int rootPin, int tipPin) {
        int cursor = rootPin;
        for (int guard = 0; guard <= bones.size(); guard++) {
            if (cursor == tipPin) return true;
            int parent = parentBoneOf(cursor);
            if (parent < 0) return false;
            cursor = bones.get(parent).rootPin;
        }
        return true;    // ran past the bone count: already looped
    }

    /**
     * Every pin from {@code pinIndex} back to the root of its chain, nearest first.
     *
     * <p>This is the unit that a drag writes to, that a simplification thins together, and that a
     * blend applies to as one — all three for the same reason. Thin an arm's pins onto DIFFERENT
     * key times and the chain no longer solves to the same shape between them, so the limb
     * wobbles; blend one pin of a chain while its neighbours do not and the limb tears.
     *
     * <p>A lone pin returns just itself, which is correct: it is its own chain.
     */
    
    public int[] chainToRoot(int pinIndex) {
        List<Integer> out = new ArrayList<>();
        int cursor = pinIndex;
        for (int guard = 0; guard <= bones.size(); guard++) {
            out.add(cursor);
            int parent = parentBoneOf(cursor);
            if (parent < 0) break;
            cursor = bones.get(parent).rootPin;
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * The component indices a chain occupies in the pose track — {@code 2i} and {@code 2i+1} for
     * each pin on the way to the root.
     *
     * <p>Handed straight to {@code MeshPoseTrack.putComponents}, which is what makes one drag of
     * one pin ONE key and ONE undo press even when the solve moved three pins.
     */
    
    public int[] chainComponents(int pinIndex) {
        int[] chain = chainToRoot(pinIndex);
        int[] comps = new int[chain.length * 2];
        for (int i = 0; i < chain.length; i++) {
            comps[i * 2] = chain[i] * 2;
            comps[i * 2 + 1] = chain[i] * 2 + 1;
        }
        return comps;
    }

    /** True when the chain's root is anchored, i.e. the solve has something to pull against. */
    public boolean chainIsAnchored(int pinIndex) {
        int[] chain = chainToRoot(pinIndex);
        if (chain.length == 0) return false;
        return pins.get(chain[chain.length - 1]).type == PuppetPin.Type.PIN;
    }

    // ── agreement with the topology ──────────────────────────────────────

    /**
     * The pins here and the handles there must be the same count, or every index in this file
     * points somewhere else. Callers check; this class cannot enforce it alone.
     */
    public boolean matchesTopology(int handleCount) { return handleCount == pins.size(); }

    // ── undo ─────────────────────────────────────────────────────────────

    /**
     * A deep copy, for the ONE snapshot a gesture undoes to.
     *
     * <p>It must be deep: a chain drag mutates several pins, and an undo that restored a list of
     * references to the same mutated objects would restore nothing at all.
     */
    
    public PuppetRig copy() {
        PuppetRig r = new PuppetRig();
        for (PuppetPin p : pins) r.pins.add(p.copy());
        for (Bone b : bones) r.bones.add(b.copy());
        r.softness = softness;
        r.meshDetail = meshDetail;
        r.gravity = gravity;
        r.wind = wind;
        r.windDirDeg = windDirDeg;
        r.edgeThreshold = edgeThreshold;
        r.edgeExpansion = edgeExpansion;
        r.showPins = showPins;
        r.showBones = showBones;
        r.showMesh = showMesh;
        r.locked = locked;
        r.recordOnTouch = recordOnTouch;
        r.detail = detail;
        r.blendOutMs = blendOutMs;
        r.snapToKeys = snapToKeys;
        r.meshSignature = meshSignature;
        return r;
    }


    @Override
    public String toString() {
        return "PuppetRig{" + pins.size() + " pins, " + bones.size() + " bones"
                + (locked ? ", LOCKED" : "") + "}";
    }
}
