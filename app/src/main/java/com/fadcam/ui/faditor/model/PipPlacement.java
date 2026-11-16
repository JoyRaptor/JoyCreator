package com.fadcam.ui.faditor.model;

/**
 * Where a picture-in-picture lands on the frame: ONE rule for the preview and both export paths
 * (Codex round 2, R2-FX-01).
 *
 * <p>The size is the PiP's own picture fitted whole into the frame, times its scale: a 9:16 phone
 * clip over a 16:9 project at scale 0.35 is 35% of the frame's HEIGHT tall and keeps its 9:16
 * shape. The editor ({@code OverlayVideoPreviewView}) and the Canvas export path always did this;
 * the GPU export path, which every unmasked PiP takes, used a placeholder of 30% of the frame's
 * width and height whatever the clip's shape, so the export came out about a third of the size,
 * stretched to the project's shape, and adding a mask (which moves it to the Canvas path) changed
 * its size.</p>
 *
 * <p>Pixel space here is the frame's: origin top-left, y down, positive rotation clockwise on
 * screen, exactly as {@code Canvas.rotate} and the editor's View rotation read it.</p>
 */
public final class PipPlacement {
    private PipPlacement() {}

    /**
     * The drawn size {w, h} in pixels of a {@code srcW}x{@code srcH} picture fitted into a
     * {@code boxW}x{@code boxH} frame at {@code scale} (1 = fills the frame on its tighter side).
     * An unknown source size falls back to the frame's own shape, never to nothing.
     */
    public static float[] fitSize(float boxW, float boxH, float srcW, float srcH, float scale) {
        if (!(srcW > 0f) || !(srcH > 0f)) { srcW = boxW; srcH = boxH; }
        float fit = Math.min(boxW / srcW, boxH / srcH);
        return new float[]{srcW * fit * scale, srcH * fit * scale};
    }

    /**
     * The 4x4 matrix (column-major, as {@code glUniformMatrix4fv} reads it) that draws the
     * unit quad {@code aPosition} in [-1, 1]^2 as this PiP into a framebuffer of
     * {@code frameW}x{@code frameH}.
     *
     * <ul>
     *   <li>The quad's corner {@code (-1, -1)} carries texture coordinate (0, 0), which a
     *       {@code SurfaceTexture} matrix maps to the picture's BOTTOM-left; so the picture's
     *       top is the quad's {@code +y} edge.</li>
     *   <li>The framebuffer is stored the way the PiP compositor reads its Canvas bitmaps: row
     *       0 is the frame's TOP ({@code BlendModeGlEffect} samples every overlay V-flipped).
     *       So pixel row y lands at NDC {@code 2y/H - 1}.</li>
     *   <li>Rotation is applied in PIXEL space. Rotating in NDC (as the old code did, after a
     *       non-uniform scale) skews a rotated PiP on any frame that is not square.</li>
     * </ul>
     */
    public static float[] quadMatrix(int frameW, int frameH, float cx, float cy,
                                     float w, float h, float rotDeg) {
        float fw = Math.max(1, frameW), fh = Math.max(1, frameH);
        double r = Math.toRadians(rotDeg);
        float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
        // pixel = c + R * (ax * w/2, -ay * h/2);  ndc = (2 px / W - 1, 2 py / H - 1)
        float[] m = new float[16];
        m[0] = w * cos / fw;      // d ndcX / d ax
        m[1] = w * sin / fh;      // d ndcY / d ax
        m[4] = h * sin / fw;      // d ndcX / d ay
        m[5] = -h * cos / fh;     // d ndcY / d ay
        m[10] = 1f;
        m[12] = 2f * cx / fw - 1f;
        m[13] = 2f * cy / fh - 1f;
        m[15] = 1f;
        return m;
    }

    /** Where the quad corner ({@code ax}, {@code ay}) lands, in frame pixels {x, y}: for tests and logs. */
    public static float[] cornerPixel(float[] m, int frameW, int frameH, float ax, float ay) {
        float nx = m[0] * ax + m[4] * ay + m[12];
        float ny = m[1] * ax + m[5] * ay + m[13];
        return new float[]{(nx + 1f) * 0.5f * frameW, (ny + 1f) * 0.5f * frameH};
    }
}
