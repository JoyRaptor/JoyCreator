import com.fadcam.ui.faditor.model.BlendModes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * GENERATES {@code joybrush/shaders/jb_blend.glsl} (JB-2.20b) from the Studio's own shader string,
 * {@code BlendModes.glslBlendFnWithModeParam()}.
 *
 * <p>LEAD RULING R23: the equations are not transcribed into Joy Brush. This program prints the
 * Studio's function, byte for byte after a fixed header, and {@code tools/blend-glsl/gen_blend_glsl.sh
 * --check} regenerates and {@code cmp}s it against the committed file, so ONE character changed in the
 * Studio's shader turns a build red. That is the check {@code gen_blend_golden.sh --model} claimed and
 * could not make (it never opened {@code BlendModes.java}); this one can, because it reads the string
 * the GPU runs.
 *
 * <p>The function is GLSL ES 1.00 in the Studio; every construct in it (ternaries, {@code step},
 * {@code mix}, {@code dot}) is also valid in ES 3.00, and this generator does not edit the body. If a
 * future Studio edit uses a 1.00-only construct the compile fails loudly, which is the right failure.
 *
 * <p>Usage: {@code java GenBlendGlsl <out.glsl>}
 */
public final class GenBlendGlsl {

    static final String HEADER =
            "// jb_blend.glsl -- GENERATED FILE, DO NOT EDIT BY HAND (JB-2.20b, LEAD_RULINGS R23).\n"
            + "//\n"
            + "// This is the Studio's own blend function, BlendModes.glslBlendFnWithModeParam(), printed\n"
            + "// by tools/blend-glsl/GenBlendGlsl.java. Nothing below the marker line was typed here.\n"
            + "// Regenerate:  bash tools/blend-glsl/gen_blend_glsl.sh\n"
            + "// Drift check: bash tools/blend-glsl/gen_blend_glsl.sh --check   (non-zero on ANY difference)\n"
            + "//\n"
            + "// vec3 blendPix(vec3 b, vec3 s, float uBlendMode): b = backdrop, s = source, both STRAIGHT\n"
            + "// (un-premultiplied) rgb in 0..1; uBlendMode is the Studio's mode code 0..25 (BlendRgb.studioCodeOf).\n"
            + "// NORMAL returns s, because the caller's alpha composite is already source-over.\n"
            + "// Functions only: no #version, no main, no uniforms. Include AFTER `precision highp float;`.\n"
            + "// ---- the Studio's function starts on the next line ----\n";

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java GenBlendGlsl <out.glsl>");
            System.exit(2);
        }
        String text = HEADER + BlendModes.glslBlendFnWithModeParam();
        Files.write(Paths.get(args[0]), text.getBytes(StandardCharsets.UTF_8));
    }
}
