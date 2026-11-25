package androidx.media3.common.util;

import java.util.HashMap;
import java.util.Map;

public final class GlProgram {
    public static final Map<String, float[]> uniforms = new HashMap<>();
    public static final Map<String, Integer> samplers = new HashMap<>();
    public static int programs;
    public static int deletions;

    public GlProgram(String vertex, String fragment) throws GlUtil.GlException { programs++; }
    public void setBufferAttribute(String name, float[] values, int size) {}
    public void setSamplerTexIdUniform(String name, int texture, int unit) { samplers.put(name, texture); }
    public void setFloatsUniform(String name, float[] values) { uniforms.put(name, values.clone()); }
    public void use() throws GlUtil.GlException {}
    public void bindAttributesAndUniforms() throws GlUtil.GlException {}
    public void delete() throws GlUtil.GlException { deletions++; }
}
