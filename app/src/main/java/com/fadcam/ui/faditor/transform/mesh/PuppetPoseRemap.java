package com.fadcam.ui.faditor.transform.mesh;

/**
 * WHAT HAPPENS TO AN ANIMATED PUPPET WHEN THE PINS CHANGE.
 *
 * <p>Adding a pin to a character that is already animated must not cost the animation, and
 * removing one must not shift every other pin's performance onto its neighbour. Both are silent
 * failures — the picture still draws, the drawer still opens, and the work is simply gone or
 * wrong — which is why this is a file with tests rather than a line inside a rebuild.
 *
 * <h3>The rule, and why it cannot be "copy by index"</h3>
 * <p>Appending a pin renumbers nothing, so copying by index is right for that case and it is
 * tempting to stop there. But {@code PuppetRig.removePin} removes from the MIDDLE and renumbers
 * everything after it: delete pin 2 of 5 and the old pins 3 and 4 become the new 2 and 3. Copying
 * by index then hands new pin 2 the DELETED pin's pose, new pin 3 the old pin 3's, and so on —
 * every pin after the deletion inherits its predecessor's performance. Nothing errors. The arm
 * simply animates wrongly from then on.
 *
 * <p>So the caller states the mapping instead of implying it: {@code newToOld[i]} is the index
 * this pin USED to have, or -1 for a pin that did not exist. A fresh pin gets a zero offset, which
 * is the identity pose and means "wherever it was placed, that is its rest".
 *
 * <h3>Use it through {@link MeshWarpSpec#retopologize}, never around it</h3>
 * <p>{@code retopologize} rewrites the static pose AND every keyframed pose in one call and
 * refuses the whole thing if any of them comes out the wrong length. Rebuilding a spec from
 * scratch and copying the handles across — which is the obvious thing to write — keeps the static
 * pose and drops the entire track, because the track was never part of what was copied.
 *
 * <p>No Android imports.
 */
public final class PuppetPoseRemap {

    private PuppetPoseRemap() {}

    /**
     * The general case: {@code newToOld[i]} is where new pin {@code i}'s pose comes from, or -1
     * for a pin that is new.
     *
     * @param componentsPerHandle {@code MeshTopology.handleComponents()} — 2 for a puppet. Passed
     *                            rather than assumed, so this file never has to learn what a pin is.
     */
    public static MeshPoseTrack.Remapper byPinIndex(final int[] newToOld,
                                                    final int componentsPerHandle) {
        final int comps = Math.max(1, componentsPerHandle);
        final int[] map = newToOld == null ? new int[0] : newToOld.clone();
        return new MeshPoseTrack.Remapper() {
            @Override
            public float[] remap(float[] pose) {
                float[] out = new float[map.length * comps];
                if (pose == null) return out;
                for (int i = 0; i < map.length; i++) {
                    int from = map[i];
                    if (from < 0) continue;                       // a new pin starts at rest
                    int src = from * comps, dst = i * comps;
                    if (src + comps > pose.length) continue;      // a pose shorter than claimed
                    System.arraycopy(pose, src, out, dst, comps);
                }
                return out;
            }
        };
    }

    /**
     * Pins were APPENDED. Every existing pin keeps its pose; the new ones start at rest.
     *
     * <p>This is what dropping another pin onto a character does, and it is the common case.
     */
    public static MeshPoseTrack.Remapper appended(int oldPinCount, int newPinCount,
                                                  int componentsPerHandle) {
        int n = Math.max(0, newPinCount);
        int[] map = new int[n];
        for (int i = 0; i < n; i++) map[i] = i < oldPinCount ? i : -1;
        return byPinIndex(map, componentsPerHandle);
    }

    /**
     * One pin was REMOVED at {@code removedIndex} and everything after it slid down one.
     *
     * <p>The removed pin's pose is the one thing that should not survive — and with this mapping
     * it is the only thing that does not.
     */
    public static MeshPoseTrack.Remapper afterRemoval(int oldPinCount, int removedIndex,
                                                      int componentsPerHandle) {
        int n = Math.max(0, oldPinCount - 1);
        int[] map = new int[n];
        for (int i = 0; i < n; i++) map[i] = i < removedIndex ? i : i + 1;
        return byPinIndex(map, componentsPerHandle);
    }

    /**
     * Move {@code spec} onto {@code to}, carrying the static pose and every keyframe.
     *
     * <p>The one-call version of the correct thing, so the correct thing is the short thing to
     * write.
     *
     * @return false when the spec, the topology or the mapping do not line up; the spec is then
     *         left completely unchanged and the caller should keep the mesh it has rather than
     *         hand the user a puppet with no animation
     */
    public static boolean apply(MeshWarpSpec spec, MeshTopology to, int[] newToOld) {
        if (spec == null || to == null || newToOld == null) return false;
        if (newToOld.length != to.handleCount()) return false;
        return spec.retopologize(to, byPinIndex(newToOld, to.handleComponents()));
    }
}
