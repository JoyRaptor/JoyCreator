package com.fadcam.ui.faditor.gltransitions;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class GlTransitionShaderLoader {

    // SMOKE TEST (not run here — requires a real GL context; wire into a CI check):
    //
    //   for (File f : assetsDir.listFiles((d, n) -> n.endsWith(".glsl"))) {
    //       String body = sanitize(readFile(f), false);  // bundled mode
    //       String injected = exportTemplate(ratio)
    //               .replace(EXTRA_PARAM_MARKER, uniformsFor(stripExt(f.getName())))
    //               .replace(TRANSITION_BODY_MARKER, body);
    //       try {
    //           new GlProgram(VERTEX_SHADER, injected);   // throws on compile error
    //           FLog.d(TAG, "OK: " + f.getName());
    //       } catch (Exception e) {
    //           FLog.e(TAG, "FAIL: " + f.getName() + " — " + e.getMessage());
    //       }
    //   }
    //
    // Catches: undeclared uniforms (compile error), uninitialised uniforms that
    // silently default to 0 (no error, but flagged by visual diff against the
    // known-good default e.g. burn.glsl's vec3(0.9, 0.4, 0.2)).
    //
    // EXTRA_PARAM_MARKER  = the literal "/* EXTRA_PARAM_UNIFORMS_GO_HERE */" token
    // TRANSITION_BODY_MARKER = the literal "/* TRANSITION_BODY_GOES_HERE */" token
    // (kept as separate String constants so this comment compiles).

    /** External GL transition bodies (id → raw GLSL), registered from the user's pinned folder. */
    private static final java.util.Map<String, String> EXTERNAL =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Default param values (id → {name → value}) parsed from external shaders' {@code // = N} comments. */
    private static final java.util.Map<String, java.util.Map<String, Float>> EXTERNAL_PARAMS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private GlTransitionShaderLoader() {}

    /** Register a user-supplied GL transition body so {@link #wrap} can resolve it by id. */
    public static void registerExternal(@NonNull String id, @NonNull String body) {
        EXTERNAL.put(id, body);
    }

    /** Register parsed default param values for an external transition (applied in the shader program). */
    public static void registerExternalParams(@NonNull String id,
                                              @NonNull java.util.Map<String, Float> params) {
        EXTERNAL_PARAMS.put(id, params);
    }

    @NonNull
    public static java.util.Map<String, Float> getExternalParams(@NonNull String id) {
        java.util.Map<String, Float> p = EXTERNAL_PARAMS.get(id);
        return p != null ? p : java.util.Collections.emptyMap();
    }

    public static boolean isExternal(@NonNull String id) {
        return EXTERNAL.containsKey(id);
    }

    @NonNull
    public static String loadWrappedExportShader(@NonNull Context context,
                                                  @NonNull String transitionId,
                                                  int outW, int outH) throws IOException {
        return wrap(context, transitionId, false, outW, outH);
    }

    @NonNull
    public static String loadWrappedPreviewShader(@NonNull Context context,
                                                   @NonNull String transitionId) throws IOException {
        return wrap(context, transitionId, true, 0, 0);
    }

    /**
     * Live-preview variant: both legs are {@code samplerExternalOES} SurfaceTextures fed by
     * real decoders (main player = A's tail, a muted prefetch player = B's head), so both legs
     * MOVE during the blend. Crop + fit-center happen IN THE SHADER (uniforms below) because
     * live frames arrive at raw source geometry — unlike the bitmap path, where
     * decodeTransitionFrame pre-crops and letterboxes on the CPU.
     */
    @NonNull
    public static String loadWrappedLivePreviewShader(@NonNull Context context,
                                                      @NonNull String transitionId) throws IOException {
        boolean external = EXTERNAL.containsKey(transitionId);
        String body;
        String uniforms;
        if (external) {
            body = sanitize(EXTERNAL.get(transitionId), true);
            uniforms = "";
        } else {
            body = sanitize(readAsset(context, "gl_transitions/" + transitionId + ".glsl"), false);
            uniforms = uniformsFor(transitionId);
        }
        return livePreviewTemplate()
                .replace("/* EXTRA_PARAM_UNIFORMS_GO_HERE */", uniforms)
                .replace("/* TRANSITION_BODY_GOES_HERE */", body);
    }

    /** Fallback (plain crossfade) live shader for ids whose body fails to load/compile. */
    @NonNull
    public static String fallbackLiveShader() {
        return livePreviewTemplate()
                .replace("/* TRANSITION_BODY_GOES_HERE */",
                        "vec4 transition(vec2 uv) { return mix(getFromColor(uv), getToColor(uv), progress); }");
    }

    @NonNull
    public static String fallbackShader(boolean preview) {
        return fallbackShader(preview, 1f);
    }

    @NonNull
    public static String fallbackShader(boolean preview, float ratio) {
        return (preview ? previewTemplate() : exportTemplate(ratio))
                .replace("/* TRANSITION_BODY_GOES_HERE */",
                        "vec4 transition(vec2 uv) { return mix(getFromColor(uv), getToColor(uv), progress); }");
    }

    @NonNull
    private static String wrap(@NonNull Context context, @NonNull String transitionId,
                                boolean preview) throws IOException {
        return wrap(context, transitionId, preview, 0, 0);
    }

    @NonNull
    private static String wrap(@NonNull Context context, @NonNull String transitionId,
                                boolean preview, int outW, int outH) throws IOException {
        boolean external = EXTERNAL.containsKey(transitionId);
        String body;
        String uniforms;
        if (external) {
            // User-supplied shader: keep its OWN uniform declarations (no catalog to re-inject them).
            body = sanitize(EXTERNAL.get(transitionId), true);
            uniforms = "";
        } else {
            body = sanitize(readAsset(context, "gl_transitions/" + transitionId + ".glsl"), false);
            uniforms = uniformsFor(transitionId);
        }
        float ratio = (preview || outW <= 0 || outH <= 0) ? 1f : (float) outW / (float) outH;
        String template = preview ? previewTemplate() : exportTemplate(ratio);
        template = template.replace("/* EXTRA_PARAM_UNIFORMS_GO_HERE */", uniforms);
        template = template.replace("/* TRANSITION_BODY_GOES_HERE */", body);
        return template;
    }

    @NonNull
    private static String readAsset(@NonNull Context context, @NonNull String path) throws IOException {
        try (InputStream in = context.getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        }
    }

    @NonNull
    private static String sanitize(@NonNull String body, boolean keepUniforms) {
        String[] lines = body.split("\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("precision")) continue;
            // The template always declares `uniform float progress` (preview + export) and
            // `const float ratio` (export only). If the body re-declares them we get a
            // duplicate-declaration error regardless of keepUniforms.
            if (trimmed.equals("uniform float progress")
                    || trimmed.equals("uniform float ratio")
                    || trimmed.equals("uniform float progress;")
                    || trimmed.equals("uniform float ratio;")) {
                continue;
            }
            // Bundled shaders re-declare params via uniformsFor(); external shaders keep their own.
            if (!keepUniforms
                    && (trimmed.startsWith("uniform float") || trimmed.startsWith("uniform vec4"))) {
                continue;
            }
            kept.add(line);
        }
        return join(kept);
    }

    @NonNull
    private static String uniformsFor(@NonNull String transitionId) {
        GLTransitionCatalog.Entry entry = GLTransitionCatalog.find(transitionId);
        if (entry == null) return "";
        StringBuilder uniforms = new StringBuilder();
        if ("GridFlip".equals(transitionId)) {
            uniforms.append("uniform vec4 bgcolor;\n");
        }
        for (GLTransitionCatalog.Param param : entry.params) {
            uniforms.append("uniform float ").append(param.name).append(";\n");
        }
        return uniforms.toString();
    }

    @NonNull
    private static String exportTemplate(float ratio) {
        return "#version 100\n"
                + "precision mediump float;\n"
                + "uniform sampler2D uVideoTexSampler0;\n"
                + "uniform sampler2D uOverlayTexSampler0;\n"
                + "uniform float uOverlayAlphaScale0;\n"
                + "uniform float progress;\n"
                + "const float ratio = " + ratio + ";\n"
                // Fit-centered rect of the outgoing input on the (canvas-sized) output —
                // identity {0,0,1,1} when the segment runs at its own dims. Black outside.
                + "uniform vec4 uFromFit;\n"
                + "/* EXTRA_PARAM_UNIFORMS_GO_HERE */"
                + "varying vec2 vTexSamplingCoord;\n"
                + "vec4 getFromColor(vec2 uv) {\n"
                + "  vec2 c = (uv - uFromFit.xy) / max(uFromFit.zw, vec2(0.0001));\n"
                + "  if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);\n"
                + "  return texture2D(uVideoTexSampler0, c);\n"
                + "}\n"
                + "vec4 getToColor(vec2 uv) { vec4 c = texture2D(uOverlayTexSampler0, uv); c.a *= uOverlayAlphaScale0; return c; }\n"
                + "/* TRANSITION_BODY_GOES_HERE */"
                + "void main() {\n"
                + "  vec2 uv = vTexSamplingCoord;\n"
                + "  vec4 fromColor = getFromColor(uv);\n"
                + "  vec4 toColor = getToColor(uv);\n"
                + "  vec4 outColor = transition(uv);\n"
                + "  outColor.a = mix(fromColor.a, toColor.a, clamp(progress, 0.0, 1.0));\n"
                + "  gl_FragColor = outColor;\n"
                + "}\n";
    }

    @NonNull
    private static String previewTemplate() {
        return "#version 100\n"
                + "precision mediump float;\n"
                + "uniform sampler2D uFromTex;\n"
                + "uniform sampler2D uToTex;\n"
                + "uniform float progress;\n"
                + "uniform float ratio;\n"
                + "/* EXTRA_PARAM_UNIFORMS_GO_HERE */"
                + "varying vec2 vTexSamplingCoord;\n"
                // Preview textures upload Android-bitmap top-row-first, so texture V=0 is
                // the image TOP while GLTransitions uv assumes V=0 at the BOTTOM. Flip V
                // at sampling or every frame renders inverted for the transition's
                // duration (and spin-style shaders make it read as mirrored).
                + "vec4 getFromColor(vec2 uv) { return texture2D(uFromTex, vec2(uv.x, 1.0 - uv.y)); }\n"
                + "vec4 getToColor(vec2 uv) { return texture2D(uToTex, vec2(uv.x, 1.0 - uv.y)); }\n"
                + "/* TRANSITION_BODY_GOES_HERE */"
                + "void main() {\n"
                + "  gl_FragColor = transition(vTexSamplingCoord);\n"
                + "}\n";
    }

    /**
     * Template for the LIVE two-decoder preview. Geometry contract (all uv, origin bottom-left
     * as GLTransitions expects; the SurfaceTexture ST matrix handles the buffer's own
     * orientation/flip):
     * <ul>
     *   <li>{@code u*Fit}: xy = offset, zw = size of the leg's fit-centered content rect within
     *       the view — outside it the leg is black (the letterbox bars).</li>
     *   <li>{@code u*Crop}: xy = offset, zw = size of the clip's crop sub-rect in source uv —
     *       content coords map into this window so the blend shows the CROPPED framing
     *       (F12's decode-side crop, done shader-side for live frames).</li>
     *   <li>{@code u*ST}: the SurfaceTexture transform matrix.</li>
     * </ul>
     */
    @NonNull
    private static String livePreviewTemplate() {
        return "#version 100\n"
                + "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "uniform samplerExternalOES uFromTex;\n"
                + "uniform samplerExternalOES uToTex;\n"
                + "uniform mat4 uFromST;\n"
                + "uniform mat4 uToST;\n"
                + "uniform vec4 uFromFit;\n"
                + "uniform vec4 uToFit;\n"
                + "uniform vec4 uFromCrop;\n"
                + "uniform vec4 uToCrop;\n"
                + "uniform float progress;\n"
                + "uniform float ratio;\n"
                + "/* EXTRA_PARAM_UNIFORMS_GO_HERE */"
                + "varying vec2 vTexSamplingCoord;\n"
                + "vec4 getFromColor(vec2 uv) {\n"
                + "  vec2 c = (uv - uFromFit.xy) / max(uFromFit.zw, vec2(0.0001));\n"
                + "  if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);\n"
                + "  vec2 s = uFromCrop.xy + c * uFromCrop.zw;\n"
                + "  return texture2D(uFromTex, (uFromST * vec4(s, 0.0, 1.0)).xy);\n"
                + "}\n"
                + "vec4 getToColor(vec2 uv) {\n"
                + "  vec2 c = (uv - uToFit.xy) / max(uToFit.zw, vec2(0.0001));\n"
                + "  if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);\n"
                + "  vec2 s = uToCrop.xy + c * uToCrop.zw;\n"
                + "  return texture2D(uToTex, (uToST * vec4(s, 0.0, 1.0)).xy);\n"
                + "}\n"
                + "/* TRANSITION_BODY_GOES_HERE */"
                + "void main() {\n"
                + "  gl_FragColor = transition(vTexSamplingCoord);\n"
                + "}\n";
    }

    @NonNull
    private static String join(@NonNull List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }
}
