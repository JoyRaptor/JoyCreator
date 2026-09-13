package com.fadcam.ui.faditor.transform.mesh;

/**
 * The ONE easing authority for mesh pose tracks.
 *
 * <p>This lived as a package-private constant on {@code TextOverlayItem}, which was correct while
 * images were the only thing that could bend. SPEC Z gives a mesh to sprites, PiP clips and the
 * spine as well, and none of those are in that class's package — so the choice was to widen a
 * constant on the text/image model class, or to move it to the package the mesh itself lives in.
 *
 * <p>It moved. An easing curve shared by four object types is not a property of any one of them,
 * and leaving it on {@code TextOverlayItem} would have meant three other types reaching into the
 * text model to find out how a bend eases — which is how "uniform across the board" becomes four
 * subtly different answers.
 *
 * <p>Deliberately delegates to {@link com.fadcam.ui.faditor.keyframe.Easing} rather than
 * implementing an interpolation of its own: a second easing implementation would let a bend and
 * every other animated property drift apart mid-gesture. That class is android-free, so this
 * package stays harness-testable.
 */
public final class MeshCurves {

    /** Every mesh pose track eases through the app's own {@code Easing}, by name. */
    public static final MeshPoseTrack.Curve APP_EASING = new MeshPoseTrack.Curve() {
        @Override
        public float apply(String name, float t) {
            try {
                return com.fadcam.ui.faditor.keyframe.Easing.fromName(name).apply(t);
            } catch (Exception ignored) {
                return t;
            }
        }
    };

    /**
     * Install {@link #APP_EASING} on a spec's track, tolerating a null spec or a spec with no
     * track. Every warpable model calls this from its own {@code setMesh}, so the curve cannot be
     * installed by one type and forgotten by another.
     */
    public static void install(MeshWarpSpec spec) {
        if (spec == null) return;
        try {
            MeshPoseTrack t = spec.track();
            if (t != null) t.setCurve(APP_EASING);
        } catch (Exception ignored) {
            // A curve that cannot be installed costs the easing, never the bend.
        }
    }

    private MeshCurves() { }
}
