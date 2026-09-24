package com.fadcam.ui.faditor.transform.mesh;

/**
 * THE ONE SHADER SOURCE for the mesh warp stamp — preview and export compile THESE strings.
 *
 * <p><b>Why this class exists.</b> {@code FxGlSource} exists for the same reason one level up:
 * two renderers assembling their own source is precisely how a preview starts lying. The stamp
 * vertex/fragment below are compiled by {@code compositor/MeshStampGl} on the preview GL thread
 * AND on the media3 effect thread for export (same wrapper class, different instances). There is
 * no second transcription of this arithmetic anywhere.
 *
 * <h3>Contract (from {@link MeshEngine})</h3>
 * <ul>
 *   <li>{@code aLocal} — deformed unit-space position {@code D(u,v)}, object-local 0..1,
 *       top-left origin (0,0 top-left, 1,1 bottom-right). Produced per-frame by
 *       {@link MeshEngine} into {@code buffers().positions}; uploaded EVERY frame.</li>
 *   <li>{@code aUv} — source texture coordinate, the UNdeformed {@code (u,v)} (equal to
 *       {@code buffers().rest} for the lattice). Uploaded only when
 *       {@code buffers().topologyStamp()} changes.</li>
 *   <li>{@code uHomography} — corner-pin forward homography on the unit square (identity when
 *       unpinned), column-major for {@code glUniformMatrix3fv(transpose=false)}. Built by the
 *       single authority {@code CornerPin.buildMatrix(...,0,0,1,1,...)} on the callers' side;
 *       this file never solves a homography.</li>
 *   <li>{@code uPlace} — placement affine (last row 0,0,1) mapping the homography output to clip
 *       space, column-major. Built by {@link MeshPlacement} — the only place that math lives.</li>
 *   <li><b>Do NOT divide by {@code w}.</b> {@code gl_Position = vec4(p.x, p.y, 0, p.z)} hands the
 *       rasteriser a real {@code w} so {@code vUv} interpolates with correct 1/w weighting.
 *       Dividing in the vertex shader and writing {@code w=1} gives an affine-per-triangle
 *       approximation and the classic diagonal seam across every quad — invisible until someone
 *       pins a corner hard.</li>
 *   <li><b>Back-face culling OFF</b> (set by the wrapper, not the shader) — a deliberate fold
 *       reverses winding and a culled fold vanishes instead of showing its back.</li>
 * </ul>
 *
 * <h3>Fragment: premultiplied, baked alpha + reveal, source sampled STRAIGHT (SPEC R)</h3>
 * <p>The stamp is a drop-in replacement for the frame-sized bitmap
 * {@code ImageOverlayFrameOverlay} produces: frame-sized RGBA, transparent outside the item,
 * opacity and wipe already baked into alpha. Downstream (preview identity-Pip composite,
 * export {@code ImageBlendGlEffect} mesh branch) then applies blend/mask/key/FX/adjustment
 * unchanged.
 * <ul>
 *   <li><b>SPEC R — sampling is STRAIGHT, {@code s = vUv}, and copying the still path's flip here
 *       was the upside-down bug.</b> The flip is not a property of the bitmap upload alone; it is
 *       the meeting of the upload with whatever {@code v} the quad hands the sampler, and the two
 *       paths hand it OPPOSITE conventions.
 *       <ul>
 *         <li>{@code PIP_STILL_FRAGMENT} builds {@code uv = q*0.5+0.5} out of the frame's y-UP
 *             {@code vFxUv}, so its {@code uv.y == 1} is the TOP of the picture. A
 *             {@code GLUtils.texImage2D} upload puts the bitmap's top row at {@code t == 0}.
 *             Top-meets-top therefore needs {@code 1-uv.y} there.</li>
 *         <li>Here {@code aUv} is {@code MeshBuffers.rest} — object-local, TOP-LEFT origin, so
 *             {@code vUv.y == 0} is the top of the picture ({@link MeshPlacement#buildPlace} sends
 *             local {@code v=0} to clip {@code y=+1}, the top of the screen). Top already meets
 *             top at {@code t == 0}: sampling {@code 1-vUv.y} is a SECOND flip on top of the one
 *             the placement matrix already performs, and an even number of flips upside-down is
 *             what JoyRaptor saw at 7.6 degrees.</li>
 *       </ul>
 *       Downstream is unchanged and stays correct: the stamp FBO is bottom-up, so
 *       {@code PIP_MESH_FRAGMENT} (preview) and {@code FRAGMENT_MESH_BASE} (export) both sample it
 *       without a flip. End to end the picture is flipped exactly once — in {@code uPlace}.</li>
 *   <li>Output is premultiplied ({@code rgb*a, a}) so the export's {@code sc = src.rgb/max(src.a)}
 *       un-premultiply recovers straight colour and the preview's premultiplied blend stays as it
 *       was for stills.</li>
 *   <li>{@code uReveal} tests the UNdeformed {@code u} — the wipe travels across the PICTURE, not
 *       the screen, which is what the Canvas {@code clipRect} does today.</li>
 *   <li>Mipmaps are mandatory on the SOURCE texture (not the stamp FBO): any mesh region that
 *       compresses the photo aliases without them. Set by the wrapper
 *       ({@code glGenerateMipmap}, {@code LINEAR_MIPMAP_LINEAR}).</li>
 * </ul>
 *
 * <p>No Android imports — harness-loadable like the rest of this package.
 */
