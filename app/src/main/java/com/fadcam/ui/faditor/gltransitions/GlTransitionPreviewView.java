package com.fadcam.ui.faditor.gltransitions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.ui.faditor.model.Transition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public class GlTransitionPreviewView extends GLSurfaceView {

    /** Receives the two producer surfaces once the GL thread has created the OES pipeline. */
    public interface LiveSurfacesCallback {
        void onLiveSurfacesReady(@NonNull Surface fromSurface, @NonNull Surface toSurface);
    }

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

    /**
     * Upgrade the running blend to LIVE two-decoder rendering. Must be called AFTER the first
     * {@link #render} of the window (the view must be VISIBLE so the GL surface exists — the
     * OES pipeline is created lazily on the render thread's next frame). The callback fires on
     * the main thread with the two producer surfaces; until BOTH producers deliver a frame,
     * drawing continues from the static endpoint bitmaps, so a producer that never attaches
     * degrades to exactly the old freeze-frame blend.
     */
    public void startLive(@NonNull LiveSurfacesCallback callback) {
        renderer.requestLive(callback, this);
        requestRender();
    }

    /**
     * Per-leg geometry for the live shader — see {@code GlTransitionShaderLoader}'s live
     * template doc. Each array is {fitOffX, fitOffY, fitW, fitH, cropOffX, cropOffY, cropW,
     * cropH} in bottom-left-origin uv.
     */
    public void setLiveGeometry(@NonNull float[] fromGeometry, @NonNull float[] toGeometry) {
        renderer.setLiveGeometry(fromGeometry, toGeometry);
    }

    /** Whether live frames are actually being composited (both producers delivered). */
    public boolean isLiveShowing() {
        return renderer.isLiveShowing();
    }

    public void clear() {
        renderer.clear();
        // Tear down the OES pipeline on the GL thread (context still alive while attached).
        // Safe to queue even when live was never started — it no-ops.
        queueEvent(renderer::releaseLiveObjects);
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

        // ── Live (two-decoder OES) state ─────────────────────────────────
        // Created lazily on the GL thread (guaranteed context) on the first frame after
        // requestLive; the producer Surfaces are posted back to the main thread. Torn down
        // in releaseLiveObjects. All GL-object fields are touched ONLY on the GL thread
        // (except final release at view detach, when the render thread is gone).
        private volatile LiveSurfacesCallback pendingLiveCallback;
        private volatile GlTransitionPreviewView liveHostView;
        private boolean liveObjectsCreated;
        private int liveFromTex = -1;
        private int liveToTex = -1;
        private SurfaceTexture liveFromSt;
        private SurfaceTexture liveToSt;
        private Surface liveFromSurface;
        private Surface liveToSurface;
        private final AtomicBoolean liveFromPending = new AtomicBoolean(false);
        private final AtomicBoolean liveToPending = new AtomicBoolean(false);
        /** Set on the GL thread after the first successful updateTexImage per leg. */
        private volatile boolean liveFromSeen;
        private volatile boolean liveToSeen;
        private final float[] liveFromMatrix = new float[16];
        private final float[] liveToMatrix = new float[16];
        private int liveProgram = -1;
        private String liveProgramId;
        /** {fitOffX, fitOffY, fitW, fitH, cropOffX, cropOffY, cropW, cropH} per leg. */
        private volatile float[] liveFromGeometry;
        private volatile float[] liveToGeometry;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        PreviewRenderer(Context context) {
            this.context = context.getApplicationContext();
        }

        void requestLive(LiveSurfacesCallback callback, GlTransitionPreviewView host) {
            pendingLiveCallback = callback;
            liveHostView = host;
        }

        void setLiveGeometry(float[] fromGeometry, float[] toGeometry) {
            liveFromGeometry = fromGeometry;
            liveToGeometry = toGeometry;
        }

        boolean isLiveShowing() {
            return liveFromSeen && liveToSeen;
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
            lastFromUploaded = null;
            lastToUploaded = null;
        }

        /**
         * Tear down the live OES pipeline. Runs on the GL thread via queueEvent in normal
         * operation; also called from {@link #release()} at view detach (render thread gone —
         * the GL deletes are moot then, but the SurfaceTexture/Surface releases still matter).
         */
        void releaseLiveObjects() {
            pendingLiveCallback = null;
            liveFromSeen = false;
            liveToSeen = false;
            liveFromPending.set(false);
            liveToPending.set(false);
            if (liveFromSurface != null) { try { liveFromSurface.release(); } catch (Exception ignored) {} liveFromSurface = null; }
            if (liveToSurface != null) { try { liveToSurface.release(); } catch (Exception ignored) {} liveToSurface = null; }
            if (liveFromSt != null) { try { liveFromSt.release(); } catch (Exception ignored) {} liveFromSt = null; }
            if (liveToSt != null) { try { liveToSt.release(); } catch (Exception ignored) {} liveToSt = null; }
            if (liveFromTex > 0) GLES20.glDeleteTextures(1, new int[]{liveFromTex}, 0);
            if (liveToTex > 0) GLES20.glDeleteTextures(1, new int[]{liveToTex}, 0);
            liveFromTex = -1;
            liveToTex = -1;
            if (liveProgram > 0) GLES20.glDeleteProgram(liveProgram);
            liveProgram = -1;
            liveProgramId = null;
            liveObjectsCreated = false;
            liveFromGeometry = null;
            liveToGeometry = null;
        }

        void release() {
            if (fromTex > 0) GLES20.glDeleteTextures(1, new int[]{fromTex}, 0);
            if (toTex > 0) GLES20.glDeleteTextures(1, new int[]{toTex}, 0);
            fromTex = -1;
            toTex = -1;
            lastFromUploaded = null;
            lastToUploaded = null;
            if (program > 0) GLES20.glDeleteProgram(program);
            program = -1;
            releaseLiveObjects();
        }

        @Override
        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                     javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            // Fresh EGL context: old texture ids are dead. Reset so bindTexture re-creates
            // and re-uploads (the upload-on-change cache would otherwise skip the upload
            // and sample stale/garbage textures after a surface recreation).
            fromTex = -1;
            toTex = -1;
            lastFromUploaded = null;
            lastToUploaded = null;
            reloadProgram();
            // A live session's SurfaceTextures were attached to the DEAD context — the
            // producers now hold surfaces that can't reach us. Drop the pipeline; drawing
            // falls back to the static endpoint bitmaps (the invariant: degrade, never black).
            if (liveObjectsCreated) {
                liveFromTex = -1; // ids died with the context; skip the glDelete calls
                liveToTex = -1;
                liveProgram = -1;
                releaseLiveObjects();
            }
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
            // Live pipeline setup + frame latch happen HERE (GL thread, context guaranteed).
            LiveSurfacesCallback cb = pendingLiveCallback;
            if (cb != null && !liveObjectsCreated) {
                createLiveObjects(cb);
            }
            if (liveObjectsCreated) {
                try {
                    if (liveFromPending.getAndSet(false) && liveFromSt != null) {
                        liveFromSt.updateTexImage();
                        liveFromSt.getTransformMatrix(liveFromMatrix);
                        liveFromSeen = true;
                    }
                    if (liveToPending.getAndSet(false) && liveToSt != null) {
                        liveToSt.updateTexImage();
                        liveToSt.getTransformMatrix(liveToMatrix);
                        liveToSeen = true;
                    }
                } catch (Exception ignored) {
                    // A released/abandoned SurfaceTexture mid-teardown — keep last state.
                }
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (transition == null) return;
            // Live draw once BOTH legs delivered a frame; static endpoint bitmaps until then
            // (and forever, if live never attaches — the freeze-frame tier is the floor).
            if (liveFromSeen && liveToSeen && drawLiveFrame()) {
                return;
            }
            if (program <= 0 || fromBitmap == null || toBitmap == null) return;
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

        /** GL thread. Build the OES textures + SurfaceTextures and hand the surfaces to main. */
        private void createLiveObjects(LiveSurfacesCallback cb) {
            pendingLiveCallback = null;
            try {
                int[] tex = new int[2];
                GLES20.glGenTextures(2, tex, 0);
                liveFromTex = tex[0];
                liveToTex = tex[1];
                for (int t : tex) {
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                }
                liveFromSt = new SurfaceTexture(liveFromTex);
                liveToSt = new SurfaceTexture(liveToTex);
                final GlTransitionPreviewView host = liveHostView;
                // Listeners on the main handler: mark the leg dirty and schedule a frame.
                liveFromSt.setOnFrameAvailableListener(st -> {
                    liveFromPending.set(true);
                    if (host != null) host.requestRender();
                }, mainHandler);
                liveToSt.setOnFrameAvailableListener(st -> {
                    liveToPending.set(true);
                    if (host != null) host.requestRender();
                }, mainHandler);
                liveFromSurface = new Surface(liveFromSt);
                liveToSurface = new Surface(liveToSt);
                reloadLiveProgram();
                liveObjectsCreated = true;
                final Surface fromSurface = liveFromSurface;
                final Surface toSurface = liveToSurface;
                mainHandler.post(() -> cb.onLiveSurfacesReady(fromSurface, toSurface));
            } catch (Exception e) {
                // No live this window — the static blend carries it.
                releaseLiveObjects();
            }
        }

        private void reloadLiveProgram() {
            if (liveProgram > 0) GLES20.glDeleteProgram(liveProgram);
            String id = transitionId == null ? "CrossZoom" : transitionId;
            String shader;
            try {
                shader = GlTransitionShaderLoader.loadWrappedLivePreviewShader(context, id);
            } catch (Exception e) {
                shader = GlTransitionShaderLoader.fallbackLiveShader();
            }
            liveProgram = createProgram(vertexShader(), shader);
            liveProgramId = id;
        }

        /** GL thread. Returns false when the live program isn't usable (caller falls back). */
        private boolean drawLiveFrame() {
            if (!liveObjectsCreated) return false;
            String id = transitionId == null ? "CrossZoom" : transitionId;
            if (liveProgram <= 0 || !id.equals(liveProgramId)) {
                reloadLiveProgram();
            }
            if (liveProgram <= 0) return false;
            try {
                GLES20.glUseProgram(liveProgram);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, liveFromTex);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, liveToTex);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(liveProgram, "uFromTex"), 0);
                GLES20.glUniform1i(GLES20.glGetUniformLocation(liveProgram, "uToTex"), 1);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(liveProgram, "uFromST"), 1, false, liveFromMatrix, 0);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(liveProgram, "uToST"), 1, false, liveToMatrix, 0);
                float[] fromGeom = liveFromGeometry;
                float[] toGeom = liveToGeometry;
                setLegUniforms(liveProgram, "uFromFit", "uFromCrop", fromGeom);
                setLegUniforms(liveProgram, "uToFit", "uToCrop", toGeom);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(liveProgram, "progress"), progress);
                GLES20.glUniform1f(GLES20.glGetUniformLocation(liveProgram, "ratio"), width / (float) height);
                int position = GLES20.glGetAttribLocation(liveProgram, "aFramePosition");
                GLES20.glEnableVertexAttribArray(position);
                GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8,
                        directBuffer(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}));
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glDisableVertexAttribArray(position);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        private static void setLegUniforms(int program, String fitName, String cropName,
                                           @Nullable float[] geom) {
            float fitOffX = 0f, fitOffY = 0f, fitW = 1f, fitH = 1f;
            float cropOffX = 0f, cropOffY = 0f, cropW = 1f, cropH = 1f;
            if (geom != null && geom.length >= 8) {
                fitOffX = geom[0]; fitOffY = geom[1]; fitW = geom[2]; fitH = geom[3];
                cropOffX = geom[4]; cropOffY = geom[5]; cropW = geom[6]; cropH = geom[7];
            }
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, fitName), fitOffX, fitOffY, fitW, fitH);
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, cropName), cropOffX, cropOffY, cropW, cropH);
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

        // Last bitmap uploaded to each texture unit — with static endpoint frames the
        // upload happens ONCE per transition instead of every draw. (The old path
        // re-uploaded BOTH textures per frame through a per-pixel Java loop — ~100ms+
        // per draw, which is why the blend crawled even when frames were available.)
        private Bitmap lastFromUploaded;
        private Bitmap lastToUploaded;

        private void bindTexture(int unit, Bitmap bitmap, boolean isFrom) {
            int[] tex = new int[1];
            GLES20.glActiveTexture(unit);
            boolean created = false;
            if ((unit == GLES20.GL_TEXTURE0 && fromTex <= 0) || (unit == GLES20.GL_TEXTURE1 && toTex <= 0)) {
                GLES20.glGenTextures(1, tex, 0);
                if (unit == GLES20.GL_TEXTURE0) fromTex = tex[0]; else toTex = tex[0];
                created = true;
            } else {
                tex[0] = unit == GLES20.GL_TEXTURE0 ? fromTex : toTex;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            Bitmap lastUploaded = isFrom ? lastFromUploaded : lastToUploaded;
            if (created || lastUploaded != bitmap) {
                if (bitmap.isRecycled()) return; // frame cache recycled it — keep last texture
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                // Native upload (GLUtils) — same top-down RGBA order as the old manual buffer.
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
                if (isFrom) lastFromUploaded = bitmap; else lastToUploaded = bitmap;
            }
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

        private static ByteBuffer directBuffer(float[] values) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder());
            buffer.asFloatBuffer().put(values);
            buffer.position(0);
            return buffer;
        }
    }
}
