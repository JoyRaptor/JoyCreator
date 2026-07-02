package com.fadcam.ui.faditor.gltransitions;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Transition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class GlTransitionPreviewView extends GLSurfaceView {

    private final PreviewRenderer renderer;

    public GlTransitionPreviewView(Context context) {
        super(context);
        renderer = new PreviewRenderer(context);
        init();
    }

    public GlTransitionPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        renderer = new PreviewRenderer(context);
        init();
    }

    private void init() {
        setVisibility(GONE);
        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void render(@NonNull Bitmap from, @NonNull Bitmap to,
                       @NonNull Transition transition, float progress) {
        renderer.setFrame(from, to, transition, progress);
        setVisibility(VISIBLE);
        requestRender();
    }

    public void clear() {
        renderer.clear();
        setVisibility(GONE);
        requestRender();
    }

    @Override
    protected void onDetachedFromWindow() {
        renderer.release();
        super.onDetachedFromWindow();
    }

    private static final class PreviewRenderer implements GLSurfaceView.Renderer {

        private final Context context;
        private int program = -1;
        private int fromTex = -1;
        private int toTex = -1;
        private Bitmap fromBitmap;
        private Bitmap toBitmap;
        private Transition transition;
        private float progress;
        private String transitionId;
        private int width = 1;
        private int height = 1;

        PreviewRenderer(Context context) {
            this.context = context.getApplicationContext();
        }

        void setFrame(Bitmap from, Bitmap to, Transition transition, float progress) {
            fromBitmap = from;
            toBitmap = to;
            this.transition = transition;
            this.progress = Math.max(0f, Math.min(1f, progress));
            String id = transition.glTransitionId == null ? "CrossZoom" : transition.glTransitionId;
            if (!id.equals(transitionId)) {
                transitionId = id;
                reloadProgram();
            }
        }

        void clear() {
            fromBitmap = null;
            toBitmap = null;
            transition = null;
        }

        void release() {
            if (fromTex > 0) GLES20.glDeleteTextures(1, new int[]{fromTex}, 0);
            if (toTex > 0) GLES20.glDeleteTextures(1, new int[]{toTex}, 0);
            fromTex = -1;
            toTex = -1;
            if (program > 0) GLES20.glDeleteProgram(program);
            program = -1;
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            reloadProgram();
        }

        @Override
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl,
                                     int width, int height) {
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            GLES20.glViewport(0, 0, this.width, this.height);
        }

        @Override
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program <= 0 || fromBitmap == null || toBitmap == null || transition == null) return;
            try {
                GLES20.glUseProgram(program);
                bindTexture(GLES20.GL_TEXTURE0, fromBitmap, true);
                bindTexture(GLES20.GL_TEXTURE1, toBitmap, false);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFromTex"), 0);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uToTex"), 1);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "progress"), progress);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "ratio"), width / (float) height);
                int position = GLES20.glGetAttribLocation(program, "aFramePosition");
                GLES20.glEnableVertexAttribArray(position);
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8,
                        directBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}));
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(position);
            } catch (Exception ignored) {
            }
        }

        private void reloadProgram() {
            if (program > 0) GLES20.glDeleteProgram(program);
            String id = transitionId == null ? "CrossZoom" : transitionId;
            String shader;
            try {
                shader = GlTransitionShaderLoader.loadWrappedPreviewShader(context, id);
            } catch (Exception e) {
                shader = GlTransitionShaderLoader.fallbackShader(true);
            }
            program = createProgram(vertexShader(), shader);
        }

        private void bindTexture(int unit, Bitmap bitmap, boolean repeat) {
            int[] tex = new int[1];
            GLES20.glActiveTexture(unit);
            if ((unit == GLES20.GL_TEXTURE0 && fromTex <= 0) || (unit == GLES20.GL_TEXTURE1 && toTex <= 0)) {
                GLES20.glGenTextures(1, tex, 0);
                if (unit == GLES20.GL_TEXTURE0) fromTex = tex[0]; else toTex = tex[0];
            } else {
                tex[0] = unit == GLES20.GL_TEXTURE0 ? fromTex : toTex;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    repeat ? GLES20.GL_LINEAR : GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    bitmap.getWidth(), bitmap.getHeight(), 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, bitmapBuffer(bitmap));
        }

        private static String vertexShader() {
            return "#version 100\n"
                    + "attribute vec2 aFramePosition;\n"
                    + "varying vec2 vTexSamplingCoord;\n"
                    + "void main() {\n"
                    + "  vTexSamplingCoord = aFramePosition * 0.5 + 0.5;\n"
                    + "  gl_Position = vec4(aFramePosition, 0.0, 1.0);\n"
                    + "}\n";
        }

        private static int createProgram(String vertex, String fragment) {
            int v = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
            int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            GLES20.glDeleteShader(v);
            GLES20.glDeleteShader(f);
            return p;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        private static ByteBuffer bitmapBuffer(@NonNull Bitmap bitmap) {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            ByteBuffer buffer = ByteBuffer.allocateDirect(pixels.length * 4).order(ByteOrder.nativeOrder());
            for (int pixel : pixels) {
                buffer.put((byte) ((pixel >> 16) & 0xFF));
                buffer.put((byte) ((pixel >> 8) & 0xFF));
                buffer.put((byte) (pixel & 0xFF));
                buffer.put((byte) ((pixel >> 24) & 0xFF));
            }
            buffer.position(0);
            return buffer;
        }

        private static ByteBuffer directBuffer(float[] values) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder());
            buffer.asFloatBuffer().put(values);
            buffer.position(0);
            return buffer;
        }
    }
}