public final class MeshGlSource {

    private MeshGlSource() {}

    /**
     * See the class note. {@code aLocal} is deformed, {@code aUv} is not.
     *
     * <p>SPEC H mirror: {@code uMirror} carries {@code TextOverlayItem.mirrorSignX/Y} (+1/-1
     * per axis), the ONE shared mirror definition every other renderer reads. The SOURCE
     * coordinate is mirrored about the unit centre (0.5); the deformed position is not, so the
     * bend stays where its dots are. i.e. bitmap -&gt; MIRROR -&gt; pin -&gt; place — the exact order
     * {@code ImageOverlayDraw}, {@code CornerPinImageView} and the GL Pip draw it in. Unmirrored
     * (1,1) is the identity and compiles the same picture as before; preview and export compile
     * THESE strings, so they cannot disagree.
     */
    public static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec2 aLocal;\n"
            + "attribute vec2 aUv;\n"
            + "uniform mat3 uHomography;\n"
            + "uniform mat3 uPlace;\n"
            + "uniform vec2 uMirror;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            // MIRROR THE PICTURE, NOT THE BEND (2026-09-24). Mirroring both the deformed position
            // and the source coordinate cancelled out: a mirrored AND bent picture drew
            // UNmirrored, with only its bend shape flipped away from the dots the user drags
            // (those are drawn unmirrored). bitmap -> MIRROR -> pin -> place means the SOURCE is
            // mirrored and the bent shape stays where it was authored.
            + "  vec2 ml = aLocal;\n"
            + "  vec2 mu = vec2(0.5 + uMirror.x * (aUv.x - 0.5), 0.5 + uMirror.y * (aUv.y - 0.5));\n"
            + "  vec3 h = uHomography * vec3(ml, 1.0);\n"
            + "  vec3 p = uPlace * h;\n"
            + "  gl_Position = vec4(p.x, p.y, 0.0, p.z);\n"
            + "  vUv = mu;\n"
            + "}\n";

    /**
     * Stamp fragment. Highp where the device has it: unlike neighbouring passes that only add or
     * multiply a colour, this one samples a minified source through a deformed uv — mediump is
     * ~10 mantissa bits on many ES2 parts, a visible wobble along a bent edge at 1080p. Same
     * guard {@code SpineTransform.fragmentShader} uses.
     */
    public static final String FRAGMENT_SHADER =
            "#version 100\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "varying vec2 vUv;\n"
            + "uniform sampler2D uImage;\n"
            + "uniform float uAlpha;\n"
            + "uniform float uReveal;\n"
            + "void main() {\n"
            + "  if (vUv.x > uReveal) { gl_FragColor = vec4(0.0); return; }\n"
            + "  vec2 s = vUv;\n"
            + "  vec4 tex = texture2D(uImage, s);\n"
            + "  gl_FragColor = vec4(tex.rgb * uAlpha, tex.a * uAlpha);\n"
            + "}\n";
}
