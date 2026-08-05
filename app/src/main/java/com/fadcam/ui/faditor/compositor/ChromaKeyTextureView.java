package com.fadcam.ui.faditor.compositor;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.ChromaKey;
import com.fadcam.ui.faditor.model.CompositingSpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * §3a — the LIVE chroma-key tier: a {@link TextureView} that takes decoder frames on an
 * external-OES surface, runs the shared key shader over them, and presents the keyed result
 * with real per-pixel alpha so the master video shows through.
 *
 * <p><b>Why a whole GL path rather than a filter.</b> Three cheaper routes were checked first
 * and all three are closed on this project's floor (minSdk 24, sandbox device on API 29):
 * {@code RenderEffect} is API 31+ and AGSL {@code RuntimeShader} API 33+, which is why
 * {@code FaditorEditorActivity.applyPreviewColorGrade} previews colour only on new phones;
 * media3's {@code setVideoEffects} is recorded in that same method's javadoc as not rendering
 * in this preview path; and a per-frame {@code getBitmap()} + CPU loop cannot hold frame rate
 * at 1080p. A key is a per-pixel ALPHA decision from a distance test, which no
 * {@code ColorMatrix} can express at any API level. So GL it is — the same tier
 * {@code GlTransitionPreviewView} already proved on this device for two-decoder live blends.</p>
 *
 * <p><b>It shares the export's shader source, it does not copy it.</b> The fragment program is
 * assembled from {@link ChromaKey#GLSL_KEY_FN} — the identical string
 * {@code BlendModeGlEffect} compiles — so preview and export cannot disagree about what the
 * key does. That was the whole reason the key stayed export-only: a tolerance slider tuned
 * against a preview that keys DIFFERENTLY is worse than one tuned against no preview at all,
 * because it looks trustworthy.</p>
 *
 * <p><b>Cost is opt-in.</b> Nothing constructs this view unless {@link ChromaKey#isActive} is
 * true for the clip on screen. A project that never touched the key runs the plain
 * {@code TextureView} path byte-for-byte as before — the same discipline the mask feather
 * took ("feather 0 takes the identical clipPath path").</p>
 *
 * <p><b>Output is PREMULTIPLIED.</b> Android composites {@code TextureView} content as
 * premultiplied alpha; emitting straight colour makes keyed edges glow. The shader premuls on
 * the way out, which is also why the key math runs on straight colour first — see
 * {@link ChromaKey}'s note on the premultiplication trap.</p>
 */
public class ChromaKeyTextureView extends TextureView
        implements TextureView.SurfaceTextureListener, SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "ChromaKeyTexture";

    private static final String VERTEX_SHADER =
            "#version 100\n"
            + "attribute vec4 aPosition;\n"
            + "attribute vec4 aTexCoord;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "varying vec2 vTexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = aPosition;\n"
            + "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
            + "}\n";

    /**
     * Straight-colour key, then premultiply. The OES sample is already opaque straight colour
     * (a decoder never emits alpha), so alpha starts at 1 and the key is the only thing that
     * can lower it.
     */
    private static final String FRAGMENT_SHADER =
            "#version 100\n"
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 vTexCoord;\n"
            + "uniform samplerExternalOES uTexture;\n"
            + "uniform vec3 uKeyColor;\n"
            + "uniform vec4 uKeyParams;\n"
            + ChromaKey.GLSL_KEY_FN
            + "void main() {\n"
            + "  vec3 c = texture2D(uTexture, vTexCoord).rgb;\n"
            + "  float a = fadKeyAlpha(c, 1.0, uKeyColor, uKeyParams);\n"
            + "  gl_FragColor = vec4(c * a, a);\n"   // premultiplied for the view compositor
            + "}\n";

    /** Full-screen triangle strip in clip space, and its matching texture coords. */
    private static final float[] QUAD = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 0f, 1f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 0f, 1f,
    };
    private static final float[] UV = {
            0f, 0f, 0f, 1f,
            1f, 0f, 0f, 1f,
            0f, 1f, 0f, 1f,
            1f, 1f, 0f, 1f,
    };

    public interface SurfaceListener {
        /** Fires on the MAIN thread once a decoder-facing surface exists. */
        void onKeyedInputSurfaceReady(@NonNull Surface surface);
        /** Fires on the MAIN thread when the surface goes away and the player must let go. */
        void onKeyedInputSurfaceLost();
    }

    @Nullable private SurfaceListener surfaceListener;
    @Nullable private HandlerThread glThread;
    @Nullable private Handler glHandler;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());

    // GL state — touched ONLY on glHandler.
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program, texId, aPosition, aTexCoord, uTexMatrix, uTexture, uKeyColor, uKeyParams;
    @Nullable private SurfaceTexture inputTexture;
    @Nullable private Surface inputSurface;
    private final float[] texMatrix = new float[16];
    private FloatBuffer quadBuf, uvBuf;
    private int surfaceW, surfaceH;

    /**
     * Key uniforms, written on the main thread and read on the GL thread. Volatile rather than
     * locked: a slider drag writes these every few milliseconds and a frame that renders with
     * the previous value is invisible, whereas taking a lock on the render thread is not.
     */
    private volatile float[] keyColor = {0f, 1f, 0f};
    private volatile float[] keyParams = {0f, 0f, 0f, 0f};

    public ChromaKeyTextureView(Context context) {
        super(context);
        init();
    }

    public ChromaKeyTextureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Without this the view is treated as opaque and the keyed-out alpha is discarded —
        // the subject would key correctly and still sit on a black card.
        setOpaque(false);
        setSurfaceTextureListener(this);
        quadBuf = toBuffer(QUAD);
        uvBuf = toBuffer(UV);
    }

    public void setSurfaceListener(@Nullable SurfaceListener l) {
        this.surfaceListener = l;
    }

    /** Push the authored key onto the render thread. Safe from the main thread at any time. */
    public void setSpec(@Nullable CompositingSpec spec) {
        keyColor = ChromaKey.packColor(spec);
        keyParams = ChromaKey.packParams(spec);
        requestFrame();
    }

    /** Redraw with the current uniforms even if no new decoder frame has arrived (paused scrub). */
    public void requestFrame() {
        Handler h = glHandler;
        if (h != null) h.post(this::drawFrame);
    }

    /** Delivers a sampled 0xRRGGBB on the MAIN thread, or null if the frame could not be read. */
    public interface ColorSink { void onColorSampled(@Nullable Integer rgb); }

    /**
     * Sample the RAW, UN-KEYED colour at normalised {@code (u, v)} of the video — the
     * eyedropper.
     *
     * <p><b>Why it must be the unkeyed frame.</b> The user points at the green they want gone.
     * If the sample came from what is on screen, then the moment the key is even slightly
     * working that pixel is already transparent, and the dropper would return the colour
     * BEHIND it — so each tap would key something further from the green than the last, and
     * the tool would drift away from the answer the harder you tried. Sampling upstream of the
     * key makes the dropper idempotent: tapping the same pixel twice gives the same colour.</p>
     *
     * <p>Read from the BACK buffer before the presented draw, so nothing unkeyed ever reaches
     * the screen — no FBO, and no flicker.</p>
     */
    public void sampleRawColor(float u, float v, @NonNull ColorSink sink) {
        Handler h = glHandler;
        if (h == null) { main.post(() -> sink.onColorSampled(null)); return; }
        h.post(() -> {
            Integer result = null;
            try {
                // surfaceW/H are 0 until the first size callback. Without this guard the pixel
                // coords below go NEGATIVE (u * (0-1)), glReadPixels quietly fails, and the
                // untouched buffer reads as pure black — so the dropper would "succeed" and
                // key out black. A wrong colour is far worse than a reported failure.
                if (eglSurface != EGL14.EGL_NO_SURFACE && inputTexture != null
                        && surfaceW > 0 && surfaceH > 0) {
                    // Draw the frame with the key OFF into the back buffer, read one pixel,
                    // then draw it again keyed. Only the second draw is swapped/presented.
                    drawInternal(new float[]{0f, 0f, 0f, 0f}, false);
                    int px = Math.round(Math.max(0f, Math.min(1f, u)) * (surfaceW - 1));
                    // GL's origin is bottom-left; the caller thinks in top-down view coords.
                    int py = Math.round((1f - Math.max(0f, Math.min(1f, v))) * (surfaceH - 1));
                    ByteBuffer buf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
                    GLES20.glReadPixels(px, py, 1, 1, GLES20.GL_RGBA,
                            GLES20.GL_UNSIGNED_BYTE, buf);
                    buf.position(0);
                    int r = buf.get() & 0xFF, g = buf.get() & 0xFF, b = buf.get() & 0xFF;
                    result = (r << 16) | (g << 8) | b;
                    // Restore WITHOUT advancing: the sample and the picture presented back to
                    // the user must be the same frame, or on a moving subject the dropper
                    // reports a colour from a frame that was never on screen when it was tapped.
                    drawInternal(keyParams, false);
                    EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                }
            } catch (Exception e) {
                com.fadcam.FLog.w(TAG, "eyedropper sample failed: " + e.getMessage());
            }
            final Integer out = result;
            main.post(() -> sink.onColorSampled(out));
        });
    }

    // ── TextureView.SurfaceTextureListener (main thread) ─────────────────────────────────

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        surfaceW = w;
        surfaceH = h;
        HandlerThread t = new HandlerThread("chroma-key-gl");
        t.start();
        glThread = t;
        glHandler = new Handler(t.getLooper());
        glHandler.post(() -> setupGl(st));
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
        surfaceW = w;
        surfaceH = h;
        requestFrame();
    }

    /**
     * Returning true hands the {@link SurfaceTexture} back to the framework to release — so the
     * EGL window surface built on it MUST already be gone. {@link #releaseGl} tears down on the
     * GL thread, which is asynchronous, so this waits for that to finish. Without the wait the
     * framework can release the SurfaceTexture while EGL still holds it, which is a
     * use-after-free in the driver rather than an exception — it presents as a hard crash on
     * rotation or on leaving the editor, at a stack that names neither this class nor GL.
     */
    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        HandlerThread t = glThread;
        releaseGl();
        if (t != null) {
            try {
                // Bounded: a wedged GL thread must not hang the UI thread outright. If the
                // join times out we return FALSE and keep ownership, which leaks one
                // SurfaceTexture rather than crashing the process.
                t.join(500);
                if (t.isAlive()) {
                    com.fadcam.FLog.w(TAG, "GL thread did not stop; keeping the SurfaceTexture");
                    return false;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) { /* we drive our own draws */ }

    // ── GL thread ────────────────────────────────────────────────────────────────────────

    private void setupGl(@NonNull SurfaceTexture output) {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);
            // EGL_ALPHA_SIZE 8 is the load-bearing attribute: without a destination alpha
            // channel the keyed alpha has nowhere to live and the view composites opaque.
            int[] cfgAttr = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] numCfg = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfgAttr, 0, cfgs, 0, 1, numCfg, 0)
                    || numCfg[0] == 0) {
                throw new RuntimeException("no RGBA8888 ES2 EGL config");
            }
            eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], output,
                    new int[]{EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

            program = buildProgram();
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uTexture = GLES20.glGetUniformLocation(program, "uTexture");
            uKeyColor = GLES20.glGetUniformLocation(program, "uKeyColor");
            uKeyParams = GLES20.glGetUniformLocation(program, "uKeyParams");

            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            texId = ids[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            inputTexture = new SurfaceTexture(texId);
            inputTexture.setOnFrameAvailableListener(this);
            inputSurface = new Surface(inputTexture);

            final Surface ready = inputSurface;
            main.post(() -> {
                SurfaceListener l = surfaceListener;
                if (l != null) l.onKeyedInputSurfaceReady(ready);
            });
        } catch (Exception e) {
            com.fadcam.FLog.e(TAG, "GL setup failed; the key tier is unavailable", e);
            releaseGl();
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        requestFrame();
    }

    private void drawFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE || inputTexture == null) return;
        try {
            drawInternal(keyParams, true);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        } catch (Exception e) {
            com.fadcam.FLog.w(TAG, "keyed draw failed: " + e.getMessage());
        }
    }

    /**
     * One draw into the back buffer. {@code params} is the key packing to use — the eyedropper
     * passes an all-zero (disabled) packing to get the raw frame. {@code advance} consumes a
     * new decoder frame; the eyedropper's second pass must NOT, or it would skip a frame and
     * the sample would describe a picture the user never saw.
     */
    private void drawInternal(@NonNull float[] params, boolean advance) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            if (advance) {
                inputTexture.updateTexImage();
                inputTexture.getTransformMatrix(texMatrix);
            }

            GLES20.glViewport(0, 0, surfaceW, surfaceH);
            // Transparent clear, not black: every pixel the key removes must let the master
            // video through, and anything the quad does not cover is outside the PiP.
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            quadBuf.position(0);
            GLES20.glVertexAttribPointer(aPosition, 4, GLES20.GL_FLOAT, false, 0, quadBuf);
            GLES20.glEnableVertexAttribArray(aPosition);
            uvBuf.position(0);
            GLES20.glVertexAttribPointer(aTexCoord, 4, GLES20.GL_FLOAT, false, 0, uvBuf);
            GLES20.glEnableVertexAttribArray(aTexCoord);

            GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);
            float[] col = keyColor;
            GLES20.glUniform3f(uKeyColor, col[0], col[1], col[2]);
            GLES20.glUniform4f(uKeyParams, params[0], params[1], params[2], params[3]);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glUniform1i(uTexture, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private int buildProgram() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("key program link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private int compile(int type, @NonNull String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            // Named loudly: a silent shader-compile failure would look exactly like "the key
            // does nothing", which is the bug this whole tier exists to make impossible.
            throw new RuntimeException("key shader compile failed: " + log);
        }
        return s;
    }

    /**
     * Tear down on the GL thread, then join. Ordering matters: the player must be told the
     * surface is gone BEFORE it is released, or media3 writes into a dead Surface.
     */
    private void releaseGl() {
        Handler h = glHandler;
        HandlerThread t = glThread;
        glHandler = null;
        glThread = null;
        main.post(() -> {
            SurfaceListener l = surfaceListener;
            if (l != null) l.onKeyedInputSurfaceLost();
        });
        if (h != null) {
            h.post(() -> {
                if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
                if (inputTexture != null) { inputTexture.release(); inputTexture = null; }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                    }
                    EGL14.eglTerminate(eglDisplay);
                }
                eglSurface = EGL14.EGL_NO_SURFACE;
                eglContext = EGL14.EGL_NO_CONTEXT;
                eglDisplay = EGL14.EGL_NO_DISPLAY;
                if (t != null) t.quitSafely();
            });
        } else if (t != null) {
            t.quitSafely();
        }
    }

    @NonNull
    private static FloatBuffer toBuffer(@NonNull float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data);
        b.position(0);
        return b;
    }
}
