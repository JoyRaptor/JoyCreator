package androidx.media3.common.util;

import android.opengl.GLES20;

import java.util.HashMap;
import java.util.Map;

public final class GlProgram {
    public static final Map<String, float[]> uniforms = new HashMap<>();
    public static final Map<String, Integer> samplers = new HashMap<>();
    public static int programs;
    public static int deletions;
    private final Map<Integer, Integer> textureUnits = new HashMap<>();

    public GlProgram(String vertex, String fragment) throws GlUtil.GlException { programs++; }
    public void setBufferAttribute(String name, float[] values, int size) {}
    public void setSamplerTexIdUniform(String name, int texture, int unit) {
        samplers.put(name, texture);
        textureUnits.put(unit, texture);
    }
    public void setFloatsUniform(String name, float[] values) { uniforms.put(name, values.clone()); }
    public void use() throws GlUtil.GlException {}
    public void bindAttributesAndUniforms() throws GlUtil.GlException {
        for (var sampler : textureUnits.entrySet()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + sampler.getKey());
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sampler.getValue());
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        }
    }
    public void delete() throws GlUtil.GlException { deletions++; }
}
