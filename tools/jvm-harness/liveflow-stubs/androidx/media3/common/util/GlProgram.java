package androidx.media3.common.util;

public final class GlProgram {
    public GlProgram(String vertex, String fragment) throws GlUtil.GlException {}
    public void setBufferAttribute(String name, float[] values, int size) {}
    public void setSamplerTexIdUniform(String name, int texture, int unit) {}
    public void setFloatsUniform(String name, float[] values) {}
    public void use() throws GlUtil.GlException {}
    public void bindAttributesAndUniforms() throws GlUtil.GlException {}
    public void delete() throws GlUtil.GlException {}
}
